package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.Executors

/**
 * The F-Droid build's camera, which has no Play Services to lend it Google's scanner: CameraX's
 * preview fills the page and ZXing reads each frame. It asks for the camera when the page opens,
 * and with access refused says so, with a way to the app's settings, as the iOS page does.
 */
object FlavorQrScannerCamera : QrScannerCamera {
    @Composable
    override fun Area(onScanned: (String) -> Boolean, onRead: (String) -> Unit, modifier: Modifier) {
        val context = LocalContext.current
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return
        // Saved, so turning the phone does not ask again.
        var granted by rememberSaveable { mutableStateOf(hasCameraPermission(context)) }
        var refused by rememberSaveable { mutableStateOf(false) }
        val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            refused = !it
        }
        LaunchedEffect(Unit) { if (!granted && !refused) request.launch(Manifest.permission.CAMERA) }
        // Back from the app's settings page, access may have been granted.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) granted = hasCameraPermission(context)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        when {
            granted -> CameraPreview(onScanned = onScanned, modifier = modifier)
            refused -> Box(modifier, contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.class_table_import_camera_denied),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    FilledTonalButton(onClick = { openAppSettings(context) }) {
                        Text(stringResource(R.string.action_go_to_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(onScanned: (String) -> Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onScanned)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val session = CameraSession()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val cameraProvider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.w(TAG, "No camera provider: ${e.logSummary()}")
                return@addListener
            }
            session.start {
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider { request -> surfaceRequest = request }
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    // Codes go to the import on the main thread, where it keeps its state.
                    .also { it.setAnalyzer(executor, QrFrameReader { text -> mainExecutor.execute { latest(text) } }) }
                try {
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector(cameraProvider), preview, analysis)
                } catch (e: Exception) {
                    Log.w(TAG, "The camera did not start: ${e.logSummary()}")
                }
                return@start { cameraProvider.unbind(preview, analysis) }
            }
        }, mainExecutor)
        onDispose {
            session.dispose()
            executor.shutdown()
        }
    }
    surfaceRequest?.let { request ->
        CameraXViewfinder(surfaceRequest = request, contentScale = ContentScale.Crop, modifier = modifier.fillMaxSize())
    }
}

/** The back camera, or on a device without one, the front one or whichever there is. */
private fun cameraSelector(provider: ProcessCameraProvider): CameraSelector = listOf(
    CameraSelector.DEFAULT_BACK_CAMERA,
    CameraSelector.DEFAULT_FRONT_CAMERA,
).firstOrNull { selector ->
    try {
        provider.hasCamera(selector)
    } catch (_: Exception) {
        false
    }
} ?: provider.availableCameraInfos.firstOrNull()?.cameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA

/** Reads each frame's brightness with ZXing; see [RepeatedQrCodeFilter] for a code kept in view. */
private class QrFrameReader(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    private val filter = RepeatedQrCodeFilter()

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
            // The luminance plane as the sensor gives it: ZXing reads a code at any angle.
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
            val text = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (_: ReaderException) {
                null
            } catch (e: RuntimeException) {
                // A frame of anything the camera points at: no picture may stop the scanner.
                Log.w(TAG, "A frame did not read: ${e.logSummary()}")
                null
            } finally {
                reader.reset()
            }
            if (text != null && filter.isNew(text, System.currentTimeMillis())) onCode(text)
        }
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

private const val TAG = "QrScannerCamera"
