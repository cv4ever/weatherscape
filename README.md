# Weatherscape

Pine Valley, an Android live wallpaper that follows the real weather and the sun where you are.
The valley is one photographic plate brought to life by OpenGL ES 2 shaders: drifting fog and
cloud, wind in the trees, rain, snow, lightning, the real moon, stars and the Milky Way, plus rare
moments (birds, deer, an owl, fog rolling in, aurora and more) when the conditions fit. Raindrops
and condensation gather on the camera glass. A home-screen widget shows the conditions and the
next change in the forecast.

Weather comes from [Open-Meteo](https://open-meteo.com) (CC BY 4.0), with no account or key.

## Build and install

Needs JDK 17 and the Android SDK (path in `local.properties`, which is not committed).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open Weatherscape and tap **Set as wallpaper**.

## Layout

| Path | What |
|---|---|
| `SceneDirector.kt` | Turns weather, time, sun, moon and season into one `Frame` of scene values; schedules moments |
| `SceneRenderer.kt` | GL thread: draws the landscape pass, overlays, then the glass (mist and drops) |
| `assets/shaders/landscape.frag` | The valley: light, fog, cloud, rain haze, moon, aurora, snow cover |
| `Overlays.kt` | Rain, snow and moment geometry (sprites from the atlases in `assets/textures`) |
| `LensDrops.kt`, `glass.frag`, `mist.frag` | Raindrops, splashes and condensation on the camera glass |
| `Weather.kt`, `WeatherRefresher.kt` | Open-Meteo fetch, cache and mapping to the scene's atmosphere |
| `PineValleyWallpaperService.kt` | Wallpaper engine: frame pacing, unlock and tap events, previews |
| `SettingsActivity.kt`, `Settings.kt` | Settings screen and the preferences shared with the engine |
| `ConditionsWidget.kt` | Home-screen widget |

## Power

The engine draws at the chosen frame rate only while something moves fast (rain, snow, strong wind,
lightning, a moment, water on the glass). While the valley only drifts it drops to 15 fps
("Save power when calm"). Reduced motion and Battery Saver redraw twice a minute, and nothing
renders while the wallpaper is hidden.

## Notes

- Adreno GPUs break arithmetic shader hashes at large coordinates, so value noise reads a
  256×256 random texture instead.
- The HTML prototypes the effects were designed in are not part of this repository.
