(ns re-frame.spawn-join-edn-state-test
  "A spawned child is a registered machine TYPE, so every machine slot in
  runtime-db is EDN: snapshots and the `:spawned` registry name types and
  children by reference, and a `:spawn-all` join keeps its attempt facts as
  data while its callbacks resolve from the parent's CURRENT definition
  (Spec 005 §Spawn-and-join via `:spawn-all` §Join state)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]
            [re-frame.machines.ssr :as rf.machines.ssr]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)
(def ^:private runtime-db rf.machines.test-support/runtime-db)

(defn- reg-kid!
  "A registered child TYPE with a fn guard and a classified `:token`."
  []
  (rf/reg-machine :edn/kid
    {:initial   :running
     :data      {:token nil}
     :sensitive [[:data :token]]
     :guards    {:ok? (fn [_] true)}
     :states    {:running {:on {:finish {:target :done :guard :ok?}}}
                 :done    {:final? true :output-key :token}}}))

(defn- child
  "A `:spawn-all` child entry for `:edn/kid` at fixed address `addr`, whose
  `:on-done` stamps `tag` into the parent's `:data`."
  [id addr tag & {:as extra}]
  (merge {:id             id
          :machine-id     :edn/kid
          :fixed-actor-id addr
          :on-done        (fn [{:keys [data]}] (assoc data :via tag))}
         extra))

(defn- reg-parent!
  [parent-id children & {:keys [data] :or {data {}}}]
  (rf/reg-machine parent-id
    {:initial :idle
     :data    data
     :states  {:idle     {:on {:go :working}}
               :working  {:spawn-all {:children        children
                                      :on-all-complete [:all-done]}
                          :on        {:all-done :finished}}
               :finished {}}}))

(defn- fn-free? [v]
  (not-any? fn? (tree-seq coll? seq v)))

(defn- edn-stable? [v]
  (= v (read-string (pr-str v))))

(defn- spawned-slots [rt]
  (for [[_parent by-invoke] (get-in rt [:rf.runtime/machines :spawned])
        [_invoke slot]      by-invoke]
    slot))

(deftest a-spawn-all-writes-only-edn-into-runtime-db
  (testing "registered children with fn guards and per-child fn :on-done: no write during
            the drain carries a fn, and every snapshot and :spawned slot round-trips"
    (reg-kid!)
    (reg-parent! :edn/parent [(child :a :edn/a :v1) (child :b :edn/b :v1)])
    (rf/dispatch-sync [:edn/parent [:rf.machine/start]])
    (let [writes    (atom [])
          container (rf.frame/frame-state-container :rf/default)]
      (add-watch container ::writes (fn [_ _ _ after] (swap! writes conj (:rf.db/runtime after))))
      (try
        (rf/dispatch-sync [:edn/parent [:go]])
        (finally (remove-watch container ::writes)))
      (is (seq @writes) "control: the watch saw the drain's writes")
      (is (= [] (into [] (comp (map-indexed vector) (remove (comp fn-free? second)) (map first))
                      @writes))
          "indices of runtime-db writes that carried a fn")
      (let [rt (runtime-db)]
        (is (= #{:edn/parent :edn/a :edn/b}
               (set (keys (get-in rt [:rf.runtime/machines :snapshots]))))
            "control: both children are live")
        (is (every? edn-stable? (vals (get-in rt [:rf.runtime/machines :snapshots]))))
        (is (seq (spawned-slots rt)) "control: the join slot is present")
        (is (every? edn-stable? (spawned-slots rt)))))))

(deftest a-classified-child-data-override-stays-off-the-hydration-wire
  (testing "a :spawn-all child entry's :data override carries a classified token; the
            machines hydration projection carries no SECRET-* value but the planted control"
    (reg-kid!)
    (reg-parent! :edn/parent [(child :a :edn/a :v1 :data {:token "SECRET-JOIN"})]
                 :data {:note "SECRET-CONTROL"})
    (rf/dispatch-sync [:edn/parent [:go]])
    (is (= "SECRET-JOIN" (get-in (snapshot :edn/a) [:data :token]))
        "control: the child holds the raw token in its live :data")
    (let [projected (rf.machines.ssr/project-ssr-runtime-db (runtime-db) :rf/default)]
      (is (= :rf/redacted (get-in projected [:snapshots :edn/a :data :token]))
          "the child's live :data is redacted")
      (is (= #{"SECRET-CONTROL"} (set (re-seq #"SECRET-[A-Z]+" (pr-str projected))))
          "the scan sees the planted control and nothing else"))))

(deftest join-callbacks-follow-the-parents-current-definition
  (testing "the parent is re-registered mid-attempt with a changed per-child :on-done;
            the join applies the NEW callback"
    (reg-kid!)
    (reg-parent! :edn/parent [(child :a :edn/a :v1) (child :b :edn/b :v1)])
    (rf/dispatch-sync [:edn/parent [:go]])
    (reg-parent! :edn/parent [(child :a :edn/a :v2) (child :b :edn/b :v2)])
    (rf/dispatch-sync [:edn/a [:finish]])
    (is (= :v2 (get-in (snapshot :edn/parent) [:data :via])))
    (rf/dispatch-sync [:edn/b [:finish]])
    (is (= :finished (:state (snapshot :edn/parent))) "control: the join resolved")))

(deftest join-work-identity-is-captured-when-the-join-is-seeded
  (testing "a re-registration that drops a child's :fixed-actor-id does not change the
            work identity of the attempt already running"
    (reg-kid!)
    ;; Consume one attempt token first, so this attempt's token cannot equal
    ;; the generation an unsuffixed fixed address parses to (1).
    (reg-parent! :edn/warmup [(child :w :edn/w :v1)])
    (rf/dispatch-sync [:edn/warmup [:go]])
    (reg-parent! :edn/parent [(child :a :edn/a :v1)])
    (rf/dispatch-sync [:edn/parent [:go]])
    (let [attempt (get-in (runtime-db) [:rf.runtime/machines :spawned :edn/parent [:working] :rf/attempt])]
      (is (and (some? attempt) (not= 1 attempt)) "premise: the attempt token discriminates")
      (reg-parent! :edn/parent [(dissoc (child :a :edn/a :v1) :fixed-actor-id)])
      (rf.machines.test-support/with-trace-capture seen
        (rf/dispatch-sync [:edn/a [:finish]])
        (let [work-ids (->> @seen
                            (filter #(= :rf.machine.spawn-all/all-completed (:operation %)))
                            (map #(get-in % [:tags :rf.reply/work-id])))]
          (is (= [[:rf.work/machine :edn/a [:working] attempt]] (vec work-ids))))))))
