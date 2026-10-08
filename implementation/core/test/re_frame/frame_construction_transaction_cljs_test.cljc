(ns re-frame.frame-construction-transaction-cljs-test
  "A frame id has one construction transaction from adapter allocation
  through setup to publication. A same-id `make-frame` re-entered synchronously
  from an adapter callback or an initial event loses promptly with
  `:rf.error/frame-construction-in-progress` and cannot install a frame the
  outer construction later overwrites; a failed outer construction removes only
  its own provisional row. All barriers are synchronous."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
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
      ;; the outer commits, the nested loses, only one state container is
      ;; allocated, the outer config stands, and the reservation is released
      (is (= [true :rf.error/frame-construction-in-progress 1 #{:outer} true]
             [(some? (rf/make-frame {:id id :tags #{:outer}}))
              @nested-outcome
              @state-calls
              (get-in (rf.frame/frame id) [:config :tags])
              (some? (rf/make-frame {:id id :tags #{:sequential-refresh}}))])
          (str callback)))))

(deftest same-id-reentry-from-every-adapter-constructor-loses
  (run! assert-adapter-reentry-loses!
        [:make-state-container :make-app-derived :make-runtime-derived]))

(deftest same-id-make-from-initial-event-cannot-commit-a-later-revision
  (let [id             :construction-setup/same-id
        nested-outcome (atom ::not-called)]
    (rf/reg-event :construction-setup/reenter-and-fail
      (fn [_ _]
        (reset! nested-outcome
                (err-id #(rf/make-frame {:id id :tags #{:nested-success}})))
        (throw (ex-info "fail the provisional constructor" {:fixture true}))))
    ;; the outer setup failure is the terminal outcome, the nested make lost,
    ;; nothing was left behind, and the reservation is free for a retry
    (is (= [:rf.error/initial-events-step-failed
            :rf.error/frame-construction-in-progress
            nil
            true]
           [(err-id #(rf/make-frame
                       {:id id
                        :tags #{:outer}
                        :initial-events [[:construction-setup/reenter-and-fail]]}))
            @nested-outcome
            (rf.frame/frame id)
            (some? (rf/make-frame {:id id :tags #{:clean-retry}}))]))))

(deftest set-claim-is-atomic-and-its-engine-handoff-is-one-shot
  (let [a     :construction-handoff/a
        b     :construction-handoff/b
        free  :construction-handoff/free
        owner (rf.frame/claim-frame-construction! #{a b} :plan-preflight)]
    (try
      (is (= [:rf.error/frame-construction-in-progress true]
             [(err-id #(rf.frame/claim-frame-construction! #{b free} :competing-plan))
              (some? (rf/make-frame {:id free :tags #{:disjoint}}))])
          "an overlapping set claim loses as a unit, reserving none of its free ids")
      (is (= [[true :rf.error/frame-construction-in-progress] #{:handed-off}]
             [(rf.frame/call-with-frame-construction-handoff!
                owner a
                (fn []
                  [(some? (rf/make-frame {:id a :tags #{:handed-off}}))
                   (err-id #(rf/make-frame {:id a :tags #{:second-entry}}))]))
              (get-in (rf.frame/frame a) [:config :tags])])
          "one handoff permits exactly one engine entry")
      (finally
        (rf.frame/release-frame-construction! owner)))
    (is (some? (rf/make-frame {:id a :tags #{:after-release}}))
        "the set owner's release frees the id for ordinary construction")))
