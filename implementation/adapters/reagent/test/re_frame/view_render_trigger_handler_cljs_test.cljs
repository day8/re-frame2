(ns re-frame.view-render-trigger-handler-cljs-test
  "Per rf2-npm2p — `:rf.trace/trigger-handler` rides the `:rf.view/render`
  trace event with the view's own registration coord.

  Spec 009 §:rf.trace/trigger-handler table — 'Inside a view render:
  the view's coord'. `views.cljs`'s `reg-view*` wraps the user render-fn
  in a frame-aware-view that rebinds `*current-trigger-handler*` to the
  view's `trigger-handler-from-meta` value around the body, and
  `emit-render-trace!` fires `:rf.view/render` from inside that binding.
  The trace event therefore carries the view's registration coord on
  the top-level `:rf.trace/trigger-handler` slot — Xray's event-detail
  panel and re-frame2-pair's jump-to-source UX render click-to-jump links from
  this field for every trace in a cascade, including view renders.

  Locked shape (per rf2-3nn8 / rf2-lf84g):

    {:kind         :view
     :id           <registered-view-id>
     :source-coord {:ns <sym> :file <string> :line <int> :column <int>}}

  CLJS-specific — the Reagent adapter / `views.cljs` wrapper is the
  emit site; the JVM core has no view-render machinery. Mirror tests
  for the other handler scopes (event, fx, sub, machine, cofx) live in
  `implementation/core/test/re_frame/success_path_trigger_handler_test.clj`
  and `implementation/machines/test/re_frame/machine_transition_trigger_handler_test.clj`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]
                   [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- helpers ---------------------------------------------------------------

(def ^:private view-render-pred
  #(= :rf.view/render (:operation %)))

(defn- assert-trigger-shape
  "Assert the value at top-level `:rf.trace/trigger-handler` on `ev`
  carries the locked shape — `:kind`, `:id`, and a `:source-coord` map
  with at least `:ns` / `:file` / `:line`."
  [ev expected-id]
  (let [t (:rf.trace/trigger-handler ev)]
    (is (some? t)
        (str "expected :rf.trace/trigger-handler on " (:operation ev)))
    (is (= :view (:kind t)) "kind matches :view")
    (is (= expected-id (:id t)) "id matches the registered view-id")
    (let [c (:source-coord t)]
      (is (map? c) ":source-coord present")
      (is (symbol? (:ns c))    ":ns is a symbol")
      (is (string? (:file c))  ":file is a string")
      (is (integer? (:line c)) ":line is an integer"))))

;; ---- :rf.view/render carries the view's registration coord -------------------

(deftest view-render-carries-trigger-handler
  (testing ":rf.view/render rides the view's own registration coord —
   Xray / re-frame2-pair want jump-to-source on a view render trace to land
   on the reg-view site, the same way fx-handled / sub-run / machine-
   transition tests already pin"
    (with-trace-recorder! [traces {:pred view-render-pred}]
      (rf/reg-view ^{:rf/id :rf2-npm2p/sample} sample-view []
        [:span "ok"])
      (let [render (rf/view :rf2-npm2p/sample)]
        (render))
      (is (= 1 (count @traces)) "exactly one :rf.view/render trace fired")
      (assert-trigger-shape (first @traces) :rf2-npm2p/sample))))

(deftest view-render-trigger-rides-at-top-level
  (testing ":rf.trace/trigger-handler on :rf.view/render is a top-level
   field, NOT nested under :tags — mirrors the error / fx-handled /
   sub-run / machine-transition shapes"
    (with-trace-recorder! [traces {:pred view-render-pred}]
      (rf/reg-view ^{:rf/id :rf2-npm2p/top-level-view} top-level-view []
        [:span "hi"])
      ((rf/view :rf2-npm2p/top-level-view))
      (let [ev (first @traces)]
        (is (some? ev))
        (is (contains? ev :rf.trace/trigger-handler)
            ":rf.trace/trigger-handler lives at top level")
        (is (not (contains? (:tags ev) :rf.trace/trigger-handler))
            ":rf.trace/trigger-handler does NOT live under :tags")))))

(deftest view-render-trigger-matches-registrar-coord
  (testing "the :source-coord under :rf.trace/trigger-handler on
   :rf.view/render equals what the registrar holds on the view's slot —
   same comparison the other scope tests do"
    (with-trace-recorder! [traces {:pred view-render-pred}]
      (rf/reg-view ^{:rf/id :rf2-npm2p/coord-view} coord-view []
        [:p "p"])
      ((rf/view :rf2-npm2p/coord-view))
      (let [reg-meta (rf/handler-meta :view :rf2-npm2p/coord-view)
            ev       (first @traces)
            coord    (-> ev :rf.trace/trigger-handler :source-coord)]
        (is (some? ev))
        (is (= (:ns     reg-meta) (:ns coord)))
        (is (= (:file   reg-meta) (:file coord)))
        (is (= (:line   reg-meta) (:line coord)))
        (is (= (:column reg-meta) (:column coord)))))))

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
      (is (= 3 (count @traces)) "three :rf.view/render traces fired")
      (doseq [ev @traces]
        (assert-trigger-shape ev :rf2-npm2p/multi-render)))))

;; ---- programmatic registration → no coord → no trigger-handler ------------

(deftest programmatic-view-omits-trigger-on-render
  (testing "a view registered via `reg-view*` without macro-captured
   coords emits :rf.view/render with no :rf.trace/trigger-handler field —
   better no-data than poison-data (mirrors the fx, sub, cofx
   programmatic paths)"
    (with-trace-recorder! [traces {:pred view-render-pred}]
      ;; `reg-view*` (the plain-fn surface, not the macro) bypasses
      ;; the source-coord capture path entirely. The trigger-handler
      ;; builder gets an empty meta map and yields nil, so the slot
      ;; is omitted from the emitted event.
      (rf/reg-view* :rf2-npm2p/programmatic
        (fn [] [:span "x"]))
      ((rf/view :rf2-npm2p/programmatic))
      (let [ev (first @traces)]
        (is (some? ev) ":rf.view/render fired")
        (is (not (contains? ev :rf.trace/trigger-handler))
            "programmatic view-registration → no coord → field omitted")))))
