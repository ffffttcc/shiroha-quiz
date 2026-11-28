package com.yiqiu.shirohaquiz.latex

import org.junit.Test
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula

class LatexMathParseTest {

    private val samples = listOf(
        "\\mathrm{H}_{1}", "\\mathrm{ED}_{50}", "x^2", "\\frac{a}{b}",
        "\\sqrt{2}", "1/\\sqrt{2}", "\\mathbf{B}_{12}", "pK_{a}",
        "\\mathrm{H}" + "_{1}", "\\mathrm{Na}_{2}\\mathrm{CO}_{3}"
    )

    @Test
    fun dumpSizes() {
        for (s in samples) {
            try {
                val icon = TeXFormula(s).createTeXIcon(TeXConstants.STYLE_DISPLAY, 30f)
                System.out.println("OK   size=${icon.iconWidth}x${icon.iconHeight}  tex=[$s]")
            } catch (t: Throwable) {
                System.out.println("FAIL ${t.javaClass.name}: ${t.message}  tex=[$s]")
            }
        }
        // 带 $ 的对比，验证 $ 是否导致失败
        for (s in listOf("$\\mathrm{H}_{1}$", "\\mathrm{H}_{1}")) {
            try {
                val icon = TeXFormula(s).createTeXIcon(TeXConstants.STYLE_DISPLAY, 30f)
                System.out.println("OK   size=${icon.iconWidth}x${icon.iconHeight}  tex=[$s]")
            } catch (t: Throwable) {
                System.out.println("FAIL ${t.javaClass.name}: ${t.message}  tex=[$s]")
            }
        }
    }
}
