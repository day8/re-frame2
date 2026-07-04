(ns long-flow-w-failure.core
  "Shared framework-behavior testbed — a multi-second cascade of
  app-db writes that drive a three-flow topology, with a configurable
  mid-flow failure injection. A consumer (Xray, Story, re-frame2-pair-mcp)
  observes the flow-failure ATOMICITY contract (per
  [spec/013 §Failure semantics] / rf2-u0zz5) play out over a
  human-visible time window:

    A flow throw is a PRE-INSTALL throw, so it ABORTS THE WHOLE EVENT:
      - No :db install — app-db is UNCHANGED for the failing tick.
        Neither :input, nor :a-result (a prior flow's write), nor
        :b-result, nor :c-result advances. NO partial commit.
      - No :rf.event/db-changed for the failing tick.
      - :fx is skipped.
      - last-inputs is rolled back, so every flow re-attempts cleanly
        on the next drain.
      - The exception surfaces at the router as
        :rf.error/flow-eval-exception; the per-flow :rf.flow/failed
        trace fires first for attribution.

    So: ticks BEFORE fail-at commit all three flows (every value
    advances). The tick AT/AFTER fail-at throws on :flow-b and the
    whole tick aborts — the visible state freezes at the last clean
    tick, while the trace stream still shows :rf.flow/computed (for
    the prior flow that RAN before the throw — its write was
    discarded) → :rf.flow/failed → :rf.error/flow-eval-exception per
    failing tick.

  The cascade ('long flow'):

    Click Start →
      [:dispatch [::tick]] now
      [:dispatch-later 250ms [::tick]]
      [:dispatch-later 500ms [::tick]] ...
      [:dispatch-later (N-1)*250ms [::tick]]

  Each :tick handler bumps :input — a numeric counter that's an
  input to all three flows. Every bump recomputes the three flows in
  topo order:

    :flow-a — :inputs [[:input]]; :derive multiplies by 2; :output-path
              [:a-result].
    :flow-b — :inputs [[:input]]; :derive throws on the configured
              tick index; otherwise multiplies by 3; :output-path
              [:b-result].
    :flow-c — :inputs [[:a-result] [:b-result]]; :derive sums
              the two; :output-path [:c-result]. Only runs when :flow-a and
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
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

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
  and default-fail-at=5, four ticks succeed (1..4) and commit all
  three flows; tick 5 throws on :flow-b and the WHOLE tick aborts
  (no install — app-db frozen at tick 4); ticks 6..20 each re-attempt
  and re-abort (their :input ≥ :fail-at, so :flow-b re-throws). The
  visible state stays frozen at tick 4 while the trace stream shows a
  :rf.flow/failed → :rf.error/flow-eval-exception pair per failing
  tick — and NO :rf.event/db-changed for any of ticks 5..20."
  5)

;; ----------------------------------------------------------------------------
;; App-db
;; ----------------------------------------------------------------------------

(rf/reg-event ::initialise
  (fn [_cofx _ev]
    {:db {;; The single numeric input every flow watches.
          :input        0
          ;; Bookkeeping for the failure injection.
          :tick-count   0
          :fail-at      default-fail-at
          :total-ticks  default-total-ticks
          :status       :idle                 ;; :idle | :running | :done
          ;; The runtime writes :a-result / :b-result / :c-result via
          ;; the flows' :output-path slots — declared here only so the initial
          ;; state is observable before the first drain.
          :a-result     0
          :b-result     0
          :c-result     0}}))

;; ----------------------------------------------------------------------------
;; Out-of-band closure capture for the failure threshold
;; ----------------------------------------------------------------------------
;;
;; :flow-b's :derive reads the failure threshold via this atom so the
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
;; Topology pin (rf2-sabm5). Spec 013 §Topological sort orders a flow
;; map by path-prefix overlap between one flow's `:output-path` and another's
;; `:inputs` — two flows that don't share such an overlap are
;; topologically *parallel*, and Kahn's algorithm picks an unspecified
;; order between them. The atomicity contract distinguishes the flow
;; that RAN before the throw (whose :rf.flow/computed trace still
;; fires, even though its write is discarded by the abort) from the
;; downstream flow that never ran. To make :flow-a the demonstrable
;; "ran-then-discarded" prior flow and :flow-c the "never-ran"
;; downstream flow, the testbed pins :flow-a → :flow-b → :flow-c in
;; topo order: listing `[:a-result]` in :flow-b's :inputs forces the
;; path-prefix overlap edge with :flow-a's :output-path, and the
;; `[:a-result] [:b-result]` pair already pins :flow-c after both.
;;
;; The :input itself stays in :flow-b's :inputs so the read-from-app-
;; db dirty-check still fires when the tick handler bumps :input —
;; without that input edge, :flow-b would still re-evaluate on every
;; tick (because :a-result advanced), but the input value used inside
;; :derive would not be the trigger. Listing both keeps the value
;; flow honest and the ordering pinned.
;;
;; Atomicity evidence: when :flow-b throws on tick N, the WHOLE tick
;; aborts — :flow-a's :rf.flow/computed fires (it ran) but its write
;; is DISCARDED (no install); :flow-c never runs (cascade halts); and
;; app-db (incl. :input, :a-result, :b-result, :c-result) is frozen at
;; tick N-1's committed value.

;; main (rf2-3khbut): the three reg-flow calls moved OUT of ns-load and INTO
;; `run` AFTER `rf/init!` via this fn — same fix as the sibling deliberate_throw
;; testbed (main rf2-7gvnmy). A frame must be live or the rf2-zbxvqj guard in
;; `reg-flow` rejects with :rf.error/flow-frame-not-live (no frame is live until
;; init!). Registration order relative to the events/fx/subs above doesn't
;; matter — the runtime topsorts the flow map before the first drain.
;; EP-0002 (rf2-5q7um6 / rf2-9o48ih): reg-flow is context-required frame-local;
;; it needs a CARRIED frame stamp. `run` calls this inside a
;; `(with-frame :rf/default …)` scope so all three flows register against the
;; explicitly-registered `:rf/default` frame (the runtime never synthesises one
;; from absence). This testbed hosts on :rf/default.
(defn- register-flows!
  "Register the three cascade flows against the live `:rf/default` frame.
  Called from `run` after `rf/init!` (and inside a `with-frame :rf/default`
  scope) — a frame must be live or the rf2-zbxvqj guard in `reg-flow`
  rejects with :rf.error/flow-frame-not-live."
  []

  (rf/reg-flow ::flow-a
  {:inputs [[:input]]
   :output-path [:a-result]
   :doc    "Doubles :input. Watched by :flow-b (topo-order pin) and
            :flow-c (math)."}
  (fn flow-a [input]
    ;; Trivial pure transform. The point is the WRITE — on a
    ;; clean tick :a-result advances and commits; on a failing
    ;; tick :flow-a's :rf.flow/computed still fires (it ran)
    ;; but the write is DISCARDED when the tick aborts.
    (* 2 input)))

(rf/reg-flow ::flow-b
  {;; Two inputs — :input drives the math; :a-result is the topo-
   ;; order pin (see flows-block comment above). The :derive fn
   ;; ignores the a-result value; its only job is to declare the
   ;; dependency edge so :flow-a's write is observably PRIOR when
   ;; this flow throws.
   :inputs [[:input] [:a-result]]
   :output-path [:b-result]
   :doc    "Triples :input — UNLESS input ≥ fail-at, in which case
            throws every recompute past that threshold. Reads
            :a-result for topo-order only (math uses :input)."}
  (fn flow-b [input _a-result]
    ;; HOT PATH — the failure-injection site for the
    ;; cascade. The throw fires whenever this fn is called
    ;; with input ≥ :fail-at. The throw aborts the whole
    ;; tick (no install); last-inputs is rolled back, so the
    ;; next drain with a new :input value re-attempts. If the
    ;; new :input is also ≥ :fail-at, the throw re-fires.
    ;;
    ;; A consumer reading the trace stream sees
    ;; :rf.flow/failed (this flow's id) followed by
    ;; :rf.error/flow-eval-exception on EVERY tick after the
    ;; fail-at threshold — with NO :rf.event/db-changed for
    ;; any of those aborted ticks.
    (let [fail-at-input
          ;; The :input value the :tick handler bumps to
          ;; coincides with the tick index — tick 5
          ;; bumps :input to 5. So we throw iff input >=
          ;; :fail-at. We close over `(fn ...)` via the
          ;; module-level atom rather than reading another
          ;; app-db path so the failure threshold isn't
          ;; tangled with the dirty-check graph (matches
          ;; the realworld shape where the failure config
          ;; rides out-of-band, not in app-db).
          @fail-at-atom]
      (if (and (some? fail-at-input)
               (>= input fail-at-input))
        (throw (ex-info "long-flow-w-failure / :flow-b"
                        {:where :flow
                         :input input
                         :fail-at fail-at-input}))
        (* 3 input)))))

(rf/reg-flow ::flow-c
  {:inputs [[:a-result] [:b-result]]
   :output-path [:c-result]
   :doc    "Sums :a-result + :b-result. Watches :flow-a and
            :flow-b's outputs."}
  (fn flow-c [a b]
    ;; Watches :flow-a's and :flow-b's outputs. Only runs
    ;; when both upstreams have successfully advanced —
    ;; :flow-c does NOT run on the drain where :flow-b
    ;; throws (the cascade halts before it).
    (+ a b))))

;; ----------------------------------------------------------------------------
;; Tick handler — the cascade engine
;; ----------------------------------------------------------------------------

(rf/reg-event ::tick
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

(rf/reg-event ::start
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

(rf/reg-event ::reset
  (fn [_ctx _ev]
    {:fx [[:dispatch [::initialise]]]}))

(rf/reg-event ::set-fail-at
  (fn [{:keys [db]} [_ new-fail-at]]
    (reset! fail-at-atom new-fail-at)
    {:db (assoc db :fail-at new-fail-at)}))

(rf/reg-event ::set-total-ticks
  (fn [{:keys [db]} [_ new-total]]
    {:db (assoc db :total-ticks new-total)}))

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
      the flow-failure ATOMICITY contract (a flow throw aborts the
      whole tick — nothing commits) over a human-visible window."]

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
      "  (= 2 × input until tick=" fail-at
      "; FROZEN thereafter — the failing tick aborts, nothing commits)"    "\n"
      "b-result="   [:span {:data-testid "b-result"}   b-result]
      "  (= 3 × input until tick=" fail-at
      "; FROZEN thereafter — :flow-b throws)"                              "\n"
      "c-result="   [:span {:data-testid "c-result"}   c-result]
      "  (= a + b until tick=" fail-at
      "; FROZEN thereafter — cascade halts)"]]))

(reg-view root []
  [buttons])

;; ----------------------------------------------------------------------------
;; Mount
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  ;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from
  ;; absence — `:rf/default` is this testbed's app frame, registered
  ;; explicitly here (init! installs only the adapter). The boot dispatch
  ;; runs under the frame scope and the render is wrapped in a
  ;; `frame-provider` so in-tree dispatch/subscribe resolve to it.
  (rf/reg-frame :rf/default {})
  ;; Both the three-flow registration (main rf2-3khbut: reg-flow must run AFTER
  ;; init! against a live frame) and the boot dispatch run inside the carried
  ;; `:rf/default` scope. The render is wrapped in a `frame-provider` so in-tree
  ;; dispatch/subscribe resolve to `:rf/default`.
  (rf/with-frame :rf/default
    (register-flows!)
    (rf/dispatch-sync [::initialise]))
  (rdc/render react-root [rf/frame-provider {:frame :rf/default} [root]]))
