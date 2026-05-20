(ns parallel-frames.core
  "Parallel-Frames testbed (rf2-m00rw) — THE canonical multi-frame
  isolation demo. One app, mounted in TWO frames on ONE page, with
  zero cross-frame coupling. The exercise IS observing the two
  frames diverge as the user interacts with each independently.

  ## Shape

  ONE app code path. TWO frame ids: `:above` and `:below`. Each
  `frame-provider` rooted subtree is a fully isolated reactive
  context — its own `app-db`, its own router queue, its own sub-cache.
  Handlers and subs are registered once (globally) and run against
  whichever frame the dispatch envelope targets. The
  `reg-view`-injected `dispatch` / `subscribe` close over the
  surrounding frame-provider's frame id via React context, so the
  same view source produces two independent reactive contexts.

  Architectural anchors:
    - Frames are isolated contexts. NO cross-frame sub computation.
      NO data routing between frames. NO sub that reaches into
      another frame's app-db. (Spec 006 §The cache is held inside
      the frame container.)
    - Testbeds are TEST surfaces, not tutorial surfaces. No
      deliberate bugs, no teaching layers, no anti-pattern
      demonstrations. The demo's punchline is observing two
      isolated frames diverge — not 'fix this bug.'

  ## The app

  Each frame mounts the same panel exercising four feature surfaces:

    Counter        — `+ / −` buttons. Demonstrates events, app-db
                     evolution, simple sub recomputation.
    Clock          — per-frame Tick button increments the local
                     `:ticks` counter once per click. Demonstrates
                     event-driven sub recomputation cleanly on demand,
                     and (because each frame's `:ticks` slot is
                     independent) the parallel-frame isolation: ticking
                     :above does not move :below's counter and vice
                     versa. The auto-tick dispatch chain that used to
                     drive this was retired in rf2-gxgmt — spine
                     pollution outweighed teaching value.
    Title (HTTP)   — Refresh button dispatches an event that, via the
                     `:title/flow` state machine, fires an in-process
                     mock-HTTP effect. The mock resolves ~600ms after
                     dispatch with the wall-clock time. Force-error
                     dispatches the same flow with `{:force-error? true}`,
                     which the mock rejects. Demonstrates HTTP
                     correlation, in-flight / settled state, and the
                     :idle → :loading → :loaded / :error machine cycle.
    Issues source  — The mock HTTP fx is deliberately slow (~600ms),
                     which exceeds Spec 009 / Causa's slow-effect
                     threshold and surfaces legitimately in Causa's
                     Issues panel. Not a bug — a normal slow request.

  ## What Causa users observe

  - Frame picker: switching between `:above` and `:below` re-scopes
    every L2 (Events) and L4 (App-db, Views, Machines, HTTP, Issues,
    Trace) panel to the active frame.
  - Each frame's counter / clock / machine state evolves on its own
    independent of the other frame.
  - Slow-fetch surfaces an Issue per request, distinct per frame.

  See `tools/causa/testbeds/parallel-frames/README.md` for the
  walk-through and `spec.cjs` for the browser smoke."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            ;; Loading these namespaces installs their late-bind hooks.
            ;; Without them, `rf/reg-machine` would fail at registration
            ;; time, and the mock-HTTP fx would have no place to land.
            [re-frame.machines]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; rf2-6jyf6 — Causa's `configure!` to seed `:project-root`
            ;; so the Event lens 'open' chip resolves a classpath-
            ;; relative `:file` slot to an absolute on-disk URI the
            ;; OS-side editor handler can stat.
            [day8.re-frame2-causa.config :as causa-config])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; FRAME IDs
;; ============================================================================

(def frame-above :above)
(def frame-below :below)

;; ============================================================================
;; CONSTANTS
;; ============================================================================

(def CLOCK-TICK-MS
  "Clock tick period — referenced only by the parked `::clock-start`
  handler (currently unused; see rf2-gxgmt). Kept so any future
  opt-in auto-tick toggle can re-enable the self-perpetuating chain
  without re-introducing the magic number."
  1000)

(def HTTP-MOCK-DELAY-MS
  "Mock-HTTP delay. The 600ms figure is the load-bearing decision:
  long enough to exceed Spec 009's slow-effect threshold (so Causa's
  Issues panel surfaces the slow fetch as a legitimate, non-bug
  Issue), short enough that the smoke test resolves it without
  excessive wall-clock cost."
  600)

