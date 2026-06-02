(ns day8.re-frame2-xray.panels.epoch.format
  "View-presentation string formatters for the Epoch panel (rf2-qkygs).

  ## Why a separate ns from `projection`

  `panels.epoch.projection` is the PURE-DATA step-derivation engine —
  epoch-record → ordered vector of pipeline-step rows (data-in /
  data-out). These fns are the OTHER side of that boundary: they turn
  a projected row's slots into the display STRINGS the view paints
  (`0.1ms` / `:my-ns/foo` / `guard :x` / `2 microsteps`). Parking them
  in the projection ns blurred the data/presentation line — a reader
  could not tell load-bearing derivation from cosmetic formatting, and
  the projection ns carried two jobs.

  This matches the rest of the codebase's pure-data / helpers split
  (`spec/Conventions.md` §Pure-data helpers as `.cljc`; the
  `*_helpers.cljc` per-theme convention). The view requires this ns
  for its per-row labels; the projection ns now reads as the pure
  step-derivation engine only.

  ## Pure-data + JVM-portable

  No DOM, no substrate runtime — display-string builders over already-
  projected row data. JVM-testable via `clojure -M:test`."
  (:require [day8.re-frame2-xray.panels.epoch.projection :as proj]))

(defn format-duration-ms
  "Render a duration in ms as a short string (`0.1ms` / `12ms` /
  `1.2s`). Returns nil for non-numbers so the view can render an
  em-dash on a missing duration without guarding the call site."
  [ms]
  (when (number? ms)
    (cond
      (>= ms 1000) (str (/ (Math/round (double (* (/ ms 1000.0) 10))) 10.0) "s")
      (>= ms 10)   (str (Math/round (double ms)) "ms")
      :else        (let [rounded (/ (Math/round (double (* ms 10))) 10.0)]
                     (str rounded "ms")))))

(defn event-display
  "Render the dispatched event vector as a one-line monospace string
  for the DISPATCH row's target slot."
  [event-vec]
  (when (vector? event-vec)
    (str event-vec)))

(defn path-display
  "Render a db path vector as the `[:foo :bar 0]` repr used by every
  diff-style row. Returns `\"\"` for nil/empty."
  [path]
  (if (sequential? path)
    (str (vec path))
    ""))

(defn ns-keyword
  "Render an id as a clojure-style keyword string (`:my-ns/foo` or
  `:foo`). Falls through `str` for non-keywords."
  [id]
  (cond
    (qualified-keyword? id) (str ":" (namespace id) "/" (name id))
    (keyword? id)           (str ":" (name id))
    :else                   (str id)))

(defn truncate
  "Truncate a string to `n` chars with an ellipsis. Pure fn used by
  the view layer for long arg displays in the FX table."
  ([s] (truncate s 60))
  ([s n]
   (let [s (str s)]
     (if (<= (count s) n)
       s
       (str (subs s 0 n) "…")))))

(defn coeffect-row-display
  "Render a coeffect row's id → value pair as a one-liner for the
  view's diff-style add row (`+ [:session] {:user-id 42 …}`)."
  [{:keys [id value]}]
  (let [head (str "+ [" (ns-keyword id) "] ")
        tail (truncate (pr-str value) 80)]
    (str head tail)))

(defn phase-label
  "Render a machine action-ran `:phase` keyword as a UI label string."
  [phase]
  (case phase
    :exit            "exit"
    :transition      "transition"
    :entry           "entry"
    :always          "always"
    :after-action    "after-action"
    :initial-entry   "initial-entry"
    :destroy-exit    "destroy-exit"
    (when (keyword? phase) (name phase))))

(defn timer-reason-label
  "Render a timer-cancelled `:reason` keyword as a UI label string."
  [reason]
  (case reason
    :on-exit          "on-exit"
    :on-destroy       "on-destroy"
    :on-resolution    "on-resolution"
    :on-supersede     "on-supersede"
    :on-frame-destroy "on-frame-destroy"
    (when (keyword? reason) (name reason))))

(defn cascade-row-label
  "Render a cascade row's human-readable verb (rf2-u69j7). Used by the
  view's per-row header. Pure-data; the view never reaches into a
  row's slots to compute its label."
  [{:keys [kind action-id guard-id from-state to-state state reason]}]
  (case kind
    :guard       (str "guard " (ns-keyword guard-id))
    ;; rf2-nhovk — the ACTION kind-pill + phase chip already convey kind +
    ;; phase, so the verb is JUST the action-id (empty for an anonymous
    ;; action — the pill + chip + source body carry it). The redundant
    ;; "{phase} action " prefix is dropped.
    :action      (ns-keyword action-id)
    ;; rf2-ge6uj ISSUE 3 — the TRANSITION row's verb is JUST the state
    ;; change `<before> → <after>`, made the focal point. The redundant
    ;; leading "transition" word (the KIND pill already says TRANSITION)
    ;; and the machine-name echo (`:door/main` — already the cascade
    ;; context) are DROPPED; the lower-line state/event repetition is
    ;; dropped in the view (`cascade-row-transition-details`).
    :transition  (str (if from-state (pr-str from-state) "?")
                      " → "
                      (if to-state (pr-str to-state) "?"))
    :timer       (str "timer " (when state (pr-str state))
                      (when reason (str " · " (name reason))))
    (str (when kind (name kind)))))

