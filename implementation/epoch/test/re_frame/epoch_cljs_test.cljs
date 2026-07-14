(ns re-frame.epoch-cljs-test
  "CLJS smoke coverage for the epoch artefact. JVM coverage in
  `re-frame.epoch-test` is
  dense, but the epoch surface ships through `re-frame.epoch.cljc` and
  the late-bind seam under CLJS too; this file pins the cross-target
  contract for the load-bearing behaviours:

    1. Recording — every dequeued event commits one `:rf/epoch-record` and
       the record carries the canonical shape (`:event-id`, `:db-before`,
       `:db-after`, `:effects`, `:outcome :ok`).
    2. Restore happy path — `restore-epoch!` rewinds `app-db` to a named
       earlier epoch's `:db-after`.
    3. Ring depth cap — `(rf/configure! {:epoch-history {:depth 3}})`
       followed by five dispatches keeps the last three; the oldest two
       are dropped.
    4. Per-dispatch fan-out — `register-epoch-listener!` fires once per
       event epoch with the assembled record (the contract that
       Xray's preload routes through to dispatch
       `:rf.xray/epoch-recorded`; the per-dispatch signal is the
       epoch-cb fan-out itself, plus the `:rf.epoch/snapshotted`
       trace emitted alongside).
    5. Production-elision DCE — runtime gate sanity. Under the
       `:node-test` build (`goog.DEBUG=true`) the surface is live;
       the framework-level grep
       (`implementation/scripts/check-elision.cjs`) is the
       authoritative `:advanced` + `goog.DEBUG=false` DCE assertion
       and already pins every `:rf.epoch/*` sentinel. This file's
       gate-state assertion locks the dev-side companion: under the
       dev gate, `interop/debug-enabled?` is truthy and the surface
       actually records — without that, the grep test would be
       vacuous (it asserts ABSENCE of strings that only enter the
       bundle when this gate is true).

  Covers one happy path, one ring-cap path, one listener path,
  one runtime-gate-on path. The JVM tests carry the conformance
  weight; this file locks the cross-substrate contract.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up via
  `:ns-regexp \"cljs-test$\"`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.epoch :as epoch]
            [re-frame.epoch.listeners :as epoch.listeners]
            [re-frame.epoch.state :as state]
            ;; rf2-vxgfnd.265 — the reentrant claim-before-delivery fixture reads
            ;; the successor incarnation's token to model its pre-first-epoch claim.
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            ;; rf2-vxgfnd.245 — the reentrant CLJS fail-before fixture wraps the
            ;; `:epoch/on-frame-destroyed` late-bind hook to install + settle a
            ;; same-id successor synchronously inside A's terminal publish.
            [re-frame.late-bind :as late-bind]
            ;; rf2-lo28u — schemas + the Malli adapter so a `:schema`-bearing
            ;; reg-event's `:where :event` violation actually fires (without
            ;; the adapter the default validator soft-passes).
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; The `:node-test` build has no DOM and no Reagent reactive context —
;; the plain-atom adapter is the right substrate for epoch coverage
;; (matches the JVM test fixture in `re-frame.epoch-test`). Per
;; `test-support/make-reset-runtime-fixture` (rf2-am9d): the registrar is
;; snapshot/restored around each test so framework / example
;; registrations survive cross-ns CLJS test runs.
;;
;; rf2-yw1w1u — epoch state isolation (ring history, listeners, config)
;; now flows through the fixture's reset-hook table
;; (`:epoch/clear-history!`, `:epoch/clear-epoch-listeners!`,
;; `:epoch/reset-config!`), so the `:init-fn` only re-applies this
;; suite's non-default `:trace-events-keep 5` (NOT the shipped 50 =
;; :depth) through the public `configure!` boundary — no test ns reaches
;; into the private `state/config` var for fixture reset.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- 1. Recording — happy-path record shape -------------------------------

