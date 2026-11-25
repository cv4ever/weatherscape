package me.carlosoliveira.weatherscape

import android.Manifest
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    private val wallpaperActive = mutableStateOf(false)
    private val refreshing = mutableStateOf(false)
    /** Bumped on every resume so the screen re-checks what may have changed elsewhere (widget, permission). */
    private val resumes = mutableIntStateOf(0)
    private lateinit var refresher: WeatherRefresher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The wallpaper engine reloads the report through the shared preference change.
        refresher = WeatherRefresher(applicationContext, onBusy = { refreshing.value = it }) {}
        setContent {
            WeatherscapeTheme {
                SettingsScreen(
                    active = wallpaperActive.value,
                    resumes = resumes.intValue,
                    refreshing = refreshing.value,
                    onSetWallpaper = ::openWallpaperPicker,
                    onRefresh = { refresher.refresh() },
                    onPreview = ::preview,
                    onLensPreview = ::previewLens,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val info = WallpaperManager.getInstance(this).wallpaperInfo
        wallpaperActive.value = info?.serviceName == PineValleyWallpaperService::class.java.name
        resumes.intValue++
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
        if (!goHomeToPreview()) return
        SettingsStore.requestPreview(this, kind)
    }

    /** Shows a glass effect on the home screen so the lens settings can be judged there. */
    private fun previewLens(kind: LensPreview) {
        if (!goHomeToPreview()) return
        SettingsStore.requestLensPreview(this, kind)
    }

    private fun goHomeToPreview(): Boolean {
        if (!wallpaperActive.value) {
            Toast.makeText(this, "Set Pine Valley as your wallpaper first.", Toast.LENGTH_SHORT).show()
            return false
        }
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
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

private val DEFAULTS = Settings()

/** How many of the given (current, default) pairs differ. */
private fun changed(vararg pairs: Pair<Any, Any>) = pairs.count { (now, default) -> now != default }

@Composable
private fun SettingsScreen(
    active: Boolean,
    resumes: Int,
    refreshing: Boolean,
    onSetWallpaper: () -> Unit,
    onRefresh: () -> Unit,
    onPreview: (MomentKind) -> Unit,
    onLensPreview: (LensPreview) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { SettingsStore.prefs(context) }
    var s by remember { mutableStateOf(SettingsStore.read(context)) }
    var weather by remember { mutableStateOf(weatherStatus(prefs)) }
    var locationAllowed by remember { mutableStateOf(DeviceLocation.permitted(context)) }
    fun update(next: Settings) { s = next; SettingsStore.write(context, next) }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            s = SettingsStore.read(context)
            weather = weatherStatus(prefs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationAllowed = granted
        if (granted) onRefresh()
        else {
            update(s.copy(useDeviceLocation = false))
            Toast.makeText(context, "Location off. Pick a city instead.", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        if (s.useDeviceLocation && !DeviceLocation.permitted(context)) permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val widgets = remember { AppWidgetManager.getInstance(context) }
    val widgetProvider = remember { ComponentName(context, ConditionsWidget::class.java) }
    val hasWidget = remember(resumes) { widgets.getAppWidgetIds(widgetProvider).isNotEmpty() }
    LaunchedEffect(resumes) { locationAllowed = DeviceLocation.permitted(context) }
    val addWidget = { widgets.requestPinAppWidget(widgetProvider, null, null); Unit }

    fun useDeviceLocation(on: Boolean) {
        if (on && !DeviceLocation.permitted(context)) permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        update(s.copy(useDeviceLocation = on))
        if (on) onRefresh()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    Text("Pine Valley", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                    Text(
                        "A live wallpaper that follows the weather and the sun where you are.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val weatherReady = weather.report != null
                if (!active || !weatherReady) {
                    SetupCard(active, weather, s.placeName, hasWidget, widgets.isRequestPinAppWidgetSupported, onSetWallpaper, addWidget)
                } else {
                    WallpaperRow(hasWidget, widgets.isRequestPinAppWidgetSupported, onSetWallpaper, addWidget)
                }

                if (s.mode == Mode.TIMELAPSE || s.weather != WeatherChoice.AUTO || s.time != TimeChoice.AUTO) {
                    OverrideBanner("${overrideSummary(s, context)} Real now: ${weather.headline}.") {
                        update(s.copy(mode = DEFAULTS.mode, weather = DEFAULTS.weather, time = DEFAULTS.time))
                    }
                }

                NowCard(s, weather, refreshing, onRefresh) { update(s.copy(forecastHours = it)) }

                SettingsGroup(
                    "Location",
                    if (s.useDeviceLocation) "Phone location · ${s.placeName}" else "${s.placeName}, chosen by you",
                ) {
                    Segmented(listOf(true to "Phone location", false to "Choose a city"), s.useDeviceLocation, ::useDeviceLocation)
                    if (s.useDeviceLocation && !locationAllowed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Hint("Location permission is off, so the valley shows ${s.placeName}.", Modifier.weight(1f))
                            TextButton(onClick = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("Allow") }
                        }
                    } else if (s.useDeviceLocation) {
                        Hint("Now ${s.placeName} (${coordinates(s.latitude, s.longitude)}). Follows you when you open this app or the weather refreshes.")
                    } else {
                        Hint("Now ${s.placeName} (${coordinates(s.latitude, s.longitude)}).")
                        CitySearch { place -> update(s.copy(placeName = place.name, latitude = place.latitude, longitude = place.longitude)) }
                    }
                }

                LookGroup(s, ::update)
                GlassGroup(s, active, ::update, onLensPreview)
                MomentsGroup(s, active, ::update, onPreview)
                PowerGroup(s, ::update)
                OverrideGroup(s, ::update)

                Hint("Weather data by Open-Meteo.com (CC BY 4.0).", Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            }
        }
    }
}

// ------------------------------------------------------------------- setup and status

@Composable
private fun SetupCard(
    active: Boolean,
    weather: WeatherStatus,
    placeName: String,
    hasWidget: Boolean,
    canPinWidget: Boolean,
    onSetWallpaper: () -> Unit,
    onAddWidget: () -> Unit,
) {
    val steps = listOf(active, weather.report != null, hasWidget)
    val done = steps.count { it }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Get set up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                Text("$done of ${steps.size} done", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(
                progress = { done / steps.size.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
            )
            SetupStep(1, "Set Pine Valley as your wallpaper", "Opens a preview: tap Set wallpaper, then pick your screens.", active) {
                Button(onClick = onSetWallpaper) { Text("Set as wallpaper") }
            }
            SetupStep(
                2,
                "Load the weather",
                when {
                    weather.report != null -> "$placeName · ${weather.headline}"
                    weather.error != null -> "Failed: ${weather.error}. Check your connection, or choose a city under Location."
                    else -> "Loading for $placeName. Takes a few seconds with a connection."
                },
                weather.report != null,
            )
            SetupStep(3, "Add the weather widget (optional)", "Shows the conditions and when they change next.", hasWidget) {
                if (canPinWidget) OutlinedButton(onClick = onAddWidget) { Text("Add widget") }
                else Hint("Long-press your home screen, then Widgets.")
            }
        }
    }
}

@Composable
private fun SetupStep(number: Int, title: String, detail: String, done: Boolean, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val tint = MaterialTheme.colorScheme.primary
        if (done) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = tint, modifier = Modifier.size(24.dp))
        } else {
            Box(
                Modifier.size(24.dp).border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("$number", style = MaterialTheme.typography.labelMedium) }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!done) Box(Modifier.padding(top = 6.dp)) { action() }
        }
    }
}

@Composable
private fun WallpaperRow(hasWidget: Boolean, canPinWidget: Boolean, onSetWallpaper: () -> Unit, onAddWidget: () -> Unit) {
    Column {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                "Pine Valley is your wallpaper",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onSetWallpaper) { Text("Change") }
        }
        if (!hasWidget && canPinWidget) {
            TextButton(onClick = onAddWidget) { Text("Add the weather widget to your home screen") }
        }
    }
}

