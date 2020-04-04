package com.example.timelapse

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.pm.PackageManager
import android.os.*
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


@TargetApi(Build.VERSION_CODES.LOLLIPOP) //NOTE: camera 2 api was added in API level 21
@RequiresApi(Build.VERSION_CODES.LOLLIPOP) //NOTE: camera 2 api was added in API level 21
class MainActivity : AppCompatActivity() {
    val REQUEST_CODE_AUDIO_PERMISSION = 1
    val projectName = AtomicReference<String>("test_${SimpleDateFormat("yyMMddHH").format(Date())}")
    val takePicture = AtomicBoolean(false)
    val recordAudio = AtomicBoolean(false)
    val audioIn = AudioIn(440.0, 1, takePicture, recordAudio)
    private val pictureRun = PictureHandlerThread(this, takePicture, projectName)

    @SuppressLint("SimpleDateFormat")
    override fun onCreate(savedInstanceState: Bundle?) {
        println("Main thread runs in ${Thread.currentThread().id}")
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        println("OUTSIDE PERMISSIONS CHECK")
        ensurePermissionAllowed()

        val recordButton = findViewById<Button>(R.id.record)
        val stopButton = findViewById<Button>(R.id.stop)

        recordButton.setOnClickListener {
            recordButton.isEnabled = false
            val targetFrequency = (findViewById<EditText>(R.id.target_frequency)).text.toString().toDoubleOrNull()
            audioIn.targetFrequency = targetFrequency?:440.0
            projectName.set("${(findViewById<EditText>(R.id.target_frequency)).text}_" +
                    SimpleDateFormat("yyyyMMddHH").format(Date()))
            recordAudio.set(true)
            stopButton.isEnabled = true
        }

        stopButton.setOnClickListener {
            stopButton.isEnabled = false
            recordAudio.set(false)
            recordButton.isEnabled = true
        }
        pictureRun.start()
        audioIn.start()
    }

    private class PictureHandlerThread(val activity: Activity, val takePicture: AtomicBoolean, val projectName: AtomicReference<String>): Thread() {
        override fun  run() {
            Looper.prepare()

            val mHandler = Handler()
            val pictureCapturingService = PictureCapturingService(activity, projectName)
            val pictureRun = PictureRun(Process.THREAD_PRIORITY_MORE_FAVORABLE, pictureCapturingService, takePicture, mHandler)

            pictureRun.start()
            Looper.loop()
        }
    }

    private class PictureRun(val threadPriority: Int, val pictureCapturingService: PictureCapturingService, val takePicture: AtomicBoolean, val handler: Handler): Thread() {
        override fun run() {
            Process.setThreadPriority(threadPriority)
            try {
//                println("Running picture thread ${currentThread().id}")
                if (!takePicture.get()) {
//                    println("Sleeping")
                    sleep(2000)
                } else {
                    println("Taking picture ${Thread.currentThread().id}")
                    pictureCapturingService.startCapturing()
                    takePicture.set(false)
                }
                handler.post(PictureRun(threadPriority, pictureCapturingService, takePicture, handler))
            } catch (e: Exception) {
                println(e.message)
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
