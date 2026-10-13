(ns day8.re-frame2-xray.panels.epoch.machine-epochs-harness-cljs-test
  "ASSERTION-BACKED render-regression harness for the machine-epochs deck.
  The COMPLEMENT to `hard-machine-fidelity-cljs-test` (which this SUBSUMES
  + extends deck-wide): where that pins the HVAC HARD machine's render
  fidelity, THIS pins the FULL machine × Xray-cascade-render SURFACE
  matrix the deck drives — every rung backed by an
  assertion so re-driving the deck is a real regression test of Xray
  rendering, not just a visual deck.

  ## What it drives + asserts

  It registers the IDENTICAL machine specs the testbed mounts (via
  `machine-epochs.machines/register-all!` — the SAME values, no drift),
  drives each rung's event through the LIVE machine substrate
  (`dispatch-sync`), captures the trace stream Xray's ring buffer recorded,
  and feeds it through the rendering layers that FEED the devtools render:

    - `proj/machine-cascade-rows`        — the Epoch panel's per-epoch machine
      cascade (the exit/action/entry/no-op/timer rows; canonical sort).
    - the structured `:cascade` the `:rf.machine/transition` trace carries
      — the LCA + microstep walk, read through this file's own
      `cascade-regions` / `cascade-microsteps` order oracles.
    - `diff/project` — the snapshot diff (member-level set diff for `:tags`).

  EACH rung asserts THREE layers:
    (a) TRACE shape — the machine cascade kinds + order, read off the
        structured `:cascade` (its `:kind` / `:state` / `:action` steps in
        execution order + the `:microsteps`).
    (b) STRUCTURED outcome — the Xray-surface analogue of the
        cascade-summary `:outcome` / `:no-op?`: a thrown action shows
        `:threw? true` on its cascade row; the unhandled rung shows
        exactly ONE `:no-op` row + zero transition rows.
    (c) RENDERED rows — the Epoch-panel projection emits the expected row
        kinds / verbs.

  A Xray/Story-style CLJS unit test, NOT Playwright. The render facts are pinned against REALITY: drive the
  substrate, read what the projection produces."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.diff.engine :as diff]
            [day8.re-frame2-xray.panels.epoch.format :as fmt]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            ;; The SHARED machine specs the deck mounts — the harness drives
            ;; the IDENTICAL values (single source of truth).
            [machine-epochs.machines :as machines]))

;; ============================================================================
;; fixture + drivers
;; ============================================================================

(use-fixtures :each
  ;; `make-xray-runtime-fixture`: the `:all` reset
  ;; tier — install (== preload's alias) + registry + mount idempotency
  ;; sentinels plus the trace-collector rings — over the Reagent adapter
  ;; this suite renders through.
  (xray-test-support/make-xray-runtime-fixture {:adapter rf.adapter.reagent/adapter}))

(defn- setup!
  "Register the Xray handlers + trace collector, register every (non-throwing)
  deck machine via the shared specs, and START each to its initial state.
  Clears the trace buffer so per-rung captures contain only that macrostep."
  []
  (registry/register-xray-handlers!)
  (preload/register-trace-collector!)
  (machines/register-all!)
  (doseq [id [:door/main :traffic/light :quiz/scorer :brew/machine
              :session/flow :hvac/controller :media/deep :media/shallow
              :modal/main :gate/main]]
    (rf/dispatch-sync [id [:rf.machine/start]])))

(defn- snapshot [machine-id]
  ;; Machine snapshots are durable runtime-db state (EP-0001).
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/machines :snapshots machine-id]))

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
(defn- structured-of
  "The structured `:cascade` step vector the macrostep's
  `:rf.machine/transition` trace carries (Spec 005 §The structured
  transition cascade): exit / action / entry / microstep steps in
  EXECUTION order."
  [record]
  (some #(when (= :rf.machine/transition (:operation %))
           (get-in % [:tags :cascade]))
        (:trace-events record)))

