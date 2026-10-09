(ns re-frame.join-strict-mint-epoch-replay-test
  "A `:spawn-all` join's strict-mint replay through the REAL epoch seams: the
  `:rf/epoch-record` read off `rf/epoch-history`, an EDN round-trip of its
  replay material, and `rf/restore-epoch!`.

  `re-frame.join-strict-mint-cljs-test` (machines) proves the join's strict
  and live logic against a hand-built token and a test-only runtime-db
  restore; this proves the epoch record carries the completion's mid-run mint
  and that restore rewinds the join, so the two compose. It lives on the epoch
  lane because only that lane has machines and epoch on one classpath."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Publishes the machine hooks, the spawn fx and the join
            ;; lifecycle fx; the fixture keeps those ns-load registrations.
            [re-frame.machines]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.epoch/clear-history!)
                (rf.epoch/clear-epoch-listeners!))}))

(defn- runtime-db []
  (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- join-state [parent-id]
  (get-in (runtime-db) [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- machine-state [machine-id]
  (:state (get-in (runtime-db) [:rf.runtime/machines :snapshots machine-id])))

(defn- epoch-record-for
  "The newest record in `:rf/default`'s ring whose `:trigger-event` is `trigger`."
  [trigger]
  (some #(when (= trigger (:trigger-event %)) %)
        (reverse (rf/epoch-history :rf/default))))

(defn- completing-child
  "Completes on `:go`, and the action entering `:final?` requires the
  generator-backed `:strictmint/roll`, so the roll rides the completion."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id  (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :stamp-roll {:rf.cofx/requires [:strictmint/roll]
                          :fn (fn [{data :data cofx :rf.cofx}]
                                {:data (assoc data :roll (:strictmint/roll cofx))})}}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :stamp-roll}}}
             :done {:final? true :output-key :roll}}})

(defn- plain-child
  "Never driven: it holds the `:all` join open after `:a` folds."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done}}}
             :done {:final? true :output-key :id}}})

(deftest strict-replay-through-real-epoch-record-and-restore-reproduces-the-join
  (let [calls (atom 0)]
    (rf/reg-cofx :strictmint/roll {:recordable? true} (fn [] (swap! calls inc) 6))
    (rf/reg-machine :j1/ta (completing-child))
    (rf/reg-machine :j1/pb (plain-child))
    (rf/reg-machine :j1/rp
      {:initial :idle
       :states  {:idle   {:on {:start :racing}}
                 :racing {:spawn-all {:children        [{:id :a :machine-id :j1/ta :start [:set-id :a]}
                                                        {:id :b :machine-id :j1/pb :start [:set-id :b]}]
                                      :join            :all
                                      :on-all-complete [:all/done]}
                          :on {:abort :idle}}}})
    (rf/dispatch-sync [:j1/rp [:start]])
    (let [pre-epoch-id (:epoch-id (last (rf/epoch-history :rf/default)))
          a            (get-in (join-state :j1/rp) [:children :a])]
      ;; An external :rf.cofx makes run-start pin a token the mid-run mint folds into.
      (rf/dispatch-sync [a [:go]] {:rf.cofx {:rf/time-ms 1}})
      (let [rec       (epoch-record-for [a [:go]])
            durable   [(:trigger-event rec) (:rf.cofx rec)]
            [ev cofx] (edn/read-string (pr-str durable))]
        (is (= {:strictmint/roll 6 :rf/time-ms 1}
               (select-keys (:rf.cofx rec) [:strictmint/roll :rf/time-ms]))
            "the record's token captured the mid-run mint beside the external fact")
        (is (= durable [ev cofx]) "the replay material survives an EDN round-trip")
        (is (true? (rf/restore-epoch! :rf/default pre-epoch-id)))
        (is (= [#{} :running] [(:done (join-state :j1/rp)) (machine-state a)])
            "restore rewound the join slot and the child's snapshot")
        (reset! calls 0)
        (rf/dispatch-sync ev {:rf.cofx cofx :rf.cofx/mint-policy :strict})
        (is (= [0 #{:a}] [@calls (:done (join-state :j1/rp))])
            "the strict replay folds from the recorded fact; the generator stays idle")))))
