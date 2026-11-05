package com.photovideo.gallerykz.memories

import com.photovideo.gallerykz.memories.launcher.IconBounds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconBoundsTest {

    private val solid = 0xFF336699.toInt()

    /** A [w] x [h] picture, empty except for a solid rectangle from ([l], [t]) to ([r], [b]) (exclusive). */
    private fun picture(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int): IntArray {
        val px = IntArray(w * h)
        for (y in t until b) for (x in l until r) px[y * w + x] = solid
        return px
    }

    @Test
    fun contentBox_findsTheVisiblePart() {
        val box = IconBounds.contentBox(picture(20, 20, 4, 6, 15, 12), 20, 20)
        assertArrayEquals(intArrayOf(4, 6, 15, 12), box)
    }

    @Test
    fun contentBox_isNullForAnEmptyPicture() {
        assertNull(IconBounds.contentBox(IntArray(100), 10, 10))
    }

    @Test
    fun contentBox_ignoresFaintShadows() {
        val px = picture(20, 20, 5, 5, 10, 10)
        px[0] = 0x10000000 // alpha 16: below the limit
        assertArrayEquals(intArrayOf(5, 5, 10, 10), IconBounds.contentBox(px, 20, 20))
    }

    @Test
    fun fullTile_fillsTheBox() {
        val box = IconBounds.contentBox(picture(50, 50, 0, 0, 50, 50), 50, 50)!!
        assertTrue(IconBounds.fillsBox(box, 50, 50))
    }

    @Test
    fun smallLogo_doesNotFillTheBox() {
        val box = IconBounds.contentBox(picture(50, 50, 10, 10, 40, 40), 50, 50)!!
        assertFalse(IconBounds.fillsBox(box, 50, 50))
    }

    @Test
    fun scale_growsASmallLogoToFillTheBox_butNotBeyondTheLimit() {
        // 40 of 50: grows by 1.25 to fill.
        assertEquals(1.25f, IconBounds.scaleFor(40, 30, 50), 0.001f)
        // 20 of 50 would need 2.5: limited to 1.3.
        assertEquals(1.3f, IconBounds.scaleFor(20, 20, 50), 0.001f)
    }

    @Test
    fun scale_usesTheLongerSide_soTallAndWideIconsLookEqual() {
        // A tall pin (30 x 45) and a wide logo (45 x 30) get the same scale in a 50 box.
        assertEquals(IconBounds.scaleFor(30, 45, 50), IconBounds.scaleFor(45, 30, 50), 0.0001f)
    }

    @Test
    fun scale_neverShrinks() {
        assertEquals(1f, IconBounds.scaleFor(60, 60, 50), 0.0001f)
    }

    @Test
    fun scale_isOneForBadInput() {
        assertEquals(1f, IconBounds.scaleFor(0, 10, 50), 0.0001f)
        assertEquals(1f, IconBounds.scaleFor(10, 10, 0), 0.0001f)
    }

    @Test
    fun contentBox_ignoresThePlainTileColour() {
        // A full white layer with a small blue logo: only the logo is measured.
        val white = 0xFFFFFFFF.toInt()
        val px = IntArray(20 * 20) { white }
        for (y in 8 until 12) for (x in 6 until 14) px[y * 20 + x] = solid
        assertArrayEquals(intArrayOf(6, 8, 14, 12), IconBounds.contentBox(px, 20, 20, ignoreColour = white))
    }

    @Test
    fun contentBox_withoutIgnoredColour_seesTheWholeTile() {
        val white = 0xFFFFFFFF.toInt()
        val px = IntArray(20 * 20) { white }
        assertArrayEquals(intArrayOf(0, 0, 20, 20), IconBounds.contentBox(px, 20, 20))
    }

    @Test
    fun contentBox_isNullWhenOnlyTheTileIsThere() {
        val white = 0xFFFFFFFF.toInt()
        assertNull(IconBounds.contentBox(IntArray(100) { white }, 10, 10, ignoreColour = white))
    }
}
