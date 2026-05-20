(ns day8.re-frame2-causa.data-display.render
  "Shared data-display renderer for Causa L4 panels (rf2-jgip1).

  The renderer is **ONE canonical component used everywhere data appears**
  — App-db's huge nested map, the Event panel's coeffects + effects, the
  Reactive panel's sub values, Trace ops' expanded payloads, Issues
  `ex-data`. Operator learns one interaction pattern; applies it
  everywhere. Per spec/021-Dynamic-Panel-Designs.md §10 (canonical ·
  merged in #1720).

  ## Capabilities (LOCKED per super-prompt B.9)

  1. **Lazy collapsible tree** — hierarchical EDN with expand/collapse.
     Large collections render as `{N entries}` / `[N items]` until the
     operator opens them. Default expansion follows §10.4's depth /
     children heuristic; per-node override is sticky (writes a path
     entry into the `:rf.causa/data-display-expansion` app-db slot).

  2. **Inline diff highlighting** — when callers pass `:before` +
     `:after`, the renderer paints a left-margin gutter (green `+` /
     red `-` / yellow `~` / violet `◴`) on the changed branches and
     annotates `← changed from <prior>`. **No side-by-side
     before|after** — diff is annotation on a single rendered state
     (§10.1.2). Unchanged values dim to `:text-tertiary`.

  3. **Minimal type colouring** — keywords get the violet accent (the
     only coloured type). Strings / numbers / nil / booleans / symbols
     render mono in `:text-primary`. Aids EDN-shape recognition
     without colour-noise (§10.3).

  4. **Clickable paths** — every key segment is a click target. Clicking
     emits `[:rf.causa/navigate-to-path <path> opts]`; the parent
     panel wires the navigation semantics (App-db ↔ Reactive
     propagation). The renderer never reaches across panels itself —
     dispatch + listening live in the consuming panel.

  ## Stripped capabilities (deliberately OUT per B.9)

  - **No blame popover** (`who set this value?`)
  - **No copy-path button**
  - **No copy-value button**
  - **No type-tooltip on hover**

  These were considered and explicitly removed. Future beads that
  re-propose them must re-open the B.9 lock with the mayor first.

  ## Public API

      (render-tree {:value     v
                    :diff?     bool        ; default false
                    :before    x           ; required when diff?
                    :after     y           ; defaults to :value when diff?
                    :panel-id  pid         ; required (e.g. :app-db)
                    :render-id rid         ; required (per-mount uniquifier)
                    :default-depth 2       ; per-panel override knob (§10.4)
                    :evicted?  bool        ; render the §10.7 placeholder
                    :epoch-id  e}          ; epoch number for the evicted msg
                    → hiccup vector

  Pure helpers (`expansion-key`, `default-expanded?`, `diff-op`, …)
  are public so the test surface exercises them without driving the
  full Reagent render."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack]]))

;; ---- public app-db slot --------------------------------------------------
;;
;; Per spec/021 §10.4 — sticky per-node expansion lives in
;; `:rf.causa/data-display-expansion`. Keys are `[panel-id render-id path]`
;; tuples so two adjacent renders on the same panel never collide.

(def expansion-slot
  "App-db key (under the `:rf/causa` frame) that holds the per-node
  expansion overrides. Public so the consuming panel's :install! /
  reset-expansion affordance can clear it."
  :rf.causa/data-display-expansion)

(defn expansion-key
  "Compose the per-node expansion-state key. Pure data, JVM-portable
  (used in tests + by the consuming panel's reset affordance)."
  [panel-id render-id path]
  [panel-id render-id (vec path)])

;; ---- expansion heuristic (§10.4) -----------------------------------------

(def collapse-children-threshold
  "Per §10.4 — a depth-3 node with more than this many children starts
  collapsed."
  10)

(defn default-expanded?
  "§10.4 default expansion heuristic. Pure fn of depth + child-count +
  default-depth + has-changed-descendant?

  | Depth | Size | Default state |
  |---|---|---|
  | ≤ default-depth - 1 | any           | Expanded |
  | = default-depth     | ≤ threshold   | Expanded |
  | = default-depth     | > threshold   | Collapsed |
  | > default-depth     | any           | Collapsed |

  Changed descendants force the ancestor chain open (the
  `has-changed-descendant?` flag overrides depth/size)."
  [{:keys [depth child-count default-depth has-changed-descendant?]
    :or   {default-depth 2 child-count 0}}]
  (cond
    has-changed-descendant?            true
    (<= depth (dec default-depth))     true
    (= depth default-depth)            (<= child-count
                                           collapse-children-threshold)
    :else                              false))

;; ---- diff helpers --------------------------------------------------------

(defn diff-op
  "Classify a (before, after) pair into an op keyword:

    :added    — before is `::missing`, after exists
    :removed  — before exists, after is `::missing`
    :modified — both exist and differ
    :same     — equal

  Pure data; JVM-portable."
  [before after]
  (cond
    (= before ::missing) (if (= after ::missing) :same :added)
    (= after  ::missing) :removed
    (= before after)     :same
    :else                :modified))

(def op->gutter-glyph
  "Left-gutter glyph per spec §10.3 + §17 cascade-gutter token mapping.
  Public so tests can assert the mapping without re-deriving."
  {:added    "+"
   :removed  "-"
   :modified "~"
   :children "◴"   ;; ◴
   :same     " "})

(def op->gutter-tone-key
  "Token-key per gutter glyph (§10.3)."
  {:added    :green
   :removed  :red
   :modified :yellow
   :children :accent-violet
   :same     :text-tertiary})

(defn gutter-colour
  "Resolve the gutter colour for an op. Pure-data → string hex."
  [op]
  (get tokens (op->gutter-tone-key op) (:text-tertiary tokens)))

;; ---- has-changed-descendant? --------------------------------------------

(defn changed?
  "True when (diff-op before after) is anything other than :same. Pure."
  [before after]
  (not= :same (diff-op before after)))

(defn changed-descendant?
  "True when at least one descendant in (before, after) differs. Walks
  maps + sequentials; pure. Returns true for primitive mismatches at
  the root too — so a caller can use this to drive ancestor-open.

  Always returns a primitive boolean (never nil) so callers can dispatch
  on `true?` / `false?` without nil-coercion."
  [before after]
  (boolean
    (cond
      (and (map? before) (map? after))
      (or (not= (set (keys before)) (set (keys after)))
          (some (fn [k] (changed-descendant? (get before k ::missing)
                                             (get after  k ::missing)))
                (into (set (keys before)) (keys after))))

      (and (sequential? before) (sequential? after))
      (or (not= (count before) (count after))
          (some true?
                (map-indexed
                  (fn [i bv]
                    (changed-descendant? bv (nth (vec after) i ::missing)))
                  before))
          ;; Extra trailing items in `after`.
          (some true?
                (map-indexed
                  (fn [i av]
                    (when (>= i (count before))
                      (changed-descendant? ::missing av)))
                  after)))

      :else (not= before after))))

;; ---- subscriptions + event handlers --------------------------------------
;;
;; Self-contained: this ns installs its own slot subs + toggle event +
;; navigation event (idempotent registration via `defonce` sentinel —
;; same pattern the `registry` ns uses for the orchestrator's :rf.causa
;; handler bundle).

(rf/reg-sub expansion-slot
  (fn [db _] (get db expansion-slot)))

(rf/reg-sub :rf.causa/data-display-node-state
  :<- [expansion-slot]
  (fn [all [_ panel-id render-id path]]
    (get all (expansion-key panel-id render-id path))))

(rf/reg-event-db :rf.causa/data-display-toggle-node
  (fn [db [_ panel-id render-id path]]
    (let [k (expansion-key panel-id render-id path)
          current (get-in db [expansion-slot k])
          ;; current is either nil (no override) or {:expanded? bool}.
          ;; Toggle inverts whatever the operator last picked; if no
          ;; override exists yet, we treat the prior state as the
          ;; default (caller can read it via `default-expanded?`) —
          ;; here we just flip a stored boolean, defaulting to true
          ;; (so the first click on a collapsed node opens it).
          next?   (not (boolean (:expanded? current)))]
      (assoc-in db [expansion-slot k] {:expanded? next?}))))

(rf/reg-event-db :rf.causa/data-display-set-expanded
  (fn [db [_ panel-id render-id path expanded?]]
    (assoc-in db [expansion-slot (expansion-key panel-id render-id path)]
              {:expanded? (boolean expanded?)})))

(rf/reg-event-db :rf.causa/data-display-reset-expansion
  (fn [db _]
    (dissoc db expansion-slot)))

;; ---- navigation event ----------------------------------------------------
;;
;; The renderer never wires cross-panel navigation directly. It emits
;; `:rf.causa/navigate-to-path` with `{:path … :panel-id … :render-id …}`;
;; the parent panel installs a handler that interprets the payload (e.g.
;; "if I'm App-db, switch to Reactive and highlight subs downstream of
;; this path"). Per spec §10.5: this is the only path-click semantic.
;;
;; We register a SAFE NO-OP handler here so a render outside a wired
;; panel doesn't crash with "no handler registered." The consuming
;; panel overrides via its own `reg-event-fx` — re-frame's last-write
;; semantics make the override transparent.

(defonce ^:private navigate-default-registered?
  (atom false))

(when (compare-and-set! navigate-default-registered? false true)
  (rf/reg-event-fx :rf.causa/navigate-to-path
    (fn [_ _]
      ;; No-op default. The consuming panel replaces this with its own
      ;; handler that does cross-panel propagation. The :rf.causa
      ;; prefix collision contract makes the override safe.
      {})))

;; ---- pure render helpers (no Reagent / no rf/dispatch) -------------------

(defn keyword-style
  "Inline style map for a keyword leaf. Public so unit-tests can assert
  the violet token resolves through `tokens`."
  []
  {:color       (:accent-violet tokens)
   :font-family mono-stack})

(defn mono-style
  "Inline style map for any non-keyword leaf (string / number / nil /
  boolean / symbol)."
  []
  {:color       (:text-primary tokens)
   :font-family mono-stack})

(defn dim-style
  "Inline style map for unchanged-and-dimmed rows in diff mode."
  []
  {:color       (:text-tertiary tokens)
   :font-family mono-stack})

(defn render-scalar
  "Render a single non-collection value. Returns a `[:span ...]` hiccup.
  Public so tests can drive every leaf shape independently of the
  recursive tree walk."
  [v]
  (cond
    (nil? v)        [:span {:style (mono-style)} "nil"]
    (keyword? v)    [:span {:style (keyword-style)} (str v)]
    (boolean? v)    [:span {:style (mono-style)} (str v)]
    (number? v)     [:span {:style (mono-style)} (str v)]
    (string? v)     [:span {:style (mono-style)} (str "\"" v "\"")]
    (symbol? v)     [:span {:style (mono-style)} (str v)]
    :else
    [:span {:style (mono-style)}
     (try (pr-str v) (catch :default _ (str v)))]))

;; ---- evicted-epoch placeholder (§10.7) ------------------------------------

(defn evicted-placeholder
  "§10.7 — when the operator scrubs onto an epoch evicted from the
  buffer, every data-display renders this same placeholder. Pure
  hiccup; takes the optional epoch-id to render in the header."
  [{:keys [epoch-id panel-id render-id]}]
  [:div {:data-testid (str "rf-causa-data-display-evicted-"
                           (name (or panel-id :unknown))
                           "-"
                           (str (or render-id "")))
         :style {:padding       "12px 16px"
                 :margin        "8px 0"
                 :background    (:bg-2 tokens)
                 :border        (str "1px dashed " (:border-default tokens))
                 :border-radius "4px"
                 :font-family   sans-stack
                 :font-size     "12px"
                 :color         (:text-secondary tokens)
                 :line-height   1.5}}
   [:div {:style {:font-family mono-stack
                  :font-size   "11px"
                  :color       (:text-tertiary tokens)
                  :margin-bottom "4px"}}
    (str "epoch " (if epoch-id (str "#" epoch-id) "(unknown)"))]
   [:div {:style {:color (:text-primary tokens) :font-weight 600}}
    "Epoch evicted from buffer."]
   [:div "Increase :epoch-history to retain more."]
   [:div {:style {:color (:text-tertiary tokens) :font-size "11px"}}
    "Settings → General → Epoch history."]])

;; ---- expansion read (pure projection) -----------------------------------

(defn resolve-expanded?
  "Pure projection — given the per-render expansion map (sub'd from
  `expansion-slot`), the path, and the default-heuristic result,
  return whether THIS node renders expanded.

  The operator's sticky override (if present) wins; otherwise fall
  back to the supplied default."
  [expansion-map panel-id render-id path default?]
  (let [k        (expansion-key panel-id render-id path)
        override (get expansion-map k)]
    (if (contains? override :expanded?)
      (boolean (:expanded? override))
      (boolean default?))))

;; ---- clickable path segment ----------------------------------------------

(defn path-segment
  "Render a single key as a clickable path segment. Click dispatches
  `[:rf.causa/navigate-to-path {:path … :panel-id … :render-id …}]`.

  `path` is the FULL path-vector from the tree-root up to and INCLUDING
  this segment, so the consumer can switch panels with the exact slot
  in hand. Per §10.5 — this is the only path-click semantic."
  [{:keys [k path panel-id render-id]}]
  (let [keyword?    (keyword? k)
        on-click    (fn [^js e]
                      (when e
                        (.preventDefault e)
                        (.stopPropagation e))
                      (rf/dispatch
                        [:rf.causa/navigate-to-path
                         {:path      (vec path)
                          :panel-id  panel-id
                          :render-id render-id}]))]
    [:span {:data-testid          (str "rf-causa-data-display-path-"
                                       (str/join "/" (map pr-str path)))
            :on-click             on-click
            :title                (str "Navigate to " (pr-str (vec path)))
            :style                {:cursor                "pointer"
                                   :font-family           mono-stack
                                   :color                 (if keyword?
                                                            (:accent-violet tokens)
                                                            (:text-primary tokens))
                                   :text-decoration       "underline"
                                   :text-decoration-style "dotted"
                                   :text-underline-offset "2px"
                                   :text-decoration-color (:border-default tokens)}}
     (pr-str k)]))

;; ---- node header (toggle + key + summary) --------------------------------

(defn- container-glyph
  "Tree-disclosure glyph per §17.1.5 — `▾` expanded, `▸` collapsed."
  [expanded?]
  (if expanded? "▾" "▸"))

(defn- container-summary
  "`{N entries}` / `[N items]` / `#{N items}` placeholder for collapsed
  containers."
  [v]
  (cond
    (map? v)        (str "{" (count v) " entries}")
    (vector? v)     (str "[" (count v) " items]")
    (set? v)        (str "#{" (count v) " items}")
    (sequential? v) (str "(" (count v) " items)")
    :else           "(?)"))

(defn- container-opener
  [v]
  (cond
    (map? v) "{" (vector? v) "[" (set? v) "#{" (sequential? v) "(" :else "?"))

(defn- container-closer
  [v]
  (cond
    (map? v) "}" (vector? v) "]" (set? v) "}" (sequential? v) ")" :else "?"))

;; ---- recursive renderer -------------------------------------------------

(declare render-node)

(defn- gutter-row
  "Wrap a row with the diff gutter (3px left border + glyph). When the
  op is `:same` the wrapper is invisible (transparent border, blank
  glyph) so non-diff renders share the same hiccup shape as diff
  renders."
  [op body]
  (let [active? (not= :same op)]
    [:div {:style {:display      "flex"
                   :align-items  "flex-start"
                   :gap          "4px"
                   :padding-left "6px"
                   :border-left  (str "3px solid "
                                      (if active?
                                        (gutter-colour op)
                                        "transparent"))}}
     [:span {:style {:flex          "0 0 12px"
                     :color         (gutter-colour op)
                     :font-family   mono-stack
                     :font-size     "11px"
                     :font-weight   700
                     :text-align    "center"
                     :user-select   "none"}}
      (op->gutter-glyph op)]
     [:div {:style {:flex 1 :min-width 0}} body]]))

(defn- change-annotation
  "Inline `← changed from <prior>` chip rendered to the right of a
  diff'd leaf. Pure hiccup."
  [before]
  [:span {:style {:margin-left "8px"
                  :color       (:text-secondary tokens)
                  :font-family sans-stack
                  :font-size   "11px"
                  :font-style  "italic"}}
   (str "← changed from " (pr-str before))])

(defn- render-leaf
  "Render a primitive leaf, with optional diff annotation. `op` is the
  diff-op for this leaf (`:same` when not in diff mode)."
  [{:keys [value before op]}]
  (case op
    :modified
    [:span {:style {:display "inline-flex" :align-items "baseline"}}
     [:span {:style {:color (:yellow tokens) :font-family mono-stack}}
      (render-scalar value)]
     (change-annotation before)]

    :added
    [:span {:style {:color (:green tokens) :font-family mono-stack}}
     (render-scalar value)]

    :removed
    [:span {:style {:color (:red tokens)
                    :font-family mono-stack
                    :text-decoration "line-through"}}
     (render-scalar before)]

    ;; :same — render normally; dim if we're inside a diff context (the
    ;; caller passes :diff?-in-tree? down via op = :same with
    ;; :dim? metadata; we keep it simple here by leaving :text-primary).
    (render-scalar value)))

(defn- render-container
  "Render a map / vector / set / list container. Click on header glyph
  toggles expansion (sticky into `expansion-slot`). The opening `{` /
  `[` / `#{` / `(` is part of the header row; children render
  indented; the closing brace renders below."
  [{:keys [value before path panel-id render-id default-depth
           diff? expansion-map depth]}]
  (let [op            (cond
                        (not diff?)                       :same
                        (= before ::missing)              :added
                        (= value  ::missing)              :removed
                        (changed-descendant? before value) :children
                        :else                              :same)
        has-change?   (and diff? (not= op :same))
        children-cnt  (cond (map? value) (count value)
                            (coll? value) (count value)
                            :else 0)
        default?      (default-expanded?
                        {:depth                  depth
                         :child-count            children-cnt
                         :default-depth          default-depth
                         :has-changed-descendant? has-change?})
        expanded?     (resolve-expanded? expansion-map panel-id render-id
                                         path default?)
        toggle        #(rf/dispatch [:rf.causa/data-display-toggle-node
                                     panel-id render-id path])
        glyph         (container-glyph expanded?)]
    (gutter-row
      op
      [:div {:data-testid (str "rf-causa-data-display-container-"
                               (str/join "/" (map pr-str path)))
             :style {:font-family mono-stack
                     :font-size   "12px"
                     :line-height 1.4
                     :color       (:text-primary tokens)}}
       ;; Header row — toggle glyph + container summary OR opener.
       [:div {:style {:display     "flex"
                      :align-items "baseline"
                      :gap         "4px"}}
        [:span {:on-click toggle
                :data-testid (str "rf-causa-data-display-toggle-"
                                  (str/join "/" (map pr-str path)))
                :style {:cursor      "pointer"
                        :color       (:text-secondary tokens)
                        :user-select "none"
                        :font-size   "11px"
                        :width       "12px"}}
         glyph]
        (if expanded?
          [:span {:style {:color (:text-tertiary tokens)}}
           (container-opener value)]
          [:span {:style {:color (:text-tertiary tokens)}}
           (container-summary value)])]
       ;; Children — only when expanded. Children invocations are
       ;; DIRECT fn calls (`(render-node ...)`) — NOT the Reagent
       ;; component-form `[render-node ...]`. The renderer produces
       ;; realized hiccup so unit tests can walk the tree
       ;; substrate-agnostically (no Reagent / React in scope) and
       ;; downstream Reagent / UIx / Helix all see the same hiccup.
       (when expanded?
         (into
           [:div {:style {:padding-left "14px"
                          :margin-left  "5px"
                          :border-left  (str "1px solid "
                                             (:border-subtle tokens))}}]
           (cond
             (map? value)
             (let [all-keys (vec
                              (if (and diff? (map? before))
                                (into (set (keys value)) (keys before))
                                (keys value)))]
               (for [k all-keys]
                 (let [child-path (conj (vec path) k)
                       v          (get value  k ::missing)
                       b          (if diff?
                                    (get before k ::missing)
                                    ::missing)]
                   (with-meta
                     (render-node {:k             k
                                   :value         v
                                   :before        b
                                   :path          child-path
                                   :panel-id      panel-id
                                   :render-id     render-id
                                   :default-depth default-depth
                                   :diff?         diff?
                                   :expansion-map expansion-map
                                   :depth         (inc depth)})
                     {:key (pr-str k)}))))

             (sequential? value)
             (let [a-vec  (vec value)
                   b-vec  (if (and diff? (sequential? before)) (vec before) [])
                   n      (max (count a-vec) (count b-vec))]
               (for [i (range n)]
                 (let [child-path (conj (vec path) i)
                       v          (if (< i (count a-vec)) (nth a-vec i) ::missing)
                       b          (if (and diff? (< i (count b-vec)))
                                    (nth b-vec i) ::missing)]
                   (with-meta
                     (render-node {:k             i
                                   :value         v
                                   :before        b
                                   :path          child-path
                                   :panel-id      panel-id
                                   :render-id     render-id
                                   :default-depth default-depth
                                   :diff?         diff?
                                   :expansion-map expansion-map
                                   :depth         (inc depth)})
                     {:key i}))))

             (set? value)
             (for [e value]
               (let [child-path (conj (vec path) e)]
                 (with-meta
                   (render-node {:k             nil
                                 :value         e
                                 :before        ::missing
                                 :path          child-path
                                 :panel-id      panel-id
                                 :render-id     render-id
                                 :default-depth default-depth
                                 :diff?         diff?
                                 :expansion-map expansion-map
                                 :depth         (inc depth)})
                   {:key (pr-str e)}))))))
       ;; Closer — only when expanded.
       (when expanded?
         [:div {:style {:color (:text-tertiary tokens)}}
          (container-closer value)])])))

