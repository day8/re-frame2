(ns day8.re-frame2-causa-mcp.tools.get-machine-list-test
  "Unit tests for the T-Insp-6 tool `get-machine-list` (rf2-8xzoe.19).
  Pins:
    - Eval-form composition addresses the runtime accessor with the
      :causa-mcp origin binding.
    - shape-envelope counts elision markers in the per-machine
      metadata and stamps :elided-large.
    - Dispatcher overflow path (W-1) replaces the result with the
      :rf.mcp/overflow marker when the rendered envelope exceeds the
      cap.
    - probe-rejection surfaces :runtime-not-preloaded with the setup
      hint.

  Stub strategy mirrors get_trace_buffer_test.cljs: direct `set!`
  with fixture restoration, sidestepping the rf2-wb06a async-cleanup
  race."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]
            [day8.re-frame2-causa-mcp.tools :as tools]
            [day8.re-frame2-causa-mcp.tools.get-machine-list :as gml]))

(def ^:private orig-cljs-eval-value nrepl/cljs-eval-value)
(def ^:private orig-ensure-runtime! probe/ensure-runtime!)

(defn- restore-stubs! []
  (set! nrepl/cljs-eval-value orig-cljs-eval-value)
  (set! probe/ensure-runtime! orig-ensure-runtime!))

(use-fixtures :each
  {:after (fn [] (restore-stubs!))})

(defn- stub-runtime! [runtime-env]
  (set! probe/ensure-runtime! (fn [_ _] (js/Promise.resolve nil)))
  (set! nrepl/cljs-eval-value
        (fn
          ([_ _ _]   (js/Promise.resolve runtime-env))
          ([_ _ _ _] (js/Promise.resolve runtime-env)))))

(deftest public-surface-resolvable
  (is (fn? gml/get-machine-list-tool))
  (is (fn? gml/build-form))
  (is (fn? gml/shape-envelope))
  (is (map? gml/descriptor))
  (is (= "get-machine-list" (:name gml/descriptor))))

(deftest registered-in-catalogue
  (is (some? (registry/handler-for "get-machine-list"))))

(deftest build-form-addresses-runtime-accessor
  (let [form (gml/build-form {:include-sensitive? false
                              :include-large?     false})]
    (is (.includes form "day8.re-frame2-causa.runtime/get-machine-list"))
    (is (.includes form ":causa-mcp"))))

(deftest shape-envelope-happy-path
  (let [runtime-env {:ok? true
                     :machines {:checkout/fsm {:initial-state :idle
                                               :transitions   {}}}
                     :count    1}
        shaped      (gml/shape-envelope runtime-env)]
    (is (true? (:ok? shaped)))
    (is (= 1 (:count shaped)))
    (is (= [:checkout/fsm] (vec (keys (:machines shaped)))))
    (is (nil? (:elided-large shaped)))))

(deftest shape-envelope-counts-elided-markers
  (let [runtime-env {:ok? true
                     :machines {:checkout/fsm {:initial-state :idle
                                               :spec {:rf.size/large-elided
                                                      {:bytes 102400}}}
                                :tour/fsm {:initial-state :start
                                           :transitions   {}}}
                     :count 2}
        shaped      (gml/shape-envelope runtime-env)]
    (is (= 1 (:elided-large shaped))
        "the one machine with a large elision marker is counted")))

(deftest shape-envelope-passes-runtime-failure
  (let [runtime-env {:ok? false :reason :runtime-failure :hint "..."}]
    (is (= runtime-env (gml/shape-envelope runtime-env)))))

(deftest dispatcher-overflow-path
  (testing "W-1: dispatcher replaces over-cap result with overflow marker"
    (async done
      (let [big-meta    {:spec (apply str (repeat 10000 \x))}
            runtime-env {:ok? true :machines {:huge/fsm big-meta} :count 1}]
        (stub-runtime! runtime-env)
        (-> (tools/invoke (atom {}) "get-machine-list"
                          #js {"max-tokens" 500} nil)
            (.then (fn [^js result]
                     (let [payload (-> (j/get result :content) (aget 0)
                                       (j/get :text) edn/read-string)]
                       (is (contains? payload :rf.mcp/overflow))
                       (is (contains? token-cap/hint-vocabulary
                                      (get-in payload [:rf.mcp/overflow :hint]))))
                     (done)))
            (.catch (fn [err]
                      (is false (str "failed: " (.-message err)))
                      (done))))))))

(deftest probe-rejection-surfaces-runtime-not-preloaded
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (js/Promise.reject
              (ex-info "preload missing"
                       {:reason :runtime-not-preloaded :hint "setup"}))))
    (-> (gml/get-machine-list-tool (atom {}) #js {})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :runtime-not-preloaded (:reason payload)))
                   (is (= "setup" (:hint payload))))
                 (done))))))
