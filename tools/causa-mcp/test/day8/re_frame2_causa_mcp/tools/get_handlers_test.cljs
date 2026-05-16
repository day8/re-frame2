(ns day8.re-frame2-causa-mcp.tools.get-handlers-test
  "Unit tests for the T-Insp-8 tool `get-handlers` (rf2-8xzoe.21)."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]
            [day8.re-frame2-causa-mcp.tools :as tools]
            [day8.re-frame2-causa-mcp.tools.get-handlers :as gh]))

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

(deftest public-surface
  (is (fn? gh/get-handlers-tool))
  (is (fn? gh/shape-envelope))
  (is (= "get-handlers" (:name gh/descriptor))))

(deftest registered
  (is (some? (registry/handler-for "get-handlers"))))

(deftest build-form-addresses-runtime
  (let [form (gh/build-form {:include-sensitive? false :include-large? false})]
    (is (.includes form "day8.re-frame2-causa.runtime/get-handlers"))
    (is (.includes form ":causa-mcp"))))

(deftest shape-envelope-groups-by-kind
  (let [runtime-env {:ok? true
                     :handlers [{:kind :event :id :cart/add :meta {:doc "add"}}
                                {:kind :sub   :id :cart/items :meta {:doc "items"}}
                                {:kind :event :id :cart/clear :meta {:doc "clear"}}]
                     :count 3}
        shaped      (gh/shape-envelope runtime-env true)]
    (is (true? (:ok? shaped)))
    (is (= 3 (:count shaped)))
    (is (= #{:event :sub} (set (:kinds shaped))))
    (is (= 2 (count (get-in shaped [:handlers :event]))))
    (is (= 1 (count (get-in shaped [:handlers :sub]))))
    (is (= :cart/add (-> shaped :handlers :event first :id))
        "per-kind insertion order is preserved")
    (is (nil? (:elided-large shaped)))))

(deftest shape-envelope-flat-mode
  (let [runtime-env {:ok? true
                     :handlers [{:kind :event :id :a :meta {}}
                                {:kind :sub   :id :b :meta {}}]
                     :count 2}
        shaped      (gh/shape-envelope runtime-env false)]
    (is (vector? (:handlers shaped)))
    (is (= 2 (:count shaped)))
    (is (nil? (:kinds shaped))
        "flat mode omits the :kinds rollup")))

(deftest shape-envelope-counts-elision-markers
  (let [runtime-env {:ok? true
                     :handlers [{:kind :event :id :a
                                 :meta {:big {:rf.size/large-elided
                                              {:bytes 102400}}}}]
                     :count 1}
        shaped      (gh/shape-envelope runtime-env true)]
    (is (= 1 (:elided-large shaped)))))

(deftest shape-envelope-runtime-failure
  (let [runtime-env {:ok? false :reason :something-wrong :hint "..."}]
    (is (= runtime-env (gh/shape-envelope runtime-env true)))))

(deftest dispatcher-overflow-path
  (async done
    (let [runtime-env {:ok? true
                       :handlers [{:kind :event :id :huge
                                   :meta {:doc (apply str (repeat 10000 \x))}}]
                       :count 1}]
      (stub-runtime! runtime-env)
      (-> (tools/invoke (atom {}) "get-handlers"
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
                    (done)))))))

(deftest probe-rejection-surfaces-runtime-not-preloaded
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (js/Promise.reject
              (ex-info "preload missing"
                       {:reason :runtime-not-preloaded :hint "h"}))))
    (-> (gh/get-handlers-tool (atom {}) #js {})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :runtime-not-preloaded (:reason payload))))
                 (done))))))
