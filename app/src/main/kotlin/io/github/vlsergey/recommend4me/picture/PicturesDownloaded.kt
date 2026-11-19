package io.github.vlsergey.recommend4me.picture

/** Pictures of the works [ids] of the [source] downloaded and kept on disk: to be analysed ([PictureVectors]). */
data class PicturesDownloaded(val source: String, val ids: List<String>)
