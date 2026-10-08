(ns re-frame2-pair-mcp.probe-test
  "Unit tests for the runtime preload probe: its per-`(conn, build-id)` cache
  of positive results, the failure diagnostic ladder, the JVM-side liveness
  re-check behind a cached positive, and `err->result`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.freshness :as freshness]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defn- with-stubbed-eval!
  "Stub `nrepl/cljs-eval-value` to resolve `canned-value`, counting calls
  into `call-count*`, and `nrepl/jvm-eval` so the diagnostic ladder sees a
  reachable JVM running `:app`. Runs the Promise-returning `body-fn`, then
  restores both."
  [canned-value call-count* body-fn]
  (let [orig-cljs nrepl/cljs-eval-value
        orig-jvm  nrepl/jvm-eval
        cljs-stub (fn
                    ([_conn _build-id _form-str]
                     (swap! call-count* inc)
                     (js/Promise.resolve canned-value))
                    ([_conn _build-id _form-str _opts]
                     (swap! call-count* inc)
                     (js/Promise.resolve canned-value)))
        jvm-stub  (fn
                    ([_conn form-str]
                     (js/Promise.resolve
                       (if (re-find #"active-builds" form-str)
                         {:value "[:app]"}
                         {:value "1"})))
                    ([_conn form-str _opts]
                     (js/Promise.resolve
                       (if (re-find #"active-builds" form-str)
                         {:value "[:app]"}
                         {:value "1"}))))]
    (set! nrepl/cljs-eval-value cljs-stub)
    (set! nrepl/jvm-eval         jvm-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (tu/restore-eval! cljs-stub orig-cljs)
                    (tu/restore-jvm-eval! jvm-stub orig-jvm))))))

;; A real conn-atom, never connected: the probe cache lives on it.
(defn- fresh-conn []
  (nrepl/make-conn 0 "127.0.0.1"))

(deftest distinct-builds-probe-independently
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-eval! true calls
            (fn []
              (-> (probe/runtime-preloaded? conn :app)
                  (.then (fn [_] (probe/runtime-preloaded? conn :other)))
                  (.then (fn [_] (probe/runtime-preloaded? conn :app)))
                  (.then (fn [_] (probe/runtime-preloaded? conn :other)))
                  (.then (fn [ok?]
                           (is (true? ok?) "a cache hit resolves true")
                           (is (= 2 @calls)
                               "Each build probes once; subsequent hits cache"))))))
          (.then (fn [_] (done)))))))

(deftest negative-probe-is-not-cached
  ;; Re-probing lets a freshly-added preload land without a server restart.
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-eval! false calls
            (fn []
              (-> (probe/runtime-preloaded? conn :app)
                  (.then (fn [_] (probe/runtime-preloaded? conn :app)))
                  (.then (fn [ok?]
                           (is (false? ok?))
                           (is (= 2 @calls)
                               "Negative result must re-probe"))))))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; Diagnostic ladder — each rung end-to-end through `ensure-runtime!`.
;; ---------------------------------------------------------------------------

(defn- with-tri-stub!
  "Stub `cljs-eval-value` and `jvm-eval` with `(fn [form-str] -> canned)`
  fns, run the Promise-returning `body-fn`, then restore both."
  [cljs-fn jvm-fn body-fn]
  (let [orig-cljs nrepl/cljs-eval-value
        orig-jvm  nrepl/jvm-eval
        cljs-stub (fn
                    ([_conn _build-id form-str]      (js/Promise.resolve (cljs-fn form-str)))
                    ([_conn _build-id form-str _opts] (js/Promise.resolve (cljs-fn form-str))))
        jvm-stub  (fn
                    ([_conn form-str]      (js/Promise.resolve (jvm-fn form-str)))
                    ([_conn form-str _opts] (js/Promise.resolve (jvm-fn form-str))))]
    (set! nrepl/cljs-eval-value cljs-stub)
    (set! nrepl/jvm-eval         jvm-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (tu/restore-eval! cljs-stub orig-cljs)
                    (tu/restore-jvm-eval! jvm-stub orig-jvm))))))

(defn- assert-ladder-rejects-with-reason
  "Drive `ensure-runtime!` to its rejection and assert the rung's reason and
  hint pattern, plus any `extra-assertions` on the ex-data."
  [conn expected-reason hint-pattern done extra-assertions]
  (-> (probe/ensure-runtime! conn :app)
      (.then (fn [_] (is false "must reject"))
             (fn [err]
               (let [data (ex-data err)]
                 (is (= expected-reason (:reason data)))
                 (is (re-find hint-pattern (:hint data)))
                 (when extra-assertions (extra-assertions data)))))
      (.then (fn [_] (done)))))

