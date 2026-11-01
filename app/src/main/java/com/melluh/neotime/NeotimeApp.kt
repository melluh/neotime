package com.melluh.neotime

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.melluh.neotime.auth.OsirisSession
import com.melluh.neotime.auth.SessionManager
import com.melluh.neotime.auth.SessionState
import com.melluh.neotime.auth.SessionStore
import com.melluh.neotime.auth.WebViewCookieJar
import com.melluh.neotime.data.LecturerRepository
import com.melluh.neotime.data.MapRepository
import com.melluh.neotime.data.RegistrationRepository
import com.melluh.neotime.data.RoomFocus
import com.melluh.neotime.data.RoomRepository
import com.melluh.neotime.data.ScheduleRepository
import com.melluh.neotime.timeedit.MazeMapClient
import com.melluh.neotime.timeedit.OsirisClient
import com.melluh.neotime.timeedit.PeopleDirectory
import com.melluh.neotime.timeedit.TimeEditClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.CookieJar
import okhttp3.OkHttpClient

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

    /** The people pages and their image CDN are public; login cookies must not reach them. */
    val anonymousHttp = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

    val client = TimeEditClient(http)
    val session = SessionManager(app, client, SessionStore(app.session))
    val schedule = ScheduleRepository(client, session, app.settings, File(app.filesDir, "schedule.json"))
    val lecturers = LecturerRepository(PeopleDirectory(anonymousHttp), File(app.filesDir, "lecturers.json"))
    val rooms = RoomRepository(client, session, schedule, app.settings)
    val maps = MapRepository(MazeMapClient(anonymousHttp), app.settings, File(app.cacheDir, "mazemap"), readOverrides = {
        app.assets.open("map-overrides.json").bufferedReader().use { it.readText() }
    })
    val focus = RoomFocus(maps)

    private val osirisClient = OsirisClient(http)
    val registrations = RegistrationRepository(osirisClient, OsirisSession(app, osirisClient), session, schedule, app.settings)

    init {
        scope.launch {
            // Every launch starts out LoggedOut until restore(), so only a real change is a logout
            var wasLoggedIn = false
            session.state.collect { state ->
                val loggedIn = state !is SessionState.LoggedOut
                if (wasLoggedIn && !loggedIn) registrations.resetConsent()
                wasLoggedIn = loggedIn
            }
        }
    }
}

class NeotimeApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch { container.session.restore() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.anonymousHttp })) }
        .build()
}
