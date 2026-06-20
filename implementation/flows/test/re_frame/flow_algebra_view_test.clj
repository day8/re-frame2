(ns re-frame.flow-algebra-view-test
  "Tests for the STATIC derivation/process algebra view of flows
  (EP-0014 slice-3, rf2-s8w3nw). Per [spec/Derivations.md] (graduated from
  EP-0014) and the `:rf/derivation-node` shape in [spec/Spec-Schemas.md].

  `re-frame.flows.tooling/flow-algebra-view` lowers every registered flow
  into the normalized algebra node every declared fact/process shares: a
  `:derivation` whose MATERIALIZED output lands in app-db, evaluated
  `:after-event`, owned by the `:frame` it registered against — the
  subscription's policy twin (same whole-value function, different output /
  storage / evaluation / lifecycle).

  These tests pin the registrar-derived projection: the fixed
  classifications (`:kind` / `:storage` / `:evaluation` / `:lifecycle` /
  `:materialized?`), the per-path declared-input lowering (bare app-db vs
  `[:rf.db/runtime …]`), the output app-db address, the frame-scoped keying,
  the `:owner`, the source-form metadata, the opaque `:derive` body token,
  and the source coordinates.

  Slice-3 ships NO public accessor (EP-0014 issue-1 disposition): the view
  lives in the bundle-isolated `re-frame.flows.tooling` sibling, reached on
  JVM through the `re-frame.flows/flow-algebra-view` convenience alias, and
  consumed by Xray + the conformance fixtures. There is no
  `re-frame.core/flow-algebra-view` public facade export."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.flows.tooling :as flows-tooling]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace]))

;; Mirrors the flows_test.clj fixture so each deftest starts from a clean
;; registrar / frames / flows state, with a :rf/default frame established
;; and `*current-frame*` pinned so the ambient `reg-flow` calls in the
;; bodies below carry a scope stamp (EP-0002 — reg-flow is context-required
;; frame-local).
(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (flows/reset-last-inputs!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

;; The five fixed classifications EVERY flow algebra node carries
;; (Derivations §Worked equivalence — the flow column: the canonical
;; app-db / after-event / frame MATERIALIZED member of the algebra).
(def fixed-classifications
  {:kind          :derivation
   :storage       :app-db
   :evaluation    :after-event
   :lifecycle     :frame
   :materialized? true})

(defn- has-fixed-classifications? [node]
  (= fixed-classifications (select-keys node (keys fixed-classifications))))

;; ---- empty / shape contract ----------------------------------------------

(deftest empty-registry-returns-empty-map
  (testing "(flow-algebra-view) returns {} (not nil) when no flows are registered"
    (flows/reset-flows!)
    (is (= {} (flows-tooling/flow-algebra-view)))
    (is (map? (flows-tooling/flow-algebra-view))))
  (testing "(flow-algebra-view frame-id) returns {} for a frame with no flows"
    (is (= {} (flows-tooling/flow-algebra-view :rf/default)))))

(deftest jvm-alias-mirrors-the-tooling-fn
  (testing "the JVM `re-frame.flows/flow-algebra-view` alias is the tooling fn"
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* (or w 0) (or h 0)))
                  :output-path   [:rect :area]})
    (is (= (flows-tooling/flow-algebra-view)
           (flows/flow-algebra-view))
        "the JVM convenience alias projects identically to the tooling sibling")))

;; ---- a registered flow exposes its algebra view --------------------------

(deftest flow-exposes-its-full-algebra-view
  (testing "a reg-flow exposes the full materialized / after-event / frame node"
    (rf/reg-flow {:id     :cart/materialized-total
                  :inputs [[:cart :items] [:pricing :discounts]]
                  :derive (fn [items discounts] [items discounts])
                  :output-path   [:cart :total]})
    (let [node (get-in (flows-tooling/flow-algebra-view)
                       [:rf/default :cart/materialized-total])]
      (is (some? node) "the flow is present under its owning frame's slot")
      (is (has-fixed-classifications? node)
          "flow carries the fixed derivation / app-db / after-event / frame / materialized classifications")
      (is (= :cart/materialized-total (:id node)))
      (is (= {:kind :reg-flow :id :cart/materialized-total} (:source-form node)))
      (is (= [:db [:cart :total]] (:output node))
          "the output materializes the whole value into app-db at the flow's :output-path")
      (is (= [[:db [:cart :items]] [:db [:pricing :discounts]]] (:inputs node))
          "each bare :inputs path lowers to a [:db path] declared input, in declaration order")
      (is (= [:frame :rf/default] (:owner node))
          "the owner is the frame the flow registered against")
      (is (fn? (:derive node))
          "the :derive body fn is surfaced as an opaque :derive token"))))

(deftest single-frame-arity-returns-that-frames-flows
  (testing "(flow-algebra-view frame-id) returns {flow-id node} for one frame"
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* (or w 0) (or h 0)))
                  :output-path   [:rect :area]})
    (let [per-frame (flows-tooling/flow-algebra-view :rf/default)]
      (is (= #{:area} (set (keys per-frame))))
      (is (= (get-in (flows-tooling/flow-algebra-view) [:rf/default :area])
             (get per-frame :area))
          "the one-frame form equals that frame's entry in the all-flows map"))))

