(ns day8.re-frame2-xray.views.resizable-table-persistence-cljs-test
  "The column-widths persistence rows that need no real localStorage: the
  `->edn` / `<-edn` codec, the tick reducer's clamp, and the
  pre-registration `hydrate!` short-circuit. The storage round-trips —
  `custom-storage-key-isolates-per-instance`,
  `resize-pair-commit-persists-current-slot`,
  `reset-clears-table-and-persists` and `hydrate-lifts-persisted-widths` —
  live in `resizable-table-persistence-dom-cljs-test`, because only a
  `-dom-cljs-test` namespace reaches the lane with a `window.localStorage`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture))

(deftest edn-codec-round-trips-and-sanitises
  (let [widths {:rf.xray.epoch/subscriptions          {:sub 220 :inputs 180 :value 240}
                :rf.xray.epoch/subscriptions-disposed {:disposed 200}}]
    (doseq [[label expected encoded]
            [["round-trip preserves the {table-id {col-id px}} shape"
              widths (rt/->edn widths)]
             ["a non-map parsed value collapses to the default"
              {} "[1 2 3]"]
             [(str "a corrupted entry below the min-col floor (24px) is clamped "
                   "on read, so a stale persisted value can't sneak past the "
                   "resolver")
              {:t1 {:a 24}} (pr-str {:t1 {:a 5}})]
             [(str "defence-in-depth: a non-number width drops out rather than "
                   "poisoning the slot")
              {:t1 {:a 100}} (pr-str {:t1 {:a 100 :b "oops"}})]]]
      (is (= expected (rt/<-edn encoded)) label))))

(deftest resize-pair-tick-clamps-sub-floor-width
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray.column-widths/resize-pair-tick
                       :rf.xray.epoch/subscriptions
                       :sub 5 :inputs 300])
    (is (= {:sub 24 :inputs 300}
           @(rf/subscribe [:rf.xray.column-widths/for-table
                           :rf.xray.epoch/subscriptions]))
        "sub clamped to the 24px floor; inputs verbatim")))

(deftest hydrate-is-no-op-pre-frame-registration
  (testing "hydrate! short-circuits when :rf/xray is not yet registered
            (the preload-time call from registry's install! fan-out lands
            here): it dispatches nothing, so no error is emitted"
    ;; A stored map puts the frame check in charge; an empty load would
    ;; stop earlier. Unguarded, the dispatch into the unregistered frame is
    ;; refused with `:rf.error/frame-destroyed` and dropped rather than
    ;; thrown, so the error channel is what this row reads.
    (let [errors (atom [])]
      (rf.error-emit/register-error-listener!
        ::pre-registration-observer #(swap! errors conj %))
      (try
        (with-redefs [rt/load (constantly {:t1 {:a 100}})]
          (rt/hydrate!))
        (is (empty? @errors))
        (finally
          (rf.error-emit/unregister-error-listener! ::pre-registration-observer))))))
