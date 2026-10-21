package com.sovereignedge.pocketpantry

import com.sovereignedge.pocketpantry.barcode.SizeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeParserTest {
    @Test
    fun readsSizesOutOfRetailTitles() {
        assertEquals("12 fl oz", SizeParser.parse("Coca-Cola Can, 12 fl oz"))
        assertEquals("16 oz", SizeParser.parse("Signature Select Spaghetti 16 oz"))
        assertEquals("16 oz", SizeParser.parse("Signature SELECT Pasta, 16 OZ"))
        assertEquals("1.25 oz", SizeParser.parse("Chili Seasoning Mix 30% Less Sodium, 1.25 Ounce"))
        assertEquals("5 oz", SizeParser.parse("StarKist Chunk Light Tuna in Water - 5oz"))
        assertEquals("2 lb", SizeParser.parse("Jasmine Rice 2 lbs"))
        assertEquals("500 g", SizeParser.parse("Fage Total Greek Yogurt 500g"))
        assertEquals("1.5 L", SizeParser.parse("Sparkling water 1,5 l"))
        assertEquals("40 × 16.9 oz", SizeParser.parse("Kirkland Signature Purified Drinking Water, 40 x 16.9 Ounce"))
        assertEquals("12 ct", SizeParser.parse("Large Eggs, 12 ct"))
        assertEquals("6 pack", SizeParser.parse("Yogurt cups, pack of 6"))
    }

    @Test
    fun ignoresTextWithoutASize() {
        assertNull(SizeParser.parse("Bay Leaf"))
        assertNull(SizeParser.parse("30% Less Sodium"))
        assertNull(SizeParser.parse(null))
        assertTrue(SizeParser.looksLikeSize("16 oz"))
        assertTrue(SizeParser.looksLikeSize("12 fl oz"))
        assertFalse(SizeParser.looksLikeSize("9-11"))
        assertFalse(SizeParser.looksLikeSize("Multi-Color"))
    }
}
