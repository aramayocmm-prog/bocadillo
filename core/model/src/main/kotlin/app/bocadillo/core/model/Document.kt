package app.bocadillo.core.model

import java.util.UUID

/** Information about the original picture stored unmodified inside the project. */
data class SourceInfo(
    /** File name inside the project archive, e.g. "source.jpg". */
    val fileName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
) {
    init { require(width > 0 && height > 0) { "Invalid source size ${width}x$height" } }
}

/**
 * One raster operation applied to the picture (transform, adjustment or effect).
 * Operations are replayed in order on top of the original source, which keeps every edit
 * reversible and parameter-editable.
 */
data class OpInstance(
    val uid: String,
    val opId: String,
    val params: Params,
    /** Optional mask limiting where the effect applies (see [MaskRef]). */
    val maskId: String? = null,
    /** When true the mask is used inverted. */
    val maskInverted: Boolean = false,
) {
    companion object {
        fun create(opId: String, params: Params, maskId: String? = null): OpInstance =
            OpInstance(newId(), opId, params, maskId)
    }
}

/** Reference to a mask bitmap stored in the archive under masks/<id>.mask. */
data class MaskRef(val id: String, val width: Int, val height: Int)

/**
 * An editable project: the untouched source picture, the ordered raster operations, the overlay
 * objects (titles, balloons, stickers) and the masks used by operations.
 */
data class Document(
    val id: String,
    val title: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val source: SourceInfo,
    val ops: List<OpInstance> = emptyList(),
    val objects: List<CanvasObject> = emptyList(),
    val masks: List<MaskRef> = emptyList(),
) {
    fun withOp(op: OpInstance, now: Long): Document = copy(ops = ops + op, modifiedAt = now)

    fun replaceOp(op: OpInstance, now: Long): Document {
        val index = ops.indexOfFirst { it.uid == op.uid }
        require(index >= 0) { "Unknown op ${op.uid}" }
        return copy(ops = ops.toMutableList().also { it[index] = op }, modifiedAt = now)
    }

    fun removeOp(uid: String, now: Long): Document = copy(ops = ops.filterNot { it.uid == uid }, modifiedAt = now)

    /** Back to the opened picture: removes every raster op, overlay object and mask. */
    fun resetToOriginal(now: Long): Document =
        copy(ops = emptyList(), objects = emptyList(), masks = emptyList(), modifiedAt = now)

    fun withMask(mask: MaskRef, now: Long): Document =
        copy(masks = masks.filterNot { it.id == mask.id } + mask, modifiedAt = now)

    /** Masks referenced by ops; used to garbage-collect mask files when saving. */
    fun referencedMaskIds(): Set<String> = ops.mapNotNull { it.maskId }.toSet()

    companion object {
        const val FORMAT_VERSION = 1

        fun create(title: String, source: SourceInfo, now: Long): Document =
            Document(id = newId(), title = title, createdAt = now, modifiedAt = now, source = source)
    }
}

/**
 * Overlay objects drawn above the picture. Stage 3 adds the concrete kinds (text, balloon,
 * sticker); the document format already stores the list so projects stay forward compatible.
 */
sealed interface CanvasObject {
    val id: String
}

internal fun newId(): String = UUID.randomUUID().toString()
