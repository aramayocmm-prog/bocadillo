package app.bocadillo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bocadillo.core.imaging.objects.Cutout
import app.bocadillo.core.imaging.preview.SampleScene
import app.bocadillo.core.model.ProjectBundle
import app.bocadillo.core.model.StickerObject
import app.bocadillo.core.model.TextObject
import app.bocadillo.data.FontOption
import app.bocadillo.data.ImageIo
import app.bocadillo.editor.EditorViewModel
import app.bocadillo.editor.InsertStage
import app.bocadillo.editor.ToolState
import app.bocadillo.render.processPicture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MAC Editor 1.1.0 corrections on an emulator (not a phone):
 *  1. live stroke: latency per finger move and fidelity (slow, fast, closed curve, zig-zag, long,
 *     small and large brushes, large picture);
 *  2. handle tap: size ↔ perspective without changing the picture; drag still transforms;
 *  3. Cut Out: the erased pixel is the one under the finger (zoom, rotation, perspective), live =
 *     export, latency;
 *  4. finishing: green check, Back, Guardar imagen (PNG / JPG, found in the gallery), Guardar
 *     proyecto, Salir sin guardar, drafts;
 *  5. fonts: catalogue grouped by family, search, pick, persistence; personal library (only on the
 *     personal test APK);
 *  6. home thumbnail about twice the size, with the photo's proportions.
 */
@RunWith(AndroidJUnit4::class)
class Mac11Test {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = rule.activity
    private val app get() = ctx.application as BocadilloApp
    private fun s(id: Int) = ctx.getString(id)
    private val shots by lazy { File(ctx.filesDir, "screens").apply { mkdirs() } }

