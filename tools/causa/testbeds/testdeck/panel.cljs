(ns testdeck.panel
  "Testdeck PANEL (rf2-6qgbs.1) — the reusable tabbed app shell that
  both testdeck surfaces mount. ONE app code path, mounted by:

    - `two-frame-isolation.core` (rf2-6qgbs.1) — twice, in two
      frame-providers, to prove per-frame isolation.
    - the single-frame step-deck (rf2-6qgbs.2) — once.

  The shell is a tab bar over the four locked feature surfaces
  (Counter / Machine / Routing / Async&errors). Tabs ARE routes
  (`testdeck.routes`): a tab click dispatches `:rf.route/navigate`
  through the surrounding frame-provider's injected `dispatch`, so the
  click navigates ONLY the frame it landed in. The body switches on the
  per-frame `:testdeck/active-tab` sub.

  ## Per-frame everything

  Every `subscribe` / `dispatch` in the views below is the
  `reg-view`-injected pair that resolves via React context to the
  surrounding frame-provider's frame id (Spec 002 §View ergonomics).
  The SAME view source therefore produces independent reactive contexts
  per frame — two counters, two machines, two route slots, two async
  slices, all isolated. No view here reaches into another frame's
  app-db (Spec 006 §The cache is held inside the frame container); the
  cross-frame anti-pattern is structurally impossible because the
  injected accessors only see the current frame.

  ## Boot

  `:testdeck/initialise` seeds a frame's app-db with each feature's
  initial slice and pings the machine so its `:disconnected` snapshot
  materialises on first paint. Wired as a frame's `:on-create` event.
  A TEST surface — no deliberate bugs, no teaching layers."
  (:require [testdeck.routes :as routes]
            [testdeck.counter :as counter]
            [testdeck.machine :as machine]
            [testdeck.async :as async]
            [re-frame.core :as rf]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; BOOT — :testdeck/initialise  (per-frame :on-create)
;; ============================================================================
;;
;; Registered once globally; runs once per frame against that frame's
;; empty app-db (Spec 002 §Frame creation). Seeds each feature slice and
;; navigates to the default tab so the route slot is live on first
;; paint. Pings the connection machine so its initial `:disconnected`
;; snapshot materialises (Spec 005 §Initial-state cascading) — the
;; Causa Machines lens then shows the machine from the first frame.

(rf/reg-event-fx :testdeck/initialise
  {:doc "Seed a frame's app-db for all four feature tabs and land it on
         the default tab. Single `:on-create` boot event per frame."}
  (fn handler-testdeck-initialise [_ctx _ev]
    {:db (merge counter/initial-db async/initial-db machine/initial-db)
     :fx [;; Materialise the machine's initial snapshot (no-op event;
          ;; the runtime synthesises the :disconnected snapshot on first
          ;; dispatch).
          [:dispatch [:ws/connection [:ws/noop]]]
          ;; Land on the default tab — writes this frame's :rf/route slot.
          [:dispatch [:rf.route/navigate routes/default-tab]]]}))

;; ============================================================================
;; SHARED STYLE HELPERS  (pure data — no per-frame state)
;; ============================================================================

(def ^:private mono
  {:font-family "ui-monospace, SFMono-Regular, Menlo, monospace"})

(defn- pill-style [active? on off]
  {:font-size      "11px"
   :font-weight    "bold"
   :text-transform "uppercase"
   :letter-spacing "0.04em"
   :padding        "1px 8px"
   :border-radius  "999px"
   :color          (if active? (:fg on) (:fg off))
   :background     (if active? (:bg on) (:bg off))
   :border         (str "1px solid " (if active? (:bd on) (:bd off)))})

(def ^:private ok-pill  {:fg "#1a5" :bg "#e6f7ee" :bd "#bfe6cf"})
(def ^:private hot-pill {:fg "#a40" :bg "#fdece6" :bd "#f3cab8"})

;; ============================================================================
;; COUNTER TAB
;; ============================================================================

(reg-view counter-tab [frame-label]
  ;; UI state lives in app-db (frame-scoped, Causa-observable): whether
  ;; to render the L2 parity sub (its create/destroy is observable in
  ;; Causa's Views lens), and the changeable threshold for the
  ;; show-greater-than view.
  (let [value        @(subscribe [:counter/value])
        clicked      @(subscribe [:counter/last-clicked])
        show-parity? @(subscribe [:counter/show-parity?])
        threshold    @(subscribe [:counter/threshold])
        ;; The canonical changeable-arg view: show-greater-than-N. The
        ;; threshold is the changeable argument carried in the query
        ;; vector — default 5 (show-greater-than-five).
        gt?          @(subscribe [:counter/greater-than? threshold])]
    [:div {:data-testid (str frame-label "-counter-tab")}
     [:div {:style {:display "flex" :gap "8px" :align-items "center"
                    :margin "0.5em 0"}}
      [:strong "Counter:"]
      [:button {:data-testid (str frame-label "-counter-dec")
                :on-click    #(dispatch [:counter/dec])} "−"]
      [:span {:data-testid (str frame-label "-counter-value")
              :style       (merge mono {:min-width "2.5em" :text-align "center"
                                        :font-weight "bold" :font-size "18px"})}
       value]
      [:button {:data-testid (str frame-label "-counter-inc")
                :on-click    #(dispatch [:counter/inc])} "+"]
      [:button {:data-testid (str frame-label "-counter-reset")
                :on-click    #(dispatch [:counter/reset])} "reset"]]

     ;; Changeable-arg view: show-greater-than-N = (> counter N). The
     ;; threshold control changes the argument, so the same sub
     ;; registration backs many distinct cache entries.
     [:div {:data-testid (str frame-label "-counter-gt")
            :style {:margin "0.25em 0" :display "flex" :gap "6px"
                    :align-items "center"}}
      "show-greater-than"
      [:input {:type "number"
               :data-testid (str frame-label "-counter-threshold")
               :value     threshold
               :on-change #(dispatch [:counter/set-threshold
                                      (js/parseInt (.. % -target -value) 10)])
               :style {:width "4em"}}]
      [:span {:style (pill-style gt? hot-pill ok-pill)}
       (if gt? (str "counter > " threshold) (str "counter ≤ " threshold))]]

     ;; L2 sub create/destroy: toggle the parity pill on/off. When ON the
     ;; :counter/parity L2 sub is created (a live view reads it); when OFF
     ;; it is destroyed (last reader gone). Observable in Causa's Views
     ;; lens.
     [:div {:style {:margin "0.25em 0"}}
      [:label {:style {:font-size "12px" :cursor "pointer"}}
       [:input {:type      "checkbox"
                :data-testid (str frame-label "-counter-parity-toggle")
                :checked   (boolean show-parity?)
                :on-change #(dispatch [:counter/toggle-parity])}]
       " show parity (L2 sub create/destroy)"]
      (when show-parity?
        (let [parity @(subscribe [:counter/parity])]
          [:span {:data-testid (str frame-label "-counter-parity")
                  :style (merge {:margin-left "8px"}
                                (pill-style (= parity "even") ok-pill hot-pill))}
           parity]))]

     ;; now cofx — last-clicked wall-clock time stamped into app-db.
     [:div {:data-testid (str frame-label "-counter-last-clicked")
            :style (merge mono {:font-size "11px" :color "#888"})}
      "last clicked: "
      (if clicked (.toISOString (js/Date. clicked)) "—")]

     ;; Clean handler-exception path — a feature being exercised, not a
     ;; buggy demo. Lights up Causa's Issues lens scoped to this frame.
     [:div {:style {:margin-top "0.5em"}}
      [:button {:data-testid (str frame-label "-counter-throw")
                :on-click    #(dispatch [:counter/throw])}
       "throw (exercise error surface)"]]]))

;; ============================================================================
;; MACHINE TAB
;; ============================================================================

(reg-view machine-tab [frame-label]
  (let [state      @(subscribe [:ws/state])
        connected? @(subscribe [:ws/connected?])
        active?    @(subscribe [:ws/active?])
        failed?    @(subscribe [:ws/failed?])
        armed?     @(subscribe [:ws/fail-armed?])
        messages   @(subscribe [:ws/messages])
        conns      @(subscribe [:ws/connections])
        draft      @(subscribe [:ws.ui/draft])]
    [:div {:data-testid (str frame-label "-machine-tab")}
     [:div {:style {:display "flex" :gap "8px" :align-items "center"
                    :margin "0.5em 0"}}
      [:strong "WebSocket:"]
      [:span {:data-testid (str frame-label "-machine-state")
              :style (merge mono {:color (cond connected? "#1a5"
                                               failed?    "#a40"
                                               active?    "#249"
                                               :else      "#444")})}
       (str state)]
      [:span {:style {:font-size "11px" :color "#888"}}
       "(" conns " connect" (when (not= 1 conns) "s") ")"]]

     [:div {:style {:display "flex" :gap "6px" :margin "0.25em 0" :flex-wrap "wrap"}}
      [:button {:data-testid (str frame-label "-machine-connect")
                :disabled    active?
                :on-click    #(dispatch [:ws/connection [:ws/connect]])}
       "Connect"]
      [:button {:data-testid (str frame-label "-machine-disconnect")
                :disabled    (= state :disconnected)
                :on-click    #(dispatch [:ws/connection [:ws/disconnect]])}
       "Disconnect"]
      [:label {:style {:font-size "12px" :cursor "pointer"
                       :display "flex" :align-items "center" :gap "3px"}}
       [:input {:type "checkbox"
                :data-testid (str frame-label "-machine-arm-fail")
                :checked  (boolean armed?)
                :on-change #(dispatch [:ws/connection
                                       [(if (.. % -target -checked)
                                          :ws/arm-fail :ws/disarm-fail)]])}]
       "next connect fails"]]

     ;; Send a message — only effective while :connected (the leaf
     ;; override + :can-send? guard gate the append).
     [:div {:style {:display "flex" :gap "6px" :margin "0.25em 0"}}
      [:input {:data-testid (str frame-label "-machine-input")
               :placeholder "message…"
               :value     (or draft "")
               :on-change #(dispatch [:ws.ui/set-draft (.. % -target -value)])
               :style {:flex 1}}]
      [:button {:data-testid (str frame-label "-machine-send")
                :disabled    (not connected?)
                :on-click    #(do (dispatch [:ws/connection [:ws/send draft]])
                                  (dispatch [:ws.ui/clear-draft]))}
       "Send"]
      [:button {:data-testid (str frame-label "-machine-clear")
                :on-click    #(dispatch [:ws/connection [:ws/clear]])}
       "Clear"]]

     [:ul {:data-testid (str frame-label "-machine-log")
           :style (merge mono {:font-size "12px" :color "#333"
                               :max-height "120px" :overflow "auto"
                               :margin "0.25em 0" :padding "0 0 0 1.25em"})}
      (if (seq messages)
        (map-indexed (fn [i m]
                       ^{:key i} [:li (str (name (:dir m)) " · " (:text m))])
                     messages)
        [:li {:style {:list-style "none" :color "#999" :margin-left "-1.25em"}}
         "(no messages — connect, then send)"])]]))

;; ============================================================================
;; ROUTING TAB  (the route table that drives tab nav)
;; ============================================================================

(reg-view routing-tab [frame-label]
  (let [active @(subscribe [:testdeck/active-tab])]
    [:div {:data-testid (str frame-label "-routing-tab")}
     [:p {:style {:color "#444" :margin "0.5em 0"}}
      "Tabs are routes. The active tab below is this frame's "
      [:code ":rf/route"] " slot — switching tabs is "
      [:code ":rf.route/navigate"] ", scoped to this frame."]
     [:div {:data-testid (str frame-label "-route-current")
            :style (merge mono {:margin "0.25em 0"})}
      "active route: "
      [:span {:style {:font-weight "bold"}} (str active)]]
     [:table {:style {:border-collapse "collapse" :font-size "12px"
                      :margin "0.5em 0"}}
      [:thead
       [:tr {:style {:text-align "left" :color "#666"}}
        [:th {:style {:padding "2px 12px 2px 0"}} "tab"]
        [:th {:style {:padding "2px 12px 2px 0"}} "route id"]
        [:th {:style {:padding "2px 0"}} "path"]]]
      [:tbody
       (for [{:keys [id label path]} routes/tabs]
         ^{:key id}
         [:tr {:style {:font-weight (if (= id active) "bold" "normal")}}
          [:td {:style {:padding "2px 12px 2px 0"}} label]
          [:td {:style (merge mono {:padding "2px 12px 2px 0"})} (str id)]
          [:td {:style (merge mono {:padding "2px 0" :color "#888"})} path]])]]]))

;; ============================================================================
;; ASYNC & ERRORS TAB
;; ============================================================================

(reg-view async-tab [frame-label]
  (let [status @(subscribe [:testdeck.async/status])
        value  @(subscribe [:testdeck.async/value])
        error  @(subscribe [:testdeck.async/error])
        n      @(subscribe [:testdeck.async/count])
        loading? (= status :loading)]
    [:div {:data-testid (str frame-label "-async-tab")}
     [:div {:style {:display "flex" :gap "8px" :align-items "center"
                    :margin "0.5em 0"}}
      [:strong "Async:"]
      [:span {:data-testid (str frame-label "-async-status")
              :style (merge mono {:color (case status
                                           :loading "#249"
                                           :loaded  "#1a5"
                                           :error   "#a40"
                                           "#444")})}
       (str status)]
      [:span {:style {:font-size "11px" :color "#888"}}
       "(" n " request" (when (not= 1 n) "s") ")"]]

     [:div {:data-testid (str frame-label "-async-result")
            :style (merge mono {:font-size "12px" :background "#fff"
                                :border "1px solid #eee" :border-radius "3px"
                                :padding "4px 8px" :margin "0.25em 0"
                                :overflow "auto"})}
      (cond
        error (str "ERROR: " error)
        value value
        :else "(no result yet — click Fetch)")]

     [:div {:style {:display "flex" :gap "6px" :margin-top "0.25em"}}
      [:button {:data-testid (str frame-label "-async-fetch")
                :disabled    loading?
                :on-click    #(dispatch [:testdeck.async/start {}])}
       (if loading? "Loading…" "Fetch (async, slow)")]
      [:button {:data-testid (str frame-label "-async-fetch-fail")
                :disabled    loading?
                :on-click    #(dispatch [:testdeck.async/start {:fail? true}])}
       "Fetch (fail)"]
      [:button {:data-testid (str frame-label "-async-reset")
                :on-click    #(dispatch [:testdeck.async/reset])}
       "reset"]]]))

;; ============================================================================
;; TAB BAR + PANEL SHELL
;; ============================================================================

(reg-view tab-bar [frame-label]
  (let [active @(subscribe [:testdeck/active-tab])]
    [:nav {:data-testid (str frame-label "-tab-bar")
           :style {:display "flex" :gap "2px" :border-bottom "2px solid #ddd"
                   :margin-bottom "0.5em"}}
     (for [{:keys [id label]} routes/tabs]
       (let [active? (= id active)]
         ^{:key id}
         [:button {:data-testid (str frame-label "-tab-" (name id))
                   :aria-current (when active? "page")
                   :on-click  #(dispatch [:rf.route/navigate id])
                   :style {:border        "none"
                           :border-bottom (str "2px solid "
                                               (if active? "#7C5CFF" "transparent"))
                           :background    "transparent"
                           :padding       "6px 12px"
                           :margin-bottom "-2px"
                           :cursor        "pointer"
                           :font-weight   (if active? "bold" "normal")
                           :color         (if active? "#333" "#777")}}
          label]))]))

(reg-view panel
  "The reusable testdeck panel: a tab bar over the four feature
  surfaces, switching the active body on the per-frame route slot. Both
  testdeck surfaces mount this view."
  [frame-label]
  (let [active @(subscribe [:testdeck/active-tab])]
    [:section {:data-testid (str frame-label "-panel")}
     [tab-bar frame-label]
     [:div {:data-testid (str frame-label "-tab-body")}
      (case active
        :testdeck/counter [counter-tab frame-label]
        :testdeck/machine [machine-tab frame-label]
        :testdeck/routing [routing-tab frame-label]
        :testdeck/async   [async-tab frame-label]
        [counter-tab frame-label])]]))
