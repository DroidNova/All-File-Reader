import { PptxViewer, RECOMMENDED_ZIP_LIMITS } from "./aiden0z-pptx-renderer.browser.es.js";
import { chooseMostVisible, wrappedIndex } from "./viewer-state.js";

const scrollHost = document.getElementById("scrollHost");
const slidesHost = document.getElementById("slides");
let viewer = null;
let destroyed = false;
let destroyCalled = false;
let observer = null;
let frameId = 0;
let searchGeneration = 0;
let activeHighlight = null;
const mountedSlides = new Map();
const visibleRatios = new Map();
let matches = [];
const state = {
  status: "loading", stage: "viewer_loaded", progress: 0, slide: 1, slides: 0,
  query: "", searching: false, matchCount: 0, activeMatchIndex: -1,
  searchErrorCode: "",
};
const snapshot = () => ({ ...state });

function setActiveSlide(zeroBasedIndex) {
  if (!Number.isInteger(zeroBasedIndex) || state.slides < 1) return;
  const oneBased = Math.min(state.slides, Math.max(1, zeroBasedIndex + 1));
  if (state.slide !== oneBased) state.slide = oneBased;
}
function chooseActiveSlide() {
  frameId = 0;
  if (destroyed || mountedSlides.size === 0) return;
  const root = scrollHost.getBoundingClientRect();
  const rootCentre = root.top + root.height / 2;
  const candidates = [];
  for (const [index, element] of mountedSlides) {
    if (!element.isConnected) continue;
    const rect = element.getBoundingClientRect();
    const visibleTop = Math.max(rect.top, root.top);
    const visibleBottom = Math.min(rect.bottom, root.bottom);
    const measuredRatio = Math.max(0, visibleBottom - visibleTop) / Math.max(1, rect.height);
    candidates.push({ index, ratio: measuredRatio, centreDistance: Math.abs((rect.top + rect.bottom) / 2 - rootCentre) });
  }
  const activeIndex = chooseMostVisible(candidates);
  if (activeIndex !== null) setActiveSlide(activeIndex);
}
function scheduleActiveSlide() {
  if (!frameId) frameId = requestAnimationFrame(chooseActiveSlide);
}
function onSlideRendered(event) {
  const { index, element } = event.detail;
  state.progress++;
  if (state.stage === "render_started") state.stage = "first_slide_rendered";
  mountedSlides.set(index, element);
  observer?.observe(element);
  scheduleActiveSlide();
}
function onSlideUnmounted(event) {
  const index = event.detail.index;
  const element = mountedSlides.get(index);
  if (element) observer?.unobserve(element);
  mountedSlides.delete(index);
  visibleRatios.delete(index);
  scheduleActiveSlide();
}
function onSlideChange(event) {
  setActiveSlide(event.detail.index);
  scheduleActiveSlide();
}
function createObserver() {
  observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      const tracked = [...mountedSlides].find(([, element]) => element === entry.target);
      if (tracked) visibleRatios.set(tracked[0], entry.intersectionRatio);
    }
    scheduleActiveSlide();
  }, { root: scrollHost, threshold: [0, 0.1, 0.25, 0.5, 0.75, 1] });
}

