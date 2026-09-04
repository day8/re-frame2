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
            ;; rf2-gj2bo — the CLJS same-id-successor injection pin interposes
            ;; its churn on the real precondition check via with-redefs.
            [re-frame.epoch.tool-pair :as tool-pair]
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

;; ---- 2c. rf2-gj2bo — injection fenced to the exact incarnation --------------
;;
;; JVM coverage in `re-frame.epoch-test` carries the full matrix (pre-write
;; churn, post-write tail, both-partition, no-churn controls); this pins the
;; load-bearing A-to-B PRE-WRITE invariant in the CLJS lane, deterministically,
;; via the same precheck interposition the JVM tests use — no timing sleeps.
;; The precheck runs REAL against live incarnation A and yields A's exact
;; record-derived token; the interposed churn destroys A and seats + seeds a
;; same-id successor B before the write; the token fence must then refuse.

(deftest replace-frame-state-fenced-to-exact-incarnation-cljs
  (testing "rf2-gj2bo — an injection validated against incarnation A, with a
            same-id successor B seated + seeded BEFORE the write, is REJECTED:
            false return, canonical :rf.error/no-such-handler, B's frame-state
            and history byte-for-byte at their baselines, and no
            :rf.epoch/db-replaced record or trace"
    (rf/make-frame {:id :gj2bo/succ})
    (rf/reg-event :gj2bo/set-owner (fn [_ [_ o]] {:db {:owner o}}))
    (rf/dispatch-sync [:gj2bo/set-owner :A] {:frame :gj2bo/succ})
    (let [real-check tool-pair/check-replace-frame-state-preconditions!
          a-token    (frame/frame-incarnation-token :gj2bo/succ)
          checked    (atom nil)
          b-baseline (atom nil)
          ops        (atom #{})]
      (trace-tooling/register-listener! ::gj2bo-rec
                                        (fn [ev] (swap! ops conj (:operation ev))))
      (with-redefs [tool-pair/check-replace-frame-state-preconditions!
                    (fn [frame-id new-frame-state]
                      (let [r (real-check frame-id new-frame-state)]
                        (reset! checked r)
                        ;; Interpose: destroy A, seat + seed same-id successor
                        ;; B, capture B's baselines, hand back A's ticket.
                        (rf/destroy-frame! frame-id)
                        (rf/make-frame {:id frame-id})
                        (rf/dispatch-sync [:gj2bo/set-owner :B] {:frame frame-id})
                        (reset! b-baseline
                                {:frame-state (rf/frame-state-value frame-id)
                                 :history     (rf/epoch-history frame-id)})
                        r))]
        (let [result (rf/replace-frame-state! :gj2bo/succ {:rf.db/app {:owner :STALE-A}})]
          (trace-tooling/unregister-listener! ::gj2bo-rec)
          (is (= :ok (:outcome @checked))
              "the REAL precondition check passed against live incarnation A")
          (is (identical? a-token (:incarnation-token @checked))
              "the :ok ticket carries A's exact record-derived token")
          (is (not (identical? a-token (frame/frame-incarnation-token :gj2bo/succ)))
              "successor B is a fresh incarnation (A/B tokens distinct)")
          (is (false? result) "the stale cross-incarnation injection is REJECTED")
          (is (= {:owner :B} (rf/app-db-value :gj2bo/succ))
              "B's app-db is byte-for-byte unchanged — :STALE-A never installed")
          (is (= (:frame-state @b-baseline) (rf/frame-state-value :gj2bo/succ))
              "B's whole frame-state is at its captured baseline")
          (is (= (:history @b-baseline) (rf/epoch-history :gj2bo/succ))
              "B's history is at its captured baseline — no stale synthetic record")
          (is (contains? @ops :rf.error/no-such-handler)
              "the canonical :rf.error/no-such-handler typed failure fired")
          (is (not (contains? @ops :rf.epoch/db-replaced))
              "no :rf.epoch/db-replaced success trace")
          (rf/destroy-frame! :gj2bo/succ))))))

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

(deftest lowering-depth-prunes-the-live-ring-cljs
  ;; rf2-f8wu — the cross-target half of the JVM suite's
  ;; `lowering-depth-prunes-every-live-ring-immediately` /
  ;; `depth-zero-drops-retained-history-and-refuses-time-travel` pair. The
  ;; enforcement lives in `re-frame.epoch.state/merge-config!`, a `.cljc`
  ;; shared with the JVM, so this pins that the depth bound reaches the
  ;; live ring under CLJS too — on BOTH axes, since a fix that closed the
  ;; query surface without the restore surface would look green on either
  ;; one alone.
  (testing "a depth reduction prunes the live ring at the configure!
            boundary, and depth 0 empties it — the dropped records are
            afterwards neither queryable nor restorable"
    (rf/configure! {:epoch-history {:depth 10}})
    (rf/reg-event :n/init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :n/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:n/init])
    (dotimes [_ 4] (rf/dispatch-sync [:n/inc]))
    (is (= 5 (count (rf/epoch-history :rf/default)))
        "precondition: the ring holds five records")

    ;; Lowering the depth bites now — no further dispatch appends.
    (rf/configure! {:epoch-history {:depth 2}})
    (is (= [{:n 3} {:n 4}] (mapv :db-after (rf/epoch-history :rf/default)))
        "the newest two are retained, still oldest-first")

    (let [saved-id (:epoch-id (first (rf/epoch-history :rf/default)))]
      (rf/configure! {:epoch-history {:depth 0}})
      (is (= [] (rf/epoch-history :rf/default))
          "depth 0 empties the ring, records retained beforehand included")
      (is (= [] (rf/projected-history :rf/default))
          "the off-box projection reads the same pruned ring")
      (is (false? (rf/restore-epoch! :rf/default saved-id))
          "a retired id is no longer a valid restore target")
      (is (= {:n 4} (rf/app-db-value :rf/default))
          "and the refused restore left app-db untouched"))))

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

;; ---- 10. rf2-vxgfnd.285 — exact / linearizable / bounded lineage -----------

(defn- vxgfnd285-total-marks
  "Count of `[frame cb]` terminal-silence marks currently retained."
  []
  (reduce + 0 (map count (vals (state/terminal-silence-marks-snapshot)))))

(deftest vxgfnd285-trace-listener-rearming-later-identity-mid-fan-rechecked-cljs
  (testing "rf2-vxgfnd.285 (reentrant CLJS, red before fix) — LINEARIZABLE. The
            owed identities fan in a deterministic cb-id order; a trace listener
            fired by the FIRST silence re-arms a LATER identity on a live
            successor B synchronously mid-fan. Because eligibility is re-read
            FRESH inside each per-identity claim, the re-armed identity is skipped.
            A stale pre-loop observer set would miss the re-arm and wrongly
            silence it."
    (let [id         :rf2-285-rearm/frame
          trigger    ::a-trigger            ; sorts first
          target     ::z-target             ; sorts last
          token-a    #js {}
          token-b    #js {}
          silencings (atom [])
          silences-for (fn [cb] (count (filter #(= cb (:cb-id (:tags %))) @silencings)))]
      (rf/register-listener! :epoch trigger (fn [_] nil))
      (rf/register-listener! :epoch target (fn [_] nil))
      (try
        (state/claim-frame-owner! id token-a)
        (epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
        (let [target-gen (:generation (get (state/listeners-snapshot) target))
              a-ev       (epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)]
          ;; Live successor B claims (dropping every observation); neither identity
          ;; is a live observer at fan start.
          (state/claim-frame-owner! id token-b)
          (rf/register-listener! :trace ::rf2-285-rearm-silencing
            (fn [ev]
              (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                (swap! silencings conj ev)
                (when (= trigger (:cb-id (:tags ev)))
                  ;; Re-arm z-target on B mid-fan, before it is reached.
                  (state/record-observation! target target-gen id)))))
          (epoch.listeners/on-frame-destroyed! id token-a a-ev)
          (is (= 1 (silences-for trigger)) "a-trigger — A-only — is silenced first")
          (is (zero? (silences-for target))
              "z-target — re-armed live mid-fan — is rechecked and skipped"))
        (finally
          (rf/unregister-listener! :epoch trigger)
          (rf/unregister-listener! :epoch target)
          (rf/unregister-listener! :trace ::rf2-285-rearm-silencing))))))

(deftest vxgfnd285-lineage-marks-bounded-across-unique-frames-cljs
  (testing "rf2-vxgfnd.285 (synchronous CLJS, red before fix) — BOUNDED. One
            persistent callback observes and destroys many UNIQUE frame ids at the
            epoch-state seam; each destroy's deferred window closes and reclaims
            that frame's marks, so lineage storage returns to a constant (empty)
            baseline rather than accreting a permanent tombstone per id."
    (let [cb ::rf2-285-bounded-cb
          n  1500]
      (rf/register-listener! :epoch cb (fn [_] nil))
      (try
        (dotimes [i n]
          (let [id    (keyword "rf2-285-bounded" (str i))
                token #js {}]
            (state/claim-frame-owner! id token)
            (epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
            (epoch.listeners/on-frame-destroyed! id token
              (epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil))
            (is (<= (vxgfnd285-total-marks) 1)
                "at most one frame's marks are ever retained at once")))
        (is (zero? (vxgfnd285-total-marks))
            "after all destroys settle, lineage storage returns to a constant baseline")
        (finally
          (rf/unregister-listener! :epoch cb))))))

(deftest vxgfnd285-reset-listeners-clears-silence-lineage-cljs
  (testing "rf2-vxgfnd.285 (CLJS) — BOUNDED. A full listener wipe clears the
            terminal-silence lineage too — the old reset-listeners! left a
            tombstone per destroyed frame behind."
    (let [id :rf2-285-reset/frame
          cb ::rf2-285-reset-cb]
      (rf/register-listener! :epoch cb (fn [_] nil))
      ;; cb is owed (never a live observer of id), so the claim reserves a mark.
      (state/open-silence-lineage! id)
      (state/claim-and-publish-delayed-silence!
        id cb (:generation (get (state/listeners-snapshot) cb)) 0 (fn [] nil))
      (is (pos? (vxgfnd285-total-marks)) "a terminal-silence mark is present")
      (state/reset-listeners!)
      (is (zero? (vxgfnd285-total-marks))
          "reset-listeners! clears the terminal-silence lineage, not just the registry"))))

;; ---- rf2-6ys5n / rf2-uhouu — the public receiver decision self-filters -----

(deftest epoch-silence-current-public-self-filter-cljs
  (testing "rf2-6ys5n + rf2-uhouu (CLJS peer of the JVM public-boundary suite) —
            the silence self-filter is implementable through the SUPPORTED public
            API on the CLJS host too, as ONE call. Reaches for NO private state
            (no `listeners-snapshot`): only `re-frame.core` public vars."
    ;; Boundary: a signal that names no live registration is never current.
    (is (false? (rf/epoch-silence-current?
                  {:cb-id ::never-cljs :frame :test/nowhere-cljs :observed-gen 1}))
        "an unregistered cb-id is never current")

    ;; Real emitted silence self-filters at the public boundary.
    (rf/make-frame {:id :test/short-lived-cljs})
    (rf/reg-event :seed6 (fn [{:keys [db]} _] {:db {:n 0}}))
    (let [recorded (atom [])]
      (rf/register-listener! :trace ::recorder6 (fn [ev] (swap! recorded conj ev)))
      (rf/register-listener! :epoch ::watcher6 (fn [_] nil))
      (rf/dispatch-sync [:seed6] {:frame :test/short-lived-cljs})
      (rf/destroy-frame! :test/short-lived-cljs)
      (let [tags (->> @recorded
                      (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                  (:operation %)))
                      first
                      :tags)]
        (is (some? tags) "a silence fired for the destroyed frame")
        (is (contains? tags :observed-gen) "the signal is generation-qualified")
        ;; Current registration: the live signal is accepted.
        (is (true? (rf/epoch-silence-current? tags))
            "the live signal names a current fact — APPLY")
        ;; Supersede (G→H) through the public verb; the same signal self-filters.
        (rf/register-listener! :epoch ::watcher6 (fn [_] nil))
        (is (false? (rf/epoch-silence-current? tags))
            "the superseded signal is no longer current — DISCARD")
        ;; An unregister-drop is the same kind of mutation.
        (rf/unregister-listener! :epoch ::watcher6)
        (is (false? (rf/epoch-silence-current? tags))
            "a dropped registration is never current"))
      (rf/unregister-listener! :trace ::recorder6))))

(deftest epoch-silence-current-supersedes-a-same-generation-rearm-cljs
  (testing "rf2-qg98y (CLJS peer) — a silence whose `:observed-gen` still matches
            can nonetheless be SUPERSEDED by a fresh delivery on the same
            registration, because a delivery mints no generation. Registration
            identity alone accepts it; the supported decision — which weighs
            observation continuum in the SAME operation (rf2-uhouu) — rejects it.
            Public API only, one call, no private state."
    (rf/register-listener! :epoch ::watcher7 (fn [_] nil))
    (rf/reg-event :seed7 (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/make-frame {:id :test/churn-cljs})
    (let [recorded (atom [])]
      (rf/register-listener! :trace ::recorder7 (fn [ev] (swap! recorded conj ev)))
      (rf/dispatch-sync [:seed7] {:frame :test/churn-cljs})
      (rf/destroy-frame! :test/churn-cljs)
      (let [tags (->> @recorded
                      (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                  (:operation %)))
                      first
                      :tags)]
        (is (some? tags) "the destroy emitted a silence")
        (is (true? (rf/epoch-silence-current? tags))
            "the destroy dropped the observation — the callback IS silent, ACCEPT")

        ;; A same-id SUCCESSOR frame re-arms the SAME registration — no
        ;; replacement, so no new generation. A consumer processing the trace
        ;; stream after this point must not still read the silence as current.
        (rf/make-frame {:id :test/churn-cljs})
        (rf/dispatch-sync [:seed7] {:frame :test/churn-cljs})
        (is (false? (rf/epoch-silence-current? tags))
            "the callback is receiving records from that frame again — REJECT,
             even though the carried generation still matches")
        ;; Frame-scoped: the re-arm on :test/churn-cljs must not suppress a
        ;; silence owed for a DIFFERENT frame. A cb-scoped decision would swallow
        ;; every silence a busy multi-frame tool listener is owed.
        (is (true? (rf/epoch-silence-current? (assoc tags :frame :test/other-cljs)))
            "the decision is (cb, frame)-scoped — a re-arm on one frame does not
             mask another frame's silence")
        (rf/destroy-frame! :test/churn-cljs))
      (rf/unregister-listener! :trace ::recorder7)
      (rf/unregister-listener! :epoch ::watcher7))))

;; ---- rf2-oh1y8 — nil-evidence cleanup across every owned store, CLJS host ---
;;
;; The JVM peer `destroy-cleans-exact-owner-stores-when-snapshot-evidence-is-nil`
;; (`re-frame.epoch-test`) directly seeds and asserts each of the FIVE exact-owner
;; stores `on-frame-destroyed!`'s cleanup-fn drops. `re-frame.epoch.state` is a
;; host-shared `.cljc`, but owner serialization differs by host (`locking` on JVM
;; vs synchronous direct execution on CLJS), and rf2-hclxos called for BOTH hosts.
;; These two synchronous CLJS fixtures close that gap:
;;
;;   1. the #5939 nil-evidence path drops all five stores AND fabricates nothing —
;;      re-nesting cleanup under the evidence guard fails causally;
;;   2. a stale predecessor A resuming with nil evidence CANNOT erase a claimed
;;      same-id B's stores (the compare-owned no-op). The equivalent JVM invariant
;;      lives in `frame-destroy-incarnation-jvm-test`.

(deftest destroy-cleans-exact-owner-stores-when-snapshot-evidence-is-nil-cljs
  (testing "rf2-oh1y8 (synchronous CLJS) — a throwing :epoch/snapshot-frame-
            destroyed hook yields nil terminal-evidence (#5939), but the destroyed
            incarnation's FIVE id-keyed epoch stores are STILL dropped, and the
            nil bundle publishes/fabricates nothing. Each store is seeded and
            asserted directly, so re-nesting cleanup under the evidence guard — or
            removing any single drop — fails exactly its assertion."
    (let [id            :rf2-oh1y8/nil-evidence
          cb            ::rf2-oh1y8-nil-evidence-cb
          render-key    ::rf2-oh1y8-nil-evidence-view
          records       (atom [])
          traces        (atom [])
          original-snap (late-bind/get-fn :epoch/snapshot-frame-destroyed)]
      (rf/make-frame {:id id})
      (rf/reg-event :rf2-oh1y8/seed (fn [_ _] {:db {:audit/seed :from-A}}))
      ;; cb OBSERVES A: fanning the settled record stamps its observation, so a
      ;; fabricating publish would have a real observer to (wrongly) silence.
      (rf/register-listener! :epoch cb (fn [r] (swap! records conj r)))
      (rf/register-listener! :trace ::rf2-oh1y8-nil-evidence-trace
        (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf2-oh1y8/seed] {:frame id})
      (reset! records [])                          ; drop the :seed :ok record
      (let [a-epoch-id (:epoch-id (first (rf/epoch-history id)))]
        ;; The cascade seeds history + last-settled + cb's observation. Seed the two
        ;; the synchronous cascade cannot: the harvested-empty capture buffer and
        ;; the render mount-attribution (no React commit).
        (state/buffer-event! id {:operation :rf.event/run-start
                                 :tags {:rf.trace/dispatch-id ::seed-dispatch}})
        (state/record-mount-epoch! id render-key a-epoch-id)
        (state/record-render-deps! id render-key ::a-sub)

        ;; PRE-DESTROY: all five exact-owner stores hold A's state.
        (is (contains? (set (state/cbs-observing-frame id)) cb)
            "observation stamp seeded — cb observed A")
        (is (= 1 (count (state/history-for id)))
            "history seeded — A settled one epoch")
        (is (seq (state/buffer-for id))
            "capture buffer seeded")
        (is (some? (state/last-settled-epoch-id id))
            "last-settled epoch seeded")
        (is (some? (state/mount-epoch-for id render-key))
            "mount attribution seeded")
        (try
          (late-bind/set-fn! :epoch/snapshot-frame-destroyed
            (fn [& args]
              (if (= id (first args))
                (throw (ex-info "snapshot blew" {:why :test}))
                (when original-snap (apply original-snap args)))))
          (rf/destroy-frame! id)

          ;; (1) All five id-keyed stores are dropped despite the nil bundle.
          (is (empty? (state/cbs-observing-frame id))
              "observation stamps dropped (drop-frame-observation!)")
          (is (= [] (state/history-for id))
              "history dropped (drop-frame-history!)")
          (is (empty? (state/buffer-for id))
              "capture buffer dropped (drop-frame-buffer!)")
          (is (nil? (state/last-settled-epoch-id id))
              "last-settled epoch dropped (drop-last-settled-epoch!)")
          (is (nil? (state/mount-epoch-for id render-key))
              "mount attribution dropped (drop-frame-mount-attribution!)")
          (is (nil? (state/render-deps-for id render-key))
              "mount attribution read-set dropped with the frame's entry")
          (is (= [] (rf/epoch-history id))
              "public epoch-history is [] even though the snapshot threw")
          (is (nil? (frame/frame-incarnation-token id))
              "A is fully destroyed — no live incarnation owns the id")

          ;; (2) #5939 NON-FABRICATION: the nil bundle publishes/opens nothing.
          (is (empty? (filter #(= :halted-destroy (:outcome %)) @records))
              "no :halted-destroy record is fabricated from the nil bundle")
          (is (empty? (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                  (:operation %))
                              @traces))
              "no silencing fires — the nil bundle owed none")
          (is (zero? (reduce + 0 (map count
                                      (vals (state/terminal-silence-marks-snapshot)))))
              "a nil bundle opens no deferred-silence window — no mark accreted")
          (finally
            (late-bind/set-fn! :epoch/snapshot-frame-destroyed original-snap)
            (when (frame/frame id) (rf/destroy-frame! id))
            (rf/unregister-listener! :epoch cb)
            (rf/unregister-listener! :trace ::rf2-oh1y8-nil-evidence-trace)))))))

(deftest stale-a-nil-evidence-cleanup-cannot-erase-claimed-same-id-b-cljs
  (testing "rf2-oh1y8 (synchronous CLJS) — a stale predecessor A resuming with NIL
            terminal-evidence runs its exact-owner cleanup, but a same-id successor
            B already CLAIMED the id-keyed stores. A LOSES the compare-owned
            comparison, so its cleanup no-ops and cannot erase ANY of B's five
            exact-owner stores. The equivalent JVM invariant lives in
            frame-destroy-incarnation-jvm-test."
    (let [id         :rf2-oh1y8-stale/frame
          cb         ::rf2-oh1y8-stale-cb
          render-key ::rf2-oh1y8-stale-view
          token-a    #js {}
          token-b    #js {}
          b-record   {:frame id :epoch-id 2 :outcome :ok :db-after {:audit/seed :from-B}}]
      (rf/register-listener! :epoch cb (fn [_] nil))
      (try
        ;; A claims + cb observes A (the state a live incarnation holds before its
        ;; nil-evidence destroy is deferred past a same-id successor).
        (state/claim-frame-owner! id token-a)
        (epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
        ;; Same-id B claims the id-keyed stores (dropping A's), then seeds ALL FIVE
        ;; of its own.
        (state/claim-frame-owner! id token-b)
        (epoch.listeners/notify-listeners! b-record)   ; cb re-observes under B
        (state/record! b-record)                       ; history
        (state/set-last-settled-epoch! id 2)           ; last-settled
        (state/buffer-event! id {:operation :rf.event/run-start
                                 :tags {:rf.trace/dispatch-id ::b-dispatch}}) ; buffer
        (state/record-mount-epoch! id render-key 2)    ; mount attribution
        (state/record-render-deps! id render-key ::b-sub)

        ;; PRE-RESUME: B's five stores are populated.
        (is (contains? (set (state/cbs-observing-frame id)) cb))
        (is (= [b-record] (state/history-for id)))
        (is (seq (state/buffer-for id)))
        (is (= 2 (state/last-settled-epoch-id id)))
        (is (= 2 (state/mount-epoch-for id render-key)))

        ;; Stale A resumes with NIL evidence: cleanup loses the comparison to B.
        (epoch.listeners/on-frame-destroyed! id token-a nil)

        ;; B's FIVE stores are byte-identical — A's stale cleanup no-op'd.
        (is (contains? (set (state/cbs-observing-frame id)) cb)
            "B's observation survives A's stale nil-evidence cleanup")
        (is (= [b-record] (state/history-for id))
            "B's history survives")
        (is (seq (state/buffer-for id))
            "B's capture buffer survives")
        (is (= 2 (state/last-settled-epoch-id id))
            "B's last-settled anchor survives")
        (is (= 2 (state/mount-epoch-for id render-key))
            "B's mount attribution survives")
        (is (= #{::b-sub} (state/render-deps-for id render-key))
            "B's mount read-set survives")
        (finally
          ;; B tears itself down (token-b owns → cleanup runs) and releases the
          ;; owner ledger, then unregister cb.
          (epoch.listeners/on-frame-destroyed! id token-b nil)
          (rf/unregister-listener! :epoch cb))))))

;; ---- rf2-4go8s — a NON-KEYWORD comparable listener id round-trips -----------

(deftest non-keyword-comparable-cb-id-round-trips-through-silencing-cljs
  (testing "rf2-4go8s (CLJS) — `register-epoch-listener!` accepts ANY comparable
            value as the listener id, not only keyword|string. A NON-KEYWORD id
            (a vector) registers, mints a generation, and is emitted VERBATIM as
            the silencing signal's `:cb-id` — raw-ID preservation — and the
            supported receiver decision works identically on it. This is the
            end-to-end proof the emitted `:cb-id` domain is the same comparable-ID
            domain the public register/query API accepts (Spec-Schemas
            EpochCbSilencedOnFrameDestroyTags :cb-id :any, widened from the stale
            keyword|string narrowing)."
    (let [cb-id [:my-app/epoch-log 7]]         ; a vector — NOT a keyword|string
      (is (false? (rf/epoch-silence-current?
                    {:cb-id cb-id :frame :test/non-kw-cljs :observed-gen 1}))
          "an unregistered non-keyword id names no live registration")
      (rf/make-frame {:id :test/non-kw-cljs})
      (rf/reg-event :seed-nonkw (fn [{:keys [db]} _] {:db {:n 0}}))
      (let [recorded (atom [])]
        (rf/register-listener! :trace ::recorder-nonkw (fn [ev] (swap! recorded conj ev)))
        (rf/register-listener! :epoch cb-id (fn [_] nil))
        ;; Observe the frame under the live generation, then destroy it.
        (rf/dispatch-sync [:seed-nonkw] {:frame :test/non-kw-cljs})
        (let [g-observed (get-in (state/listeners-snapshot) [cb-id :generation])]
          (is (some? g-observed)
              "the non-keyword id minted a generation on registration")
          (rf/destroy-frame! :test/non-kw-cljs)
          (let [tags (->> @recorded
                          (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                      (:operation %)))
                          first
                          :tags)]
            (is (some? tags) "a silence fired for the destroyed frame")
            ;; RAW-ID PRESERVATION: the emitted :cb-id is the vector VERBATIM.
            (is (= cb-id (:cb-id tags))
                "the emitted :cb-id is the raw non-keyword id, not narrowed/coerced")
            (is (vector? (:cb-id tags))
                "and it is genuinely a non-keyword comparable value")
            (is (= g-observed (:observed-gen tags))
                "the silence names the generation that observed the frame")
            ;; GENERATION-QUALIFIED SELF-FILTER on the non-keyword id, through
            ;; the ONE supported receiver decision (rf2-uhouu).
            (is (true? (rf/epoch-silence-current? tags))
                "live signal names the current registration — APPLY")
            (rf/register-listener! :epoch cb-id (fn [_] nil))  ; supersede G→H
            (is (false? (rf/epoch-silence-current? tags))
                "a superseded signal is no longer current — DISCARD")))
        (rf/unregister-listener! :trace ::recorder-nonkw)
        (rf/unregister-listener! :epoch cb-id)))))
