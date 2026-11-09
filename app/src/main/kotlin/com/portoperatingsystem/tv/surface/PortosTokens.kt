// Generated from frontend/packages/ui/theme/tokens.json by frontend/packages/ui/theme/generate-tokens.mjs.
// Do not edit by hand. A consumer repository vendors this file with its sync target, which rewrites the next two lines.
// Source commit: 794504f604eb482b94558bf4eb0640518da50797
// Source SHA-256: 9105e42a7258f67b636fbecc49905c2975533945215c2e8749c56085daa86021
// Font family: Manrope (not bundled on the TV in v1; text uses the platform sans-serif).
@file:Suppress("all")

package com.portoperatingsystem.tv.surface

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private const val TV_SCALE = 1.5f

object PortosTokens {
    /** The one TV scale factor applied to every length below (type, spacing, sizes, radii, elevation). */
    const val TV_SCALE_FACTOR: Float = TV_SCALE

    object Display {
        val layoutWidth: Dp = 960.dp
        val layoutHeight: Dp = 540.dp
        val safeAreaInset: Dp = 24.dp
        const val LAYOUT_WIDTH_DP: Int = 960
        const val LAYOUT_HEIGHT_DP: Int = 540
        const val SAFE_AREA_INSET_DP: Int = 24
    }

    object Colors {
        val background = Color(0xFFFFFFFF)
        val foreground = Color(0xFF17191D)
        val card = Color(0xFFFFFFFF)
        val cardForeground = Color(0xFF2C2C2C)
        val popover = Color(0xFFFFFFFF)
        val popoverForeground = Color(0xFF2C2C2C)
        val primary = Color(0xFFFFDA36)
        val primaryForeground = Color(0xFF2C2C2C)
        val secondary = Color(0xFFF7F7F7)
        val secondaryForeground = Color(0xFF2C2C2C)
        val muted = Color(0xFFF7F7F7)
        val mutedForeground = Color(0xFF59616E)
        val tertiaryForeground = Color(0xFF687080)
        val accent = Color(0xFFCACACA)
        val accentForeground = Color(0xFF2C2C2C)
        val destructive = Color(0xFFF43F5E)
        val destructiveForeground = Color(0xFFFCFCFC)
        val success = Color(0xFF22C55E)
        val successForeground = Color(0xFFFCFCFC)
        val warning = Color(0xFFF59E0B)
        val warningForeground = Color(0xFF2C2C2C)
        val info = Color(0xFF5EB4C2)
        val infoForeground = Color(0xFFFCFCFC)
        val border = Color(0xFFF2F2F2)
        val codePanel = Color(0xFFF5F6F8)
        val codePanelLine = Color(0xFFE7E8EB)
        val hairline = Color(0xFFE7E8EB)
        val scrollbarThumb = Color(0xFFC5C9D0)
        val scrollbarThumbHover = Color(0xFFA3A9B3)
        val codePanelForeground = Color(0xFF17191D)
        val input = Color(0xFF8C8C8C)
        val ring = Color(0xFF828282)
        val chart1 = Color(0xFFE76E50)
        val chart2 = Color(0xFF2A9D90)
        val chart3 = Color(0xFF274754)
        val chart4 = Color(0xFFE8C468)
        val chart5 = Color(0xFFF4A462)
        val neutral0 = Color(0xFFFFFFFF)
        val neutral50 = Color(0xFFF8FAFC)
        val neutral100 = Color(0xFFF1F5F9)
        val neutral200 = Color(0xFFE2E8F0)
        val neutral300 = Color(0xFFCBD5E1)
        val neutral400 = Color(0xFF94A3B8)
        val neutral500 = Color(0xFF64748B)
        val neutral600 = Color(0xFF475569)
        val neutral700 = Color(0xFF334155)
        val neutral800 = Color(0xFF1E293B)
        val neutral900 = Color(0xFF0F172A)
        val tempWarm = Color(0xFFFFB366)
        val tempSoft = Color(0xFFFFD699)
        val tempNeutral = Color(0xFFFFF5E6)
        val tempPure = Color(0xFFFFFFFF)
        val tempCool = Color(0xFFE6F2FF)
        val primarySubtle = Color(0xFFFFF9E9)
        val primaryMuted = Color(0xFFFBE39B)
        val primaryDefault = Color(0xFFFFDA36)
        val primaryEmphasis = Color(0xFFBB8909)
        val primaryStrong = Color(0xFF755B00)
        val brandBlue = Color(0xFF2563EB)
        val link = Color(0xFF2563EB)
        val linkHover = Color(0xFF1D4ED8)
        val destructiveSubtle = Color(0xFFFEF2F2)
        val destructiveMuted = Color(0xFFFECACA)
        val destructiveDefault = Color(0xFFDC2626)
        val destructiveEmphasis = Color(0xFFB91C1C)
        val destructiveStrong = Color(0xFF991B1B)
        val successSubtle = Color(0xFFF0FDF4)
        val successMuted = Color(0xFFBBF7D0)
        val successDefault = Color(0xFF16A34A)
        val successEmphasis = Color(0xFF15803D)
        val successStrong = Color(0xFF166534)
        val warningSubtle = Color(0xFFFFFBEB)
        val warningMuted = Color(0xFFFDE68A)
        val warningDefault = Color(0xFFD97706)
        val warningEmphasis = Color(0xFFB45309)
        val warningStrong = Color(0xFF92400E)
        val infoSubtle = Color(0xFFF0F9FF)
        val infoMuted = Color(0xFFBAE6FD)
        val infoDefault = Color(0xFF0284C7)
        val infoEmphasis = Color(0xFF0369A1)
        val infoStrong = Color(0xFF075985)
        val white = Color(0xFFFFFFFF)
        val black = Color(0xFF000000)
        val mediaCanvas = Color(0xFF000000)
        val overlay = Color(0x804E4E4E)
        val deviceFrame = Color(0xFFD6D8DC)
        val deviceGlass = Color(0xFFF5F5F5)
        val inputBorder = Color(0xFFB8B5AA)
        val brandInk = Color(0xFF17191D)
        val onBrandBlue = Color(0xFFFFFFFF)
        val deviceScreen = Color(0xFFFFFFFF)
    }