(defn- jvm-running-app [form-str]
  (if (re-find #"active-builds" form-str)
    {:value "[:app]"}
    {:value "1"}))

(deftest diagnose-rung-nrepl-unreachable
  ;; The JVM answers garbage, not "1".
  (async done
    (with-tri-stub! (fn [_] false)
                    (fn [_] {:value "dead"})
      (fn []
        (assert-ladder-rejects-with-reason
          (fresh-conn) :nrepl-unreachable #"nREPL" done nil)))))

(deftest diagnose-rung-build-not-running
  (async done
    (with-tri-stub! (fn [_] false)
                    (fn [form-str]
                      (if (re-find #"active-builds" form-str)
                        {:value "[:other]"}
                        {:value "1"}))
      (fn []
        (assert-ladder-rejects-with-reason
          (fresh-conn) :build-not-running #":other" done
          (fn [data]
            (is (= [:other] (:running-builds data)))))))))

(deftest diagnose-rung-no-runtime-connected
  ;; The build runs but its cljs-eval answers blank.
  (async done
    (with-tri-stub! (fn [_] nil) jvm-running-app
      (fn []
        (assert-ladder-rejects-with-reason
          (fresh-conn) :no-runtime-connected #"no CLJS runtime" done nil)))))

;; A property pin, not a text pin. Under :deps or :lein shadow IGNORES a
;; shadow-cljs.edn :source-paths key, so a hint sending every app there is
;; wrong for most of them, and SKILL.md has the agent read this hint out
;; verbatim. What must hold is that the delivered hint names the classpath
;; and branches to every file that can own it; the prose stays free to move.
(deftest preload-missing-hint-branches-on-classpath-owner
  (async done
    (with-tri-stub! (fn [_] false) jvm-running-app
      (fn []
        (assert-ladder-rejects-with-reason
          (fresh-conn) :runtime-loaded-but-preload-missing #"(?i)classpath" done
          (fn [data]
            (is (every? #(str/includes? (:hint data) %)
                        ["deps.edn" ":extra-paths" "project.clj" ":preloads"])
                "the :deps and :lein owners and the :preloads line are all named")
            (is (= :app (:build data))
                "rejection carries the build id for the operator's next move")))))))

;; ---------------------------------------------------------------------------
;; Liveness re-validation. A cached positive marker outlives the browser tab,
;; so every `ensure-runtime!` also reads the JVM-side `:runtime-count`
;; (`freshness/jvm-build-freshness`, a separate round-trip, stubbed here).
;; ---------------------------------------------------------------------------

(defn- with-stubbed-freshness-sequence!
  "Stub `freshness/jvm-build-freshness` to resolve each of `values` in turn
  (the last repeats), counting calls into `calls*`."
  [values calls* body-fn]
  (let [orig freshness/jvm-build-freshness
        n    (count values)
        stub (fn [_conn _build-id]
               (let [i (swap! calls* inc)]
                 (js/Promise.resolve (nth values (min (dec i) (dec n))))))]
    (set! freshness/jvm-build-freshness stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-freshness! stub orig))))))

(deftest ensure-runtime-revalidates-a-previously-cached-positive-probe
  ;; The first call caches the marker; then the tab goes away (:runtime-count
  ;; 0, and the ladder's own eval answers blank). The second call must reject.
  (async done
    (let [conn            (fresh-conn)
          eval-calls      (atom 0)
          freshness-calls (atom 0)
          cljs-fn         (fn [_form]
                            (if (= 1 (swap! eval-calls inc)) true nil))
          drive!          (fn []
                            (-> (probe/ensure-runtime! conn :app)
                                (.then (fn [_] (probe/ensure-runtime! conn :app)))
                                (.then (fn [_]
                                         (is false "second ensure-runtime! must reject once the tab disconnects")))
                                (.catch (fn [err]
                                          (is (= {:reason :no-runtime-connected :build :app}
                                                 (select-keys (ex-data err) [:reason :build]))
                                              "the liveness re-check routes to the precise reason")))))]
      (-> (with-stubbed-freshness-sequence!
            [{:runtime-count 1} {:runtime-count 0}] freshness-calls
            (fn [] (with-tri-stub! cljs-fn jvm-running-app drive!)))
          (.then (fn [_]
                   (is (= 2 @freshness-calls)
                       "the liveness read re-runs on EVERY ensure-runtime! call, cache hit or not")
                   (done)))))))

(deftest ensure-runtime-cache-hit-stays-live-when-jvm-confirms-runtime
  (async done
    (let [conn  (fresh-conn)
          calls (atom 0)]
      (-> (with-stubbed-freshness-sequence! [{:runtime-count 1}] (atom 0)
            (fn []
              (with-stubbed-eval! true calls
                (fn []
                  (-> (probe/ensure-runtime! conn :app)
                      (.then (fn [_] (probe/ensure-runtime! conn :app)))
                      (.then (fn [_] (probe/ensure-runtime! conn :app)))
                      (.catch (fn [err]
                                (is false (str "must not reject: " (.-message err))))))))))
          (.then (fn [_]
                   (is (= 1 @calls)
                       "the marker probe is still cache-hit — only the JVM liveness read re-runs")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; `err->result`: a rejection is a known-tool failure, so it MUST surface as
;; isError rather than a success-shaped value the response cache could store.
;; ---------------------------------------------------------------------------

(deftest err->result-bare-rejection-is-error-with-fallback-reason
  (let [out (probe/err->result :watch-until-failed (js/Error. "socket boom"))]
    (is (tu/error? out) "bare rejection is :isError true")
    (is (= {:ok? false :reason :watch-until-failed :message "socket boom"} (tu/extract-edn out))
        "a non-ex-info rejection takes the fallback reason and carries its message")))
