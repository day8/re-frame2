(ns re-frame.story.sub-overrides-render-dom-cljs-test
  "End-to-end ACCEPTANCE test for the sub-override subscribe seam
  (rf2-7pgiz): render a REAL, normally-authored view through the Story
  override-context carriage and assert the pinned `:sub-overrides` value
  appears ON SCREEN — not merely that the resolver returns it.

  This is the test the bead deferred to land WITH the seam. It is the
  load-bearing assertion because the bug it closes was specifically that
  the override did NOT survive into a view's DEFERRED React render: a
  `binding`-bound dynamic var unwinds before the descendant view's own
  reaction runs. Only a REAL React render of the override-context
  Provider wrapping the view proves the carriage survives that boundary
  and the core `:subs/resolve-sub-override` consult substitutes.

  Naming convention (rf2-2hrj8): the `-dom-cljs-test$` suffix opts this
  file into the `:browser-test` build (Playwright + Chromium, real React
  via `react-dom/client`). `:node-test` also loads it (its `cljs-test$`
  regex matches the `-dom-cljs-test` suffix), where the mounting branch
  self-gates on `(browser?)` and exits early — the node half is covered
  by `re-frame.subs-override-seam-cljs-test` (the seam mechanics) and
  `re-frame.story.sub-overrides-cljs-test` (the honesty boundary)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.subs :as subs]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.story.sub-overrides :as sub-overrides]))

;; `make-reset-runtime-fixture` performs the snapshot/restore + frames-reset +
;; adapter dispose/install this suite hand-rolled. `:ambient-frame nil` preserves
;; the suite's no-ambient-scope behaviour — each render-based test binds its own
;; `*current-frame* :rf/default` around the mount.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter :ambient-frame nil}))

;; ---- browser + act gate (mirrors react-shared-suite) ----------------------

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

;; ---- the view under test — authored the COMPLETELY NORMAL way -------------
;;
;; It has no idea it is inside Story; it just subscribes and renders.

(defn- login-panel []
  (let [state @(rf/subscribe [:login/state])
        msg   @(rf/subscribe [:login/message])]
    [:div.login {:data-state (name (or state :idle))}
     [:span.state (name (or state :idle))]
     (when (= state :error)
       [:p.err msg])]))

;; ---- 1 · the acceptance criterion — override surfaces ON SCREEN ----------

(deftest override-surfaces-at-render
  (testing "a variant pinning [:login/state] :error renders the error state on screen"
    (with-browser-act
     (fn [act-fn]
       ;; Real subs reading app-db. With no events dispatched the real
       ;; values are nil/idle — so the error styling appears ONLY if the
       ;; override actually surfaces.
       (rf/reg-sub :login/state   (fn [db _] (get-in db [:login :state])))
       (rf/reg-sub :login/message (fn [db _] (get-in db [:login :message])))
       (let [overrides  {[:login/state]   :error
                         [:login/message] "Incorrect password"}
             mount-node (make-mount-node!)
             root       (react-dom-client/createRoot mount-node)]
         (try
           ;; EP-0002 (rf2-9o48ih): `login-panel` is a plain Reagent fn (no
           ;; `:contextType` wiring), so its `subscribe` resolves the frame from
           ;; the dynamic-var tier, not React context. The Story override-
           ;; provider carries sub-overrides, NOT a frame scope. Bind the
           ;; ambient `:rf/default` frame around the synchronous `act` render so
           ;; the view's subscribe resolves a frame (the fixture already ensured
           ;; the `:rf/default` frame exists) instead of raising
           ;; :rf.error/no-frame-context.
           (binding [frame/*current-frame* :rf/default]
             (act-fn
               (fn []
                 (.render root
                   (r/as-element
                     (sub-overrides/override-provider overrides [login-panel]))))))
           (let [text (.-textContent mount-node)]
             (is (re-find #"error" text)
                 "the pinned :error state surfaces in the rendered DOM")
             (is (re-find #"Incorrect password" text)
                 "the pinned :login/message override surfaces in the rendered DOM"))
           (is (= "error" (.. mount-node -firstChild (getAttribute "data-state")))
               "the view branched on the overridden state (error styling), not idle")
           (finally
             (try (.unmount root) (catch :default _ nil)))))))))

(deftest no-override-renders-real-value
  (testing "WITHOUT an override the same view renders its real (idle) state"
    (with-browser-act
     (fn [act-fn]
       (rf/reg-sub :login/state   (fn [db _] (get-in db [:login :state])))
       (rf/reg-sub :login/message (fn [db _] (get-in db [:login :message])))
       (let [mount-node (make-mount-node!)
             root       (react-dom-client/createRoot mount-node)]
         (try
           ;; nil overrides → the Provider is render-transparent; the view
           ;; reads the real (default nil → idle) subscription. EP-0002
           ;; (rf2-9o48ih): bind the ambient `:rf/default` frame around the
           ;; render so the plain-fn view's subscribe resolves a frame via the
           ;; dynamic-var tier (the override-provider carries no frame scope).
           (binding [frame/*current-frame* :rf/default]
             (act-fn
               (fn []
                 (.render root
                   (r/as-element
                     (sub-overrides/override-provider nil [login-panel]))))))
           (let [text (.-textContent mount-node)]
             (is (re-find #"idle" text)
                 "no override → the real idle state renders")
             (is (not (re-find #"Incorrect password" text))
                 "no override → no pinned message leaks in"))
           (finally
             (try (.unmount root) (catch :default _ nil)))))))))

;; ---- 2 · the honesty boundary survives a REAL render ---------------------

(deftest honesty-boundary-holds-under-real-render
  (testing "even while the override surfaces on screen, compute-sub reads real app-db"
    (with-browser-act
     (fn [act-fn]
       (rf/reg-sub :login/state (fn [db _] (get-in db [:login :state])))
       (rf/reg-sub :login/message (fn [db _] (get-in db [:login :message])))
       ;; Seed a REAL app-db value distinct from the override.
       (rf/reg-event ::seed (fn [{:keys [db]} _] {:db {:login {:state :ok}}}))
       ;; EP-0002 (rf2-9o48ih): the dispatch + the plain-fn view's subscribe
       ;; both need a carried frame. Bind the ambient `:rf/default` scope
       ;; (the fixture ensured the frame exists) around the seed dispatch and
       ;; the synchronous `act` render so neither raises no-frame-context.
       (binding [frame/*current-frame* :rf/default]
       (rf/dispatch-sync [::seed])
       (let [overrides  {[:login/state] :error}
             mount-node (make-mount-node!)
             root       (react-dom-client/createRoot mount-node)]
         (try
           (act-fn
             (fn []
               (.render root
                 (r/as-element
                   (sub-overrides/override-provider overrides [login-panel])))))
           (testing "the screen shows the OVERRIDE (:error)"
             (is (re-find #"error" (.-textContent mount-node))))
           (testing "but compute-sub — the :rf.assert/sub-equals seam — still reads :ok"
             (is (= :ok (subs/compute-sub [:login/state] {:login {:state :ok}})))
             (is (not= :error (subs/compute-sub [:login/state] {:login {:state :ok}}))
                 "the override can never satisfy a sub-equals assertion"))
           (finally
             (try (.unmount root) (catch :default _ nil))))))))))
