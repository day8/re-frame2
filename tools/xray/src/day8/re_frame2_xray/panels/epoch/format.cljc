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
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]))

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

;; -- render-args size elision (rf2-yi0nr) --------------------------------
;;
;; The VIEWS step's col-2 render-args cell mounts the args VECTOR through
;; the shared `ei/edn-inspector`. The framework's wire-elision walker
;; (`re-frame.elision/elide-wire-value`) is SCHEMA-DRIVEN — it only
;; substitutes the `:rf.size/large-elided` marker on slots a schema marks
;; `{:large? true}`. View props are arbitrary positional render args, not
;; a schema-addressed db path, so they ride through un-elided AND Xray
;; reads RAW epoch records in-process (the egress walk never touches what
;; Xray sees — see `panels.app-db-diff-helpers` head comment). Net: a view
;; that takes a substantial map/collection prop — which ANY real app does —
;; dumped its FULL value into the cell.
;;
;; The fix mirrors the App-db panel's large-state treatment: oversized
;; values render as the framework's canonical `{:rf.size/large-elided …}`
;; sentinel, which the edn-inspector already paints as a yellow `● large ·
;; N bytes` chip (drill-in deferred per rf2-ndb13 — same affordance App-db
;; gets). The cap is purely a DISPLAY guard (Xray is read-only, in-process);
;; we reuse the framework's WIRE VOCABULARY (`:rf.size/large-elided` + the
;; `:bytes :type :reason :hint :path :handle` body keys per spec/015) so the
;; chip reads identically — `:reason :size` marks the tool-side, threshold-
;; driven origin (vs the framework's schema-declared `:reason :schema`).

(def render-args-byte-budget
  "Byte budget (UTF-8 `pr-str` length) above which a single render-arg
  ELEMENT is elided to the `:rf.size/large-elided` chip in the VIEWS
  col-2 cell (rf2-yi0nr). 512 bytes ≈ a small-to-mid prop map renders
  inline / browsable; a fat props payload (the machine-epochs runner's
  26-map steps vector is ~thousands of bytes) collapses to the size chip.
  Public so the unit test pins the threshold without re-deriving it."
  512)

(defn- pr-str-bytes
  "UTF-8 byte count of `v`'s `pr-str` form — the same size metric the
  framework's `re-frame.elision` walker uses for its `:bytes` slot, so a
  tool-side chip reports the same figure a schema-driven one would. Cheap
  estimate (`count` of the string) on CLJS where byte-exactness isn't
  available; exact on the JVM test path."
  [v]
  (let [s (pr-str v)]
    #?(:clj  (count (.getBytes ^String s "UTF-8"))
       :cljs (count s))))

(defn- render-arg-value-type
  "Coarse value-type tag for the size-marker body — mirrors the
  framework's `re-frame.elision/value-type` closed set so the chip's
  `· <type>` suffix reads identically."
  [v]
  (cond
    (map? v)    :map
    (vector? v) :vector
    (set? v)    :set
    (string? v) :string
    :else       :scalar))

(defn- ->size-marker
  "Wrap `v` in the framework's canonical `{:rf.size/large-elided <body>}`
  sentinel (spec/015 wire vocabulary) so the edn-inspector renders the
  shared yellow size chip. `:reason :size` distinguishes this tool-side,
  threshold-driven elision from the framework's schema-declared
  `:reason :schema`. `:path` is the positional arg index vector."
  [v idx]
  {:rf.size/large-elided
   {:path   [idx]
    :bytes  (pr-str-bytes v)
    :type   (render-arg-value-type v)
    :reason :size
    :hint   "Large render arg elided by Xray (display-only size cap); inspect via the live runtime."
    :handle [:rf.elision/at [idx]]}})

(defn elide-large-render-args
  "Size-guard a VIEWS-row render-args VECTOR before it mounts in the
  shared edn-inspector (rf2-yi0nr). Walks the TOP-LEVEL positional args:
  any element whose `pr-str` exceeds `render-args-byte-budget` is replaced
  by the `:rf.size/large-elided` size-marker (the SAME sentinel + chip the
  App-db panel surfaces for large state); small elements pass through
  untouched so a modest prop renders inline / browsable.

  Per-element (not whole-vector) so a render that mixes a small id arg
  with a fat props map elides ONLY the fat element — the operator still
  reads the cheap args inline. Returns the input unchanged when it is not
  a vector (defensive — the projection always stamps a vector) or carries
  no oversized element, so the no-op path allocates nothing new.

  Pure / JVM-portable — no DOM, no rf reads."
  [render-args]
  (if (vector? render-args)
    (reduce-kv
      (fn [acc idx v]
        (assoc acc idx
               (if (> (pr-str-bytes v) render-args-byte-budget)
                 (->size-marker v idx)
                 v)))
      render-args
      render-args)
    render-args))

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