@Composable
private fun OverrideBanner(summary: String, onReal: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Row {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                    Text("Not your real weather", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            TextButton(
                onClick = onReal,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
            ) { Text("Use real weather", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun NowCard(s: Settings, weather: WeatherStatus, refreshing: Boolean, onRefresh: () -> Unit, onForecast: (Int) -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(weather.headline, style = MaterialTheme.typography.titleLarge)
                    Text("${s.placeName} · ${weather.age}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Updating")
                    } else {
                        Text("Refresh")
                    }
                }
            }
            if (!refreshing) weather.error?.let {
                Text("Last update failed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Label("The valley shows")
            Segmented(listOf(0 to "Now", 1 to "In 1 hour", 3 to "In 3 hours"), s.forecastHours, onForecast)
            val report = weather.report
            Text(
                when {
                    s.forecastHours == 0 -> "The weather right now, blending in over a minute or two as it changes."
                    report == null -> "The forecast appears once the weather loads."
                    else -> report.sample(System.currentTimeMillis() / 1000, s.forecastHours).let {
                        "Forecast for ${clock(context, it.epochSeconds * 1000)}: ${it.describe()}"
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// ------------------------------------------------------------------- groups

@Composable
private fun LookGroup(s: Settings, update: (Settings) -> Unit) {
    val d = DEFAULTS
    val count = changed(s.seasons to d.seasons, s.cloudSpeed to d.cloudSpeed, s.dimPercent to d.dimPercent, s.parallax to d.parallax)
    val summary = listOfNotNull(
        if (s.seasons) "Seasons" else "No seasons",
        "clouds ${times(s.cloudSpeed)}",
        if (s.dimPercent > 0) "${s.dimPercent}% darker" else null,
    ).joinToString(" · ")
    SettingsGroup("Look", summary, count, {
        update(s.copy(seasons = d.seasons, cloudSpeed = d.cloudSpeed, dimPercent = d.dimPercent, parallax = d.parallax))
    }) {
        SwitchRow("Seasons", "Colder light in winter, fresher greens in spring, warmer late summers.", s.seasons) { update(s.copy(seasons = it)) }
        var cloud by remember(s.cloudSpeed) { mutableStateOf(s.cloudSpeed) }
        ValueSlider(
            "Cloud and fog speed", times(cloud), cloud, .25f..3f, 10,
            onChange = { cloud = (it * 4).roundToInt() / 4f },
            onDone = { update(s.copy(cloudSpeed = cloud)) },
            hint = when {
                cloud == 1f -> "The natural pace."
                cloud < 1f -> "Calmer than the natural pace."
                else -> "Faster than the natural pace, closer to a timelapse."
            },
        )
        var dim by remember(s.dimPercent) { mutableStateOf(s.dimPercent) }
        ValueSlider(
            "Darken for readable icons", if (dim == 0) "Off" else "$dim%", dim.toFloat(), 0f..40f, 7,
            onChange = { dim = it.roundToInt() },
            onDone = { update(s.copy(dimPercent = dim)) },
            hint = "Helps white icon labels stand out on bright skies.",
        )
        SwitchRow("Move with home screen pages", "Pans the view slightly as you swipe between pages.", s.parallax) { update(s.copy(parallax = it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlassGroup(s: Settings, active: Boolean, update: (Settings) -> Unit, onLensPreview: (LensPreview) -> Unit) {
    val d = DEFAULTS
    val count = changed(
        s.lensDrops to d.lensDrops, s.lensAmount to d.lensAmount, s.lensSize to d.lensSize, s.lensSpeed to d.lensSpeed,
        s.lensDrying to d.lensDrying, s.lensSplashes to d.lensSplashes, s.lensMist to d.lensMist,
    )
    val drySeconds = LensDrops.secondsToDry(s.lensDrying.scale)
    val summary = listOfNotNull(
        if (s.lensDrops) "${times(s.lensAmount)} raindrops, dry in about $drySeconds s" else "No raindrops",
        if (s.lensMist) "mist" else null,
    ).joinToString(" · ")
    SettingsGroup("Rain on the glass", summary, count, {
        update(
            s.copy(
                lensDrops = d.lensDrops, lensAmount = d.lensAmount, lensSize = d.lensSize, lensSpeed = d.lensSpeed,
                lensDrying = d.lensDrying, lensSplashes = d.lensSplashes, lensMist = d.lensMist,
            ),
        )
    }) {
        SwitchRow("Raindrops", "Drops gather on the glass while it rains, slide down, and dry afterwards.", s.lensDrops) {
            update(s.copy(lensDrops = it))
        }
        if (s.lensDrops) {
            var amount by remember(s.lensAmount) { mutableStateOf(s.lensAmount) }
            ValueSlider(
                "Amount", times(amount), amount, .25f..2.5f, 8,
                onChange = { amount = (it * 4).roundToInt() / 4f },
                onDone = { update(s.copy(lensAmount = amount)) },
                hint = "Scales how much rain reaches the glass. A downpour still brings more than a drizzle.",
            )
            Label("Drop size")
            Segmented(listOf(DropSize.SMALL to "Small", DropSize.MEDIUM to "Medium", DropSize.LARGE to "Large"), s.lensSize) {
                update(s.copy(lensSize = it))
            }
            Label("Sliding")
            Segmented(listOf(SlideSpeed.SLOW to "Slow", SlideSpeed.NORMAL to "Normal", SlideSpeed.FAST to "Fast"), s.lensSpeed) {
                update(s.copy(lensSpeed = it))
            }
            Label("After the rain")
            Segmented(listOf(Drying.QUICK to "Quick", Drying.NORMAL to "Normal", Drying.LINGER to "Linger"), s.lensDrying) {
                update(s.copy(lensDrying = it))
            }
            Hint("The biggest drops are gone about $drySeconds s after the rain stops.")
            SwitchRow("Splashes in heavy rain", "Drops hit the glass hard and spread out before they settle.", s.lensSplashes) {
                update(s.copy(lensSplashes = it))
            }
        }
        SwitchRow("Mist", "On foggy mornings the edges of the glass fog up, then clear as the fog lifts.", s.lensMist) {
            update(s.copy(lensMist = it))
        }
        if (s.lensDrops || s.lensMist) {
            Label("Try it on your home screen")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.lensDrops) OutlinedButton(onClick = { onLensPreview(LensPreview.SHOWER) }, enabled = active) { Text("25-second shower") }
                if (s.lensMist) OutlinedButton(onClick = { onLensPreview(LensPreview.MIST) }, enabled = active) { Text("Mist") }
            }
            PreviewHint(active, "The valley keeps its real weather.")
        }
    }
}

private val MOMENT_GROUPS = listOf(
    "Daytime" to listOf(
        MomentKind.BIRD, MomentKind.FLOCK, MomentKind.SQUIRREL, MomentKind.SUNBEAM,
        MomentKind.RAINBOW, MomentKind.BUZZARD, MomentKind.GEESE,
    ),
    "Dawn and dusk" to listOf(MomentKind.FOG_ROLL, MomentKind.DEER, MomentKind.FIREFLIES, MomentKind.BATS),
    "Night" to listOf(MomentKind.COMET, MomentKind.MOONRISE, MomentKind.OWL, MomentKind.AURORA, MomentKind.CAR),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MomentsGroup(s: Settings, active: Boolean, update: (Settings) -> Unit, onPreview: (MomentKind) -> Unit) {
    val d = DEFAULTS
    val count = changed(s.moments to d.moments, s.frequency to d.frequency, s.unlockReveal to d.unlockReveal, s.tapToWake to d.tapToWake)
    val summary = when {
        s.reducedMotion -> "Paused by Reduce motion"
        !s.moments -> "Off"
        else -> "${frequencyLabel(s.frequency)} · ${percent(s.frequency.visibleChance)} of visits home"
    }
    SettingsGroup("Wildlife and sky", summary, count, {
        update(s.copy(moments = d.moments, frequency = d.frequency, unlockReveal = d.unlockReveal, tapToWake = d.tapToWake))
    }) {
        if (s.reducedMotion) Hint("Reduce motion is on (Battery and smoothness), so moments and touch effects are paused.")
        SwitchRow("Moments", "Wildlife, fog, the moon, aurora and more, only when the weather and light fit.", s.moments) {
            update(s.copy(moments = it))
        }
        if (s.moments) {
            Label("How often")
            Segmented(Frequency.entries.map { it to frequencyLabel(it) }, s.frequency) { update(s.copy(frequency = it)) }
            Hint("Plays on ${percent(s.frequency.visibleChance)} of your visits to the home screen, at most one every ${duration(s.frequency.cooldownSeconds)}.")
        }
        Label("When you touch the phone")
        SwitchRow("Wake on unlock", "The mist thins as you unlock, and sometimes a bird leaves a treetop.", s.unlockReveal) {
            update(s.copy(unlockReveal = it))
        }
        SwitchRow("Tap to wake the valley", "Tapping empty home-screen space sends birds up from the nearest trees.", s.tapToWake) {
            update(s.copy(tapToWake = it))
        }
        Label("Play one now (${MomentKind.entries.size} moments)")
        MOMENT_GROUPS.forEach { (title, kinds) ->
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { kind -> SuggestionChip(onClick = { onPreview(kind) }, label = { Text(kind.label) }, enabled = active) }
            }
        }
        PreviewHint(active, "Opens your home screen and plays it there.")
    }
}

@Composable
private fun PowerGroup(s: Settings, update: (Settings) -> Unit) {
    val d = DEFAULTS
    val context = LocalContext.current
    val screen = remember { context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds }
    val count = changed(
        s.frameRate to d.frameRate, s.quality to d.quality, s.calmSaver to d.calmSaver,
        s.pauseOnBatterySaver to d.pauseOnBatterySaver, s.reducedMotion to d.reducedMotion,
    )
    val summary = if (s.reducedMotion) "Reduce motion: 2 frames a minute" else listOfNotNull(
        "${s.frameRate} fps",
        if (s.calmSaver && s.frameRate > CALM_FPS) "$CALM_FPS when calm" else null,
        "${qualityLabel(s.quality)} detail",
    ).joinToString(" · ")
    SettingsGroup("Battery and smoothness", summary, count, {
        update(
            s.copy(
                frameRate = d.frameRate, quality = d.quality, calmSaver = d.calmSaver,
                pauseOnBatterySaver = d.pauseOnBatterySaver, reducedMotion = d.reducedMotion,
            ),
        )
    }) {
        Label("Frames per second")
        Segmented(listOf(15 to "15", 24 to "24", 30 to "30", 60 to "60"), s.frameRate) { update(s.copy(frameRate = it)) }
        Hint(frameRateHint(s))
        Label("Detail")
        Segmented(Quality.entries.map { it to qualityLabel(it) }, s.quality) { update(s.copy(quality = it)) }
        val w = (screen.width() * s.quality.renderScale).roundToInt()
        val h = (screen.height() * s.quality.renderScale).roundToInt()
        Hint("Draws $w × $h pixels, ${percent(s.quality.renderScale)} of your ${screen.width()} × ${screen.height()} screen. Fewer pixels use less battery.")
        SwitchRow(
            "Save power when calm",
            if (s.frameRate > CALM_FPS) "Drops from ${s.frameRate} to $CALM_FPS fps while only fog and clouds drift. Rain, wind and moments stay smooth."
            else "No effect at $CALM_FPS fps: the valley already draws at the calm rate.",
            s.calmSaver,
        ) { update(s.copy(calmSaver = it)) }
        SwitchRow("Pause in Battery Saver", "Redraws twice a minute while the phone's Battery Saver is on.", s.pauseOnBatterySaver) {
            update(s.copy(pauseOnBatterySaver = it))
        }
        SwitchRow("Reduce motion", "Keeps the scene still: redraws twice a minute, still following the weather and the time of day.", s.reducedMotion) {
            update(s.copy(reducedMotion = it))
        }
    }
}

@Composable
private fun OverrideGroup(s: Settings, update: (Settings) -> Unit) {
    val d = DEFAULTS
    val context = LocalContext.current
    val count = changed(s.mode to d.mode, s.weather to d.weather, s.time to d.time)
    SettingsGroup(
        "Try other weather",
        if (count == 0) "Off · real weather, following the sun" else overrideSummary(s, context),
        count,
        { update(s.copy(mode = d.mode, weather = d.weather, time = d.time)) },
    ) {
        Segmented(listOf(Mode.LIVE to "Live", Mode.TIMELAPSE to "Storm timelapse"), s.mode) { update(s.copy(mode = it)) }
        if (s.mode == Mode.TIMELAPSE) {
            Hint("Loops a ${SceneDirector.TIMELAPSE_SECONDS.roundToInt()}-second afternoon storm into a starry night, ignoring the weather and time below.")
        } else {
            Label("Weather")
            Chips(WeatherChoice.entries.map { it to weatherLabel(it) }, s.weather) { update(s.copy(weather = it)) }
            Label("Time of day")
            val times = remember(s.latitude, s.longitude) { choiceTimes(context, s) }
            Chips(TimeChoice.entries.map { it to "${timeLabel(it)} · ${times.getValue(it)}" }, s.time) { update(s.copy(time = it)) }
            Hint("Times are today's at ${s.placeName}, from its sunrise and sunset.")
        }
    }
}

// ------------------------------------------------------------------- derived text

private const val CALM_FPS = PineValleyWallpaperService.CALM_FPS

private fun frameRateHint(s: Settings): String = when {
    s.reducedMotion -> "Reduce motion is on: the valley redraws twice a minute whatever you pick here."
    else -> buildString {
        if (s.calmSaver && s.frameRate > CALM_FPS) append("${s.frameRate} fps in rain, wind or a moment, $CALM_FPS fps while only clouds drift.")
        else append("${s.frameRate} fps whenever the valley is on screen.")
        if (s.pauseOnBatterySaver) append(" Twice a minute in Battery Saver.")
    }
}

private fun overrideSummary(s: Settings, context: android.content.Context): String = when {
    s.mode == Mode.TIMELAPSE -> "Playing the ${SceneDirector.TIMELAPSE_SECONDS.roundToInt()}-second storm timelapse."
    else -> {
        val weather = if (s.weather == WeatherChoice.AUTO) "Real weather" else weatherLabel(s.weather)
        val time = if (s.time == TimeChoice.AUTO) "following the sun"
        else "at ${timeLabel(s.time).lowercase()} (${choiceTimes(context, s).getValue(s.time)})"
        "$weather, $time."
    }
}

/** The clock time each time-of-day choice shows today at the chosen place. */
private fun choiceTimes(context: android.content.Context, s: Settings): Map<TimeChoice, String> {
    val now = Instant.now()
    val (day, minute) = SolarDay.at(s.latitude, s.longitude, now)
    val midnight = now.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    return TimeChoice.entries.associateWith { clock(context, midnight + (it.minuteOn(day, minute) * 60_000).toLong()) }
}

private fun clock(context: android.content.Context, epochMillis: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMillis))

private fun weatherLabel(w: WeatherChoice) = when (w) {
    WeatherChoice.AUTO -> "Real weather"
    WeatherChoice.SUNNY -> "Sunny"
    WeatherChoice.WINDY -> "Windy"
    WeatherChoice.RAINY -> "Rainy"
    WeatherChoice.STORM -> "Storm"
    WeatherChoice.FOGGY -> "Foggy"
    WeatherChoice.SNOWY -> "Snowy"
}

private fun timeLabel(t: TimeChoice) = when (t) {
    TimeChoice.AUTO -> "Follow the sun"
    TimeChoice.MORNING -> "Morning"
    TimeChoice.AFTERNOON -> "Afternoon"
    TimeChoice.SUNSET -> "Sunset"
    TimeChoice.NIGHT -> "Night"
}

private fun frequencyLabel(f: Frequency) = when (f) {
    Frequency.RARE -> "Rarely"
    Frequency.OCCASIONAL -> "Sometimes"
    Frequency.OFTEN -> "Often"
}

private fun qualityLabel(q: Quality) = when (q) {
    Quality.BATTERY -> "Light"
    Quality.BALANCED -> "Balanced"
    Quality.SHARP -> "Sharp"
}

private fun times(v: Float) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.') + "×"

private fun percent(v: Float) = "${(v * 100).roundToInt()}%"

private fun duration(seconds: Int) = if (seconds < 120) "$seconds s" else "${seconds / 60} min"

private fun coordinates(latitude: Double, longitude: Double) = String.format(
    Locale.US, "%.2f° %s, %.2f° %s", abs(latitude), if (latitude >= 0) "N" else "S", abs(longitude), if (longitude >= 0) "E" else "W",
)

private class WeatherStatus(val report: WeatherReport?, val headline: String, val age: String, val error: String?)

private fun weatherStatus(prefs: SharedPreferences): WeatherStatus {
    val json = prefs.getString(SettingsStore.WEATHER_JSON, null)
    val fetched = prefs.getLong(SettingsStore.WEATHER_FETCHED_AT, 0)
    val report = json?.let { runCatching { WeatherReport.parse(it, fetched) }.getOrNull() }
    val age = if (fetched == 0L) "not updated yet"
    else if (System.currentTimeMillis() - fetched in 0 until DateUtils.MINUTE_IN_MILLIS) "updated just now"
    else "updated " + DateUtils.getRelativeTimeSpanString(fetched, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    return WeatherStatus(report, report?.current?.describe() ?: "Waiting for weather", age, prefs.getString(SettingsStore.WEATHER_ERROR, null))
}

// ------------------------------------------------------------------- building blocks

@Composable
private fun CitySearch(onPick: (Place) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun search(text: String) {
        message = "Searching…"
        val found = withContext(Dispatchers.IO) { runCatching { OpenMeteo.search(text) } }
        results = found.getOrDefault(emptyList())
        message = when {
            found.isFailure -> "Search failed. Check your connection."
            results.isEmpty() -> "No places match “$text”."
            else -> null
        }
    }
    // Search as you type, once typing pauses.
    LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); message = null; return@LaunchedEffect }
        delay(450)
        search(query.trim())
    }
    OutlinedTextField(
        query, { query = it },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("Change city") },
        placeholder = { Text("Start typing, e.g. Porto") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) scope.launch { search(query.trim()) } }),
        trailingIcon = {
            IconButton(onClick = { if (query.isNotBlank()) scope.launch { search(query.trim()) } }) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        },
    )
    message?.let { Hint(it) }
    results.forEach { place ->
        Column(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Use this place") { onPick(place); results = emptyList(); query = "" }
                .padding(vertical = 10.dp),
        ) {
            Text(place.name, style = MaterialTheme.typography.bodyLarge)
            Text(place.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A titled card that folds its settings behind a one-line summary of their current values. */
@Composable
private fun SettingsGroup(
    title: String,
    summary: String,
    changed: Int = 0,
    onReset: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (open) "Collapse" else "Expand") { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (changed > 0) {
                Text(
                    "$changed changed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                content()
                if (changed > 0) {
                    TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) {
                        Text(if (changed == 1) "Reset 1 setting to default" else "Reset $changed settings to defaults")
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    hint: String,
) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
    Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onDone,
        valueRange = range,
        steps = steps,
        modifier = Modifier.semantics { stateDescription = valueText },
    )
    Hint(hint, Modifier.padding(top = 0.dp))
}

@Composable
private fun PreviewHint(active: Boolean, text: String) {
    Hint(if (active) text else "Set Pine Valley as your wallpaper to try these.")
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier.padding(top = 6.dp)) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
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
