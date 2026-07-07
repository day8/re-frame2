(ns re-frame.story.play.runner-events
  "re-frame-side driver for the rich-DSL play runner.

  The pure step state machine + script parsing lives in
  `re-frame.story.play.runner`. This namespace owns the impure seams
  the step types reach for:

  - `:dispatch` → dispatch + settle through `settled-boundary`
    (`boundary/dispatch-and-settle!`) against the variant's
    frame; in headless this drains via `dispatch-sync*` to a fixed point
    (richer runners supply reactive / DOM flushes through flush-hooks).
    `:dispatch-sync` → the low-level `rf/dispatch-sync*` escape.
  - `:wait` ms → JS `setTimeout` (CLJS) or `Thread/sleep` (JVM).
  - `:assert-db` path value → read from `rf/app-db-value` and compare.
  - `:assert-db` path :pred fn-or-sym → invoke the predicate. A FN
    handed in directly is called as-is (advanced-CLJS-safe); a SYMBOL
    is resolved at run time via `requiring-resolve` (JVM) or a
    best-effort `goog.global` walk (CLJS — fragile under advanced
    compilation).
  - `:click` / `:type` / `:assert-dom` → delegate to
    `re-frame.story.play.dom` (no-op on JVM / no-DOM).

  The driver is async (returns a promise) so `:wait` and async
  dispatches compose naturally. On JVM, the driver runs the steps
  synchronously and returns a resolved future.

  ## Per-frame run state

  The runner stores a per-frame run-state map in a global atom
  (`run-state`) so the toolbar status chip can read it reactively.
  Keys: `{variant-id <runner state map>}`.

  ## Trace integration

  Each step emits a `:rf.story.play/step` trace event via
  `re-frame.trace.tooling/with-trace` so the Xray Trace tab shows the
  full play timeline with PASS/FAIL outcomes alongside the rest of the
  cascade."
  (:refer-clojure :exclude [run!])
  (:require [re-frame.core              :as rf]
            [re-frame.frame             :as frame]
            #?(:cljs [reagent.core      :as r])
            [re-frame.story.assertions  :as assertions]
            [re-frame.story.config      :as config]
            [re-frame.story.late-bind   :as late-bind]
            [re-frame.story.play        :as play]
            [re-frame.story.play.browser :as browser]
            [re-frame.story.play.dom    :as dom]
            [re-frame.story.play.evidence :as evidence]
            [re-frame.story.play.runner :as runner]
            [re-frame.story.play.settled-boundary :as boundary]
            [re-frame.story.predicates  :as pred]
            [re-frame.story.registrar   :as registrar]))

;; ---- per-variant run-state -----------------------------------------------

(defonce
  ^{:doc "frame-id → runner-state map. The UI's play-status chip derefs
         this atom and renders the per-variant status. The driver
         swaps the slot on every step transition. CLJS uses a Reagent
         ratom (so UI re-renders observe it); JVM uses a plain atom.

         rf2-tl7zk multi-play: the stored state carries a `:play-key`
         slot (the play's `:name` for `:plays` variants, nil for the
         single-script `:play-script` slot). The chip uses this to
         show which play was last run; per-play history lives in
         `runs-by-play` for finer-grained queries."}
  run-state
  #?(:cljs (r/atom {})
     :clj  (atom  {})))

(defonce
  ^{:doc "[frame-id play-key] → runner-state. rf2-tl7zk: per-play
         history so the toolbar dropdown can show each play's last
         outcome and the CI runner can read per-play terminal state.
         For single-script (`:play-script`) variants the only key is
         `[variant-id nil]`. CLJS uses a Reagent ratom."}
  runs-by-play
  #?(:cljs (r/atom {})
     :clj  (atom  {})))

(defonce
  ^{:doc "frame-id → play-key. rf2-tl7zk: the play the toolbar is
         currently focused on. Default is the first play's name (or
         nil for single-script variants). Changed by the dropdown's
         `select-play!`. Reagent ratom on CLJS."}
  active-play
  #?(:cljs (r/atom {})
     :clj  (atom  {})))

