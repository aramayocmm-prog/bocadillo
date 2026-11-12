package app.bocadillo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bocadillo.data.ImageIo
import app.bocadillo.editor.EditorViewModel
import app.bocadillo.editor.Labels
import app.bocadillo.editor.ToolState
import app.bocadillo.home.BlankLimits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * MAC Editor 1.2.0 decisions on an emulator (not a phone): 4 new blank image, 5 unified colour
 * picker (HEX, favourites and recents kept, eyedropper with zoom on every picture layer).
 */
@RunWith(AndroidJUnit4::class)
class Mac12Test {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = rule.activity
    private val app get() = ctx.application as BocadilloApp
    private fun s(id: Int) = ctx.getString(id)
    private val shots by lazy { File(ctx.filesDir, "screens").apply { mkdirs() } }
    private fun log(msg: String) { android.util.Log.i("Mac12", msg); runCatching { File(shots, "mac12_log.txt").appendText(msg + "\n") } }
    private fun shot(name: String, settle: Long = 900) {
        rule.waitForIdle(); Thread.sleep(settle); rule.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun hasDesc(d: String) = rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tap(text: String, substring: Boolean = false) { rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun desc(d: String) { rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun waitFor(text: String, ms: Long = 30_000) { rule.waitUntil(ms) { has(text) } }
    private fun vm() = EditorViewModel.lastInstance!!
    private fun ui(f: () -> Unit) = rule.runOnUiThread(f)
    private fun statusText(): String = rule.onAllNodesWithContentDescription("blank_status", useUnmergedTree = true).fetchSemanticsNodes().first()
        .config.let { c -> c.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text } ?: "" }
    private fun dismissRecovery() { if (has(s(R.string.home_recover_title))) { tap(s(R.string.cancel)); Thread.sleep(300) } }
    private fun okEnabled(): Boolean = rule.onAllNodesWithText(s(R.string.ok), useUnmergedTree = true).fetchSemanticsNodes().first()
        .let { n -> generateSequence(n) { it.parent }.none { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }

    private fun openBlankDialog() {
        dismissRecovery()
        desc(s(R.string.home_more)); tap(s(R.string.more_new_blank)); waitFor(s(R.string.blank_title))
    }

    @Test fun newBlankImageFormatsAndLimits() {
        openBlankDialog()
        shot("200_nueva_imagen")
        log("BLANK default: ${statusText()}")
        assertTrue("default 1080 × 1920", statusText().contains("1080 × 1920"))
        for ((name, expect) in listOf("4:5" to "1080 × 1350", "1:1" to "1080 × 1080", "16:9" to "1920 × 1080", "9:16" to "1080 × 1920")) {
            tap(name); log("BLANK $name: ${statusText()}"); assertTrue("$name → $expect", statusText().contains(expect))
        }
        // orientation: swap width and height
        tap("4:5"); desc(s(R.string.blank_swap)); log("BLANK swapped: ${statusText()}"); assertTrue(statusText().contains("1350 × 1080"))
        // custom sizes, validation
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("8"); assertTrue(statusText(), statusText() == s(R.string.blank_error_small).format(BlankLimits.MIN_SIDE)); assertFalse(okEnabled())
        fields[0].performTextReplacement("20000"); assertFalse(okEnabled()); log("BLANK 20000: ${statusText()}")
        fields[0].performTextReplacement("9000"); fields[1].performTextReplacement("9000"); log("BLANK 9000×9000: ${statusText()} (max pixels ${BlankLimits.maxPixels(Runtime.getRuntime().maxMemory())})")
        assertFalse("9000 × 9000 refused on this heap", okEnabled())
        fields[0].performTextReplacement("1234"); fields[1].performTextReplacement("777"); assertTrue(statusText().contains("1234 × 777")); assertTrue(okEnabled())
        shot("201_nueva_imagen_personalizada")
        // background with the unified picker: HEX
        tap(s(R.string.color_custom))
        rule.onAllNodes(hasContentDescription(s(R.string.color_hex))).onFirst().performTextReplacement("#FF3366")
        rule.waitForIdle(); shot("202_nueva_imagen_color")
        tap(s(R.string.ok))
        rule.waitUntil(30_000) { EditorViewModel.lastInstance?.state?.value?.loading == false && has(s(R.string.menu_adjust)) }
        Thread.sleep(2000)
        val st = vm().state.value
        val px = vm().previewBitmap()!!
        val c = px.pixels[px.height / 2 * px.width + px.width / 2]
        log("BLANK created ${st.outputWidth}×${st.outputHeight} centre=#%08X".format(c))
        assertEquals(1234, st.outputWidth); assertEquals(777, st.outputHeight)
        assertEquals(0xFFFF3366.toInt(), c)
        shot("203_nueva_imagen_creada")
        assertTrue("background colour is a recent colour", 0xFFFF3366.toInt() in app.settings.recentColors)
    }

    /** Left half red, right half blue: the eyedropper must take the side under the loupe at any zoom. */
    private fun halves(title: String = "Mitades"): String {
        dismissRecovery()
        val b = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Color.RED); drawRect(500f, 0f, 1000f, 800f, Paint().apply { color = Color.BLUE }) }
        val png = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val doc = runBlocking { app.projects.create(ImageIo.Imported(png, "image/png", title), 1000, 800, false, title, ImageIo.thumbnail(b.copy(Bitmap.Config.ARGB_8888, false))) }
        app.projects.clearDraft(doc.id)
        return doc.id
    }

    @Test fun colorPickerHexFavouritesRecentsEyedropper() {
        halves()
        if (!has("Mitades")) { desc(s(R.string.home_more)); tap(s(R.string.more_recent_projects)) }
        tap("Mitades"); waitFor(s(R.string.menu_adjust)); Thread.sleep(2500)
        // an effect with a colour (Solid border), all options: the unified picker with its eyedropper
        ui { vm().openTool(app.registry["solid_border"]!!) }
        rule.waitForIdle(); Thread.sleep(800)
        tap(s(Labels.op("solid_border")), substring = true); tap(s(R.string.more_options)); Thread.sleep(600)
        assertTrue("eyedropper in the colour picker", hasDesc(s(R.string.eyedropper)))
        shot("210_selector_color_efecto")
        // HEX
        tap(s(R.string.color_custom))
        rule.onAllNodes(hasContentDescription(s(R.string.color_hex))).onFirst().performTextReplacement("#80112233")
        rule.waitForIdle()
        fun param() = ((vm().state.value.tool as ToolState.Adjust).params.color("color", 0))
        log("PICK hex → #%08X".format(param()))
        assertEquals(0x80112233.toInt(), param())
        // favourite
        desc(s(R.string.color_favorite_toggle))
        assertTrue(0x80112233.toInt() in app.settings.favoriteColors)
        val stored = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getString("favorite_colors", "") ?: ""
        assertTrue("favourite kept in the preferences ($stored)", (0x80112233L).toString() in stored.split(','))
        shot("211_selector_color_avanzado")
        // eyedropper at zoom ×4: left of the centre line is red, right is blue
        desc(s(R.string.eyedropper))
        rule.waitUntil(10_000) { vm().lastEyedropperImage != null && hasDesc(s(R.string.eyedropper_picture)) }
        Thread.sleep(500)
        val area = rule.onAllNodesWithContentDescription(s(R.string.eyedropper_picture), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        val img = vm().lastEyedropperImage!!.asAndroidBitmap()
        // the centre of the picture on screen (fitted, then zoomed about the screen centre: same point)
        val k = minOf(area.width / img.width, area.height / img.height)
        val c = area.center
        rule.onRoot().performTouchInput { pinch(c - Offset(40f, 0f), c - Offset(160f, 0f), c + Offset(40f, 0f), c + Offset(160f, 0f), 400) }
        rule.waitForIdle(); Thread.sleep(500)
        shot("212_cuentagotas_zoom")
        // after a symmetric pinch the centre stays; at ×4 one picture pixel is 4·k screen px
        rule.onRoot().performTouchInput { down(c - Offset(12f, 0f)); moveTo(c - Offset(10f, 0f)) }
        rule.waitForIdle(); Thread.sleep(400)
        shot("213_cuentagotas_lupa")
        assertTrue("loupe shows the colour", hasDesc(s(R.string.eyedropper_loupe)))
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle(); Thread.sleep(500)
        log("PICK eyedropper left of centre (k=$k) → #%08X".format(param()))
        assertEquals(0xFFFF0000.toInt(), param())
        assertTrue("picked colour in the recent colours", 0xFFFF0000.toInt() in app.settings.recentColors)
        desc(s(R.string.eyedropper)); rule.waitUntil(10_000) { hasDesc(s(R.string.eyedropper_picture)) }; Thread.sleep(500)
        rule.onRoot().performTouchInput { pinch(c - Offset(40f, 0f), c - Offset(160f, 0f), c + Offset(40f, 0f), c + Offset(160f, 0f), 400) }
        rule.waitForIdle(); Thread.sleep(400)
        rule.onRoot().performTouchInput { down(c + Offset(12f, 0f)); moveTo(c + Offset(10f, 0f)); up() }
        rule.waitForIdle(); Thread.sleep(500)
        log("PICK eyedropper right of centre → #%08X".format(param()))
        assertEquals(0xFF0000FF.toInt(), param())
        // Back closes the eyedropper without picking
        desc(s(R.string.eyedropper)); rule.waitUntil(10_000) { hasDesc(s(R.string.eyedropper_picture)) }
        ui { ctx.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle(); Thread.sleep(400)
        assertFalse(hasDesc(s(R.string.eyedropper_picture)))
        assertEquals(0xFF0000FF.toInt(), param())
        ui { vm().cancelTool() }
    }

    private fun assetBytes(path: String) = ctx.assets.open(path).use { it.readBytes() }
    private fun fontsZip(): File {
        val f = File(ctx.cacheDir, "fuentes12.zip")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() }
            // two valid fonts (unique bytes: 16 bytes appended), in a folder
            put("Fuentes/Lora12.ttf", assetBytes("fonts/Lora-Variable.ttf") + ByteArray(16) { 7 })
            put("Fuentes/Bebas12.ttf", assetBytes("fonts/BebasNeue-Regular.ttf") + ByteArray(16) { 9 })
            put("Fuentes/Comfortaa-Bold.ttf", assetBytes("fonts/Comfortaa-Bold.ttf"))      // duplicate of a font inside the app
            put("Fuentes/broken.ttf", ByteArray(500) { 1 })                                // not a font
            put("../evil.ttf", assetBytes("fonts/Poppins-Bold.ttf") + ByteArray(16) { 3 })  // unsafe path
            put("huge.ttf", ByteArray(26 * 1024 * 1024))                                   // larger than 25 MB unpacked
            put("leeme.txt", "hola".toByteArray())                                         // ignored
        }
        return f
    }

    private fun openTitleFonts(project: String) {
        dismissRecovery()
        if (!has(project)) { desc(s(R.string.home_more)); tap(s(R.string.more_recent_projects)) }
        tap(project); waitFor(s(R.string.menu_adjust)); Thread.sleep(2500)
        tap(s(R.string.menu_sticker)); tap(s(R.string.title_sticker)); waitFor(s(R.string.enter_text_here))
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Fuentes 1.2")
        ui { ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ctx.window.decorView.windowToken, 0) }
        Thread.sleep(800)
        desc(s(R.string.title_preview_hint)); waitFor(s(R.string.alignment))
        tap(s(R.string.tab_font)); waitFor(s(R.string.fonts_import_button))
    }
    private fun draftFont() = (vm().state.value.tool as ToolState.EditText).draft.style.font
    private fun top(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top

    @Test fun fontsCompactHeaderFavouritesRecentsImportDelete() {
        halves("Fuentes12")
        openTitleFonts("Fuentes12")
        shot("220_mas_fuentes")
        // decision 7: import button, then the short line, then the search, then the list
        val tb = top(s(R.string.fonts_import_button)); val tl = top(s(R.string.fonts_import_short))
        val ts = rule.onAllNodesWithContentDescription("fonts_search", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top
        log("FONTS12 header: button=$tb line=$tl search=$ts")
        assertTrue("button above the line above the search", tb < tl && tl < ts)
        desc(s(R.string.fonts_help_title)); waitFor(s(R.string.fonts_help_title)); shot("221_ayuda_fuentes")
        assertTrue(has("ZIP")); tap(s(R.string.ok))
        // decision 9: favourite with the star; the Favourites section appears and is kept
        desc(s(R.string.fonts_favorite_toggle).format("Bebas Neue"))
        assertTrue("bundled:BebasNeue-Regular.ttf" in app.settings.fontFavorites)
        assertTrue(has(s(R.string.fonts_favorites)))
        val stored = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getString("font_favorites", "") ?: ""
        assertTrue("favourite stored", "bundled:BebasNeue-Regular.ttf" in stored)
        // search: timing of a keystroke filter, then pick → applied and back to the text (decision 8)
        val t0 = System.nanoTime()
        rule.onAllNodesWithContentDescription("fonts_search", useUnmergedTree = true).onFirst().performTextReplacement("comf")
        rule.waitForIdle()
        log("FONTS12 search 'comf' shown in ${(System.nanoTime() - t0) / 1_000_000} ms (fonts=${app.fonts.all().size})")
        shot("222_busqueda")
        assertTrue(has("Comfortaa Bold")); assertFalse(has("Poppins"))
        tap("Comfortaa Bold")
        waitFor(s(R.string.alignment), 5_000)
        assertEquals("bundled:Comfortaa-Bold.ttf", draftFont())
        assertEquals("bundled:Comfortaa-Bold.ttf", app.settings.fontRecents.first())
        // recents: listed, no duplicates after picking again
        tap(s(R.string.tab_font)); waitFor(s(R.string.fonts_recent))
        assertTrue(hasDesc("rec Comfortaa Bold"))
        desc("rec Comfortaa Bold"); waitFor(s(R.string.alignment), 5_000)
        assertEquals(1, app.settings.fontRecents.count { it == "bundled:Comfortaa-Bold.ttf" })
        // decision 10: ZIP import with progress and summary
        tap(s(R.string.tab_font)); waitFor(s(R.string.fonts_import_button))
        val before = app.fonts.importedFonts().size
        var sawProgress = false
        ui { vm().importFonts(listOf(android.net.Uri.fromFile(fontsZip()))) { } }
        val tImp = System.nanoTime()
        rule.waitUntil(60_000) { if (vm().fontImport.value != null) sawProgress = true; hasDesc("fonts_report_summary") }
        log("FONTS12 ZIP import ${(System.nanoTime() - tImp) / 1_000_000} ms, progress seen=$sawProgress")
        shot("223_resumen_importacion")
        val r = vm().fontReport.value!!
        log("FONTS12 report added=${r.added} dups=${r.duplicates} invalid=${r.invalid} errors=${r.errors}")
        assertEquals(2, r.added.size); assertEquals(1, r.duplicates.size); assertEquals(1, r.invalid.size)
        assertTrue("unsafe path refused", r.errors.any { it.second == "unsafe path" })
        assertTrue("oversize refused", r.errors.any { it.second.contains("25 MB") })
        assertEquals(before + 2, app.fonts.importedFonts().size)
        tap(s(R.string.ok)); Thread.sleep(400)
        // use an imported font in the title, save the project
        val imported = app.fonts.importedFonts().first { it.displayName.contains("Bebas", true) }
        rule.onAllNodesWithContentDescription("fonts_search", useUnmergedTree = true).onFirst().performTextReplacement(imported.displayName)
        rule.waitForIdle(); desc("all ${imported.displayName}"); waitFor(s(R.string.alignment), 5_000)
        assertEquals(imported.ref, draftFont())
        ui { ctx.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle(); Thread.sleep(500)
        desc(s(R.string.apply)); Thread.sleep(1000)
        var saved: Boolean? = null
        ui { vm().saveProject { saved = it } }; rule.waitUntil(20_000) { saved != null }
        // delete it: the warning names the project; bundled fonts cannot be deleted (no long press)
        tap(s(R.string.menu_sticker)); tap(s(R.string.title_sticker)); waitFor(s(R.string.enter_text_here))
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("x")
        ui { ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ctx.window.decorView.windowToken, 0) }
        Thread.sleep(800)
        desc(s(R.string.title_preview_hint)); waitFor(s(R.string.alignment))
        tap(s(R.string.tab_font)); waitFor(s(R.string.fonts_import_button))
        rule.onAllNodesWithContentDescription("fonts_search", useUnmergedTree = true).onFirst().performTextReplacement(imported.displayName)
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("all ${imported.displayName}", useUnmergedTree = true).onFirst().performTouchInput { longClick() }
        rule.waitUntil(10_000) { hasDesc("fonts_delete_used") }
        shot("224_borrar_fuente_en_uso")
        assertTrue(has("Fuentes12"))
        tap(s(R.string.action_delete)); Thread.sleep(600)
        assertFalse(app.fonts.importedFonts().any { it.ref == imported.ref })
        assertFalse(imported.ref in app.settings.fontRecents)
        // the project still has its own copy of the font
        val doc = runBlocking { app.projects.load(projectIdOf("Fuentes12")) }
        assertTrue("project keeps the font", doc.assets.keys.any { imported.ref.removePrefix("asset:") == it })
        ui { vm().cancelTool() }
    }
    private fun projectIdOf(title: String) = app.projects.index.value.entries.first { it.title == title }.id

    private fun backKey() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); rule.waitForIdle(); Thread.sleep(500) }

