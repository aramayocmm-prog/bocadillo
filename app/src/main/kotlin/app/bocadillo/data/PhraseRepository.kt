package app.bocadillo.data

import android.content.Context
import app.bocadillo.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Saved common phrases ("Common Phrase" in the original), stored as a JSON list. */
class PhraseRepository(private val context: Context) {
    private val file = File(context.filesDir, "phrases.json")

    fun list(): List<String> = runCatching {
        Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonPrimitive.content }
    }.getOrElse { context.resources.getStringArray(R.array.default_phrases).toList() }

    private fun save(list: List<String>) = file.writeText(JsonArray(list.map { JsonPrimitive(it) }).toString())

    fun add(p: String): List<String> = (list() + p.trim()).distinct().filter { it.isNotEmpty() }.also(::save)
    fun update(index: Int, p: String): List<String> = list().toMutableList().also { if (index in it.indices) it[index] = p.trim() }.filter { it.isNotEmpty() }.also(::save)
    fun remove(index: Int): List<String> = list().toMutableList().also { if (index in it.indices) it.removeAt(index) }.also(::save)
}
