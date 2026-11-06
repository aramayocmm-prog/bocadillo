package app.bocadillo.editor

import androidx.annotation.StringRes
import app.bocadillo.R

/** Maps stable op ids / param label keys from the core modules to localized strings. */
object Labels {
    @StringRes
    fun op(id: String): Int = when (id) {
        "brightness" -> R.string.op_brightness
        "exposure_contrast" -> R.string.op_exposure_contrast
        "saturation" -> R.string.op_saturation
        "temperature_tint" -> R.string.op_temperature_tint
        "hue" -> R.string.op_hue
        "colorize" -> R.string.op_colorize
        "invert" -> R.string.op_invert
        "sepia" -> R.string.op_sepia
        "crop" -> R.string.op_crop
        "resize" -> R.string.op_resize
        "orient" -> R.string.op_orient
        "rotate" -> R.string.op_rotate
        "restore_original" -> R.string.restore_original
        "paint" -> R.string.brush
        "history_paint" -> R.string.history_paint
        "opened" -> R.string.history_opened
        else -> R.string.untitled
    }

    @StringRes
    fun param(labelKey: String): Int = when (labelKey) {
        "brightness" -> R.string.p_brightness
        "exposure" -> R.string.p_exposure
        "contrast" -> R.string.p_contrast
        "saturation" -> R.string.p_saturation
        "temperature" -> R.string.p_temperature
        "tint" -> R.string.p_tint
        "hue" -> R.string.p_hue
        "straighten" -> R.string.p_straighten
        "angle" -> R.string.p_angle
        "width" -> R.string.p_width
        "height" -> R.string.p_height
        "expand_canvas" -> R.string.p_expand_canvas
        "background_color" -> R.string.p_background_color
        else -> R.string.p_amount
    }
}
