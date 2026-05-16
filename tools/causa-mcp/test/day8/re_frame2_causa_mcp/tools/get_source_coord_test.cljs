(ns day8.re-frame2-causa-mcp.tools.get-source-coord-test
  "Unit tests for the T-Insp-9 tool `get-source-coord` (rf2-8xzoe.22)."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]
            [day8.re-frame2-causa-mcp.tools :as tools]
            [day8.re-frame2-causa-mcp.tools.get-source-coord :as gsc]))

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
  (is (fn? gsc/get-source-coord-tool))
  (is (fn? gsc/shape-envelope))
  (is (= "get-source-coord" (:name gsc/descriptor))))

(deftest registered
  (is (some? (registry/handler-for "get-source-coord"))))

(deftest build-form-addresses-runtime
  (let [form (gsc/build-form {:kind :event :id :cart/add})]
    (is (.includes form "day8.re-frame2-causa.runtime/get-source-coord"))
    (is (.includes form ":causa-mcp"))
    (is (.includes form ":kind :event"))
    (is (.includes form ":id :cart/add"))))

(deftest shape-envelope-happy-path
  (let [runtime-env {:ok? true
                     :kind :event :id :cart/add
                     :source-coord {:ns "my.app.cart"
                                    :line 42 :column 3
                                    :file "src/my/app/cart.cljs"}}
        shaped      (gsc/shape-envelope runtime-env)]
    (is (true? (:ok? shaped)))
    (is (= :event (:kind shaped)))
    (is (= "my.app.cart" (-> shaped :source-coord :ns)))
    (is (nil? (:elided-large shaped)))))

(deftest shape-envelope-no-source-coord
  (let [runtime-env {:ok? false :reason :no-source-coord
                     :kind :event :id :cart/no-source}
        shaped      (gsc/shape-envelope runtime-env)]
    (is (= runtime-env shaped))))

(deftest missing-kind-arg-short-circuits-before-runtime-call
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (is false "should not have reached the probe — args were invalid")
            (js/Promise.resolve nil)))
    (-> (gsc/get-source-coord-tool (atom {}) #js {"id" "cart/add"})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :missing-kind (:reason payload))))
                 (done))))))

(deftest missing-id-arg-short-circuits-before-runtime-call
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (is false "should not have reached the probe — args were invalid")
            (js/Promise.resolve nil)))
    (-> (gsc/get-source-coord-tool (atom {}) #js {"kind" "event"})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :missing-id (:reason payload))))
                 (done))))))

(deftest dispatcher-overflow-path
  (async done
    (let [;; Pad the source-coord with garbage to force overflow.
          runtime-env {:ok? true :kind :event :id :a
                       :source-coord {:ns (apply str (repeat 10000 \x))
                                      :line 1 :column 1
                                      :file "f"}}]
      (stub-runtime! runtime-env)
      (-> (tools/invoke (atom {}) "get-source-coord"
                        #js {"kind" "event" "id" "a"
                             "max-tokens" 500} nil)
          (.then (fn [^js result]
                   (let [payload (-> (j/get result :content) (aget 0)
                                     (j/get :text) edn/read-string)]
                     (is (contains? payload :rf.mcp/overflow))
                     (is (contains? token-cap/hint-vocabulary
                                    (get-in payload [:rf.mcp/overflow :hint]))))
                   (done)))))))

(deftest probe-rejection-surfaces-runtime-not-preloaded
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (js/Promise.reject
              (ex-info "preload missing"
                       {:reason :runtime-not-preloaded :hint "h"}))))
    (-> (gsc/get-source-coord-tool (atom {})
                                   #js {"kind" "event" "id" "a"})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :runtime-not-preloaded (:reason payload))))
                 (done))))))
