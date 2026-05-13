(ns re-frame-pair2-mcp.tools.subscribe
  "Tool: subscribe — streaming trace + epoch channel (rf2-hq49).

  The MCP `tools/call` request runs until either:
    (a) the client aborts (cancellation arrives via the MCP `extra.signal`
        AbortSignal), or
    (b) an `unsubscribe` op clears the sub-id from the runtime.

  While running, each batch of newly-queued runtime events is shipped to
  the client as a `notifications/progress` notification correlated to the
  original tools/call via `extra._meta.progressToken`. The final
  `tools/call` result is a summary `{:ok? true :sub-id :delivered N
  :overflow N :reason <terminated-reason>}`.

  The runtime queue is bounded by a byte+event budget (rf2-ho4ve):
  default 500 events OR ~5 MB of pr-str bytes, whichever trips first.
  On overflow the OLDEST queued events are evicted (drop-oldest FIFO).
  The drain payload carries `:dropped-events`, `:dropped-bytes`, and
  `:overflow-reason` (`:max-buffered-events` / `:max-buffered-bytes`)
  so the AI client knows which budget to tune.

  Per-tick progress-payload composition + final-summary envelope live
  in `re-frame-pair2-mcp.tools.subscribe-emit` (rf2-vrbwx).

  ## Internal shape (rf2-w5etd)

  All rolling per-stream accounting lives in a single `state` atom
  holding a map `{:tick :delivered :dropped-events :dropped-bytes
  :overflow-reason :dropped-sensitive :elided-large}`. The poll loop
  applies one `swap!` per drain (merging the drain's contributions
  into the accumulators) rather than 5-7 separate ones. Termination,
  poll-step, and per-tick emission are factored into `make-stream-
  controller`, which closes over the atom and returns the `terminate`
  + `poll` fns — the streaming-loop body reads top-down."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.vocab :as base-vocab]
            [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.eval-form :as ef]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.probe :as probe]
            [re-frame-pair2-mcp.tools.args :as args]
            [re-frame-pair2-mcp.tools.dedup :as dedup]
            [re-frame-pair2-mcp.tools.sensitive :as sensitive]
            [re-frame-pair2-mcp.tools.subscribe-emit :as emit]))

(def ^:private default-poll-ms 100)
(def ^:private default-max-buffered-events 500)
(def ^:private default-max-buffered-bytes
  ;; ~5 MB — see runtime.cljs's identical default for the rationale.
  ;; Mirrored here so the MCP server can fill in the slot before the
  ;; nREPL call if the caller omits it; the runtime still applies the
  ;; same default if the slot is `nil`.
  5000000)

(def ^:private initial-state
  "The rolling per-stream accounting map (rf2-w5etd). Held inside one
  atom for the lifetime of a `subscribe-tool` call; merged once per
  drain. Indicator slots (`:dropped-sensitive`, `:elided-large`) feed
  `wire/with-indicators` at terminal-summary emit time."
  {:tick              0
   :delivered         0
   :dropped-events    0
   :dropped-bytes     0
   :overflow-reason   nil
   :dropped-sensitive 0
   :elided-large      0})

