(ns day8.re-frame2-xray.views.edn-widget.cljs-devtools-render
  "Adapter from `binaryage/cljs-devtools`'s formatters API onto Xray's
  pure-hiccup rendering surface (rf2-gycfj).

  ## What this gives us

  `devtools.formatters.core/header-api-call` is the same entry point
  Chrome DevTools invokes when it renders a CLJS value in the console.
  Output is JSONML — a hiccup-shaped JS-array tree carrying
  syntax-coloured spans for keywords / strings / numbers / records,
  with `[\"object\" {...}]` placeholders where the user would expand
  a nested structure.

  Adopting this engine (rather than continuing to hand-roll the leaf
  renderer Xray shipped through Phase 1) gives us the curated CLJS
  knowledge `binaryage/cljs-devtools` accrued over a decade: records
  keep their type tag, persistent collections render with their
  native delimiters, metadata is annotated, `datafy`/`nav` flows
  through when the value implements it, IRecord vs IMap is faithfully
  preserved. Same engine re-frame-10x adopted for the same reason.

  ## What this is NOT

  - **Not** a registration of Chrome's custom formatters. We never
    call `(devtools.core/install!)`; this ns only INVOKES the markup
    builders. The console's formatter story is orthogonal.
  - **Not** a replacement for Xray's diff engine. The diff machinery
    in `data-display/render` walks CLJS values pairwise, paints the
    §10.3 gutter glyphs, and threads the sticky expansion state
    through `:rf.xray/data-display-expansion`. cljs-devtools knows
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
  - `value->tree-hiccup` — render a CLJS value as the re-frame-10x
    nested-tree current-state look (`{ :counter ▸ {3 entries} }`)
    with INTERACTIVE click-to-toggle per `object` surrogate
    (rf2-dw8n7). Each collection node renders a `▸` (collapsed) /
    `▾` (expanded) glyph wrapped in a `[:span]` that dispatches
    `:rf.xray/data-display-toggle-node` on click — the same sticky-
    expansion contract `data-display/render` uses, so an operator's
    drill-downs persist through re-renders. Default expansion
    follows §10.4's depth+size heuristic (`default-depth` defaults
    to 1: root expanded, nested collections collapsed). Past the
    hard `max-depth` stack-guard the node degrades to the one-line
    `▸ {…}` summary so a pathological / cyclic structure can't blow
    the call stack.
  - `jsonml->hiccup` — pure JSONML walker; public for tests. The
    arity that takes an opts map drives recursive expansion + the
    interactive toggle wiring.

  ## Posture

  Pure hiccup; substrate-agnostic. Dev-only — Xray's preload gate
  keeps `devtools.formatters` out of production bundles per the
  bundle-isolation contract (`tools/README.md`)."
  (:require [clojure.string :as str]
            [devtools.formatters.core :as formatters]
            [devtools.prefs :as devtools-prefs]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.data-display.render :as data-display]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack]]))

;; ---- prefs ---------------------------------------------------------------
;;
;; cljs-devtools ships sensible default prefs but they bake assumptions
;; about Chrome's console (border-left rules, item-style padding) that
;; clash with Xray's in-page chrome. We override the chrome bits below
;; to defer to Xray's theme tokens; the rest (token classification,
;; collection delimiters, IRecord handling, metadata rendering) we
;; inherit unchanged.
;;
;; `body-style` / `cljs-land-style` are CSS strings cljs-devtools
;; embeds into its JSONML attribute maps. Setting them to empty strings
;; removes the inline rules and lets our parent container's tokens win.

