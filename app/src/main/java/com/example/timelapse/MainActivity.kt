package com.example.timelapse

import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecord.OnRecordPositionUpdateListener
import android.media.MediaRecorder.AudioSource
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType


class MainActivity : AppCompatActivity() {
    val CAPTURE_SIZE = 128
    val REQUEST_CODE_AUDIO_PERMISSION = 1

    private var visualizer: Visualizer? = null

    private var mWaveBuffer: ByteArray? = null
    private var mFftBuffer: ByteArray? = null
    private var mDataCaptureSize: Int = 0

    private var bufferSize: Int = 0

    private var isRecording: Boolean = false
    private var audioIn: AudioIn = AudioIn(440.0, 0.1f)
    private var lastBuffer = 0

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        println("OUTSIDE PERMISSIONS CHECK")
        ensurePermissionAllowed()

        val button = findViewById<Button>(R.id.record)

//        try {
//            println("BEGIN INITIALIZING VIZUALIZER.")
//            println("Audio Session ID: ${recorder!!.audioSessionId}")
////            visualizer = Visualizer(mAudioRecord!!.audioSessionId).apply {
//            visualizer = Visualizer(0).apply {
//                enabled = false
//                captureSize = CAPTURE_SIZE
//
//                try {
//                    scalingMode = Visualizer.SCALING_MODE_NORMALIZED
//                } catch (e: NoSuchMethodError) {
//                    println("CANT SET SCALING MODE.")
//                }
//                measurementMode = Visualizer.MEASUREMENT_MODE_NONE
//
//                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
//                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
//                        val n = fft?.size
//                        val magnitudes = FloatArray(n!! / 2 + 1)
//                        val frequencies = FloatArray(n / 2 + 1)
//                        frequencies[0] = 0.0.toFloat()
//                        frequencies[n / 2] = (getSamplingRate() * 0.0001 / 2).toFloat()
//                        magnitudes[0] = abs(fft[0].toFloat())
//                        magnitudes[n / 2] = abs(fft[1].toFloat())
//                        for (k in 1 until n / 2) {
//                            magnitudes[k] = Math.hypot(fft[k * 2].toDouble(), fft[k * 2 + 1].toDouble()).toFloat()
//                            frequencies[k] = frequencies[n /2 ] * k * 2 / n
//                        }
//                        val ms = magnitudes.joinToString(", ")
//                        println("Frequencies: ${frequencies.joinToString(", ")}")
//                        println("Magnitudes: $ms" )
//                    }
//                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
//                        val waveBuffer = mWaveBuffer ?: return
//                        if (waveform == null || waveform.size != waveBuffer.size) {
//                            return
//                        }
//                        System.arraycopy(waveform, 0, waveBuffer, 0, waveform.size)
//                    }
//
//                }, Visualizer.getMaxCaptureRate(), true, true)
//            }.apply {
//                mDataCaptureSize = captureSize.apply {
//                    mWaveBuffer = ByteArray(this)
//                    mFftBuffer = ByteArray(this)
//                }
//            }
//        } catch (e: RuntimeException) {
//            println("ERROR DURING VISUALIZER INITIALIZATION: $e")
//        }

        button.setOnClickListener {
            audioIn.startStop()
        }
//        audioIn.startStop()
    }

    private fun ensurePermissionAllowed() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            println("PERMISSION TO RECORD AUDIO DENIED.  REQUESTING.")
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_CODE_AUDIO_PERMISSION)
        } else if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            println("PERMISSION TO MODIFY_AUDIO_SETTINGS DENIED.  REQUESTING.")
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.MODIFY_AUDIO_SETTINGS), REQUEST_CODE_AUDIO_PERMISSION)
        }
        else {
            println("PERMISSION TO RECORD AUDIO GRANTED.")
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            println("PERMISSION TO CAMERA.  REQUESTING.")
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 1)
        } else if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            println("PERMISSION TO WRITE_EXTERNAL_STORAGE.  REQUESTING.")
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        } else {
            println("PERMISSION GRANTED.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_CODE_AUDIO_PERMISSION -> {
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Demo need record permission, please allow it to show this visualize effect!", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }


}