    /** Decision 2: Back goes back step by step; cancelling the system file picker keeps the work. */
    @Test fun backHierarchyAndFilePickerCancel() {
        halves("Atras12")
        dismissRecovery()
        if (!has("Atras12")) { desc(s(R.string.home_more)); tap(s(R.string.more_recent_projects)) }
        tap("Atras12"); waitFor(s(R.string.menu_adjust)); Thread.sleep(2500)
        // 1. a menu: Back closes it, the editor stays
        tap(s(R.string.menu_adjust)); Thread.sleep(500)
        assertTrue(has(s(R.string.op_exposure_contrast)))
        backKey()
        assertFalse("menu closed", has(s(R.string.op_exposure_contrast))); assertTrue(has(s(R.string.menu_adjust)))
        // 2. a tool with all its options open: Back closes the options, then the tool
        ui { vm().openTool(app.registry["solid_border"]!!) }; rule.waitForIdle(); Thread.sleep(600)
        tap(s(Labels.op("solid_border")), substring = true); tap(s(R.string.more_options)); Thread.sleep(500)
        assertTrue("all options shown", has(s(R.string.reset)))
        backKey()
        assertTrue("tool still open after closing its options", vm().state.value.tool is ToolState.Adjust)
        backKey()
        assertTrue("tool closed", vm().state.value.tool == null)
        log("BACK menu → options → tool: ok")
        // 3. a change, then Guardar imagen in a chosen place, and the system picker is cancelled
        ui { vm().orient(90, false, false) }; Thread.sleep(1500)
        desc(s(R.string.finish)); waitFor(s(R.string.finish_title))
        tap(s(R.string.finish_save_image)); waitFor(s(R.string.save_image_title))
        tap(s(R.string.save_image_choose))
        tap(s(R.string.save_image_save))
        Thread.sleep(3000); shot("230_selector_archivos_sistema")
        // the system picker is another app: Back cancels it
        backKey(); Thread.sleep(1500)
        rule.waitUntil(15_000) { runCatching { has(s(R.string.menu_adjust)) || has(s(R.string.save_image_title)) }.getOrDefault(false) }
        shot("231_tras_cancelar_selector")
        log("BACK picker cancelled: editor=${has(s(R.string.menu_adjust))} saveDialog=${has(s(R.string.save_image_title))} pending=${vm().hasPendingChanges()}")
        assertTrue("work kept after cancelling", vm().hasPendingChanges())
        if (has(s(R.string.save_image_title))) tap(s(R.string.cancel))
        // 4. Back in the editor with changes: the finish dialog; «Guardar proyecto» keeps it, it opens again with the change
        backKey(); waitFor(s(R.string.finish_title))
        tap(s(R.string.finish_save_project)); rule.waitUntil(15_000) { !has(s(R.string.menu_adjust)) }
        Thread.sleep(800)
        val id = app.projects.index.value.entries.first { it.title == "Atras12" }.id
        val saved = runBlocking { app.projects.load(id) }.document
        log("BACK saved project ops=${saved.ops.size}")
        assertEquals(1, saved.ops.size)
        if (!has("Atras12")) { desc(s(R.string.home_more)); tap(s(R.string.more_recent_projects)) }
        tap("Atras12"); waitFor(s(R.string.menu_adjust)); Thread.sleep(2500)
        assertEquals("recovered with its change", 1, vm().state.value.historyStep.let { _ -> runBlocking { app.projects.load(id) }.document.ops.size })
        assertEquals(800, vm().state.value.outputWidth) // rotated 90°: 1000×800 → 800×1000
        // 5. no changes: Back leaves without a dialog
        backKey(); rule.waitUntil(10_000) { !has(s(R.string.menu_adjust)) }
        assertFalse(has(s(R.string.finish_title)))
    }

