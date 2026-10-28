# Verification record

Date: 2026-10-28.

## Executed

**26 backend/HTTP tests passed, zero skipped**, in the final run recorded in `test-run.txt`.

Tested using Python 3.12, Pillow 12.3.0, python-docx 1.2.0, openpyxl 3.1.5, pdfplumber 0.11.9, olefile 0.47, installed Tesseract, system Poppler 24.02.0, and a bundled LibreOfficeDev 26.8 alpha runtime. The Dockerfile installs distribution LibreOffice packages instead; the container image itself was not built here.

Verified behaviors:

- PNG → real JPEG bytes with the chosen transparency background; JPEG → actual PNG bytes; rotation changes dimensions.
- DOCX table → XLSX cells, including literal `=1+1` preserved as text rather than an executable spreadsheet formula.
- No-table documents fail with a helpful message; invalid input signatures and invalid page ranges are rejected.
- PDF → editable Word text with selected-page behavior.
- PDF → multiple PNG pages packed into a valid ZIP.
- Ruled PDF tables, aligned image tables, and scanned-PDF tables → editable XLSX cells.
- Image OCR and scanned-PDF OCR → editable DOCX text.
- Image embedding → DOCX with an actual embedded image.
- XLSX sheet/print-area selection → Word tables; landscape Excel and Word rendering → actual PDF files.
- Legacy XLS → DOCX values; legacy DOC identification and PDF rendering.
- Legacy output formats are rejected, rather than renamed.
- HTTP authentication, complete upload/status/download conversion, process-group cancellation, invalid-archive handling and source/result deletion after download/cancellation.
- Archive traversal attempts do not write outside the job directory.

All seven Kotlin source/test files passed a tree-sitter Kotlin syntax parse. Android XML resources/manifests parsed successfully. Python source passed compileall. **Syntax parsing is not Kotlin type checking, Android compilation, lint, or execution.**

## Observed fidelity issue

An exploratory DOCX → legacy DOC → DOCX round trip with the bundled **development/alpha LibreOffice runtime** flattened a table into text. Consequently a DOC → XLSX attempt correctly failed with “No usable table was found.” The final legacy DOC test verifies detection and PDF rendering, not table preservation. Direct DOCX table extraction and legacy XLS value conversion passed.

This does not establish fidelity for arbitrary real-world DOC files or the production Docker LibreOffice version. Verify representative legacy documents with the deployed runtime. FileFlip does not invent spreadsheet cells when normalization loses the table structure.

## Not executed / not claimed

- Android Gradle build, Kotlin type checking, Android unit tests or instrumentation tests, emulator UI, SAF integration and share/save behavior. The environment has no SDK/Gradle/Kotlin compiler, and its Java executable lacks required runtime components. **No debug APK was built.**
- Docker build, public HTTPS certificate issuance, Caddy deployment, or physical-device network integration.
- Multilingual Arabic OCR quality, handwriting, mixed text-and-scan pages, complex tables, uncommon fonts, formulas/pivots/charts, or exact Office layout preservation across a production corpus.
- Resource-exhaustion/load/security audit of the service. Limits and process isolation are implemented, but this is a private-instance baseline, not a production multi-tenant system.

## Device acceptance checklist

1. Run the supplied Android unit and instrumentation tests on API 26 and API 35.
2. Pick local and cloud-provider documents through SAF; check filename/type/actual copied size.
3. With airplane mode enabled, test transparent PNG → JPG, JPG → PNG, reordered/rotated images → PDF/DOCX, selected PDF pages → image ZIP.
4. Deny upload consent and verify no network request occurs. Enable HTTPS settings and verify OCR/Office conversions and server cleanup.
5. Cancel during upload, processing, and download; disconnect the network; test invalid/locked documents and low-storage conditions.
6. Review extracted XLSX cells, save through SAF, share through a content URI, preview with installed viewers, delete results, restart, and clear history.
7. Check light/dark themes, large text, TalkBack labels, and narrow screens. Exact screenshot/layout QA remains outstanding.
