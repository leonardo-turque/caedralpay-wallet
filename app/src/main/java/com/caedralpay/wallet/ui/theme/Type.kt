package com.caedralpay.wallet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import com.caedralpay.wallet.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Manrope — The Manrope Project Authors, SIL OFL 1.1.
// Assets locais; licença redistribuída em assets/licenses/Manrope-OFL.txt.
val CaedralFont = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold)
)
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = CaedralFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(fontFamily = CaedralFont, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(
        fontFamily = CaedralFont,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(fontFamily = CaedralFont, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = CaedralFont, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = CaedralFont, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = CaedralFont, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(
        fontFamily = CaedralFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    displayLarge = TextStyle(fontFamily = CaedralFont, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = CaedralFont, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 48.sp),
    displaySmall = TextStyle(fontFamily = CaedralFont, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = CaedralFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = CaedralFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleSmall = TextStyle(fontFamily = CaedralFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = CaedralFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)
