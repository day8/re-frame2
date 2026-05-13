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
  in `re-frame-pair2-mcp.tools.subscribe-emit` (rf2-vrbwx)."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.vocab :as base-vocab]
            [re-frame-pair2-mcp.nrepl :as nrepl]
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
        progress-tk        (some-> extra (j/get :_meta) (j/get :progressToken))
        send-note          (some-> extra (j/get :sendNotification))
        signal             (some-> extra (j/get :signal))]
    (cond
      (or (nil? topic)
          (not (#{:trace :epoch :fx :error} topic)))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :unknown-topic
                        :given (wire/arg raw-args :topic)
                        :hint  "Recognised topics: trace, epoch, fx, error."}))

      :else
      (let [subscribe-form
            (str "(re-frame-pair2.runtime/subscribe! "
                 (pr-str (cond-> {:topic               topic
                                  :max-buffered-events max-buf-events
                                  :max-buffered-bytes  max-buf-bytes}
                           filter-map (assoc :filter filter-map)))
                 ")")]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id subscribe-form)))
            (.then
              (fn [subscribe-resp]
                (if-not (:ok? subscribe-resp)
                  (wire/ok-text subscribe-resp)
                  (let [sub-id (:sub-id subscribe-resp)]
                    (js/Promise.
                      (fn [resolve _reject]
                        ;; Eight atoms wrap the rolling per-stream
                        ;; accounting. T13 (audit) flags the shape as a
                        ;; future map-atom refactor; deliberately
                        ;; preserved here so the rf2-vrbwx split is a
                        ;; pure rearrange.
                        (let [tick               (atom 0)
                              delivered          (atom 0)
                              dropped-events*    (atom 0)
                              dropped-bytes*     (atom 0)
                              overflow-reason*   (atom nil)
                              dropped-sensitive* (atom 0)
                              elided-large*      (atom 0)
                              terminate
                              (fn [reason]
                                (-> (nrepl/cljs-eval-value
                                      conn build-id
                                      (str "(re-frame-pair2.runtime/unsubscribe! "
                                           (pr-str sub-id) ")"))
                                    (.catch (fn [_] nil))
                                    (.then
                                      (fn [_]
                                        (resolve
                                          (emit/final-summary
                                            {:sub-id             sub-id
                                             :topic              topic
                                             :delivered          delivered
                                             :tick               tick
                                             :dropped-events*    dropped-events*
                                             :dropped-bytes*     dropped-bytes*
                                             :overflow-reason*   overflow-reason*
                                             :dropped-sensitive* dropped-sensitive*
                                             :elided-large*      elided-large*
                                             :reason             reason}))))))
                              poll
                              (fn poll []
                                (cond
                                  (and signal (.-aborted signal))
                                  (terminate :aborted)

                                  (and (pos? max-events)
                                       (>= @delivered max-events))
                                  (terminate :max-events-reached)

                                  :else
                                  (-> (nrepl/cljs-eval-value
                                        conn build-id
                                        (str "(re-frame-pair2.runtime/drain-subscription! "
                                             (pr-str sub-id) ")"))
                                      (.then
                                        (fn [drain-resp]
                                          (cond
                                            (:gone? drain-resp)
                                            (terminate :sub-gone)

                                            :else
                                            (let [raw-evts       (:events drain-resp)
                                                  ev-dropped     (:dropped-events  drain-resp 0)
                                                  by-dropped     (:dropped-bytes   drain-resp 0)
                                                  ov-reason      (:overflow-reason drain-resp)
                                                  [evts dropped] (sensitive/strip-sensitive
                                                                   (vec raw-evts) incl?)
                                                  tick-elided    (base-vocab/count-elided-markers evts)
                                                  n              (count evts)]
                                              (when (pos? ev-dropped)
                                                (swap! dropped-events* + ev-dropped))
                                              (when (pos? by-dropped)
                                                (swap! dropped-bytes* + by-dropped))
                                              (when ov-reason
                                                (reset! overflow-reason* ov-reason))
                                              (when (pos? dropped)
                                                (swap! dropped-sensitive* + dropped))
                                              (when (pos? tick-elided)
                                                (swap! elided-large* + tick-elided))
                                              (when (or (pos? n) (pos? ev-dropped))
                                                (swap! tick inc)
                                                (swap! delivered + n)
                                                (when (and send-note progress-tk)
                                                  (emit/emit-progress-tick!
                                                    {:send-note   send-note
                                                     :progress-tk progress-tk
                                                     :sub-id      sub-id}
                                                    dedup?
                                                    {:tick         @tick
                                                     :dedup-events (dedup/dedup-value evts dedup?)
                                                     :ev-dropped   ev-dropped
                                                     :by-dropped   by-dropped
                                                     :ov-reason    ov-reason
                                                     :dropped      dropped
                                                     :tick-elided  tick-elided})))
                                              (js/setTimeout poll poll-ms)))))
                                      (.catch
                                        (fn [_err]
                                          ;; nREPL hiccup — back off
                                          ;; and try again rather than
                                          ;; collapsing the stream.
                                          (js/setTimeout poll (* 2 poll-ms)))))))]
                          (when (pos? max-ms)
                            (js/setTimeout #(terminate :max-ms-reached) max-ms))
                          (poll))))))))
            (.catch (fn [err] (probe/err->result :subscribe-failed err))))))))
