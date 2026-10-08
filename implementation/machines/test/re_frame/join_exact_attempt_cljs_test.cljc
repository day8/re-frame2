(ns re-frame.join-exact-attempt-cljs-test
  "A join folds a completion carrier only when every exact-attempt coordinate field
  equals the current join attempt (Spec 005 §Exact-attempt fold fence); the fence
  runs before the resolved/unresolved split, and resolution destroys only survivors."
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

(def ^:private child
  {:initial :running
   :states  {:running {:on {:go :done}}
             :done    {:final? true}}})

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- events-of [op]
  (rf.machines.test-support/events-of op))

(defn- stale-reasons []
  (mapv (comp :rf.reply/stale-reason :tags) (events-of :rf.machine.spawn-all/stale-completion)))

(defn- reg-join-parent!
  "Register and start a two-child `:all` join parent. It stays on `:racing` at
  resolution, so the join slot survives; `:abort` then `:start` re-enters it as a
  NEW attempt. Returns the seeded join state."
  [parent-kw child-kw]
  (rf/reg-machine child-kw child)
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all {:children        [{:id :a :machine-id child-kw}
                                                      {:id :b :machine-id child-kw}]
                                    :join            :all
                                    :on-all-complete [:all/done]}
                        :on {:abort :idle}}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (join-state parent-kw))

(defn- dispatch-forged! [parent-kw completion]
  (rf/dispatch-sync [parent-kw [:rf.machine.spawn/done [:racing] completion]]))

(defn- exact-completion
  "The completion the runtime would mint for `child-id` at the join's CURRENT attempt."
  [parent-kw child-id]
  (let [j (join-state parent-kw)]
    {:result     child-id
     :error?     false
     :child-id   child-id
     :parent-id  parent-kw
     :invoke-id  [:racing]
     :spawned-id (get-in j [:children child-id])
     :attempt    (:rf/attempt j)}))

(deftest exact-current-coordinate-accepted-from-any-source-metadata-slot-not-read
  (reg-join-parent! :jea/p4 :jea/p4c)
  (dispatch-forged! :jea/p4 (exact-completion :jea/p4 :a))
  (is (= [#{:a} []] [(:done (join-state :jea/p4)) (stale-reasons)])
      "a hand-authored exact-current coordinate on the carrier folds")
  (reg-join-parent! :jea/p5 :jea/p5c)
  (rf.machines.test-support/reset-captured!)
  (rf/dispatch-sync [:jea/p5 (with-meta [:rf.machine.spawn/done [:racing] {:result :a :error? false :child-id :a}]
                                        {:rf/join-attempt (exact-completion :jea/p5 :a)})])
  (let [j (join-state :jea/p5)]
    (is (= [#{} false [:rf.machine.spawn-all/attempt-unverified]]
           [(:done j) (:resolved? j) (stale-reasons)])
        "the same tuple on event metadata is not read")))

(deftest a-coordinate-off-current-in-any-field-is-superseded
  (let [prior-attempt (:rf/attempt (reg-join-parent! :jea/p1 :jea/p1c))]
    (rf/dispatch-sync [:jea/p1 [:abort]])
    (rf/dispatch-sync [:jea/p1 [:start]])
    (doseq [[field v] [[:attempt prior-attempt]   ;; the CURRENT actor id, a prior attempt's token
                       [:spawned-id (get-in (join-state :jea/p1) [:children :b])]
                       [:invoke-id [:other-invoke]]
                       [:parent-id :jea/other-parent]]]
      (rf.machines.test-support/reset-captured!)
      (dispatch-forged! :jea/p1 (assoc (exact-completion :jea/p1 :a) field v))
      (is (= [#{} [:rf.machine.spawn-all/attempt-superseded]]
             [(:done (join-state :jea/p1)) (stale-reasons)])
          (str field)))))

(deftest join-resolution-destroys-only-survivors
  (rf/reg-machine :jea/p2c child)
  (rf/reg-machine :jea/p2
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all {:children         [{:id :a :machine-id :jea/p2c}
                                                       {:id :b :machine-id :jea/p2c}]
                                    :join             :any
                                    :on-some-complete [:any/done]}}}})
  (rf/dispatch-sync [:jea/p2 [:start]])
  (let [{:keys [a b]}  (:children (join-state :jea/p2))
        destroy-reasons (fn [actor-id]
                          (into [] (comp (filter #(= actor-id (:actor-id (:tags %))))
                                         (map (comp :reason :tags)))
                                (events-of :rf.machine/destroyed)))]
    (rf/dispatch-sync [a [:go]])
    (is (= [[:rf.machine/finished] [:explicit] [:b]]
           [(destroy-reasons a)
            (destroy-reasons b)
            (mapv (comp :child-id :tags)
                  (events-of :rf.machine.spawn/cancelled-on-join-resolution))])
        "the decisive child closed itself; only the survivor is cancelled by the join")))

;; Classifying `:resolved?` first would attribute any carrier against a resolved
;; join to the CURRENT attempt and forge a `:late-completion` from it.
(deftest against-a-resolved-join-only-an-exact-current-carrier-is-late
  (let [straggler (do (reg-join-parent! :jea/p3 :jea/p3c)
                      (exact-completion :jea/p3 :a))]
    (rf/dispatch-sync [:jea/p3 [:abort]])
    (rf/dispatch-sync [:jea/p3 [:start]])
    (let [{:keys [a b]} (:children (join-state :jea/p3))]
      (rf/dispatch-sync [a [:go]])
      (rf/dispatch-sync [b [:go]]))
    (let [resolved (join-state :jea/p3)]
      (is (:resolved? resolved))
      (is (not= (:spawned-id straggler) (get-in resolved [:children :a]))
          "the successor respawned :a, so borrowed identity is observable")
      (rf.machines.test-support/reset-captured!)
      (dispatch-forged! :jea/p3 straggler)
      (dispatch-forged! :jea/p3 {:result :a :error? false :child-id :a})
      (dispatch-forged! :jea/p3 {:result :zzz :error? false :child-id :zzz})
      (is (= [[:rf.machine.spawn-all/attempt-superseded :rf.machine.spawn-all/attempt-unverified]
              (:spawned-id straggler)
              []
              1]
             [(stale-reasons)
              (second (:rf.reply/work-id (:tags (first (events-of :rf.machine.spawn-all/stale-completion)))))
              (events-of :rf.machine.spawn-all/late-completion)
              (count (events-of :rf.error/machine-spawn-all-bad-child-id))])
          "superseded (with its OWN identity), unverified and bad-child — never late")
      (is (= resolved (join-state :jea/p3)) "zero mutation"))))
