(ns day8.re-frame2-causa-mcp.tools.get-issues-test
  "Unit tests for the T-Insp-7 tool `get-issues` (rf2-8xzoe.20)."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]
            [day8.re-frame2-causa-mcp.tools :as tools]
            [day8.re-frame2-causa-mcp.tools.get-issues :as gi]))

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
  (is (fn? gi/get-issues-tool))
  (is (fn? gi/shape-envelope))
  (is (= "get-issues" (:name gi/descriptor)))
  (is (= #{:error :warning :all} gi/severity-vocabulary)))

(deftest registered
  (is (some? (registry/handler-for "get-issues"))))

(deftest build-form-addresses-runtime
  (let [form (gi/build-form {:include-sensitive? false :include-large? false})]
    (is (.includes form "day8.re-frame2-causa.runtime/get-issues"))
    (is (.includes form ":causa-mcp"))))

(deftest shape-envelope-all-severity-default
  (let [runtime-env {:ok? true
                     :issues [{:op-type :error   :message "boom"}
                              {:op-type :warning :message "uh"}
                              {:op-type :rf.schema/violation}]
                     :count 3}
        shaped      (gi/shape-envelope runtime-env
                                       {:severity :all
                                        :include-sensitive? false
                                        :limit 50 :offset 0})]
    (is (true? (:ok? shaped)))
    (is (= 3 (:count shaped)))
    (is (= 3 (:total shaped)))
    (is (= :all (:severity shaped)))))

(deftest shape-envelope-severity-error-narrows
  (let [runtime-env {:ok? true
                     :issues [{:op-type :error}
                              {:op-type :warning}
                              {:op-type :rf.schema/violation}
                              {:op-type :rf.hydration/mismatch}]
                     :count 4}
        shaped      (gi/shape-envelope runtime-env
                                       {:severity :error
                                        :include-sensitive? false
                                        :limit 50 :offset 0})]
    (is (= 3 (:count shaped))
        ":error keeps :error + :rf.schema/violation + :rf.hydration/mismatch")
    (is (= :error (:severity shaped)))))

(deftest shape-envelope-severity-warning-narrows
  (let [runtime-env {:ok? true
                     :issues [{:op-type :error}
                              {:op-type :warning}
                              {:op-type :warning :extra "x"}]
                     :count 3}
        shaped      (gi/shape-envelope runtime-env
                                       {:severity :warning
                                        :include-sensitive? false
                                        :limit 50 :offset 0})]
    (is (= 2 (:count shaped)))))

(deftest shape-envelope-drops-sensitive-by-default
  (let [runtime-env {:ok? true
                     :issues [{:op-type :error :message "ok"}
                              {:op-type :error :message "secret" :sensitive? true}]
                     :count 2}
        shaped      (gi/shape-envelope runtime-env
                                       {:severity :all
                                        :include-sensitive? false
                                        :limit 50 :offset 0})]
    (is (= 1 (:count shaped)))
    (is (= 1 (:dropped-sensitive shaped)))))

(deftest shape-envelope-include-sensitive-opts-back-in
  (let [runtime-env {:ok? true
                     :issues [{:op-type :error :message "ok"}
                              {:op-type :error :message "secret" :sensitive? true}]
                     :count 2}
        shaped      (gi/shape-envelope runtime-env
                                       {:severity :all
                                        :include-sensitive? true
                                        :limit 50 :offset 0})]
    (is (= 2 (:count shaped)))
    (is (nil? (:dropped-sensitive shaped)))))

(deftest shape-envelope-counts-elided-markers
  (let [runtime-env {:ok? true
                     :issues [{:op-type :error
                               :body {:rf.size/large-elided {:bytes 10000}}}
                              {:op-type :warning}]
                     :count 2}
        shaped      (gi/shape-envelope runtime-env
                                       {:severity :all
                                        :include-sensitive? false
                                        :limit 50 :offset 0})]
    (is (= 1 (:elided-large shaped)))))

(deftest shape-envelope-pagination
  (let [issues     (vec (for [i (range 10)]
                          {:op-type :warning :id i}))
        runtime-env {:ok? true :issues issues :count 10}
        shaped     (gi/shape-envelope runtime-env
                                      {:severity :all
                                       :include-sensitive? false
                                       :limit 3 :offset 4})]
    (is (= 3 (:count shaped)))
    (is (= [4 5 6] (mapv :id (:issues shaped))))))

(deftest invalid-severity-short-circuits
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (is false "should not have reached probe")
            (js/Promise.resolve nil)))
    (-> (gi/get-issues-tool (atom {}) #js {"severity" "fatal"})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :invalid-severity (:reason payload))))
                 (done))))))

(deftest dispatcher-overflow-path
  (async done
    (let [runtime-env {:ok? true
                       :issues [{:op-type :error
                                 :message (apply str (repeat 10000 \x))}]
                       :count 1}]
      (stub-runtime! runtime-env)
      (-> (tools/invoke (atom {}) "get-issues"
                        #js {"max-tokens" 500} nil)
          (.then (fn [^js result]
                   (let [payload (-> (j/get result :content) (aget 0)
                                     (j/get :text) edn/read-string)]
                     (is (contains? payload :rf.mcp/overflow))
                     (is (contains? token-cap/hint-vocabulary
                                    (get-in payload [:rf.mcp/overflow :hint]))))
                   (done)))))))

(deftest probe-rejection
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (js/Promise.reject
              (ex-info "h" {:reason :runtime-not-preloaded :hint "h"}))))
    (-> (gi/get-issues-tool (atom {}) #js {})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :runtime-not-preloaded (:reason payload))))
                 (done))))))
