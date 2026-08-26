package com.example.decibelmeterv2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

data class Measure(
    val time: Long,
    val db: Float,
    val lat: Double?,
    val lon: Double?
)

data class DayStats(
    val day: String,
    val min: Float,
    val avg: Float,
    val max: Float,
    val samples: Int,
    val durationSeconds: Long
)

class MainActivity : ComponentActivity() {
    companion object {
        var measuring by mutableStateOf(false)
        var db by mutableFloatStateOf(0f)
        var calibration by mutableFloatStateOf(94f)
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DBMeterApp(
                onRequestPermissions = {
                    permissions.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    )
                }
            )
        }
    }
}

private fun prefs(context: Context) =
    context.getSharedPreferences("dbmeter", Context.MODE_PRIVATE)

private fun loadMeasures(context: Context): List<Measure> {
    val raw = prefs(context).getString("measures", "") ?: ""
    if (raw.isBlank()) return emptyList()
    return raw.split("|").mapNotNull {
        val p = it.split(";")
        if (p.size != 4) null else runCatching {
            Measure(p[0].toLong(), p[1].toFloat(),
                if (p[2] == "null") null else p[2].toDouble(),
                if (p[3] == "null") null else p[3].toDouble())
        }.getOrNull()
    }
}

private fun saveMeasures(context: Context, list: List<Measure>) {
    // Keep the last 20,000 samples locally.
    val trimmed = list.takeLast(20000)
    val raw = trimmed.joinToString("|") {
        "${it.time};${it.db};${it.lat ?: "null"};${it.lon ?: "null"}"
    }
    prefs(context).edit().putString("measures", raw).apply()
}

private fun dayOf(t: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(t))

private fun timeOf(t: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))