;; ============================================================================
;; APP-DB INITIALISER (shared by both frames)
;; ============================================================================
;;
;; The same `:rf/initialise` handler runs once per frame via each
;; frame's `:on-create` opt, against that frame's empty `app-db`. The
;; two frames diverge from this identical seed as the user clicks.

(rf/reg-event-fx ::initialise
  ;; Single `:on-create` boot event per Spec 002 §Frame creation —
  ;; `:on-create` accepts ONE event vector; multi-step initialisation
  ;; fans out via the effect map. We seed app-db AND ping the title
  ;; machine (so its `:idle` snapshot materialises immediately rather
  ;; than lazily-on-first-refresh — the Causa Machines panel and the
  ;; smoke's :idle assertion both want the snapshot live on first
  ;; paint). The `:rf/init` ping is not declared on the `:idle`
  ;; state's `:on` map, so it is a no-op transition — but the runtime
  ;; synthesises the initial snapshot on first dispatch (Spec 005
  ;; §Initial-state cascading), which is exactly the side-effect we
  ;; want.
  ;;
  ;; rf2-gxgmt — the clock-tick chain is no longer started here. The
  ;; per-frame Tick button dispatches `::clock-tick` on demand
  ;; instead, demonstrating parallel-frame isolation without
  ;; continuous spine pollution.
  (fn [_ctx _ev]
    {:db {:counter   0
          ;; rf2-gxgmt — `:clock {:tick-gen 0 :ticks 0}` still seeded
          ;; here so the per-frame Tick button (which dispatches
          ;; `[::clock-tick 0]`) finds both slots present on first
          ;; click. `:tick-gen` stays at 0 in on-demand mode; it's
          ;; the parking spot for a future Reset/Pause toggle.
          :clock     {:tick-gen 0
                      :ticks    0}
          :title     {:value    "Parallel-Frames (untouched)"
                      :error    nil
                      :requests 0}}
     ;; Clock-tick chain disabled (rf2-gxgmt — annoying + low value;
     ;; spine pollution from recursive ::clock-tick dispatch). The
     ;; per-frame Tick buttons drive `::clock-tick` on demand instead —
     ;; isolation visible without continuous spine noise.
     ;; ::clock-start / ::clock-tick / ::clock-ticks remain registered
     ;; below; ::clock-start is now unused (kept for a future opt-in
     ;; "auto-tick" toggle if it ever becomes useful again).
     :fx [[:dispatch [:title/flow [:rf/init]]]]}))

;; ============================================================================
;; COUNTER — events + sub
;; ============================================================================

(rf/reg-event-db ::counter-inc
  (fn [db _ev] (update db :counter (fnil inc 0))))

(rf/reg-event-db ::counter-dec
  (fn [db _ev] (update db :counter (fnil dec 0))))

(rf/reg-sub ::counter (fn [db _] (:counter db)))

;; ============================================================================
;; CLOCK — on-demand tick via per-frame "Tick" button
;; ============================================================================
;;
;; rf2-gxgmt — the auto-ticking dispatch chain was removed. Each frame
;; now exposes a "Tick" button that dispatches `::clock-tick` once
;; against the surrounding frame. Clicking Tick on :above increments
;; only :above's tick counter; clicking Tick on :below increments only
;; :below's. The isolation is visible on demand without continuous
;; spine pollution.
;;
;; ::clock-start is no longer wired (`:on-create` doesn't dispatch it
;; any more) but stays registered as a parking spot for any future
;; opt-in auto-tick toggle. The `:tick-gen` generation guard in
;; ::clock-tick stays idiomatic — costs nothing and pairs with a
;; future Reset/Pause affordance if one materialises.

(rf/reg-event-fx ::clock-start
  ;; Currently unused — the testbed runs in on-demand mode (rf2-gxgmt).
  ;; Left registered so a future auto-tick toggle can re-enable the
  ;; self-perpetuating chain without touching the registration graph.
  (fn [{:keys [db]} _ev]
    (let [gen (get-in db [:clock :tick-gen])]
      {:fx [[:dispatch-later {:ms    CLOCK-TICK-MS
                              :event [::clock-tick gen]}]]})))