(def ^:private xray-prefs
  "Xray overrides on cljs-devtools' default prefs. Hide the index tags
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

(def ^:private xray-expanded-prefs
  "Prefs for the interactive tree (`value->tree-hiccup`, rf2-dw8n7).
  We FORCE cljs-devtools to emit `object` surrogate references for
  every nested collection rather than inlining them in the header.

  - `:max-print-level 1` clamps the header inline-depth to a single
    level, so a map like `{:outer {:inner 1}}` renders as
    `{ :outer ▸ {…} }` — the inner map appears as a surrogate ref
    that the walker can wrap in a click-to-toggle triangle. With the
    cljs-devtools default of 2 (or our previously-lifted 10), the
    whole tree inlines as one uninterruptible line and the toggle UI
    has nothing to attach to.
  - `:body-line-max-print-level 1` applies the same clamp to the
    expanded body rows — each LI emits its value as a surrogate.
  - `:max-header-elements` / `:max-number-body-items` stay generous
    so a wide map doesn't truncate visible keys mid-list.
  - The shallow chrome overrides (`:item-style`, `:body-style`, …)
    carry through from `xray-prefs`.

  `value->hiccup` / `mini` use the shallow `xray-prefs` instead so
  they keep their one-line semantics for inline chips."
  (merge xray-prefs
         {:max-print-level           1
          :body-line-max-print-level 1
          :max-header-elements       100
          :max-number-body-items     1000}))

(defn- with-prefs*
  "Run `f` with `prefs` merged over the live cljs-devtools prefs,
  restoring the previous prefs after. Defensive try/finally so an
  exception inside the markup builder never leaves global prefs in the
  Xray-overridden state."
  [prefs f]
  (let [previous (devtools-prefs/get-prefs)]
    (try
      (devtools-prefs/set-prefs! (merge previous prefs))
      (f)
      (finally
        (devtools-prefs/set-prefs! previous)))))

(defn- with-prefs
  "One-line-summary prefs (the shallow `xray-prefs`). Used by
  `value->hiccup` + `mini`."
  [f]
  (with-prefs* xray-prefs f))

(defn- with-expanded-prefs
  "Fully-expanded prefs (`xray-expanded-prefs`). Used by
  `value->tree-hiccup` for the re-frame-10x current-state look."
  [f]
  (with-prefs* xray-expanded-prefs f))

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
  "Hard stack-guard for `value->tree-hiccup`'s body recursion. Beyond
  this depth every nested `object` surrogate degrades to the collapsed
  `▸ {…}` summary regardless of the operator's per-path expansion
  state — a defence against cyclic / pathological structures, not a
  UX choice. The §10.4 default-expansion heuristic (default-depth 1)
  is the UX bound; this cap is a safety floor."
  16)

(def ^:private default-default-depth
  "§10.4 default expansion depth for `value->tree-hiccup` when the
  caller doesn't override. `1` = the root collection's children render
  as collapsed `▸ {…}` summaries by default; the operator clicks a
  triangle to expand. Matches re-frame-10x's app-db panel default
  (rf2-dw8n7 acceptance)."
  1)

(declare jsonml->hiccup body-child-count)

(defn- toggle-style
  "Inline style for the click-to-toggle triangle wrapper on an
  interactive `object` surrogate. Pointer cursor + accessible focus
  outline (rf2-dw8n7)."
  []
  {:cursor       "pointer"
   :user-select  "none"
   :display      "inline-block"
   :margin-right "2px"
   :color        (:text-tertiary tokens)})

