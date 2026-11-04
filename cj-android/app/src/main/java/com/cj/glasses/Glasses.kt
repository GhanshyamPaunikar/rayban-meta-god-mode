package com.cj.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ExifInterface
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.HingeState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** [final]: retrying won't help (no glasses linked, camera access denied, an update needed…). */
class GlassesException(message: String, val final: Boolean = false) : Exception(message)

/** A photo from the glasses: the bitmap to show on the phone and the JPEG to send to the brain. */
class Snapshot(val bitmap: Bitmap, val jpegBase64: String)

/**
 * Ray-Ban Meta glasses through Meta's Wearables Device Access Toolkit (developer preview).
 * The user links CJ to the glasses once in the Meta AI app (registration); after that CJ opens a device
 * session on demand and grabs a photo from a short camera stream. The glasses' microphone and speakers
 * are ordinary Bluetooth audio, handled in Voice.kt.
 */
class Glasses(private val context: Context) {
    private val _ready = MutableStateFlow(false)
    /** True once the SDK is initialized; [registration] and [devices] may only be read after that. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    val registration: StateFlow<RegistrationState> get() = Wearables.registrationState
    val devices: StateFlow<Set<DeviceIdentifier>> get() = Wearables.devices

    private val lock = Mutex()
    private var session: DeviceSession? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    /**
     * True while a photo is being set up. The glasses' call link (the mic, HFP/SCO) is dropped for that moment, and
     * the hands-free service must not put it back until the camera is running.
     */
    @Volatile var busy = false
        private set

    /** Needs BLUETOOTH_CONNECT on Android 12+. Returns an error message, or null when ready. */
    fun initialize(): String? = Wearables.initialize(context).fold(
        { _ready.value = true; null },
        { error, _ ->
            if (error == WearablesError.ALREADY_INITIALIZED) { _ready.value = true; null } else error.description
        },
    )

    suspend fun hasCameraPermission(): Boolean =
        Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() == PermissionStatus.Granted