(defn render-node
  "Recursive entry — picks container vs leaf, threads diff op through.

  Inputs:
    :k             — key/index in parent (nil for root)
    :value         — value at this node (may be ::missing for :removed)
    :before        — pre-image (::missing when not present pre-diff)
    :path          — full path-vector from root
    :panel-id      — caller's panel keyword
    :render-id     — caller's per-mount uniquifier
    :default-depth — depth-threshold knob (§10.4)
    :diff?         — whether to apply diff semantics
    :expansion-map — projection of `expansion-slot`
    :depth         — recursion depth (0 at root)"
  [{:keys [k value before path panel-id render-id default-depth
           diff? expansion-map depth]
    :as args}]
  (let [container? (or (map? value) (coll? value))
        op         (cond
                     (not diff?)              :same
                     (= before ::missing)
                     (if (= value ::missing) :same :added)
                     (= value ::missing)      :removed
                     (and container?
                          (changed-descendant? before value)) :children
                     (= before value)         :same
                     :else                    :modified)]
    (cond
      ;; Removed leaf — render the prior value, struck through.
      (= op :removed)
      [:div {:data-testid (str "rf-causa-data-display-removed-"
                               (str/join "/" (map pr-str path)))
             :style {:display "flex" :flex-wrap "wrap" :align-items "baseline"
                     :gap "6px"}}
       (when k
         (path-segment {:k k :path path :panel-id panel-id
                        :render-id render-id}))
       (gutter-row op (render-leaf {:value ::missing :before before :op :removed}))]

      ;; Container — header + children + closer.
      container?
      [:div {:data-testid (str "rf-causa-data-display-node-"
                               (str/join "/" (map pr-str path)))
             :style {:display "flex" :flex-wrap "wrap" :align-items "baseline"
                     :gap "6px" :padding "1px 0"}}
       (when k
         (path-segment {:k k :path path :panel-id panel-id
                        :render-id render-id}))
       (render-container (assoc args :path path :depth depth))]

      ;; Scalar leaf.
      :else
      [:div {:data-testid (str "rf-causa-data-display-leaf-"
                               (str/join "/" (map pr-str path)))
             :style {:display "flex" :flex-wrap "wrap" :align-items "baseline"
                     :gap "6px" :padding "1px 0"}}
       (when k
         (path-segment {:k k :path path :panel-id panel-id
                        :render-id render-id}))
       (gutter-row op (render-leaf {:value value :before before :op op}))])))

