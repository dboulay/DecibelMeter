package com.example.decibelmeterv2

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.math.log10
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeasurementService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: AudioRecord? = null
    private var running = false
    private val prefs by lazy { getSharedPreferences("dbmeter", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopMeasurement()
            return START_NOT_STICKY
        }
        if (!running) startMeasurement()
        return START_STICKY
    }

    private fun startMeasurement() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this,
            1001,
            notification("Mesure en cours…"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        running = true

        scope.launch {
            val rate = 44100
            val size = (AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2).coerceAtLeast(4096)

            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, size
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                stopMeasurement()
                return@launch
            }

            recorder = ar
            val buffer = ShortArray(size / 2)
            val locationClient = LocationServices.getFusedLocationProviderClient(this@MeasurementService)
            var lastLocation = 0L
            var lat: Double? = null
            var lon: Double? = null
            var lastNotification = 0L
            var lastSave = 0L

            ar.startRecording()
            try {
                while (isActive && running) {
                    val n = ar.read(buffer, 0, buffer.size)
                    if (n <= 0) continue

                    var energy = 0.0
                    for (i in 0 until n) {
                        val x = buffer[i] / 32768.0
                        energy += x * x
                    }
                    val rms = sqrt(energy / n).coerceAtLeast(1e-9)
                    val calibration = prefs.getFloat("calibration", 94f)
                    val db = (20 * log10(rms) + calibration).toFloat().coerceIn(20f, 140f)
                    MainActivity.db = db

                    val now = System.currentTimeMillis()

                    if (now - lastLocation >= 5000L &&
                        (ContextCompat.checkSelfPermission(this@MeasurementService,
                            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                         ContextCompat.checkSelfPermission(this@MeasurementService,
                            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
                        lastLocation = now
                        runCatching {
                            val loc = locationClient.getCurrentLocation(
                                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                                CancellationTokenSource().token
                            ).await()
                            if (loc != null) {
                                lat = loc.latitude
                                lon = loc.longitude
                            }
                        }
                    }

                    // Save one sample every 5 seconds.
                    if (now - lastSave >= 5000L) {
                        lastSave = now
                        saveSample(now, db, lat, lon)
                    }

                    if (now - lastNotification >= 2000L) {
                        lastNotification = now
                        val text = "${db.toInt()} dBA"
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(1001, notification("Mesure en cours : $text"))
                    }
                }
            } finally {
                ar.stop()
                ar.release()
                recorder = null
            }
        }
    }

    private fun saveSample(time: Long, db: Float, lat: Double?, lon: Double?) {
        val old = prefs.getString("measures", "") ?: ""
        val row = "$time;$db;${lat ?: "null"};${lon ?: "null"}"
        val new = if (old.isBlank()) row else "$old|$row"
        val parts = new.split("|")
        val trimmed = if (parts.size > 20000) parts.takeLast(20000).joinToString("|") else new
        prefs.edit().putString("measures", trimmed).apply()
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, "dbmeter_measurement")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DB-Meter")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0, "ARRÊTER",
                android.app.PendingIntent.getService(
                    this, 2, Intent(this, MeasurementService::class.java).setAction("STOP"),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0
                )
            )
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "dbmeter_measurement", "Mesure DB-Meter",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun stopMeasurement() {
        running = false
        scope.coroutineContext.cancelChildren()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        MainActivity.measuring = false
    }

    override fun onDestroy() {
        running = false
        recorder?.runCatching { stop() }
        recorder?.runCatching { release() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
