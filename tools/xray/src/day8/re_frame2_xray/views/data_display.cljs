(ns day8.re-frame2-xray.views.data-display
  "Xray's first-class data-display widget — roll-your-own CLJS-value
  renderer (rf2-oqa60 phase 1).

  ## What this is

  ONE renderer for every Xray surface that shows a CLJS value:
  App-db, Trace per-event payload, Sub value inspector, Machine
  snapshot drill-in. The widget produces pure hiccup, owns its
  expansion state in re-frame app-db, and reads colour through CSS
  token variables so light + dark themes resolve at paint time
  without a re-render.

  ## What replaces what

  - `views/edn-widget/widget` — superseded for current-state browse
    (phase 1 wires the App-DB panel here directly; phases 2-5 migrate
    the remaining call sites).
  - `theme/data-inspector` — sentinels (`:rf/redacted`, `:rf/large`)
    become first-class types INSIDE this widget; the chrome wrapper
    goes away (D3=a per rf2-sndui).
  - `data-display/render` — diff renderer subsumed (phase 5; D5=a per
    rf2-sndui). The diff path is now an opt-in mode on this same
    widget — pass `:before` to render with gutter glyphs +
    `← changed from <prior>` annotations.
  - `binaryage/cljs-devtools` dep — dropped from the project; this
    widget renders CLJS values natively from CLJS itself (rf2-oqa60).

  ## Public API

      [data-display value]                ;; browse (no diff)
      [data-display value opts]           ;; browse / diff
      [data-display-diff before after]    ;; diff convenience
      [data-display-diff before after opts]

      [mini value]              ;; one-line inline (no expansion)
      [mini value max-len]      ;; with width cap

  `opts` keys:

  - `:panel-id`     (optional, default `:rf.xray.data-display/anon`)
                    distinguishes per-panel expansion state.
  - `:site-id`      (optional, rf2-pvsxs) — when supplied, becomes the
                    second component of the per-node expansion key
                    INSTEAD of the auto-generated mount-id. Lets the
                    same logical call site survive a panel-leave-and-
                    return round-trip (auto-mount-id changes on remount;
                    a stable site-id does not). Omit to keep the per-
                    call-site isolation default (two `[data-display]`
                    mounts side-by-side stay independent).
  - `:default-expanded-depth` (optional, default 2) — first-render
                    expansion depth before operator clicks.
  - `:max-inline-width` (optional, default 60) — character budget
                    before forced-vertical layout.
  - `:max-depth` (optional, default 16) — hard cap on recursion
                    depth; deeper levels render `{…}` collapsed.
  - `:before` (optional) — when supplied, the widget renders in DIFF
                    mode. The `value` arg is treated as the `after`
                    side; the supplied `:before` is the prior value.
                    Gutter glyphs + colours paint per node
                    (`+` added · `-` removed · `~` modified ·
                    `◴` children-changed); modified leaves get an
                    inline `← changed from <prior>` annotation;
                    ancestors of any changed descendant force open
                    regardless of the default-expand heuristic.
  - `:popup-affordance?` (optional, default false) — rf2-l4625; when
                    true the widget renders a top-right ⊕ icon button
                    that dispatches
                    `[:rf.xray.data-display-popup/open mount-id payload]`
                    (the open event registered by
                    `views.data-display-popup/install-events!`). The
                    popup-mount-id is derived from this widget's own
                    mount-id, so re-clicking raises the existing popup
                    rather than spawning a duplicate. Opt-in per
                    call-site; panels enable the affordance where the
                    inline widget is genuinely cramped (App-DB whole-
                    tree, machine snapshots, sub values).
  - `:card?`         (optional, default false) — rf2-63ie5; when true
                    the widget's outer container carries the inspector-
                    card chrome (background `:bg-1`, 1px `:border-
                    default` border, `8px` radius, `8px 10px` padding,
                    `8px` margin-bottom) so multiple top-level mounts
                    in the same panel read as DISTINCT cards rather
                    than blending into one continuous block. Theme-
                    aware via tokens — both light + dark resolve at
                    paint time. Opt-in per call-site: inline mounts
                    (table cells, popup contents that already carry
                    modal chrome, diff sub-renderers) leave it off;
                    panels with multiple top-level inspector mounts
                    (App-DB's TOP + per-`:rf/*` sections; Handler's
                    side-by-side event-detail mounts) opt in.

  Drop the `:render-id` arg from the old facade — mount-id is now
  auto-generated internally per D4=a (rf2-sndui).

  ## Per-call-site isolation (rf2-sndui D4=a · rf2-pvsxs opt-out)

  Each `[data-display …]` mount auto-assigns a UUID `mount-id` on
  first render (captured in a form-2 outer `let`). Two mounts side-
  by-side in the same panel get independent expansion state. The
  expansion key is `[panel-id mount-id path]` by default.

  When the same logical call site needs to SURVIVE a remount (e.g.
  the App-DB tab unmounts on tab-switch and re-mounts on return),
  pass a stable `:site-id` in `opts`. The expansion key becomes
  `[panel-id site-id path]` — independent of remount cadence. The
  cost is opt-in: a consumer that passes the SAME `:site-id` from
  two mount sites would have them share expansion state. Per-call-
  site isolation is the default; persistence-across-unmount is opt-in.

  ## Why pure hiccup + no Reagent direct

  Substrate-agnostic — downstream adapters (Reagent, UIx, Helix) all
  see the same hiccup. The mount-id capture uses Reagent form-2
  semantics in `data-display` itself (rf2 substrate is currently
  Reagent in tools); the rest of the renderer is pure data
  transformations + `rf/subscribe` reads.

  ## CSS token classes

  The widget paints via CSS-variable strings from `theme.tokens` — no
  per-theme code path. The class table maps each leaf type to its
  token; the active theme class scope on the shell root decides which
  hex resolves.

  | Leaf type        | Token              | Bracket style            |
  |------------------|--------------------|--------------------------|
  | nil              | `:syntax-nil`      | n/a                      |
  | boolean          | `:syntax-boolean`  | n/a                      |
  | integer / float  | `:syntax-number`   | n/a                      |
  | string           | `:syntax-string`   | n/a                      |
  | keyword          | `:syntax-keyword`  | n/a                      |
  | symbol           | `:syntax-symbol`   | n/a                      |
  | uuid / regex     | `:info`            | n/a                      |
  | inst / date      | `:info`            | n/a                      |
  | fn               | `:text-tertiary`   | (italic)                 |
  | :rf/redacted     | `:magenta`         | (chip)                   |
  | :rf.size/elided  | `:yellow`          | (chip)                   |
  | map              | `:text-tertiary`   | `{` `}`                  |
  | vector           | `:text-secondary`  | `[` `]`                  |
  | list / seq       | `:text-secondary`  | `(` `)`                  |
  | set              | `:text-secondary`  | `#{` `}`                 |
  | map-entry        | `:accent`          | `[` `]` (accent tone)    |
  | record           | `:info`            | `#user.Rec{` `}`         |
  | js object        | `:text-tertiary`   | `#object[` `]`           |

  Per rf2-79ojx the per-type tokens were renamed + the palette retuned
  to an editor-syntax-highlight scheme (One Dark / One Light). Five
  scalar types now span four hue families: keyword magenta, string
  green, number orange, boolean gold, nil grey."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-xray.views.data-display-protocol :as ddp]
            ;; rf2-x16b1 — load default IXrayDataDisplay formatters
            ;; for uuid + inst. Requiring for side-effect (extend-type).
            ;; Consumers that extend the same types win — `extend-type`
            ;; installs the most-recently-loaded impl.
            [day8.re-frame2-xray.views.data-display-default-formatters]))

;; =========================================================================
;; expansion state — lives in :rf.xray.data-display/expansion under :rf/xray
;; =========================================================================

(def expansion-slot
  "App-db slot holding the per-node expansion overrides. Public so
  the consuming panel's reset affordance can clear it.

  Distinct from the legacy `:rf.xray/data-display-expansion` slot
  used by `data-display/render` — keeping them separate lets the old
  engine and the new widget coexist during the phased rollout."
  :rf.xray.data-display/expansion)

(defn expansion-key
  "Compose the per-node expansion key. Pure data, JVM-portable."
  [panel-id mount-id path]
  [panel-id mount-id (vec path)])

(rf/reg-sub expansion-slot
  (fn [db _] (get db expansion-slot)))

