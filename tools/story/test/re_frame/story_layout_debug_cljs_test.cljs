(ns re-frame.story-layout-debug-cljs-test
  "CLJS smoke tests for Stage 6 (rf2-zhwd) — layout-debug decorator
  trio. JVM coverage in `re-frame.story-layout-debug-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.decorators :as rf.story.decorators]
            [re-frame.story.layout-debug :as rf.story.layout-debug]
            [re-frame.story.ui.panels :as rf.story.ui.panels]
            [re-frame.test-helpers :as rf.test-helpers]))

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.story.layout-debug/reset-wrap-counter!)
  (reset! rf.story.ui.panels/layout-debug-toggles {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- registration --------------------------------------------------------

(deftest three-decorators-register
  (testing "the three layout-debug decorators register at boot"
    (let [decs (rf.story/registrations :decorator)]
      (is (contains? decs :rf.story/layout-debug.measure))
      (is (contains? decs :rf.story/layout-debug.outline))
      (is (contains? decs :rf.story/layout-debug.pseudo)))))

(deftest public-ids-exposed
  (testing "the three public id Vars match the canonical ids"
    (is (= :rf.story/layout-debug.measure rf.story/layout-debug-measure-id))
    (is (= :rf.story/layout-debug.outline rf.story/layout-debug-outline-id))
    (is (= :rf.story/layout-debug.pseudo  rf.story/layout-debug-pseudo-id))))

;; ---- decorator resolution -----------------------------------------------

(deftest decorator-resolves-as-hiccup
  (testing "a variant referencing layout-debug.outline resolves to :hiccup"
    (rf.story/reg-variant* :story.x/outlined
                        {:decorators [[:rf.story/layout-debug.outline]]})
    (let [pack (rf.story.decorators/resolve-decorators :story.x/outlined)]
      (is (= 1 (count (:hiccup pack))))
      (is (empty? (:errors pack))))))

;; ---- wrap shape ---------------------------------------------------------

(deftest measure-wrap-returns-style-block
  (testing "the measure wrap fn produces [:div {:class \"…\"} [:style ...] body]"
    (let [wrap (-> (rf.story/handler-meta :decorator :rf.story/layout-debug.measure)
                    :wrap)
          out  (wrap [:span "x"] {})]
      (is (vector? out))
      (is (= :div (first out)))
      (let [attrs (second out)]
        (is (true? (:data-rf-story-measure attrs)))
        (is (string? (:class attrs)))))))

(deftest pseudo-wrap-ref-args
  (testing "pseudo wrap reads ref-args via :decorator/args"
    (let [wrap (-> (rf.story/handler-meta :decorator :rf.story/layout-debug.pseudo)
                    :wrap)
          out  (wrap [:span "x"] {:decorator/args [#{:hover :focus}]})
          attrs (second out)]
      (is (re-find #"force-focus" (:class attrs)))
      (is (re-find #"force-hover" (:class attrs))))))

;; ---- rf2-yv8tsd: per-overlay toggle state + DOM + variant-state ---------
;;
;; 015-Test-Coverage.md:120 (Layout-debug overlays) owes: assert each
;; overlay toggles its DOM/aria state AND that the variant's own state stays
;; unchanged across toggles. The layout-debug panel (`rf.story.ui.panels/layout-debug-
;; view`) is the toggle surface — a checkbox per decorator id. The active
;; set lives in `rf.story.ui.panels/layout-debug-toggles`, a per-process ratom keyed by
;; variant-id and wholly independent of any variant's frame/app-db, so
;; toggling an overlay provably CANNOT mutate variant state. Per the locked
;; Story testing posture these are CLJS unit tests over the pure toggle
;; state + the rendered checkbox DOM (the `:checked` attr the browser would
;; show), not a Playwright spec.

(defn- render-layout-debug-panel
  "Render the form-2 layout-debug panel for `variant-id` to hiccup."
  [variant-id]
  (rf.test-helpers/expand-tree [rf.story.ui.panels/layout-debug-view variant-id]))

(defn- overlay-checkboxes
  "The three overlay checkbox nodes in render order (measure / outline /
  pseudo)."
  [tree]
  (rf.test-helpers/find-all-by-attr tree :type "checkbox"))

;; ---- pure per-overlay toggle state --------------------------------------

(deftest each-overlay-toggles-independently
  (testing "toggling one overlay id flips only that id in the active set;
            a second overlay toggles independently; re-toggling clears it"
    (let [vid :story.x/probe]
      (is (= #{} (rf.story.ui.panels/active-layout-debug-decorators vid))
          "fresh variant has no overlays active")
      ;; Toggle outline on.
      (rf.story.ui.panels/toggle-layout-debug! vid rf.story.layout-debug/id-outline)
      (is (= #{rf.story.layout-debug/id-outline}
             (rf.story.ui.panels/active-layout-debug-decorators vid)))
      ;; Toggle measure on — outline stays on (independent).
      (rf.story.ui.panels/toggle-layout-debug! vid rf.story.layout-debug/id-measure)
      (is (= #{rf.story.layout-debug/id-outline rf.story.layout-debug/id-measure}
             (rf.story.ui.panels/active-layout-debug-decorators vid)))
      ;; Re-toggle outline off — measure survives.
      (rf.story.ui.panels/toggle-layout-debug! vid rf.story.layout-debug/id-outline)
      (is (= #{rf.story.layout-debug/id-measure}
             (rf.story.ui.panels/active-layout-debug-decorators vid))))))

(deftest overlay-toggles-are-per-variant-isolated
  (testing "toggling variant A's overlay does not leak into variant B's set"
    (rf.story.ui.panels/toggle-layout-debug! :story.x/a rf.story.layout-debug/id-outline)
    (is (= #{rf.story.layout-debug/id-outline}
           (rf.story.ui.panels/active-layout-debug-decorators :story.x/a)))
    (is (= #{} (rf.story.ui.panels/active-layout-debug-decorators :story.x/b))
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
      (rf.story.ui.panels/toggle-layout-debug! vid rf.story.layout-debug/id-outline)
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
      (rf.story/reg-variant* vid {:args {:n 7}
                               :setup [[:set-thing 1]]})
      (let [body-before (rf.story/handler-meta :variant vid)]
        ;; Toggle every overlay on, then off again.
        (doseq [id [rf.story.layout-debug/id-measure
                    rf.story.layout-debug/id-outline
                    rf.story.layout-debug/id-pseudo]]
          (rf.story.ui.panels/toggle-layout-debug! vid id))
        (doseq [id [rf.story.layout-debug/id-measure
                    rf.story.layout-debug/id-outline
                    rf.story.layout-debug/id-pseudo]]
          (rf.story.ui.panels/toggle-layout-debug! vid id))
        (is (= #{} (rf.story.ui.panels/active-layout-debug-decorators vid))
            "overlays round-tripped back to empty")
        (is (= body-before (rf.story/handler-meta :variant vid))
            "the registered variant body is byte-identical after the toggles —
             overlay state never touches the variant's own registration")))))
