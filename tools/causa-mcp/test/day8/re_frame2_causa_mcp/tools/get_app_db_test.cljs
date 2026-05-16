(ns day8.re-frame2-causa-mcp.tools.get-app-db-test
  "Unit tests for the T-Insp-3 tool `get-app-db` (rf2-8xzoe.16)."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]
            [day8.re-frame2-causa-mcp.tools :as tools]
            [day8.re-frame2-causa-mcp.tools.get-app-db :as gad]))

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
  (is (fn? gad/get-app-db-tool))
  (is (fn? gad/shape-envelope))
  (is (= "get-app-db" (:name gad/descriptor)))
  (is (= 64 gad/summary-keys-cap)))

(deftest registered
  (is (some? (registry/handler-for "get-app-db"))))

(deftest build-form-addresses-runtime
  (let [form (gad/build-form {:include-sensitive? false :include-large? false})]
    (is (.includes form "day8.re-frame2-causa.runtime/get-app-db"))
    (is (.includes form ":causa-mcp"))))

(deftest shape-envelope-summary-mode-default-map
  (let [runtime-env {:ok? true :frame :rf/default :path []
                     :value {:cart {:items []} :user {:id 1} :nav :home}}
        shaped      (gad/shape-envelope runtime-env {:mode :summary})]
    (is (true? (:ok? shaped)))
    (is (= :summary (:mode shaped)))
    (is (= :map (-> shaped :value :rf.mcp/summary :type)))
    (is (= 3 (-> shaped :value :rf.mcp/summary :count)))
    (is (= #{:cart :user :nav}
           (set (-> shaped :value :rf.mcp/summary :top-keys))))))

(deftest shape-envelope-summary-mode-truncates-large-key-list
  (let [big-map     (zipmap (map #(keyword (str "k-" %)) (range 100))
                            (repeat :v))
        runtime-env {:ok? true :frame :rf/default :path [] :value big-map}
        shaped      (gad/shape-envelope runtime-env {:mode :summary})]
    (is (= 100 (-> shaped :value :rf.mcp/summary :count)))
    (is (= 64  (count (-> shaped :value :rf.mcp/summary :top-keys))))
    (is (true? (-> shaped :value :rf.mcp/summary :keys-truncated?)))))

(deftest shape-envelope-summary-mode-on-vector
  (let [runtime-env {:ok? true :frame :rf/default :path [:items]
                     :value [{:a 1} {:b 2} {:c 3}]}
        shaped      (gad/shape-envelope runtime-env {:mode :summary})]
    (is (= :vector (-> shaped :value :rf.mcp/summary :type)))
    (is (= 3 (-> shaped :value :rf.mcp/summary :count)))))

(deftest shape-envelope-summary-mode-scalar-passes-through
  (let [runtime-env {:ok? true :frame :rf/default :path [:nav]
                     :value :home}
        shaped      (gad/shape-envelope runtime-env {:mode :summary})]
    (is (= :home (:value shaped))
        "scalars ride through unchanged in summary mode")))

(deftest shape-envelope-full-mode
  (let [v           {:cart {:items [{:sku "A"}]}}
        runtime-env {:ok? true :frame :rf/default :path [] :value v}
        shaped      (gad/shape-envelope runtime-env {:mode :full})]
    (is (= :full (:mode shaped)))
    (is (= v (:value shaped)))))

(deftest shape-envelope-counts-elision-markers
  (let [runtime-env {:ok? true :frame :rf/default :path []
                     :value {:user {:rf.size/large-elided {:bytes 100000}}
                             :nav  :home}}
        shaped      (gad/shape-envelope runtime-env {:mode :full})]
    (is (= 1 (:elided-large shaped)))))

(deftest shape-envelope-runtime-failure-passthrough
  (let [runtime-env {:ok? false :reason :no-frame-resolved}]
    (is (= runtime-env (gad/shape-envelope runtime-env {:mode :summary})))))

(deftest invalid-mode-short-circuits
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (is false "should not have reached probe")
            (js/Promise.resolve nil)))
    (-> (gad/get-app-db-tool (atom {}) #js {"mode" "verbose"})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :invalid-mode (:reason payload))))
                 (done))))))

(deftest dispatcher-overflow-path
  (async done
    (let [runtime-env {:ok? true :frame :rf/default :path []
                       :value {:huge (apply str (repeat 10000 \x))}}]
      (stub-runtime! runtime-env)
      (-> (tools/invoke (atom {}) "get-app-db"
                        #js {"mode" "full" "max-tokens" 500} nil)
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
    (-> (gad/get-app-db-tool (atom {}) #js {})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :runtime-not-preloaded (:reason payload))))
                 (done))))))
