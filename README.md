# Pocket Pantry

A native Android app (Kotlin, Jetpack Compose, Material You) for the Pixel 10 Pro / Pro XL that keeps track of the
food in a household and what it costs to replace it.

- **Pantry** – an inventory organised by storage location (Pantry, Counter, Fridge, Freezer, or your own). Quantities,
  units, pack sizes, expiry dates, low-stock warnings, notes. Add items by hand or by scanning a barcode; scanned and typed items are sorted into Fridge, Freezer, Pantry or
  Counter from their name and category (and where the household kept the same barcode before), with a one-tap
  "sort existing items" in Settings that previews every move.
- **Cart** – pick your grocery store, then scan items as they go in the cart and watch the running total (with the
  store's sales tax on taxable lines). Prices are remembered per store, so a product you've bought before is added at
  its last price in one scan. Finishing a trip can add everything to the pantry in one tap. Past trips are kept.
- **List** – a shopping list. Scanning a matching product in the cart ticks it off automatically; low-stock pantry
  items and a recipe's missing ingredients can be added in bulk.
- **Recipes** – unlocked once a Claude API key is connected. Ask Claude for recipes and meal plans; it is given the
  current inventory (with expiry dates), the shopping list and your dietary notes. Save replies, rate them 0–5 stars,
  add a photo of the dish, and have Claude work out which ingredients are missing and put them on the shopping list.
- **Household** – share one pantry with the people you live with. One phone starts a household and shows a code
  (QR or 32 characters); the other scans or types it. No account and no server: phones sync directly over the home
  Wi-Fi, or from anywhere over Tailscale, encrypted with a key derived from the code. Changes merge field by field,
  quantities add up, and an optional background listener keeps a phone reachable while the app is closed.
- **Settings** – Claude connection (API key, model, effort), appearance (system / light / dark, Material You colours),
  cooking preferences, stores, locations, currency, data.

Everything is stored on the phone in SQLite. The API key is encrypted with an AES key held in the Android hardware
Keystore (StrongBox / Titan M2 where available) and backups are disabled. There is no account, server or telemetry.
Barcodes are read on-device with ML Kit's bundled model (no Play services needed); unknown barcodes are looked up on
Open Food Facts, then UPCitemdb (US retail and store brands), and the answer is cached and shared with the household.
Anything neither knows you name once.

## Installing

- **F-Droid client (Droid-ify, Neo Store, F-Droid)**: add the repo `https://frenchtoastbig7-ux.github.io/fdroid/repo`
  and install Pocket Pantry; updates arrive through the client.
- **Direct APK**: each release on the [Releases page](https://github.com/frenchtoastbig7-ux/pocket-pantry/releases)
  carries the signed APK.

Release signing certificate (SHA-256):
`a8f05853f333c9d7da23fdd008161219cd73125ac969abcfc8363b03b42d6118`

## Household sync

Every write to a synced table (pantry, locations, products, stores, prices, finished trips, shopping list, saved
recipes) is recorded as an operation with a hybrid logical clock; phones exchange the operations they are missing and
replay them, so the same op log means the same state. Fields are last-writer-wins, quantity changes are deltas, and
deletes leave tombstones. The live cart, the recipe chat, the API key and the theme stay on each phone.

Transport is a TCP connection on port 47632 with AES-256-GCM frames keyed from the household code. Phones find each
other on the LAN with DNS-SD (`_pocketpantry._tcp`) and a UDP broadcast beacon on port 47633, and remember every address a peer was reached at, Tailscale
(100.64.0.0/10) first, so a pair that met once on Wi-Fi can later sync across the tailnet. A peer can also be added by
address. "Keep syncing in the background" runs a foreground service so the phone stays reachable when the app is
closed.

## Claude connector (no API key)

Settings → Claude connector turns the phone into an MCP server for Claude Code or Claude Desktop on your own computer,
so Claude on your subscription can read the pantry, keep the shopping list, look up prices and save recipes. It binds
only to a VPN address (Tailscale) or to loopback for USB, requires a bearer token, and deletes only if you allow it.
The screen shows the one-line `claude mcp add` command to run on the computer. The Claude app's Remote Control then
gives you the same session on the phone.

## Connecting Claude (in-app recipes)

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

## Licence

Apache License 2.0, copyright 2026 Sovereign Edge Inc. See LICENSE and NOTICE.
