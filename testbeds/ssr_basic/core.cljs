(ns ssr-basic.core
  "Shared framework-behavior testbed — SSR hydration baseline.

  This surface exercises the SSR round-trip from the browser end. The
  pre-rendered HTML and the serialised payload are baked into the static
  `index.html` (the shape `re-frame.ssr/render-to-string` + the JVM
  payload builder would emit — see `examples/reagent/ssr/core.cljc`'s
  `handle-request` for the reference). The browser-side `run` reads the
  payload, dispatches `:rf/hydrate` (locked `:replace-app-db` policy per
  [spec/011-SSR.md §The :rf/hydrate event]), renders against the now-
  seeded app-db, and invokes `verify-hydration!` to drive hash-based
  mismatch detection.

  Per-request `:rf.server/*` response data — the per-request side-channel
  the server-side framework uses for headers / cookies / status / redirect
  — round-trips via the payload's optional `:rf/response` slice; the
  client materialises it into app-db at `[:server-response]` so the view
  can render the resolved shape.

  What a Playwright consumer observes:

    - The pre-rendered counter / title / response panel are visible from
      the first byte (before main.js loads).
    - After main.js boots, `:rf/hydrate` replaces app-db with the
      payload's `:rf/app-db` slice. The hydrate metadata lands at
      [:rf/hydration] in app-db and a `data-testid='hydrated'` element
      switches from `not-hydrated` to `hydrated`.
    - `:rf.ssr/check-version` (always) and `:rf.ssr/check-schema-digest`
      (when the payload carries a digest) fxs run. The runtime emits
      `:rf.ssr/compatibility-check-skipped` traces when the late-bind
      hooks aren't registered — captured by the trace listener installed
      below and exposed on `window.__rf_trace_events()`.
    - `verify-hydration!` is invoked post-first-render with the resolved
      client tree. On the happy path (no hash baked in the payload) the
      check no-ops cleanly; a sibling testbed (`ssr_hydration_mismatch/`)
      exercises the mismatch trace.
    - Click pipeline is live post-hydration: `inc` mutates `:count`, the
      sub recomputes, the view re-renders.

  This is NOT a tutorial — bodies are stark. HOT PATH comments mark the
  three load-bearing trigger sites: trace-bus listener install, hydrate
  dispatch, and post-render `verify-hydration!`."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.ssr :as ssr]
            [re-frame.trace :as trace]
            [cljs.reader])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; Trace listener — capture every :rf.ssr/* + :rf/hydrate trace event
;; onto `window.__rf_trace_events()` so Playwright can assert against
;; the hydration handshake post-load.
;; ----------------------------------------------------------------------------

(defonce trace-events (atom []))

(defn- ev->js
  "Project a trace event into a Playwright-friendly JS shape — only the
  fields the spec.cjs assertions read against. `:recovery` is hoisted
  to the top level of the trace envelope (per Spec 009 §Error event
  shape — `re-frame.trace/build-event`'s recovery-hoist branch); the
  others ride in `:tags`."
  [ev]
  (let [t (:tags ev)]
    (clj->js
      (cond-> {:operation (str (:operation ev))
               :op-type   (some-> ev :op-type str)}
        (:server-hash t) (assoc :server-hash (:server-hash t))
        (:client-hash t) (assoc :client-hash (:client-hash t))
        (:failing-id t)  (assoc :failing-id (str (:failing-id t)))
        (:check t)       (assoc :check (str (:check t)))
        ;; `:recovery` is hoisted onto the top-level envelope.
        (:recovery ev)   (assoc :recovery (str (:recovery ev)))))))

(defn install-trace-listener! []
  ;; HOT PATH — wire the trace bus to a window-side mirror so a
  ;; Playwright assertion can inspect the hydration trace stream
  ;; without poking at internal cljs vars.
  (trace/register-trace-cb! ::ssr-basic-listener
    (fn [ev]
      (let [op (:operation ev)]
        (when (and op
                   (or (= "rf.ssr" (namespace op))
                       (= :rf/hydrate op)))
          (swap! trace-events conj ev)))))
  (set! (.-__rf_trace_events js/window)
        (fn [] (clj->js (mapv #(js->clj (ev->js %)) @trace-events)))))

;; ----------------------------------------------------------------------------
;; Events / subs
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::inc
  (fn [db _ev]
    (update db :count (fnil inc 0))))

(rf/reg-event-db ::set-title
  (fn [db [_ t]]
    (assoc db :title t)))

(rf/reg-sub :count       (fn [db _] (or (:count db) 0)))
(rf/reg-sub :title       (fn [db _] (or (:title db) "untitled")))
(rf/reg-sub :server-resp (fn [db _] (:server-response db)))
(rf/reg-sub :hydrated?   (fn [db _] (boolean (:rf/hydration db))))

;; ----------------------------------------------------------------------------
;; Views
;; ----------------------------------------------------------------------------

(reg-view counter-panel []
  (let [n     @(subscribe [:count])
        title @(subscribe [:title])]
    [:div {:data-testid "counter-panel"
           :style {:border  "1px solid #aaa"
                   :padding "0.5em"
                   :margin  "0.25em 0"}}
     [:h2 {:data-testid "title"} title]
     [:p "count=" [:span {:data-testid "count"} n]]
     [:button {:data-testid "inc"
               :on-click    #(dispatch [::inc])}
      "Inc"]
     [:button {:data-testid "set-title"
               :on-click    #(dispatch [::set-title "hydrated"])}
      "Set title"]]))

(reg-view server-response-panel []
  (let [resp @(subscribe [:server-resp])]
    [:div {:data-testid "server-response"
           :style {:border  "1px solid #ddd"
                   :padding "0.5em"
                   :margin  "0.25em 0"
                   :font-family "monospace"}}
     [:h3 "Per-request response"]
     [:p "status=" [:span {:data-testid "resp-status"} (str (:status resp))]]
     [:p "content-type="
      [:span {:data-testid "resp-ct"}
       (str (get-in resp [:headers "content-type"]))]]
     [:p "cookies-count="
      [:span {:data-testid "resp-cookies-count"}
       (str (count (:cookies resp)))]]
     [:p "first-cookie-name="
      [:span {:data-testid "resp-cookie-name"}
       (str (or (:name (first (:cookies resp))) "-"))]]]))

(reg-view hydration-status-panel []
  (let [hydrated? @(subscribe [:hydrated?])]
    [:div {:data-testid (if hydrated? "hydrated" "not-hydrated")
           :style {:padding "0.25em"
                   :color   (if hydrated? "#070" "#700")}}
     (if hydrated? "hydrated" "not-hydrated")]))

(reg-view root []
  [:div {:data-testid "ssr-basic"
         :style {:font-family "sans-serif" :padding "1em"}}
   [:h1 "ssr-basic testbed"]
   [hydration-status-panel]
   [counter-panel]
   [server-response-panel]])

;; ----------------------------------------------------------------------------
;; Mount + hydrate
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn read-server-payload
  "Read the EDN payload baked into the static index.html. Per Spec 011
  §Payload scope: `{:rf/version :rf/frame-id :rf/app-db :rf/render-hash
  :rf/schema-digest? :rf/response?}`."
  []
  (when-let [el (.getElementById js/document "__rf_payload")]
    (cljs.reader/read-string (.-textContent el))))

(defn- materialise-response
  "Hoist the payload's optional `:rf/response` slice into the seeded
  `:rf/app-db` under `[:server-response]` so the client view can render
  the resolved per-request response shape. The server-side framework
  keeps the response accumulator in a private side-channel (per Spec 011
  §Response storage substrate) — not in app-db — so this client-side
  hoist is the test surface's bridge from the wire to the view layer."
  [payload]
  (cond-> payload
    (and (map? payload) (:rf/response payload))
    (update :rf/app-db assoc :server-response (:rf/response payload))))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (install-trace-listener!)

  (let [payload (some-> (read-server-payload) materialise-response)]
    (if payload
      ;; HOT PATH — :rf/hydrate is auto-registered by re-frame.ssr;
      ;; replaces app-db with the payload's :rf/app-db, stashes
      ;; metadata at [:rf/hydration], dispatches the two compatibility-
      ;; check fxs (which the trace listener mirrors).
      (rf/dispatch-sync [:rf/hydrate payload])
      ;; Degraded path: no payload baked. Don't crash — render against
      ;; the empty app-db. This is the "client-only first load" shape.
      (rf/dispatch-sync [::inc]))
    (rdc/render react-root [root])

    ;; HOT PATH — verify-hydration! reads the server-hash stashed at
    ;; [:rf/hydration :server-hash], computes the client-render hash,
    ;; and emits :rf.ssr/hydration-mismatch on disagreement.
    ;; Resolve the view under the current (default) frame so the
    ;; subs evaluate against the post-hydrate app-db.
    (when payload
      (let [tree ((rf/view :ssr-basic.core/root))]
        (ssr/verify-hydration! :rf/default tree)))))
