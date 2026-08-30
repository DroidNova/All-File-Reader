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
