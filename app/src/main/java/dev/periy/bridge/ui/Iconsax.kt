/*
 * Icon path data from Iconsax (github.com/lusaxweb/iconsax) by Vuesax, via the iconsax-react
 * package by Erfan Khadivar. MIT License:
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions: The above copyright
 * notice and this permission notice shall be included in all copies or substantial portions of
 * the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */
package dev.periy.bridge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Iconsax (github.com/lusaxweb/iconsax, MIT licence), in its "Broken" style: the icon set
 * Namida's library and player are drawn with. Each is an ImageVector on the 24-unit grid, its
 * path data taken from the iconsax-react package (MIT). Only the music screens use them.
 */
object Iconsax {
    /** Back a song: a bar and a triangle pointing back. */
    val Prev: ImageVector = icon("Prev", S("m12.269 7.392 4.15-2.39c1.7-.98 3.83.24 3.83 2.21v9.57c0 1.96-2.13 3.19-3.83 2.21l-4.15-2.39-4.15-2.4c-1.7-.98-1.7-3.43 0-4.41M3.762 18.18V17M3.762 14V5.82", true, true))
    /** On a song, and play next. */
    val Next: ImageVector = icon("Next", S("m11.73 7.392-4.15-2.39c-1.7-.98-3.83.24-3.83 2.21v9.57c0 1.96 2.13 3.19 3.83 2.21l4.15-2.39 4.15-2.4c1.7-.98 1.7-3.43 0-4.41M20.238 18.18V17M20.238 14V5.82", true, true))
    val Play: ImageVector = icon("Play", S("M17.13 7.98c3.83 2.21 3.83 5.83 0 8.04l-3.09 1.78-3.09 1.78C7.13 21.79 4 19.98 4 15.56V8.44c0-4.42 3.13-6.23 6.96-4.02l2.25 1.3", true, true))
    val Pause: ImageVector = icon("Pause", S("M5.01 3C3.57 3 3 3.54 3 4.89v14.22C3 20.46 3.57 21 5.01 21h3.63c1.43 0 2.01-.54 2.01-1.89V4.89c0-1.35-.57-1.89-2.01-1.89M18.992 21c1.43 0 2.01-.54 2.01-1.89V4.89c0-1.35-.57-1.89-2.01-1.89h-3.63c-1.43 0-2.01.54-2.01 1.89v14.22c0 1.35.57 1.89 2.01 1.89", true, true))
    /** A song without a heart. */
    val Heart: ImageVector = icon("Heart", S("M20.59 4.972c.88.99 1.41 2.29 1.41 3.72 0 7-6.48 11.13-9.38 12.13-.34.12-.9.12-1.24 0-2.9-1-9.38-5.13-9.38-12.13 0-3.09 2.49-5.59 5.56-5.59 1.82 0 3.43.88 4.44 2.24a5.53 5.53 0 0 1 4.44-2.24", true, true))
    /** A song with a heart: the heart filled. */
    val HeartOn: ImageVector = icon("HeartOn", F("M16.44 3.102c-1.81 0-3.43.88-4.44 2.23a5.549 5.549 0 0 0-4.44-2.23c-3.07 0-5.56 2.5-5.56 5.59 0 1.19.19 2.29.52 3.31 1.58 5 6.45 7.99 8.86 8.81.34.12.9.12 1.24 0 2.41-.82 7.28-3.81 8.86-8.81.33-1.02.52-2.12.52-3.31 0-3.09-2.49-5.59-5.56-5.59Z"))
    /** The player's chip: what plays is sound. */
    val Headphone: ImageVector = icon("Headphone", S("M2 12.22C1.89 6.6 6.33 2.05 11.95 2.05 17.57 2.05 22 6.6 22 12.11v6.16c0 1.95-1.62 3.57-3.57 3.57-1.95 0-3.57-1.62-3.57-3.57v-2.81c0-.97.76-1.84 1.84-1.84.97 0 1.84.76 1.84 1.84v3.03", true, true), S("M5.46 18.49v-2.92c0-.97.76-1.84 1.84-1.84.97 0 1.84.76 1.84 1.84v2.81c0 1.95-1.62 3.57-3.57 3.57-1.95 0-3.57-1.63-3.57-3.57V16", true, true))
    /** Repeat, off. */
    val RepeatOff: ImageVector = icon("RepeatOff", S("m9.999 20.999-2.44-2.34 7.95.02c3.57 0 6.5-2.93 6.5-6.52 0-1.79-.73-3.42-1.91-4.6M9 12h6M8.559 4.98c-3.57 0-6.5 2.93-6.5 6.52 0 1.79.73 3.42 1.91 4.6M14.058 2.66 16.498 5l-3.51-.01", true, true))
    /** Repeat every song. */
    val RepeatAll: ImageVector = icon("RepeatAll", S("M17.42 5.16c1.66 0 3 1.34 3 3v3.32M3.58 5.16h9.41M6.74 2L3.58 5.16l3.16 3.16M20.42 18.84H6.58c-1.66 0-3-1.34-3-3v-3.32", true, true), S("M17.26 22l3.16-3.16-3.16-3.16", true, true))
    /** Repeat this song. */
    val RepeatOne: ImageVector = icon("RepeatOne", S("M8.5 5.32C4.93 5.32 2 8.25 2 11.84c0 1.79.73 3.42 1.91 4.6M14 3l2.44 2.34-3.51-.01M9.999 20.999l-2.44-2.34 7.95.02c3.57 0 6.5-2.93 6.5-6.52 0-1.79-.73-3.42-1.91-4.6", true, true), S("M12.25 14.668v-5.34l-1.5 1.67", true, true))
    /** The sound controls: pitch, speed and volume. */
    val Sound: ImageVector = icon("Sound", S("M3 8.25v7.5M7.5 5.75v12.5M12 9.96v10.79M12 3.25v2.72M16.5 5.75v12.5M21 8.25v7.5", true, true))
    /** Close the player; a list in its order. */
    val Down: ImageVector = icon("Down", S("M16.01 12.85l-2.62 2.62c-.77.77-2.03.77-2.8 0L4.08 8.95M19.92 8.95l-1.04 1.04", true, true))
    /** Back a page. */
    val Back: ImageVector = icon("Back", S("M11.19 7.94l-2.62 2.62c-.77.77-.77 2.03 0 2.8l6.52 6.52M15.09 4.04l-1.04 1.04", true, true))
    /** A list the other way round. */
    val Up: ImageVector = icon("Up", S("M17.69 12.78c1.66 2.87.3 5.22-3.01 5.22H9.33c-3.31 0-4.67-2.35-3.01-5.22l1.34-2.31L9 8.16c1.66-2.87 4.37-2.87 6.03 0", true, true))
    /** Search. */
    val Search: ImageVector = icon("Search", S("M11 2a9 9 0 1 1-4.07.97M19.071 20.97c.53 1.6 1.74 1.76 2.67.36.86-1.28.3-2.33-1.24-2.33-1.15 0-1.79.89-1.43 1.97Z", true, true))
    /** More: three rings in a row (turned upright on a song's tile). */
    val More: ImageVector = icon("More", S("M5 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2ZM19 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2Z", false, false), S("M10 12c0 1.1.9 2 2 2s2-.9 2-2-.9-2-2-2", true, true))
    val Shuffle: ImageVector = icon("Shuffle", S("m16.45 6 4.55.02M3 17.98l2.55.01c.91 0 1.76-.45 2.26-1.2l1.18-1.77.76-1.14L13.67 8M19 19.98l2-2M8.89 8.62l-1.08-1.5A2.675 2.675 0 0 0 5.61 6L3 6.01M12.969 15.379l1.22 1.57c.51.66 1.31 1.05 2.15 1.05l4.67-.02M21 6.02l-2-2", true, true))
    /** The Tracks page. */
    val Tracks: ImageVector = icon("Tracks", S("M2.578 8.67a9.993 9.993 0 0 1 3.14-4.44M2 12c0 1.17.21 2.29.58 3.33M9.09 21.568c.92.28 1.9.43 2.91.43 5.52 0 10-4.48 10-10 0-.6-.06-1.19-.16-1.76M5.718 19.748c-.47-.38-.92-.8-1.32-1.26M20.24 6.34A9.982 9.982 0 0 0 12 2c-1.01 0-1.99.15-2.91.43M8.59 17.11a1.59 1.59 0 1 0 0-3.18 1.59 1.59 0 0 0 0 3.18Z", true, true), S("M16.002 14.46V8.25c0-1.32-.83-1.51-1.67-1.28l-3.18.87c-.58.16-.98.61-.98 1.28v6.4", true, true), S("M14.41 16.051a1.59 1.59 0 1 0 0-3.18 1.59 1.59 0 0 0 0 3.18ZM10.18 10.762 16 9.172", true, true))
    /** The Albums page, and a song's album. */
    val Albums: ImageVector = icon("Albums", S("M2 9c0-5 2-7 7-7h6c5 0 7 2 7 7v6c0 5-2 7-7 7H9c-5 0-7-2-7-7v-1.41M7 2.5v19", true, true), S("M11.47 16.8a1.49 1.49 0 1 0 0-2.98 1.49 1.49 0 0 0 0 2.98Z", true, true), S("M18.43 14.31V8.48c0-1.24-.78-1.41-1.57-1.2l-2.98.81c-.54.15-.92.58-.92 1.2v6.01", true, true), S("M16.931 15.8a1.49 1.49 0 1 0 0-2.98 1.49 1.49 0 0 0 0 2.98ZM12.96 11.04l5.47-1.49", true, true))
    /** Play after everything queued. */
    val PlayLast: ImageVector = icon("PlayLast", S("M19.07 19.07c3.91-3.91 3.91-10.24 0-14.14M4.929 4.93c-3.91 3.91-3.91 10.24 0 14.14M8.7 21.41c1.07.37 2.18.55 3.3.55 1.12-.01 2.23-.18 3.3-.55M8.7 2.59c1.07-.37 2.18-.55 3.3-.55 1.12 0 2.23.18 3.3.55", true, true), S("M8.738 10.33c0-2.08 1.47-2.93 3.27-1.89l1.45.84 1.45.84c1.8 1.04 1.8 2.74 0 3.78l-1.45.84-1.45.84c-1.8 1.04-3.27.19-3.27-1.89", true, true))
    /** How many there are. */
    val Arrange: ImageVector = icon("Arrange", S("M10.18 17.15l-3.04-3.04M10.18 6.85v10.3M13.82 6.85l3.04 3.04M13.82 14.11V6.85M13.82 17.15v-.52", true, true), S("M4 6c-1.25 1.67-2 3.75-2 6 0 5.52 4.48 10 10 10s10-4.48 10-10S17.52 2 12 2c-1.43 0-2.8.3-4.03.85", true, true))
    /** A song with no cover. */
    val Note: ImageVector = icon("Note", S("M4.109 16.98c-.09.32-.14.67-.14 1.02 0 2.21 1.79 4 4 4s4-1.79 4-4a3.999 3.999 0 0 0-5.02-3.87M11.969 18V4M14.61 2.11l4.42 1.47c1.07.36 1.95 1.57 1.95 2.7v1.17c0 1.53-1.18 2.38-2.63 1.9l-4.42-1.47c-1.07-.36-1.95-1.57-1.95-2.7V4c-.01-1.52 1.18-2.38 2.63-1.89Z", true, true))
    /** Clear the search. */
    val Clear: ImageVector = icon("Clear", S("m13.99 10.012.84-.84M9.17 14.828l2.75-2.75M14.83 14.832l-5.66-5.66M4 6c-1.25 1.67-2 3.75-2 6 0 5.52 4.48 10 10 10s10-4.48 10-10S17.52 2 12 2c-1.43 0-2.8.3-4.03.85", true, true))
    /** Hi-res and lossless sound. */
    val Wind: ImageVector = icon("Wind", S("M9.5 11.6c1.54.92 3.46.92 5 0s3.46-.92 5 0l2.5 1.5M2 13.1l2.5-1.5c.46-.28.96-.47 1.47-.58M2 3.898l2.5 1.5c1.54.92 3.46.92 5 0s3.46-.92 5 0 3.46.92 5 0l2.5-1.5M2 20.1l2.5-1.5c1.54-.92 3.46-.92 5 0s3.46.92 5 0 3.46-.92 5 0l2.5 1.5", true, true))
    /** Pitch. */
    val Pitch: ImageVector = icon("Pitch", S("M6.72 9.56h-.94A3.79 3.79 0 0 1 2 5.78C2 3.7 3.7 2 5.78 2h1.89a2.84 2.84 0 0 1 2.83 2.83V17.1c0 1.04-.85 1.89-1.89 1.89s-1.89-.85-1.89-1.89V9.56Z", true, true), S("M5.78 6.72a.939.939 0 1 1 0-1.88M17.28 13.52V9.56h.94c2.08 0 3.78-1.7 3.78-3.78S20.3 2 18.22 2h-1.89a2.84 2.84 0 0 0-2.83 2.83V17.1c0 1.04.85 1.89 1.89 1.89s1.89-.85 1.89-1.89", true, true), S("M18.22 6.72a.939.939 0 1 0 0-1.88M8.5 22v-3M15.5 22v-3", true, true))
    /** Speed. */
    val Speed: ImageVector = icon("Speed", S("M2 8.34v7.32c0 1.5 1.63 2.44 2.93 1.69l3.17-1.82 3.17-1.83c.2-.12.36-.25.49-.41v-2.56c-.13-.16-.29-.29-.49-.41L8.1 8.49 4.93 6.67C3.63 5.9 2 6.84 2 8.34Z", true, true), S("M14.68 6.65c-1.3-.75-2.93.19-2.93 1.69v7.32c0 1.5 1.63 2.44 2.93 1.69l3.17-1.82 3.17-1.83c1.3-.75 1.3-2.62 0-3.38l-2.18-1.26", true, true))
    /** Volume. */
    val Volume: ImageVector = icon("Volume", S("M15 7.412c0-2.98-2.07-4.12-4.59-2.54l-2.92 1.83c-.32.19-.69.3-1.06.3H5c-2 0-3 1-3 3v4c0 2 1 3 3 3h1.43c.37 0 .74.11 1.06.3l2.92 1.83c2.52 1.58 4.59.43 4.59-2.54v-5.12M18 8a6.66 6.66 0 0 1 0 8M19.828 18.5c1.45-1.93 2.17-4.21 2.17-6.5M19.828 5.5c.59.78 1.05 1.62 1.4 2.5", true, true))
    /** Volume, off. */
    val Mute: ImageVector = icon("Mute", S("M2 14c0 2 1 3 3 3h2M15 8.372v-.96c0-2.98-2.07-4.12-4.59-2.54l-2.92 1.83c-.32.19-.69.3-1.06.3H5c-2 0-3 1-3 3M10.41 19.13c2.52 1.58 4.59.43 4.59-2.54v-3.64M18.81 9.422c.9 2.15.63 4.66-.81 6.58M20.78 17c-.27.52-.58 1.02-.94 1.5M21.148 7.8c.83 1.97 1.05 4.13.66 6.2M22 2 2 22", true, true))
    /** Back as it was. */
    val Reset: ImageVector = icon("Reset", S("M18.01 19.99A9.964 9.964 0 0112 22c-5.52 0-8.89-5.56-8.89-5.56m0 0h4.52m-4.52 0v5M22 12c0 1.82-.49 3.53-1.34 5M6.03 3.97A9.921 9.921 0 0112 2c6.67 0 10 5.56 10 5.56m0 0v-5m0 5h-4.44M2 12c0-1.82.48-3.53 1.33-5", true, true))
    /** A handle to drag a song by: three lines. */
    val Handle: ImageVector = icon("Handle", S("M3 7h18M9.49 12H21M3 12h2.99M3 17h18", true, false))
    /** Take it off. */
    val Trash: ImageVector = icon("Trash", S("M21 5.98c-3.33-.33-6.68-.5-10.02-.5-1.98 0-3.96.1-5.94.3L3 5.98M8.5 4.97l.22-1.31C8.88 2.71 9 2 10.69 2h2.62c1.69 0 1.82.75 1.97 1.67l.22 1.3M15.21 22H8.79C6 22 5.91 20.78 5.8 19.21L5.15 9.14M18.85 9.14l-.65 10.07M10.33 16.5h3.33M12.82 12.5h1.68M9.5 12.5h.83", true, true))
    /** Go to the song playing. */
    val Jump: ImageVector = icon("Jump", S("M4.87 4.99A9.936 9.936 0 002 12c0 5.52 4.48 10 10 10s10-4.48 10-10S17.52 2 12 2c-.69 0-1.36.07-2.02.2", true, true), S("M8.47 10.74L12 14.26l3.53-3.52", true, true))
    /** Configure. */
    val Configure: ImageVector = icon("Configure", S("M22 6.5h-6M6 6.5H2M13.5 6.5c0 1.93-1.57 3.5-3.5 3.5S6.5 8.43 6.5 6.5a3.504 3.504 0 0 1 4.48-3.36M22 17.5h-4M8 17.5H2M14 21a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z", true, true))
    /** Clear some of the queue. */
    val Broom: ImageVector = icon("Broom", S("m9.87 5.671-3.42 2.08-1.56-2.56a2.01 2.01 0 0 1 .67-2.75 2.01 2.01 0 0 1 2.75.67l1.56 2.56ZM9.2 20.44c.66 1.35 2.26 1.82 3.54 1.03l6.43-3.91c1.29-.78 1.6-2.41.71-3.62l-2.77-3.74c-1.2-1.61-3.46-2.16-5.29-1.04l-3.16 1.92a3.978 3.978 0 0 0-1.51 5.18", true, true), S("m10.757 5.098-5.124 3.12 2.08 3.417 5.125-3.12-2.08-3.417ZM14.31 16.809l1.65 2.71M11.75 18.371l1.65 2.71M16.87 15.25l1.65 2.71", true, true))
    /** Time left. */
    val Timer: ImageVector = icon("Timer", S("m9.61 9.83 7.65 6.95C19.29 18.62 19 22 15.24 22H8.76C5 22 4.71 18.62 6.74 16.78l10.52-9.56C19.29 5.38 19 2 15.24 2H8.76C5 2 4.71 5.38 6.74 7.22", true, true))
    /** Add songs. */
    val Add: ImageVector = icon("Add", S("M12 16V8M14.99 12H16M8 12h3.81M12 16V8M4 6c-1.25 1.67-2 3.75-2 6 0 5.52 4.48 10 10 10s10-4.48 10-10S17.52 2 12 2c-1.43 0-2.8.3-4.03.85", true, true))
    /** The song playing is further up. */
    val ArrowUp: ImageVector = icon("ArrowUp", S("M18.07 9.57L12 3.5 5.93 9.57M12 12V3.67M12 20.5v-4.53", true, true))
    /** The song playing is further down. */
    val ArrowDown: ImageVector = icon("ArrowDown", S("M18.07 14.43L12 20.5l-6.07-6.07M12 12v8.33M12 3.5v4.53", true, true))
    /** The song playing is in view. */
    val Cd: ImageVector = icon("Cd", S("M14 12c0-1.1-.9-2-2-2s-2 .9-2 2 .9 2 2 2", true, true), S("M4 6c-1.25 1.67-2 3.75-2 6 0 5.52 4.48 10 10 10s10-4.48 10-10S17.52 2 12 2c-1.43 0-2.8.3-4.03.85", true, true))

    private class Part(val d: String, val fill: Boolean, val round: Boolean = true, val roundJoin: Boolean = true)

    private fun S(d: String, round: Boolean, roundJoin: Boolean) = Part(d, fill = false, round = round, roundJoin = roundJoin)
    private fun F(d: String) = Part(d, fill = true)

    private fun icon(name: String, vararg parts: Part): ImageVector =
        ImageVector.Builder("iconsax-$name", 24.dp, 24.dp, 24f, 24f).apply {
            parts.forEach { p ->
                val nodes = PathParser().parsePathString(p.d).toNodes()
                if (p.fill) addPath(nodes, fill = SolidColor(Color.Black))
                else addPath(
                    nodes,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = if (p.round) StrokeCap.Round else StrokeCap.Butt,
                    strokeLineJoin = if (p.roundJoin) StrokeJoin.Round else StrokeJoin.Miter,
                    strokeLineMiter = 10f,
                )
            }
        }.build()
}
