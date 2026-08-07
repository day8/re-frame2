(ns re-frame.adapter.react-test-support
  "Lightweight, dependency-free test helpers shared across the React-shaped
  adapter test surfaces (UIx) — rf2-5g21s.

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
  (:require [clojure.string :as str]))

(defn react-element-attr
  "Pull `attr` (a string prop name) off a React element's `.-props`, or
  nil. Defensively returns nil on every branch so callers can `nil?`-test
  the result. The single hoisted home for this accessor: the React-hook
  `*-source-coord-dom-elision-prod-test.cljs` twins (and the shared suite)
  reference it instead of each carrying a byte-identical private copy. Both
  elision attrs (`data-rf2-source-coord` + `data-rf-view`) ride the same
  `interop/debug-enabled?` gate, so the one accessor covers both."
  [el attr]
  (when (and el (.-props el))
    (aget (.-props el) attr)))

;; ---- the DevTools-visible component name (rf2-976bw) -----------------------
;;
;; Spec 006 §React DevTools support item 1 is a claim about what a developer
;; READS in the component tree, and a `.-displayName` read off the pre-mount fn
;; is not that: React resolves a component's name off the committed fiber's
;; `type`, and each substrate reaches that type by its own machinery (Reagent
;; builds a class from the fn; the React-hook spine uses the fn directly). The
;; two helpers below read the name the way React DevTools does — from the
;; mounted fiber — so the assertion covers the machinery, not the input to it.

(defn- fiber-of
  "The React fiber React attached to `dom-node`, or nil. React stamps it as an
  own property named `__reactFiber$<random>` (React 17+; `__reactInternalInstance$`
  on 16), which is exactly how React DevTools finds a node's fiber."
  [dom-node]
  (when dom-node
    (some (fn [k]
            (when (or (str/starts-with? k "__reactFiber$")
                      (str/starts-with? k "__reactInternalInstance$"))
              (aget dom-node k)))
          (array-seq (js/Object.keys dom-node)))))

(defn devtools-names-above
  "The component names React DevTools would show for the composite fibers at
  and above `dom-node`, innermost first. Host fibers (a `\"div\"` type) and
  unnamed types contribute nothing. Returns a vector; empty when the node was
  never mounted by React."
  [dom-node]
  (loop [f (fiber-of dom-node), acc []]
    (if (nil? f)
      acc
      (let [t  (.-type ^js f)
            nm (when (and (some? t) (not (string? t)))
                 (or (.-displayName ^js t) (.-name ^js t)))]
        (recur (.-return ^js f)
               (cond-> acc (not (nil? nm)) (conj nm)))))))
