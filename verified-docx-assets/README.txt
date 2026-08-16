All File Reader - Verified Offline DOCX Viewer Assets

This bundle was assembled from the official tagged GitHub repositories.
It is intended to unblock Prompt 9B/9C in an environment without npm or
GitHub network access.

docx-preview/docxjs
  Version/tag: 0.4.0
  Repository: https://github.com/VolodymyrBaydalka/docxjs.git
  Commit: 191d3e0db009da578fbe4da70d55305cd8d50226
  Bundled file: docx-preview.min.js
  License file: LICENSE-docx-preview.txt
  License: Apache-2.0

JSZip
  Version/tag: v3.10.1
  Repository: https://github.com/Stuk/jszip.git
  Commit: 0f2f1e4d0509514417db83fe5b86bde90e0ffe8d
  Annotated tag object: ba70b5c724915d5fc2a5c1fb6aeebed0a6824357
  Bundled file: jszip.min.js
  License file: LICENSE-jszip.txt
  License: MIT

Usage handoff
  1. Attach this ZIP to the coding-agent conversation.
  2. Tell it to extract only into a temporary directory.
  3. Verify every SHA-256 value in SHA256SUMS.txt.
  4. Copy the two minified JavaScript files and two license files into the
     Android app assets as specified by Prompt 9B.
  5. Do not fetch, rebuild, or modify these files.

The completed Android app must not make runtime requests to npm, GitHub, a
CDN, or a document server.
