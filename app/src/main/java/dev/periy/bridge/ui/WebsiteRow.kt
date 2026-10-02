package dev.periy.bridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.net.Site

/**
 * The phone as a website (docs/website.md): the switch, and under it what it needs, typed here by
 * the person: the name made at dynv6, its token, a PIN, and their own "I agree" to Let's Encrypt's
 * terms. The token and the PIN never show once saved.
 */
@Composable
fun WebsiteRow() {
    val ctx = LocalContext.current
    val site = (ctx.applicationContext as dev.periy.bridge.BridgeApp).container.site
    val s by site.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val detail = when {
        s.on && s.serving -> site.url.removePrefix("https://")
        s.on && s.status.isNotEmpty() -> s.status
        s.name.isNotEmpty() -> s.name + " · off"
        else -> "From any browser, with a PIN"
    }
    SettingRow("Website", detail, icon = BlazeIcons.Lock, iconColor = Bridge.Indigo, onClick = { open = !open }) {
        Toggle(s.on) { on ->
            site.setOn(on)
            if (on && !(s.hasToken && s.hasPin && s.agreed && s.name.isNotEmpty())) open = true
        }
    }
    AnimatedVisibility(open) { WebsiteFields(site, s) }
}

@Composable
private fun WebsiteFields(site: Site, s: Site.State) {
    val ctx = LocalContext.current
    var name by rememberSaveable { mutableStateOf(s.name) }
    var token by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(s.agreed) }
    Column(Modifier.padding(start = 60.dp, end = 16.dp, bottom = 16.dp)) {
        Text(
            "Make a free name at dynv6 (yourname.dynv6.net) and an HTTP token (Keys, in its menu), then type both here. " +
                "The phone keeps the name pointing at it, gets an HTTPS certificate from Let's Encrypt, and opens " +
                "https://yourname.dynv6.net:${Site.PORT} to any browser that knows the PIN. Needs IPv6 where the browser is.",
            style = CaptionStyle, color = Bridge.Muted,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftButton("Open dynv6", onClick = { open(ctx, "https://dynv6.com/users/sign_up") })
        }
        Spacer(Modifier.height(12.dp))
        BridgeTextField(name, { name = it }, placeholder = "yourname.dynv6.net", minHeight = 46.dp, mono = true)
        Spacer(Modifier.height(8.dp))
        SecretField(token, { token = it }, if (s.hasToken) "Token saved · type to replace" else "dynv6 HTTP token")
        Spacer(Modifier.height(8.dp))
        SecretField(pin, { pin = it.filter(Char::isDigit).take(12) }, if (s.hasPin) "PIN set · type to change" else "PIN, 6 digits or more", digits = true)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Toggle(agreed) { agreed = it }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("I agree to Let's Encrypt's Subscriber Agreement", style = CaptionStyle.copy(fontSize = 14.sp), color = Bridge.Text)
                Text(
                    "Read it", style = CaptionStyle, color = Bridge.Blue,
                    modifier = Modifier.clickable { open(ctx, Site.AGREEMENT) }.padding(vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val pinOk = pin.isEmpty() || pin.length >= 6
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftButton("Save", tint = Bridge.Blue, onClick = {
                if (!pinOk) { Toast.makeText(ctx, "The PIN needs 6 digits or more", Toast.LENGTH_SHORT).show(); return@SoftButton }
                site.configure(name, token, pin, agreed)
                token = ""; pin = ""
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
            })
            if (s.serving) {
                Spacer(Modifier.width(10.dp))
                SoftButton("Copy address", onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Website", site.url))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                })
            }
        }
        if (s.on || s.status.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            if (s.status.isNotEmpty()) Text(s.status, style = CaptionStyle, color = if (s.problem) Bridge.Orange else Bridge.Muted)
            if (s.address.isNotEmpty()) Text("Pointing at ${s.address}", style = CaptionStyle, color = Bridge.Muted)
            if (s.certUntil > 0) Text(
                "Certificate until " + java.text.DateFormat.getDateInstance().format(java.util.Date(s.certUntil)),
                style = CaptionStyle, color = Bridge.Muted,
            )
        }
    }
}

/** A field whose text never shows: the token, the PIN. */
@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, placeholder: String, digits: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp)).background(Bridge.Chip)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = BodyStyle.copy(fontSize = 15.sp), color = Bridge.Faint)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.NumberPassword else KeyboardType.Password),
            textStyle = MonoStyle.copy(color = Bridge.Text),
            cursorBrush = SolidColor(Bridge.Blue),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun open(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
