(ns re-frame.join-parallel-attempt-select-test
  "Two active parallel regions whose `:spawn-all` joins both own a logical child
  `:worker`: a completion folds only into the region whose attempt minted it, not
  into the first join that owns the child id."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private parent-kw :rf2-wsrtlw/parent)

(defn- region
  "A region whose `:spawn-all` pairs `:worker` with a never-completing `:helper`,
  so a `:worker` fold leaves the join open. Each region spawns from its own
  `racing-state`: registration refuses two regions spawning at one in-region path."
  [racing-state on-complete]
  {:initial :idle
   :states  {:idle        {:on {:start racing-state}}
             racing-state {:spawn-all {:children        [{:id :worker :machine-id :rf2-wsrtlw/worker}
                                                         {:id :helper :machine-id :rf2-wsrtlw/helper}]
                                       :join            :all
                                       :on-all-complete on-complete}}}})

(defn- join-for [on-complete]
  (some (fn [[_invoke js]] (when (= on-complete (get-in js [:spec :on-all-complete])) js))
        (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-kw])))

(deftest later-region-folds-only-itself-by-exact-attempt
  (rf/reg-machine :rf2-wsrtlw/worker {:initial :running
                                      :states  {:running {:on {:go :done}}
                                                :done    {:final? true}}})
  (rf/reg-machine :rf2-wsrtlw/helper {:initial :idle :states {:idle {}}})
  (rf/reg-machine parent-kw {:type    :parallel
                             :regions {:r1 (region :r1-racing [:r1/done])
                                       :r2 (region :r2-racing [:r2/done])}})
  (rf/dispatch-sync [parent-kw [:start]])
  (rf/dispatch-sync [(get-in (join-for [:r2/done]) [:children :worker]) [:go]])
  (is (= [#{} #{:worker} [] []]
         [(:done (join-for [:r1/done]))
          (:done (join-for [:r2/done]))
          (rf.machines.test-support/events-of :rf.machine.spawn-all/stale-completion)
          (rf.machines.test-support/events-of :rf.error/machine-spawn-all-bad-child-id)])
      ":r2 folds its own worker; :r1 is untouched and no stale or bad-child evidence fires"))
