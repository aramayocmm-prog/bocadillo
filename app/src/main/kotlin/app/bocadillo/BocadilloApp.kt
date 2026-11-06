package app.bocadillo

import android.app.Application
import app.bocadillo.core.imaging.ops.OpRegistry
import app.bocadillo.core.imaging.objects.StickerCatalog
import app.bocadillo.data.ExportRepository
import app.bocadillo.data.FontRepository
import app.bocadillo.data.PhraseRepository
import app.bocadillo.data.ProjectRepository
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
    val registry: OpRegistry by lazy { OpRegistry.default() }

    /** Built-in vector stickers (assets/stickers/catalog.json). */
    val stickers: StickerCatalog by lazy {
        StickerCatalog.parse(assets.open("stickers/catalog.json").bufferedReader().use { it.readText() })
    }

    /** Outlives screens: exports keep running if the user leaves the editor. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        projects = ProjectRepository(this)
        exports = ExportRepository(this)
        fonts = FontRepository(this)
        phrases = PhraseRepository(this)
    }
}
