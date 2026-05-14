(ns re-frame-pair2-mcp.tools.dispatch
  "Tool: dispatch — fire an event."
  (:require [clojure.string :as str]
            [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.eval-form :as ef]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.probe :as probe]))

(defn dispatch-tool [conn args]
  (let [event-str    (wire/arg args :event)
        build-id     (wire/arg-build args)
        sync?        (boolean (wire/arg args :sync))
        trace?       (boolean (wire/arg args :trace))
        frame        (wire/arg-keyword args :frame)
        fx-overrides (when-let [o (wire/arg args :fx-overrides)] (js->clj o :keywordize-keys true))]
    (cond
      (or (nil? event-str) (str/blank? event-str))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :missing-event
                        :hint "usage: dispatch {event '[:ev/id ...]' [sync true] [trace true] [frame :foo] [fx-overrides {...}]}"}))

      :else
      (let [opts-form (cond-> {}
                        frame        (assoc :frame frame)
                        fx-overrides (assoc :fx-overrides fx-overrides))
            ;; The agent passes `event-str` as raw CLJS source (e.g.
            ;; "[:my/event arg]"); wrap with `rt-raw` so `emit` inlines
            ;; it verbatim instead of `pr-str`'ing the outer string.
            event-form (ef/rt-raw event-str)
            fn-sym     (cond trace? 'dispatch-and-collect
                             sync?  'pair-dispatch-sync!
                             :else  'pair-dispatch!)
            form (ef/emit (ef/rt-call fn-sym event-form opts-form))
            mode (cond trace? :trace sync? :sync :else :queued)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v] (wire/ok-text (merge {:mode mode} (when (map? v) v)))))
            (.catch (fn [err] (probe/err->result :dispatch-failed err))))))))