(rf/reg-event-fx ::clock-tick
  ;; On-demand single-tick handler (rf2-gxgmt). The per-frame "Tick"
  ;; button dispatches `[::clock-tick gen]` with the current
  ;; `:tick-gen`; the handler increments `:ticks` and stops. The
  ;; generation guard stays idiomatic — `:tick-gen` lets a future
  ;; Reset bump the generation so any stale in-flight ticks no-op.
  (fn [{:keys [db]} [_ gen]]
    (if (not= gen (get-in db [:clock :tick-gen]))
      ;; Stale tick from a retired generation. Drop it silently.
      {}
      {:db (update-in db [:clock :ticks] (fnil inc 0))})))

(rf/reg-sub ::clock-ticks
  (fn [db _] (get-in db [:clock :ticks])))

;; ============================================================================
;; TITLE (HTTP) — :title/flow state machine + mock-HTTP fx
;; ============================================================================
;;
;; The `:title/flow` machine cycles
;;
;;   :idle → :loading → :loaded
;;                    → :error
;;
;; on Refresh / Force-error. Entry actions seed the in-flight request
;; payload into the machine's `:data` slot so the request body and
;; the per-request `:force-error?` flag travel through the cascade.
;;
;; The mock HTTP effect is `::mock-fetch` (frame-scoped via the fx
;; envelope — `js/setTimeout` carries the frame id forward through
;; the closure to ensure the reply lands on the originating frame).
;; The 600ms delay is the legitimate slow-effect Issue source — see
;; the README and the docstring on HTTP-MOCK-DELAY-MS.

(def title-flow
  "State machine driving the Title-request lifecycle.

  States:
    :idle    — initial; ready to fetch
    :loading — request in flight; mock fx running
    :loaded  — last fetch succeeded; :data carries the value
    :error   — last fetch failed; :data carries the error message

  Events:
    :refresh         — :idle / :loaded / :error → :loading (no-op from :loading)
    :reply-success   — :loading → :loaded
    :reply-failure   — :loading → :error"
  {:initial :idle
   :data    {:value nil :error nil}

   :actions
   {:record-reply-success
    (fn record-reply-success [{[_ payload] :event}]
      {:data {:value payload :error nil}})

    :record-reply-failure
    (fn record-reply-failure [{[_ message] :event}]
      {:data {:value nil :error message}})}

   :states
   {:idle
    {:on {:refresh {:target :loading}}}

    :loading
    ;; Note: no transition for :refresh while in :loading — clicking
    ;; Refresh again is a no-op until the current request settles.
    {:on {:reply-success {:target :loaded
                          :action :record-reply-success}
          :reply-failure {:target :error
                          :action :record-reply-failure}}}

    :loaded
    {:on {:refresh {:target :loading}}}

    :error
    {:on {:refresh {:target :loading}}}}})

(rf/reg-machine :title/flow title-flow)

;; ----------------------------------------------------------------------------
;; Mock-HTTP fx
;; ----------------------------------------------------------------------------
;;
;; In-process mock fx that resolves ~HTTP-MOCK-DELAY-MS after dispatch
;; with the wall-clock time, or rejects when `:force-error?` is set on
;; the args map. The frame id is carried through the closure so the
;; settled reply dispatches back to the originating frame (subs are
;; per-frame; the response must land where the request fired).
;;
;; Why a custom fx (rather than `:rf.http/managed-canned-success`)?
;;   - We want a single fx that handles both success and failure paths
;;     under the same delay — Causa's Trace lens then surfaces one row
;;     per request regardless of outcome.
;;   - We want the slow-effect duration to be exactly long enough to
;;     trip Spec 009's slow-effect Issue surface. A custom fx pins the
;;     duration locally without depending on managed-HTTP's retry
;;     policy semantics.
;;   - Bundle isolation: nothing under `tools/causa/testbeds/` ships
;;     in production, so the in-process mock is appropriate here.

