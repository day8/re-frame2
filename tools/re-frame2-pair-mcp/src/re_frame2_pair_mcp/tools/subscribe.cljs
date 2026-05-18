(ns re-frame2-pair-mcp.tools.subscribe
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

  ## Internal shape (rf2-w5etd)

  All rolling per-stream accounting lives in a single `state` atom
  holding a map `{:tick :delivered :dropped-events :dropped-bytes
  :overflow-reason :dropped-sensitive :elided-large}`. The poll loop
  applies one `swap!` per drain (merging the drain's contributions
  into the accumulators) rather than 5-7 separate ones. Termination,
  poll-step, and per-tick emission are factored into `make-stream-
  controller`, which closes over the atom and returns the `terminate`
  + `poll` fns — the streaming-loop body reads top-down.

  ## Per-tick + final-summary emit (rf2-zkca8.3)

  `progress-payload`, `emit-progress-tick!`, and `final-summary` live
  in this same namespace. They were briefly split out as
  `tools/subscribe-emit.cljs` to keep this body under the leaf-size
  ceiling; the audit (rf2-zkca8.3) recognised the split was an
  artificial seam — a single-consumer helper coupled to this loop's
  state shape, with no independent tests and no reuse surface. Folded
  back so the streaming-loop body reads top-down without bouncing
  between two files when the state shape changes."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.elision :as base-elision]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.dedup :as dedup]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.sensitive :as sensitive]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.resource-controls :as resource]))

(def ^:private default-poll-ms 100)
;; `:max-buffered-events` / `:max-buffered-bytes` are NOT mirrored here
;; (rf2-ambfv): the runtime applies its own defaults when the slot is
;; nil, and mirroring forces a two-file sync on every runtime tweak
;; (e.g. rf2-ho4ve raising the byte cap). Pass nil → runtime defaults.

(def ^:private initial-state
  "The rolling per-stream accounting map (rf2-w5etd). Held inside one
  atom for the lifetime of a `subscribe-tool` call; merged once per
  drain. Indicator slots (`:dropped-sensitive`, `:elided-large`) feed
  `wire/with-indicators` at terminal-summary emit time.

  rf2-3ijbl extends this with `:rate-dropped` — the count of ticks
  the per-session rate-limit (resource-controls token bucket) silenced
  to keep the wire under the operator-configured events/sec cap. The
  count surfaces on the final summary so the operator can see whether
  the cap was tripped (a signal to raise `--max-events-per-sec` or
  to look at why a consumer is sending so much)."
  ;; :elided-large counts upstream-pre-elided markers per
  ;; Spec 009 §Indicator field (rf2-8cntr) — cumulative across drains.
  {:tick              0
   :delivered         0
   :dropped-events    0
   :dropped-bytes     0
   :overflow-reason   nil
   :dropped-sensitive 0
   :elided-large      0
   :rate-dropped      0})

(defn drain-produced-output?
  "Did this drain produce a tick the client should see? True iff the
  drain delivered kept events OR reported queue-overflow drops — the
  two shapes that surface as a `notifications/progress` tick.

  Lifted to a named predicate (rather than inlining the OR at the
  two call sites — `merge-drain`'s `tick?` gate and the poll loop's
  emit gate) so the contract is single-sourced: any future change
  to what counts as a tick (e.g. surfacing tick-elided-only drains)
  lands here once, not at two sites that could drift."
  [{:keys [n ev-dropped]}]
  (or (pos? (or n 0)) (pos? (or ev-dropped 0))))

(defn merge-drain
  "Pure state update — fold one drain's contributions into the rolling
  accumulators. The drain reports `:ev-dropped`/`:by-dropped` for
  queue-overflow eviction (rf2-ho4ve), `:dropped` for sensitive-strip,
  and `:tick-elided` for the count of `:rf.size/large-elided` markers
  on this batch. `:n` is the kept-event count after sensitive-strip.

  A tick is counted whenever `drain-produced-output?` returns true —
  the predicate single-sources the gate.

  Public (not `defn-`) so unit tests in `subscribe_test.cljs` can
  pin the state-merge contract directly; the runtime contract surface
  is otherwise nrepl-only."
  [s {:keys [n ev-dropped by-dropped ov-reason dropped tick-elided] :as drain}]
  (cond-> s
    (drain-produced-output? drain)
                       (-> (update :tick      inc)
                           (update :delivered + n))
    (pos? ev-dropped)  (update :dropped-events    + ev-dropped)
    (pos? by-dropped)  (update :dropped-bytes     + by-dropped)
    ov-reason          (assoc  :overflow-reason   ov-reason)
    (pos? dropped)     (update :dropped-sensitive + dropped)
    (pos? tick-elided) (update :elided-large      + tick-elided)))

;; ---------------------------------------------------------------------------
;; Per-tick progress payload + final-summary emit (folded back from
;; `tools/subscribe-emit.cljs` per rf2-zkca8.3).
;; ---------------------------------------------------------------------------

(defn progress-payload
  "Build the JSON params payload for one `notifications/progress` tick.
  `events` is the EDN-printed string of the batch (kept as a string so
  the agent host sees the same shape as `tools/call` results). The
  `_meta.data` carries the structured drop counts and the
  `:overflow-reason` keyword (per rf2-ho4ve) so AI clients can
  pattern-match on which budget tripped without re-parsing the EDN
  message. The official MCP SDK strips unknown top-level progress
  params, but preserves `_meta`."
  [progress-token tick events dropped-events dropped-bytes overflow-reason]
  #js {:progressToken progress-token
       :progress      tick
       ;; `message` is the human-readable slot. We stash an EDN form
       ;; here so an MCP client that surfaces progress messages to
       ;; the agent shows the events directly. A capable client can
       ;; additionally inspect `_meta.data` for the structured
       ;; counts.
       :message       events
       :_meta         #js {:data #js {:dropped-events  dropped-events
                                      :dropped-bytes   dropped-bytes
                                      ;; `overflow-reason` is an EDN keyword on
                                      ;; the runtime side — stringify here so it
                                      ;; rides JSON-RPC cleanly. The runtime
                                      ;; sentinels are `:max-buffered-events` /
                                      ;; `:max-buffered-bytes`.
                                      :overflow-reason (when overflow-reason
                                                         (pr-str overflow-reason))}}})

