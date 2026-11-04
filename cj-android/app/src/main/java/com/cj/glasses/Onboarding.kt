package com.cj.glasses

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything CJ needs from Android. Asked one by one on first launch, and listed again in Setup → Permissions. */
enum class Need(val title: String, val why: String) {
    MIC("Microphone", "So CJ can hear you when you say \"CJ\"."),
    BLUETOOTH("Bluetooth", "To connect to your glasses."),
    GLASSES("Glasses camera", "So CJ can see what you see. Your glasses are linked through the Meta AI app."),
    LOCATION("Location", "For travel times, places nearby and reminders where you need them."),
    CALLS("Phone calls", "So CJ can call someone for you, after you say yes."),
    NOTIFICATIONS("Notifications", "Android needs this to keep CJ listening with the screen off."),
    AGENT("Phone agent", "Lets CJ tap through apps for you, like booking a cab. Android keeps this switch in Accessibility settings."),
    BATTERY("Run in the background", "Stops Android from closing CJ to save battery, so \"CJ\" always works.");

    /** The runtime permissions behind this need; empty when Android grants it some other way (or not at all on this version). */
    val runtime: List<String>
        get() = when (this) {
            MIC -> listOf(Manifest.permission.RECORD_AUDIO)
            BLUETOOTH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else emptyList()
            LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            CALLS -> listOf(Manifest.permission.CALL_PHONE)
            NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            else -> emptyList()
        }

    /** Whether Android allows it right now. The glasses camera is checked separately: see [glassesStep]. */
    fun granted(context: Context): Boolean = when (this) {
        AGENT -> agentEnabled(context)
        BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        LOCATION -> has(context, Manifest.permission.ACCESS_COARSE_LOCATION)  // "approximate" is enough
        GLASSES -> false
        else -> runtime.all { has(context, it) }
    }
}

object Perms {
    /** Bumped whenever CJ comes back to the front, so the permission screens re-check what Android now allows. */
    var tick by mutableIntStateOf(0)
}

