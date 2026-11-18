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
    fun readsUsLiquidVolumes() {
        assertEquals("1 gal", SizeParser.parse("Whole Milk, 1 Gallon"))
        assertEquals("0.5 gal", SizeParser.parse("Orange Juice 0.5 gal"))
        assertEquals("1 qt", SizeParser.parse("Half & Half, 1 qt."))
        assertEquals("2 qt", SizeParser.parse("Chicken Broth 2 Quarts"))
        assertEquals("1 pt", SizeParser.parse("Heavy Whipping Cream 1 Pint"))
        assertEquals("6 × 1 pt", SizeParser.parse("Ice Cream 6 x 1 pt"))
        assertNull(SizeParser.parse("Top 5 Galaxy Bars"))
    }

    @Test
    fun prefersAPhysicalSizeOverACount() {
        assertEquals("16 oz", SizeParser.parse("Rice 2 bags, 16 oz"))
        assertEquals("2 bag", SizeParser.parse("Rice 2 bags"))
        assertEquals("12 ct", SizeParser.parse("Large Eggs, 12 ct"))
        assertFalse(SizeParser.looksLikeSize("2 bags, 16 oz"))
    }

    @Test
    fun readsFractionsWhole() {
        assertEquals("1/2 gal", SizeParser.parse("Milk 1/2 gallon"))
        assertEquals("1 1/2 qt", SizeParser.parse("Broth 1 1/2 qt"))
        assertEquals("3/4 lb", SizeParser.parse("Deli Ham 3/4 lb"))
        assertTrue(SizeParser.looksLikeSize("1/2 gal"))
        assertNull(SizeParser.parse("Scale 1/24 model"))
    }

    @Test
    fun ignoresTextWithoutASize() {
        assertNull(SizeParser.parse("Bay Leaf"))
        assertNull(SizeParser.parse("30% Less Sodium"))
        assertNull(SizeParser.parse(null))
        assertTrue(SizeParser.looksLikeSize("16 oz"))
        assertTrue(SizeParser.looksLikeSize("12 fl oz"))
        assertTrue(SizeParser.looksLikeSize("1 Gallon"))
        assertTrue(SizeParser.looksLikeSize(" 16 Ounce "))
        assertTrue(SizeParser.looksLikeSize("4 x 15 Ounces"))
        assertFalse(SizeParser.looksLikeSize("Whole Milk, 1 Gallon"))
        assertFalse(SizeParser.looksLikeSize(null))
        assertFalse(SizeParser.looksLikeSize("9-11"))
        assertFalse(SizeParser.looksLikeSize("Multi-Color"))
    }
}
