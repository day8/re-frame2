(ns day8.re-frame2-xray.panels.epoch.machine-epochs-harness-cljs-test
  "ASSERTION-BACKED render-regression harness for the machine-epochs deck
  (rf2-g27vv). The COMPLEMENT to `hard-machine-fidelity-cljs-test` (rf2-k08ay,
  which this SUBSUMES + extends deck-wide): where that pins the HVAC HARD
  machine's render fidelity, THIS pins the FULL machine × Xray-cascade-render
  SURFACE matrix the redesigned deck drives — every rung backed by an
  assertion so re-driving the deck is a real regression test of Xray
  rendering, not just a visual deck.

  ## What it drives + asserts

  It registers the IDENTICAL machine specs the testbed mounts (via
  `machine-epochs.machines/register-all!` — the SAME values, no drift),
  drives each rung's event through the LIVE machine substrate
  (`dispatch-sync`), captures the trace stream Xray's ring buffer recorded,
  and feeds it through the rendering layers that FEED the devtools render:

    - `proj/machine-cascade-rows`        — the Epoch panel's per-epoch machine
      cascade (the exit/action/entry/no-op/timer rows; canonical sort;
      no-op-transition suppression).
    - the structured `:cascade` off the transition row (`proj/cascade-regions`
      / `cascade-microsteps` / `cascade-step-count`) — the LCA + microstep
      walk.
    - `mih/project-focused-event-transitions` — the inspector's focused-event
      view-model.
    - `diff/project` — the snapshot diff (member-level set diff for `:tags`).

  Per rf2-k08ay + the rf2-hhkbb drive-through finding, EACH rung asserts THREE
  layers:
    (a) TRACE shape — the machine cascade kinds + order, read off the
        structured `:cascade` (its `:kind` / `:state` / `:action` steps in
        execution order + the `:microsteps`).
    (b) STRUCTURED outcome — the Xray-surface analogue of the rf2-hhkbb
        cascade-summary `:outcome` / `:no-op?`: a thrown `:*` action shows
        `:threw? true` on its cascade row (catching hhkbb); the unhandled rung
        shows exactly ONE `:no-op` row + zero transition rows.
    (c) RENDERED rows — the Epoch-panel projection emits the expected row
        kinds / verbs.

  Causa/Story-as-CLJS-unit-test (feedback_causa_story_cljs_unit_tests_not_playwright),
  NOT Playwright. The render facts are pinned against REALITY: drive the
  substrate, read what the projection produces."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [day8.re-frame2-xray.diff.engine :as diff]
            [day8.re-frame2-xray.panels.epoch.format :as fmt]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.panels.machine-inspector-helpers :as mih]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; The SHARED machine specs the deck mounts — the harness drives
            ;; the IDENTICAL values (single source of truth, rf2-g27vv).
            [machine-epochs.machines :as machines]))

;; ============================================================================
;; fixture + drivers
;; ============================================================================

(defn- xray-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn xray-init!}))

(defn- setup!
  "Register the Xray handlers + trace collector, register every (non-throwing)
  deck machine via the shared specs, and START each to its initial state.
  Clears the trace buffer so per-rung captures contain only that macrostep."
  []
  (registry/register-xray-handlers!)
  (preload/register-trace-collector!)
  (machines/register-all!)
  (doseq [id [:door/main :traffic/light :quiz/scorer :brew/machine
              :session/flow :hvac/controller :media/deep :media/shallow]]
    (rf/dispatch-sync [id [:rf.machine/start]])))

(defn- snapshot [machine-id]
  (get-in (rf/app-db-value :rf/default)
          [:rf/runtime :machines :snapshots machine-id]))

(defn- drive!
  "Dispatch one event into `machine-id` and return the trace stream it
  produced as an epoch-record-shaped map. Resets the trace buffer first so
  the captured cascade is exactly this macrostep."
  [machine-id event-v]
  (trace-collector/reset-for-test!)
  (rf/dispatch-sync [machine-id event-v])
  {:event-id      machine-id
   :trigger-event [machine-id event-v]
   :trace-events  (vec (trace-collector/buffer-for-test))})

