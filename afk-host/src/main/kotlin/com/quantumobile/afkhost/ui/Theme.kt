package com.quantumobile.afkhost.ui

import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment

/** The look of the host window: the app's dark purple and teal. */
object Theme {
    val bg = Color(0x0E0B1F)
    val card = Color(0x1A1733)
    val cardHigh = Color(0x24204A)
    val outline = Color(0x332F63)
    val text = Color(0xEDE9FF)
    val textDim = Color(0xAFA9D6)
    val teal = Color(0x2DE0D0)
    val purple = Color(0x8F6BFF)
    val purpleDeep = Color(0x4B2FB0)
    val green = Color(0x43D17A)
    val amber = Color(0xF6B73C)
    val red = Color(0xFF5C6C)
    val gray = Color(0x7C77A3)

    private val family: String by lazy {
        val names = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        listOf("Malgun Gothic", "맑은 고딕", "Apple SD Gothic Neo", "Noto Sans CJK KR", "Noto Sans KR", "NanumGothic", "WenQuanYi Zen Hei")
            .firstOrNull { it in names } ?: Font.SANS_SERIF
    }
    private val mono: String by lazy {
        val names = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        listOf("Cascadia Mono", "Consolas", "Menlo", "DejaVu Sans Mono").firstOrNull { it in names } ?: Font.MONOSPACED
    }

    fun font(size: Float, bold: Boolean = false): Font = Font(family, if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)
    fun monoFont(size: Float, bold: Boolean = false): Font = Font(mono, if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)

    fun alpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)
}
