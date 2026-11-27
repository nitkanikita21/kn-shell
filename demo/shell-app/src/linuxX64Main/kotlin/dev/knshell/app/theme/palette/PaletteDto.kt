package dev.knshell.app.theme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PaletteJson(
    val colors: List<ColorJson>,
    // palette-core reports NAN when the wallpaper has no chromatic clusters at all;
    // serde renders a NAN f32 as `null`, so this must stay nullable.
    @SerialName("suggested_hue") val suggestedHue: Float? = null,
    @SerialName("suggested_chroma") val suggestedChroma: Float,
    @SerialName("suggested_mode") val suggestedMode: String,
    @SerialName("is_fallback") val isFallback: Boolean,
    @SerialName("source_hash") val sourceHash: String,
)

@Serializable
data class ColorJson(
    val l: Float,
    val c: Float,
    val h: Float? = null,
    val weight: Float,
)
