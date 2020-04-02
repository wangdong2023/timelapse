package com.example.timelapse

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import java.util.concurrent.atomic.AtomicBoolean


class AudioIn(val targetFrequency: Double, private val tolerance: Float, val takePicture: AtomicBoolean): Thread() {
    val SAMPLING_RATE = 44100

    private var stopped = true
    val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLING_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    val sampleBufferSize = 512
    val buffers =
        Array(256) { ShortArray(sampleBufferSize) }
    var lastBuffer = 0

    val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLING_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize
    )
    val transformer = FastFourierTransformer(DftNormalization.STANDARD)

    init {
        recorder.setPositionNotificationPeriod(sampleBufferSize)
        recorder.setRecordPositionUpdateListener(object :
            AudioRecord.OnRecordPositionUpdateListener {
            override fun onPeriodicNotification(recorder: AudioRecord) {
//                println("last buffer ${lastBuffer}, ${currentThread().id}")
                val buffer: ShortArray = buffers.get(++lastBuffer % buffers.size)
                recorder.read(buffer, 0, sampleBufferSize)
                if (lastBuffer % 5 == 0) {
                    process(buffer)
                }
            }

            override fun onMarkerReached(recorder: AudioRecord) {}
        })
    }

    public fun startStop() {
        if (stopped) {
            println("Starting ${currentThread().id}")
            stopped = false
            recorder.startRecording()
        } else {
            println("Stopping")
            recorder.stop()
            stopped = true
        }
    }

    public fun getIsRunning(): Boolean {
        return !stopped
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
        while(true) {
            if (stopped) {
                sleep(500)
            }
        }
    }

    private fun process(buffer: ShortArray){
//        println("processing, ${currentThread().id}")
        val ffts = transformer.transform(buffer.map{it.toDouble()}.toDoubleArray(), TransformType.FORWARD)
        val magnitudes = ffts.map { Math.log10(Math.hypot(it.real, it.imaginary)) }
        val frequencies = magnitudes.mapIndexed { index, d -> index * SAMPLING_RATE * 1.0 / sampleBufferSize }
//            println(buffer.joinToString(","))
        var max = 0.0
        var maxFreq = -1.0
        for (i in 0 .. sampleBufferSize - 1) {
            if (magnitudes[i] > max) {
                max = magnitudes[i]
                maxFreq = frequencies[i]
            }
        }

        if (maxFreq > targetFrequency * (1 - tolerance) && maxFreq < targetFrequency * (1 + tolerance)) {
            println("Taking a picture")
            takePicture.set(true)
        }
    }

    private fun close() {
        recorder.stop()
        stopped = true
    }
}