(def ^:private start-cause->label
  "Map a `:rf.machine/started` `:cause` enum → the short tag rendered on
  the `[START]` badge (rf2-it4vt). The cause tells the operator HOW the
  machine came to life:

    :explicit — a deliberate eager `[:machine-id [:rf.machine/start]]` kick
                (xstate's `createActor(m).start()`).
    :lazy     — init folded into the first REAL event's epoch. Flags an
                ORDERING SMELL: something dispatched to the machine before
                it was explicitly started.
    :spawned  — the spawn fx pre-seeded the snapshot; init ran on the
                actor's first dispatch."
  {:explicit "explicit"
   :lazy     "lazy"
   :spawned  "spawned"})

(defn start-cause-label
  "Render a `:rf.machine/started` `:cause` enum as the short tag string the
  `[START]` badge carries (rf2-it4vt). Falls through `name` for an unknown
  cause keyword so a future enum value still paints, nil for non-keywords."
  [cause]
  (or (get start-cause->label cause)
      (when (keyword? cause) (name cause))))

(defn start-cause-smell?
  "True iff a `[START]` row's `:cause` is the ORDERING SMELL `:lazy`
  (rf2-it4vt) — something dispatched to the machine before it was explicitly
  started, so init folded into that event's epoch. The view paints the
  `:lazy` cause tag with a warning tone to flag it; `:explicit` / `:spawned`
  ride the muted/neutral tone (clean birth)."
  [cause]
  (= :lazy cause))

(defn cascade-row-label
  "Render a cascade row's human-readable verb (rf2-u69j7). Used by the
  view's per-row header. Pure-data; the view never reaches into a
  row's slots to compute its label."
  ;; rf2-iu3no — `:event` is no longer destructured: the `:no-op` verb
  ;; (the only case that read it) collapsed to "staying in {state}" and no
  ;; longer echoes the event (the focused-epoch Event header names it).
  [{:keys [kind action-id guard-id from-state to-state state reason
           machine-id show-machine-name? cause]}]
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
    ;; rf2-iu3no — the benign unhandled-user-event no-op. The verb is the
    ;; CONSEQUENCE only: "staying in {state}" (the machine matched no
    ;; transition, so its state is unchanged). The `[NO OP]` kind-pill is
    ;; the sole marker; the focused-epoch Event header already names the
    ;; event — so the rf2-ugdas "no-op — <machine> received <event> in
    ;; <state>, no transition" sentence (badge + prefix + event echo +
    ;; suffix, all saying the same thing) is collapsed away.
    ;;
    ;; The machine name is kept ONLY when >1 machine is in play this epoch
    ;; (broadcast event / parallel regions) so the operator can tell WHICH
    ;; machine stood pat — `machine-cascade-rows` stamps `:show-machine-name?`
    ;; on the no-op row when the cascade spans multiple machine-ids. The
    ;; single-machine case drops it (the EVENT HANDLER section names the
    ;; machine above).
    :no-op       (str (when (and show-machine-name? machine-id)
                        (str (ns-keyword machine-id) " "))
                      "staying in "
                      (if state (pr-str state) "?"))
    ;; rf2-it4vt — the machine's BIRTH verb: "<machine-id> started in
    ;; {state}". The `[START]` kind-pill is the badge; the verb names WHICH
    ;; machine was born and its INITIAL logical state (`:state` off the
    ;; `:rf.machine/started` trace — a keyword / path-vector for flat /
    ;; compound, a region→state map for parallel; rendered verbatim). The
    ;; initial `:data` rides the body box (`cascade-row-source-body`); the
    ;; `:cause` rides a tag chip (`start-cause-label`).
    :start       (str (when machine-id (str (ns-keyword machine-id) " "))
                      "started in "
                      (if state (pr-str state) "?"))
    (str (when kind (name kind)))))

(defn cascade-row-source-key
  "Spec-path tuple used to look up a cascade row's source-coord on the
  registered machine spec. Pure-data; the view layer reuses this for the
  coord lookup so the source-link affordance reads off ONE authoritative
  key.

  Per rf2-npvsx / rf2-vqja2 the lookup target differs by tuple shape:
  - Named `[:guards <id>]` / `[:actions <id>]` keys resolve to the
    co-located element entry's `:source-coords` / `:source-code`.
  - Reference-site `[:states ...]` keys resolve to the `:source-coords`
    co-located on the nearest enclosing `:states`-tree map node
    (`projection/state-node-source-coords`).
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
    ;; rf2-iu3no — the benign no-op carries NO outcome chip. The "[NO OP]"
    ;; kind-pill + the "staying in {state}" verb (`cascade-row-label`) are
    ;; the whole story; the rf2-ugdas "ignored" chip was a third restatement.
    nil))

(defn history-kind-label
  "Render a history `:kind` keyword (`:shallow` / `:deep`) as a UI label."
  [kind]
  (case kind
    :shallow "shallow"
    :deep    "deep"
    (when (keyword? kind) (name kind))))

