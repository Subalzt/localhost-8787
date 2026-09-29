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
    /** Lyrics, shown over the cover. */
    val Lyrics: ImageVector = icon("Lyrics", S("M2 9c0-5 2-7 7-7h5M22 10v5c0 5-2 7-7 7H9c-5 0-7-2-7-7v-2.02M18 10c-3 0-4-1-4-4V2l8 8", true, true))
    /** Lyrics, switched off. */
    val LyricsOff: ImageVector = icon("LyricsOff", S("M2 8.5h13.24M6 16.5h1.29M11 16.5h3.5", true, true), S("M7.98 20.5h9.58c3.56 0 4.44-.88 4.44-4.39V6.89M2 14.969v1.14c0 2.34.39 3.51 1.71 4.03M19.99 3.75c-.62-.18-1.42-.25-2.43-.25H6.44C2.89 3.5 2 4.38 2 7.89v3.05M22 2 2 22", true, true))
    /** A ring broken in three: repeat a number of times, with the number inside it. */
    val Status: ImageVector = icon("Status", S("M2.45 14.969c1.07 3.44 3.95 6.09 7.53 6.82M2.05 10.98A9.996 9.996 0 0 1 12 2c5.18 0 9.44 3.94 9.95 8.98M14.01 21.8c3.57-.73 6.44-3.35 7.53-6.78", true, true))
    /** One fewer. */
    val MinusCircle: ImageVector = icon("MinusCircle", S("M14.99 12H16M8 12h4M4 6c-1.25 1.67-2 3.75-2 6 0 5.52 4.48 10 10 10s10-4.48 10-10S17.52 2 12 2c-1.43 0-2.8.3-4.03.85", true, true))
    /** Home. */
    val Home2: ImageVector = icon("Home2", S("M22 10.498c0-1.21-.81-2.76-1.8-3.45l-6.18-4.33c-1.4-.98-3.65-.93-5 .12l-5.39 4.2c-.9.7-1.63 2.19-1.63 3.32v7.41c0 2.32 1.89 4.22 4.21 4.22h11.58c2.32 0 4.21-1.9 4.21-4.21v-3.1M12 17.988v-3", true, true))
    /** Devices: a phone and a laptop. */
    val Devices: ImageVector = icon("Devices", S("M17.01 12.73a1.07 1.07 0 1 0 0-2.14 1.07 1.07 0 0 0 0 2.14Z", true, true), S("M2 6c0-3.2.8-4 4-4h10c3.2 0 4 .8 4 4v1.79c-.25-.03-.54-.05-.85-.05h-4.28c-2.14 0-2.85.71-2.85 2.85v5.11H6c-3.2 0-4-.8-4-4v-1.65M9 15.7V20M2 11.898h10M5.95 20H12", true, true), S("M17.01 12.73a1.07 1.07 0 1 0 0-2.14 1.07 1.07 0 0 0 0 2.14Z", true, true), S("M20 7.788c-.25-.03-.54-.05-.85-.05h-4.28c-2.14 0-2.85.71-2.85 2.85v8.56c0 2.14.71 2.85 2.85 2.85h4.28c2.14 0 2.85-.71 2.85-2.85v-8.56c0-1.83-.52-2.61-2-2.8Zm-2.99 2.8a1.071 1.071 0 0 1 0 2.14c-.59 0-1.07-.48-1.07-1.07 0-.59.48-1.07 1.07-1.07Zm0 8.56c-1.18 0-2.14-.96-2.14-2.14a2.142 2.142 0 0 1 3.54-1.62c.45.4.74.98.74 1.62 0 1.18-.96 2.14-2.14 2.14Z", true, true), S("M19.15 17.007c0 1.18-.96 2.14-2.14 2.14-1.18 0-2.14-.96-2.14-2.14a2.142 2.142 0 0 1 3.54-1.62c.45.4.74.98.74 1.62ZM17.01 12.73a1.07 1.07 0 1 0 0-2.14 1.07 1.07 0 0 0 0 2.14Z", true, true))
    /** The phone as a trackpad. */
    val Mouse: ImageVector = icon("Mouse", S("M2 13.02V15c0 5 2 7 7 7h3M22 12V9c0-5-2-7-7-7H9C4 2 2 4 2 9M20.96 17.838l-1.63.55c-.45.15-.81.5-.96.96l-.55 1.63c-.47 1.41-2.45 1.38-2.89-.03l-1.85-5.95c-.36-1.18.73-2.28 1.9-1.91l5.96 1.85c1.4.44 1.42 2.43.02 2.9Z", true, true))
    /** A pulse: what is live. */
    val Activity: ImageVector = icon("Activity", S("M2 12.96V15c0 5 2 7 7 7h6c5 0 7-2 7-7V9c0-5-2-7-7-7H9C4 2 2 4 2 9", true, true), S("m7.33 14.492 2.38-3.09c.34-.44.97-.52 1.41-.18l1.83 1.44c.44.34 1.07.26 1.41-.17l2.31-2.98", true, true))
    /** Out of the phone: send, upload. */
    val Export: ImageVector = icon("Export", S("M17.52 18.01C16.16 19.25 14.29 20 12 20c-5 0-8-3.58-8-8M20 12c0 1.05-.17 2.05-.49 2.97M9.44 6.47L12 3.91l2.56 2.56M12 9.15V3.98M12 14.15v-1.96", true, true))
    /** Into the phone: receive, download. */
    val Import: ImageVector = icon("Import", S("M9.44 11.68L12 14.24l2.56-2.56M12 9v5.17M12 4v1.96M17.52 18.01C16.16 19.25 14.29 20 12 20c-5 0-8-3.58-8-8M20 12c0 1.05-.17 2.05-.49 2.97", true, true))
    /** Paste. */
    val Paste: ImageVector = icon("Paste", S("M3 10c0-4.56 1.67-5.8 5-5.98M14 22.002H9c-5 0-6-2-6-6v-1.99M16 4.02c3.33.18 5 1.41 5 5.98v5M10.96 2H10C9 2 8 2 8 4s1 2 2 2h4c2 0 2-1 2-2 0-2-1-2-2-2M21 19v3h-3M15 16l5.96 5.96", true, true))
    /** Copy. */
    val Copy: ImageVector = icon("Copy", S("M2 12.9C2 9.4 3.4 8 6.9 8h4.2c3.5 0 4.9 1.4 4.9 4.9v4.2c0 3.5-1.4 4.9-4.9 4.9H6.9C3.4 22 2 20.6 2 17.1", true, true), S("M22 11.1c0 3.5-1.4 4.9-4.9 4.9H16v-3.1C16 9.4 14.6 8 11.1 8H8V6.9C8 3.4 9.4 2 12.9 2h4.2C20.6 2 22 3.4 22 6.9", true, true))
    /** Send. */
    val Send: ImageVector = icon("Send", S("M15.89 3.49c3.81-1.27 5.88.81 4.62 4.62l-2.83 8.49c-1.9 5.71-5.02 5.71-6.92 0l-.84-2.52-2.52-.84c-5.71-1.9-5.71-5.01 0-6.92L12 4.79M10.11 13.649l3.58-3.59", true, true))
    /** Goes somewhere: a chevron. */
    val Chevron: ImageVector = icon("Chevron", S("M12.9 7.94l2.62 2.62c.77.77.77 2.03 0 2.8L9 19.87M9 4.04l1.04 1.04", true, true))
    /** A link. */
    val Link: ImageVector = icon("Link", S("M3.27 12A5.46 5.46 0 0 1 2 8.5C2 5.48 4.47 3 7.5 3h5C15.52 3 18 5.48 18 8.5S15.53 14 12.5 14H10", true, true), S("M11.98 21h-.48C8.48 21 6 18.52 6 15.5S8.47 10 11.5 10H14M20.73 12A5.46 5.46 0 0 1 22 15.5c0 3.02-2.47 5.5-5.5 5.5", true, true))
    /** Fast. */
    val Flash: ImageVector = icon("Flash", S("M14.82 7.02v-3.5c0-1.68-.91-2.02-2.02-.76l-7.57 8.6c-.93 1.05-.54 1.92.87 1.92h3.09v7.2c0 1.68.91 2.02 2.02.76l7.57-8.6c.93-1.05.54-1.92-.87-1.92h-3.09", true, true))
    /** The phone's hotspot. */
    val Hotspot: ImageVector = icon("Hotspot", S("M12.55 12.92a2.206 2.206 0 0 1-2.68-2.68 2.205 2.205 0 0 1 4.32.26M7.64 3.148a8.78 8.78 0 0 0-4.43 7.64c0 2.54 1.08 4.83 2.81 6.43M18.01 17.19a8.731 8.731 0 0 0 2.78-6.4A8.79 8.79 0 0 0 12 2", true, true), S("M8 14.55c-.92-.98-1.49-2.3-1.49-3.76C6.51 7.76 8.97 5.3 12 5.3c3.03 0 5.49 2.46 5.49 5.49 0 1.46-.57 2.77-1.49 3.76M10.3 16.661l-1.44 1.79c-1.14 1.43-.13 3.54 1.7 3.54h2.87c1.83 0 2.85-2.12 1.7-3.54l-1.44-1.79c-.86-1.09-2.52-1.09-3.39 0Z", true, true))
    /** Wi-Fi. */
    val Wifi: ImageVector = icon("Wifi", S("M16.31 10.21c.97.4 1.9.94 2.78 1.62M4.91 11.839c2.44-1.89 5.3-2.7 8.1-2.44M8.36 5.28c4.63-1.1 9.55-.08 13.64 3.08M2 8.36c.94-.72 1.92-1.34 2.93-1.84M6.79 15.49c3.15-2.44 7.26-2.44 10.41 0M9.4 19.15c1.58-1.22 3.63-1.22 5.21 0", true, true))
    /** Locked, private. */
    val Lock: ImageVector = icon("Lock", S("M6 10V8c0-3.31 1-6 6-6s6 2.69 6 6v2M22 17v-2c0-4-1-5-5-5H7c-4 0-5 1-5 5v2c0 4 1 5 5 5h10c1.76 0 2.94-.19 3.71-.75", true, true), S("M15.995 16h.008M11.995 16h.009M7.995 16h.008", true, true))
    /** A code to scan. */
    val Qr: ImageVector = icon("Qr", S("M2 9V6.5C2 4.01 4.01 2 6.5 2H9M15 2h2.5C19.99 2 22 4.01 22 6.5V9M22 16v1.5c0 2.49-2.01 4.5-4.5 4.5H16M9 22H6.5C4.01 22 2 19.99 2 17.5V15M17 9.5v5c0 2-1 3-3 3h-4c-2 0-3-1-3-3v-5c0-2 1-3 3-3h4M19 12H5", true, true))
    /** A computer. */
    val Monitor: ImageVector = icon("Monitor", S("M22 10.63v2.15c0 3.56-.89 4.44-4.44 4.44H6.44c-3.55 0-4.44-.89-4.44-4.44V6.44C2 2.89 2.89 2 6.44 2h11.11C21.11 2 22 2.89 22 6.44M12 17.219v4.78M2 13h20M7.5 22h9", true, true))
    /** A folder. */
    val Folder: ImageVector = icon("Folder", S("M8 2h9c2 0 3 1 3 3v1.38", true, true), S("M2 13.02V7c0-4 1-5 5-5h1.5c1.5 0 1.83.44 2.4 1.2l1.5 2c.38.5.6.8 1.6.8h3c4 0 5 1 5 5M22 14.988v2.01c0 4-1 5-5 5H7c-4 0-5-1-5-5", true, true))
    /** A file. */
    val File: ImageVector = icon("File", S("M12 13h1M7 13h2.45M7 17h4M2 9c0-5 2-7 7-7h5M22 10v5c0 5-2 7-7 7H9c-5 0-7-2-7-7v-2.02M18 10c-3 0-4-1-4-4V2l8 8", true, true))
    /** Done. */
    val Check: ImageVector = icon("Check", S("M4 6c-1.25 1.67-2 3.75-2 6 0 5.52 4.48 10 10 10s10-4.48 10-10S17.52 2 12 2c-1.43 0-2.8.3-4.03.85M15 10.38l1.12-1.13", true, true), S("m7.88 12 2.74 2.75 2.55-2.54", true, true))
    /** A message. */
    val Message: ImageVector = icon("Message", S("M2 8c0-4 2-6 6-6h8c4 0 6 2 6 6v5c0 4-2 6-6 6h-.5c-.31 0-.61.15-.8.4l-1.5 2c-.66.88-1.74.88-2.4 0l-1.5-2c-.16-.22-.53-.4-.8-.4H8c-4 0-6-1-6-6v-1", true, true), S("M15.995 11h.008M11.995 11h.009M7.995 11h.008", true, true))
    /** Play or pause. */
    val PlayPause: ImageVector = icon("PlayPause", S("M14.908 14.119c1.8-1.04 1.8-2.74 0-3.78l-1.45-.84-1.45-.84c-1.8-1.04-3.27-.19-3.27 1.89v3.34c0 1.66.94 2.54 2.24 2.29", true, true), S("M4 6c-1.25 1.67-2 3.75-2 6 0 5.52 4.48 10 10 10s10-4.48 10-10S17.52 2 12 2c-1.43 0-2.8.3-4.03.85", true, true))
    /** Quieter. */
    val VolumeLow: ImageVector = icon("VolumeLow", S("M22 12h-.14M18 12h1.8M2 14.04c0 2.04 1.02 3.06 3.06 3.06h1.46c.38 0 .76.11 1.08.31l2.98 1.86c2.58 1.61 4.68.44 4.68-2.6V7.32c0-3.04-2.11-4.21-4.68-2.6L7.6 6.59c-.33.2-.7.31-1.08.31H5.06C3.02 6.9 2 7.92 2 9.96", true, true))
    /** History. */
    val Clock: ImageVector = icon("Clock", S("m15.71 15.182-3.1-1.85c-.54-.32-.98-1.09-.98-1.72v-4.1", true, true), S("M4 6c-1.25 1.67-2 3.75-2 6 0 5.52 4.48 10 10 10s10-4.48 10-10S17.52 2 12 2c-1.43 0-2.8.3-4.03.85", true, true))
    /** A picture. */
    val Gallery: ImageVector = icon("Gallery", S("M2 12.99V15c0 5 2 7 7 7h6c5 0 7-2 7-7V9c0-5-2-7-7-7H9C4 2 2 4 2 9", true, true), S("M11 8c0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2M2.672 18.949l4.93-3.31c.79-.53 1.93-.47 2.64.14l.33.29c.78.67 2.04.67 2.82 0l4.16-3.57c.78-.67 2.04-.67 2.82 0l1.63 1.4", true, true))
    /** Music. */
    val Music: ImageVector = icon("Music", S("M6.28 22.002a3.12 3.12 0 1 0 0-6.24 3.12 3.12 0 0 0 0 6.24Z", true, true), S("M20.838 7.96V4.6c0-2.6-1.63-2.96-3.28-2.51l-6.24 1.7c-1.14.31-1.92 1.21-1.92 2.51v12.57M20.84 16.8V12M17.722 19.92a3.12 3.12 0 1 0 0-6.24 3.12 3.12 0 0 0 0 6.24ZM9.398 9.518l11.44-3.12", true, true))
    /** Where it is. */
    val Locate: ImageVector = icon("Locate", S("M8.5 5.365A7.5 7.5 0 1 1 5.365 8.5", true, true), S("M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM12 4V2M4 12H2M12 20v2M20 12h2", true, true))
    /** Light. */
    val Sun: ImageVector = icon("Sun", S("M7 7.85a6.5 6.5 0 1 0 5-2.35", true, true), S("m19.14 19.14-.13-.13m0-14.02.13-.13-.13.13ZM4.86 19.14l.13-.13-.13.13ZM12 2.08V2v.08ZM12 22v-.08.08ZM2.08 12H2h.08ZM22 12h-.08.08ZM4.99 4.99l-.13-.13.13.13Z", true, true))
    /** Dark. */
    val Moon: ImageVector = icon("Moon", S("M4.18 5.38a10.146 10.146 0 0 0-2.15 7.04c.36 5.15 4.73 9.34 9.96 9.57 3.69.16 6.99-1.56 8.97-4.27.82-1.11.38-1.85-.99-1.6-.67.12-1.36.17-2.08.14C13 16.06 9 11.97 8.98 7.14c-.01-1.3.26-2.53.75-3.65.54-1.24-.11-1.83-1.36-1.3", true, true))

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