(rf/reg-fx ::mock-fetch
  (fn [{:keys [frame]} {:keys [force-error?]}]
    ;; `frame` from the fx ctx is the originating frame id (Spec 002
    ;; §`:fx` ordering). Capture it in the closure so the deferred
    ;; reply dispatch lands on the same frame the request fired from.
    ;; Subs are per-frame; the response must land where the request
    ;; fired, otherwise the wrong frame's :title/flow machine sees
    ;; the reply.
    (js/setTimeout
      (fn []
        (if force-error?
          (rf/dispatch [:title/flow [:reply-failure "Forced error (mock)"]]
                       {:frame frame})
          (rf/dispatch [:title/flow
                        [:reply-success
                         (str "Parallel-Frames @ "
                              (.toISOString (js/Date.)))]]
                       {:frame frame})))
      HTTP-MOCK-DELAY-MS)))

;; ----------------------------------------------------------------------------
;; Title events
;; ----------------------------------------------------------------------------

(rf/reg-event-fx ::title-refresh
  (fn [{:keys [db]} [_ {:keys [force-error?]}]]
    {:db (update-in db [:title :requests] (fnil inc 0))
     ;; Drive the machine first (intra-frame `:dispatch`), then issue
     ;; the mock fetch. The machine's :loading entry will be live by
     ;; the time the fetch settles ~600ms later.
     :fx [[:dispatch [:title/flow [:refresh]]]
          [::mock-fetch {:force-error? (boolean force-error?)}]]}))

(rf/reg-sub ::title-state
  (fn [db _] (get-in db [:rf/machines :title/flow :state])))

(rf/reg-sub ::title-data
  (fn [db _] (get-in db [:rf/machines :title/flow :data])))

(rf/reg-sub ::title-requests
  (fn [db _] (get-in db [:title :requests])))

;; ============================================================================
;; VIEWS — one panel mounted twice (once per frame-provider subtree)
;; ============================================================================
;;
;; The `reg-view`-injected `dispatch` / `subscribe` resolve via React
;; context to the surrounding `frame-provider`'s frame id (per
;; Spec 002 §View ergonomics). Same view source, two independent
;; reactive contexts.