(defn- cascade-regions
  "Group a structured cascade's `:exit` / `:action` / `:entry` steps by
  `:region` in FIRST-ENCOUNTER order — `[{:region r :steps [...]} ...]`,
  one `nil` group for a flat / compound machine."
  [cascade]
  (let [steps  (filterv #(contains? #{:exit :action :entry} (:kind %)) cascade)
        order  (distinct (map :region steps))
        groups (group-by :region steps)]
    (mapv (fn [r] {:region r :steps (get groups r)}) order)))

(defn- cascade-microsteps
  "A structured cascade's `:microstep` steps, ordered by `:microstep-index`."
  [cascade]
  (->> cascade
       (filterv #(= :microstep (:kind %)))
       (sort-by #(or (:microstep-index %) 0))
       vec))

;; The structured `:cascade` is the order-oracle: each step carries
;; `:kind` (`:exit` / `:action` / `:entry`), `:state`, `:region`, `:action`
;; (nil for an action-free boundary) in EXECUTION order (exit-deepest-first →
;; action @ LCA → entry-shallowest-first). `region-steps` returns the
;; structural steps of one `:region` (nil for flat/compound) so a test can
;; assert the cascade ORDER directly off the Xray-surface projection.
(defn- region-steps [record region]
  (->> (cascade-regions (structured-of record))
       (some #(when (= region (:region %)) (:steps %)))
       vec))

;; ============================================================================
;; DOOR (FLAT) — plain · entry/exit · transition-action · guards · internal ·
;;               fx · unhandled-no-op · root-:on RESOLUTION (gap 6)
;; ============================================================================

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
    (let [rows      (cascade (drive! :door/main [:door/close]))
          guard-row (first (rows-of-kind rows :guard))]
      ;; The blocking guard is SURFACED in the cascade LIST, not only on the
      ;; chart, so the operator can answer "which guard blocked my event?".
      (is (= ":may-close?" (fmt/cascade-row-label guard-row))
          "the guard row renders a legible verb naming the blocking guard")
      (is (= "fail" (fmt/cascade-outcome-label guard-row))
          "(a) :may-close? fails when held-open? — the fail chip renders")
      ;; One guard row, then one no-op and no transition row (canonical rank
      ;; guard(0) → no-op(2)).
      (is (= [:guard :no-op] (mapv :kind rows))
          "(b) the blocked guard is a no-op, led by the blocking guard"))
    (is (= :open (:state (snapshot :door/main)))
        "(c) the door STAYS :open — the blocked close did not advance")))

;; ============================================================================
;; TRAFFIC (PARALLEL) — regions · history · TAG-SET delta (gap 7)
;; ============================================================================

(deftest traffic-tag-set-delta-renders-member-swap
  (testing "rung #10 (gap 7) — a tick swaps the :tags SET members
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
          "(c) the joining tag renders as a member-level :added")
      (is (member-of :traffic/vehicle-stop removed)
          "(c) the leaving tag renders as a member-level :removed")
      (is (not (member-of :traffic/shared added))
          "(c) the constant :traffic/shared member does NOT churn (no spurious add)")
      (is (not (member-of :traffic/shared removed))
          "(c) the constant :traffic/shared member does NOT churn (no spurious remove)"))))

