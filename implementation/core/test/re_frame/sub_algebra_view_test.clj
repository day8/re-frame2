(ns re-frame.sub-algebra-view-test
  "The STATIC algebra view of subscriptions, `re-frame.subs.tooling/sub-algebra-view`
  (spec/Derivations.md; `:rf/derivation-node` in spec/Spec-Schemas.md): every
  registered sub lowers to a `:derivation` whose ephemeral output fact is
  evaluated `:on-demand` and owned by its `:subscription-cache-entry`, with
  per-kind declared inputs. There is no public accessor: Xray and the
  conformance fixtures name the bundle-isolated tooling sibling directly.

  The algebra is posture-independent. The auto-captured `:source` coords and
  `:doc` are reflection metadata elided in production, so their assertions sit
  behind `rf.interop/debug-enabled?`; `:schema` is load-bearing and is asserted
  in both postures."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.subs :as rf.subs]
            [re-frame.subs.tooling :as rf.subs.tooling]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest layer-1-sub-exposes-its-algebra-view
  ;; No `:input-producer`, `:schema` or `:doc` when the registration has none.
  (rf/reg-sub :cart/items (fn [db _] (get-in db [:cart :items])))
  (let [node ((rf.subs.tooling/sub-algebra-view) :cart/items)]
    (is (= {:id            :cart/items
            :kind          :derivation
            :output        [:fact :cart/items]
            :storage       :ephemeral
            :evaluation    :on-demand
            :lifecycle     :subscription-cache-entry
            :materialized? false
            :source-form   {:kind :reg-sub :id :cart/items}
            :inputs        [[:db []]]}
           (dissoc node :derive :source)))
    (is (fn? (:derive node)) "the body fn is an opaque :derive token")))

(deftest each-sub-kind-lowers-its-declared-inputs
  ;; Static inputs keep declaration order and query-vector args; the framework
  ;; runtime and frame-state subs read their partition whole.
  (rf/reg-sub :k/a (fn [db _] (:a db)))
  (rf/reg-sub :k/b (fn [db [_ _arg]] (:b db)))
  (rf/reg-sub :k/static {:inputs [[:k/b :arg] [:k/a]]} (fn [[b a] _] [b a]))
  (rf.subs/reg-runtime-sub :rf.route/params
                           (fn [runtime-db _] (get-in runtime-db [:rf.runtime/routing :current :params])))
  (rf.subs/reg-frame-state-sub :rf/whole-state (fn [frame-state _] frame-state))
  (let [view (rf.subs.tooling/sub-algebra-view)]
    (is (= {:k/a             [[:db []]]
            :k/static        [[:sub [:k/b :arg]] [:sub [:k/a]]]
            :rf.route/params [[:runtime []]]
            :rf/whole-state  [[:frame-state []]]}
           (into {} (map (fn [k] [k (:inputs (view k))]))
                 [:k/a :k/static :rf.route/params :rf/whole-state])))))

(deftest parametric-sub-reports-the-parametric-marker
  ;; Realized edges depend on the concrete outer query-v, so the static graph
  ;; reports the marker and never fabricates edges (Derivations §Static and
  ;; live graphs).
  (rf/reg-sub :article/by-id (fn [db [_ id]] (get-in db [:articles id])))
  (rf/reg-sub :article/page
              {:inputs (fn [[_ id]] [[:article/by-id id]])}
              (fn [[article] [_ id]] {:id id :article article}))
  (let [node ((rf.subs.tooling/sub-algebra-view) :article/page)]
    (is (= [:parametric true] [(:inputs node) (fn? (:input-producer node))]))))

(deftest schema-and-doc-pass-through
  (rf/reg-sub :priced
              {:doc    "a priced item"
               :schema :app.money/amount}
              (fn [db _] (:price db)))
  (let [node ((rf.subs.tooling/sub-algebra-view) :priced)]
    (is (= :app.money/amount (:schema node)))
    (when rf.interop/debug-enabled?
      (let [{:keys [ns line file]} (:source node)]
        (is (= ["a priced item" true true true]
               [(:doc node) (some? ns) (number? line) (some? file)]))))))