(reg-view frame-panel [frame-label]
  (let [counter   @(subscribe [::counter])
        ticks     @(subscribe [::clock-ticks])
        state     @(subscribe [::title-state])
        data      @(subscribe [::title-data])
        requests  @(subscribe [::title-requests])
        loading?  (= state :loading)
        accent    (case frame-label
                    "above" "#2b7"
                    "below" "#36c"
                    "#444")]
    [:section {:data-testid (str frame-label "-panel")
               :style {:border        (str "1px solid " accent)
                       :border-radius "6px"
                       :padding       "1em 1.25em"
                       :background    (case frame-label
                                        "above" "#f7fff9"
                                        "below" "#f5f8ff"
                                        "#fafafa")
                       :margin        "0.5em 0"}}
     [:header {:style {:display         "flex"
                       :justify-content "space-between"
                       :align-items     "baseline"}}
      [:h3 {:style {:margin 0 :color accent}}
       (str/upper-case frame-label) " frame "
       [:small {:style {:color "#666" :font-weight "normal"}}
        "(" frame-label ")"]]
      [:span {:style {:font-size "11px" :color "#888"}}
       "isolated reactive context"]]

     ;; --- Counter -------------------------------------------------------
     [:div {:style {:margin "0.75em 0 0.5em 0"
                    :display "flex" :gap "8px" :align-items "center"}}
      [:strong "Counter:"]
      [:button {:data-testid (str frame-label "-counter-dec")
                :on-click    #(dispatch [::counter-dec])}
       "−"]
      [:span {:data-testid (str frame-label "-counter-value")
              :style       {:min-width "2em"
                            :text-align "center"
                            :font-family "monospace"
                            :font-weight "bold"}}
       counter]
      [:button {:data-testid (str frame-label "-counter-inc")
                :on-click    #(dispatch [::counter-inc])}
       "+"]]

     ;; --- Clock ---------------------------------------------------------
     ;; rf2-gxgmt — on-demand tick (auto-tick chain retired). Clicking
     ;; the per-frame Tick button increments only this frame's :ticks
     ;; slot; the other frame stays put. Parallel-frame isolation
     ;; visible without continuous spine noise.
     [:div {:style {:margin "0.5em 0"
                    :display "flex" :gap "8px" :align-items "center"}}
      [:strong "Clock:"]
      [:button {:data-testid (str frame-label "-clock-tick")
                :on-click    #(dispatch [::clock-tick 0])}
       "Tick"]
      [:span {:data-testid (str frame-label "-clock-ticks")
              :style       {:font-family "monospace"}}
       (str ticks " tick" (when (not= 1 ticks) "s"))]
      [:span {:style {:font-size "11px" :color "#888"}}
       "on-demand"]]

     ;; --- Title (HTTP) --------------------------------------------------
     [:div {:style {:margin "0.5em 0 0 0"
                    :padding "0.5em 0"
                    :border-top "1px solid #ddd"}}
      [:div {:style {:display "flex" :gap "8px" :align-items "center"
                     :margin-bottom "0.25em"}}
       [:strong "Title:"]
       [:span {:data-testid (str frame-label "-title-state")
               :style {:font-family "monospace"
                       :color (case state
                                :loading "#249"
                                :loaded  "#1a5"
                                :error   "#a40"
                                "#444")}}
        (str state)]
       [:span {:style {:font-size "11px" :color "#888"}}
        "(" requests " request" (when (not= 1 requests) "s") ")"]]
      [:div {:data-testid (str frame-label "-title-value")
             :style {:font-family "monospace"
                     :font-size "12px"
                     :color "#333"
                     :background "#fff"
                     :border "1px solid #eee"
                     :border-radius "3px"
                     :padding "4px 8px"
                     :margin "0.25em 0"
                     :overflow "auto"}}
       (cond
         (:error data) (str "ERROR: " (:error data))
         (:value data) (:value data)
         :else         "(no value yet — click Refresh)")]
      [:div {:style {:display "flex" :gap "6px" :margin-top "0.25em"}}
       [:button {:data-testid (str frame-label "-title-refresh")
                 :disabled    loading?
                 :on-click    #(dispatch [::title-refresh {}])}
        (if loading? "Loading…" "Refresh (HTTP)")]
       [:button {:data-testid (str frame-label "-title-force-error")
                 :disabled    loading?
                 :on-click    #(dispatch [::title-refresh
                                          {:force-error? true}])}
        "Force error"]]]]))

(reg-view root []
  [:div {:data-testid "parallel-frames-root"
         :style {:font-family "system-ui, sans-serif"
                 :padding     "1em"
                 :max-width   "900px"
                 :margin      "0 auto"}}
   [:header {:style {:margin-bottom "1em"}}
    [:h2 {:style {:margin 0}} "Parallel Frames demo"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "Same app, two isolated reactive contexts. Each "
     [:code "frame-provider"]
     " below mounts the same view source against a separate "
     [:code "app-db"] " + sub-cache. Click "
     [:em "Refresh"] " in one frame; the other stays put. Switch frames
      in Causa (Ctrl+Shift+C, then use the frame picker) to compare."]]
   [:div {:style {:display "flex" :flex-direction "column"}}
    [rf/frame-provider {:frame frame-above}
     [frame-panel "above"]]
    [rf/frame-provider {:frame frame-below}
     [frame-panel "below"]]]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(def ^:private default-project-root
  "C:/Users/miket/code/re-frame2/tools/causa/testbeds")

(defn- query-param
  "Return the named URL query param as a string, or nil when absent
  / blank. Pure-data helper — kept private to this testbed since the
  query-string override is a per-host knob (not a Causa-API surface)."
  [param-name]
  (when (exists? js/window)
    (let [params (-> js/window .-location .-search
                     (js/URLSearchParams.))
          v      (.get params param-name)]
      (when (and (string? v) (seq v)) v))))

(defn- resolve-project-root []
  (or (query-param "project-root") default-project-root))

(defn ^:export run []
  ;; Configure Causa BEFORE `rf/init!` so the preload's auto-open
  ;; reads the right project-root on its first paint of any chip.
  ;; (rf2-6jyf6 — see the same configure! call in the shop testbed.)
  (causa-config/configure! {:rf.causa/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Register the two frames. Each `:on-create` seeds its own app-db
  ;; synchronously (rf2-gxgmt — the clock-tick chain is no longer
  ;; auto-started; the per-frame Tick button drives `::clock-tick` on
  ;; demand instead). The `::initialise` handler is registered once
  ;; globally and resolves against whichever frame the dispatch
  ;; envelope targets — per-frame state evolution is automatic.
  (rf/reg-frame frame-above {:on-create [::initialise]})
  (rf/reg-frame frame-below {:on-create [::initialise]})
  (rdc/render react-root [root]))
