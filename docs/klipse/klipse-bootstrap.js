/*
 * Klipse bootstrap — instant-navigation-safe loader (rf2-zr5r5).
 *
 * Background: the live-code ClojureScript page (docs/cljs/index.md) renders
 * ```klipse fences as <pre class="language-klipse">…</pre> and turns each into
 * an in-browser CodeMirror cell evaluated by the (vendored, ~7 MB) Klipse
 * plugin. Klipse used to be wired by an inline <script> at the bottom of that
 * page. With `navigation.instant` enabled, Material swaps page <main> content
 * via fetch without a full reload — and it does NOT re-execute inline <script>
 * tags in page content. So an inline-script bootstrap silently stops working
 * the moment a reader reaches the cljs page via an in-site (instant) link.
 *
 * Fix: move the bootstrap into `extra_javascript`. Material RE-RUNS every
 * `extra_javascript` module on each instant navigation, so this file fires on
 * the initial load and on every subsequent instant page swap. It is:
 *
 *   - Guarded: it does nothing on pages with no `.language-klipse` cells, so
 *     the heavy plugin loads ONLY on the cljs page (the deliberate decision
 *     from the mkdocs audit — Klipse must NOT be site-wide).
 *   - Idempotent: it injects the CSS, the klipse_settings, and the plugin at
 *     most once per full document; on later instant navs back to the cljs page
 *     it asks the already-loaded plugin to re-scan the freshly swapped DOM.
 */
(function () {
  "use strict";

  // Resolve sibling-asset URLs relative to THIS file's own location.
  // MkDocs emits no <base> tag and pages reference assets with relative
  // paths, so we can't rely on a site root. But this bootstrap and the
  // Klipse assets (codemirror.css, klipse_plugin.js) all live together in
  // <root>/klipse/, and Material loads this script with a correctly-pathed
  // <script src>. `document.currentScript` is the bootstrap's own element
  // during initial parse — capture its absolute URL once, then resolve
  // siblings against it. Works on GitHub Pages' /re-frame2/ sub-path and at
  // the domain root alike.
  var selfUrl = (document.currentScript && document.currentScript.src) ||
    (function () {
      var scripts = document.getElementsByTagName('script');
      for (var i = scripts.length - 1; i >= 0; i--) {
        if (scripts[i].src && scripts[i].src.indexOf('klipse-bootstrap.js') !== -1) {
          return scripts[i].src;
        }
      }
      return '';
    })();

  function asset(path) {
    // selfUrl ends in ".../klipse/klipse-bootstrap.js"; siblings share its
    // directory. `new URL(name, selfUrl)` resolves name against that dir.
    return new URL(path, selfUrl).href;
  }

  function hasCells() {
    return document.querySelector('.language-klipse') !== null;
  }

  function injectOnce(id, build) {
    if (document.getElementById(id)) return false;
    build(id);
    return true;
  }

  function loadKlipse() {
    if (!hasCells()) return;

    // 1. CodeMirror stylesheet for the live cells (once per document).
    injectOnce('klipse-codemirror-css', function (id) {
      var link = document.createElement('link');
      link.id = id;
      link.rel = 'stylesheet';
      link.type = 'text/css';
      link.href = asset('codemirror.css');
      document.head.appendChild(link);
    });

    // 2. Klipse settings — must exist before the plugin script runs.
    //    `selector` keys off the .language-klipse class pymdownx.superfences
    //    emits; Klipse walks <pre> down to its <code> child and replaces it
    //    with a CodeMirror editor evaluating the cell as ClojureScript.
    if (!window.klipse_settings) {
      window.klipse_settings = {
        selector: '.language-klipse',
        codemirror_options_in: {
          lineWrapping: true,
          autoCloseBrackets: true,
          matchBrackets: true
        },
        codemirror_options_out: {
          lineWrapping: true
        }
      };
    }

    // 3. The plugin itself. First arrival on the cljs page loads the (heavy)
    //    vendored plugin, which scans the DOM and mounts the cells. On a
    //    later instant nav back to the page the plugin is already present, so
    //    we just ask it to re-scan the freshly swapped content.
    var injected = injectOnce('klipse-plugin-js', function (id) {
      var script = document.createElement('script');
      script.id = id;
      script.src = asset('klipse_plugin.js');
      document.body.appendChild(script);
    });

    if (!injected && window.klipse && typeof window.klipse.plugin === 'function') {
      // Already loaded earlier this session — re-mount cells in the new DOM.
      window.klipse.plugin(window.klipse_settings);
    }
  }

  // Material exposes the `document$` observable, which emits on the initial
  // load AND after every instant navigation. Subscribe ONCE when it's
  // available (Material may re-execute this module on instant nav, so a
  // global guard prevents stacking duplicate subscribers); otherwise fall
  // back to a one-shot DOMContentLoaded (the no-instant case).
  if (window.document$ && typeof window.document$.subscribe === 'function') {
    if (!window.__klipseBootstrapSubscribed) {
      window.__klipseBootstrapSubscribed = true;
      window.document$.subscribe(loadKlipse);
    }
  } else if (document.readyState !== 'loading') {
    loadKlipse();
  } else {
    document.addEventListener('DOMContentLoaded', loadKlipse);
  }
})();
