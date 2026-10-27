package com.ytstyle.bili.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Supplies the session cookie for the embedded browser.
 *
 * The browser screens are the only place a WebView sees the session, and it is
 * injected from the same store the API client uses so the two cannot drift.
 */
@HiltViewModel
class InAppBrowserViewModel @Inject constructor(
    session: SessionStore,
) : ViewModel() {

    /** Login cookie plus the device fingerprint, as one header value. */
    val cookie: StateFlow<String> = session.cookieFlow
        .map { login ->
            listOf(login, session.cachedBuvid)
                .filter { it.isNotEmpty() }
                .joinToString("; ")
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
}
