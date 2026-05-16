(ns day8.re-frame2-causa-mcp.tools.unsubscribe-test
  "Unit tests for the T-Stream-2 tool `unsubscribe` (rf2-8xzoe.27)."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.tools.unsubscribe :as u]))

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
  (is (fn? u/unsubscribe-tool))
  (is (fn? u/build-form))
  (is (fn? u/shape-envelope))
  (is (= "unsubscribe" (:name u/descriptor))))

(deftest registered-in-the-catalogue
  (is (some? (registry/handler-for "unsubscribe"))))

(deftest build-form-routes-runtime-ns-with-origin
  (let [form (u/build-form "sub-42")]
    (is (.includes form "day8.re-frame2-causa.runtime/unsubscribe!"))
    (is (.includes form ":sub-id \"sub-42\""))
    (is (.includes form ":causa-mcp"))))

(deftest missing-sub-id-short-circuits
  (async done
    (-> (u/unsubscribe-tool (atom {}) #js {})
        (.then (fn [r]
                 (is (true? (j/get r :isError)))
                 (is (= :missing-sub-id (:reason (payload-of r))))
                 (done))))))

(deftest blank-sub-id-short-circuits
  (async done
    (-> (u/unsubscribe-tool (atom {}) #js {"sub-id" ""})
        (.then (fn [r]
                 (is (= :missing-sub-id (:reason (payload-of r))))
                 (done))))))

(deftest shape-envelope-merges-runtime-ack
  (let [shaped (u/shape-envelope {:ok? true :existed? true} "sub-1")]
    (is (true? (:ok? shaped)))
    (is (= "sub-1" (:sub-id shaped)))
    (is (true? (:existed? shaped)))))

(deftest shape-envelope-idempotent-closed-sub
  (testing "re-call for already-closed sub-id returns :existed? false"
    (let [shaped (u/shape-envelope {:ok? true :existed? false} "sub-stale")]
      (is (true? (:ok? shaped)))
      (is (false? (:existed? shaped))))))

(deftest happy-path-via-stubs
  (async done
    (stub-runtime! {:ok? true :sub-id "sub-1" :existed? true})
    (-> (u/unsubscribe-tool (atom {}) #js {"sub-id" "sub-1"})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (true? (:ok? p)))
                   (is (= "sub-1" (:sub-id p)))
                   (is (true? (:existed? p))))
                 (done))))))

(deftest probe-rejection-surfaces-runtime-not-preloaded
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (js/Promise.reject
              (ex-info "causa runtime not preloaded"
                       {:reason :runtime-not-preloaded
                        :hint   "setup-hint"}))))
    (-> (u/unsubscribe-tool (atom {}) #js {"sub-id" "sub-1"})
        (.then (fn [r]
                 (is (= :runtime-not-preloaded (:reason (payload-of r))))
                 (done))))))
