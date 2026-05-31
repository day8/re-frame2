(ns re-frame.adapter.react-test-support
  "Lightweight, dependency-free test helpers shared across the React-shaped
  adapter test surfaces (UIx, Helix) — rf2-5g21s.

  WHY A SEPARATE NS. The parameterised `re-frame.adapter.react-shared-suite`
  is the home for shared *assertions*, but it `:require`s the full
  production surface (SSR, managed-HTTP, routing, react-dom/server, …) so
  it can drive end-to-end contracts. The narrowly-scoped
  `*-source-coord-dom-elision-prod-test.cljs` twins only need ONE tiny prop
  accessor; pulling the whole suite into the `:browser-test-prod-elision`
  build (`:advanced` + goog.DEBUG=false) just to share that accessor would
  drag the suite's heavy transitive deps — and its compile warnings — into
  a build that otherwise compiles two small files. This ns holds only the
  zero-dependency helpers, so both the suite and the elision-prod twins can
  reference it without bloating either build."
  (:refer-clojure :exclude []))

(defn react-element-attr
  "Pull `attr` (a string prop name) off a React element's `.-props`, or
  nil. Defensively returns nil on every branch so callers can `nil?`-test
  the result. The single hoisted home for this accessor: the UIx and Helix
  `*-source-coord-dom-elision-prod-test.cljs` twins (and the shared suite)
  reference it instead of each carrying a byte-identical private copy. Both
  elision attrs (`data-rf2-source-coord` + `data-rf-view`) ride the same
  `interop/debug-enabled?` gate, so the one accessor covers both."
  [el attr]
  (when (and el (.-props el))
    (aget (.-props el) attr)))
