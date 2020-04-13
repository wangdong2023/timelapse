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
import android.hardware.camera2.CameraDevice.StateCallback
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
import java.util.concurrent.atomic.AtomicReference


/**
 * The aim of this service is to secretly take pictures (without preview or opening device's camera app)
 * from all available cameras using Android Camera 2 API
 *
 * @author hzitoun (zitoun.hamed@gmail.com)
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP) //NOTE: camera 2 api was added in API level 21
@RequiresApi(Build.VERSION_CODES.LOLLIPOP) //NOTE: camera 2 api was added in API level 21
class PictureCapturingService(private val activity: Activity, private val projectName: AtomicReference<String>, private val handler: Handler? = null) {
    private val ORIENTATIONS = SparseIntArray()

    init {
        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }
    private var cameraDevice: CameraDevice? = null

    private var cameraId: String? = null
    private var cameraIdle = true
    private var reader: ImageReader
    private var cameraCaptureSession: CameraCaptureSession? = null

    private val onImageAvailableListener =
        OnImageAvailableListener { imReader: ImageReader ->
            val image = imReader.acquireLatestImage()
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            // buffer.get(bytes)
            buffer[bytes]
            Log.i(this.javaClass.simpleName, "Got image at ${TimeService.getForLog()}")
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
        Log.i(this.javaClass.simpleName, "Cameraid ${cameraId}")

        val streamConfigurationMap =
            characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        var jpegSizes: Array<Size> = emptyArray()
        if (streamConfigurationMap != null) {
            jpegSizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)
        }

        val width = if (jpegSizes.isNotEmpty()) jpegSizes[0].width else 640
        val height = if (jpegSizes.isNotEmpty()) jpegSizes[0].height else 480

        Log.i(this.javaClass.simpleName, "Image size is ${width} * ${height}")
        reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 5)
        reader.setOnImageAvailableListener(onImageAvailableListener, handler)

    }

    /**
     * Starts pictures capturing treatment.
     *
     * @param listener picture capturing listener
     */
    fun capture() {
        if (!cameraIdle) {
            Log.i(this.javaClass.simpleName, "Camera in use, skip this picture")
        } else {
            takePicture()
        }
    }

    private fun openCameraSession() {
        cameraDevice!!.createCaptureSession(
            listOf(reader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        Log.i(this.javaClass.simpleName, "Session configured ${TimeService.getForLog()}")
                        cameraCaptureSession = session
                    } catch (e: CameraAccessException) {
                        Log.e(this.javaClass.simpleName, "Exception while accessing $cameraId", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            handler)
    }

    fun closeCamera() {
        Log.d(this.javaClass.simpleName, "closing camera " + cameraDevice!!.id)
        if (null != cameraDevice) {
            cameraDevice!!.close()
            cameraDevice = null
        }
        cameraIdle = true
    }

    fun openCamera() {
        Log.i(this.javaClass.simpleName, "Opening camera ${TimeService.getForLog()}")

        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                manager.openCamera(cameraId!!, object: StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        Log.i(this.javaClass.simpleName, "camera " + camera.id + " opened" + " ${TimeService.getForLog()}")
                        cameraDevice = camera

                        openCameraSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.d(this.javaClass.simpleName, " camera " + camera.id + " disconnected")
                        if (cameraDevice != null && !cameraIdle) {
                            cameraIdle = true
                            cameraDevice!!.close()
                        }
                    }

                    override fun onClosed(camera: CameraDevice) {
                        cameraIdle = true
                        Log.d(this.javaClass.simpleName, "camera " + camera.id + " closed")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(this.javaClass.simpleName, "camera in error, int code $error")
                        if (cameraDevice != null && !cameraIdle) {
                            cameraDevice!!.close()
                        }
                    }
                }, handler)
            } else {
                throw RuntimeException("Permission not granted for camera")
            }
        } catch (e: CameraAccessException) {
            Log.e(this.javaClass.simpleName, " exception occurred while opening camera $cameraId", e)
        }
    }

    private val captureListener: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest,
            result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
            cameraIdle = true
        }
    }

    private fun getOrientation(): Int {
        val rotation: Int = activity.getWindowManager().getDefaultDisplay().getRotation()
        return ORIENTATIONS.get(rotation)
    }

    private fun takePicture() {
        if (null == cameraCaptureSession) {
            openCamera()
            Log.e(this.javaClass.simpleName, "camera session is null, re-initialing")
            return
        }

        val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureBuilder.addTarget(reader.surface)
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureBuilder[CaptureRequest.JPEG_ORIENTATION] = getOrientation()
        cameraCaptureSession!!.capture(captureBuilder.build(), captureListener, handler)
    }

    private fun saveImageToDisk(bytes: ByteArray) {
        val file = createImageFile()
        try {
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }
            Log.i(this.javaClass.simpleName, "Image created at ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e(this.javaClass.simpleName, "Exception while saving picture", e)
        }
    }

    private fun createImageFile(): File {
        // Create an image file name
        val storageDir = context.getExternalFilesDir(projectName.get())
        return File(storageDir, "${TimeService.getForFile()}.jpg")
    }
}