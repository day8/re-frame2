(ns day8.re-frame2-causa-mcp.tools.list-subscriptions-test
  "Unit tests for the T-Stream-3 tool `list-subscriptions` (rf2-8xzoe.28)."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.tools.list-subscriptions :as ls]))

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
  (is (fn? ls/list-subscriptions-tool))
  (is (fn? ls/build-form))
  (is (fn? ls/shape-envelope))
  (is (= "list-subscriptions" (:name ls/descriptor))))

(deftest registered-in-the-catalogue
  (is (some? (registry/handler-for "list-subscriptions"))))

(deftest build-form-routes-runtime-ns-with-origin
  (let [form (ls/build-form {:topic :trace})]
    (is (.includes form "day8.re-frame2-causa.runtime/list-subscriptions"))
    (is (.includes form ":topic :trace"))
    (is (.includes form ":causa-mcp"))))

(deftest shape-envelope-happy-path
  (let [shaped (ls/shape-envelope
                 {:ok? true
                  :subs [{:id "s-1" :topic :trace :filter {} :origin :causa-mcp :created-at 0}
                         {:id "s-2" :topic :epoch :filter {} :origin :causa-mcp :created-at 100}]
                  :count 2})]
    (is (true? (:ok? shaped)))
    (is (= 2 (:count shaped)))
    (is (= 2 (count (:subs shaped))))))

(deftest shape-envelope-empty-subs
  (let [shaped (ls/shape-envelope {:ok? true :subs [] :count 0})]
    (is (= 0 (:count shaped)))
    (is (= [] (:subs shaped)))))

(deftest shape-envelope-failure-passthrough
  (let [env {:ok? false :reason :no-such-thing :hint "..."}]
    (is (= env (ls/shape-envelope env)))))

(deftest shape-envelope-defensive-elision-counter
  (let [shaped (ls/shape-envelope
                 {:ok? true
                  :subs [{:id "s-1" :topic :trace
                          :filter {:big {:rf.size/large-elided {:bytes 1000}}}}]
                  :count 1})]
    (is (= 1 (:elided-large shaped)))))

(deftest happy-path-via-stubs
  (async done
    (stub-runtime! {:ok? true
                    :subs [{:id "s-1" :topic :trace :filter {} :origin :causa-mcp :created-at 0}]
                    :count 1})
    (-> (ls/list-subscriptions-tool (atom {}) #js {})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (true? (:ok? p)))
                   (is (= 1 (:count p))))
                 (done))))))

(deftest filter-args-route-through
  (async done
    (stub-runtime! {:ok? true :subs [] :count 0})
    (set! nrepl/cljs-eval-value
          (fn [_ _ form]
            (is (.includes form ":topic :trace"))
            (is (.includes form ":sub-id \"s-1\""))
            (js/Promise.resolve {:ok? true :subs [] :count 0})))
    (-> (ls/list-subscriptions-tool (atom {})
                                    #js {"topic" "trace" "sub-id" "s-1"})
        (.then (fn [_] (done))))))

(deftest probe-rejection-surfaces-runtime-not-preloaded
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (js/Promise.reject
              (ex-info "causa runtime not preloaded"
                       {:reason :runtime-not-preloaded
                        :hint   "setup-hint"}))))
    (-> (ls/list-subscriptions-tool (atom {}) #js {})
        (.then (fn [r]
                 (is (= :runtime-not-preloaded (:reason (payload-of r))))
                 (done))))))
