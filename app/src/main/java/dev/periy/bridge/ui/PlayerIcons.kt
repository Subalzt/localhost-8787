package dev.periy.bridge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The player's icons, in the manner of Namida's: fine outlines with rounded ends, each broken
 * once along its line, so they read as drawn rather than stamped. Drawn here on the app's
 * 24-unit grid (the page has the same shapes as SVG); used only inside the music player.
 */
object PlayerIcons {
    /** A bar broken near its foot and a triangle pointing back, its lower edge broken: Next, mirrored. */
    val Prev: ImageVector = broken(
        "p-prev",
        "M19 15.6V7.4c0-1.3-1.4-2.1-2.5-1.4L9.2 10.6c-1 .7-1 2.1 0 2.8l1.7 1.1",
        "M13.6 16.1l2.9 1.9c1.1.7 2.5-.1 2.5-1.4",
        "M5.5 6.3v8.2", "M5.5 17.4v.3",
    )

    /** A triangle pointing on, its lower edge broken, and a bar broken near its foot. */
    val Next: ImageVector = broken(
        "p-next",
        "M5 15.6V7.4c0-1.3 1.4-2.1 2.5-1.4l7.3 4.6c1 .7 1 2.1 0 2.8l-1.7 1.1",
        "M10.4 16.1 7.5 18c-1.1.7-2.5-.1-2.5-1.4",
        "M18.5 6.3v8.2", "M18.5 17.4v.3",
    )

    /** Play: a rounded triangle, broken low on its right side. */
    val Play: ImageVector = broken(
        "p-play",
        "M6.8 11V7.3c0-1.6 1.7-2.6 3.1-1.8l7.8 4.6c1.4.8 1.4 2.9 0 3.7l-2.4 1.4",
        "M12.4 16.9l-2.5 1.5c-1.4.8-3.1-.2-3.1-1.8v-1.4",
    )

    /** Pause: two rounded bars, the second broken near its top. */
    val Pause: ImageVector = broken(
        "p-pause",
        "M10.4 17.6V6.4c0-1.1-.5-1.9-1.8-1.9H7.3c-1.3 0-1.8.8-1.8 1.9v11.2c0 1.1.5 1.9 1.8 1.9h1.3c1.3 0 1.8-.8 1.8-1.9Z",
        "M18.5 9.4v8.2c0 1.1-.5 1.9-1.8 1.9h-1.3c-1.3 0-1.8-.8-1.8-1.9V6.4c0-1.1.5-1.9 1.8-1.9h1.3c1 0 1.5.5 1.7 1.3",
    )

    /** A heart, broken low on its right. */
    val Heart: ImageVector = broken(
        "p-heart",
        "M16.4 17.2c-1.4 1.3-2.9 2.3-3.6 2.7-.5.3-1.1.3-1.6 0C9 18.6 3.5 14.9 3.5 9.7 3.5 7.1 5.6 5 8.2 5c1.5 0 2.9.7 3.8 1.9.9-1.2 2.3-1.9 3.8-1.9 2.6 0 4.7 2.1 4.7 4.7 0 1.7-.6 3.3-1.5 4.6",
    )

