package app.bocadillo.core.model

/**
 * Immutable bag of tool parameters. Values are restricted to JSON-friendly types so that a
 * document can always be serialized: [Double], [Boolean], [String] and [List] of [Double].
 */
class Params private constructor(private val values: Map<String, Any>) {

    val keys: Set<String> get() = values.keys

    fun raw(key: String): Any? = values[key]

    fun float(key: String, default: Float): Float = (values[key] as? Double)?.toFloat() ?: default
    fun double(key: String, default: Double): Double = (values[key] as? Double) ?: default
    fun int(key: String, default: Int): Int = (values[key] as? Double)?.toInt() ?: default
    fun bool(key: String, default: Boolean): Boolean = (values[key] as? Boolean) ?: default
    fun string(key: String, default: String): String = (values[key] as? String) ?: default

    /** ARGB color stored as a number. */
    fun color(key: String, default: Int): Int = (values[key] as? Double)?.toLong()?.toInt() ?: default

    @Suppress("UNCHECKED_CAST")
    fun floats(key: String): FloatArray? =
        (values[key] as? List<Double>)?.let { list -> FloatArray(list.size) { list[it].toFloat() } }

    fun with(key: String, value: Number): Params = Params(values + (key to value.toDouble()))
    fun with(key: String, value: Boolean): Params = Params(values + (key to value))
    fun with(key: String, value: String): Params = Params(values + (key to value))
    fun withColor(key: String, argb: Int): Params = Params(values + (key to (argb.toLong() and 0xffffffffL).toDouble()))
    fun with(key: String, value: FloatArray): Params = Params(values + (key to value.map { it.toDouble() }))
    fun without(key: String): Params = Params(values - key)

    fun asMap(): Map<String, Any> = values

    override fun equals(other: Any?): Boolean = other is Params && other.values == values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "Params$values"

    companion object {
        val EMPTY = Params(emptyMap())

        /** Builds params from raw JSON-like values, normalizing all numbers to [Double]. */
        fun of(raw: Map<String, Any>): Params {
            val normalized = raw.mapValues { (k, v) ->
                when (v) {
                    is Number -> v.toDouble()
                    is Boolean, is String -> v
                    is List<*> -> v.map { (it as? Number)?.toDouble() ?: error("Param $k: list must contain numbers") }
                    is FloatArray -> v.map { it.toDouble() }
                    else -> error("Unsupported param type for $k: ${v::class}")
                }
            }
            return Params(normalized)
        }

        fun of(vararg pairs: Pair<String, Any>): Params = of(pairs.toMap())
    }
}
