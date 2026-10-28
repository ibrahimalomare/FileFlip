# Dependency and license review

This project separates Android-native conversion from server-only Office/OCR tools. No commercial conversion API or embedded private credential is used. The review below describes upstream licenses, not a substitute for the exact notices shipped by installed versions. Retain upstream copyright/license notices when distributing dependencies. Source code here does not vendor dependency binaries.

| Component | Role | License / distribution notes |
|---|---|---|
| Kotlin / kotlinx.coroutines | Android language, cancellable background work | Apache-2.0 |
| AndroidX Activity, Core, Lifecycle, Compose Material 3, ExifInterface | UI, state, content URI sharing, EXIF orientation | Apache-2.0; retain artifact notices |
| Android platform Bitmap/PdfDocument/PdfRenderer/SAF | Local conversion and file selection | OS APIs; no separate conversion SDK bundled |
| OkHttp 4.12.0 and Okio | HTTPS upload/poll/download | Apache-2.0 |
| Python standard library HTTP/process/ZIP tools | Private-instance API and isolation | Python Software Foundation license |
| python-docx 1.2.0 | Editable DOCX and tables | MIT; lxml transitive dependency has its own notices |
| openpyxl 3.1.5 | Editable XLSX and sheet selection | MIT; et-xmlfile transitive notices apply |
| pdfplumber 0.11.9 | PDF text and ruled-table extraction | MIT; pdfminer.six and pypdfium2 dependencies require their notices too |
| Pillow 12.3.0 | Image decoding/encoding/rotation | MIT-CMU; packaged codec libraries have separate notices |
| olefile 0.47 | Validate legacy OLE stream types | BSD-2-Clause |
| LibreOffice Writer/Calc | Office layout rendering and legacy normalization | MPL-2.0 with additional upstream component licenses; server-only executable. Consult the installed LICENSE files, preserve notices, and satisfy applicable source obligations if redistributing its binaries/modifications |
| Poppler pdftoppm | Server PDF rasterization | GPL-2.0-or-later; used as a separate server executable, not linked into the Android app. Distributing the Docker image includes Poppler and requires its applicable GPL notices/source provisions |
| Tesseract | OCR text and word boxes | Apache-2.0; Leptonica is BSD-style; trained data/font packages have their own licenses |
| Caddy | TLS reverse proxy | Apache-2.0 plus dependency notices |
| Liberation / DejaVu / Noto fonts | Office substitution and OCR fixtures | Font-specific open licenses; preserve package copyright files. No Microsoft fonts are included |
| ReportLab (test only) | Synthetic PDF fixtures | BSD-style; not required by the conversion service |
| JUnit / Android test libraries (test only) | Local/Android test harnesses | JUnit EPL-1.0; AndroidX Apache-2.0 |

The app intentionally does not use PyMuPDF/pdf2docx, avoiding a separate AGPL/commercial PDF engine dependency. DOCX embedded-image output is a small purpose-built OOXML writer using platform ZIP and XML text; there is no third-party Word rendering library in the APK.

For distributing built binaries, generate a full dependency/SBOM report from the resolved Gradle/Python/OS packages, include their license texts, and review transitive versions. Container sources and notices are available from the relevant upstream projects and OS package archives. This source bundle is not a pre-cleared binary distribution.

## Primary references consulted

- Android SAF: https://developer.android.com/training/data-storage/shared/documents-files
- Android PDF/SAF sample: https://github.com/android/storage-samples/tree/main/ActionOpenDocument
- LibreOffice command-line conversion: https://help.libreoffice.org/latest/en-GB/text/shared/guide/start_parameters.html
- LibreOffice licensing: https://www.libreoffice.org/licenses/
- python-docx: https://pypi.org/project/python-docx/ and https://github.com/python-openxml/python-docx
- openpyxl: https://openpyxl.readthedocs.io/en/stable/
- pdfplumber license: https://github.com/jsvine/pdfplumber/blob/stable/LICENSE.txt
- Pillow licensing: https://pillow.readthedocs.io/en/stable/about.html
- Tesseract license: https://github.com/tesseract-ocr/tesseract/blob/main/LICENSE
- Poppler: https://poppler.freedesktop.org/
- OkHttp source/license: https://github.com/square/okhttp
- Coroutines cancellation/resource handling: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-cancellable-continuation/resume.html

Version choices reproduce the implementation baseline; they are not a claim to be current latest versions or a complete vulnerability audit.
