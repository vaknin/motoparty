package com.kivan.motoparty.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import androidx.palette.graphics.Target
import androidx.palette.graphics.get
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The colour of the cover behind what is playing on the Ride tab: a soft glow at AMBIENT_ALPHA
 * over the dark surface. Only a backdrop — buttons, the link dot, TALK and LIVE keep the fixed
 * colours of Theme.kt. The rule that picks and tames the colour is plain arithmetic on ARGB ints
 * (AmbientTest); the loading is below it.
 */

/** How strong the glow is at its strongest point. */
const val AMBIENT_ALPHA = 0.25f

/** A swatch greyer than this (HSL saturation) is no colour: the surface stays plain. */
private const val MIN_SATURATION = 0.12f
private const val MAX_SATURATION = 0.70f
private const val MIN_LIGHTNESS = 0.30f
private const val MAX_LIGHTNESS = 0.50f

/**
 * The brightest the tinted surface may get (relative luminance; the plain surface is 0.005). At
 * this, secondary text keeps about 7.6:1 and the red of a warning 3.5:1 — a yellow or a light
 * green cover is darkened until it is under it.
 */
const val AMBIENT_MAX_LUMINANCE = 0.024

/**
 * The tint for a cover, as opaque ARGB, or null when it has no colour to speak of. [swatches] are
 * the palette's colours in order of preference (null = the palette has none of that kind): the
 * first one that is not grey wins. Its saturation is capped and its lightness brought into a
 * middle band, so a near-black cover still glows and a neon one does not shout; then it is
 * darkened until [AMBIENT_ALPHA] of it over [surface] stays under [AMBIENT_MAX_LUMINANCE].
 */
fun ambientTint(swatches: List<Int?>, surface: Int): Int? {
    val hsl = swatches.asSequence().filterNotNull().map(::hsl).firstOrNull { it[1] >= MIN_SATURATION } ?: return null
    val s = hsl[1].coerceAtMost(MAX_SATURATION)
    var l = hsl[2].coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS)
    var argb = hslToArgb(hsl[0], s, l)
    while (luminance(blend(argb, AMBIENT_ALPHA, surface)) > AMBIENT_MAX_LUMINANCE && l > 0.02f) {
        l -= 0.02f
        argb = hslToArgb(hsl[0], s, l)
    }
    return argb
}

/** [top] at [alpha] over the opaque [bottom], per channel, as the screen composites it. */
fun blend(top: Int, alpha: Float, bottom: Int): Int {
    fun ch(shift: Int): Int {
        val t = (top shr shift) and 0xff
        val b = (bottom shr shift) and 0xff
        return (t * alpha + b * (1 - alpha) + 0.5f).toInt()
    }
    return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** WCAG relative luminance of an sRGB colour, 0 (black) … 1 (white). */
fun luminance(argb: Int): Double {
    fun lin(shift: Int): Double {
        val c = ((argb shr shift) and 0xff) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
}

/** WCAG contrast ratio of two colours, 1 … 21. */
fun contrast(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** Hue in degrees, saturation and lightness 0…1. */
private fun hsl(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xff) / 255f
    val g = ((argb shr 8) and 0xff) / 255f
    val b = (argb and 0xff) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val l = (max + min) / 2
    if (d == 0f) return floatArrayOf(0f, 0f, l)
    val h = when (max) {
        r -> ((g - b) / d).mod(6f)
        g -> (b - r) / d + 2
        else -> (r - g) / d + 4
    }
    return floatArrayOf(h * 60, d / (1 - abs(2 * l - 1)), l)
}

private fun hslToArgb(h: Float, s: Float, l: Float): Int {
    val c = (1 - abs(2 * l - 1)) * s
    val x = c * (1 - abs((h / 60).mod(2f) - 1))
    val m = l - c / 2
    val (r, g, b) = when ((h / 60).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun ch(v: Float) = ((v + m) * 255 + 0.5f).toInt().coerceIn(0, 255)
    return (0xff shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** The tint of a decoded cover: vibrant colours first, then muted ones, then whatever dominates. */
internal fun tintOf(bitmap: Bitmap, surface: Int): Int? {
    val p = Palette.from(bitmap).maximumColorCount(16).generate()
    val order = listOf(Target.VIBRANT, Target.DARK_VIBRANT, Target.LIGHT_VIBRANT, Target.MUTED, Target.DARK_MUTED, Target.LIGHT_MUTED)
    return ambientTint(order.map { p[it]?.rgb } + p.dominantSwatch?.rgb, surface)
}

/** Tints by art URL, so a cover is read once: going back to a song, or to the Ride tab, is instant. */
internal object ArtTints {
    /** "This cover has no colour", remembered like a colour. */
    private const val NONE = 0
    private const val EDGE_PX = 96
    private val cache = LruCache<String, Int>(64)

    /** The cover has been read already; its answer may be "no tint". */
    fun known(url: String): Boolean = cache.get(url) != null

    /** The tint of a cover read earlier, or null (not read yet, or it has none). */
    fun cached(url: String): Int? = cache.get(url)?.takeIf { it != NONE }

    fun put(url: String, tint: Int?) {
        cache.put(url, tint ?: NONE)
    }

    fun clear() = cache.evictAll()

    /**
     * Loads [url] small (the palette needs no more than ~100 px) as a software bitmap through
     * Coil — its disk cache already has the cover the screen shows — and reads the palette on a
     * worker thread. Null, and nothing remembered, when the image cannot be loaded: the next
     * time the song is on screen it is tried again.
     */
    suspend fun load(context: Context, url: String, surface: Int): Int? {
        val request = ImageRequest.Builder(context).data(url).size(EDGE_PX).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        return withContext(Dispatchers.Default) {
            runCatching { tintOf(result.image.toBitmap(), surface) }
                .onSuccess { put(url, it) }
                .getOrNull()
        }
    }
}

/**
 * The glow's colour for the cover at [artUrl], already at [AMBIENT_ALPHA]; transparent with no
 * art, while it loads and when it fails. A new cover's colour fades in over the old one. Read it
 * in a draw lambda ([ambient]): the fade then repaints one rectangle and recomposes nothing.
 */
@Composable
fun rememberAmbientColor(artUrl: String?): State<Color> {
    val context = LocalContext.current.applicationContext
    val surface = MaterialTheme.colorScheme.surface.toArgb()
    var tint by remember(artUrl) { mutableStateOf(artUrl?.let(ArtTints::cached)) }
    LaunchedEffect(artUrl) {
        if (artUrl != null && !ArtTints.known(artUrl)) tint = ArtTints.load(context, artUrl, surface)
    }
    return animateColorAsState(
        tint?.let { Color(it).copy(alpha = AMBIENT_ALPHA) } ?: Color.Transparent,
        animationSpec = tween(700),
        label = "ambient",
    )
}

/** The glow itself: nothing at the top edge, strongest behind the cover, gone before TALK. */
fun Modifier.ambient(color: State<Color>): Modifier = drawBehind {
    val c = color.value
    if (c.alpha > 0f) {
        val clear = c.copy(alpha = 0f)
        drawRect(Brush.verticalGradient(0f to clear, 0.3f to c, 0.85f to clear))
    }
}
