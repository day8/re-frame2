(ns ssr-hydration-mismatch.core
  "Shared framework-behavior testbed — deliberate hydration-mismatch.

  Per [spec/011-SSR.md §Hydration-mismatch detection]: after the first
  client render, `verify-hydration!` recomputes the structural hash of
  the resolved render-tree, reads the server hash stashed at
  `[:rf/hydration :server-hash]`, and emits
  `:rf.ssr/hydration-mismatch` (op-type `:error`, recovery
  `:warned-and-replaced`) on disagreement.

  This surface forces disagreement: the payload bakes a known-wrong
  `:rf/render-hash` string (`'deadbeef'`) that cannot match whatever
  the client's render-tree hashes to under the seeded app-db. The
  mismatch trace fires; the cljs trace listener captures the event
  and exposes its `:server-hash`, `:client-hash`, `:failing-id`, and
  `:recovery` payload onto `window.__rf_trace_events()`. A
  `data-testid='mismatch-banner'` element renders the captured
  payload visibly so dev-mode visibility is observable from the DOM
  (dev posture: warn-and-replace; the client renders against the
  seeded state and the page remains interactive).

  What a Playwright consumer observes:

    - Hydration completes — `data-testid='hydrated'` marker is visible.
    - `verify-hydration!` is called post-first-render; the trace
      listener captures a `:rf.ssr/hydration-mismatch` event with
      `:server-hash='deadbeef'`, a non-nil `:client-hash`, and
      `:recovery=:warned-and-replaced`.
    - The mismatch-banner div renders the captured payload — proves
      dev-mode visibility of the structured trace surface.
    - The page is still interactive — clicking `inc` works post-
      mismatch; the runtime's default recovery is warn-and-replace,
      not crash."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.ssr :as ssr]
            [re-frame.trace :as trace]
            [cljs.reader])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; Trace listener — mirrors :rf.ssr/* + :rf/hydrate traces onto the
;; window AND keeps the most recent mismatch payload as a Reagent atom
;; the view can subscribe to (so the dev-mode banner re-renders when
;; the mismatch fires).
;; ----------------------------------------------------------------------------

(defonce trace-events (atom []))

(rf/reg-event-db ::record-mismatch
  (fn [db [_ tags]]
    (assoc db :mismatch tags)))

(defn- normalise-event
  "Project a trace event into a plain edn map carrying just the slots
  the spec assertions read against. `:recovery` is hoisted to the top
  level of the trace envelope (per Spec 009 §Error event shape — see
  `re-frame.trace/build-event`'s recovery-hoist branch); the other
  hydration-mismatch payload slots ride in `:tags`."
  [ev]
  (let [t (:tags ev)]
    (cond-> {:operation (str (:operation ev))
             :op-type   (some-> ev :op-type str)}
      (:server-hash t)     (assoc :server-hash (:server-hash t))
      (:client-hash t)     (assoc :client-hash (:client-hash t))
      (:failing-id t)      (assoc :failing-id (str (:failing-id t)))
      (:reason t)          (assoc :reason (:reason t))
      (:first-diff-path t) (assoc :first-diff-path (pr-str (:first-diff-path t)))
      ;; `:recovery` is HOISTED — at top level, not under tags.
      (:recovery ev)       (assoc :recovery (str (:recovery ev))))))

(defn install-trace-listener! []
  (trace/register-trace-cb! ::ssr-hydration-mismatch-listener
    (fn [ev]
      (let [op (:operation ev)]
        (when (and op
                   (or (= "rf.ssr" (namespace op))
                       (= :rf/hydrate op)))
          (swap! trace-events conj ev)
          ;; HOT PATH — when the mismatch fires, propagate the
          ;; projected payload into app-db so the dev-mode banner
          ;; view re-renders against the captured trace.
          (when (= :rf.ssr/hydration-mismatch op)
            (rf/dispatch [::record-mismatch (normalise-event ev)]))))))

  (set! (.-__rf_trace_events js/window)
        (fn []
          (clj->js (mapv normalise-event @trace-events)))))

;; ----------------------------------------------------------------------------
;; Events / subs
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::inc
  (fn [db _ev]
    (update db :count (fnil inc 0))))

(rf/reg-sub :count     (fn [db _] (or (:count db) 0)))
(rf/reg-sub :mismatch  (fn [db _] (:mismatch db)))
(rf/reg-sub :hydrated? (fn [db _] (boolean (:rf/hydration db))))

;; ----------------------------------------------------------------------------
;; Views
;; ----------------------------------------------------------------------------

(reg-view hydration-status []
  (let [hydrated? @(subscribe [:hydrated?])]
    [:div {:data-testid (if hydrated? "hydrated" "not-hydrated")
           :style {:padding "0.25em" :color (if hydrated? "#070" "#700")}}
     (if hydrated? "hydrated" "not-hydrated")]))

(reg-view mismatch-banner []
  ;; Dev-mode visibility of the :rf.ssr/hydration-mismatch payload.
  ;; Per spec/011 §Mismatch recovery and configuration the default
  ;; posture is warn-and-replace; the user-visible banner makes the
  ;; structured trace inspectable without dev-tools.
  (let [m @(subscribe [:mismatch])]
    (if m
      [:div {:data-testid "mismatch-banner"
             :style {:border  "2px solid #c33"
                     :padding "0.75em"
                     :margin  "0.5em 0"
                     :background "#fee"
                     :font-family "monospace"}}
       [:h3 {:style {:margin-top 0}} ":rf.ssr/hydration-mismatch"]
       [:p "server-hash="
        [:span {:data-testid "mismatch-server-hash"} (:server-hash m)]]
       [:p "client-hash="
        [:span {:data-testid "mismatch-client-hash"} (str (:client-hash m))]]
       [:p "failing-id="
        [:span {:data-testid "mismatch-failing-id"} (:failing-id m)]]
       [:p "recovery="
        [:span {:data-testid "mismatch-recovery"} (:recovery m)]]
       [:p {:style {:color "#666"}} (:reason m)]]
      [:div {:data-testid "mismatch-banner-empty"
             :style {:color "#888"}}
       "(no mismatch yet)"])))

(reg-view counter-panel []
  (let [n @(subscribe [:count])]
    [:div {:data-testid "counter-panel"
           :style {:border "1px solid #aaa" :padding "0.5em" :margin "0.25em 0"}}
     [:p "count=" [:span {:data-testid "count"} n]]
     [:button {:data-testid "inc"
               :on-click    #(dispatch [::inc])}
      "Inc"]]))

(reg-view root []
  [:div {:data-testid "ssr-hydration-mismatch"
         :style {:font-family "sans-serif" :padding "1em"}}
   [:h1 "ssr-hydration-mismatch testbed"]
   [hydration-status]
   [mismatch-banner]
   [counter-panel]])

;; ----------------------------------------------------------------------------
;; Mount + hydrate
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn read-server-payload []
  (when-let [el (.getElementById js/document "__rf_payload")]
    (cljs.reader/read-string (.-textContent el))))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (install-trace-listener!)
  (let [payload (read-server-payload)]
    (when payload
      ;; HOT PATH — :rf/hydrate replaces app-db; the payload's
      ;; :rf/render-hash 'deadbeef' is stashed at
      ;; [:rf/hydration :server-hash] so verify-hydration! can compare
      ;; against the client's computed hash.
      (rf/dispatch-sync [:rf/hydrate payload]))
    (rdc/render react-root [root])

    ;; HOT PATH — the trigger site for :rf.ssr/hydration-mismatch.
    ;; The resolved tree hashes to a value that won't equal the
    ;; payload's 'deadbeef'; verify-hydration! emits the mismatch
    ;; trace which our listener routes to ::record-mismatch.
    (let [tree ((rf/view :ssr-hydration-mismatch.core/root))]
      (ssr/verify-hydration! :rf/default tree))))
