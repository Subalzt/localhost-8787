package dev.periy.bridge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Localhost 8787's own line icons: one 24-unit grid, one stroke weight, round ends. */
object BlazeIcons {
    /** A house. */
    val Home: ImageVector = stroked(
        "home",
        "M3.5,10.5 L12,3.5 L20.5,10.5",
        "M5.5,9 V19 A1.5,1.5 0 0 0 7,20.5 H17 A1.5,1.5 0 0 0 18.5,19 V9",
        "M10,20.5 V15 A2,2 0 0 1 14,15 V20.5",
    )

    /** A phone sending out waves either side: other phones nearby. */
    val Phones: ImageVector = stroked(
        "phones",
        // Drawn inside the grid with room for the stroke: the outer waves once ran past its edges and were cut.
        "M10.6,3.5 H13.4 A2,2 0 0 1 15.4,5.5 V18.5 A2,2 0 0 1 13.4,20.5 H10.6 A2,2 0 0 1 8.6,18.5 V5.5 A2,2 0 0 1 10.6,3.5 Z",
        "M11.2,17.3 H12.8",
        "M6,9.2 A4,4 0 0 0 6,14.8",
        "M3.4,7 A7.5,7.5 0 0 0 3.4,17",
        "M18,9.2 A4,4 0 0 1 18,14.8",
        "M20.6,7 A7.5,7.5 0 0 1 20.6,17",
    )

    /** A trackpad: a rounded pad with its two buttons underneath. */
    val Trackpad: ImageVector = stroked(
        "trackpad",
        "M6,3 H18 A3,3 0 0 1 21,6 V14 A3,3 0 0 1 18,17 H6 A3,3 0 0 1 3,14 V6 A3,3 0 0 1 6,3 Z",
        "M6,21 H11",
        "M13,21 H18",
    )

    /** Two sliders, for settings. */
    val Sliders: ImageVector = stroked(
        "sliders",
        "M3.5,7.5 H13", "M18,7.5 H20.5",
        "M15.5,5 A2.5,2.5 0 1 1 15.5,10 A2.5,2.5 0 1 1 15.5,5 Z",
        "M3.5,16.5 H6", "M11,16.5 H20.5",
        "M8.5,14 A2.5,2.5 0 1 1 8.5,19 A2.5,2.5 0 1 1 8.5,14 Z",
    )

    /** A pulse line, for the live monitor. */
    val Pulse: ImageVector = stroked("pulse", "M2,12 H6 L9,5 L14,19 L17,10 L19,12 H22")

    /** An arrow rising out of a tray: send. */
    val Upload: ImageVector = stroked(
        "upload",
        "M12,15 V4", "M7.5,8.5 L12,4 L16.5,8.5",
        "M4,14 V18 A2,2 0 0 0 6,20 H18 A2,2 0 0 0 20,18 V14",
    )

    /** A clipboard: paste. */
    val Paste: ImageVector = stroked(
        "paste",
        "M9 5.5H7.5A1.5 1.5 0 0 0 6 7v12a1.5 1.5 0 0 0 1.5 1.5h9A1.5 1.5 0 0 0 18 19V7a1.5 1.5 0 0 0-1.5-1.5H15",
        "M10 3.5h4a1 1 0 0 1 1 1v1.5a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V4.5a1 1 0 0 1 1-1Z",
    )

    /** Two sheets: copy. */
    val Copy: ImageVector = stroked(
        "copy",
        "M15 8.5V6a1.5 1.5 0 0 0-1.5-1.5H6A1.5 1.5 0 0 0 4.5 6v7.5A1.5 1.5 0 0 0 6 15h2.5",
        "M10 8.5h8a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5h-8A1.5 1.5 0 0 1 8.5 18v-8A1.5 1.5 0 0 1 10 8.5Z",
    )

    /** A bin: clear. */
    val Trash: ImageVector = stroked(
        "trash",
        "M4.5 7h15",
        "M9.5 7V5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v2",
        "M6.5 7l.8 11.5A1.5 1.5 0 0 0 8.8 20h6.4a1.5 1.5 0 0 0 1.5-1.5L17.5 7",
    )