(defn- render-object-summary
  "Render an `[\"object\" {object: v, config: c}]` surrogate as an
  inline `▸ <header>` ONE-LINE summary (no body expansion). Used as
  the leaf-fallback in `value->hiccup` — non-interactive, no click
  handler. For the interactive `value->tree-hiccup` path the wrapper
  is added by `render-object-interactive`; this function still emits
  the header markup so both paths share the same inline shape."
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

(defn- render-object-interactive
  "Render an `[\"object\" {object: surrogate}]` reference as the
  re-frame-10x interactive click-to-toggle node (rf2-dw8n7).

  The triangle is a `[:span]` with `:on-click` dispatching
  `:rf.xray/data-display-toggle-node` against this surrogate's
  walker-path. When EXPANDED the triangle is followed by cljs-devtools'
  BODY (the recursive child rows). When COLLAPSED the triangle is
  followed by cljs-devtools' HEADER (the one-line `{…}` summary). Body
  OR header — never both (rf2-oswhk). re-frame-10x's
  `data-structure` / `data-structure-with-path-annotations` use the
  same shape — see `src/day8/re_frame_10x/components/cljs_devtools.cljs`.

  Defaults: §10.4 heuristic — depth ≤ default-depth → expanded; below
  the body's child-count threshold at the boundary → still expanded;
  beyond → collapsed. The operator's sticky override (recorded in
  `:rf.xray/data-display-expansion`) always wins.

  Hard stack-guard at `max-depth`: deeper than that, the node degrades
  to the inline summary regardless of the operator's choice — a
  defence against cyclic / pathological structures.

  `cljs-devtools` models a value's expandable rows as a surrogate whose
  `body-api-call` returns an `[\"ol\" ...]` of `[\"li\" ...]` lines;
  each line embeds child values as further `object` references — so
  recursing on `body-api-call` walks the whole structure with the
  type-aware colour palette intact (keyword magenta, number blue,
  string red, nil gray, boolean teal — cljs-devtools' canonical
  Chrome-console scheme)."
  [obj {:keys [depth max-depth panel-id render-id path
               expansion-map default-depth]
        :as opts}]
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

      ;; Leaf-shaped surrogate (no expandable body) — header only,
      ;; no triangle. cljs-devtools uses surrogates for some scalar
      ;; shapes too (e.g. native JS objects, certain records) — those
      ;; have nothing to expand so the toggle UI would be misleading.
      (not has-body?)
      [:span {:style {:font-family mono-stack}}
       (jsonml->hiccup header opts)]

      ;; Hard stack-guard — beyond max-depth degrade to summary
      ;; regardless of operator override. Defence against cyclic /
      ;; pathological structures.
      (>= depth max-depth)
      (render-object-summary obj opts)

      :else
      (let [body         (try (formatters/body-api-call surrogate cfg)
                              (catch :default _ nil))
            child-cnt    (body-child-count body)
            default?     (data-display/default-expanded?
                           {:depth         depth
                            :child-count   child-cnt
                            :default-depth (or default-depth
                                               default-default-depth)})
            expanded?    (if (and panel-id render-id)
                           (data-display/resolve-expanded?
                             expansion-map panel-id render-id path default?)
                           default?)
            glyph        (if expanded? "▾" "▸") ; ▾ / ▸
            ;; Stable dispatch — `:on-click` fires after React pops the
            ;; frame context, so the dispatch envelope carries the Xray
            ;; frame explicitly (matches the segment-inspector + copy
            ;; affordance pattern in `widget.cljs`).
            toggle-fn    (when (and panel-id render-id)
                           (fn [^js e]
                             (when e
                               (.preventDefault e)
                               (.stopPropagation e))
                             (rf/dispatch
                               [:rf.xray/data-display-toggle-node
                                panel-id render-id path]
                               {:frame :rf/xray})))
            key-fn       (when toggle-fn
                           (fn [^js e]
                             (let [k (.-key e)]
                               (when (or (= k "Enter") (= k " "))
                                 (.preventDefault e)
                                 (.stopPropagation e)
                                 (toggle-fn e)))))
            testid       (when (and panel-id render-id)
                           (str "rf-xray-edn-widget-toggle-"
                                (name panel-id) "-"
                                render-id "-"
                                (str/join "/" (map str path))))
            triangle     (if toggle-fn
                           [:span (cond-> {:on-click   toggle-fn
                                           :on-key-down key-fn
                                           :role       "button"
                                           :tab-index  0
                                           :aria-label (str (if expanded? "Collapse" "Expand")
                                                            " node")
                                           :style      (toggle-style)}
                                    testid (assoc :data-testid testid))
                            glyph]
                           [:span {:style {:color (:text-tertiary tokens)
                                           :margin-right "2px"}}
                            glyph])
            ;; Surrogates inside the surrogate's body represent CHILDREN
            ;; of this node — they live at depth+1 in the value tree, so
            ;; the walker descends with the bumped depth. The path is
            ;; reset for the inner walk: the body's own li-indices form
            ;; the path segments inside, on top of THIS surrogate's path
            ;; already in `:path`. The walker's :path → child-index
            ;; tracking re-establishes uniqueness as we descend.
            inner-opts   (assoc opts :depth (inc depth))]
        (if (and expanded? (some? body))
          ;; Expanded: triangle + BODY. cljs-devtools' body emits
          ;; `[:standard-ol [:standard-li <name-tag> <value-markup>]
          ;;                [:standard-li <name-tag> <value-markup>] ...]`
          ;; — one row per child, with the value-markup carrying the
          ;; per-token colour palette via the templating system. Per
          ;; re-frame-10x: we render the body INSTEAD of the header
          ;; under the triangle, so the visible content is the deeper
          ;; structure rather than the redundant `{…}` summary.
          [:span {:style {:font-family mono-stack}}
           triangle
           (jsonml->hiccup body inner-opts)]
          ;; Collapsed: triangle + one-line header summary
          ;; (`{…}` / `[…]` etc., emitted by `header-api-call` because
          ;; `:max-print-level 1` clamps the inline depth).
          [:span {:style {:font-family mono-stack
                          :color       (:text-secondary tokens)}}
           triangle
           (jsonml->hiccup header inner-opts)])))))

(defn- render-object
  "Route an `object` reference to the interactive expanding renderer
  (when `opts` carries `:expand? true` — the `value->tree-hiccup`
  path) or the static one-line summary (header-only `value->hiccup`,
  `mini`)."
  [obj opts]
  (if (:expand? opts)
    (render-object-interactive obj opts)
    (render-object-summary obj opts)))