private fun has(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Reads the system switch rather than [AgentService.instance], which lags a moment behind it. */
fun agentEnabled(context: Context): Boolean {
    val me = ComponentName(context, AgentService::class.java).flattenToString()
    val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return AgentService.instance != null || on.split(':').any { it.equals(me, ignoreCase = true) }
}

/** The glasses SDK needs Bluetooth before it can start; call again once Bluetooth is allowed. */
fun initGlasses(context: Context) {
    if (!Core.glasses.ready.value && Need.BLUETOOTH.granted(context)) Core.glasses.initialize()
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

private fun appInfo(context: Context) =
    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

private enum class GlassesStep { NEEDS_BLUETOOTH, NOT_LINKED, LINKING, NEEDS_CAMERA, DONE }

@Composable
private fun glassesStep(glasses: Glasses): GlassesStep {
    val ready by glasses.ready.collectAsState()
    if (!ready) return GlassesStep.NEEDS_BLUETOOTH
    val registration by glasses.registration.collectAsState()
    var camera by remember { mutableStateOf(false) }
    LaunchedEffect(registration, Perms.tick) {
        camera = registration == RegistrationState.REGISTERED && glasses.hasCameraPermission()
    }
    return when {
        registration == RegistrationState.REGISTERING -> GlassesStep.LINKING
        registration != RegistrationState.REGISTERED -> GlassesStep.NOT_LINKED
        !camera -> GlassesStep.NEEDS_CAMERA
        else -> GlassesStep.DONE
    }
}

@Composable
private fun isGranted(need: Need, glasses: Glasses): Boolean {
    if (need == Need.GLASSES) return glassesStep(glasses) == GlassesStep.DONE
    val context = LocalContext.current
    val tick = Perms.tick
    return remember(need, tick) { need.granted(context) }
}

/** The one button that gets [need] granted: a permission prompt, the right settings page, or the glasses flow. */
@Composable
private fun NeedButton(need: Need, glasses: Glasses, controls: GlassesControls, primary: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        if (need == Need.BLUETOOTH) initGlasses(context)
        Perms.tick++
    }
    val glassesStep = if (need == Need.GLASSES) glassesStep(glasses) else null
    // Once you've said no twice, Android stops showing the prompt; only the app's settings page can change it then.
    val blocked = asked && need.runtime.isNotEmpty() && !need.granted(context) &&
        need.runtime.none { (context as? Activity)?.shouldShowRequestPermissionRationale(it) == true }

    val choice: Pair<String, () -> Unit> = when {
        blocked -> "Open app settings" to { appInfo(context) }
        need == Need.AGENT -> "Open Accessibility settings" to { open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        need == Need.BATTERY -> "Allow" to {
            open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        }
        need == Need.GLASSES -> when (glassesStep) {
            GlassesStep.NEEDS_BLUETOOTH -> "Allow Bluetooth first" to { launcher.launch(Need.BLUETOOTH.runtime.toTypedArray()) }
            GlassesStep.NOT_LINKED -> "Link glasses" to { controls.register() }
            GlassesStep.LINKING -> "Finish in the Meta AI app" to { controls.register() }
            else -> "Allow camera" to { scope.launch { controls.requestCamera(); Perms.tick++ } }
        }
        else -> "Allow" to { launcher.launch(need.runtime.toTypedArray()) }
    }
    val (label, action) = choice
    if (primary) Button(onClick = action, Modifier.fillMaxWidth().height(56.dp)) { Text(label, fontSize = 18.sp) }
    else FilledTonalButton(onClick = action) { Text("Fix") }  // the row already says what it's for
}

@Composable
private fun NeedHint(need: Need, glasses: Glasses) {
    val text = when (need) {
        Need.AGENT -> "Tap \"CJ phone agent\" and turn it on. If Android says \"Restricted setting\", open App info, " +
            "tap ⋮ and choose \"Allow restricted settings\", then try again."
        Need.GLASSES -> when (glassesStep(glasses)) {
            GlassesStep.NOT_LINKED -> "First turn on Developer Mode in the Meta AI app: Settings → App info → tap the version number 5 times."
            GlassesStep.LINKING -> "Finish linking in the Meta AI app, then come back."
            GlassesStep.NEEDS_CAMERA -> "Turn on and unfold your glasses, then allow the camera."
            else -> null
        }
        Need.LOCATION -> "\"While using the app\" is enough. Approximate location works too."
        else -> null
    } ?: return
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Dot(on: Boolean) {
    Box(Modifier.size(12.dp).clip(CircleShape)
        .background(if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline))
}

/** First launch: one need per screen, each with a reason, Allow and Skip, then a checklist. */
@Composable
fun Onboarding(glasses: Glasses, controls: GlassesControls, onDone: () -> Unit) {
    val needs = Need.entries
    var step by rememberSaveable { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme

    // Surface, not a background modifier: it also gives the text its light colour.
    Surface(Modifier.fillMaxSize(), color = colors.background) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)
        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (step in 1..needs.size) {
            LinearProgressIndicator(progress = { step / needs.size.toFloat() }, Modifier.fillMaxWidth())
            Text("Step $step of ${needs.size}", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
        when (step) {
            0 -> {
                Spacer(Modifier.height(48.dp))
                Text("CJ", fontSize = 64.sp, fontWeight = FontWeight.Black, color = colors.primary)
                Text("Your hands-free assistant for Ray-Ban Meta glasses.", fontSize = 22.sp)
                Text("CJ needs a few permissions to hear you, see through your glasses and do things for you. " +
                    "We'll ask for each one now, so you don't have to look for them later. You can skip any of them.",
                    style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { step = 1 }, Modifier.fillMaxWidth().height(56.dp)) { Text("Get started", fontSize = 18.sp) }
            }
            in 1..needs.size -> {
                val need = needs[step - 1]
                val granted = isGranted(need, glasses)
                // Already allowed (or allowed just now): move on by itself.
                LaunchedEffect(need, granted) { if (granted) { delay(500); step++ } }
                Spacer(Modifier.height(24.dp))
                Text(need.title, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(need.why, fontSize = 20.sp)
                NeedHint(need, glasses)
                Spacer(Modifier.height(16.dp))
                if (granted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(true); Spacer(Modifier.width(10.dp)); Text("Allowed", fontSize = 18.sp)
                    }
                } else {
                    NeedButton(need, glasses, controls, primary = true)
                    TextButton(onClick = { step++ }, Modifier.fillMaxWidth()) { Text("Skip for now", fontSize = 16.sp) }
                }
            }
            else -> {
                Spacer(Modifier.height(24.dp))
                Text("All set", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("You can change any of these later in Setup → Permissions.",
                    style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                PermissionList(glasses, controls)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDone, Modifier.fillMaxWidth().height(56.dp)) { Text("Start using CJ", fontSize = 18.sp) }
            }
        }
    }
    }
}

/** A row per need: a dot, what it's for, and a Fix button while it's missing. */
@Composable
fun PermissionList(glasses: Glasses, controls: GlassesControls) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (need in Need.entries) {
            val granted = isGranted(need, glasses)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Dot(granted)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(need.title, fontWeight = FontWeight.SemiBold)
                    Text(if (granted) "Allowed" else need.why, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!granted) NeedButton(need, glasses, controls, primary = false)
            }
        }
    }
}
