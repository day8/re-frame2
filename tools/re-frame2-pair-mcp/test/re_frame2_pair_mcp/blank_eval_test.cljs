(ns re-frame2-pair-mcp.blank-eval-test
  "An eval no runtime answered comes back blank (nil). Its outcome is
  unknown, so a tool must say so rather than read the nil as an answer: an
  empty registry, or a write the runtime refused, when the write may have
  landed."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.handler-meta :as handler-meta]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.replay-epoch :as replay-epoch]
            [re-frame2-pair-mcp.tools.restore-epoch :as restore-epoch]
            [re-frame2-pair-mcp.tools.writes :as writes]))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:before (fn [] (writes/set-allow-writes! true) (raw-state/reset-runtime-signal-cache!))
   :after  (fn []
             (set! nrepl/cljs-eval-value pristine-eval)
             (writes/set-allow-writes! false)
             (raw-state/reset-runtime-signal-cache!))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- blank-runtime! []
  (set! nrepl/cljs-eval-value
        (fn ([_ _ _] (js/Promise.resolve nil)) ([_ _ _ _] (js/Promise.resolve nil)))))

(deftest a-blank-eval-is-an-unknown-outcome
  (async done
    (blank-runtime!)
    (let [blank? (fn [r] (and (tu/error? r) (= :blank-eval-result (:reason (tu/extract-edn r)))))]
      (-> (handler-meta/list-handlers-tool (fresh-conn) (tu/args->js {:kind "event"}))
          (.then (fn [r]
                   (is (blank? r) "list-handlers: not an empty registry")
                   (restore-epoch/restore-epoch-tool (fresh-conn) (tu/args->js {:epoch-id "7"}))))
          (.then (fn [r]
                   (is (blank? r) "restore-epoch: not a refused restore")
                   (replay-epoch/replay-epoch-tool (fresh-conn) (tu/args->js {:epoch-id "7"}))))
          (.then (fn [r] (is (blank? r) "replay-epoch: not a stale preload")))
          (.catch (fn [e] (is false (str "rejected: " (.-message e)))))
          (.then (fn [_] (done)))))))