(deftest record-on-event-settle-cljs
  (testing "dispatch a single event under CLJS — exactly one record
            lands in the ring with the canonical shape (`:event-id`,
            `:db-before`, `:db-after`, `:effects`, `:outcome :ok`)"
    (rf/reg-event :n/init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :n/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:n/init])
    (rf/dispatch-sync [:n/inc])

    (let [history (rf/epoch-history :rf/default)]
      (is (= 2 (count history))
          "one record per dequeued event under the CLJS plain-atom adapter")
      (let [r (last history)]
        (is (= :n/inc      (:event-id r))
            ":event-id names the triggering event")
        (is (= [:n/inc]    (:trigger-event r))
            ":trigger-event is the full event vector")
        (is (= {:n 0}      (:db-before r))
            ":db-before is the pre-cascade snapshot")
        (is (= {:n 1}      (:db-after r))
            ":db-after is the post-settle snapshot")
        (is (= :ok         (:outcome r))
            ":outcome :ok pins the event-settle outcome on the happy path")
        (is (vector? (:effects r))
            ":effects is a vector projection from the trace stream")))))

;; ---- 1b. rf2-lo28u — event-args :where :event lands in epoch :trace-events --
;;
;; FAITHFUL repro for the standard_epochs button-18 symptom. Mirrors the
;; live wiring: an app-db schema registered for the frame (button 19's
;; [:auth]) PLUS a plain reg-event handler carrying the inline `:schema`
;; metadata button 18 uses verbatim. Dispatch the bad arg; the
;; `:rf.error/schema-validation-failure :where :event` violation MUST be
;; captured into the triggering epoch's `:trace-events` — exactly where
;; Xray's Issues / Schema-timeline lens reads it.
;;
;; This is the surface the prior `schemas_cljs_test` (which only watched
;; the GLOBAL trace-listener stream) could not see: the violation always
;; reached the global stream, but `epoch.capture/capture-event!` DROPS any
;; trace whose tags lack `:frame`, so the `:where :event` trace (which did
;; not tag `:frame`) never landed in the per-frame epoch record — so the
;; live Xray lens (reading `:trace-events`) showed nothing while the
;; `:where :app-db` path (which DOES tag `:frame`) surfaced. Asserting on
;; the epoch record reproduces Mike's RED.
;;
;; RED (pre-fix): epoch `:trace-events` has NO :where :event violation.
;; The violation belongs to the triggering epoch.

(deftest event-args-violation-captured-in-epoch-trace-events-cljs
  (testing "rf2-lo28u — a plain reg-event :schema violation fires
            :where :event AND lands in the triggering epoch's
            :trace-events (parity with :where :app-db)"
    (rf/reg-app-schema [:auth] [:map [:token :string]])
    (let [calls (atom 0)]
      (rf/reg-event :lo28u/bad-event-args
        {:schema [:cat [:= :lo28u/bad-event-args] pos-int?]}
        (fn [{:keys [db]} _ev] (swap! calls inc) {:db (assoc db :baseline 1)}))

      (rf/dispatch-sync [:lo28u/bad-event-args "not-a-number"])

      (is (= 0 @calls)
          "handler skipped — the bad arg was rejected pre-handler")
      (let [history    (rf/epoch-history :rf/default)
            r          (last history)
            violations (filter #(and (= :rf.error/schema-validation-failure
                                        (:operation %))
                                     (= :event (-> % :tags :where)))
                               (:trace-events r))]
        (is (= :lo28u/bad-event-args (:event-id r))
            "the last epoch is the bad-event-args dispatch")
        (is (= 1 (count violations))
            "the :where :event violation is captured in THIS epoch's
             :trace-events")
        (when-let [v (first violations)]
          (is (= :rf/default (-> v :tags :frame))
              "the captured violation carries its :frame tag"))))))

;; ---- 2. Restore happy path -------------------------------------------------

(deftest restore-rewinds-app-db-cljs
  (testing "dispatch three events, restore to the second — app-db
            matches the recorded :db-after"
    (rf/reg-event :n/init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :n/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:n/init])             ;; n=0
    (rf/dispatch-sync [:n/inc])              ;; n=1
    (rf/dispatch-sync [:n/inc])              ;; n=2
    (rf/dispatch-sync [:n/inc])              ;; n=3

    (let [history   (rf/epoch-history :rf/default)
          ;; The 2nd record corresponds to the first :n/inc — :db-after {:n 1}.
          target    (nth history 1)
          target-id (:epoch-id target)]
      (is (= {:n 1} (:db-after target))
          "sanity — the targeted epoch has the expected :db-after")
      (is (true? (rf/restore-epoch! :rf/default target-id))
          "restore-epoch! returns true on the happy path")
      (is (= {:n 1} (rf/app-db-value :rf/default))
          "app-db now matches the named epoch's :db-after"))))