(defn body-child-count
  "Count the `li` rows in a cljs-devtools body. cljs-devtools wraps
  the row list in `[\"span\" attrs [\"ol\" attrs li li ...]]` — so
  this fn descends until it finds the `ol` (or `table`) and returns
  the number of row children inside it. Used to drive the §10.4
  default-expansion heuristic at the boundary; returns 0 when body
  is nil or the shape isn't recognised."
  [body]
  (letfn [(child-of [node idx]
            (cond
              (and (array? node) (> (.-length node) idx)) (aget node idx)
              (and (vector? node) (> (count node) idx)) (nth node idx)
              :else nil))
          (tag-of [node]
            (cond
              (array? node) (when (pos? (.-length node)) (aget node 0))
              (vector? node) (first node)
              :else nil))
          (length-of [node]
            (cond
              (array? node) (.-length node)
              (vector? node) (count node)
              :else 0))]
    (loop [n body depth-budget 4]
      (cond
        (nil? n) 0
        (neg? depth-budget) 0
        :else
        (let [tag (tag-of n)]
          (case tag
            ("ol" "ul" "table")
            (max 0 (- (length-of n) 2))
            ;; descend into the first child (skip tag + attrs)
            (let [c (child-of n 2)]
              (if c
                (recur c (dec depth-budget))
                0))))))))

(defn- child-path
  "Compose the walker child path. Each li-index inside a body becomes
  a path segment; nested object refs inside a li inherit that segment
  via the body-recursion step. Pure data — stable across re-renders."
  [path child-index]
  (conj (vec path) child-index))

