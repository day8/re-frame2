(ns day8.re-frame2-causa-mcp.tools.get-app-db-diff-test
  "Unit tests for the T-Insp-4 tool `get-app-db-diff` (rf2-8xzoe.17)."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]
            [day8.re-frame2-causa-mcp.tools :as tools]
            [day8.re-frame2-causa-mcp.tools.get-app-db-diff :as gadd]))

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
  (is (fn? gadd/get-app-db-diff-tool))
  (is (fn? gadd/shape-envelope))
  (is (fn? gadd/changed-paths))
  (is (= "get-app-db-diff" (:name gadd/descriptor))))

(deftest registered
  (is (some? (registry/handler-for "get-app-db-diff"))))

(deftest build-form-addresses-runtime
  (let [form (gadd/build-form {:epoch-id "e-1"
                               :include-sensitive? false :include-large? false})]
    (is (.includes form "day8.re-frame2-causa.runtime/get-app-db-diff"))
    (is (.includes form ":causa-mcp"))))

;; ---------------------------------------------------------------------------
;; changed-paths — pure projection.
;; ---------------------------------------------------------------------------

(deftest changed-paths-empty-diff
  (let [r (gadd/changed-paths {:a 1} {:a 1})]
    (is (= [] (:added r)))
    (is (= [] (:removed r)))
    (is (= [] (:changed r)))
    (is (= {:added 0 :removed 0 :changed 0} (:counts r)))))

(deftest changed-paths-detects-top-level-add-remove
  (let [r (gadd/changed-paths {:a 1 :b 2} {:b 2 :c 3})]
    (is (= [[:c]] (:added r)))
    (is (= [[:a]] (:removed r)))
    (is (= [] (:changed r)))))

(deftest changed-paths-detects-top-level-change
  (let [r (gadd/changed-paths {:a 1} {:a 2})]
    (is (= [] (:added r)))
    (is (= [] (:removed r)))
    (is (= [[:a]] (:changed r)))))

(deftest changed-paths-recurses-into-nested-maps
  (let [r (gadd/changed-paths
            {:cart {:items [] :total 0}
             :user {:id 1}}
            {:cart {:items [{:sku "A"}] :total 100}
             :user {:id 1}})]
    (is (= [] (:added r)))
    (is (= [] (:removed r)))
    (is (= #{[:cart :items] [:cart :total]} (set (:changed r))))))

(deftest changed-paths-stops-at-scalar-boundary
  (let [r (gadd/changed-paths
            {:items [1 2 3]}
            {:items [4 5 6]})]
    (is (= [[:items]] (:changed r))
        "a changed vector is one :changed entry, not per-element")))

(deftest changed-paths-counts-include-nested
  (let [r (gadd/changed-paths
            {:a {:x 1 :y 2 :z 3}}
            {:a {:x 1 :y 99 :z 3 :w 4}})]
    (is (= {:added 1 :removed 0 :changed 1} (:counts r)))
    (is (= [[:a :w]] (:added r)))
    (is (= [[:a :y]] (:changed r)))))

;; ---------------------------------------------------------------------------
;; shape-envelope.
;; ---------------------------------------------------------------------------

(deftest shape-envelope-changed-paths-default
  (let [runtime-env {:ok? true :frame :rf/default :epoch-id "e-42"
                     :diff {:before {:a 1}
                            :after  {:a 2 :b 3}}}
        shaped      (gadd/shape-envelope runtime-env {:mode :changed-paths})]
    (is (true? (:ok? shaped)))
    (is (= :changed-paths (:mode shaped)))
    (is (= [[:b]] (:added (:diff shaped))))
    (is (= [[:a]] (:changed (:diff shaped))))
    (is (= 1 (:added (:counts (:diff shaped)))))
    (is (nil? (:elided-large shaped)))))

(deftest shape-envelope-nested-mode
  (let [runtime-env {:ok? true :frame :rf/default :epoch-id "e-42"
                     :diff {:before {:a 1}
                            :after  {:a 2}}}
        shaped      (gadd/shape-envelope runtime-env {:mode :nested})]
    (is (= :nested (:mode shaped)))
    (is (= {:a 1} (-> shaped :diff :before)))
    (is (= {:a 2} (-> shaped :diff :after)))))

(deftest shape-envelope-counts-elision-in-nested-mode
  (let [runtime-env {:ok? true :frame :rf/default :epoch-id "e-42"
                     :diff {:before {:secret {:rf.size/large-elided
                                              {:bytes 10000}}}
                            :after  {:secret {:rf.size/large-elided
                                              {:bytes 10001}}}}}
        shaped      (gadd/shape-envelope runtime-env {:mode :nested})]
    (is (= 2 (:elided-large shaped))
        ":nested mode surfaces both markers from the before+after")))

(deftest shape-envelope-runtime-failure
  (let [runtime-env {:ok? false :reason :no-such-epoch :epoch-id "missing"}]
    (is (= runtime-env (gadd/shape-envelope runtime-env
                                            {:mode :changed-paths})))))

(deftest missing-epoch-id-short-circuits
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (is false "should not have reached probe")
            (js/Promise.resolve nil)))
    (-> (gadd/get-app-db-diff-tool (atom {}) #js {})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :missing-epoch-id (:reason payload))))
                 (done))))))

(deftest invalid-mode-short-circuits
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (is false "should not have reached probe")
            (js/Promise.resolve nil)))
    (-> (gadd/get-app-db-diff-tool (atom {})
                                   #js {"epoch-id" "e-1"
                                        "mode" "verbose"})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :invalid-mode (:reason payload))))
                 (done))))))

(deftest dispatcher-overflow-path-nested-mode
  (async done
    (let [runtime-env {:ok? true :frame :rf/default :epoch-id "e-1"
                       :diff {:before {:huge (apply str (repeat 10000 \x))}
                              :after  {:huge (apply str (repeat 10001 \x))}}}]
      (stub-runtime! runtime-env)
      (-> (tools/invoke (atom {}) "get-app-db-diff"
                        #js {"epoch-id" "e-1"
                             "mode" "nested"
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
              (ex-info "h" {:reason :runtime-not-preloaded :hint "h"}))))
    (-> (gadd/get-app-db-diff-tool (atom {})
                                   #js {"epoch-id" "e-1"})
        (.then (fn [^js result]
                 (let [payload (-> (j/get result :content) (aget 0)
                                   (j/get :text) edn/read-string)]
                   (is (= :runtime-not-preloaded (:reason payload))))
                 (done))))))
