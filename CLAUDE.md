# Pocket Pantry: notes for Claude Code

Native Android app (Kotlin, Jetpack Compose, Material 3) for a household's pantry, grocery cart, shopping list and
recipes. Two phones in one household sync directly (Wi-Fi or Tailscale, no server). Package
`com.sovereignedge.pocketpantry`; debug builds add `.debug` and install alongside the real app with separate data.

## Build and test

- JDK 21 and an Android SDK with platform 37.2 and build-tools 36. `local.properties` holds `sdk.dir` and is not
  committed; create it if missing.
- Debug build: `./gradlew assembleDebug` (APK in `app/build/outputs/apk/debug/`).
- Unit tests: `./gradlew testDebugUnitTest`.
- Device tests (emulator or phone over ADB): `ANDROID_SERIAL=<serial> ./gradlew connectedDebugAndroidTest`. They
  delete the debug app's database and uninstall it afterwards, so never point them at a phone whose debug app holds
  data anyone cares about.
- Debug builds accept a demo-data intent for reviewing screens:
  `adb shell am start -a com.sovereignedge.pocketpantry.SEED_DEMO -n com.sovereignedge.pocketpantry.debug/com.sovereignedge.pocketpantry.MainActivity`
- `adb shell cmd uimode night yes|no` switches dark mode for screenshots.

## Releases

Signed release builds and publishing (GitHub Releases and the household's F-Droid repo) are done by the repository
owner only. The signing key is not in this repo and must never be added. Contributors build debug APKs, test them,
and send changes as a pull request. Don't change `applicationId`, the signing config, or bump `versionCode` in a pull
request; the owner bumps it when releasing.

## Layout

- `data/` SQLite schema (`PantryDb`, migrations in `onUpgrade`), `Repo` (all reads as Flows, all writes), and
  `Sync.kt` (ops and hybrid logical clocks).
- `sync/` household pairing code, encrypted frames, the sync protocol, LAN discovery (DNS-SD plus a UDP beacon), and
  the optional background service.
- `bridge/` the Claude connector: an MCP server (Ktor + Kotlin MCP SDK) on the phone's Tailscale address, port 47634.
- `ai/` in-app Claude API calls (API key path). `barcode/` CameraX + ML Kit scanner and product lookups.
- `ui/` Compose screens; shared building blocks in `ui/common/Ui.kt` (`BigTitleBar`, `Tile`, `Pill`, `Monogram`).

## Rules that keep the household in sync

- Every write to a synced table goes through `Repo.commit { set/delta/delete }` so it becomes a replicated op. Never
  write synced tables with raw SQL; the other phone would never see the change.
- Synced tables are listed in `SyncTable.ALL` (`data/Sync.kt`). A new synced column needs: the column in `onCreate`,
  an `ALTER TABLE` in `onUpgrade` with a `VERSION` bump, and an entry in that table's `columns` map.
- Both phones may run different versions for a while. Ops must stay readable by the previous release: add fields,
  don't rename or repurpose existing ones.
- Edits send only the fields that changed (see `upsertItem`), so two people editing different fields both win.

## Gotchas already paid for

- R8 strips classes ML Kit loads by reflection; the keep rules in `proguard-rules.pro` matter. Test the scanner on a
  release build with camera permission revoked (`pm revoke`) so the grant path runs.
- Android 17 needs the runtime permission `ACCESS_LOCAL_NETWORK` for any LAN traffic; the Household screen asks.
- The Kotlin MCP SDK's DNS-rebinding guard rejects non-localhost Host headers unless `allowedHosts` includes the bound
  address (`ConnectorServer`).
- A foreground service can't start from `Application.onCreate` after an upgrade; services are restarted when the
  Activity comes to the foreground.
- CameraX must be unbound when the scanner leaves the screen.
