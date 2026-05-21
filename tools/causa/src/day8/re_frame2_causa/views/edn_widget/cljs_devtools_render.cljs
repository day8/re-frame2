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
    cljs-devtools' formatters. Header-only entry; never expands
    `[\"object\" ...]` placeholders (those render as a `▶ {…}`
    summary). Suitable for the `mini` one-liner and inline chips
    where a single line is wanted.
  - `value->tree-hiccup` — render a CLJS value as a FULLY-EXPANDED
    cljs-devtools tree (the re-frame-10x look · type-coloured ·
    nested · indented). Recursively expands `[\"object\" ...]`
    surrogate references up to a depth budget; deeper levels
    degrade to the collapsed `▶ {…}` summary so a pathological
    app-db can't blow the call stack. This is the entry the
    current-state `browse` path uses for collections.
  - `jsonml->hiccup` — pure JSONML walker; public for tests. The
    arity that takes an opts map drives recursive expansion.

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

(def ^:private causa-expanded-prefs
  "Extra prefs for the FULLY-EXPANDED current-state tree
  (`value->tree-hiccup`, rf2-dmso5). cljs-devtools defaults inline only
  `:max-print-level 2` / `:body-line-max-print-level 3` levels and
  `:max-header-elements 5` entries before degrading a value to a
  collapsed `▸ {…}` reference — that's the one-line summary look. For
  Causa's current-state surfaces we want the re-frame-10x
  fully-expanded look, so the tree renderer lifts the inline budgets
  generously (the per-leaf header preview then inlines the whole
  structure for ordinary app-db depths) and `value->tree-hiccup`'s
  body-recursion picks up anything past them. `value->hiccup` / `mini`
  do NOT use these — they stay one-line."
  (merge causa-prefs
         {:max-print-level           10
          :body-line-max-print-level 10
          :max-header-elements       100
          :max-number-body-items     1000}))

(defn- with-prefs*
  "Run `f` with `prefs` merged over the live cljs-devtools prefs,
  restoring the previous prefs after. Defensive try/finally so an
  exception inside the markup builder never leaves global prefs in the
  Causa-overridden state."
  [prefs f]
  (let [previous (devtools-prefs/get-prefs)]
    (try
      (devtools-prefs/set-prefs! (merge previous prefs))
      (f)
      (finally
        (devtools-prefs/set-prefs! previous)))))

(defn- with-prefs
  "One-line-summary prefs (the shallow `causa-prefs`). Used by
  `value->hiccup` + `mini`."
  [f]
  (with-prefs* causa-prefs f))

(defn- with-expanded-prefs
  "Fully-expanded prefs (`causa-expanded-prefs`). Used by
  `value->tree-hiccup` for the re-frame-10x current-state look."
  [f]
  (with-prefs* causa-expanded-prefs f))

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

(def ^:private default-max-depth
  "How deep `value->tree-hiccup` recursively expands `object`
  surrogate references before degrading to the collapsed `▶ {…}`
  summary. App-db is rarely deeper than this; the cap is a stack-guard
  against pathological / cyclic structures more than a UI choice. The
  re-frame-10x look reads as fully-expanded for ordinary data."
  12)

(declare jsonml->hiccup)

(defn- render-object-summary
  "Render an `[\"object\" {object: v, config: c}]` surrogate as an
  inline `▸ <header>` ONE-LINE summary (no body expansion). Used as
  the leaf-fallback in `value->hiccup` and as the depth-budget cap in
  `value->tree-hiccup`: the user still sees \"there is nested structure
  here\" via the collection's one-line `{…}` header without the tree
  committing to expand it."
  [obj opts]
  (let [^js o obj
        v    (some-> o .-object)
        cfg  (some-> o .-config)]
    [:span {:style {:font-family mono-stack
                    :color       (:text-secondary tokens)}}
     [:span {:style {:color (:text-tertiary tokens)
                     :margin-right "2px"}}
      "▸"]
     ;; Render the *header* of the nested surrogate. The header of a
     ;; collection is its one-line `{...}` summary, so this terminates.
     (let [inner (try
                   (formatters/header-api-call v cfg)
                   (catch :default _ nil))]
       (when (some? inner)
         [:span (jsonml->hiccup inner opts)]))]))

