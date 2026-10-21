package com.buzzme.app.ui.components

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

object SystemQrScanner {
    private const val TAG = "SystemQrScanner"

    fun isAvailable(context: Context): Boolean {
        return try {
            val availability = GoogleApiAvailability.getInstance()
            val resultCode = availability.isGooglePlayServicesAvailable(context)
            resultCode == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check Google Play Services availability", e)
            false
        }
    }

    fun startScan(
        context: Context,
        onSuccess: (String) -> Unit,
        onCanceled: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()

            val scanner = GmsBarcodeScanning.getClient(context, options)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val rawValue = barcode.rawValue?.takeIf { it.isNotBlank() }
                        ?: barcode.displayValue?.takeIf { it.isNotBlank() }
                    if (rawValue != null) {
                        Log.i(TAG, "Barcode scanned successfully: len=${rawValue.length}")
                        onSuccess(rawValue)
                    } else {
                        Log.w(TAG, "Barcode scanned but no raw/display value found")
                        onFailure(IllegalStateException("No content detected in QR code"))
                    }
                }
                .addOnCanceledListener {
                    Log.i(TAG, "Barcode scan canceled by user")
                    onCanceled()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scan failed", e)
                    onFailure(e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting GmsBarcodeScanning", e)
            onFailure(e)
        }
    }
}
