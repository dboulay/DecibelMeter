package com.example.decibelmeterv2

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private var recorder: AudioRecord? = null

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) measuring = true }

    companion object {
        var measuring by mutableStateOf(false)
        var db by mutableFloatStateOf(0f)
        var calibration by mutableFloatStateOf(94f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App(
                onStart = {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) measuring = true
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStop = {
                    measuring = false
                    recorder?.runCatching { stop() }
                    recorder?.release()
                    recorder = null
                }
            )
        }
    }
}

@Composable
fun App(onStart: () -> Unit, onStop: () -> Unit) {
    var history by remember { mutableStateOf(listOf<Float>()) }
    var min by remember { mutableFloatStateOf(200f) }
    var max by remember { mutableFloatStateOf(-200f) }
    var sum by remember { mutableFloatStateOf(0f) }
    var count by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableIntStateOf(0) }

    LaunchedEffect(MainActivity.measuring) {
        if (!MainActivity.measuring) return@LaunchedEffect
        min = 200f; max = -200f; sum = 0f; count = 0; elapsed = 0

        val rate = 44100
        val bufferSize = (AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2).coerceAtLeast(4096)

        withContext(Dispatchers.IO) {
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) return@withContext
            ar.startRecording()
            val buffer = ShortArray(bufferSize / 2)
            var lastSecond = System.currentTimeMillis()

            try {
                while (isActive && MainActivity.measuring) {
                    val n = ar.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    var energy = 0.0
                    for (i in 0 until n) {
                        val x = buffer[i] / 32768.0
                        energy += x*x
                    }
                    val rms = sqrt(energy/n).coerceAtLeast(1e-9)
                    // Relative level + user calibration offset.
                    val value = (20*log10(rms) + MainActivity.calibration).toFloat()
                        .coerceIn(20f, 140f)
                    MainActivity.db = value

                    withContext(Dispatchers.Main) {
                        history = (history + value).takeLast(120)
                        min = kotlin.math.min(min, value)
                        max = kotlin.math.max(max, value)
                        sum += value; count++
                        if (System.currentTimeMillis()-lastSecond >= 1000) {
                            elapsed++
                            lastSecond = System.currentTimeMillis()
                        }
                    }
                }
            } finally {
                ar.stop()
                ar.release()
            }
        }
    }

    val avg = if (count == 0) 0f else sum/count
    val level = MainActivity.db
    val label = when {
        level < 40 -> "Très calme"
        level < 55 -> "Calme"
        level < 70 -> "Normal"
        level < 85 -> "Bruyant"
        else -> "Très bruyant"
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF101114),
            surface = Color(0xFF191B20),
            primary = Color(0xFF55C7FF)
        )
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("DÉCIBELMÈTRE", fontSize=25.sp, fontWeight=FontWeight.Bold)
                Text("Sonomètre dBA • V2", color=Color.LightGray)
                Spacer(Modifier.height(25.dp))

                Box(
                    Modifier.size(230.dp),
                    contentAlignment=Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val fraction=((level-30f)/90f).coerceIn(0f,1f)
                        drawArc(
                            Color.DarkGray, 135f, 270f, false,
                            style=androidx.compose.ui.graphics.drawscope.Stroke(22f)
                        )
                        drawArc(
                            Color(0xFF55C7FF), 135f, 270f*fraction, false,
                            style=androidx.compose.ui.graphics.drawscope.Stroke(22f)
                        )
                    }
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("${level.toInt()}", fontSize=52.sp, fontWeight=FontWeight.Bold)
                        Text("dBA", fontSize=20.sp)
                        Text(label, color=Color.LightGray)
                    }
                }

                Spacer(Modifier.height(18.dp))

                Canvas(
                    Modifier.fillMaxWidth().height(110.dp)
                ) {
                    if(history.size>1) {
                        val step=size.width/(history.size-1)
                        for(i in 1 until history.size) {
                            val y1=size.height-((history[i-1]-30)/90f)*size.height
                            val y2=size.height-((history[i]-30)/90f)*size.height
                            drawLine(
                                Color(0xFF55C7FF),
                                Offset((i-1)*step,y1.coerceIn(0f,size.height)),
                                Offset(i*step,y2.coerceIn(0f,size.height)),
                                4f
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.SpaceEvenly
                ) {
                    Stat("MIN", if(count>0) "${min.toInt()}" else "—")
                    Stat("MOY", if(count>0) "${avg.toInt()}" else "—")
                    Stat("MAX", if(count>0) "${max.toInt()}" else "—")
                    Stat("TEMPS", "${elapsed}s")
                }

                Spacer(Modifier.height(18.dp))

                Button(
                    onClick={if(MainActivity.measuring) onStop() else onStart()},
                    modifier=Modifier.fillMaxWidth().height(55.dp)
                ) {
                    Text(if(MainActivity.measuring) "ARRÊTER" else "DÉMARRER", fontSize=18.sp)
                }

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Calibration : ${MainActivity.calibration.toInt()} dB")
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick={
                            MainActivity.calibration =
                                (MainActivity.calibration + 1f).coerceAtMost(120f)
                        },
                        shape=CircleShape,
                        contentPadding=PaddingValues(0.dp),
                        modifier=Modifier.size(42.dp)
                    ) { Text("+") }
                    Spacer(Modifier.width(5.dp))
                    Button(
                        onClick={
                            MainActivity.calibration =
                                (MainActivity.calibration - 1f).coerceAtLeast(50f)
                        },
                        shape=CircleShape,
                        contentPadding=PaddingValues(0.dp),
                        modifier=Modifier.size(42.dp)
                    ) { Text("−") }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "⚠ La valeur dBA affichée est une estimation. " +
                    "Pour une mesure acoustique fiable, utilisez une source de calibration connue " +
                    "et tenez compte du microphone et du traitement audio du téléphone.",
                    fontSize=11.sp, color=Color.Gray
                )
            }
        }
    }
}

@Composable
fun Stat(title:String,value:String) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text(title,fontSize=11.sp,color=Color.Gray)
        Text(value,fontSize=20.sp,fontWeight=FontWeight.Bold)
    }
}
