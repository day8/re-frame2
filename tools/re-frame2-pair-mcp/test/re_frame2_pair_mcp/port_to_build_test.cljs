(ns re-frame2-pair-mcp.port-to-build-test
  "URL port to build resolution via the shadow-cljs `:dev-http` map.

  An operator who knows only the browser URL passes its port:
  `probe/resolve-build-by-port` reads `:dev-http` JVM-side and returns the
  build whose `:output-dir` that port serves, and discover-app probes it.
  An explicit `:build` arg wins; an unmappable port fails loud with
  `:port-unresolved` rather than silently defaulting to `:app`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{} :resolved-build-id nil)
    conn))

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

;; ---------------------------------------------------------------------------
;; discover-app with `:port`, the resolver stubbed.
;; ---------------------------------------------------------------------------

(defn- with-port-resolution! [resolved health body-fn]
  (let [orig-port probe/resolve-build-by-port
        orig-eval nrepl/cljs-eval-value
        eval-stub (fn
                    ([_c _b _f] (js/Promise.resolve health))
                    ([_c _b _f _o] (js/Promise.resolve health)))]
    (set! probe/resolve-build-by-port (fn [_c _p] (js/Promise.resolve resolved)))
    (set! nrepl/cljs-eval-value eval-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (set! probe/resolve-build-by-port orig-port)
                    (tu/restore-eval! eval-stub orig-eval))))))

(deftest discover-app-port-resolves-to-the-serving-build
  ;; A port-resolved build is a deliberate choice, not an auto-selection.
  (async done
    (let [conn (fresh-conn)]
      (swap! conn update :probed-builds conj :examples/step-deck)
      (-> (with-port-resolution! :examples/step-deck healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {:port 8031}))))
          (.then
            (fn [result]
              (is (= {:ok? true :build-id :examples/step-deck}
                     (select-keys (tu/extract-edn result) [:ok? :build-id :auto-selected-build])))
              (is (= :examples/step-deck (:resolved-build-id @conn))
                  "resolved build cached for follow-up calls")
              (done)))))))

(deftest discover-app-port-unresolved-fails-loud
  ;; It rides isError like every other `:ok? false`, and caches nothing.
  (async done
    (let [conn (fresh-conn)]
      (-> (with-port-resolution! nil healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {:port 9999}))))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)]
                (is (= {:isError true :ok? false :reason :port-unresolved :port 9999 :cached nil}
                       {:isError (tu/error? result) :ok? (:ok? edn) :reason (:reason edn)
                        :port (:port edn) :cached (:resolved-build-id @conn)})))
              (done)))))))

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
