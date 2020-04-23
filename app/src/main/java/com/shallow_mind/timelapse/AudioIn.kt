package com.shallow_mind.timelapse

import android.annotation.TargetApi
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresApi
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.min


@TargetApi(Build.VERSION_CODES.LOLLIPOP)
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class AudioIn(targetFrequency: Double, val takePicture: AtomicBoolean) {
    val SAMPLING_RATE = 44100
    val PROCESSING_INTERVAL = 10
    val tolerance = 0.02

    private var lowerBound = targetFrequency - targetFrequency * tolerance
    private var upperBound = targetFrequency + targetFrequency * tolerance

    var targetFrequency: Double = targetFrequency
        set(value) {
            field = value
            lowerBound = value - value * tolerance
            upperBound = value + value * tolerance
        }

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLING_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    private var backgroundThread: HandlerThread? = null

    private var backgroundHandler: Handler? = null

    val sampleBufferSize = 2048
    private val resolution = SAMPLING_RATE * 1.0 / sampleBufferSize
    val buffer = ShortArray(sampleBufferSize)
    var lastBuffer = 0

    private var recorder: AudioRecord? = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLING_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize
    )
    private val transformer = FastFourierTransformer(DftNormalization.STANDARD)

    fun startRecording() {
        startBackgroundThread()
        Log.i(this.javaClass.simpleName, "Starting recording, target frequency ${targetFrequency}, resolution: ${resolution}Hz, bufferSize: ${bufferSize}")

        if (recorder == null) {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLING_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }

        recorder!!.positionNotificationPeriod = sampleBufferSize
        recorder!!.setRecordPositionUpdateListener(object :
            AudioRecord.OnRecordPositionUpdateListener {
            override fun onPeriodicNotification(recorder: AudioRecord) {
                lastBuffer = (lastBuffer + 1) % PROCESSING_INTERVAL
                recorder.read(buffer, 0, sampleBufferSize)
                if (lastBuffer == (PROCESSING_INTERVAL - 1)) {
                    process()
                }
            }

            override fun onMarkerReached(recorder: AudioRecord) {}
        }, backgroundHandler)
        recorder!!.startRecording()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("AudioBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(this.javaClass.simpleName, e.toString())
        }
    }

    private fun process(){
//        Log.i("AudioIn", buffer.joinToString(","))
        val movingAve = movingAve(buffer)
        val ffts = transformer.transform(movingAve, TransformType.FORWARD)
        val magnitudes = ffts.slice(0 until buffer.size / 2 + 1)
            .mapIndexed{ ind, cp -> Pair(ind * resolution, hypot(cp.real, cp.imaginary)) }

        val peaks = findPeak(magnitudes)
            .sortedByDescending { p -> p.second }

        val k = min(5, peaks.size)

        for (peak in peaks.slice(0 until k)) {
            if (peak.first > lowerBound && peak.first < upperBound) {
                Log.i("AudioIn", "Taking a picture ${TimeService.getForLog()}")
                takePicture.set(true)
                break
            }
        }
    }

    private fun findPeak(magnitudes: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val res = mutableListOf<Pair<Double, Double>>()
        for (i in magnitudes.indices) {
            val prev = if (i > 0) magnitudes[i-1].second else 0.0
            val next = if (i < magnitudes.size - 1) magnitudes[i+1].second else 0.0
            if (magnitudes[i].second > prev && magnitudes[i].second > next) {
                res.add(magnitudes[i])
            }
        }
        return res
    }

    private fun movingAve(buffer: ShortArray, windowSize: Int = 2): DoubleArray {
        val ave = DoubleArray(buffer.size)
        for (i in buffer.indices) {
            val start = if (i - windowSize > 0) (i - windowSize) else 0
            val end = if (i + windowSize < buffer.size - 1) i + windowSize else buffer.size - 1
            ave[i] = buffer.slice(start .. end).sum() * 1.0/ (start - end)
        }
        return ave
    }

    fun stopRecording() {
        recorder?.release()
        recorder = null
        stopBackgroundThread()
    }
}