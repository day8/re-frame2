(ns re-frame.story.ui.render-shell-cljs-test
  "Render-shell recovery and loader feedback (spec/003 §Shell lifecycle,
  spec/015 §Render shell scenarios):

  - a throwing `:hiccup` decorator is caught by `safe-decorated-view`,
    which returns an inline error block AROUND the uncoated view — the
    'never blank the canvas' rule;
  - `loader-incomplete-record` builds the `:rf.error/loader-incomplete`
    projection the canvas surfaces;
  - the grid's `safe-render-cell` names an unregistered substrate inline;
  - `render-decorated-view`, the seam the `render-variant` host shares
    with the canvas, applies the variant's decorators."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.plan       :as rf.story.plan]
            [re-frame.story.runtime    :as rf.story.runtime]
            [re-frame.story.ui.canvas  :as rf.story.ui.canvas]
            [re-frame.story.ui.multi-substrate :as rf.story.ui.multi-substrate]
            [re-frame.story.ui.state   :as rf.story.ui.state]
            [re-frame.subs             :as rf.subs]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  ;; Re-register the framework `:rf/machine` sub after the registrar clear.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- helpers -------------------------------------------------------------

(defn- hiccup-text-flatten
  "Every string node in a hiccup tree, for substring assertions."
  [hiccup]
  (->> (tree-seq coll? seq hiccup)
       (filter string?)))

;; ===========================================================================
;; error-boundary: decorator wrap throws
;; ===========================================================================

