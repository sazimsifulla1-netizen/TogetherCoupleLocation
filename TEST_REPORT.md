# Together Romantic Edition v1.1 — Validation

## Passed locally
- Backend JavaScript syntax with `node --check server/index.js`
- Unit tests: 3/3 via `node --test server/helpers.test.js`
- Inline WebView JavaScript syntax via `node --check` of extracted script
- Android XML parsing: manifest, vector drawable icon, styles
- Source contains an Android GitHub Actions debug APK build workflow
- `design-preview.html` is a browser-only UI preview with artificial coordinates, not real tracking

## Not validated
- Android debug APK compilation (Android SDK/Gradle absent from this runtime)
- App install/foreground service behavior on physical Android phones
- Postgres-backed login/pairing/location API live end-to-end
- Render deployment, because the destination Render account/service is not connected
- OpenStreetMap tile loading / map UI visual screenshot: not fully validated

Do not equate source validation with a working production deploy.
