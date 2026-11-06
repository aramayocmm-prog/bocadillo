package app.bocadillo.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelTest {

    private val source = SourceInfo("source.jpg", "image/jpeg", 4000, 3000, hasAlpha = false)

    private fun sampleDoc(): Document {
        val d = Document.create("Prueba", source, now = 1000L)
        return d
            .withOp(OpInstance.create("brightness", Params.of("amount" to 25.0)), 1100L)
            .withOp(OpInstance.create("crop", Params.of("rect" to listOf(0.1, 0.1, 0.9, 0.8), "angle" to 2.5)), 1200L)
            .withOp(OpInstance("u3", "hue", Params.of("amount" to -40.0, "flag" to true, "mode" to "x"), maskId = "m1", maskInverted = true), 1300L)
            .withMask(MaskRef("m1", 40, 30), 1300L)
    }

    @Test fun historyUndoRedoAndBranching() {
        val h = History(0, "open", limit = 10)
        assertFalse(h.canUndo)
        h.push(1, "a"); h.push(2, "b"); h.push(3, "c")
        assertEquals(3, h.current)
        assertEquals(2, h.undo())
        assertEquals(1, h.undo())
        assertTrue(h.canRedo)
        assertEquals(2, h.redo())
        h.push(9, "branch")
        assertFalse("redo branch must be discarded", h.canRedo)
        assertEquals(listOf(0, 1, 2, 9), h.stages().map { it.state })
        assertEquals(9, h.current)
    }

    @Test fun historyLimitKeepsOpenedStage() {
        val h = History(0, "open", limit = 4)
        for (i in 1..10) h.push(i, "s$i")
        assertEquals(4, h.size)
        assertEquals(0, h.stages().first().state)
        assertEquals(10, h.current)
        repeat(3) { h.undo() }
        assertEquals(0, h.current)
        assertFalse(h.canUndo)
    }

    @Test fun historyJumpToStage() {
        val h = History("o", "open")
        h.push("a", "a"); h.push("b", "b")
        assertEquals("o", h.jumpTo(0))
        assertTrue(h.canRedo)
        assertEquals("b", h.jumpTo(2))
    }

    @Test fun documentJsonRoundTrip() {
        val d = sampleDoc()
        val back = DocumentJson.decode(DocumentJson.encode(d))
        assertEquals(d, back)
        assertEquals(-40f, back.ops[2].params.float("amount", 0f))
        assertTrue(back.ops[2].params.bool("flag", false))
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.9f, 0.8f), back.ops[1].params.floats("rect"), 1e-6f)
    }

    @Test fun colorParamsSurviveRoundTrip() {
        val p = Params.EMPTY.withColor("c", 0xFF80FF00.toInt()).withColor("t", 0x00000000)
        val back = DocumentJson.paramsFromJson(DocumentJson.paramsToJson(p))
        assertEquals(0xFF80FF00.toInt(), back.color("c", 0))
        assertEquals(0, back.color("t", 1))
    }

    @Test fun rejectsNewerFormat() {
        val text = DocumentJson.encode(sampleDoc()).replace("\"formatVersion\": 1", "\"formatVersion\": 99")
        try { DocumentJson.decode(text); fail("expected exception") } catch (e: ProjectFormatException) {
            assertTrue(e.message!!.contains("newer"))
        }
    }

    @Test fun resetToOriginalClearsEdits() {
        val d = sampleDoc().resetToOriginal(5000L)
        assertTrue(d.ops.isEmpty()); assertTrue(d.masks.isEmpty())
        assertEquals(source, d.source)
    }

    @Test fun archiveRoundTripKeepsSourceBytesAndMasks() {
        val d = sampleDoc()
        val src = ByteArray(5000) { (it * 31).toByte() }
        val mask = MaskData(40, 30, ByteArray(1200) { (it % 256).toByte() })
        val unused = MaskData(2, 2, ByteArray(4))
        val out = ByteArrayOutputStream()
        ProjectArchive.write(ProjectBundle(d, src, mapOf("m1" to mask, "zz" to unused), byteArrayOf(1, 2, 3)), out)
        val back = ProjectArchive.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(d, back.document)
        assertArrayEquals(src, back.sourceBytes)
        assertEquals(setOf("m1"), back.masks.keys)
        assertArrayEquals(mask.data, back.masks["m1"]!!.data)
        assertArrayEquals(byteArrayOf(1, 2, 3), back.thumbnailJpeg)
    }

    @Test fun archiveDropsUnreferencedMaskRefs() {
        val d = sampleDoc().withMask(MaskRef("orphan", 2, 2), 2000L)
        val out = ByteArrayOutputStream()
        ProjectArchive.write(ProjectBundle(d, byteArrayOf(9), mapOf("m1" to MaskData.filled(40, 30, 255))), out)
        val back = ProjectArchive.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf("m1"), back.document.masks.map { it.id })
    }

    @Test fun archiveRejectsForeignZip() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("hello.txt")); it.write("x".toByteArray()); it.closeEntry() }
        try { ProjectArchive.read(ByteArrayInputStream(out.toByteArray())); fail() } catch (e: ProjectFormatException) { }
    }

    @Test fun projectIndexOrderingFavoritesAndPersistence() {
        var idx = ProjectIndex()
        for (i in 1..12) idx = idx.upsert(ProjectEntry("p$i", "T$i", modifiedAt = i * 10L))
        assertEquals(12, idx.entries.size) // no 8-picture limit
        idx = idx.setFavorite("p3", true).upsert(ProjectEntry("p3", "T3b", modifiedAt = 999L))
        assertEquals("p3", idx.recent().first().id)
        assertTrue("favorite flag kept after update", idx.find("p3")!!.favorite)
        assertEquals(listOf("p3"), idx.favorites().map { it.id })
        val back = ProjectIndex.decode(idx.encode())
        assertEquals(idx.recent(), back.recent())
        assertNull(back.remove("p3").find("p3"))
    }

    @Test fun sliderSpecSnapAndDefaults() {
        val s = ParamSpec.Slider("a", "a", -10f, 10f, 0.1f, 0f)
        assertEquals(2.3f, s.snap(2.3123f), 1e-5f)
        assertEquals(10f, s.snap(50f), 0f)
        val p = listOf(s, ParamSpec.Toggle("t", "t", true)).defaults(Params.of("a" to 4.0))
        assertEquals(4f, p.float("a", 0f))
        assertTrue(p.bool("t", false))
    }
}
