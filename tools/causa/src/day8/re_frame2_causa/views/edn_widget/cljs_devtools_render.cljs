(ns day8.re-frame2-causa.views.edn-widget.cljs-devtools-render
  "Adapter from `binaryage/cljs-devtools`'s formatters API onto Causa's
  pure-hiccup rendering surface (rf2-gycfj).

  ## What this gives us

  `devtools.formatters.core/header-api-call` is the same entry point
  Chrome DevTools invokes when it renders a CLJS value in the console.
  Output is JSONML — a hiccup-shaped JS-array tree carrying
  syntax-coloured spans for keywords / strings / numbers / records,
  with `[\"object\" {...}]` placeholders where the user would expand
  a nested structure.

  Adopting this engine (rather than continuing to hand-roll the leaf
  renderer Causa shipped through Phase 1) gives us the curated CLJS
  knowledge `binaryage/cljs-devtools` accrued over a decade: records
  keep their type tag, persistent collections render with their
  native delimiters, metadata is annotated, `datafy`/`nav` flows
  through when the value implements it, IRecord vs IMap is faithfully
  preserved. Same engine re-frame-10x adopted for the same reason.

  ## What this is NOT

  - **Not** a registration of Chrome's custom formatters. We never
    call `(devtools.core/install!)`; this ns only INVOKES the markup
    builders. The console's formatter story is orthogonal.
  - **Not** a replacement for Causa's diff engine. The diff machinery
    in `data-display/render` walks CLJS values pairwise, paints the
    §10.3 gutter glyphs, and threads the sticky expansion state
    through `:rf.causa/data-display-expansion`. cljs-devtools knows
    nothing about any of that — it operates on a single value.
    Callers that want diff stay on `render-tree`; cljs-devtools
    handles the LEAF rendering inside that engine and the
    `mini` one-liner.
  - **Not** a substrate adapter call. Hiccup out, no React/Reagent
    requires.

  ## API

  - `value->hiccup` — render a CLJS value as hiccup using
    cljs-devtools' formatters. Top-level entry; never expands
    `[\"object\" ...]` placeholders (those render as a `▶ {…}`
    summary). Suitable for non-collection leaves and for the
    `mini` one-liner.
  - `jsonml->hiccup` — pure JSONML walker; public for tests.

  ## Posture

  Pure hiccup; substrate-agnostic. Dev-only — Causa's preload gate
  keeps `devtools.formatters` out of production bundles per the
  bundle-isolation contract (`tools/README.md`)."
  (:require [clojure.string :as str]
            [devtools.formatters.core :as formatters]
            [devtools.prefs :as devtools-prefs]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack]]))

;; ---- prefs ---------------------------------------------------------------
;;
;; cljs-devtools ships sensible default prefs but they bake assumptions
;; about Chrome's console (border-left rules, item-style padding) that
;; clash with Causa's in-page chrome. We override the chrome bits below
;; to defer to Causa's theme tokens; the rest (token classification,
;; collection delimiters, IRecord handling, metadata rendering) we
;; inherit unchanged.
;;
;; `body-style` / `cljs-land-style` are CSS strings cljs-devtools
;; embeds into its JSONML attribute maps. Setting them to empty strings
;; removes the inline rules and lets our parent container's tokens win.

(def ^:private causa-prefs
  "Causa overrides on cljs-devtools' default prefs. Hide the index tags
  (we render flat collection content); zero out the body/cljs-land
  background+padding so the rendered JSONML inherits our parent
  container's `:bg-2` + monospace stack rather than cljs-devtools'
  console defaults; lift the depth budget so deeply-nested app-db
  values render in full."
  {:index-tag                      [:span :none-style]
   :none-style                     "display:none;"
   :item-style                     "display:inline-block;white-space:nowrap;padding:0;margin:0;"
   :body-style                     "display:inline-block;padding:0;margin:0;border:0;"
   :cljs-land-style                ""
   :initial-hierarchy-depth-budget false})

(defn- with-prefs
  "Run `f` with the Causa-overridden cljs-devtools prefs swapped in,
  restoring the previous prefs after. Mirrors re-frame-10x's
  `with-cljs-devtools-prefs` macro at runtime (no macro needed — we
  swap once at the boundary, render, swap back). Defensive try/finally
  so an exception inside the markup builder never leaves global prefs
  in the Causa-overridden state."
  [f]
  (let [previous (devtools-prefs/get-prefs)]
    (try
      (devtools-prefs/set-prefs! (merge previous causa-prefs))
      (f)
      (finally
        (devtools-prefs/set-prefs! previous)))))

