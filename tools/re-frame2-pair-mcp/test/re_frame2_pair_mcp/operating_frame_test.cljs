(ns re-frame2-pair-mcp.operating-frame-test
  "`get-operating-frame` and `reset-operating-frame` route a blank / non-map
  runtime answer to an isError envelope, never `ok-text`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.operating-frame :as op-frame]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(deftest blank-runtime-result-is-isError
  (async done
    (let [orig nrepl/cljs-eval-value
          stub (fn
                 ([_c _b _form] (js/Promise.resolve nil))
                 ([_c _b _form _o] (js/Promise.resolve nil)))]
      (set! nrepl/cljs-eval-value stub)
      (-> (js/Promise.all
            #js [(op-frame/get-operating-frame-tool (fresh-conn) (tu/args->js {}))
                 (op-frame/reset-operating-frame-tool (fresh-conn) (tu/args->js {}))])
          (.then (fn [results]
                   (doseq [r results]
                     (is (tu/error? r))
                     (is (= {:ok? false :reason :unexpected-shape :value nil}
                            (tu/extract-edn r))))))
          (.catch (fn [e] (is false (str "rejected: " e))))
          (.finally (fn []
                      (tu/restore-eval! stub orig)
                      (done)))))))
