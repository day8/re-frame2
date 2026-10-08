(ns day8.re-frame2-xray.views.resizable-table-fresco-head-cljs-test
  "The shared resizable-table's two heads: `resizable-table`, the Reagent
  `reg-view`, and `resizable-table-view`, the `rf.fresco/defview`
  boundary. Same props, one renderer; they differ only in how they resolve
  the column-widths read and the frame-bound dispatcher.

  `reagent-head-is-invalid-to-the-codec` pins the refusal a consumer panel
  meets if it mounts the Reagent head inside a boundary, which is what
  makes that mistake LOUD. `both-heads-resolve-the-same-widths` runs the
  boundary body through `re-frame.fresco.test/tree`, whose `:subs` roster
  refuses a read no fixture answers, so its fixture also pins the query the
  boundary reads.

  The fixture is the core one with `:ambient-frame nil`: the kit runs the
  body under its own probe frame, and `(rf/capture-frame)` inside a
  boundary body refuses a carried `:rf/default` stamp naming a different
  frame (`:rf.error/ambient-frame-refused`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.test :as rf.fresco.test]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.substrate.plain-atom/adapter
     :ambient-frame nil
     :init-fn       xray-test-support/reset-all!}))

(def ^:private table-id :rf.xray.test/fresco)

(def ^:private columns
  [{:id :a :label "a" :default-flex "1fr"}
   {:id :b :label "b" :default-flex "1fr"}
   {:id :c :label "c" :default-flex "1fr"}])

(def ^:private overrides
  "Width overrides, so a read that never happened produces a different
  template from one that did."
  {:a 120 :b 90})

(defn- opts []
  {:table-id  table-id
   :columns   columns
   :rows      [{:v 1} {:v 2}]
   :row-key   (fn [_row i] (str "row-" i))
   :row-cells (fn [row _i]
                (for [col columns]
                  [:div {:data-col (name (:id col))} (str (:v row))]))})

(defn- style-of [node]
  (:style (rf.fresco.test/attrs node)))

(deftest reagent-head-is-invalid-to-the-codec
  (testing "the refusal a consumer panel meets if it mounts
            `[rt/resizable-table …]` inside a boundary"
    (is (= :rf.error/fresco-bad-head
           (try
             (rf.fresco.impl.codec/as-element [rt/resizable-table (opts)])
             nil
             (catch :default e (:rf.error/id (ex-data e))))))))

(deftest both-heads-resolve-the-same-widths
  (testing "one renderer, two reads: the Reagent head resolves the slot
            through its injected `subscribe`, the boundary through
            `rf.fresco/sub`, and with the same overrides in play both
            produce the same grid template"
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray.column-widths/hydrate {table-id overrides}]))
    (let [reagent-template (-> (rf/with-frame :rf/xray (rt/resizable-table (opts)))
                               (nth 2)
                               (get-in [1 :style :grid-template-columns]))
          fresco-template  (-> (rf.fresco.test/tree
                                 [rt/resizable-table-view (opts)]
                                 {:subs {[:rf.xray.column-widths/for-table table-id] overrides}})
                               (rf.fresco.test/find #(= "grid" (:display (style-of %))))
                               style-of
                               :grid-template-columns)]
      (is (= "120px 4px 90px 4px 1fr" reagent-template fresco-template)))))
