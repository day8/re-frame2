(ns day8.re-frame2-causa-mcp.tools.get-machine-state-test
  "Unit tests for the T-Insp-5 tool `get-machine-state` (rf2-8xzoe.18)."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]
            [day8.re-frame2-causa-mcp.tools :as tools]
            [day8.re-frame2-causa-mcp.tools.get-machine-state :as gms]))

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

(def ^:private sample-state
  {:initial-state :idle
   :tags #{:cart}
   :transitions {:idle {:cart/add :adding}
                 :adding {:add/done :idle}}})

(deftest public-surface
  (is (fn? gms/get-machine-state-tool))
  (is (fn? gms/shape-envelope))
  (is (= "get-machine-state" (:name gms/descriptor))))

(deftest registered
  (is (some? (registry/handler-for "get-machine-state"))))

(deftest build-form-addresses-runtime
  (let [form (gms/build-form {:machine-id :checkout/fsm
                              :include-sensitive? false
                              :include-large?     false})]
    (is (.includes form "day8.re-frame2-causa.runtime/get-machine-state"))
    (is (.includes form ":causa-mcp"))
    (is (.includes form ":machine-id :checkout/fsm"))))

(deftest shape-envelope-summary-mode-default
  (let [runtime-env {:ok? true :frame :rf/default :machine-id :checkout/fsm
                     :state sample-state}
        shaped      (gms/shape-envelope runtime-env {:mode :summary :path nil})]
    (is (true? (:ok? shaped)))
    (is (= :summary (:mode shaped)))
    (is (= :idle (-> shaped :state :initial-state)))
    (is (= [:adding :idle] (-> shaped :state :state-names)))
    (is (nil? (-> shaped :state :transitions))
        "summary mode strips the heavy transitions detail")))

(deftest shape-envelope-full-mode
  (let [runtime-env {:ok? true :frame :rf/default :machine-id :checkout/fsm
                     :state sample-state}
        shaped      (gms/shape-envelope runtime-env {:mode :full :path nil})]
    (is (= :full (:mode shaped)))
    (is (= sample-state (:state shaped))
        "full mode passes the entire spec through unchanged")))

(deftest shape-envelope-path-slice
  (let [runtime-env {:ok? true :frame :rf/default :machine-id :checkout/fsm
                     :state sample-state}
        shaped      (gms/shape-envelope runtime-env
                                        {:mode :full :path [:transitions :idle]})]
    (is (= {:cart/add :adding} (:state shaped)))
    (is (= [:transitions :idle] (:path shaped)))))

(deftest shape-envelope-counts-elision-markers
  (let [runtime-env {:ok? true :frame :rf/default :machine-id :huge/fsm
                     :state {:initial-state :idle
                             :spec {:rf.size/large-elided {:bytes 102400}}}}
        shaped      (gms/shape-envelope runtime-env {:mode :full :path nil})]
    (is (= 1 (:elided-large shaped)))))

(deftest shape-envelope-runtime-failure-passthrough
  (let [runtime-env {:ok? false :reason :no-such-machine
                     :frame :rf/default :machine-id :missing/fsm
                     :registered [:checkout/fsm :tour/fsm]}]
    (is (= runtime-env (gms/shape-envelope runtime-env {:mode :summary :path nil})))))

(deftest missing-machine-id-short-circuits
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (is false "should not have reached the probe")
            (js/Promise.resolve nil)))
    (-> (gms/get-machine-state-tool (atom {}) #js {})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :missing-machine-id (:reason payload))))
                 (done))))))

(deftest invalid-mode-short-circuits
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (is false "should not have reached the probe")
            (js/Promise.resolve nil)))
    (-> (gms/get-machine-state-tool (atom {})
                                    #js {"machine-id" "checkout/fsm"
                                         "mode" "verbose"})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :invalid-mode (:reason payload))))
                 (done))))))

(deftest dispatcher-overflow-path
  (async done
    (let [runtime-env {:ok? true :frame :rf/default :machine-id :huge/fsm
                       :state {:initial-state :idle
                               :spec (apply str (repeat 10000 \x))}}]
      (stub-runtime! runtime-env)
      (-> (tools/invoke (atom {}) "get-machine-state"
                        #js {"machine-id" "huge/fsm"
                             "mode" "full"
                             "max-tokens" 500} nil)
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
              (ex-info "preload missing"
                       {:reason :runtime-not-preloaded :hint "h"}))))
    (-> (gms/get-machine-state-tool (atom {})
                                    #js {"machine-id" "x"})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :runtime-not-preloaded (:reason payload))))
                 (done))))))