(deftest multiple-machines-one-cascade-no-op-names-its-machine
  (testing "rung #11 — when >1 machine is in play in one cascade,
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
    ;; machine-ids in play, so each no-op row names its machine.
    (trace-collector/reset-for-test!)
    (rf/dispatch-sync [:door/main [:door/insert-coin]])  ; door no-op in :alarming
    (rf/dispatch-sync [:brew/machine [:brew/abort]])      ; brew no-op in :idle
    (let [rows   (proj/machine-cascade-rows (vec (trace-collector/buffer-for-test)))
          no-ops (rows-of-kind rows :no-op)
          ids    (set (map :machine-id no-ops))]
      (is (= 2 (count no-ops)) "(b) both machines' unhandled events are no-ops")
      (is (= #{:door/main :brew/machine} ids) "(c) two distinct machines in play")
      (is (every? #(true? (:show-machine-name? %)) no-ops)
          "(c) >1 machine in play → EACH no-op row names its machine")
      (is (empty? (rows-of-kind rows :transition))
          "(b) no transition row beside the no-ops — the source suppresses the no-change transition")
      (is (= :alarming (:state (some #(when (= :door/main (:machine-id %)) %) no-ops)))
          "the door's no-op row carries the state it stayed in"))))

;; ============================================================================
;; PARALLEL ROUND — parent-owned cross-region :always rounds
;; ============================================================================
;;
;; REAL runtime: a parallel macrostep can move a region on the EVENT and then
;; again on a parent-owned cross-region `:always` round. The runtime commits
;; ONE aggregate `:rf.machine/transition` (settled region-map) but emits one
;; standalone `:rf.machine.microstep/transition` per SELECTED regional round
;; (`machines/parallel.cljc`). The transition row carries no nested
;; structured-cascade body, so the per-emit cascade pipeline is the sole
;; canonical display — so each such round must be harvested as a first-class
;; `[ALWAYS]` row. This machine is ACTIONLESS (no `:rf.machine/action-ran`
;; fires for the round), so the round trace is its ONLY first-class evidence.

(def ^:private parallel-round-machine
  {:type         :parallel
   :region-order [:a :b]
   :regions {:a {:initial :idle
                 :states  {:idle   {:on {:go :staged}}
                           :staged {:always :done}
                           :done   {}}}
             :b {:initial :idle
                 :states  {:idle   {:on {:go :staged}}
                           :staged {:always :done}
                           :done   {}}}}})

(deftest parallel-always-round-projects-first-class-microstep-rows
  (testing "driving the REAL runtime, :go moves both regions
            :idle→:staged then a parent round co-selects both :staged→:done;
            the standalone :rf.machine.microstep/transition traces are
            harvested as first-class [ALWAYS] round rows (regions [:a :b],
            round-index 0), NOT collapsed into the aggregate transition"
    (setup!)
    (rf/reg-machine :xray-test.round/par parallel-round-machine)
    (rf/dispatch-sync [:xray-test.round/par [:rf.machine/start]])
    (let [record (drive! :xray-test.round/par [:go])
          rows   (cascade record)
          micros (rows-of-kind rows :microstep)]
      (is (= 1 (count (rows-of-kind rows :transition)))
          "the macrostep still commits ONE aggregate transition row")
      (is (= [:a :b] (mapv :region micros))
          "one first-class round row per co-selected region, in region order")
      (is (= [0 0] (mapv :round-index micros))
          "co-selected regions share round-index 0 — ONE parent-owned round")
      (is (= [[:staged :done] [:staged :done]]
             (mapv (juxt :from-state :to-state) micros))
          "each round row carries its region-relative :always hop")
      (is (empty? (rows-of-kind rows :action))
          "the round is ACTIONLESS — the round trace is its only evidence")
      (is (= {:a :done :b :done} (:state (snapshot :xray-test.round/par)))
          "the macrostep settled both regions to :done"))))

;; ============================================================================
;; QUIZ (MICROSTEP) — :always EVENTLESS microsteps (gap 1, THE biggest)
;; ============================================================================

(deftest quiz-answer-to-pass-fires-always-microstep-event-bundle
  (testing "rung #13 (gap 1) — answers reach the pass mark and the guarded
            :always chain SETTLES :asking ──► :passed over N>0 microsteps.
            (a) the transition row's :microsteps > 0 AND the structured
            :cascade carries a :microstep step whose nested :steps explain the
            eventless transition (the :award action → :passed); (c) the quiz
            lands in :passed.
            The TRANSITION row renders NO `N microstep(s)` outcome summary;
            the microstep is surfaced as its own cascade row instead, so
            the count would carry no extra signal."
    (setup!)
    (drive! :quiz/scorer [:quiz/answer])                ; score → 1
    (drive! :quiz/scorer [:quiz/answer])                ; score → 2
    ;; the THIRD answer reaches the mark (>=3) and the :always fires.
    (let [record     (drive! :quiz/scorer [:quiz/answer])
          rows       (cascade record)
          tx         (first (rows-of-kind rows :transition))
          structured (structured-of record)
          microsteps (cascade-microsteps structured)
          microstep  (first microsteps)]
      ;; (a) the macrostep settled over at least one eventless microstep.
      (is (and (number? (:microsteps tx)) (pos? (:microsteps tx)))
          "(a) :always settled over N>0 microsteps (projection preserves the count)")
      ;; The transition row renders no outcome summary: an `N microstep(s)`
      ;; count would be redundant (the microstep below carries the signal).
      ;; The projection keeps :microsteps as a pure-data slot; only the
      ;; RENDERED summary is absent.
      (is (nil? (fmt/cascade-outcome-label tx))
          "(c) the TRANSITION row renders NO 'N microstep(s)' summary")
      (is (= :asking (:from microstep))
          "(a/c) the microstep is surfaced as its own cascade step — the
                 signal a count would summarise")
      (is (= :passed (:to microstep))
          "(a) the microstep transitions :asking → :passed")
      (is (some #(= :award (:action %)) (:steps microstep))
          "(a) the eventless transition's :award action is EXPLAINABLE inside the microstep")
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
      (is (= :on-exit (:reason timer))
          "(c) the cancellation :reason is :on-exit (exit beat the timer)")
      (is (= "cancelled (on-exit)" (fmt/cascade-outcome-label timer))
          "(c) the :cancelled chip renders the reason"))
    (is (= :idle (:state (snapshot :brew/machine))))))

;; ============================================================================
;; FUSE (THROW-ON-BOOT) — the initial `:entry` action THROWS (gap b: the
;; unasserted outcome)
;; ============================================================================

(deftest fuse-boot-entry-throw-renders-as-error-not-no-op
  (testing "rung #19 (gap b) — an initial `:entry`
            action that THROWS on boot is a REAL machine-action exception,
            NOT a benign no-op. The first event lazily boots :armed, whose
            `:entry :blow-fuse` throws DURING the initial-entry cascade. (b) a
            cascade row carries :threw? true + an :exception (the Xray-surface
            analogue of cascade-summary :outcome :error); (b) NO :no-op row (a
            throw is never a no-op — the contrast with the door's benign
            no-op). The start marker is a pure init-kick, so the boot-time
            `:entry` is the exception vehicle."
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
      (is (some? (:exception (first thrown)))
          "(b) a cascade row is marked :threw? true and carries its :exception
               for the EXCEPTION card")
      (is (empty? (rows-of-kind rows :no-op))
          "(b) a throwing boot `:entry` is NEVER a no-op — the foil to the benign no-op"))))

;; ============================================================================
;; HVAC (DEEP-COMPOUND) — the LCA + self-transition headline
;; ============================================================================

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
          "(c) one real transition row (not suppressed)"))
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
;; HISTORY (gap 8, LIVE) — shallow + deep RESTORE render
;; ============================================================================

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
  (testing "rung #26 (gap 8) — :media/deep eject → re-insert
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
      (is (some #(= :recorded (:source %)) entries)
          "(b) at least one history-driven :entry step carries :source :recorded")
      ;; (c) the live machine landed at the exact recorded leaf.
      (is (= [:player :playing :mid-track] (:state (snapshot :media/deep)))
          "(c) deep history restored the exact leaf, not :initial"))))

(deftest history-eject-records-and-renders-recorded-banner
  (testing "gap 8 — ejecting :player (exit to the off-compound
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

;; ============================================================================
;; MODAL (MULTI-EVENT transition) — the events-as-nodes divergence
;; ============================================================================
;;
;; THE events-as-nodes case: ONE edge (:open ──► :closed)
;; reached on THREE distinct events (:modal/cancel, :modal/submit [+:save],
;; :modal/escape). xstate v5 STACKS the three labels on one edge; re-frame2's
;; events-as-nodes render draws THREE event-nodes fanning INTO :closed. The
;; BEHAVIOUR is identical (XState v5 gold standard): every event lands :closed.
;; The harness asserts (a) each event's cascade is a real :open ──► :closed
;; transition, (b) the :submit branch ALSO runs the :save action (the data-
;; bearing fan-in branch), and (c) the multi-event fan-in is real — the rows
;; below reach the SAME (from→to) edge on distinct event-ids (:modal/escape
;; takes the same path as :modal/cancel).

(defn- modal-open! []
  (drive! :modal/main [:modal/open]))

(deftest modal-submit-runs-save-and-lands-closed
  (testing ":modal/submit (fan-in event #2) drives :open ──►
            :closed AND runs the :save action (the data-bearing branch of the
            fan-in). (a) the transition + an :save action whose :data-write sets
            :saved?; (c) the modal lands :closed with :saved? true in :data."
    (setup!)
    (modal-open!)                                       ; → :open
    (let [record (drive! :modal/main [:modal/submit])    ; → :closed (runs :save)
          rows   (cascade record)
          tx     (first (rows-of-kind rows :transition))]
      (is (= :open (:from-state tx)) "(c) a real transition row renders")
      (is (= :closed (:to-state tx)) "(a) :modal/submit lands :closed")
      (is (some #(true? (get-in % [:data-write :saved?]))
                (rows-of-kind rows :action))
          "(a)(c) the :save action ran (its :data-write set :saved?)"))
    (is (= :closed (:state (snapshot :modal/main))))
    (is (true? (get-in (snapshot :modal/main) [:data :saved?]))
        "(c) the :save action wrote :saved? into the snapshot")))

;; ============================================================================
;; GATE (MULTI-BRANCH GUARDED fork) — the guard-fork divergence
;; ============================================================================
;;
;; THE guard-fork case: ONE event (:gate/check) forks from
;; :idle by a guarded candidate VECTOR — first guard-pass wins (:gate-high? →
;; :high, :gate-low? → :low) with an unguarded fallback (→ :rejected). xstate v5
;; draws ONE labelled edge per branch from :idle (the guard in the label). The
;; harness asserts each :check lands the guarded branch AND surfaces the guard
;; predicate(s) on the fork's guard rows (:guard-id + :outcome) — the fork's
;; guard predicates the render shows.


(defn- gate-guard-outcome [record guard-id]
  (->> (rows-of-kind (cascade record) :guard)
       (some #(when (= guard-id (:guard-id %)) (:outcome %)))))

(deftest gate-low-branch-fires-when-high-fails
  (testing ":gate/set 2 then :gate/check forks to :low: the FIRST
            candidate :gate-high? FAILS (2<5), the candidate walk advances to
            :gate-low? which passes. (a) the fork shows :gate-high? :fail AND
            :gate-low? :pass — the multi-branch walk; (c) the gate lands :low."
    (setup!)
    (drive! :gate/main [:gate/set 2])
    (let [record (drive! :gate/main [:gate/check])
          tx     (first (rows-of-kind (cascade record) :transition))]
      (is (= :low (:to-state tx)) "(c) :gate-low? selected the :low branch")
      (is (= :fail (gate-guard-outcome record :gate-high?))
          "(a) :gate-high? FAILED (2 < 5) — the first candidate's guard blocked")
      (is (= :pass (gate-guard-outcome record :gate-low?))
          "(a) the walk advanced to :gate-low? which PASSED (0 < 2 < 5)"))
    (is (= :low (:state (snapshot :gate/main))))))


