(ns re-frame.reply-stale-authority-elision-prod-test
  "Per rf2-j538f7.14 — a stale async completion NEVER app-delivers, and that is
  a CORRECTNESS boundary, so it MUST survive production elision (`:advanced` +
  `goog.DEBUG=false`). `re-frame.reply/suppress` returns `:deliver? false` for
  EVERY target with NO dev-gated assertion or `goog.DEBUG` branch — no
  debug-only elision can create or remove a delivery decision — so a superseded
  completion is non-delivering under an advanced-compiled bundle exactly as
  under dev. (The former per-target stale-delivery capability / authority is
  deleted; there is no issuer for app code to reach, at any optimization level.)

  Naming convention: files ending in `-elision-prod-test.cljs` are picked up
  ONLY by the `:browser-test-prod-elision` build (`:advanced` +
  `{goog.DEBUG false}`), via the shared `re-frame.prod-elision-runner`. Pure
  substrate — no runtime fixture needed."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.reply :as rf.reply]))

(def ^:private carried {:g 1})
(def ^:private current {:g 2})

(deftest stale-suppression-never-delivers-under-prod
  (testing "rf2-j538f7.14: under `:advanced` + `goog.DEBUG=false`, `suppress` is
            universally non-delivering — a plain app target, one spelling the
            removed :dispatch-stale? flag, and one forging a truthy authority
            datum all yield :deliver? false; a stale envelope never reaches app
            state, with no dev-gated code to elide"
    (doseq [target [[:app/replied]
                    {:event [:app/replied] :dispatch-stale? true}
                    {:event [:app/replied] :dispatch-stale? true :re-frame.reply/stale-authority true}]]
      (let [{:keys [deliver? reply]} (rf.reply/suppress target carried current)]
        (is (false? deliver?)
            "the stale outcome is non-delivering under advanced compilation")
        (is (= :stale (:status reply)) "still a well-formed stale reply")
        (is (not (contains? reply :value)) "no :value can mutate app state")))))

(deftest observer-may-self-dispatch-under-prod
  (testing "rf2-j538f7.14: a framework/tool observer can still self-dispatch the
            stale :reply on its OWN authority under prod — observation is
            ordinary `complete` + dispatch, structurally separate from the
            (universally non-delivering) suppress boundary"
    (let [{:keys [reply]} (rf.reply/suppress [:app/replied] carried current)]
      (is (= [:tool/observed reply] (rf.reply/complete [:tool/observed] reply))
          "the observer builds the completed event from the stale reply itself"))))

(deftest durable-target-strips-ephemeral-under-prod
  (testing "rf2-j538f7.14: durable projection strips the ephemeral ::post slot
            under prod — a mapped target becomes data-only when persisted"
    (let [mapped (rf.reply/map-completed-event (fn [e] e) [:x 1])]
      (is (false? (rf.reply/data-only-target? mapped)))
      (is (true? (rf.reply/data-only-target? (rf.reply/durable-target mapped)))
          "durable-target strips ::post under advanced compilation"))))
