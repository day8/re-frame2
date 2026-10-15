(ns re-frame.form-2-render-evidence-cljs-test
  "A registered Form-2 view emits its render evidence on every render, not
  only on the outer call that constructs it. Reagent and reagent-slim cache
  the inner fn a Form-2 outer call returns and call it directly on every
  later render, so the per-render scope (`:rf.view/render`,
  `:rf.view/rendered`, the deref sink) has to ride the fn the outer call
  hands back.

  Headless: `reagent.impl.component/*current-component*` is bound to one
  stand-in component object, so every call shares the instance token a
  mounted Reagent instance's renders share. The protocol is the
  substrate's: call the head once, then call the fn it returns once per
  render."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.impl.component :as reagent.component]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(defn- view-op? [ev]
  (contains? #{:rf.view/render :rf.view/rendered} (:operation ev)))

(deftest form-2-view-emits-render-evidence-on-every-render
  (testing "a Form-2 view that mounts and re-renders twice emits one
            :rf.view/render and one :rf.view/rendered per render, the first
            flagged as the mount, each carrying the inner fn's own sub read"
    (let [outer-calls (atom 0)]
      (rf/reg-sub :rf.form-2-evidence/n (fn [_ _] 7))
      (rf/reg-view* :rf.form-2-evidence/counter
        (fn counter-outer []
          (swap! outer-calls inc)
          (fn counter-inner []
            [:span @(rf/subscribe [:rf.form-2-evidence/n])])))
      (with-trace-recorder! [observed {:pred view-op? :shape :by-op}]
        (binding [reagent.component/*current-component* #js {}]
          (let [inner ((rf/view :rf.form-2-evidence/counter))]
            (dotimes [_ 3] (inner))))
        (let [rendered (:rf.view/rendered @observed)
              tags     (mapv :tags rendered)]
          (is (= {:outer-calls 1
                  :renders     3
                  :mount?      [true false false]
                  :deref-subs  (repeat 3 [[:rf.form-2-evidence/n]])
                  :elapsed-ms? [true true true]
                  :render-keys 1}
                 {:outer-calls @outer-calls
                  :renders     (count (:rf.view/render @observed))
                  :mount?      (mapv :rf.view/mount? tags)
                  :deref-subs  (map :rf.view/deref-subs tags)
                  :elapsed-ms? (mapv #(number? (:rf.view/elapsed-ms %)) tags)
                  :render-keys (count (set (map :rf.view/render-key tags)))})
              "the outer body ran once; every inner render emitted a start marker and a rendered row on one instance's render-key"))))))
