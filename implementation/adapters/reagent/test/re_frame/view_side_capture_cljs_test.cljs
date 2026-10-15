(ns re-frame.view-side-capture-cljs-test
  "View traces report per-view reads, mount identity and disposal; Form-2 render evidence covers rerenders."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views :as rf.views]
            [re-frame.epoch]) ;; load so :epoch/run-cause hook is bound
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(defn- in-op-set [op-set]
  (fn [ev] (contains? op-set (:operation ev))))

(deftest rf-view-rendered-carries-deref-subs-the-view-reads
  (testing ":rf.view/rendered carries :rf.view/deref-subs — the query-vectors THIS
   view deref'd during its render (its OWN read-set), so a consumer can
   filter the cascade-wide changed subs down to this view's reasons"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/rendered})
                                     :shape :by-op}]
      (rf/reg-sub :rf2-9hoos/a (fn [_ _] 1))
      (rf/reg-sub :rf2-9hoos/b (fn [_ _] 2))
      (rf/reg-view ^{:rf/id :rf2-9hoos/reader} reader-view []
        @(rf/subscribe [:rf2-9hoos/a])
        @(rf/subscribe [:rf2-9hoos/b])
        [:span "ok"])
      ((rf/view :rf2-9hoos/reader))
      (let [subs (get-in (first (:rf.view/rendered @observed)) [:tags :rf.view/deref-subs])]
        (is (= [true #{[:rf2-9hoos/a] [:rf2-9hoos/b]}] [(vector? subs) (set subs)])
            ":rf.view/deref-subs is a vector listing exactly the two subs the view deref'd")))))

(deftest deref-subs-is-per-view-not-cascade-wide
  (testing ":rf.view/deref-subs is scoped to the view that read the sub — a sub a
   sibling view (or the handler) reads does NOT bleed into this view's
   read-set, unlike the cascade-wide :rf.view/cause-subs"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/rendered})
                                     :shape :by-op}]
      (rf/reg-sub :rf2-9hoos/mine    (fn [_ _] :mine))
      (rf/reg-sub :rf2-9hoos/sibling (fn [_ _] :sibling))
      (rf/reg-view ^{:rf/id :rf2-9hoos/only-mine} only-mine-view []
        @(rf/subscribe [:rf2-9hoos/mine])
        [:span "x"])
      (rf/reg-view ^{:rf/id :rf2-9hoos/only-sibling} only-sibling-view []
        @(rf/subscribe [:rf2-9hoos/sibling])
        [:span "y"])
      ((rf/view :rf2-9hoos/only-mine))
      ((rf/view :rf2-9hoos/only-sibling))
      (let [evs (:rf.view/rendered @observed)
            mine-ev (first (filter #(= :rf2-9hoos/only-mine
                                       (get-in % [:tags :rf.view/id])) evs))
            sib-ev  (first (filter #(= :rf2-9hoos/only-sibling
                                       (get-in % [:tags :rf.view/id])) evs))]
        (is (= [[[:rf2-9hoos/mine]] [[:rf2-9hoos/sibling]]]
               [(get-in mine-ev [:tags :rf.view/deref-subs]) (get-in sib-ev [:tags :rf.view/deref-subs])])
            "each view's read-set is exactly its own sub — no bleed")))))

(deftest structural-render-has-no-deref-subs
  (testing "a view that reads no subs (a pure structural render) omits
   :rf.view/deref-subs — the consumer reads its absence as 'parent re-render',
   the unnamed structural reason"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/rendered})
                                     :shape :by-op}]
      (rf/reg-view ^{:rf/id :rf2-9hoos/structural} structural-view []
        [:span "no subs"])
      ((rf/view :rf2-9hoos/structural))
      (let [ev (first (:rf.view/rendered @observed))]
        (is (= [true false] [(some? ev) (contains? (:tags ev) :rf.view/deref-subs)])
            ":rf.view/rendered fired, and :rf.view/deref-subs is omitted when the view derefs no subs")))))

(deftest distinct-instances-each-mount
  (testing "two distinct component instances (distinct instance-tokens)
   each report :rf.view/mount? true on their first render — headless direct
   invocation mints a fresh token per call, mirroring per-mount-fresh"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/rendered})
                                     :shape :by-op}]
      (rf/reg-view ^{:rf/id :rf2-9hoos/two-mounts} two-mounts-view []
        [:span "x"])
      (let [render (rf/view :rf2-9hoos/two-mounts)]
        (render)   ;; instance A — fresh token → mount
        (render))  ;; instance B — fresh token → mount
      (let [evs (:rf.view/rendered @observed)]
        (is (= [true true] (mapv #(get-in % [:tags :rf.view/mount?]) evs))
            "both fresh instances report :rf.view/mount? true")))))

(deftest install-unmount-hook-fires-emit-on-reaction-dispose
  (testing "install-unmount-hook! builds a lifecycle reaction whose
   disposal fires :rf.view/unmounted — the per-render-instance
   reaction-dispose mechanism that, on a real Reagent mount, the
   component's render reaction disposes on componentWillUnmount. Driven
   here by disposing the reaction directly (no DOM), mirroring
   spine-dispose-cljs-test."
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/unmounted})
                                     :shape :by-op}]
      (let [rea (rf.views/install-unmount-hook! :rf2-9hoos/lifecycle
                                             [:rf2-9hoos/lifecycle 1]
                                             :rf/default)]
        (is (empty? (:rf.view/unmounted @observed))
            "no unmount emit before disposal")
        @rea
        (rf.interop/dispose! rea)
        (is (= {:rf.view/id         :rf2-9hoos/lifecycle
                :frame              :rf/default
                :rf.view/render-key [:rf2-9hoos/lifecycle 1]}
               (-> (:rf.view/unmounted @observed) first :tags
                   (select-keys [:rf.view/id :frame :rf.view/render-key])))
            ":rf.view/unmounted fired on reaction disposal")))))
