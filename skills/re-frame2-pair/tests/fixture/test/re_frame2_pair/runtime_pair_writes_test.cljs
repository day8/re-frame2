(ns re-frame2-pair.runtime-pair-writes-test
  "The Pair write gestures report what actually happened: `app-db-reset!`
  taps its write's outcome, and a queued dispatch in an ambiguous session
  refuses with the frames to choose from."
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame2-pair.runtime :as rt]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- reset-with-taps
  "`app-db-reset!`'s result for `v` on a fresh frame, beside the
  `:app-db/reset` taps it emitted, delivered synchronously."
  [v]
  (let [frame-id :pair-writes/main
        taps     (atom [])
        tap-fn   #(when (= :app-db/reset (:re-frame2-pair/op %)) (swap! taps conj %))]
    (rf/make-frame {:id frame-id})
    (add-tap tap-fn)
    (try
      (binding [*exec-tap-fn* (fn [f] (f) true)]
        [(rt/app-db-reset! v frame-id) @taps])
      (finally (remove-tap tap-fn)))))

(deftest a-landed-reset-is-tapped-as-landed
  (let [[result taps] (reset-with-taps {:n 1})]
    (is (true? (:ok? result)))
    (is (= [[true nil]] (mapv (juxt :ok? :reason) taps)))))

(deftest a-refused-reset-is-tapped-as-refused
  ;; The human's tap consumer must not show a change that never happened.
  (with-redefs [rf.epoch/replace-frame-state!
                (fn [f _] {:ok? false :reason :rf.epoch/replace-schema-mismatch :frame f})]
    (let [[result taps] (reset-with-taps {:n 1})]
      (is (= :rf.epoch/replace-schema-mismatch (:reason result)))
      (is (= [[false :rf.epoch/replace-schema-mismatch]] (mapv (juxt :ok? :reason) taps))))))

(deftest an-unscoped-queued-dispatch-in-an-ambiguous-session-names-the-frames
  (rf/make-frame {:id :pair-writes/a})
  (rf/make-frame {:id :pair-writes/b})
  (let [data (try (rt/pair-dispatch! [:pair-writes/noop]) nil
                  (catch :default e (ex-data e)))]
    (is (= [:ambiguous-frame :dispatch] ((juxt :reason :operation) data)))
    (is (every? (set (:available-frames data)) [:pair-writes/a :pair-writes/b])
        "the refusal lists the frames to choose from")))
