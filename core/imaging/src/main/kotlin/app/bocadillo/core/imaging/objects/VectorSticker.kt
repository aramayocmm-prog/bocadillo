package app.bocadillo.core.imaging.objects

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Path command in absolute coordinates (the parser normalizes relative/shorthand commands). */
sealed interface PathCmd {
    data class Move(val x: Float, val y: Float) : PathCmd
    data class Line(val x: Float, val y: Float) : PathCmd
    data class Quad(val x1: Float, val y1: Float, val x: Float, val y: Float) : PathCmd
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathCmd
    data object Close : PathCmd
}

/**
 * Parser for the SVG path-data subset used by the sticker catalogue: M L H V C S Q T Z,
 * absolute and relative. Arcs (A) are not supported (the catalogue generator emits beziers).
 */
object PathData {
    fun parse(d: String): List<PathCmd> {
        val tokens = Regex("[MmLlHhVvCcSsQqTtZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
        val out = ArrayList<PathCmd>()
        var i = 0
        var cmd = ' '
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        var lastCtrlX = 0f; var lastCtrlY = 0f; var lastKind = ' '
        fun num(): Float {
            if (i >= tokens.size) throw IllegalArgumentException("Path ended unexpectedly")
            return tokens[i++].toFloatOrNull() ?: throw IllegalArgumentException("Expected number at token ${i - 1}: '${tokens[i - 1]}'")
        }
        fun isNum() = i < tokens.size && tokens[i][0].let { it.isDigit() || it == '-' || it == '.' }
        while (i < tokens.size) {
            if (!isNum()) { cmd = tokens[i++][0] } else if (cmd == ' ') throw IllegalArgumentException("Path must start with a command")
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val nx = num() + if (rel) x else 0f; val ny = num() + if (rel) y else 0f
                    out += PathCmd.Move(nx, ny); x = nx; y = ny; sx = nx; sy = ny
                    cmd = if (rel) 'l' else 'L' // subsequent pairs are implicit lineto
                    lastKind = 'M'
                }
                'L' -> { val nx = num() + if (rel) x else 0f; val ny = num() + if (rel) y else 0f; out += PathCmd.Line(nx, ny); x = nx; y = ny; lastKind = 'L' }
                'H' -> { val nx = num() + if (rel) x else 0f; out += PathCmd.Line(nx, y); x = nx; lastKind = 'L' }
                'V' -> { val ny = num() + if (rel) y else 0f; out += PathCmd.Line(x, ny); y = ny; lastKind = 'L' }
                'C', 'S' -> {
                    val x1: Float; val y1: Float
                    if (cmd.uppercaseChar() == 'C') { x1 = num() + if (rel) x else 0f; y1 = num() + if (rel) y else 0f }
                    else if (lastKind == 'C') { x1 = 2 * x - lastCtrlX; y1 = 2 * y - lastCtrlY } else { x1 = x; y1 = y }
                    val x2 = num() + if (rel) x else 0f; val y2 = num() + if (rel) y else 0f
                    val nx = num() + if (rel) x else 0f; val ny = num() + if (rel) y else 0f
                    out += PathCmd.Cubic(x1, y1, x2, y2, nx, ny); lastCtrlX = x2; lastCtrlY = y2; x = nx; y = ny; lastKind = 'C'
                }
                'Q', 'T' -> {
                    val x1: Float; val y1: Float
                    if (cmd.uppercaseChar() == 'Q') { x1 = num() + if (rel) x else 0f; y1 = num() + if (rel) y else 0f }
                    else if (lastKind == 'Q') { x1 = 2 * x - lastCtrlX; y1 = 2 * y - lastCtrlY } else { x1 = x; y1 = y }
                    val nx = num() + if (rel) x else 0f; val ny = num() + if (rel) y else 0f
                    out += PathCmd.Quad(x1, y1, nx, ny); lastCtrlX = x1; lastCtrlY = y1; x = nx; y = ny; lastKind = 'Q'
                }
                'Z' -> { out += PathCmd.Close; x = sx; y = sy; lastKind = 'Z'; cmd = ' ' ; if (isNum()) throw IllegalArgumentException("Numbers after Z") }
                else -> throw IllegalArgumentException("Unsupported path command '$cmd'")
            }
        }
        return out
    }
}

