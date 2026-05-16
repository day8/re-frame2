(ns re-frame.story.qr
  "Local QR-code rendering for the per-variant share popover. Per Stage 6
  (rf2-zhwd) + the rf2-20w5i security audit.

  Wraps the `qrcode-generator` npm package (Kazuhiko Arase's classic
  pure-JS implementation, MIT, zero deps) to produce an inline SVG
  string. The share UI splices the SVG directly into the popover —
  no `<img>` element, no network request, no third-party endpoint.

  ## Why local

  Pre-fix (per rf2-20w5i §High) the share popover loaded a QR image
  from `https://api.qrserver.com/v1/create-qr-code/?data=<share-url>`.
  Any author-typed `:cell-overrides` rode in that URL — every value
  the user adjusted in a control reached api.qrserver.com (and any
  intermediary the dev session traversed) plus the browser's network
  history. Local generation eliminates that egress entirely.

  ## Bundle isolation

  This ns is reachable only from `re-frame.story.ui.share`, which is
  itself part of the Story UI shell. Under `:advanced` builds with
  `:rf.story/enabled?` false the UI shell is DCE'd and the
  `qrcode-generator` module is never reached from a live entry point;
  Closure's DCE then drops the wrapper. The bundle-isolation contract
  (`scripts/check-bundle-isolation.cjs`) verifies the Story sentinels
  are absent from `examples/counter`'s release bundle — which
  transitively covers this ns.

  ## API shape

  `qr-svg-string` is data → data: given a URL string and a square
  cell size in pixels, return an SVG fragment as a string. Callers
  splice the SVG into the DOM via React's `:dangerouslySetInnerHTML`
  (the SVG is constructed locally from a trusted library — no caller
  payload is interpolated as markup)."
  (:require ["qrcode-generator" :as qrcode]))

(def ^:const default-type-number
  "QR type-number passed to `qrcode-generator`. `0` means 'auto-pick the
  smallest type that fits the data at the chosen error-correction
  level'. Share URLs vary widely in length depending on the size of
  `:cell-overrides` (an EDN map can be a few bytes or a couple hundred);
  auto-pick yields the densest readable code in every case."
  0)

(def ^:const default-error-correction-level
  "Error-correction level. `\"M\"` (~15%) is the QR-spec default and
  the right tradeoff for a phone scanning a URL off a dev's monitor —
  high enough to survive a smudge or slight glare, low enough to keep
  the type-number small for long URLs."
  "M")

(def ^:const default-margin
  "Quiet-zone margin around the QR symbol, in cells. The QR spec
  recommends a minimum of 4; phone scanners sometimes tolerate less,
  but going below 4 starts to bite under poor lighting. Mirrors
  qrcode-generator's own README example."
  4)

(defn qr-svg-string
  "Build an inline SVG string encoding `text` as a QR code. Returns
  a string suitable for splicing into the DOM via React's
  `:dangerouslySetInnerHTML`.

  - `text`     — the string to encode (a share URL in practice).
  - `cell-px`  — pixel size of each QR cell. The total square edge is
                 roughly `(module-count + 2*margin) * cell-px`.

  Pure data → data; no network access, no DOM touched.

  Returns `nil` when the input exceeds QR capacity at the chosen
  error-correction level (rf2-3y7l4 — `qrcode-generator` throws on
  overlong inputs; the share popover must not crash on long
  `:cell-overrides`). Callers must handle the `nil` return as a
  degraded-state signal (e.g. render a 'URL too long for QR — copy
  link instead' panel) rather than splicing `nil` into the DOM."
  ([text]         (qr-svg-string text 4))
  ([text cell-px]
   (try
     (let [qr (qrcode default-type-number default-error-correction-level)]
       (.addData qr text)
       (.make qr)
       (.createSvgTag qr cell-px default-margin))
     (catch :default _
       nil))))
