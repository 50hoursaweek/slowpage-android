package com.fiftyhoursaweek.slowpage.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.hushhour.FontChoice
import java.io.File

/** F3: every font choice has its four bundled faces in the APK's assets (unmodified files). */
class FontsTest {
    private val fonts = File(System.getProperty("slowpage.assets")!!, "fonts")

    @Test fun everyChoiceHasItsFourFaces() {
        for (choice in FontChoice.entries) {
            for (face in listOf("Regular", "Italic", "Bold", "BoldItalic")) {
                val file = File(fonts, "${fileStem(choice)}-$face.ttf")
                assertTrue("$choice: missing $file", file.isFile && file.length() > 10_000)
            }
        }
    }

    @Test fun serifIsLiterataAndNotASansFace() {
        assertTrue(fileStem(FontChoice.SERIF) == "Literata")
        assertTrue(fileStem(FontChoice.SANS) != fileStem(FontChoice.SERIF))
    }
}
