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
import android.os.Environment
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
import java.text.SimpleDateFormat
import java.util.*


/**
 * The aim of this service is to secretly take pictures (without preview or opening device's camera app)
 * from all available cameras using Android Camera 2 API
 *
 * @author hzitoun (zitoun.hamed@gmail.com)
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP) //NOTE: camera 2 api was added in API level 21
@RequiresApi(Build.VERSION_CODES.LOLLIPOP) //NOTE: camera 2 api was added in API level 21
class PictureCapturingService(activity: Activity) {
    private val ORIENTATIONS = SparseIntArray()

    init {
        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null

    private var currentCameraId: String? = null
    private var cameraClosed = false
    private var activity: Activity = activity
    /**
     * stores a sorted map of (pictureUrlOnDisk, PictureData).
     */
    private var picturesTaken: TreeMap<String, ByteArray>? = null

    var context: Context
    var manager: CameraManager

    init {
        context = activity.applicationContext
        manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
//    private var capturingListener: PictureCapturingListener? = null

    /**
     * Starts pictures capturing treatment.
     *
     * @param listener picture capturing listener
     */
    fun startCapturing() {
        picturesTaken = TreeMap()
        try {
            currentCameraId = manager.cameraIdList[0]
            openCamera()
        } catch (e: CameraAccessException) {
            Log.e(
                TAG,
                "Exception occurred while accessing the list of cameras",
                e
            )
        }
    }

    private fun openCamera() {
        Log.d(
            TAG,
            "opening camera $currentCameraId"
        )
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                == PackageManager.PERMISSION_GRANTED
            ) {
                manager.openCamera(currentCameraId, stateCallback, null)
            } else {
                print("I'm else")
            }
        } catch (e: CameraAccessException) {
            Log.e(
                TAG,
                " exception occurred while opening camera $currentCameraId",
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
            if (picturesTaken!!.lastEntry() != null) {
//                capturingListener.onCaptureDone(
//                    picturesTaken!!.lastEntry().key,
//                    picturesTaken!!.lastEntry().value
//                )
                Log.i(
                    TAG,
                    "done taking picture from camera " + cameraDevice!!.id
                )
            }
            closeCamera()
        }
    }
    private val onImageAvailableListener =
        OnImageAvailableListener { imReader: ImageReader ->
            val image = imReader.acquireLatestImage()
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer[bytes]
            saveImageToDisk(bytes)
            image.close()
        }
    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraClosed = false
            Log.d(
                TAG,
                "camera " + camera.id + " opened"
            )
            cameraDevice = camera
            Log.i(
                TAG,
                "Taking picture from camera " + camera.id
            )
            print("Taking picture from camera " + camera.id)
            //Take the picture after some delay. It may resolve getting a black dark photos.
            Handler().postDelayed({
                try {
                    takePicture()
                } catch (e: CameraAccessException) {
                    Log.e(
                        TAG,
                        " exception occurred while taking picture from $currentCameraId",
                        e
                    )
                }
            }, 500)
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
        val rotation: Int = this.activity.getWindowManager().getDefaultDisplay().getRotation()
        return ORIENTATIONS.get(rotation)
    }

    @Throws(CameraAccessException::class)
    private fun takePicture() {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null")
            return
        }
        val characteristics: CameraCharacteristics =
            manager.getCameraCharacteristics(cameraDevice!!.id)
        var jpegSizes: Array<Size>? = null
        val streamConfigurationMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (streamConfigurationMap != null) {
            jpegSizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)
        }
        val jpegSizesNotEmpty = jpegSizes != null && 0 < jpegSizes.size
        val width = if (jpegSizesNotEmpty) jpegSizes!![0].width else 640
        val height = if (jpegSizesNotEmpty) jpegSizes!![0].height else 480
        val reader =
            ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
        val outputSurfaces: MutableList<Surface> =
            ArrayList()
        outputSurfaces.add(reader.surface)
        val captureBuilder =
            cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureBuilder.addTarget(reader.surface)
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureBuilder[CaptureRequest.JPEG_ORIENTATION] = getOrientation()
        reader.setOnImageAvailableListener(onImageAvailableListener, null)
        cameraDevice!!.createCaptureSession(
            outputSurfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        session.capture(captureBuilder.build(), captureListener, null)
                    } catch (e: CameraAccessException) {
                        Log.e(
                            TAG,
                            " exception occurred while accessing $currentCameraId",
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
                picturesTaken!!.put(file.path, bytes)
            }
            Log.i(TAG, "Image save successful")
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
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val storageDir: File = Environment.getExternalStorageDirectory()
        return File.createTempFile(
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        )
    }

    private fun closeCamera() {
        Log.d(
            TAG,
            "closing camera " + cameraDevice!!.id
        )
        if (null != cameraDevice && !cameraClosed) {
            cameraDevice!!.close()
            cameraDevice = null
        }
        if (null != imageReader) {
            imageReader!!.close()
            imageReader = null
        }
    }
}