(ns re-frame.view-render-trigger-handler-cljs-test
  "`:rf.view/render` carries the view's registration coord on the top-level
  `:rf.trace/trigger-handler` slot (Spec 009 §:rf.trace/trigger-handler:
  'Inside a view render: the view's coord'), as

    {:kind         :view
     :id           <registered-view-id>
     :source-coord {:ns <sym> :file <string> :line <int> :column <int>}}

  The `views.cljs` wrapper is the emit site, so this is CLJS-only; the other
  handler scopes are pinned in
  `implementation/core/test/re_frame/success_path_trigger_handler_test.clj`
  and `implementation/machines/test/re_frame/machine_transition_trigger_handler_test.clj`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- helpers ---------------------------------------------------------------

(def ^:private view-render-pred
  #(= :rf.view/render (:operation %)))

(defn- trigger-shape
  "`[kind id ns-symbol? file-string? line-integer?]` read off `ev`'s
  top-level `:rf.trace/trigger-handler`."
  [ev]
  (let [t (:rf.trace/trigger-handler ev)
        c (:source-coord t)]
    [(:kind t) (:id t) (symbol? (:ns c)) (string? (:file c)) (integer? (:line c))]))

(def ^:private coord (juxt :ns :file :line :column))

;; ---- :rf.view/render carries the view's registration coord -------------------

(deftest view-render-trigger-rides-at-top-level
  (testing ":rf.trace/trigger-handler on :rf.view/render is a top-level
   field, NOT nested under :tags — mirrors the error / fx-handled /
   sub-run / machine-transition shapes"
    (with-trace-recorder! [traces {:pred view-render-pred}]
      (rf/reg-view ^{:rf/id :rf2-npm2p/top-level-view} top-level-view []
        [:span "hi"])
      ((rf/view :rf2-npm2p/top-level-view))
      (let [ev (first @traces)]
        (is (= [true false]
               [(contains? ev :rf.trace/trigger-handler) (contains? (:tags ev) :rf.trace/trigger-handler)])
            ":rf.trace/trigger-handler lives at top level, NOT under :tags")))))

(deftest view-render-trigger-matches-registrar-coord
  (testing "the :source-coord under :rf.trace/trigger-handler on
   :rf.view/render equals what the registrar holds on the view's slot —
   same comparison the other scope tests do"
    (with-trace-recorder! [traces {:pred view-render-pred}]
      (rf/reg-view ^{:rf/id :rf2-npm2p/coord-view} coord-view []
        [:p "p"])
      ((rf/view :rf2-npm2p/coord-view))
      (let [reg-meta (rf/handler-meta {:source :store :kind :view :id :rf2-npm2p/coord-view})
            ev       (first @traces)]
        (is (= [true (coord reg-meta)]
               [(some? ev) (coord (-> ev :rf.trace/trigger-handler :source-coord))]))))))

(deftest each-render-carries-trigger-handler
  (testing "every :rf.view/render invocation carries the trigger-handler
   — not just the first render. The wrapper rebinds the dynamic var on
   each invocation; the slot rides every emit."
    (with-trace-recorder! [traces {:pred view-render-pred}]
      (rf/reg-view ^{:rf/id :rf2-npm2p/multi-render} multi-render [n]
        [:span "n-" n])
      (let [render (rf/view :rf2-npm2p/multi-render)]
        (render 1)
        (render 2)
        (render 3))
      (is (= (repeat 3 [:view :rf2-npm2p/multi-render true true true])
             (map trigger-shape @traces))
          "each of the three :rf.view/render traces carries the locked trigger-handler shape"))))

;; ---- programmatic registration → no coord → no trigger-handler ------------

(deftest programmatic-view-omits-trigger-on-render
  (testing "a view registered via `reg-view*` without macro-captured
   coords emits :rf.view/render with no :rf.trace/trigger-handler field —
   better no-data than poison-data (mirrors the fx, sub, cofx
   programmatic paths)"
    (with-trace-recorder! [traces {:pred view-render-pred}]
      (rf/reg-view* :rf2-npm2p/programmatic
        (fn [] [:span "x"]))
      ((rf/view :rf2-npm2p/programmatic))
      (let [ev (first @traces)]
        (is (= [true false] [(some? ev) (contains? ev :rf.trace/trigger-handler)])
            ":rf.view/render fired, and the programmatic registration's missing coord omits the field")))))