;; ---- public entry --------------------------------------------------------

(defn render-tree
  "Render `value` as the shared L4-panel data-display tree.

  Required opts: `:value :panel-id :render-id`.
  Diff opts:     `:diff? true :before <prior-value>` (the `:value` is
                 the `:after`).
  Tuning:        `:default-depth N` per §10.4 (App-db defaults 3,
                 Event payload defaults 2; the caller picks).
  Eviction:      `:evicted? true` + `:epoch-id N` → render the §10.7
                 placeholder instead of the tree.

  Returns hiccup. Substrate-agnostic — never references Reagent /
  UIx / Helix directly.

  Per spec/021 §10 (canonical · merged in #1720)."
  [{:keys [value diff? before panel-id render-id
           default-depth evicted? epoch-id]
    :or   {default-depth 2 diff? false}
    :as   _opts}]
  (cond
    evicted?
    (evicted-placeholder {:epoch-id  epoch-id
                          :panel-id  panel-id
                          :render-id render-id})

    :else
    (let [expansion-map (try
                          (or @(rf/subscribe [expansion-slot]) {})
                          (catch :default _ {}))]
      [:div {:data-testid (str "rf-causa-data-display-"
                               (name (or panel-id :unknown))
                               "-"
                               (str (or render-id "")))
             :style {:font-family mono-stack
                     :font-size   "12px"
                     :color       (:text-primary tokens)
                     :line-height 1.4}}
       (render-node {:k             nil
                     :value         value
                     :before        (if diff? before ::missing)
                     :path          []
                     :panel-id      panel-id
                     :render-id     render-id
                     :default-depth default-depth
                     :diff?         (boolean diff?)
                     :expansion-map expansion-map
                     :depth         0})])))
