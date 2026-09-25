package ai.synheart.behavior

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Collects raw 50 Hz accelerometer samples and forwards them to the runtime
 * in 1-second batches for `MotionStateHead` for the on-device motion classifier to
 * classify. The collector forwards raw 50 Hz accel batches; downstream consumers run motion classification.
 *
 * Privacy: only raw motion timing/values, no location or content.
 */
class MotionSignalCollector(private val context: Context, private var config: BehaviorConfig) :
        SensorEventListener {

    companion object {
        /** 20 000 µs = 50 Hz. Units are microseconds, not milliseconds. */
        private const val ACCEL_SAMPLING_PERIOD_US = 20_000
    }

    private var sensorManager: SensorManager? = null
    private var accelerometerSensor: Sensor? = null

    private var isCollecting = false
    private var sessionStartTime: Long = 0

    // Raw accel buffer.
    private val accelerometerSamples =
            ConcurrentLinkedQueue<Pair<Long, FloatArray>>() // timestamp, [x, y, z]

    // Raw-sample batch emission (future work).
    private var rawSampleBatchHandler: ((List<Map<String, Any>>) -> Unit)? = null
    private var lastRawBatchEndMs: Long = 0
    private val rawBatchIntervalMs: Long = 1000L
    private val rawBatchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rawBatchRunnable = object : Runnable {
        override fun run() {
            flushRawBatch()
            rawBatchHandler.postDelayed(this, rawBatchIntervalMs)
        }
    }
    private var rawBatchTimerActive = false

    fun updateConfig(newConfig: BehaviorConfig) {
        config = newConfig
        val shouldCollect = config.enableMotionLite || config.emitRawMotionSamples
        if (!shouldCollect && isCollecting) {
            stopCollecting()
        } else if (shouldCollect && !isCollecting && sessionStartTime > 0) {
            startCollecting()
        }
        updateRawBatchTimer()
    }

    fun setRawSampleBatchHandler(handler: (List<Map<String, Any>>) -> Unit) {
        rawSampleBatchHandler = handler
        updateRawBatchTimer()
    }

    fun startSession(sessionStartTime: Long) {
        this.sessionStartTime = sessionStartTime
        this.lastRawBatchEndMs = sessionStartTime

        accelerometerSamples.clear()

        if (config.enableMotionLite || config.emitRawMotionSamples) {
            startCollecting()
        }
        updateRawBatchTimer()
    }

    fun stopSession() {
        stopCollecting()
        flushRawBatch()
        stopRawBatchTimer()
    }

    private fun startCollecting() {
        if (isCollecting) return
        if (!config.enableMotionLite && !config.emitRawMotionSamples) return

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sensorManager == null) {
            android.util.Log.w("MotionSignalCollector", "SensorManager not available")
            return
        }

        accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometerSensor == null) {
            android.util.Log.w(
                    "MotionSignalCollector",
                    "Accelerometer not available on this device"
            )
            return
        }

        // 50 Hz, requested as an explicit period rather than a SENSOR_DELAY_*
        // constant.
        //
        // This used to pass SENSOR_DELAY_NORMAL, which is ~200 ms (~5 Hz) — an
        // order of magnitude below what this class's own doc claims, and below
        // what iOS delivers (accelerometerUpdateInterval = 0.02). The two
        // platforms were feeding the same heads accelerometer streams that
        // differed 10x in rate, which is not a difference any downstream
        // consumer can see or correct for.
        //
        // SENSOR_DELAY_GAME also lands near 50 Hz, but the named constants are
        // hints whose real periods vary by OEM. An explicit period says what we
        // mean. The platform still delivers at whatever rate the sensor
        // supports at or faster than this, so treat it as a request, not a
        // guarantee.
        sensorManager?.registerListener(
                this,
                accelerometerSensor,
                ACCEL_SAMPLING_PERIOD_US
        )

        isCollecting = true
        android.util.Log.d("MotionSignalCollector", "Started collecting motion data")
    }

    private fun stopCollecting() {
        if (!isCollecting) return

        sensorManager?.unregisterListener(this)
        sensorManager = null
        accelerometerSensor = null

        isCollecting = false
        android.util.Log.d("MotionSignalCollector", "Stopped collecting motion data")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isCollecting) return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val timestamp = System.currentTimeMillis()
        val values = FloatArray(3)
        System.arraycopy(event.values, 0, values, 0, 3)
        accelerometerSamples.offer(Pair(timestamp, values))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op.
    }

    fun dispose() {
        stopCollecting()
        stopRawBatchTimer()
        accelerometerSamples.clear()
    }

    // MARK: - Raw-sample batch emission (future work)

    private fun updateRawBatchTimer() {
        val shouldEmit = config.emitRawMotionSamples &&
                rawSampleBatchHandler != null &&
                sessionStartTime > 0
        if (shouldEmit && !rawBatchTimerActive) {
            rawBatchTimerActive = true
            rawBatchHandler.postDelayed(rawBatchRunnable, rawBatchIntervalMs)
        } else if (!shouldEmit) {
            stopRawBatchTimer()
        }
    }

    private fun stopRawBatchTimer() {
        if (rawBatchTimerActive) {
            rawBatchHandler.removeCallbacks(rawBatchRunnable)
            rawBatchTimerActive = false
        }
    }

    /**
     * Pull samples accumulated since the last flush and forward to the
     * runtime via the registered handler. Trims the per-axis buffer to the
     * last 10 seconds so memory stays bounded if no consumer is attached.
     */
    private fun flushRawBatch() {
        val handler = rawSampleBatchHandler ?: return
        if (!config.emitRawMotionSamples) return
        val nowMs = System.currentTimeMillis()
        val cutoffStart = lastRawBatchEndMs
        val trimCutoff = nowMs - 10_000L

        val batch = mutableListOf<Map<String, Any>>()
        // Drain newer-than-cutoff samples; trim older ones.
        val keep = ArrayList<Pair<Long, FloatArray>>(accelerometerSamples.size)
        for (entry in accelerometerSamples) {
            val ts = entry.first
            if (ts <= cutoffStart) {
                if (ts >= trimCutoff) keep.add(entry)
                continue
            }
            if (ts > nowMs) {
                keep.add(entry)
                continue
            }
            val axes = entry.second
            batch.add(
                mapOf(
                    "ts_ms" to ts,
                    "ax" to axes[0].toDouble(),
                    "ay" to axes[1].toDouble(),
                    "az" to axes[2].toDouble(),
                ) as Map<String, Any>
            )
            if (ts >= trimCutoff) keep.add(entry)
        }
        accelerometerSamples.clear()
        accelerometerSamples.addAll(keep)
        lastRawBatchEndMs = nowMs

        if (batch.isEmpty()) return
        handler(batch)
    }
}
