package app.bocadillo.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** JSON mapping of overlay objects. Colours are stored as unsigned 32-bit numbers. */
internal object ObjectJson {

    fun encode(o: CanvasObject): JsonObject = buildJsonObject {
        put("id", o.id)
        put("transform", transform(o.transform))
        put("opacity", o.opacity)
        when (o) {
            is TextObject -> {
                put("type", "text"); put("text", o.text); put("style", style(o.style)); put("curve", o.curve)
            }
            is BalloonObject -> {
                put("type", "balloon"); put("shape", o.shape.name)
                put("width", o.width); put("height", o.height)
                put("tailX", o.tailX); put("tailY", o.tailY); put("tailWidth", o.tailWidth); put("hasTail", o.hasTail)
                putColor("fillColor", o.fillColor); putColor("strokeColor", o.strokeColor); put("strokeWidth", o.strokeWidth)
                if (o.fillColor2 != 0) putColor("fillColor2", o.fillColor2)
                if (o.shadowColor != 0) putColor("shadowColor", o.shadowColor)
                put("text", o.text); put("style", style(o.style))
            }
            is StickerObject -> {
                put("type", "sticker"); put("source", o.source); put("size", o.size); put("aspect", o.aspect)
                put("outlineScale", o.outlineScale)
                put("colors", buildJsonArray { o.colors.forEach { add(JsonPrimitive(it.toLong() and 0xffffffffL)) } })
                o.vectorStyle?.let { v ->
                    put("vectorStyle", buildJsonObject {
                        putColor("fill", v.fill); putColor("stroke", v.stroke); put("strokeWidth", v.strokeWidth); put("roundCap", v.roundCap)
                    })
                }
            }
        }
    }

    fun decode(j: JsonObject): CanvasObject {
        val id = j.s("id")
        val t = j["transform"]?.jsonObject?.let(::transform) ?: ObjectTransform()
        val opacity = j.f("opacity", 1f)
        return when (val type = j.s("type")) {
            "text" -> TextObject(id, t, j.s("text"), style(j["style"]?.jsonObject), opacity, j.f("curve", 0f))
            "balloon" -> {
                val d = BalloonObject(id, text = "")
                BalloonObject(
                    id = id, transform = t,
                    shape = runCatching { BalloonShape.valueOf(j.s("shape")) }.getOrDefault(BalloonShape.SPEECH),
                    width = j.f("width", d.width), height = j.f("height", d.height),
                    tailX = j.f("tailX", d.tailX), tailY = j.f("tailY", d.tailY), tailWidth = j.f("tailWidth", d.tailWidth),
                    hasTail = j["hasTail"]?.jsonPrimitive?.booleanOrNull ?: true,
                    fillColor = j.color("fillColor", d.fillColor), strokeColor = j.color("strokeColor", d.strokeColor),
                    strokeWidth = j.f("strokeWidth", d.strokeWidth),
                    fillColor2 = j.color("fillColor2", 0), shadowColor = j.color("shadowColor", 0),
                    text = j["text"]?.jsonPrimitive?.content ?: "",
                    style = j["style"]?.jsonObject?.let { style(it) } ?: d.style,
                    opacity = opacity,
                )
            }
            "sticker" -> StickerObject(
                id = id, transform = t, source = j.s("source"),
                size = j.f("size", 0.35f), aspect = j.f("aspect", 1f),
                colors = j["colors"]?.jsonArray?.map { it.jsonPrimitive.long.toInt() } ?: emptyList(),
                outlineScale = j.f("outlineScale", 1f), opacity = opacity,
                vectorStyle = j["vectorStyle"]?.jsonObject?.let { v ->
                    VectorStyle(v.color("fill", 0), v.color("stroke", 0), v.f("strokeWidth", 0f), v["roundCap"]?.jsonPrimitive?.booleanOrNull ?: true)
                },
            )
            else -> throw ProjectFormatException("Unknown object type '$type'")
        }
    }

