(ns re-frame-pair2-mcp.tools.tail-build
  "Tool: tail-build — wait for hot-reload to land."
  (:require [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.wire :as wire]))

(defn tail-build-tool [conn args]
  (let [build-id (wire/arg-build args)
        wait-ms  (or (wire/arg args :wait-ms) 5000)
        probe    (wire/arg args :probe)
        poll-ms  100]
    (cond
      (nil? probe)
      ;; Soft delay — matches the bash version's behaviour when no probe
      ;; is supplied. We just resolve after a short sleep.
      (js/Promise.
        (fn [resolve _]
          (js/setTimeout
            (fn []
              (resolve (wire/ok-text {:ok? true :t (js/Date.now) :soft? true
                                      :note "No probe supplied; waited a 300ms fixed delay."})))
            300)))

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