(defn final-summary
  "The terminal `ok-text` result emitted when the subscription ends —
  client cancel, unsubscribe, max-events / max-ms hit, sub-gone, or
  abuse-detected (rf2-3ijbl).

  `state` is the deref'd rolling accumulators map (see
  `initial-state`); `wire/with-indicators` splices the
  `:dropped-sensitive` / `:elided-large` counters onto the envelope
  per the cross-MCP indicator-field convention. `:rate-dropped`
  surfaces only when non-zero — same suppress-when-zero discipline
  as the indicator-field MUSTs."
  [{:keys [sub-id topic state reason]}]
  (let [{:keys [tick delivered dropped-events dropped-bytes
                overflow-reason dropped-sensitive elided-large
                rate-dropped]} state]
    (wire/ok-text
      (wire/with-indicators
        (cond-> {:ok?            true
                 :sub-id         sub-id
                 :topic          topic
                 :delivered      delivered
                 :dropped-events dropped-events
                 :dropped-bytes  dropped-bytes
                 :ticks          tick
                 :reason         reason}
          overflow-reason
          (assoc :overflow-reason overflow-reason)
          (pos? (or rate-dropped 0))
          (assoc :rate-dropped rate-dropped))
        {:dropped dropped-sensitive :elided elided-large}))))

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

;; ---------------------------------------------------------------------------
;; Drain eval-form — server-side elision wrap (rf2-vr2hn).
;; ---------------------------------------------------------------------------
;;
;; `drain-subscription!` returns `{:ok? :sub-id :events [...]
;; :dropped-events :dropped-bytes :overflow-reason :gone?}`. The `:epoch`
;; topic ships full epoch records — `:db-after` / `:db-before` / `:app-db`
;; slices ride verbatim. When the `--allow-raw-state` boot gate is OFF
;; (the published-build default), each event must flow through
;; `re-frame.core/elide-wire-value` BEFORE it crosses the nREPL wire —
;; mirroring the snapshot / get-path gate (rf2-c2dtu) so an operator who
;; didn't pass `--allow-raw-state` can't be talked into shipping raw
;; state through a hostile per-call arg.
;;
;; The walker reads the live `[:rf/elision]` registry, so it has to run
;; app-side. We compose `drain-subscription!` server-side with a mapv
;; over `:events`, returning the same envelope with elided values in
;; place. When elision is OFF (operator opted in via `--allow-raw-state`
;; AND passed `:elision false`), the bare drain form ships raw — the
;; pre-rf2-vr2hn posture.