    private fun transform(t: ObjectTransform) = buildJsonObject {
        put("cx", t.cx); put("cy", t.cy); put("scale", t.scale)
        put("stretchX", t.stretchX); put("stretchY", t.stretchY); put("rotation", t.rotation)
        put("flipH", t.flipH); put("flipV", t.flipV)
        // written only when used, so projects without them stay byte-identical to 0.8
        if (t.skewX != 0f) put("skewX", t.skewX)
        if (t.skewY != 0f) put("skewY", t.skewY)
        if (t.hasPerspective) put("perspective", buildJsonArray { t.perspective.forEach { add(JsonPrimitive(it)) } })
    }

    private fun transform(j: JsonObject) = ObjectTransform(
        cx = j.f("cx", 0.5f), cy = j.f("cy", 0.5f), scale = j.f("scale", 1f),
        stretchX = j.f("stretchX", 1f), stretchY = j.f("stretchY", 1f), rotation = j.f("rotation", 0f),
        flipH = j["flipH"]?.jsonPrimitive?.boolean ?: false, flipV = j["flipV"]?.jsonPrimitive?.boolean ?: false,
        skewX = j.f("skewX", 0f), skewY = j.f("skewY", 0f),
        perspective = j["perspective"]?.jsonArray?.mapNotNull { it.jsonPrimitive.floatOrNull }?.takeIf { it.size == 8 } ?: emptyList(),
    )

    private fun style(s: TextStyle) = buildJsonObject {
        put("font", s.font); put("size", s.size); putColor("color", s.color)
        put("bold", s.bold); put("italic", s.italic); put("align", s.align.name)
        put("lineHeight", s.lineHeight); put("letterSpacing", s.letterSpacing)
        putColor("outlineColor", s.outlineColor); put("outlineWidth", s.outlineWidth)
        putColor("shadowColor", s.shadowColor); put("shadowDx", s.shadowDx); put("shadowDy", s.shadowDy); put("shadowBlur", s.shadowBlur)
        putColor("backgroundColor", s.backgroundColor)
        if (s.gradientColor != 0) putColor("gradientColor", s.gradientColor)
        if (s.outline2Width > 0f) { putColor("outline2Color", s.outline2Color); put("outline2Width", s.outline2Width) }
    }

    private fun style(j: JsonObject?): TextStyle {
        val d = TextStyle()
        if (j == null) return d
        return TextStyle(
            font = j["font"]?.jsonPrimitive?.content ?: d.font,
            size = j.f("size", d.size), color = j.color("color", d.color),
            bold = j["bold"]?.jsonPrimitive?.booleanOrNull ?: false, italic = j["italic"]?.jsonPrimitive?.booleanOrNull ?: false,
            align = runCatching { TextAlign.valueOf(j.s("align")) }.getOrDefault(d.align),
            lineHeight = j.f("lineHeight", d.lineHeight), letterSpacing = j.f("letterSpacing", d.letterSpacing),
            outlineColor = j.color("outlineColor", d.outlineColor), outlineWidth = j.f("outlineWidth", d.outlineWidth),
            shadowColor = j.color("shadowColor", d.shadowColor), shadowDx = j.f("shadowDx", d.shadowDx),
            shadowDy = j.f("shadowDy", d.shadowDy), shadowBlur = j.f("shadowBlur", d.shadowBlur),
            backgroundColor = j.color("backgroundColor", d.backgroundColor),
            gradientColor = j.color("gradientColor", 0),
            outline2Color = j.color("outline2Color", 0), outline2Width = j.f("outline2Width", 0f),
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putColor(key: String, c: Int) = put(key, c.toLong() and 0xffffffffL)
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content ?: throw ProjectFormatException("Missing '$k'")
    private fun JsonObject.f(k: String, d: Float) = this[k]?.jsonPrimitive?.floatOrNull ?: d
    private fun JsonObject.color(k: String, d: Int) = this[k]?.jsonPrimitive?.long?.toInt() ?: d

    fun encodeAll(list: List<CanvasObject>) = JsonArray(list.map(::encode))
}