function clearHighlight() {
  activeHighlight?.dispose?.();
  activeHighlight = null;
  viewer?.clearSearchHighlights();
}
function resetSearch(query = "") {
  searchGeneration++;
  clearHighlight();
  matches = [];
  state.query = query;
  state.searching = false;
  state.matchCount = 0;
  state.activeMatchIndex = -1;
  state.searchErrorCode = "";
}
function waitForSlide(index, generation) {
  if (viewer?.isSlideMounted(index)) return Promise.resolve(true);
  return new Promise(resolve => {
    let settled = false;
    const finish = value => { if (settled) return; settled = true; viewer?.off("sliderendered", listener); clearTimeout(timer); resolve(value); };
    const listener = event => { if (event.detail.index === index) finish(generation === searchGeneration); };
    const timer = setTimeout(() => finish(false), 3000);
    viewer?.on("sliderendered", listener);
  });
}
async function navigateToMatch(index, generation) {
  try {
    const match = matches[index];
    if (!match || generation !== searchGeneration || destroyed) return;
    clearHighlight();
    await viewer.goToSlide(match.slideIndex, { behavior: "smooth", block: "center" });
    if (!await waitForSlide(match.slideIndex, generation) || generation !== searchGeneration || destroyed) return;
    activeHighlight = await viewer.highlightSearchResult(match);
    if (generation !== searchGeneration || destroyed) { activeHighlight?.dispose?.(); activeHighlight = null; return; }
    state.activeMatchIndex = index;
    state.searching = false;
    setActiveSlide(match.slideIndex);
    scheduleActiveSlide();
  } catch (error) {
    if (generation !== searchGeneration) return;
    state.searching = false;
    state.searchErrorCode = "SEARCH_NAVIGATION_FAILED";
    console.error(`PPTX search error code=SEARCH_NAVIGATION_FAILED name=${String(error?.name || "Error").slice(0, 60)}`);
  }
}
function search(queryValue) {
  const query = String(queryValue ?? "").trim();
  if (!query) { resetSearch(); console.debug("PPTX search queryLength=0 matches=0"); return true; }
  if (!viewer || state.status !== "ready") {
    resetSearch(query); state.searchErrorCode = "SEARCH_NOT_READY";
    console.debug(`PPTX search queryLength=${query.length} matches=0 code=SEARCH_NOT_READY`);
    return false;
  }
  resetSearch(query);
  const generation = searchGeneration;
  state.searching = true;
  try {
    matches = viewer.searchText(query, { matchCase: false });
    state.matchCount = matches.length;
    console.debug(`PPTX search queryLength=${query.length} matches=${matches.length}`);
    if (!matches.length) { state.searching = false; return true; }
    state.activeMatchIndex = 0;
    void navigateToMatch(0, generation);
    return true;
  } catch (error) {
    state.searching = false; state.searchErrorCode = "SEARCH_FAILED";
    console.error(`PPTX search error code=SEARCH_FAILED queryLength=${query.length} name=${String(error?.name || "Error").slice(0, 60)}`);
    return false;
  }
}
function moveMatch(delta) {
  if (!viewer || !matches.length) return false;
  const next = wrappedIndex(state.activeMatchIndex, delta, matches.length);
  const generation = ++searchGeneration;
  state.activeMatchIndex = next;
  state.searching = true;
  state.searchErrorCode = "";
  void navigateToMatch(next, generation);
  return true;
}

async function open() {
  try {
    state.stage = "document_fetch_started";
    const response = await fetch(`/presentation/${new URLSearchParams(location.search).get("session")}/document.pptx`, { credentials: "omit", cache: "no-store" });
    if (!response.ok) throw new Error("document");
    const arrayBuffer = await response.arrayBuffer();
    if (destroyed) return;
    state.stage = "document_fetch_complete";
    createObserver();
    viewer = new PptxViewer(slidesHost, { fitMode: "contain", pdfjs: false, zipLimits: RECOMMENDED_ZIP_LIMITS, scrollContainer: scrollHost });
    viewer.on("sliderendered", onSlideRendered).on("slideunmounted", onSlideUnmounted).on("slidechange", onSlideChange);
    state.stage = "render_started";
    await viewer.open(arrayBuffer, { renderMode: "list", lazySlides: true, lazyMedia: true, listOptions: { windowed: true, initialSlides: 4, batchSize: 4 } });
    state.slides = viewer.slideCount;
    state.slide = state.slides > 0 ? 1 : 0;
    state.status = "ready";
    state.stage = "render_complete";
    for (const index of viewer.getMountedSlides()) {
      // sliderendered normally populated the map; this fallback only schedules a recalculation.
      if (index === 0) setActiveSlide(0);
    }
    scheduleActiveSlide();
  } catch (error) {
    state.status = "error"; state.stage = "render_failed"; state.searchErrorCode = "RENDER_FAILED";
    console.error("PPTX render failed", error?.name || "Error");
  }
}
function cleanup() {
  if (destroyCalled) return true;
  destroyCalled = true; destroyed = true; searchGeneration++;
  clearHighlight();
  observer?.disconnect(); observer = null;
  scrollHost.removeEventListener("scroll", scheduleActiveSlide);
  window.removeEventListener("resize", scheduleActiveSlide);
  if (frameId) cancelAnimationFrame(frameId); frameId = 0;
  mountedSlides.clear(); visibleRatios.clear(); matches = [];
  state.slide = 1; state.slides = 0; state.query = ""; state.matchCount = 0; state.activeMatchIndex = -1;
  viewer?.off("sliderendered", onSlideRendered).off("slideunmounted", onSlideUnmounted).off("slidechange", onSlideChange);
  viewer?.destroy(); viewer = null;
  return true;
}
scrollHost.addEventListener("scroll", scheduleActiveSlide, { passive: true });
window.addEventListener("resize", scheduleActiveSlide, { passive: true });
window.pptxControl = {
  status: snapshot,
  search,
  next: () => moveMatch(1),
  previous: () => moveMatch(-1),
  clear: () => { resetSearch(); return true; },
  fit: () => { if (!viewer) return false; void viewer.setFitMode("contain").then(scheduleActiveSlide); return true; },
  destroy: cleanup,
};
open();
