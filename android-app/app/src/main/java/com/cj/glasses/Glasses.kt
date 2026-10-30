package com.cj.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
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

class GlassesException(message: String) : Exception(message)

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

    // Lazy: creating a selector touches the SDK, which must be initialized first.
    private val selector by lazy { AutoDeviceSelector() }
    private val lock = Mutex()
    private var session: DeviceSession? = null

    /** Needs BLUETOOTH_CONNECT on Android 12+. Returns an error message, or null when ready. */
    fun initialize(): String? = Wearables.initialize(context).fold(
        { _ready.value = true; null },
        { error, _ ->
            if (error == WearablesError.ALREADY_INITIALIZED) { _ready.value = true; null } else error.description
        },
    )

    suspend fun hasCameraPermission(): Boolean =
        Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() == PermissionStatus.Granted

    /** Photo from the glasses. If the link went stale (Bluetooth dropped), reconnects and tries once more. */
    suspend fun takePhoto(): Snapshot = try {
        takePhotoOnce()
    } catch (e: GlassesException) {
        disconnect()
        takePhotoOnce()
    }

    private suspend fun takePhotoOnce(): Snapshot = lock.withLock {
        val current = connect()
        val camera = current.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15))
            .fold({ it }, { error, _ -> throw GlassesException("Couldn't open the glasses camera: ${error.description}") })
        try {
            val stream = camera.stream
            stream.start().onFailure { error, _ -> throw GlassesException("Camera didn't start: ${error.description}") }
            withTimeoutOrNull(15_000) { stream.state.first { it == StreamState.STREAMING || it == StreamState.STARTED } }
                ?: throw GlassesException("The glasses camera took too long to start.")
            val photo = stream.capturePhoto()
                .fold({ it }, { error, _ -> throw GlassesException("Photo failed: ${error.description}") })
            withContext(Dispatchers.Default) { encode(photo) }
        } finally {
            camera.stop()
        }
    }

    private suspend fun connect(): DeviceSession {
        session?.let { if (it.state.value == DeviceSessionState.STARTED) return it else it.stop() }
        val created = Wearables.createSession(selector)
            .fold({ it }, { error, _ -> throw GlassesException(explain(error)) })
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
                ?: "Couldn't reach the glasses. Are they on, unfolded and connected in the Meta AI app?")
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
}
