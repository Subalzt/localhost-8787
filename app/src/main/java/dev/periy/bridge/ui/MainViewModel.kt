package dev.periy.bridge.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.periy.bridge.container
import dev.periy.bridge.net.Address
import dev.periy.bridge.net.LinkEstimate
import dev.periy.bridge.net.NetInfo
import dev.periy.bridge.server.PairRequest
import dev.periy.bridge.server.PairedDevice
import dev.periy.bridge.server.Storage
import dev.periy.bridge.server.SystemClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UsbManager.ACTION_USB_STATE, which the SDK hides: sticky, with a "connected" extra. */
private const val USB_STATE = "android.hardware.usb.action.USB_STATE"

data class UiState(
    val addresses: List<Address> = emptyList(),
    val port: Int = 8787,
    val storageMode: Storage.Mode = Storage.Mode.NO_DESTINATION,
    val destination: String? = null,
    val freeSpace: Long = -1,
    val forceStagedCopy: Boolean = false,
    val notificationsGranted: Boolean = true,
    val batteryExempt: Boolean = false,
    val deviceName: String = "",
    val uploadStreams: Int = 4,
    val estimate: LinkEstimate? = null,
    /** A USB link is present but is not the address being advertised. */
    val fasterLink: Address? = null,
    /** A computer is on the USB cable, but USB tethering is off, so there is no link over it yet. */
    val cableNoTether: Boolean = false,
    /** The phone is running its own access point, so a computer can join it directly. */
    val hotspotActive: Boolean = false,
    /** Mobile data is the only route out, so nothing can reach this phone. */
    val onlyCellular: Boolean = false,
    /** Android's "All files access" is on, so the laptop page can browse the phone. */
    val browsable: Boolean = false,
    /** Clipboard follows between phone and laptop without pressing Send. */
    val clipSync: Boolean = true,
    /** New screenshots go on the shared clipboard; and whether Localhost 8787 may read the photos for it. */
    val screenshotClip: Boolean = true,
    val canReadPhotos: Boolean = false,
    /** Copies in any app reach the laptop at once: the log permission and the overlay (see ClipWatch). */
    val watchLogs: Boolean = false,
    val watchOverlay: Boolean = false,
    /** Android's "Notification access" is on for Localhost 8787, so the laptop page shows the phone's notifications. */
    val notifAccess: Boolean = false,
    /** Music permission granted, and how many tracks the library holds. */
    val musicGranted: Boolean = false,
    val musicTracks: Int = 0,
) {
    val primaryUrl: String? get() = addresses.firstOrNull()?.url(port)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshAddresses()
        override fun onLost(network: Network) = refreshAddresses()
    }

    /** Whether a computer (not just a charger) is on the USB port, from the sticky USB_STATE broadcast. */
    @Volatile
    private var usbHostConnected = false

    /**
     * USB tethering coming up does not change the default network, so the network callback
     * never hears of it. The USB state broadcast does: it fires when the cable goes in or out
     * and when the port's functions change (tethering on or off). The tethering interface gets
     * its address a moment after, so the addresses are read again a few times.
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            usbHostConnected = intent.getBooleanExtra("connected", false)
            viewModelScope.launch {
                for (wait in longArrayOf(0, 1_500, 4_000)) {
                    delay(wait)
                    refreshAddresses()
                }
            }
        }
    }

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        // A protected system broadcast: only Android can send it, so exporting is harmless.
        runCatching {
            ContextCompat.registerReceiver(
                app, usbReceiver, IntentFilter(USB_STATE), ContextCompat.RECEIVER_EXPORTED,
            )
        }
        refresh()
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        runCatching { getApplication<Application>().unregisterReceiver(usbReceiver) }
        super.onCleared()
    }

    private fun cableNoTether(addrs: List<Address>): Boolean =
        usbHostConnected && addrs.none { it.kind == dev.periy.bridge.net.LinkKind.USB }

    /** Full refresh. Called on resume, because permissions can change while we are away. */
    fun refresh() {
        val app = getApplication<Application>()
        val prefs = app.container.prefs
        viewModelScope.launch {
            val storageInfo = withContext(Dispatchers.IO) {
                app.container.storage.refresh()
                Triple(
                    app.container.storage.mode,
                    app.container.storage.destinationLabel,
                    app.container.storage.freeSpaceBytes(),
                )
            }
            val addrs = withContext(Dispatchers.IO) { NetInfo.addresses() }
            _state.value = _state.value.copy(
                addresses = addrs,
                port = prefs.port,
                storageMode = storageInfo.first,
                destination = storageInfo.second,
                freeSpace = storageInfo.third,
                forceStagedCopy = prefs.forceStagedCopy,
                notificationsGranted = notificationsGranted(),
                batteryExempt = OemBatterySetup.isIgnoringBatteryOptimizations(app),
                deviceName = app.container.deviceName(),
                uploadStreams = prefs.uploadStreams,
                estimate = withContext(Dispatchers.IO) { NetInfo.estimate(app) },
                fasterLink = NetInfo.fasterLinkAvailable(addrs.firstOrNull()),
                cableNoTether = cableNoTether(addrs),
                onlyCellular = NetInfo.onlyCellular(),
                hotspotActive = NetInfo.hotspotActive(),
                musicGranted = app.container.music.granted(),
                browsable = Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager(),
                clipSync = prefs.clipSync,
                screenshotClip = prefs.screenshotClip,
                canReadPhotos = dev.periy.bridge.server.canReadPhotos(app),
                watchLogs = dev.periy.bridge.server.ClipWatch.canReadLogs(app),
                watchOverlay = dev.periy.bridge.server.ClipWatch.canOverlay(app),
                notifAccess = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName),
                musicTracks = withContext(Dispatchers.IO) { app.container.music.tracks(refresh = true).size },
            )
        }
    }

    private fun refreshAddresses() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val addrs = withContext(Dispatchers.IO) { NetInfo.addresses() }
            _state.value = _state.value.copy(
                addresses = addrs,
                estimate = withContext(Dispatchers.IO) { NetInfo.estimate(app) },
                fasterLink = NetInfo.fasterLinkAvailable(addrs.firstOrNull()),
                cableNoTether = cableNoTether(addrs),
                onlyCellular = NetInfo.onlyCellular(),
                hotspotActive = NetInfo.hotspotActive(),
            )
        }
    }

    private fun notificationsGranted(): Boolean {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------------ actions

    /**
     * Persists the folder the user picked, and takes the permission across reboots.
     *
     * Without takePersistableUriPermission the grant dies with the activity, and the
     * failure mode is silent: the first upload after a restart fails at finalisation,
     * having already spent an hour moving bytes.
     */
    fun setDestination(uri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        app.container.prefs.treeUri = uri
        refresh()
    }

    // ------------------------------------------------------------------ connected computers

    /** Computers waiting for a tap on this phone. */
    val pairRequests: StateFlow<List<PairRequest>>
        get() = getApplication<Application>().container.pairing.pending

    /** "system", "light" or "dark": the app and every connected page follow it. */
    val theme: StateFlow<String>
        get() = getApplication<Application>().container.theme

    fun setTheme(value: String) = getApplication<Application>().container.setTheme(value)

    val look: StateFlow<dev.periy.bridge.Look>
        get() = getApplication<Application>().container.look


    fun setAccent(accent: String) = getApplication<Application>().container.setLook(accent = accent)

    // ------------------------------------------------------------------ direct link

    /** The phone's own offline network, for the laptop or another phone. */
    val direct: StateFlow<dev.periy.bridge.net.DirectLink.State>
        get() = getApplication<Application>().container.direct.state

    /** Starts Localhost 8787 too if it is off: the link is only useful with the server behind it. */
    fun startDirect() {
        val app = getApplication<Application>()
        dev.periy.bridge.service.BridgeService.start(app)
        app.container.direct.start(app.container.prefs.port)
    }

    fun stopDirect() = getApplication<Application>().container.direct.stop()

    private val _phoneDirect = MutableStateFlow(app.container.prefs.phoneDirect)
    /** Sends to other phones go over a direct link between the two. */
    val phoneDirect: StateFlow<Boolean> = _phoneDirect

    fun setPhoneDirect(on: Boolean) {
        getApplication<Application>().container.prefs.phoneDirect = on
        _phoneDirect.value = on
    }

    // ------------------------------------------------------------------ laptop link mode

    data class LaptopLink(val mode: String, val ssid: String, val pass: String)

    private val _laptopLink = MutableStateFlow(
        app.container.prefs.let { LaptopLink(it.laptopLink, it.hotspotSsid, it.hotspotPass) }
    )
    /** Direct link (fastest, laptop offline) or the phone's hotspot (the laptop keeps internet). */
    val laptopLink: StateFlow<LaptopLink> = _laptopLink

    fun setLaptopLink(mode: String? = null, ssid: String? = null, pass: String? = null) {
        val prefs = getApplication<Application>().container.prefs
        mode?.let { prefs.laptopLink = it }
        ssid?.let { prefs.hotspotSsid = it }
        pass?.let { prefs.hotspotPass = it }
        _laptopLink.value = LaptopLink(prefs.laptopLink, prefs.hotspotSsid, prefs.hotspotPass)
        // Open pages and the laptop helper look again.
        dev.periy.bridge.server.EventBus.emit("direct", "mode")
    }

    /** Every computer that has been allowed in. */
    val devices: StateFlow<List<PairedDevice>>
        get() = getApplication<Application>().container.devices.devices

    /** Device id -> open connections; present means the page is open right now. */
    val liveDevices: StateFlow<Map<String, Int>>
        get() = getApplication<Application>().container.devices.live

    fun approve(requestId: String) = getApplication<Application>().container.pairing.approve(requestId)

    fun deny(requestId: String) = getApplication<Application>().container.pairing.deny(requestId)

    /** Locks one computer out, effective on its very next request. */
    fun removeDevice(id: String) = getApplication<Application>().container.devices.remove(id)

    /**
     * Locks every computer out. Clearing the list is enough on its own; rotating the key as
     * well means even a cookie copied off a computer before today is worthless.
     */
    fun unpairAll() {
        val app = getApplication<Application>()
        app.container.devices.clear()
        app.container.prefs.rotateSessionKey()
        refresh()
    }

    /** Android's "Notification access" screen, where Localhost 8787 is allowed to see notifications. */
    fun notifAccessIntent(): Intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /** Android's "Display over other apps" screen for Localhost 8787, which the copy watch needs. */
    fun overlayIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getApplication<Application>().packageName))

    fun setScreenshotClip(on: Boolean) {
        getApplication<Application>().container.prefs.screenshotClip = on
        if (on) getApplication<Application>().container.prefs.clipSync = true
        refresh()
    }

    fun setClipSync(on: Boolean) {
        getApplication<Application>().container.prefs.clipSync = on
        dev.periy.bridge.server.EventBus.emit("clipsync", if (on) "on" else "off")
        refresh()
    }

    /** Android's own screen for "All files access", where browsing is allowed or taken back. */
    fun allFilesIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        val app = getApplication<Application>()
        return Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + app.packageName))
            .takeIf { runCatching { app.packageManager.resolveActivity(it, 0) != null }.getOrDefault(false) }
            ?: Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    fun setForceStagedCopy(force: Boolean) {
        getApplication<Application>().container.prefs.forceStagedCopy = force
        refresh()
    }

    /**
     * Default parallel connection count offered to the browser. The page may override it
     * locally; this is what a freshly opened tab starts from.
     */
    fun setUploadStreams(n: Int) {
        getApplication<Application>().container.prefs.uploadStreams = n
        refresh()
    }

    /**
     * The direct battery-exemption prompt, or null when this ROM will not surface it.
     * Exposed from here because `getApplication()` is protected and the UI has no other
     * handle on an Application context.
     */
    fun batteryOptimizationIntent(): Intent? =
        OemBatterySetup.requestIgnoreBatteryOptimizations(getApplication())

    /**
     * Opens the hotspot / tethering settings screen.
     *
     * There is no public action for this, and vendors move it, so several candidates are
     * tried in order and each is resolved before use -- the same approach the OEM
     * battery walkthrough takes, for the same reason: launching a vendor activity that
     * no longer exists is an instant crash.
     */
    fun tetherSettingsIntent(): Intent? {
        val app = getApplication<Application>()
        val candidates = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$TetherSettingsActivity",
            ),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
        )
        return candidates.firstOrNull { intent ->
            runCatching {
                @Suppress("DEPRECATION")
                app.packageManager.resolveActivity(intent, 0) != null
            }.getOrDefault(false)
        }
    }

    // ------------------------------------------------------------------ phone to PC

    /** Files the computer can download, newest first. */
    val files: StateFlow<List<dev.periy.bridge.server.FileEntry>>
        get() = getApplication<Application>().container.index.flow

    /** Off the list only; the file itself stays where it is. */
    fun forgetFile(id: String) { getApplication<Application>().container.index.remove(id) }

    fun clearFiles() = getApplication<Application>().container.index.clear()

    private val _sendStatus = MutableStateFlow("")
    val sendStatus: StateFlow<String> = _sendStatus

    /**
     * Publishes files the user picked with the system document picker.
     *
     * Nothing is copied. The picker is the one source that grants a permission we can
     * keep, so the file is served straight from where it already lives -- which is why
     * offering a 4 GB video costs nothing and is instant.
     */
    fun offerPickedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            var added = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    runCatching {
                        app.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    runCatching {
                        app.container.index.add(app.container.storage.adopt(uri))
                        added++
                    }
                }
            }
            _sendStatus.value = if (added == 0) "Could not read those files"
            else "$added file${if (added == 1) "" else "s"} ready on the computer"
            clearSendStatusLater()
        }
    }

    /**
     * Takes files handed over by another app through the share sheet.
     *
     * These have to be copied, unlike picked files. A share grant is scoped to the task
     * that received it and Android revokes it afterwards, so serving the original later
     * would fail silently once the grant expired. The copy happens while the grant is
     * still alive.
     */
    fun acceptSharedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        if (!app.container.storage.hasDestination()) {
            _sendStatus.value = "Choose a destination folder first"
            clearSendStatusLater()
            return
        }
        viewModelScope.launch {
            var added = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    runCatching {
                        val id = "share-" + System.nanoTime()
                        app.container.index.add(app.container.storage.importCopy(uri, id))
                        added++
                    }.onFailure { android.util.Log.w("Localhost 8787", "Share import failed", it) }
                }
            }
            _sendStatus.value = if (added == 0) "Could not copy those files"
            else "$added file${if (added == 1) "" else "s"} ready on the computer"
            clearSendStatusLater()
        }
    }

    fun acceptSharedText(text: String) {
        getApplication<Application>().container.clipboard.set(text)
        _sendStatus.value = "Text sent to the computer"
        clearSendStatusLater()
    }

    private fun clearSendStatusLater() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _sendStatus.value = ""
        }
    }

    // ------------------------------------------------------------------ clipboard

    /** The shared text slot, live in both directions. */
    val clipboard: StateFlow<String>
        get() = getApplication<Application>().container.clipboard.flow

    private val _clipStatus = MutableStateFlow("")
    val clipStatus: StateFlow<String> = _clipStatus

    /**
     * Publishes text to the PC. The browser sees it over SSE within a frame or two. Typing
     * calls this by itself, so it only speaks up when something went wrong.
     */
    fun sendClipboard(text: String) {
        val ok = getApplication<Application>().container.clipboard.set(text)
        if (!ok) flashClip("Too long to send")
    }

    /** Recent clipboard items, newest first. */
    val clipHistory: StateFlow<List<dev.periy.bridge.server.ClipMeta>>
        get() = getApplication<Application>().container.clipboard.history

    fun historyFile(v: Long): java.io.File? = getApplication<Application>().container.clipboard.historyBlob(v)

    /** Puts a history item back on the clipboard: here, on the phone's own, and on the computer. */
    fun reuseClip(v: Long) {
        val app = getApplication<Application>()
        val c = app.container.clipboard
        val m = c.reuse(v) ?: return
        c.blob()?.let { SystemClipboard.writeFile(app, it, m.name, m.mime) } ?: SystemClipboard.write(app, m.text)
        flashClip("Back on the clipboard")
    }

    fun forgetClip(v: Long) {
        val app = getApplication<Application>()
        val wasCurrent = app.container.clipboard.meta.value.v == v
        app.container.clipboard.forget(v)
        if (wasCurrent) SystemClipboard.clear(app)
    }

    fun forgetAllClips() = getApplication<Application>().container.clipboard.forgetAll()

    /** The picture or file on the shared clipboard, for the panel's preview. */
    fun clipFile(): java.io.File? = getApplication<Application>().container.clipboard.blob()

    /**
     * Copies the phone's system clipboard into the shared slot.
     *
     * This is the phone-to-PC direction, and it has to be a button. Android 10 and later
     * refuse a clipboard read to any app that is not in the foreground, so the read is
     * only legal at the moment the user taps -- see [dev.periy.bridge.server.SystemClipboard].
     * Making that a visible, deliberate action is the honest design, not a workaround.
     */
    fun pasteFromDevice() {
        // Text, a picture or a file: whatever was copied last on the phone.
        flashClip(ClipSync.sendFromPhone(getApplication(), always = true)
            .replace("the laptop", "the computer").ifEmpty { "Nothing in the phone clipboard" })
    }

    /** What the shared clipboard holds, for the panel: text, a picture, a file. */
    val clipMeta: StateFlow<dev.periy.bridge.server.ClipMeta>
        get() = getApplication<Application>().container.clipboard.meta

    /** Puts what is on the shared clipboard onto the phone's own, ready to paste anywhere. */
    fun copyToDevice() {
        val app = getApplication<Application>()
        val c = app.container.clipboard
        val m = c.meta.value
        val file = c.blob()
        when {
            file != null -> SystemClipboard.writeFile(app, file, m.name, m.mime)
            m.text.isNotEmpty() -> SystemClipboard.write(app, m.text)
            else -> { flashClip("Nothing to copy"); return }
        }
        flashClip("Copied to the phone clipboard")
    }

    /** Empties the shared clipboard everywhere, and the phone's own. */
    fun clearClipboard() {
        val app = getApplication<Application>()
        app.container.clipboard.set("")
        SystemClipboard.clear(app)
        flashClip("Cleared")
    }

    private fun flashClip(msg: String) {
        _clipStatus.value = msg
        viewModelScope.launch {
            kotlinx.coroutines.delay(2500)
            if (_clipStatus.value == msg) _clipStatus.value = ""
        }
    }
}
