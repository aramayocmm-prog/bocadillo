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
        "boost" -> R.string.op_boost
        "sharpen" -> R.string.op_sharpen
        "smooth" -> R.string.op_smooth
        "blur" -> R.string.op_blur
        "adjust_rgb" -> R.string.op_adjust_rgb
        "spherize" -> R.string.op_spherize
        "twirl" -> R.string.op_twirl
        "bulge" -> R.string.op_bulge
        "circle_splash" -> R.string.op_circle_splash
        "stretch" -> R.string.op_stretch
        "stretch_v" -> R.string.op_stretch_v
        "stretch_h" -> R.string.op_stretch_h
        "mirror" -> R.string.op_mirror
        "dizziness" -> R.string.op_dizziness
        "pixelize" -> R.string.op_pixelize
        "grayscale" -> R.string.op_grayscale
        "black_white" -> R.string.op_black_white
        "duotone" -> R.string.op_duotone
        "rainbow" -> R.string.op_rainbow
        "blend_color" -> R.string.op_blend_color
        "comic_print" -> R.string.op_comic_print
        "posterize" -> R.string.op_posterize
        "pop_art" -> R.string.op_pop_art
        "pencil_sketch" -> R.string.op_pencil_sketch
        "neon" -> R.string.op_neon
        "lomo" -> R.string.op_lomo
        "cross_process" -> R.string.op_cross_process
        "gritty" -> R.string.op_gritty
        "fading_colors" -> R.string.op_fading_colors
        "film_grain" -> R.string.op_film_grain
        "red_scale" -> R.string.op_red_scale
        "faux_hdr" -> R.string.op_faux_hdr
        "tilt_shift" -> R.string.op_tilt_shift
        "soft_glow" -> R.string.op_soft_glow
        "vignette" -> R.string.op_vignette
        "focal_blur" -> R.string.op_focal_blur
        "top_lens" -> R.string.op_top_lens
        "edge_lens" -> R.string.op_edge_lens
        "spotlight" -> R.string.op_spotlight
        "instant" -> R.string.op_instant
        "film" -> R.string.op_film
        "rounded_corners" -> R.string.op_rounded_corners
        "solid_border" -> R.string.op_solid_border
        "matte" -> R.string.op_matte
        "x_ray" -> R.string.op_x_ray
        "heat_map" -> R.string.op_heat_map
        "make_transparent" -> R.string.op_make_transparent
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
        "background" -> R.string.p_background
        "band_size" -> R.string.p_band_size
        "blend_mode" -> R.string.p_blend_mode
        "block_size" -> R.string.p_block_size
        "blue" -> R.string.p_blue
        "blur" -> R.string.p_blur
        "border_width" -> R.string.p_border_width
        "color" -> R.string.p_color
        "color_dark" -> R.string.p_color_dark
        "color_light" -> R.string.p_color_light
        "darkness" -> R.string.p_darkness
        "dot_size" -> R.string.p_dot_size
        "falloff" -> R.string.p_falloff
        "filter_hue" -> R.string.p_filter_hue
        "filter_strength" -> R.string.p_filter_strength
        "green" -> R.string.p_green
        "levels" -> R.string.p_levels
        "matte_size" -> R.string.p_matte_size
        "radius" -> R.string.p_radius
        "red" -> R.string.p_red
        "right_to_left" -> R.string.p_right_to_left
        "size" -> R.string.p_size
        "threshold" -> R.string.p_threshold
        "vignette" -> R.string.p_vignette
        "waves" -> R.string.p_waves
        "center" -> R.string.p_center
        else -> R.string.p_amount
    }

    /** Option of a [app.bocadillo.core.model.ParamSpec.Choice]; null = show the raw value. */
    @StringRes
    fun choice(option: String): Int? = when (option) {
        "normal" -> R.string.c_normal
        "multiply" -> R.string.c_multiply
        "screen" -> R.string.c_screen
        "overlay" -> R.string.c_overlay
        "soft_light" -> R.string.c_soft_light
        "color" -> R.string.c_color
        "clear" -> R.string.c_clear
        else -> null
    }

    @StringRes
    fun category(c: app.bocadillo.core.imaging.ops.OpCategory): Int = when (c) {
        app.bocadillo.core.imaging.ops.OpCategory.DISTORT -> R.string.cat_distort
        app.bocadillo.core.imaging.ops.OpCategory.ARTISTIC -> R.string.cat_artistic
        app.bocadillo.core.imaging.ops.OpCategory.PHOTO -> R.string.cat_photo
        app.bocadillo.core.imaging.ops.OpCategory.FRAMES -> R.string.cat_frames
        app.bocadillo.core.imaging.ops.OpCategory.MISC -> R.string.cat_misc
        app.bocadillo.core.imaging.ops.OpCategory.ADJUST -> R.string.menu_adjust
        app.bocadillo.core.imaging.ops.OpCategory.COLOR -> R.string.menu_color
        app.bocadillo.core.imaging.ops.OpCategory.TRANSFORM -> R.string.menu_transform
        app.bocadillo.core.imaging.ops.OpCategory.DRAW -> R.string.menu_draw
        app.bocadillo.core.imaging.ops.OpCategory.MODERN -> R.string.menu_more
    }
}