(defn drain-form
  "Build the nREPL drain eval form. When `elision?` is true, wraps the
  drain envelope so `:events` flows through `re-frame.core/elide-wire-value`
  server-side; when false, emits the bare drain call. `incl?` threads
  into the walker's `:rf.size/include-sensitive?` opt — gate-OFF callers
  see redacted sensitive slots regardless of any per-call opt-in.

  Public (not `defn-`) so unit tests can pin the form shape directly —
  the form-string is the contract surface between MCP server and the
  app-side runtime."
  [sub-id elision? incl?]
  (if elision?
    (ef/emit
      (ef/rt-let
        ['drain (ef/rt-call 'drain-subscription! sub-id)
         'opts  (ef/rt-raw (elision/elision-opts-edn true incl?))
         'evts  (ef/rt-raw
                  (str "(mapv #(re-frame.core/elide-wire-value % opts)"
                       " (:events drain))"))]
        (ef/rt-raw "(assoc drain :events evts)")))
    (ef/emit (ef/rt-call 'drain-subscription! sub-id))))

;; ---------------------------------------------------------------------------
;; Streaming controller — termination + poll loop.
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
  "Build the `terminate` + `poll` fns over a shared `state` atom and
  the per-call context. Returns `{:state :terminate :poll}` so the
  caller's body reads top-down — controller built, then `(poll)`
  invoked.

  Both fns close over the same atom. `terminate` issues the
  runtime-side `unsubscribe!`, releases the resource-controls
  stream slot (rf2-3ijbl — must run on EVERY exit path so the
  concurrent-stream counter doesn't leak), then `resolve`s the outer
  `tools/call` Promise with the final-summary envelope. `poll` runs
  the drain → state-merge → progress-emit → reschedule cycle until
  termination triggers (client abort, max-events reached, sub-gone,
  or abuse-detected)."
  [{:keys [conn build-id sub-id topic resolve state
           signal send-note progress-tk poll-ms max-events
           incl? elision? dedup?]}]
  (let [drain-src    (drain-form sub-id elision? incl?)
        terminated?  (atom false)
        terminate
        (fn terminate [reason]
          ;; Idempotent: a double-fire (e.g. abuse-detected fires the
          ;; same tick the max-events cap was reached) would otherwise
          ;; release the resource slot twice and double-resolve the
          ;; outer Promise. The atom guards the first-wins path.
          (when (compare-and-set! terminated? false true)
            (resource/release-stream!)
            (-> (nrepl/cljs-eval-value
                  conn build-id
                  (ef/emit (ef/rt-call 'unsubscribe! sub-id)))
                (.catch (fn [_] nil))
                (.then
                  (fn [_]
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
            (-> (nrepl/cljs-eval-value conn build-id drain-src)
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
                            ;; :elided-large counts upstream-pre-elided markers per
                            ;; Spec 009 §Indicator field (rf2-8cntr) — per-tick contribution.
                            tick-elided    (base-elision/count-elided-markers evts)
                            n              (count evts)
                            drain-delta    {:n           n
                                            :ev-dropped  ev-dropped
                                            :by-dropped  by-dropped
                                            :ov-reason   ov-reason
                                            :dropped     dropped
                                            :tick-elided tick-elided}
                            tick?          (drain-produced-output? drain-delta)
                            ;; rf2-3ijbl — per-session rate-limit gate. The
                            ;; token bucket holds at most `max-events-per-sec`
                            ;; tokens; one tick (= one progress notification)
                            ;; consumes one token. When the bucket is empty
                            ;; the tick is dropped silently and counted as
                            ;; `:rate-dropped` on the final summary.
                            allow?         (or (not tick?) (resource/check-rate!))]
                        (if allow?
                          (let [s' (swap! state merge-drain drain-delta)]
                            (when (and tick? send-note progress-tk)
                              (emit-progress-tick!
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
                          ;; Rate-dropped — tally and skip the emit. The
                          ;; runtime-side queue still holds the events;
                          ;; subsequent ticks drain them when tokens
                          ;; refill.
                          (swap! state update :rate-dropped inc))
                        ;; rf2-3ijbl — abuse-detection: any drain that
                        ;; reported a queue overflow contributes to the
                        ;; session's rolling window. Sustained overflow
                        ;; (the consumer can't keep up) terminates the
                        ;; stream rather than churning forever.
                        (if (and ov-reason
                                 (= :abuse-detected (resource/record-overflow!)))
                          (do (js/console.error
                                (str "[re-frame2-pair-mcp] stream abuse detected "
                                     "(sub-id=" sub-id " topic=" topic
                                     ") — sustained overflow exceeded threshold; "
                                     "terminating."))
                              (terminate :rf.error/stream-abuse-detected))
                          (js/setTimeout poll poll-ms))))))
                (.catch
                  (fn [_err]
                    ;; nREPL hiccup — back off and try again rather
                    ;; than collapsing the stream.
                    (js/setTimeout poll (* 2 poll-ms)))))))]
    {:state state :terminate terminate :poll poll}))

(defn- run-acquired
  "Drive the subscription lifecycle once the resource-controls
  stream slot is reserved (rf2-3ijbl). Returns a Promise resolving to
  the MCP tool result; on any pre-controller exit path the slot is
  released here — the stream-controller's `terminate` only fires
  AFTER `make-stream-controller` wires up, so failures before that
  point need explicit release."
  [{:keys [conn raw-args topic build-id filter-map max-buf-events
           max-buf-bytes poll-ms max-ms max-events
           incl? elision? dedup? signal send-note progress-tk]}]
  (let [subscribe-form
        (ef/emit
          (ef/rt-call 'subscribe!
                      ;; Only inline slots the caller actually
                      ;; supplied — the runtime applies its own
                      ;; defaults for absent budget knobs (rf2-ambfv).
                      (cond-> {:topic topic}
                        max-buf-events (assoc :max-buffered-events max-buf-events)
                        max-buf-bytes  (assoc :max-buffered-bytes  max-buf-bytes)
                        filter-map     (assoc :filter              filter-map))))]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (raw-state/signal-runtime! conn build-id)))
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id subscribe-form)))
        (.then
          (fn [subscribe-resp]
            (if-not (:ok? subscribe-resp)
              (do (resource/release-stream!)
                  (wire/ok-text subscribe-resp))
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
                             :elision?    elision?    :dedup?      dedup?})]
                      (when (pos? max-ms)
                        (js/setTimeout #(terminate :max-ms-reached) max-ms))
                      (poll))))))))
        (.catch (fn [err]
                  ;; Probe / signal-runtime / subscribe-eval failure —
                  ;; controller never wired, so terminate's release
                  ;; never fires. Release here.
                  (resource/release-stream!)
                  (probe/err->result :subscribe-failed err))))))