    /** An arrow going up and away: send. */
    val Send: ImageVector = stroked("send", "M12 19V5.5", "M6.5 11 12 5.5 17.5 11")

    /** A sun. */
    val Sun: ImageVector = stroked(
        "sun",
        "M12 8a4 4 0 1 1 0 8a4 4 0 1 1 0-8Z",
        "M12 2.5v2", "M12 19.5v2", "M2.5 12h2", "M19.5 12h2",
        "M5.3 5.3l1.4 1.4", "M17.3 17.3l1.4 1.4", "M5.3 18.7l1.4-1.4", "M17.3 6.7l1.4-1.4",
    )

    /** A handset: calling a linked phone (turned 135 degrees, hanging up). */
    val Call: ImageVector = stroked(
        "call",
        "M8.2 3.5h-2a2 2 0 0 0-2 2.2c.9 7.6 6.5 13.2 14.1 14.1a2 2 0 0 0 2.2-2v-2a1.5 1.5 0 0 0-1.2-1.5l-3-.7a1.5 1.5 0 0 0-1.5.5l-1.1 1.3a11 11 0 0 1-5.4-5.4l1.3-1.1a1.5 1.5 0 0 0 .5-1.5l-.7-3a1.5 1.5 0 0 0-1.5-1.2Z",
    )

    /** A video camera: a rounded body and its lens hood. */
    val Video: ImageVector = stroked(
        "video",
        "M5.5 6.5h8a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2Z",
        "M15.5 10.5l4.2-2.6a.5.5 0 0 1 .8.4v7.4a.5.5 0 0 1-.8.4l-4.2-2.6",
    )

    /** The video camera, struck through. */
    val VideoOff: ImageVector = stroked(
        "video-off",
        "M9.5 6.5h4a2 2 0 0 1 2 2v4",
        "M15.5 10.5l4.2-2.6a.5.5 0 0 1 .8.4v7.4a.5.5 0 0 1-.8.4l-3.2-2",
        "M13.8 17.5H5.5a2 2 0 0 1-2-2v-7a2 2 0 0 1 1.4-1.9",
        "M3.5 3.5l17 17",
    )

    /** A camera with arrows turning round it: the other camera. */
    val FlipCamera: ImageVector = stroked(
        "flip-camera",
        "M4.5 8.5a2 2 0 0 1 2-2h1.8l1.2-1.6a1 1 0 0 1 .8-.4h3.4a1 1 0 0 1 .8.4l1.2 1.6h1.8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2Z",
        "M14.6 11.2a2.9 2.9 0 0 0-5 .3",
        "M9.4 13.8a2.9 2.9 0 0 0 5-.3",
        "M14.6 9.6v1.6H13",
        "M9.4 15.4v-1.6H11",
    )

    /** A screen with an arrow rising out of it: share this screen. */
    val ScreenShare: ImageVector = stroked(
        "screen-share",
        "M4.5 5.5h15a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 16V7a1.5 1.5 0 0 1 1.5-1.5Z",
        "M9 20.5h6",
        "M12 14V9M9.8 11.2 12 9l2.2 2.2",
    )

    /** A person with a plus beside them. */
    val PersonAdd: ImageVector = stroked(
        "person-add",
        "M10 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z",
        "M3.5 20a6.5 6.5 0 0 1 11.6-4",
        "M18.5 14v6M15.5 17h6",
    )

    /** A microphone. */
    val Mic: ImageVector = stroked(
        "mic",
        "M12 3.5a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3Z",
        "M6 11a6 6 0 0 0 12 0",
        "M12 17v3.5",
    )

    /** The microphone, struck through. */
    val MicOff: ImageVector = stroked(
        "mic-off",
        "M15 10V6.5a3 3 0 0 0-5.6-1.5",
        "M9 9v2.5a3 3 0 0 0 4.6 2.5",
        "M6 11a6 6 0 0 0 9.4 4.9M18 11a6 6 0 0 1-.6 2.6",
        "M12 17v3.5",
        "M3.5 3.5l17 17",
    )

