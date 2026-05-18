(ns re-frame2-pair-mcp.tools.tail-build
  "Tool: tail-build — wait for hot-reload to land."
  (:require [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.wire :as wire]))

(def ^:private default-wait-ms
  "Default deadline for the probe to change after a hot-reload. Five
  seconds is generous for a typical shadow-cljs incremental rebuild;
  heavy ns reloads / first-time-load scenarios can override via the
  `:wait-ms` MCP arg."
  5000)

(def ^:private probe-poll-ms
  "Cadence at which we re-evaluate the probe form when waiting for
  its value to change. 100ms = ~10 probes/sec — fine-grained enough
  to land within ~100ms of the reload completing, cheap enough on
  the nREPL socket to not flood it."
  100)

(def ^:private no-probe-soft-delay-ms
  "When the caller passes no probe form, we resolve after a fixed
  soft delay — matches the bash-shim's behaviour. 300ms is the
  span empirical observation places shadow-cljs's bundle-swap cycle
  within after the source-file save event fires."
  300)

(defn tail-build-tool [conn args]
  (let [build-id (wire/arg-build args)
        wait-ms  (or (wire/arg args :wait-ms) default-wait-ms)
        probe    (wire/arg args :probe)
        poll-ms  probe-poll-ms]
    (cond
      (nil? probe)
      ;; Soft delay — matches the bash version's behaviour when no probe
      ;; is supplied. We just resolve after a short sleep.
      (js/Promise.
        (fn [resolve _]
          (js/setTimeout
            (fn []
              (resolve (wire/ok-text {:ok?   true
                                      :t     (js/Date.now)
                                      :soft? true
                                      :note  (str "No probe supplied; waited a "
                                                  no-probe-soft-delay-ms
                                                  "ms fixed delay.")})))
            no-probe-soft-delay-ms)))

      :else
      (let [start (js/Date.now)]
        (-> (nrepl/cljs-eval-value conn build-id probe)
            (.then
              (fn [before]
                (js/Promise.
                  (fn [resolve _]
                    (letfn [(poll []
                              (js/setTimeout
                                (fn []
                                  (let [elapsed (- (js/Date.now) start)]
                                    (if (>= elapsed wait-ms)
                                      (resolve
                                        (wire/ok-text {:ok? false :reason :timed-out :timed-out? true
                                                       :note "Probe did not change within wait-ms. Likely a compile error."}))
                                      (-> (nrepl/cljs-eval-value conn build-id probe)
                                          (.then
                                            (fn [now]
                                              (if (not= now before)
                                                (resolve (wire/ok-text {:ok? true :t (js/Date.now) :soft? false}))
                                                (poll))))
                                          (.catch (fn [_] (poll)))))))
                                poll-ms))]
                      (poll))))))
            (.catch
              (fn [err]
                (wire/ok-text {:ok? false :reason :probe-failed
                               :message (.-message err)}))))))))
