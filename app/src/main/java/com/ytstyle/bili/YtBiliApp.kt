package com.ytstyle.bili

import android.app.Application
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.session.SessionStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class YtBiliApp : Application() {

    @Inject
    lateinit var session: SessionStore

    @Inject
    lateinit var repository: BiliRepository

    override fun onCreate() {
        super.onCreate()
        // The OkHttp interceptor reads the cached cookies synchronously, so the
        // persisted session must be in memory before the first request fires.
        // buvid is then fetched once so early requests carry a fingerprint.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            session.prime()
            repository.ensureBuvid()
        }
    }
}
