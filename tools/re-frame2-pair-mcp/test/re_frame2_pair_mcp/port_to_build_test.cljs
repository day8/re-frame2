(ns re-frame2-pair-mcp.port-to-build-test
  "URL port to build resolution via the shadow-cljs `:dev-http` map.

  An operator who knows only the browser URL passes its port:
  `probe/resolve-build-by-port` reads `:dev-http` JVM-side and returns the
  build whose `:output-dir` that port serves, and discover-app probes it.
  An explicit `:build` arg wins. discover-app's `:port` paths are pinned by
  build-id-cache-test's `port-discover-no-pre-probe-sticks-through-invoke`
  and the corpus row `:discover-app/port-unresolved`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- fresh-conn []
  (nrepl/make-conn 0 "127.0.0.1"))

(def ^:private healthy-health
  {:ok?                        true
   :debug-enabled?             true
   :coord-annotation-enabled?  true
   :frames                     [:rf/default]
   :ambiguous-frame?           false})

(deftest resolve-build-by-port-reads-the-jvm-result
  ;; The MCP wire may deliver the port as a string; it reaches the JVM form
  ;; as the integer, and the keyword the form returns is read back.
  (async done
    (let [conn   (fresh-conn)
          orig   nrepl/jvm-eval
          answer (fn [form] (js/Promise.resolve
                              {:value (if (re-find #"\b8031\b" form) ":examples/step-deck" "nil")}))
          stub   (fn
                   ([_c form] (answer form))
                   ([_c form _o] (answer form)))]
      (set! nrepl/jvm-eval stub)
      (-> (probe/resolve-build-by-port conn "8031")
          (.then (fn [bid]
                   (is (= :examples/step-deck bid))))
          (.finally (fn [] (tu/restore-jvm-eval! stub orig)))
          (.then (fn [_] (done)))))))

(deftest discover-app-explicit-build-wins-over-port
  ;; With both given, `:build` wins and the resolver is never consulted.
  (async done
    (let [conn (fresh-conn)
          orig probe/resolve-build-by-port]
      (swap! conn update :probed-builds conj :my-app)
      (set! probe/resolve-build-by-port
            (fn [& _] (throw (js/Error. "resolver must not be called when :build is explicit"))))
      (-> (tu/with-stubbed-eval! healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {:build "my-app" :port 8031}))))
          (.then
            (fn [result]
              (is (= {:ok? true :build-id :my-app}
                     (select-keys (tu/extract-edn result) [:ok? :build-id])))
              nil))
          ;; Nothing may run after `done`, and a `.finally` re-throws a
          ;; rejection past it, so the rejection is reported here and the
          ;; restore and the single `done` share the last step.
          (.catch (fn [e]
                    (is false (str "explicit-build discovery rejected: " e))
                    nil))
          (.then (fn [_]
                   (set! probe/resolve-build-by-port orig)
                   (done)))))))
