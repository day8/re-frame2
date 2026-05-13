(ns re-frame-pair2-mcp.tools.subscribe-emit
  "Per-tick progress-payload composition + final-summary envelope for
  the `subscribe` tool. Split out of `tools/subscribe.cljs` (rf2-vrbwx)
  so the streaming-loop body stays under the leaf-size ceiling."
  (:require [re-frame-pair2-mcp.tools.wire :as wire]))

(defn progress-payload
  "Build the JSON params payload for one `notifications/progress` tick.
  `events` is the EDN-printed string of the batch (kept as a string so
  the agent host sees the same shape as `tools/call` results). The
  `:data` slot carries the structured drop counts and the
  `:overflow-reason` keyword (per rf2-ho4ve) so AI clients can
  pattern-match on which budget tripped without re-parsing the EDN
  message."
  [progress-token tick events dropped-events dropped-bytes overflow-reason]
  #js {:progressToken progress-token
       :progress      tick
       ;; `message` is the human-readable slot. We stash an EDN form
       ;; here so an MCP client that surfaces progress messages to
       ;; the agent shows the events directly. A capable client can
       ;; additionally inspect the `data` slot for the structured
       ;; counts.
       :message       events
       :data          #js {:dropped-events  dropped-events
                           :dropped-bytes   dropped-bytes
                           ;; `overflow-reason` is an EDN keyword on
                           ;; the runtime side — stringify here so it
                           ;; rides JSON-RPC cleanly. The runtime
                           ;; sentinels are `:max-buffered-events` /
                           ;; `:max-buffered-bytes`.
                           :overflow-reason (when overflow-reason
                                              (pr-str overflow-reason))}})

(defn final-summary
  "The terminal `ok-text` result emitted when the subscription ends —
  client cancel, unsubscribe, max-events / max-ms hit, or sub-gone."
  [{:keys [sub-id topic delivered tick dropped-events* dropped-bytes*
           overflow-reason* dropped-sensitive* elided-large* reason]}]
  (wire/ok-text
    (wire/with-indicators
      (cond-> {:ok?            true
               :sub-id         sub-id
               :topic          topic
               :delivered      @delivered
               :dropped-events @dropped-events*
               :dropped-bytes  @dropped-bytes*
               :ticks          @tick
               :reason         reason}
        @overflow-reason*
        (assoc :overflow-reason @overflow-reason*))
      {:dropped @dropped-sensitive* :elided @elided-large*})))

(defn emit-progress-tick!
  "Build the per-tick progress payload and ship it via the MCP
  `sendNotification`. Failures are swallowed — a flaky client must not
  collapse the stream."
  [{:keys [send-note progress-tk sub-id]} dedup? tick-state]
  (let [{:keys [tick dedup-events ev-dropped by-dropped
                ov-reason dropped tick-elided]} tick-state]
    (try
      (send-note
        #js {:method "notifications/progress"
             :params (progress-payload
                       progress-tk
                       tick
                       (pr-str (wire/with-indicators
                                 (cond-> {:sub-id         sub-id
                                          :events         dedup-events
                                          :dedup          dedup?
                                          :dropped-events ev-dropped
                                          :dropped-bytes  by-dropped}
                                   ov-reason
                                   (assoc :overflow-reason ov-reason))
                                 {:dropped dropped :elided tick-elided}))
                       ev-dropped
                       by-dropped
                       ov-reason)})
      (catch :default _ nil))))
