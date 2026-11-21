package me.carlosoliveira.weatherscape

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    private val wallpaperActive = mutableStateOf(false)
    private lateinit var refresher: WeatherRefresher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The wallpaper engine reloads the report through the shared preference change.
        refresher = WeatherRefresher(applicationContext) {}
        setContent {
            WeatherscapeTheme {
                SettingsScreen(
                    active = wallpaperActive.value,
                    onSetWallpaper = ::openWallpaperPicker,
                    onRefresh = { refresher.refresh() },
                    onPreview = ::preview,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val info = WallpaperManager.getInstance(this).wallpaperInfo
        wallpaperActive.value = info?.serviceName == PineValleyWallpaperService::class.java.name
        // Fetch from the foreground, where location access is allowed without background permission.
        refresher.refreshIfStale()
    }

    override fun onDestroy() {
        refresher.close()
        super.onDestroy()
    }

    private fun openWallpaperPicker() {
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, PineValleyWallpaperService::class.java),
        )
        try {
            startActivity(direct)
        } catch (_: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    /** Queues the moment and goes home, where the wallpaper plays it as soon as it is visible. */
    private fun preview(kind: MomentKind) {
        if (!wallpaperActive.value) {
            Toast.makeText(this, "Set Pine Valley as your wallpaper first.", Toast.LENGTH_SHORT).show()
            return
        }
        SettingsStore.requestPreview(this, kind)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun WeatherscapeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF9CC9AE), secondary = Color(0xFFA9C7D9))
        else -> lightColorScheme(primary = Color(0xFF2F5D49), secondary = Color(0xFF3F6273))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(active: Boolean, onSetWallpaper: () -> Unit, onRefresh: () -> Unit, onPreview: (MomentKind) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { SettingsStore.prefs(context) }
    var s by remember { mutableStateOf(SettingsStore.read(context)) }
    var status by remember { mutableStateOf(weatherStatus(prefs)) }
    fun update(next: Settings) { s = next; SettingsStore.write(context, next) }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            s = SettingsStore.read(context)
            status = weatherStatus(prefs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onRefresh()
        else {
            update(s.copy(useDeviceLocation = false))
            Toast.makeText(context, "Location off. Pick a city instead.", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        if (s.useDeviceLocation && !DeviceLocation.permitted(context)) permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Column(Modifier.widthIn(max = 640.dp).align(Alignment.CenterHorizontally)) {
                Text("Pine Valley", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "A live wallpaper that follows the weather and the sun where you are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                if (active) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "This is your wallpaper",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onSetWallpaper) { Text("Wallpaper picker") }
                    }
                } else {
                    Button(onClick = onSetWallpaper) { Text("Set as wallpaper") }
                }

                Spacer(Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(status.headline, style = MaterialTheme.typography.titleMedium)
                            Text("${s.placeName} · ${status.age}", style = MaterialTheme.typography.bodySmall)
                            status.error?.let {
                                Text("Last update failed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(onClick = onRefresh) { Text("Refresh") }
                    }
                }

                Section("Location")
                SwitchRow("Use phone location", "Updates when you open this screen or the weather refreshes.", s.useDeviceLocation) { on ->
                    if (on && !DeviceLocation.permitted(context)) permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    update(s.copy(useDeviceLocation = on))
                    if (on) onRefresh()
                }
                if (!s.useDeviceLocation) CitySearch { place ->
                    update(s.copy(placeName = place.name, latitude = place.latitude, longitude = place.longitude))
                }

                Section("Weather")
                Label("Show")
                Segmented(listOf(0 to "Now", 1 to "In 1 hour", 3 to "In 3 hours"), s.forecastHours) { update(s.copy(forecastHours = it)) }
                Label("Override for testing")
                Chips(
                    listOf(
                        WeatherChoice.AUTO to "Real weather", WeatherChoice.SUNNY to "Sunny", WeatherChoice.WINDY to "Windy",
                        WeatherChoice.RAINY to "Rainy", WeatherChoice.STORM to "Storm", WeatherChoice.FOGGY to "Foggy",
                    ),
                    s.weather,
                ) { update(s.copy(weather = it)) }
                Label("Time of day")
                Chips(
                    listOf(
                        TimeChoice.AUTO to "Follow the sun", TimeChoice.MORNING to "Morning", TimeChoice.AFTERNOON to "Afternoon",
                        TimeChoice.SUNSET to "Sunset", TimeChoice.NIGHT to "Night",
                    ),
                    s.time,
                ) { update(s.copy(time = it)) }
                Label("Mode")
                Segmented(listOf(Mode.LIVE to "Live", Mode.TIMELAPSE to "Storm timelapse"), s.mode) { update(s.copy(mode = it)) }
                Hint(
                    if (s.mode == Mode.LIVE) "Weather changes blend in over a minute or two."
                    else "Loops a 48-second afternoon storm into a starry night. Ignores the real weather.",
                )

                Section("Wildlife and sky moments")
                SwitchRow("Show moments", "Birds, a squirrel, fireflies, shooting stars, sunbeams and rainbows, when the conditions fit.", s.moments) {
                    update(s.copy(moments = it))
                }
                if (s.moments) {
                    Label("How often")
                    Segmented(
                        listOf(Frequency.RARE to "Rarely", Frequency.OCCASIONAL to "Sometimes", Frequency.OFTEN to "Often"),
                        s.frequency,
                    ) { update(s.copy(frequency = it)) }
                }
                Label("Preview one now")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MomentKind.entries.forEach { kind -> OutlinedButton(onClick = { onPreview(kind) }) { Text(kind.label) } }
                }
                Hint("Opens your home screen and plays it there.")

                Section("Display")
                Label("Frame rate")
                Segmented(listOf(15 to "15", 24 to "24", 30 to "30", 60 to "60 fps"), s.frameRate) { update(s.copy(frameRate = it)) }
                Label("Detail")
                Segmented(
                    listOf(Quality.BATTERY to "Battery saver", Quality.BALANCED to "Balanced", Quality.SHARP to "Sharp"),
                    s.quality,
                ) { update(s.copy(quality = it)) }
                Label("Cloud and fog speed: ${String.format(java.util.Locale.US, "%.2f", s.cloudSpeed).trimEnd('0').trimEnd('.')}×")
                Slider(
                    value = s.cloudSpeed,
                    onValueChange = { s = s.copy(cloudSpeed = (it * 4).roundToInt() / 4f) },
                    onValueChangeFinished = { update(s) },
                    valueRange = .25f..3f,
                    steps = 10,
                )
                Hint("1× matches the preview. Slower feels calm, faster looks more like a timelapse.")
                Label("Darken for readability: ${s.dimPercent}%")
                Slider(
                    value = s.dimPercent.toFloat(),
                    onValueChange = { s = s.copy(dimPercent = it.roundToInt()) },
                    onValueChangeFinished = { update(s) },
                    valueRange = 0f..40f,
                    steps = 7,
                )
                SwitchRow("Move with home screen pages", "Pans the view slightly as you swipe between pages.", s.parallax) {
                    update(s.copy(parallax = it))
                }
                SwitchRow("Reduce motion", "Keeps the scene still. It still follows the weather and the time of day.", s.reducedMotion) {
                    update(s.copy(reducedMotion = it))
                }
                SwitchRow("Pause in Battery Saver", "Redraws twice a minute while Battery Saver is on.", s.pauseOnBatterySaver) {
                    update(s.copy(pauseOnBatterySaver = it))
                }
                Spacer(Modifier.height(24.dp))
                Hint("Weather data by Open-Meteo.com (CC BY 4.0).")
            }
        }
    }
}

private class WeatherStatus(val headline: String, val age: String, val error: String?)

private fun weatherStatus(prefs: SharedPreferences): WeatherStatus {
    val json = prefs.getString(SettingsStore.WEATHER_JSON, null)
    val fetched = prefs.getLong(SettingsStore.WEATHER_FETCHED_AT, 0)
    val report = json?.let { runCatching { WeatherReport.parse(it, fetched) }.getOrNull() }
    val age = if (fetched == 0L) "not updated yet"
    else "updated " + DateUtils.getRelativeTimeSpanString(fetched, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    return WeatherStatus(report?.current?.describe() ?: "Waiting for weather", age, prefs.getString(SettingsStore.WEATHER_ERROR, null))
}

@Composable
private fun CitySearch(onPick: (Place) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun search() {
        if (query.isBlank()) return
        message = "Searching…"
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { OpenMeteo.search(query) } }
            results = found.getOrDefault(emptyList())
            message = when {
                found.isFailure -> "Search failed. Check your connection."
                results.isEmpty() -> "No places found."
                else -> null
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("City") }, singleLine = true)
        TextButton(onClick = ::search) { Text("Search") }
    }
    message?.let { Hint(it) }
    results.forEach { place ->
        Column(
            Modifier.fillMaxWidth().clickable { onPick(place); results = emptyList(); query = "" }.padding(vertical = 10.dp),
        ) {
            Text(place.name, style = MaterialTheme.typography.bodyLarge)
            Text(place.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(28.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 4.dp))
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}