    private fun shot(name: String, settle: Long = 1200) {
        rule.waitForIdle(); Thread.sleep(settle); rule.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun screenshot(): Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
    private fun tap(text: String) { rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun desc(d: String) { rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun hasDesc(d: String) = rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String, ms: Long = 30_000) { rule.waitUntil(ms) { has(text) } }
    private fun waitDesc(d: String, ms: Long = 30_000) { rule.waitUntil(ms) { hasDesc(d) } }
    private fun boundsOf(d: String) = rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
    private fun vm() = EditorViewModel.lastInstance!!
    private fun ui(f: () -> Unit) = rule.runOnUiThread(f)
    /** Logged and kept in files/screens/mac11_log.txt (published with the captures). */
    private fun log(msg: String) { android.util.Log.i("Mac11", msg); runCatching { File(shots, "mac11_log.txt").appendText(msg + "\n") } }
    private fun backKey() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); rule.waitForIdle(); Thread.sleep(400) }
    private fun backGesture() { ui { ctx.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle(); Thread.sleep(400) }
    private fun insertTool() = vm().state.value.tool as ToolState.InsertPicture

    private fun dismissRecovery() { if (has(s(R.string.home_recover_title))) { tap(s(R.string.cancel)); Thread.sleep(300) } }

    /** A project made from a picture: a draft unless [saved]. */
    private fun newProject(title: String, w: Int = 1200, h: Int = 900, saved: Boolean = true): String {
        dismissRecovery()
        val sample = SampleScene.render(w, h)
        val png = ByteArrayOutputStream().also { ImageIo.toBitmap(sample).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val doc = runBlocking { app.projects.create(ImageIo.Imported(png, "image/png", title), w, h, false, title, ImageIo.thumbnail(sample)) }
        if (saved) app.projects.clearDraft(doc.id)
        rule.waitForIdle()
        return doc.id
    }
    private fun openProject(title: String) {
        dismissRecovery()
        if (!has(title)) { desc(s(R.string.home_more)); tap(s(R.string.more_recent_projects)) }
        tap(title); waitFor(s(R.string.menu_adjust)); Thread.sleep(2500)
    }
    /** Out of the editor (home main page, More… or the recent-projects list). */
    private fun atHome() = !has(s(R.string.menu_adjust)) && !has(s(R.string.finish_title))

    private fun stats(xs: List<Float>): String {
        if (xs.isEmpty()) return "n=0"
        val v = xs.sorted()
        fun pct(p: Double) = v[((v.size - 1) * p).roundToInt()]
        return "n=%d median=%.2f ms p95=%.2f ms max=%.2f ms".format(v.size, pct(0.5), pct(0.95), v.last())
    }

    // ------------------------------------------------------------------ 1. live stroke

    /** Touch path in canvas fractions → strokes drawn with real touch input, finger held before lifting. */
    private fun drawWithFinger(area: androidx.compose.ui.geometry.Rect, path: List<Pair<Float, Float>>, delayMs: Long, beforeUp: () -> Unit) {
        fun at(p: Pair<Float, Float>) = Offset(area.left + p.first * area.width, area.top + p.second * area.height)
        rule.onRoot().performTouchInput { down(at(path.first())) }
        for (p in path.drop(1)) { rule.onRoot().performTouchInput { moveTo(at(p)) }; if (delayMs > 0) Thread.sleep(delayMs) }
        rule.waitForIdle(); Thread.sleep(300)
        beforeUp()
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
    }

    @Test fun liveStrokeLatencyAndFidelity() {
        // a large picture (4000×3000: preview 2048×1536) — the case that slowed 1.0 down
        newProject("Trazo", 4000, 3000)
        openProject("Trazo")
        tap(s(R.string.menu_effect)); tap(s(R.string.paint)); Thread.sleep(800)
        // the picture's own box (strokes start inside the picture)
        val area = boundsOf(s(R.string.draw_area))
        val cases = listOf(
            // name, brush size, hardness, opacity, colour, path, delay between moves
            Triple("lento_fino", floatArrayOf(0.008f, 1f, 1f), (0..120).map { i -> 0.1f + i * 0.0065f to 0.30f + 0.05f * kotlin.math.sin(i * 0.08f) }) to 16L,
            Triple("rapido_grueso", floatArrayOf(0.12f, 0.9f, 1f), (0..8).map { i -> 0.08f + i * 0.105f to if (i % 2 == 0) 0.35f else 0.65f }) to 0L,
            Triple("curva_cerrada", floatArrayOf(0.03f, 0.3f, 0.8f), (0..70).map { i -> 0.5f + 0.22f * kotlin.math.cos(i * 0.09f) to 0.5f + 0.3f * kotlin.math.sin(i * 0.09f) }) to 8L,
            Triple("zigzag", floatArrayOf(0.02f, 0.6f, 1f), (0..24).map { i -> 0.1f + i * 0.033f to if (i % 2 == 0) 0.62f else 0.88f }) to 8L,
            Triple("largo", floatArrayOf(0.05f, 0.5f, 0.7f), (0..300).map { i -> 0.5f + 0.4f * kotlin.math.sin(i * 0.031f) to 0.5f + 0.4f * kotlin.math.sin(i * 0.047f + 1f) }) to 4L,
        )
        val colors = intArrayOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt(), 0xFF8E24AA.toInt())
        var allTimes = ArrayList<Float>()
        var maxDiff = 0; var diffPx = 0L; var total = 0L
        val rt = Runtime.getRuntime()
        val memBefore = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        cases.forEachIndexed { k, (c, delay) ->
            val (name, b, path) = c
            ui {
                val t = vm().state.value.tool as ToolState.Draw
                vm().setBrush(t.brush.copy(color = colors[k], size = b[0], hardness = b[1], opacity = b[2]))
            }
            ui { vm().liveTimings.clear(); vm().liveBackgroundCount = 0 }
            var live: Pair<Int, IntArray>? = null
            drawWithFinger(area, path, delay) {
                rule.waitUntil(20_000) { var p = true; ui { p = vm().livePending() }; !p } // background work, if any, done
                ui { assertTrue("$name: live tiles while the finger is down", vm().liveTilesShown()); live = vm().liveSnapshot() }
                if (k == 4) shot("100_trazo_dedo_abajo", 200)
            }
            val times = vm().liveTimings.toList(); allTimes.addAll(times)
            log("PERF stroke=$name points=${path.size} ${stats(times)} background=${vm().liveBackgroundCount}")
            // the rendered picture replaces the live tiles; compare pixel by pixel
            rule.waitUntil(30_000) { var gone = false; ui { gone = !vm().liveTilesShown() }; gone }
            val fin = vm().state.value.preview!!.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            val l = live!!
            assertEquals(l.first, fin.width)
            val px = IntArray(fin.width * fin.height).also { fin.getPixels(it, 0, fin.width, 0, 0, fin.width, fin.height) }
            var d = 0; var m = 0
            for (i in px.indices) if (px[i] != l.second[i]) {
                d++
                for (sh in intArrayOf(0, 8, 16, 24)) m = max(m, abs(((px[i] ushr sh) and 0xFF) - ((l.second[i] ushr sh) and 0xFF)))
            }
            log("FIDELITY stroke=$name differing=$d/${px.size} max=$m")
            diffPx += d; total += px.size; maxDiff = max(maxDiff, m)
        }
        shot("101_trazo_final")
        // undo / redo still work
        ui { vm().undoStroke() }; Thread.sleep(1500)
        assertEquals(cases.size - 1, (vm().state.value.tool as ToolState.Draw).strokes.size)
        ui { vm().redoStroke() }; Thread.sleep(1500)
        assertEquals(cases.size, (vm().state.value.tool as ToolState.Draw).strokes.size)
        // long session: 60 more quick strokes
        ui { vm().liveTimings.clear(); vm().liveBackgroundCount = 0 }
        repeat(60) { i ->
            val y = 0.1f + (i % 20) * 0.04f
            drawWithFinger(area, (0..12).map { j -> 0.1f + j * 0.06f to y + (j % 2) * 0.01f }, 0L) {}
        }
        rule.waitUntil(60_000) { var gone = false; ui { gone = !vm().liveTilesShown() }; gone }
        val memAfter = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        log("PERF session=60_strokes ${stats(vm().liveTimings.toList())} heap_before=${memBefore}MB heap_after=${memAfter}MB native=${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576}MB")
        log("PERF all_strokes ${stats(allTimes)}")
        log("FIDELITY total differing=$diffPx/$total max=$maxDiff")
        assertEquals(cases.size + 60, (vm().state.value.tool as ToolState.Draw).strokes.size)
        assertTrue("live and final pictures differ in $diffPx px (max $maxDiff)", diffPx == 0L)
    }

    // ------------------------------------------------------------------ 2. handle tap

    /** A 900×600 picture: cyan field, yellow disc, two pure-blue 18-px markers (for Cut Out). */
    private fun testPicture(): File {
        val b = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.rgb(20, 190, 210))
            drawCircle(450f, 300f, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW })
            val blue = Paint().apply { color = Color.rgb(0, 0, 255) }
            drawRect(279f, 219f, 297f, 237f, blue)   // marker A, centre (288, 228) = (0.32, 0.38)
            drawRect(603f, 363f, 621f, 381f, blue)   // marker B, centre (612, 372) = (0.68, 0.62)
        }
        val f = File(ctx.cacheDir, "insert11.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun toPlace() {
        app.settings.updateInsertGuideOff(true)
        rule.runOnUiThread { vm().insertPicture(Uri.fromFile(testPicture())) }
        waitDesc(s(R.string.ins_crop_frame))
        desc(s(R.string.ins_next_place)); waitFor(s(R.string.ins_place_picture)); Thread.sleep(800)
    }

    private fun tapAt(p: Offset, jitter: Float = 0f) {
        rule.onRoot().performTouchInput { down(p); if (jitter > 0f) moveTo(p + Offset(jitter, -jitter)); up() }
        rule.waitForIdle(); Thread.sleep(400)
    }

    @Test fun handleTapTogglesSizeAndPerspective() {
        newProject("Asa"); openProject("Asa"); toPlace()
        val label = s(R.string.transform_handle)
        val t0 = insertTool().draft.transform
        assertTrue("starts in size mode", !insertTool().perspective)
        shot("110_asa_modo_tamano")
        // tap on the blue circle (bottom right): perspective, picture unchanged
        val h = boundsOf(label); tapAt(h.center)
        assertTrue("tap → perspective", insertTool().perspective)
        assertEquals("tap does not change the picture", t0, insertTool().draft.transform)
        shot("111_asa_modo_perspectiva")
        // tap with a small tremor (under the touch slop) on the bottom-right diamond: back to size
        tapAt(boundsOf("$label 3").center, jitter = 3f)
        assertTrue("tap on the bottom-right diamond → size", !insertTool().perspective)
        assertEquals(t0, insertTool().draft.transform)
        // a tap on another diamond does not switch
        tapAt(boundsOf(label).center); assertTrue(insertTool().perspective)
        tapAt(boundsOf("$label 1").center); assertTrue("top-left diamond does not switch", insertTool().perspective)
        // drag in perspective mode: the corner moves, the mode stays
        val c = boundsOf("$label 3").center
        rule.onRoot().performTouchInput { down(c); for (i in 1..12) moveTo(c + Offset(i * 6f, i * 4f)); up() }
        rule.waitForIdle(); Thread.sleep(500)
        assertTrue("drag = perspective change", insertTool().draft.transform.hasPerspective)
        assertTrue("drag is not a tap", insertTool().perspective)
        shot("112_asa_arrastre_perspectiva")
        // back to size and drag: scale / rotation change, perspective kept
        val p1 = insertTool().draft.transform.perspective
        tapAt(boundsOf("$label 3").center); assertTrue(!insertTool().perspective)
        assertEquals("switching keeps the perspective already made", p1, insertTool().draft.transform.perspective)
        val hc = boundsOf(label).center
        rule.onRoot().performTouchInput { down(hc); for (i in 1..12) moveTo(hc + Offset(i * 8f, i * 2f)); up() }
        rule.waitForIdle(); Thread.sleep(500)
        val t2 = insertTool().draft.transform
        assertTrue("drag in size mode transforms (${t0.scale}→${t2.scale}, ${t0.rotation}→${t2.rotation})", abs(t2.scale - t0.scale) > 0.01f || abs(t2.rotation - t0.rotation) > 0.5f)
        assertTrue("still size mode", !insertTool().perspective)
        shot("113_asa_arrastre_tamano")
        // the five ⋮ entries are still there
        desc(s(R.string.more_options))
        for (e in listOf(R.string.ins_stretch_to_fit, R.string.ins_scale_to_fill, R.string.ins_mirror, R.string.ins_perspective_tool, R.string.ins_show_stickers)) assertTrue(s(e), has(s(e)))
        backKey()
        ui { vm().cancelTool() }
    }

    // ------------------------------------------------------------------ 3. Cut Out precision

    /** Centre (in root coordinates) of the pure-blue pixels inside [area] on the screen, or null. */
    private fun findMarker(area: androidx.compose.ui.geometry.Rect, nearX: Float? = null): Offset? {
        val shotBmp = screenshot()
        val loc = IntArray(2); (ctx.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)).getLocationOnScreen(loc)
        val pts = ArrayList<Pair<Int, Int>>()
        for (y in max(0, (area.top + loc[1]).toInt()) until minOf(shotBmp.height, (area.bottom + loc[1]).toInt())) for (x in max(0, (area.left + loc[0]).toInt()) until minOf(shotBmp.width, (area.right + loc[0]).toInt())) {
            val p = shotBmp.getPixel(x, y)
            if (Color.red(p) < 50 && Color.green(p) < 50 && Color.blue(p) > 200) pts += x to y
        }
        if (pts.isEmpty()) return null
        // two clusters: split by x around the middle and keep the one asked for
        val mid = (pts.minOf { it.first } + pts.maxOf { it.first }) / 2
        val cl = if (nearX == null) pts.filter { it.first <= mid } else pts.filter { (it.first <= mid) == (nearX + loc[0] <= mid) }
        return Offset(cl.map { it.first }.average().toFloat() - loc[0], cl.map { it.second }.average().toFloat() - loc[1])
    }

    @Test fun cutOutUnderTheFinger() {
        newProject("Silueta"); openProject("Silueta"); toPlace()
        val label = s(R.string.transform_handle)
        // rotate / scale with the blue handle, then perspective with a diamond (real touches)
        val hc = boundsOf(label).center
        rule.onRoot().performTouchInput { down(hc); for (i in 1..10) moveTo(hc + Offset(-i * 4f, i * 9f)); up() }
        rule.waitForIdle(); Thread.sleep(400)
        tapAt(boundsOf(label).center) // perspective mode
        val d3 = boundsOf("$label 3").center
        rule.onRoot().performTouchInput { down(d3); for (i in 1..10) moveTo(d3 + Offset(i * 7f, i * 3f)); up() }
        rule.waitForIdle(); Thread.sleep(400)
        val tr = insertTool().draft.transform
        log("CUT transform rotation=${tr.rotation} scale=${tr.scale} perspective=${tr.perspective}")
        assertTrue("rotated", abs(tr.rotation) > 3f); assertTrue("perspective", tr.hasPerspective)
        // Cut Out, eraser with a small round tip, then zoom the canvas ×2 with two fingers
        desc(s(R.string.ins_next_cutout)); waitFor(s(R.string.ins_cut_out)); Thread.sleep(800)
        desc(s(R.string.ins_eraser)); waitFor(s(R.string.brush_hardness))
        desc(s(R.string.ins_tip_round) + " 3% · 100%"); desc(s(R.string.collapse))
        val area = boundsOf(s(R.string.ins_cut_area))
        val ctr = area.center
        rule.onRoot().performTouchInput { pinch(ctr - Offset(60f, 0f), ctr - Offset(220f, 0f), ctr + Offset(60f, 0f), ctr + Offset(220f, 0f), 500) }
        rule.waitForIdle(); Thread.sleep(1200)
        shot("120_silueta_zoom")
        val markerA = findMarker(area) ?: throw AssertionError("marker A not visible")
        val markerB = findMarker(area, nearX = area.right) ?: throw AssertionError("marker B not visible")
        log("CUT marker A on screen $markerA, B $markerB")
        // one short stroke exactly on marker A
        ui { vm().liveTimings.clear(); vm().liveBackgroundCount = 0 }
        rule.onRoot().performTouchInput { down(markerA); moveTo(markerA + Offset(2f, 0f)); moveTo(markerA - Offset(2f, 0f)); up() }
        rule.waitForIdle(); Thread.sleep(800)
        rule.waitUntil(20_000) { var p = true; ui { p = vm().livePending() }; !p }
        shot("121_silueta_borrado_en_el_marcador")
        val snap = vm().cutSnapshot()!!
        val (w, h, px) = snap
        fun alphaAt(u: Float, v: Float) = (px[(v * h).toInt().coerceIn(0, h - 1) * w + (u * w).toInt().coerceIn(0, w - 1)] ushr 24) and 0xFF
        val aA = alphaAt(0.32f, 0.38f); val aB = alphaAt(0.68f, 0.62f)
        log("CUT alpha at marker A=$aA (erased under the finger), B=$aB (untouched) raster=${w}x$h")
        assertTrue("marker A (under the finger) erased: alpha $aA", aA < 40)
        assertTrue("marker B untouched: alpha $aB", aB == 255)
        // a longer eraser and restore stroke for timing, then undo / redo
        val p0 = area.center
        rule.onRoot().performTouchInput { down(p0); for (i in 1..60) moveTo(p0 + Offset(kotlin.math.sin(i * 0.2f) * 150f, i * 3f - 90f)); up() }
        rule.waitForIdle()
        desc(s(R.string.ins_restore_brush)); desc(s(R.string.collapse))
        rule.onRoot().performTouchInput { down(p0); for (i in 1..40) moveTo(p0 + Offset(i * 3f - 60f, kotlin.math.cos(i * 0.3f) * 40f)); up() }
        rule.waitForIdle(); Thread.sleep(600)
        log("PERF cutout ${stats(vm().liveTimings.toList())} background=${vm().liveBackgroundCount}")
        assertEquals(3, Cutout.strokes(insertTool().draft.cutout).size)
        desc(s(R.string.undo)); assertEquals(2, Cutout.strokes(insertTool().draft.cutout).size)
        desc(s(R.string.more_options)); tap(s(R.string.redo)); assertEquals(3, Cutout.strokes(insertTool().draft.cutout).size)
        shot("122_silueta_final", 1500)
        // what is seen is what is exported
        rule.waitUntil(20_000) { vm().cutSnapshot()?.let { it.third.isNotEmpty() } == true }
        Thread.sleep(1500)
        val d = insertTool().draft
        val raw = BitmapFactory.decodeByteArray(vm().assetsSnapshot()[d.source.removePrefix("asset:")]!!, 0, vm().assetsSnapshot()[d.source.removePrefix("asset:")]!!.size)
        val out = processPicture(raw, d.crop, d.cutout)
        rule.waitUntil(20_000) { var p = true; ui { p = vm().livePending() }; !p }
        val (w2, h2, liveRaw) = vm().cutSnapshot()!!
        assertEquals(out.width, w2); assertEquals(out.height, h2)
        val ex = IntArray(w2 * h2).also { out.getPixels(it, 0, w2, 0, 0, w2, h2) }
        // both through an Android Bitmap, as on screen (premultiplied alpha rounds soft edges the same way)
        val live = IntArray(w2 * h2).also { Bitmap.createBitmap(liveRaw, w2, h2, Bitmap.Config.ARGB_8888).getPixels(it, 0, w2, 0, 0, w2, h2) }
        var diff = 0; for (i in ex.indices) if (ex[i] != live[i]) diff++
        log("CUT live vs export differing=$diff/${ex.size}")
        assertEquals("Cut Out on screen = export", 0, diff)
        ui { vm().cancelTool() }
    }

    // ------------------------------------------------------------------ 4. finishing and saving

    private fun galleryHas(name: String): ByteArray? {
        val cr = ctx.contentResolver
        cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH),
            "${MediaStore.Images.Media.DISPLAY_NAME}=?", arrayOf(name), null)?.use { c ->
            if (!c.moveToFirst()) return null
            val path = c.getString(1)
            log("GALLERY $name in $path")
            assertTrue("in Pictures/MAC Editor para MEMES ($path)", path.contains("MAC Editor para MEMES"))
            val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
            return cr.openInputStream(uri)?.use { it.readBytes() }
        }
        return null
    }

    private fun saveImage(name: String, png: Boolean) {
        desc(s(R.string.finish)); waitFor(s(R.string.finish_title))
        tap(s(R.string.finish_save_image)); waitFor(s(R.string.save_image_title))
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput(name)
        tap(s(if (png) R.string.save_image_png else R.string.save_image_jpg))
        shot(if (png) "131_guardar_imagen_png" else "132_guardar_imagen_jpg", 600)
        tap(s(R.string.save_image_save))
        waitFor(s(R.string.image_saved_title), 60_000); shot(if (png) "133_imagen_guardada_png" else "134_imagen_guardada_jpg", 600)
        tap(s(R.string.finish_continue)); Thread.sleep(500)
    }

    @Test fun finishSaveAndBackNavigation() {
        // a draft (picture opened, nothing done): Back leaves and nothing is kept
        val draft = newProject("Borrador", saved = false)
        openProject("Borrador")
        assertTrue(app.projects.isDraft(draft))
        backKey(); rule.waitUntil(10_000) { atHome() }
        Thread.sleep(800)
        assertTrue("unchanged draft deleted", app.projects.index.value.entries.none { it.id == draft })

        // a draft with work: the green check opens the finish dialog
        val id = newProject("Proy11", saved = false)
        openProject("Proy11")
        ui { vm().orient(90, false, false) }; Thread.sleep(1500)
        desc(s(R.string.finish)); waitFor(s(R.string.finish_title)); shot("130_finalizar_dialogo")
        for (o in listOf(R.string.finish_save_image, R.string.finish_save_project, R.string.finish_continue, R.string.finish_exit_without_saving)) assertTrue(s(o), has(s(o)))
        tap(s(R.string.finish_continue)); assertTrue(!has(s(R.string.finish_title)))
        // Guardar imagen: PNG and JPG, found in the gallery with the right format and size
        saveImage("prueba_png_11", png = true)
        val pngBytes = galleryHas("prueba_png_11.png") ?: throw AssertionError("PNG not in the gallery")
        assertTrue("PNG signature", pngBytes[1] == 'P'.code.toByte() && pngBytes[2] == 'N'.code.toByte())
        val pb = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size); assertEquals(900, pb.width); assertEquals(1200, pb.height)
        saveImage("prueba_jpg_11", png = false)
        val jpg = galleryHas("prueba_jpg_11.jpg") ?: throw AssertionError("JPG not in the gallery")
        assertTrue("JPEG signature", (jpg[0].toInt() and 0xFF) == 0xFF && (jpg[1].toInt() and 0xFF) == 0xD8)
        log("SAVE png=${pngBytes.size} B jpg=${jpg.size} B")

        // Back inside a tool cancels the tool and stays in the editor (key and gesture)
        tap(s(R.string.menu_adjust)); tap(s(R.string.op_exposure_contrast)); Thread.sleep(800)
        assertNotNull(vm().state.value.tool)
        backKey()
        assertTrue("Back closed the tool", vm().state.value.tool == null); assertTrue("still in the editor", has(s(R.string.menu_adjust)))
        assertTrue("no finish dialog for a tool", !has(s(R.string.finish_title)))
        tap(s(R.string.menu_adjust)); tap(s(R.string.op_exposure_contrast)); Thread.sleep(800)
        backGesture()
        assertTrue("gesture Back closed the tool", vm().state.value.tool == null); assertTrue(has(s(R.string.menu_adjust)))

        // Back in the editor with changes: the same dialog; Guardar proyecto → home, kept, not a draft
        backGesture(); waitFor(s(R.string.finish_title))
        tap(s(R.string.finish_save_project)); rule.waitUntil(15_000) { atHome() }
        Thread.sleep(1000)
        assertTrue("saved project kept", app.projects.index.value.entries.any { it.id == id })
        assertTrue("no longer a draft", !app.projects.isDraft(id))
        val saved = runBlocking { app.projects.load(id) }.document
        assertEquals(1, saved.ops.size)
        shot("135_inicio_tras_guardar")

        // reopen, change, then Salir sin guardar: the project goes back to the saved state
        openProject("Proy11")
        ui { vm().orient(90, false, false) }; Thread.sleep(1500)
        backKey(); waitFor(s(R.string.finish_title))
        tap(s(R.string.finish_exit_without_saving)); waitFor(s(R.string.discard_title)); shot("136_descartar_confirmacion")
        tap(s(R.string.discard_confirm)); rule.waitUntil(15_000) { atHome() }
        Thread.sleep(1000)
        val after = runBlocking { app.projects.load(id) }.document
        assertEquals("changes discarded, saved state kept", saved.ops, after.ops)

        // opening it again and leaving without changes: no dialog
        openProject("Proy11")
        backKey(); rule.waitUntil(10_000) { atHome() }
        assertTrue(app.projects.index.value.entries.any { it.id == id })
    }

    // ------------------------------------------------------------------ 5. fonts

    @Test fun fontCatalogueSearchPickPersist() {
        val id = newProject("Catalogo"); openProject("Catalogo")
        tap(s(R.string.menu_sticker)); tap(s(R.string.title_sticker)); waitFor(s(R.string.enter_text_here))
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Texto MAC")
        rule.runOnUiThread { ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ctx.window.decorView.windowToken, 0) }
        Thread.sleep(800)
        desc(s(R.string.title_preview_hint)); waitFor(s(R.string.alignment))
        val t0 = System.nanoTime()
        tap(s(R.string.tab_font)); waitFor(s(R.string.fonts_custom_files))
        log("FONTS open list ${(System.nanoTime() - t0) / 1_000_000} ms, fonts=${app.fonts.all().size} library=${app.fonts.libraryFonts.size}")
        shot("140_fuentes_lista")
        // scroll to the end (System Fonts) and back: time it
        val t1 = System.nanoTime()
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(s(R.string.fonts_system_header), substring = true))
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(s(R.string.fonts_custom_files)))
        log("FONTS scroll whole list ${(System.nanoTime() - t1) / 1_000_000} ms")
        // search, open the Creato Display family? (bundled fonts are single rows) — pick Creato Display Black
        rule.onAllNodesWithContentDescription("fonts_search", useUnmergedTree = true).onFirst().performTextInput("creato")
        Thread.sleep(600); shot("141_fuentes_busqueda")
        assertTrue(has("Creato Display Black")); assertTrue("search filters", !has("Comfortaa"))
        tap("Creato Display Black")
        backKey(); Thread.sleep(500); shot("142_titulo_con_fuente")
        backKey()
        desc(s(R.string.apply)); Thread.sleep(1000)
        TestNav.leaveEditor(rule, ::s)
        val t = runBlocking { app.projects.load(id) }.document.objects.filterIsInstance<TextObject>().single()
        assertEquals("Texto MAC", t.text)
        assertEquals("bundled:CreatoDisplay-Black.otf", t.style.font)
        openProject("Catalogo"); shot("143_fuente_reabierta", 2500)
        assertTrue("no missing-font warning", !has("not available") && !has("no están disponibles"))
    }

    /** Only on the personal test APK (fonts added by tools/personal_fonts/make_personal_apk.py). */
    @Test fun personalLibraryFonts() {
        val lib = app.fonts.libraryFonts
        log("LIBRARY ${lib.map { it.displayName + " ← " + it.ref }}")
        assertTrue("personal library present", lib.isNotEmpty())
        for (f in lib) {
            val tf = app.fonts.typeface(f.ref, false, false, emptyMap())
            assertTrue("${f.ref} loads (not the default font)", tf !== android.graphics.Typeface.DEFAULT)
            assertEquals(FontOption.Kind.LIBRARY, f.kind)
        }
        val id = newProject("Biblioteca"); openProject("Biblioteca")
        tap(s(R.string.menu_sticker)); tap(s(R.string.title_sticker)); waitFor(s(R.string.enter_text_here))
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Mi fuente")
        rule.runOnUiThread { ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ctx.window.decorView.windowToken, 0) }
        Thread.sleep(800)
        desc(s(R.string.title_preview_hint)); waitFor(s(R.string.alignment))
        tap(s(R.string.tab_font)); waitFor(s(R.string.fonts_library), 10_000); shot("145_biblioteca_personal")
        val pick = lib.first()
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(pick.displayName))
        tap(pick.displayName)
        backKey(); backKey()
        desc(s(R.string.apply)); Thread.sleep(1000)
        TestNav.leaveEditor(rule, ::s)
        val t = runBlocking { app.projects.load(id) }.document.objects.filterIsInstance<TextObject>().single()
        assertEquals(pick.ref, t.style.font)
    }

    // ------------------------------------------------------------------ 6. home thumbnail

    @Test fun homeThumbnailAboutTwiceAsBig() {
        val id = newProject("Miniatura", 1200, 900)
        dismissRecovery()
        // the most recent project is shown: make this one the most recent
        runBlocking { val b = app.projects.load(id); app.projects.save(ProjectBundle(b.document.copy(modifiedAt = System.currentTimeMillis()), b.sourceBytes, b.masks, b.thumbnailJpeg, b.assets)) }
        ui { ctx.recreate() }; Thread.sleep(2500)
        dismissRecovery()
        rule.waitUntil(10_000) { hasDesc("Miniatura") }
        val r = boundsOf("Miniatura")
        val dp = ctx.resources.displayMetrics.density
        val long = max(r.width, r.height) / dp; val short = minOf(r.width, r.height) / dp
        log("THUMB ${r.width / dp} × ${r.height / dp} dp (1.0: 44 × 58 dp)")
        shot("150_inicio_miniatura")
        assertTrue("long side about 116 dp (twice 58): $long", long in 100f..120f)
        assertTrue("photo proportions 4:3 kept: ${long / short}", abs(long / short - 4f / 3f) < 0.06f)
        assertTrue("top right (${r.right / dp}, ${r.top / dp} dp)", r.right > ctx.resources.displayMetrics.widthPixels - 40 * dp && r.top < 110 * dp)
    }
}
