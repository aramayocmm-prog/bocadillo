package app.bocadillo.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class ProjectFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Hand-written JSON mapping for [Document]. It is explicit (no reflection / compiler plugin) so
 * the on-disk format is stable and reviewable. Unknown keys are ignored for forward compatibility.
 */
object DocumentJson {

    private val json = Json { prettyPrint = true }

    fun encode(doc: Document): String = json.encodeToString(JsonObject.serializer(), toJson(doc))

    fun decode(text: String): Document = try {
        fromJson(json.parseToJsonElement(text).jsonObject)
    } catch (e: ProjectFormatException) {
        throw e
    } catch (e: Exception) {
        throw ProjectFormatException("Invalid document JSON: ${e.message}", e)
    }

    fun toJson(doc: Document): JsonObject = buildJsonObject {
        put("formatVersion", Document.FORMAT_VERSION)
        put("id", doc.id)
        put("title", doc.title)
        put("createdAt", doc.createdAt)
        put("modifiedAt", doc.modifiedAt)
        put("source", buildJsonObject {
            put("fileName", doc.source.fileName)
            put("mimeType", doc.source.mimeType)
            put("width", doc.source.width)
            put("height", doc.source.height)
            put("hasAlpha", doc.source.hasAlpha)
        })
        put("ops", buildJsonArray {
            for (op in doc.ops) add(buildJsonObject {
                put("uid", op.uid)
                put("opId", op.opId)
                put("params", paramsToJson(op.params))
                op.maskId?.let { put("maskId", it) }
                if (op.maskInverted) put("maskInverted", true)
            })
        })
        put("objects", JsonArray(emptyList()))
        put("masks", buildJsonArray {
            for (m in doc.masks) add(buildJsonObject {
                put("id", m.id); put("width", m.width); put("height", m.height)
            })
        })
    }

    fun fromJson(o: JsonObject): Document {
        val version = o["formatVersion"]?.jsonPrimitive?.int ?: throw ProjectFormatException("Missing formatVersion")
        if (version > Document.FORMAT_VERSION) {
            throw ProjectFormatException("Project format $version is newer than supported ${Document.FORMAT_VERSION}")
        }
        val src = o.req("source").jsonObject
        val objects = o["objects"]?.jsonArray ?: JsonArray(emptyList())
        if (objects.isNotEmpty()) throw ProjectFormatException("Overlay objects are not supported by this version")
        return Document(
            id = o.str("id"),
            title = o.str("title"),
            createdAt = o.req("createdAt").jsonPrimitive.long,
            modifiedAt = o.req("modifiedAt").jsonPrimitive.long,
            source = SourceInfo(
                fileName = src.str("fileName"),
                mimeType = src.str("mimeType"),
                width = src.req("width").jsonPrimitive.int,
                height = src.req("height").jsonPrimitive.int,
                hasAlpha = src["hasAlpha"]?.jsonPrimitive?.booleanOrNull ?: false,
            ),
            ops = (o["ops"]?.jsonArray ?: JsonArray(emptyList())).map { e ->
                val op = e.jsonObject
                OpInstance(
                    uid = op.str("uid"),
                    opId = op.str("opId"),
                    params = paramsFromJson(op["params"]?.jsonObject ?: JsonObject(emptyMap())),
                    maskId = op["maskId"]?.jsonPrimitive?.content,
                    maskInverted = op["maskInverted"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            },
            masks = (o["masks"]?.jsonArray ?: JsonArray(emptyList())).map { e ->
                val m = e.jsonObject
                MaskRef(m.str("id"), m.req("width").jsonPrimitive.int, m.req("height").jsonPrimitive.int)
            },
        )
    }

    fun paramsToJson(p: Params): JsonObject = JsonObject(p.asMap().mapValues { (_, v) -> valueToJson(v) })

    fun paramsFromJson(o: JsonObject): Params = Params.of(o.mapValues { (k, v) -> valueFromJson(k, v) })

    private fun valueToJson(v: Any): JsonElement = when (v) {
        is Double -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { JsonPrimitive(it as Double) })
        else -> error("Unsupported param value $v")
    }

    private fun valueFromJson(key: String, e: JsonElement): Any = when (e) {
        is JsonArray -> e.map { it.jsonPrimitive.doubleOrNull ?: throw ProjectFormatException("Param $key: non-numeric list") }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull!!
            e.doubleOrNull != null -> e.doubleOrNull!!
            else -> throw ProjectFormatException("Param $key: unsupported value")
        }
        JsonNull -> throw ProjectFormatException("Param $key is null")
        is JsonObject -> throw ProjectFormatException("Param $key: objects not allowed")
    }

    private fun JsonObject.req(key: String): JsonElement = this[key] ?: throw ProjectFormatException("Missing '$key'")
    private fun JsonObject.str(key: String): String = req(key).jsonPrimitive.content
}
