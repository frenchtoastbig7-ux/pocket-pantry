package com.sovereignedge.pocketpantry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sovereignedge.pocketpantry.PantryApp
import com.sovereignedge.pocketpantry.R
import com.sovereignedge.pocketpantry.ui.cart.CartScreen
import com.sovereignedge.pocketpantry.ui.household.HouseholdScreen
import com.sovereignedge.pocketpantry.ui.inventory.InventoryScreen
import com.sovereignedge.pocketpantry.ui.recipes.RecipesScreen
import com.sovereignedge.pocketpantry.ui.settings.SettingsScreen
import com.sovereignedge.pocketpantry.ui.shopping.ShoppingListScreen

/** Set to false by a screen while it shows a full-screen scanner; AppRoot then hides the navigation bar. */
val LocalChromeVisible = compositionLocalOf<MutableState<Boolean>> { error("No chrome state") }

enum class Tab(val label: String, val icon: Int) {
    Pantry("Pantry", R.drawable.ic_kitchen),
    Cart("Cart", R.drawable.ic_cart),
    List("List", R.drawable.ic_list),
    Recipes("Recipes", R.drawable.ic_restaurant),
}

@Composable
fun AppRoot(app: PantryApp) {
    val settings by app.settings.state.collectAsStateWithLifecycle()
    PocketPantryTheme(themeMode = settings.themeMode) {
        var tabIndex by rememberSaveable { mutableIntStateOf(0) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showHousehold by rememberSaveable { mutableStateOf(false) }
        val pendingJoin by app.pendingJoin.collectAsStateWithLifecycle()
        androidx.compose.runtime.LaunchedEffect(pendingJoin) { if (pendingJoin != null) showHousehold = true }
        val chromeVisible = remember { mutableStateOf(true) }
        CompositionLocalProvider(LocalChromeVisible provides chromeVisible) { Surface {
            if (showHousehold) {
                BackHandler { showHousehold = false }
                HouseholdScreen(app = app, onBack = { showHousehold = false })
            } else if (showSettings) {
                BackHandler { showSettings = false }
                SettingsScreen(app = app, onBack = { showSettings = false }, onOpenHousehold = { showHousehold = true })
            } else {
                Scaffold(
                    bottomBar = {
                        if (chromeVisible.value) NavigationBar {
                            Tab.entries.forEachIndexed { index, tab ->
                                NavigationBarItem(
                                    selected = tabIndex == index,
                                    onClick = { tabIndex = index },
                                    icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val modifier = if (chromeVisible.value) Modifier.padding(bottom = padding.calculateBottomPadding()) else Modifier
                    AnimatedContent(targetState = Tab.entries[tabIndex], label = "tab") { tab ->
                        when (tab) {
                            Tab.Pantry -> InventoryScreen(app, modifier, onOpenSettings = { showSettings = true })
                            Tab.Cart -> CartScreen(app, modifier, onOpenSettings = { showSettings = true })
                            Tab.List -> ShoppingListScreen(app, modifier, onOpenSettings = { showSettings = true })
                            Tab.Recipes -> RecipesScreen(app, modifier, onOpenSettings = { showSettings = true })
                        }
                    }
                }
            }
        } }
    }
}