;; ---- runtime-db partition-qualified input --------------------------------

(deftest runtime-qualified-input-lowers-to-a-runtime-read
  (testing "a [:rf.db/runtime …] input lowers to a [:runtime …rest] declared input"
    ;; EP-0001 §535-551 (rf2-4eisfr): any flow may READ runtime-db via an
    ;; explicit partition-qualified input; only the write side is reserved.
    (rf/reg-flow {:id     :route/derived
                  :inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]
                           [:local :seed]]
                  :derive (fn [route-id seed] [route-id seed])
                  :output-path   [:derived :slug]})
    (let [node (get-in (flows-tooling/flow-algebra-view)
                       [:rf/default :route/derived])]
      (is (has-fixed-classifications? node))
      (is (= [[:runtime [:rf.runtime/routing :current :route-id]]
              [:db [:local :seed]]]
             (:inputs node))
          "the partition key is stripped for the runtime read; the bare path stays a [:db …] read"))))

;; ---- multiple flows ------------------------------------------------------

(deftest multiple-flows-all-project
  (testing "every registered flow on a frame projects to its own node"
    (rf/reg-flow {:id     :a
                  :inputs [[:w]]
                  :derive (fn [w] w)
                  :output-path   [:out :a]})
    (rf/reg-flow {:id     :b
                  :inputs [[:h]]
                  :derive (fn [h] h)
                  :output-path   [:out :b]})
    (let [per-frame (flows-tooling/flow-algebra-view :rf/default)]
      (is (= #{:a :b} (set (keys per-frame))))
      (is (every? has-fixed-classifications? (vals per-frame)))
      (is (= [:db [:out :a]] (:output (:a per-frame))))
      (is (= [:db [:out :b]] (:output (:b per-frame)))))))

(deftest same-flow-id-on-two-frames-projects-per-frame
  (testing "the same flow-id on two frames yields two distinct nodes (frame-scoped)"
    ;; Flows are frame-scoped — the same id may carry different :inputs /
    ;; :output-path on different frames. The view preserves the frame dimension.
    (rf/reg-frame :other {:doc "second frame for the frame-scoped view test"})
    (rf/reg-flow {:id     :shared
                  :inputs [[:a]]
                  :derive (fn [a] a)
                  :output-path   [:out :default]}
                 {:frame :rf/default})
    (rf/reg-flow {:id     :shared
                  :inputs [[:b]]
                  :derive (fn [b] b)
                  :output-path   [:out :other]}
                 {:frame :other})
    (let [view (flows-tooling/flow-algebra-view)]
      (is (= [:db [:out :default]] (get-in view [:rf/default :shared :output])))
      (is (= [:db [:out :other]]   (get-in view [:other :shared :output])))
      (is (= [:frame :rf/default]  (get-in view [:rf/default :shared :owner])))
      (is (= [:frame :other]       (get-in view [:other :shared :owner]))
          "each node names its own owning frame"))))

;; ---- source-coords / schema / doc passthrough ----------------------------

(deftest source-coords-surface-in-the-node
  (testing ":ns / :line / :file captured by reg-flow surface under :source"
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* (or w 0) (or h 0)))
                  :output-path   [:rect :area]})
    (let [node   (get-in (flows-tooling/flow-algebra-view) [:rf/default :area])
          source (:source node)]
      (is (some? source) ":source map is present when the registration carried coords")
      (is (some? (:ns source))     ":ns captured at the call site")
      (is (number? (:line source)) ":line captured at the call site")
      (is (some? (:file source))   ":file captured at the call site"))))

(deftest schema-and-doc-pass-through
  (testing ":schema and :doc supplied on the flow map surface in the node"
    (rf/reg-flow {:id     :priced
                  :doc    "a priced total"
                  :schema :app.money/amount
                  :inputs [[:price]]
                  :derive (fn [p] p)
                  :output-path   [:cart :total]})
    (let [node (get-in (flows-tooling/flow-algebra-view) [:rf/default :priced])]
      (is (= :app.money/amount (:schema node))
          "the declared output :schema surfaces as a node fact")
      (is (= "a priced total" (:doc node))))))

(deftest no-schema-or-doc-when-absent
  (testing ":schema / :doc are absent when the registration didn't supply them"
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* (or w 0) (or h 0)))
                  :output-path   [:rect :area]})
    (let [node (get-in (flows-tooling/flow-algebra-view) [:rf/default :area])]
      (is (not (contains? node :schema)))
      (is (not (contains? node :doc))))))

;; ---- registry semantics --------------------------------------------------

(deftest cleared-flow-is-removed
  (testing "(clear-flow id) removes the flow from the algebra view"
    (rf/reg-flow {:id     :a
                  :inputs [[:w]]
                  :derive (fn [w] w)
                  :output-path   [:out :a]})
    (rf/reg-flow {:id     :b
                  :inputs [[:h]]
                  :derive (fn [h] h)
                  :output-path   [:out :b]})
    (is (contains? (flows-tooling/flow-algebra-view :rf/default) :a))
    (flows/clear-flow :a)
    (let [per-frame (flows-tooling/flow-algebra-view :rf/default)]
      (is (not (contains? per-frame :a)))
      (is (contains? per-frame :b)))))
