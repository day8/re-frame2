(ns re-frame2-pair-mcp.tools.unsubscribe
  "Tool: unsubscribe — close a streaming subscription."
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defn unsubscribe-tool [conn args]
  (let [build-id (wire/arg-build args)
        sub-id   (wire/arg args :sub-id)]
    (cond
      (or (nil? sub-id) (str/blank? sub-id))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :missing-sub-id
                        :hint "usage: unsubscribe {sub-id '<uuid>'}"}))

      :else
      (let [form (ef/emit (ef/rt-call 'unsubscribe! sub-id))]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v] (wire/ok-text (merge {:ok? true :sub-id sub-id}
                                                (when (map? v) v)))))
            (.catch (fn [err] (probe/err->result :unsubscribe-failed err))))))))
