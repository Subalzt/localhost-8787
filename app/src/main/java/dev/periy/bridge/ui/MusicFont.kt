package dev.periy.bridge.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.periy.bridge.R

/**
 * Lexend Deca (SIL Open Font License 1.1), the typeface Namida is set in: the music screens
 * and the player use it; the rest of the app keeps its own.
 */
val LexendDeca: FontFamily = FontFamily(
    Font(R.font.lexend_deca_regular, FontWeight.Normal),
    Font(R.font.lexend_deca_medium, FontWeight.Medium),
    Font(R.font.lexend_deca_semibold, FontWeight.SemiBold),
    Font(R.font.lexend_deca_bold, FontWeight.Bold),
)
