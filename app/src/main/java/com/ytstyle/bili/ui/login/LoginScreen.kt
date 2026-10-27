package com.ytstyle.bili.ui.login

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * Sign-in with four methods, laid out the way the official clients and
 * PiliPlus do: 密码 / 短信 / 扫码 / Cookie.
 *
 * QR is the default and is the most reliable path, because it involves no
 * captcha at all. The password and SMS flows still need a Geetest challenge,
 * and when that challenge cannot be presented the screen says so and points at
 * the two methods that do not depend on it.
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onBack: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onLoggedIn()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppTheme.colors.onBackground,
                )
            }
            Text(
                text = "登录",
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.onBackground,
            )
        }

        MethodTabs(selected = state.method, onSelect = viewModel::onMethodChange)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(20.dp))

            when (state.method) {
                LoginMethod.Qr -> QrPanel(state = state, onRefresh = viewModel::startQr)

                LoginMethod.Cookie -> CookiePanel(
                    state = state,
                    viewModel = viewModel,
                    onSubmit = {
                        keyboard?.hide()
                        viewModel.submitCookie()
                    },
                )

                LoginMethod.Password -> PasswordFields(
                    state = state,
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                    viewModel = viewModel,
                    onSubmit = {
                        keyboard?.hide()
                        viewModel.startPasswordLogin()
                    },
                )

                LoginMethod.Sms -> SmsFields(
                    state = state,
                    viewModel = viewModel,
                    onSubmit = {
                        keyboard?.hide()
                        if (state.stage == LoginStage.AwaitingSmsCode) {
                            viewModel.submitSmsCode()
                        } else {
                            viewModel.startSmsSend()
                        }
                    },
                )
            }

            if (state.stage == LoginStage.Captcha) {
                val gt = state.geetest
                if (gt != null) {
                    Spacer(Modifier.height(16.dp))
                    CaptchaSection(
                        gt = gt.gt,
                        challenge = gt.challenge,
                        onSolved = viewModel::onCaptchaSolved,
                        onError = viewModel::onCaptchaFailed,
                        onCancel = viewModel::cancelCaptcha,
                    )
                }
            }

            // Only the credential methods submit from a button.
            if (state.method == LoginMethod.Password || state.method == LoginMethod.Sms) {
                Spacer(Modifier.height(20.dp))
                if (state.stage != LoginStage.Captcha) {
                    SubmitButton(
                        enabled = state.canSubmit,
                        busy = state.stage == LoginStage.Submitting,
                        label = primaryActionLabel(state),
                        onClick = {
                            keyboard?.hide()
                            when {
                                state.method == LoginMethod.Sms &&
                                    state.stage == LoginStage.AwaitingSmsCode ->
                                    viewModel.submitSmsCode()

                                state.method == LoginMethod.Sms -> viewModel.startSmsSend()
                                else -> viewModel.startPasswordLogin()
                            }
                        },
                    )
                }
            }

            if (state.method == LoginMethod.Cookie) {
                Spacer(Modifier.height(16.dp))
                SubmitButton(
                    enabled = state.canSubmit,
                    busy = state.stage == LoginStage.Submitting,
                    label = "使用 Cookie 登录",
                    onClick = {
                        keyboard?.hide()
                        viewModel.submitCookie()
                    },
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "忘记密码",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.forgotPasswordUrl)),
                                )
                            }
                        }
                        .padding(8.dp),
                )
            }

            state.error?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    color = YtColors.Red,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }

            state.oneTimeMessage?.let { message ->
                LaunchedEffect(message) { viewModel.dismissMessage() }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

private fun primaryActionLabel(state: LoginUiState): String = when {
    state.stage == LoginStage.Submitting -> "处理中…"
    state.method == LoginMethod.Sms && state.stage == LoginStage.AwaitingSmsCode -> "登录"
    state.method == LoginMethod.Sms -> "获取验证码"
    else -> "登录"
}

@Composable
private fun MethodTabs(selected: LoginMethod, onSelect: (LoginMethod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        LoginMethod.entries.forEach { method ->
            val active = method == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(method) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = iconFor(method),
                    contentDescription = method.label,
                    tint = if (active) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = method.label,
                    color = if (active) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.divider),
    )
}

private fun iconFor(method: LoginMethod): ImageVector = when (method) {
    LoginMethod.Password -> Icons.Filled.Password
    LoginMethod.Sms -> Icons.Filled.Sms
    LoginMethod.Qr -> Icons.Filled.QrCode2
    LoginMethod.Cookie -> Icons.Filled.Key
}

/**
 * QR panel. The image is generated locally from the URL the API returns, so no
 * QR image is fetched from Bilibili.
 */
@Composable
private fun QrPanel(state: LoginUiState, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "使用 bilibili 官方 App 扫码登录",
            color = AppTheme.colors.onBackground,
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = when (state.qrStatus) {
                QrStatus.Loading, QrStatus.Idle -> "正在获取二维码…"
                QrStatus.Waiting -> "二维码尚未确认"
                QrStatus.Scanned -> "已扫码，请在手机上确认"
                QrStatus.Expired -> "二维码已失效"
            },
            color = if (state.qrStatus == QrStatus.Scanned) {
                YtColors.Red
            } else {
                AppTheme.colors.onBackgroundSecondary
            },
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.qrUrl.isEmpty()) {
                CircularProgressIndicator(color = YtColors.Red)
            } else {
                QrImage(content = state.qrUrl, modifier = Modifier.fillMaxSize())
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(AppTheme.colors.chipBackground)
                .clickable(onClick = onRefresh)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = AppTheme.colors.onBackground,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "刷新二维码",
                color = AppTheme.colors.onBackground,
                fontSize = 13.sp,
            )
        }
    }
}

