package com.shallow_mind.timelapse

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
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
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.schedule

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class PictureCapturingService(private val activity: Activity, private val projectName: AtomicReference<String>, private val handler: Handler? = null) {
    private val ORIENTATIONS = SparseIntArray()
    // to not take picture too frequently, this also avoids the bug that it may take 2 pictures for one layer change
    private val MIN_CAPTURE_INTERVAL = 2000L

    init {
        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }
    private var cameraDevice: CameraDevice? = null

    enum class CameraState {
        CLOSED, OPEN, LOCKING_FOCUS, PRE_CAPTURING, READY, CAPTURING
    }

    private var cameraId: String? = null
    private var cameraState = CameraState.CLOSED
    private var reader: ImageReader
    private var cameraCaptureSession: CameraCaptureSession? = null

    private val dummyPreview = SurfaceTexture(1)
    private val dummySurface = Surface(dummyPreview)
    private var flashSupported = false

    private val onImageAvailableListener = OnImageAvailableListener { imReader: ImageReader ->
        Timer("start", false).schedule(MIN_CAPTURE_INTERVAL) {
            cameraState = CameraState.OPEN
        }

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
                flashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
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
        reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
        reader.setOnImageAvailableListener(onImageAvailableListener, handler)
    }

    fun capture(): Boolean {
        if (cameraState != CameraState.OPEN) {
            Log.i(this.javaClass.simpleName, "Camera not ready, skip this picture")
            return false
        } else {
            Log.i(this.javaClass.simpleName, "Start capturing at ${TimeService.getForLog()}")
            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(dummySurface)
            // Auto focus should be continuous for camera preview.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            // Flash is automatically enabled when necessary.
            setAutoFlash(previewRequestBuilder)
            cameraCaptureSession?.setRepeatingRequest(previewRequestBuilder.build(), captureCallback, handler)
            runPrecaptureSequenceAndThenCapture()
            return true
        }
    }

    private fun openCameraSession() {
        cameraDevice!!.createCaptureSession(
            listOf(reader.surface, dummySurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        Log.i(this.javaClass.simpleName, "Session configured ${TimeService.getForLog()}")
                        cameraCaptureSession = session
                        cameraState = CameraState.OPEN

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
            if (null != cameraCaptureSession) {
                cameraCaptureSession!!.close()
            }
            cameraDevice!!.close()
            cameraDevice = null
        }
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
                        if (cameraDevice != null) {
                            cameraState = CameraState.CLOSED
                            cameraDevice!!.close()
                        }
                    }

                    override fun onClosed(camera: CameraDevice) {
                        Log.d(this.javaClass.simpleName, "camera " + camera.id + " closed")
                        cameraState = CameraState.CLOSED
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(this.javaClass.simpleName, "camera in error, int code $error")
                        if (cameraDevice != null) {
                            cameraState = CameraState.CLOSED
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

    private val captureCallback: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            when (cameraState) {
                CameraState.PRE_CAPTURING -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == null) {
                        Log.i(this.javaClass.simpleName, "AF null, Direct still capture")
                        captureStillPicture()
                    } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        // CONTROL_AE_STATE can be null on some devices
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            Log.i(this.javaClass.simpleName, "AE null or converged, still capture")
                            captureStillPicture()
                        } else {
                            Log.i(this.javaClass.simpleName, "AE not ready")
                        }
                    }
                }
                else -> {
                    Log.i(this.javaClass.simpleName, "None capture state ${cameraState}")
                }
            }
        }
    }

    private val stillCaptureCallback: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest,
            result: TotalCaptureResult) {

            Log.i(this.javaClass.simpleName, "Camera state ${cameraState}")
            cameraState = CameraState.READY
        }
    }

    private fun getOrientation(): Int {
        val rotation: Int = activity.getWindowManager().getDefaultDisplay().getRotation()
        return ORIENTATIONS.get(rotation)
    }

    private fun captureStillPicture() {
        cameraState = CameraState.READY
        cameraCaptureSession!!.stopRepeating()
        val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.JPEG_ORIENTATION, getOrientation())
            // Use the same AE and AF modes as the preview.
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        cameraState = CameraState.CAPTURING

        cameraCaptureSession!!.capture(captureBuilder.build(), stillCaptureCallback, handler)
    }

    private fun saveImageToDisk(bytes: ByteArray) {
        val file = createImageFile()
        try {
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }
            val toast = Toast.makeText(activity.applicationContext, "Image captured at ${file.absolutePath}", Toast.LENGTH_SHORT)
            toast.show()
        } catch (e: IOException) {
            Log.e(this.javaClass.simpleName, "Exception while saving picture", e)
        }
    }

    private fun createImageFile(): File {
        val storageDir = context.getExternalFilesDir(projectName.get())
        return File(storageDir, "${TimeService.getForFile()}.jpg")
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private fun lockFocus() {
        try {
            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(dummySurface)
            // This is how to tell the camera to lock focus.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START)
            setAutoFlash(previewRequestBuilder)
            // Tell #captureCallback to wait for the lock.
            cameraState = CameraState.LOCKING_FOCUS
//            Log.i(this.javaClass.simpleName, "locking focus")

            cameraCaptureSession?.capture(previewRequestBuilder.build(), captureCallback, handler)
        } catch (e: CameraAccessException) {
            Log.e(this.javaClass.simpleName, e.toString())
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in [.captureCallback] from [.lockFocus].
     */
    private fun runPrecaptureSequenceAndThenCapture() {
        try {
            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(dummySurface)
            // This is how to tell the camera to trigger.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START)
            setAutoFlash(previewRequestBuilder)
            // Tell #captureCallback to wait for the precapture sequence to be set.
            cameraState = CameraState.PRE_CAPTURING
            cameraCaptureSession?.capture(previewRequestBuilder.build(), object : CaptureCallback() {}, handler)
        } catch (e: CameraAccessException) {
            Log.e(this.javaClass.simpleName, e.toString())
        }
    }

    private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
        if (flashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
    }
}