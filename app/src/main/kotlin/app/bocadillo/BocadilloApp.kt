package app.bocadillo

import android.app.Application
import app.bocadillo.core.imaging.ops.OpRegistry
import app.bocadillo.core.imaging.objects.StickerCatalog
import app.bocadillo.data.ExportRepository
import app.bocadillo.data.FontRepository
import app.bocadillo.data.NotoStickers
import app.bocadillo.data.PhraseRepository
import app.bocadillo.data.ProjectRepository
import app.bocadillo.data.StickerLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual dependency container (small app, no DI framework needed). */
class BocadilloApp : Application() {
    lateinit var projects: ProjectRepository
        private set
    lateinit var exports: ExportRepository
        private set
    lateinit var fonts: FontRepository
        private set
    lateinit var phrases: PhraseRepository
        private set
    lateinit var stickerLibrary: StickerLibrary
        private set
    lateinit var noto: NotoStickers
        private set
    val registry: OpRegistry by lazy { OpRegistry.default() }

    /** Built-in vector stickers (assets/stickers/catalog.json). */
    val stickers: StickerCatalog by lazy {
        StickerCatalog.parse(assets.open("stickers/catalog.json").bufferedReader().use { it.readText() })
    }

    /** Outlives screens: exports keep running if the user leaves the editor. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Report of the last crash (written by the uncaught-exception handler), shown once on next start. */
    val crashFile: java.io.File get() = java.io.File(filesDir, "last_crash.txt")

    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
        projects = ProjectRepository(this)
        exports = ExportRepository(this)
        fonts = FontRepository(this)
        phrases = PhraseRepository(this)
        stickerLibrary = StickerLibrary(this)
        noto = NotoStickers(this)
    }

    /** Saves the stack trace locally (never sent anywhere) so the user can share it. */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val info = packageManager.getPackageInfo(packageName, 0)
                val sw = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(sw))
                crashFile.writeText(
                    "Bocadillo ${info.versionName} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nThread: ${thread.name}\n\n$sw",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
