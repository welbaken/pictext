package app.pictext.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = PicTextBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF001849),
    secondary = Color(0xFF565E71),
    background = Color(0xFFFAFAFC),
    surface = Color(0xFFFAFAFC),
)

private val DarkScheme = darkColorScheme(
    primary = PicTextBlueDark,
    onPrimary = Color(0xFF002B75),
    primaryContainer = Color(0xFF12459F),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFBEC6DC),
    background = Color(0xFF111214),
    surface = Color(0xFF111214),
)

@Composable
fun PicTextTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = PicTextTypography,
        content = content,
    )
}