    /** A crescent. */
    val Moon: ImageVector = stroked("moon", "M19.5 14.5A8 8 0 1 1 9.5 4.5a6.5 6.5 0 0 0 10 10Z")

    /** A chevron pointing on. */
    val Chevron: ImageVector = stroked("chevron", "M9.5,5.5 L16,12 L9.5,18.5")

    /** A link: typing an address. */
    val Link: ImageVector = stroked(
        "link",
        "M10,14 A4,4 0 0 0 15.66,14 L18.49,11.17 A4,4 0 0 0 12.83,5.51 L11.5,6.84",
        "M14,10 A4,4 0 0 0 8.34,10 L5.51,12.83 A4,4 0 0 0 11.17,18.49 L12.5,17.16",
    )

    /** A lightning bolt: the direct link. */
    val Bolt: ImageVector = stroked("bolt", "M13.5 2.5 5 13.5h6.5l-1 8 8.5-11h-6.5l1-8Z")

    /** Waves over a dot: the phone's hotspot. */
    val Hotspot: ImageVector = stroked(
        "hotspot",
        "M12 13.2a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6Z",
        "M8.2 11.6a5.4 5.4 0 0 1 7.6 0",
        "M5.2 8.6a9.6 9.6 0 0 1 13.6 0",
        "M12 16v4.5",
    )

    /** Three arcs over a dot: Wi-Fi through the router. */
    val Wifi: ImageVector = stroked(
        "wifi",
        "M12 17.2a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6Z",
        "M8.3 14.6a5.2 5.2 0 0 1 7.4 0",
        "M5.2 11.5a9.6 9.6 0 0 1 13.6 0",
        "M2.3 8.4a13.8 13.8 0 0 1 19.4 0",
    )

    /** A USB plug on its cable. */
    val Usb: ImageVector = stroked(
        "usb",
        "M9.5 2.5h5v4h-5Z",
        "M7.5 6.5h9v6a2 2 0 0 1-2 2h-5a2 2 0 0 1-2-2Z",
        "M12 14.5v7",
    )

    /** A padlock: the trackpad, locked. */
    val Lock: ImageVector = stroked(
        "lock",
        "M6.5 10.5h11a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1Z",
        "M8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3",
        "M12 14.5v2.5",
    )

    /** A power symbol: Localhost 8787 on and off. */
    val Power: ImageVector = stroked("power", "M12 3v8.5", "M6.9 6.4a7.5 7.5 0 1 0 10.2 0")

    /** A square of squares: show the QR code. */
    val Qr: ImageVector = stroked(
        "qr",
        "M4 4h6v6H4Z", "M14 4h6v6h-6Z", "M4 14h6v6H4Z",
        "M14 14h2.5", "M20 14v2.5", "M14 20h6", "M17 17h3", "M14 17v3",
    )

    /** A laptop. */
    val Laptop: ImageVector = stroked(
        "laptop",
        "M5.5 5.5h13a1 1 0 0 1 1 1V16h-15V6.5a1 1 0 0 1 1-1Z",
        "M2.5 18.5h19",
    )

    /** A folder. */
    val Folder: ImageVector = stroked(
        "folder",
        "M3.5 7.5A1.5 1.5 0 0 1 5 6h4l2 2h8a1.5 1.5 0 0 1 1.5 1.5V17a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 17Z",
    )

    /** A document: a file on the phone. */
    val File: ImageVector = stroked(
        "file",
        "M13.5 3.5H7A1.5 1.5 0 0 0 5.5 5v14A1.5 1.5 0 0 0 7 20.5h10a1.5 1.5 0 0 0 1.5-1.5V8.5Z",
        "M13.5 3.5v5h5",
    )

    /** An arrow coming down into a tray: received. */
    val Download: ImageVector = stroked(
        "download",
        "M12 4v11", "M7.5 10.5 12 15l4.5-4.5",
        "M4 14v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4",
    )

    /** A cross. */
    val Close: ImageVector = stroked("close", "M6.5 6.5l11 11", "M17.5 6.5l-11 11")
    val Reply: ImageVector = stroked("reply", "M9.5 6.5L4 12l5.5 5.5", "M4 12h10a6 6 0 0 1 6 6v1")