    /** The heart, filled: a song with a heart. */
    val HeartOn: ImageVector = ImageVector.Builder("p-heart-on", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            PathParser().parsePathString("M12 19.9c-.3 0-.5-.1-.8-.2C9 18.6 3.5 14.9 3.5 9.7 3.5 7.1 5.6 5 8.2 5c1.5 0 2.9.7 3.8 1.9.9-1.2 2.3-1.9 3.8-1.9 2.6 0 4.7 2.1 4.7 4.7 0 5.2-5.5 8.9-7.7 10-.3.1-.5.2-.8.2Z").toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()

    /** Headphones: what is playing is sound. */
    val Headphones: ImageVector = broken(
        "p-phones",
        "M5.5 17.8v-5.5a6.5 6.5 0 0 1 12.2-3.1",
        "M18.5 12v5.8",
        "M5.5 14.2h1.2c.9 0 1.6.7 1.6 1.6v2.6c0 .9-.7 1.6-1.6 1.6H5.6c-1 0-1.6-.7-1.6-1.6v-2.6c0-.9.7-1.6 1.5-1.6Z",
        "M18.5 14.2h-1.2c-.9 0-1.6.7-1.6 1.6v2.6c0 .9.7 1.6 1.6 1.6h1.1c1 0 1.6-.7 1.6-1.6v-2.6c0-.9-.7-1.6-1.5-1.6Z",
    )

    /** Four bars of a level meter, the tallest broken: the sound controls (speed, pitch, loudness). */
    val Sound: ImageVector = broken(
        "p-sound",
        "M5.5 9.5v5", "M9.8 4.5v5.6", "M9.8 13v6.5", "M14.2 7.5v9", "M18.5 10.5v3",
    )

    /** Two arrows going round, one broken: repeat. */
    val Repeat: ImageVector = broken(
        "p-repeat",
        "M3.6 12.4V9.8c0-1.8 1.4-3.3 3.3-3.3h13.5", "M17.6 3.7l2.8 2.8-2.8 2.8",
        "M20.4 11.6v2.6c0 1.8-1.4 3.3-3.3 3.3H10", "M6.4 20.3l-2.8-2.8 2.8-2.8", "M7 17.5h-.6",
    )

    /** Repeat, this one song: the arrows around a 1. */
    val RepeatOne: ImageVector = broken(
        "p-repeat-one",
        "M3.6 12.4V9.8c0-1.8 1.4-3.3 3.3-3.3h13.5", "M17.6 3.7l2.8 2.8-2.8 2.8",
        "M20.4 11.6v2.6c0 1.8-1.4 3.3-3.3 3.3H3.6", "M6.4 20.3l-2.8-2.8 2.8-2.8",
        "M11.3 11 12.4 10v5",
    )

    /** Two paths crossing, one broken: shuffle. */
    val Shuffle: ImageVector = broken(
        "p-shuffle",
        "M3 17.8h2.4c1.1 0 2.2-.6 2.8-1.5l4.1-6.1c.6-.9 1.7-1.5 2.8-1.5h5.4",
        "M18.7 6.4l1.9 1.9-1.9 1.9",
        "M3 6.2h2.4c1.2 0 2.3.6 2.9 1.6l.6.9", "M11.8 15.4l.6.9c.6 1 1.7 1.6 2.9 1.6h5.3",
        "M18.7 15.9l1.9 1.9-1.9 1.9",
    )

    /** Two stacked rows, the top one broken: the queue. */
    val Queue: ImageVector = broken(
        "p-queue",
        "M19.6 13.6H4.4c-1.5 0-2.1.6-2.1 2.1v2.2c0 1.5.6 2.1 2.1 2.1h15.2c1.5 0 2.1-.6 2.1-2.1v-2.2c0-1.5-.6-2.1-2.1-2.1Z",
        "M21.7 6v2.2c0 1.5-.6 2.1-2.1 2.1H4.4c-1.5 0-2.1-.6-2.1-2.1V6c0-1.5.6-2.1 2.1-2.1h13.2",
    )

    /** A page with lines, its corner broken: the lyrics. */
    val Lyrics: ImageVector = broken(
        "p-lyrics",
        "M20.5 11v4.6c0 3.7-1.5 4.9-4.9 4.9H8.4c-3.4 0-4.9-1.2-4.9-4.9V8.4c0-3.7 1.5-4.9 4.9-4.9h5.9",
        "M8 12.5h7", "M8 16h4.5",
    )

    /** A thin chevron pointing down, broken on its left arm: close the player. */
    val Down: ImageVector = broken("p-down", "M19.4 9.2l-6.2 6.2c-.7.7-1.8.7-2.5 0L9 13.7", "M7.3 12 4.6 9.2")

    /** A thin chevron pointing back, broken on its upper arm: back a page. */
    val Back: ImageVector = broken("p-back", "M15 19.4l-6.2-6.2c-.7-.7-.7-1.8 0-2.5L10.3 9.2", "M12 7.3l3-2.7")

    /** A lens, broken where the handle leaves it: search. */
    val Search: ImageVector = broken("p-search", "M15.2 18.1A8 8 0 1 1 18.1 15.2", "M17.6 17.6 21 21")

    /** A cross, one stroke broken in the middle: clear. */
    val Close: ImageVector = broken("p-close", "M18 6 6 18", "M6 6l4.7 4.7", "M13.3 13.3 18 18")

    /** A note, its stem broken short of the second head: a song, and the Tracks page. */
    val Note: ImageVector = broken(
        "p-note",
        "M9 17.8V7c0-.9.6-1.6 1.4-1.8l7-1.9c1-.3 2 .5 2 1.5v8.1", "M9 9.6l10.4-2.8",
        "M6.5 15.3a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5Z", "M16.9 12.8a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5Z",
    )

    /** Three rings stacked: more for this song. */
    val Dots: ImageVector = broken(
        "p-dots",
        "M12 4.1a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6Z",
        "M12 10.7a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6Z",
        "M12 17.3a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6Z",
    )

    /** Three lines getting shorter, the first broken: the order a list is in. */
    val Order: ImageVector = broken("p-order", "M3.5 7h10.4", "M16.9 7h3.6", "M6.5 12h11", "M9.5 17h5")

    /** A row with a plus after it: play after everything queued. */
    val AddLast: ImageVector = broken(
        "p-add-last",
        "M3.5 7h12", "M3.5 12h8.5", "M3.5 17h6.5", "M17.5 12.5v7", "M14 16h7",
    )

    /** A row with an arrow into it: play next. */
    val AddNext: ImageVector = broken(
        "p-add-next",
        "M9.5 7h11", "M12.5 12h8", "M9.5 17h11", "M3.5 9.3 7 12l-3.5 2.7",
    )

    /** Three rings in a row: more. */
    val More: ImageVector = broken(
        "p-more",
        "M5.5 10.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6Z",
        "M12 10.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6Z",
        "M18.5 10.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6Z",
    )

    /** A disc, broken, with its centre: back to the song playing. */
    val Disc: ImageVector = broken(
        "p-disc",
        "M20.5 12a8.5 8.5 0 1 1-2.5-6", "M12 9.8a2.2 2.2 0 1 1 0 4.4a2.2 2.2 0 1 1 0-4.4Z",
    )

    /** Three short lines: a handle to drag by. */
    val Handle: ImageVector = broken("p-handle", "M5 9h14", "M5 12h14", "M5 15h9")

    /** A bin, its lid lifted: take it off. */
    val Trash: ImageVector = broken(
        "p-trash",
        "M20.5 6c-3.3-.3-6.7-.5-10-.5-2 0-4 .1-6 .3L3.5 6",
        "M8.5 5l.2-1.3c.2-.9.3-1.7 1.9-1.7h2.8c1.6 0 1.7.8 1.9 1.7l.2 1.3",
        "M18.8 9.1l-.6 10.1c-.1 1.6-.2 2.8-3 2.8H8.8c-2.8 0-2.9-1.2-3-2.8L5.2 9.1",
    )

    private fun broken(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.6f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