/** Paint reference of a sticker layer: none, a recolourable slot, or a fixed colour. */
sealed interface PaintRef {
    data object None : PaintRef
    data class Slot(val index: Int) : PaintRef
    data class Fixed(val argb: Int) : PaintRef
}

class StickerLayer(val path: List<PathCmd>, val fill: PaintRef, val stroke: PaintRef, val strokeWidth: Float, val evenOdd: Boolean)

class StickerDef(
    val id: String,
    val width: Float,
    val height: Float,
    /** Default colour of each slot; users can change them per sticker ("Set Shape Style"). */
    val slots: List<Int>,
    val layers: List<StickerLayer>,
) {
    val aspect: Float get() = width / height

    fun resolve(ref: PaintRef, overrides: List<Int>): Int? = when (ref) {
        PaintRef.None -> null
        is PaintRef.Fixed -> ref.argb
        is PaintRef.Slot -> overrides.getOrNull(ref.index) ?: slots.getOrNull(ref.index)
    }
}

class StickerCategory(val id: String, val names: Map<String, String>, val stickers: List<StickerDef>) {
    fun name(lang: String): String = names[lang] ?: names["en"] ?: id
}

/** Catalogue JSON (assets/stickers/catalog.json), produced by tools/stickers/gen_stickers.py. */
class StickerCatalog(val categories: List<StickerCategory>) {
    private val byId = categories.flatMap { it.stickers }.associateBy { it.id }
    operator fun get(id: String): StickerDef? = byId[id]
    val size: Int get() = byId.size

    companion object {
        fun parse(json: String): StickerCatalog {
            val root = Json.parseToJsonElement(json).jsonObject
            val cats = root["categories"]!!.jsonArray.map { c ->
                val co = c.jsonObject
                StickerCategory(
                    id = co["id"]!!.jsonPrimitive.content,
                    names = co["names"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
                    stickers = co["stickers"]!!.jsonArray.map { s ->
                        val so = s.jsonObject
                        StickerDef(
                            id = so["id"]!!.jsonPrimitive.content,
                            width = so["w"]!!.jsonPrimitive.float,
                            height = so["h"]!!.jsonPrimitive.float,
                            slots = so["slots"]!!.jsonArray.map { parseColor(it.jsonPrimitive.content) },
                            layers = so["layers"]!!.jsonArray.map { l ->
                                val lo = l.jsonObject
                                StickerLayer(
                                    path = PathData.parse(lo["d"]!!.jsonPrimitive.content),
                                    fill = paint(lo["fill"]?.jsonPrimitive),
                                    stroke = paint(lo["stroke"]?.jsonPrimitive),
                                    strokeWidth = lo["sw"]?.jsonPrimitive?.floatOrNull ?: 0f,
                                    evenOdd = lo["evenodd"]?.jsonPrimitive?.content == "true",
                                )
                            },
                        )
                    },
                )
            }
            return StickerCatalog(cats)
        }

        private fun paint(p: JsonPrimitive?): PaintRef = when {
            p == null -> PaintRef.None
            p.intOrNull != null -> if (p.int < 0) PaintRef.None else PaintRef.Slot(p.int)
            else -> PaintRef.Fixed(parseColor(p.content))
        }

        /** "#RRGGBB" or "#AARRGGBB". */
        fun parseColor(s: String): Int {
            val h = s.removePrefix("#")
            return when (h.length) {
                6 -> (0xFF000000 or h.toLong(16)).toInt()
                8 -> h.toLong(16).toInt()
                else -> throw IllegalArgumentException("Bad colour $s")
            }
        }
    }
}