(defn- merge-drain
  "Pure state update — fold one drain's contributions into the rolling
  accumulators. The drain reports `:ev-dropped`/`:by-dropped` for
  queue-overflow eviction (rf2-ho4ve), `:dropped` for sensitive-strip,
  and `:tick-elided` for the count of `:rf.size/large-elided` markers
  on this batch. `:n` is the kept-event count after sensitive-strip.

  A tick is counted whenever the drain produced kept events OR the
  drain reported queue-overflow drops — both shapes should surface to
  the client as `notifications/progress`."
  [s {:keys [n ev-dropped by-dropped ov-reason dropped tick-elided]}]
  (let [tick? (or (pos? n) (pos? ev-dropped))]
    (cond-> s
      tick?            (-> (update :tick      inc)
                           (update :delivered + n))
      (pos? ev-dropped)  (update :dropped-events    + ev-dropped)
      (pos? by-dropped)  (update :dropped-bytes     + by-dropped)
      ov-reason          (assoc  :overflow-reason   ov-reason)
      (pos? dropped)     (update :dropped-sensitive + dropped)
      (pos? tick-elided) (update :elided-large      + tick-elided))))

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
  "Build the `terminate` + `poll` fns over a shared `state` atom and
  the per-call context. Returns `{:state :terminate :poll}` so the
  caller's body reads top-down — controller built, then `(poll)`
  invoked.

  Both fns close over the same atom. `terminate` issues the
  runtime-side `unsubscribe!`, then `resolve`s the outer `tools/call`
  Promise with the final-summary envelope. `poll` runs the drain →
  state-merge → progress-emit → reschedule cycle until termination
  triggers (client abort, max-events reached, or sub-gone)."
  [{:keys [conn build-id sub-id topic resolve state
           signal send-note progress-tk poll-ms max-events
           incl? dedup?]}]
  (let [terminate
        (fn terminate [reason]
          (-> (nrepl/cljs-eval-value
                conn build-id
                (ef/emit (ef/rt-call 'unsubscribe! sub-id)))
              (.catch (fn [_] nil))
              (.then
                (fn [_]
                  (resolve
                    (emit/final-summary
                      {:sub-id sub-id :topic topic
                       :state  @state :reason reason}))))))
        poll
        (fn poll []
          (cond
            (and signal (.-aborted signal))
            (terminate :aborted)

            (and (pos? max-events)
                 (>= (:delivered @state) max-events))
            (terminate :max-events-reached)

            :else
            (-> (nrepl/cljs-eval-value
                  conn build-id
                  (ef/emit (ef/rt-call 'drain-subscription! sub-id)))
                (.then
                  (fn [drain-resp]
                    (if (:gone? drain-resp)
                      (terminate :sub-gone)
                      (let [raw-evts       (:events drain-resp)
                            ev-dropped     (:dropped-events  drain-resp 0)
                            by-dropped     (:dropped-bytes   drain-resp 0)
                            ov-reason      (:overflow-reason drain-resp)
                            [evts dropped] (sensitive/strip-sensitive
                                             (vec raw-evts) incl?)
                            tick-elided    (base-vocab/count-elided-markers evts)
                            n              (count evts)
                            drain-delta    {:n           n
                                            :ev-dropped  ev-dropped
                                            :by-dropped  by-dropped
                                            :ov-reason   ov-reason
                                            :dropped     dropped
                                            :tick-elided tick-elided}
                            s'             (swap! state merge-drain drain-delta)]
                        (when (or (pos? n) (pos? ev-dropped))
                          (when (and send-note progress-tk)
                            (emit/emit-progress-tick!
                              {:send-note   send-note
                               :progress-tk progress-tk
                               :sub-id      sub-id}
                              dedup?
                              {:tick         (:tick s')
                               :dedup-events (dedup/dedup-value evts dedup?)
                               :ev-dropped   ev-dropped
                               :by-dropped   by-dropped
                               :ov-reason    ov-reason
                               :dropped      dropped
                               :tick-elided  tick-elided})))
                        (js/setTimeout poll poll-ms)))))
                (.catch
                  (fn [_err]
                    ;; nREPL hiccup — back off and try again rather
                    ;; than collapsing the stream.
                    (js/setTimeout poll (* 2 poll-ms)))))))]
    {:state state :terminate terminate :poll poll}))

(defn subscribe-tool [conn raw-args extra]
  (let [build-id           (wire/arg-build raw-args)
        topic              (some-> (wire/arg raw-args :topic) keyword)
        filter-map         (args/parse-filter-arg (wire/arg raw-args :filter))
        max-buf-events     (or (wire/arg raw-args :max-buffered-events)
                               default-max-buffered-events)
        max-buf-bytes      (or (wire/arg raw-args :max-buffered-bytes)
                               default-max-buffered-bytes)
        poll-ms            (or (wire/arg raw-args :poll-ms) default-poll-ms)
        max-ms             (or (wire/arg raw-args :max-ms) 0)
        max-events         (or (wire/arg raw-args :max-events) 0)
        incl?              (sensitive/include-sensitive? raw-args)
        dedup?             (dedup/parse-dedup-arg (wire/arg raw-args :dedup))
        {:keys [signal send-note progress-tk]} (parse-mcp-extra extra)]
    (cond
      (or (nil? topic)
          (not (#{:trace :epoch :fx :error} topic)))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :unknown-topic
                        :given (wire/arg raw-args :topic)
                        :hint  "Recognised topics: trace, epoch, fx, error."}))

      :else
      (let [subscribe-form
            (ef/emit
              (ef/rt-call 'subscribe!
                          (cond-> {:topic               topic
                                   :max-buffered-events max-buf-events
                                   :max-buffered-bytes  max-buf-bytes}
                            filter-map (assoc :filter filter-map))))]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id subscribe-form)))
            (.then
              (fn [subscribe-resp]
                (if-not (:ok? subscribe-resp)
                  (wire/ok-text subscribe-resp)
                  (let [sub-id (:sub-id subscribe-resp)]
                    (js/Promise.
                      (fn [resolve _reject]
                        (let [{:keys [terminate poll]}
                              (make-stream-controller
                                {:conn        conn        :build-id    build-id
                                 :sub-id      sub-id      :topic       topic
                                 :resolve     resolve     :state       (atom initial-state)
                                 :signal      signal      :send-note   send-note
                                 :progress-tk progress-tk :poll-ms     poll-ms
                                 :max-events  max-events  :incl?       incl?
                                 :dedup?      dedup?})]
                          (when (pos? max-ms)
                            (js/setTimeout #(terminate :max-ms-reached) max-ms))
                          (poll))))))))
            (.catch (fn [err] (probe/err->result :subscribe-failed err))))))))
