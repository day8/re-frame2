(ns re-frame.story.sub-overrides-render-dom-cljs-test
  "End-to-end ACCEPTANCE test for the sub-override subscribe seam: render
  a REAL, normally-authored view through the Story override-context
  carriage and assert the pinned `:sub-overrides` value appears ON
  SCREEN — not merely that the resolver returns it.

  It is the load-bearing assertion because the override must survive into
  a view's DEFERRED React render, and a `binding`-bound dynamic var would
  not: it unwinds before the descendant view's own reaction runs. Only a
  REAL React render of the override-context Provider wrapping the view
  proves the carriage survives that boundary and the core
  `:subs/resolve-sub-override` consult substitutes.

  The `-dom-cljs-test$` suffix puts this file on `:browser-test` (real
  React via `react-dom/client`). `:node-test` also loads it, where the
  mounting branch self-gates on `(browser?)`; the node half is covered by
  `re-frame.subs-override-seam-cljs-test` (the seam mechanics and the
  honesty boundary) and `re-frame.story.sub-overrides-cljs-test` (Story's
  context resolver)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.story.sub-overrides :as rf.story.sub-overrides]))

;; `:ambient-frame nil` leaves the suite with no ambient scope; the test
;; binds `*current-frame* :rf/default` around its own dispatch and mount.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (when (exists? (.-act React)) (.-act React)))

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

(defn- with-browser-act [f]
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the render assertions")
    (let [act-fn (get-act)]
      (if (nil? act-fn)
        (is true "act() not reachable from this runner; skipping")
        (do (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (f act-fn))))))

;; Authored the normal way: it has no idea it is inside Story.
(defn- login-panel []
  (let [state @(rf/subscribe [:login/state])
        msg   @(rf/subscribe [:login/message])]
    [:div.login {:data-state (name (or state :idle))}
     [:span.state (name (or state :idle))]
     (when (= state :error)
       [:p.err msg])]))

(deftest override-surfaces-at-render
  (testing "with app-db really holding :ok, a variant pinning
            [:login/state] :error renders the error state on screen"
    (with-browser-act
     (fn [act-fn]
       (rf/reg-sub :login/state   (fn [db _] (get-in db [:login :state])))
       (rf/reg-sub :login/message (fn [db _] (get-in db [:login :message])))
       (rf/reg-event ::seed (fn [_ _] {:db {:login {:state :ok}}}))
       (let [overrides  {[:login/state]   :error
                         [:login/message] "Incorrect password"}
             mount-node (make-mount-node!)
             root       (react-dom-client/createRoot mount-node)]
         (try
           ;; `login-panel` is a plain Reagent fn, so its subscribe resolves
           ;; the frame from the dynamic var; the override Provider carries
           ;; sub-overrides, not a frame scope.
           (binding [rf.frame/*current-frame* :rf/default]
             (rf/dispatch-sync [::seed])
             (act-fn
               (fn []
                 (.render root
                   (r/as-element
                     (rf.story.sub-overrides/override-provider overrides [login-panel]))))))
           (let [text (.-textContent mount-node)]
             (is (re-find #"error" text)
                 "the pinned :error state surfaces in the rendered DOM")
             (is (re-find #"Incorrect password" text)
                 "the pinned :login/message override surfaces in the rendered DOM"))
           (is (= "error" (.. mount-node -firstChild (getAttribute "data-state")))
               "the view branched on the override, not the real :ok")
           (finally
             (try (.unmount root) (catch :default _ nil)))))))))
