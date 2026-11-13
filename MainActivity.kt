package com.example.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.*

data class Person(val id: String, val ig: String, val lat: Double, val lng: Double, val ts: Long)

fun distKm(a: Double, b: Double, c: Double, d: Double): Double {
    val r = Math.PI / 180
    val h = sin((c - a) * r / 2).pow(2) + cos(a * r) * cos(c * r) * sin((d - b) * r / 2).pow(2)
    return 12742 * asin(sqrt(h))
}

fun cleanIg(s: String) = s.filter { it.isLetterOrDigit() || it == '.' || it == '_' }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder().setApiKey(Cfg.API_KEY).setApplicationId(Cfg.APP_ID)
                    .setProjectId(Cfg.PROJECT_ID).build()
            )
        }
        enableEdgeToEdge()
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

// تسجيل الدخول يتم داخل صفحة إنستغرام نفسها، فلا يرى التطبيق كلمة مرورك
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Web(url: String, modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = { c ->
        WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl(url)
        }
    })
}

@SuppressLint("MissingPermission")
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val prefs = remember { ctx.getSharedPreferences("p", 0) }

    var uid by remember { mutableStateOf(auth.currentUser?.uid) }
    var ig by remember { mutableStateOf(prefs.getString("ig", "") ?: "") }
    var visible by remember { mutableStateOf(prefs.getBoolean("vis", false)) }
    var loc by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var msg by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var sub by remember { mutableStateOf(0) }

    suspend fun fetchLocation() {
        try {
            val l = LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token)
                .await()
            // الموقع يُقرّب إلى نحو كيلومتر واحد لحماية الخصوصية
            if (l != null) loc = Pair(round(l.latitude * 100) / 100, round(l.longitude * 100) / 100)
            else msg = "تعذّر تحديد الموقع، فعّل GPS ثم أعد المحاولة"
        } catch (e: Exception) {
            msg = "تعذّر تحديد الموقع"
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { fetchLocation() } else msg = "نحتاج إذن الموقع لعرض القريبين منك"
    }

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            try { auth.signInAnonymously().await() } catch (e: Exception) { msg = "تعذّر الاتصال بالخادم" }
        }
        uid = auth.currentUser?.uid
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) fetchLocation() else permLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // نشر موقعك التقريبي واسم حسابك فقط إن فعّلت الظهور
    LaunchedEffect(uid, loc, ig, visible) {
        val u = uid
        val l = loc
        if (u != null && l != null) {
            delay(1500)
            db.collection("users").document(u).set(
                mapOf(
                    "ig" to ig, "lat" to l.first, "lng" to l.second,
                    "ts" to System.currentTimeMillis(), "visible" to (visible && ig.isNotBlank())
                )
            )
        }
    }

    Scaffold(bottomBar = {
        NavigationBar {
            listOf("🏠" to "الرئيسية", "✉️" to "الرسائل", "👤" to "حسابي").forEachIndexed { i, (e, t) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(e) }, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> Column(Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = sub) {
                        Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("المتابَعون") })
                        Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("القريبون (10 كم)") })
                    }
                    if (sub == 0) Web("https://www.instagram.com/", Modifier.weight(1f).fillMaxWidth())
                    else NearbyTab(db, loc, uid, msg, Modifier.weight(1f).fillMaxWidth())
                }
                1 -> Web("https://www.instagram.com/direct/inbox/", Modifier.fillMaxSize())
                else -> ProfileTab(
                    ig, visible,
                    onIg = { v -> val c = cleanIg(v); ig = c; prefs.edit().putString("ig", c).apply() },
                    onVisible = { v -> visible = v; prefs.edit().putBoolean("vis", v).apply() },
                    onLogout = {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        Toast.makeText(ctx, "تم تسجيل الخروج من إنستغرام", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun NearbyTab(db: FirebaseFirestore, loc: Pair<Double, Double>?, uid: String?, msg: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var people by remember { mutableStateOf(listOf<Person>()) }

    DisposableEffect(loc) {
        val l = loc
        if (l == null) {
            onDispose { }
        } else {
            val d = 10 / 111.0
            val reg = db.collection("users")
                .whereGreaterThanOrEqualTo("lat", l.first - d)
                .whereLessThanOrEqualTo("lat", l.first + d)
                .addSnapshotListener { s, _ ->
                    if (s != null) people = s.documents.mapNotNull { doc ->
                        if (doc.getBoolean("visible") != true) return@mapNotNull null
                        Person(
                            doc.id, doc.getString("ig") ?: return@mapNotNull null,
                            doc.getDouble("lat") ?: return@mapNotNull null,
                            doc.getDouble("lng") ?: return@mapNotNull null,
                            doc.getLong("ts") ?: 0L
                        )
                    }
                }
            onDispose { reg.remove() }
        }
    }

    val l = loc
    val week = 7 * 86400000L
    val shown = if (l == null) emptyList() else
        people.filter { it.id != uid && System.currentTimeMillis() - it.ts < week }
            .map { it to distKm(l.first, l.second, it.lat, it.lng) }
            .filter { it.second <= 10 }.sortedBy { it.second }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (msg.isNotBlank()) Text(msg, color = MaterialTheme.colorScheme.error)
        if (shown.isEmpty()) {
            Text(if (l == null) "جارٍ تحديد موقعك..." else "لا أحد قريب منك بعد. فعّل ظهورك من «حسابي»، وادعُ أصدقاءك لتثبيت التطبيق.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown) { (p, d) ->
                Card(Modifier.fillMaxWidth().clickable {
                    val u = Uri.parse("https://www.instagram.com/" + cleanIg(p.ig) + "/")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, u))
                }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("@" + cleanIg(p.ig), style = MaterialTheme.typography.titleSmall)
                        Text((if (d < 1) "أقل من 1" else "${d.roundToInt()}") + " كم")
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileTab(
    ig: String, visible: Boolean,
    onIg: (String) -> Unit, onVisible: (Boolean) -> Unit, onLogout: () -> Unit
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("حسابي", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            ig, onIg, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("اسم حسابك في إنستغرام (بدون @)") }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = visible, onCheckedChange = onVisible)
            Spacer(Modifier.width(8.dp))
            Text("أظهر حسابي لمن حولي (10 كم)")
        }
        Text("عند التفعيل يرى الآخرون اسم حسابك وبعدك التقريبي فقط، وليس موقعك الدقيق.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onLogout) { Text("تسجيل الخروج من إنستغرام") }
    }
}
