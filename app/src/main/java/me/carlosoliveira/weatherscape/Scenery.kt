package me.carlosoliveira.weatherscape

/**
 * One wallpaper the app offers. Each has its own wallpaper service, so the system picker lists it
 * separately; all of them share the settings, the weather and the sky.
 */
enum class Scenery(
    val title: String,
    /** Fragment shader in assets/shaders, drawn after sky.glsl. */
    val shader: String,
    /** Wallpaper colour hints by day and by night: primary, secondary, tertiary. */
    val dayColors: IntArray,
    val nightColors: IntArray,
) {
    PINE_VALLEY(
        "Pine Valley", "landscape",
        dayColors = intArrayOf(0xff9fb6c6.toInt(), 0xff2f4a3c.toInt(), 0xff6f8a78.toInt()),
        nightColors = intArrayOf(0xff1c2a44.toInt(), 0xff13231c.toInt(), 0xff3a4a6a.toInt()),
    ),
    ROCK_JETTY(
        "Rock Jetty", "jetty",
        dayColors = intArrayOf(0xff9db8e0.toInt(), 0xff3f7fa6.toInt(), 0xff9a9890.toInt()),
        nightColors = intArrayOf(0xff1a2438.toInt(), 0xff14283a.toInt(), 0xff3c4250.toInt()),
    ),
    ;

    val service: Class<out SceneryWallpaperService>
        get() = when (this) {
            PINE_VALLEY -> PineValleyWallpaperService::class.java
            ROCK_JETTY -> RockJettyWallpaperService::class.java
        }

    /** The moments staged on this scenery's plate. */
    val moments: List<MomentKind> get() = MomentKind.entries.filter { it.scenery == this }

    companion object {
        fun ofService(className: String?): Scenery? = entries.firstOrNull { it.service.name == className }
    }
}
