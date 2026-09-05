import fs from "node:fs";
import assert from "node:assert/strict";

const source = fs.readFileSync("app/src/main/assets/pptx_viewer/viewer-state.js", "utf8");
const moduleUrl = `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`;
const { boundedSearchResults, normalizeSearchLimit, wrappedIndex, HARD_MAX_SEARCH_LIMIT } = await import(moduleUrl);

assert.equal(normalizeSearchLimit(0), 250);
assert.equal(normalizeSearchLimit(-1), 250);
assert.equal(normalizeSearchLimit("invalid"), 250);
assert.equal(normalizeSearchLimit(500), 500);
assert.equal(normalizeSearchLimit(HARD_MAX_SEARCH_LIMIT), HARD_MAX_SEARCH_LIMIT);
assert.equal(normalizeSearchLimit(HARD_MAX_SEARCH_LIMIT + 1), HARD_MAX_SEARCH_LIMIT);
assert.deepEqual(boundedSearchResults([], 500), { matches: [], rawCount: 0, hasMore: false });
assert.deepEqual(boundedSearchResults(null, 500), { matches: [], rawCount: 0, hasMore: false });
assert.equal(wrappedIndex(-1, 1, 0), -1);
assert.equal(wrappedIndex(0, 1, 3), 1);
assert.equal(wrappedIndex(0, -1, 3), 2);
assert.equal(wrappedIndex(499, 1, 500), 0);

for (const count of [1, 499, 500, 501, 10_000]) {
  const raw = Array.from({ length: count }, (_, index) => ({ index }));
  const bounded = boundedSearchResults(raw, 500);
  assert.equal(bounded.matches.length, Math.min(count, 500));
  assert.equal(bounded.hasMore, count > 500);
  assert.equal(bounded.rawCount, count);
  assert.deepEqual(bounded.matches.map(item => item.index), Array.from({ length: Math.min(count, 500) }, (_, index) => index));
  assert.notEqual(bounded.matches, raw);
}

console.log("PPTX bounded search helper checks passed");
