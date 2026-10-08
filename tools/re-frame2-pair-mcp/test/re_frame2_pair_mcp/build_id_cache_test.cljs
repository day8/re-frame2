(ns re-frame2-pair-mcp.build-id-cache-test
  "The session-scoped `:resolved-build-id` cache: the build `discover-app`
  last resolved becomes the default for tool calls that pass no `:build`.

  `discover-app` writes it on success (warning branches included),
  `wire/arg-build` reads it after an explicit `:build` arg and before the
  env-var fallback, and `probe/resolve-build!` treats it as deliberate.
  Its lifecycle across `close!` and a same-port reopen is pinned by
  nrepl-test."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- fresh-conn
  "A conn-atom as `connect!` leaves it: both build caches empty."
  []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{} :resolved-build-id nil)
    conn))

(def ^:private healthy-health
  {:ok?                        true
   :debug-enabled?             true
   :coord-annotation-enabled?  true
   :frames                     [:rf/default]
   :ambiguous-frame?           false})

(defn- prime-probe-cache!
  "Mark `build-id` probed so `runtime-preloaded?` short-circuits and the
  eval stub only has to answer the health read."
  [conn build-id]
  (swap! conn update :probed-builds (fnil conj #{}) build-id))

;; ---------------------------------------------------------------------------
;; `wire/arg-build`.
;; ---------------------------------------------------------------------------

(deftest arg-build-tolerates-a-leading-colon
  ;; The hint prints the colon form; both forms must resolve to one
  ;; keyword, never the doubled-colon `::examples/step-deck`.
  (let [conn (fresh-conn)]
    (is (= [:examples/step-deck :examples/step-deck]
           (mapv #(wire/arg-build conn (tu/args->js {:build %}))
                 ["examples/step-deck" ":examples/step-deck"])))))

(deftest arg-build-explicit-arg-overrides-cache
  (let [conn (fresh-conn)]
    (swap! conn assoc :resolved-build-id :examples/step-deck)
    (is (= :other-build (wire/arg-build conn (tu/args->js {:build "other-build"}))))))

;; ---------------------------------------------------------------------------
;; `discover-app` writes the cache.
;; ---------------------------------------------------------------------------

(deftest discover-app-caches-on-warning-branches
  ;; An ambiguous-frame warning still means the build is reachable.
  (async done
    (let [conn      (fresh-conn)
          _         (prime-probe-cache! conn :examples/step-deck)
          ambiguous (assoc healthy-health
                           :frames [:rf/default :feature/sandbox]
                           :ambiguous-frame? true)]
      (-> (tu/with-stubbed-eval! ambiguous
            (fn [] (discover-app/discover-app conn (tu/args->js {:build "examples/step-deck"}))))
          (.then
            (fn [_]
              (is (= :examples/step-deck (:resolved-build-id @conn)))
              (done)))))))

(deftest discover-app-caches-and-echoes-the-canonical-build
  ;; `:build` echoes the canonical keyword under the input arg's name, so
  ;; it can be copied straight back into a later call.
  (async done
    (let [conn (fresh-conn)
          _    (prime-probe-cache! conn :examples/step-deck)]
      (-> (tu/with-stubbed-eval! healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {:build "examples/step-deck"}))))
          (.then
            (fn [result]
              (is (= {:build-id :examples/step-deck :build :examples/step-deck}
                     (select-keys (tu/extract-edn result) [:build-id :build])))
              (is (= :examples/step-deck (:resolved-build-id @conn)))
              (done)))))))

;; ---------------------------------------------------------------------------
;; Single-build auto-selection: exactly one running build fills an omitted
;; `:build`; zero or many keep the `:app` default so the diagnostic lists
;; the running builds rather than silently picking one.
;; ---------------------------------------------------------------------------

(defn- with-running-builds!
  "Stub `probe/running-builds` to `running-vec` and `nrepl/cljs-eval-value`
  to `health`, restoring both in `.finally`."
  [running-vec health body-fn]
  (let [orig-running probe/running-builds
        orig-eval    nrepl/cljs-eval-value
        eval-stub    (fn
                       ([_c _b _f] (js/Promise.resolve health))
                       ([_c _b _f _o] (js/Promise.resolve health)))]
    (set! probe/running-builds (fn [_conn] (js/Promise.resolve running-vec)))
    (set! nrepl/cljs-eval-value eval-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (set! probe/running-builds orig-running)
                    (tu/restore-eval! eval-stub orig-eval))))))

(deftest discover-app-auto-selects-the-single-running-build
  (async done
    (let [conn (fresh-conn)
          _    (prime-probe-cache! conn :examples/step-deck)]
      (-> (with-running-builds! [:examples/step-deck] healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {}))))
          (.then
            (fn [result]
              (is (= {:ok? true :build-id :examples/step-deck :auto-selected-build :examples/step-deck}
                     (select-keys (tu/extract-edn result) [:ok? :build-id :auto-selected-build])))
              (is (= :examples/step-deck (:resolved-build-id @conn))
                  "the auto-selected build is cached for follow-up calls")
              (done)))))))

