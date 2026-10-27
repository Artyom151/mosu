package com.mosu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val MosuPink = Color(0xFFFF4F6D)

private val LightFallback = lightColorScheme(
    primary = MosuPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DE),
    onPrimaryContainer = Color(0xFF40000F),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFFFB2BD),
    onPrimary = Color(0xFF670020),
    primaryContainer = Color(0xFF920033),
    onPrimaryContainer = Color(0xFFFFD9DE),
)

/**
 * Material 3 Expressive theme for mosu. Uses Material You dynamic colors on Android 12+ and a pink fallback palette
 * below that.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MosuTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkFallback
        else -> LightFallback
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
