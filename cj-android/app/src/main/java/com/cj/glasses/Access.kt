package com.cj.glasses

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Setup → Accessibility: switches for blind and low-vision users, and for anyone who wants CJ slower, shorter
 * or more careful. The glasses features need the glasses camera, which is asked for when one is switched on.
 */
@Composable
fun AccessibilitySection(store: Store, glasses: Glasses, controls: GlassesControls) {
    val scope = rememberCoroutineScope()
    var readAloud by remember { mutableStateOf(store.readAloud) }
    var money by remember { mutableStateOf(store.moneyReader) }
    var describe by remember { mutableStateOf(store.describeSurroundings) }
    var medicine by remember { mutableStateOf(store.medicineLabels) }
    var slowSpeech by remember { mutableStateOf(store.slowSpeech) }
    var confirm by remember { mutableStateOf(store.confirmFirst) }
    var speed by remember { mutableFloatStateOf(store.speechSpeed) }
    var detail by remember { mutableStateOf(store.detail) }
    var cameraNote by remember { mutableStateOf<String?>(null) }

    /** Switching on a glasses feature asks for the glasses camera if CJ doesn't have it yet. */
    fun needsCamera(on: Boolean) {
        if (!on) return
        scope.launch {
            cameraNote = when {
                !Core.glassesLinked() -> "Link your glasses under Glasses above so CJ can see."
                glasses.hasCameraPermission() -> null
                controls.requestCamera() -> null
                else -> "CJ needs the glasses camera for this. Allow it under Permissions above."
            }
            Perms.tick++
        }
    }

    Section("Accessibility") {
        Hint("For blind and low-vision users, and anyone who wants CJ slower, shorter or more careful. " +
            "Everything here is off until you switch it on.")

        Text("With your glasses", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Toggle("Read aloud", "Reads signs, labels, menus and letters word for word. Say \"read this\".", readAloud) {
            readAloud = it; store.readAloud = it; needsCamera(it)
        }
        Toggle("Money reader", "Names each note and says the total. Say \"how much money is this?\"", money) {
            money = it; store.moneyReader = it; needsCamera(it)
        }
        Toggle("Describe surroundings", "Starts with steps, obstacles and people close by, with clock directions. " +
            "Say \"what's in front of me?\"", describe) {
            describe = it; store.describeSurroundings = it; needsCamera(it)
        }
        Toggle("Medicine labels", "Reads the name, dose and expiry date, and warns you when it has expired. " +
            "Say \"read this medicine\".", medicine) {
            medicine = it; store.medicineLabels = it; needsCamera(it)
        }
        cameraNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        Text("Talking with CJ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Toggle("Slow speech", "Waits longer before deciding you've finished talking.", slowSpeech) {
            slowSpeech = it; store.slowSpeech = it
        }
        Toggle("Confirm before acting", "Always checks with you before calling, messaging, paying or ordering.", confirm) {
            confirm = it; store.confirmFirst = it
        }
        val shown = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "×"
        Text("Speaking speed: $shown")
        Slider(value = speed, onValueChange = { speed = (it * 4).let(Math::round) / 4f },
            onValueChangeFinished = { store.speechSpeed = speed }, valueRange = 0.75f..1.5f, steps = 2,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = "$shown speed" })
        Hint("Takes effect from CJ's next answer.")

        Text("Answer length")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in listOf("short" to "Short", "normal" to "Normal", "detailed" to "Detailed")) {
                FilterChip(selected = detail == value, onClick = { detail = value; store.detail = value },
                    label = { Text(label) })
            }
        }

        SosSettings(store)

        Text("Coming soon", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Toggle("Sound alerts", "Doorbell, alarms or your name called, as a vibration on your phone.", false, enabled = false) {}
        Toggle("Medicine reminders", "Reminds you to take a dose and waits for \"taken\".", false, enabled = false) {}
    }
}

/** SOS: the contact, what to do, and the permissions it needs (asked when you switch it on). */
@Composable
private fun SosSettings(store: Store) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(store.sosOn) }
    var name by remember { mutableStateOf(store.sosName) }
    var number by remember { mutableStateOf(store.sosNumber) }
    var text by remember { mutableStateOf(store.sosText) }
    var call by remember { mutableStateOf(store.sosCall) }
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Perms.tick++ }
    val pick = rememberLauncherForActivityResult(PickPhoneNumber()) { uri ->
        pickedContact(context, uri)?.let { (n, num) ->
            name = n; number = num; store.sosName = n; store.sosNumber = num
        }
    }
    val tick = Perms.tick
    val missing = remember(tick, on) {
        listOf(Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }

    Text("Emergency", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Toggle("SOS", "Say \"CJ, help\" or tap SOS. After a five-second countdown you can cancel, CJ texts your " +
        "contact where you are and calls them.", on) {
        on = it; store.sosOn = it
        if (it && missing.isNotEmpty()) allow.launch(missing.toTypedArray())
        if (it && number.isBlank()) pick.launch(Unit)
    }
    if (on) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (number.isBlank()) "No contact chosen" else name.ifBlank { number })
                if (number.isNotBlank() && name.isNotBlank()) Hint(number)
            }
            FilledTonalButton(onClick = { pick.launch(Unit) }) { Text(if (number.isBlank()) "Choose contact" else "Change") }
        }
        Toggle("Text my location", "A map link to where you are.", text) { text = it; store.sosText = it }
        Toggle("Then call them", "The call starts right after the text.", call) { call = it; store.sosCall = it }
        if (missing.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SOS needs permission to text, call and find your location.", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { allow.launch(missing.toTypedArray()) }) { Text("Allow") }
            }
        }
        Hint("SOS doesn't replace emergency services. If you're in danger, call 112.")
    }
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Hint(hint)
        }
        val colors = MaterialTheme.colorScheme
        // The theme's default "off" switch is nearly invisible on these cards; this one reads at a glance.
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = SwitchDefaults.colors(
            uncheckedThumbColor = colors.onSurfaceVariant, uncheckedBorderColor = colors.onSurfaceVariant,
            uncheckedTrackColor = colors.surfaceContainer))
    }
}