(defn- render-object-expanded
  "Recursively EXPAND an `[\"object\" {object: surrogate}]` reference:
  render the surrogate's header inline, then — when it has a body and
  we're within the depth budget — render that body indented underneath.
  This is what produces the re-frame-10x nested-tree look for
  collections. Beyond the depth budget the reference degrades to the
  one-line `render-object-summary`.

  `binaryage/cljs-devtools` models a value's expandable rows as a
  surrogate whose `body-api-call` returns an `[\"ol\" …]` of `[\"li\" …]`
  lines; each line embeds the child values as further `object`
  references — so recursing on `body-api-call` walks the whole
  structure."
  [obj {:keys [depth max-depth] :as opts}]
  (let [^js o     obj
        surrogate (some-> o .-object)
        cfg       (some-> o .-config)
        header    (try (formatters/header-api-call surrogate cfg)
                       (catch :default _ nil))
        has-body? (try (boolean (formatters/has-body-api-call surrogate cfg))
                       (catch :default _ false))]
    (cond
      ;; Couldn't even get a header — fall back to the inline summary.
      (nil? header)
      (render-object-summary obj opts)

      ;; Leaf-shaped surrogate (no expandable body) — header only.
      (not has-body?)
      [:span {:style {:font-family mono-stack}}
       (jsonml->hiccup header opts)]

      ;; Depth budget hit — keep the one-line summary so we never
      ;; recurse without bound on a deep / cyclic structure.
      (>= depth max-depth)
      (render-object-summary obj opts)

      ;; Within budget + has a body — expand: header line, then the
      ;; body's rows indented one level deeper.
      :else
      (let [body      (try (formatters/body-api-call surrogate cfg)
                           (catch :default _ nil))
            next-opts (assoc opts :depth (inc depth))]
        [:span {:style {:font-family mono-stack}}
         [:span {:style {:display "block"}}
          (jsonml->hiccup header opts)]
         (when (some? body)
           [:div {:style {:padding-left "14px"
                          :border-left  (str "1px solid " (:border-subtle tokens))
                          :margin-left  "2px"}}
            (jsonml->hiccup body next-opts)])]))))

(defn- render-object
  "Route an `object` reference to the expanding or summary renderer
  based on `opts`. `:expand? true` (set by `value->tree-hiccup`) walks
  the surrogate's body; otherwise (header-only `value->hiccup`,
  `mini`) it renders the one-line summary."
  [obj opts]
  (if (:expand? opts)
    (render-object-expanded obj opts)
    (render-object-summary obj opts)))

(defn jsonml->hiccup
  "Pure walker — convert JSONML to hiccup. Public for tests.

  Numbers / strings pass through verbatim (CLJS keeps them as plain
  values; hiccup renders them as text children). JS arrays whose first
  element is a known container tag become the equivalent hiccup vector.
  `object` references route through `render-object` — expanded into a
  nested tree when `opts` carries `:expand? true`, collapsed to a
  one-line `▸ {…}` summary otherwise. `annotation` (cljs-devtools'
  path-marker) wraps its children in a bare `[:span]`.

  Single-arg arity renders header-only (no expansion). The opts arity
  threads `{:expand? :depth :max-depth}` so a caller can drive the
  recursive tree walk."
  ([jsonml] (jsonml->hiccup jsonml {:expand? false :depth 0 :max-depth 0}))
  ([jsonml opts]
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
               (recur (inc i) (conj acc (jsonml->hiccup (aget jsonml i) opts))))))

         (= tag-name "object")
         (render-object attrs opts)

         (= tag-name "annotation")
         (loop [i 2 acc [:span {}]]
           (if (>= i child-cnt)
             acc
             (recur (inc i) (conj acc (jsonml->hiccup (aget jsonml i) opts)))))

         :else
         ;; Unknown tag — fall back to a plain span so the markup
         ;; doesn't disappear silently.
         (loop [i 2 acc [:span {}]]
           (if (>= i child-cnt)
             acc
             (recur (inc i) (conj acc (jsonml->hiccup (aget jsonml i) opts)))))))

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
                 (map #(jsonml->hiccup % opts))
                 children))

         (= tag-name "object") (render-object attrs opts)

         :else (into [:span {}] (map #(jsonml->hiccup % opts)) children)))

     :else
     (try (pr-str jsonml) (catch :default _ (str jsonml))))))

