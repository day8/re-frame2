(ns re-frame.join-child-terminal-cljs-test
  "Every `:spawn-all` child gets exactly one JOIN-side terminal beside its own
  `:rf.machine/done` finality row: a non-decisive fold publishes it at fold time
  (`child-completed`), a decisive fold through the resolution trace, and
  duplicate or post-resolution carriers add none."
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

(defn- terminal-rows-for
  "Every terminal reply row on `spawned-id`'s work-id, as `[<trace-op> <work-status>]`."
  [spawned-id]
  (into []
        (comp (filter #(= spawned-id (second (:rf.reply/work-id (:tags %)))))
              (keep (fn [ev]
                      (let [st (:rf.reply/work-status (:tags ev))]
                        (when (#{:completed :failed :cancelled} st)
                          [(:operation ev) st])))))
        (rf.machines.test-support/captured-events)))

(defn- child-completed-tags []
  (:tags (first (rf.machines.test-support/events-of :rf.machine.spawn-all/child-completed))))

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- reg-join-parent!
  "Register and start a two-child join parent (`spawn-all-extra` adds the mode and
  resolution keys); children finish on `:go` and fail on `:fail`. The parent has
  no `:on` for resolution, so the join slot survives. Returns the join state."
  [parent-kw child-kw spawn-all-extra]
  (rf/reg-machine child-kw {:initial :running
                            :states  {:running {:on {:go :done :fail :failed}}
                                      :done    {:final? true}
                                      :failed  {:final? true :error? true}}})
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all (merge {:children [{:id :a :machine-id child-kw}
                                                      {:id :b :machine-id child-kw}]}
                                          spawn-all-extra)}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (join-state parent-kw))

(defn- redeliver!
  "Hand-deliver the exact-current completion carrier the runtime minted for `child-id`."
  [parent-kw child-id]
  (let [j (join-state parent-kw)]
    (rf/dispatch-sync [parent-kw [:rf.machine.spawn/done [:racing]
                                  {:result     child-id
                                   :error?     false
                                   :child-id   child-id
                                   :parent-id  parent-kw
                                   :invoke-id  [:racing]
                                   :spawned-id (get-in j [:children child-id])
                                   :attempt    (:rf/attempt j)}]])))

(deftest non-decisive-completed-child-gets-exactly-one-completed-terminal
  (let [j      (reg-join-parent! :jct/p1 :jct/p1c {:join :all :on-all-complete [:all/done]})
        a      (get-in j [:children :a])
        b      (get-in j [:children :b])
        a-rows [[:rf.machine/done :completed]
                [:rf.machine.spawn-all/child-completed :completed]]]
    (rf/dispatch-sync [a [:go]])
    (redeliver! :jct/p1 :a)
    (is (= a-rows (terminal-rows-for a)) "published at fold time; the duplicate adds none")
    (is (= {:child-id :a :spawned-id a :kind :done :rf.reply/status :ok}
           (select-keys (child-completed-tags) [:child-id :spawned-id :kind :rf.reply/status])))
    (rf/dispatch-sync [b [:go]])
    (redeliver! :jct/p1 :a)
    (is (= [a-rows
            [[:rf.machine/done :completed] [:rf.machine.spawn-all/all-completed :completed]]
            [:stale]]
           [(terminal-rows-for a)
            (terminal-rows-for b)
            (mapv (comp :rf.reply/status :tags)
                  (rf.machines.test-support/events-of :rf.machine.spawn-all/late-completion))])
        "the decisive B closes through the resolution trace; A's post-resolution straggler is :stale")))

(deftest non-decisive-failed-child-gets-exactly-one-failed-terminal
  (let [a (get-in (reg-join-parent! :jct/p2 :jct/p2c {:join :all :on-all-complete [:all/done]})
                  [:children :a])]
    (rf/dispatch-sync [a [:fail]])
    (is (= [[[:rf.machine/done :failed] [:rf.machine.spawn-all/child-completed :failed]]
            {:kind :failed :rf.reply/status :error}]
           [(terminal-rows-for a)
            (select-keys (child-completed-tags) [:kind :rf.reply/status])]))))

(deftest decisive-folds-keep-the-resolution-authority
  (doseq [[parent-kw child-kw extra event rows]
          [[:jct/p3 :jct/p3c {:join :any :on-some-complete [:race/won]} :go
            [[:rf.machine/done :completed] [:rf.machine.spawn-all/some-completed :completed]]]
           [:jct/p4 :jct/p4c {:join :all :on-all-complete [:all/done] :on-any-failed [:all/failed]} :fail
            [[:rf.machine/done :failed] [:rf.machine.spawn-all/any-failed :failed]]]]]
    (let [a (get-in (reg-join-parent! parent-kw child-kw extra) [:children :a])]
      (rf/dispatch-sync [a [event]])
      (is (= rows (terminal-rows-for a)) (str extra)))))
