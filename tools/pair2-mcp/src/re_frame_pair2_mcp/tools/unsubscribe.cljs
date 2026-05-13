(ns re-frame-pair2-mcp.tools.unsubscribe
  "Tool: unsubscribe — close a streaming subscription."
  (:require [clojure.string :as str]
            [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.probe :as probe]))

(defn unsubscribe-tool [conn args]
  (let [build-id (wire/arg-build args)
        sub-id   (wire/arg args :sub-id)]
    (cond
      (or (nil? sub-id) (str/blank? sub-id))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :missing-sub-id
                        :hint "usage: unsubscribe {sub-id '<uuid>'}"}))

      :else
      (let [form (str "(re-frame-pair2.runtime/unsubscribe! "
                      (pr-str sub-id) ")")]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v] (wire/ok-text (merge {:ok? true :sub-id sub-id}
                                                (when (map? v) v)))))
            (.catch (fn [err] (probe/err->result :unsubscribe-failed err))))))))