    /** A tick. */
    val Check: ImageVector = stroked("check", "M5 12.5 10 17.5 19 7")

    /** A speech bubble: send text. */
    val Message: ImageVector = stroked(
        "message",
        "M5 5.5h14a1.5 1.5 0 0 1 1.5 1.5v8.5A1.5 1.5 0 0 1 19 17h-8l-4.5 3.5V17H5a1.5 1.5 0 0 1-1.5-1.5V7A1.5 1.5 0 0 1 5 5.5Z",
    )

    /** A half-filled circle: appearance. */
    val Contrast: ImageVector = stroked(
        "contrast",
        "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17Z",
        "M12 3.5v17",
        "M12 7.5h4.5", "M12 11.5h5.5", "M12 15.5h4.5",
    )

    /** Media keys: previous track, play or pause, next track. */
    val Prev: ImageVector = stroked("prev", "M6.5 6v12", "M18 6l-8.5 6 8.5 6Z")
    val Next: ImageVector = stroked("next", "M17.5 6v12", "M6 6l8.5 6-8.5 6Z")
    val PlayPause: ImageVector = stroked("playpause", "M4.5 6l7.5 6-7.5 6Z", "M15.5 6.5v11", "M19.5 6.5v11")

    /** A speaker with one wave, two waves, or crossed out: volume down, up, mute. */
    val VolumeDown: ImageVector = stroked("voldown", "M4 9.5h3.5L12 5.5v13l-4.5-4H4Z", "M15.5 9.5a3.5 3.5 0 0 1 0 5")
    val VolumeUp: ImageVector = stroked(
        "volup", "M4 9.5h3.5L12 5.5v13l-4.5-4H4Z", "M15.5 9.5a3.5 3.5 0 0 1 0 5", "M18 7a7 7 0 0 1 0 10",
    )
    val Mute: ImageVector = stroked("mute", "M4 9.5h3.5L12 5.5v13l-4.5-4H4Z", "M16 9.5l5 5", "M21 9.5l-5 5")

    /** A clock turning back: the clipboard's history. */
    val History: ImageVector = stroked(
        "history",
        "M4 12a8 8 0 1 0 2.4-5.7",
        "M4 4.5v4h4",
        "M12 7.5V12l3 2",
    )

    /** A framed landscape: a picture. */
    val Image: ImageVector = stroked(
        "image",
        "M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5Z",
        "M3.5 16l5-5 4 4 3-3 5 5",
        "M15.5 8.2a1.2 1.2 0 1 1 0 2.4a1.2 1.2 0 1 1 0-2.4Z",
    )

    /** Solid shapes for the big transport buttons, as Apple Music and Apple TV draw them. */
    val Play: ImageVector = filled("play", "M7.5 4.8v14.4a1 1 0 0 0 1.5.86l11.6-7.2a1 1 0 0 0 0-1.72L9 3.94a1 1 0 0 0-1.5.86Z")
    val Pause: ImageVector = filled(
        "pause",
        "M6.5 4.5h3a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1Z",
        "M14.5 4.5h3a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1Z",
    )
    /** Two solid triangles to a bar: the next song, and (mirrored) the one before. */
    val NextSolid: ImageVector = filled(
        "next-solid",
        "M2.5 6.3v11.4a1 1 0 0 0 1.55.83l8.2-5.7a1 1 0 0 0 0-1.66l-8.2-5.7A1 1 0 0 0 2.5 6.3Z",
        "M11.5 6.3v11.4a1 1 0 0 0 1.55.83l8.2-5.7a1 1 0 0 0 0-1.66l-8.2-5.7a1 1 0 0 0-1.55.83Z",
    )
    /** Three solid dots: more. */
    val Dots: ImageVector = filled(
        "dots",
        "M5 10.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6Z",
        "M12 10.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6Z",
        "M19 10.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6Z",
    )
    val PreviousSolid: ImageVector = filled(
        "previous-solid",
        "M21.5 6.3v11.4a1 1 0 0 1-1.55.83l-8.2-5.7a1 1 0 0 1 0-1.66l8.2-5.7a1 1 0 0 1 1.55.83Z",
        "M12.5 6.3v11.4a1 1 0 0 1-1.55.83l-8.2-5.7a1 1 0 0 1 0-1.66l8.2-5.7a1 1 0 0 1 1.55.83Z",
    )
    val Plus: ImageVector = stroked("plus", "M12 5v14", "M5 12h14")

