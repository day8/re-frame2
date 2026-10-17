(ns re-frame.flow-algebra-view-test
  "The static derivation/process algebra view of flows (EP-0014; spec
  Derivations.md, and the `:rf/derivation-node` shape in Spec-Schemas.md):
  `re-frame.flows.tooling/flow-algebra-view` lowers each flow a frame runs to
  a `:derivation` materialized in app-db, evaluated `:after-event` and owned by
  that frame. The view has no public accessor: it lives in the bundle-isolated
  tooling sibling, which Xray and the conformance fixtures name directly."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows]
            [re-frame.flows.tooling :as rf.flows.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest flow-exposes-its-full-algebra-view
  ;; An empty registry projects `{}`, not nil, in both arities. Absent
  ;; `:schema` and `:doc` stay absent from the node.
  (is (= [{} {}] [(rf.flows.tooling/flow-algebra-view) (rf.flows.tooling/flow-algebra-view :rf/default)]))
  (rf/reg-flow :cart/total {:inputs [[:cart :items] [:pricing :discounts]] :output-path [:cart :total]}
    (fn [items discounts] [items discounts]))
  (is (= {} (rf.flows.tooling/flow-algebra-view)) "a registration no frame runs projects nothing")
  (rf/make-frame {:id :rf/default :flows [:cart/total]})
  (let [node (get-in (rf.flows.tooling/flow-algebra-view) [:rf/default :cart/total])]
    (is (= {:id            :cart/total
            :kind          :derivation
            :storage       :app-db
            :evaluation    :after-event
            :lifecycle     :frame
            :materialized? true
            :source-form   {:kind :reg-flow :id :cart/total}
            :output        [:db [:cart :total]]
            :inputs        [[:db [:cart :items]] [:db [:pricing :discounts]]]
            :owner         [:frame :rf/default]}
           (dissoc node :derive :source)))
    (is (= [true #{:ns :file :line} true true]
           [(fn? (:derive node)) (set (keys (:source node)))
            (every? some? (vals (:source node))) (number? (get-in node [:source :line]))]))
    (is (= {:cart/total node} (rf.flows.tooling/flow-algebra-view :rf/default)))))

(deftest runtime-qualified-input-lowers-to-a-runtime-read
  ;; EP-0001 §535-551: a flow may read runtime-db through a partition-qualified
  ;; input, which lowers with the partition key stripped. A supplied `:schema`
  ;; and `:doc` pass through as node facts.
  (rf/reg-flow :route/derived {:doc "a route slug" :schema :app/slug
                               :inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]
                                        [:local :seed]]
                               :output-path [:derived :slug]}
    (fn [route-id seed] [route-id seed]))
  (rf/make-frame {:id :rf/default :flows [:route/derived]})
  (is (= {:inputs [[:runtime [:rf.runtime/routing :current :route-id]] [:db [:local :seed]]]
          :schema :app/slug
          :doc    "a route slug"}
         (select-keys (get-in (rf.flows.tooling/flow-algebra-view) [:rf/default :route/derived])
                      [:inputs :schema :doc]))))

(deftest one-flow-run-by-two-frames-projects-per-frame
  (rf/reg-flow :shared {:inputs [[:a]] :output-path [:out]} identity)
  (rf/reg-flow :only-other {:inputs [[:b]] :output-path [:other]} identity)
  (rf/make-frame {:id :rf/default :flows [:shared]})
  (rf/make-frame {:id :other :flows [:shared :only-other]})
  (is (= {:rf/default {:shared [[:db [:out]] [:frame :rf/default]]}
          :other      {:shared     [[:db [:out]] [:frame :other]]
                       :only-other [[:db [:other]] [:frame :other]]}}
         (into {} (for [[frame-id nodes] (rf.flows.tooling/flow-algebra-view)]
                    [frame-id (update-vals nodes (juxt :output :owner))])))))
