(ns long-flow-w-failure.core
  "Shared framework-behavior testbed — a multi-second cascade of
  app-db writes that drive a three-flow topology, with a configurable
  mid-flow failure injection. A consumer (Causa, Story, pair2-mcp)
  observes the four-rule flow-failure contract (per
  [spec/013 §Failure semantics] / rf2-hrqvg) play out over a
  human-visible time window:

    Rule 1 — prior-flow writes are preserved (:flow-a's output keeps
             advancing while :flow-b's throw is mid-cascade).
    Rule 2 — the failing flow's output is not written; its
             `last-inputs` is NOT advanced; the flow re-attempts on
             the next drain.
    Rule 3 — the cascade halts at the failing flow; :flow-c does NOT
             run on the drain where :flow-b throws.
    Rule 4 — the exception surfaces at the router's outer catch as
             :rf.error/flow-eval-exception; the per-flow
             :rf.flow/failed trace fires first.

  The cascade ('long flow'):

    Click Start →
      [:dispatch [::tick]] now
      [:dispatch-later 250ms [::tick]]
      [:dispatch-later 500ms [::tick]] ...
      [:dispatch-later (N-1)*250ms [::tick]]

  Each :tick handler bumps :input — a numeric counter that's an
  input to all three flows. Every bump recomputes the three flows in
  topo order:

    :flow-a — :inputs [[:input]]; :output multiplies by 2; :path
              [:a-result].
    :flow-b — :inputs [[:input]]; :output throws on the configured
              tick index; otherwise multiplies by 3; :path
              [:b-result].
    :flow-c — :inputs [[:a-result] [:b-result]]; :output sums
              the two; :path [:c-result]. Only runs when :flow-a and
              :flow-b both successfully advance.

  Two toggles configure the failure injection:

    [Fail at tick #N] — the tick index at which :flow-b throws.
                        Default: 5 (so the failure lands ~1.25s in,
                        with prior ticks succeeding and subsequent
                        ticks still re-attempting).
    [Total ticks N]   — how many ticks the Start button schedules.
                        Default: 20 (5 seconds total at 250ms/tick).

  This is NOT a tutorial — the bodies are minimal. The whole point
  is to give a consumer a single Start click that produces a visible
  3-trace-per-tick stream over 5 seconds with a :rf.flow/failed
  shape at a deterministic mid-point a spec can assert against."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.flows]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; Constants
;; ----------------------------------------------------------------------------

(def default-tick-ms
  "Inter-tick delay. 250ms keeps the cascade visible to a human
  observer while being short enough for a Playwright spec to run
  through 20 ticks in ~5 seconds."
  250)

(def default-total-ticks
  "Total number of ticks the Start button schedules. 20 ticks at
  250ms/tick → 5-second cascade. Each tick produces three flow
  recomputes (or two if :flow-b throws at this tick), so the trace
  stream is ~60 :rf.flow/* events."
  20)

(def default-fail-at
  "Tick index at which :flow-b throws. With default-total-ticks=20
  and default-fail-at=5, four ticks succeed (1..4), :flow-b throws
  on tick 5, then ticks 6..20 each retry (because :flow-b's
  last-inputs is NOT advanced per rule 2) — every subsequent tick
  re-throws on :flow-b's recompute against the new :input."
  5)

;; ----------------------------------------------------------------------------
;; App-db
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::initialise
  (fn [_db _ev]
    {;; The single numeric input every flow watches.
     :input        0
     ;; Bookkeeping for the failure injection.
     :tick-count   0
     :fail-at      default-fail-at
     :total-ticks  default-total-ticks
     :status       :idle                 ;; :idle | :running | :done
     ;; The runtime writes :a-result / :b-result / :c-result via
     ;; the flows' :path slots — declared here only so the initial
     ;; state is observable before the first drain.
     :a-result     0
     :b-result     0
     :c-result     0}))

;; ----------------------------------------------------------------------------
;; Out-of-band closure capture for the failure threshold
;; ----------------------------------------------------------------------------
;;
;; :flow-b's :output reads the failure threshold via this atom so the
;; flow keeps `:inputs [[:input]]` (single-input, matches the
;; canonical "one upstream input is the failure trigger" shape). The
;; toggle button below `reset!`s this atom AND writes :fail-at into
;; app-db (so the view can display it). A consumer can also flip the
;; threshold via the atom directly for tests, but the app-db mirror
;; is the canonical view-state.

(defonce fail-at-atom (atom default-fail-at))

;; ----------------------------------------------------------------------------
;; Flows — three in topo order
;; ----------------------------------------------------------------------------
;;
;; Rule-1 / Rule-3 evidence: when :flow-b throws on tick N, :flow-a's
;; output for tick N IS flushed to :a-result (prior writes preserved);
;; :flow-c's output for tick N is NOT computed (cascade halts).

(rf/reg-flow
  {:id     ::flow-a
   :inputs [[:input]]
   :output (fn flow-a [input]
             ;; Trivial pure transform. The point is the WRITE — a
             ;; consumer that sees :a-result advance on tick N has
             ;; positive evidence of rule 1.
             (* 2 input))
   :path   [:a-result]
   :doc    "Doubles :input. Watched by :flow-c."})

(rf/reg-flow
  {:id     ::flow-b
   :inputs [[:input]]
   :output (fn flow-b [input]
             ;; HOT PATH — the failure-injection site for the
             ;; cascade. The throw fires whenever this fn is called
             ;; with input == :fail-at. The flow's last-inputs is
             ;; NOT advanced (rule 2), so the next drain with a new
             ;; :input value re-attempts; if the new :input is also
             ;; ≥ :fail-at, the throw re-fires.
             ;;
             ;; A consumer reading the trace stream sees
             ;; :rf.flow/failed (this flow's id) followed by
             ;; :rf.error/flow-eval-exception (rule 4) on EVERY
             ;; subsequent tick after the fail-at threshold —
             ;; positive evidence of rule 2.
             (let [fail-at-input
                   ;; The :input value the :tick handler bumps to
                   ;; coincides with the tick index — tick 5
                   ;; bumps :input to 5. So we throw iff input >=
                   ;; :fail-at (the runtime reads :fail-at out of
                   ;; app-db at flow-eval time via a closure capture
                   ;; below — flows take their inputs by path, but
                   ;; the throwing fn can read any closure-captured
                   ;; state).
                   ;;
                   ;; We close over `(fn ...)` rather than reading
                   ;; another path so :flow-b stays single-input
                   ;; (matches the realworld failure shape where
                   ;; ONE of N flow inputs is the failure trigger,
                   ;; not the failure config itself).
                   @fail-at-atom]
               (if (and (some? fail-at-input)
                        (>= input fail-at-input))
                 (throw (ex-info "long-flow-w-failure / :flow-b"
                                 {:where :flow
                                  :input input
                                  :fail-at fail-at-input}))
                 (* 3 input))))
   :path   [:b-result]
   :doc    "Triples :input — UNLESS input ≥ fail-at, in which case
            throws every recompute past that threshold."})

(rf/reg-flow
  {:id     ::flow-c
   :inputs [[:a-result] [:b-result]]
   :output (fn flow-c [a b]
             ;; Watches :flow-a's and :flow-b's outputs. Only runs
             ;; when both upstreams have successfully advanced —
             ;; per rule 3, :flow-c does NOT run on the drain
             ;; where :flow-b throws.
             (+ a b))
   :path   [:c-result]
   :doc    "Sums :a-result + :b-result. Watches :flow-a and
            :flow-b's outputs."})

;; ----------------------------------------------------------------------------
;; Tick handler — the cascade engine
;; ----------------------------------------------------------------------------

(rf/reg-event-fx ::tick
  (fn [{:keys [db]} _ev]
    (let [next-tick (inc (:tick-count db))
          total     (:total-ticks db)]
      ;; Bump :input — this is the WRITE every flow watches.
      ;; Per [spec/013 §Dirty-check semantics], the input-change
      ;; triggers a flows pass; all three flows recompute (or
      ;; attempt to) in topo order.
      {:db (-> db
               (assoc :input      next-tick)
               (assoc :tick-count next-tick)
               (cond->
                 (>= next-tick total) (assoc :status :done)))})))

(rf/reg-event-fx ::start
  (fn [{:keys [db]} _ev]
    (let [total   (:total-ticks db)
          tick-ms default-tick-ms]
      ;; Pre-schedule every tick at boot — the cascade is data,
      ;; not a recursive timer chain. Per [spec/002 §Cascade
      ;; propagation], `:dispatch-later` captures the current
      ;; frame id at scheduling time so every tick lands on the
      ;; right frame regardless of when the timer fires.
      ;;
      ;; The fx vector grows linearly with :total-ticks; for the
      ;; default 20 ticks this is 20 entries. A consumer testing
      ;; the cascade halt rules can dial :total-ticks down to a
      ;; minimum 6-or-so to keep specs fast.
      {:db (assoc db :status :running :tick-count 0 :input 0)
       :fx (vec (for [i (range 1 (inc total))]
                  [:dispatch-later
                   {:ms (* i tick-ms) :event [::tick]}]))})))

(rf/reg-event-fx ::reset
  (fn [_ctx _ev]
    {:fx [[:dispatch [::initialise]]]}))

(rf/reg-event-db ::set-fail-at
  (fn [db [_ new-fail-at]]
    (reset! fail-at-atom new-fail-at)
    (assoc db :fail-at new-fail-at)))

(rf/reg-event-db ::set-total-ticks
  (fn [db [_ new-total]]
    (assoc db :total-ticks new-total)))

;; ----------------------------------------------------------------------------
;; Subs + view
;; ----------------------------------------------------------------------------

(rf/reg-sub :input       (fn [db _] (:input db)))
(rf/reg-sub :tick-count  (fn [db _] (:tick-count db)))
(rf/reg-sub :fail-at     (fn [db _] (:fail-at db)))
(rf/reg-sub :total-ticks (fn [db _] (:total-ticks db)))
(rf/reg-sub :status      (fn [db _] (:status db)))
(rf/reg-sub :a-result    (fn [db _] (:a-result db)))
(rf/reg-sub :b-result    (fn [db _] (:b-result db)))
(rf/reg-sub :c-result    (fn [db _] (:c-result db)))

(reg-view buttons []
  (let [status      @(subscribe [:status])
        tick-count  @(subscribe [:tick-count])
        total       @(subscribe [:total-ticks])
        fail-at     @(subscribe [:fail-at])
        input-val   @(subscribe [:input])
        a-result    @(subscribe [:a-result])
        b-result    @(subscribe [:b-result])
        c-result    @(subscribe [:c-result])
        running?    (= status :running)]
    [:div {:data-testid "long-flow-w-failure"
           :style {:font-family "sans-serif" :padding "1em"}}
     [:h1 "long-flow-w-failure testbed"]
     [:p "A " [:span {:data-testid "duration-label"}
              (str (/ (* total default-tick-ms) 1000.0))]
      "-second cascade. Each tick recomputes three flows in topo
      order; :flow-b throws when :input ≥ fail-at, demonstrating
      the four-rule flow-failure contract over a human-visible
      window."]

     [:div {:style {:display :flex :gap "0.5em" :flex-wrap :wrap
                    :align-items :center :margin-bottom "0.5em"}}
      [:label
       "Fail at tick:"
       [:input {:data-testid "fail-at"
                :type        "number"
                :min         1
                :value       fail-at
                :style       {:width "4em" :margin-left "0.25em"}
                :disabled    running?
                :on-change   (fn [e]
                               (let [v (js/parseInt (.. e -target -value) 10)]
                                 (dispatch [::set-fail-at v])))}]]
      [:label
       "Total ticks:"
       [:input {:data-testid "total-ticks"
                :type        "number"
                :min         1
                :value       total
                :style       {:width "4em" :margin-left "0.25em"}
                :disabled    running?
                :on-change   (fn [e]
                               (let [v (js/parseInt (.. e -target -value) 10)]
                                 (dispatch [::set-total-ticks v])))}]]
      [:button {:data-testid "start"
                :on-click    #(dispatch [::start])
                :disabled    running?}
       "Start cascade"]
      [:button {:data-testid "reset"
                :on-click    #(dispatch [::reset])}
       "Reset"]]

     [:p {:style {:color "#666" :white-space :pre-wrap}}
      "status="     [:span {:data-testid "status"}     (name status)]      "\n"
      "tick="       [:span {:data-testid "tick"}       (str tick-count "/" total)] "\n"
      "input="      [:span {:data-testid "input"}      input-val]          "\n"
      "a-result="   [:span {:data-testid "a-result"}   a-result]
      "  (= 2 × input — rule 1 evidence)"                                  "\n"
      "b-result="   [:span {:data-testid "b-result"}   b-result]
      "  (= 3 × input until tick=" fail-at
      "; thereafter unchanged — rule 2 evidence)"                          "\n"
      "c-result="   [:span {:data-testid "c-result"}   c-result]
      "  (= a + b until tick=" fail-at
      "; thereafter unchanged — rule 3 evidence)"]]))

(reg-view root []
  [buttons])

;; ----------------------------------------------------------------------------
;; Mount
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [::initialise])
  (rdc/render react-root [root]))
