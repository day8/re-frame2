(ns re-frame-pair2-mcp.tools.discover-app
  "Tool: discover-app — verify the stack and probe the preloaded runtime."
  (:require [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.probe :as probe]))

(defn discover-app [conn args]
  (let [build-id (wire/arg-build args)]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (probe/runtime-health! conn build-id)))
        (.then
          (fn [health]
            (cond
              (not (:ok? health))
              (wire/ok-text health)

              (not (:debug-enabled? health))
              (wire/ok-text {:ok? false :reason :debug-disabled
                             :hint (str "re-frame.interop/debug-enabled? is false. "
                                        "This is a production build (or goog.DEBUG was "
                                        "forced off). Trace and epoch surfaces are elided.")})

              (empty? (:frames health))
              (wire/ok-text {:ok? false :reason :no-frames-registered
                             :hint "Call (rf/init!) to register :rf/default, or wait for app boot."})

              (:ambiguous-frame? health)
              (wire/ok-text (assoc health :ok? true
                                          :warning :ambiguous-frame
                                          :note (str "Multiple frames registered: "
                                                     (vec (:frames health))
                                                     ". Mutating ops require --frame :foo "
                                                     "or run `frames/select` first.")))

              (not (:coord-annotation-enabled? health))
              (wire/ok-text (assoc health :ok? true
                                          :warning :no-source-coord-annotation
                                          :note (str "Neither data-rf2-source-coord nor "
                                                     "data-rc-src is on any element. "
                                                     "DOM->source ops will degrade. Enable "
                                                     "(rf/configure :source-coords {:annotate-dom? true}) "
                                                     "or use re-com with :src (at).")))

              :else
              (wire/ok-text (assoc health :ok? true :build-id build-id)))))
        (.catch (fn [err] (probe/err->result :discover-failed err))))))
