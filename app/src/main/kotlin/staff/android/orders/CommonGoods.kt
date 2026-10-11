package staff.android.orders

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BodyFont = FontFamily(Font(R.font.cg_google_sans_regular), Font(R.font.cg_google_sans_bold, FontWeight.Bold))
private val DisplayFont = FontFamily(Font(R.font.cg_sora_bold, FontWeight.Bold))
private val Light = lightColorScheme(
    primary = Color(0xFF3156D3), onPrimary = Color.White,
    primaryContainer = Color(0xFFEBEFFE), onPrimaryContainer = Color(0xFF243F9E),
    secondary = Color(0xFF526263), onSecondary = Color.White,
    background = Color.White, onBackground = Color(0xFF192327),
    surface = Color.White, onSurface = Color(0xFF192327),
    surfaceVariant = Color(0xFFF1F4F6), onSurfaceVariant = Color(0xFF58656E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F9FA),
    surfaceContainer = Color(0xFFF3F5F7), surfaceContainerHigh = Color(0xFFF1F4F6),
    surfaceContainerHighest = Color(0xFFE8ECEF), surfaceTint = Color(0xFF3156D3),
    outline = Color(0xFF74828D), outlineVariant = Color(0xFFE4E8EB),
    error = Color(0xFFB23829), onError = Color.White,
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB5C4FF), onPrimary = Color(0xFF162D77),
    primaryContainer = Color(0xFF293E7C), onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFFB4C8C8), background = Color(0xFF12181D), onBackground = Color(0xFFEBEFF1),
    surface = Color(0xFF12181D), onSurface = Color(0xFFEBEFF1),
    surfaceVariant = Color(0xFF202A32), onSurfaceVariant = Color(0xFFB6C0C8),
    surfaceContainerLowest = Color(0xFF0D1216), surfaceContainerLow = Color(0xFF172026),
    surfaceContainer = Color(0xFF1C252D), surfaceContainerHigh = Color(0xFF202A32),
    surfaceContainerHighest = Color(0xFF29353E), surfaceTint = Color(0xFFB5C4FF),
    outline = Color(0xFF87929A), outlineVariant = Color(0xFF35424B),
)
private val CommonTypography = Typography(
    displaySmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 39.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 35.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 31.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 29.sp),
    titleLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp),
)

@Composable fun CommonGoodsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = CommonTypography, content = content)
}


@Composable
fun CommonGoodsHeader() {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.cg_mark), contentDescription = null, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Text("Common Goods", style = MaterialTheme.typography.titleLarge)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun ProductPhoto(id: String, modifier: Modifier = Modifier) {
    val asset = when (id.lowercase()) {
        "tea" -> R.drawable.cg_tea
        "bag" -> R.drawable.cg_bag
        "headphones" -> R.drawable.cg_headphones
        "camera" -> R.drawable.cg_camera
        else -> null
    }
    if (asset == null) {
        ShopMessage("Product photo unavailable", modifier)
        return
    }
    Image(painterResource(asset), contentDescription = null, contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxWidth().height(156.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow))
}

@Composable
fun ProductIdentity(id: String, title: String, subtitle: String = "Saved for later") {
    ProductPhoto(id)
    Text(title, style = MaterialTheme.typography.headlineSmall)
    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ShopMessage(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp)) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun LabControls(content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag("lab_controls").semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
            Text(if (expanded) "Close lab controls" else "Lab controls")
        }
        if (expanded) Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow,
            RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