;; ---- public entry --------------------------------------------------------

(defn- pr-str-fallback
  "Defensive degrade — a plain pr-str span for when cljs-devtools
  refuses a value or throws inside its markup builder."
  [value]
  [:span {:style {:font-family mono-stack
                  :color       (:text-primary tokens)}}
   (try (pr-str value) (catch :default _ (str value)))])

(defn value->hiccup
  "Render a CLJS `value` as hiccup via cljs-devtools' formatters,
  HEADER-ONLY. Returns hiccup — substrate-agnostic, no Reagent/React/
  UIx in scope.

  The output renders inline (a `[:span ...]`) — cljs-devtools' header
  markup is a one-line summary; nested collections show as a `▸ {…}`
  summary, not expanded. Use for the `mini` one-liner and inline chips
  where a single line is wanted. For the full expandable current-state
  tree (App-DB / Trace payloads / Event :fx / coeffects) use
  `value->tree-hiccup`."
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
            (pr-str-fallback value)
            (jsonml->hiccup jsonml)))))
    (catch :default _
      ;; Defensive — any throw in cljs-devtools' markup builder
      ;; degrades gracefully to a pr-str leaf rather than crashing
      ;; the panel.
      (pr-str-fallback value))))

(defn value->tree-hiccup
  "Render a CLJS `value` as a FULLY-EXPANDED cljs-devtools tree — the
  re-frame-10x current-state look: type-coloured leaves, native
  collection delimiters, records keeping their type tag, nested
  structure expanded and indented. Returns hiccup; substrate-agnostic.

  Implementation: take the value's header (the one-line `{…}` / `[…]`
  summary) and, when the value has an expandable body, render that body
  underneath — recursively expanding each nested `object` surrogate
  reference up to `max-depth` (defaults to `default-max-depth`). A
  scalar value (no body) renders exactly like `value->hiccup`.

  This is the entry the current-state `browse` path uses for
  collections; the diff path (Event panel `:db`) stays on the
  home-grown smallest-diff engine in `data-display/render`."
  ([value] (value->tree-hiccup value default-max-depth))
  ([value max-depth]
   (try
     (with-expanded-prefs
       (fn []
         (let [header    (formatters/header-api-call value nil)
               has-body? (boolean (formatters/has-body-api-call value nil))]
           (cond
             (nil? header)
             (pr-str-fallback value)

             ;; Scalar / leaf — no body to expand. Render the header.
             (not has-body?)
             [:span {:style {:font-family mono-stack}}
              (jsonml->hiccup header {:expand? false :depth 0 :max-depth max-depth})]

             ;; Collection — render the header line, then the expanded
             ;; body indented underneath.
             :else
             (let [body (formatters/body-api-call value nil)
                   opts {:expand? true :depth 1 :max-depth max-depth}]
               [:span {:style {:font-family mono-stack}}
                [:span {:style {:display "block"}}
                 (jsonml->hiccup header opts)]
                (when (some? body)
                  [:div {:style {:padding-left "14px"
                                 :border-left  (str "1px solid " (:border-subtle tokens))
                                 :margin-left  "2px"}}
                   (jsonml->hiccup body opts)])])))))
     (catch :default _
       (pr-str-fallback value)))))