(defonce
  ^{:doc "[frame-id play-key] → vector of per-dispatch-step settle
         boundaries (rf2-rkd14). Each element is the `:epoch-id` of the
         most-recently-committed epoch at the moment a dispatch-opening
         step's settle BEGAN (0 when none has committed yet), recorded in
         dispatch-step execution order. The evidence projection
         (`runtime/record-result-map`) reads this through
         `settle-boundaries` and hands it to
         `evidence/project-evidence` as `:attribution`, lighting up the
         EXACT narrative attribution (`spans-from-stamps`) instead of the
         EVEN heuristic.

         rf2-96qsjr: boundaries are the framework's genuine, globally-
         monotonic `:epoch-id` — NOT an `epoch-history` ring-LENGTH
         snapshot. A length snapshot plateaus at the configured ring
         depth (default 50) once the ring fills, so two boundaries
         recorded after that point are numerically IDENTICAL — a long
         run (a big `:script`, a heavy cascade, or multi-play accumulation
         across ALL auto-plays) silently collapses distinct dispatch
         steps onto the same cursor and misattributes their epochs. An
         `:epoch-id` never plateaus and never depends on how much of the
         ring survives eviction — `stamp-tape` compares it directly
         against each SURVIVING record's own `:epoch-id`, so eviction can
         only ever drop beats, never misattribute them.

         rf2-m0cge5 finding 2: keyed by the `[frame-id play-key]` PAIR, not
         `frame-id` alone. A multi-play sequencer (`run-plays-sequentially!`
         / `runtime/run-phase-4!`) accumulates boundaries across several
         play-keys on ONE frame (`:clear-boundaries? false`); the per-`[frame
         play-key]` run-token guard (`current-state-for-play`) does NOT block
         a CONCURRENT `run!` / `re-run!` / `run-play!` for a DIFFERENT
         play-key on the SAME frame — that concurrent call's default
         `:clear-boundaries? true` used to wipe this ONE shared frame-id
         bucket out from under the in-flight sequence. Keying by the pair
         confines each play's boundaries to its own slot so a same-frame,
         different-play-key run can never collide with it; a multi-play
         reader concatenates the per-play-key slots in the same order it
         drove the plays (mirrors `:executed-script`'s
         `(mapcat :script auto-plays)`).

         Plain atom on both runtimes — it is read once at result-build
         time, not a reactive UI input."}
  step-boundaries
  (atom {}))

(defonce ^:private ^{:doc "Set of variant ids that have already received
                          the both-:play-script-and-:plays console
                          warning. One warning per variant per page
                          lifetime keeps the console quiet. rf2-a1lvd:
                          re-armed by `clear-all-runs!` so a fresh test
                          + each hot-reload reset can warn again (a
                          warn-once cache that is never reset suppresses
                          the affordance across test order + hot-reload)."}
  warned-both-slots
  (atom #{}))

(defn current-state
  "Read the current run-state for `frame-id`, or nil if no run exists."
  [frame-id]
  (get @run-state frame-id))

(defn settle-boundaries
  "Read the recorded per-dispatch-step settle boundaries for
  `[frame-id play-key]`, or nil. The runner-recorded narrative attribution
  the evidence projection consumes to stamp `:rf.story/script-idx`.

  rf2-m0cge5 finding 2: keyed by the pair, not `frame-id` alone — see
  `step-boundaries`'s docstring. A caller reading a MULTI-play sequence's
  accumulated boundaries calls this once per play-key it drove (in the
  same order) and concatenates the results."
  [frame-id play-key]
  (get @step-boundaries [frame-id play-key]))

(defn last-epoch-id
  "The `:epoch-id` of the most-recently-committed epoch for `frame-id`, or
  `0` if none has committed yet. `0` is never a real epoch-id — the
  framework counter is 1-based (`re-frame.epoch.state/next-epoch-id`) — so
  it composes as a safe exclusive lower bound with plain `<` comparisons.

  rf2-96qsjr: this is a genuine, globally-monotonic IDENTITY, not a
  ring-length/count snapshot. `rf/epoch-history` is a bounded per-frame
  ring (default depth 50); a count of its current length PLATEAUS once
  the ring is full (`(count (rf/epoch-history frame-id))` stops growing
  even though epochs keep committing), so a boundary bookkeeper built on
  it silently loses the ability to tell dispatch steps apart on a long
  run. `:epoch-id` keeps incrementing regardless of ring occupancy and
  survives eviction unchanged — a record that is still IN the ring always
  carries the same `:epoch-id` it was assigned at commit time, so
  comparing against it stays correct however much of the ring has been
  evicted since. Public — `runtime.cljc` reads this too (`:epoch-baseline`
  uses the same identity so the tape-scoping and the boundary bookkeeping
  agree on what a run's epochs look like). Tolerant — 0 when the frame
  has no history / the epoch dep is absent (the facade degrades to `[]`)."
  [frame-id]
  (try (or (:epoch-id (last (rf/epoch-history frame-id))) 0)
       (catch #?(:clj Throwable :cljs :default) _ 0)))

(defn- record-settle-boundary!
  "Snapshot the last-committed `:epoch-id` at the START of a dispatch-
  opening `step`'s settle. No-op for non-dispatch steps (assert / wait /
  unknown) — those commit no span of their own; their epochs roll into
  the preceding dispatch span (the narrative's forward-attribution model).
  The boundaries accumulate in dispatch-step execution order, which — for
  both the single-play and multi-play (sequential) runner — is exactly the
  order the dispatch steps appear in the concatenated executed-script the
  narrative spans. For multi-play this holds ONLY because the sequencer
  clears once up front and drives each play with `:clear-boundaries? false`:
  the boundaries from play K+1 (epoch-ids, which only ever increase) tail
  play K's, so the full vector zips against the concatenated script in
  dispatch order — an ordering fact, independent of ring occupancy. A
  per-play clear would drop every earlier play's boundaries, leaving only
  the last play's to be mis-zipped.

  rf2-m0cge5 finding 2: writes to the `[frame-id play-key]` composite key
  (see `step-boundaries`'s docstring), so a same-frame run of a DIFFERENT
  play-key accumulates into its OWN slot and can never collide with — or
  be wiped alongside — this one."
  [frame-id play-key step]
  (when (evidence/dispatch-step? step)
    (swap! step-boundaries update [frame-id play-key] (fnil conj []) (last-epoch-id frame-id)))
  nil)

(defn current-state-for-play
  "Read the run-state for `(frame-id, play-key)`, or nil. Exposes
  per-play state for the dropdown's per-row status badges + the CI
  runner's per-play outcome read."
  [frame-id play-key]
  (get @runs-by-play [frame-id play-key]))

(defn active-play-key
  "Return the play-key the toolbar is currently focused on for
  `frame-id`, or nil (multi-play)."
  [frame-id]
  (get @active-play frame-id))

(defn set-active-play!
  "Set the toolbar's focused play for `frame-id` (multi-play).
  Idempotent — re-setting the same key is a no-op."
  [frame-id play-key]
  (swap! active-play assoc frame-id play-key)
  nil)

(defn clear-step-boundaries!
  "Reset the per-dispatch-step settle boundaries for `[frame-id play-key]`
  (the 1-arity defaults `play-key` to nil — the default/single play, the
  stepper's convention). Called at the START of a fresh script run so the
  narrative attribution windows onto THIS run's epoch tape, not a previous
  run's boundaries.

  Owned by every entry that WRITES boundaries: the public driver
  `run!` (clears its OWN resolved play-key), the multi-play sequencers
  (`run-plays-sequentially!`, the orchestrator `runtime/run-phase-4!` —
  which also covers its no-auto-plays branch) clear each play-key they are
  ABOUT to drive, and the stepper start `play/begin-stepper!` (via the
  `:clear-step-boundaries` late-bind seam, always play-key nil — the
  stepper only ever walks the default play). So a non-orchestrator re-run /
  replay-in-place / stepping session never inherits stale accumulated
  offsets that would mis-attribute the exact narrative.

  rf2-m0cge5 finding 2: scoped to ONE `[frame-id play-key]` slot (see
  `step-boundaries`'s docstring) — clearing play-key K's boundaries never
  touches a DIFFERENT play-key's slot on the same frame, closing the
  concurrent-run wipe hazard."
  ([frame-id] (clear-step-boundaries! frame-id nil))
  ([frame-id play-key]
   (swap! step-boundaries dissoc [frame-id play-key])
   nil))

(defn clear-state!
  "Wipe the run-state for `frame-id` across all four process-global atoms
  (`run-state` / `runs-by-play` / `active-play` / `step-boundaries`).
  Wired into frame teardown via the `:drop-run-state` late-bind hook
  (`frames/destroy!` + `destroy-inline!`) so a destroyed variant
  frame leaves no stale terminal play status behind."
  [frame-id]
  (swap! run-state dissoc frame-id)
  (swap! runs-by-play (fn [m]
                        (into {} (remove (fn [[[fid _]]]
                                           (= fid frame-id)) m))))
  (swap! active-play dissoc frame-id)
  ;; `step-boundaries` is keyed by [frame-id play-key] (rf2-m0cge5 finding
  ;; 2) — evict every play-key's slot for this frame, mirroring the
  ;; `runs-by-play` cleanup just above.
  (swap! step-boundaries (fn [m]
                           (into {} (remove (fn [[[fid _]]]
                                              (= fid frame-id)) m))))
  nil)

(defn clear-all-runs!
  "Wipe ALL per-variant run-state across every frame — the four
  per-process atoms (`run-state` / `runs-by-play` / `active-play` /
  `step-boundaries`) AND the `warned-both-slots` one-shot warning cache.
  Used by the Story test-fixture helper so a fresh test doesn't observe a
  previous test's play outcomes; `clear-state!` is the per-frame
  counterpart called from teardown.

  Clearing `warned-both-slots` here re-arms the both-`:play-script`-and-
  `:plays` console warning per test and per hot-reload reset, following
  the warn-once-clear pattern: a warn-once cache that is never reset
  suppresses the affordance across test order + hot-reload."
  []
  (reset! run-state      {})
  (reset! runs-by-play   {})
  (reset! active-play    {})
  (reset! step-boundaries {})
  (reset! warned-both-slots #{})
  nil)

(defn- update-state!
  [frame-id play-key f & args]
  (swap! run-state update frame-id #(apply f % args))
  (swap! runs-by-play update [frame-id play-key] #(apply f % args))
  nil)

(defn- set-state!
  [frame-id play-key state]
  (let [tagged (assoc state :play-key play-key)]
    (swap! run-state    assoc frame-id tagged)
    (swap! runs-by-play assoc [frame-id play-key] tagged))
  nil)

;; ---- wall-clock probe ----------------------------------------------------

(defn- now-ms []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

;; ---- spec resolution -----------------------------------------------------

(defn- handler-meta
  "Look up the variant body via the framework registrar."
  [variant-id]
  (try
    (registrar/handler-meta :variant variant-id)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

;; ---- replay target (EP-0023) ---------------------------------------------
;;
;; A recording's replay address is the variant FRAME (`{:frame variant-id}`).
;; The public address is that single frame target: the runner dispatches
;; frame-scoped, and the framework derives the running environment from the
;; targeted frame, so replay lands in the frame's own image generation by
;; construction. Realm is the framework's internal installation substrate —
;; the runner carries no separate realm key on the replay address.

;; ---- folded-plan consumption --------------------------------------------
;;
;; The runtime CONSUMES the folded plan: every play's script is folded
;; through `assertions/fold-script` at resolution time, so a shipping
;; `:assert-db` / `:assert-dom` step is rewritten to the canonical
;; `[:assert assertion-atom]` checkpoint BEFORE the run loop drives it
;; (spec/017 §Assertions — one atom, two positions). After folding,
;; `exec-step!` only ever sees the ONE assertion atom in its `[:assert …]`
;; checkpoint position — there is no synthetic `:rf.assert/db` /
;; `:rf.assert/dom` rail (a folded `:assert-db` dispatches the real
;; `:rf.assert/path-equals` handler; a folded `:assert-dom` routes through
;; the DOM executor recording a canonical `:rf.assert/dom-*` record). One
;; assertion-record vocabulary, not two.

(defn- fold-spec
  "Fold a parsed play spec's `:script` through `assertions/fold-script` so
  shipping `:assert-db` / `:assert-dom` steps become canonical
  `[:assert …]` checkpoints. Pure data → data; preserves
  `:auto-run?` / `:name`."
  [spec]
  (cond-> spec
    (contains? spec :script) (update :script assertions/fold-script)))

(defn variant-play-script
  "Resolve the `:play-script` body on `variant-id`, parse it, and FOLD its
  script. Returns the normalised spec map per `runner/parse-spec` with
  every shipping `:assert-db` / `:assert-dom` step rewritten to the
  canonical `[:assert …]` checkpoint. Variants without `:play-script`
  return `{:script [] :auto-run? true}`.

  Note: variants declaring `:plays` resolve to the FIRST play's spec —
  matching the single-script call sites + the toolbar's 'default play'
  behaviour. Callers that need the full plays vector should use
  `variant-plays` instead."
  [variant-id]
  (let [body  (handler-meta variant-id)
        plays (runner/variant-body->plays body)]
    (fold-spec
      (cond
        ;; Multi-play (:plays slot) — default to the first play.
        (and (seq plays) (contains? body :plays))
        (first plays)

        ;; Single-script (:play-script slot).
        :else
        (runner/parse-spec (when body (:play-script body)))))))

;; ---- multi-play warning (one-shot) ---------------------------------------

(defn- warn-both-slots-once!
  [variant-id]
  (when (and variant-id (not (contains? @warned-both-slots variant-id)))
    (swap! warned-both-slots conj variant-id)
    #?(:cljs
       (try
         (js/console.warn
           (str "[re-frame.story.play] " (pr-str variant-id)
                " declares BOTH :play-script and :plays — preferring :plays."
                " Pick one per variant to silence this warning."))
         (catch :default _ nil))
       :clj
       (binding [*out* *err*]
         (println (str "[re-frame.story.play] " (pr-str variant-id)
                       " declares BOTH :play-script and :plays — preferring :plays."
                       " Pick one per variant to silence this warning."))))))

(defn variant-plays
  "Resolve the canonical vector of parsed plays for `variant-id`. Pure
  data → data; works on JVM + CLJS (multi-play).

  - `:plays` present → returns the parsed plays vector (size >= 1).
  - `:play-script` present → returns a single-entry vector wrapping the
    parsed single-script spec.
  - Both present → warns once, prefers `:plays`.
  - Neither → returns `[]`."
  [variant-id]
  (let [body (handler-meta variant-id)]
    (when (and body
               (contains? body :play-script)
               (contains? body :plays))
      (warn-both-slots-once! variant-id))
    ;; FOLD every resolved play's script so the runtime consumes the
    ;; folded plan: `:assert-db` / `:assert-dom` steps become canonical
    ;; `[:assert …]` checkpoints before the run loop.
    (mapv fold-spec (runner/variant-body->plays body))))

(defn resolve-play
  "Resolve a `(variant-id, play-key)` pair to the parsed, FOLDED play spec,
  or nil. `play-key` may be nil — meaning 'the default play' (first
  entry for multi-play, the single script for `:play-script`).
  Multi-play. The script is folded via `variant-plays`."
  [variant-id play-key]
  (let [plays (variant-plays variant-id)]
    (if (nil? play-key)
      (first plays)
      (runner/find-play plays play-key))))

;; ---- trace emission ------------------------------------------------------

(defn- emit-trace!
  "Emit a `:rf.story.play/step` trace event for `step` against
  `variant-id`. Goes through `re-frame.core/emit-trace-event!` (which
  delegates to `re-frame.trace/emit!`) so the bus + ring buffer +
  listener fan-out all observe it. Safe under production elision —
  `re-frame.trace/emit!` short-circuits when `interop/debug-enabled?`
  is false."
  [variant-id name idx step result]
  (try
    (let [payload (runner/trace-record
                    {:variant-id variant-id
                     :name       name
                     :idx        idx
                     :step       step
                     :result     result})]
      ;; rf/emit-trace-event! arity is (op operation tags) per
      ;; re-frame.trace/emit!. We tag the envelope's :frame slot so
      ;; consumers (Trace tab, story.assertions listener) can route
      ;; per-frame.
      (rf/emit-trace-event!
        :rf.story.play/step
        runner/trace-event-id
        (merge {:frame variant-id} payload)))
    (catch #?(:clj Throwable :cljs :default) _ nil)))

;; ---- settled-boundary flush hooks ---------------------------------------
;;
;; `[:dispatch event-vector]` settles through `settled-boundary` (spec/017
;; §Script and `settled-boundary`). The runner takes its flush-hooks from
;; the adapter-aware caller via the `:settled-boundary-hooks` late-bind
;; slot; the default is the headless hooks (`dispatch-sync*` drain). Story
;; core never reaches for `dispatch-sync` directly — the boundary ns owns
;; the drain, the hooks own the richer reactive / DOM flushes.

(defn current-flush-hooks
  "Resolve the active flush-hooks map. An adapter-aware caller (the
  Reagent/UIx/Helix shell, a future `:dom` browser runner) registers a
  richer hooks map via `late-bind/set-fn! :settled-boundary-hooks <fn>`,
  where the fn takes the frame-id and returns the hooks for that frame.
  When no adapter has registered, the headless hooks are used so a
  `[:dispatch …]` step settles to fixed point synchronously."
  [frame-id]
  (if-let [f (late-bind/get-fn :settled-boundary-hooks)]
    (or (try (f frame-id) (catch #?(:clj Throwable :cljs :default) _ nil))
        boundary/headless-flush-hooks)
    boundary/headless-flush-hooks))

;; ---- step executors ------------------------------------------------------

(declare read-frame-db)

(defn- assertion-count
  "Count of records currently in the frame's `:rf.story/assertions`. Tolerant."
  [frame-id]
  (count (:rf.story/assertions (read-frame-db frame-id))))

(defn- failed-since
  "Records appended to `:rf.story/assertions` since `prev-count` that
  have `:passed? false`. Used to bridge handler-recorded assertion
  failures (e.g. `:dispatch-sync [:rf.assert/path-equals ...]`) back
  into the runner's step-result stream so the play's terminal status
  reflects the failure."
  [frame-id prev-count]
  (let [all (vec (:rf.story/assertions (read-frame-db frame-id)))]
    (filterv (fn [r] (false? (:passed? r)))
             (subvec all (min prev-count (count all))))))

(defn- dispatch-step-result
  "Build the step result for a (possibly-assertion-bearing) :dispatch /
  :dispatch-sync. If new failed assertions appeared in the frame's
  `:rf.story/assertions` since `prev` (typically because the
  dispatched event was a `:rf.assert/*` whose reg-event handler
  recorded a `:passed? false` record), surface them as a step-fail.
  Otherwise step-skip (no assertion contribution to pass/fail)."
  [frame-id prev idx step]
  (let [failed (failed-since frame-id prev)]
    (if (seq failed)
      (let [rec (first failed)
            msg (or (:message rec)
                    (str (:id rec) " " (pr-str (:payload rec))
                         " failed (expected " (pr-str (:expected rec))
                         ", actual " (pr-str (:actual rec)) ")"))]
        (runner/step-fail idx step
                          {:expected (:expected rec)
                           :actual   (:actual rec)
                           :message  msg}))
      (runner/step-skip idx step))))

(defn- boundary-result->step
  "Project a `settled-boundary/dispatch-and-settle!` non-`:settled`
  outcome into a runner step-result. `:cannot-run` becomes a step-fail
  carrying the refusal (so the play surfaces a distinct refusal, never a
  silent pass — spec/017 §`:cannot-run`); `:error` becomes a step-fail
  with the boundary error message. Returns nil for `:settled` (the
  dispatch's pass/fail comes from the assertion bridge instead)."
  [idx step settle]
  (case (:status settle)
    :cannot-run
    (runner/step-fail idx step
                      {:cannot-run? true
                       :required-boundary (:required-boundary settle)
                       :provided-boundary (:provided-boundary settle)
                       :reason   (:reason settle)
                       :message  (str "cannot run " (pr-str step)
                                      " — requires settled-boundary "
                                      (pr-str (:required-boundary settle))
                                      " but runner provides "
                                      (pr-str (:provided-boundary settle))
                                      " (" (name (or (:reason settle) :runner-below-required-boundary)) ")")})
    :error
    (runner/step-exception idx step (:error settle))
    ;; :settled → no step-level result from the boundary itself.
    nil))

(defn- exec-dispatch!
  "Execute a `:dispatch` step — dispatch the event and settle through
  `settled-boundary` (spec/017 §Script and `settled-boundary`).
  In headless this is the `dispatch-sync*` run-to-fixed-point drain,
  named via `settled-boundary`; richer runners
  supply reactive / DOM flushes through their flush-hooks. The runner
  NEVER hard-codes `dispatch-sync` — it routes through the boundary's
  caller-supplied hooks (`current-flush-hooks`).

  When the active runner cannot satisfy the step's required boundary the
  boundary refuses with `:cannot-run` and the step is NOT dispatched
  (fail-closed); the refusal becomes a runner step-fail rather than a
  silent pass.

  Drains any handler-exception trace events captured by the play
  listener into `:rf.story/assertions` so the test-mode pane + Xray
  assertions panel see the failure. The re-frame router
  catches handler exceptions and emits `:rf.error/handler-exception`
  rather than re-throwing, so the local catch fires only for
  exceptions that escape the interceptor chain entirely.

  Bridges handler-recorded `:rf.assert/*` failures into the runner's
  step-result stream — a `:rf.assert/*` event whose handler records
  `:passed? false` becomes a runner-visible step-fail so the play's
  terminal status flips to `:fail`."
  [frame-id idx step]
  (let [evec     (runner/step-event step)
        ;; A replayed `[:dispatch evec {:rf.cofx …}]` step carries a
        ;; captured recordable-coeffect envelope. Pass it to the boundary
        ;; as dispatch-opts so the handler's declared coeffects replay from
        ;; the recorded value instead of being restamped.
        cofx     (runner/step-cofx step)
        dopts    (when (and (map? cofx) (seq cofx)) {:rf.cofx cofx})
        prev     (assertion-count frame-id)
        required (boundary/step-required-boundary step)
        hooks    (current-flush-hooks frame-id)
        settle   (try
                   (boundary/dispatch-and-settle! frame-id evec hooks required step dopts)
                   (catch #?(:clj Throwable :cljs :default) e
                     {:status :error
                      :error  #?(:clj (.getMessage ^Throwable e) :cljs (str e))
                      :step   step}))]
    (play/drain-pending-exceptions! frame-id :phase-4-play)
    (or (boundary-result->step idx step settle)
        (dispatch-step-result frame-id prev idx step))))

(defn- exec-dispatch-sync!
  [frame-id idx step]
  (let [evec   (runner/step-event step)
        ;; When the step carries a captured `:rf.cofx` envelope, thread it
        ;; into the dispatch opts so the handler's declared recordable
        ;; coeffects (provided facts + the framework `:rf/time-ms`) replay
        ;; from the recorded value instead of being restamped / failing
        ;; `:rf.error/missing-required-cofx`. Absent cofx dispatches with
        ;; the bare frame opts.
        cofx   (runner/step-cofx step)
        opts   (cond-> {:frame frame-id}
                 (and (map? cofx) (seq cofx)) (assoc :rf.cofx cofx))
        prev   (assertion-count frame-id)
        result (try
                 (rf/dispatch-sync* evec opts)
                 nil
                 (catch #?(:clj Throwable :cljs :default) e
                   (runner/step-exception idx step
                                          #?(:clj  (.getMessage ^Throwable e)
                                             :cljs (str e)))))]
    (play/drain-pending-exceptions! frame-id :phase-4-play)
    (or result (dispatch-step-result frame-id prev idx step))))

(defn- read-frame-db
  "Read the app-db for `frame-id`. Tolerant — returns nil if the frame
  is gone."
  [frame-id]
  (try
    (rf/app-db-value frame-id)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

;; ---- DOM-family assertion atom executor ---------------------------------
;;
;; The DOM family (`:rf.assert/dom-visible` / `:rf.assert/dom-hidden` /
;; `:rf.assert/dom-text`) is the fold target for the shipping `:assert-dom`
;; step. It has no reg-event handler (the DOM runner that PROVES it is the
;; `:dom` capability wired via `requirements`), so an
;; `[:assert [:rf.assert/dom-* …]]` checkpoint is EVALUATED directly through
;; the DOM executor (`dom/assert-visible` / `dom/assert-text`) and records a
;; CANONICAL `:rf.assert/dom-*` record on the frame's `:rf.story/assertions`
;; slot. There is no synthetic `:rf.assert/dom` id — the canonical folded id
;; is the one recorded.

(def ^:private dom-atom->mode
  "Map a DOM-family assertion id to the `dom/assert-visible` mode it
  evaluates. `:rf.assert/dom-text` is handled separately (it calls
  `dom/assert-text`)."
  {:rf.assert/dom-visible :visible
   :rf.assert/dom-hidden  :hidden})

(defn- exec-assert-dom-atom!
  "Evaluate a folded DOM-family assertion atom `[:rf.assert/dom-* selector
  & args]` at this checkpoint. Drives the DOM executor,
  records the CANONICAL `:rf.assert/dom-*` record on the frame slot (so the
  unified result's `:assertions` carries the real folded id, not a
  synthetic one), and returns the runner step-result. A no-DOM headless
  context records a `{:skipped? true}` step — `:cannot-run` at assertion
  granularity (the slot mirror keeps it OUT of the recorded outcomes so it
  is not a vacuous pass), never a silent green."
  [frame-id idx step atom-v]
  (let [aid      (first atom-v)
        selector (nth atom-v 1 nil)
        result   (if (= :rf.assert/dom-text aid)
                   (dom/assert-text selector (nth atom-v 2 nil))
                   (dom/assert-visible selector (get dom-atom->mode aid :visible)))]
    ;; Record the canonical DOM-family assertion record on the slot (unless
    ;; the step was a no-DOM skip — a skip proved nothing, so it must not
    ;; read as a vacuous pass).
    (when-not (:skipped? result)
      (assertions/record!
        frame-id
        (cond-> {:assertion    aid
                 :passed?      (boolean (:passed? result))
                 :payload      (vec (rest atom-v))
                 :source-coord (:source (registrar/handler-meta :variant frame-id))}
          (contains? result :expected) (assoc :expected (:expected result))
          (contains? result :actual)   (assoc :actual   (:actual result))
          (:message result)            (assoc :reason   (:message result)))))
    (cond
      (:skipped? result) (runner/step-fail idx step
                                           {:skipped? true
                                            :message  (or (:message result)
                                                          (str "no DOM — cannot prove "
                                                               (pr-str atom-v)))})
      (:passed? result)  (runner/step-pass idx step)
      :else              (runner/step-fail idx step result))))

(defn- exec-click!
  [_frame-id idx step]
  (let [selector (runner/step-selector step)]
    (cond
      (not (dom/dom-available?))
      (runner/step-fail idx step
                        {:skipped? true
                         :message  (str "no DOM — cannot click " (pr-str selector))})

      (dom/click! selector)
      (runner/step-skip idx step)

      :else
      (runner/step-fail idx step
                        {:message (str "click failed — no node matched " (pr-str selector))}))))

(defn- exec-type!
  [_frame-id idx step]
  (let [[selector text] (runner/step-type-text step)]
    (cond
      (not (dom/dom-available?))
      (runner/step-fail idx step
                        {:skipped? true
                         :message  (str "no DOM — cannot type into " (pr-str selector))})

      (dom/type! selector text)
      (runner/step-skip idx step)

      :else
      (runner/step-fail idx step
                        {:message (str "type failed — no node matched " (pr-str selector))}))))

(defn- exec-focus!
  "Execute a `[:focus selector]` step — a DOM focus event.
  Parallels `exec-click!`: a no-DOM headless runner records a
  `{:skipped? true}` no-op (the capability registry already refuses the
  step at preflight, so this is the belt-and-braces runtime guard); a
  DOM/browser runner fires the synthetic focus and records a step-skip."
  [_frame-id idx step]
  (let [selector (runner/step-selector step)]
    (cond
      (not (dom/dom-available?))
      (runner/step-fail idx step
                        {:skipped? true
                         :message  (str "no DOM — cannot focus " (pr-str selector))})

      (dom/focus! selector)
      (runner/step-skip idx step)

      :else
      (runner/step-fail idx step
                        {:message (str "focus failed — no node matched " (pr-str selector))}))))

(declare exec-assert-dom-atom!)

;; ---- browser-tier assertion atom executor -------------------------------
;;
;; The browser-tier oracle family (`:rf.assert/visual-snapshot` /
;; `:rf.assert/a11y` / `:rf.assert/a11y-structural`) has its dedicated pure
;; executor in `re-frame.story.play.browser` (`eval-browser-assertion`). The
;; run path wires that executor IN, mirroring how the DOM family is routed to
;; `exec-assert-dom-atom!`:
;;
;;   - `:rf.assert/a11y-structural` is the `:hiccup` rung — it walks the
;;     rendered hiccup TREE (data). When a host can supply that tree (the
;;     `:render-hiccup` late-bind seam — a `:hiccup`-or-richer runner), the
;;     check EVALUATES (:pass / :fail) on the normal run path. When NO hiccup
;;     tree is available (the bare headless floor), it FAILS CLOSED to
;;     `:cannot-run` — NEVER a vacuous pass over a nil tree.
;;   - `:rf.assert/visual-snapshot` / `:rf.assert/a11y` are browser-only
;;     (`:pixels` / `:a11y-engine`); the executor's own `browser-available?`
;;     fail-closed guard returns `:cannot-run` under a headless run.
;;
;; The canonical assertion record the executor produces is recorded on the
;; frame's `:rf.story/assertions` slot (the ONE accumulator
;; `record-result-map` / `result/run-result` folds into the unified verdict),
;; exactly as the DOM family is. A `:cannot-run` finding rides the SAME
;; refusal shape (`:status :cannot-run`) the rest of the run path uses, so a
;; browser-tier assertion the runner could not prove surfaces as the distinct
;; THIRD status, never a false pass/fail.

(defn- render-hiccup-for
  "Resolve the rendered hiccup tree for `frame-id` via the `:render-hiccup`
  late-bind seam (`re-frame.story.late-bind`), or nil when no host installed
  one (the bare headless floor — no `:hiccup-structure` proof). Tolerant: a
  throwing host yields nil. This is the `:hiccup`-tier proof surface
  `:rf.assert/a11y-structural` walks."
  [frame-id]
  (when-let [f (late-bind/get-fn :render-hiccup)]
    (try (f frame-id)
         (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- browser-assertion-ctx
  "Build the per-run `ctx` `browser/eval-browser-assertion` consumes for
  `frame-id`. Carries `:frame-id` (so the a11y evaluator can read the live
  axe-violations atom) and the rendered `:hiccup` tree from the
  `:render-hiccup` seam (so the structural-a11y evaluator can walk it). The
  visual evaluator computes its own snapshot identity; nothing more is
  threaded here."
  [frame-id hiccup]
  {:frame-id frame-id
   :hiccup   hiccup})

(defn- structural-a11y-cannot-run-finding
  "The `:cannot-run` record for an `:rf.assert/a11y-structural` checkpoint on
  a runner that cannot supply a rendered hiccup tree (the bare headless floor
  — no `:render-hiccup` host, no `:hiccup-structure` proof). Rides the ONE
  refusal shape (`:status :cannot-run`) so it is the distinct THIRD status,
  never a vacuous pass over a nil tree (the honesty floor — spec/017
  §`:cannot-run`)."
  [payload]
  {:assertion   assertions/id-a11y-structural
   :payload     (vec payload)
   :passed?     false
   :cannot-run? true
   :status      :cannot-run
   :reason      (str "structural a11y requires a rendered hiccup tree "
                     "(:hiccup-structure); the headless runner produced none "
                     "(no :render-hiccup host)")})

(defn- exec-assert-browser-atom!
  "Evaluate a browser-tier oracle assertion atom `[:rf.assert/visual-snapshot
  | :rf.assert/a11y | :rf.assert/a11y-structural & args]` at this checkpoint
  (drives `browser/eval-browser-assertion` from the run path). Mirrors
  `exec-assert-dom-atom!`: drives the pure
  executor, records the CANONICAL assertion record on the frame slot (so the
  unified result's `:assertions` carries the real browser-tier id), and
  returns the runner step-result.

  - `:rf.assert/a11y-structural` runs at the `:hiccup` tier: it walks the
    rendered hiccup tree from the `:render-hiccup` seam. With NO tree
    available it FAILS CLOSED to `:cannot-run` (never a vacuous pass over a
    nil tree); with a tree it EVALUATES (:pass / :fail).
  - `:rf.assert/visual-snapshot` / `:rf.assert/a11y` are browser-only; the
    executor's `browser-available?` guard returns `:cannot-run` headless.

  A `:cannot-run` finding is recorded on the slot (so the run aggregates to
  `:cannot-run`, never a silent pass) and surfaced as a step-fail carrying the
  refusal."
  [frame-id idx step atom-v]
  (let [aid    (assertions/assertion-atom-id atom-v)
        hiccup (render-hiccup-for frame-id)
        result (if (and (= assertions/id-a11y-structural aid) (nil? hiccup))
                 ;; Honesty floor: a11y-structural with no hiccup tree cannot
                 ;; be proven — refuse rather than pass over an empty tree.
                 (structural-a11y-cannot-run-finding (vec (rest atom-v)))
                 (browser/eval-browser-assertion
                   atom-v (browser-assertion-ctx frame-id hiccup)))]
    ;; Record the canonical browser-tier assertion record on the slot so the
    ;; unified verdict folds it (a :cannot-run record aggregates to the THIRD
    ;; status; a :fail flips the run to :fail).
    (assertions/record!
      frame-id
      (cond-> {:assertion    aid
               :passed?      (boolean (:passed? result))
               :payload      (vec (rest atom-v))
               :source-coord (:source (registrar/handler-meta :variant frame-id))}
        (contains? result :status)   (assoc :status   (:status result))
        (:cannot-run? result)        (assoc :cannot-run? true)
        (contains? result :expected) (assoc :expected (:expected result))
        (contains? result :actual)   (assoc :actual   (:actual result))
        (:reason result)             (assoc :reason   (:reason result))))
    (cond
      (:cannot-run? result)
      (runner/step-fail idx step
                        {:cannot-run? true
                         :reason      (:reason result)
                         :message     (or (:reason result)
                                          (str "cannot run " (pr-str atom-v)))})
      (:passed? result) (runner/step-pass idx step)
      :else             (runner/step-fail idx step
                                          {:expected (:expected result)
                                           :actual   (:actual result)
                                           :message  (:reason result)}))))

(defn- tape-evaluated-assertion?
  "True iff the assertion atom `atom-v` (`[:rf.assert/id & args]`) is
  evaluated AGAINST THE EPOCH TAPE in the result boundary rather than by
  dispatching a `reg-event` handler into the frame.

  Two assertion families carry NO `reg-event` handler and are minted
  by the result boundary (`re-frame.story.result`), not by a dispatch:

  - `:rf.assert/schema-error` — paired against the projected
    `:rf.error/schema-validation-failure` evidence.
  - the causal / cascade family (`:rf.assert/caused` /
    `:rf.assert/no-cascade-rerender`) — paired against the
    `:reactive-counts` `:by-cause` projection.

  This is the SINGLE classifier the in-script `[:assert …]` executor
  consults so a tape-evaluated checkpoint is NEVER dispatched into the
  frame (which, with no handler, would mint a spurious
  `:rf.error/no-such-handler` trace and skip the real tape evaluation).
  It deliberately reads the existing `re-frame.story.assertions`
  predicates / id-sets — the ONE source of truth for which family an id
  belongs to — so a future tape-evaluated family is covered by adding it
  there, with no special-case to grow here.

  Two families are NOT here — each has its own inline executor routed
  AHEAD of this classifier: the DOM family (`exec-assert-dom-atom!`) and
  the browser-tier oracle family (`exec-assert-browser-atom!`). The
  browser-tier family is routed to its dedicated executor (which evaluates
  the rendered hiccup tree and surfaces the honest `:cannot-run` for the
  browser-only pair), so it is NOT tape-evaluated."
  [atom-v]
  (or (assertions/schema-error? atom-v)
      (assertions/causal? atom-v)))

(defn- exec-assert!
  "Execute an `[:assert [:rf.assert/id & args]]` in-script checkpoint.
  Evaluates the wrapped assertion atom at THIS point in the script
  (spec/017 §Inline script assertions vs terminal assertions). This is
  the SINGLE in-script assertion executor — after the fold the runtime
  consumes, a shipping `:assert-db` / `:assert-dom` step has already been
  rewritten to this `[:assert …]` checkpoint, so there is no parallel
  `:assert-db` / `:assert-dom` executor minting synthetic `:rf.assert/db`
  / `:rf.assert/dom` records.

  Four routes, by atom family:

  - The DOM family (`:rf.assert/dom-visible` / `:rf.assert/dom-hidden` /
    `:rf.assert/dom-text`) has no reg-event handler yet (the DOM runner
    that proves it lands later); it is EVALUATED directly through the DOM
    executor (`exec-assert-dom-atom!`), recording a canonical
    `:rf.assert/dom-*` record on the slot.
  - The browser-tier oracle family (`:rf.assert/visual-snapshot` /
    `:rf.assert/a11y` / `:rf.assert/a11y-structural`) is EVALUATED directly
    through `exec-assert-browser-atom!` (driving
    `browser/eval-browser-assertion`). At the
    `:hiccup` tier `:rf.assert/a11y-structural` walks the rendered hiccup
    tree and records a real `:pass` / `:fail`; with no tree (the headless
    floor) it records `:cannot-run`. The browser-only pair record
    `:cannot-run` under a headless run (their `browser-available?` guard).
  - The tape-evaluated families (`tape-evaluated-assertion?`: schema-error,
    the causal / cascade family) carry no reg-event handler — they are
    minted by the result boundary against the epoch tape, not by a dispatch.
    An in-script checkpoint for one of these records a no-op step-skip (the
    boundary owns its verdict); dispatching it would mint a spurious
    `:rf.error/no-such-handler` trace AND skip the real tape evaluation.
  - Every other (dispatchable) `:rf.assert/*` atom dispatches the event
    through `settled-boundary` — the standard reg-event handler records
    the `:passed?` record on the frame's `:rf.story/assertions` slot — and
    we bridge that record back into the runner step stream so a failing
    checkpoint flips the play's terminal status to `:fail`."
  [frame-id idx step]
  (let [atom-v (runner/step-assertion step)]
    (cond
      (contains? assertions/dom-assertion-ids (first atom-v))
      (exec-assert-dom-atom! frame-id idx step atom-v)

      ;; Browser-tier oracle family — route to the dedicated executor.
      ;; a11y-structural runs at :hiccup; visual / a11y fail closed to
      ;; :cannot-run headless. NEVER a no-op skip.
      (contains? assertions/browser-assertion-ids (first atom-v))
      (exec-assert-browser-atom! frame-id idx step atom-v)

      ;; Tape-evaluated families carry no reg-event handler; the result
      ;; boundary owns their verdict. Record a no-op step-skip — never a
      ;; dispatch (which would hit :rf.error/no-such-handler).
      (tape-evaluated-assertion? atom-v)
      (runner/step-skip idx step)

      :else
      (let [prev     (assertion-count frame-id)
            required (boundary/step-required-boundary step)
            hooks    (current-flush-hooks frame-id)
            settle   (try
                       (boundary/dispatch-and-settle! frame-id atom-v hooks required step)
                       (catch #?(:clj Throwable :cljs :default) e
                         {:status :error
                          :error  #?(:clj (.getMessage ^Throwable e) :cljs (str e))
                          :step   step}))]
        (play/drain-pending-exceptions! frame-id :phase-4-play)
        (or (boundary-result->step idx step settle)
            ;; The wrapped :rf.assert/* handler recorded its outcome on the
            ;; frame's :rf.story/assertions slot. Read what landed since the
            ;; pre-dispatch count and surface it as the checkpoint's result.
            (let [all   (vec (:rf.story/assertions (read-frame-db frame-id)))
                  new   (subvec all (min prev (count all)))
                  rec   (last new)]
              (cond
                (nil? rec)            (runner/step-skip idx step)
                (false? (:passed? rec))
                (runner/step-fail idx step
                                  {:expected (:expected rec)
                                   :actual   (:actual rec)
                                   :message  (or (:reason rec)
                                                 (str (:assertion rec) " "
                                                      (pr-str (:payload rec)) " failed"))})
                :else                 (runner/step-pass idx step))))))))

(defn- frame-router-state
  "Read the raw `{:queue :scheduled? ...}` router state for `frame-id`, or
  nil for an unknown / destroyed frame. There is no dedicated public
  accessor for router/queue state on the framework facade (`re-frame.core`)
  yet; Story already depends on `re-frame.frame` directly elsewhere
  (`re-frame.story.frames`), so this reaches one level further into the
  SAME already-depended-on ns rather than adding a new cross-boundary
  dependency. Tolerant — a throwing host (or an absent/destroyed frame)
  reads as nil."
  [frame-id]
  (try
    (some-> (frame/frame frame-id) :router deref)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- queue-empty?
  "True iff `frame-id`'s event queue has GENUINELY drained — no envelope
  left in the router's `:queue` AND no pending async drain tick
  (`:scheduled?`). A real read of the frame's router state (rf2-m0cge5
  finding 1) — the previous implementation was a hard-coded `true` stub.

  That stub's own comment justified it ONLY from the `[:dispatch …]` case:
  under `settled-boundary` a preceding dispatch step already ran the
  router to a fixed point, so the queue IS drained by the time a
  following `[:wait-until [:queue-empty]]` runs. But this step can ALSO
  follow a `:click` / `:type` / `:focus` step — `exec-click!` / `exec-type!`
  / `exec-focus!` (above) fire a synthetic DOM event directly and never
  call `boundary/dispatch-and-settle!`. A handler the synthetic event
  triggers may issue an ASYNC `[:dispatch …]` that has not yet drained —
  the router schedules an async drain via `interop/next-tick`, genuinely
  deferred, never inline (`re-frame.router/ensure-drain-scheduled!`). A
  hard-coded `true` silently reported that pending dispatch as settled, so
  a following `:assert-*` step could read app-db BEFORE it landed — the
  exact dispatch-vs-settle race `[:wait-until [:queue-empty]]` exists to
  catch. Per `exec-wait-until!`'s one-shot (non-polling) headless contract,
  an unmet predicate now correctly FAILS readably instead of passing
  vacuously.

  Tolerant — an unregistered / destroyed frame reads as drained (nothing
  left to wait for)."
  [frame-id]
  (let [state (frame-router-state frame-id)]
    (or (nil? state)
        (and (empty? (:queue state))
             (not (:scheduled? state))))))

(defn- wait-until-satisfied?
  "Evaluate a decomposed `:wait-until` predicate against `frame-id`'s
  current state. Pure-ish — reads the frame snapshot, no dispatch.
  Returns a boolean."
  [frame-id {:keys [kind path mode expected pred-ref pred-fn?]}]
  (case kind
    :db          (let [db     (read-frame-db frame-id)
                       actual (get-in db path)]
                   (case mode
                     :equals (= expected actual)
                     :pred   (let [f (if pred-fn? pred-ref (pred/resolve-sym-pred pred-ref))]
                               (boolean (and f (try (f actual)
                                                    (catch #?(:clj Throwable :cljs :default) _ false)))))
                     false))
    :queue-empty (queue-empty? frame-id)
    false))

(defn- exec-wait-until!
  "Execute a `[:wait-until predicate-spec]` step — the DETERMINISTIC
  settle-on-condition. In headless the preceding
  `[:dispatch …]` already drained to a fixed point, so the predicate is
  checked once synchronously: satisfied → step-skip (advance);
  unsatisfied → step-fail TIMING OUT READABLY with the unmet
  predicate-spec, NEVER a silent pass. (A richer DOM/browser runner that
  needs to await an async flush re-checks under a bounded poll; that
  poll is the adapter caller's concern — the headless contract is the
  one-shot deterministic check this fn implements.)"
  [frame-id idx step]
  (let [pspec   (runner/step-wait-until step)]
    (if (wait-until-satisfied? frame-id pspec)
      (runner/step-skip idx step)
      (runner/step-fail idx step
                        {:cannot-run? false
                         :expected (nth step 1)
                         :message  (str "wait-until " (pr-str (nth step 1))
                                        " never became true (deterministic "
                                        "queue/state predicate did not hold "
                                        "after the preceding dispatch settled)")}))))

(defn exec-step!
  "Execute ONE step against `frame-id`. Returns a step-result record
  (per `runner/step-pass` / `step-fail` / `step-skip` / `step-exception`).
  Pure-shape return — the run-state mutation is the caller's job.

  `:wait` is special-cased OUT of this fn — it requires an async
  yield (`setTimeout` / `Thread/sleep`) the driver schedules around.

  The runtime consumes the folded plan: every script is folded at
  resolution time (`runner-events/variant-plays` /
  `play/variant-play-steps`), so a shipping `:assert-db` / `:assert-dom`
  step has already become the canonical `[:assert assertion-atom]`
  checkpoint by the time it reaches here. A raw `:assert-db` / `:assert-dom`
  (a hand-built step that bypassed resolution) is folded inline as a
  belt-and-braces guard so there is ONE in-script assertion executor and
  ONE assertion-record vocabulary — never a synthetic `:rf.assert/db` /
  `:rf.assert/dom` rail."
  [frame-id idx step]
  (let [stype (runner/step-type step)]
    (case stype
      :dispatch       (exec-dispatch!      frame-id idx step)
      :dispatch-sync  (exec-dispatch-sync! frame-id idx step)
      :assert         (exec-assert!        frame-id idx step)
      ;; A raw shipping assertion step that escaped folding — fold it inline
      ;; to the canonical checkpoint and run the ONE assert executor.
      (:assert-db
       :assert-dom)   (exec-assert! frame-id idx (assertions/fold-assert-step step))
      :wait-until     (exec-wait-until!    frame-id idx step)
      :click          (exec-click!         frame-id idx step)
      :type           (exec-type!          frame-id idx step)
      :focus          (exec-focus!         frame-id idx step)
      :wait           (runner/step-skip idx step)   ; driver handles the actual sleep
      (runner/unknown-step idx step))))

;; ---- terminal assertions ------------------------------------------------
;;
;; A variant's terminal `:assertions` are the handler-backed "check the
;; FINAL settled state" surface (spec/017 §Inline script assertions vs
;; terminal assertions): each is the SAME assertion atom an in-script
;; `[:assert …]` checkpoint wraps, but evaluated ONCE after the script
;; phase has settled rather than at a mid-script point. They AUTO-RUN —
;; they contribute `:pass` / `:fail` verdicts recorded as assertion-records
;; on `:rf.story/assertions`, exactly like an in-script checkpoint.
;;
;; This reuses the ONE in-script executor (`exec-assert!`) rather than a
;; parallel evaluator: each terminal atom is wrapped in its `[:assert …]`
;; checkpoint shape and run through `exec-assert!`, which routes by atom
;; family precisely as the mid-script position does —
;;
;;   - handler-backed atoms (`:rf.assert/path-equals` / `path-matches` /
;;     `sub-equals` / `dispatched?` / `state-is` / `no-warnings` /
;;     `effect-emitted`) are DISPATCHED through `settled-boundary`; the
;;     reg-event handler records the canonical record on the slot;
;;   - DOM-family atoms are evaluated by the DOM executor;
;;   - tape-evaluated atoms (`:rf.assert/schema-error`, the causal / cascade
;;     family, the browser-tier oracle family) record a no-op step-skip and
;;     are NEVER dispatched — the result boundary owns their verdict against
;;     the epoch tape. So this path does NOT double-process the schema /
;;     causal kinds the plan collector already feeds to `result/run-result`'s
;;     tape matchers (the critical guard — the `exec-assert!`
;;     `tape-evaluated-assertion?` branch is the single source of truth for
;;     that split).

(defn run-terminal-assertions!
  "Evaluate `frame-id`'s terminal handler-backed `:assertions` against the
  FINAL settled state, AFTER the script phase. `atoms` is the
  plan's `[:expect :assertions]` vector — the bare assertion atoms
  (`[:rf.assert/id & args]`), the same atoms an in-script `[:assert …]`
  checkpoint wraps.

  Each atom is wrapped in its canonical `[:assert atom]` checkpoint shape
  and run through the ONE in-script executor (`exec-assert!`), so the
  verdict lands on `:rf.story/assertions` via the SAME recording path the
  mid-script checkpoints use — no parallel evaluator, no second record
  shape. The per-atom step-results are discarded (terminal assertions are
  not a runner step stream; their verdict is the slot record
  `record-result-map` / `result/run-result` folds into the unified status).

  The tape-evaluated families (schema-error / causal / browser-tier) carry
  no reg-event handler; `exec-assert!` records a no-op step-skip for them
  and never dispatches — the result boundary already evaluates them against
  the epoch tape from the plan, so they are NOT double-processed here.

  Idempotent w.r.t. an empty / nil `atoms` (no-op). Production callers
  (Story disabled) no-op."
  [frame-id atoms]
  (when (and config/enabled? (seq atoms))
    (doseq [[idx atom-v] (map-indexed vector atoms)]
      (exec-assert! frame-id idx [:assert atom-v])))
  nil)

;; ---- single-step driver -------------------------------------------------
;;
;; The play step-debugger (`re-frame.story.ui.test-mode.stepper-state`)
;; drives every rich-DSL step type via `run-step!`, which bases the stepper
;; on the SAME executor the canvas auto-run path uses — so every step type
;; (`:dispatch` / `:dispatch-sync` / `:wait` / `:click` / `:type` /
;; `:assert-db` / `:assert-dom`) runs in the debugger exactly as it does
;; live, with the right cursor/total and assert outcomes.

(defn run-step!
  "Execute ONE coerced rich-DSL `step` (at index `idx`) against
  `frame-id`, mirror assertion-class outcomes into the
  `:rf.story/assertions` slot, emit the per-step trace event, and return
  the step-result record. Synchronous on both runtimes (`:wait` records
  a step-skip rather than blocking — the interactive stepper does not
  sleep).

  Public so the step-debugger substrate (`play/step-once!`) can drive a
  full rich-DSL step without re-implementing the executor. Reached from
  `play.cljc` via the `:run-play-step` late-bind hook to avoid the
  play ↔ runner-events require cycle.

  Always records its settle boundary under play-key nil — the stepper
  (`play/variant-play-steps`) only ever walks the DEFAULT play, never a
  named `:plays` entry."
  [frame-id idx step]
  (record-settle-boundary! frame-id nil step)
  (let [result (try
                 (exec-step! frame-id idx step)
                 (catch #?(:clj Throwable :cljs :default) e
                   (runner/step-exception idx step
                                          #?(:clj  (.getMessage ^Throwable e)
                                             :cljs (str e)))))]
    ;; `exec-step!` (via `exec-assert!`) already wrote the canonical
    ;; assertion record onto `:rf.story/assertions`; no synthetic slot
    ;; mirror here.
    (emit-trace! frame-id nil idx step result)
    result))

;; ---- async scheduler -----------------------------------------------------

(defn- schedule!
  "Run `f` after `ms` milliseconds. CLJS → `js/setTimeout`; JVM →
  block the calling thread via `Thread/sleep` then invoke. Returns a
  cancellable handle on CLJS, nil on JVM."
  [ms f]
  #?(:cljs (js/setTimeout f ms)
     :clj  (do (when (pos? ms) (Thread/sleep ^long ms))
               (f)
               nil)))

;; ---- assertion-slot recording -------------------------------------------
;;
;; ONE assertion-record vocabulary. The runtime consumes the folded plan
;; (`variant-plays` / `variant-play-steps` fold every script), so every
;; in-script assertion arrives as the canonical `[:assert assertion-atom]`
;; checkpoint, and `exec-assert!` is the SOLE recorder:
;;
;;   - a non-DOM `:rf.assert/*` atom dispatches its reg-event handler,
;;     which writes the canonical record onto `:rf.story/assertions`;
;;   - a DOM-family atom is evaluated by `exec-assert-dom-atom!`, which
;;     writes a canonical `:rf.assert/dom-*` record.
;;
;; There is no synthetic `:rf.assert/db` / `:rf.assert/dom` slot-mirror
;; bridge: the folded checkpoint's OWN record IS the slot record, so
;; mirroring the step-result on top would double-count. `record-result!`
;; therefore only updates the run-state + emits the trace event; the slot
;; write already happened inside `exec-assert!`. This is what keeps
;; run-state and the `:rf.story/assertions` slot deriving from the ONE
;; canonical record — closing the "false GREEN" hazard.

(defn- record-result!
  "Append `result` to the run-state for `frame-id` and emit the per-step
  trace event. The `:rf.story/assertions` slot write already happened
  inside `exec-assert!` (the canonical folded-atom record), so this does
  not mirror a synthetic record on top."
  [frame-id play-key name idx step result]
  (update-state! frame-id play-key runner/record-step-result result)
  (emit-trace! frame-id name idx step result)
  nil)

(defn- finish!
  "Transition the run-state to `:pass` / `:fail` and resolve `done-cb`
  with the final state."
  [frame-id play-key done-cb]
  (update-state! frame-id play-key runner/finish (now-ms))
  (when done-cb
    (try (done-cb (current-state-for-play frame-id play-key))
         (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- settle-abort!
  "Settle `done-cb` for a run-loop that ABORTED before finishing — the frame
  was torn down mid-run (run-state vanished) or a newer `run!` took over the
  state slot (token mismatch). Every exit path of the run loop MUST settle
  the awaiting continuation, otherwise the play-promise (and the outer
  `run-variant` promise chained off it) hangs forever. The
  abort is reachable on CLJS, where an async `:wait` yield gives a hot-reload
  reset / concurrent `run!` / teardown a window to mutate the shared
  run-state between steps.

  Unlike `finish!`, this does NOT call `update-state!` / `runner/finish` — an
  aborted loop must NOT clobber the shared run-state slot, which by this point
  may belong to the NEWER run that took over (token mismatch) or be gone (frame
  torn down). It resolves the awaiting `done-cb` with the last-known state
  (`current-state-for-play`, possibly nil) so the continuation advances and the
  promise settles. The continuation only chains the next play / builds the
  result from the frame's accumulated assertions; it does not re-read this
  state for correctness."
  [frame-id play-key done-cb]
  (when done-cb
    (try (done-cb (current-state-for-play frame-id play-key))
         (catch #?(:clj Throwable :cljs :default) _ nil)))
  nil)

(defn- run-loop!
  "Iterate over the script, running each step. `:wait` steps yield
  to the scheduler and resume from the wait time onwards.

  `done-cb` is invoked with the final run-state once the loop ends.

  Each call to `run!` stamps a unique
  `:run-token` on the state map. The loop carries the token it started
  with and aborts if a fresh `run!` has overwritten the state with a
  newer token — that way a concurrent `runner-events/run!`
  (e.g. selection-watcher fires while runtime's `run-phase-4!` is
  mid-script) does not result in TWO loops walking the same shared
  state and double-dispatching events.

  Sync-class steps (`:dispatch-sync`, `:assert-db`, `:assert-dom`)
  recur synchronously; async-class steps (`:dispatch`, `:click`,
  `:type`, `:wait`) yield one tick so the queued effects drain before
  the next step runs. A blanket setTimeout-0 between every step would
  reintroduce the re-mount race the async-class split avoids — see
  `runner/async-yield?`."
  [frame-id play-key token done-cb]
  (let [state (current-state-for-play frame-id play-key)]
    (cond
      ;; abort if state has gone missing (frame torn down mid-run).
      ;; Still settle `done-cb` so the awaiting continuation advances and
      ;; the play-promise (and the outer `run-variant` promise) resolves
      ;; rather than hanging forever. We do NOT `finish!` here — the
      ;; run-state slot is gone, so there is nothing to transition; we only
      ;; release the continuation.
      (nil? state)
      (settle-abort! frame-id play-key done-cb)

      ;; abort if a newer run! has taken over the state slot — the
      ;; newer loop owns continuation now, so the stale loop bails
      ;; rather than racing it. The stale loop must STILL settle ITS OWN
      ;; `done-cb` (the continuation/promise for THIS run) so it does not
      ;; strand the chain — but via `settle-abort!` (no `update-state!`),
      ;; so it never clobbers the newer run's run-state slot.
      (and token (some? (:run-token state)) (not= token (:run-token state)))
      (settle-abort! frame-id play-key done-cb)

      (runner/done? state)
      (finish! frame-id play-key done-cb)

      :else
      (let [idx  (:step-idx state)
            step (runner/current-step state)
            nm   (:name state)]
        (cond
          (= :wait (runner/step-type step))
          (let [ms (runner/step-wait-ms step)]
            (record-result! frame-id play-key nm idx step (runner/step-skip idx step))
            (schedule! (or ms 0) #(run-loop! frame-id play-key token done-cb)))

          :else
          (let [_      (record-settle-boundary! frame-id play-key step)
                result (try
                         (exec-step! frame-id idx step)
                         (catch #?(:clj Throwable :cljs :default) e
                           (runner/step-exception idx step
                                                  #?(:clj  (.getMessage ^Throwable e)
                                                     :cljs (str e)))))
                yield? (runner/async-yield? step)]
            (record-result! frame-id play-key nm idx step result)
            ;; Sync-class step → recur synchronously so the next step
            ;; observes the just-committed effects atomically (the
            ;; `doseq`-style sequential semantics).
            ;;
            ;; Async-class step → yield one tick on CLJS so the
            ;; queued router work / synthetic DOM event handlers drain
            ;; before the next step runs.
            #?(:cljs (if yield?
                       (js/setTimeout #(run-loop! frame-id play-key token done-cb) 0)
                       (recur frame-id play-key token done-cb))
               :clj  (recur frame-id play-key token done-cb))))))))

;; ---- public driver -------------------------------------------------------

(defn run!
  "Drive the play script for `variant-id`. Resets the per-variant
  run-state, walks every step in order, records results, and resolves
  `done-cb` with the terminal run-state.

  Returns the initial run-state (NOT a promise) so synchronous callers
  can immediately observe `:status :running` + `:total`. CLJS callers
  that need a promise can wrap with `js/Promise.` themselves;
  `done-cb` is the canonical completion hook.

  Idempotent w.r.t. concurrent runs — calling `run!` while a previous
  run is in flight cancels the previous run's `done-cb` (the new one
  takes over the run-state slot).

  Arities:
  - `[variant-id]`                — run the default play (the first
                                    play of `:plays`, or the single
                                    `:play-script`).
  - `[variant-id done-cb]`        — as above + completion callback.
  - `[variant-id play-key spec done-cb]` — multi-play form:
                                    drive a specific play. `play-key`
                                    is the play's `:name` string (or
                                    nil for the single-script case);
                                    callers handing a hand-built `spec`
                                    pass its `:name` as `play-key`.
  - `[variant-id play-key spec done-cb opts]` — as above + an options
                                    map. `{:clear-boundaries? false}`
                                    SUPPRESSES the per-run settle-boundary
                                    reset, for a caller SEQUENCING several
                                    plays against one frame:
                                    the sequencer clears ONCE up front and
                                    each subsequent play APPENDS its
                                    absolute boundaries, so the
                                    concatenated-script attribution stays
                                    positionally aligned. Defaults to
                                    `{:clear-boundaries? true}` — the
                                    standalone single-play contract."
  ([variant-id]
   (run! variant-id nil nil nil nil))
  ([variant-id done-cb]
   ;; Two-arity: variant-id + done-cb. Picks the default play.
   (run! variant-id nil nil done-cb nil))
  ([variant-id play-key spec done-cb]
   (run! variant-id play-key spec done-cb nil))
  ([variant-id play-key spec done-cb {:keys [clear-boundaries?]
                                      :or   {clear-boundaries? true}}]
   (let [spec  (or spec
                   (resolve-play variant-id play-key)
                   ;; Fall back to the single-script path so the default
                   ;; play of a `:play-script` variant Just Works.
                   (variant-play-script variant-id))
         pk    (or play-key (:name spec))
         init  (runner/initial-state spec)
         ;; Stamp a fresh token on every run. The loop reads it back from
         ;; the state map; if a concurrent run! replaces the state mid-loop,
         ;; the stale loop sees a mismatched token and aborts before
         ;; re-dispatching.
         token   #?(:clj (java.util.UUID/randomUUID)
                    :cljs (.toString (js/Math.random)))
         started (-> (runner/start init (now-ms))
                     (assoc :run-token token))]
     ;; Reset the per-dispatch-step settle boundaries here, in the SAME
     ;; public entry that resets run-state and then writes boundaries via
     ;; `run-loop!`/`record-settle-boundary!`. Owning the reset alongside the
     ;; write keeps the attribution windowed onto THIS run's epoch tape. The
     ;; leading-nil-span semantics hold: the boundaries snapshot the last-
     ;; committed `:epoch-id`, so any setup epochs already on the tape carry
     ;; an id at or before the first boundary (in the orchestrator path
     ;; setup runs in phase-2, before phase-4 drives `run!`).
     ;;
     ;; A MULTI-PLAY sequencer (`run-plays-sequentially!`, the orchestrator
     ;; `runtime/run-phase-4!`) passes `:clear-boundaries? false` for the
     ;; 2nd…Nth play so the boundaries ACCUMULATE across the whole auto-run
     ;; sequence. The narrative spans the CONCATENATED script
     ;; (`(mapcat :script auto-plays)`), and `:epoch-id` only ever increases
     ;; across the run (rf2-96qsjr — true regardless of ring eviction), so
     ;; each play's boundaries tail the previous play's — keeping the
     ;; dispatch-order zip in `stamp-tape` aligned. Clearing per-play would
     ;; drop every earlier play's boundaries, leaving only the LAST play's
     ;; to be mis-zipped against the concatenated script's leading dispatch
     ;; steps — a false-green evidence-provenance failure.
     ;;
     ;; rf2-m0cge5 finding 2: clears ONLY this run's OWN `[variant-id pk]`
     ;; slot, never the whole frame — a concurrent run! for a DIFFERENT
     ;; play-key on this SAME frame (not blocked by the per-`[frame
     ;; play-key]` run-token guard above) writes to its OWN slot and can
     ;; no longer wipe an in-flight multi-play sequence's accumulator.
     (when clear-boundaries?
       (clear-step-boundaries! variant-id pk))
     (set-state! variant-id pk started)
     (set-active-play! variant-id pk)
     (run-loop! variant-id pk token done-cb)
     started)))

(defn re-run!
  "Re-run the play script for `variant-id`. Convenience wrapper around
  `run!` — distinct fn name so the toolbar's `[Re-run]` button has a
  one-call API.

  With no explicit `play-key`, re-runs the currently active play (set by
  the dropdown). For single-script variants the active play is nil, so
  this re-runs the single script."
  ([variant-id]
   (re-run! variant-id nil))
  ([variant-id done-cb]
   (let [pk (active-play-key variant-id)]
     (run! variant-id pk nil done-cb))))

(defn run-play!
  "Run the play identified by `play-key` (a play's `:name`) for
  `variant-id` (multi-play). Passing nil picks the default
  play (first entry for multi-play, the single script for
  `:play-script`)."
  ([variant-id play-key]
   (run-play! variant-id play-key nil))
  ([variant-id play-key done-cb]
   (run! variant-id play-key nil done-cb)))

(defn select-play!
  "Set `variant-id`'s active play to `play-key` WITHOUT running it. Used
  by the toolbar dropdown when the user picks a play but hasn't pressed
  Re-run yet."
  [variant-id play-key]
  (set-active-play! variant-id play-key))

(declare run-plays-sequentially!)

(defn auto-run!
  "Run the play script if `:auto-run?` is true. Called from the shell
  after the variant mounts. No-op when the variant has no
  `:play-script` / `:plays` slot or no play declares `:auto-run? true`.

  Multi-play: every play with `:auto-run? true` is run in ORDER
  (sequentially) so they don't race against the same frame. By
  the per-play default (first play true, rest false) only the first
  play auto-runs on mount; subsequent plays opt in explicitly."
  ([variant-id]
   (auto-run! variant-id nil))
  ([variant-id done-cb]
   (when config/enabled?
     (let [plays      (variant-plays variant-id)
           auto-plays (runner/auto-runnable-plays plays)]
       (cond
         (empty? auto-plays)
         nil

         ;; Single auto-run play — direct fire so the single-script
         ;; callers keep their run shape.
         (= 1 (count auto-plays))
         (let [spec (first auto-plays)]
           (run! variant-id (:name spec) spec done-cb))

         ;; Multiple auto-run plays — sequence them so a later play
         ;; doesn't trample the frame mid-run.
         :else
         (run-plays-sequentially! variant-id auto-plays done-cb))))))

;; ---- run-all (sequential) -----------------------------------------------

(defn- run-plays-sequentially!
  "Internal: run `plays` against `variant-id` one after another.
  Resolves `done-cb` with a vector of terminal states once every play
  has finished (or the loop is interrupted by a missing frame).

  Clears the per-dispatch-step settle boundaries for EVERY play-key this
  sequence is about to drive, ONCE up front, then drives each play with
  `:clear-boundaries? false` so each play's OWN `[variant-id play-key]`
  slot ACCUMULATES across the sequence (rf2-m0cge5 finding 2: boundaries
  are keyed by the pair, not by `variant-id` alone, so this clear can never
  wipe a DIFFERENT, concurrently-running play-key's slot on this same
  frame — see `step-boundaries`'s docstring). The evidence narrative spans
  the CONCATENATED play scripts, and `:epoch-id` only ever increases across
  the run (rf2-96qsjr — independent of ring eviction), so each play's
  boundaries tail the previous play's for `stamp-tape`'s dispatch-order
  zip to stay aligned. Letting each `run!` clear would leave only the
  last play's boundaries, mis-attributing later-play effects to
  earlier-play steps."
  [variant-id plays done-cb]
  (let [acc (atom [])]
    (doseq [pk (map :name plays)]
      (clear-step-boundaries! variant-id pk))
    (letfn [(step! [remaining]
              (if (empty? remaining)
                (when done-cb
                  (try (done-cb @acc)
                       (catch #?(:clj Throwable :cljs :default) _ nil)))
                (let [spec (first remaining)
                      pk   (:name spec)]
                  (run! variant-id pk spec
                        (fn [final]
                          (swap! acc conj final)
                          (step! (rest remaining)))
                        {:clear-boundaries? false}))))]
      (step! plays))))

(defn run-all-plays!
  "Run every play declared on `variant-id` in order, sequentially
  (multi-play). Calls `done-cb` with a vector of per-play
  terminal states once every play has completed. Returns nil.

  No-op when the variant carries no plays."
  ([variant-id]
   (run-all-plays! variant-id nil))
  ([variant-id done-cb]
   (let [plays (variant-plays variant-id)]
     (when (seq plays)
       (run-plays-sequentially! variant-id (vec plays) done-cb)))))

;; ---- step-debugger seam -------------------------------------------------
;;
;; `play.cljc` owns the step-debugger substrate but cannot `:require`
;; this ns (the cycle: runner-events → play). It fetches `run-step!` via
;; the `:run-play-step` late-bind hook. Registered at ns load so it is
;; available as soon as the play module drives a stepped run.

(late-bind/set-fn! :run-play-step run-step!)

;; The stepper appends a settle boundary per `run-step!`, so a fresh
;; stepping session (`play/begin-stepper!`) must reset the frame's
;; boundaries first — the stepper analogue of `run!`'s reset. Exposed via the
;; same late-bind seam since `play.cljc` cannot `:require` this ns.

(late-bind/set-fn! :clear-step-boundaries clear-step-boundaries!)
