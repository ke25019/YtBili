package com.ytstyle.bili.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.auth.PasswordCrypto
import com.ytstyle.bili.data.net.Geetest
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The four ways to sign in, mirroring the official clients and PiliPlus.
 *
 * QR and Cookie are deliberately first-class rather than fallbacks: they need
 * no captcha at all, so they keep working when the Geetest challenge cannot be
 * presented.
 */
enum class LoginMethod(val label: String) {
    Password("密码"),
    Sms("短信"),
    Qr("扫码"),
    Cookie("Cookie"),
}

/** Progress of the QR handshake. */
enum class QrStatus { Idle, Loading, Waiting, Scanned, Expired }

/** Where the credential flow currently is. */
enum class LoginStage {
    Credentials,
    Captcha,
    AwaitingSmsCode,
    Submitting,
    Done,
}

/** What to do once the captcha is solved. */
private enum class CaptchaPurpose { PasswordLogin, SendSms }

data class LoginUiState(
    val method: LoginMethod = LoginMethod.Qr,

    // password
    val username: String = "",
    val password: String = "",

    // sms
    val tel: String = "",
    val smsCode: String = "",
    val countryCode: Int = 86,
    val resendCountdown: Int = 0,

    // qr
    val qrUrl: String = "",
    val qrStatus: QrStatus = QrStatus.Idle,

    // cookie
    val manualCookie: String = "",

    val stage: LoginStage = LoginStage.Credentials,
    val geetest: Geetest? = null,
    val captchaToken: String = "",
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val oneTimeMessage: String? = null,
) {
    val canSubmit: Boolean
        get() = when (method) {
            LoginMethod.Password ->
                username.isNotBlank() && password.isNotBlank() && stage != LoginStage.Submitting

            LoginMethod.Sms -> when (stage) {
                LoginStage.Credentials -> tel.length >= 6
                LoginStage.AwaitingSmsCode -> smsCode.length >= 4
                else -> false
            }

            LoginMethod.Qr -> false

            LoginMethod.Cookie ->
                manualCookie.contains("SESSDATA=") && stage != LoginStage.Submitting
        }
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var captchaPurpose = CaptchaPurpose.PasswordLogin
    private var captchaValidate: String = ""
    private var captchaSeccode: String = ""
    private var qrJob: Job? = null

    /** Bilibili's reset page; opened in the browser as it is a multi-step flow. */
    val forgotPasswordUrl = "https://passport.bilibili.com/resetpwd"

    init {
        // QR is the default because it needs no captcha and cannot be defeated
        // by a broken challenge.
        startQr()
    }

    override fun onCleared() {
        qrJob?.cancel()
        super.onCleared()
    }

    fun onMethodChange(method: LoginMethod) {
        if (method == _state.value.method) return
        qrJob?.cancel()
        _state.update {
            it.copy(
                method = method,
                stage = LoginStage.Credentials,
                geetest = null,
                error = null,
                qrStatus = QrStatus.Idle,
            )
        }
        if (method == LoginMethod.Qr) startQr()
    }

    fun onUsernameChange(value: String) =
        _state.update { it.copy(username = value.trim(), error = null) }

    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, error = null) }

    fun onTelChange(value: String) =
        _state.update { it.copy(tel = value.filter { c -> c.isDigit() }, error = null) }

    fun onSmsCodeChange(value: String) =
        _state.update { it.copy(smsCode = value.filter { c -> c.isDigit() }, error = null) }

    fun onCookieChange(value: String) =
        _state.update { it.copy(manualCookie = value.trim(), error = null) }

    // ---- QR ----

    /**
     * Requests a QR code and polls until it is scanned or expires.
     *
     * On success Bilibili sets the session cookies on the *poll response*, so
     * they are drained from the response interceptor rather than the body.
     */
    fun startQr() {
        qrJob?.cancel()
        _state.update {
            it.copy(qrStatus = QrStatus.Loading, qrUrl = "", error = null)
        }

        qrJob = viewModelScope.launch {
            when (val generated = repository.qrGenerate()) {
                is Result.Err -> _state.update {
                    it.copy(qrStatus = QrStatus.Expired, error = generated.message)
                }

                is Result.Ok -> {
                    val (url, key) = generated.value
                    _state.update { it.copy(qrUrl = url, qrStatus = QrStatus.Waiting) }

                    var attempts = 0
                    while (isActive && attempts < QR_MAX_POLLS) {
                        attempts++
                        delay(QR_POLL_INTERVAL_MS)

                        val poll = repository.qrPoll(key)
                        if (poll is Result.Err) {
                            _state.update {
                                it.copy(qrStatus = QrStatus.Expired, error = poll.message)
                            }
                            return@launch
                        }

                        when ((poll as Result.Ok).value.code) {
                            0 -> {
                                val cookie = repository.captureCookiesFromLastResponse()
                                if (cookie.isNullOrEmpty()) {
                                    _state.update {
                                        it.copy(
                                            qrStatus = QrStatus.Expired,
                                            error = "扫码成功但未取得会话 Cookie，请刷新重试",
                                        )
                                    }
                                    return@launch
                                }
                                session.save(cookie)
                                confirmSession()
                                return@launch
                            }
                            86090 -> _state.update { it.copy(qrStatus = QrStatus.Scanned) }
                            86038 -> {
                                _state.update { it.copy(qrStatus = QrStatus.Expired) }
                                return@launch
                            }
                            else -> Unit
                        }
                    }
                    _state.update { it.copy(qrStatus = QrStatus.Expired) }
                }
            }
        }
    }

    // ---- Cookie ----

    /**
     * Accepts a cookie header copied from a browser.
     *
     * `SESSDATA` is the only strictly required field; `bili_jct` is needed for
     * write actions, so it is reported when missing instead of failing later on
     * the first like or comment.
     */
    fun submitCookie() {
        val raw = _state.value.manualCookie
        if (!raw.contains("SESSDATA=")) {
            _state.update { it.copy(error = "Cookie 中缺少 SESSDATA") }
            return
        }
        _state.update { it.copy(stage = LoginStage.Submitting, error = null) }

        viewModelScope.launch {
            session.save(raw)
            when (val nav = repository.nav()) {
                is Result.Ok -> {
                    if (!nav.value.isLogin) {
                        session.clear()
                        _state.update {
                            it.copy(stage = LoginStage.Credentials, error = "Cookie 无效或已过期")
                        }
                        return@launch
                    }
                    session.saveProfile(nav.value.uname, nav.value.face)
                    val missingCsrf = !raw.contains("bili_jct=")
                    _state.update {
                        it.copy(
                            stage = LoginStage.Done,
                            isLoggedIn = true,
                            manualCookie = "",
                            oneTimeMessage = if (missingCsrf) {
                                "已登录，但 Cookie 缺少 bili_jct，点赞/投币等操作会失败"
                            } else {
                                null
                            },
                        )
                    }
                }
                is Result.Err -> _state.update {
                    it.copy(stage = LoginStage.Credentials, error = nav.message)
                }
            }
        }
    }

    // ---- password / sms ----

    fun startPasswordLogin() {
        if (!_state.value.canSubmit) return
        captchaPurpose = CaptchaPurpose.PasswordLogin
        _state.update { it.copy(stage = LoginStage.Submitting, error = null) }
        fetchCaptchaThenContinue()
    }

    fun startSmsSend() {
        if (_state.value.tel.length < 6) {
            _state.update { it.copy(error = "请输入手机号") }
            return
        }
        captchaPurpose = CaptchaPurpose.SendSms
        _state.update { it.copy(stage = LoginStage.Submitting, error = null) }
        fetchCaptchaThenContinue()
    }

    fun submitSmsCode() {
        val current = _state.value
        if (current.smsCode.length < 4) return
        _state.update { it.copy(stage = LoginStage.Submitting, error = null) }

        viewModelScope.launch {
            when (
                val result = repository.smsLogin(
                    tel = current.tel,
                    cid = current.countryCode,
                    code = current.smsCode,
                    token = current.captchaToken,
                    challenge = current.geetest?.challenge.orEmpty(),
                    validate = captchaValidate,
                    seccode = captchaSeccode,
                )
            ) {
                is Result.Err -> failWith(result.message)
                is Result.Ok -> handleLoginStatus(
                    result.value.status,
                    result.value.message,
                    result.value.url,
                )
            }
        }
    }

    private fun fetchCaptchaThenContinue() {
        viewModelScope.launch {
            when (val result = repository.loginCaptcha()) {
                is Result.Ok -> {
                    val captcha = result.value
                    val geetest = captcha.geetest
                    if (geetest == null || geetest.gt.isEmpty() || geetest.challenge.isEmpty()) {
                        captchaValidate = ""
                        captchaSeccode = ""
                        _state.update { it.copy(captchaToken = captcha.token) }
                        continueAfterCaptcha(captcha.token)
                    } else {
                        _state.update {
                            it.copy(
                                stage = LoginStage.Captcha,
                                geetest = geetest,
                                captchaToken = captcha.token,
                            )
                        }
                    }
                }
                is Result.Err -> {
                    // The captcha endpoint itself failing is the common reason
                    // password login is unusable; point at the paths that do
                    // not need a challenge.
                    failWith("${result.message}（可改用扫码或 Cookie 登录）")
                }
            }
        }
    }

    fun onCaptchaSolved(validate: String, seccode: String) {
        captchaValidate = validate
        captchaSeccode = seccode
        _state.update { it.copy(stage = LoginStage.Submitting) }
        continueAfterCaptcha(_state.value.captchaToken)
    }

    /** Called when the WebView reports that the challenge could not load. */
    fun onCaptchaFailed(message: String) {
        failWith("验证码加载失败：$message（可改用扫码或 Cookie 登录）")
    }

    private fun continueAfterCaptcha(token: String) {
        when (captchaPurpose) {
            CaptchaPurpose.PasswordLogin -> performPasswordLogin(token)
            CaptchaPurpose.SendSms -> performSmsSend(token)
        }
    }

    fun cancelCaptcha() {
        captchaValidate = ""
        captchaSeccode = ""
        _state.update {
            it.copy(
                stage = LoginStage.Credentials,
                geetest = null,
                captchaToken = "",
                oneTimeMessage = "已取消验证码",
            )
        }
    }

    private fun performSmsSend(token: String) {
        val current = _state.value
        viewModelScope.launch {
            when (
                val result = repository.smsSend(
                    tel = current.tel,
                    cid = current.countryCode,
                    token = token,
                    challenge = current.geetest?.challenge.orEmpty(),
                    validate = captchaValidate,
                    seccode = captchaSeccode,
                )
            ) {
                is Result.Ok -> {
                    _state.update {
                        it.copy(
                            stage = LoginStage.AwaitingSmsCode,
                            oneTimeMessage = "验证码已发送，请查收短信",
                            resendCountdown = RESEND_SECONDS,
                        )
                    }
                    startResendCountdown()
                }
                is Result.Err -> failWith(result.message)
            }
        }
    }

    private fun startResendCountdown() {
        viewModelScope.launch {
            while (_state.value.resendCountdown > 0) {
                delay(1000)
                _state.update { it.copy(resendCountdown = (it.resendCountdown - 1).coerceAtLeast(0)) }
            }
        }
    }

    private fun performPasswordLogin(token: String) {
        val current = _state.value
        viewModelScope.launch {
            val keyResult = repository.loginKey()
            if (keyResult is Result.Err) {
                failWith(keyResult.message)
                return@launch
            }
            val loginKey = (keyResult as Result.Ok).value

            val encrypted = try {
                PasswordCrypto.encrypt(loginKey.key, loginKey.hash, current.password)
            } catch (e: Exception) {
                failWith("密码加密失败：${e.message ?: "公钥无效"}")
                return@launch
            }

            when (
                val result = repository.passwordLogin(
                    username = current.username,
                    encryptedPassword = encrypted,
                    token = token,
                    challenge = current.geetest?.challenge.orEmpty(),
                    validate = captchaValidate,
                    seccode = captchaSeccode,
                )
            ) {
                is Result.Err -> failWith(result.message)
                is Result.Ok -> handleLoginStatus(
                    result.value.status,
                    result.value.message,
                    result.value.url,
                )
            }
        }
    }

    private suspend fun handleLoginStatus(status: Int, message: String, url: String) {
        when (status) {
            0 -> {
                val cookie = repository.captureCookiesFromLastResponse()
                if (cookie.isNullOrEmpty()) {
                    failWith("登录成功但未取得会话 Cookie，请重试")
                    return
                }
                session.save(cookie)
                confirmSession()
            }

            2 -> _state.update {
                it.copy(
                    stage = LoginStage.Credentials,
                    error = "该账号需要额外验证（通常是短信或设备确认），本客户端无法完成此步骤。" +
                        if (url.isNotEmpty()) " 验证地址：$url" else "",
                )
            }

            else -> failWith(message.ifBlank { "登录失败（status=$status）" })
        }
    }

    /**
     * Confirms the freshly stored session.
     *
     * The session cookies are the thing that actually logs the user in, and they
     * are already saved by this point. A failure to *read* the profile is
     * therefore a warning, not a login failure — treating it as fatal is what
     * turned a cosmetic parsing problem (`nav.vip_label` changing shape) into
     * "登录失败" while the account was in fact signed in.
     */
    private suspend fun confirmSession() {
        when (val nav = repository.nav()) {
            is Result.Ok -> {
                session.saveProfile(nav.value.uname, nav.value.face)
                _state.update {
                    it.copy(
                        stage = LoginStage.Done,
                        isLoggedIn = true,
                        // Drop credentials immediately.
                        password = "",
                        smsCode = "",
                    )
                }
            }
            is Result.Err -> _state.update {
                it.copy(
                    stage = LoginStage.Done,
                    isLoggedIn = true,
                    password = "",
                    smsCode = "",
                    oneTimeMessage = "已登录，但读取账号信息失败：${nav.message}",
                )
            }
        }
    }

    private fun failWith(message: String) {
        _state.update {
            it.copy(
                stage = if (it.method == LoginMethod.Sms && it.smsCode.isNotEmpty()) {
                    LoginStage.AwaitingSmsCode
                } else {
                    LoginStage.Credentials
                },
                error = message,
            )
        }
    }

    fun dismissMessage() = _state.update { it.copy(oneTimeMessage = null) }

    private companion object {
        const val RESEND_SECONDS = 60
        const val QR_POLL_INTERVAL_MS = 2_000L
        const val QR_MAX_POLLS = 90
    }
}
