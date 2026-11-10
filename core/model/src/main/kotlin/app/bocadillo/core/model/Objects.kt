package app.bocadillo.core.model

/*
 * Overlay objects (stage 3). They stay editable inside the project: nothing is rasterized until
 * export (or until the user explicitly blends an object into the picture in a later stage).
 *
 * Units: positions are normalized to the output picture (0..1). Sizes use the unit
 * "u" = min(outputWidth, outputHeight), so objects keep their look at any export resolution.
 */

/** Placement of an object on the canvas. */
data class ObjectTransform(
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    /** Uniform scale (Scale & Rotate tool). */
    val scale: Float = 1f,
    /** Non-uniform stretch (Stretch tool). */
    val stretchX: Float = 1f,
    val stretchY: Float = 1f,
    /** Degrees, clockwise. */
    val rotation: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
) {
    fun moved(dx: Float, dy: Float) = copy(cx = cx + dx, cy = cy + dy)

    /** Reset Transform keeps position only (as "Reset" in the original Transform menu). */
    fun reset() = ObjectTransform(cx = cx, cy = cy)

    companion object {
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 20f
    }
}

enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * Text appearance shared by titles and balloon captions.
 * [size] is the font em-size in u; outline/shadow sizes are fractions of the font size.
 */
data class TextStyle(
    /** "system:<family>", "bundled:<file>" or "asset:fonts/<file>" (imported, stored in the project). */
    val font: String = "system:sans-serif-black",
    val size: Float = 0.09f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: TextAlign = TextAlign.CENTER,
    val lineHeight: Float = 1.0f,
    val letterSpacing: Float = 0f,
    val outlineColor: Int = 0xFF000000.toInt(),
    /** 0 = no outline. */
    val outlineWidth: Float = 0.14f,
    /** Alpha 0 = no shadow. */
    val shadowColor: Int = 0x00000000,
    val shadowDx: Float = 0.06f,
    val shadowDy: Float = 0.06f,
    val shadowBlur: Float = 0.06f,
    /** Alpha 0 = no background box. */
    val backgroundColor: Int = 0x00000000,
)

enum class BalloonShape { SPEECH, THOUGHT, SCREAM, WHISPER, RECTANGLE, ROUNDED, CAPTION, POINTER;
    companion object {
        /** Order of the shape picker (two columns, as in the classic screen). */
        val pickerOrder = listOf(SPEECH, RECTANGLE, THOUGHT, WHISPER, ROUNDED, SCREAM, CAPTION, POINTER)
    }
}

enum class Arrange { FRONT, FORWARD, BACKWARD, BACK }

sealed interface CanvasObject {
    val id: String
    val transform: ObjectTransform
    /** 0..1 */
    val opacity: Float

    fun withTransform(t: ObjectTransform): CanvasObject
    fun withOpacity(o: Float): CanvasObject
    fun withId(newId: String): CanvasObject
}

/** "Title": free text, multi-line, optionally wrapped on a circle (Circular Wrap). */
data class TextObject(
    override val id: String,
    override val transform: ObjectTransform = ObjectTransform(),
    val text: String,
    val style: TextStyle = TextStyle(),
    override val opacity: Float = 1f,
    /** Circular Wrap −360…360°: total arc the (single-line) text is bent over. 0 = straight. */
    val curve: Float = 0f,
) : CanvasObject {
    override fun withTransform(t: ObjectTransform) = copy(transform = t)
    override fun withOpacity(o: Float) = copy(opacity = o.coerceIn(0f, 1f))
    override fun withId(newId: String) = copy(id = newId)
}

/**
 * Word balloon. [width]/[height] are the body size in u; the tail tip ([tailX],[tailY]) is in
 * u relative to the body centre, in the object's local (unrotated) frame, so it moves with the
 * balloon but can be dragged independently.
 */
