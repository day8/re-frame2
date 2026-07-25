(ns day8.re-frame2-xray.panels.reactive-panel-view
  "Root view for the Views panel (rf2-ad7zx.6 · Figma reconcile · prior
  beads: rf2-e33ad / rf2-8ve8z / rf2-wyvf2 / rf2-isun6 · chrome cleanup
  rf2-fhh34).

  Renders the reactive event-bundle as a left → right REACTIVE FLOW graph —
  an inline-SVG node-and-edge canvas (NOT the prior three stacked
  tables). Reconciled to `tools/xray/spec/021-Dynamic-Panel-Designs.md`
  §3.2 + `tools/xray/design-reference/xray_devtools_reference.cljs`
  (the `views-panel` component — the
  later iteration, authoritative over the §3.1.1 table iteration).

  ## Shape (spec/021 §3.2)

  Four columns, left → right:

      app-db        a single source node at the far left
      Level-1 subs  extractors (read app-db) — plain fan-out from app-db
      Level-2+ subs derived (`:<-` composition; OPTIONAL layer)
      views         the right-most focus; each (rerendered)

  Node + edge encoding (colour/edge first per spec/022 — NOT glyphs):

  - **changed / recomputed** node → filled tint + accent border + bold
    label; outgoing edges SOLID accent arrows that PROPAGATE downstream.
  - **unchanged / short-circuited** node → transparent fill + dashed dim
    outline + dim label; edges DASHED grey + visually CUT (no arrowhead).
  - **view** node → success-tinted box labelled `(rerendered)`; carries
    its per-view `:triggered-by` cause + `:elapsed-ms` timing
    (rf2-8wrzz.1) as a sub-label.
  - **shared subscription** → a sub read by ≥2 views fans out to N view
    nodes; the node carries a `×N` annotation.

  Below the graph a collapsed disclosure + two list sections complete the
  panel:

  - **Show N unchanged subs** (§3.4) — the memo-hit coverage: subs
    reactively considered this epoch whose input was value-equal, so they
    short-circuited without recomputing (canonical `:rf.sub/skip` →
    `:subs-skipped`). Collapsed by default; the per-panel toggle / Settings
    pin expands it to the dim rows.
  - **UNMOUNTED VIEWS** — views whose component unmounted this epoch.
  - **DESTROYED SUBSCRIPTIONS** — subs cleaned up when their last reader
    unmounted (data-availability honest: empty until the sub-dispose op
    lands — see reactive-panel-subs/destroyed-subscriptions).

  A legend closes the panel with three swatches: changed (propagates) ·
  no change (short-circuits) · unmounted / destroyed.

  ## Why pure-SVG, not xyflow

  The Machine panel's xyflow integration (spec/021 §6) is a separate,
  interactive, draggable surface. The reactive-flow graph is a static
  cause/timing snapshot per the spec + reference, so it is a XRAY-NATIVE
  pure-SVG graph — geometry computed by the JVM-testable
  `reactive-flow-graph/layout`, rendered here as hiccup. Mirrors
  `chart/timing-waterfall`.

  ## Hover-highlight (rf2-e33ad / rf2-8l03l — preserved)

  Hovering a view NODE toggles the `.rf-xray-view-highlight` class onto
  the rendered view's root DOM node (matched by `data-rf-view` — the
  attribute the framework stamps per Spec 006). The class paints a
  translucent pink diagonal-stripe `background-image` (theme/global-
  styles) — background-only, NO border / outline / shadow that would
  perturb layout. Cleared on mouseleave.

  Pure hiccup — frame isolation via the enclosing
  `[rf/frame-provider {:frame :rf/xray}]` in the shell."
  (:require [clojure.string :as string]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.mounted-views :as mounted-views]
            [day8.re-frame2-xray.panels.reactive-flow-graph :as graph]
            [day8.re-frame2-xray.panels.shared.coord-link :as coord-link]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack with-alpha]]))

;; ---- section chrome -----------------------------------------------------

(defn- section-label-style
  "Muted caption preceding each section, echoing the Figma
  `devtools-caption tracking-wide` heading.

  rf2-tha26 — the PRIMARY `Reactive Flow` heading renders in TITLE CASE
  (`:title-case?`), not the all-caps the secondary teardown captions
  (`Unmounted Views` / `Destroyed Subscriptions`) keep. The Figma
  reference uppercases every caption via CSS, but the title-case
  `Reactive Flow` reads as the panel's headline rather than a shout —
  the all-caps render flattened it into the same register as the
  smaller teardown sections."
  [title-case?]
  (cond-> {:padding        "0 0 8px 0"
           :font-family    sans-stack
           :font-size      "10px"
           :font-weight    600
           :letter-spacing "0.6px"
           :color          (:text-tertiary tokens)}
    (not title-case?) (assoc :text-transform "uppercase")))

(defn- section-label
  "Section caption. testid: `rf-xray-reactive-section-<id>-label`.
  `:title-case?` (rf2-tha26) renders the literal title without the
  CSS uppercase transform — used for the primary `Reactive Flow`
  heading."
  ([id title] (section-label id title nil))
  ([id title {:keys [title-case?]}]
   [:div {:data-testid (str "rf-xray-reactive-section-" id "-label")
          :style       (section-label-style title-case?)}
    title]))

;; ---- pure formatters ---------------------------------------------------

(defn- format-id
  [id]
  (cond
    (nil? id)     ""
    (keyword? id) (str id)
    :else         (pr-str id)))

(defn- view-display-name
  "Resolve a view's human-friendly name. The `reg-view :name` slot wins;
  fall back to the registry id keyword's name. Returns the string the
  panel renders."
  [view-id meta]
  (let [registered-name (:name meta)]
    (cond
      (and (string? registered-name) (not (string/blank? registered-name)))
      registered-name

      (keyword? view-id)
      (str view-id)

      :else
      (pr-str view-id))))