(defn subscribe-tool [conn raw-args extra]
  (let [build-id           (wire/arg-build raw-args)
        topic              (wire/arg-keyword raw-args :topic)
        filter-map         (args/parse-filter-arg (wire/arg raw-args :filter))
        max-buf-events     (wire/arg raw-args :max-buffered-events)
        max-buf-bytes      (wire/arg raw-args :max-buffered-bytes)
        poll-ms            (or (wire/arg raw-args :poll-ms) default-poll-ms)
        max-ms             (or (wire/arg raw-args :max-ms) 0)
        max-events         (or (wire/arg raw-args :max-events) 0)
        ;; rf2-c2dtu — the `--allow-raw-state` boot gate forces
        ;; `:include-sensitive false` on every streamed event when OFF
        ;; (the published-build default). `sensitive/strip-sensitive`
        ;; below honours the post-gate value, so a caller's
        ;; `:include-sensitive true` arg is dropped before reaching the
        ;; runtime drain.
        incl?              (if (raw-state/force-redact?)
                             false
                             (args/parse-bool-arg raw-args :include-sensitive))
        ;; rf2-vr2hn — the `--allow-raw-state` boot gate forces
        ;; `:elision true` on every streamed event when OFF, mirroring
        ;; the snapshot / get-path gate. Server-side, the drain envelope's
        ;; `:events` flow through `re-frame.core/elide-wire-value` before
        ;; crossing the nREPL wire — declared-large slots elide and
        ;; declared-sensitive slots redact. `force-elision?` is the
        ;; single arbiter; a caller's `:elision false` arg is dropped
        ;; when the gate is OFF.
        elision?           (or (args/parse-bool-arg raw-args :elision)
                               (raw-state/force-elision?))
        dedup?             (args/parse-bool-arg raw-args :dedup)
        {:keys [signal send-note progress-tk]} (parse-mcp-extra extra)]
    (cond
      (or (nil? topic)
          (not (#{:trace :epoch :fx :error} topic)))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :unknown-topic
                        :given (wire/arg raw-args :topic)
                        :hint  "Recognised topics: trace, epoch, fx, error."}))

      :else
      ;; rf2-3ijbl — reserve a session-wide stream slot BEFORE any
      ;; runtime allocation. A rejection at this gate returns an
      ;; isError result without touching the nREPL socket — the
      ;; client must close an existing subscription first.
      (let [acquire (resource/acquire-stream!)]
        (if-not (:ok? acquire)
          (js/Promise.resolve (wire/err-text acquire))
          (run-acquired
            {:conn conn :raw-args raw-args :topic topic :build-id build-id
             :filter-map filter-map
             :max-buf-events max-buf-events :max-buf-bytes max-buf-bytes
             :poll-ms poll-ms :max-ms max-ms :max-events max-events
             :incl? incl? :elision? elision? :dedup? dedup?
             :signal signal :send-note send-note :progress-tk progress-tk}))))))
