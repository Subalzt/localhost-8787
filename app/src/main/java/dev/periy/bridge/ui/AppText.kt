package dev.periy.bridge.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * The app's Text and Icon outside Music: Material's, but with Namida's look on (the shared
 * switch, [LocalNamidaUi]) text is in Lexend Deca and each icon is its Iconsax twin. Files that
 * use these leave out Material's own Text and Icon imports; Music's files import Material's, as
 * Music is Namida's either way.
 */

/** Lexend Deca, while Namida's look is on and [style] names no font of its own (a monospace one stays). */
@Composable
@ReadOnlyComposable
private fun namidaFont(style: TextStyle, fontFamily: FontFamily?): FontFamily? =
    fontFamily ?: if (LocalNamidaUi.current && style.fontFamily == null) LexendDeca else null

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
    fontFamily = namidaFont(style, fontFamily), letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
    lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
    onTextLayout = onTextLayout, style = style,
)

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
    fontFamily = namidaFont(style, fontFamily), letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
    lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
    inlineContent = inlineContent, onTextLayout = onTextLayout, style = style,
)

@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = androidx.compose.material3.Icon(
    if (LocalNamidaUi.current) BLAZE_TWINS[imageVector.name] ?: imageVector else imageVector,
    contentDescription, modifier, tint,
)

/**
 * Each of the app's icons (by its name in Icons.kt) and its twin in Namida's Iconsax set. The few
 * with no good twin (USB, power, contrast) keep their own.
 */
private val BLAZE_TWINS: Map<String, ImageVector> by lazy {
    mapOf(
        "home" to Iconsax.Home2, "phones" to Iconsax.Devices, "trackpad" to Iconsax.Mouse, "sliders" to Iconsax.Configure,
        "pulse" to Iconsax.Activity, "upload" to Iconsax.Export, "download" to Iconsax.Import, "paste" to Iconsax.Paste,
        "copy" to Iconsax.Copy, "trash" to Iconsax.Trash, "send" to Iconsax.Send, "sun" to Iconsax.Sun, "moon" to Iconsax.Moon,
        "chevron" to Iconsax.Chevron, "link" to Iconsax.Link, "bolt" to Iconsax.Flash, "hotspot" to Iconsax.Hotspot,
        "wifi" to Iconsax.Wifi, "lock" to Iconsax.Lock, "qr" to Iconsax.Qr, "laptop" to Iconsax.Monitor, "folder" to Iconsax.Folder,
        "file" to Iconsax.File, "close" to Iconsax.Clear, "check" to Iconsax.Check, "message" to Iconsax.Message,
        "prev" to Iconsax.Prev, "next" to Iconsax.Next, "playpause" to Iconsax.PlayPause, "voldown" to Iconsax.VolumeLow,
        "volup" to Iconsax.Volume, "mute" to Iconsax.Mute, "history" to Iconsax.Clock, "image" to Iconsax.Gallery,
        "play" to Iconsax.Play, "pause" to Iconsax.Pause, "plus" to Iconsax.Add, "music" to Iconsax.Music, "search" to Iconsax.Search,
        "shuffle" to Iconsax.Shuffle, "repeat" to Iconsax.RepeatAll, "back" to Iconsax.Back, "refresh" to Iconsax.Reset,
        "handle" to Iconsax.Handle, "album" to Iconsax.Albums, "locate" to Iconsax.Locate, "more" to Iconsax.More,
    )
}