(defn- id-slug
  "Stable testid suffix — kw/symbol punctuation flattened to `_`."
  [id]
  (when id (string/replace (str id) #"[^a-zA-Z0-9_]" "_")))

(declare canonical-str)

(defn- canonical-entries
  "Entries of an associative value rendered `<k> <v>` and SORTED, so
  insertion order cannot leak into the encoding. Shared by the map and the
  record branch of `canonical-str` — a record's extension entries (its
  `__extmap`) are canonicalized exactly like a plain map's."
  [x]
  (->> x
       (map (fn [[k v]] (str (canonical-str k) " " (canonical-str v))))
       sort
       (string/join ", ")))

(defn- record-tag
  "The fully-qualified `my.ns/MyRec` name of a defrecord instance's type.
  `defrecord` sets the type's `cljs$lang$ctorPrWriter` to write that name as
  a COMPILE-TIME literal, so `pr-str` on the type is stable, unique per
  record type, and survives `:advanced` renaming. (`cljs.core/type->str`
  would NOT do: it reads `cljs$lang$ctorStr`, which `deftype` sets but
  `defrecord` does not, so it degrades to the constructor's JS source text.)"
  [x]
  (pr-str (type x)))

(defn- canonical-str
  "Lossless, order-canonical string rendering of a concrete-query identity.
  Distinct EDN values render to distinct strings (injective); value-equal
  identities render identically — maps, records, and sets emit their elements
  in canonical (sorted) order, so an identity built in ANY map/set insertion
  order yields ONE stable string. Vectors and lists keep their significant
  order; scalars defer to `pr-str` (faithful + reader-quoted, so a string can
  never masquerade as structure). Small + local to the selector below — NOT a
  general serializer.

  RECORDS ARE TYPE-PRESERVING (rf2-5h9td). CLJS records satisfy `map?`, so a
  bare `map?` branch would discard the record's type and render `(->A 1)`,
  `(->B 1)` and `{:x 1}` all as `{:x 1}` — three UNEQUAL concrete queries
  collapsed onto one selector. Records are matched FIRST and carry an explicit
  `#my.ns/MyRec` type tag; they can never collide with a set (`#{`, and a
  record tag is non-empty and never starts with `{`) nor with a plain map.
  Records are legal concrete-query arguments — the fresh-object cache
  diagnostic already names them (`map / collection / record built inline`).

  SUPPORTED DOMAIN, honestly stated. Injectivity holds for the EDN value
  space a concrete query is built from: collections (above), and scalars whose
  `pr-str` is faithful and type-distinct — keyword, symbol, string, number,
  boolean, nil, char, uuid, inst, regex. It does NOT hold for values `pr-str`
  cannot distinguish: raw JS objects/arrays (`#js {…}` renders by content, but
  two such objects are `=`-distinct by identity) and functions. Nor for
  deliberately degenerate reader-hostile symbols (`(symbol \"#a/B{:x 1}\")`
  prints as structure). Those are not concrete-query identities in practice;
  the selector does not claim to separate them."
  [x]
  (cond
    (record? x) (str "#" (record-tag x) "{" (canonical-entries x) "}")
    (map? x)    (str "{" (canonical-entries x) "}")
    (set? x)    (str "#{" (->> x (map canonical-str) sort (string/join " ")) "}")
    (vector? x) (str "[" (->> x (map canonical-str) (string/join " ")) "]")
    (seq? x)    (str "(" (->> x (map canonical-str) (string/join " ")) ")")
    :else       (pr-str x)))

(defn- concrete-query-selector
  "GENUINELY INJECTIVE `data-testid` suffix for an unchanged-sub row's
  concrete-query identity (rf2-bk2c6 / rf2-haoip / rf2-5h9td). `id-slug` alone
  is LOSSY — it flattens every non-alnum/non-`_` char to `_`, so DISTINCT queries
  collapse to one slug (`[:item/derived :a-b]` and `[:item/derived :a/b]` both
  slug to `__item_derived__a_b_`). Appending a 32-bit `hash` did NOT fix this:
  a 32-bit hash COLLIDES, so distinct queries (`[:item/derived \" @\"]` and
  `[:item/derived \"!!\"]` share hash `1127258382`) still minted the identical
  selector — false identity, two rows onto one `data-testid` the DOM cannot
  address independently. The discriminator is now a LOSSLESS, order-canonical
  encoding of the full identity, made DOM-safe via `encodeURIComponent`:
  distinct concrete queries get distinct selectors (collision-free), and
  value-equal identities — maps in any insertion order included — get ONE
  stable selector (the dedup contract). That encoding is TYPE-PRESERVING for
  records (rf2-5h9td): since CLJS records satisfy `map?`, a bare map branch
  would have rendered `(->A 1)`, `(->B 1)` and `{:x 1}` identically and
  re-collapsed three distinct queries onto one selector — see `canonical-str`
  for the record tag and the honestly-stated supported domain.

  The readable `id-slug` stem survives; projection, React keys, the visible
  label, and `data-query-v` are untouched;
  only this testid encoding changes. BOTH the readable stem and the
  discriminator derive from `canonical-str`, so the WHOLE selector is a function
  of VALUE — the `id-slug` stem cannot re-leak map insertion order."
  [ident]
  (let [canon (canonical-str ident)]
    (str (id-slug canon) "-" (js/encodeURIComponent canon))))

(defn- elapsed-label
  "Format a render's `:elapsed-ms` for the view-node sub-label. Sub-ms
  rounds to one decimal; ≥1ms rounds to whole ms. nil → nil."
  [ms]
  (when (number? ms)
    (if (< ms 1)
      (str (/ (Math/round (* ms 10.0)) 10.0) "ms")
      (str (Math/round ms) "ms"))))

;; ---- hover-highlight (rf2-e33ad / rf2-8l03l) --------------------------
;;
;; Hover a view node → stamp the pink diagonal-stripe highlight class on
;; the rendered view's root DOM node (matched via `data-rf-view`).
;; Cleared on mouseleave. Background-only — no layout perturbation.

(def ^:private highlight-class "rf-xray-view-highlight")

(defn- highlight-selector
  "DOM selector for a view-id (Spec 006 stores `(str id)`)."
  [view-id]
  (str "[data-rf-view='" view-id "']"))

(defn- apply-highlight!
  [view-id]
  (when (and (exists? js/document) view-id)
    (let [nodes (.querySelectorAll js/document (highlight-selector view-id))]
      (.forEach nodes
                (fn [^js node] (.add (.-classList node) highlight-class)))
      nil)))

(defn- clear-highlight!
  [view-id]
  (when (and (exists? js/document) view-id)
    (let [nodes (.querySelectorAll js/document (highlight-selector view-id))]
      (.forEach nodes
                (fn [^js node] (.remove (.-classList node) highlight-class)))
      nil)))

;; ---- [code] open-chip --------------------------------------------------

(defn- open-source!
  "Dispatch the jump-to-source effect for a topology coord. rf2-vw5pi
  — delegates to the shared `coord-link/open-in-editor!` so the
  `:rf.xray/open-in-editor` dispatch lives in ONE place; the reactive
  graph's source affordances are SVG `<g>`/`<rect>` node clicks (not
  `<button>` chips), so they bind this helper to `:on-click` directly
  rather than mounting a `coord-chip` / `coord-link`."
  [coord e]
  (coord-link/open-in-editor! coord e))

;; ---- SVG paint helpers -------------------------------------------------

(def ^:private arrow-id "rf-xray-reactive-arrow-changed")

(defn- arrow-defs
  "The single arrowhead marker — accent-coloured, used only by changed
  (propagating) edges. Unchanged edges are visually CUT (no arrowhead)."
  []
  [:defs
   [:marker {:id arrow-id :markerWidth 9 :markerHeight 7
             :refX 8 :refY 3.5 :orient "auto"}
    [:polygon {:points "0 0, 9 3.5, 0 7" :fill (:accent tokens)}]]])

(defn- edge
  "Render one event-bundle edge. Changed → solid accent line + arrowhead
  (propagates). Unchanged → dashed dim line, NO arrowhead (cut)."
  [{:keys [from-id to-id x1 y1 x2 y2 changed? kind]} i]
  ^{:key (str "edge-" kind "-" i)}
  [:line (cond-> {:data-testid (str "rf-xray-reactive-edge-" (name kind))
                  :data-edge-changed (str (boolean changed?))
                  :data-edge-from (str from-id)
                  :data-edge-to (str to-id)
                  :x1 x1 :y1 y1 :x2 x2 :y2 y2}
           changed?       (assoc :stroke (:accent tokens)
                                 :stroke-width 2
                                 :marker-end (str "url(#" arrow-id ")"))
           (not changed?) (assoc :stroke (:dim tokens)
                                 :stroke-width 1
                                 :stroke-dasharray "4,3"))])

(defn- appdb-node
  "The single app-db source node at the far left."
  [{:keys [x y w h]}]
  [:g {:data-testid "rf-xray-reactive-appdb-node"}
   [:rect {:x x :y y :width w :height h :rx 4
           :fill (:bg-3 tokens)
           :stroke (:border-default tokens) :stroke-width 1.5}]
   [:text {:x (+ x (/ w 2)) :y (+ y (/ h 2) 4)
           :text-anchor "middle" :fill (:text-primary tokens)
           :font-size 12 :font-family mono-stack}
    "app-db"]])

(defn- sub-node
  "Render a Level-1 / Level-2 sub node. Changed → filled tint + accent
  border + bold; unchanged → transparent + dashed dim outline + dim
  label. A shared sub (read by ≥2 views) carries a `×N` annotation.
  Clicking the node jumps to the sub's registration source."
  [{:keys [id slug label changed? shared-count coord x y w h kind]}]
  (let [click (when coord (fn [e] (open-source! coord e)))]
    [:g (cond-> {:data-testid (str "rf-xray-reactive-node-" (name kind) "-" slug)
                 :data-node-changed (str (boolean changed?))
                 :data-node-id (str id)}
          click (assoc :on-click click
                       :style {:cursor "pointer"}))
     [:rect (cond-> {:x x :y y :width w :height h :rx 4}
              changed?       (assoc :fill (with-alpha :accent 12)
                                    :stroke (:accent tokens) :stroke-width 2)
              (not changed?) (assoc :fill "transparent"
                                    :stroke (:dim tokens) :stroke-width 1
                                    :stroke-dasharray "4,2"))]
     [:text {:x (+ x (/ w 2)) :y (+ y (/ h 2) 4)
             :text-anchor "middle"
             :fill (if changed? (:accent tokens) (:dim tokens))
             :font-size 11 :font-family mono-stack
             :font-weight (if changed? 600 400)}
      label]
     (when (and shared-count (> shared-count 1))
       [:text {:data-testid (str "rf-xray-reactive-shared-" slug)
               :x (+ x w -4) :y (- y 3)
               :text-anchor "end" :fill (:text-tertiary tokens)
               :font-size 9 :font-family sans-stack}
        (str "×" shared-count)])]))

(defn- view-node
  "Render a view node — the event-bundle leaf + focus. Success-tinted box
  labelled `(rerendered)` (or `(mounted)`); carries the per-view
  render CAUSE + `:elapsed-ms` timing as a sub-label (rf2-8wrzz.1).

  A view re-renders for exactly one of two reasons — a SUBSCRIPTION it
  derefs changed value, or its PROPS changed (the orthogonal `:rf/props`
  channel). The cause sub-label attributes which (rf2-bhi3t):
  `← :sub-id` when `:triggered-by` is present, `← props` on a re-render
  whose own subs all held value (the props channel). A mount carries no
  cause — the `(mounted)` label already conveys the first render.
  Hovering toggles the pink DOM highlight (rf2-8l03l)."
  [{:keys [id slug label action triggered-by elapsed-ms x y w h]}]
  (let [meta      (when id (rf/handler-meta :view id))
        disp-name (view-display-name id meta)
        coord     (when (string? (:file meta))
                    {:file (:file meta) :line (:line meta) :ns (:ns meta)})
        mount?    (= :mount action)
        sub-label (str "(" (if mount? "mounted" "rerendered") ")")
        ;; rf2-bhi3t — props-driven re-render attribution. On a re-render
        ;; with no `:triggered-by`, none of the view's own subs changed
        ;; value, so the cause is props. Mounts show no cause.
        cause     (cond
                    triggered-by (str "← " (format-id triggered-by))
                    mount?       nil
                    :else        "← props")
        timing    (elapsed-label elapsed-ms)
        meta-line (->> [cause timing] (remove nil?) (string/join " · "))]
    [:g {:data-testid (str "rf-xray-reactive-view-node-" slug)
         :data-node-id (str id)
         :data-rf-xray-view-id (str id)
         :on-mouse-enter (fn [_e] (apply-highlight! id))
         :on-mouse-leave (fn [_e] (clear-highlight! id))
         :on-click       (when coord (fn [e] (open-source! coord e)))
         :style {:cursor (if coord "pointer" "default")}}
     [:rect {:x x :y y :width w :height h :rx 4
             :fill (with-alpha :success 12)
             :stroke (:success tokens) :stroke-width 2}]
     ;; view name
     [:text {:x (+ x (/ w 2)) :y (+ y 16)
             :text-anchor "middle" :fill (:success tokens)
             :font-size 11 :font-family mono-stack :font-weight 600}
      (if (and (string? disp-name) (seq disp-name)) disp-name label)]
     ;; (rerendered) + cause/timing sub-label
     [:text {:data-testid (str "rf-xray-reactive-view-meta-" slug)
             :x (+ x (/ w 2)) :y (+ y 28)
             :text-anchor "middle" :fill (:text-tertiary tokens)
             :font-size 9 :font-family sans-stack}
      (if (seq meta-line) (str sub-label "  " meta-line) sub-label)]]))

;; ---- REACTIVE FLOW graph -----------------------------------------------

(defn- flow-graph
  "Render the left → right reactive-flow SVG canvas from the pure
  `graph/layout` geometry."
  [data]
  (let [g (graph/layout data)]
    (if (:empty? g)
      [:div {:data-testid "rf-xray-reactive-graph-empty"
             :style {:padding "8px 0 16px 0"
                     :color (:text-tertiary tokens)
                     :font-family sans-stack :font-size "12px"}}
       "No subs subscribed to changed paths · no views re-rendered."]
      [:div {:data-testid "rf-xray-reactive-graph-card"
             ;; rf2-tha26 — the card edge reads as a real rounded-lg
             ;; card frame. The plain `:border-default` (#373737) hairline
             ;; was near-invisible against the card's `:bg-1` fill on the
             ;; dark theme; a `:dim`-tinted edge gives the SVG canvas a
             ;; clearly-bounded card the operator can read at a glance.
             :style {:border (str "1px solid " (with-alpha :dim 45))
                     :border-radius "8px"
                     :padding "16px"
                     :background (:bg-1 tokens)
                     :overflow-x "auto"}}
       [:svg {:data-testid "rf-xray-reactive-flow-svg"
              :width (:width g) :height (:height g)
              :viewBox (str "0 0 " (:width g) " " (:height g))
              :style {:display "block" :max-width "100%"}}
        (arrow-defs)
        ;; edges first so nodes paint over them
        (into [:g {:data-testid "rf-xray-reactive-edges"}]
              (map-indexed (fn [i e] (edge e i)) (:edges g)))
        (appdb-node (:appdb g))
        (into [:g {:data-testid "rf-xray-reactive-l1-nodes"}]
              (map (fn [n] ^{:key (:slug n)} [sub-node n]) (-> g :nodes :l1)))
        (into [:g {:data-testid "rf-xray-reactive-l2-nodes"}]
              (map (fn [n] ^{:key (:slug n)} [sub-node n]) (-> g :nodes :l2)))
        (into [:g {:data-testid "rf-xray-reactive-view-nodes"}]
              (map (fn [n] ^{:key (:slug n)} [view-node n]) (-> g :nodes :view)))]])))

;; ---- hoisted row-level styles (rf2-gjiog · audit F9) -------------------
;;
;; Extends the existing `list-card-style` hoist (sibling
;; `panels/event_detail.cljs` / `panels/cancellation_event-bundle.cljs`
;; precedents) to the list-row + sub-value-row + legend-swatch row
;; primitives below. Each Reactive panel render produces ~10-20 list
;; rows and ~5-15 sub-value rows; collapsing the inline `:style {...}`
;; allocations to ns-top map references trims ~80 allocations off a
;; representative panel render. Per-row colour variation rides a tiny
;; `assoc`-overlay on a shared base map (the audit-F4 pattern).
;;
;; Token reads resolve at ns-load; theme switching rides the CSS vars
;; seam per spec/007-UX-IA so the hoist is safe across light + dark.

;; ---- teardown list sections (UNMOUNTED VIEWS / DESTROYED SUBS) ---------

(def ^:private list-card-style
  {:border (str "1px solid " (:border-default tokens))
   :border-radius "8px"
   :background (:bg-1 tokens)
   :overflow "hidden"})

(def ^:private section-margin-top-style
  {:margin-top "24px"})

(def ^:private list-row-style
  {:display "flex" :align-items "center" :gap "12px"
   :padding "8px 14px"
   :border-top (str "1px solid " (:border-subtle tokens))
   :font-family mono-stack :font-size "12px"})

(def ^:private list-row-swatch-style-base
  {:width "14px" :height "14px" :border-radius "3px"
   :flex "0 0 auto"})

(def ^:private list-row-primary-style
  {:flex 1 :color (:text-primary tokens)})

(def ^:private list-row-tag-style
  {:color (:text-tertiary tokens)
   :font-family sans-stack :font-size "10px"})

(def ^:private empty-placeholder-style
  {:padding "2px 0" :color (:text-tertiary tokens)
   :font-style "italic" :font-family sans-stack
   :font-size "11px"})

(def ^:private destroyed-caption-style
  {:margin "8px 0 0 0" :color (:text-tertiary tokens)
   :font-family sans-stack :font-size "10px"})

(defn- list-row
  "One teardown-list row: a small tinted swatch + identifier + a muted
  trailing tag, matching the Figma `divide-y` list rows."
  [{:keys [testid swatch-token primary tag]}]
  [:div {:data-testid testid
         :style list-row-style}
   [:span {:style (assoc list-row-swatch-style-base
                         :background (with-alpha swatch-token 20))}]
   [:span {:style list-row-primary-style} primary]
   [:span {:style list-row-tag-style}
    tag]])

(defn- unmounted-views-section
  [data]
  (let [rows (:unmounted-views data)]
    [:section {:data-testid "rf-xray-reactive-unmounted-section"
               :style section-margin-top-style}
     (section-label "unmounted" "Unmounted Views")
     (if (seq rows)
       (into [:div {:data-testid "rf-xray-reactive-unmounted-list"
                    :style list-card-style}]
             (for [{:keys [view-id]} rows
                   :let [meta (when view-id (rf/handler-meta :view view-id))
                         nm   (view-display-name view-id meta)]]
               ^{:key (str view-id)}
               [list-row {:testid (str "rf-xray-reactive-unmounted-row-"
                                       (id-slug view-id))
                          :swatch-token :error
                          :primary nm
                          :tag "unmounted"}]))
       [:div {:data-testid "rf-xray-reactive-unmounted-empty"
              :style empty-placeholder-style}
        "(no views unmounted)"])]))

(defn- destroyed-subs-section
  [data]
  (let [rows (:destroyed-subs data)]
    [:section {:data-testid "rf-xray-reactive-destroyed-section"
               :style section-margin-top-style}
     (section-label "destroyed" "Destroyed Subscriptions")
     (if (seq rows)
       (into [:div {:data-testid "rf-xray-reactive-destroyed-list"
                    :style list-card-style}]
             (for [{:keys [sub-id]} rows]
               ^{:key (str sub-id)}
               [list-row {:testid (str "rf-xray-reactive-destroyed-row-"
                                       (id-slug sub-id))
                          :swatch-token :dim
                          :primary (format-id sub-id)
                          :tag "no readers remaining"}]))
       [:div {:data-testid "rf-xray-reactive-destroyed-empty"
              :style empty-placeholder-style}
        "(no subscriptions destroyed)"])
     [:p {:data-testid "rf-xray-reactive-destroyed-caption"
          :style destroyed-caption-style}
      "Subscriptions cleaned up when their last reader unmounted"]]))

;; ---- unchanged subs disclosure (spec/021 §3.4) ------------------------
;;
;; The memo-hit coverage — subs reactively considered this epoch whose
;; input was value-equal, so they short-circuited without recomputing
;; (the canonical `:rf.sub/skip` evidence, projected to `:subs-skipped`).
;; Coverage signal, not signal-of-the-moment, so it is collapsed by
;; default behind a footer `[Show N unchanged subs ▾]` line; the open-
;; state (`:show-unchanged?`) is the OR of the panel-local quick-toggle
;; and the Settings always-expand pin, resolved in the composite sub.

(def ^:private unchanged-toggle-style
  {:appearance "none" :border "none" :background "none"
   :padding "6px 0" :cursor "pointer"
   :font-family sans-stack :font-size "11px" :font-weight 600
   :letter-spacing "0.4px"
   :color (:text-tertiary tokens)})

(def ^:private unchanged-row-style
  {:display "flex" :align-items "center" :gap "12px"
   :padding "6px 14px"
   :border-top (str "1px solid " (:border-subtle tokens))
   :font-family mono-stack :font-size "12px"
   ;; §3.4 — rendered at 60% opacity (:text-tertiary): coverage, dimmed.
   :color (:text-tertiary tokens)})

(defn- unchanged-row-identity
  "Concrete-query identity a skipped-sub row keys + test-ids by (rf2-cj2yx).
  The full `:query-v` when the skip evidence carried it — so distinct
  parameterizations of one registered sub (`[:item/derived 1]` /
  `[:item/derived 2]`) render as distinct, individually-addressable rows;
  the documented fallback to the registered `:sub-id` only when the
  evidence genuinely lacked a query-v."
  [{:keys [sub-id query-v]}]
  (if (some? query-v) query-v sub-id))

(defn- unchanged-row-label
  "Display label for a skipped-sub row (rf2-cj2yx). A bare single-element
  query `[:sub/id]` renders as the plain sub-id (the common unparameterized
  case — unchanged); a parameterized query renders the FULL vector so
  `[:item/derived 1]` and `[:item/derived 2]` read distinctly. Falls back
  to the registered id when the row carries no query-v."
  [{:keys [sub-id query-v]}]
  (cond
    (and (vector? query-v) (= 1 (count query-v))) (format-id (first query-v))
    (vector? query-v)                             (pr-str query-v)
    (some? query-v)                               (format-id query-v)
    :else                                         (format-id sub-id)))

(defn- unchanged-subs-section
  "The 'Show N unchanged subs' footer disclosure (spec/021 §3.4).

  Renders nothing when no sub was memo-hit this epoch. Otherwise a footer
  toggle button; collapsed by default, expanded (per-panel toggle OR the
  `:show-unchanged-subs?` Settings pin — both fold into `:show-unchanged?`)
  it lists the memo-hit subs dim. The button dispatches the panel-local
  `:rf.xray/reactive-toggle-unchanged` quick-toggle.

  Rows key + test-id + label by CONCRETE query-v (rf2-cj2yx), so distinct
  parameterizations of one registered sub stay individually visible.

  `dispatch` (rf2-16y3x) is the facade-injected frame-aware dispatcher: the
  toggle's deferred `:on-click` calls IT, not a bare global `rf/dispatch`,
  so the flip lands on the surrounding Xray instance's frame after render
  scope unwinds (a bare dispatch would leak to `:rf/default` / emit
  `:rf.error/no-frame-context` and leave the disclosure state untouched)."
  [dispatch data]
  (let [rows  (:subs-skipped data)
        n     (count rows)
        open? (boolean (:show-unchanged? data))]
    (when (pos? n)
      [:section {:data-testid "rf-xray-reactive-unchanged-section"
                 :style section-margin-top-style}
       [:button {:data-testid "rf-xray-reactive-unchanged-toggle"
                 :data-open   (str open?)
                 :aria-expanded (str open?)
                 :on-click    (fn [_e]
                                (dispatch [:rf.xray/reactive-toggle-unchanged]))
                 :style       unchanged-toggle-style}
        (str (if open? "Hide" "Show") " " n " unchanged sub" (when (not= 1 n) "s")
             " " (if open? "▴" "▾"))]
       (when open?
         (into [:div {:data-testid "rf-xray-reactive-unchanged-list"
                      :style       list-card-style}]
               (for [{:keys [query-v] :as row} rows
                     :let [ident (unchanged-row-identity row)]]
                 ^{:key (str ident)}
                 [:div {:data-testid (str "rf-xray-reactive-unchanged-row-"
                                          (concrete-query-selector ident))
                        :data-query-v (str query-v)
                        :style       unchanged-row-style}
                  [:span {:style {:flex 1}} (unchanged-row-label row)]
                  [:span {:style {:font-family sans-stack :font-size "10px"}}
                   "input unchanged · memo hit"]])))])))

;; EP-0025: the STANDING `:public`-claim declassification audit section is
;; REMOVED — classification no longer propagates input → output, so there is no
;; `:rf.egress/output-sensitivity :rf.egress/public` declassify claim to surface.

;; ---- mounted views (Freehand tool door · rf2-7gth0) ---------------------
;;
;; One row per occurrence CONNECTED RIGHT NOW, read from
;; `re-frame.freehand.tool/read-mounted-views` and joined with the bounded
;; read-time fold `explain-render` returns.
;;
;; The vocabulary here is Freehand's, and it is deliberately smaller than the
;; donor tier's. There is no lifetime render count, no batch count, no epoch
;; span, no hide-versus-unmount label and no accumulated union of every target
;; an occurrence ever observed, because the substrate keeps no accumulator to
;; derive them from (rf2-drpa3.167). What is here instead is exact: the latest
;; committed generation, the commit's own staged reads, and — where Spec 009's
;; window still reaches — the run that caused the render. Where it does not
;; reach, the row says which of the two reasons applies rather than showing a
;; confident blank.

(defn- format-occurrence
  "Short, stable rendering of a Freehand occurrence key — `{:parent p :key k}`
  — as the `k` a reader recognises, qualified by its parent when it has one.
  Occurrence keys are minted by the host's identity primitive, so this formats
  whatever it is given rather than assuming a shape."
  [occurrence]
  (if (map? occurrence)
    (let [{:keys [parent key]} occurrence]
      (str (format-id key) (when (some? parent) (str " ◂ " (format-id parent)))))
    (format-id occurrence)))

(defn- cause-label
  "The render's cause as one phrase — the event that started the run the commit
  was correlated to, with the subscriptions that run recomputed and this commit
  reads. nil when there is no cause, which the loss label then explains."
  [{:keys [cause-event-id sub-ids]}]
  (when cause-event-id
    (str (format-id cause-event-id)
         (when (seq sub-ids)
           (str " → " (string/join ", " (map format-id (sort-by str sub-ids))))))))

(defn- loss-label
  "Why an explanation is INCOMPLETE, in the reader's terms — the two reasons
  are different remedies, so they get different words. `:cap` is the window's
  one knob (`:rf.trace/events-retained`); `:uncorrelated` is a commit that
  named no run at all, which a bigger buffer would not fix. nil for a complete
  explanation."
  [{:keys [reason]} candidate-count]
  (case reason
    :cap          (str "cause not retained — Spec 009's window does not hold it"
                       (when (pos? candidate-count)
                         (str "; " candidate-count " lead"
                              (when (not= 1 candidate-count) "s"))))
    :uncorrelated (str "no cascade in scope at commit — uncorrelated"
                       (when (pos? candidate-count)
                         (str "; " candidate-count " lead"
                              (when (not= 1 candidate-count) "s"))))
    nil))

(defn- mounted-view-tag
  "One muted trailing summary per row: lowering · generation · frame · the
  commit's read count · the render's cause, or the honest reason there is none.

  Every quantity is a fact about NOW. `gen N` is the latest committed
  generation, not a tally of renders; `N reads` is what THAT commit staged, not
  a union over the occurrence's life."
  [{:keys [lowering generation frame reads cause candidates loss explained?]}]
  (let [n (count reads)]
    (str (when lowering (str (name lowering) " · "))
         "gen " generation
         (when frame (str " · " (format-id frame)))
         " · " n " read" (when (not= 1 n) "s")
         (if explained?
           (when-some [c (cause-label cause)] (str " · " c))
           (when-some [l (loss-label loss (count candidates))] (str " · " l))))))

(def ^:private schema-banner-style
  {:margin "0 0 8px 0" :padding "8px 12px"
   :border-radius "6px"
   :background (with-alpha :warning 12)
   :border (str "1px solid " (with-alpha :warning 40))
   :color (:text-secondary tokens)
   :font-family sans-stack :font-size "11px"})

(defn- mounted-views-schema-banner
  "Honest evidence-schema mismatch banner. When the running application's
  Freehand door stamps a schema this Xray build does not understand, `rows`
  degrades to empty rather than mis-parse; this banner tells the operator WHY
  the section is empty, so a mismatched deployment reads honestly instead of
  looking like a host with nothing mounted."
  []
  (let [{:keys [schema supported?]}
        @(rf/subscribe [:rf.xray/mounted-views-schema])]
    (when (and schema (not supported?))
      [:div {:data-testid "rf-xray-reactive-mounted-views-schema-banner"
             :style schema-banner-style}
       (str "Evidence schema " (format-id schema)
            " is not recognised by this Xray build — mounted-view rows are "
            "suppressed to avoid mis-parsing an evolved shape.")])))

(defn- mounted-views-section
  "The MOUNTED VIEWS section — Xray's render path over the Freehand tool
  door's connected-occurrence roster (rf2-7gth0). One row per occurrence
  connected right now, keyed by its runtime occurrence, so two simultaneous
  occurrences of one view are two addressable rows.

  Current state, not history: a disconnect REMOVES a row rather than labelling
  it, which is why there is no unmounted arm here and no lifecycle tag. Empty
  on hosts not running Freehand."
  []
  (let [rows @(rf/subscribe [:rf.xray/mounted-views])]
    [:section {:data-testid "rf-xray-reactive-mounted-views-section"
               :style section-margin-top-style}
     (section-label "mounted-views" "Mounted Views")
     (mounted-views-schema-banner)
     (if (seq rows)
       (into [:div {:data-testid "rf-xray-reactive-mounted-views-list"
                    :style list-card-style}]
             (for [[i {:keys [occurrence view-id root] :as row}] (map-indexed vector rows)]
               ^{:key (str view-id "|" (pr-str occurrence))}
               [list-row {:testid (str "rf-xray-reactive-mounted-views-row-" i)
                          :swatch-token :accent
                          :primary (str (format-id view-id)
                                        " · occ " (format-occurrence occurrence)
                                        ;; `:root` is ALWAYS `:unknown` — cells do
                                        ;; not know their owning root and the commit
                                        ;; seam carries no root identity. Render the
                                        ;; marker as the absence it is; a row that
                                        ;; named a root would be inventing one.
                                        (when-not (mounted-views/unknown? root)
                                          (str " · " (format-id root))))
                          :tag (mounted-view-tag row)}]))
       [:div {:data-testid "rf-xray-reactive-mounted-views-empty"
              :style empty-placeholder-style}
        "(nothing connected — the host is not running Freehand, or no view has
         committed yet)"])
     [:p {:data-testid "rf-xray-reactive-mounted-views-caption"
          :style destroyed-caption-style}
      "Occurrences connected now (latest committed generation · that commit's
       reads · the run that caused the render, or why the retained window
       cannot say)"]]))

;; ---- declared view sites (static manifest projection · rf2-7gth0) --------
;;
;; Subscription + event-handler sites read from each view's compiler manifest,
;; for the views present in the roster above. Honest about what it cannot know
;; in BOTH directions: a `:dynamic?` query or an `:opaque` handler is labelled
;; rather than shown as source code, and an INTERPRETED declaration — which has
;; no analysis step at all — says so instead of rendering the empty rosters
;; that would read as a clean bill of health.

(defn- dependencies-summary
  "One-line dependency summary — subscription count with a dynamic tally
  (query shapes carrying a captured local, projected honestly). nil when a
  view declares no dependency sites."
  [subscriptions]
  (let [subs-n   (count subscriptions)
        dyn-subs (count (filter :dynamic? subscriptions))]
    (when (pos? subs-n)
      (str subs-n " sub" (when (not= 1 subs-n) "s")
           (when (pos? dyn-subs) (str " (" dyn-subs " dynamic)"))))))

(defn- subscription-site-label
  "One subscription site — its literal query verbatim, or the honest dynamic
  form: the query-id the compiler really does know, with the runtime argument
  left unsaid rather than invented."
  [{:keys [dynamic? query query-id]}]
  (if dynamic?
    (str (if query-id (format-id query-id) "?") " (dynamic args)")
    (pr-str query)))

(defn- event-site-label
  "One event-handler site — `:on-click · vector · [:cart/add 3]` for a literal
  handler that IS the shape which will dispatch, and `:on-click · vector ·
  :cart/add (dynamic args)` where the handler carries a captured local. A
  callback BODY has no event vector at all and reads `(opaque)`.

  `:classification` is what keeps those two apart, so it is rendered: an
  `:opaque` handler on a `:vector` or `:options` site is an event vector with a
  runtime argument; on any other classification it is code."
  [{:keys [prop classification handler event-id]}]
  (str (format-id prop) " · " (name classification) " · "
       (if (= :opaque handler)
         (if event-id (str (format-id event-id) " (dynamic args)") "(opaque)")
         (pr-str handler))))

(defn- diagnostic-label
  "One compile-tier a11y finding — `a11y-click-non-interactive · <div>`, with
  the author's suppression reason when the finding was silenced at the source.
  The compiler owns the verdict; this line only reports it."
  [{:keys [id tag suppressed? reason]}]
  (str (name id)
       (when tag (str " · <" (name tag) ">"))
       (when suppressed?
         (str " · suppressed" (when reason (str ": " reason))))))

(def ^:private view-site-row-style
  {:padding "8px 14px"
   :border-top (str "1px solid " (:border-subtle tokens))
   :font-family mono-stack :font-size "12px"
   :color (:text-primary tokens)})

(def ^:private view-site-detail-style
  {:color (:text-tertiary tokens)
   :font-family sans-stack :font-size "10px"
   :margin-top "3px"})

(def ^:private view-site-diagnostic-style
  (assoc view-site-detail-style :color (:warning tokens)))

(def ^:private view-site-opaque-style
  (assoc view-site-detail-style :font-style "italic"))

(defn- site-coord-chip
  "The `[code]` affordance for ONE declared site.

  Per SITE, not per view: the Freehand tool door publishes a `:source-coord`
  on each roster entry and none on the declaration itself, so a view-level
  chip would have to pick one site's coordinate and present it as the view's.
  A per-site chip is the coordinate the substrate actually states, and it
  lands the reader on the exact `v/sub` or handler rather than the top of the
  declaration. Absent — not a dead chip — when a site carries no coordinate
  (`:source-coord` is total or absent, never partial)."
  [testid coord]
  (when (string? (:file coord))
    [:span {:data-testid testid
            :on-click (fn [e] (open-source! coord e))
            :style {:cursor "pointer" :color (:accent tokens)
                    :font-family sans-stack :font-size "10px"
                    :margin-left "6px"}}
     "[code]"]))

(defn- view-site-row
  [{:keys [view-id lowering complete? loss capabilities view-cell reactive?
           subscriptions event-sites diagnostics]}]
  (let [slug (id-slug view-id)
        deps (dependencies-summary subscriptions)
        caps (when (seq capabilities)
               (string/join " · " (map name (sort-by name capabilities))))]
    [:div {:data-testid (str "rf-xray-reactive-view-site-row-" slug)
           :style view-site-row-style}
     [:div {:style {:display "flex" :align-items "center" :gap "10px"}}
      [:span {:style {:flex 1}} (format-id view-id)]
      (when lowering
        [:span {:style {:color (:text-tertiary tokens)
                        :font-family sans-stack :font-size "10px"}}
         (name lowering)])]
     ;; The arm the projection vocabulary exists for: an interpreted
     ;; declaration has no analysis step, so its empty rosters mean nobody
     ;; looked — NOT that there is nothing there. Say which.
     (if (false? complete?)
       [:div {:data-testid (str "rf-xray-reactive-view-site-opaque-" slug)
              :style view-site-opaque-style}
        (str "no static analysis — "
             (if (= :no-static-analysis (:reason loss))
               "this declaration is interpreted, so its sites are unknown, not absent"
               "the projection reported this view incomplete"))]
       [:<>
        (when (or deps caps view-cell (some? reactive?))
          [:div {:data-testid (str "rf-xray-reactive-view-site-facts-" slug)
                 :style view-site-detail-style}
           (->> [deps
                 (when caps (str "caps " caps))
                 (when view-cell (str "view-cell " (name view-cell)))
                 (when (false? reactive?) "non-reactive")]
                (remove nil?)
                (string/join " · "))])
        (when (seq subscriptions)
          (into [:div {:data-testid (str "rf-xray-reactive-view-site-subs-" slug)
                       :style view-site-detail-style}]
                (for [[i site] (map-indexed vector subscriptions)]
                  ^{:key i}
                  [:div (subscription-site-label site)
                   (site-coord-chip
                     (str "rf-xray-reactive-view-site-sub-code-" slug "-" i)
                     (:source-coord site))])))
        (when (seq event-sites)
          (into [:div {:data-testid (str "rf-xray-reactive-view-site-events-" slug)
                       :style view-site-detail-style}]
                (for [[i site] (map-indexed vector event-sites)]
                  ^{:key i}
                  [:div (event-site-label site)
                   (site-coord-chip
                     (str "rf-xray-reactive-view-site-event-code-" slug "-" i)
                     (:source-coord site))])))])
     (when (seq diagnostics)
       (into [:div {:data-testid (str "rf-xray-reactive-view-site-diagnostics-" slug)
                    :style view-site-diagnostic-style}]
             (for [[i d] (map-indexed vector diagnostics)]
               ^{:key i}
               [:div (diagnostic-label d)])))]))

(defn- view-sites-section
  "The DECLARED VIEW SITES section — per-view dependency + event-site
  provenance from the compiler manifest, for the views present in the mounted
  roster (evidence-keyed: renders nothing on a host with nothing connected).
  Honest about dynamic queries, opaque handlers, and — the axis the donor tier
  could not state — an interpreted declaration whose sites were never
  analysed."
  []
  (let [views @(rf/subscribe [:rf.xray/mounted-view-sites])]
    (when (seq views)
      [:section {:data-testid "rf-xray-reactive-view-sites-section"
                 :style section-margin-top-style}
       (section-label "view-sites" "Declared View Sites")
       (into [:div {:data-testid "rf-xray-reactive-view-sites-list"
                    :style list-card-style}]
             (for [{:keys [view-id] :as v} views]
               ^{:key (str view-id)}
               [view-site-row v]))
       [:p {:data-testid "rf-xray-reactive-view-sites-caption"
            :style destroyed-caption-style}
        "Declared subscription + event-handler sites from the compiler
         manifest (before-mount evidence; dynamic queries, opaque handlers and
         un-analysed interpreted declarations each labelled, never fabricated)"]])))

;; ---- legend ------------------------------------------------------------

(def ^:private legend-swatch-wrapper-style
  {:display "inline-flex" :align-items "center" :gap "8px"})

(def ^:private legend-swatch-base-style
  {:display "inline-block" :width "12px"
   :height "12px" :border-radius "3px"})

(def ^:private legend-style
  (merge section-margin-top-style
         {:color (:text-tertiary tokens)
          :font-family sans-stack :font-size "10px"}))

(def ^:private legend-caption-style
  {:margin "0 0 6px 0"})

(def ^:private legend-swatch-row-style
  {:display "flex" :flex-wrap "wrap" :gap "16px"
   :align-items "center"})

(defn- swatch
  [style label]
  [:span {:style legend-swatch-wrapper-style}
   [:span {:style (merge legend-swatch-base-style style)}]
   label])

(defn- legend
  []
  [:div {:data-testid "rf-xray-reactive-legend"
         :style legend-style}
   [:p {:style legend-caption-style}
    "Views (right) are the focus — each: re-rendered + why (reactive vs parent re-render)"]
   [:div {:style legend-swatch-row-style}
    (swatch {:background (:accent tokens)} "changed (propagates downstream)")
    (swatch {:background "transparent" :border (str "1px dashed " (:dim tokens))}
            "no change (short-circuits)")
    (swatch {:background (with-alpha :error 20)} "unmounted / destroyed")]])

;; ---- empty state ------------------------------------------------------

(defn- empty-state
  [data]
  [:div {:data-testid "rf-xray-reactive-empty"
         :style {:padding "16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "13px"}}
   (if (nil? (:current (:focus data)))
     [:p "No event focused."]
     [:p "Focused event-bundle has no reactive activity captured yet."])])

;; ---- panel root --------------------------------------------------------

(defn reactive-panel
  "Plain Reagent fn — invoked from `reactive-panel/Panel` (the public
  facade reg-view) via a function call so the React-context frame tier
  resolves to `:rf/xray` inside the leaf's subscribes.

  Renders the left → right REACTIVE FLOW graph (rf2-ad7zx.6) followed by
  the UNMOUNTED VIEWS + DESTROYED SUBSCRIPTIONS sections and the closing
  legend.

  `dispatch` (rf2-16y3x) is the frame-aware dispatcher the facade `Panel`
  reg-view injects and threads down — the panel-local unchanged-subs
  disclosure toggle's deferred `:on-click` calls it (never a bare global
  `rf/dispatch`) so the click lands on the surrounding instance frame after
  render scope unwinds. The 0-arity is a test convenience (plain-fn mounts
  that never click the toggle); production always threads a real dispatcher."
  ([] (reactive-panel nil))
  ([dispatch]
   (let [data @(rf/subscribe [:rf.xray/reactive-data])]
    [:section {:data-testid "rf-xray-reactive"
               :style {:height "100%"
                       :display "flex"
                       :flex-direction "column"
                       :background (:bg-2 tokens)
                       :color (:text-primary tokens)
                       :font-family sans-stack
                       :font-size "14px"}}
     [:div {:style {:flex 1 :overflow "auto"}}
      (if (not (:has-event-bundle? data))
        [:div {:data-testid "rf-xray-reactive-pipeline-empty"
               :style {:padding "16px"}}
         (empty-state data)
         ;; The evidence + compiled-view-sites sections are CUMULATIVE (not
         ;; epoch-scoped), so they render with or without a focused
         ;; event-bundle. Plain function call — the subscribe must run inside
         ;; THIS render's carried :rf/xray frame scope (the panel's
         ;; delegation idiom).
         (mounted-views-section)
         (view-sites-section)]
        [:div {:data-testid "rf-xray-reactive-pipeline"
               :style {:padding "16px"}}
         [:section {:data-testid "rf-xray-reactive-flow-section"}
          (section-label "flow" "Reactive Flow" {:title-case? true})
          (flow-graph data)]
         (unchanged-subs-section dispatch data)
         (unmounted-views-section data)
         (destroyed-subs-section data)
         (mounted-views-section)
         (view-sites-section)
         (legend)])]])))
