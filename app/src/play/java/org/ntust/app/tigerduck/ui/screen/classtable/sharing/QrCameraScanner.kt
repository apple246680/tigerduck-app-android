package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.util.logSummary

/**
 * Google's code scanner: Play Services draws the camera full screen and hands back one code, so
 * the app asks for no camera permission. It opens with the page, as the iOS page's camera does;
 * the scanner icon opens it again after a code was refused, the scanner was closed, or it failed
 * to open, which can pass, as while Play Services fetches its module. That module comes with the
 * app's install, as the manifest asks.
 */
object FlavorQrScannerCamera : QrScannerCamera {
    @Composable
    override fun Area(onScanned: (String) -> Boolean, onRead: (String) -> Unit, modifier: Modifier) {
        val context = LocalContext.current
        val latestOnRead by rememberUpdatedState(onRead)
        val scan: () -> Unit = { startScan(context) { latestOnRead(it) } }
        // Once a page: not again when a dark mode or font change rebuilds it.
        var opened by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!opened) {
                opened = true
                scan()
            }
        }
        Box(modifier, contentAlignment = Alignment.Center) {
            FilledTonalIconButton(onClick = scan, modifier = Modifier.size(96.dp)) {
                Icon(
                    Icons.Outlined.QrCodeScanner,
                    contentDescription = stringResource(R.string.class_table_import),
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }

    private fun startScan(context: Context, onCode: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            // A code of several terms is dense, and often on another phone's screen.
            .enableAutoZoom()
            .build()
        try {
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode -> barcode.rawValue?.let(onCode) }
                .addOnFailureListener { e -> logFailure(e) }
        } catch (e: RuntimeException) {
            // Without Play Services, or before ML Kit has started, the client throws at once.
            logFailure(e)
        }
    }

    private fun logFailure(e: Exception) {
        Log.w("QrScannerCamera", "Google's code scanner did not open: ${e.logSummary()}")
    }
}
