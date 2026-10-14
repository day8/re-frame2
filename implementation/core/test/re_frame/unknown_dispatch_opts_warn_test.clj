(ns re-frame.unknown-dispatch-opts-warn-test
  "Emit `:rf.warning/unknown-dispatch-opt` when a `dispatch` / `dispatch-sync`
  opts map carries a key outside `re-frame.router.diagnostics/known-dispatch-opts`.

  `build-envelope` reads only the known keys, so a typo (`:fram` for `:frame`)
  would otherwise change nothing and give no signal — a quietness the
  no-silent-swallow principle forbids.

  The warning is OBSERVATIONAL (`:recovery :no-recovery`): the dispatch
  proceeds unchanged. It is emitted in `build-envelope` before frame
  resolution, and the fixture binds a `:rf/default` scope so the ambient
  dispatches complete after it fires.

  ## Posture split

  The warning is dev-only (gated on `rf.interop/debug-enabled?`), so every
  assertion about it sits in a `(when rf.interop/debug-enabled? …)` arm. What
  survives the gate is the observational claim: each case lands a marker in
  app-db and asserts it arrived, in both postures."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

(deftest fires-on-unknown-opts-key
  (testing "unrecognised opts keys emit exactly ONE warning naming them all and
            the known set, the legitimate :origin beside them is not flagged,
            and the dispatch still lands"
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db (assoc db :landed :app/noop)}))
    (let [recorded (atom [])]
      (rf/register-listener! :trace ::unknown (fn [ev] (swap! recorded conj ev)))
      (rf/dispatch-sync [:app/noop] {:fram :rf/default :srce :ui :origin :app})
      (is (= :app/noop (:landed (rf/app-db-value :rf/default))))
      (when rf.interop/debug-enabled?
        (let [warns (filterv #(and (= :warning (:op-type %))
                                   (= :rf.warning/unknown-dispatch-opt (:operation %)))
                             @recorded)
              w     (first warns)
              t     (:tags w)]
          (is (= [1 [:app/noop] :app/noop #{:fram :srce} true true :no-recovery]
                 [(count warns) (:event t) (:event-id t) (set (:unknown-keys t))
                  (contains? (set (:known-keys t)) :frame)
                  ;; the reason lists the known keys, the likely intended
                  ;; :frame among them
                  (boolean (re-find #":frame" (:reason t)))
                  (:recovery w)])))))))
