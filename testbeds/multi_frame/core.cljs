(ns multi-frame.core
  "Shared framework-behavior testbed — three isolated frames living in
  the same page, with deliberate cross-frame dispatch. A consumer
  (Causa, Story, re-frame2-pair-mcp) observes that the framework keeps each
  frame's `app-db`, signal-graph cache, and epoch ring buffer cleanly
  partitioned — even when an event dispatched against frame A reaches
  in to dispatch against frame B during the same drain.

  The three frames:

    :counter/a  → simple counter; the canonical 'frame A' identity.
    :counter/b  → simple counter; the canonical 'frame B' identity.
    :log        → append-only event log; a passive sink the bridge
                  feeds. Demonstrates that an :origin tag carrying the
                  *source* frame survives the cross-frame hop.

  Three buttons exercise the cross-frame contract:

    Button A · Inc A          → frame :counter/a only
    Button B · Inc B          → frame :counter/b only
    Button X · Cross-bump     → handler in :counter/a fans out a
                                testbed bridge fx that dispatches with
                                public `rf/dispatch` frame opts. Reserved
                                `:dispatch` is intra-frame by contract.
                                that bumps :counter/b AND appends to
                                :log/entries. The three frames'
                                app-dbs diverge in lockstep.

  This is NOT a tutorial — the bodies are minimal. The whole point is
  to produce three distinct `[:rf/frame ... :rf/app-db ...]` ring
  buffer entries per click (per [spec/009 §Per-frame epoch buffers])
  and three distinct sub-cache populations a consumer can diff. No
  recovery, no error handling, no orchestration prose."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; Frames
;; ----------------------------------------------------------------------------
;;
;; Three named frames. The framework guarantees each gets its own
;; `app-db`, router queue, and signal-graph cache (per [spec/002
;; §What lives in a frame]). The handler registrar is global — the
;; ::initialise, ::inc, and ::log-append handlers below are registered
;; once and resolve against whichever frame the dispatch targets.

(def frame-a   :counter/a)
(def frame-b   :counter/b)
(def frame-log :log)

;; ----------------------------------------------------------------------------
;; App-db initialisers (per-frame shape, registered once)
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::counter-init
  (fn [_db _ev]
    {:n 0}))

(rf/reg-event-db ::log-init
  (fn [_db _ev]
    {:entries []}))

;; ----------------------------------------------------------------------------
;; Per-frame counter handlers
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::inc
  (fn [db _ev]
    ;; HOT PATH — runs against whichever frame the dispatch targeted.
    ;; The same handler-fn is exercised once per frame; the framework
    ;; passes the correct frame's `app-db` to each invocation.
    (update db :n (fnil inc 0))))

(rf/reg-sub :n (fn [db _] (:n db)))

;; ----------------------------------------------------------------------------
;; Log frame handler — pure sink for cross-frame writes
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::log-append
  (fn [db [_ entry]]
    (update db :entries (fnil conj []) entry)))

(rf/reg-sub :entries (fn [db _] (:entries db)))

;; ----------------------------------------------------------------------------
;; Cross-frame bridge handler
;; ----------------------------------------------------------------------------
;;
;; The handler RUNS against :counter/a (Button X dispatches against
;; :counter/a). It returns three fx — one local `:db` write and two
;; testbed bridge entries targeted at :counter/b and :log.
;;
;; Reserved `:dispatch` inherits the parent envelope frame, so this
;; testbed uses a deliberately tiny custom fx that calls public
;; `rf/dispatch` with explicit frame opts. That keeps the surface inside
;; the testbed while giving consumers real browser-visible fan-out.
;;
;; A consumer that watches the trace stream sees three
;; `:event/dispatched` traces for one click — one per frame — and
;; three separate epoch ring buffer entries.

(rf/reg-fx ::dispatch-to-frame
  (fn [_ctx {:keys [event frame]}]
    (rf/dispatch event {:frame frame})))

