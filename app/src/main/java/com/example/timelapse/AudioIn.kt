package com.example.timelapse

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.min


class AudioIn(var targetFrequency: Double, private val tolerance: Int, val takePicture: AtomicBoolean, val recordAudio: AtomicBoolean): Thread() {
    val SAMPLING_RATE = 44100
    val PROCESSING_INTERVAL = 30

    val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLING_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    val sampleBufferSize = 2048
    val resolution = SAMPLING_RATE * 1.0 / sampleBufferSize
    val buffers =
        Array(PROCESSING_INTERVAL * 2) { ShortArray(sampleBufferSize) }
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
                if (lastBuffer % PROCESSING_INTERVAL == (PROCESSING_INTERVAL - 1)) {
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
                    println(recorder.recordingState)
                    println("Starting ${currentThread().id}, target frequency ${targetFrequency}, resolution: ${resolution}Hz, bufferSize: ${bufferSize}")
                    recorder.startRecording()
                }
            }
        }
    }

    private fun process(buffer: ShortArray, index: Int){
        val movingAve = movingAve(buffer, 2)
        val ffts = transformer.transform(movingAve, TransformType.FORWARD)
//        println(buffer.joinToString(","))
//        println(ffts.joinToString(","))
        val magnitudes = ffts.slice(0 until buffer.size / 2 + 1)
            .mapIndexed{ ind, cp -> Pair(ind * resolution, hypot(cp.real, cp.imaginary)) }

        val peaks = findPeak(magnitudes)
            .sortedByDescending { p -> p.second }
//        println(magnitudes.joinToString(","))
//        println("average is ${magnitudes.map { p -> p.second }.sum() / magnitudes.size}, find ${peaks.size} peaks")

        val k = min(5, peaks.size)

//        println("Thread ${currentThread().id}, ${index}th processing, " +
//                "top ${k} frequencies are ${peaks.slice(0 until k).map { p -> p.first }.joinToString(",")}, " +
//                "with magnitudes ${peaks.slice(0 until k).map { p -> p.second }.joinToString(",")}")

        for (peak in peaks.slice(0 until k)) {
            if (peak.first > (targetFrequency - tolerance * resolution) && peak.first < (targetFrequency + tolerance * resolution)) {
                println("Taking a picture ${TimeService.getForLog()}")
                takePicture.set(true)
                break
            }
        }
    }

    private fun findPeak(magnitudes: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val res = mutableListOf<Pair<Double, Double>>();
        for (i in magnitudes.indices) {
            val prev = if (i > 0) magnitudes[i-1].second else 0.0
            val next = if (i < magnitudes.size - 1) magnitudes[i+1].second else 0.0
            if (magnitudes[i].second > prev && magnitudes[i].second > next) {
                res.add(magnitudes[i])
            }
        }
        return res;
    }

    private fun movingAve(buffer: ShortArray, windowSize: Int): DoubleArray {
        val ave = DoubleArray(buffer.size)
        for (i in 0 until buffer.size) {
            val start = if (i - windowSize > 0) (i - windowSize) else 0
            val end = if (i + windowSize < buffer.size - 1) i + windowSize else buffer.size - 1
            ave[i] = buffer.slice(start .. end).sum() * 1.0/ (start - end)
        }
        return ave
    }

    private fun close() {
        recorder.stop()
        recordAudio.set(false)
    }
}