package app.bocadillo.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Gallery entry for one project stored in app-private storage. */
data class ProjectEntry(
    val id: String,
    val title: String,
    val modifiedAt: Long,
    val favorite: Boolean = false,
)

/**
 * Immutable list of known projects (recents + favorites). Persisted as JSON by the app.
 * Unlike the Lite original there is no 8-project limit.
 */
data class ProjectIndex(val entries: List<ProjectEntry> = emptyList()) {

    /** Most recently modified first. */
    fun recent(): List<ProjectEntry> = entries.sortedByDescending { it.modifiedAt }
    fun favorites(): List<ProjectEntry> = recent().filter { it.favorite }
    fun find(id: String): ProjectEntry? = entries.firstOrNull { it.id == id }

    fun upsert(entry: ProjectEntry): ProjectIndex {
        val old = find(entry.id)
        val merged = if (old != null) entry.copy(favorite = old.favorite || entry.favorite) else entry
        return ProjectIndex(entries.filterNot { it.id == entry.id } + merged)
    }

    fun setFavorite(id: String, favorite: Boolean): ProjectIndex =
        ProjectIndex(entries.map { if (it.id == id) it.copy(favorite = favorite) else it })

    fun remove(id: String): ProjectIndex = ProjectIndex(entries.filterNot { it.id == id })

    fun encode(): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("version", 1)
        put("projects", buildJsonArray {
            for (e in entries) add(buildJsonObject {
                put("id", e.id); put("title", e.title); put("modifiedAt", e.modifiedAt); put("favorite", e.favorite)
            })
        })
    })

    companion object {
        fun decode(text: String): ProjectIndex = try {
            val o = Json.parseToJsonElement(text).jsonObject
            ProjectIndex(o["projects"]!!.jsonArray.map { el ->
                val p = el.jsonObject
                ProjectEntry(
                    id = p["id"]!!.jsonPrimitive.content,
                    title = p["title"]?.jsonPrimitive?.content ?: "",
                    modifiedAt = p["modifiedAt"]?.jsonPrimitive?.long ?: 0L,
                    favorite = p["favorite"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            })
        } catch (e: Exception) {
            throw ProjectFormatException("Invalid project index: ${e.message}", e)
        }
    }
}
