package com.buzzme.app.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CameraQrScanner"

@Composable
fun CameraQrScanner(
    onBarcodeScanned: (String) -> Unit,
    onClose: () -> Unit,
    timeoutSeconds: Int = 120
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Resolve true Activity lifecycle owner to avoid Dialog lifecycle teardown issues
    val lifecycleOwner = remember(context) {
        context.findLifecycleOwner()
    } ?: LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Log.w(TAG, "Camera permission denied by user")
        }
    }

    var remainingSeconds by remember { mutableStateOf(timeoutSeconds) }
    var hasScanned by remember { mutableStateOf(false) }

    // Countdown timer
    LaunchedEffect(Unit) {
        while (remainingSeconds > 0 && !hasScanned) {
            delay(1000)
            remainingSeconds--
        }
        if (remainingSeconds == 0 && !hasScanned) {
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!hasCameraPermission) {
            // Permission request UI
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Camera",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Camera Permission Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "BuzzMe needs camera access to quickly scan pairing QR codes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Grant Permission")
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onClose,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Cancel")
                }
            }
        } else {
            val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
            val isScanningActive = remember { AtomicBoolean(true) }

            DisposableEffect(Unit) {
                onDispose {
                    isScanningActive.set(false)
                    cameraExecutor.shutdown()
                }
            }

            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        // Use COMPATIBLE (TextureView) to prevent SurfaceView BufferQueue abandonment inside Dialogs
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            // Explicitly optimize ML Kit for QR codes
                            val scannerOptions = BarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .build()
                            val barcodeScanner = BarcodeScanning.getClient(scannerOptions)

                            // ZXing fallback decoder initialized with QR format
                            val zxingReader = MultiFormatReader().apply {
                                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                if (!isScanningActive.get() || hasScanned) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }

                                processDualImageProxy(
                                    scanner = barcodeScanner,
                                    zxingReader = zxingReader,
                                    imageProxy = imageProxy
                                ) { scannedText ->
                                    if (isScanningActive.compareAndSet(true, false)) {
                                        Log.i(TAG, "QR code detected successfully: length=${scannedText.length}")
                                        hasScanned = true
                                        try {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        } catch (e: Exception) {
                                            // Ignore haptic failure
                                        }
                                        onBarcodeScanned(scannedText)
                                    }
                                }
                            }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                            Log.i(TAG, "CameraProvider successfully bound to lifecycle")
                        } catch (e: Exception) {
                            Log.e(TAG, "CameraProvider bindToLifecycle failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Viewfinder cutout and reticle overlay
            ViewfinderOverlay()

            // Header instructions & countdown
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (hasScanned) "QR Code Scanned!" else "Align QR code inside frame",
                    color = if (hasScanned) Color(0xFF10B981) else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!hasScanned) {
                    Text(
                        text = "Timeout in: ${remainingSeconds}s",
                        color = if (remainingSeconds < 30) Color(0xFFEF4444) else Color(0xFFFBBF24),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Bottom cancel button
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
            ) {
                Text("Cancel", color = Color.White)
            }
        }
    }
}

/**
 * Renders a darkened overlay with a clear rounded square cutout in the center
 * and sharp corner brackets for intuitive camera aiming.
 */
@Composable
private fun ViewfinderOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val minDim = size.width.coerceAtMost(size.height)
        val boxSize = minDim * 0.68f
        val left = (size.width - boxSize) / 2f
        val top = (size.height - boxSize) / 2f
        val cornerRadius = 16.dp.toPx()
        val cornerLength = 28.dp.toPx()
        val strokeWidth = 4.dp.toPx()
        val bracketColor = Color(0xFFFBBF24) // Bumblebee amber

        val cutoutPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(left, top, left + boxSize, top + boxSize),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                )
            )
        }

        // Draw darkened backdrop everywhere except the cutout box
        clipPath(cutoutPath, clipOp = ClipOp.Difference) {
            drawRect(color = Color.Black.copy(alpha = 0.6f))
        }

        // Draw the 4 corner bracket accents
        // Top-Left
        drawLine(
            color = bracketColor,
            start = Offset(left, top + cornerLength),
            end = Offset(left, top + cornerRadius),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = bracketColor,
            start = Offset(left + cornerRadius, top),
            end = Offset(left + cornerLength, top),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Top-Right
        drawLine(
            color = bracketColor,
            start = Offset(left + boxSize, top + cornerLength),
            end = Offset(left + boxSize, top + cornerRadius),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = bracketColor,
            start = Offset(left + boxSize - cornerRadius, top),
            end = Offset(left + boxSize - cornerLength, top),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Bottom-Left
        drawLine(
            color = bracketColor,
            start = Offset(left, top + boxSize - cornerLength),
            end = Offset(left, top + boxSize - cornerRadius),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = bracketColor,
            start = Offset(left + cornerRadius, top + boxSize),
            end = Offset(left + cornerLength, top + boxSize),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Bottom-Right
        drawLine(
            color = bracketColor,
            start = Offset(left + boxSize, top + boxSize - cornerLength),
            end = Offset(left + boxSize, top + boxSize - cornerRadius),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = bracketColor,
            start = Offset(left + boxSize - cornerRadius, top + boxSize),
            end = Offset(left + boxSize - cornerLength, top + boxSize),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Dual Analyzer:
 * 1. Runs ML Kit Barcode Scanner.
 * 2. Crucially, extracts barcode.rawValue ?: barcode.displayValue directly without
 *    rejecting TYPE_UNKNOWN (fixing the JSON payload drop bug!).
 * 3. In parallel, runs ZXing MultiFormatReader on the Y-plane luminance channel as an instant offline fallback.
 */
@SuppressLint("UnsafeOptInUsageError")
private fun processDualImageProxy(
    scanner: BarcodeScanner,
    zxingReader: MultiFormatReader,
    imageProxy: ImageProxy,
    onSuccess: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    var handled = false

    // 1. Try ZXing offline decoder directly on Y plane (very fast, zero Play Services dependency)
    try {
        val yPlane = imageProxy.planes[0]
        val buffer = yPlane.buffer
        val yBytes = ByteArray(buffer.remaining())
        buffer.get(yBytes)
        val source = PlanarYUVLuminanceSource(
            yBytes,
            imageProxy.width,
            imageProxy.height,
            0,
            0,
            imageProxy.width,
            imageProxy.height,
            false
        )
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val zxingResult = zxingReader.decodeWithState(binaryBitmap)
        val text = zxingResult?.text
        if (!text.isNullOrBlank()) {
            handled = true
            imageProxy.close()
            onSuccess(text)
            return
        }
    } catch (e: Exception) {
        // ZXing failed on this frame, fall through to ML Kit
    } finally {
        zxingReader.reset()
    }

    // 2. ML Kit processing
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            if (handled) return@addOnSuccessListener
            for (barcode in barcodes) {
                // DO NOT filter by barcode.valueType! JSON QR codes are often classified as TYPE_UNKNOWN
                val raw = barcode.rawValue?.takeIf { it.isNotBlank() }
                    ?: barcode.displayValue?.takeIf { it.isNotBlank() }
                if (raw != null) {
                    handled = true
                    onSuccess(raw)
                    return@addOnSuccessListener
                }
            }
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "ML Kit barcode scanning failed on frame: ${e.message}")
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var cur = this
    while (cur is ContextWrapper) {
        if (cur is LifecycleOwner) return cur
        cur = cur.baseContext
    }
    return null
}