    /**
     * Photo from the glasses. Bluetooth hiccups are common (the link drops for a second, the glasses are still waking
     * up), so it keeps trying with a short pause between attempts for up to [PHOTO_DEADLINE_MS].
     */
    suspend fun takePhoto(): Snapshot = lock.withLock {
        val deadline = SystemClock.elapsedRealtime() + PHOTO_DEADLINE_MS
        var attempt = 0
        var last: GlassesException? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            attempt++
            try {
                return@withLock takePhotoOnce().also { Trail.log("camera: photo taken (attempt $attempt)") }
            } catch (e: GlassesException) {
                last = e
                Trail.log("camera: attempt $attempt failed: ${e.message}")
                disconnect()
                if (e.final) break
            }
            delay(minOf(3_000L, 500L * attempt, maxOf(0L, deadline - SystemClock.elapsedRealtime())))
        }
        throw last ?: GlassesException(unavailable())
    }

    private suspend fun takePhotoOnce(): Snapshot {
        val camera = withCallLinkPaused {
            val current = connect()
            val camera = current.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15))
                .fold({ it }, { error, _ -> throw GlassesException("Couldn't open the glasses camera: ${error.description}") })
            try {
                camera.stream.start().onFailure { error, _ -> throw GlassesException("Camera didn't start: ${error.description}") }
                withTimeoutOrNull(CAMERA_START_MS) {
                    camera.stream.state.first { it == StreamState.STREAMING || it == StreamState.STARTED }
                } ?: throw GlassesException("The glasses camera took too long to start.")
            } catch (e: Exception) {
                camera.stop()
                throw e
            }
            camera
        }
        try {
            val photo = camera.stream.capturePhoto()
                .fold({ it }, { error, _ -> throw GlassesException("Photo failed: ${error.description}") })
            return withContext(Dispatchers.Default) { encode(photo) }
        } finally {
            camera.stop()
        }
    }

    /**
     * The glasses carry the mic (call link, HFP/SCO) and the camera over the same Bluetooth connection, and the
     * camera can't finish its handshake while the call link is up. So it's dropped for that moment (the mic falls
     * back to the phone for a second or two) and restored right after, whether the camera started or not.
     */
    private suspend fun <T> withCallLinkPaused(block: suspend () -> T): T {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            audio.communicationDevice?.takeIf { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } else null
        busy = true
        try {
            if (device != null) {
                audio.clearCommunicationDevice()
                audio.mode = AudioManager.MODE_NORMAL
                delay(CALL_LINK_SETTLE_MS)  // let Android actually take the call link down
                Trail.log("camera: call link paused for the handshake")
            }
            return block()
        } finally {
            if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                val back = GlassesMic.glassesFirst(audio.availableCommunicationDevices)
                if (back == null || !audio.setCommunicationDevice(back)) Trail.log("camera: call link didn't come back yet")
            }
            busy = false
        }
    }

    /**
     * The glasses to use: the one whose link is up, else the first one paired. Picked by hand because the SDK's
     * automatic choice waits for a "worn" signal from the glasses' sensor and fails with NO_ELIGIBLE_DEVICE
     * when that signal hasn't arrived yet. Waits a few seconds for the link, and says why when it can't.
     */
    private suspend fun connectedGlasses(): DeviceIdentifier {
        val until = SystemClock.elapsedRealtime() + LINK_WAIT_MS
        while (true) {
            val all = Wearables.devices.value
            val id = all.firstOrNull { metadata(it)?.linkState == LinkState.CONNECTED } ?: all.firstOrNull()
            val meta = id?.let(::metadata)
            if (id != null && meta?.linkState == LinkState.CONNECTED && meta.hingeState != HingeState.CLOSED) return id
            if (SystemClock.elapsedRealtime() >= until) throw GlassesException(unavailable(), final = id == null)
            delay(500)
        }
    }

    private fun metadata(id: DeviceIdentifier) = Wearables.devicesMetadata[id]?.value

    /** The most useful reason the glasses can't be reached right now. */
    private fun unavailable(): String {
        val id = Wearables.devices.value.firstOrNull()
            ?: return "No glasses are linked. Link them in CJ's Setup, then try again."
        val meta = metadata(id)
        return when {
            meta?.hingeState == HingeState.CLOSED -> "Your glasses are folded. Open the arms and try again."
            meta?.linkState != LinkState.CONNECTED -> "The glasses aren't connected. Put them on, and check Bluetooth and the Meta AI app."
            else -> "Couldn't reach the glasses. Try again in a moment."
        }
    }

    private suspend fun connect(): DeviceSession {
        session?.let { if (it.state.value == DeviceSessionState.STARTED) return it else it.stop() }
        val created = Wearables.createSession(SpecificDeviceSelector(connectedGlasses()))
            .fold({ it }, { error, _ -> throw GlassesException(explain(error), hopeless(error)) })
        session = created
        var lastError: DeviceSessionError? = null
        val state = coroutineScope {
            val errors = launch { created.errors.collect { lastError = it } }
            created.start()  // returns at once; the connection comes up in the background
            val result = withTimeoutOrNull(20_000) {
                created.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED }
            }
            errors.cancel()
            result
        }
        if (state != DeviceSessionState.STARTED) {
            created.stop()
            session = null
            throw GlassesException(lastError?.let(::explain)
                ?: "Couldn't reach the glasses. Are they on, unfolded and connected in the Meta AI app?",
                lastError?.let(::hopeless) ?: false)
        }
        return created
    }

    private fun explain(error: DeviceSessionError): String = when (error) {
        DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
            "Your glasses need an update for CJ. Tap Update glasses app in Setup, then try again."
        DeviceSessionError.NO_ELIGIBLE_DEVICE, DeviceSessionError.DEVICE_DISCONNECTED ->
            "No glasses found. Turn them on, unfold them and check they're connected in the Meta AI app."
        DeviceSessionError.CAPABILITY_DENIED -> "Meta hasn't enabled camera access for this account yet."
        DeviceSessionError.BATTERY_CRITICAL -> "The glasses battery is too low. Charge them first."
        DeviceSessionError.THERMAL_CRITICAL, DeviceSessionError.THERMAL_EMERGENCY ->
            "The glasses are too warm. Let them cool down for a few minutes."
        DeviceSessionError.INSUFFICIENT_SDK_VERSION -> "Update the Meta AI app and the glasses firmware."
        else -> "Glasses error: ${error.description}"
    }

    private fun hopeless(error: DeviceSessionError): Boolean = error in setOf(
        DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED, DeviceSessionError.CAPABILITY_DENIED,
        DeviceSessionError.BATTERY_CRITICAL, DeviceSessionError.THERMAL_CRITICAL, DeviceSessionError.THERMAL_EMERGENCY,
        DeviceSessionError.INSUFFICIENT_SDK_VERSION)

    fun disconnect() {
        session?.stop()
        session = null
    }

    private fun encode(photo: PhotoData): Snapshot {
        val bitmap = when (photo) {
            is PhotoData.Bitmap -> photo.bitmap
            is PhotoData.HEIC -> decodeHeic(photo)
            else -> null
        } ?: throw GlassesException("Couldn't read the photo from the glasses.")
        val scaled = shrink(bitmap, 1280)
        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        return Snapshot(scaled, Base64.encodeToString(jpeg, Base64.NO_WRAP))
    }

    /** HEIC from the glasses keeps its rotation in EXIF, which BitmapFactory ignores. */
    private fun decodeHeic(photo: PhotoData.HEIC): Bitmap? {
        val buffer = photo.data.duplicate().apply { rewind() }
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val degrees = when (ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    private fun shrink(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val f = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt(), (bitmap.height * f).toInt(), true)
    }

    private companion object {
        const val PHOTO_DEADLINE_MS = 30_000L
        const val CAMERA_START_MS = 10_000L
        const val LINK_WAIT_MS = 8_000L
        const val CALL_LINK_SETTLE_MS = 350L
    }
}
