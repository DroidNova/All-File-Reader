export function chooseMostVisible(candidates) {
  let best = null;
  for (const candidate of candidates) {
    if (!best || candidate.ratio > best.ratio + 0.02 ||
        (Math.abs(candidate.ratio - best.ratio) <= 0.02 && candidate.centreDistance < best.centreDistance)) best = candidate;
  }
  return best?.ratio > 0 ? best.index : null;
}
export function wrappedIndex(current, delta, count) {
  if (count <= 0) return -1;
  return ((current < 0 ? 0 : current) + delta + count) % count;
}

export const HARD_MAX_SEARCH_LIMIT = 1000;
export const DEFAULT_SEARCH_LIMIT = 250;

export function normalizeSearchLimit(value) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) return DEFAULT_SEARCH_LIMIT;
  return Math.min(parsed, HARD_MAX_SEARCH_LIMIT);
}

export function boundedSearchResults(rawResults, maximum) {
  if (!Array.isArray(rawResults)) return { matches: [], rawCount: 0, hasMore: false };
  const limit = normalizeSearchLimit(maximum);
  const rawCount = rawResults.length;
  return { matches: rawResults.slice(0, limit), rawCount, hasMore: rawCount > limit };
}