data class BalloonObject(
    override val id: String,
    override val transform: ObjectTransform = ObjectTransform(),
    val shape: BalloonShape = BalloonShape.SPEECH,
    val width: Float = 0.5f,
    val height: Float = 0.3f,
    val tailX: Float = -0.12f,
    val tailY: Float = 0.32f,
    /** Tail base width as a fraction of the body's smaller side. */
    val tailWidth: Float = 0.3f,
    val hasTail: Boolean = true,
    val fillColor: Int = 0xFFFFFFFF.toInt(),
    val strokeColor: Int = 0xFF000000.toInt(),
    /** In u. */
    val strokeWidth: Float = 0.008f,
    val text: String = "",
    val style: TextStyle = TextStyle(color = 0xFF000000.toInt(), outlineWidth = 0f, size = 0.06f, font = "system:casual"),
    override val opacity: Float = 1f,
) : CanvasObject {
    override fun withTransform(t: ObjectTransform) = copy(transform = t)
    override fun withOpacity(o: Float) = copy(opacity = o.coerceIn(0f, 1f))
    override fun withId(newId: String) = copy(id = newId)
}

/**
 * Sticker. [source] is "builtin:<catalogId>" (vector, recolourable) or "asset:stickers/<file>"
 * (imported PNG). [colors] overrides the vector colour slots (empty = catalogue defaults).
 * [size] is the length of the longer side in u; [aspect] = width / height.
 */
data class StickerObject(
    override val id: String,
    override val transform: ObjectTransform = ObjectTransform(),
    val source: String,
    val size: Float = 0.35f,
    val aspect: Float = 1f,
    val colors: List<Int> = emptyList(),
    /** Multiplies vector stroke widths ("Outline Size"). */
    val outlineScale: Float = 1f,
    override val opacity: Float = 1f,
) : CanvasObject {
    override fun withTransform(t: ObjectTransform) = copy(transform = t)
    override fun withOpacity(o: Float) = copy(opacity = o.coerceIn(0f, 1f))
    override fun withId(newId: String) = copy(id = newId)

    val isVector: Boolean get() = source.startsWith("builtin:")
}

// ------------------------------------------------------------------ document operations

fun Document.objectById(id: String): CanvasObject? = objects.firstOrNull { it.id == id }

fun Document.addObject(obj: CanvasObject, now: Long): Document = copy(objects = objects + obj, modifiedAt = now)

fun Document.updateObject(obj: CanvasObject, now: Long): Document {
    val i = objects.indexOfFirst { it.id == obj.id }
    require(i >= 0) { "Unknown object ${obj.id}" }
    return copy(objects = objects.toMutableList().also { it[i] = obj }, modifiedAt = now)
}

fun Document.removeObject(id: String, now: Long): Document = copy(objects = objects.filterNot { it.id == id }, modifiedAt = now)

/** Copies the object, offset slightly, right above the original. Returns the new document and id. */
fun Document.duplicateObject(id: String, now: Long, offset: Float = 0.04f): Pair<Document, String> {
    val i = objects.indexOfFirst { it.id == id }
    require(i >= 0) { "Unknown object $id" }
    val src = objects[i]
    val newId = newId()
    val copy = src.withId(newId).withTransform(src.transform.moved(offset, offset))
    val list = objects.toMutableList().also { it.add(i + 1, copy) }
    return copy(objects = list, modifiedAt = now) to newId
}

/** Z-order: list order is bottom → top. */
fun Document.arrangeObject(id: String, how: Arrange, now: Long): Document {
    val i = objects.indexOfFirst { it.id == id }
    require(i >= 0) { "Unknown object $id" }
    val list = objects.toMutableList()
    val o = list.removeAt(i)
    val target = when (how) {
        Arrange.FRONT -> list.size
        Arrange.BACK -> 0
        Arrange.FORWARD -> minOf(list.size, i + 1)
        Arrange.BACKWARD -> maxOf(0, i - 1)
    }
    list.add(target, o)
    return copy(objects = list, modifiedAt = now)
}

/** Asset files (fonts, imported stickers) referenced by objects; kept in the project archive. */
fun Document.referencedAssets(): Set<String> = objects.flatMap { o ->
    when (o) {
        is TextObject -> listOfNotNull(o.style.font.assetName())
        is BalloonObject -> listOfNotNull(o.style.font.assetName())
        is StickerObject -> listOfNotNull(o.source.assetName())
    }
}.toSet()

private fun String.assetName(): String? = if (startsWith("asset:")) removePrefix("asset:") else null

fun newObjectId(): String = newId()
