package app.bocadillo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerLibraryTest {
    private val png1 = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)
    private val png2 = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 9, 9, 9)
    private val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
    private val svg = """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10"/></svg>""".toByteArray()

    @Test fun thirtyTwoCategoriesInOrderAndResolvable() {
        assertEquals(32, StickerCategories.all.size)
        assertEquals("hats_helmets", StickerCategories.all.first().id)
        assertEquals("activity", StickerCategories.all.last().id)
        assertEquals(32, StickerCategories.all.map { it.id }.toSet().size)
        assertEquals("hats_helmets", StickerCategories.resolve("01_hats_helmets")?.id)
        assertEquals("hats_helmets", StickerCategories.resolve("Hats & Helmets")?.id)
        assertEquals("st_patricks_day", StickerCategories.resolve("St. Patrick’s Day")?.id)
        assertEquals("st_patricks_day", StickerCategories.resolve("27_st_patrick_s_day")?.id)
        assertEquals("food_drink", StickerCategories.resolve("17")?.id)
        assertEquals("food_drink", StickerCategories.resolve("Comida y bebida")?.id)
        assertNull(StickerCategories.resolve("nope"))
        // every folder name of the reference catalogue resolves to its own position
        val folders = listOf("01_hats_helmets", "02_hairstyles", "03_eyes", "04_masks_glasses", "05_beards_moustaches", "06_ears_nose_mouth",
            "07_masks_pointers", "08_emoticons", "09_celebrations", "10_hands", "11_nature", "12_animals", "13_prints_stamps", "14_romance",
            "15_signs_symbols", "16_comic", "17_food_drink", "18_accessories", "19_sport", "20_basic_shapes", "21_summer", "22_halloween",
            "23_thanksgiving", "24_christmas_holiday", "25_happy_new_year", "26_easter_spring", "27_st_patrick_s_day", "28_independence_day",
            "29_travel_places", "30_objects", "31_people", "32_activity")
        folders.forEachIndexed { i, f -> assertEquals(f, i + 1, StickerCategories.resolve(f)?.position) }
    }

    @Test fun formatsAreSniffedFromContent() {
        assertEquals(StickerFormat.PNG, StickerFormat.sniff(png1))
        assertEquals(StickerFormat.WEBP, StickerFormat.sniff(webp))
        assertEquals(StickerFormat.SVG, StickerFormat.sniff(svg))
        assertNull(StickerFormat.sniff("hello".toByteArray()))
    }

    @Test fun manifestImportAddsKeepsAndReportsProblems() {
        val manifest = """{"format":"bocadillo-sticker-library","version":1,"stickers":[
            {"id":"hat1","category":"hats_helmets","name":"Hat","position":2,"file":"01_hats_helmets/hat1.png"},
            {"id":"hat2","category":"Hats & Helmets","name":"Cap","position":1,"file":"01_hats_helmets/hat2.webp"},
            {"id":"star","category":20,"file":"shapes/star.svg"},
            {"id":"dup","category":"eyes","file":"eyes/same.png"},
            {"id":"bad","category":"eyes","file":"eyes/bad.png"},
            {"id":"lost","category":"eyes","file":"eyes/missing.png"},
            {"id":"where","category":"Unicorns","file":"x/hat1.png"}
        ]}"""
        val files = mapOf(
            "01_hats_helmets/hat1.png" to png1, "01_hats_helmets/hat2.webp" to webp, "shapes/star.svg" to svg,
            "eyes/same.png" to png1, "eyes/bad.png" to "not a picture".toByteArray(), "x/hat1.png" to png2,
        )
        val p = StickerImporter.plan(StickerLibraryIndex(), StickerManifest.parse(manifest), files, now = 1)
        assertEquals(listOf("hat1", "hat2", "star"), p.added.map { it.id })
        assertEquals(1, p.duplicates.size)          // eyes/same.png has the same content as hat1
        assertEquals(3, p.errors.size)              // bad picture, missing file, unknown category
        assertEquals(listOf("hat2", "hat1"), p.index.inCategory("hats_helmets").map { it.id })
        assertEquals("basic_shapes", p.index.byId("star")?.category)
        assertEquals(3, p.newFiles.size)

        // second import: one changed picture, one new; nothing existing is lost
        val again = StickerImporter.plan(p.index, StickerManifest.parse("""{"stickers":[
            {"id":"hat1","category":"hats_helmets","name":"Hat","position":2,"file":"a.png"},
            {"id":"hat3","category":"hats_helmets","file":"b.png"}]}"""), mapOf("a.png" to png2, "b.png" to svg), now = 2)
        assertEquals(listOf("hat1"), again.updated.map { it.id })
        assertTrue(again.added.isEmpty()) // b.png is the same picture as "star"
        assertEquals(1, again.duplicates.size)
        assertEquals(3, again.index.stickers.size)
        assertEquals(StickerImporter.sha256(png2), again.index.byId("hat1")?.sha256)

        // the same ZIP twice changes nothing
        val same = StickerImporter.plan(p.index, StickerManifest.parse(manifest), files, now = 3)
        assertEquals(3, same.unchanged); assertTrue(same.added.isEmpty() && same.updated.isEmpty() && same.newFiles.isEmpty())
    }

    @Test fun foldersWithoutManifestAreOrganizedNaturally() {
        val paths = listOf("Lib/01_hats_helmets/10.png", "Lib/01_hats_helmets/2.png", "Lib/Animals/cat.svg", "__MACOSX/x.png", "readme.txt", "Lib/unknown/x.png")
        val e = StickerManifest.fromFolders(paths)
        val hats = e.filter { it.category == "01_hats_helmets" }
        assertEquals(listOf("Lib/01_hats_helmets/2.png" to 1, "Lib/01_hats_helmets/10.png" to 2), hats.map { it.file to it.position })
        assertEquals("Animals", e.first { it.file.endsWith("cat.svg") }.category)
        val plan = StickerImporter.plan(StickerLibraryIndex(), e, mapOf(
            "Lib/01_hats_helmets/10.png" to png1, "Lib/01_hats_helmets/2.png" to png2, "Lib/Animals/cat.svg" to svg, "Lib/unknown/x.png" to webp,
        ), now = 1)
        assertEquals(3, plan.added.size)
        assertEquals(1, plan.errors.size) // unknown folder → no category
        assertEquals("hats_helmets/2", plan.index.inCategory("hats_helmets").first().id)
    }

    @Test fun indexRoundTrip() {
        val p = StickerImporter.plan(StickerLibraryIndex(), listOf(ManifestEntry("a", "eyes", "Eye", 3, "a.svg")), mapOf("a.svg" to svg), 5)
        val back = StickerLibraryIndex.fromJson(p.index.toJson())
        assertEquals(p.index.stickers, back.stickers)
    }

    @Test fun pathTraversalIsRejected() {
        val p = StickerImporter.plan(StickerLibraryIndex(), listOf(ManifestEntry("a", "eyes", null, null, "../evil.png")), mapOf("../evil.png" to png1), 1)
        assertTrue(p.errors.single().contains("invalid path"))
    }
}
