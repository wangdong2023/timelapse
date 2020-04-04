package com.example.timelapse

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import java.util.concurrent.atomic.AtomicBoolean


class AudioIn(var targetFrequency: Double, private val tolerance: Int, val takePicture: AtomicBoolean, val recordAudio: AtomicBoolean): Thread() {
    val SAMPLING_RATE = 44100
    val PROCESSING_INTERVAL = 10

    val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLING_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    val sampleBufferSize = 1024
    val resolution = SAMPLING_RATE * 1.0 / sampleBufferSize
    val buffers =
        Array(200) { ShortArray(sampleBufferSize) }
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
                val buffer: ShortArray = buffers.get(++lastBuffer % buffers.size)
                recorder.read(buffer, 0, sampleBufferSize)
                if (lastBuffer % PROCESSING_INTERVAL == 0) {
                    process(buffer, lastBuffer)
                }
            }

            override fun onMarkerReached(recorder: AudioRecord) {}
        })
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
        println("Audio thread runs in ${currentThread().id}")
        while(true) {
            if (!recordAudio.get()) {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                    lastBuffer = 0
                }
                sleep(500)
            } else {
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    println("Starting ${currentThread().id}, target frequency ${targetFrequency}, resolution: ${resolution}Hz")
                    recorder.startRecording()
                }
            }
        }
    }

    private fun process(buffer: ShortArray, index: Int){
        val ffts = transformer.transform(buffer.map{it.toDouble()}.toDoubleArray(), TransformType.FORWARD)
        val magnitudes = ffts.mapIndexed{ index, cp -> Pair(index * resolution, Math.log10(Math.hypot(cp.real, cp.imaginary))) }
            .sortedByDescending { p -> p.second }

        val k = 3

        println("Thread ${currentThread().id}, ${index}th processing, " +
                "top ${k} frequencies are ${magnitudes.slice(0 until k).map { p -> p.first }.joinToString(",")}, " +
                "with magnitudes ${magnitudes.slice(0 until k).map { p -> p.second }.joinToString(",")}")

        if (magnitudes[0].first > (targetFrequency - tolerance * resolution) && magnitudes[0].first < (targetFrequency + tolerance * resolution)) {
            println("Taking a picture")
            takePicture.set(true)
        }
    }

    private fun close() {
        recorder.stop()
        recordAudio.set(false)
    }
}