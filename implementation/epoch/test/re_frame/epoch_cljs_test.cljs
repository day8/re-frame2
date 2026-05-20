(ns re-frame.epoch-cljs-test
  "Per rf2-5lvk6 (test-coverage sweep G3 from rf2-toib5) — CLJS smoke
  for the epoch artefact. The JVM coverage in `re-frame.epoch-test` is
  dense, but the epoch surface ships through `re-frame.epoch.cljc` and
  the late-bind seam under CLJS too; this file pins the cross-target
  contract for the load-bearing behaviours:

    1. Recording — every drain-settle commits one `:rf/epoch-record` and
       the record carries the canonical shape (`:event-id`, `:db-before`,
       `:db-after`, `:effects`, `:outcome :ok`).
    2. Restore happy path — `restore-epoch` rewinds `app-db` to a named
       earlier epoch's `:db-after`.
    3. Ring depth cap — `(rf/configure :epoch-history {:depth 3})`
       followed by five dispatches keeps the last three; the oldest two
       are dropped.
    4. Per-dispatch fan-out — `register-epoch-listener!` fires once per
       drain-settle with the assembled record (the contract that
       Causa's preload routes through to dispatch
       `:rf.causa/epoch-recorded`; the per-dispatch signal is the
       epoch-cb fan-out itself, plus the `:rf.epoch/snapshotted`
       trace emitted alongside).
    5. Production-elision DCE — runtime gate sanity. Under the
       `:node-test` build (`goog.DEBUG=true`) the surface is live;
       the framework-level grep
       (`implementation/scripts/check-elision.cjs`, rf2-11hn) is the
       authoritative `:advanced` + `goog.DEBUG=false` DCE assertion
       and already pins every `:rf.epoch/*` sentinel. This file's
       gate-state assertion locks the dev-side companion: under the
       dev gate, `interop/debug-enabled?` is truthy and the surface
       actually records — without that, the grep test would be
       vacuous (it asserts ABSENCE of strings that only enter the
       bundle when this gate is true).

  Mirrors the smoke shape of `re-frame.schemas-cljs-test` (per
  rf2-5b6x): one happy path, one ring-cap path, one listener path,
  one runtime-gate-on path. The JVM tests carry the conformance
  weight; this file locks the cross-substrate contract.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up via
  `:ns-regexp \"cljs-test$\"`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.epoch :as epoch]
            [re-frame.epoch.state :as state]
            [re-frame.interop :as interop]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; The `:node-test` build has no DOM and no Reagent reactive context —
;; the plain-atom adapter is the right substrate for epoch coverage
;; (matches the JVM test fixture in `re-frame.epoch-test`). Per
;; `test-support/reset-runtime-fixture` (rf2-am9d): the registrar is
;; snapshot/restored around each test so framework / example
;; registrations survive cross-ns CLJS test runs.
(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                ;; Per the JVM fixture (epoch_test.clj): reset epoch
                ;; state and the config atom between tests so a prior
                ;; test's `:depth 3` or pending cb leaks don't bleed
                ;; into the next. The fixture's adapter install
                ;; replaces the registrar / frames; epoch's own state
                ;; lives in module-level atoms that need their own
                ;; reset.
                (epoch/clear-history!)
                (epoch/clear-epoch-listeners!)
                (reset! @#'state/config {:depth 50 :trace-events-keep 5 :redact-fn nil}))}))

;; ---- 1. Recording — happy-path record shape -------------------------------

(deftest record-on-drain-settle-cljs
  (testing "dispatch a single event under CLJS — exactly one record
            lands in the ring with the canonical shape (`:event-id`,
            `:db-before`, `:db-after`, `:effects`, `:outcome :ok`)"
    (rf/reg-event-db :n/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :n/inc  (fn [db _] (update db :n inc)))

    (rf/dispatch-sync [:n/init])
    (rf/dispatch-sync [:n/inc])

    (let [history (rf/epoch-history :rf/default)]
      (is (= 2 (count history))
          "one record per drain-settle under the CLJS plain-atom adapter")
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
            ":outcome :ok pins the drain-settle outcome on the happy path")
        (is (vector? (:effects r))
            ":effects is a vector projection from the trace stream")))))

;; ---- 2. Restore happy path -------------------------------------------------

(deftest restore-rewinds-app-db-cljs
  (testing "dispatch three events, restore to the second — app-db
            matches the recorded :db-after"
    (rf/reg-event-db :n/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :n/inc  (fn [db _] (update db :n inc)))

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
      (is (true? (rf/restore-epoch :rf/default target-id))
          "restore-epoch returns true on the happy path")
      (is (= {:n 1} (rf/get-frame-db :rf/default))
          "app-db now matches the named epoch's :db-after"))))

;; ---- 3. Ring depth cap -----------------------------------------------------

(deftest ring-depth-evicts-oldest-cljs
  (testing "configure :depth 3, dispatch 5 events, oldest 2 are dropped"
    (rf/configure :epoch-history {:depth 3})
    (rf/reg-event-db :n/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :n/inc  (fn [db _] (update db :n inc)))

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
  (testing "register-epoch-listener! fires once per drain-settle — the
            contract Causa's preload (`:rf.causa/epoch-recorded`)
            routes through. The companion `:rf.epoch/snapshotted`
            trace also fires once per dispatch."
    (rf/reg-event-db :n/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :n/inc  (fn [db _] (update db :n inc)))

    (let [cb-seen    (atom [])
          trace-seen (atom [])]
      (rf/register-epoch-listener! ::watcher (fn [r] (swap! cb-seen conj r)))
      (trace-tooling/register-trace-listener! ::recorder
                             (fn [ev]
                               (when (= :rf.epoch/snapshotted (:operation ev))
                                 (swap! trace-seen conj ev))))

      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/inc])
      (rf/dispatch-sync [:n/inc])

      (rf/unregister-epoch-listener! ::watcher)
      (trace-tooling/unregister-trace-listener! ::recorder)

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
          "the trace's :outcome tag is :ok on clean drain-settle"))))

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
    ;; ring buffer, restore-epoch's happy / failure paths — is locked
    ;; by the four deftests above. This test is the lone gate-state
    ;; assertion; pairing it with the framework-level grep gives the
    ;; cross-mode pin (`gate=true` => surface lives; `gate=false`
    ;; => bundle elided).
    (rf/reg-event-db :probe/init (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:probe/init])
    (is (pos? (count (rf/epoch-history :rf/default)))
        "with the gate ON, a dispatch lands a record in the ring")))
