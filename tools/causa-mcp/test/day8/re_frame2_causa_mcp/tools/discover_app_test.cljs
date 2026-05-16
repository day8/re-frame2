(ns day8.re-frame2-causa-mcp.tools.discover-app-test
  "Unit tests for the T-Meta-1 tool `discover-app` (rf2-8xzoe.30).

  Pins the five-rung warning ladder and the pre-flight probe gating."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.tools.discover-app :as da]
            [day8.re-frame2-causa-mcp.tools.eval-cljs :as eval-cljs]))

(def ^:private orig-cljs-eval-value nrepl/cljs-eval-value)
(def ^:private orig-ensure-runtime! probe/ensure-runtime!)
(def ^:private orig-runtime-health! probe/runtime-health!)
(def ^:private orig-allow-eval? (eval-cljs/allow-eval-enabled?))

(defn- restore-all! []
  (set! nrepl/cljs-eval-value orig-cljs-eval-value)
  (set! probe/ensure-runtime! orig-ensure-runtime!)
  (set! probe/runtime-health! orig-runtime-health!)
  (eval-cljs/set-allow-eval! orig-allow-eval?))

(use-fixtures :each {:after (fn [] (restore-all!))})

(defn- stub-health! [health]
  (set! probe/ensure-runtime! (fn [_ _] (js/Promise.resolve nil)))
  (set! probe/runtime-health! (fn [_ _] (js/Promise.resolve health))))

(defn- payload-of [^js result]
  (-> result (j/get :content) (aget 0) (j/get :text) edn/read-string))

(deftest public-surface-resolvable
  (is (fn? da/discover-app-tool))
  (is (fn? da/shape-envelope))
  (is (= "discover-app" (:name da/descriptor))))

(deftest registered-in-the-catalogue
  (is (some? (registry/handler-for "discover-app"))))

;; --- shape-envelope — warning ladder ----------------------------------------

(deftest shape-envelope-healthy-app
  (let [shaped (da/shape-envelope
                 {:ok? true :debug-enabled? true :frames [:app]
                  :ambiguous-frame? false :coord-annotation-enabled? true
                  :session-id "s-1" :origin :causa-mcp}
                 :app false)]
    (is (true? (:ok? shaped)))
    (is (nil? (:warning shaped)))
    (is (= :app (:build-id shaped)))
    (is (false? (:eval-cljs-enabled? shaped)))))

(deftest shape-envelope-debug-disabled-fails-loud
  (let [shaped (da/shape-envelope
                 {:ok? true :debug-enabled? false :frames []}
                 :app false)]
    (is (false? (:ok? shaped)))
    (is (= :debug-disabled (:reason shaped)))))

(deftest shape-envelope-no-frames-fails-loud
  (let [shaped (da/shape-envelope
                 {:ok? true :debug-enabled? true :frames []}
                 :app false)]
    (is (false? (:ok? shaped)))
    (is (= :no-frames-registered (:reason shaped)))))

(deftest shape-envelope-ambiguous-frame-warns
  (let [shaped (da/shape-envelope
                 {:ok? true :debug-enabled? true :frames [:app :other]
                  :ambiguous-frame? true :coord-annotation-enabled? true}
                 :app false)]
    (is (true? (:ok? shaped)))
    (is (= :ambiguous-frame (:warning shaped)))
    (is (string? (:note shaped)))))

(deftest shape-envelope-no-coord-annotation-warns
  (let [shaped (da/shape-envelope
                 {:ok? true :debug-enabled? true :frames [:app]
                  :ambiguous-frame? false :coord-annotation-enabled? false}
                 :app false)]
    (is (true? (:ok? shaped)))
    (is (= :no-source-coord-annotation (:warning shaped)))))

(deftest shape-envelope-failing-health-passthrough
  (let [env {:ok? false :reason :something}]
    (is (= env (da/shape-envelope env :app false)))))

(deftest shape-envelope-stamps-eval-cljs-enabled
  (let [shaped (da/shape-envelope
                 {:ok? true :debug-enabled? true :frames [:app]
                  :ambiguous-frame? false :coord-annotation-enabled? true}
                 :app true)]
    (is (true? (:eval-cljs-enabled? shaped)))))

;; --- end-to-end via stubs ---------------------------------------------------

(deftest happy-path-via-stubs
  (async done
    (eval-cljs/set-allow-eval! false)
    (stub-health! {:ok? true :debug-enabled? true :frames [:app]
                   :ambiguous-frame? false :coord-annotation-enabled? true
                   :session-id "s-1"})
    (-> (da/discover-app-tool (atom {}) #js {})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (true? (:ok? p)))
                   (is (= [:app] (:frames p)))
                   (is (false? (:eval-cljs-enabled? p))))
                 (done))))))

(deftest probe-rejection-surfaces-runtime-not-preloaded
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (js/Promise.reject
              (ex-info "causa runtime not preloaded"
                       {:reason :runtime-not-preloaded
                        :hint   "setup-hint"}))))
    (-> (da/discover-app-tool (atom {}) #js {})
        (.then (fn [r]
                 (is (= :runtime-not-preloaded (:reason (payload-of r))))
                 (done))))))