(deftest decorator-wrap-throw-recovered-as-inline-error
  (testing "a :wrap fn that throws does NOT unmount the shell: the error
            block names the thrown message and the offending decorator id,
            and embeds the uncoated view"
    (let [boom-dec   {:id   :crashing-wrap
                      :body {:wrap (fn [_body _args]
                                     (throw (ex-info "wrap exploded"
                                                     {:where :under-test})))}}
          result     (rf.story.ui.canvas/safe-decorated-view [:div.user "user view"] [boom-dec] {})
          text-bits  (hiccup-text-flatten result)]
      ;; The marker `one-passing-decorator-wraps-as-expected` relies on
      ;; being absent from a happy-path wrap.
      (is (some #(re-find #"Decorator wrap threw" %) text-bits))
      (is (some #(re-find #"wrap exploded" %) text-bits))
      (is (some #(re-find #"crashing-wrap" %) text-bits))
      (is (some #(= "user view" %) text-bits)
          "the user's view renders uncoated below the error block"))))

(deftest decorator-wrap-throw-multiple-decorators-names-stack
  (testing "when ONE of several :hiccup decorators throws, the error
            projection names the FULL stack so the user can isolate it"
    (let [good-1     {:id   :outer-wrap
                      :body {:wrap (fn [body _] [:div.outer body])}}
          boom       {:id   :middle-wrap
                      :body {:wrap (fn [_ _]
                                     (throw (ex-info "middle threw" {})))}}
          good-2     {:id   :inner-wrap
                      :body {:wrap (fn [body _] [:div.inner body])}}
          text-bits  (hiccup-text-flatten
                       (rf.story.ui.canvas/safe-decorated-view [:div.user "view"] [good-1 boom good-2] {}))]
      (doseq [id [:outer-wrap :middle-wrap :inner-wrap]]
        (is (some #(re-find (re-pattern (name id)) %) text-bits)
            (str "decorator id " id " appears in the error projection"))))))

(deftest no-decorators-passes-view-unchanged
  (let [view [:div.user "user view"]]
    (is (= view (rf.story.ui.canvas/safe-decorated-view view [] {})))))

(deftest one-passing-decorator-wraps-as-expected
  (testing "one decorator, no throws — the wrap result is the whole result,
            with no error projection"
    (let [dec  {:id   :ok-wrap
                :body {:wrap (fn [body _]
                               [:div.wrapper body])}}
          view [:div.user "view"]]
      (is (= [:div.wrapper view]
             (rf.story.ui.canvas/safe-decorated-view view [dec] {}))))))

;; ===========================================================================
;; loader-incomplete projection
;; ===========================================================================

(deftest loader-incomplete-record-shape-pinned
  (testing "the slots the canvas's loading-affordance render reads"
    (let [record (#'rf.story.runtime/loader-incomplete-record
                   :story.slow.loader/probe {:loaders-complete-when :probe/never})]
      (is (= {:assertion  :rf.error/loader-incomplete
              :variant-id :story.slow.loader/probe
              :phase      :phase-1-loaders
              :predicate  :probe/never
              :passed?    false}
             (select-keys record [:assertion :variant-id :phase :predicate :passed?])))
      (is (string? (:reason record))
          ":reason is the human-readable string the canvas surfaces verbatim"))))

(deftest loader-incomplete-record-without-predicate-still-builds
  (testing "a variant with :loaders but no :loaders-complete-when still
            builds the record, with a nil :predicate"
    (let [record (#'rf.story.runtime/loader-incomplete-record
                   :story.slow.loader.no-pred/probe {:loaders [[:probe/start]]})]
      (is (= :rf.error/loader-incomplete (:assertion record)))
      (is (nil? (:predicate record))))))

;; ===========================================================================
;; unregistered-substrate inline error cell
;; ===========================================================================

(deftest unregistered-substrate-renders-inline-error-cell
  (testing "spec/003 §Multi-substrate: an unregistered substrate id renders
            an inline error cell naming it, NOT a blank cell or a throw"
    (swap! rf.story.ui.multi-substrate/substrate->render-fn dissoc :uix)
    (rf.story/reg-variant :story.substrate.missing/probe
      {:substrates #{:uix}
       :setup     []})
    (is (some #(re-find #"substrate :uix is not registered" %)
              (hiccup-text-flatten
                (rf.story.ui.multi-substrate/multi-substrate-grid :story.substrate.missing/probe))))))

(deftest registered-substrate-renders-cell-body
  (testing "a registered substrate takes the render-fn branch, not the error
            cell. The registered cell is a Reagent class that React resolves
            on mount, so the data-level claim is the error cell's absence"
    (rf.story.ui.multi-substrate/register-substrate!
      :uix
      (fn [_vid _view-id _args]
        [:div.stub-cell "rendered via stub"]))
    (rf.story/reg-variant :story.substrate.ok/probe
      {:substrates #{:uix}
       :setup     []})
    (is (not-any? #(re-find #"is not registered" %)
                  (hiccup-text-flatten
                    (rf.story.ui.multi-substrate/multi-substrate-grid :story.substrate.ok/probe))))))

;; ===========================================================================
;; render-variant host APPLIES decorators
;;
;; The render-variant host hook (`canonical/render-host-scope`) and the live
;; canvas both route through the shared
;; `rf.story.ui.multi-substrate/render-decorated-view` seam, so they paint the
;; same tree. A host rendering the BARE view would drop the variant's
;; :decorators, and a render-variant render of a decorated variant would
;; diverge from the canvas. render_cljs_test §decorators-are-view-wrapping
;; pins only that :decorators RIDE render-inputs; these CLJS tests prove the
;; HOST APPLIES them. They use a REGISTERED variant via the DEFAULT lookup
;; (the production path).
;; ===========================================================================

(deftest render-decorated-view-wraps-via-shared-seam
  (testing "the shared seam wraps the rendered view in the variant's
            :hiccup decorators resolved from the compiled plan's
            [:world :decorators] — the host does not paint the bare view"
    (rf.story/reg-decorator :deco/themed
      {:kind :hiccup :wrap (fn [body _] [:div.themed body])})
    (rf/reg-view* :views/probe (fn [_] [:span.leaf "leaf"]))
    (rf.story/reg-variant :story.hostdeco/v
      {:component  :views/probe
       :decorators [[:deco/themed]]
       :setup     []})
    (let [deco-refs (get-in (rf.story.plan/variant-plan :story.hostdeco/v) [:world :decorators])]
      (is (= :div.themed
             (first (rf.story.ui.multi-substrate/render-decorated-view
                      :reagent :story.hostdeco/v :views/probe {} deco-refs)))))))
