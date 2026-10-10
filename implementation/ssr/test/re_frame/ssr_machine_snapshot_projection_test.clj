(ns re-frame.ssr-machine-snapshot-projection-test
  "A durable machine snapshot's `:data` hydrates projected, not raw.

  The frame classifies the snapshot's absolute runtime-db path through a
  commit-plane `:sensitive` / `:large` effect (EP-0025), and `project-runtime-db`
  projects `:rf.runtime/machines` through the late-bound
  `:machines/project-ssr-runtime-db` hook. A machine's `[:schemas :data]`
  validates `:data`; it does not classify it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            ;; Loading machines publishes :machines/project-ssr-runtime-db.
            [re-frame.machines]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private auth-id :rf.ssr-machine/auth)

(deftest sensitive-machine-data-redacted-in-hydration-projection
  ;; The large blob rides whole: the client actor needs its `:data`, so the
  ;; hydration wire applies no size elision.
  (rf/reg-machine auth-id
    {:initial :anon
     :data    {:retries 0 :token nil :blob nil}
     :schemas {:data [:map [:retries :int] [:token [:maybe :string]] [:blob [:maybe :string]]]}
     :states  {:anon {:on {:login :authed}} :authed {}}})
  (rf/reg-event :rf.ssr-machine/classify-snapshot
    (fn [_ _]
      {:sensitive [[:rf.runtime/machines :snapshots auth-id :data :token]]
       :large     [[:rf.runtime/machines :snapshots auth-id :data :blob]]}))
  (rf/dispatch-sync [:rf.ssr-machine/classify-snapshot])
  (is (= {:rf.runtime/machines
          {:snapshots {auth-id {:state :authed
                                :data  {:retries 2 :token :rf/redacted :blob "huge-blob-value"}}}
           :spawned   {}}}
         (rf.ssr.payload-policy/project-runtime-db
           {:rf.runtime/machines
            {:snapshots {auth-id {:state :authed
                                  :data  {:retries 2
                                          :token   "secret-jwt-snapshot"
                                          :blob    "huge-blob-value"}}}
             :spawned   {}}}))))

(deftest a-collection-element-declaration-redacts-every-element-as-the-trace-does
  ;; A spawned actor's projection-relative `[:data :items :token]` names the
  ;; field in EVERY element; the durable walker the trace path uses reads it
  ;; that way, and the hydration projection must agree.
  (rf/reg-machine :rf.ssr-machine/kid
    {:initial   :wait
     :data      {:token "SECRET-DEFAULT"
                 :items [{:id 1 :token "SECRET-ITEM"} {:id 2 :token "SECRET-ITEM-2"}]}
     :sensitive [[:data :token] [:data :items :token]]
     :states    {:wait {}}})
  (rf/reg-machine :rf.ssr-machine/parent
    {:initial :working
     :data    {}
     :states  {:working {:spawn {:machine-id :rf.ssr-machine/kid}}}})
  (rf/dispatch-sync [:rf.ssr-machine/parent [:rf.machine/start]])
  (let [runtime-db        (:rf.db/runtime (rf/frame-state-value :rf/default))
        [kid-id snapshot] (->> (get-in runtime-db [:rf.runtime/machines :snapshots])
                               (filter #(= :rf.ssr-machine/kid (:rf/machine-type (val %))))
                               first)
        projected         (get-in (rf.ssr.payload-policy/project-runtime-db runtime-db :rf/default)
                                  [:rf.runtime/machines :snapshots kid-id :data])]
    (is (= {:token :rf/redacted
            :items [{:id 1 :token :rf/redacted} {:id 2 :token :rf/redacted}]}
           (select-keys projected [:token :items])))
    (is (= (rf.elision/elide-wire-value
             (:data snapshot)
             {:frame                    :rf/default
              :path                     [:rf.runtime/machines :snapshots kid-id :data]
              :rf.egress/include-large? true})
           projected)
        "the hydration projection equals the durable walker's")))

(deftest undeclared-machine-snapshot-rides-verbatim
  ;; The projection is precise, not a blanket scrub.
  (rf/reg-machine :rf.ssr-machine/plain
    {:initial :idle :data {:public "ok"} :states {:idle {}}})
  (let [rt {:rf.runtime/machines
            {:snapshots {:rf.ssr-machine/plain {:state :idle :data {:public "ok"}}}}}]
    (is (= rt (rf.ssr.payload-policy/project-runtime-db rt)))))
