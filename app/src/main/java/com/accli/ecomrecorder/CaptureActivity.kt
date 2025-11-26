package com.accli.ecomrecorder

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import android.media.ToneGenerator
import android.media.AudioManager

class CaptureActivity : AppCompatActivity() {
    private lateinit var capture: CaptureManager
    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var scanLabel: TextView
    private lateinit var typeInsteadButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.custom_capture_layout)

        barcodeView = findViewById(R.id.zxing_barcode_scanner)

        scanLabel = findViewById(R.id.tv_scan_label)
        typeInsteadButton = findViewById(R.id.btn_type_instead)

        val mode = intent.getStringExtra("MODE")
        scanLabel.text = "Scan the barcode for $mode"

        typeInsteadButton.setOnClickListener {
            setResult(RESULT_FIRST_USER)
            finish()
        }



        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.setShowMissingCameraPermissionDialog(false)

        barcodeView.decodeSingle(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                toneGen.startTone(ToneGenerator.TONE_DTMF_1, 100) // High pitch beep

                val intent = Intent()
                intent.putExtra("SCAN_RESULT", result.text)
                setResult(RESULT_OK, intent)
                finish()
            }

            override fun possibleResultPoints(resultPoints: List<ResultPoint>) {}
        })
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        return barcodeView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }
}