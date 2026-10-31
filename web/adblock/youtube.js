// Removes ads from YouTube's data before the page reads it. This is the same "json-prune" approach that
// uBlock Origin and Brave's YouTube rules use: the player is told there are no ads, so none are requested.
// It runs before any YouTube script on every page load. YouTube changes often, so this can stop working.
(() => {
  const AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  // Home screen tiles and banners that are ads
  const AD_ITEMS = ['adSlotRenderer', 'tvMastheadRenderer', 'promotedSparklesWebRenderer', 'compactPromotedVideoRenderer',
    'promotedVideoRenderer', 'searchPyvRenderer', 'bannerPromoRenderer', 'statementBannerRenderer'];
  const HINT = /adPlacements|playerAds|adSlots|adSlotRenderer|tvMastheadRenderer|[pP]romoted|PyvRenderer|bannerPromo/;

  const prune = (node, depth = 0) => {
    if (!node || typeof node !== 'object' || depth > 60) return node;
    if (Array.isArray(node)) {
      for (let i = node.length - 1; i >= 0; i--) {
        const v = node[i];
        if (v && typeof v === 'object' && !Array.isArray(v) && AD_ITEMS.some(k => k in v)) node.splice(i, 1);
        else prune(v, depth + 1);
      }
      return node;
    }
    for (const k of AD_KEYS) if (k in node) delete node[k];
    for (const k in node) prune(node[k], depth + 1);
    return node;
  };

  const parse = JSON.parse;
  JSON.parse = function (text, reviver) {
    const out = parse.call(this, text, reviver);
    return typeof text === 'string' && HINT.test(text) ? prune(out) : out;
  };

  const json = Response.prototype.json;
  Response.prototype.json = function () {
    const api = String(this.url || '').includes('/youtubei/');
    return json.call(this).then(out => (api ? prune(out) : out));
  };

  // A remote's or mouse's Back button makes the browser go back a page, which leaves YouTube's TV interface out of step
  // with the video (it keeps playing) and ends on a blank page. YouTube's own Back is Escape, so it gets that instead.
  const back = e => {
    e.preventDefault();
    e.stopImmediatePropagation();
    const target = document.activeElement || document.body;
    for (const type of ['keydown', 'keyup']) {
      target.dispatchEvent(new KeyboardEvent(type, {key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true}));
    }
  };
  addEventListener('keydown', e => { if (e.key === 'BrowserBack' || e.keyCode === 166) back(e); }, true);
  addEventListener('keyup', e => { if (e.key === 'BrowserBack' || e.keyCode === 166) e.preventDefault(); }, true);
  addEventListener('mouseup', e => { if (e.button === 3) back(e); }, true);
  addEventListener('mousedown', e => { if (e.button === 3) e.preventDefault(); }, true);

  // The desktop site also embeds the first video's data straight into the page
  for (const name of ['ytInitialPlayerResponse', 'ytInitialData']) {
    let value;
    try {
      Object.defineProperty(window, name, {configurable: true, get: () => value, set: v => { value = prune(v); }});
    } catch {}
  }
})();
