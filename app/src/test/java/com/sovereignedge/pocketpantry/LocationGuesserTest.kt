package com.sovereignedge.pocketpantry

import com.sovereignedge.pocketpantry.barcode.LocationGuesser
import com.sovereignedge.pocketpantry.barcode.LocationGuesser.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationGuesserTest {
    @Test
    fun sortsTypicalGroceries() {
        assertEquals(Kind.FREEZER, LocationGuesser.guess("Birds Eye Frozen Peas"))
        assertEquals(Kind.FREEZER, LocationGuesser.guess("Ben & Jerry's", category = "Ice cream"))
        assertEquals(Kind.FRIDGE, LocationGuesser.guess("Fage Total Greek Yogurt"))
        assertEquals(Kind.FRIDGE, LocationGuesser.guess("Organic Valley Whole Milk"))
        assertEquals(Kind.FRIDGE, LocationGuesser.guess("Large Eggs 12 ct"))
        assertEquals(Kind.FRIDGE, LocationGuesser.guess("Boneless chicken thighs", category = "Meat"))
        assertEquals(Kind.COUNTER, LocationGuesser.guess("Bananas"))
        assertEquals(Kind.COUNTER, LocationGuesser.guess("Sourdough loaf"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Jalapeno Tuna", "StarKist"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Chicken Bouillon Packets", "Goya"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Chili Seasoning Mix 30% Less Sodium", "McCormick"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Signature Select Diced Tomatoes 14.5 oz"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Swanson Chicken Broth"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Hershey's Milk Chocolate Bar"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Arborio rice", "Riso Gallo"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Open Food Facts item", category = "en:canned-foods, en:legumes"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Evaporated Milk / Leche Evaporada"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("CREAM OF MUSHROOM WITH ROASTED GARLIC"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Dill Relish"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Parsley flakes"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Sazon Con Cilantro Y Achiote"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Yakisoba Orange Chicken Flavor"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("smart pop! MINI BAGS BUTTER"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("SNACK ON THE RUN! CHICKEN SALAD & CRACKERS"))
        assertEquals(Kind.PANTRY, LocationGuesser.guess("Pineapple Chunks"))
        assertEquals(Kind.FRIDGE, LocationGuesser.guess("Ahi Tuna Steaks"))
        assertEquals(Kind.FRIDGE, LocationGuesser.guess("Wild Alaskan Cod Fillets"))
        assertNull(LocationGuesser.guess("Bay Leaf"))
        assertNull(LocationGuesser.guess(""))
    }

    @Test
    fun mapsKindsToTheHouseholdsLocationNames() {
        val names = listOf("Pantry", "Counter", "Fridge", "Freezer")
        assertEquals("Fridge", LocationGuesser.matchLocation(Kind.FRIDGE, names))
        assertEquals("Freezer", LocationGuesser.matchLocation(Kind.FREEZER, names))
        assertEquals("Counter", LocationGuesser.matchLocation(Kind.COUNTER, names))
        assertEquals("Pantry", LocationGuesser.matchLocation(Kind.PANTRY, names))
        assertEquals("Kitchen cupboard", LocationGuesser.matchLocation(Kind.PANTRY, listOf("Kitchen cupboard", "Refrigerator")))
        assertNull(LocationGuesser.matchLocation(Kind.FREEZER, listOf("Kitchen cupboard", "Refrigerator")))
    }
}
