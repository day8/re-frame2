(ns day8.re-frame2-causa-mcp.tools.get-epoch-history-test
  "Unit tests for the T-Insp-2 tool `get-epoch-history` (rf2-8xzoe.15)."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]
            [day8.re-frame2-causa-mcp.tools :as tools]
            [day8.re-frame2-causa-mcp.tools.get-epoch-history :as geh]))

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

(defn- mk-epochs
  "Build n synthetic epoch records with monotonic ids."
  [n]
  (vec (for [i (range n)]
         {:epoch-id (str "e-" i)
          :event-id (keyword "test" (str "event-" i))
          :db-after {:counter i}})))

(deftest public-surface
  (is (fn? geh/get-epoch-history-tool))
  (is (fn? geh/shape-envelope))
  (is (fn? geh/encode-cursor))
  (is (fn? geh/decode-cursor))
  (is (= "get-epoch-history" (:name geh/descriptor)))
  (is (= 50 geh/default-limit)))

(deftest registered
  (is (some? (registry/handler-for "get-epoch-history"))))

(deftest build-form-addresses-runtime
  (let [form (geh/build-form {:include-sensitive? false :include-large? false})]
    (is (.includes form "day8.re-frame2-causa.runtime/get-epoch-history"))
    (is (.includes form ":causa-mcp"))))

(deftest cursor-round-trip
  (let [m {:after-id "e-42"}
        s (geh/encode-cursor m)]
    (is (string? s))
    (is (= m (geh/decode-cursor s)))))

(deftest decode-cursor-nil-and-blank
  (is (nil? (geh/decode-cursor nil)))
  (is (nil? (geh/decode-cursor ""))))

(deftest decode-cursor-malformed
  (is (= :day8.re-frame2-causa-mcp.tools.get-epoch-history/malformed
         (geh/decode-cursor "not-base64-EDN!!!"))))

(deftest shape-envelope-first-page
  (let [runtime-env {:ok? true :frame :rf/default :epochs (mk-epochs 60)}
        shaped      (geh/shape-envelope runtime-env
                                        {:cursor-in nil
                                         :limit 50
                                         :include-sensitive? false})]
    (is (true? (:ok? shaped)))
    (is (= 50 (:count shaped)))
    (is (= 60 (:total shaped)))
    (is (string? (:next-cursor shaped))
        "first page of 60-epoch history has a next-cursor")
    (let [resume (geh/decode-cursor (:next-cursor shaped))]
      (is (= "e-49" (:after-id resume))))))

(deftest shape-envelope-resume-with-cursor
  (let [all          (mk-epochs 60)
        runtime-env  {:ok? true :frame :rf/default :epochs all}
        cursor-in    {:after-id "e-49"}
        shaped       (geh/shape-envelope runtime-env
                                         {:cursor-in cursor-in
                                          :limit 50
                                          :include-sensitive? false})]
    (is (= 10 (:count shaped)))
    (is (nil? (:next-cursor shaped))
        "tail page has no next-cursor")
    (is (= "e-50" (-> shaped :epochs first :epoch-id)))))

(deftest shape-envelope-stale-cursor
  (let [runtime-env {:ok? true :frame :rf/default :epochs (mk-epochs 10)}
        cursor-in   {:after-id "e-999"}
        shaped      (geh/shape-envelope runtime-env
                                        {:cursor-in cursor-in
                                         :limit 50
                                         :include-sensitive? false})]
    (is (false? (:ok? shaped)))
    (is (= :cursor-stale (:reason shaped)))
    (is (= "e-999" (:requested-id shaped)))))

(deftest shape-envelope-drops-sensitive-epochs
  (let [runtime-env {:ok? true :frame :rf/default
                     :epochs [{:epoch-id "e-1"}
                              {:epoch-id "e-2" :sensitive? true}
                              {:epoch-id "e-3"}]}
        shaped      (geh/shape-envelope runtime-env
                                        {:cursor-in nil
                                         :limit 50
                                         :include-sensitive? false})]
    (is (= 2 (:count shaped)))
    (is (= 1 (:dropped-sensitive shaped)))))

(deftest shape-envelope-counts-elision-markers
  (let [runtime-env {:ok? true :frame :rf/default
                     :epochs [{:epoch-id "e-1"
                               :db-after {:rf.size/large-elided {:bytes 102400}}}
                              {:epoch-id "e-2"
                               :db-after {:counter 1}}]}
        shaped      (geh/shape-envelope runtime-env
                                        {:cursor-in nil
                                         :limit 50
                                         :include-sensitive? false})]
    (is (= 1 (:elided-large shaped)))))

(deftest shape-envelope-runtime-failure
  (let [runtime-env {:ok? false :reason :no-frame-resolved
                     :hint "Pass :frame :foo or register at least one frame."}]
    (is (= runtime-env (geh/shape-envelope runtime-env
                                           {:cursor-in nil :limit 50
                                            :include-sensitive? false})))))

(deftest malformed-cursor-arg-short-circuits
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (is false "should not have reached probe")
            (js/Promise.resolve nil)))
    (-> (geh/get-epoch-history-tool (atom {})
                                    #js {"cursor" "garbage-not-base64"})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :cursor-malformed (:reason payload))))
                 (done))))))

(deftest dispatcher-overflow-path
  (async done
    (let [runtime-env {:ok? true :frame :rf/default
                       :epochs [{:epoch-id "huge"
                                 :db-after {:big (apply str (repeat 10000 \x))}}]}]
      (stub-runtime! runtime-env)
      (-> (tools/invoke (atom {}) "get-epoch-history"
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
    (-> (geh/get-epoch-history-tool (atom {}) #js {})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :runtime-not-preloaded (:reason payload))))
                 (done))))))
