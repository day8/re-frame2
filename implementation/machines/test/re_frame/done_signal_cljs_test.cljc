(ns re-frame.done-signal-cljs-test
  "The done-state signal at the live handler boundary: an embedded `:final?`
  leaf signals its compound's `:on-done` without destroying the machine, and a
  parallel root's `:on-done` fires once when every region is final (Spec 005
  §The done-state signal)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(def ^:private two-final-regions
  {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
   :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}})

(deftest compound-done-no-on-done-rests-without-destroy
  (rf/reg-machine :rf2-zlmz7/rest
    {:initial :flow
     :states
     {:flow {:initial :step
             :states  {:step       {:on {:finish :inner-done}}
                       :inner-done {:final? true}}}}})
  (rf/dispatch-sync [:rf2-zlmz7/rest [:finish]])
  (is (= [:flow :inner-done] (:state (snapshot :rf2-zlmz7/rest)))
      "an embedded final with no :on-done rests rather than tearing the machine down"))

(deftest compound-done-runs-on-done-action
  (rf/reg-machine :rf2-zlmz7/act
    {:initial :flow
     :data    {:hits 0}
     :actions {:bump (fn [{d :data}] {:data (update d :hits inc)})}
     :states
     {:flow {:initial :step
             :on-done {:target :next :action :bump}
             :states  {:step       {:on {:finish :inner-done}}
                       :inner-done {:final? true}}}
      :next {}}})
  (rf/dispatch-sync [:rf2-zlmz7/act [:finish]])
  (let [s (snapshot :rf2-zlmz7/act)]
    (is (= [[:next] 1] [(:state s) (get-in s [:data :hits])])
        ":on-done advances and runs its action once, in the same macrostep")))

(deftest parallel-on-done-fires-once-in-the-transition-phase
  (let [continued (atom 0)]
    (rf/reg-event :rf2-h3wca/coordinator
      (fn [{:keys [db]} _] (swap! continued inc) {:db db}))
    (rf/reg-machine :rf2-h3wca/once
      {:type    :parallel
       :data    {:completions 0}
       :actions {:complete (fn [{d :data}]
                             {:data (update d :completions inc)
                              :fx   [[:dispatch [:rf2-h3wca/coordinator]]]})}
       :on-done {:action :complete}
       :regions two-final-regions})
    (let [counts   #(vector (get-in (snapshot :rf2-h3wca/once) [:data :completions]) @continued)
          _        (rf/dispatch-sync [:rf2-h3wca/once [:fin]])
          on-entry (counts)]
      ;; Resting all-final, an event every region declines must not re-fire it.
      (rf/dispatch-sync [:rf2-h3wca/once [:fin]])
      (is (= {:on-entry [1 1] :resting [1 1] :phases [:transition]}
             {:on-entry on-entry
              :resting  (counts)
              :phases   (->> (rf.machines.test-support/events-of :rf.machine/action-ran)
                             (filter #(= :complete (-> % :tags :action-id)))
                             (mapv (comp :phase :tags)))})
          ":data and :fx land once, on entering the done config, under phase :transition"))))

(deftest parallel-on-done-action-returning-db-emits-error-and-drops-db
  (rf/reg-machine :rf2-z522n/par-on-done-db
    {:type    :parallel
     :data    {:completions 0}
     :actions {:complete (fn [{d :data}]
                           {:db   {:hacked true}
                            :data (update d :completions inc)})}
     :on-done {:action :complete}
     :regions two-final-regions})
  (rf/dispatch-sync [:rf2-z522n/par-on-done-db [:fin]])
  (is (= [{:action-id :complete :offending-value :rf/redacted}]
         (mapv #(select-keys (:tags %) [:action-id :offending-value])
               (rf.machines.test-support/events-of :rf.error/machine-action-wrote-db))))
  (is (= 1 (get-in (snapshot :rf2-z522n/par-on-done-db) [:data :completions]))
      "the :data write still flows; only :db is dropped"))

;; Every target-bearing form is refused: at runtime a parallel root :on-done
;; target would mark the done handled, suppress auto-destroy and move nowhere.
(deftest parallel-on-done-target-rejected
  (doseq [[on-done extra] [[{:target :somewhere} {}]
                           [:next {}]
                           [[:next] {}]
                           [[{:guard :g :target :next} {:action :a}]
                            {:guards  {:g (constantly true)}
                             :actions {:a (fn [{d :data}] {:data d})}}]]]
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error)
          #":rf.error/machine-parallel-on-done-target"
          (rf/reg-machine :rf2-6srk5/bad-target
            (merge {:type    :parallel
                    :on-done on-done
                    :regions {:a {:initial :run :states {:run {}}}}}
                   extra)))
        (pr-str on-done))))

(deftest compound-on-done-unresolved-action-rejected
  (is (thrown-with-msg?
        #?(:clj Exception :cljs js/Error)
        #":rf.error/machine-unresolved-action"
        (rf/reg-machine :rf2-zlmz7/bad-ref
          {:initial :flow
           :states  {:flow {:initial :step
                            :on-done {:target :next :action :nope}
                            :states  {:step       {:on {:finish :inner-done}}
                                      :inner-done {:final? true}}}
                     :next {}}}))))
