package com.example.timelapse

import android.Manifest
import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {
    val SAMPLING_RATE = 44100
    val REQUEST_CODE_AUDIO_PERMISSION = 1

    private var visualizer: Visualizer? = null

    lateinit var magnitudesArray: FloatArray

    private var mWaveBuffer: ByteArray? = null
    private var mFftBuffer: ByteArray? = null
    private var mDataCaptureSize: Int = 0

    private var mAudioBufferSize: Int = 0
    private var mAudioRecord: AudioRecord? = null

    private var mAudioRecordState: Boolean = false

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ensurePermissionAllowed()
        println("OUTSIDE PERMISSIONS CHECK")

        mAudioBufferSize = AudioRecord.getMinBufferSize(SAMPLING_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_8BIT)
        mAudioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLING_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_8BIT, mAudioBufferSize)

        val button = findViewById<Button>(R.id.button)
        println("mAudioBufferSize: $mAudioBufferSize")

        if (mAudioRecord!!.state != AudioRecord.STATE_INITIALIZED)
            println("AudioRecord init failed")
        else
            println("AudioRecord init success")

        try {
            println("BEGIN INITIALIZING VIZUALIZER.")
            println("Audio Session ID: ${mAudioRecord!!.audioSessionId}")
            //visualizer = Visualizer(mAudioRecord.audioSessionId).apply {
            visualizer = Visualizer(0).apply {
                enabled = false
                captureSize = 512
                //captureSize = captureSizeRange[1]

                try {
                    scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                } catch (e: NoSuchMethodError) {
                    println("CANT SET SCALING MODE.")
                }
                measurementMode = Visualizer.MEASUREMENT_MODE_NONE

                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        val n = fft?.size
                        val magnitudes = FloatArray(n!! / 2 + 1)
                        magnitudes[0] = Math.abs(fft[0].toFloat())
                        magnitudes[n / 2] = Math.abs(fft[1].toFloat())
                        for (k in 1 until n / 2) {
                            val i = k * 2
                            magnitudes[k] = Math.hypot(fft[i].toDouble(), fft[i + 1].toDouble()).toFloat()
                        }
                        val ms = magnitudes.joinToString(", ")
                        println("Magnitudes: $ms" )
                    }
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        val waveBuffer = mWaveBuffer ?: return
                        if (waveform == null || waveform.size != waveBuffer.size) {
                            return
                        }
                        System.arraycopy(waveform, 0, waveBuffer, 0, waveform.size)
                    }

                }, Visualizer.getMaxCaptureRate(), true, true)
            }.apply {
                mDataCaptureSize = captureSize.apply {
                    mWaveBuffer = ByteArray(this)
                    mFftBuffer = ByteArray(this)
                }
            }
        } catch (e: RuntimeException) {
            println("ERROR DURING VISUALIZER INITIALIZATION: $e")
        }

        button.setOnClickListener {
            if (!mAudioRecordState) {
                mAudioRecord!!.startRecording()
                visualizer?.enabled = true

                mAudioRecordState = true
            }
            else {
                mAudioRecord!!.stop()
                visualizer?.enabled = false

                mAudioRecordState = false
            }
        }
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
