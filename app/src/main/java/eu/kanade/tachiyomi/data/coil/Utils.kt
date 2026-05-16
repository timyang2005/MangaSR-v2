package eu.kanade.tachiyomi.data.coil

import coil3.Extras
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import coil3.size.isOriginal
import coil3.size.pxOrElse

internal inline fun Size.widthPx(scale: Scale, original: () -> Int): Int {
    return if (isOriginal) original() else width.toPx(scale)
}

internal inline fun Size.heightPx(scale: Scale, original: () -> Int): Int {
    return if (isOriginal) original() else height.toPx(scale)
}

internal fun Dimension.toPx(scale: Scale): Int = pxOrElse {
    when (scale) {
        Scale.FILL -> Int.MIN_VALUE
        Scale.FIT -> Int.MAX_VALUE
    }
}

internal val superResolutionKey = Extras.Key(default = false)

internal val pageIndexKey = Extras.Key(default = -1)

internal val chapterIdKey = Extras.Key(default = -1L)

fun ImageRequest.Builder.cropBorders(enable: Boolean) = apply {
    extras[cropBordersKey] = enable
}

val Options.cropBorders: Boolean
    get() = getExtra(cropBordersKey)

private val cropBordersKey = Extras.Key(default = false)

fun ImageRequest.Builder.customDecoder(enable: Boolean) = apply {
    extras[customDecoderKey] = enable
}

val Options.customDecoder: Boolean
    get() = getExtra(customDecoderKey)

private val customDecoderKey = Extras.Key(default = false)

fun ImageRequest.Builder.superResolution(enable: Boolean) = apply {
    extras[superResolutionKey] = enable
}

fun ImageRequest.Builder.pageIndex(index: Int) = apply {
    extras[pageIndexKey] = index
}

fun ImageRequest.Builder.chapterId(id: Long) = apply {
    extras[chapterIdKey] = id
}

val ImageRequest.superResolution: Boolean
    get() = extras[superResolutionKey] ?: false

val ImageRequest.pageIndex: Int
    get() = extras[pageIndexKey] ?: -1

val ImageRequest.chapterId: Long
    get() = extras[chapterIdKey] ?: -1L

val Options.superResolution: Boolean
    get() = getExtra(superResolutionKey)

val Options.pageIndex: Int
    get() = getExtra(pageIndexKey)

val Options.chapterId: Long
    get() = getExtra(chapterIdKey)
