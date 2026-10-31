package com.melluh.neotime

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.melluh.neotime.auth.SessionManager
import com.melluh.neotime.auth.SessionStore
import com.melluh.neotime.auth.WebViewCookieJar
import com.melluh.neotime.data.ScheduleRepository
import com.melluh.neotime.timeedit.TimeEditClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

private val Context.settings by preferencesDataStore(name = "settings")
private val Context.session by preferencesDataStore(name = "session")

/** Manual dependency wiring; one instance per process. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob())

    private val http = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val client = TimeEditClient(http)
    val session = SessionManager(app, client, SessionStore(app.session))
    val schedule = ScheduleRepository(client, session, app.settings, File(app.filesDir, "schedule.json"))
}

class NeotimeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch { container.session.restore() }
    }
}