    /** Decision 6: every category has its illustration; empty ones say «Pendiente de importar». */
    @Test fun stickerCategoryIllustrations() {
        halves("Iconos12")
        dismissRecovery()
        if (!has("Iconos12")) { desc(s(R.string.home_more)); tap(s(R.string.more_recent_projects)) }
        tap("Iconos12"); waitFor(s(R.string.menu_adjust)); Thread.sleep(2500)
        tap(s(R.string.menu_sticker)); Thread.sleep(800)
        shot("240_categorias_stickers")
        val missing = ArrayList<String>()
        for (cat in app.bocadillo.core.model.StickerCategories.all) {
            rule.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).onFirst()
                .performScrollToNode(androidx.compose.ui.test.hasContentDescription("icon_${cat.id}"))
            if (!hasDesc("icon_${cat.id}")) missing += cat.id
        }
        shot("241_categorias_stickers_final")
        log("ICONS ${app.bocadillo.core.model.StickerCategories.all.size} categories, missing=$missing, pending notes=${rule.onAllNodesWithText(s(R.string.sticker_pending_short), useUnmergedTree = true).fetchSemanticsNodes().size}")
        assertTrue("all categories illustrated: missing $missing", missing.isEmpty())
        // an empty category: the large illustration and the pending text, no invented stickers
        val empty = app.bocadillo.core.model.StickerCategories.all.firstOrNull { app.stickerPacks.index.inCategory(it.id).isEmpty() }
        if (empty != null) {
            tap(empty.name(java.util.Locale.getDefault().language)); Thread.sleep(800)
            shot("242_categoria_vacia")
            assertTrue(has(s(R.string.sticker_pending_long).substringBefore("\n")))
        }
    }
}
