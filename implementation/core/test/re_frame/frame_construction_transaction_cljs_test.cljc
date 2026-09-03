(ns re-frame.frame-construction-transaction-cljs-test
  "rf2-vxgfnd.197 — a frame id has one construction transaction from adapter
  allocation through setup and final publication.

  The custom-adapter fixtures synchronously re-enter construction for the SAME
  id from each opaque allocation callback. The nested public call must lose
  promptly with `:rf.error/frame-construction-in-progress`; it may not install a
  frame that the outer callback later overwrites. The setup fixture exercises
  the same admission rule after the provisional row exists: an initial event's
  same-id make loses, then the outer setup failure removes only its own
  provisional construction. All barriers are synchronous; there are no sleeps.

  `.cljc` plus the `-cljs-test` suffix runs these ownership proofs on both JVM
  and CLJS."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- err-id [thunk]
  (try
    (thunk)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
      (:rf.error/id (ex-data e)))))

(defn- assert-adapter-reentry-loses! [callback]
  (let [id               (keyword "construction-reentry" (name callback))
        nested-outcome   (atom ::not-called)
        in-nested?       (atom false)
        state-calls      (atom 0)
        outer-derived-n  (atom 0)
        original-state   rf.substrate.adapter/make-state-container
        original-derived rf.substrate.adapter/make-derived-value
        reenter!         (fn []
                           (reset! in-nested? true)
                           (try
                             (reset! nested-outcome
                                     (err-id #(rf/make-frame
                                                {:id id :tags #{:nested}})))
                             (finally
                               (reset! in-nested? false))))]
    (with-redefs [rf.substrate.adapter/make-state-container
                  (fn [initial]
                    (swap! state-calls inc)
                    (when (and (= :make-state-container callback)
                               (not @in-nested?))
                      (reenter!))
                    (original-state initial))
                  rf.substrate.adapter/make-derived-value
                  (fn [sources compute-fn]
                    (when-not @in-nested?
                      (let [n (swap! outer-derived-n inc)]
                        (when (or (and (= :make-app-derived callback) (= 1 n))
                                  (and (= :make-runtime-derived callback) (= 2 n)))
                          (reenter!))))
                    (original-derived sources compute-fn))]
      (is (some? (rf/make-frame {:id id :tags #{:outer}}))
          (str callback " outer construction commits"))
      (is (= :rf.error/frame-construction-in-progress @nested-outcome)
          (str callback " same-id nested construction loses with the stable type"))
      (is (= 1 @state-calls)
          (str callback " allocates only the committed frame's state container"))
      (is (= #{:outer} (get-in (rf.frame/frame id) [:config :tags]))
          (str callback " cannot overwrite the committed outer config"))
      (is (some? (rf/make-frame {:id id :tags #{:sequential-refresh}}))
          (str callback " releases its reservation for a later sequential refresh")))))

(deftest same-id-reentry-from-every-adapter-constructor-loses
  (doseq [callback [:make-state-container
                    :make-app-derived
                    :make-runtime-derived]]
    (testing (name callback)
      (assert-adapter-reentry-loses! callback))))

(deftest same-id-make-from-initial-event-cannot-commit-a-later-revision
  (let [id             :construction-setup/same-id
        nested-outcome (atom ::not-called)]
    (rf/reg-event :construction-setup/reenter-and-fail
      (fn [_ _]
        (reset! nested-outcome
                (err-id #(rf/make-frame {:id id :tags #{:nested-success}})))
        (throw (ex-info "fail the provisional constructor" {:fixture true}))))
    (is (= :rf.error/initial-events-step-failed
           (err-id #(rf/make-frame
                      {:id id
                       :tags #{:outer}
                       :initial-events [[:construction-setup/reenter-and-fail]]})))
        "the outer setup failure remains the constructor's terminal outcome")
    (is (= :rf.error/frame-construction-in-progress @nested-outcome)
        "the synchronous same-id make cannot report success against a provisional row")
    (is (nil? (rf.frame/frame id))
        "the failed outer transaction leaves no frame or nested revision")
    (is (some? (rf/make-frame {:id id :tags #{:clean-retry}}))
        "failure released the exact reservation for a clean retry")))

(deftest set-claim-is-atomic-and-its-engine-handoff-is-one-shot
  (let [a     :construction-handoff/a
        b     :construction-handoff/b
        free  :construction-handoff/free
        owner (rf.frame/claim-frame-construction! #{a b} :plan-preflight)]
    (try
      (is (= :rf.error/frame-construction-in-progress
             (err-id #(rf.frame/claim-frame-construction! #{b free}
                                                        :competing-plan)))
          "a set claim that overlaps one owned id loses as a unit")
      (is (some? (rf/make-frame {:id free :tags #{:disjoint}}))
          "the losing set claim did not partially reserve its free id")
      (is (= [true :rf.error/frame-construction-in-progress]
             (rf.frame/call-with-frame-construction-handoff!
               owner a
               (fn []
                 [(some? (rf/make-frame {:id a :tags #{:handed-off}}))
                  (err-id #(rf/make-frame {:id a :tags #{:second-entry}}))])))
          "one handoff permits exactly one engine entry; a second public entry collides")
      (is (= #{:handed-off} (get-in (rf.frame/frame a) [:config :tags]))
          "only the handed-off engine entry published")
      (finally
        (rf.frame/release-frame-construction! owner)))
    (is (some? (rf/make-frame {:id a :tags #{:after-release}}))
        "the external set owner compare-releases for later ordinary construction")))