(defn jsonml->hiccup
  "Pure walker — convert JSONML to hiccup. Public for tests.

  Numbers / strings pass through verbatim (CLJS keeps them as plain
  values; hiccup renders them as text children). JS arrays whose first
  element is a known container tag become the equivalent hiccup vector.
  `object` references route through `render-object` — the interactive
  click-to-toggle wrapper when `opts` carries `:expand? true`,
  collapsed to a one-line `▸ {…}` summary otherwise. `annotation`
  (cljs-devtools' path-marker) wraps its children in a bare `[:span]`.

  Every child gets a per-position path segment so each nested `object`
  reference resolves to a UNIQUE `[panel-id render-id path]` key for
  the sticky-expansion map (rf2-oswhk). Pre-fix the walker only added
  path segments inside `\"ol\"` containers — so the n sibling
  surrogates inside a single map's header all hashed to the same key
  and a click on one toggled them all. Now every descent appends a
  positional index (the child's index in its parent JSONML array),
  mirroring re-frame-10x's `(conj indexed-path i)` walk.

  Single-arg arity renders header-only (no expansion). The opts arity
  threads `{:expand? :depth :max-depth :panel-id :render-id
  :expansion-map :path :default-depth}` so a caller can drive the
  recursive tree walk + the interactive toggle wiring."
  ([jsonml] (jsonml->hiccup jsonml {:expand? false :depth 0 :max-depth 0
                                    :path []}))
  ([jsonml opts]
   (let [walk-known
         (fn walk-known [tag-name attrs children-getter child-cnt]
           ;; `children-getter` is a fn that returns the i-th child (JS
           ;; array `aget` or CLJS-vector `nth` — abstracted so the same
           ;; loop handles both wrappers). Path segment per child so
           ;; sibling surrogates resolve to distinct expansion keys.
           (let [style (attrs-style->map attrs)
                 base  [(keyword tag-name)
                        {:style (assoc style :font-family mono-stack)}]]
             (loop [i 2 acc base child-idx 0]
               (if (>= i child-cnt)
                 acc
                 (let [c          (children-getter i)
                       child-opts (assoc opts :path
                                         (child-path (:path opts) child-idx))]
                   (recur (inc i)
                          (conj acc (jsonml->hiccup c child-opts))
                          (inc child-idx)))))))
         walk-fallback
         (fn walk-fallback [children-getter child-cnt]
           ;; Annotation + unknown tags: a bare `[:span]` wrapper, with
           ;; path-segment indexing matching the known-tag walk.
           (loop [i 2 acc [:span {}] child-idx 0]
             (if (>= i child-cnt)
               acc
               (let [c          (children-getter i)
                     child-opts (assoc opts :path
                                       (child-path (:path opts) child-idx))]
                 (recur (inc i)
                        (conj acc (jsonml->hiccup c child-opts))
                        (inc child-idx))))))]
     (cond
       (nil? jsonml)     nil
       (number? jsonml)  jsonml
       (string? jsonml)  jsonml
       (boolean? jsonml) (str jsonml)
       ;; JSONML is a JS array of [tag attrs ...children]. ClojureScript's
       ;; `array?` predicate identifies the JS array shape.
       (array? jsonml)
       (let [tag-name  (aget jsonml 0)
             attrs     (aget jsonml 1)
             child-cnt (.-length jsonml)
             get-child (fn [i] (aget jsonml i))]
         (cond
           (contains? known-tags tag-name)
           (walk-known tag-name attrs get-child child-cnt)

           (= tag-name "object")
           (render-object attrs opts)

           ;; annotation + unknown tags share the bare-span fallback.
           :else
           (walk-fallback get-child child-cnt)))

       ;; Some JSONML producers wrap their content in a CLJS vector
       ;; instead of a JS array (e.g. when re-routed through edn->js).
       ;; Walk those too.
       (vector? jsonml)
       (let [[tag-name attrs & children] jsonml
             children-vec (vec children)
             child-cnt    (+ 2 (count children-vec))
             ;; Position 0=tag, 1=attrs, 2+ = children — match the JS
             ;; array layout so `i` indexes the same positions.
             get-child    (fn [i] (nth children-vec (- i 2) nil))]
         (cond
           (contains? known-tags tag-name)
           (walk-known tag-name attrs get-child child-cnt)

           (= tag-name "object")
           (render-object attrs opts)

           :else
           (walk-fallback get-child child-cnt)))

       :else
       (try (pr-str jsonml) (catch :default _ (str jsonml)))))))

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
  "Render a CLJS `value` as the re-frame-10x current-state nested tree
  with INTERACTIVE click-to-toggle per collection node (rf2-dw8n7).

  Each `object` surrogate renders a `▸` / `▾` triangle wrapped in a
  `[:span]` that dispatches `:rf.xray/data-display-toggle-node` on
  click — the same sticky-expansion contract the home-grown
  `data-display/render` engine uses, so an operator's drill-downs
  persist across re-renders. Default expansion follows §10.4's
  depth+size heuristic (`:default-depth` defaults to 1: root expanded,
  nested collections collapsed; matches re-frame-10x's app-db look).

  The `:max-depth` opt is the HARD stack-guard — past it every
  surrogate degrades to the inline `▸ {…}` summary regardless of the
  operator's override. Defence against cyclic / pathological
  structures; not a UX bound (that's `:default-depth`).

  When called WITHOUT `:panel-id` + `:render-id` the triangles still
  render but carry no click handler — operator-state has nowhere to
  land. The interactive `browse` path always supplies both.

  ## opts

  | key            | default          | meaning                              |
  |----------------|------------------|--------------------------------------|
  | `:panel-id`    | nil              | dispatch key (panel scope)           |
  | `:render-id`   | nil              | dispatch key (per-render scope)      |
  | `:expansion-map` | nil            | sub'd from `:rf.xray/data-display-expansion` |
  | `:default-depth` | 1              | depth threshold (§10.4 heuristic)    |
  | `:max-depth`   | `default-max-depth` | stack-guard                       |

  Two-arg / single-arg arities supply only `value` (+ optional
  `max-depth`) — useful for pure-data tests; the click wiring is off
  in that case."
  ([value] (value->tree-hiccup value {}))
  ([value opts-or-max-depth]
   (if (number? opts-or-max-depth)
     (value->tree-hiccup value (assoc {} :max-depth opts-or-max-depth))
     (let [{:keys [panel-id render-id expansion-map default-depth max-depth]
            :or   {default-depth default-default-depth
                   max-depth     default-max-depth}} opts-or-max-depth]
       (try
         (with-expanded-prefs
           (fn []
             (let [header    (formatters/header-api-call value nil)
                   base-opts {:expand?       true
                              ;; Inline surrogates in the header represent
                              ;; CHILDREN of the root value (depth 1 in the
                              ;; value tree), so the walker descends with
                              ;; `:depth 1`. `render-object-interactive`
                              ;; bumps further as it expands each surrogate.
                              :depth         1
                              :max-depth     max-depth
                              :default-depth default-depth
                              :path          []
                              :panel-id      panel-id
                              :render-id     render-id
                              :expansion-map expansion-map}]
               ;; cljs-devtools' `has-body-api-call` returns false for raw
               ;; CLJS values — only its SURROGATES carry bodies. So the
               ;; root call always renders the header inline; the walker
               ;; encounters any nested surrogate INSIDE the header and
               ;; routes through `render-object-interactive`, which is
               ;; where the click-to-toggle wrappers live. (The ROOT
               ;; itself doesn't get its own outer triangle — re-frame-10x
               ;; matches: the toggle UI rides on each collection-valued
               ;; key inside the root, not on the root container.)
               (if (nil? header)
                 (pr-str-fallback value)
                 [:span {:style {:font-family mono-stack}}
                  (jsonml->hiccup header base-opts)]))))
         (catch :default _
           (pr-str-fallback value)))))))
