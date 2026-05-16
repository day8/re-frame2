(ns day8.re-frame2-causa-mcp.tools.reset-frame-db-test
  "Unit tests for the T-Mut-3 tool `reset-frame-db` (rf2-8xzoe.25).

  Pins:
    - Public surface + registration.
    - `parse-value-arg` distinguishes `::absent` from `::malformed`
      from `nil-parsed-value`.
    - Origin tag wrap on the eval form.
    - Three-row failure surface passthrough."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.tools.reset-frame-db :as rfdb]))

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
  (is (fn? rfdb/reset-frame-db-tool))
  (is (fn? rfdb/build-form))
  (is (fn? rfdb/shape-envelope))
  (is (fn? rfdb/parse-value-arg))
  (is (= "reset-frame-db" (:name rfdb/descriptor))))

(deftest registered-in-the-catalogue
  (is (some? (registry/handler-for "reset-frame-db"))))

;; --- parse-value-arg --------------------------------------------------------

(deftest parse-value-arg-distinguishes-absent-malformed-nil
  (is (= ::rfdb/absent (rfdb/parse-value-arg nil)))
  (is (= ::rfdb/malformed (rfdb/parse-value-arg "(((not-edn")))
  (is (= {:a 1} (rfdb/parse-value-arg "{:a 1}")))
  (is (nil? (rfdb/parse-value-arg "nil"))
      "EDN nil parses to nil — distinct from ::absent (no input at all)")
  (is (= {:cart {}} (rfdb/parse-value-arg {:cart {}}))
      "CLJS-map passthrough"))

;; --- build-form -------------------------------------------------------------

(deftest build-form-wraps-origin-and-routes-runtime-ns
  (let [form (rfdb/build-form {:frame :app :value {:cart {}}})]
    (is (.includes form "day8.re-frame2-causa.runtime/reset-frame-db!"))
    (is (.includes form ":causa-mcp"))
    (is (.includes form ":value {:cart {}}"))))

;; --- handler validation -----------------------------------------------------

(deftest missing-value-short-circuits
  (async done
    (-> (rfdb/reset-frame-db-tool (atom {}) #js {})
        (.then (fn [r]
                 (is (= :missing-value (:reason (payload-of r))))
                 (done))))))

(deftest malformed-value-short-circuits
  (async done
    (-> (rfdb/reset-frame-db-tool (atom {}) #js {"value" "((not-edn"})
        (.then (fn [r]
                 (is (= :value-malformed (:reason (payload-of r))))
                 (done))))))

;; --- shape-envelope ---------------------------------------------------------

(deftest shape-envelope-success-passthrough
  (let [shaped (rfdb/shape-envelope {:ok? true :frame :app :origin :causa-mcp})]
    (is (true? (:ok? shaped)))
    (is (= :causa-mcp (:origin shaped)))))

(deftest shape-envelope-failure-passthrough
  (testing "three-row failure: schema mismatch / unknown frame / reset
            during drain all surface as :rf.epoch/reset-failed"
    (let [env    {:ok? false :frame :app :origin :causa-mcp
                  :reason :rf.epoch/reset-failed
                  :hint   "Reset failed — read the trace bus..."}
          shaped (rfdb/shape-envelope env)]
      (is (false? (:ok? shaped)))
      (is (= :rf.epoch/reset-failed (:reason shaped))))))

;; --- end-to-end via stubs ---------------------------------------------------

(deftest happy-path-via-stubs
  (async done
    (stub-runtime! {:ok? true :frame :app :origin :causa-mcp})
    (-> (rfdb/reset-frame-db-tool (atom {}) #js {"value" "{:cart {}}"})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (true? (:ok? p)))
                   (is (= :causa-mcp (:origin p))))
                 (done))))))

(deftest probe-rejection-surfaces-runtime-not-preloaded
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (js/Promise.reject
              (ex-info "causa runtime not preloaded"
                       {:reason :runtime-not-preloaded
                        :hint   "setup-hint"}))))
    (-> (rfdb/reset-frame-db-tool (atom {}) #js {"value" "{}"})
        (.then (fn [r]
                 (is (= :runtime-not-preloaded (:reason (payload-of r))))
                 (done))))))
