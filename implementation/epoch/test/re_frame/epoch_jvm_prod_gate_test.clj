(ns re-frame.epoch-jvm-prod-gate-test
  "READ THIS FIRST. Despite the namespace's name, this suite is
  NOT THE LOAD-TIME GATE. Every test below rebinds
  `re-frame.interop/debug-enabled?` with `with-redefs`, which happens after the
  framework has loaded; the gate itself is read ONCE at `re-frame.interop` load
  time from `-Dre-frame.debug` / `RE_FRAME_DEBUG`. What is pinned here is that
  the epoch surfaces honour a REBOUND flag: with it false the ring stays empty
  and `restore-epoch!`, `replay-epoch!` and `replace-frame-state!` refuse.

  The load-time posture is the epoch production-gate lane's job
  (`scripts/test-epoch-prod-gate.sh`, the `:prod-gate` alias in
  `implementation/epoch/deps.edn`, pinned by
  `re-frame.epoch-prod-gate-lane-pin-test`), and that lane runs every deftest
  below again with the property genuinely on the JVM command line. A rebind
  reaches epoch's own gated branches — each is a runtime
  `(when interop/debug-enabled? …)` Var deref — but not what the framework
  decided while it LOADED; that half is what the lane adds.
  `re-frame.prod-gate-naming-drift-test` requires a file named like this one to
  reach the gate or disclaim it; this file disclaims it, in the sentence at the
  top.

  Every absence below is paired with a WITNESS that the dispatch it reasons
  about happened — the handler's app-db write, read back through `app-db-of` —
  because an empty ring is also what a dispatch that never ran leaves behind.
  Do not delete a witness to \"simplify\" a test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Side-effect require: machines publishes the late-bind hook
            ;; (see epoch_test.clj for the same dance).
            [re-frame.machines]))

;; The `:init-fn` runs OUTSIDE each test's `with-redefs`, so config lands at
;; the normal gate value.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

(defn- app-db-of [frame-id]
  (:rf.db/app (rf/frame-state-value frame-id)))

(deftest epoch-history-inert-when-debug-disabled
  (testing "when the JVM debug gate reads false, the
            per-frame epoch ring stays empty regardless of how many
            events drain. No `:db-before` / `:db-after` /
            `:trace-events` payloads land in heap memory, so no
            tokens / PII / secrets are retained in SSR process
            memory — and, since a listener is fed only by the same commit
            that appends to this ring, no epoch listener sees one either."
    (with-redefs [rf.interop/debug-enabled? false]
      (rf/reg-event :prod-gate.epoch/inc
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod-gate.epoch/inc])
      (rf/dispatch-sync [:prod-gate.epoch/inc])
      (rf/dispatch-sync [:prod-gate.epoch/inc])
      (is (= 3 (:n (app-db-of :rf/default)))
          "WITNESS: all three dispatches ran their handler and committed —
           so the empty ring below is elision, not a dead dispatch loop")
      (is (empty? (rf.epoch/epoch-history :rf/default))
          "epoch ring is empty under disabled debug gate"))))

(deftest restore-epoch-refuses-when-debug-disabled
  (testing "`restore-epoch!` MUST refuse to operate
            when the JVM debug gate is off. The state-rewrite admin
            surface is dev-only; SSR production processes do NOT
            give arbitrary in-process code the ability to mutate
            `app-db` out of band."
    (rf/reg-event :prod-gate.epoch/restore-probe
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    ;; The target is a REAL retained `:ok` epoch, recorded with the gate
    ;; rebound open so the production-gate lane records it too. An unknown id
    ;; is refused whatever the gate reads, so only a restorable target leaves
    ;; the gate as the one thing refusing.
    (let [target-id (with-redefs [rf.interop/debug-enabled? true]
                      (rf/dispatch-sync [:prod-gate.epoch/restore-probe])
                      (rf/dispatch-sync [:prod-gate.epoch/restore-probe])
                      (:epoch-id (first (rf/epoch-history :rf/default))))]
      (with-redefs [rf.interop/debug-enabled? false]
        (is (false? (rf/restore-epoch! :rf/default target-id))
            "restore-epoch! returns false (refuses to operate)")
        (is (= 2 (:n (app-db-of :rf/default)))
            "app-db is not rewound to the target epoch"))
      (with-redefs [rf.interop/debug-enabled? true]
        (is (true? (rf/restore-epoch! :rf/default target-id))
            "CONTROL: with the gate open the same restore succeeds, so the
             refusal above was the gate's alone")))))

(deftest replay-epoch-refuses-when-debug-disabled
  (testing "`replay-epoch!` is gated exactly like `restore-epoch!`
            — under the disabled gate it returns `false`, resolves no record
            and dispatches nothing."
    (rf/reg-event :prod-gate.epoch/replay-probe
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    ;; Under the dev gate the probe's record is retained, so the refusal is
    ;; exercised against a RETAINED id; under the production-gate lane the ring
    ;; stays empty and the id falls back to an unknown one — both must refuse.
    (rf/dispatch-sync [:prod-gate.epoch/replay-probe])
    (let [recorded-id (or (:epoch-id (last (rf/epoch-history :rf/default)))
                          :some-epoch-id)]
      (with-redefs [rf.interop/debug-enabled? false]
        (is (false? (rf/replay-epoch! :rf/default recorded-id))
            "replay-epoch! returns false (refuses to operate)"))
      (is (= 1 (:n (app-db-of :rf/default)))
          "WITNESS: the probe ran exactly once — it did run, and the refused
           replay did not run it again"))))

(deftest replace-app-db-refuses-when-debug-disabled
  (testing "`replace-frame-state!` must refuse to operate
            when the JVM debug gate is off. Same admin-surface
            concern as `restore-epoch!` — pair-tool writes (Tool-Pair
            §Pair-tool writes) are a dev-only surface."
    (with-redefs [rf.interop/debug-enabled? false]
      (is (false? (rf/replace-frame-state! :rf/default {:rf.db/app {:any "db"}}))
          "replace-frame-state! returns false (refuses to operate)"))))

(deftest ^:requires-debug epoch-still-records-with-default-gate
  (testing "dev parity: with the gate at its default reading the ring fills"
    (rf/reg-event :prod-gate.epoch/dev-inc
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:prod-gate.epoch/dev-inc])
    (is (seq (rf.epoch/epoch-history :rf/default)))))
