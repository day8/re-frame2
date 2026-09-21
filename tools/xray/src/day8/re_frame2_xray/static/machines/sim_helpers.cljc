(ns day8.re-frame2-xray.static.machines.sim-helpers
  "Pure-data helpers for Xray's Static Machines Sim sub-mode.

  Sim is exclusively a Static-surface sub-mode (event-INDEPENDENT
  'what-if' simulator).

  ## Why a separate `.cljc` ns

  Same dual-target pattern every other Xray helper ns uses
  (subscriptions, routes, machine-inspector-helpers).
  The sim view in `static/machines/sim.cljs` builds the hiccup; the
  *logic* — projecting a definition into an initial snapshot, deriving
  available transitions, building audit-trail rows — lives here as
  pure data → data so the JVM unit-test target (`clojure -M:test`)
  drives it without a CLJS runtime.

  ## Design source-of-truth

  `ai/findings/xray-uc1-simulation-design-2026-05-17.md` carries the
  full UC1 Sim design. Quick recap:

    - Sim is a **sub-mode of Mode A** — a toggle in the panel header
      flips the chart from live-highlight to sim-highlight (amber).
    - The sim **clones** the registered machine definition into Xray
      state; production registry is untouched.
    - A user picks an event from an autocomplete-style picker (a
      text input + dropdown of declared events for the current state)
      and clicks Step.
    - The runtime calls `re-frame.machines/machine-transition` (the
      machines artefact's pure engine entry) with the cloned definition
      + current sim snapshot + event vector. On `:status :ok`, the snapshot advances and an
      audit-trail row is appended. On `:status :error`, the snapshot
      stays and an error surfaces.
    - Reset returns to the declared initial state.
    - Exit disposes the sim state (per-machine slot deleted).

  ## Sim-state shape

  The Sim sub-mode keeps per-machine state on Xray's frame app-db at
  `[:sim/by-machine <machine-id>]`. Each slot is:

      {:active?          <bool>  ;; sub-mode toggled on?
       :definition       <map>   ;; the cloned machine definition
       :initial-snapshot <map>   ;; the seed, for an exact Reset
       :snapshot         <map>   ;; current {:state :data ...}
       :audit-trail      <vec>   ;; [{:from :to :event :guard?} ...]
       :last-error       <map>   ;; nil or {:event :info :reason}
       :pending-event    <str>   ;; the event-id text the user is typing
       :pending-data     <str>}  ;; EDN payload (optional)

  ## What this ns exposes

    1. `initial-snapshot`          — definition (+ the engine's own
                                     seeder) → seed `{:state :data}`
    2. `available-transitions`     — definition + snapshot → seq of
                                     {:event :target :guard} maps
    2b. `available-after-transitions` /
        `after-elapsed-event`      — the `:after` timer rows on the
                                     active path, and the engine's own
                                     synthetic timer event for one of
                                     them, its epoch read back off the
                                     stored `:rf.machine/after-schedule`
                                     fx (rf2-pzuqw)
    3. `event-id-suggestions`      — definition → distinct sorted
                                     event-ids (autocomplete source)
    4. `parse-event-vector`        — string \"[:foo {:x 1}]\" → vector
                                     or nil on parse error
    5. `append-audit-row`          — push a step entry onto the trail
    6. `format-state-display`      — sim chart state-keyword resolver
    7. `step-sim`                  — fold one
                                     `re-frame.machines/machine-transition`
                                     result (the Spec 005 §Level 1 map)
                                     into the sim-state
    8. `last-transition` / `current-sim-state` / `edge-click->event`
       — on-chart binding algebra: the focused-edge lens
       inputs + active-state highlight + edge-click → step-event
       coercion that bind the topology chart to the sim engine."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

;; ---- definition introspection -------------------------------------------

(defn- all-state-nodes
  "Depth-first seq of every state-node under `state-map` — each compound
  state's children are flattened in after the parent. Pure: returns the
  nodes themselves, so callers aggregate with ordinary transforms rather
  than a side-effecting walk."
  [state-map]
  (mapcat (fn [[_state-id node]]
            (cons node (all-state-nodes (:states node))))
          state-map))

(defn event-id-suggestions
  "Return the distinct sorted event-ids declared anywhere in `definition`.
  Walks every state-node's `:on` map and aggregates the keys. The
  autocomplete in the sim event-picker is seeded from this set so the
  user sees only events the machine actually responds to.

  Returns `[]` when the definition is nil or has no `:states`."
  [definition]
  (if (or (nil? definition) (nil? (:states definition)))
    []
    (->> (all-state-nodes (:states definition))
         (mapcat (comp keys :on))
         distinct
         (sort-by str)
         vec)))

(defn- node-at
  "Walk `state-map` down the given `path` (vector of state-id keywords) and
  return the LEAF node. Tolerates a path with a single keyword or a
  hierarchical vector.

  Returns nil unless the WHOLE path resolves: a partial match is not a leaf,
  and falling back to the deepest ancestor would quietly widen the picker
  past the leaf-only policy `available-transitions` documents.

  Takes the STATE-MAP rather than the whole definition, so a parallel
  region's own `:states` is walked by this same code (rf2-ky034) — the
  region's states are at `[:regions <region> :states]`, not `[:states]`."
  [state-map path]
  (loop [m  state-map
         p  (vec path)]
    (cond
      (empty? p)      nil
      (nil? m)        nil
      :else
      (let [n (get m (first p))]
        (cond
          (nil? n)        nil
          (= 1 (count p)) n
          :else           (recur (:states n) (rest p)))))))

(defn- normalise-path
  "Coerce ONE state value (keyword OR vector) into a vector path for
  `node-at` lookup.

  DELIBERATELY SINGLE-VALUED, and that is the whole of its contract: a
  `:type :parallel` snapshot's `:state` is a MAP of region → state, which is
  not one state value but several, so there is no single path to return for
  it. `available-transitions` branches on the map and calls this ONCE PER
  REGION — the same split `available-after-transitions` makes.

  Do not \"fix\" this by giving the map an arm here. It used to fall through
  to `:else []`, which handed `node-at` an empty path and made the picker
  claim every parallel machine declared no outgoing transitions (rf2-ky034);
  the repair was to branch at the caller, not to force a multi-valued shape
  through a single-valued fn."
  [state]
  (cond
    (nil? state)     []
    (keyword? state) [state]
    (vector? state)  state
    :else            []))

(defn- candidates
  "Mirror of `transition.cljc`'s normaliser — flatten a transition spec
  into a seq of candidate maps each carrying `:target` / optional `:guard`
  / optional `:action`. Pure-local copy so the helpers don't reach into
  the machines artefact."
  [spec]
  (cond
    (keyword? spec)            [{:target spec}]
    (and (vector? spec)
         (every? keyword? spec)) [{:target spec}]
    (map? spec)                [spec]
    (sequential? spec)         (mapcat candidates spec)
    :else                      []))

(defn- on-rows-at
  "The `:on` rows one state-node declares, stamped with the absolute
  `decl-path` that node sits at. Normalised through `candidates`, so the
  `{:go :busy}`, `{:go {:target :busy :guard :g}}` and vector-of-candidates
  forms all list. Mirror of `after-rows-at` for the `:on` half.

  EVERY candidate lists, a TARGETLESS one included (rf2-kmr2i, and
  rf2-4cm3k for this half). `:target` is nil on such a row and the rail
  renders it through `format-destination` rather than inventing one.
  This body used to carry a `:when (some? t)` — inherited verbatim from
  the original `available-transitions` body — which dropped them
  silently: a machine whose only handler is action-only listed nothing
  and could not be fired from the rail, while the engine handled the
  same event correctly when it was typed in.

  Spec 005 §Self-transitions makes a targetless transition the ONLY
  geometry the runtime flags `internal?` — its `:action` runs, `:exit`
  and `:entry` do not, and active descendants are preserved — and the
  forbidden-transition idiom (a bare `{:help {}}` consuming an event so
  an ancestor's handler is never reached) is spelled exactly this way.
  So these are first-class declared transitions, not a degenerate case.
  An `:on` row is fired by its EVENT ID alone, so firing one never
  needed the target."
  [node decl-path]
  (for [[event-id spec] (:on node)
        candidate       (candidates spec)]
    {:event     event-id
     :target    (:target candidate)
     :action    (:action candidate)
     :guard?    (some? (:guard candidate))
     :guard     (:guard candidate)
     :decl-path decl-path}))

(defn- leaf-pairs
  "Every `[decl-path leaf-node]` the snapshot is resting on — one pair for a
  flat or compound machine, ONE PER REGION for a parallel one. Unresolvable
  paths contribute nothing rather than throwing.

  This is the LEAF-ONLY counterpart of `path-nodes`: that walker returns
  every ancestor on the way down because an `:after` is commonly declared on
  a compound ancestor, whereas the `:on` picker lists the leaf alone."
  [definition state]
  (if (map? state)
    ;; `:type :parallel` — each region resolves its own leaf inside
    ;; `[:regions <region> :states]`, and the decl-path is region-prefixed to
    ;; match what the engine puts on its own fx.
    (into []
          (keep (fn [[region region-state]]
                  (let [p (normalise-path region-state)]
                    (when-let [n (node-at (get-in definition [:regions region :states]) p)]
                      [(into [region] p) n]))))
          state)
    (let [p (normalise-path state)]
      (when-let [n (node-at (:states definition) p)]
        [[p n]]))))

(defn available-transitions
  "Return a vector of `{:event :target :action :guard? :guard :decl-path}`
  maps — one per outgoing `:on` transition declared on the state(s) the
  snapshot is resting on. The picker surfaces these as the user's step
  options; clicking a row fills the event input, and the engine decides at
  step time what the event actually does.

  `:target` is **nil** for a legal targetless / action-only candidate, which
  lists like any other (rf2-4cm3k) — the row is fired by its event id, and
  `format-destination` is what renders a row that has no target to show.

  Three snapshot shapes, because all three are supported machine shapes and
  the first alone answers for none of them:

    - a keyword `:state` resolves the one node;
    - a hierarchical path vector resolves its LEAF;
    - a `:type :parallel` region-map resolves EACH region's own leaf inside
      `[:regions <region> :states]`, with `:decl-path` region-prefixed to
      match what the engine puts on its fx (rf2-ky034). Before that, the map
      shape matched no arm of `normalise-path`, so the lookup ran against an
      empty path and EVERY parallel machine listed nothing.

  `:decl-path` is where the transition is DECLARED, not part of how it fires:
  an `:on` row is fired by its event-id alone and the engine routes it, which
  is why — unlike an `:after` row — the path is not folded into the event.
  Two regions may therefore declare the same event-id, and both rows list;
  the rail keys its rows on the pair.

  LEAF-ONLY, deliberately. `:always` / `:after` / parent-state inheritance
  are not listed (the engine still handles them at step time; the picker
  surfaces what the user can fire interactively from where the machine is
  resting). A `:type :parallel` ROOT's own `:on` — the ancestor fallback the
  engine consults when no region handles an event — is NOT listed either,
  for exactly the reason a flat machine's machine-root `:on` never has been:
  both are parent inheritance. That is a deliberate asymmetry with
  `available-after-transitions`, which walks ancestors BY DESIGN because an
  `:after` is commonly declared on one; each fn is consistent with its own
  stated policy rather than with the other's.

  Returns `[]` for a nil / non-map definition, a nil snapshot, or a state
  that doesn't resolve."
  [definition snapshot]
  (if-not (map? definition)
    []
    (vec (mapcat (fn [[decl-path node]] (on-rows-at node decl-path))
                 (leaf-pairs definition (:state snapshot))))))

;; ---- `:after` timer rows (rf2-pzuqw) ------------------------------------
;;
;; The rail lists each `:after` timer declared on the ACTIVE PATH beside the
;; `:on` rows, and clicking one fires the engine's own synthetic
;; `[:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]`
;; (Spec 005 §Timer events) through the SAME `sim-step` the Step button
;; drives. No new registration, no clock, no chart change.
;;
;; These are MANUAL TIMEOUT TRIGGERS, not an armed-timer inventory. The sim
;; keeps no clock and does not advance simulated time; it lists what the
;; definition DECLARES on the active path and lets the engine answer. A
;; stale epoch or a declined guard therefore comes back as the existing
;; amber "No change" diagnostic and the row stays listed — where the real
;; runtime would have reaped a spent one-shot.
;;
;; The epoch is the one value the definition cannot supply, and it is NOT
;; re-derived here: `step-sim` already stores each step's `:fx` on its audit
;; row, and the engine puts `[:rf.machine/after-schedule {:rf/invoke-id …
;; :delay-key … :epoch …}]` there on every step that enters an
;; `:after`-bearing node. Reading it back off that public Level 1 contract
;; is the whole mechanism — the sim reaches into no
;; `re-frame.machines.transition` internals, and for a parallel region the
;; engine has ALREADY region-prefixed `:rf/invoke-id` before the result left
;; it, so the region rules stay the engine's.

(defn- after-rows-at
  "The `:after` rows one state-node declares, stamped with the absolute
  `decl-path` that node sits at. Normalised through `candidates`, so the
  `{5000 :t}`, `{5000 {:target :t :guard :g}}` and vector-of-candidates
  forms all list.

  EVERY candidate lists, a TARGETLESS one included (rf2-kmr2i) — see
  `on-rows-at`, which carries the same relaxation for the same reason
  and by the same one-clause deletion. `{5000 {:action :bump}}` is a
  legal action-only timer; before this it never became a row, so a
  machine declaring only such timers presented an EMPTY timer list and
  could not fire them from the rail, even though the engine fires them
  correctly when the event is sent. Spec 005 §Parallel root `:after`
  names targetless / action-only outright as one of the three target
  grammars. A timer row is fired from its `:delay-key` and `:decl-path`
  (see `after-elapsed-event`), so firing one never needed the target."
  [node decl-path]
  (for [[delay-key spec] (:after node)
        candidate        (candidates spec)]
    {:delay-key delay-key
     :target    (:target candidate)
     :action    (:action candidate)
     :guard?    (some? (:guard candidate))
     :guard     (:guard candidate)
     :decl-path decl-path}))

(defn- path-nodes
  "Every `[decl-path node]` pair along `state` inside `state-map`, ANCESTORS
  FIRST — `[:auth]` then `[:auth :form]`. `prefix` is prepended to each
  decl-path so a parallel region's walk comes back region-prefixed.

  Deliberately NOT `node-at`, which resolves the leaf alone: an `:after` is
  commonly declared on a compound ancestor while a descendant is active, and
  a leaf-only lookup lists nothing there. Stops at the first unresolvable
  segment rather than throwing."
  [state-map state prefix]
  (loop [m   state-map
         p   (cond
               (nil? state)     []
               (keyword? state) [state]
               (vector? state)  state
               :else            [])
         at  (vec prefix)
         acc []]
    (if (or (empty? p) (nil? m))
      acc
      (if-let [n (get m (first p))]
        (let [at' (conj at (first p))]
          (recur (:states n) (rest p) at' (conj acc [at' n])))
        acc))))

(defn available-after-transitions
  "Return a vector of `{:delay-key :target :action :guard? :guard
  :decl-path}` maps — one per `:after` candidate declared anywhere on the
  snapshot's ACTIVE PATH. The rail renders these as `⌚` timer rows beside
  the `:on` rows.

  `:target` is **nil** for a legal targetless / action-only timer, which
  lists like any other (rf2-kmr2i) — the row is fired from its `:delay-key`
  and `:decl-path`, and `format-destination` renders the missing target.

  Three shapes, because all three are supported machine shapes and the leaf
  alone answers for none of them:

    - a keyword `:state` walks the one node;
    - a hierarchical path vector walks EVERY prefix, so an `:after` on a
      compound ancestor lists while its leaf is active;
    - a parallel region-map walks each region's own state inside
      `[:regions <region> :states]`, with `:decl-path` region-prefixed to
      match what the engine puts on its fx — plus the parallel ROOT's own
      `:after` at `:decl-path []` when the definition declares one (a flat
      or region-root `:after` is rejected at registration, so `[]` only
      ever means the parallel root).

  Returns `[]` for a nil / non-map definition, a nil snapshot, or a state
  that doesn't resolve."
  [definition snapshot]
  (if-not (map? definition)
    []
    (let [state (:state snapshot)
          pairs (if (map? state)
                  (into (if (seq (:after definition)) [[[] definition]] [])
                        (mapcat (fn [[region region-state]]
                                  (path-nodes
                                    (get-in definition [:regions region :states])
                                    region-state
                                    [region])))
                        state)
                  (path-nodes (:states definition) state []))]
      (vec (mapcat (fn [[decl-path node]] (after-rows-at node decl-path))
                   pairs)))))

(defn after-elapsed-event
  "Build the engine's synthetic timer event for one `available-after-
  transitions` row:

      [:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]

  `epoch` is the `:epoch` of the NEWEST stored `:rf.machine/after-schedule`
  fx matching this row's `[decl-path delay-key]`, folded over the audit
  trail (newest-LAST per `append-audit-row`, so a re-entry's fresh epoch
  wins). It defaults to **0**, which is not a fallback but the right answer
  for the seed: the engine's own `node-epoch` reads an absent node as 0, so
  a timer declared on the initial state fires at 0 before any step has run.

  A wrong epoch is not this fn's problem to detect — the engine declines it
  and `step-sim` already reports that as the amber \"No change\".

  Pure data → data; JVM-runnable, and it never touches the machines
  artefact."
  [sim-state {:keys [delay-key decl-path]}]
  [:rf.machine.timer/after-elapsed
   delay-key
   (or (->> (:audit-trail sim-state)
            (mapcat :fx)
            (filter #(and (vector? %)
                          (= :rf.machine/after-schedule (first %))))
            (map second)
            (filter #(and (map? %)
                          (= decl-path  (:rf/invoke-id %))
                          (= delay-key  (:delay-key %))))
            (map :epoch)
            (remove nil?)
            last)
       0)
   decl-path])

;; ---- initial snapshot ---------------------------------------------------

(defn initial-snapshot
  "Build the seed sim snapshot for `definition`.

  `seed-fn`, when supplied, is `(fn [definition] snapshot)` — the
  ENGINE's own initial-snapshot builder, threaded in by the CLJS
  wrapper exactly as `step-sim` takes its `runtime-fn`. Use it: the
  shallow read below is right only for a FLAT machine.

    - a COMPOUND root's `:initial` names a state the machine cannot
      rest in, so the shallow read leaves the sim parked on the
      compound node and every later step records a phantom
      `:auth -> :auth`;
    - a `:type :parallel` root has no `:initial` AT ALL (it declares
      `:regions`), so the shallow read returns nil outright.

  The engine already computes both — the leaf path for a compound
  root, the region->state map for a parallel one — and its snapshot
  carries the slots the runtime expects (`:rf/spawn-counter`, `:meta`,
  the initial tag union guards read). Re-deriving that here would be a
  second copy of the initial cascade to keep in step; asking the engine
  is the point of rf2-y8doi.21.

  Without a `seed-fn` this stays the pure `:initial` read, so the JVM
  unit-test target drives it with no machines artefact at all. Shape:

      {:state <keyword | path vector | region map>
       :data  <map>}

  ENTRY ACTIONS ARE STILL NOT RUN. Seeding through the engine's
  `build-initial-snapshot` computes the initial STATE; it is not
  `apply-initial-entry-cascade`, which is the separate phase that fires
  `:entry` actions. Sim deliberately skips that at bootstrap (a pure,
  hermetic step machine the user drives), and the rail says so. Action
  evaluation kicks in from step 1 onwards.

  Returns nil when `definition` is not a map, or when no `seed-fn` is
  supplied and it has no `:initial`."
  ([definition] (initial-snapshot definition nil))
  ([definition seed-fn]
   (when (map? definition)
     (or (when seed-fn
           (let [seeded (seed-fn definition)]
             (when (map? seeded) seeded)))
         (when (some? (:initial definition))
           {:state (:initial definition)
            :data  (or (:data definition) {})})))))

;; ---- sim-state lifecycle ------------------------------------------------

(defn make-sim-state
  "Build a fresh sim-state map for `machine-id` + `definition`. Called
  when the user toggles Sim on for a machine. The `:active?` flag stays
  true until `dispose-sim-state` zeros the slot.

  `seed-fn` is `initial-snapshot`'s engine seeder (see there). The seed
  is ALSO kept at `:initial-snapshot` so `reset-sim-state` rewinds to
  exactly what the slot opened with — without that, a reset would have
  to re-derive the seed and a seeded sim would silently fall back to
  the shallow read."
  ([machine-id definition] (make-sim-state machine-id definition nil))
  ([machine-id definition seed-fn]
   (let [seed (initial-snapshot definition seed-fn)]
     {:machine-id       machine-id
      :active?          true
      :definition       definition
      :initial-snapshot seed
      :snapshot         seed
      :audit-trail      []
      :last-error       nil
      :pending-event    ""
      :pending-data     ""})))

(defn reset-sim-state
  "Return `sim-state` reset to its initial snapshot, audit trail
  cleared, error cleared. Preserves `:definition` + `:active?` so the
  user stays in sim mode after a reset. Pending input is preserved (the
  user likely wants to re-fire what they were sketching).

  Rewinds to the `:initial-snapshot` the slot was SEEDED with, falling
  back to the shallow read only for a slot built without one."
  [sim-state]
  (assoc sim-state
    :snapshot    (or (:initial-snapshot sim-state)
                     (initial-snapshot (:definition sim-state)))
    :audit-trail []
    :last-error  nil))

(defn append-audit-row
  "Push one step entry onto the trail. `row` is `{:from :to :event
  :guard? :data}`. The trail grows newest-last (the view renders it in
  insertion order)."
  [sim-state row]
  (update sim-state :audit-trail (fnil conj []) row))

(defn record-error
  "Stamp an error onto sim-state without advancing the snapshot. The
  view surfaces this in a red toast / inline. `info` is whatever the
  fail-Result's `::info` slot carried; `reason` is a human-readable
  string."
  [sim-state event info reason]
  (assoc sim-state :last-error {:event event :info info :reason reason}))

(defn clear-error
  "Drop any stamped error. Called on the next successful step."
  [sim-state]
  (assoc sim-state :last-error nil))

;; ---- event-vector parsing -----------------------------------------------

(defn parse-event-vector
  "Parse the user's typed event input into a re-frame event vector.

  Accepts:

    - a keyword string like `:foo/bar`        → `[:foo/bar]`
    - a vector form like `[:foo/bar {:x 1}]` → `[:foo/bar {:x 1}]`

  Returns the event vector on success, or a `{:error <str>}` map on
  parse failure. Whitespace-only input returns `{:error \"empty\"}`.

  Pure fn — JVM-runnable so the parsing rules are unit-testable."
  [text]
  (cond
    (nil? text)
    {:error "empty"}

    (str/blank? text)
    {:error "empty"}

    :else
    (let [trimmed (str/trim text)]
      (try
        (let [parsed (edn/read-string trimmed)]
          (cond
            (keyword? parsed)
            [parsed]

            (and (vector? parsed) (keyword? (first parsed)))
            parsed

            :else
            {:error (str "expected a keyword or vector starting with a keyword, got "
                         (pr-str parsed))}))
        (catch #?(:clj Throwable :cljs :default) e
          {:error (str "EDN parse error: " (ex-message e))})))))

;; ---- step orchestrator (pure shape, runtime-callable) -------------------
;;
;; `re-frame.machines/machine-transition` returns the Spec 005 §Level 1 map —
;; `{:status :ok :snapshot … :fx …}` or `{:status :error :error {:kind …}}`
;; — plain keys, so the sim reads it with no dependency on the machines
;; artefact (which may not be on the host's classpath at all). The `:fx`
;; ride into the audit-trail row but are NOT executed — sim is hermetic.

(defn step-sim
  "Fold one engine call into a sim-state update. `runtime-fn` is a
  unary fn `(fn [event] result)` that closes over
  `(re-frame.machines/machine-transition definition snapshot ...)`; the
  helper is shaped this way so the JVM
  test target can substitute a stub result without booting the machines
  artefact (production CLJS passes the real fn).

  Returns the next `sim-state` — either with an advanced snapshot +
  trail entry, or with `:last-error` populated.

  Per the UC1 design (§4 Guards) failed-guard transitions surface
  inline; sim does NOT mutate the snapshot on fail.

  ## `:ok` is not the same as \"a transition happened\" (rf2-y8doi.21)

  The engine returns `:status :ok` with the snapshot UNCHANGED and
  `:fx []` for three benign outcomes — a stale `:after`, a candidate
  whose every guard declined, and an event no transition matched
  (`machines.cljc`: \"An event no transition matched is `:status :ok`
  with the snapshot unchanged and `:fx []`\"; Spec 005 §Transition
  resolution makes the unhandled case an xstate-parity no-op, not an
  error). Folding those as transitions invented a `#N :open -> :open`
  audit row and animated a from=to edge for a step the framework never
  took. So a step that moved NOTHING appends no row.

  WHAT THIS CAN AND CANNOT TELL APART, because the diagnostic must not
  overclaim: the public Level 1 map carries `:status` / `:snapshot` /
  `:fx` and no handled flag, so an unchanged snapshot with no effects is
  ALSO what a genuine self-transition declared with no action produces
  (measured: a `{:on {:ping :open}}` self-loop in `:open` is
  byte-identical to the guard-blocked result). The rejection therefore
  names the three possibilities rather than asserting one."
  [sim-state event runtime-fn]
  (let [{:keys [snapshot]} sim-state
        prior-state        (:state snapshot)
        result             (runtime-fn event)]
    (case (when (map? result) (:status result))
      :error
      (record-error sim-state event (:error result) "transition failed")

      :ok
      (let [new-snap (:snapshot result)]
        (if (and (= new-snap snapshot) (empty? (:fx result)))
          (record-error sim-state event
            {:kind :rf.xray.static.machines.sim/no-change}
            (str "no change — no transition matched, a guard declined, "
                 "or the matched transition was a no-op"))
          (-> sim-state
              clear-error
              (assoc :snapshot new-snap)
              (append-audit-row
                {:from   prior-state
                 :to     (:state new-snap)
                 :event  event
                 :data   (:data new-snap)
                 :fx     (:fx result)}))))

      (record-error sim-state event nil "engine returned a non-result value"))))

;; ---- on-chart binding helpers -------------------------------------------
;;
;; The on-chart simulator renders the sim ON
;; the topology chart: the active state highlights amber, the taken
;; transition's edge animates, and clicking an outgoing event edge sends
;; that event into the SAME hermetic engine the step-button drives. These
;; pure helpers are the chart↔sim binding algebra — JVM-runnable so the
;; binding is unit-tested without a CLJS/xyflow runtime.

(defn last-transition
  "Project the sim's MOST RECENT step into the chart's focused-event lens
  inputs `{:from :to :event}` (or nil when no step has been taken yet).
  The chart renders `:from` with the dashed origin highlight + `:to`
  with the landing highlight, animating the taken edge.

  Reads the tail of the `:audit-trail` (newest-last per `append-audit-row`)
  — the audit row already carries `{:from :to :event}`, so this is a thin
  projection that keeps the chart binding from re-deriving it. Returns nil
  for a nil sim-state or an empty trail (no transition to animate)."
  [sim-state]
  (when-let [row (last (:audit-trail sim-state))]
    {:from  (:from row)
     :to    (:to row)
     :event (:event row)}))

(defn current-sim-state
  "The sim snapshot's current `:state` value (keyword or path vector),
  for the chart's active-state highlight. nil-safe — nil sim-state or
  missing snapshot returns nil (no highlight)."
  [sim-state]
  (get-in sim-state [:snapshot :state]))

(defn edge-click->event
  "Coerce an on-chart edge click into a re-frame event vector for
  `sim-step`. `event-id` is the raw fireable event keyword the chart's
  edge payload carried (the chart only makes plain `:on` transition
  edges clickable; `:after` / `:always` auto edges arrive with a nil
  event-id). Returns `[event-id]` for a usable keyword, nil otherwise —
  the caller suppresses the step on nil so an inert-edge click is a
  no-op rather than a malformed dispatch.

  Pure data — JVM-runnable; the CLJS click handler reads `:eventId` off
  the JS payload and hands the keyword here."
  [event-id]
  (when (keyword? event-id)
    [event-id]))

;; ---- display helpers ----------------------------------------------------

(defn format-state-display
  "Pretty-print a sim-snapshot `:state` for inline display. Keywords
  keep their `:`; vectors join with `.` for hierarchical clarity."
  [state]
  (cond
    (nil? state)     "(none)"
    (keyword? state) (str state)
    (vector? state)  (str "[" (str/join " " (map str state)) "]")
    :else            (str state)))

(defn format-destination
  "The right-hand \"where this goes\" label for one picker row. The `:on`
  rows and the `⌚` `:after` rows share it, so the two row kinds cannot
  drift apart on the one question they answer identically.

  A TARGETED row reads `→ :done` / `→ [:auth :form]`, exactly as it always
  has. A TARGETLESS one has no target to show and must not be handed a
  fabricated one, so it reads `↻ internal` — Spec 005's own word for the
  geometry (§Self-transitions: the `:action` runs, `:exit` / `:entry` do
  not, and the configuration including active descendants is unchanged) —
  with the action NAMED where the definition names it, because the action
  is the whole of what such a transition does and a row saying only
  `internal` would not be understandable.

  A candidate with neither target nor action is the forbidden-transition
  idiom (Spec 005 §Parent fallthrough): a deliberate event CONSUMER, which
  reads as the bare `↻ internal`. Firing one comes back as the existing
  amber \"No change\" — the same answer a guard-declined timer already
  gets, and the honest one, since consuming an event that no ancestor was
  going to handle really does change nothing.

  Pure data → string; JVM-runnable, so what the rail says about a row is
  pinned here rather than only in a browser."
  [{:keys [target action]}]
  (if (some? target)
    (str "→ " (if (vector? target) (pr-str target) (str target)))
    (str "↻ internal"
         (cond
           (keyword? action) (str " " action)
           (some? action)    " (fn)"
           :else             ""))))

(defn format-event-display
  "Compact event-vector formatter for the audit trail."
  [event]
  (if (nil? event)
    ""
    (try
      (pr-str event)
      (catch #?(:clj Throwable :cljs :default) _
        (str event)))))
