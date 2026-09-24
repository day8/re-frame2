(ns re-frame.join-resolution-finished-sibling-cljs-test
  "Join resolution cancels only siblings that are still LIVE
  members of this attempt, never one that already FINISHED.

  `build-resolution-fx` cannot treat every child outside `:done ∪ :failed` as
  a survivor. Those sets record carriers already FOLDED, not actors already
  FINISHED, so a sibling that reached `:final?` (publishing its own
  `:completed` terminal and auto-destroying with `:rf.machine/finished`) while
  its carrier is still queued behind the decisive one would ALSO be stamped
  `:rf.machine.spawn/cancelled-on-join-resolution` with a `:cancelled` reply,
  before its carrier lands as `late-completion`. One attempt would carry three
  contradictory terminals, against Spec 005's one terminal authority per child
  attempt. A genuinely live survivor is cancelled (the control below).

  The file is named `*-cljs-test.cljc` so it is discovered by both
  cognitect.test-runner (JVM) and shadow-cljs (the `cljs-test$` ns-regexp)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
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

(defn- work-rows-for
  "Every `[<trace-op> <work-status>]` reply row whose work-id names
  `spawned-id`, in emission order."
  [spawned-id]
  (into []
        (comp (filter #(= spawned-id (second (:rf.reply/work-id (:tags %)))))
              (keep (fn [ev]
                      (when-let [st (:rf.reply/work-status (:tags ev))]
                        [(:operation ev) st]))))
        (or (rf.machines.test-support/captured-events) [])))

(defn- cancelled-on-resolution-for [spawned-id]
  (filterv #(= spawned-id (get-in % [:tags :spawned-id]))
           (rf.machines.test-support/events-of
             :rf.machine.spawn/cancelled-on-join-resolution)))

(defn- reg-racing-parent! [parent-kw child-kw b-start]
  (rf/reg-machine child-kw
    {:initial :running
     :states  {:running {:on {:go :done}}
               :done    {:final? true}}})
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children         [{:id :a :machine-id child-kw :start [:go]}
                                            (cond-> {:id :b :machine-id child-kw}
                                              b-start (assoc :start b-start))]
                         :join             :any
                         :on-some-complete [:race/won]}}}}))

(deftest a-finished-but-unfolded-sibling-is-not-cancelled
  (testing "both children finish before either carrier folds: the decisive
            carrier resolves the join, and the sibling that already finished
            keeps its ONE :completed terminal — no cancellation"
    (reg-racing-parent! :jrf/parent :jrf/child [:go])
    (rf/dispatch-sync [:jrf/parent [:start]])
    (is (nil? (snapshot :jrf/child#1)) "child :a finished")
    (is (nil? (snapshot :jrf/child#2)) "child :b finished")
    (is (true? (get-in (rf.machines.test-support/runtime-db)
                       [:rf.runtime/machines :spawned :jrf/parent [:racing] :resolved?]))
        "the join resolved on the decisive carrier")
    (is (empty? (cancelled-on-resolution-for :jrf/child#2))
        "the already-finished sibling is NOT cancelled on join resolution")
    (is (not-any? #{:cancelled} (map second (work-rows-for :jrf/child#2)))
        (str "no :cancelled reply for a child that completed; saw "
             (work-rows-for :jrf/child#2)))
    (is (= [[:rf.machine/done :completed]
            [:rf.machine.spawn-all/late-completion :suppressed]]
           (work-rows-for :jrf/child#2))
        "its own finality terminal, then its queued carrier suppressed as late")))

(deftest a-live-survivor-is-still-cancelled
  (testing "control: a sibling that is genuinely still running when the join
            resolves is cancelled and torn down"
    (reg-racing-parent! :jrl/parent :jrl/child nil)
    (rf/dispatch-sync [:jrl/parent [:start]])
    (is (nil? (snapshot :jrl/child#1)) "child :a finished and resolved the join")
    (is (nil? (snapshot :jrl/child#2)) "the live survivor was torn down")
    (is (= 1 (count (cancelled-on-resolution-for :jrl/child#2)))
        "the live survivor carries its cancellation trace")
    (is (some #{:cancelled} (map second (work-rows-for :jrl/child#2))))))
