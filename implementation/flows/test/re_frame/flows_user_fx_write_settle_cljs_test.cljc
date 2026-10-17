(ns re-frame.flows-user-fx-write-settle-cljs-test
  "Spec 013 §Sequencing, on both hosts: a USER fx that writes frame state
  settles the frame's flows before the dispatch returns.

  The `:fx` walk runs after the event's flow pass, so a flow reading what the
  fx wrote would be one event behind. The walk settles a frame running a flow
  whose state container an fx changed."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows]
   [re-frame.frame :as rf.frame]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(defn- db [] (rf/app-db-value :rf/default))

(deftest a-user-fx-writing-frame-state-settles-in-one-dispatch
  ;; Without the settle the flow's pass runs before the write and reads nil.
  (rf/reg-flow :p/rt {:inputs [[:rf.db/runtime :probe/n]] :output-path [:rt-n]} identity)
  (rf/make-frame {:id :rf/default :flows [:p/rt]})
  (rf/reg-fx :p/bump-runtime
    (fn [{:keys [frame]} _] (rf.frame/swap-runtime-db! frame update :probe/n (fnil inc 0))))
  (rf/reg-event :go (fn [_ _] {:fx [[:p/bump-runtime nil]]}))
  (rf/dispatch-sync [:go])
  (is (= 1 (:rt-n (db)))))
