(ns re-frame.doc-metadata-prod-elision-test
  "Spec 001 §Production elision contract: `:doc` is the one pure-documentation
  registration-metadata key. With the debug gate off, `rf/handler-meta` carries
  no `:doc`; every load-bearing key (`:schema`, `:tags`, …) is retained. In dev,
  `:doc` is retained for tooling. The CLJS bundle-string absence of `:doc`
  rides `scripts/check-elision.cjs`.

  ## Posture split

  The prod half runs in both `clojure -M:test` (gate modelled with
  `with-redefs`) and `scripts/test-core-prod-gate.sh` (gate really off). The dev
  half — `:doc` retained — sits inside a `(when rf.interop/debug-enabled? …)`
  arm."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(deftest doc-stripped-from-handler-meta-under-disabled-debug-gate
  (with-redefs [rf.interop/debug-enabled? false]
    (rf/reg-event :rf2-9wwkcm/prod-event
                  {:doc "elided in prod" :schema :int :tags #{:probe}}
                  (fn [{:keys [db]} _] {:db db}))
    (is (= {:schema :int :tags #{:probe}}
           (select-keys (rf/handler-meta {:source :store :kind :event :id :rf2-9wwkcm/prod-event})
                        [:doc :schema :tags])))))

(deftest doc-retained-in-handler-meta-under-enabled-debug-gate
  (rf/reg-event :rf2-9wwkcm/dev-event
                {:doc "kept in dev"}
                (fn [{:keys [db]} _] {:db db}))
  (let [meta (rf/handler-meta {:source :store :kind :event :id :rf2-9wwkcm/dev-event})]
    ;; Both postures: the doc-only metadata map, rewritten to `{}` by the reg-*
    ;; macro under the gate, still registers the handler the call site supplied.
    (is (= {:db {:seen true}}
           ((:handler-fn meta) {:db {:seen true}} [:rf2-9wwkcm/dev-event])))
    (when rf.interop/debug-enabled?
      (is (= "kept in dev" (:doc meta))))))

(deftest doc-stripped-uniformly-across-reg-surfaces
  ;; The strip covers every reg-* surface, not just events.
  (with-redefs [rf.interop/debug-enabled? false]
    (rf/reg-sub :rf2-9wwkcm/prod-sub {:doc "sub doc elided"} (fn [db _] db))
    (rf/reg-fx :rf2-9wwkcm/prod-fx {:doc "fx doc elided"} (fn [_] nil))
    (rf/reg-cofx :rf2-9wwkcm/prod-cofx {:doc "cofx doc elided"} (fn [ctx] ctx))
    (doseq [[kind id] {:sub  :rf2-9wwkcm/prod-sub
                       :fx   :rf2-9wwkcm/prod-fx
                       :cofx :rf2-9wwkcm/prod-cofx}]
      (is (not (contains? (rf/handler-meta {:source :store :kind kind :id id}) :doc))
          (str kind)))))

(deftest pure-documentation-keys-is-closed-to-doc
  ;; Spec 001's elidable set. Widening it to a load-bearing key such as
  ;; `:sensitive?` would strip redaction metadata in production.
  (is (= #{:doc} rf.registrar/pure-documentation-keys)))
