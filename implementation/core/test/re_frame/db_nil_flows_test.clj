(ns re-frame.db-nil-flows-test
  "A `{:db nil}` return is coerced to `{}` with one dev-only
  `:rf.warning/db-nil-coerced` whether or not the frame runs flows (Spec 002
  §The `:db` commit / no-op return family). The coercion happens before the
  flow pass reads the pending db, because the pass writes its outputs into
  the db it is handed and so turns a nil into a map that no longer looks like
  a wipe. A flow-running frame is the case that hid it; the frame running no
  flows is the control.

  The warning is dev-only, so its count sits in a
  `(when rf.interop/debug-enabled? …)` arm; the committed db holds in every
  build."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- nil-db-outcome
  "Dispatch a `{:db nil}` event on a fresh frame running `flows`; return the
  committed app-db and the number of `:rf.warning/db-nil-coerced` warnings."
  [flows]
  (rf/reg-flow :dnf/k {:inputs [[:n]] :output-path [:k]} (fn [n] (or n 0)))
  (rf/make-frame {:id :dnf/frame :flows flows})
  (rf/reg-event :dnf/seed (fn [_ _] {:db {:n 5 :other :kept}}))
  (rf/reg-event :dnf/nil-db (fn [_ _] {:db nil}))
  (rf/dispatch-sync [:dnf/seed] {:frame :dnf/frame})
  (let [warnings (atom 0)]
    (rf.trace.tooling/register-listener! ::warnings
      (fn [ev] (when (= :rf.warning/db-nil-coerced (:operation ev))
                 (swap! warnings inc))))
    (try
      (rf/dispatch-sync [:dnf/nil-db] {:frame :dnf/frame})
      {:db       (rf/app-db-value :dnf/frame)
       :warnings @warnings}
      (finally
        (rf.trace.tooling/unregister-listener! ::warnings)))))

(deftest a-nil-db-on-a-flow-running-frame-warns-once
  (let [{:keys [db warnings]} (nil-db-outcome [:dnf/k])]
    (is (= {:k 0} db) "the flow ran over the coerced {}")
    (when rf.interop/debug-enabled?
      (is (= 1 warnings)))))

(deftest a-nil-db-on-a-frame-running-no-flows-warns-once
  (let [{:keys [db warnings]} (nil-db-outcome [])]
    (is (= {} db))
    (when rf.interop/debug-enabled?
      (is (= 1 warnings)))))
