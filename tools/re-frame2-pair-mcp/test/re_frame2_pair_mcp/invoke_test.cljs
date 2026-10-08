(ns re-frame2-pair-mcp.invoke-test
  "The seams between `tools/invoke`'s steps (precheck, dispatch, cache,
  cap). Each step has its own unit suite (`cache_test`, `wire_cap_test`).

  `invoke` is async and `with-redefs` restores synchronously, so each test
  `set!`s its stubs and the `:after` fixture restores them: it runs after
  `(done)` and before the next test starts, so no stub can leak."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.cache :as cache]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.precheck :as precheck]
            [re-frame2-pair-mcp.tools.operating-frame :as operating-frame]
            [re-frame2-pair-mcp.tools.snapshot :as snapshot]))

(def ^:private orig-fetch-precheck-hash precheck/fetch-precheck-hash)
(def ^:private orig-snapshot-tool snapshot/snapshot-tool)
(def ^:private orig-reset-operating-frame-tool operating-frame/reset-operating-frame-tool)

(use-fixtures :each
  {:before (fn [] (cache/clear!))
   :after  (fn []
             (set! precheck/fetch-precheck-hash orig-fetch-precheck-hash)
             (set! snapshot/snapshot-tool orig-snapshot-tool)
             (set! operating-frame/reset-operating-frame-tool orig-reset-operating-frame-tool)
             (cache/clear!))})

(defn- mcp-result [text & {:keys [error?]}]
  (cond-> #js {:content #js [#js {:type "text" :text text}]}
    error? (j/assoc! :isError true)))

(defn- stub-snapshot! [result-fn]
  (set! snapshot/snapshot-tool (fn [_conn _args] (js/Promise.resolve (result-fn)))))

(defn- marker [result k]
  (get (tu/extract-edn result) k))

(def ^:private big-payload (pr-str {:huge (apply str (repeat 8000 "x"))}))

(deftest result-hash-hit-when-precheck-ineligible
  (async done
    (let [args (tu/args->js {:cache "true"})]
      (stub-snapshot! #(mcp-result "{:db {:k :v}}"))
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [_] (tools/invoke nil "snapshot" args nil)))
          (.then (fn [result]
                   (is (= :result-hash (:via (marker result :rf.mcp/cache-hit))))
                   (done)))))))

(deftest is-error-still-subject-to-cap
  ;; Silent truncation is unacceptable for a failure as much as a success.
  (async done
    (stub-snapshot! #(mcp-result big-payload :error? true))
    (-> (tools/invoke nil "snapshot" (tu/args->js {"max-tokens" 100}) nil)
        (.then (fn [result]
                 (is (= "snapshot" (:tool (marker result :rf.mcp/overflow))))
                 (done))))))

(deftest negative-max-tokens-rejected-not-overflow-lockout
  ;; A negative cap reaching `apply-cap` would replace every response,
  ;; however small, with the overflow marker.
  (async done
    (let [dispatched? (atom false)]
      (stub-snapshot! #(do (reset! dispatched? true) (mcp-result "{:ok? true}")))
      (-> (tools/invoke nil "snapshot" (tu/args->js {"max-tokens" -1}) nil)
          (.then (fn [result]
                   (is (tu/error? result))
                   (is (= {:arg :max-tokens :value -1}
                          (select-keys (marker result :rf.mcp/invalid-arg) [:arg :value])))
                   (is (false? @dispatched?))
                   (done)))))))

(deftest withheld-payload-never-claims-a-cache-hit
  ;; `apply-cache` records the full payload's hash before `apply-cap`
  ;; withholds it; a hit on that entry would tell the caller to re-use
  ;; bytes it never received.
  (async done
    (let [args (tu/args->js {:cache "true" "max-tokens" 200})]
      (stub-snapshot! #(mcp-result big-payload))
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [_] (tools/invoke nil "snapshot" args nil)))
          (.then (fn [result]
                   (is (some? (marker result :rf.mcp/overflow)))
                   (done)))))))

(deftest cache-disabled-passes-through-all-phases
  (async done
    (stub-snapshot! #(mcp-result "{:db {:k :v}}"))
    (-> (tools/invoke nil "snapshot" (tu/args->js {}) nil)
        (.then (fn [_]
                 (is (zero? (cache/size)) "caching is opt-in")
                 (done))))))

(deftest precheck-target-get-path-is-ineligible
  ;; get-path egresses through `project-egress`, whose elision registry
  ;; lives in runtime-db, so the app-db hash cannot see a redaction change.
  (is (nil? (precheck/precheck-target "get-path" (tu/args->js {:frame "rf/default" :path "[:k]"})))))

(deftest single-frame-appdb-only-snapshot-no-longer-precheck-hits
  ;; The app-db-only slice still egresses through `project-egress`, so an
  ;; unchanged app-db hash can sit beside a re-redacted payload. The stub
  ;; hash matches on every call, so a precheck would serve the first
  ;; payload again; the egressed text differs, so the answer is fresh.
  (async done
    (let [args    (tu/args->js {:cache "true" :frames #js ["rf/default"] :include #js ["app-db"]})
          fetches (atom 0)
          calls   (atom 0)]
      (set! precheck/fetch-precheck-hash
            (fn [_conn _args _target] (swap! fetches inc) (js/Promise.resolve 1234)))
      (stub-snapshot! #(mcp-result (str "{:app-db {:k :v} :call " (swap! calls inc) "}")))
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [_] (tools/invoke nil "snapshot" args nil)))
          (.then (fn [result]
                   (is (zero? @fetches))
                   (is (nil? (marker result :rf.mcp/cache-hit)))
                   (done)))))))

(deftest operating-frame-change-flushes-cache
  ;; The cache key cannot include the operating frame an omitted-`:frame`
  ;; read resolves to, so a pin change must flush it; a refused change
  ;; moved nothing and must leave it.
  (async done
    (let [reset-with! (fn [result]
                        (set! operating-frame/reset-operating-frame-tool
                              (fn [_conn _args] (js/Promise.resolve result)))
                        (tools/invoke nil "reset-operating-frame" (tu/args->js {}) nil))]
      (cache/apply-cache (mcp-result "{:k :frame-a}")
                         {:tool "get-path" :args (tu/args->js {:path "[:k]"}) :enabled? true})
      (-> (reset-with! (mcp-result "{:ok? false :reason :no-such-frame}" :error? true))
          (.then (fn [_]
                   (is (= 1 (cache/size)) "a refused pin leaves the cache")
                   (reset-with! (mcp-result "{:ok? true :operating :rf/b}"))))
          (.then (fn [_]
                   (is (zero? (cache/size)) "a successful pin flushes it")
                   (done)))))))
