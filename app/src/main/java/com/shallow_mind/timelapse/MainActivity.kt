package com.shallow_mind.timelapse

import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.schedule


@TargetApi(Build.VERSION_CODES.LOLLIPOP)
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class MainActivity : AppCompatActivity() {
    private val DEFAULT_PROJECT_NAME = "test"
    private val DEFAULT_TARGET_FREQUENCE = 3500.0
    private val REQUEST_CODE_AUDIO_PERMISSION = 1
    private val projectName = AtomicReference<String>("")
    private val takePicture = AtomicBoolean(false)
    private val audioIn = AudioIn(DEFAULT_TARGET_FREQUENCE, takePicture)
    private val pictureHandlerThread = HandlerThread("picture")
    private var pictureCapturingService: PictureCapturingService? = null
    private var screenOn = true

    override fun onCreate(savedInstanceState: Bundle?) {
        pictureHandlerThread.start()
        val pictureHandler = Handler(pictureHandlerThread.looper)
        pictureCapturingService = PictureCapturingService(this, projectName, pictureHandler)
        val pictureRun = PictureRun(pictureCapturingService!!, takePicture)

        println("Main thread runs in ${Thread.currentThread().id}")
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        println("OUTSIDE PERMISSIONS CHECK")
        ensurePermissionAllowed()

        val recordButton = findViewById<Button>(R.id.record)
        val stopButton = findViewById<Button>(R.id.stop)

        recordButton.setOnClickListener {
            recordButton.isEnabled = false
            pictureCapturingService!!.openCamera()
            val targetFrequency = (findViewById<EditText>(R.id.target_frequency)).text.toString().toDoubleOrNull()
            audioIn.targetFrequency = targetFrequency?: DEFAULT_TARGET_FREQUENCE
            val pName = (findViewById<EditText>(R.id.project_name)).text
            projectName.set("${TimeService.getForDirectory()}_${if(pName.isNotEmpty()) pName else DEFAULT_PROJECT_NAME}")
            Timer("start", false).schedule(1000) {
                audioIn.startRecording()
            }
            stopButton.isEnabled = true
        }

        stopButton.setOnClickListener {
            stopButton.isEnabled = false
            audioIn.stopRecording()
            pictureCapturingService!!.closeCamera()
            recordButton.isEnabled = true
        }

        pictureRun.start()
    }

    fun onCheckboxClicked(view: View) {
        if (view is CheckBox) {
            val checked: Boolean = view.isChecked
            screenOn = checked
            if (screenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private class PictureRun(val pictureCapturingService: PictureCapturingService, val takePicture: AtomicBoolean): Thread() {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
            while (true) {
                try {
                    if (!takePicture.get()) {
                        sleep(200)
                    } else {
                        takePicture.set(false)
                        pictureCapturingService.capture()
                        sleep(200)
                    }
                } catch (e: Exception) {
                    println(e.message)
                }
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
