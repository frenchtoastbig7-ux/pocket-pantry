# Pocket Pantry

A native Android app (Kotlin, Jetpack Compose, Material You) for the Pixel 10 Pro / Pro XL that keeps track of the
food in a household and what it costs to replace it.

- **Pantry** – an inventory organised by storage location (Pantry, Counter, Fridge, Freezer, or your own). Quantities,
  units, expiry dates, low-stock warnings, notes. Add items by hand or by scanning a barcode.
- **Cart** – pick your grocery store, then scan items as they go in the cart and watch the running total (with the
  store's sales tax on taxable lines). Prices are remembered per store, so a product you've bought before is added at
  its last price in one scan. Finishing a trip can add everything to the pantry in one tap. Past trips are kept.
- **List** – a shopping list. Scanning a matching product in the cart ticks it off automatically; low-stock pantry
  items and a recipe's missing ingredients can be added in bulk.
- **Recipes** – unlocked once a Claude API key is connected. Ask Claude for recipes and meal plans; it is given the
  current inventory (with expiry dates), the shopping list and your dietary notes. Save replies and have Claude work
  out which ingredients are missing and put them on the shopping list.
- **Settings** – Claude connection (API key, model, effort), appearance (system / light / dark, Material You colours),
  cooking preferences, stores, locations, currency, data.

Everything is stored on the phone in SQLite. The API key is encrypted with an AES key held in the Android hardware
Keystore (StrongBox / Titan M2 where available) and backups are disabled. There is no account, server or telemetry.
Barcodes are read on-device with ML Kit's bundled model (no Play services needed); unknown barcodes are looked up on
Open Food Facts and the answer is cached, and anything it doesn't know you name once.

## Connecting Claude

Settings → Claude → paste an API key from console.anthropic.com. Requests go directly from the phone to
`api.anthropic.com` using the official Anthropic Java SDK. Anthropic's terms do not allow third-party apps to sign in
with a Claude.ai subscription, so an API key is the supported route. Server-side refusal fallbacks are enabled for the
Opus and Sonnet models.

## Building

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```

Requires JDK 21 and an Android SDK with platform 37.2 (`local.properties` points at it). Release builds are
R8-minified; signing is read from `pocketpantry.*` properties in `~/.gradle/gradle.properties` when present.

Unit tests: `./gradlew testDebugUnitTest`.

## Layout

```
app/src/main/java/com/sovereignedge/pocketpantry/
  PantryApp.kt, MainActivity.kt, Settings.kt, SecretStore.kt
  data/        Models, SQLite schema (PantryDb) and the Repo exposing Flows
  barcode/     CameraX + ML Kit scanner composable, Open Food Facts lookup
  ai/          ClaudeService (SDK calls) and RecipeChat (conversation state, prompt, ingredient extraction)
  ui/          Compose screens: inventory, cart, shopping, recipes, settings; common widgets and a small Markdown renderer
```

Product data: Open Food Facts, licensed under the Open Database License (ODbL).