    /** Two notes on a beam: music. */
    val Music: ImageVector = stroked(
        "music",
        "M9 18V5.5l11-2V16",
        "M6.5 15.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5Z",
        "M17.5 13.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5Z",
    )

    /** A magnifying glass: search. */
    val Search: ImageVector = stroked("search", "M10.5 4a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13Z", "M15.5 15.5 20 20")

    /** Two crossing arrows: shuffle. */
    val Shuffle: ImageVector = stroked(
        "shuffle",
        "M3 7h3.5c2.5 0 3.8 1.3 5.3 4.2l.4.6c1.5 2.9 2.8 4.2 5.3 4.2H21",
        "M3 17h3.5c1.4 0 2.4-.4 3.3-1.2M14.2 8.2C15.1 7.4 16.1 7 17.5 7H21",
        "M18.5 4.5 21 7l-2.5 2.5", "M18.5 13.5 21 16l-2.5 2.5",
    )

    /** Arrows going round: repeat. */
    val Repeat: ImageVector = stroked(
        "repeat",
        "M17 3l3 3-3 3", "M4 11V9.5A3.5 3.5 0 0 1 7.5 6H20",
        "M7 21l-3-3 3-3", "M20 13v1.5a3.5 3.5 0 0 1-3.5 3.5H4",
    )

    /** Lines of a list with a note: the queue. */
    val Queue: ImageVector = stroked(
        "queue",
        "M4 6h12", "M4 11h12", "M4 16h7",
        "M16.5 20.5a2 2 0 1 1 0-4a2 2 0 1 1 0 4Z", "M18.5 18.5V11l2.5 1",
    )

    /** A chevron pointing down: close what came up. */
    val ChevronDown: ImageVector = stroked("chevron-down", "M5.5 9.5 12 16l6.5-6.5")

    /** A chevron pointing back. */
    val Back: ImageVector = stroked("back", "M14.5 5.5 8 12l6.5 6.5")
    /** A pushpin: something kept, in the clipboard's history. */
    val Pushpin: ImageVector = stroked("pushpin", "M9 3.5h6l-1 6 3.5 3.5h-11L10 9.5ZM12 13v7.5")
    val Pin: ImageVector = stroked("pin", "M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11ZM12 12.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z")

    /** An arrow going round: look again. */
    val Refresh: ImageVector = stroked("refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.5v4h-4")

    /** Three short lines: a handle to drag by. */
    val Handle: ImageVector = stroked("handle", "M6 9h12", "M6 12h12", "M6 15h12")

    /** A disc: the album. */
    val Album: ImageVector = stroked(
        "album",
        "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17Z",
        "M12 10a2 2 0 1 1 0 4a2 2 0 1 1 0-4Z",
    )

    /** A pointer to where you are: the song playing, in a long list. */
    val Locate: ImageVector = stroked(
        "locate",
        "M12 7a5 5 0 1 1 0 10a5 5 0 1 1 0-10Z", "M12 3v2.5", "M12 18.5V21", "M3 12h2.5", "M18.5 12H21",
    )
    val More: ImageVector = filled(
        "more",
        "M5 10.3a1.7 1.7 0 1 1 0 3.4a1.7 1.7 0 1 1 0-3.4Z",
        "M12 10.3a1.7 1.7 0 1 1 0 3.4a1.7 1.7 0 1 1 0-3.4Z",
        "M19 10.3a1.7 1.7 0 1 1 0 3.4a1.7 1.7 0 1 1 0-3.4Z",
    )

    private fun filled(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d -> addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black)) }
        }.build()

    private fun stroked(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.9f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
