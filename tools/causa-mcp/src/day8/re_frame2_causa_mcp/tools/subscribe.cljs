(ns day8.re-frame2-causa-mcp.tools.subscribe
  "Tool: `subscribe` — per-drain-batch streaming over a topic
  (rf2-8xzoe.26, T-Stream-1 of the causa-mcp streaming tranche).

  The MCP `tools/call` runs until either (a) the client aborts via the
  `extra.signal` AbortSignal, (b) an `unsubscribe` clears the sub-id
  from the runtime, (c) `:max-events` / `:max-ms` is reached, or (d)
  the runtime reports the sub is gone. While running, each polling
  tick emits ONE `notifications/progress` notification per non-empty
  batch correlated to the original `tools/call` via
  `extra._meta.progressToken`. The terminal `tools/call` result is a
  summary `{:ok? true :sub-id :topic :delivered N :ticks N :reason
  <terminated-reason>}`.

  ## Causa vs pair2-mcp model

  pair2-mcp's runtime owns a per-subscription queue with a bounded
  events/bytes budget; the server's drain-form pops events off the
  queue per tick. Causa's runtime is lighter — `subscribe!` records
  per-subscription metadata only; the events live on the central trace
  bus. The server polls the trace bus filtered by topic + an
  `:since-ms` cursor, yielding the events that landed in this tick's
  window. Per-tick port-pinning (the MCP contract surface) is
  preserved: one `progressToken`, one notification per tick, one
  terminal summary on close.

  Topic → trace projection:

  | Topic | Source |
  |---|---|
  | `:trace` | full trace buffer; `:op-type` filter optional |
  | `:epoch` | trace events with `:op-type :epoch/closed` |
  | `:fx` | trace events with `:op-type :fx/run` |
  | `:error` | issue-tier events (`:error` / `:warning` / `:rf.schema/violation` / `:rf.hydration/mismatch`) |

  ## Wire-boundary contract

  - **B-1 privacy** — every per-tick batch routes through
    `privacy/strip-sensitive` before egress; the cumulative
    `:dropped-sensitive` counter rides on the final summary.
  - **W-6 size elision** — the runtime accessor already routes each
    event through `re-frame.core/elide-wire-value` server-side; the
    per-tick batch's marker count accumulates onto the final summary's
    `:elided-large` counter.
  - **W-1 token cap** — applies to BOTH the per-tick progress payload
    and the final summary. Per-tick over-cap payloads are NOT
    dropped — the cap step runs on the final summary, not on
    notifications (the MCP SDK strips overflow markers from
    progress payloads without breaking the contract).

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:topic` | keyword | nil (REQUIRED) | `:trace`, `:epoch`, `:fx`, `:error` |
  | `:filter` | map | nil | per-topic filter (e.g. `{:op-type :event/dispatched}`) |
  | `:frame` | keyword | nil | scope to one frame |
  | `:poll-ms` | int | 100 | polling cadence |
  | `:max-events` | int | 0 | terminate after N events (0 = unbounded) |
  | `:max-ms` | int | 0 | terminate after N ms (0 = unbounded) |
  | `:include-sensitive?` | bool | false | opt back in to `:sensitive? true` |
  | `:include-large?` | bool | false | passes to runtime walker |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape (terminal summary)

      {:ok? true
       :sub-id <uuid>
       :topic <kw>
       :delivered <int>
       :ticks <int>
       :reason <:aborted|:max-events-reached|:max-ms-reached|:sub-gone|:unsubscribed>
       :dropped-sensitive <int?>
       :elided-large <int?>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #26. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [applied-science.js-interop :as j]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(def ^:const default-poll-ms 100)

(def ^:const recognised-topics
  "Closed set of topics the runtime accepts. Mirrors the runtime's
  `subscribe!` validation; we duplicate it server-side so an unknown
  topic short-circuits without an nREPL round-trip."
  #{:trace :epoch :fx :error})

(def ^:const initial-state
  "Per-stream rolling accumulators. One atom per `subscribe-tool`
  call; merged once per tick. Mirrors pair2-mcp's `initial-state` so
  the cross-MCP final-summary shape is byte-identical (with the
  exception of the queue-bound `:dropped-events`/`:dropped-bytes`
  slots which causa's lighter model doesn't carry)."
  {:tick              0
   :delivered         0
   :since-ms          0
   :dropped-sensitive 0
   :elided-large      0})

(def ^:const issue-op-types
  "The `:error` topic projects to this op-type set on the trace bus —
  errors, warnings, schema violations, hydration mismatches."
  #{:error :warning :rf.schema/violation :rf.hydration/mismatch})

;; ---------------------------------------------------------------------------
;; Topic → filter projection
;; ---------------------------------------------------------------------------

(defn topic->filter
  "Project a `:topic` keyword onto the runtime's trace-buffer filter
  vocabulary. Returns the filter map the runtime walker honours.
  Pure — tests pin the projection table."
  [topic user-filter frame since-ms]
  (let [base (cond-> (or user-filter {})
               frame              (assoc :frame frame)
               (pos? (or since-ms 0)) (assoc :since-ms since-ms))]
    (case topic
      :trace base
      :epoch (assoc base :op-type :epoch/closed)
      :fx    (assoc base :op-type :fx/run)
      :error base ;; :error topic post-filters by op-type set below
      base)))

(defn project-error-topic
  "Post-filter `events` to the issue-tier op-types when `topic` is
  `:error`. Pure — non-error topics pass through unchanged."
  [topic events]
  (if (= :error topic)
    (filterv #(contains? issue-op-types (:op-type %)) events)
    events))

;; ---------------------------------------------------------------------------
;; Eval form composition — drain a tick's worth of events
;; ---------------------------------------------------------------------------

(defn drain-form
  "Build the eval form addressing `runtime/get-trace-buffer` with the
  topic-projected filter. Pure — tests pin the form shape directly."
  [filter-opts]
  (-> (ef/rt-call 'get-trace-buffer filter-opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn subscribe-form
  "Build the eval form registering the subscription in the runtime's
  metadata atom. The runtime returns `{:ok? true :sub-id :topic
  :filter}` (or an `:unknown-topic` failure)."
  [topic user-filter]
  (-> (ef/rt-call 'subscribe! (cond-> {:topic topic}
                                user-filter (assoc :filter user-filter)))
      (ef/emit)
      (ef/wrap-origin)))

(defn unsubscribe-form
  "Build the eval form closing the subscription. Returns
  `{:ok? true :sub-id :existed?}`."
  [sub-id]
  (-> (ef/rt-call 'unsubscribe! {:sub-id sub-id})
      (ef/emit)
      (ef/wrap-origin)))

;; ---------------------------------------------------------------------------
;; Per-tick batch accumulators
;; ---------------------------------------------------------------------------

(defn merge-tick
  "Pure state update — fold one tick's contributions into the rolling
  accumulators. Public so tests pin the contract directly."
  [s {:keys [n dropped elided]}]
  (cond-> s
    (pos? (or n 0))       (-> (update :tick      inc)
                              (update :delivered + n))
    (pos? (or dropped 0)) (update :dropped-sensitive + dropped)
    (pos? (or elided 0))  (update :elided-large      + elided)))

(defn final-summary
  "Build the terminal `ok-text` result emitted when the subscription
  ends. Public so tests pin the shape; `wire/with-indicators` stamps
  the cumulative `:dropped-sensitive` + `:elided-large` counters per
  the cross-MCP indicator-field convention."
  [{:keys [sub-id topic state reason]}]
  (let [{:keys [tick delivered dropped-sensitive elided-large]} state]
    (wire/ok-text
      (wire/with-indicators
        {:ok?       true
         :sub-id    sub-id
         :topic     topic
         :delivered delivered
         :ticks     tick
         :reason    reason}
        {:dropped dropped-sensitive
         :elided  elided-large}))))

;; ---------------------------------------------------------------------------
;; Per-tick progress emission
;; ---------------------------------------------------------------------------

(defn progress-payload
  "Build the JSON params payload for one `notifications/progress` tick.
  Mirrors pair2-mcp's `progress-payload` shape so cross-MCP agents
  decode the same envelope from both servers. Public so tests pin
  the contract."
  [progress-token tick events-edn]
  #js {:progressToken progress-token
       :progress      tick
       :message       events-edn
       :_meta         #js {}})

(defn- emit-progress-tick!
  "Ship one `notifications/progress` notification. Failures swallowed
  — a flaky client must not collapse the stream."
  [{:keys [send-note progress-tk sub-id]} tick events dropped elided]
  (try
    (send-note
      #js {:method "notifications/progress"
           :params (progress-payload
                     progress-tk
                     tick
                     (pr-str (wire/with-indicators
                               {:sub-id sub-id :events events}
                               {:dropped dropped :elided elided})))})
    (catch :default _ nil)))

;; ---------------------------------------------------------------------------
;; Stream controller — termination + poll loop
;; ---------------------------------------------------------------------------

(defn- parse-mcp-extra
  "Pluck the three MCP-host slots the streaming loop needs out of the
  JS `extra` object: the abort signal, the progress-notification
  emitter, and the progress token correlating ticks to this
  `tools/call`."
  [extra]
  {:signal      (some-> extra (j/get :signal))
   :send-note   (some-> extra (j/get :sendNotification))
   :progress-tk (some-> extra (j/get :_meta) (j/get :progressToken))})

(defn- make-stream-controller
  "Build the `terminate` + `poll` fns over a shared `state` atom. The
  `terminate` issues the runtime-side `unsubscribe!` and resolves the
  outer `tools/call` Promise with the final-summary envelope. `poll`
  runs the drain → state-merge → progress-emit → reschedule cycle."
  [{:keys [conn build-id sub-id topic resolve state signal send-note
           progress-tk poll-ms max-events incl? frame user-filter]}]
  (let [terminated? (atom false)
        terminate
        (fn terminate [reason]
          (when (compare-and-set! terminated? false true)
            (-> (nrepl/cljs-eval-value conn build-id (unsubscribe-form sub-id))
                (.catch (fn [_] nil))
                (.then (fn [_]
                         (resolve
                           (final-summary
                             {:sub-id sub-id :topic topic
                              :state  @state :reason reason})))))))
        poll
        (fn poll []
          (cond
            (and signal (.-aborted signal))
            (terminate :aborted)

            (and (pos? max-events)
                 (>= (:delivered @state) max-events))
            (terminate :max-events-reached)

            :else
            (let [since-ms     (:since-ms @state)
                  filter-opts  (-> (topic->filter topic user-filter frame since-ms)
                                   (assoc :include-sensitive? incl?))]
              (-> (nrepl/cljs-eval-value conn build-id (drain-form filter-opts))
                  (.then
                    (fn [runtime-env]
                      (let [raw-events     (vec (project-error-topic
                                                  topic
                                                  (or (:events runtime-env) [])))
                            [kept dropped] (privacy/strip-sensitive raw-events incl?)
                            elided         (elision/count-elided-markers kept)
                            n              (count kept)
                            now-ms         (.now js/Date)]
                        ;; Always bump :since-ms to NOW so the next tick
                        ;; reads only fresh events — even a zero-event
                        ;; tick advances the cursor (otherwise we'd
                        ;; re-poll the same window forever).
                        (swap! state assoc :since-ms now-ms)
                        (when (pos? n)
                          (swap! state merge-tick
                                 {:n n :dropped dropped :elided elided})
                          (when (and send-note progress-tk)
                            (emit-progress-tick!
                              {:send-note send-note
                               :progress-tk progress-tk
                               :sub-id sub-id}
                              (:tick @state) kept dropped elided)))
                        (js/setTimeout poll poll-ms))))
                  (.catch
                    (fn [_err]
                      ;; nREPL hiccup — back off and try again rather
                      ;; than collapsing the stream.
                      (js/setTimeout poll (* 2 poll-ms))))))))]
    {:state state :terminate terminate :poll poll}))

;; ---------------------------------------------------------------------------
;; Tool handler
;; ---------------------------------------------------------------------------

(defn- run-acquired
  "Drive the subscription lifecycle. Registers the sub via the runtime,
  builds the controller, schedules max-ms watchdog, and starts the
  poll loop. Returns a Promise resolving to the final-summary MCP
  result."
  [{:keys [conn build-id topic user-filter frame poll-ms max-ms max-events
           incl? signal send-note progress-tk]}]
  (-> (probe/ensure-runtime! conn build-id)
      (.then (fn [_]
               (nrepl/cljs-eval-value conn build-id
                                      (subscribe-form topic user-filter))))
      (.then
        (fn [sub-env]
          (if-not (:ok? sub-env)
            (wire/ok-text sub-env)
            (let [sub-id (:sub-id sub-env)]
              (js/Promise.
                (fn [resolve _reject]
                  (let [{:keys [terminate poll]}
                        (make-stream-controller
                          {:conn conn :build-id build-id
                           :sub-id sub-id :topic topic
                           :resolve resolve
                           :state (atom (assoc initial-state
                                               :since-ms (.now js/Date)))
                           :signal signal :send-note send-note
                           :progress-tk progress-tk :poll-ms poll-ms
                           :max-events max-events :incl? incl?
                           :frame frame :user-filter user-filter})]
                    (when (pos? max-ms)
                      (js/setTimeout #(terminate :max-ms-reached) max-ms))
                    (poll))))))))
      (.catch (fn [err] (probe/err->result :subscribe-failed err)))))

(defn subscribe-tool [conn raw-args extra]
  (let [build-id    (wire/arg-build raw-args)
        topic       (wire/arg-keyword raw-args :topic)
        frame       (wire/arg-keyword raw-args :frame)
        user-filter (wire/arg raw-args :filter)
        poll-ms     (or (wire/arg-int raw-args :poll-ms) default-poll-ms)
        max-ms      (or (wire/arg-int raw-args :max-ms 0) 0)
        max-events  (or (wire/arg-int raw-args :max-events 0) 0)
        incl?       (privacy/parse-include-sensitive raw-args)
        {:keys [signal send-note progress-tk]} (parse-mcp-extra extra)]
    (cond
      (or (nil? topic) (not (contains? recognised-topics topic)))
      (js/Promise.resolve
        (wire/err-text {:ok?    false
                        :reason :unknown-topic
                        :given  (wire/arg raw-args :topic)
                        :hint   "Recognised topics: :trace :epoch :fx :error."}))

      :else
      (run-acquired
        {:conn conn :build-id build-id :topic topic
         :user-filter user-filter :frame frame
         :poll-ms poll-ms :max-ms max-ms :max-events max-events
         :incl? incl? :signal signal :send-note send-note
         :progress-tk progress-tk}))))

(def descriptor
  {:name        "subscribe"
   :description (str "Open a per-drain-batch streaming subscription on "
                     "topic :trace / :epoch / :fx / :error. The "
                     "tools/call stays open until the client aborts, "
                     ":max-events / :max-ms is reached, or unsubscribe "
                     "is called. Each non-empty polling tick emits one "
                     "notifications/progress notification carrying the "
                     "batch's events. The final tools/call result is a "
                     "summary with cumulative :delivered + :ticks + "
                     ":reason (terminated by). Sensitive events drop "
                     "by default; pass :include-sensitive? true to opt "
                     "in.")
   :input-schema #js {:type "object"
                      :required #js ["topic"]
                      :properties #js {:topic              #js {:type "string"}
                                       :filter             #js {:type "object"}
                                       :frame              #js {:type "string"}
                                       :poll-ms            #js {:type "integer"}
                                       :max-events         #js {:type "integer"}
                                       :max-ms             #js {:type "integer"}
                                       :include-sensitive? #js {:type "boolean"}
                                       :include-large?     #js {:type "boolean"}
                                       :max-tokens         #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) subscribe-tool descriptor)
