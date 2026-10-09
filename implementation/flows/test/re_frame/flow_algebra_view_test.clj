(ns re-frame.flow-algebra-view-test
  "The static derivation/process algebra view of flows (EP-0014; spec
  Derivations.md, and the `:rf/derivation-node` shape in Spec-Schemas.md):
  `re-frame.flows.tooling/flow-algebra-view` lowers each registered flow to a
  `:derivation` materialized in app-db, evaluated `:after-event` and owned by
  its frame. The view has no public accessor: it lives in the bundle-isolated
  tooling sibling, which Xray and the conformance fixtures name directly."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows]
            [re-frame.flows.tooling :as rf.flows.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest facade-publishes-no-algebra-view-alias
  (is (= [nil true]
         [(ns-resolve 're-frame.flows 'flow-algebra-view)
          (some? (ns-resolve 're-frame.flows.tooling 'flow-algebra-view))])))

(deftest flow-exposes-its-full-algebra-view
  ;; An empty registry projects `{}`, not nil, in both arities. Absent
  ;; `:schema` and `:doc` stay absent from the node.
  (is (= [{} {}] [(rf.flows.tooling/flow-algebra-view) (rf.flows.tooling/flow-algebra-view :rf/default)]))
  (rf/reg-flow :cart/total {:inputs [[:cart :items] [:pricing :discounts]] :output-path [:cart :total]}
    (fn [items discounts] [items discounts]))
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
  (is (= {:inputs [[:runtime [:rf.runtime/routing :current :route-id]] [:db [:local :seed]]]
          :schema :app/slug
          :doc    "a route slug"}
         (select-keys (get-in (rf.flows.tooling/flow-algebra-view) [:rf/default :route/derived])
                      [:inputs :schema :doc]))))

(deftest same-flow-id-on-two-frames-projects-per-frame
  (rf/make-frame {:id :other})
  (rf/reg-flow :shared {:frame :rf/default :inputs [[:a]] :output-path [:out :default]} identity)
  (rf/reg-flow :shared {:frame :other :inputs [[:b]] :output-path [:out :other]} identity)
  (is (= {:rf/default [[:db [:out :default]] [:frame :rf/default]]
          :other      [[:db [:out :other]] [:frame :other]]}
         (into {} (for [[frame-id nodes] (rf.flows.tooling/flow-algebra-view)]
                    [frame-id ((juxt :output :owner) (:shared nodes))])))))