(defn- cascade [record] (proj/machine-cascade-rows (:trace-events record)))
(defn- rows-of-kind [rows kind] (filterv #(= kind (:kind %)) rows))
(defn- structured-of [record]
  (-> (cascade record) (rows-of-kind :transition) first :cascade))

;; The structured `:cascade` is the order-oracle: each step carries
;; `:kind` (`:exit` / `:action` / `:entry`), `:state`, `:region`, `:action`
;; (nil for an action-free boundary) in EXECUTION order (exit-deepest-first →
;; action @ LCA → entry-shallowest-first). `region-steps` returns the
;; structural steps of one `:region` (nil for flat/compound) so a test can
;; assert the cascade ORDER directly off the Xray-surface projection.
(defn- region-steps [record region]
  (->> (proj/cascade-regions (structured-of record))
       (some #(when (= region (:region %)) (:steps %)))
       vec))

;; ============================================================================
;; DOOR (FLAT) — plain · entry/exit · transition-action · guards · internal ·
;;               fx · unhandled-no-op · root-:on RESOLUTION (gap 6)
;; ============================================================================

(deftest door-plain-transition-renders-exit-transition-entry
  (testing "rung #2 — plain :locked ──► :closed renders an exit / TRANSITION /
            entry cascade with NO guard, NO action rows, and exactly ONE
            transition row (no no-op)."
    (setup!)
    (let [record (drive! :door/main [:door/insert-coin])
          rows   (cascade record)]
      (is (= 1 (count (rows-of-kind rows :transition)))
          "(c) one transition row")
      (is (empty? (rows-of-kind rows :no-op)) "(b) not a no-op")
      (is (empty? (rows-of-kind rows :guard)) "(a) no guard fired")
      (let [tx (first (rows-of-kind rows :transition))]
        (is (= :locked (:from-state tx)))
        (is (= :closed (:to-state tx))))
      (is (= :closed (:state (snapshot :door/main)))))))

(deftest door-entry-exit-actions-render-with-data-delta
  (testing "rung #3 — :closed ──► :open runs :closed's :exit (:clear-hold) +
            :open's :entry (:count-open). (a) the structured cascade records
            the exit→action→entry order; (c) the cascade carries an :exit + an
            :entry action row; the entry action's :data-delta bumps
            :opened-count."
    (setup!)
    (drive! :door/main [:door/insert-coin])            ; :locked → :closed
    (let [record (drive! :door/main [:door/push])       ; :closed → :open
          rows   (cascade record)
          phases (mapv :phase (rows-of-kind rows :action))
          steps  (region-steps record nil)]
      (is (some #{:exit} phases)  "(c) an exit action row renders")
      (is (some #{:entry} phases) "(c) an entry action row renders")
      ;; (a) the structured cascade order-oracle: the :closed exit (:clear-hold)
      ;; fires before the :open entry (:count-open).
      (is (= [:exit :entry] (mapv :kind steps))
          "(a) the structured cascade records the exit→entry order")
      (is (= [:clear-hold :count-open] (mapv :action steps))
          "(a) the exit action runs before the entry action")
      (is (= 1 (get-in (snapshot :door/main) [:data :opened-count]))
          "(c) the :entry action bumped :opened-count"))))

(deftest door-guard-allowed-then-blocked
  (testing "rungs #4/#5 — guard ALLOWED completes, guard BLOCKED stays put.
            (a) the guard row's :outcome is :pass then :fail; (b) the blocked
            attempt is a no-op (one :no-op row, zero transition rows); (c) the
            state advances only when the guard passes."
    (setup!)
    (drive! :door/main [:door/insert-coin])
    (drive! :door/main [:door/push])                    ; → :open
    ;; #4 — guard ALLOWED (:held-open? false).
    (let [rows (cascade (drive! :door/main [:door/close]))]
      (is (= :pass (:outcome (first (rows-of-kind rows :guard))))
          "(a) :may-close? passes")
      (is (= 1 (count (rows-of-kind rows :transition))) "(c) transition fires"))
    (is (= :closed (:state (snapshot :door/main))))
    ;; #5 — re-open, arm the hold (internal), then attempt the BLOCKED close.
    (drive! :door/main [:door/push])                    ; → :open
    (drive! :door/main [:door/hold])                    ; arm :held-open?
    (let [rows (cascade (drive! :door/main [:door/close]))]
      (is (= :fail (:outcome (first (rows-of-kind rows :guard))))
          "(a) :may-close? fails when held-open?")
      (is (= 1 (count (rows-of-kind rows :no-op)))
          "(b) the blocked guard is a no-op — one [NO OP] row")
      (is (empty? (rows-of-kind rows :transition))
          "(c) blocked → NO transition row (suppressed beside the no-op)"))
    (is (= :open (:state (snapshot :door/main)))
        "(c) the door STAYS :open — the blocked close did not advance")))

(deftest door-internal-transition-renders-action-only
  (testing "rung #5 sub — :door/hold is an INTERNAL transition (no :target):
            it writes :data without changing state, so (c) NO exit/entry/no-op
            rows, just the action's effect; the state stays :open."
    (setup!)
    (drive! :door/main [:door/insert-coin])
    (drive! :door/main [:door/push])                    ; → :open
    (let [rows (cascade (drive! :door/main [:door/hold]))]
      (is (empty? (rows-of-kind rows :no-op))
          "(b) an internal transition is a REAL transition, not a no-op")
      (is (empty? (filterv #(and (= :action (:kind %)) (= :exit (:phase %))) rows))
          "(c) internal transition fires NO exit row"))
    (is (= :open (:state (snapshot :door/main)))
        "(c) internal transition leaves the state at :open")
    (is (true? (get-in (snapshot :door/main) [:data :held-open?]))
        "(c) the internal action wrote :data")))

(deftest door-transition-with-effect-renders-fx
  (testing "rung #6 — :open ──► :alarming runs :enter-alarm whose action
            writes :data (the deck dispatches the downstream child via the
            deck event-fx). (c) the cascade carries the transition + the
            :enter-alarm action whose :data-write sets :alarm-fx?."
    (setup!)
    (drive! :door/main [:door/insert-coin])
    (drive! :door/main [:door/push])
    (let [rows (cascade (drive! :door/main [:door/trip]))]
      (is (= 1 (count (rows-of-kind rows :transition))))
      (is (some #(true? (get-in % [:data-write :alarm-fx?]))
                (rows-of-kind rows :action))
          "(a)(c) the :enter-alarm action ran (its :data-write set :alarm-fx?)"))
    (is (= :alarming (:state (snapshot :door/main))))
    (is (true? (get-in (snapshot :door/main) [:data :alarm-fx?]))
        "(c) the :enter-alarm action wrote :alarm-fx? into the snapshot")))

(deftest door-unhandled-event-renders-single-no-op-staying-in-state
  (testing "rung #7 — :door/insert-coin into :alarming matches no transition →
            a BENIGN unhandled no-op (settled coozg/e6q97/iu3no pipeline).
            (b) exactly ONE :no-op row, :no-op? true (zero transition rows);
            (c) the verb is the bare 'staying in :alarming' (single machine →
            no machine name)."
    (setup!)
    (drive! :door/main [:door/insert-coin])
    (drive! :door/main [:door/push])
    (drive! :door/main [:door/trip])                    ; → :alarming
    (let [record (drive! :door/main [:door/insert-coin])
          rows   (cascade record)
          no-ops (rows-of-kind rows :no-op)
          no-op  (first no-ops)]
      (is (= 1 (count no-ops)) "(b) exactly one no-op row (coozg: single signal)")
      (is (empty? (rows-of-kind rows :transition))
          "(b) NO transition row beside the no-op (e6q97 suppression)")
      (is (= :alarming (:state no-op)))
      (is (false? (:show-machine-name? no-op)) "(c) single machine → drop name")
      (let [verb (fmt/cascade-row-label no-op)]
        (is (string/starts-with? verb "staying in ") "(c) bare consequence verb")
        (is (not (string/includes? verb "received")) "(c) no event echo")))
    (is (= :alarming (:state (snapshot :door/main)))
        "(b) the door STAYS :alarming — a no-op does not move state")))

(deftest door-audit-resolves-via-root-on-fallthrough
  (testing "rung #8 (gap 6, RESOLUTION) — :alarming declares no :door/audit;
            the event falls through to the machine ROOT :on, which targets
            :locked. (a)(c) a real transition row attributes :alarming ──►
            :locked — the root-resolved transition, NOT a no-op."
    (setup!)
    (drive! :door/main [:door/insert-coin])
    (drive! :door/main [:door/push])
    (drive! :door/main [:door/trip])                    ; → :alarming
    (let [record (drive! :door/main [:door/audit])
          rows   (cascade record)
          tx     (first (rows-of-kind rows :transition))]
      (is (empty? (rows-of-kind rows :no-op))
          "(b) the root :on HANDLED it — NOT an unhandled no-op")
      (is (some? tx) "(c) a real transition row renders")
      (is (= :alarming (:from-state tx)))
      (is (= :locked (:to-state tx))
          "(a)(c) the cascade attributes the root-resolved transition to :locked"))
    (is (= :locked (:state (snapshot :door/main))))))

;; ============================================================================
;; TRAFFIC (PARALLEL) — regions · history · TAG-SET delta (gap 7)
;; ============================================================================

(deftest traffic-tick-broadcasts-to-both-regions
  (testing "rung #9 — ONE :traffic/tick broadcasts to BOTH regions in one
            macrostep. (c) one aggregate transition row whose :to-state is a
            region→state map showing both regions advanced; (a) the inspector
            projects the single macrostep transition with both regions."
    (setup!)
    (let [record    (drive! :traffic/light [:traffic/tick])
          rows      (cascade record)
          tx        (first (rows-of-kind rows :transition))
          inspector (mih/project-focused-event-transitions
                      (:trace-events record)
                      {:traffic/light machines/traffic-machine})]
      (is (= 1 (count (rows-of-kind rows :transition)))
          "(c) a parallel macrostep commits ONE aggregate transition row")
      (is (= {:vehicle :red :pedestrian :walk} (:from-state tx)))
      (is (= {:vehicle :green :pedestrian :dont-walk} (:to-state tx))
          "(c) both regions advanced in one event")
      (is (empty? (rows-of-kind rows :no-op)) "(b) a handled broadcast is not a no-op")
      (is (= 1 (count inspector))
          "(a) the inspector projects the macrostep's single transition record"))
    (is (= {:vehicle :green :pedestrian :dont-walk} (:state (snapshot :traffic/light))))))

(deftest traffic-tag-set-delta-renders-member-swap
  (testing "rung #10 (gap 7, rf2-l0us2) — a tick swaps the :tags SET members
            while a :traffic/shared member stays constant. The snapshot diff
            must surface per-MEMBER :added / :removed (the joining + leaving
            tags), NOT a whole-key set replacement; the shared member does NOT
            churn."
    (setup!)
    (let [before (proj/machine-logical-state (snapshot :traffic/light))
          _      (drive! :traffic/light [:traffic/tick])  ; red→green, walk→dont-walk
          after  (proj/machine-logical-state (snapshot :traffic/light))
          {:keys [path-ops]} (diff/project before after)
          added     (->> path-ops (filter (fn [[_ v]] (= :added (:op v)))) (map first))
          removed   (->> path-ops (filter (fn [[_ v]] (= :removed (:op v)))) (map first))
          member-of (fn [tag paths] (some (fn [p] (= tag (last p))) paths))]
      (is (member-of :traffic/vehicle-go added)
          "(c) the joining tag renders as a member-level :added (rf2-l0us2)")
      (is (member-of :traffic/vehicle-stop removed)
          "(c) the leaving tag renders as a member-level :removed")
      (is (not (member-of :traffic/shared added))
          "(c) the constant :traffic/shared member does NOT churn (no spurious add)")
      (is (not (member-of :traffic/shared removed))
          "(c) the constant :traffic/shared member does NOT churn (no spurious remove)"))))

(deftest multiple-machines-one-cascade-no-op-names-its-machine
  (testing "rung #11 (rf2-iu3no) — when >1 machine is in play in one cascade,
            a no-op row NAMES its machine (the single-machine case drops it).
            Capture an unhandled no-op on TWO machines in one trace window
            (door in :alarming + brew in :idle, both unhandled) and assert
            BOTH no-op rows carry :show-machine-name? true + their machine-id
            — the multi-machine 'in play' branch of the projection."
    (setup!)
    ;; get the door into :alarming first (so :door/insert-coin is unhandled).
    (rf/dispatch-sync [:door/main [:door/insert-coin]])
    (rf/dispatch-sync [:door/main [:door/push]])
    (rf/dispatch-sync [:door/main [:door/trip]])         ; → :alarming
    ;; capture two machines' unhandled no-ops in one window → 2 distinct
    ;; machine-ids in play, so each no-op row names its machine (rf2-iu3no).
    (trace-collector/reset-for-test!)
    (rf/dispatch-sync [:door/main [:door/insert-coin]])  ; door no-op in :alarming
    (rf/dispatch-sync [:brew/machine [:brew/abort]])      ; brew no-op in :idle
    (let [rows   (proj/machine-cascade-rows (vec (trace-collector/buffer-for-test)))
          no-ops (rows-of-kind rows :no-op)
          ids    (set (map :machine-id no-ops))]
      (is (= 2 (count no-ops)) "(b) both machines' unhandled events are no-ops")
      (is (= #{:door/main :brew/machine} ids) "(c) two distinct machines in play")
      (is (every? #(true? (:show-machine-name? %)) no-ops)
          "(c) >1 machine in play → EACH no-op row names its machine (rf2-iu3no)"))))

;; ============================================================================
;; QUIZ (MICROSTEP) — :always EVENTLESS microsteps (gap 1, THE biggest)
;; ============================================================================

(deftest quiz-answer-below-mark-settles-zero-microsteps
  (testing "rung #12 — an :answer below the pass mark bumps :score; the
            guarded :always does NOT fire. (a) the transition row's
            :microsteps is 0 / nil; (c) the quiz stays :asking.
            rf2-cdgva — at N=0 (the common case) the TRANSITION row renders
            NO `0 microsteps` summary (the prior pure-noise affordance)."
    (setup!)
    (let [record (drive! :quiz/scorer [:quiz/answer])
          tx     (first (rows-of-kind (cascade record) :transition))]
      (is (some? tx) "(c) the :answer action transition row renders")
      (is (contains? #{0 nil} (:microsteps tx))
          "(a) below the mark → ZERO microsteps (the :always guard failed)")
      (is (nil? (fmt/cascade-outcome-label tx))
          "(c) rf2-cdgva — N=0 renders NO '0 microsteps' summary (pure noise)")
      (is (= :asking (:state (snapshot :quiz/scorer))))
      (is (= 1 (get-in (snapshot :quiz/scorer) [:data :score]))))))

(deftest quiz-answer-to-pass-fires-always-microstep-cascade
  (testing "rung #13 (gap 1) — answers reach the pass mark and the guarded
            :always chain SETTLES :asking ──► :passed over N>0 microsteps.
            (a) the transition row's :microsteps > 0 AND the structured
            :cascade carries a :microstep step whose nested :steps explain the
            eventless transition (the :award action → :passed); (c) the quiz
            lands in :passed.
            rf2-cdgva — the TRANSITION row renders NO `N microstep(s)`
            outcome summary; the microstep is surfaced as its own cascade
            row instead, so no signal is lost by dropping the count."
    (setup!)
    (drive! :quiz/scorer [:quiz/answer])                ; score → 1
    (drive! :quiz/scorer [:quiz/answer])                ; score → 2
    ;; the THIRD answer reaches the mark (>=3) and the :always fires.
    (let [record     (drive! :quiz/scorer [:quiz/answer])
          rows       (cascade record)
          tx         (first (rows-of-kind rows :transition))
          structured (structured-of record)
          microsteps (proj/cascade-microsteps structured)
          microstep  (first microsteps)]
      ;; (a) the macrostep settled over at least one eventless microstep.
      (is (and (number? (:microsteps tx)) (pos? (:microsteps tx)))
          "(a) :always settled over N>0 microsteps (projection preserves the count)")
      ;; rf2-cdgva — the transition row NO LONGER renders an outcome summary;
      ;; the redundant `N microstep(s)` count is gone (the microstep below
      ;; carries the signal). The projection still preserves :microsteps as a
      ;; legacy pure-data slot; only the RENDERED summary is removed.
      (is (nil? (fmt/cascade-outcome-label tx))
          "(c) rf2-cdgva — the TRANSITION row renders NO 'N microstep(s)' summary")
      (is (seq microsteps)
          "(a/c) the microstep is surfaced as its own cascade step — the
                 signal the dropped count summarised (n9f4z/52u5n)")
      (is (= :asking (:from microstep)))
      (is (= :passed (:to microstep))
          "(a) the microstep transitions :asking → :passed")
      (is (some #(= :award (:action %)) (:steps microstep))
          "(a) the eventless transition's :award action is EXPLAINABLE inside the microstep")
      (is (pos? (proj/cascade-step-count structured))
          "(c) the structured step count includes the microstep's nested steps")
      ;; (c) the live machine settled.
      (is (= :passed (:state (snapshot :quiz/scorer))))
      (is (true? (get-in (snapshot :quiz/scorer) [:data :passed?]))
          "(c) the :always :award action ran"))))

;; ============================================================================
;; BREW (TIMER) — :after delayed transition + cancellation (gap 2)
;; ============================================================================

(deftest brew-after-timer-schedules-then-auto-fires
  (testing "rungs #14/#15 (gap 2) — entering :brewing SCHEDULES an :after
            timer; the synthetic :after-elapsed AUTO-FIRES it. (a) the
            scheduled trace fires on entry; (c) the timer firing transitions
            :brewing ──► :ready."
    (setup!)
    ;; #14 — start: enter :brewing, schedule the timer.
    (let [record (drive! :brew/machine [:brew/start])]
      (is (= :brewing (:state (snapshot :brew/machine))))
      (is (some #(= :rf.machine.timer/scheduled (:operation %)) (:trace-events record))
          "(a) entering :brewing scheduled the :after timer"))
    ;; #15 — let the timer elapse (synthetic, deterministic).
    (let [epoch  (get-in (snapshot :brew/machine) [:data :rf/after-epoch [:brewing]])
          record (drive! :brew/machine [:rf.machine.timer/after-elapsed 5000 epoch [:brewing]])
          tx     (first (rows-of-kind (cascade record) :transition))]
      (is (some? tx) "(c) the timer firing renders a transition row")
      (is (= :ready (:state (snapshot :brew/machine)))
          "(c) the :after timer transitioned :brewing → :ready"))))

(deftest brew-exit-before-fire-cancels-the-timer
  (testing "rung #16 (gap 2) — exiting :brewing via :brew/abort BEFORE the
            timer fires CANCELS the pending timer. (c) the cascade carries a
            :timer row with :reason :on-exit (the :cancelled chip's data); the
            machine returns to :idle."
    (setup!)
    (drive! :brew/machine [:brew/start])                ; enter :brewing (schedule)
    (let [record (drive! :brew/machine [:brew/abort])    ; exit before fire
          rows   (cascade record)
          timer  (first (rows-of-kind rows :timer))]
      (is (some? timer) "(c) a :timer cascade row renders for the cancellation")
      (is (= :on-exit (:reason timer))
          "(c) the cancellation :reason is :on-exit (exit beat the timer)")
      (is (= "cancelled (on-exit)" (fmt/cascade-outcome-label timer))
          "(c) the :cancelled chip renders the reason"))
    (is (= :idle (:state (snapshot :brew/machine))))))

;; ============================================================================
;; SESSION (LIFECYCLE) — spawn · child :final · :on-done · destroy (gaps 3,4,5)
;; ============================================================================

(deftest session-open-spawns-child-actor
  (testing "rung #17 (gap 4) — :idle ──► :authenticating SPAWNS the
            :session/login child. (a) the spawn fx fires (the
            :rf.machine.spawn/spawned trace); (c) the runtime spawn-registry
            slot binds the child's deterministic actor id."
    (setup!)
    (let [record (drive! :session/flow [:session/open])]
      (is (= :authenticating (:state (snapshot :session/flow))))
      (is (some #(= :rf.machine.spawn/spawned (:operation %)) (:trace-events record))
          "(a) the spawn fx emitted :rf.machine.spawn/spawned")
      (is (some? (get-in (rf/app-db-value :rf/default)
                         [:rf/runtime :machines :spawned :session/flow [:authenticating]]))
          "(c) the spawn-registry slot binds the child actor id"))))

(deftest session-child-final-fires-on-done-and-auto-destroys
  (testing "rungs #17→#18 (gaps 3,4,5) — driving the spawned child to its
            :final? state fires the parent's :on-done (reporting the token)
            and AUTO-DESTROYS the child. (a) the :rf.machine/destroyed trace
            fires with :reason :rf.machine/finished; (c) the parent's
            :data :session-token carries the child's :output-key; the child's
            snapshot is cleared."
    (setup!)
    (drive! :session/flow [:session/open])              ; spawn child
    (let [child-id (get-in (rf/app-db-value :rf/default)
                           [:rf/runtime :machines :spawned :session/flow [:authenticating]])
          record   (drive! child-id [:succeed :session/token-abc])
          dests    (filterv #(= :rf.machine/destroyed (:operation %)) (:trace-events record))]
      (is (some #(= :rf.machine/finished (-> % :tags :reason)) dests)
          "(a) the child auto-destroyed with :reason :rf.machine/finished")
      (is (= :session/token-abc
             (get-in (snapshot :session/flow) [:data :session-token]))
          "(a)(c) the parent's :on-done callback ran, reporting the child's
                  :output-key token into the parent's :data")
      (is (nil? (snapshot child-id))
          "(c) the child's snapshot was synchronously dissoc'd (auto-destroy)"))))

;; ============================================================================
;; FUSE (WILDCARD-THROW) — :* action THROWS (gap b: the unasserted outcome)
;; ============================================================================

(deftest fuse-boot-entry-throw-renders-as-error-not-no-op
  (testing "rung #19 (rf2-hhkbb gap b / rf2-gl588) — an initial `:entry`
            action that THROWS on boot is a REAL machine-action exception,
            NOT a benign no-op. The first event lazily boots :armed, whose
            `:entry :blow-fuse` throws DURING the initial-entry cascade. (b) a
            cascade row carries :threw? true + an :exception (the Xray-surface
            analogue of cascade-summary :outcome :error); (b) NO :no-op row (a
            throw is never a no-op — the contrast with the door's benign
            no-op). Re-vehicled from the old `:*`-wildcard throw — F‴ made the
            start marker a pure init-kick, so the boot-time `:entry` is now
            the exception vehicle."
    (setup!)
    ;; The deck deliberately doesn't eager-start the fuse: its first real
    ;; event lazily boots :armed, whose `:entry` throws on the boot cascade.
    (let [record  (drive! :fuse/box [:fuse/short-circuit])
          rows    (cascade record)
          thrown  (filterv :threw? rows)
          err-ops (filterv #(= :rf.error/machine-action-exception (:operation %))
                           (:trace-events record))]
      (is (seq err-ops)
          "(b) a :rf.error/machine-action-exception rode the trace stream")
      (is (seq thrown)
          "(b) a cascade row is marked :threw? true (NOT silently green)")
      (is (some? (:exception (first thrown)))
          "(b) the thrown action carries its :exception for the EXCEPTION card")
      (is (empty? (rows-of-kind rows :no-op))
          "(b) a throwing boot `:entry` is NEVER a no-op — the foil to the benign no-op"))))

;; ============================================================================
;; HVAC (DEEP-COMPOUND) — the LCA + self-transition headline (subsumes k08ay)
;; ============================================================================

(deftest hvac-power-cycle-renders-deep-parallel-initial-cascade
  (testing "rung #20 — :hvac/power-cycle broadcasts to BOTH regions; :climate
            descends its full initial cascade to [:running :conditioning
            :heating]. (a) the structured :cascade is a per-region parallel
            walk; (c) the committed snapshot moved both regions."
    (setup!)
    (let [record     (drive! :hvac/controller [:hvac/power-cycle])
          structured (structured-of record)
          regions    (proj/cascade-regions structured)]
      (is (proj/parallel-cascade? structured) "(a) the cascade carries both regions")
      (is (= [:climate :fan] (mapv :region regions)) "(a) regions in declaration order")
      (is (= {:climate [:running :conditioning :heating] :fan :on}
             (:state (snapshot :hvac/controller)))
          "(c) both regions moved in one macrostep (deep initial cascade)"))))

(deftest hvac-mode-toggle-renders-lca-cascade-order
  (testing "rung #21 — :hvac/mode-toggle crosses the :conditioning LCA. (a) the
            structured cascade is exactly exit [:running :conditioning :heating]
            → entry [:running :conditioning :cooling] (deepest-exit-first →
            shallowest-entry-first; the LCA :conditioning stays active, so it is
            neither exited nor entered); (c) the deep compound leaf moved
            heating → cooling."
    (setup!)
    (drive! :hvac/controller [:hvac/power-cycle])       ; → :heating
    (let [record (drive! :hvac/controller [:hvac/mode-toggle])
          steps  (region-steps record :climate)]
      (is (= [:exit :entry] (mapv :kind steps))
          "(a) the LCA cascade order: deepest leaf exit → new-leaf entry")
      (is (= [[:running :conditioning :heating]
              [:running :conditioning :cooling]]
             (mapv :state steps))
          "(a) the deepest :heating leaf exits, the :cooling leaf enters; the
               LCA :conditioning stays active (neither exited nor entered)"))
    (is (= [:running :conditioning :cooling] (:climate (:state (snapshot :hvac/controller)))))))

(deftest hvac-self-transitions-external-vs-internal
  (testing "rungs #22/#23 — EXTERNAL self-transition (:hvac/nudge,
            :target :same-state + :reenter? true) fires exit+entry of :fan :on;
            INTERNAL (:hvac/tweak) fires its :tweak-fan action ONLY. (a) the
            structured cascade region steps distinguish them; (c) neither emits
            a spurious no-op transition row; both stay :fan :on."
    (setup!)
    (drive! :hvac/controller [:hvac/power-cycle])       ; fan → :on
    ;; #22 — external self-transition: :target :same-state + :reenter? re-enters :on.
    (let [record (drive! :hvac/controller [:hvac/nudge])
          rows   (cascade record)
          steps  (region-steps record :fan)]
      (is (= [:exit :entry] (mapv :kind steps))
          "(a) external self-transition re-enters :on: exit → entry boundary")
      (is (= [[:on] [:on]] (mapv :state steps))
          "(a) the exit and entry both land on :on (a genuine self re-entry)")
      (is (empty? (rows-of-kind rows :no-op))
          "(c) a genuine self-transition is NOT a no-op")
      (is (= 1 (count (rows-of-kind rows :transition)))
          "(c) one real transition row (e6q97: not suppressed)"))
    ;; #23 — internal self-transition (the foil): action ONLY, no boundary.
    (let [record (drive! :hvac/controller [:hvac/tweak])
          rows   (cascade record)
          steps  (region-steps record :fan)]
      (is (= [:action] (mapv :kind steps))
          "(a) internal self-transition: action ONLY — no exit/entry boundary")
      (is (= [:tweak-fan] (mapv :action steps))
          "(a) the internal action that ran is :tweak-fan")
      (is (empty? (filterv #(and (= :action (:kind %)) (= :exit (:phase %))) rows))
          "(c) internal self-transition fires NO exit row")
      (is (empty? (rows-of-kind rows :no-op))
          "(c) internal self-transition is a REAL transition, not a no-op"))
    (is (= :on (:fan (:state (snapshot :hvac/controller)))))
    (is (= 1 (get-in (snapshot :hvac/controller) [:data :tweaks]))
        "(c) the internal :tweak-fan action bumped :tweaks once")))

;; ============================================================================
;; HISTORY (gap 8, LIVE) — placement rejection + shallow + deep RESTORE render
;; ============================================================================

(deftest history-machine-misplaced-is-rejected
  (testing "rung #24 (gap 8) — history is FIRST-CLASS (rf2-mle6e); the engine
            implements record/restore. A :type :history pseudo-state still has
            a PLACEMENT constraint: it MUST have an owning compound. A
            `:type :history` MACHINE ROOT (the probe spec) is rejected with
            :rf.error/machine-history-misplaced."
    (let [thrown (try
                   (rf/reg-machine :machine-epochs/history-probe
                                   machines/history-machine-spec)
                   nil
                   (catch :default e e))]
      (is (some? thrown) "registering a root :type :history machine THROWS (misplaced)")
      (is (= :rf.error/machine-history-misplaced
             (:rf.error/id (ex-data thrown)))
          "the throw is the misplaced-history placement error (not a generic validator fail)")
      (is (= :history (:feature (ex-data thrown)))
          ":feature names the history grammar"))
    (is (true? (machines/history-rejected?))
        "the deck's history-rejected? helper agrees the root probe is still rejected")))

;; The eject/restore dance the deck's #25/#26 rungs drive — positions the
;; player deep, ejects (records), and returns the FINAL :insert's drive!
;; record (the restore whose cascade the Epoch panel renders).
(defn- drive-history-restore!
  "Position `machine-id` deep into :player, eject (record), then drive + return
  the FINAL :insert (the restore). Mirrors `history-restore-fx` in the deck."
  [machine-id]
  (drive! machine-id [:insert])   ; first entry → default
  (drive! machine-id [:seek])     ; :at-start → :mid-track (deep leaf)
  (drive! machine-id [:eject])    ; exit :player → :tray (RECORDS)
  (drive! machine-id [:insert]))  ; re-enter via :hist (RESTORES — the focal cascade)

(deftest history-deep-restore-renders-restored-banner-and-source-steps
  (testing "rung #26 (gap 8, rf2-mle6e.5) — :media/deep eject → re-insert
            RESTORES the exact recorded leaf. (a) a :rf.machine.history/restored
            trace fires with :source :recorded + :kind :deep; (b) the projection
            stamps :history-restored on the transition row + every history-driven
            :entry cascade step carries :source :recorded; (c) the player lands
            at the exact recorded deep leaf."
    (setup!)
    (let [record    (drive-history-restore! :media/deep)
          rows      (cascade record)
          tx        (first (rows-of-kind rows :transition))
          restored  (proj/history-restored-rows (:trace-events record))
          structured (structured-of record)
          entries   (filterv #(= :entry (:kind %)) structured)]
      ;; (a) the trace shape.
      (is (= 1 (count restored)) "(a) exactly one history-restored record")
      (is (= :recorded (:source (first restored))) "(a) restored from the RECORDED config")
      (is (= :deep (:kind (first restored))) "(a) deep history")
      (is (= [:player :playing :mid-track] (:restored-config (first restored)))
          "(a) the recorded config that drove the restore is the exact deep leaf")
      (is (= [:player :playing :mid-track] (:resolved-leaf (first restored)))
          "(a) deep restore resolved to the exact recorded leaf")
      ;; (b) the projection enrichment + cascade-step :source.
      (is (seq (:history-restored tx))
          "(b) the transition row carries :history-restored (the banner gate)")
      (is (seq entries) "(b) the restore produced entry cascade steps")
      (is (some #(= :recorded (:source %)) entries)
          "(b) at least one history-driven :entry step carries :source :recorded")
      ;; (c) the live machine landed at the exact recorded leaf.
      (is (= [:player :playing :mid-track] (:state (snapshot :media/deep)))
          "(c) deep history restored the exact leaf, not :initial"))))

(deftest history-shallow-restore-renders-restored-banner-shallow
  (testing "rung #25 (gap 8, rf2-mle6e.5) — :media/shallow eject → re-insert
            restores the recorded DIRECT CHILD then descends its :initial. (a)
            the restored record is :source :recorded + :kind :shallow with the
            child-keyword :restored-config; (b) the transition row carries
            :history-restored; (c) the player lands at the child's :initial,
            NOT the exact exit leaf."
    (setup!)
    (let [record   (drive-history-restore! :media/shallow)
          rows     (cascade record)
          tx       (first (rows-of-kind rows :transition))
          restored (proj/history-restored-rows (:trace-events record))]
      (is (= 1 (count restored)) "(a) one history-restored record")
      (is (= :recorded (:source (first restored))) "(a) restored from the recorded config")
      (is (= :shallow (:kind (first restored))) "(a) SHALLOW history")
      (is (= :playing (:restored-config (first restored)))
          "(a) shallow records + restores the direct-child keyword (:playing)")
      (is (= [:player :playing :at-start] (:resolved-leaf (first restored)))
          "(a) shallow restore descends the child's :initial chain")
      (is (seq (:history-restored tx)) "(b) the transition row carries :history-restored")
      (is (= [:player :playing :at-start] (:state (snapshot :media/shallow)))
          "(c) shallow restored the child then its :initial, not the exit leaf"))))

(deftest history-eject-records-and-renders-recorded-banner
  (testing "gap 8 (rf2-mle6e.5) — ejecting :player (exit to the off-compound
            :tray) WRITES :player's last config into :rf/history. (a) a
            :rf.machine.history/recorded trace fires; (b) the projection stamps
            :history-recorded on the EJECT transition row; (c) the snapshot's
            inspectable :rf/history slot holds the recorded leaf."
    (setup!)
    (drive! :media/deep [:insert])               ; → default :playing :at-start
    (drive! :media/deep [:seek])                 ; → :mid-track (deep leaf)
    (let [record   (drive! :media/deep [:eject])  ; exit :player → :tray (RECORDS)
          rows     (cascade record)
          tx       (first (rows-of-kind rows :transition))
          recorded (proj/history-recorded-rows (:trace-events record))]
      (is (= 1 (count recorded)) "(a) exactly one history-recorded record")
      (is (= [:player] (:compound-path (first recorded)))
          "(a) recorded under the compound's declaration path (the :rf/history key)")
      (is (= [:player :playing :mid-track] (:recorded-config (first recorded)))
          "(a) deep recording wrote the full exit leaf")
      (is (seq (:history-recorded tx))
          "(b) the eject transition row carries :history-recorded (the banner gate)")
      ;; (c) the inspectable :rf/history snapshot slot.
      (is (= [:player :playing :mid-track]
             (get-in (snapshot :media/deep) [:rf/history [:player]]))
          "(c) the snapshot's :rf/history slot holds the recorded leaf (App-db inspectable)"))))
