(ns re-frame-pair2-mcp.tools.eval-cljs
  "Tool: eval-cljs — evaluate one CLJS form."
  (:require [clojure.string :as str]
            [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.probe :as probe]))

(defn eval-cljs-tool [conn args]
  (let [form     (wire/arg args :form)
        build-id (wire/arg-build args)]
    (cond
      (or (nil? form) (str/blank? form))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :missing-form
                        :hint "usage: eval-cljs {form '<cljs-form>' [build :app]}"}))

      :else
      (-> (probe/ensure-runtime! conn build-id)
          (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
          (.then (fn [v] (wire/ok-text {:ok? true :value v})))
          (.catch (fn [err] (probe/err->result :eval-error err)))))))