(rf/reg-event-db :rf.xray.data-display/toggle-node
  (fn [db [_ panel-id mount-id path rendered-expanded?]]
    ;; rf2-y59tb — first click MUST invert the currently-visible state.
    ;;
    ;; The widget renders `default-expanded` paths (top-level nodes,
    ;; depth ≤ `default-expanded-depth`) open BEFORE the user clicks,
    ;; even though no override is stored. If the reducer flipped from
    ;; a hard-coded assumption (e.g. "first click opens") it would
    ;; emit the same state the user already sees — a silent no-op on
    ;; the first click.
    ;;
    ;; The dispatch payload now carries `rendered-expanded?` — the
    ;; value `resolve-expanded?` returned for this path on the last
    ;; render (i.e. what the user currently sees). When no override
    ;; is stored the reducer flips from that visible state; when an
    ;; override IS stored it flips the override (idempotent given a
    ;; consistent dispatcher).
    (let [k       (expansion-key panel-id mount-id path)
          current (get-in db [expansion-slot k])
          next?   (if (contains? current :expanded?)
                    (not (boolean (:expanded? current)))
                    (not (boolean rendered-expanded?)))]
      (assoc-in db [expansion-slot k] {:expanded? next?}))))

(rf/reg-event-db :rf.xray.data-display/set-node
  (fn [db [_ panel-id mount-id path expanded?]]
    (assoc-in db [expansion-slot (expansion-key panel-id mount-id path)]
              {:expanded? (boolean expanded?)})))

(rf/reg-event-db :rf.xray.data-display/reset-expansion
  (fn [db _]
    (dissoc db expansion-slot)))

(defn resolve-expanded?
  "Pure projection — given the per-render expansion map, the path,
  and the default-heuristic result, return whether THIS node renders
  expanded. The operator's sticky override (if present) wins."
  [expansion-map panel-id mount-id path default?]
  (let [k        (expansion-key panel-id mount-id path)
        override (get expansion-map k)]
    (if (contains? override :expanded?)
      (boolean (:expanded? override))
      (boolean default?))))

;; =========================================================================
;; type classification — sentinels recognised as first-class types
;; =========================================================================

(defn redacted-sentinel?
  "`:rf/redacted` bare keyword — spec/015 primary opaque sentinel."
  [v]
  (= :rf/redacted v))

(defn large-sentinel?
  "`{:rf/large {:bytes N :head s}}` size-elision sentinel."
  [v]
  (and (map? v)
       (= 1 (count v))
       (let [[k m] (first v)]
         (and (= :rf/large k) (map? m)))))

(defn redacted+size-sentinel?
  "Combined `{:rf/redacted {:bytes N}}` — sensitive + size-aware."
  [v]
  (and (map? v)
       (= 1 (count v))
       (let [[k m] (first v)]
         (and (= :rf/redacted k) (map? m)))))

(defn map-entry?*
  "True for clojure.lang.MapEntry — distinct from a 2-vector.
  ClojureScript's `MapEntry` shows up as a 2-element vector with
  identical print but distinct nominal type. The `*` suffix avoids
  shadowing `cljs.core/map-entry?` for callers that `:refer` it."
  [v]
  (instance? cljs.core/MapEntry v))

(defn record?*
  "True for a defrecord instance. CLJS records carry the
  `cljs$lang$type` static field."
  [v]
  (and (map? v)
       (try (some? (.-cljs$lang$type v))
            (catch :default _ false))))

(defn collection-kind
  "Classify a value into one of #{:map :vector :list :set :map-entry
  :record :seq :scalar :sentinel-redacted :sentinel-large :nil
  :string :number :keyword :symbol :boolean :uuid :regex :fn :other}.

  Pure function; no rendering."
  [v]
  (cond
    (nil? v)                       :nil
    (redacted-sentinel? v)         :sentinel-redacted
    (large-sentinel? v)            :sentinel-large
    (redacted+size-sentinel? v)    :sentinel-redacted-size
    (boolean? v)                   :boolean
    (keyword? v)                   :keyword
    (symbol? v)                    :symbol
    (string? v)                    :string
    (number? v)                    :number
    (uuid? v)                      :uuid
    (regexp? v)                    :regex
    (fn? v)                        :fn
    (map-entry?* v)                 :map-entry
    (record?* v)                   :record
    (map? v)                       :map
    (vector? v)                    :vector
    (set? v)                       :set
    (list? v)                      :list
    (seq? v)                       :seq
    :else                          :other))

;; =========================================================================
;; diff mode — pure helpers (op classification, gutter mapping)
;; =========================================================================
;;
;; Phase 5 (rf2-q3dzw, D5=a per rf2-sndui) subsumes the legacy
;; `data-display.render` diff engine into this widget. Diff is an
;; opt-in MODE on the same renderer: when the caller passes `:before`
;; the widget paints gutter glyphs + `← changed from <prior>`
;; annotations in place, force-expands the ancestor chain over any
;; changed descendant, and dims `:same` rows so the eye lands on the
;; change.

(def missing-sentinel
  "Marker for a `:before` / `:after` slot that does not exist in its
  side of the diff (e.g. an added key has `::missing` for `:before`).
  Distinct from `nil` (which is a real CLJS value). Public so tests
  can assert the diff-op classification against it."
  ::missing)

(defn diff-op
  "Classify a (before, after) pair into a diff op keyword:

    :added    — before is `::missing`, after exists
    :removed  — before exists, after is `::missing`
    :modified — both exist and differ (leaf-level)
    :same     — both exist and are equal

  Pure data; JVM-portable."
  [before after]
  (cond
    (= before ::missing) (if (= after ::missing) :same :added)
    (= after  ::missing) :removed
    (= before after)     :same
    :else                :modified))

(declare changed-descendant?*)

(defn changed-descendant?
  "True when at least one descendant in (before, after) differs.
  Walks maps + sequentials + sets; pure. Returns true for primitive
  mismatches at the root too — so a caller can use this to drive
  ancestor-open.

  Always returns a primitive boolean (never nil) so callers can
  dispatch on `true?` / `false?` without nil-coercion."
  [before after]
  (boolean (changed-descendant?* before after)))

(defn- changed-descendant?*
  [before after]
  (cond
    ;; Either side missing → caller treats this as added / removed,
    ;; which is itself a change.
    (or (= before ::missing) (= after ::missing))
    (not (and (= before ::missing) (= after ::missing)))

    (and (map? before) (map? after))
    (or (not= (set (keys before)) (set (keys after)))
        (some (fn [k] (changed-descendant?* (get before k ::missing)
                                            (get after  k ::missing)))
              (into (set (keys before)) (keys after))))

    (and (sequential? before) (sequential? after))
    (let [a-vec (vec after)
          b-vec (vec before)
          n     (max (count a-vec) (count b-vec))]
      (or (not= (count a-vec) (count b-vec))
          (boolean
            (some true?
                  (for [i (range n)]
                    (changed-descendant?*
                      (if (< i (count b-vec)) (nth b-vec i) ::missing)
                      (if (< i (count a-vec)) (nth a-vec i) ::missing)))))))

    (and (set? before) (set? after))
    (not= before after)

    :else (not= before after)))

(def op->gutter-glyph
  "Per-op gutter glyph (§10.3 cascade-gutter mapping). Public so tests
  can assert the mapping without re-deriving."
  {:added    "+"
   :removed  "-"
   :modified "~"
   :children "◴"
   :same     " "})

(def op->gutter-tone-key
  "Per-op token-key for the gutter glyph + 3px left border colour."
  {:added    :green
   :removed  :red
   :modified :yellow
   :children :accent
   :same     :text-tertiary})

(defn- gutter-colour
  "Resolve the gutter colour for an op via the token table."
  [op]
  (get tokens (op->gutter-tone-key op) (:text-tertiary tokens)))

(defn- container-op
  "Classify a container's diff op given (before, after). Returns
  `:added`/`:removed`/`:children`/`:same` (containers never report
  `:modified` directly — a per-leaf modification surfaces as
  `:modified` on the leaf row, with `:children` on the ancestor)."
  [before after]
  (cond
    (= before ::missing)                       :added
    (= after  ::missing)                       :removed
    (changed-descendant? before after)         :children
    :else                                      :same))

