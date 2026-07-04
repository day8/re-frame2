(ns re-frame.story-layout-debug-cljs-test
  "CLJS smoke tests for Stage 6 (rf2-zhwd) — layout-debug decorator
  trio. JVM coverage in `re-frame.story-layout-debug-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.layout-debug :as layout-debug]
            [re-frame.story.ui.panels :as panels]
            [re-frame.test-helpers :as th]))

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (layout-debug/reset-wrap-counter!)
  (reset! panels/layout-debug-toggles {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- registration --------------------------------------------------------

(deftest three-decorators-register
  (testing "the three layout-debug decorators register at boot"
    (let [decs (story/registrations :decorator)]
      (is (contains? decs :rf.story/layout-debug.measure))
      (is (contains? decs :rf.story/layout-debug.outline))
      (is (contains? decs :rf.story/layout-debug.pseudo)))))

(deftest public-ids-exposed
  (testing "the three public id Vars match the canonical ids"
    (is (= :rf.story/layout-debug.measure story/layout-debug-measure-id))
    (is (= :rf.story/layout-debug.outline story/layout-debug-outline-id))
    (is (= :rf.story/layout-debug.pseudo  story/layout-debug-pseudo-id))))

;; ---- decorator resolution -----------------------------------------------

(deftest decorator-resolves-as-hiccup
  (testing "a variant referencing layout-debug.outline resolves to :hiccup"
    (story/reg-variant* :story.x/outlined
                        {:decorators [[:rf.story/layout-debug.outline]]})
    (let [pack (decorators/resolve-decorators :story.x/outlined)]
      (is (= 1 (count (:hiccup pack))))
      (is (empty? (:errors pack))))))

;; ---- wrap shape ---------------------------------------------------------

(deftest measure-wrap-returns-style-block
  (testing "the measure wrap fn produces [:div {:class \"…\"} [:style ...] body]"
    (let [wrap (-> (story/handler-meta :decorator :rf.story/layout-debug.measure)
                    :wrap)
          out  (wrap [:span "x"] {})]
      (is (vector? out))
      (is (= :div (first out)))
      (let [attrs (second out)]
        (is (true? (:data-rf-story-measure attrs)))
        (is (string? (:class attrs)))))))

(deftest pseudo-wrap-ref-args
  (testing "pseudo wrap reads ref-args via :decorator/args"
    (let [wrap (-> (story/handler-meta :decorator :rf.story/layout-debug.pseudo)
                    :wrap)
          out  (wrap [:span "x"] {:decorator/args [#{:hover :focus}]})
          attrs (second out)]
      (is (re-find #"force-focus" (:class attrs)))
      (is (re-find #"force-hover" (:class attrs))))))

;; ---- rf2-yv8tsd: per-overlay toggle state + DOM + variant-state ---------
;;
;; 015-Test-Coverage.md:120 (Layout-debug overlays) owes: assert each
;; overlay toggles its DOM/aria state AND that the variant's own state stays
;; unchanged across toggles. The layout-debug panel (`panels/layout-debug-
;; view`) is the toggle surface — a checkbox per decorator id. The active
;; set lives in `panels/layout-debug-toggles`, a per-process ratom keyed by
;; variant-id and wholly independent of any variant's frame/app-db, so
;; toggling an overlay provably CANNOT mutate variant state. Per the locked
;; Story testing posture these are CLJS unit tests over the pure toggle
;; state + the rendered checkbox DOM (the `:checked` attr the browser would
;; show), not a Playwright spec.

(defn- render-layout-debug-panel
  "Render the form-2 layout-debug panel for `variant-id` to hiccup."
  [variant-id]
  (th/expand-tree [panels/layout-debug-view variant-id]))

(defn- overlay-checkboxes
  "The three overlay checkbox nodes in render order (measure / outline /
  pseudo)."
  [tree]
  (th/find-all-by-attr tree :type "checkbox"))

;; ---- pure per-overlay toggle state --------------------------------------

(deftest each-overlay-toggles-independently
  (testing "toggling one overlay id flips only that id in the active set;
            a second overlay toggles independently; re-toggling clears it"
    (let [vid :story.x/probe]
      (is (= #{} (panels/active-layout-debug-decorators vid))
          "fresh variant has no overlays active")
      ;; Toggle outline on.
      (panels/toggle-layout-debug! vid layout-debug/id-outline)
      (is (= #{layout-debug/id-outline}
             (panels/active-layout-debug-decorators vid)))
      ;; Toggle measure on — outline stays on (independent).
      (panels/toggle-layout-debug! vid layout-debug/id-measure)
      (is (= #{layout-debug/id-outline layout-debug/id-measure}
             (panels/active-layout-debug-decorators vid)))
      ;; Re-toggle outline off — measure survives.
      (panels/toggle-layout-debug! vid layout-debug/id-outline)
      (is (= #{layout-debug/id-measure}
             (panels/active-layout-debug-decorators vid))))))

(deftest overlay-toggles-are-per-variant-isolated
  (testing "toggling variant A's overlay does not leak into variant B's set"
    (panels/toggle-layout-debug! :story.x/a layout-debug/id-outline)
    (is (= #{layout-debug/id-outline}
           (panels/active-layout-debug-decorators :story.x/a)))
    (is (= #{} (panels/active-layout-debug-decorators :story.x/b))
        "sibling variant's overlay set is untouched")))

;; ---- DOM: the checkbox reflects the active-overlay state ----------------

(deftest overlay-checkboxes-reflect-active-set-in-dom
  (testing "the panel renders one checkbox per overlay; :checked mirrors the
            active set (the DOM state the browser toggle would show)"
    (let [vid :story.x/probe]
      ;; Nothing active → all three checkboxes unchecked.
      (let [boxes (overlay-checkboxes (render-layout-debug-panel vid))]
        (is (= 3 (count boxes)) "one checkbox per layout-debug overlay")
        (is (= [false false false]
               (mapv #(boolean (get (second %) :checked)) boxes))
            "all overlays render unchecked before any toggle"))
      ;; Toggle the OUTLINE overlay (render order: measure, outline, pseudo).
      (panels/toggle-layout-debug! vid layout-debug/id-outline)
      (let [boxes (overlay-checkboxes (render-layout-debug-panel vid))]
        (is (= [false true false]
               (mapv #(boolean (get (second %) :checked)) boxes))
            "only the outline checkbox flips to :checked=true in the DOM")))))

;; ---- variant state is unchanged across overlay toggles ------------------

(deftest overlay-toggles-do-not-mutate-variant-state
  (testing "toggling overlays leaves the variant's own state untouched — the
            toggle set lives in a separate per-process ratom, not the
            variant's frame/app-db (rf2-yv8tsd — 'variant state unchanged')"
    (let [vid :story.x/counted]
      (story/reg-variant* vid {:args {:n 7}
                               :events [[:set-thing 1]]})
      (let [body-before (story/handler-meta :variant vid)]
        ;; Toggle every overlay on, then off again.
        (doseq [id [layout-debug/id-measure
                    layout-debug/id-outline
                    layout-debug/id-pseudo]]
          (panels/toggle-layout-debug! vid id))
        (doseq [id [layout-debug/id-measure
                    layout-debug/id-outline
                    layout-debug/id-pseudo]]
          (panels/toggle-layout-debug! vid id))
        (is (= #{} (panels/active-layout-debug-decorators vid))
            "overlays round-tripped back to empty")
        (is (= body-before (story/handler-meta :variant vid))
            "the registered variant body is byte-identical after the toggles —
             overlay state never touches the variant's own registration")))))