/** Cookie paste panel, as a last-resort path that needs no challenge. */
@Composable
private fun CookiePanel(
    state: LoginUiState,
    viewModel: LoginViewModel,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "从浏览器粘贴 Cookie",
            color = AppTheme.colors.onBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "电脑浏览器登录 bilibili.com 后，从开发者工具的 Network 里复制请求头中的 " +
                "Cookie 值。需要包含 SESSDATA（缺少 bili_jct 时无法点赞、投币、发评论）。",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )

        Spacer(Modifier.height(12.dp))

        TextField(
            value = state.manualCookie,
            onValueChange = viewModel::onCookieChange,
            modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(10.dp)),
            placeholder = {
                Text(
                    "SESSDATA=...; bili_jct=...; DedeUserID=...",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 12.sp,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AppTheme.colors.chipBackground,
                unfocusedContainerColor = AppTheme.colors.chipBackground,
                focusedTextColor = AppTheme.colors.onBackground,
                unfocusedTextColor = AppTheme.colors.onBackground,
                cursorColor = YtColors.Red,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Cookie 等同于账号密码，只保存在本机 DataStore 中，不会上传到任何第三方。",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun PasswordFields(
    state: LoginUiState,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    viewModel: LoginViewModel,
    onSubmit: () -> Unit,
) {
    FieldLabel("账号")
    Spacer(Modifier.height(8.dp))
    LoginField(
        value = state.username,
        onValueChange = viewModel::onUsernameChange,
        placeholder = "手机号 / 邮箱 / 用户名",
        enabled = state.stage != LoginStage.Submitting,
        imeAction = ImeAction.Next,
    )

    Spacer(Modifier.height(20.dp))

    FieldLabel("密码")
    Spacer(Modifier.height(8.dp))
    LoginField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        placeholder = "请输入密码",
        enabled = state.stage != LoginStage.Submitting,
        imeAction = ImeAction.Done,
        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailing = {
            IconButton(onClick = onTogglePasswordVisible) {
                Icon(
                    imageVector = if (passwordVisible) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                    tint = AppTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        onDone = onSubmit,
    )

    Spacer(Modifier.height(10.dp))
    Text(
        text = "密码仅用于本次 RSA 加密提交，不会被保存。该方式需要完成安全验证。",
        color = AppTheme.colors.onBackgroundSecondary,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}

@Composable
private fun SmsFields(
    state: LoginUiState,
    viewModel: LoginViewModel,
    onSubmit: () -> Unit,
) {
    val awaiting = state.stage == LoginStage.AwaitingSmsCode

    FieldLabel("手机号")
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "+${state.countryCode}",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 10.dp),
        )
        LoginField(
            value = state.tel,
            onValueChange = viewModel::onTelChange,
            placeholder = "请输入手机号",
            enabled = !awaiting && state.stage != LoginStage.Submitting,
            imeAction = ImeAction.Next,
            keyboardType = KeyboardType.Phone,
        )
    }

    if (awaiting) {
        Spacer(Modifier.height(20.dp))
        FieldLabel("短信验证码")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoginField(
                value = state.smsCode,
                onValueChange = viewModel::onSmsCodeChange,
                placeholder = "请输入收到的验证码",
                enabled = state.stage != LoginStage.Submitting,
                imeAction = ImeAction.Done,
                keyboardType = KeyboardType.NumberPassword,
                onDone = onSubmit,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (state.resendCountdown > 0) {
                    "${state.resendCountdown}s"
                } else {
                    "重新发送"
                },
                color = if (state.resendCountdown > 0) {
                    AppTheme.colors.onBackgroundSecondary
                } else {
                    YtColors.Red
                },
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(enabled = state.resendCountdown == 0) { viewModel.startSmsSend() }
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun CaptchaSection(
    gt: String,
    challenge: String,
    onSolved: (String, String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surfaceElevated)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "请完成安全验证",
                color = AppTheme.colors.onBackground,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "取消",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onCancel).padding(4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        GeetestCaptcha(
            gt = gt,
            challenge = challenge,
            onSuccess = onSolved,
            onError = onError,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "加载不出来时，可改用扫码或 Cookie 登录。",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text = text, color = AppTheme.colors.onBackgroundSecondary, fontSize = 12.sp)
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    imeAction: ImeAction,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
        enabled = enabled,
        placeholder = {
            Text(placeholder, color = AppTheme.colors.onBackgroundSecondary, fontSize = 14.sp)
        },
        trailingIcon = trailing,
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AppTheme.colors.chipBackground,
            unfocusedContainerColor = AppTheme.colors.chipBackground,
            disabledContainerColor = AppTheme.colors.chipBackground,
            focusedTextColor = AppTheme.colors.onBackground,
            unfocusedTextColor = AppTheme.colors.onBackground,
            disabledTextColor = AppTheme.colors.onBackgroundSecondary,
            cursorColor = YtColors.Red,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/** Renders [content] as a QR bitmap locally; no image is fetched from Bilibili. */
@Composable
private fun QrImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { content.takeIf { it.isNotEmpty() }?.let(::generateQr) }

    if (bitmap == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("生成中...", color = Color.Gray, fontSize = 12.sp)
        }
        return
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "登录二维码",
        modifier = modifier,
    )
}

private fun generateQr(content: String, size: Int = 512): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}.getOrNull()

@Composable
private fun SubmitButton(enabled: Boolean, busy: Boolean, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (enabled) YtColors.Red else AppTheme.colors.chipBackground)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(label, color = Color.White, fontWeight = FontWeight.Medium)
            }
        } else {
            Text(
                text = label,
                color = if (enabled) Color.White else AppTheme.colors.onBackgroundSecondary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
