(ns re-frame.unknown-dispatch-opts-warn-test
  "Per rf2-jbzhj — emit `:rf.warning/unknown-dispatch-opt` when a
  `dispatch` / `dispatch-sync` opts map carries a key outside the
  recognised set (`re-frame.router.diagnostics/known-dispatch-opts`).

  The runtime reads only the known opts keys in `build-envelope`; any
  other key is silently swallowed, so a typo (`:fram` for `:frame`,
  `:src` for `:source`) changes nothing and gives no signal — the
  no-silent-swallow principle (rf2-3nbl5.1) forbids that quietness.

  The warning is observational: the dispatch proceeds unchanged
  (`:recovery :no-recovery`). It is dev-only — gated on
  `interop/debug-enabled?`, so production (`:advanced` +
  `goog.DEBUG=false`) DCEs the whole surface (the elision probe verifies
  that separately).

  EP-0002 (rf2-9wa0lf): the unknown-opt warning is emitted in
  `build-envelope` BEFORE frame resolution, so a `:fram`-for-`:frame`
  typo still surfaces its specific diagnostic even though the dispatch
  then fails for want of a frame. The fixture establishes a `:rf/default`
  frame SCOPE (the carried-invariant contract — no synthesised floor) so
  the ambient `dispatch-sync` calls below complete after the warning
  fires; the typo key carries no frame, but the scope does."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.router.diagnostics :as diag]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces!
  [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- unknown-opt-warnings
  [recorded]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= :rf.warning/unknown-dispatch-opt (:operation ev))))
           @recorded))

;; ---- tests ----------------------------------------------------------------

(deftest fires-on-unknown-opts-key
  (testing "an unrecognised opts key emits exactly one warning naming the bad key + the known set"
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
    (let [recorded (record-traces! ::unknown)]
      ;; `:fram` is the classic typo for `:frame` — silently swallowed
      ;; before this fix.
      (rf/dispatch-sync [:app/noop] {:fram :rf/default})

      (let [warns (unknown-opt-warnings recorded)]
        (is (= 1 (count warns))
            (str "expected exactly one unknown-opt warning, got "
                 (count warns)))
        (let [w (first warns)
              t (:tags w)]
          (is (= [:app/noop] (:event t)))
          (is (= :app/noop (:event-id t)))
          (is (= [:fram] (:unknown-keys t))
              "the bad key is named verbatim")
          (is (contains? (set (:known-keys t)) :frame)
              "the warning enumerates the known opts set")
          (is (string? (:reason t)))
          (is (re-find #":fram" (:reason t))
              "reason names the offending key")
          (is (re-find #":frame" (:reason t))
              "reason lists the known keys (incl. the likely intended :frame)")
          (is (= :no-recovery (:recovery w))))))))

(deftest names-every-unknown-key-in-one-warning
  (testing "multiple unknown keys → a single warning listing them all"
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
    (let [recorded (record-traces! ::multi)]
      (rf/dispatch-sync [:app/noop] {:fram :rf/default :srce :ui :origin :app})

      (let [warns (unknown-opt-warnings recorded)]
        (is (= 1 (count warns)) "one warning for the whole call, not one per bad key")
        (let [t (:tags (first warns))]
          (is (= #{:fram :srce} (set (:unknown-keys t)))
              "both typos named; the legitimate :origin opt is NOT flagged"))))))

(deftest no-warning-for-known-opts
  (testing "a known opts key (:frame) → no warning"
    (rf/reg-frame :game {:doc "non-default frame target"})
    (rf/reg-event :game/tick {:frame :game} (fn [{:keys [db]} _] {:db db}))
    (let [recorded (record-traces! ::known)]
      (rf/dispatch-sync [:game/tick] {:frame :game})
      (is (empty? (unknown-opt-warnings recorded))
          ":frame is a recognised opt — no warning"))))

(deftest no-warning-for-source-and-origin-opts
  (testing "the full known set is accepted without warning"
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
    (let [recorded (record-traces! ::full-known)]
      (rf/dispatch-sync [:app/noop]
                        {:source :ui :origin :app :trace-id "t1"
                         :fx-overrides {} :interceptor-overrides {}
                         :source-detail {:ms 100}})
      (is (empty? (unknown-opt-warnings recorded))
          "every key here is in known-dispatch-opts"))))

(deftest no-warning-for-empty-opts
  (testing "the no-opts dispatch path emits no warning"
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
    (let [recorded (record-traces! ::empty)]
      (rf/dispatch-sync [:app/noop])
      (is (empty? (unknown-opt-warnings recorded))
          "empty opts map has no unknown keys"))))

(deftest async-dispatch-path-also-warns
  (testing "the queued `dispatch` path (not just dispatch-sync) warns on unknown keys"
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
    (let [recorded (record-traces! ::async)]
      ;; `dispatch` enqueues; the JVM drain runs it synchronously via the
      ;; router, but `build-envelope` (where the check lives) runs at
      ;; enqueue time regardless.
      (rf/dispatch [:app/noop] {:fram :rf/default})
      (is (= 1 (count (unknown-opt-warnings recorded)))
          "build-envelope is the single chokepoint — both dispatch paths funnel through it"))))

(deftest no-warning-for-initial-events-step-index
  (testing ":initial-events setup dispatches carry :step-index (a build-envelope-read internal opt) — no false unknown-opt warning"
    (rf/reg-event :seed/set (fn [{:keys [db]} [_ v]] {:db (assoc db :seed v)}))
    (let [recorded (record-traces! ::init-step)]
      ;; `reg-frame` with `:initial-events` runs each setup step through
      ;; `frame.cljc`'s `run-setup-events!`, which stamps `:step-index` into the
      ;; dispatch opts. `build-envelope` READS `:step-index` (carrying it onto
      ;; the dispatched trace as `:rf.frame/init-step-index`), so the key belongs
      ;; in `known-dispatch-opts` and must NOT trip the unknown-dispatch-opt
      ;; warning. Before the fix, each of the two setup steps emitted one false
      ;; `:silently ignored` warning.
      (rf/reg-frame :seeded/frame {:initial-events [[:seed/set 1] [:seed/set 2]]})
      (is (empty? (unknown-opt-warnings recorded))
          ":step-index is an honoured build-envelope opt, not an unknown key"))))

(deftest known-set-matches-build-envelope-reads
  (testing "the published known-opts set documents exactly the keys build-envelope honours"
    ;; A guard against drift: if build-envelope grows/drops an opt the
    ;; set must move with it. This is the canonical enumeration callers
    ;; (and the warning message) rely on. `:rf.cofx/mint-policy` is the
    ;; EP-0017 §6 / slice-B.8 per-call cofx mint policy (rf2-5spzo7) —
    ;; build-envelope reads it and threads it through to the satisfaction
    ;; step's mint-policy resolution, so it belongs in the honoured set.
    ;; (The `:realm` opt was removed under the realm-substrate collapse —
    ;; rf2-9w37t2 / rf2-afdlyr — every dispatch is the single default realm,
    ;; so build-envelope no longer reads a realm dimension.)
    (is (= #{:frame :fx-overrides :interceptor-overrides :trace-id :source
             :source-detail :origin :rf.cofx :rf.cofx/mint-policy
             :rf.trace/call-site :rf.machine/internal? :step-index}
           diag/known-dispatch-opts))))