(defn cascade-row-source-key
  "Spec-path tuple used to look up a cascade row's source-coord on the
  registered machine spec. Pure-data; the view layer reuses this for the
  coord lookup so the source-link affordance reads off ONE authoritative
  key.

  Per rf2-npvsx the lookup target differs by tuple shape:
  - Named `[:guards <id>]` / `[:actions <id>]` keys resolve to the
    co-located element entry's `:source-coords` / `:source-code`.
  - Reference-site `[:states ...]` keys resolve through the spec's
    `:rf.machine/state-coords` index.
  The view's `named-element-key` discriminator routes between the two.

  Dispatch (rf2-u69j7 baseline + rf2-wwc3j inline-fn extensions):

  - `:action` with a keyword `:action-id` → `[:actions <id>]`
    (definition-site stamp; the named-handler path).
  - `:action` with an inline `:action-id` (fn) — derive from the row's
    `:phase` + state slot (`:source-state` / `:target-state`, stamped
    by `enrich-cascade-rows`):
    - `:entry` / `:initial-entry` → `[:states <state>... :entry]`
      (target-state)
    - `:exit` / `:destroy-exit`   → `[:states <state>... :exit]`
      (source-state)
    - `:transition`               → `[:states <state>... :on <event> :action]`
      (source-state + event-id)
    - `:always`                   → `[:states <state>... :always 0 :action]`
      (best-effort: index 0; richer index resolution requires
      substrate-side carrier of the always-index, deferred)
    - `:after-action`             → `[:states <state>... :after :action]`
      (best-effort: timer fn-form path; the macro doesn't yet stamp
      per-delay `:after` coords; D2 follow-on bead handles richer index).
  - `:guard` with a keyword `:guard-id` → `[:guards <id>]`
    (definition-site stamp; the named-guard path).
  - `:guard` with an inline `:guard-id` (fn) — derive from state +
    event-id:
    `[:states <state>... :on <event> :guard]` (best-effort: no vector
    transition-option index — for the common single-map transition).
  - `:transition` → `[:states <from-state>... :on <event>]`
    (the transition map's spec-path; opens the operator on the
    transition literal in the spec).
  - `:timer` → `[:states <state>...]`
    (D1 minimum-viable: the parent state's source-coord chip; richer
    per-`:after` coord is the D2 follow-on bead's surface)."
  [{:keys [kind action-id guard-id phase source-state target-state event-id]
    timer-state :state}]
  (let [source-prefix (proj/state-spec-path-prefix source-state)
        target-prefix (proj/state-spec-path-prefix target-state)
        timer-prefix  (proj/state-spec-path-prefix timer-state)]
    (case kind
      :action
      (cond
        ;; Named-handler path (keyword id) — definition-site stamp.
        (keyword? action-id) [:actions action-id]
        ;; Inline-fn path — slot stamp under the relevant state.
        (contains? #{:entry :initial-entry} phase)
        (when target-prefix (conj target-prefix :entry))
        (contains? #{:exit :destroy-exit} phase)
        (when source-prefix (conj source-prefix :exit))
        (= :transition phase)
        (when (and source-prefix event-id)
          (conj source-prefix :on event-id :action))
        (= :always phase)
        (when source-prefix (conj source-prefix :always 0 :action))
        (= :after-action phase)
        (when source-prefix (conj source-prefix :after :action))
        :else nil)

      :guard
      (cond
        (keyword? guard-id) [:guards guard-id]
        :else
        (when (and source-prefix event-id)
          (conj source-prefix :on event-id :guard)))

      :transition
      (when (and source-prefix event-id)
        (conj source-prefix :on event-id))

      :timer
      ;; The row's `:state` is the cancelled state vector (substrate
      ;; payload). D1 minimum-viable shape: point at the parent state's
      ;; spec-path so the operator orients on the `:after`-bearing node.
      (or timer-prefix source-prefix target-prefix)

      nil)))

(defn cascade-outcome-label
  "Render a cascade row's outcome for the view's outcome chip
  (rf2-u69j7). Pure-data.

    :guard       → `pass | fail | threw`
    :action      → `ok | threw` (the action's outcome map is rich;
                                 the chip carries only the headline)
    :transition  → `→ N microstep(s)` (the headline reads off
                                       `:microsteps`)
    :timer       → `cancelled (<reason>)`"
  [{:keys [kind outcome threw? microsteps reason]}]
  (case kind
    :guard      (if (keyword? outcome) (name outcome) nil)
    :action     (cond
                  threw?                "threw"
                  (= :ok outcome)       "ok"
                  (map? outcome)        "ok"
                  (keyword? outcome)    (name outcome)
                  :else                 nil)
    :transition (when (number? microsteps)
                  (str microsteps " microstep"
                       (when (not= 1 microsteps) "s")))
    :timer      (str "cancelled"
                     (when reason (str " (" (name reason) ")")))
    nil))

(defn handler-flavour-label
  "Human-readable label for a handler flavour keyword."
  [flavour]
  (case flavour
    :reg-event-db  "reg-event-db"
    :reg-event-fx  "reg-event-fx"
    :reg-machine   "reg-machine"
    (str flavour)))
