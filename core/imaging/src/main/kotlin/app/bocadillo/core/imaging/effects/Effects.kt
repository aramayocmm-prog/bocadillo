package app.bocadillo.core.imaging.effects

/** Every effect of this stage, in menu order within each family. */
object Effects {
    val all = PhotoOps.adjust + ColorArtOps.AdjustRgb + DistortOps.all + ColorArtOps.color + ColorArtOps.artistic +
        PhotoOps.photo + FrameOps.all + ColorArtOps.misc + ColorArtOps.MakeTransparent
}
