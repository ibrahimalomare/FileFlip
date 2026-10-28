# FileFlip

**Convert your files easily.**

A native Kotlin / Jetpack Compose Android application with real local converters and a separate Python Office/OCR service. No conversion changes only an extension; no simulated completion or percentage is used.

## Delivery status

- Android source is implemented, including local conversions, system file picker, configuration, consent, remote jobs, table preview, save/share/delete, persistent recent results, and light/dark/system themes.
- Backend conversion and HTTP tests were executed in the authoring environment. See `docs/VERIFICATION.md` and `docs/test-run.txt` for the exact scope.
- **Android compilation and emulator/device execution were not possible here:** no Android SDK, Gradle, Kotlin compiler, or complete JDK. No APK is included and the Android app is not represented as device-verified.
- Docker/TLS deployment is supplied but was not executed here. A server hostname, HTTPS certificate, and access token must be configured for remote features. Offline features need none of these.

## Conversion matrix

| Input → Output | Implementation | Offline? | Fidelity / editable output |
|---|---|---|---|
| JPG/JPEG ↔ PNG | Android Bitmap decoder/encoder | Yes | Actual pixel encoding; JPG composites transparency over selected color; metadata/color profiles may be lost |
| Images → PDF | Android PdfDocument | Yes | Ordered, per-image rotation, A4/Letter, margins, portrait/landscape; one image per page |
| PDF → JPG/PNG | Android PdfRenderer | Yes | Selected pages; rasterized to at most 2400 px on longest edge; multiple pages become ZIP |
| Images → DOCX (embed) | Android OOXML ZIP writer | Yes | One embedded PNG per page, aspect ratio maintained; text is not editable |
| Images → DOCX (OCR) | Tesseract + python-docx | No | Editable paragraphs; recognition/language/reading-order limitations |
| DOC/DOCX → PDF | LibreOffice Writer | No | Layout, tables and images rendered; fonts and complex MS Word features may differ |
| DOC/DOCX → JPG/PNG | LibreOffice → PDF → Poppler | No | Selected rendered pages; multiple images become ZIP |
| PDF → DOCX | pdfplumber text extraction + Tesseract fallback | No | Editable text with page breaks; approximated whitespace, no exact positioned layout, retained illustrations or font reconstruction |
| DOC/DOCX → XLSX | python-docx tables; DOC normalized by LibreOffice | No | Explicit top-level tables only; cells become literal text; no arbitrary page-to-grid conversion |
| PDF → XLSX | pdfplumber ruled tables; OCR for pages without text | No | Digital borderless tables may be missed; scanned aligned columns use a conservative heuristic; preview and review required |
| Images → XLSX | Tesseract word coordinates | No | At least two aligned columns and rows; rejects sources without a usable table; OCR can still misgroup cells |
| XLS/XLSX → PDF | openpyxl sheet/print-area selection → LibreOffice Calc | No | Selected sheets, A4/Letter and orientation, fit-to-width pagination; complex drawings/Excel features may change |
| XLS/XLSX → JPG/PNG | Calc PDF → Poppler | No | Render selected sheets/print ranges, then selected PDF pages; ZIP for multiple images |
| XLS/XLSX → DOCX | openpyxl + python-docx | No | Editable Word tables of cached values; selected sheets/range; no formulas/charts transferred |

**Legacy output:** DOC and XLS are accepted as inputs and verified as OLE containers/streams on the server. Editable outputs are DOCX and XLSX only. No DOC/XLS export is offered. Conversion of password-protected files requires the user to unlock and save them first.

## Architecture

`MainActivity` renders Material 3 UI and invokes system OpenMultipleDocuments/CreateDocument contracts. `FlipViewModel` owns immutable StateFlow state, job cancellation, staging, persistence and error reporting. Files from content URIs are copied into bounded private cache on `Dispatchers.IO`, so no raw filesystem path or broad storage permission is required. `Rules` defines compatible outputs and local/remote routing. `LocalConverter` performs platform-native rendering and encoding. `RemoteConverter` sends an authenticated ZIP job, polls actual processing stages, downloads the result, and requests cancellation/deletion when interrupted.

Server: `server.py` handles authenticated bounded uploads and job lifecycle; each job runs in a separate process group in `worker.py`. `engine.py` uses real document libraries and command-line renderers. Worker CPU/memory/output limits and a 180-second wall-time watchdog bound processing. A 15-minute expiry removes abandoned jobs. The result download is one-shot: all server files are deleted after transfer, including interrupted transfers. Client retries require a new conversion. Docker uses an internal network to block converter internet egress, a non-root user, read-only filesystem, temporary memory-backed storage, and Caddy for TLS.

### Working stages

1. Local image re-encoding, PDF assembly/rendering, embedded-image DOCX and ZIP export.
2. LibreOffice Word/Excel rendering, editable PDF text extraction and Office table export.
3. Tesseract OCR, conservative table detection, reviewable cell preview, authenticated HTTPS jobs and cleanup.

## Build Android

Requirements: a full **JDK 17**, **Gradle 8.9**, Android SDK platform **35**, SDK build-tools **35.0.0**, and Android Studio with Kotlin/Compose support. Minimum device: Android 8.0 (API 26).

