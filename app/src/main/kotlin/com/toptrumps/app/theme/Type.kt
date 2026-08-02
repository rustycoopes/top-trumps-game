package com.toptrumps.app.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.toptrumps.app.R

/** Anton (SIL OFL 1.1) — the bundled display face; licence text ships in `assets/fonts/OFL.txt`. */
internal val DisplayFontFamily = FontFamily(Font(R.font.anton_regular, FontWeight.Normal))

internal val TopTrumpsTypography = Typography(
    headlineLarge = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 20.sp),
)
