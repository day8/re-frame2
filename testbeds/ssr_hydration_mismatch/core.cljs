(ns ssr-hydration-mismatch.core
  "Shared framework-behavior testbed — a real hydration mismatch.

  Per [spec/011-SSR.md §Hydration-mismatch detection]: a hydrating root
  reports each divergence React recovers from — text content, or a
  missing / extra / wrong-type element — as `:rf.ssr/hydration-mismatch`,
  through the `onRecoverableError` reporter the framework installs on every
  hydrating root. The dev trace carries React's message (`:error`), the
  door that hydrated (`:where`) and `:recovery :warned-and-replaced`: React
  has already re-rendered the root on the client by the time it fires.

  This surface makes React recover. The server markup baked into
  `index.html` says `rendered on the server` where the client's root view
  renders `rendered on the client` — a text-only divergence standing in
  for a view that reads something the two hosts disagree on (the clock, a
  locale, `js/window`). `run` seeds the frame with `ssr/hydrate!`, then
  hydrates the server markup with the Reagent adapter's `render!`,
  `{:hydrate? true}` and an `:on-recoverable-error` that logs the recovery
  as a console warning. React meets the divergent text, recovers by
  re-rendering the root, and the reporter emits the trace; the trace
  listener captures it onto `window.__rf_trace_events()` and into app-db,
  so a `data-testid='mismatch-banner'` element renders it from the DOM
  (dev posture: warn-and-replace; the page stays interactive).

  What a Playwright consumer observes:

    - Hydration completes — `data-testid='hydrated'` marker is visible,
      and `render-origin` reads the client's text.
    - The trace listener captures a `:rf.ssr/hydration-mismatch` event
      with React's message in `:error`, `:where`
      `re-frame.substrate.spine/make-render` and
      `:recovery :warned-and-replaced`.
    - The mismatch-banner div renders the captured trace — proves
      dev-mode visibility of the structured trace surface.
    - The page is still interactive — clicking `inc` works after the
      recovery; React's recovery is warn-and-replace, not crash."
  (:require [re-frame.core :as rf]
            ;; SSR hydration metadata is durable runtime-db state, so the
            ;; :hydrated? discriminator is a runtime-db sub (reg-runtime-sub
            ;; lives on the re-frame.subs surface).
            [re-frame.subs :as rf.subs]
            [re-frame.views]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.ssr :as rf.ssr])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; Trace listener — mirrors :rf.ssr/* + :rf/hydrate traces onto the
;; window AND records the most recent mismatch in app-db so the dev-mode
;; banner re-renders when the mismatch fires.
;; ----------------------------------------------------------------------------

(defonce trace-events (atom []))

(rf/reg-event ::record-mismatch
  (fn [{:keys [db]} [_ tags]]
    {:db (assoc db :mismatch tags)}))

(defn- normalise-event
  "Project a trace event into a plain edn map carrying just the slots
  the assertions read. `:recovery` is hoisted to the top level of the
  trace envelope (per Spec 009 §Error event shape — see
  `re-frame.trace/build-event`'s recovery-hoist branch); the other
  hydration-mismatch slots ride in `:tags`."
  [ev]
  (let [t (:tags ev)]
    (cond-> {:operation (str (:operation ev))
             :op-type   (some-> ev :op-type str)}
      (:error t)      (assoc :error (str (:error t)))
      (:where t)      (assoc :where (str (:where t)))
      (:failing-id t) (assoc :failing-id (str (:failing-id t)))
      (:reason t)     (assoc :reason (:reason t))
      ;; `:recovery` is HOISTED — at top level, not under tags.
      (:recovery ev)  (assoc :recovery (str (:recovery ev))))))

(defn install-trace-listener! []
  (rf/register-listener! :trace ::ssr-hydration-mismatch-listener
    (fn [ev]
      (let [op (:operation ev)]
        (when (and op
                   (or (= "rf.ssr" (namespace op))
                       (= :rf/hydrate op)))
          (swap! trace-events conj ev)
          ;; HOT PATH — the mismatch fires from React's root-error
          ;; callback, outside any frame scope, so the dispatch names the
          ;; frame the banner reads.
          (when (= :rf.ssr/hydration-mismatch op)
            (rf/dispatch [::record-mismatch (normalise-event ev)]
                         {:frame :rf/default}))))))

  (set! (.-__rf_trace_events js/window)
        (fn []
          (clj->js (mapv normalise-event @trace-events)))))

;; ----------------------------------------------------------------------------
;; Events / subs
;; ----------------------------------------------------------------------------

(rf/reg-event ::inc
  (fn [{:keys [db]} _ev]
    {:db (update db :count (fnil inc 0))}))

(rf/reg-sub :count     (fn [db _] (or (:count db) 0)))
(rf/reg-sub :mismatch  (fn [db _] (:mismatch db)))
;; The SSR hydration metadata is durable runtime-db state — the `:rf/hydrate`
;; handler records it at [:rf.runtime/ssr :hydration] in the runtime-db
;; partition (NOT app-db). So :hydrated? is a runtime-db sub reading the
;; canonical source.
(rf.subs/reg-runtime-sub :hydrated?
  (fn [rt _] (boolean (get-in rt [:rf.runtime/ssr :hydration]))))

;; ----------------------------------------------------------------------------
;; Views
;; ----------------------------------------------------------------------------

(reg-view hydration-status []
  (let [hydrated? @(subscribe [:hydrated?])]
    [:div {:data-testid (if hydrated? "hydrated" "not-hydrated")
           :style {:padding "0.25em" :color (if hydrated? "#070" "#700")}}
     (if hydrated? "hydrated" "not-hydrated")]))

(reg-view mismatch-banner []
  ;; Dev-mode visibility of the :rf.ssr/hydration-mismatch trace. React has
  ;; already recovered by re-rendering on the client; the banner makes the
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
       [:p "where="
        [:span {:data-testid "mismatch-where"} (:where m)]]
       [:p "recovery="
        [:span {:data-testid "mismatch-recovery"} (:recovery m)]]
       [:p {:data-testid "mismatch-error"
            :style {:color "#666"}}
        (:error m)]]
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
   ;; The divergent node. `index.html`'s server markup carries
   ;; "rendered on the server" here, so React's hydration meets different
   ;; text at this node and recovers by re-rendering the root.
   [:p {:data-testid "render-origin"} "rendered on the client"]
   [:h1 "ssr-hydration-mismatch testbed"]
   [hydration-status]
   [mismatch-banner]
   [counter-panel]])

;; ----------------------------------------------------------------------------
;; Seed + hydrate
;; ----------------------------------------------------------------------------

(defonce app-root (rf.adapter.reagent/client-root))

(defn- log-recovery!
  "The root's `:on-recoverable-error`. Without one, React's default report
  goes through `reportError` as an uncaught page error, and the recovery
  this surface makes on purpose is its expected outcome, not a crash."
  [error _error-info]
  (js/console.warn "React recovered during hydration:" (.-message error)))

(defn ^:export run []
  (rf/init! rf.adapter.reagent/adapter)
  (install-trace-listener!)
  ;; Per EP-0002, the runtime never synthesises a frame from absence —
  ;; register `:rf/default` as the client app frame; `hydrate!` and the
  ;; provider both name it.
  (rf/make-frame {:id :rf/default})
  ;; HOT PATH — seed: `hydrate!` reads the `__rf_payload` script and
  ;; dispatches `:rf/hydrate`, so the frame holds the server's app-db before
  ;; the first render.
  (rf.ssr/hydrate! {:frame :rf/default})
  ;; HOT PATH — the hydrating render. The adapter installs the adoption
  ;; reporter on this root; React's recovery from the divergent text is
  ;; what emits :rf.ssr/hydration-mismatch. The reporter composes with
  ;; `:on-recoverable-error` rather than replacing it.
  (rf.adapter.reagent/render! app-root
    [rf/frame-provider {:frame :rf/default} [root]]
    (js/document.getElementById "app")
    {:hydrate? true
     :on-recoverable-error log-recovery!}))
