(ns re-frame2-pair-mcp.port-to-build-test
  "URL/port -> build resolution via the shadow-cljs :dev-http map.

  A pair session starts from the browser URL of the open tab (e.g.
  http://localhost:8031/counter), but discover-app speaks build-ids. The
  `:port` arg bridges the two: an operator passes the port from the URL
  and discover-app resolves the build serving it. These tests pin the
  `:port` arg path:

    - `probe/resolve-build-by-port` reads the :dev-http map JVM-side and
      returns the build whose :output-dir is served on that port.
    - discover-app accepts `:port` and probes the resolved build; an
      explicit `:build` arg wins; an unmappable port fails loud with
      `:port-unresolved` rather than silently defaulting to :app."
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

;; ---------------------------------------------------------------------------
;; The probe helper — drives the JVM form through a stubbed `jvm-eval`.
;; ---------------------------------------------------------------------------

(deftest resolve-build-by-port-reads-the-jvm-result
  ;; The JVM form does the :dev-http lookup + :output-dir match server-
  ;; side and returns a keyword; the helper reads it back as EDN.
  (async done
    (let [conn (fresh-conn)
          orig nrepl/jvm-eval
          stub (fn
                 ([_c form] (js/Promise.resolve
                              ;; sanity: the form references the port + the
                              ;; :dev-http / :output-dir matcher.
                              (if (and (re-find #"8031" form)
                                       (re-find #":dev-http" form)
                                       (re-find #":output-dir" form))
                                {:value ":examples/step-deck"}
                                {:value "nil"})))
                 ([_c form _o] (js/Promise.resolve
                                 (if (re-find #"8031" form)
                                   {:value ":examples/step-deck"}
                                   {:value "nil"}))))]
      (set! nrepl/jvm-eval stub)
      (-> (probe/resolve-build-by-port conn 8031)
          (.then (fn [bid]
                   (is (= :examples/step-deck bid)
                       "resolves the build serving port 8031")))
          (.finally (fn [] (tu/restore-jvm-eval! stub orig)))
          (.then (fn [_] (done)))))))

(deftest resolve-build-by-port-nil-on-no-match
  ;; Unmapped port → JVM form returns nil → helper returns nil (the
  ;; caller turns that into :port-unresolved).
  (async done
    (let [conn (fresh-conn)
          orig nrepl/jvm-eval
          stub (fn [& _] (js/Promise.resolve {:value "nil"}))]
      (set! nrepl/jvm-eval stub)
      (-> (probe/resolve-build-by-port conn 9999)
          (.then (fn [bid] (is (nil? bid))))
          (.finally (fn [] (tu/restore-jvm-eval! stub orig)))
          (.then (fn [_] (done)))))))

(deftest resolve-build-by-port-coerces-string-port
  ;; The MCP wire may deliver the port as a string; it's coerced to int.
  (async done
    (let [conn (fresh-conn)
          orig nrepl/jvm-eval
          seen (atom nil)
          stub (fn
                 ([_c form] (reset! seen form) (js/Promise.resolve {:value ":examples/step-deck"}))
                 ([_c form _o] (reset! seen form) (js/Promise.resolve {:value ":examples/step-deck"})))]
      (set! nrepl/jvm-eval stub)
      (-> (probe/resolve-build-by-port conn "8031")
          (.then (fn [bid]
                   (is (= :examples/step-deck bid))
                   (is (re-find #"\b8031\b" @seen)
                       "string port coerced to the integer 8031 in the JVM form")))
          (.finally (fn [] (tu/restore-jvm-eval! stub orig)))
          (.then (fn [_] (done)))))))

(deftest resolve-build-by-port-nil-on-non-numeric
  ;; A non-numeric port short-circuits to nil without a round-trip.
  (async done
    (-> (probe/resolve-build-by-port (fresh-conn) "not-a-port")
        (.then (fn [bid] (is (nil? bid))))
        (.then (fn [_] (done))))))

;; ---------------------------------------------------------------------------
;; discover-app integration — stub the resolver to keep these hermetic.
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
  ;; {:port 8031} -> probes :examples/step-deck, succeeds, caches it.
  (async done
    (let [conn (fresh-conn)]
      ;; prime so runtime-preloaded? short-circuits and the only cljs-eval
      ;; the stub serves is the health call.
      (swap! conn update :probed-builds conj :examples/step-deck)
      (-> (with-port-resolution! :examples/step-deck healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {:port 8031}))))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)]
                (is (true? (:ok? edn)))
                (is (= :examples/step-deck (:build-id edn))
                    "resolved the build serving the port")
                ;; port-resolved is a deliberate choice, not an auto-select.
                (is (not (contains? edn :auto-selected-build)))
                (is (= :examples/step-deck (:resolved-build-id @conn))
                    "resolved build cached for follow-up calls"))
              (done)))))))

(deftest discover-app-port-unresolved-fails-loud
  ;; A port that maps to no build → :port-unresolved, NOT a silent :app.
  (async done
    (let [conn (fresh-conn)]
      (-> (with-port-resolution! nil healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {:port 9999}))))
          (.then
            (fn [result]
              ;; The payload carries :ok? false but rides the ok-text
              ;; envelope (matching the other discover-app precondition
              ;; failures), so :isError is not set.
              (is (not (tu/error? result)))
              (let [edn (tu/extract-edn result)]
                (is (false? (:ok? edn)))
                (is (= :port-unresolved (:reason edn)))
                (is (= 9999 (:port edn)))
                (is (string? (:hint edn)))
                (is (nil? (:resolved-build-id @conn))
                    "an unresolved port must not cache anything"))
              (done)))))))

(deftest discover-app-explicit-build-wins-over-port
  ;; Both :build and :port given → :build wins; the resolver isn't even
  ;; consulted (a throwing stub proves it).
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
              (let [edn (tu/extract-edn result)]
                (is (true? (:ok? edn)))
                (is (= :my-app (:build-id edn)) "explicit build wins over port"))
              (done)))
          (.finally (fn [] (set! probe/resolve-build-by-port orig)))
          (.then (fn [_] (done)))))))