@Composable
fun DBMeterApp(onRequestPermissions: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var measures by remember { mutableStateOf(loadMeasures(context)) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf("measure") }
    var samples by remember { mutableStateOf(listOf<Float>()) }
    var min by remember { mutableFloatStateOf(200f) }
    var max by remember { mutableFloatStateOf(-200f) }
    var sum by remember { mutableFloatStateOf(0f) }
    var count by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(MainActivity.measuring) {
        if (!MainActivity.measuring) return@LaunchedEffect
        min=200f; max=-200f; sum=0f; count=0; elapsed=0L
        val rate=44100
        val bufferSize=(android.media.AudioRecord.getMinBufferSize(
            rate, android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )*2).coerceAtLeast(4096)
        val locationClient=LocationServices.getFusedLocationProviderClient(context)
        val start=System.currentTimeMillis()

        withContext(Dispatchers.IO) {
            val ar=android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,rate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,bufferSize
            )
            if(ar.state != android.media.AudioRecord.STATE_INITIALIZED) return@withContext
            ar.startRecording()
            val buffer=ShortArray(bufferSize/2)
            var lastSave=0L
            try {
                while(isActive && MainActivity.measuring) {
                    val n=ar.read(buffer,0,buffer.size)
                    if(n<=0) continue
                    var energy=0.0
                    for(i in 0 until n) { val x=buffer[i]/32768.0; energy+=x*x }
                    val rms=sqrt(energy/n).coerceAtLeast(1e-9)
                    val value=(20*log10(rms)+MainActivity.calibration).toFloat().coerceIn(20f,140f)
                    MainActivity.db=value
                    val now=System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        samples=(samples+value).takeLast(120)
                        min=kotlin.math.min(min,value); max=kotlin.math.max(max,value)
                        sum+=value; count++
                        elapsed=(now-start)/1000L
                    }

                    // Save one geolocated sample roughly every 5 seconds.
                    if(now-lastSave>=5000L) {
                        lastSave=now
                        var loc: Location?=null
                        if(androidx.core.content.ContextCompat.checkSelfPermission(
                                context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                           androidx.core.content.ContextCompat.checkSelfPermission(
                                context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
                            runCatching {
                                loc=locationClient.getCurrentLocation(
                                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                                    CancellationTokenSource().token
                                ).await()
                            }
                        }
                        val m=Measure(now,value,loc?.latitude,loc?.longitude)
                        withContext(Dispatchers.Main) {
                            measures=(measures+m).takeLast(20000)
                            saveMeasures(context,measures)
                        }
                    }
                }
            } finally { ar.stop(); ar.release() }
        }
    }

    val days=measures.map { dayOf(it.time) }.distinct().sortedDescending()
    val selected=selectedDay
    MaterialTheme(colorScheme=darkColorScheme(
        background=Color(0xFF101114),surface=Color(0xFF191B20),primary=Color(0xFF55C7FF)
    )) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Text("DB-METER",fontSize=25.sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                    FilterChip(selected=screen=="measure",onClick={screen="measure"},label={Text("Mesure")})
                    FilterChip(selected=screen=="history",onClick={screen="history"},label={Text("Historique")})
                    FilterChip(selected=screen=="map",onClick={screen="map"},label={Text("Carte")})
                }
                Spacer(Modifier.height(12.dp))

                when(screen) {
                    "measure" -> MeasureScreen(
                        samples, min,max,sum,count,elapsed,
                        onStart={
                            MainActivity.measuring=true
                            ContextCompat.startForegroundService(
                                context, Intent(context, MeasurementService::class.java)
                            )
                        },
                        onStop={
                            MainActivity.measuring=false
                            context.stopService(Intent(context, MeasurementService::class.java))
                        },
                        onPermissions=onRequestPermissions
                    )
                    "history" -> HistoryScreen(
                        days=days, selectedDay=selected,
                        measures=measures,
                        onSelect={ selectedDay=it; screen="map" }
                    )
                    "map" -> {
                        if(selected==null) {
                            Text("Sélectionne un jour dans Historique.")
                        } else {
                            Text("Carte — $selected",fontSize=20.sp,fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            DayMap(measures.filter { dayOf(it.time)==selected })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MeasureScreen(
    samples:List<Float>,min:Float,max:Float,sum:Float,count:Int,elapsed:Long,
    onStart:()->Unit,onStop:()->Unit,onPermissions:()->Unit
) {
    val avg=if(count==0)0f else sum/count
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text("${MainActivity.db.toInt()} dBA",fontSize=48.sp,fontWeight=FontWeight.Bold)
        Text("Sonomètre dBA",color=Color.LightGray)
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            if(samples.size>1) {
                val step=size.width/(samples.size-1)
                for(i in 1 until samples.size) {
                    val y1=size.height-((samples[i-1]-30)/90f)*size.height
                    val y2=size.height-((samples[i]-30)/90f)*size.height
                    drawLine(Color(0xFF55C7FF),
                        Offset((i-1)*step,y1.coerceIn(0f,size.height)),
                        Offset(i*step,y2.coerceIn(0f,size.height)),4f)
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            Stat("MIN",if(count>0)"${min.toInt()}" else "—")
            Stat("MOY",if(count>0)"${avg.toInt()}" else "—")
            Stat("MAX",if(count>0)"${max.toInt()}" else "—")
            Stat("TEMPS","${elapsed}s")
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick={if(MainActivity.measuring)onStop()else onStart()},
            modifier=Modifier.fillMaxWidth().height(52.dp)) {
            Text(if(MainActivity.measuring)"ARRÊTER" else "DÉMARRER")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick=onPermissions,modifier=Modifier.fillMaxWidth()) {
            Text("📍 Autoriser microphone + géolocalisation")
        }
        Spacer(Modifier.height(12.dp))
        Text("Une position GPS est enregistrée environ toutes les 5 secondes lorsque le GPS est autorisé.",
            fontSize=11.sp,color=Color.Gray)
    }
}

@Composable fun Stat(title:String,value:String) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text(title,fontSize=11.sp,color=Color.Gray); Text(value,fontSize=19.sp,fontWeight=FontWeight.Bold)
    }
}

@Composable
fun HistoryScreen(
    days:List<String>, selectedDay:String?, measures:List<Measure>,
    onSelect:(String)->Unit
) {
    Column {
        Text("Historique",fontSize=21.sp,fontWeight=FontWeight.Bold)
        Text("Sélectionne un jour pour voir les mesures et la carte.",fontSize=12.sp,color=Color.Gray)
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(days) { day ->
                val d=measures.filter { dayOf(it.time)==day }
                val mn=d.minOfOrNull { it.db } ?: 0f
                val mx=d.maxOfOrNull { it.db } ?: 0f
                val av=if(d.isEmpty())0f else d.map{it.db}.average().toFloat()
                val gps=d.count { it.lat!=null && it.lon!=null }
                Card(Modifier.fillMaxWidth().padding(vertical=4.dp).clickable{onSelect(day)}) {
                    Column(Modifier.padding(12.dp)) {
                        Text(day,fontWeight=FontWeight.Bold)
                        Text("Min ${mn.toInt()}  •  Moy ${av.toInt()}  •  Max ${mx.toInt()} dBA")
                        Text("${d.size} mesures  •  $gps positions GPS",fontSize=12.sp,color=Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun DayMap(measures:List<Measure>) {
    val points=measures.filter { it.lat!=null && it.lon!=null }
    if(points.isEmpty()) {
        Text("Aucun point GPS disponible pour ce jour.")
        return
    }
    val markers=points.joinToString(",") {
        """{"lat":${it.lat},"lon":${it.lon},"db":${it.db},"time":"${timeOf(it.time)}"}"""
    }
    AndroidView(
        modifier=Modifier.fillMaxSize(),
        factory={ctx->
            WebView(ctx).apply {
                settings.javaScriptEnabled=true
                settings.domStorageEnabled=true
                webViewClient=WebViewClient()
                loadDataWithBaseURL(
                    "https://localhost/",
                    mapHtml(markers), "text/html", "UTF-8", null
                )
            }
        }
    )
}

private fun mapHtml(markers:String)= """
<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<style>html,body,#map{height:100%;margin:0} .p{font:14px sans-serif}</style>
</head><body><div id="map"></div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
const pts=[$markers];
const center=[pts[0].lat,pts[0].lon];
const map=L.map('map').setView(center,15);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
 {maxZoom:19,attribution:'© OpenStreetMap contributors'}).addTo(map);
const bounds=[];
pts.forEach(p=>{
 const m=L.marker([p.lat,p.lon]).addTo(map);
 m.bindPopup('<div class="p"><b>'+p.db.toFixed(1)+' dBA</b><br>'+p.time+'</div>');
 bounds.push([p.lat,p.lon]);
});
if(bounds.length>1) map.fitBounds(bounds,{padding:[20,20]});
</script></body></html>
""".trimIndent()
