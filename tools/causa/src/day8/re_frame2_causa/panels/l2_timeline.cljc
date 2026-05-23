(ns day8.re-frame2-causa.panels.l2-timeline
  "Pure-fn helpers for the L2 epoch-timeline row chrome (rf2-gf58j).

  Two concerns live here, both pure-data + JVM/CLJS portable so the
  shape is testable from `clojure -M:test` without a CLJS runtime:

    1. **Dispatch-origin prefix** — per `tools/causa/spec/021-Dynamic-
       Panel-Designs.md` §17.1.5 + `spec/009-Instrumentation.md`
       §Dispatch-origin tagging (closed-enum, landed in #1735). Each
       L2 row carries a short glyph / chip prefix denoting the
       functional origin of the dispatch (`:user` is silent — the
       common case shouldn't clutter the row).

    2. **Activity badges** — per spec/021 §1 + §17.1.5. Each row
       summarises what the epoch's cascade actually DID via a
       compact badge cluster (issues / machine transitions / HTTP
       activity / fx-emit child dispatches / timer fires).

  ## Why a dedicated namespace

  `shell.cljs` is the hot-zone surface other workers (rf2-wyvf2's
  tab inventory; rf2-2moh1's L4 tab registry) iterate on; pulling
  the pure logic out keeps shell.cljs's `event-row` body stable and
  lets these helpers be tested as plain data. The shell touches one
  `:require` line and one hiccup-insertion site — the rest of the
  L2 row's structure (gutter, event-id, time chip) is unchanged.

  ## Cascade record shape consumed

  `cascade` is the per-dispatch projection record (per
  `re-frame.trace.projection/group-cascades`):

      {:dispatch-id <id>
       :event       <event-vector>
       :dispatched  <:rf.event/dispatched trace event>  ;; carries :tags :rf/dispatch-origin
       :handler     <:rf.event/run-end trace event>
       :fx          <:rf.fx/do-fx trace event>
       :effects     [...]                            ;; :op-type :rf.fx
       :subs        [...]                            ;; :rf.sub/run + :rf.sub/create
       :renders     [...]                            ;; :rf.view/render
       :other       [...]                            ;; errors, warnings,
       :errors      [...]}                           ;; existing :errors slot

  Reads are defence-in-depth nil-safe so synthetic test fixtures
  that omit slots (e.g. cascades constructed by JVM tests) do not
  blow up.

  ## Closed-enum origin → glyph mapping (spec/021 §17.1.5)

  | `:rf/dispatch-origin` | Render        | Notes                                  |
  |-----------------------|---------------|----------------------------------------|
  | `:user`               | (nothing)     | Default. Silent so the common case     |
  |                       |               | doesn't clutter the row.               |
  | `:router`             | `R` chip      | re-frame.routing internal dispatches.  |
  | `:http`               | `🌐` glyph    | Managed-HTTP settle / response.        |
  | `:ssr`                | `💧` glyph    | Hydration boot / SSR-time dispatches.  |
  | `:fx-emit`            | `⚡` glyph    | Dispatched from a parent's do-fx.      |
  | `:timer`              | `⏲` glyph    | Timer-fired dispatch.                  |
  | `:test-harness`       | `T` chip      | Opt-in test fixture dispatches.        |
  | `:tool`               | `🔧` glyph    | Tool dispatches (pair / story / REPL). |
  | `:internal`           | `i` chip      | Framework-internal dispatches.         |
  | `:websocket`          | `🌊` glyph    | App websocket adapters (reserved).     |
  | unknown / nil         | (nothing)     | Defence-in-depth — never throws.       |"
  (:require [clojure.string :as str]))

;; ---- 1. dispatch-origin prefix ------------------------------------------

(def origin-glyphs
  "Closed-enum origin → display-glyph map per spec/021 §17.1.5. `:user`
  is `nil` because the common case is silent. Unknown / nil origins
  also resolve to `nil` so the renderer omits the prefix.

  Pure-data so a test can iterate the mapping without touching the
  view layer."
  {:user         nil
   :router       "R"
   :http         "🌐"   ; 🌐
   :ssr          "💧"   ; 💧
   :fx-emit      "⚡"         ; ⚡
   :timer        "⏲"         ; ⏲
   :test-harness "T"
   :tool         "🔧"   ; 🔧
   :internal     "i"
   :websocket    "🌊"}) ; 🌊

(def origin-titles
  "Hover-title text per origin. Surfaces the closed-enum value to the
  operator on hover; the glyph is the at-a-glance affordance, the
  title is the disambiguation."
  {:user         "Dispatch origin: :user (default — app code)"
   :router       "Dispatch origin: :router (routing-substrate dispatch)"
   :http         "Dispatch origin: :http (managed-HTTP settle)"
   :ssr          "Dispatch origin: :ssr (hydration boot)"
   :fx-emit      "Dispatch origin: :fx-emit (child of a parent's do-fx)"
   :timer        "Dispatch origin: :timer (timer-fired dispatch)"
   :test-harness "Dispatch origin: :test-harness (test-fixture opt-in)"
   :tool         "Dispatch origin: :tool (tool / REPL / story)"
   :internal     "Dispatch origin: :internal (framework-internal)"
   :websocket    "Dispatch origin: :websocket (app websocket adapter)"})

(defn dispatch-origin-of
  "Read the `:rf/dispatch-origin` tag from a cascade's `:dispatched`
  trace event. Returns the closed-enum keyword or nil when absent
  (synthetic fixtures, cascades projected from older traces that
  predate rf2-t1lxr). Pure data; nil-safe at every level."
  [cascade]
  (when (map? cascade)
    (get-in cascade [:dispatched :tags :rf/dispatch-origin])))

(defn origin-prefix-glyph
  "Pure-data version of the per-origin prefix. Returns the glyph
  string or nil when the origin should render no prefix (:user,
  unknown, nil). Tests assert this without touching hiccup."
  [origin]
  (get origin-glyphs origin))

(def ui-source-tag
  "SOURCE-column label for the default app-code origin. The Figma
  EventList (`design-reference/components/EventList.tsx`) renders a
  concrete `source` tag for EVERY row — the mock's `view` rows are the
  UI-triggered dispatches (the common case). Causa's closed-enum source
  axis names that origin `:user`; the visible column label for it is
  `ui` (the dispatch came from app/UI code, not a substrate)."
  "ui")

(defn origin-source-tag
  "Pure-data SOURCE column label (rf2-ad7zx.12, rf2-lnod7). The Figma
  EventList (`design-reference/components/EventList.tsx`) renders a
  left-most `source` column as a short text tag — `fx` / `view` /
  `timer` / `machine` in the mock — and tags EVERY row, never a blank
  cell. Causa's real source axis is the closed-enum dispatch-origin
  (`:rf/dispatch-origin`): substrate origins render the bare origin
  name (`router` / `http` / `timer` / `fx-emit` / …) and the default
  app-code origin (`:user`, plus nil/unknown for synthetic or pre-
  origin-tag cascades) renders `ui`.

  Pre-rf2-lnod7 this returned nil for `:user`, which left the source
  column BLANK for the dominant ui-origin rows — the gap audit
  (rf2-4297k) flagged that http-origin rows showed their tag while
  default rows showed nothing. Tagging every row with a concrete
  source (the reference's posture) restores the column's signal.
  Never throws."
  [origin]
  (if (or (nil? origin) (= :user origin))
    ui-source-tag
    (name origin)))

(defn origin-prefix-title
  "Hover-title text for a given origin, or nil when the origin has
  no prefix to title."
  [origin]
  (when (origin-prefix-glyph origin)
    (get origin-titles origin)))

;; ---- 1b. duration column (rf2-lnod7) ------------------------------------
;;
;; The Figma EventList's fourth (right-most) column is `duration` — the
;; handler's wall-time, right-aligned, rendered as `1.2 ms` / `0.4 ms`.
;; Causa stamps the handler's elapsed time on the cascade's `:handler`
;; trace event (`:rf.event/run-end`) under `[:tags :duration-ms]` — the
;; SAME field the L4 Event-detail cascade-outcome reads. Surfacing it on
;; the L2 row restores the reference's four-column layout; the column was
;; clipped off the live list pre-rf2-lnod7 (gap audit rf2-4297k).

(defn cascade-duration-ms
  "Pluck the handler wall-time (ms) from a cascade's `:handler` trace
  event (`[:handler :tags :duration-ms]`). Returns the number or nil
  when the slot is absent / non-numeric (synthetic fixtures, cascades
  whose handler trace predates duration tagging). Nil-safe at every
  level; pure data, JVM-runnable."
  [cascade]
  (when (map? cascade)
    (let [d (get-in cascade [:handler :tags :duration-ms])]
      (when (number? d) d))))

(defn format-duration-ms
  "Format a handler duration (ms) for the L2 `duration` column, matching
  the Figma EventList's `1.2 ms` / `0.4 ms` style — one decimal place
  plus a ` ms` suffix. The platform formatter (`format \"%.1f\"` on the
  JVM, `.toFixed` in CLJS) does the rounding so both runtimes agree.
  Returns nil for a nil/non-numeric input so the renderer leaves the
  cell empty (a cascade with no measured handler time has nothing to
  show). Pure data, JVM-runnable."
  [duration-ms]
  (when (number? duration-ms)
    (str
     #?(:clj  (format "%.1f" (double duration-ms))
        :cljs (.toFixed (js/Number duration-ms) 1))
     " ms")))

(defn cascade-duration-label
  "Convenience: read + format a cascade's handler duration in one step.
  Returns the `N.N ms` string or nil when the cascade carries no
  measured handler time. Pure data, JVM-runnable."
  [cascade]
  (format-duration-ms (cascade-duration-ms cascade)))

;; ---- 2. activity badges -------------------------------------------------
;;
;; The cascade's `:other` slot collects every non-domino trace event
;; (errors / warnings / machine transitions / http settles / timer
;; fires / fx-emit child dispatches). We classify the cluster ONCE
;; into a small flag map and the renderer reads the flags — cheaper
;; than walking the slot per badge and more testable.

(defn- str-of [x]
  (when (some? x) (str x)))

(defn- op-namespace
  "Return the namespace string of a keyword `:operation` value, or nil
  when the operation is not a keyword. Defence-in-depth — synthetic
  fixtures occasionally carry string operations."
  [op]
  (when (keyword? op) (namespace op)))

(defn- has-error-op?
  "True when any event in `events` is a `:rf.error/*` or has
  `:op-type :error`. Per Spec 009 the canonical error-prefix is
  `:rf.error/` (`:operation` namespaced under it); `:op-type :error`
  is the secondary axis used by trace consumers that don't branch on
  the namespaced operation."
  [events]
  (boolean
   (some (fn [ev]
           (or (= :error (:op-type ev))
               (= "rf.error" (op-namespace (:operation ev)))))
         events)))

(defn- has-machine-op?
  "True when any event in `events` is a `:rf.machine/*` trace —
  state-machine transition / spawn / despawn."
  [events]
  (boolean
   (some (fn [ev]
           (= "rf.machine" (op-namespace (:operation ev))))
         events)))

(defn- has-http-op?
  "True when any event in `events` is a `:rf.http/*` or `:http/*`
  trace. Both namespaces are surfaced by the managed-HTTP substrate
  (`:rf.http/` is the canonical Spec 009 prefix; `:http/` is the
  legacy alias still in use by some adapters)."
  [events]
  (boolean
   (some (fn [ev]
           (let [ns (op-namespace (:operation ev))]
             (or (= "rf.http" ns)
                 (= "http" ns))))
         events)))

(defn- has-timer-op?
  "True when any event in `events` is a `:rf.timer/*` trace."
  [events]
  (boolean
   (some (fn [ev]
           (= "rf.timer" (op-namespace (:operation ev))))
         events)))

(defn cascade-activity-flags
  "Walk the cascade's `:other` events ONCE and return a small flag
  map summarising the activity classes the row's badge cluster
  surfaces. Pure-data; nil-safe on missing slots.

  Returned shape:

      {:error?    bool   ;; :rf.error/* or :op-type :error present
       :machine?  bool   ;; :rf.machine/* transition present
       :http?     bool   ;; :rf.http/* or :http/* settle present
       :timer?    bool   ;; :rf.timer/* fire present
       :fx-emit?  bool}  ;; cascade itself dispatched via :fx-emit
                         ;; (a CHILD epoch — origin tag on this cascade
                         ;; says `:fx-emit`)

  The `:fx-emit?` flag is read from the CASCADE's own origin tag
  rather than from `:other` events; per Spec 009 §Dispatch-origin
  tagging, a cascade whose origin is `:fx-emit` is itself the child
  of another cascade's do-fx phase — rendering the badge on the
  CHILD row surfaces 'this dispatch was triggered by a parent's
  do-fx'. (We expose the flag on every row regardless of origin so
  the renderer can compose; the prefix layer is what surfaces the
  origin per-row.)

  Errors are ALSO read from `:errors` (the cascade's existing
  pre-rf2-gf58j slot used by `shell/gutter-glyph`) so an
  error-bearing cascade flags the warn badge whether the trace
  surfaced as a `:rf.error/*` op or as a populated `:errors` vector."
  [cascade]
  (let [others (when (map? cascade) (:other cascade))
        errors (when (map? cascade) (:errors cascade))
        origin (dispatch-origin-of cascade)]
    {:error?   (or (boolean (seq errors)) (has-error-op? others))
     :machine? (has-machine-op? others)
     :http?    (has-http-op? others)
     :timer?   (or (has-timer-op? others) (= :timer origin))
     :fx-emit? (= :fx-emit origin)}))

(def activity-badge-glyphs
  "Pure-data badge map. Render order is fixed — issues first (the
  attention-grabbing red), then machine, then HTTP, then fx-emit,
  then timer. Tests iterate this map to assert the canonical
  mapping."
  {:error?   "⚠"         ; ⚠
   :machine? "◆"         ; ◆
   :http?    "🌐"   ; 🌐
   :fx-emit? "⚡"         ; ⚡
   :timer?   "⏲"})       ; ⏲

(def ^:private activity-badge-order
  "Render order (left → right) per spec/021 §17.1.5 — issues lead,
  machine + HTTP next (cascade-shape signals), fx-emit + timer last
  (origin-derived signals)."
  [:error? :machine? :http? :fx-emit? :timer?])

(defn activity-badges
  "Project a cascade onto an ordered vector of badge glyphs. Pure
  data; returns `[]` when the cascade has no detected activity. The
  view layer renders one `:span` per glyph.

  Render order matches `activity-badge-order`; absent flags are
  skipped so the cluster compresses to the present badges. The
  `^{:key}` metadata the view layer attaches is the glyph itself
  (stable per badge class within one row)."
  [cascade]
  (let [flags (cascade-activity-flags cascade)]
    (into []
          (keep (fn [k]
                  (when (get flags k)
                    (get activity-badge-glyphs k))))
          activity-badge-order)))

(defn activity-badges-tooltip
  "Build the row's `:title` text describing which activity badges
  are present. Returns nil when no badges to title (so the caller
  can decide whether to attach the tooltip at all). Pure-data;
  consumed by the view layer's badge-cluster span."
  [cascade]
  (let [flags (cascade-activity-flags cascade)
        parts (cond-> []
                (:error?   flags) (conj "issues raised")
                (:machine? flags) (conj "machine transition")
                (:http?    flags) (conj "HTTP activity")
                (:fx-emit? flags) (conj "fx-emit child")
                (:timer?   flags) (conj "timer fired"))]
    (when (seq parts)
      (str "Activity: " (str/join " · " parts)))))