(defn history-restored-headline
  "Render the headline string for a `:rf.machine.history/restored` record
  (rf2-mle6e.5) — the one-line answer to 'why did this re-entry land HERE?':

    :recorded → 'restored [:player] from DEEP history · [:player :paused] → [:player :paused]'
    :default  → 'restored [:player] from DEFAULT (no recording) via :default-target → [:player :playing]'

  Reads the spec/009 §`:rf.machine.history/restored` shape:
  `:compound-path` `:kind` `:source` `:fallback` `:restored-config`
  `:resolved-leaf`. On `:recorded` the headline shows the recorded config
  that drove the restore → the concrete resolved leaf; on `:default` (the
  compound was never exited, or the recorded path was dangling after a hot
  reload) it names the fallback that resolved the leaf. Pure-data."
  [{:keys [compound-path kind source fallback restored-config resolved-leaf]}]
  (let [kind-label (history-kind-label kind)
        compound   (path-display compound-path)
        leaf       (pr-str resolved-leaf)]
    (if (= :default source)
      (str "restored " compound " from DEFAULT (no recording)"
           (when fallback (str " via :" (name fallback)))
           " → " leaf)
      (str "restored " compound " from "
           (when kind-label (str (str/upper-case kind-label) " "))
           "history · " (pr-str restored-config) " → " leaf))))

(defn history-recorded-headline
  "Render the headline string for a `:rf.machine.history/recorded` record
  (rf2-mle6e.5) — 'this exit wrote the compound's config into :rf/history':

    first write   → 'history recorded [:player] = [:player :paused]'
    later write   → 'history advanced [:player] from [:player :playing] to [:player :paused]'

  Reads the spec/009 §`:rf.machine.history/recorded` shape: `:compound-path`
  `:kind` `:recorded-config` `:prev-config` (absent on the first-ever write).
  Pure-data."
  [{:keys [compound-path recorded-config prev-config]}]
  (let [compound (path-display compound-path)]
    (if (some? prev-config)
      (str "history advanced " compound
           " from " (pr-str prev-config)
           " to " (pr-str recorded-config))
      (str "history recorded " compound " = " (pr-str recorded-config)))))

(defn handler-flavour-label
  "Human-readable label for a handler flavour keyword."
  [flavour]
  (case flavour
    :reg-event-db  "reg-event-db"
    :reg-event-fx  "reg-event-fx"
    :reg-machine   "reg-machine"
    (str flavour)))

(def machine-start-marker
  "The reserved synthetic marker a machine receives as its creation kick
  (`[<machine-id> [:rf.machine/start]]`). It is NOT a real trigger — per F‴
  (rf2-gl588) it runs the initial-entry cascade then STOPS (a PURE init-kick,
  xstate's `createActor(m).start()` / `xstate.init`) — so the DISPATCH gloss
  phrases it as creation, not 'received the trigger' (rf2-18oe3;
  ai/findings/2026-06-03.machine-creation-bootstrap-review.md §1). Renamed
  from `:rf.machine/bootstrap` (pre-alpha, no back-compat shim)."
  :rf.machine/start)

(defn machine-event-gloss
  "Decode a machine dispatch's `[<machine-id> [<inner-trigger> ...]]` shape
  into a one-line plain-English gloss for the DISPATCH step's helper
  sub-line (rf2-18oe3).

  A re-frame2 machine IS an event handler addressed by its id; dispatching
  `[:door/main [:door/insert-coin]]` routes the inner trigger
  `[:door/insert-coin]` through the machine's `:on` map. The raw event
  vector reads as opaque nesting, so the gloss spells out the routing:

      [:door/main [:door/insert-coin]]
        → 'this means the machine :door/main received the trigger :door/insert-coin'

  SPECIAL CASE — the reserved `[:rf.machine/start]` marker is a creation
  kick (runs the initial-entry cascade), NOT a real trigger, so it is
  phrased as creation:

      [:door/main [:rf.machine/start]]
        → 'the machine :door/main was created / initialised'

  Returns nil when `event` is not a 2-element vector whose second element
  is a non-empty vector (the machine-dispatch shape) — the caller renders
  the gloss line only when this returns a string. Pure-data; whether the
  `<machine-id>` actually names a registered machine is the VIEW's gate
  (runtime `handler-meta` lookup) — except the reserved start marker,
  which is unambiguous on its own."
  [event]
  (when (and (vector? event)
             (= 2 (count event))
             (vector? (second event))
             (seq (second event)))
    (let [[machine-id inner]   event
          trigger              (first inner)
          machine-str          (ns-keyword machine-id)]
      (if (= machine-start-marker trigger)
        (str "the machine " machine-str " was created / initialised")
        (str "this means the machine " machine-str
             " received the trigger " (ns-keyword trigger))))))
