package app.bocadillo.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ObjectsTest {
    private val src = SourceInfo("source.png", "image/png", 800, 600, hasAlpha = true)

    private fun docWithObjects(): Document {
        val d = Document.create("Obj", src, 1L)
        val text = TextObject(
            "t1", ObjectTransform(0.3f, 0.2f, 1.5f, 1.2f, 0.9f, -15f, flipH = true),
            "Hola\nmundo", TextStyle(font = "asset:fonts/mi fuente.ttf", color = 0xFFFF0000.toInt(), align = TextAlign.LEFT, shadowColor = 0x80000000.toInt()),
            opacity = 0.7f, curve = 120f,
        )
        val balloon = BalloonObject("b1", ObjectTransform(0.6f, 0.4f), BalloonShape.THOUGHT, 0.5f, 0.3f, 0.2f, 0.4f, text = "¿Qué?")
        val sticker = StickerObject("s1", ObjectTransform(), "builtin:heart", colors = listOf(0xFFE91E63.toInt(), 0x00000000), outlineScale = 2f)
        val png = StickerObject("s2", ObjectTransform(0.1f, 0.9f), "asset:stickers/abc.png", aspect = 1.5f)
        return d.addObject(text, 2).addObject(balloon, 3).addObject(sticker, 4).addObject(png, 5)
    }

    @Test fun objectsSurviveJsonRoundTrip() {
        val d = docWithObjects()
        val back = DocumentJson.decode(DocumentJson.encode(d))
        assertEquals(d, back)
        assertEquals(0x80000000.toInt(), (back.objects[0] as TextObject).style.shadowColor)
        assertEquals(BalloonShape.THOUGHT, (back.objects[1] as BalloonObject).shape)
    }

    @Test fun unknownObjectTypeIsRejected() {
        val text = DocumentJson.encode(docWithObjects()).replace("\"type\": \"sticker\"", "\"type\": \"hologram\"")
        try { DocumentJson.decode(text); throw AssertionError("expected failure") } catch (_: ProjectFormatException) {}
    }

    @Test fun archiveKeepsOnlyReferencedAssets() {
        val d = docWithObjects()
        val font = ByteArray(300) { it.toByte() }
        val png = ByteArray(50) { 7 }
        val out = ByteArrayOutputStream()
        ProjectArchive.write(ProjectBundle(d, byteArrayOf(1), assets = mapOf("fonts/mi fuente.ttf" to font, "stickers/abc.png" to png, "stickers/unused.png" to png)), out)
        val back = ProjectArchive.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(setOf("fonts/mi fuente.ttf", "stickers/abc.png"), back.assets.keys)
        assertArrayEquals(font, back.assets["fonts/mi fuente.ttf"])
        assertEquals(d, back.document)
    }

    @Test fun missingAssetIsReported() {
        val out = ByteArrayOutputStream()
        ProjectArchive.write(ProjectBundle(docWithObjects(), byteArrayOf(1)), out)
        try { ProjectArchive.read(ByteArrayInputStream(out.toByteArray())); throw AssertionError() } catch (e: ProjectFormatException) {
            assertTrue(e.message!!.contains("Asset"))
        }
    }

    @Test fun duplicateInsertsAboveWithOffsetAndNewId() {
        val (d, newId) = docWithObjects().duplicateObject("b1", 9)
        assertEquals(listOf("t1", "b1", newId, "s1", "s2"), d.objects.map { it.id })
        val a = d.objectById("b1")!!; val b = d.objectById(newId)!!
        assertNotEquals(a.id, b.id)
        assertEquals(a.transform.cx + 0.04f, b.transform.cx, 1e-6f)
        assertEquals((a as BalloonObject).text, (b as BalloonObject).text)
    }

    @Test fun arrangeMovesInZOrder() {
        val d = docWithObjects() // t1 b1 s1 s2
        assertEquals(listOf("b1", "s1", "s2", "t1"), d.arrangeObject("t1", Arrange.FRONT, 0).objects.map { it.id })
        assertEquals(listOf("s2", "t1", "b1", "s1"), d.arrangeObject("s2", Arrange.BACK, 0).objects.map { it.id })
        assertEquals(listOf("t1", "s1", "b1", "s2"), d.arrangeObject("b1", Arrange.FORWARD, 0).objects.map { it.id })
        assertEquals(listOf("t1", "s1", "b1", "s2"), d.arrangeObject("s1", Arrange.BACKWARD, 0).objects.map { it.id })
        assertEquals(listOf("t1", "b1", "s1", "s2"), d.arrangeObject("s2", Arrange.FORWARD, 0).objects.map { it.id }) // already on top
    }

    @Test fun updateRemoveAndReset() {
        val d = docWithObjects()
        val moved = d.updateObject(d.objectById("s1")!!.withTransform(ObjectTransform(0.9f, 0.1f, scale = 3f, rotation = 45f)), 2)
        assertEquals(45f, moved.objectById("s1")!!.transform.rotation)
        assertEquals(ObjectTransform(0.9f, 0.1f), moved.objectById("s1")!!.transform.reset())
        assertNull(moved.removeObject("s1", 3).objectById("s1"))
        assertTrue(d.resetToOriginal(4).objects.isEmpty())
        assertEquals(1f, d.objectById("t1")!!.withOpacity(5f).opacity)
    }

    @Test fun historyOfObjectEditsUndoesCleanly() {
        val h = History(Document.create("x", src, 0), "open")
        var d = h.current.addObject(TextObject("a", text = "A"), 1); h.push(d, "add")
        d = d.updateObject((d.objectById("a") as TextObject).copy(text = "B"), 2); h.push(d, "edit")
        h.undo()
        assertEquals("A", (h.current.objectById("a") as TextObject).text)
        h.undo()
        assertTrue(h.current.objects.isEmpty())
    }
}
