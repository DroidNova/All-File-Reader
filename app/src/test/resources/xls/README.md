# Legacy XLS test fixtures

These Base64 text resources preserve exact XLS fixture bytes generated specifically
for this project from synthetic data by the repository's already-packaged SheetJS
Community Edition 0.20.3. They contain no third-party or user documents. The fixture
data and generation recipe are released under the project's license; SheetJS remains
Apache-2.0 licensed. The reproducible generation recipe is
`app/src/test/scripts/generate-xls-fixtures.js`. Tests decode each resource, verify
its decoded SHA-256, and only then pass the bytes to preflight or bundled SheetJS.

| Base64 resource / logical file | Purpose and expected content | Decoded SHA-256 |
|---|---|---|
| `minimal-biff8.xls.b64` / `minimal-biff8.xls` | BIFF8; `Sheet1!A1` string `Hello XLS` | `4fabc7b9d1bf34a4dbfea3e5e6b2826efb1d352a0a45b241ca9b4e22c5339a5d` |
| `multi-sheet-biff8.xls.b64` / `multi-sheet-biff8.xls` | Sheets `First`, `Second`; `First!B1=1`, `Second!A1=two` | `c71e87832ea2739eab1f93c14aac481b826e2f4fef0f2dbabfebee139b8149de` |
| `features-uppercase.XLS.b64` / `features-uppercase.XLS` | Uppercase extension; string `alpha`, number `2`, Boolean `true`, date `2020-01-02`, formula `$B$2+3` with cached result `5` | `66563ce914a958bdbe81d6e278ae660ac3db359146182fc205fe12330c768f08` |
| `empty-sheet-biff8.xls.b64` / `empty-sheet-biff8.xls` | Empty sheet named `Empty` | `08b65d4e6902b457be014ffd8c731d45f3b9bfdba161d0b59ea42162211a89bd` |
| `minimal-biff5.xls.b64` / `minimal-biff5.xls` | BIFF5; sheet `BIFF5`, `B1=5` | `4892979c1de6afd86acf74ed1765545da503c354da84de0cf354cdb44b405b71` |

Malformed CFB, sector-loop, invalid-directory, wrong-DOC/PPT, encrypted-package,
FILEPASS, resource-limit and cancellation cases are deterministically derived in
unit tests instead of storing ambiguous Office documents in the repository.
