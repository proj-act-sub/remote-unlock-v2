package com.remoteunlock.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Fallback palette (used on Android < 12 which lacks dynamic color)
private val FallbackDark = darkColorScheme(
    primary      = Purple80,
    secondary    = PurpleGrey80,
    tertiary     = Pink80
)
private val FallbackLight = lightColorScheme(
    primary      = Purple40,
    secondary    = PurpleGrey40,
    tertiary     = Pink40
)

/**
 * RemoteUnlock theme with **Material You** dynamic colour.
 *
 * On Android 12+ (API 31+) the system wallpaper colour is extracted and
 * propagated into the colour scheme automatically via [dynamicDarkColorScheme] /
 * [dynamicLightColorScheme].  On older versions the fallback palette is used.
 */
@Composable
fun RemoteUnlockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else           dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDark
        else      -> FallbackLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = AppTypography,
        content     = content
    )
}
