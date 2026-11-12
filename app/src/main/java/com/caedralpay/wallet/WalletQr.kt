package com.caedralpay.wallet

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.caedralpay.wallet.ui.theme.CaedralColors as C
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object WalletQrCodec {
    fun matrix(payload: String, size: Int = 640) = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 4))

    fun decodeLuminance(bytes: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
        val reader = QRCodeReader()
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
        } catch (_: ReaderException) {
            try { reader.decode(BinaryBitmap(HybridBinarizer(source.invert()))).text } catch (_: ReaderException) { null }
        } finally { reader.reset() }
    }
}

@Composable
internal fun ReceiveQr(payload: String) {
    val image = remember(payload) {
        val matrix = WalletQrCodec.matrix(payload)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(image, "QR Code para receber", Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(1f))
    }
}

/** All frames and decoded phrases stay inside this process, without files or external scanner apps. */
@Composable
internal fun WalletQrScanner(title: String, onDismiss: () -> Unit, onRead: (String) -> String?) {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var error by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = it
        if (!it) error = "Permita o uso da câmera para ler o QR Code."
    }
    LaunchedEffect(Unit) { if (!permitted) launcher.launch(Manifest.permission.CAMERA) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn
    )) {
        Surface(Modifier.fillMaxSize(), color = C.Background, contentColor = C.Text) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (permitted) CameraQrPreview(
                    onRead = { error = onRead(it).orEmpty() },
                    onError = { error = "Não foi possível abrir a câmera. Feche o leitor e tente novamente." }
                ) else CaedralButton(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Permitir câmera") }
                if (!permitted && error.isNotEmpty()) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("Abrir configurações") }
                Text("Posicione o QR Code no centro da câmera.", color = C.Muted)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = onDismiss) { Text("Cancelar leitura") }
            }
        }
    }
}

@Composable
private fun CameraQrPreview(onRead: (String) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentRead by rememberUpdatedState(onRead)
    val currentError by rememberUpdatedState(onError)
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black))
    DisposableEffect(view, owner) {
        val disposed = AtomicBoolean(false)
        val pending = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var lastAnalyzed = 0L
        analysis.setAnalyzer(executor) { frame ->
            var bytes: ByteArray? = null
            try {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!disposed.get() && !pending.get() && now - lastAnalyzed >= 250) {
                    lastAnalyzed = now
                    val plane = frame.planes[0]
                    val buffer = plane.buffer.duplicate()
                    val start = buffer.position()
                    val width = frame.width
                    val height = frame.height
                    val luminance = ByteArray(width * height)
                    bytes = luminance
                    for (row in 0 until height) for (column in 0 until width) {
                        luminance[row * width + column] = buffer.get(start + row * plane.rowStride + column * plane.pixelStride)
                    }
                    val payload = WalletQrCodec.decodeLuminance(luminance, width, height)
                    if (payload != null && pending.compareAndSet(false, true)) {
                        main.execute {
                            if (!disposed.get()) currentRead(payload)
                            // Avoid flooding the UI while an invalid code remains in view.
                            view.postDelayed({ pending.set(false) }, 1_200)
                        }
                    }
                }
            } catch (_: Exception) {
                // No image or decoded content is logged, including malformed QR codes.
            } finally {
                bytes?.fill(0)
                frame.close()
            }
        }
        future.addListener({
            if (!disposed.get()) try {
                val provider = future.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) currentError()
                else provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { currentError() }
        }, main)
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            if (future.isDone) runCatching { future.get().unbind(preview, analysis) }
            executor.shutdown()
        }
    }
}