(defn- gutter-row
  "Wrap `body` with the diff gutter (3px left border + glyph). When
  the op is `:same` the wrapper is invisible (transparent border, blank
  glyph) so non-diff renders share the same hiccup shape as diff
  renders.

  ## rf2-1bra5 — inline-flex, not flex

  Switched from `display: flex` (block-level) to `inline-flex` so a
  diff'd scalar leaf composes inline with its preceding key inside a
  map-row grid. Pre-fix the block-level `<div>` forced the leaf onto
  its own line (the per-row flex container couldn't keep the key+value
  on one line — `flex-wrap: wrap` pushed the wide block-div below the
  key, producing the `:show-parity?` / `:threshold` two-line rows Mike
  measured at ~28.79px against the inline ~17.79px sibling rows).

  Returns an `[:span ...]` (display: inline-flex) so it nests inside a
  grid cell without breaking the row."
  [op body]
  (let [active? (not= :same op)]
    [:span {:data-rf-diff-op (name op)
            :style {:display      "inline-flex"
                    :align-items  "baseline"
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
     [:span {:style {:flex 1 :min-width 0}} body]]))

(defn- change-annotation
  "Inline `← changed from <prior>` chip rendered to the right of a
  diff'd leaf. Pure hiccup."
  [before]
  [:span {:data-rf-diff-annotation "1"
          :style {:margin-left "8px"
                  :color       (:text-secondary tokens)
                  :font-family sans-stack
                  :font-size   "11px"
                  :font-style  "italic"}}
   (str "← changed from " (try (pr-str before)
                               (catch :default _ (str before))))])

;; =========================================================================
;; scalar rendering (no expansion)
;; =========================================================================

(defn- str-pad-quote
  "Render a string with surrounding quotes; preserve newlines as `\\n`
  for inline rendering."
  [s]
  (try
    (pr-str s)
    (catch :default _ (str "\"" s "\""))))

(defn- token-style
  [token-key]
  {:color       (get tokens token-key)
   :font-family mono-stack})

(defn render-scalar
  "Render a single non-collection value as `[:span ...]` hiccup. Pure
  function — no expansion state, no rf reads."
  [v]
  (case (collection-kind v)
    :nil      [:span {:data-rf-type "nil"
                      :style (token-style :syntax-nil)} "nil"]
    :boolean  [:span {:data-rf-type "boolean"
                      :style (token-style :syntax-boolean)} (str v)]
    :keyword  [:span {:data-rf-type "keyword"
                      :style (token-style :syntax-keyword)} (str v)]
    :symbol   [:span {:data-rf-type "symbol"
                      :style (token-style :syntax-symbol)} (str v)]
    :string   [:span {:data-rf-type "string"
                      :style (token-style :syntax-string)} (str-pad-quote v)]
    :number   [:span {:data-rf-type "number"
                      :style (token-style :syntax-number)} (str v)]
    :uuid     [:span {:data-rf-type "uuid"
                      :style (token-style :info)} (str "#uuid \"" v "\"")]
    :regex    [:span {:data-rf-type "regex"
                      :style (token-style :info)} (str v)]
    :fn       [:span {:data-rf-type "fn"
                      :style (merge (token-style :text-tertiary)
                                    {:font-style "italic"})}
               (let [nm (try (some-> v meta :name str)
                             (catch :default _ nil))]
                 (if (and nm (seq nm)) (str "#fn[" nm "]") "#fn"))]
    :sentinel-redacted
    [:span {:data-rf-type "rf-redacted"
            :data-testid  "rf-xray-data-display-redacted"
            :title        "Redacted — not revealable (spec/015)"
            :style {:display       "inline-flex"
                    :align-items   "center"
                    :gap           "4px"
                    :padding       "0 6px"
                    :border-radius "3px"
                    :background    "color-mix(in srgb, var(--rf-xray-magenta) 12%, transparent)"
                    :color         (:magenta tokens)
                    :font-family   mono-stack
                    :font-size     "11px"
                    :font-style    "italic"
                    :text-transform "lowercase"
                    :letter-spacing "0.5px"
                    :user-select   "none"}}
     [:span {:style {:font-size "10px"}} "●"]
     "redacted"]
    :sentinel-redacted-size
    (let [{:keys [bytes]} (val (first v))]
      [:span {:data-rf-type "rf-redacted-size"
              :data-testid  "rf-xray-data-display-redacted-size"
              :title        "Redacted with size — not revealable (spec/015)"
              :style {:display       "inline-flex"
                      :align-items   "center"
                      :gap           "4px"
                      :padding       "0 6px"
                      :border-radius "3px"
                      :background    "color-mix(in srgb, var(--rf-xray-magenta) 12%, transparent)"
                      :color         (:magenta tokens)
                      :font-family   mono-stack
                      :font-size     "11px"
                      :font-style    "italic"
                      :user-select   "none"}}
       [:span {:style {:font-size "10px"}} "●"]
       "redacted"
       (when (and bytes (pos? bytes))
         [:span {:style {:color (:text-tertiary tokens)
                         :font-style "normal"
                         :margin-left "4px"}}
          (str "· " bytes " bytes")])])
    :sentinel-large
    (let [{:keys [bytes head]} (val (first v))]
      [:span {:data-rf-type "rf-large"
              :data-testid  "rf-xray-data-display-large"
              :title        (when head (str "Head preview: " head))
              :style {:display       "inline-flex"
                      :align-items   "center"
                      :gap           "4px"
                      :padding       "0 6px"
                      :border-radius "3px"
                      :background    "color-mix(in srgb, var(--rf-xray-yellow) 12%, transparent)"
                      :color         (:yellow tokens)
                      :font-family   mono-stack
                      :font-size     "11px"
                      :user-select   "none"}}
       [:span {:style {:font-size "10px"}} "●"]
       "large"
       (when bytes
         [:span {:style {:color (:text-tertiary tokens) :margin-left "4px"}}
          (str "· " bytes " bytes")])])
    ;; Fallback for unknown shapes — pr-str.
    [:span {:data-rf-type "other"
            :style (token-style :text-primary)}
     (try (pr-str v) (catch :default _ (str v)))]))

;; =========================================================================
;; delimiters — bracket characters + style per collection kind
;; =========================================================================

(def delim
  "Per-collection-kind opener/closer/style key map. Bracket COLOUR
  differs per kind so map-entry vs 2-vector is visually distinct.

  - `:open`/`:close` are the bracket characters
  - `:tone-key` is the token key used to colour the brackets

  Public so tests can assert the bracket characters + tone-keys are
  stable across the test surface."
  {:map        {:open "{"  :close "}"  :tone-key :text-tertiary}
   :vector     {:open "["  :close "]"  :tone-key :text-secondary}
   :list       {:open "("  :close ")"  :tone-key :text-secondary}
   :set        {:open "#{" :close "}"  :tone-key :text-secondary}
   :seq        {:open "("  :close ")"  :tone-key :text-secondary}
   :map-entry  {:open "["  :close "]"  :tone-key :accent}
   :record     {:open "{"  :close "}"  :tone-key :info}})

(defn- record-tag
  "Render `#user.MyRec` prefix for a defrecord instance. CLJS records
  expose the constructor's name via `(.-name (type v))`."
  [v]
  (try
    (let [nm (.-name (type v))]
      (str "#" nm))
    (catch :default _ "#record")))

(defn- bracket
  "Single bracket character with the kind's delimiter colour. The
  `:edge` arg is `:open` or `:close`."
  [kind edge value]
  (let [{:keys [tone-key] :as d} (delim kind)
        ch (or (d edge) "?")
        text (cond
               (and (= kind :record) (= edge :open)) (str (record-tag value) ch)
               :else ch)]
    [:span {:style       {:color       (get tokens tone-key)
                          :font-family mono-stack}
            :data-rf-bracket (name edge)}
     text]))

;; =========================================================================
;; inline preview — `▸ {:a 1, :b 2, …}` etc.
;; =========================================================================

(declare inline-preview-string)

(defn- inline-scalar-str
  "Compact one-line string preview of a single value. Containers
  render as their `{…N keys}` / `[…N items]` placeholder — we never
  recurse into a child container's contents from inside the preview
  (one-level only). That keeps a `:deeply {:nested {:secret 1}}`
  preview from leaking `:secret` into a collapsed-collection summary."
  [v]
  (case (collection-kind v)
    :nil        "nil"
    :boolean    (str v)
    :keyword    (str v)
    :symbol     (str v)
    :string     (str-pad-quote v)
    :number     (str v)
    :uuid       (str "#uuid \"" v "\"")
    :regex      (str v)
    :fn         "#fn"
    :sentinel-redacted        "redacted"
    :sentinel-redacted-size   "redacted"
    :sentinel-large           "large"
    (:map :vector :list :seq :set :map-entry :record)
    (let [{:keys [open close]} (delim (collection-kind v))
          n (try (count v) (catch :default _ 0))
          noun (case (collection-kind v)
                 :map " keys"
                 :record " keys"
                 " items")]
      (str open "…" n noun close))
    (try (pr-str v) (catch :default _ (str v)))))

(defn inline-preview-string
  "Build a one-line preview of a collection. Returns a string; not
  hiccup. Used for collapsed-collection summaries.

  Pure function; safe to call on the JVM (`pr-str` portable). Caller
  decides max-elements and max-chars.

  Strategy: try first N elements; if the joined string fits within
  `max-chars`, return `\"{ :a 1, :b 2, :c 3 }\"`; if some fit, return
  `\"{:a 1, :b 2, …}\"`; if none fit, return the fallback
  `\"{…3 keys}\"` / `\"[…5 items]\"`."
  [v max-elements max-chars]
  (let [kind (collection-kind v)
        {:keys [open close]} (delim kind)
        fallback-n  (cond
                      (= kind :map) (count v)
                      (coll? v)     (try (count v) (catch :default _ 0))
                      :else         0)
        fallback-noun (case kind
                        :map     " keys"
                        :set     " items"
                        " items")
        fallback    (str open "…" fallback-n fallback-noun close)
        ;; Take up to max-elements + 1 to detect "more remaining".
        head-seq    (try (cond
                           (map? v)        (take (inc max-elements) v)
                           (set? v)        (take (inc max-elements) v)
                           (sequential? v) (take (inc max-elements) v)
                           :else           [])
                         (catch :default _ []))
        head        (take max-elements head-seq)
        more?       (> (count head-seq) max-elements)
        item-str    (fn [el]
                      (cond
                        (and (= kind :map)
                             (or (map-entry? el)
                                 (and (vector? el) (= 2 (count el)))))
                        (str (inline-scalar-str (first el))
                             " " (inline-scalar-str (second el)))
                        :else
                        (inline-scalar-str el)))
        joined      (str/join ", " (map item-str head))
        with-more   (str joined (when more? (if (seq joined) ", …" "…")))
        result      (str open with-more close)]
    (if (<= (count result) max-chars)
      result
      ;; Try one-element preview as a middle ground.
      (let [one  (when (seq head) (item-str (first head)))
            mid  (str open one (when (or more? (> (count head) 1)) ", …") close)]
        (if (and one (<= (count mid) max-chars))
          mid
          fallback)))))

;; =========================================================================
;; recursive renderer — produces hiccup
;; =========================================================================

(declare render-node)

(defn- children-of
  "Return a seq of `[child-key child-value]` pairs for a collection.
  Returns `nil` for non-collections. `child-key` is the path segment
  to use; for sets it's the value itself."
  [v]
  (case (collection-kind v)
    :map       (try (seq v) (catch :default _ nil))
    :map-entry (list [0 (first v)] [1 (second v)])
    :record    (try (seq v) (catch :default _ nil))
    :vector    (map-indexed (fn [i x] [i x]) v)
    :list      (map-indexed (fn [i x] [i x]) v)
    :seq       (map-indexed (fn [i x] [i x]) v)
    :set       (map (fn [x] [x x]) v)
    nil))

(defn- container? [kind]
  (contains? #{:map :vector :list :set :seq :map-entry :record} kind))

(defn- child-count
  "Count children safely (don't realise lazy infinite seqs)."
  [v kind]
  (case kind
    :map        (count v)
    :record     (count v)
    :vector     (count v)
    :set        (count v)
    :map-entry  2
    (:list :seq) (try (count (take 1001 v)) (catch :default _ 0))
    0))

(defn- key-segment
  "Render a single map/vector key/index segment. Returns hiccup.
  Used to label children inside a container."
  [k]
  (cond
    (keyword? k) [:span {:style (token-style :syntax-keyword)} (str k)]
    (string? k)  [:span {:style (token-style :syntax-string)} (str-pad-quote k)]
    (number? k)  [:span {:style (token-style :syntax-number)} (str k)]
    (symbol? k)  [:span {:style (token-style :syntax-symbol)} (str k)]
    (nil? k)     [:span {:style (token-style :syntax-nil)} "nil"]
    :else        [:span {:style (token-style :text-primary)}
                  (try (pr-str k) (catch :default _ (str k)))]))

(defn- default-expanded?
  "Pure depth/size heuristic — shallow nodes expand, deep + wide
  nodes collapse. The operator's sticky override wins via
  `resolve-expanded?`.

  In diff mode, a container with a changed descendant force-expands
  regardless of depth — operator never has to drill to find the
  change (spec/021 §10.4)."
  [{:keys [depth child-count default-expanded-depth
           has-changed-descendant?]
    :or   {default-expanded-depth 2}}]
  (cond
    has-changed-descendant?                 true
    (<= depth (dec default-expanded-depth)) true
    (= depth default-expanded-depth)        (<= (or child-count 0) 10)
    :else                                   false))

(defn- testid-for
  "Compose a stable data-testid for a node — `[panel-id mount-id path]`."
  [panel-id mount-id path]
  (str "rf-xray-data-display-"
       (name (or panel-id :anon))
       "-" mount-id
       (when (seq path) (str "-" (str/join "/" (map pr-str path))))))

;; rf2-tzvk9 — triangle expand/collapse glyph carries an explicit
;; ≥24×24 click target. The padding inside the existing key-column
;; gutter grows the hit-box without shifting the surrounding layout.
;; Public via the value so tests can assert the computed-width
;; contract without re-deriving the magic numbers.
;;
;; rf2-4aiaq — glyph size bumped 14px → 22px. The 14px glyph cleared
;; the 24px hit-box but read as a hairline against the inspector
;; chrome; Mike's live A/B (pair-debug 2026-05-26) found 22-24px
;; "much more clickable, feels right" vs. 14-18px which still felt
;; understated. 22px keeps the glyph visually balanced against the
;; surrounding 12px scalar rows (~1.83× scale) without dominating
;; the row.
;;
;; Why these numbers — `inline-flex` + the padding/font-size below
;; resolves to approximately 38×30px in Chromium at the shell's
;; default 13px root font-size:
;;
;;   width  ≈ font-size(22) * glyph-advance(~0.7) + padding-l(4) +
;;            padding-r(4)                                ≈ 23.4px
;;   height ≈ font-size(22) * line-height(1)               ≈ 22px
;;            + padding-t(4) + padding-b(4)                ≈ 30px
;;
;; `min-width 24px` pins the floor in both axes even when the glyph's
;; intrinsic width (`▸` is narrower than `▾`) doesn't reach 24px on
;; its own. Both axes clear the 24px WCAG-2.2-flavour comfortable-
;; mouse-target threshold.

(def triangle-min-target-px
  "Minimum click-target width/height the triangle must register
  (rf2-tzvk9). The CLJS-unit-test surface asserts the padding +
  font-size combination resolves to at least this many CSS pixels
  along both axes.

  Public so external surfaces (regression tests, design audits) can
  read the contract without re-deriving the number."
  24)

(def triangle-style
  "Inline-style map applied to every expand/collapse triangle (`▸`
  / `▾`) the data-display widget renders (rf2-tzvk9). One source of
  truth so every triangle gets the SAME hit-box — three call sites
  (depth-capped, expanded ▾, collapsed ▸) and one diff-mode variant
  share this.

  Layout rationale:

  - `inline-flex` + `align-items center` + `justify-content center`
    centres the glyph inside the padded box so the click target is
    visually balanced.
  - `min-width` + `min-height` pin the hit-box to ≥24px in both axes
    even if the glyph's natural metrics fall short (Chromium renders
    `▸` slightly narrower than `▾`).
  - `padding 4px 8px` grows the hit-box WITHOUT pushing the key
    column right — the padding sits inside the existing 4-6px gap
    between the triangle and the first key.
  - `font-size 22px` (rf2-4aiaq) overrides the widget's inherited
    12px so the glyph reads as the primary expand/collapse affordance
    — Mike's live A/B (pair-debug 2026-05-26) found the prior 14px
    glyph read as hairline against the inspector chrome; 22px lands
    in the operator-preferred 22-24px band where the triangle 'feels
    clickable'.
  - `line-height 1` collapses inline leading so the height comes
    purely from font + padding, not from inherited 1.4 leading.

  The colour stays on the subdued `:text-secondary` token — the
  glyph's job is to be *findable*, not loud."
  {:cursor          "pointer"
   :user-select     "none"
   :display         "inline-flex"
   :align-items     "center"
   :justify-content "center"
   :min-width       (str triangle-min-target-px "px")
   :min-height      (str triangle-min-target-px "px")
   :padding         "4px 8px"
   :font-size       "22px"
   :line-height     1
   :color           (:text-secondary tokens)})

(defn- on-toggle
  "Build the click handler for a node's `▸`/`▾` glyph.
  Dispatches the toggle event via `dispatch-fn` — the lexically-
  injected frame-aware dispatcher that `reg-view` binds inside the
  widget's render body. The dispatcher inherits the surrounding
  frame from React context (rf2-y59tb), so the event lands on the
  same frame the widget is mounted under (`:rf/xray` in the App-DB
  panel, `:rf/default` in a standalone playground).

  The payload carries `rendered-expanded?` — the current visible
  state of this node — so the reducer can invert from what the user
  sees on the first click (rf2-y59tb Bug B). Without it, default-
  expanded paths would silently no-op on the first click."
  [dispatch-fn panel-id mount-id path rendered-expanded?]
  (fn [^js e]
    (when e
      (.preventDefault e)
      (.stopPropagation e))
    (dispatch-fn [:rf.xray.data-display/toggle-node
                  panel-id mount-id path rendered-expanded?])))

(defn- collapsed-summary
  "Right-of-triangle summary for a collapsed collection. Shows an
  inline preview if any first elements fit; falls back to the
  `{…N keys}` shape."
  [v kind]
  (let [preview (inline-preview-string v 3 60)]
    [:span {:style       {:color       (get tokens (:tone-key (delim kind)))
                          :font-family mono-stack}
            :data-rf-preview "1"}
     preview]))

(defn- render-container
  "Render a map / vector / list / set / record / map-entry container.

  Returns a hiccup node. Threads:
   - panel-id / mount-id — for testid + dispatch
   - path — vector of segments from root
   - depth — for the default-expand heuristic
   - expansion-map — snapshot from the expansion-slot subscription
   - opts — `:default-expanded-depth`, `:max-depth`, `:max-inline-width`
   - dispatch-fn — frame-aware dispatcher captured by the surrounding
                   `reg-view` body (rf2-y59tb); falls back to `rf/dispatch`
                   when called outside a registered view (test/REPL).
   - diff? / before — when diff? true the renderer paints gutter rows,
                      annotates changed leaves, and force-expands the
                      ancestor chain over any changed descendant."
  [{:keys [value kind panel-id mount-id path depth expansion-map opts
           dispatch-fn diff? before]}]
  (let [{:keys [default-expanded-depth max-depth max-inline-width]
         :or {default-expanded-depth 2 max-depth 16 max-inline-width 60}} opts
        cnt           (child-count value kind)
        empty?        (zero? cnt)
        depth-capped? (>= depth max-depth)
        ;; Diff: classify this container's op vs `before`. Only
        ;; meaningful when diff? is true; otherwise everything is
        ;; `:same` and the gutter rows collapse to a transparent
        ;; left border.
        op            (if diff? (container-op before value) :same)
        has-change?   (and diff? (not= op :same))
        default?      (and (not depth-capped?)
                           (default-expanded?
                             {:depth                   depth
                              :child-count             cnt
                              :default-expanded-depth  default-expanded-depth
                              :has-changed-descendant? has-change?}))
        expanded?     (and (not empty?)
                           (not depth-capped?)
                           (resolve-expanded? expansion-map panel-id mount-id path default?))
        inline-fit?   (and (not empty?)
                           (not has-change?) ; never inline-fit a changed container
                           (<= cnt 3)
                           (every? (fn [[_ cv]]
                                     (not (container? (collection-kind cv))))
                                   (children-of value))
                           (<= (count (inline-preview-string value 5 max-inline-width))
                               max-inline-width))
        ;; `dispatch-fn` is supplied by the reg-view'd outer body so
        ;; the toggle dispatch carries the surrounding frame. Tests
        ;; that drive render-node directly without mounting fall back
        ;; to the global dispatcher's fn form (`rf/dispatch` is a
        ;; macro — use `rf/dispatch*` for HoF callers).
        dispatch-fn   (or dispatch-fn rf/dispatch*)
        ;; rendered-expanded? is the visible state at this node —
        ;; the depth-capped placeholder is always shown collapsed
        ;; (▸), and we use the computed `expanded?` for the rest.
        ;; Threading it into `on-toggle` lets the reducer invert from
        ;; the visible state on the first click.
        toggle-fn     (on-toggle dispatch-fn panel-id mount-id path
                                 (boolean (and expanded? (not depth-capped?))))
        children      (when (and (not empty?) (not depth-capped?) expanded? (not inline-fit?))
                        (children-of value))]
    [:div {:data-testid (testid-for panel-id mount-id path)
           :data-rf-kind (name kind)
           :data-rf-expanded (if expanded? "1" "0")
           :data-rf-diff-op (when diff? (name op))
           :style {:font-family mono-stack
                   :line-height 1.4}}
     ;; ---- header row ---------------------------------------------------
     [:div {:style {:display "flex"
                    :align-items "baseline"
                    :gap "4px"
                    :flex-wrap "wrap"}}
      (cond
        ;; Empty collection — show bracket pair flat, no toggle.
        empty?
        [:span {:style {:display "inline-flex" :align-items "baseline"}}
         (bracket kind :open value)
         (bracket kind :close value)]

        ;; Depth-capped — render the placeholder ellipsis, click expands one level.
        depth-capped?
        [:span {:style {:display "inline-flex" :align-items "center" :gap "4px"}}
         [:span {:on-click   toggle-fn
                 :role       "button"
                 :tabindex   0
                 :aria-expanded false
                 :data-testid (str (testid-for panel-id mount-id path) "-toggle")
                 ;; rf2-tzvk9 — ≥24×24 click target via the shared
                 ;; `triangle-style` (padding + font-size + min-width/
                 ;; -height).
                 :style triangle-style}
          "▸"]
         (bracket kind :open value)
         [:span {:style (token-style :text-tertiary)} "…"]
         (bracket kind :close value)]

        ;; Small enough to render inline — open bracket + items + close
        ;; bracket on one row, NO toggle (already exposed). Maps /
        ;; records render `key value` pairs; sequentials render values
        ;; only; sets render values only (no labelled key).
        inline-fit?
        (let [labelled? (#{:map :record :map-entry} kind)
              pairs     (children-of value)]
          (into [:span {:style {:display "inline-flex"
                                :align-items "baseline"
                                :flex-wrap "wrap"
                                :gap "4px"}}
                 (bracket kind :open value)]
                (concat
                  (apply concat
                         (map-indexed
                           (fn [i [k cv]]
                             (let [sep (when (pos? i)
                                         [:span {:style (token-style :text-tertiary)} ", "])
                                   ks  (when labelled? (key-segment k))
                                   sp  (when labelled?
                                         [:span {:style (token-style :text-tertiary)} " "])]
                               (cond-> []
                                 sep (conj sep)
                                 ks  (conj ks)
                                 sp  (conj sp)
                                 true (conj (render-scalar cv)))))
                           pairs))
                  [(bracket kind :close value)])))

        ;; Default — toggle glyph + open bracket (when expanded) OR summary.
        expanded?
        [:span {:style {:display "inline-flex" :align-items "center" :gap "4px"}}
         [:span {:on-click   toggle-fn
                 :role       "button"
                 :tabindex   0
                 :aria-expanded true
                 :data-testid (str (testid-for panel-id mount-id path) "-toggle")
                 ;; rf2-tzvk9 — ≥24×24 click target via the shared
                 ;; `triangle-style`.
                 :style triangle-style}
          "▾"]
         (bracket kind :open value)]

        :else
        [:span {:style {:display "inline-flex" :align-items "center" :gap "6px"}}
         [:span {:on-click   toggle-fn
                 :role       "button"
                 :tabindex   0
                 :aria-expanded false
                 :data-testid (str (testid-for panel-id mount-id path) "-toggle")
                 ;; rf2-tzvk9 — ≥24×24 click target via the shared
                 ;; `triangle-style`.
                 :style triangle-style}
          "▸"]
         (collapsed-summary value kind)])]

     ;; ---- body — children rendered indented -----------------------------
     ;;
     ;; Diff threading: for each child we compute the matching `:before`
     ;; slice (or `::missing` if the slot didn't exist pre-diff). This
     ;; lets the child's recursion render its own gutter row / inline
     ;; `← changed from <prior>` annotation; the parent's `:children`
     ;; row supplies the ancestor-open + ◴ glyph context.
     ;;
     ;; rf2-1bra5 — map / record bodies use CSS Grid (max-content 1fr) so
     ;; values column-align across rows. Pre-fix each row was its own
     ;; flex container with key + value sized independently → ragged
     ;; value column. With grid every row's key sits in column 1 (sized
     ;; to the widest key in THIS map; nested maps compute their own
     ;; column-1 width independently) and every row's value sits at the
     ;; single column-2 left edge.
     ;;
     ;; Sequentials (vectors / lists / sets / seqs) keep the per-row
     ;; block flow — values are emitted bare (no key column), so a
     ;; grid template wouldn't add anything.
     (when (seq children)
       (let [labelled?    (#{:map :record :map-entry} kind)
             child-pairs
             (cond
               (and diff? (= kind :map) (map? before))
               ;; Map: walk union of keys; carry both v and b per key.
               (let [all-keys (vec (into (set (keys value)) (keys before)))]
                 (for [k all-keys]
                   [k (get value k ::missing) (get before k ::missing)]))

               (and diff? (#{:vector :list :seq} kind) (sequential? before))
               ;; Sequential: index-align; treat extra trailing items.
               (let [a-vec (vec value)
                     b-vec (vec before)
                     n     (max (count a-vec) (count b-vec))]
                 (for [i (range n)]
                   [i
                    (if (< i (count a-vec)) (nth a-vec i) ::missing)
                    (if (< i (count b-vec)) (nth b-vec i) ::missing)]))

               :else
               ;; Non-diff path, or diff with no comparable structure
               ;; on the `before` side — render the present children as-is.
               (for [[k cv] children]
                 [k cv (if diff? ::missing ::missing)]))]
         (if labelled?
           ;; --- CSS Grid body for labelled-key kinds ---
           ;; Each row contributes key (col 1) + value (col 2) as direct
           ;; grid children. `column-gap` provides the key-to-value
           ;; spacing (8px = old per-row :gap "6px" rounded to a 4-step).
           ;; `row-gap 0` keeps rows tight against the canvas density.
           ;;
           ;; `align-items: baseline` aligns the key and value text
           ;; baselines per row — short keys with tall values (e.g. a
           ;; nested map's bracket) hang on the same baseline as the
           ;; key. Falls back gracefully when a value is a multi-line
           ;; container — the value cell grows down, the key stays
           ;; baseline-aligned to the value's first line.
           (into [:div {:data-testid (str (testid-for panel-id mount-id path) "-body")
                        :data-rf-body-layout "grid"
                        :style {:padding-left         "14px"
                                :margin-left          "5px"
                                :border-left          (str "1px solid "
                                                           (:border-subtle tokens))
                                :display              "grid"
                                :grid-template-columns "max-content 1fr"
                                :column-gap           "8px"
                                :row-gap              "0"
                                :align-items          "baseline"}}]
                 (mapcat
                   (fn [[k cv cb]]
                     (let [child-path (conj (vec path) k)
                           value-node (render-node
                                        {:value cv
                                         :before cb
                                         :diff? diff?
                                         :panel-id panel-id
                                         :mount-id mount-id
                                         :path child-path
                                         :depth (inc depth)
                                         :expansion-map expansion-map
                                         :dispatch-fn dispatch-fn
                                         :opts opts})]
                       [(with-meta
                          ;; Key cell — uses `div` so the grid baseline
                          ;; aligns predictably across rows. `white-
                          ;; space: nowrap` prevents long keys (e.g. a
                          ;; deeply-namespaced `:rf.x.with.many.parts/k`)
                          ;; from wrapping inside the key column.
                          [:div {:data-rf-cell "key"
                                 :style {:white-space "nowrap"}}
                           (key-segment k)]
                          {:key (str "k-" (pr-str k))})
                        (with-meta
                          ;; Value cell — `div` so nested containers
                          ;; (their own block-level expanded body)
                          ;; place inside this cell without span-vs-
                          ;; block validation issues. `min-width 0`
                          ;; protects against overflow when the value
                          ;; is wider than the grid column allotment
                          ;; (the `1fr` track stretches but won't
                          ;; shrink below the value's intrinsic width
                          ;; without this).
                          [:div {:data-rf-cell "value"
                                 :style {:min-width 0}}
                           value-node]
                          {:key (str "v-" (pr-str k))})]))
                   child-pairs))
           ;; --- Block body for sequentials (vector / list / set / seq) ---
           ;; Each child renders bare (no key column). Per-row block flow
           ;; — each entry sits below the previous; nested containers
           ;; recurse with their own grid/block decision.
           (into [:div {:data-testid (str (testid-for panel-id mount-id path) "-body")
                        :data-rf-body-layout "block"
                        :style {:padding-left "14px"
                                :margin-left  "5px"
                                :border-left  (str "1px solid "
                                                   (:border-subtle tokens))}}]
                 (map
                   (fn [[k cv cb]]
                     (let [child-path (conj (vec path) k)]
                       (with-meta
                         (render-node {:value cv
                                       :before cb
                                       :diff? diff?
                                       :panel-id panel-id
                                       :mount-id mount-id
                                       :path child-path
                                       :depth (inc depth)
                                       :expansion-map expansion-map
                                       :dispatch-fn dispatch-fn
                                       :opts opts})
                         {:key (str "v-" (pr-str k))})))
                   child-pairs)))))

     ;; ---- close bracket (only when expanded + body present) -------------
     (when (and expanded? (not empty?) (not depth-capped?) (not inline-fit?))
       [:div {:style {:color (get tokens (:tone-key (delim kind)))
                      :font-family mono-stack}}
        (let [{:keys [close]} (delim kind)] close)])]))

(defn- render-protocol-node
  "Render a value that satisfies `IXrayDataDisplay` via the consumer's
  protocol methods. Returns hiccup wrapping the consumer's header
  (and optional body) in the standard widget chrome — same testid
  + container-shape contract as the built-in renderer so panel
  layout doesn't shift between protocol + built-in nodes.

  Returns `nil` if the consumer's `-xray-render-header` returns
  `nil` — the caller treats that as a fall-through to the built-in
  renderer (header-nil means \"I don't actually want to customise
  this node\")."
  [{:keys [value panel-id mount-id path expansion-map] :as node-opts}]
  (let [proto-opts (assoc node-opts :node-opts node-opts)
        header     (ddp/xray-render-header value proto-opts)]
    (when (some? header)
      (let [body          (ddp/xray-render-body value proto-opts)
            default?      true
            expanded?     (and (some? body)
                               (resolve-expanded? expansion-map panel-id
                                                  mount-id path default?))]
        [:div {:data-testid      (testid-for panel-id mount-id path)
               :data-rf-kind     "protocol"
               :data-rf-protocol "1"
               :data-rf-expanded (if expanded? "1" "0")
               :style {:font-family mono-stack
                       :line-height 1.4}}
         [:div {:style {:display "flex"
                        :align-items "baseline"
                        :gap "4px"
                        :flex-wrap "wrap"}}
          header]
         (when (and expanded? (some? body))
           [:div {:data-testid (str (testid-for panel-id mount-id path) "-body")
                  :style {:padding-left "14px"
                          :margin-left  "5px"
                          :border-left  (str "1px solid " (:border-subtle tokens))}}
            body])]))))

(defn- render-leaf-with-diff
  "Render a scalar leaf, wrapped in the diff gutter when `diff?` is
  truthy. Returns hiccup `[:div ...]` for diff renders (so the gutter
  row can layout) or the bare `[:span ...]` for non-diff (so inline
  composition continues to work).

  - `:added`    — render `value`   in green, gutter `+`
  - `:removed`  — render `before`  in red, strike-through, gutter `-`
  - `:modified` — render `value`   in yellow + `← changed from <prior>`
                  chip, gutter `~`
  - `:same`     — render `value` (dimmed when `diff?` is true so the
                  eye lands on the changes)"
  [{:keys [value before diff?]}]
  (if-not diff?
    (render-scalar value)
    (let [op (diff-op before value)]
      (case op
        :added
        (gutter-row :added
                    [:span {:data-rf-diff-op "added"
                            :style {:color (:green tokens)
                                    :font-family mono-stack}}
                     (render-scalar value)])
        :removed
        (gutter-row :removed
                    [:span {:data-rf-diff-op "removed"
                            :style {:color (:red tokens)
                                    :font-family mono-stack
                                    :text-decoration "line-through"}}
                     (render-scalar before)])
        :modified
        (gutter-row :modified
                    [:span {:data-rf-diff-op "modified"
                            :style {:display "inline-flex"
                                    :align-items "baseline"
                                    :flex-wrap "wrap"
                                    :gap "4px"}}
                     [:span {:style {:color (:yellow tokens)
                                     :font-family mono-stack}}
                      (render-scalar value)]
                     (change-annotation before)])
        :same
        (gutter-row :same
                    [:span {:data-rf-diff-op "same"
                            :style {:color (:text-tertiary tokens)
                                    :font-family mono-stack}}
                     (render-scalar value)])))))

(defn render-node
  "Recursive entry. Picks container vs scalar; threads the
  expansion-map snapshot down. Returns hiccup. Pure projection of
  (value, expansion-map, opts) — no `rf/subscribe` calls in here.

  Consults `IXrayDataDisplay` at the head — if `value` satisfies the
  protocol AND the consumer's `-xray-render-header` returns non-nil
  hiccup, the protocol path wins. Otherwise falls through to the
  built-in container / scalar dispatch (phase 7 / rf2-0qrcr).

  Diff mode: when `:diff?` is true the renderer paints gutter rows +
  `← changed from <prior>` annotations; when `:before` is
  `::missing`, the node is rendered as `:added`; when `value` is
  `::missing`, as `:removed`.

  `:dispatch-fn` (optional) is the frame-aware dispatcher captured by
  the surrounding `reg-view` body (rf2-y59tb) so toggle clicks land on
  the same frame the widget is mounted under. Tests / programmatic
  callers that drive render-node without a mount can omit it — the
  container-renderer falls back to the global `rf/dispatch`.

  Public so unit tests can drive the renderer without mounting."
  [{:keys [value before diff? panel-id mount-id path depth expansion-map
           dispatch-fn opts]
    :or   {depth 0 path []}}]
  (or
    ;; Protocol seam (rf2-0qrcr) — light-touch satisfies? gate; nil
    ;; result falls through to built-ins. Bound to the same testid
    ;; contract as the built-in renderer so panel chrome doesn't shift.
    (when (and (not= value ::missing)
               (ddp/satisfies-xray-data-display? value))
      (render-protocol-node {:value value
                             :panel-id panel-id
                             :mount-id mount-id
                             :path path
                             :depth depth
                             :expansion-map expansion-map
                             :opts opts}))
    (cond
      ;; Diff mode: removed slot — render the prior value struck-through.
      ;; The `value` side is `::missing` but `before` carries the slot
      ;; that's being removed. Always a leaf-shaped row (containers
      ;; collapse to one removed line — operator follows the gutter, not
      ;; the structure, for deletions).
      (and diff? (= value ::missing))
      (render-leaf-with-diff {:value ::missing :before before :diff? true})

      ;; Diff mode: added slot at a container → render the new
      ;; container in green via the normal recursive path with `op
      ;; :added` threaded; for a scalar leaf, paint the `+` row.
      (and diff? (= before ::missing))
      (let [kind (collection-kind value)]
        (if (container? kind)
          (render-container {:value value
                             :kind kind
                             :panel-id panel-id
                             :mount-id mount-id
                             :path path
                             :depth depth
                             :expansion-map expansion-map
                             :dispatch-fn dispatch-fn
                             :opts opts
                             :diff? true
                             :before ::missing})
          (render-leaf-with-diff {:value value :before ::missing :diff? true})))

      :else
      (let [kind (collection-kind value)]
        (if (container? kind)
          (render-container {:value value
                             :kind kind
                             :panel-id panel-id
                             :mount-id mount-id
                             :path path
                             :depth depth
                             :expansion-map expansion-map
                             :dispatch-fn dispatch-fn
                             :opts opts
                             :diff? (boolean diff?)
                             :before before})
          (render-leaf-with-diff {:value value
                                  :before before
                                  :diff? (boolean diff?)}))))))

;; =========================================================================
;; mount-id generator + public entry — data-display (form-2 component)
;; =========================================================================

(defn- gen-mount-id
  "Generate a stable per-mount id. Form-2 closure captures it once,
  so re-renders preserve it; an actual unmount/remount allocates a
  fresh id (which is the contract — independent expansion per
  call-site mount)."
  []
  (str (random-uuid)))

;; =========================================================================
;; popup affordance — opt-in "open in popup" control (rf2-l4625)
;; =========================================================================
;;
;; When a `[data-display value opts]` call site passes
;; `:popup-affordance? true` in `opts`, the widget renders a small icon
;; button positioned at the top-right of the container. Click dispatches
;; the popup's `:open` event with a stable popup-mount-id derived from
;; the data-display's own mount-id — so re-clicking just raises the
;; existing popup (window-manager "raise" semantics matching
;; `data-display-popup/push-entry`).
;;
;; Opt-in (not always-on): scalar / tiny-value mounts don't benefit from
;; a larger inspection surface. Panels enable the affordance where the
;; inline widget is genuinely cramped (App-DB whole-tree, machine
;; snapshots, sub values). Default off keeps simple call sites quiet.
;;
;; The affordance dispatches the OPEN event id literally — no `require`
;; on the popup ns from here, which would form a cycle (the popup ns
;; requires data-display). The event id keyword is the public contract.

(def ^:private popup-affordance-button-style
  {:position      "absolute"
   :top           "2px"
   :right         "2px"
   :background    "transparent"
   :border        "none"
   :color         (:text-tertiary tokens)
   :font-size     "12px"
   :line-height   1
   :cursor        "pointer"
   :padding       "2px 6px"
   :border-radius "3px"
   ;; Subtle by default — hover bumps colour up to text-secondary via
   ;; the data-rf-affordance="popup" hook in the global stylesheet
   ;; (theme/global-styles.cljs reads the attribute selector). Keeping
   ;; the colour change off the inline style avoids paint thrash on
   ;; every render.
   :opacity       0.6})

(defn popup-affordance-button
  "Render the 'open in popup' icon button. `dispatch-fn` is the
  lexically-captured frame-aware dispatcher; `popup-mount-id` is the
  stable id keyed to the data-display's own mount-id; `value` + `opts`
  are the popup's payload.

  Public so unit tests can drive the button without spinning up the
  router (pass a stub `dispatch-fn` that captures the event vector)."
  [dispatch-fn popup-mount-id value opts]
  [:button
   {:data-testid             (str "rf-xray-data-display-popup-affordance-"
                                  popup-mount-id)
    :data-rf-affordance      "popup"
    :data-rf-popup-mount-id  popup-mount-id
    :aria-label              "Open in popup"
    :title                   "Open in popup"
    :on-click                (fn [^js e]
                               (when e
                                 (.preventDefault e)
                                 (.stopPropagation e))
                               (dispatch-fn
                                 [:rf.xray.data-display-popup/open
                                  popup-mount-id
                                  {:value value
                                   :opts  (-> (or opts {})
                                              ;; Don't recurse the
                                              ;; affordance inside the
                                              ;; popup's embedded
                                              ;; data-display.
                                              (assoc :popup-affordance? false))}]))
    :style                   popup-affordance-button-style}
   ;; ⊕ glyph reads as "open" without the language baggage of e.g. 🔍
   "⊕"])

(rf/reg-view data-display
  "First-class data-display widget — single source of truth for
  browse + diff + mini.

  Pass `[data-display value]` or `[data-display value opts]`. The
  `opts` map carries `:panel-id`, `:default-expanded-depth`,
  `:max-inline-width`, `:max-depth`, `:before` — see the ns
  docstring for the full key inventory.

  - Browse mode (default): no `:before` opt; the widget renders
    `value` with expand/collapse + sticky operator overrides.
  - Diff mode: pass `:before` in `opts` (or use the
    `data-display-diff` 3-arg convenience). The widget renders
    `value` as the AFTER side with gutter glyphs +
    `← changed from <prior>` annotations, force-expands the
    ancestor chain over any changed descendant, and dims `:same`
    rows.

  Form-2 component: the outer body allocates a stable `mount-id`
  in closure; the inner fn subscribes to the expansion slot,
  threads the snapshot through the recursive renderer, and returns
  hiccup.

  Per D4=a (rf2-sndui) the public API does NOT take a `:render-id` —
  mount-id is generated internally. Two simultaneous mounts get
  independent expansion state via the auto-id.

  Per-call-site isolation is the key correctness property here: two
  `[data-display value]` mounts in the same panel must NOT share
  expansion state. The form-2 closure delivers that — the outer body
  runs once per mount.

  ## rf2-y59tb — `reg-view`-registered so dispatch / subscribe inherit
  the surrounding frame

  Before this fix `data-display` was a plain `defn`. Plain Reagent fns
  do not consult the `frame-provider` React context, so when the
  widget mounted under `:rf/xray` (App-DB panel) toggle dispatches and
  expansion-slot subscribes routed to `:rf/default` instead — the
  click landed in the wrong frame's app-db and the rendering sub
  never saw it. Same root cause as `ribbon-theme-toggle` (rf2-uu3lp).

  The `reg-view` registration makes the component
  `:contextType frame-context`-aware: dispatch / subscribe both
  resolve to whatever frame the enclosing `frame-provider` puts in
  scope. The lexical `dispatch` injected by the macro is threaded
  through `render-node` opts so callbacks deep in the recursion
  carry the same frame.

  Call sites continue to read `[dd/data-display value opts]` — the
  macro defs the `data-display` Var to the registered render fn, so
  the public API is unchanged. Call sites that omit `opts`
  (`[dd/data-display value]`) flow the same way; Reagent passes the
  positional args from the hiccup vector to both the outer and inner
  fn, and the inner fn tolerates `opts=nil` via the destructure's
  `:or` defaults."
  [_value & _opts]
  (let [mount-id    (gen-mount-id)
        ;; Capture the frame-aware dispatcher lexically. The closure
        ;; binds to the surrounding frame the outer body runs under;
        ;; every callback the inner renderer creates (toggle handlers,
        ;; recursive render-node descents) threads this closure so the
        ;; dispatch carries the right frame even when fired long after
        ;; render unwinds.
        dispatch-fn dispatch]
    (fn render-data-display
      [value & rest-args]
      (let [opts          (first rest-args)
            {:keys [panel-id site-id default-expanded-depth max-inline-width
                    max-depth before popup-affordance? card?]
             :or   {panel-id :rf.xray.data-display/anon
                    default-expanded-depth 2
                    max-inline-width 60
                    max-depth 16
                    popup-affordance? false
                    card? false}} opts
            diff?         (contains? opts :before)
            ;; rf2-pvsxs — `site-id` (when supplied) opt-out of the
            ;; per-call-site mount-id isolation. The expansion-key's
            ;; second slot reads `site-id` instead of the auto-mount-
            ;; id, so a panel that leaves AND returns to the same
            ;; surface (e.g. App-DB tab switching) finds its prior
            ;; overrides under a stable key. When omitted, behaviour
            ;; is unchanged — auto-mount-id keeps per-call-site
            ;; isolation for naive callers (two `[data-display]`
            ;; mounts side-by-side toggle independently).
            ;;
            ;; The choice is per-consumer-panel: App-DB / Handler /
            ;; Trace pass a stable :site-id derived from their
            ;; cascade epoch + slot role; throw-away surfaces (popup
            ;; previews, table-cell minis) omit it.
            effective-id  (or site-id mount-id)
            ;; `subscribe` is the lexical frame-aware closure
            ;; injected by `reg-view` — reads the expansion-slot
            ;; from the surrounding frame's app-db (e.g. `:rf/xray`).
            expansion-map @(subscribe [expansion-slot])
            container-id  (str "rf-xray-data-display-"
                               (name panel-id) "-" mount-id)
            ;; Stable popup-mount-id derived from THIS data-display's
            ;; own mount-id so re-clicking the affordance "raises" the
            ;; existing popup rather than spawning a duplicate
            ;; (matches `data-display-popup/push-entry` semantics).
            popup-mount-id (str "ddp-" mount-id)]
        [:div {:data-testid     container-id
               :data-rf-mount-id mount-id
               :data-rf-site-id  (when site-id (pr-str site-id))
               :data-rf-mode    (if diff? "diff" "browse")
               :data-rf-popup-affordance? (when popup-affordance? "1")
               :data-rf-card     (when card? "1")
               :style (cond-> {:font-family mono-stack
                               :font-size   "12px"
                               :color       (:text-primary tokens)
                               :line-height 1.4
                               ;; Position context for the absolute-
                               ;; positioned affordance button. Harmless
                               ;; when the affordance is off — no
                               ;; descendant uses absolute positioning
                               ;; otherwise.
                               :position    (when popup-affordance? "relative")}
                        ;; rf2-63ie5 — inspector-card chrome on
                        ;; top-level mounts. Theme-aware via tokens so
                        ;; both light + dark resolve at paint time. The
                        ;; opt-in (`:card? true`) keeps inline / nested
                        ;; mounts unchrromed; panels with multiple top-
                        ;; level inspector mounts (App-DB, Handler)
                        ;; pass `:card? true` so the eye reads each
                        ;; mount as a discrete card rather than one
                        ;; continuous block.
                        card? (assoc :background-color (:bg-1 tokens)
                                     :border           (str "1px solid "
                                                            (:border-default tokens))
                                     :border-radius    "8px"
                                     :padding          "8px 10px"
                                     :margin-bottom    "8px"))}
         (when popup-affordance?
           (popup-affordance-button dispatch-fn popup-mount-id value opts))
         (render-node {:value value
                       :before (if diff? before ::missing)
                       :diff? diff?
                       :panel-id panel-id
                       ;; rf2-pvsxs — `mount-id` slot in render-node's
                       ;; key carries the EFFECTIVE id (site-id if
                       ;; supplied; auto-mount-id otherwise). Callbacks
                       ;; deep in the recursion thread the same value,
                       ;; so toggle dispatches store + read overrides
                       ;; under the persistence-friendly key.
                       :mount-id effective-id
                       :path []
                       :depth 0
                       :expansion-map expansion-map
                       :dispatch-fn dispatch-fn
                       :opts {:default-expanded-depth default-expanded-depth
                              :max-inline-width max-inline-width
                              :max-depth max-depth}})]))))

(defn data-display-diff
  "Diff convenience — `[data-display-diff before after]` or
  `[data-display-diff before after opts]`. Equivalent to
  `[data-display after (assoc opts :before before)]`. Use when the
  call site reads more naturally with both halves of the diff at the
  callsite head."
  ([before after] (data-display-diff before after nil))
  ([before after opts]
   [data-display after (assoc (or opts {}) :before before)]))

;; =========================================================================
;; mini — one-line inline rendering (D2=a: 2-arg overload, sentinel-aware)
;; =========================================================================

(defn mini
  "One-line inline rendering of `value`. No expansion, no toggle —
  used in chip rows, table cells, hover tooltips where a full tree
  would crowd the layout.

  Per D2=a (rf2-sndui) the 2-arg overload keeps a `max-len` cap and
  sentinels route through here too (no separate `inspect-inline`).

  Returns hiccup `[:span ...]` so callers embed inline."
  ([value] (mini value 80))
  ([value max-len]
   (let [kind (collection-kind value)
         pr-text (try (pr-str value) (catch :default _ (str value)))
         truncated (if (<= (count pr-text) max-len)
                     pr-text
                     (str (subs pr-text 0 max-len) "…"))]
     [:span {:data-testid "rf-xray-data-display-mini"
             :data-rf-mini "1"
             :title pr-text
             :style {:font-family mono-stack
                     :font-size   "11px"
                     :white-space "nowrap"
                     :overflow    "hidden"
                     :text-overflow "ellipsis"
                     :max-width   "100%"
                     :display     "inline-block"
                     :vertical-align "bottom"}}
      (cond
        ;; Sentinels keep their chip chrome inline.
        (#{:sentinel-redacted :sentinel-redacted-size :sentinel-large} kind)
        (render-scalar value)

        ;; Scalar — colour-coded mini.
        (not (container? kind))
        (render-scalar value)

        :else
        ;; Container — one-line preview string in the kind's bracket colour.
        [:span {:style (token-style :text-secondary)
                :data-rf-mini-text truncated}
         (inline-preview-string value 3 max-len)])])))