(deftest discover-app-no-arg-does-not-auto-select-when-many-run
  (async done
    (let [conn (fresh-conn)
          _    (prime-probe-cache! conn :app)]
      (-> (with-running-builds! [:testbeds/panel-gallery :examples/step-deck] healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {}))))
          (.then
            (fn [result]
              (is (= {:build-id :app}
                     (select-keys (tu/extract-edn result) [:build-id :auto-selected-build]))
                  "falls back to the :app default; no auto-selection")
              (done)))))))

(deftest discover-app-explicit-build-skips-auto-select
  ;; An explicit `:build` is used verbatim even when one OTHER build runs.
  (async done
    (let [conn (fresh-conn)
          _    (prime-probe-cache! conn :my-app)]
      (-> (with-running-builds! [:examples/step-deck] healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {:build "my-app"}))))
          (.then
            (fn [result]
              (is (= {:build-id :my-app}
                     (select-keys (tu/extract-edn result) [:build-id :auto-selected-build])))
              (done)))))))

;; ---------------------------------------------------------------------------
;; The eval-path resolver `resolve-build!`.
;; ---------------------------------------------------------------------------

(deftest resolve-build-rejects-with-candidates-when-no-target-and-many-run
  (async done
    ;; The stub is restored in the trailing step, ahead of `done`: a
    ;; `.finally` restore would fire after `done` and leak into the next test.
    (let [orig     probe/running-builds
          restore! (fn [] (set! probe/running-builds orig))
          conn     (fresh-conn)]
      (set! probe/running-builds
            (fn [_] (js/Promise.resolve [:examples/step-deck :testbeds/panel-gallery])))
      (-> (probe/resolve-build! conn :app false)
          (.then (fn [_]
                   (is false "must reject on an ambiguous target"))
                 (fn [err]
                   (is (= {:reason         :no-runtime-for-build
                           :running-builds [:examples/step-deck :testbeds/panel-gallery]}
                          (select-keys (ex-data err) [:reason :running-builds])))))
          (.then (fn [_] (restore!) (done)))))))

(deftest resolve-build-honours-cached-session-target-over-ambiguity
  ;; The cached target counts as deliberate, so it resolves verbatim with
  ;; no running-builds lookup to be ambiguous about.
  (async done
    (let [conn (fresh-conn)
          args (tu/args->js {})]
      (swap! conn assoc :resolved-build-id :examples/step-deck)
      (-> (probe/resolve-build! conn (wire/arg-build conn args) (wire/arg-build-explicit? conn args))
          (.then (fn [resolved]
                   (is (= :examples/step-deck resolved))))
          (.catch (fn [_] (is false "must not reject when a session target is cached") nil))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; discover-app {:port} with no pre-seeded probe cache, then a no-build call
;; through `tools/invoke`: the live first-contact flow on a multi-build
;; workspace.
;; ---------------------------------------------------------------------------

(defn- preload-probe-form?
  "True for the `runtime-preloaded?` sentinel probe, false for the health read."
  [form-str]
  (and (string? form-str)
       (re-find #"__re_frame2_pair_runtime" form-str)))

(defn- live-like-eval-stub
  "Answers like a connected runtime: `true` to the preload probe, `health`
  to the health read."
  [health]
  (fn
    ([_c _b form] (js/Promise.resolve (if (preload-probe-form? form) true health)))
    ([_c _b form _o] (js/Promise.resolve (if (preload-probe-form? form) true health)))))

(deftest port-discover-no-pre-probe-sticks-through-invoke
  (async done
    (let [conn          (fresh-conn)
          captured      (atom :NOT-CALLED)
          orig-running  probe/running-builds
          orig-port     probe/resolve-build-by-port
          orig-eval     nrepl/cljs-eval-value
          orig-jvm      nrepl/jvm-eval
          orig-get-path get-path/get-path-tool
          eval-stub     (live-like-eval-stub healthy-health)
          jvm-stub      (fn [& _] (js/Promise.resolve {:value ""}))]
      (set! probe/running-builds
            (fn [_] (js/Promise.resolve [:examples/machine-epochs :examples/standard-epochs])))
      (set! probe/resolve-build-by-port (fn [_c _p] (js/Promise.resolve :examples/machine-epochs)))
      (set! nrepl/cljs-eval-value eval-stub)
      (set! nrepl/jvm-eval jvm-stub)
      (set! get-path/get-path-tool
            (fn [c args]
              (reset! captured (wire/arg-build c args))
              (js/Promise.resolve
                #js {:content #js [#js {:type "text" :text "{:ok? true}"}]})))
      (-> (discover-app/discover-app conn (tu/args->js {:port 8033}))
          (.then (fn [result]
                   (is (= {:ok? true :build-id :examples/machine-epochs}
                          (select-keys (tu/extract-edn result) [:ok? :build-id])))
                   (is (contains? (:probed-builds @conn) :examples/machine-epochs)
                       "discover-app probed and marked the resolved build itself")
                   (tools/invoke conn "get-path" (tu/args->js {:path "[:k]"}) nil)))
          (.then (fn [_]
                   (is (= :examples/machine-epochs @captured)
                       "the no-build call through invoke targets the resolved build, not :app")))
          (.finally (fn []
                      (set! probe/running-builds orig-running)
                      (set! probe/resolve-build-by-port orig-port)
                      (tu/restore-eval! eval-stub orig-eval)
                      (tu/restore-jvm-eval! jvm-stub orig-jvm)
                      (set! get-path/get-path-tool orig-get-path)))
          (.then (fn [_] (done)))))))
