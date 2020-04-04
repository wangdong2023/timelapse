package com.example.timelapse

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.RuntimeException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference


/**
 * The aim of this service is to secretly take pictures (without preview or opening device's camera app)
 * from all available cameras using Android Camera 2 API
 *
 * @author hzitoun (zitoun.hamed@gmail.com)
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP) //NOTE: camera 2 api was added in API level 21
@RequiresApi(Build.VERSION_CODES.LOLLIPOP) //NOTE: camera 2 api was added in API level 21
class PictureCapturingService(val activity: Activity, val projectName: AtomicReference<String>) {
    private val ORIENTATIONS = SparseIntArray()

    init {
        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }
    private var cameraDevice: CameraDevice? = null

    private var cameraId: String? = null
    private var cameraClosed = true
    private var reader: ImageReader

    private val onImageAvailableListener =
        OnImageAvailableListener { imReader: ImageReader ->
            val image = imReader.acquireLatestImage()
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer[bytes]
            saveImageToDisk(bytes)
            image.close()
        }

    var context = activity.applicationContext
    var manager: CameraManager

    init {
        manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        var characteristics: CameraCharacteristics? = null
        for (cid in manager.cameraIdList) {
            characteristics = manager.getCameraCharacteristics(cid)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = cid
                break
            }
        }
        if (cameraId == null) {
            throw RuntimeException("No backward camera")
        }
        println("Cameraid ${cameraId}")


        val streamConfigurationMap =
            characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        var jpegSizes: Array<Size> = emptyArray()
        if (streamConfigurationMap != null) {
            jpegSizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)
        }

        val width = if (jpegSizes.isNotEmpty()) jpegSizes[0].width else 640
        val height = if (jpegSizes.isNotEmpty()) jpegSizes[0].height else 480

        println("Image size is ${width} * ${height}")
        reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 5)
        reader.setOnImageAvailableListener(onImageAvailableListener, null)
    }

    /**
     * Starts pictures capturing treatment.
     *
     * @param listener picture capturing listener
     */
    fun startCapturing() {
        try {
            if (!cameraClosed) {
                println("Camera in use, skip this picture")
            } else {
                openCamera()
            }
        } catch (e: CameraAccessException) {
            Log.e(
                TAG,
                "Exception occurred while accessing the list of cameras",
                e
            )
        }
    }

    private fun openCamera() {
        println("Opening camera")

        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                == PackageManager.PERMISSION_GRANTED
            ) {
                manager.openCamera(cameraId!!, stateCallback, null)
            } else {
                println("I'm else")
            }
        } catch (e: CameraAccessException) {
            Log.e(
                TAG,
                " exception occurred while opening camera $cameraId",
                e
            )
        }
    }

    private val captureListener: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            closeCamera()
        }
    }


    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraClosed = false
            Log.d(
                TAG,
                "camera " + camera.id + " opened"
            )
            cameraDevice = camera

            println("Taking picture from camera " + camera.id)
            //Take the picture after some delay. It may resolve getting a black dark photos.
            Handler().postDelayed({
                try {
                    takePicture()
                } catch (e: CameraAccessException) {
                    Log.e(
                        TAG,
                        " exception occurred while taking picture from $cameraId",
                        e
                    )
                }
            }, 200)
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(
                TAG,
                " camera " + camera.id + " disconnected"
            )
            if (cameraDevice != null && !cameraClosed) {
                cameraClosed = true
                cameraDevice!!.close()
            }
        }

        override fun onClosed(camera: CameraDevice) {
            cameraClosed = true
            Log.d(
                TAG,
                "camera " + camera.id + " closed"
            )
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(
                TAG,
                "camera in error, int code $error"
            )
            if (cameraDevice != null && !cameraClosed) {
                cameraDevice!!.close()
            }
        }
    }

    private fun getOrientation(): Int {
        val rotation: Int = activity.getWindowManager().getDefaultDisplay().getRotation()
        return ORIENTATIONS.get(rotation)
    }

    @Throws(CameraAccessException::class)
    private fun takePicture() {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null")
            return
        }

        val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureBuilder.addTarget(reader.surface)
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureBuilder[CaptureRequest.JPEG_ORIENTATION] = getOrientation()

        cameraDevice!!.createCaptureSession(
            listOf(reader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        session.capture(captureBuilder.build(), captureListener, null)
                    } catch (e: CameraAccessException) {
                        Log.e(
                            TAG,
                            " exception occurred while accessing $cameraId",
                            e
                        )
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }
            ,
            null)
    }

    private fun saveImageToDisk(bytes: ByteArray) {
        val file = createImageFile()
        try {
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }
            Log.i(TAG, "Image created at ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e(
                TAG,
                "Exception occurred while saving picture to external storage ",
                e
            )
        }
    }

    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat("yyMMdd_HHmmss").format(Date())
        val storageDir = context.getExternalFilesDir(projectName.get())
        return File(
            storageDir,
            "${timeStamp}.jpg"
        )
    }

    private fun closeCamera() {
        Log.d(
            TAG,
            "closing camera " + cameraDevice!!.id
        )
        if (null != cameraDevice) {
            cameraDevice!!.close()
            cameraDevice = null
        }
        cameraClosed = true
    }
}