    object Spacing {
        val space0 = 0.dp * TV_SCALE
        val space1 = 4.dp * TV_SCALE
        val space2 = 8.dp * TV_SCALE
        val space3 = 12.dp * TV_SCALE
        val space4 = 16.dp * TV_SCALE
        val space5 = 20.dp * TV_SCALE
        val space6 = 24.dp * TV_SCALE
        val space8 = 32.dp * TV_SCALE
        val space10 = 40.dp * TV_SCALE
        val space12 = 48.dp * TV_SCALE
        val space16 = 64.dp * TV_SCALE
    }

    object Sizes {
        val control = 44.dp * TV_SCALE
        val controlLg = 56.dp * TV_SCALE
        val row = 44.dp * TV_SCALE
        val band = 64.dp * TV_SCALE
        val iconMd = 24.dp * TV_SCALE
        val cardPadding = 24.dp * TV_SCALE
        val cardPaddingCompact = 12.dp * TV_SCALE
        val cardGap = 16.dp * TV_SCALE
        val cardHeader = 72.dp * TV_SCALE
        val dividerThickness = 1.dp * TV_SCALE
        val imageIcon = 24.dp * TV_SCALE
        val imageAvatar = 40.dp * TV_SCALE
        val imageSmallFeature = 64.dp * TV_SCALE
        val imageMediumFeature = 160.dp * TV_SCALE
        val imageLargeFeature = 320.dp * TV_SCALE
        val imageHeaderHeight = 180.dp * TV_SCALE
    }

    object Radii {
        val sm = 4.dp * TV_SCALE
        val md = 6.dp * TV_SCALE
        val lg = 8.dp * TV_SCALE
        val xl = 12.dp * TV_SCALE
        val xxl = 16.dp * TV_SCALE
        val card = 20.dp * TV_SCALE
        val pill = 999.dp * TV_SCALE
    }

    object Shapes {
        val sm = RoundedCornerShape(Radii.sm)
        val md = RoundedCornerShape(Radii.md)
        val lg = RoundedCornerShape(Radii.lg)
        val xl = RoundedCornerShape(Radii.xl)
        val xxl = RoundedCornerShape(Radii.xxl)
        val card = RoundedCornerShape(Radii.card)
        val pill = RoundedCornerShape(Radii.pill)
    }

    object Elevation {
        val raised = 3.dp * TV_SCALE
        val floating = 6.dp * TV_SCALE
        val modal = 25.dp * TV_SCALE
        val hero = 64.dp * TV_SCALE
    }

    object Weights {
        val regular = FontWeight(400)
        val medium = FontWeight(500)
        val semibold = FontWeight(600)
        val bold = FontWeight(700)
        val extrabold = FontWeight(800)
    }

    object Type {
        val fontFamily: FontFamily = FontFamily.SansSerif

        val highlight = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(800), fontSize = 60.sp * TV_SCALE, lineHeight = 67.8.sp * TV_SCALE, letterSpacing = -0.055.em)
        val feature = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(800), fontSize = 52.sp * TV_SCALE, lineHeight = 59.8.sp * TV_SCALE, letterSpacing = -0.04.em)
        val headline = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(800), fontSize = 38.sp * TV_SCALE, lineHeight = 45.6.sp * TV_SCALE, letterSpacing = -0.05.em)
        val pageTitle = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(700), fontSize = 28.sp * TV_SCALE, lineHeight = 36.sp * TV_SCALE, letterSpacing = -0.5.sp * TV_SCALE)
        val sectionHeading = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(600), fontSize = 20.sp * TV_SCALE, lineHeight = 28.sp * TV_SCALE, letterSpacing = -0.25.sp * TV_SCALE)
        val subsectionHeading = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(600), fontSize = 16.sp * TV_SCALE, lineHeight = 24.sp * TV_SCALE, letterSpacing = 0.sp * TV_SCALE)
        val body = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(400), fontSize = 14.sp * TV_SCALE, lineHeight = 22.sp * TV_SCALE, letterSpacing = 0.sp * TV_SCALE)
        val label = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(500), fontSize = 12.sp * TV_SCALE, lineHeight = 16.sp * TV_SCALE, letterSpacing = 0.2.sp * TV_SCALE)
        val caption = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(400), fontSize = 10.sp * TV_SCALE, lineHeight = 13.3333.sp * TV_SCALE, letterSpacing = 0.2.sp * TV_SCALE)
    }
}
