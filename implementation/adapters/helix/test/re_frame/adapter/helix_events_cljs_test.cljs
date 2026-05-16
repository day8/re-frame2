(ns re-frame.adapter.helix-events-cljs-test
  "Adapter-parity port of `re-frame.events-cljs-test` (rf2-bbea) to the
  Helix adapter (rf2-ta4b5).

  The contract: `reg-event-*` warns when `:interceptors` is mistakenly
  placed inside the metadata map. The warning is emitted via the trace
  channel; the channel is wired by `install-adapter!`. This port exists
  so the warning is observed to fire under a Helix-installed adapter —
  it pins that the registrar + trace tier compose with the Helix
  adapter's late-bind hook stack the same way they do with Reagent's.

  Parallel to:
    - implementation/adapters/reagent/test/re_frame/events_cljs_test.cljs

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

(defn- collect-warnings
  "Attach a listener that records `:rf.warning/interceptors-in-metadata-map`
  events and return the recording atom."
  [k]
  (let [a (atom [])]
    (trace-tooling/register-trace-cb! k
      (fn [ev]
        (when (and (= :warning (:op-type ev))
                   (= :rf.warning/interceptors-in-metadata-map (:operation ev)))
          (swap! a conj ev))))
    a))

(def ^:private noop-icpt
  {:id :test/noop :before identity :after identity})

(deftest reg-event-db-warns-on-meta-interceptors-helix
  (testing "metadata-map :interceptors triggers :rf.warning/interceptors-in-metadata-map under the Helix adapter"
    (let [warns (collect-warnings ::db-warn)]
      (rf/reg-event-db :test.bbea.helix/db-bad
        {:doc "Wrongly-shaped." :interceptors [noop-icpt]}
        (fn [db _] db))
      (trace-tooling/remove-trace-cb! ::db-warn)
      (is (= 1 (count @warns)))
      (let [t (:tags (first @warns))]
        (is (= "reg-event-db" (:reg-fn t)))
        (is (= :test.bbea.helix/db-bad (:id t)))))))

(deftest reg-event-fx-warns-on-meta-interceptors-helix
  (let [warns (collect-warnings ::fx-warn)]
    (rf/reg-event-fx :test.bbea.helix/fx-bad
      {:interceptors [noop-icpt]}
      (fn [_ _] {:db {}}))
    (trace-tooling/remove-trace-cb! ::fx-warn)
    (is (= 1 (count @warns)))
    (is (= "reg-event-fx" (:reg-fn (:tags (first @warns)))))))

(deftest reg-event-ctx-warns-on-meta-interceptors-helix
  (let [warns (collect-warnings ::ctx-warn)]
    (rf/reg-event-ctx :test.bbea.helix/ctx-bad
      {:interceptors [noop-icpt]}
      (fn [ctx] ctx))
    (trace-tooling/remove-trace-cb! ::ctx-warn)
    (is (= 1 (count @warns)))
    (is (= "reg-event-ctx" (:reg-fn (:tags (first @warns)))))))

(deftest correct-positional-form-stays-silent-helix
  (testing "interceptors in the positional slot do NOT warn under Helix"
    (let [warns (collect-warnings ::quiet)]
      (rf/reg-event-db :test.bbea.helix/quiet-1
        [noop-icpt]
        (fn [db _] db))
      (rf/reg-event-db :test.bbea.helix/quiet-2
        {:doc "metadata only"}
        [noop-icpt]
        (fn [db _] db))
      (rf/reg-event-db :test.bbea.helix/quiet-3
        {:doc "metadata only, no positional interceptors"}
        (fn [db _] db))
      (trace-tooling/remove-trace-cb! ::quiet)
      (is (zero? (count @warns))))))
