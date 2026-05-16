(ns day8.re-frame2-causa-mcp.tools.restore-epoch-test
  "Unit tests for the T-Mut-2 tool `restore-epoch` (rf2-8xzoe.24).

  Pins:
    - Public surface + registration.
    - `:missing-epoch-id` pre-flight short-circuit.
    - Tool-Pair six-row failure surface — runtime `:ok? false` with
      `:rf.epoch/restore-failed` rides through verbatim; per-row
      `:rf.epoch/*` keyword lives on the trace bus, not the envelope.
    - Origin tag wrap on the eval form."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.tools.restore-epoch :as re]))

(def ^:private orig-cljs-eval-value nrepl/cljs-eval-value)
(def ^:private orig-ensure-runtime! probe/ensure-runtime!)

(defn- restore-stubs! []
  (set! nrepl/cljs-eval-value orig-cljs-eval-value)
  (set! probe/ensure-runtime! orig-ensure-runtime!))

(use-fixtures :each {:after (fn [] (restore-stubs!))})

(defn- stub-runtime! [runtime-env]
  (set! probe/ensure-runtime! (fn [_ _] (js/Promise.resolve nil)))
  (set! nrepl/cljs-eval-value
        (fn
          ([_ _ _]   (js/Promise.resolve runtime-env))
          ([_ _ _ _] (js/Promise.resolve runtime-env)))))

(defn- payload-of [^js result]
  (-> result (j/get :content) (aget 0) (j/get :text) edn/read-string))

(deftest public-surface-resolvable
  (is (fn? re/restore-epoch-tool))
  (is (fn? re/build-form))
  (is (fn? re/shape-envelope))
  (is (= "restore-epoch" (:name re/descriptor))))

(deftest registered-in-the-catalogue
  (is (some? (registry/handler-for "restore-epoch")))
  (is (= "restore-epoch" (:name (registry/descriptor-for "restore-epoch")))))

(deftest build-form-wraps-origin-and-routes-runtime-ns
  (let [form (re/build-form {:frame :app :epoch-id "ep-1"})]
    (is (.includes form "day8.re-frame2-causa.runtime/restore-epoch!"))
    (is (.includes form ":causa-mcp"))
    (is (.includes form ":epoch-id \"ep-1\""))))

(deftest missing-epoch-id-short-circuits
  (async done
    (-> (re/restore-epoch-tool (atom {}) #js {})
        (.then (fn [r]
                 (is (= :missing-epoch-id (:reason (payload-of r))))
                 (done))))))

(deftest blank-epoch-id-short-circuits
  (async done
    (-> (re/restore-epoch-tool (atom {}) #js {"epoch-id" ""})
        (.then (fn [r]
                 (is (= :missing-epoch-id (:reason (payload-of r))))
                 (done))))))

(deftest shape-envelope-success-passthrough
  (let [shaped (re/shape-envelope {:ok? true :frame :app :epoch-id "ep-1"
                                   :origin :causa-mcp})]
    (is (true? (:ok? shaped)))
    (is (= "ep-1" (:epoch-id shaped)))
    (is (= :causa-mcp (:origin shaped)))))

(deftest shape-envelope-failure-passthrough
  (testing "Tool-Pair six-row failure: runtime returns :ok? false with
            :rf.epoch/restore-failed; per-row keyword lives on the
            trace bus, not the envelope"
    (let [env    {:ok? false :frame :app :epoch-id "ep-stale"
                  :origin :causa-mcp
                  :reason :rf.epoch/restore-failed
                  :hint   "Restore failed — read the trace bus..."}
          shaped (re/shape-envelope env)]
      (is (false? (:ok? shaped)))
      (is (= :rf.epoch/restore-failed (:reason shaped)))
      (is (string? (:hint shaped))))))

(deftest happy-path-via-stubs
  (async done
    (stub-runtime! {:ok? true :frame :app :epoch-id "ep-42"
                    :origin :causa-mcp})
    (-> (re/restore-epoch-tool (atom {}) #js {"epoch-id" "ep-42"})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (true? (:ok? p)))
                   (is (= "ep-42" (:epoch-id p))))
                 (done))))))

(deftest probe-rejection-surfaces-runtime-not-preloaded
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (js/Promise.reject
              (ex-info "causa runtime not preloaded"
                       {:reason :runtime-not-preloaded
                        :hint   "setup-hint"}))))
    (-> (re/restore-epoch-tool (atom {}) #js {"epoch-id" "ep-1"})
        (.then (fn [r]
                 (is (= :runtime-not-preloaded (:reason (payload-of r))))
                 (done))))))
