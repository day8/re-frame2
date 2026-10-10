(ns re-frame.machine-ssr-keyed-data-projection-test
  "A machine whose `:data` is keyed by id at its root redacts in the SSR
  hydration projection as the durable walker does. The projector walks the
  snapshot's `:data` with its `:data` anchor intact, so `[:data :token]`
  reaches the `:token` under every key."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            ;; Registers `:rf/machine` and the hook `rf/reg-machine` resolves through.
            [re-frame.machines]
            [re-frame.machines.ssr :as rf.machines.ssr]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest a-root-keyed-data-declaration-redacts-in-hydration-as-the-trace-does
  (rf/reg-machine ::kid
    {:initial   :wait
     :data      {"a" {:id 1 :token "SSR-SECRET-A"}
                 "b" {:id 2 :token "SSR-SECRET-B"}}
     :sensitive [[:data :token]]
     :states    {:wait {}}})
  (rf/reg-machine ::parent
    {:initial :working
     :data    {}
     :states  {:working {:spawn {:machine-id ::kid}}}})
  (rf/dispatch-sync [::parent [:rf.machine/start]])
  (let [runtime-db        (:rf.db/runtime (rf/frame-state-value :rf/default))
        [kid-id snapshot] (->> (get-in runtime-db [:rf.runtime/machines :snapshots])
                               (filter #(= ::kid (:rf/machine-type (val %))))
                               first)
        projected         (get-in (rf.machines.ssr/project-ssr-runtime-db runtime-db :rf/default)
                                  [:snapshots kid-id :data])]
    (is (some? kid-id) "the parent spawned the kid")
    (is (= {"a" {:id 1 :token :rf/redacted}
            "b" {:id 2 :token :rf/redacted}}
           (select-keys projected ["a" "b"])))
    (is (= (rf.elision/elide-wire-value
             (:data snapshot)
             {:frame                    :rf/default
              :path                     [:rf.runtime/machines :snapshots kid-id :data]
              :rf.egress/include-large? true})
           projected)
        "the hydration projection equals the durable walker's")))
