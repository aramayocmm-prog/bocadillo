package app.bocadillo.core.model

/**
 * Declarative description of one control of a tool. The UI builds sliders, switches and pickers
 * from these specs, so adding a tool never requires touching the editor screens.
 *
 * [labelKey] is a stable key; the app maps it to a localized string resource.
 */
sealed interface ParamSpec {
    val key: String
    val labelKey: String

    data class Slider(
        override val key: String,
        override val labelKey: String,
        val min: Float,
        val max: Float,
        val step: Float,
        val default: Float,
        val unit: Unit = Unit.NONE,
        /** Value is displayed multiplied by this factor (e.g. 100 to show 0..1 as %). */
        val displayFactor: Float = 1f,
    ) : ParamSpec {
        init {
            require(min < max) { "$key: min must be < max" }
            require(default in min..max) { "$key: default $default outside [$min,$max]" }
            require(step > 0f) { "$key: step must be > 0" }
        }

        fun clamp(v: Float): Float = v.coerceIn(min, max)

        /** Snaps to the nearest step, measured from [min]. */
        fun snap(v: Float): Float {
            val steps = Math.round((clamp(v) - min) / step)
            return clamp(min + steps * step)
        }
    }

    data class Toggle(
        override val key: String,
        override val labelKey: String,
        val default: Boolean,
    ) : ParamSpec

    data class Choice(
        override val key: String,
        override val labelKey: String,
        val options: List<String>,
        val default: String,
    ) : ParamSpec {
        init { require(default in options) { "$key: default not in options" } }
    }

    data class Color(
        override val key: String,
        override val labelKey: String,
        val default: Int,
    ) : ParamSpec

    /** Normalized point (0..1, 0..1) the user places on the canvas, e.g. effect centers. */
    data class Point(
        override val key: String,
        override val labelKey: String,
        val defaultX: Float = 0.5f,
        val defaultY: Float = 0.5f,
    ) : ParamSpec

    /**
     * List of numbers edited by a dedicated control (curve points, warp strokes, red-eye
     * circles); not shown by the generic panel.
     */
    data class Data(
        override val key: String,
        override val labelKey: String = key,
        val default: List<Float> = emptyList(),
    ) : ParamSpec

    enum class Unit { NONE, PERCENT, DEGREES, PIXELS }
}

/** Returns params with every spec default filled in for keys not already present. */
fun List<ParamSpec>.defaults(base: Params = Params.EMPTY): Params {
    var p = base
    for (spec in this) {
        if (spec.key in p.keys) continue
        p = when (spec) {
            is ParamSpec.Slider -> p.with(spec.key, spec.default)
            is ParamSpec.Toggle -> p.with(spec.key, spec.default)
            is ParamSpec.Choice -> p.with(spec.key, spec.default)
            is ParamSpec.Color -> p.withColor(spec.key, spec.default)
            is ParamSpec.Point -> p.with(spec.key, floatArrayOf(spec.defaultX, spec.defaultY))
            is ParamSpec.Data -> p.with(spec.key, spec.default.toFloatArray())
        }
    }
    return p
}