;; ---- 2b. rf2-3fc89f.4 — reentrant tool writes refuse mid-drain -------------
;;
;; The drain-serialization fix routes tool writes through the frame's
;; `:drain-lock` (`re-frame.frame/call-serialized-with-drain!`). A restore /
;; replace issued reentrantly from inside an event handler (i.e. from the
;; active drainer) must REFUSE with the documented during-drain op rather than
;; run — otherwise it would re-take the lock. CLJS cannot thread-preempt, so it
;; never hits the cross-thread TOCTOU, but it MUST still take the same reentrant
;; mid-drain refusal WITHOUT deadlocking (the pre-lock `drain-in-flight?`
;; precondition, unchanged by the fix, is what fires here on `:in-drain? true`).

(deftest reentrant-tool-writes-refuse-mid-drain-cljs
  (testing "restore-epoch! / replace-frame-state! called from inside a drain
            return false, emit :rf.epoch/restore-during-drain /
            :rf.epoch/replace-during-drain, record NO synthetic epoch, and do
            not deadlock (the drain settles cleanly)"
    (rf/reg-event :n/init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:n/init])
    (let [target-id  (:epoch-id (last (rf/epoch-history :rf/default)))
          restore-r  (atom ::unset)
          replace-r  (atom ::unset)
          ops        (atom #{})]
      (trace-tooling/register-listener! ::rec
                                        (fn [ev] (swap! ops conj (:operation ev))))
      (rf/reg-event :n/try-writes
        (fn [{:keys [db]} _]
          (reset! restore-r (rf/restore-epoch! :rf/default target-id))
          (reset! replace-r (rf/replace-frame-state! :rf/default {:rf.db/app {:n 99}}))
          {:db (assoc db :phase :done)}))
      ;; If either reentrant call deadlocked re-taking the lock, this
      ;; dispatch-sync would never return.
      (rf/dispatch-sync [:n/try-writes])
      (trace-tooling/unregister-listener! ::rec)

      (is (false? @restore-r) "reentrant restore-epoch! returned false")
      (is (false? @replace-r) "reentrant replace-frame-state! returned false")
      (is (contains? @ops :rf.epoch/restore-during-drain)
          ":rf.epoch/restore-during-drain fired")
      (is (contains? @ops :rf.epoch/replace-during-drain)
          ":rf.epoch/replace-during-drain fired")
      (is (nil? (some #(when (= :rf.epoch/db-replaced (:event-id %)) %)
                      (rf/epoch-history :rf/default)))
          "no synthetic :rf.epoch/db-replaced record was created by the refused replace")
      (is (= :done (:phase (rf/app-db-value :rf/default)))
          "the drain settled cleanly (no deadlock); app-db carries the handler's own commit"))))

;; ---- 3. Ring depth cap -----------------------------------------------------

(deftest ring-depth-evicts-oldest-cljs
  (testing "configure :depth 3, dispatch 5 events, oldest 2 are dropped"
    (rf/configure! {:epoch-history {:depth 3}})
    (rf/reg-event :n/init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :n/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:n/init])             ;; n=0, would-be record #1
    (dotimes [_ 4] (rf/dispatch-sync [:n/inc])) ;; n=1..4, records #2..#5

    (let [history (rf/epoch-history :rf/default)
          dbs     (mapv :db-after history)]
      (is (= 3 (count history))
          "the ring caps at the configured depth of 3")
      (is (= [{:n 2} {:n 3} {:n 4}] dbs)
          "the three most-recent records are kept; oldest two are evicted FIFO"))))

;; ---- 4. Per-dispatch fan-out — register-epoch-listener! + snapshotted trace ----