1. Extract the archive and open `android/` in Android Studio. Set `ANDROID_HOME`, or create `android/local.properties` with `sdk.dir=/absolute/path/to/Android/sdk`.
2. This source bundle intentionally does not contain an unverified Gradle wrapper JAR. Install Gradle 8.9 locally, then generate the standard wrapper:

   ```sh
   cd android
   gradle wrapper --gradle-version 8.9
   ./gradlew testDebugUnitTest assembleDebug
   ```

3. Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
4. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk` or Run from Android Studio.
5. With an emulator/device attached: `./gradlew connectedDebugAndroidTest`.

The included GitHub Actions workflow performs unit tests, Android lint, APK compilation, and uploads the debug APK after the project is pushed to your repository. It is supplied, not already run. Dependency versions are pinned for this source baseline, not claimed to be the newest available.

## Run the backend

### Recommended private deployment with Docker Compose

Install Docker with Compose. Point a DNS hostname to your host; allow inbound ports 80 and 443 for Caddy's certificate provisioning.

```sh
cd backend
export FILEFLIP_DOMAIN=convert.example.com
export FILEFLIP_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
docker compose up --build -d
```

Keep the token securely for your own device; do not commit it or compile it into the APK. Configure **Settings → HTTPS server URL** as `https://convert.example.com` and enter the token. The app holds the token only in memory until process exit. Every remote conversion asks for consent and identifies the server and upload size. Never use a public untrusted converter for sensitive documents.

Docker images and OS packages must be rebuilt/updated by the operator. Install additional licensed fonts in the converter image when needed; neither Microsoft fonts nor private fonts are bundled. English and Arabic Tesseract language packs are installed by the Dockerfile. Other languages require adding corresponding packages and using their language codes.

This is a private-instance service with one shared deployment token, not a multi-tenant public SaaS. Add per-user identity, quotas, monitoring without content logging, and stronger per-job container isolation before operating a public conversion service. Only the TLS proxy is exposed; the converter service port is not published. Do not weaken TLS checks in the app.

### Local backend development (Linux)

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
# Install LibreOffice Writer/Calc, Poppler utilities, Tesseract, desired language packs,
# Liberation/DejaVu/Noto fonts through your OS package manager.
export FILEFLIP_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
python server.py
```

The development listener binds `127.0.0.1:8080`. The Android app deliberately rejects HTTP: put a trusted HTTPS proxy in front of it. `FILEFLIP_SOFFICE` and `FILEFLIP_PDFTOPPM` can point to custom executable paths. `FILEFLIP_TMP` chooses the private temporary directory. The production Compose network restriction is not present when running directly on your host.

## Tests

```sh
cd backend
pip install -r requirements.txt -r requirements-test.txt
python -m unittest discover -s tests -v
```

Tests generate files and inspect actual encoded bytes, document cells/text/images, selected PDF pages, OCR, table detection, no-table errors, invalid input, page range validation, archive traversal rejection, authentication, cancellation, and temporary file deletion. LibreOffice tests skip if its executable is unavailable; legacy tests additionally need `olefile`. Test fixtures are synthetic. Android JVM and instrumentation tests are supplied separately; see build commands above.

## Operational and product limitations

- Keep the app open during conversion. Work survives activity rotation via ViewModel, but is **not a persistent WorkManager task**. Process termination interrupts work; orphaned cache is removed on next startup and server jobs expire. Local PDF rendering/encoding is cancellable between operations; an individual native render/compress call is not interruptible.
- Up to 20 selected images or one Office/PDF document; 50 MB total input, 100 PDF pages; Android images limited to 16 MP, server images 25 MP. Office sheet inspection is capped at 100,000 cells; Word table export at 10,000 cells per range. Backend output is capped at 100 MB. These limits protect memory/storage.
- PDF selection is 1-based (`1,3-5`). Output image scale is bounded; this is not archival-resolution export.
- Preview for images/PDF/Word/ZIP uses an installed compatible viewer. If unavailable, the app explains how to save/open elsewhere. Table previews are built in, showing up to 15 rows and 10 columns per table; the saved workbook contains the full extracted table.
- Excel source formulas must have cached values for Word export. Missing caches yield empty cells. Named/complex print areas, very wide tables, hidden layout, macros, embedded objects, pivot tables and charts may not be preserved. Sheet names containing commas cannot be selected individually through the comma-separated sheet selector.
- PDF OCR is per page only when no text is extracted. Mixed text/scan pages, incorrect existing text layers, handwriting, skewed scans, multilingual reading order and complex layouts need manual correction. There is no guarantee of perfect OCR or original layout reconstruction.
- The bundled development LibreOffice used for verification flattened a table in an exploratory DOC round trip; legacy table fidelity must be checked on the deployed runtime. See the verification record.
- No arbitrary image/page-to-spreadsheet conversion, no legacy editable output, no password unlocking, no macro execution, no format-preserving round trip guarantee.
- Recent results are private app files and persist until deleted/cleared or app uninstall. Input copies persist in private cache until replacement, next startup, or OS eviction. Android backups are disabled. Clearing history deletes local results; exported/shared copies remain under user control.

See `docs/DEPENDENCIES.md` for dependency licenses and upstream references, `docs/VERIFICATION.md` for what actually ran, and `docs/API.md` for the transport contract.