(rf/reg-event-fx ::cross-bump
  (fn [{:keys [db]} _ev]
    {;; Local write against :counter/a.
     :db (update db :n (fnil inc 0))
     :fx [;; HOT PATH — cross-frame dispatch to :counter/b.
          [::dispatch-to-frame {:event [::inc]
                                :frame frame-b}]
          ;; HOT PATH — cross-frame dispatch to :log; carries the
          ;; bridge's source-frame id verbatim as evidence the
          ;; framework didn't quietly rewrite it.
          [::dispatch-to-frame {:event [::log-append {:from frame-a
                                                      :to   #{frame-b frame-log}
                                                      :kind :cross-bump}]
                                :frame frame-log}]]}))

;; ----------------------------------------------------------------------------
;; Views
;; ----------------------------------------------------------------------------
;;
;; Each frame gets its own `frame-provider`-rooted subtree; the
;; `reg-view`-injected `dispatch` / `subscribe` inside each subtree
;; closes over the providing frame's id. A consumer that diffs the
;; rendered DOM observes three independent counters / log lines —
;; each fed by exactly one frame's signal-graph.

(reg-view counter-panel [label]
  ;; The injected `dispatch` / `subscribe` resolve via React context
  ;; to the surrounding `frame-provider`'s frame keyword (per [spec/002
  ;; §View ergonomics]).
  (let [n @(subscribe [:n])]
    [:div {:style {:border       "1px solid #aaa"
                   :padding      "0.5em"
                   :margin       "0.25em"
                   :min-width    "10em"}}
     [:h3 label]
     [:p "n=" [:span {:data-testid (str "n-" (name label))} n]]
     [:button {:data-testid (str "inc-" (name label))
               :on-click    #(dispatch [::inc])}
      "Inc"]]))

(reg-view log-panel []
  (let [entries @(subscribe [:entries])]
    [:div {:style {:border    "1px solid #aaa"
                   :padding   "0.5em"
                   :margin    "0.25em"
                   :min-width "20em"}}
     [:h3 "log"]
     [:p "count=" [:span {:data-testid "log-count"} (count entries)]]
     [:ul {:data-testid "log-entries"
           :style {:max-height "8em" :overflow :auto}}
      (for [[idx e] (map-indexed vector entries)]
        ^{:key idx}
        [:li (pr-str e)])]]))

(reg-view root []
  [:div {:data-testid "multi-frame" :style {:font-family "sans-serif" :padding "1em"}}
   [:h1 "multi-frame testbed"]
   [:p "Three frames coexist on this page. Each owns its own app-db,
        router queue, and sub-cache. The Cross-bump button dispatches
        a fan-out from " [:code ":counter/a"]
    " that bumps " [:code ":counter/b"] " and appends to "
    [:code ":log"] " in one drain."]
   [:p {:data-testid "multi-frame-fanout-browser-semantics"
        :style       {:color "#666"}}
    "The browser gate asserts direct A/B isolation, cross-frame fan-out
     into B and log, and Causa panel selection across the resulting
     frame-tagged traces."]

   [:div {:style {:display :flex :flex-wrap :wrap :align-items :flex-start}}
    [rf/frame-provider {:frame frame-a}
     [counter-panel "A"]]

    [rf/frame-provider {:frame frame-b}
     [counter-panel "B"]]

    [rf/frame-provider {:frame frame-log}
     [log-panel]]]

   [:div {:style {:margin-top "0.5em"}}
    ;; The Cross-bump button is OUTSIDE every `frame-provider` — it
    ;; reads the React-context default (`:rf/default`) and supplies
    ;; the target frame explicitly via the two-arg dispatch form.
    ;; The handler then fans out to :counter/b and :log via cross-
    ;; frame bridge fx (see ::cross-bump above).
    [:button {:data-testid "cross-bump"
              :on-click    #(rf/dispatch [::cross-bump] {:frame frame-a})}
     "Cross-bump (A → B + log)"]]])

;; ----------------------------------------------------------------------------
;; Mount
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  ;; Register the three frames before any dispatch. `:on-create`
  ;; seeds each frame's app-db via the corresponding init handler;
  ;; the framework runs each frame's `:on-create` against that
  ;; frame's empty app-db, so the same init handler can be shared
  ;; across both counter frames.
  (rf/reg-frame frame-a   {:on-create [::counter-init]})
  (rf/reg-frame frame-b   {:on-create [::counter-init]})
  (rf/reg-frame frame-log {:on-create [::log-init]})
  (rdc/render react-root [root]))
