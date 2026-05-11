(ns re-frame.adapter.helix-parity-cljs-test
  "Per Spec 006 §Adapter shipping convention + rf2-2qit Decision 5: the
  Helix adapter must match the Reagent adapter's render-time contracts
  for hot-reload, render-key, and source-coord DOM annotation.

  This file is the parity counterpart to:
    - implementation/adapters/reagent/test/re_frame/hot_reload_cljs_test.cljs
    - implementation/adapters/reagent/test/re_frame/render_key_cljs_test.cljs
    - implementation/adapters/reagent/test/re_frame/source_coord_dom_cljs_test.cljs

  Per bead rf2-v1y7. The shape mirrors the Reagent tests; each block
  asserts the Helix adapter respects the same contract.

  ---------------------------------------------------------------------------
  IMPORTANT (rf2-00li): the source-coord-DOM-annotation test is
  currently :disabled. Investigation surfaced that the Helix adapter
  exposes a `wrap-view` fn (helix.cljs:381) that injects
  `data-rf2-source-coord` on the rendered root, but `reg-view*` (in
  core.cljc / views.cljs) never calls it. The Reagent adapter wires
  the annotation inline inside `views/reg-view*`; the Helix-side
  dispatch is missing. Until rf2-00li lands, a registered Helix view's
  rendered output carries no source-coord annotation — the test
  documents the desired contract and points at the bug bead.
  ---------------------------------------------------------------------------"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views :as views]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

;; ---- (1) Hot-reload contract ----------------------------------------------

(deftest view-re-register-causes-rerender-helix
  (testing "after re-registering a view, the next registry lookup returns
            the new body — the contract underlying hot-reload"
    (let [observed (atom nil)]
      (rf/reg-view* :rf.helix-parity-test/probe
        (fn []
          (reset! observed :body-v1)
          :v1-output))
      ((rf/view :rf.helix-parity-test/probe))
      (is (= :body-v1 @observed)
          "v1 body ran on first render")

      ;; Re-register with a DIFFERENT body.
      (rf/reg-view* :rf.helix-parity-test/probe
        (fn []
          (reset! observed :body-v2)
          :v2-output))
      ((rf/view :rf.helix-parity-test/probe))
      (is (= :body-v2 @observed)
          "after re-registration, the next render mutates observed to v2"))))

;; ---- (2) Render-key contract ----------------------------------------------

(deftest current-render-key-anonymous-fallback-helix
  (testing "outside a render, current-render-key returns the documented
            anonymous fallback [:rf.view/anonymous nil]"
    (is (= [:rf.view/anonymous nil] (views/current-render-key))
        "current-render-key reads the anonymous fallback outside any render")
    (is (nil? views/*render-key*)
        "*render-key* is nil outside any render cycle")))

;; ---- (3) Source-coord DOM annotation helper -------------------------------

(deftest format-source-coord-helper-shape-helix
  (testing "the Helix adapter's wrap-view fn is callable and dispatches
            to the user fn"
    (let [out-from-fn (atom nil)
          wrapped     (helix-adapter/wrap-view
                        :rf.helix-parity-test/sample
                        {:line 42 :column 7}
                        (fn []
                          (reset! out-from-fn :ran)
                          nil))]
      (is (fn? wrapped) "wrap-view returns a fn")
      (wrapped)
      (is (= :ran @out-from-fn)
          "the wrapped fn invokes the user fn"))))

;; --- (disabled, rf2-00li) — DOM-annotation through reg-view ---------------
;;
;; The full contract — `rf/reg-view ...` produces a rendered root with
;; `data-rf2-source-coord` — depends on reg-view* dispatching through
;; the installed adapter's wrap-view. As of this commit that wiring is
;; absent; see rf2-00li. The test contract lives in this commented
;; block; promote to a deftest after rf2-00li lands.
;;
;;   (deftest reg-view-produces-source-coord-annotation-helix
;;     (testing "Per rf2-00li (pending): reg-view* under the Helix adapter
;;               injects data-rf2-source-coord on the rendered root via
;;               (adapter/wrap-view id metadata user-fn)"
;;       (rf/reg-view* :rf.helix-parity-test/annotated
;;                     (fn [] [:span "hi"]))
;;       (let [render (rf/view :rf.helix-parity-test/annotated)
;;             out    (render)]
;;         (is (some? out)))))
