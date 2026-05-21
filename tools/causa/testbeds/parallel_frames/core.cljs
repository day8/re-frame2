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

  Each frame mounts the same panel exercising three feature surfaces:

    Counter        — `+ / −` buttons. Demonstrates events, app-db
                     evolution, simple sub recomputation.
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
  - Each frame's counter / machine state evolves on its own
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
  (fn [_ctx _ev]
    {:db {:counter   0
          :title     {:value    "Parallel-Frames (untouched)"
                      :error    nil
                      :requests 0}}
     :fx [[:dispatch [:title/flow [:rf/init]]]]}))

;; ============================================================================
;; COUNTER — events + sub
;; ============================================================================

(rf/reg-event-db ::counter-inc
  (fn [db _ev] (update db :counter (fnil inc 0))))

(rf/reg-event-db ::counter-dec
  (fn [db _ev] (update db :counter (fnil dec 0))))

(rf/reg-sub ::counter (fn [db _] (:counter db)))

;; Layer-2 derived sub — cascades ← ::counter. Recomputes whenever the
;; counter value changes (every +/- click), giving Causa's Views panel
;; live `cascaded?` data: ::counter is L1 (app-db input, cascaded ✗);
;; ::counter-parity is L2 (sub input, cascaded ✓ ← ::counter).
(rf/reg-sub ::counter-parity
  :<- [::counter]
  (fn [counter _] (if (even? counter) "even" "odd")))

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
;;
;; The panel is composed from two logical `reg-view`s — `counter-view`
;; and `title-view` — wrapped by `frame-panel`. Each child is its own
;; `reg-view` so its injected `dispatch` / `subscribe` resolve against
;; the SAME surrounding `frame-provider` context that `frame-panel`
;; renders under. The split is purely structural — the rendered DOM is
;; identical to the single monolithic panel it replaced.

(reg-view counter-view [frame-label]
  ;; Subscribing `::counter-parity` is what makes the L2 chain run — a
  ;; sub only recomputes when a rendered view reads it. The pill below
  ;; is therefore both a feature display AND the fixture that gives
  ;; Causa's Views `cascaded?` column live data on every +/- click.
  (let [counter @(subscribe [::counter])
        parity  @(subscribe [::counter-parity])
        even?    (= parity "even")]
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
      "+"]
     [:span {:data-testid (str frame-label "-counter-parity")
             :style {:font-size "11px"
                     :font-weight "bold"
                     :text-transform "uppercase"
                     :letter-spacing "0.04em"
                     :padding "1px 8px"
                     :border-radius "999px"
                     :color      (if even? "#1a5" "#a40")
                     :background  (if even? "#e6f7ee" "#fdece6")
                     :border      (str "1px solid " (if even? "#bfe6cf" "#f3cab8"))}}
      parity]]))

(reg-view title-view [frame-label]
  (let [state    @(subscribe [::title-state])
        data     @(subscribe [::title-data])
        requests @(subscribe [::title-requests])
        loading? (= state :loading)]
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
       "Force error"]]]))

(reg-view frame-panel [frame-label]
  (let [accent (case frame-label
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
     [counter-view frame-label]
     [title-view frame-label]]))

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
  ;; synchronously. The `::initialise` handler is registered once
  ;; globally and resolves against whichever frame the dispatch
  ;; envelope targets — per-frame state evolution is automatic.
  (rf/reg-frame frame-above {:on-create [::initialise]})
  (rf/reg-frame frame-below {:on-create [::initialise]})
  (rdc/render react-root [root]))