(deftest epoch-cb-fires-per-dispatch-cljs
  (testing "register-epoch-listener! fires once per event epoch — the
            contract Xray's preload (`:rf.xray/epoch-recorded`)
            routes through. The companion `:rf.epoch/snapshotted`
            trace also fires once per dispatch."
    (rf/reg-event :n/init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :n/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (let [cb-seen    (atom [])
          trace-seen (atom [])]
      (rf/register-listener! :epoch ::watcher (fn [r] (swap! cb-seen conj r)))
      (trace-tooling/register-listener! ::recorder
                             (fn [ev]
                               (when (= :rf.epoch/snapshotted (:operation ev))
                                 (swap! trace-seen conj ev))))

      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/inc])
      (rf/dispatch-sync [:n/inc])

      (rf/unregister-listener! :epoch ::watcher)
      (trace-tooling/unregister-listener! ::recorder)

      (is (= 3 (count @cb-seen))
          "register-epoch-listener! fired once per dispatch")
      (is (= [:n/init :n/inc :n/inc]
             (mapv :event-id @cb-seen))
          "every record carries its dispatched event-id in order")
      (is (every? #(contains? % :db-after) @cb-seen)
          "every record carries :db-after")
      (is (every? #(contains? % :effects)  @cb-seen)
          "every record carries :effects")
      (is (= 3 (count @trace-seen))
          ":rf.epoch/snapshotted trace fires once per dispatch")
      (is (every? #(= :rf.epoch (:op-type %)) @trace-seen)
          "the trace event's :op-type is :rf.epoch")
      (is (every? #(= :ok (-> % :tags :outcome)) @trace-seen)
          "the trace's :outcome tag is :ok on a clean event settle"))))

;; ---- 5. Production-elision DCE — runtime gate sanity ----------------------

(deftest dev-gate-runtime-sanity-cljs
  (testing "Under `:node-test` (`goog.DEBUG=true`) the
            `interop/debug-enabled?` gate is truthy and the epoch
            surface is live. The framework-level grep
            (`scripts/check-elision.cjs`, rf2-11hn) pins the
            `:advanced` + `goog.DEBUG=false` DCE — every
            `:rf.epoch/*` sentinel must be ABSENT from the
            production bundle. This runtime assertion is the
            dev-side companion: without the gate truthy in dev,
            the grep test would be vacuous (it asserts ABSENCE of
            strings that only enter the bundle when the gate is
            ON in the control build)."
    (is (true? interop/debug-enabled?)
        "the dev gate reads true under :node-test — surface is live")
    ;; The surface's actual liveness — recording, listener fan-out, the
    ;; ring buffer, restore-epoch!'s happy / failure paths — is locked
    ;; by the four deftests above. This test is the lone gate-state
    ;; assertion; pairing it with the framework-level grep gives the
    ;; cross-mode pin (`gate=true` => surface lives; `gate=false`
    ;; => bundle elided).
    (rf/reg-event :probe/init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:probe/init])
    (is (pos? (count (rf/epoch-history :rf/default)))
        "with the gate ON, a dispatch lands a record in the ring")))

;; ---- 6. Capture buffer — claim-based child marker retention (rf2-bhglx) ----
;;
;; The cross-target pin for the JVM `epoch-attribution-test/inv-6c…`. The harvest
;; seam lives in `re-frame.epoch.state` (.cljc), so it runs under CLJS too; this
;; locks the claim-based retention contract on the CLJS runtime — a child
;; `:event/dispatched` marker is RETAINED VERBATIM across every intervening
;; sibling settle until the child's own run-start claims it (ordinary :dispatch
;; fx children go to the FIFO tail, so siblings can settle ahead of the child).
;; The earlier rf2-fxowr count-1 reclaim dropped a legitimate child marker on
;; the first intervening sibling harvest; rf2-bhglx removes that, bounding memory
;; via the terminal paths that clear the whole buffer instead.

(deftest child-marker-retained-across-sibling-settles-cljs
  (testing "rf2-bhglx — a child marker survives intervening sibling settles and
            is claimed only at the child's own settle; a truly-stranded marker is
            bounded by the terminal buffer clear (drop-frame-buffer!)."
    (let [frame    :test/stranded-cljs
          c-mark   {:op-type :rf.event :operation :rf.event/dispatched
                    :tags {:rf.trace/dispatch-id 99 :rf.trace/event-id :child
                           :rf.trace/parent-dispatch-id 7}}
          rs-1     {:op-type :rf.event :operation :rf.event/run-start
                    :tags {:rf.trace/phase :run-start :rf.trace/dispatch-id 7 :rf.trace/event-id :g1}}
          body-1   {:op-type :rf.event :operation :rf.event/db-changed
                    :tags {:rf.trace/dispatch-id 7}}
          rs-2     {:op-type :rf.event :operation :rf.event/run-start
                    :tags {:rf.trace/phase :run-start :rf.trace/dispatch-id 8 :rf.trace/event-id :g2}}
          body-2   {:op-type :rf.event :operation :rf.event/db-changed
                    :tags {:rf.trace/dispatch-id 8}}
          c-rs     {:op-type :rf.event :operation :rf.event/run-start
                    :tags {:rf.trace/phase :run-start :rf.trace/dispatch-id 99 :rf.trace/event-id :child}}
          c-body   {:op-type :rf.event :operation :rf.event/db-changed
                    :tags {:rf.trace/dispatch-id 99}}]
      ;; Parent (id 7) settles, stranding C's marker; then sibling (id 8) settles.
      (state/buffer-event! frame c-mark)
      (state/buffer-event! frame rs-1)
      (state/buffer-event! frame body-1)
      (let [h1 (state/harvest-buffer-for-event! frame)]
        (is (= [rs-1 body-1] h1) "parent harvests ONLY its own traces")
        (is (= [c-mark] (state/buffer-for frame))
            "C's marker survives the parent harvest, verbatim"))
      (state/buffer-event! frame rs-2)
      (state/buffer-event! frame body-2)
      (let [h2 (state/harvest-buffer-for-event! frame)]
        (is (= [rs-2 body-2] h2) "the sibling harvests ONLY its own traces")
        (is (not-any? #(= 99 (-> % :tags :rf.trace/dispatch-id)) h2)
            "C's marker is never folded into the sibling's epoch")
        (is (= [c-mark] (state/buffer-for frame))
            "rf2-bhglx — C's marker SURVIVES the intervening sibling settle"))
      ;; C finally settles, claiming its own marker.
      (state/buffer-event! frame c-rs)
      (state/buffer-event! frame c-body)
      (let [hc (state/harvest-buffer-for-event! frame)]
        (is (= [c-mark c-rs c-body] hc)
            "C's settle claims its dispatch marker + own traces")
        (is (= 7 (-> hc first :tags :rf.trace/parent-dispatch-id))
            "the claimed marker still names its parent (causality kept)")
        (is (empty? (state/buffer-for frame)) "buffer empty after C settles"))
      ;; A truly-stranded marker is bounded by the terminal buffer clear.
      (state/buffer-event! frame c-mark)
      (state/drop-frame-buffer! frame)
      (is (empty? (state/buffer-for frame))
          "rf2-bhglx — drop-frame-buffer! (terminal path) clears a stranded marker"))))

;; ---- 7. Same-id listener replacement generation semantics (rf2-j538f7.5) ---
;;
;; CLJS cannot thread-preempt between swaps, so the two-thread erasure race the
;; JVM suite drives cannot manifest here. But the generation-scoped bookkeeping
;; the fix installs lives in `re-frame.epoch.state` (.cljc) and runs under CLJS
;; too; this pins the sequential-interleaving contract on the CLJS runtime: a
;; same-id replacement mints a fresh generation, a stale old-generation fan-out
;; is refused, the new generation's observation survives the re-registration,
;; and frame destroy silences only the live generation exactly once.

(deftest same-id-replacement-generation-semantics-cljs
  (testing "rf2-j538f7.5 — under CLJS' single-threaded runtime the listener
            registry is generation-scoped: a stale old-generation observation is
            refused, the new generation's observation survives a same-id
            re-registration, and frame destroy silences only the live generation
            exactly once"
    (rf/make-frame {:id :j538/main})
    (rf/make-frame {:id :j538/other})
    (let [silences (atom [])]
      (trace-tooling/register-listener! ::rec
        (fn [ev]
          (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
            (swap! silences conj (:tags ev)))))
      ;; Gen 1 registered; capture its token (a stale fan-out snapshot).
      (rf/register-listener! :epoch ::probe (fn [_] nil))
      (let [gen1 (:generation (get (state/listeners-snapshot) ::probe))]
        ;; Gen 1 observes :j538/main.
        (epoch.listeners/notify-listeners! {:frame :j538/main :epoch-id 1})
        (is (= [::probe] (state/cbs-observing-frame :j538/main))
            "gen 1 is the live observer of :j538/main")
        ;; Same-id replacement → gen 2.
        (rf/register-listener! :epoch ::probe (fn [_] nil))
        (let [gen2 (:generation (get (state/listeners-snapshot) ::probe))]
          (is (not= gen1 gen2) "replacement minted a fresh generation")
          ;; A stale gen-1 fan-out cannot arm the new registration.
          (state/record-observation! ::probe gen1 :j538/other)
          (is (not (contains? (get (state/observations-snapshot) ::probe) :j538/other))
              "stale old-generation observation refused")
          ;; The gen-1 stamp on :j538/main is no longer live.
          (is (empty? (state/cbs-observing-frame :j538/main))
              "the retired generation's :j538/main observation is not live")
          ;; Gen 2 observes :j538/main — survives, stamped under gen 2.
          (epoch.listeners/notify-listeners! {:frame :j538/main :epoch-id 2})
          (is (= gen2 (get-in (state/observations-snapshot) [::probe :j538/main]))
              "the new generation re-stamped :j538/main and its observation survives")
          (is (= [::probe] (state/cbs-observing-frame :j538/main))
              "gen 2 is the live observer of :j538/main")
          ;; Destroy silences the live generation exactly once.
          (rf/destroy-frame! :j538/main)
          (is (= 1 (count (filter #(= :j538/main (:frame %)) @silences)))
              "exactly one silencing trace for the live generation")))
      (trace-tooling/unregister-listener! ::rec)
      (rf/unregister-listener! :epoch ::probe))))

;; ---- 8. rf2-vxgfnd.245 — honest delayed silencing after same-id rearm ------

(deftest predecessor-silencing-suppressed-after-reentrant-successor-rearm-cljs
  (testing "rf2-vxgfnd.245 (synchronous/reentrant CLJS, red before fix) — A's
            post-dissoc epoch hook re-entrantly installs a same-id successor B and
            settles it, claiming the id-keyed stores and re-arming the unchanged
            cb generation with B's record. A must NOT then republish its stale
            snapshot silencing for cb (cb is live on B); B silences cb on B's own
            destroy exactly once. Before the fix A published its silencing
            unconditionally, so cb was falsely silenced and B re-emitted an
            identical unqualified signal."
    (let [id         :rf2-245/frame
          cb         ::rf2-245-cljs-cb
          received   (atom [])
          silencings (atom [])
          armed?     (atom true)
          original   (late-bind/get-fn :epoch/on-frame-destroyed)]
      (rf/reg-event :rf2-245/seed     (fn [{:keys [db]} _] {:db {:n 0}}))
      (rf/reg-event :rf2-245/b-settle (fn [{:keys [db]} _] {:db {:owner :b}}))
      (rf/make-frame {:id id})
      ;; cb observes A by settling one ordinary event; A claims the id-keyed stores.
      (rf/register-listener! :epoch cb (fn [r] (swap! received conj r)))
      (rf/dispatch-sync [:rf2-245/seed] {:frame id})
      (rf/register-listener! :trace ::rf2-245-cljs-silencing
        (fn [ev]
          (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
            (swap! silencings conj ev))))
      (try
        (late-bind/set-fn! :epoch/on-frame-destroyed
          (fn [& args]
            ;; On A's FIRST post-dissoc hook, re-entrantly install same-id B and
            ;; settle it: B claims the id-keyed stores and re-arms cb with B's
            ;; record — synchronously, no threads. The idle top-level destroy runs
            ;; this hook with no drain on the stack (continuing? true), so the
            ;; reentrant settle commits normally.
            (when (and (= id (first args)) (compare-and-set! armed? true false))
              (rf/make-frame {:id id})
              (rf/dispatch-sync [:rf2-245/b-settle] {:frame id}))
            (when original (apply original args))))
        (rf/destroy-frame! id)
        ;; THE FIX: A's silencing fan for cb is suppressed — cb is live on B.
        (is (empty? (filter #(= cb (:cb-id (:tags %))) @silencings))
            "no bare A silencing for cb after reentrant B claimed + re-armed it")
        ;; cb is live on B: another B settle reaches it.
        (let [before (count @received)]
          (rf/dispatch-sync [:rf2-245/b-settle] {:frame id})
          (is (= (inc before) (count @received))
              "cb continues to receive B's records — it is live on B"))
        ;; B destroy → exactly one truthful silencing for cb.
        (rf/destroy-frame! id)
        (is (= 1 (count (filter #(= cb (:cb-id (:tags %))) @silencings)))
            "exactly one truthful silencing for cb — fired by B's own destroy")
        (finally
          (late-bind/set-fn! :epoch/on-frame-destroyed original)
          (rf/unregister-listener! :epoch cb)
          (rf/unregister-listener! :trace ::rf2-245-cljs-silencing))))))

;; ---- 9. rf2-vxgfnd.265 — per-identity delayed silencing (claim / ABA) -------

(deftest predecessor-silences-after-reentrant-successor-claims-without-delivery-cljs
  (testing "rf2-vxgfnd.265 (reentrant CLJS, red before fix) — A's post-dissoc
            epoch hook re-entrantly installs a same-id successor B which CLAIMS
            the id-keyed stores (the pre-first-epoch render/backfill claim routes
            through `claim-frame-owner!`) but SETTLES nothing — cb never observes
            B. When A resumes it must STILL emit its one owed silence for cb. Under
            #5872's coarse gate A LOST the cleanup comparison to B's claim and
            emitted nothing — a false negative."
    (let [id         :rf2-265-claim/frame
          cb         ::rf2-265-claim-cb
          silencings (atom [])
          armed?     (atom true)
          original   (late-bind/get-fn :epoch/on-frame-destroyed)]
      (rf/reg-event :rf2-265-claim/seed (fn [{:keys [db]} _] {:db {:n 0}}))
      (rf/make-frame {:id id})
      (rf/register-listener! :epoch cb (fn [_] nil))
      ;; cb observes A by settling one ordinary event; A claims the id-keyed stores.
      (rf/dispatch-sync [:rf2-265-claim/seed] {:frame id})
      (rf/register-listener! :trace ::rf2-265-claim-silencing
        (fn [ev]
          (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
            (swap! silencings conj ev))))
      (try
        (late-bind/set-fn! :epoch/on-frame-destroyed
          (fn [& args]
            (when (and (= id (first args)) (compare-and-set! armed? true false))
              ;; Same-id B claims the id-keyed stores WITHOUT settling — no B
              ;; record reaches cb, so cb never observes B.
              (rf/make-frame {:id id})
              (state/claim-frame-owner! id (frame/frame-incarnation-token id)))
            (when original (apply original args))))
        (rf/destroy-frame! id)
        ;; THE FIX: cb never observed B, so A owes and emits exactly one silence.
        (is (= 1 (count (filter #(= cb (:cb-id (:tags %))) @silencings)))
            "A emits its one owed silence for cb — B claimed but never delivered")
        (finally
          (late-bind/set-fn! :epoch/on-frame-destroyed original)
          (rf/unregister-listener! :epoch cb)
          (rf/unregister-listener! :trace ::rf2-265-claim-silencing)
          (when (frame/frame id) (rf/destroy-frame! id)))))))

(deftest late-predecessor-does-not-re-emit-silence-a-retired-successor-fired-cljs
  (testing "rf2-vxgfnd.265 (synchronous CLJS, red before fix) — a same-id
            successor B re-arms cb and then RETIRES before paused predecessor A
            resumes (the A→B→nil ABA). B emits the one truthful silence and
            releases the stores; late A now WINS the cleanup comparison but must
            NOT re-emit the identical unqualified signal. #5872's coarse gate let
            A re-fire it — a double signal. The monotonic terminal-silence mark
            surviving B's cleanup is what lets late A recognise B already fired."
    (let [id         :rf2-265-aba/frame
          cb         ::rf2-265-aba-cb
          silencings (atom [])
          token-a    #js {}
          token-b    #js {}]
      (rf/register-listener! :epoch cb (fn [_] nil))
      (rf/register-listener! :trace ::rf2-265-aba-silencing
        (fn [ev]
          (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
            (swap! silencings conj ev))))
      (try
        ;; A claims + cb observes A.
        (state/claim-frame-owner! id token-a)
        (epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
        ;; A snapshots its owed identities + terminal-silence baseline BEFORE the
        ;; successor acts (a same-id B is constructable only after dissoc).
        (let [a-ev (epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)]
          ;; B re-arms cb, then RETIRES — its terminal hook fires cb's one silence.
          (state/claim-frame-owner! id token-b)
          (epoch.listeners/notify-listeners! {:frame id :epoch-id 2})
          (epoch.listeners/on-frame-destroyed! id token-b
            (epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil))
          (is (= 1 (count (filter #(= cb (:cb-id (:tags %))) @silencings)))
              "B's retire fired exactly one truthful silence for cb")
          ;; A resumes with its stale snapshot — must add NO silence.
          (epoch.listeners/on-frame-destroyed! id token-a a-ev)
          (is (= 1 (count (filter #(= cb (:cb-id (:tags %))) @silencings)))
              "late A adds no silence — the retired successor already fired the one signal"))
        (finally
          (rf/unregister-listener! :epoch cb)
          (rf/unregister-listener! :trace ::rf2-265-aba-silencing))))))