;; ---- JSONML → hiccup walker ---------------------------------------------
;;
;; JSONML shape (per Chrome's Custom Object Formatters spec):
;;
;;     ["tag-name" attrs-js-obj child ... child]
;;
;; where `tag-name` is a string ("div", "span", "object", "annotation"
;; for cljs-devtools' path annotations), `attrs-js-obj` is a plain JS
;; object (usually `{"style": "color:red;..."}` or for "object" carries
;; `.object` + `.config` properties), and children are either numbers /
;; strings / nested JSONML arrays.

(defn- attrs-style->map
  "Convert a JSONML inline-style string (`\"color:red;padding:2px;\"`)
  into a hiccup `:style` map. Returns `{}` when no style is present."
  [^js attrs]
  (if-let [css (some-> attrs (.-style))]
    (->> (str/split css #";")
         (keep (fn [decl]
                 (when-let [[prop value] (when-not (str/blank? decl)
                                           (str/split decl #":" 2))]
                   (when (and prop value)
                     [(keyword (str/trim prop)) (str/trim value)]))))
         (into {}))
    {}))

(def ^:private known-tags
  "JSONML container tags cljs-devtools emits. We map each onto the
  matching hiccup keyword so the resulting tree renders verbatim."
  #{"div" "span" "ol" "li" "table" "tr" "td"})

(declare jsonml->hiccup)

(defn- render-object-placeholder
  "Render an `[\"object\" {object: v, config: c}]` JSONML node as an
  inline `▶ <header>` summary. We don't expand inline (Causa's diff
  engine + `data-display/render` own the expansion contract for
  collections in `browse`/`diff`; `value->hiccup` is used for LEAVES
  and the `mini` one-liner). The summary keeps the visual signal —
  user sees \"there's nested structure here\" — without committing
  to an expansion state."
  [obj]
  ;; cljs-devtools' object placeholder carries `.object` (the value)
  ;; and `.config`. We render a single ▶ + the header markup of the
  ;; object so the row remains informative.
  (let [^js o obj
        v    (some-> o .-object)
        cfg  (some-> o .-config)]
    [:span {:style {:font-family mono-stack
                    :color       (:text-secondary tokens)}}
     [:span {:style {:color (:text-tertiary tokens)
                     :margin-right "2px"}}
      "▸"]
     ;; Render the *header* of the nested object recursively. The
     ;; recursion terminates because cljs-devtools only emits "object"
     ;; nodes for collection-shaped values, and the header of a
     ;; collection is the one-line `{...}` summary (no further
     ;; "object" children).
     (let [inner (try
                   (formatters/header-api-call v cfg)
                   (catch :default _ nil))]
       (when (some? inner)
         [:span (jsonml->hiccup inner)]))]))

(defn jsonml->hiccup
  "Pure walker — convert JSONML to hiccup. Public for tests.

  Numbers / strings pass through verbatim (CLJS keeps them as plain
  values; hiccup renders them as text children). JS arrays whose first
  element is a known container tag become the equivalent hiccup vector.
  `object` placeholders render via `render-object-placeholder`.
  `annotation` (cljs-devtools' path-marker) wraps its children in a
  bare `[:span]`."
  [jsonml]
  (cond
    (nil? jsonml)     nil
    (number? jsonml)  jsonml
    (string? jsonml)  jsonml
    (boolean? jsonml) (str jsonml)
    ;; JSONML is a JS array of [tag attrs ...children]. ClojureScript's
    ;; `array?` predicate identifies the JS array shape.
    (array? jsonml)
    (let [tag-name   (aget jsonml 0)
          attrs      (aget jsonml 1)
          child-cnt  (.-length jsonml)]
      (cond
        (contains? known-tags tag-name)
        (let [style (attrs-style->map attrs)
              base  [(keyword tag-name)
                     {:style (assoc style :font-family mono-stack)}]]
          (loop [i 2 acc base]
            (if (>= i child-cnt)
              acc
              (recur (inc i) (conj acc (jsonml->hiccup (aget jsonml i)))))))

        (= tag-name "object")
        (render-object-placeholder attrs)

        (= tag-name "annotation")
        (loop [i 2 acc [:span {}]]
          (if (>= i child-cnt)
            acc
            (recur (inc i) (conj acc (jsonml->hiccup (aget jsonml i))))))

        :else
        ;; Unknown tag — fall back to a plain span so the markup
        ;; doesn't disappear silently.
        (loop [i 2 acc [:span {}]]
          (if (>= i child-cnt)
            acc
            (recur (inc i) (conj acc (jsonml->hiccup (aget jsonml i))))))))

    ;; Some JSONML producers wrap their content in a CLJS vector
    ;; instead of a JS array (e.g. when re-routed through edn->js).
    ;; Walk those too.
    (vector? jsonml)
    (let [[tag-name attrs & children] jsonml]
      (cond
        (contains? known-tags tag-name)
        (let [style (attrs-style->map attrs)]
          (into [(keyword tag-name)
                 {:style (assoc style :font-family mono-stack)}]
                (map jsonml->hiccup)
                children))

        (= tag-name "object") (render-object-placeholder attrs)

        :else (into [:span {}] (map jsonml->hiccup) children)))

    :else
    (try (pr-str jsonml) (catch :default _ (str jsonml)))))

;; ---- public entry --------------------------------------------------------

(defn value->hiccup
  "Render a CLJS `value` as hiccup via cljs-devtools' formatters.
  Returns hiccup — substrate-agnostic, no Reagent/React/UIx in scope.

  The output renders inline (a `[:span ...]`) — cljs-devtools' header
  markup is a one-line summary. Use for leaves, chips, and the `mini`
  one-liner; for full expandable trees, the diff/browse engine in
  `data-display/render` owns the contract."
  [value]
  (try
    (with-prefs
      (fn []
        (let [jsonml (formatters/header-api-call value nil)]
          (if (nil? jsonml)
            ;; cljs-devtools returns nil for values it considers
            ;; uninteresting (the rare scalar surfaces that aren't
            ;; cljs-typed — plain JS objects passed through, e.g.).
            ;; Fall back to pr-str so the leaf still renders.
            [:span {:style {:font-family mono-stack
                            :color       (:text-primary tokens)}}
             (try (pr-str value) (catch :default _ (str value)))]
            (jsonml->hiccup jsonml)))))
    (catch :default _
      ;; Defensive — any throw in cljs-devtools' markup builder
      ;; degrades gracefully to a pr-str leaf rather than crashing
      ;; the panel.
      [:span {:style {:font-family mono-stack
                      :color       (:text-primary tokens)}}
       (try (pr-str value) (catch :default _ (str value)))])))
