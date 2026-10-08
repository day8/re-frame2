(ns re-frame2-pair-mcp.describe-image-test
  "Unit tests for the describe-image tool — the EP-0023 Use-Case 7 read
  over a frame's resolved image generation. The tool emits
  `(re-frame2-pair.runtime/describe-image <opts>)` and forwards the
  runtime's envelope through `probe/map-envelope-result`, whose arms the
  conformance corpus pins; the descriptor is held by `check:descriptors`."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [cljs.reader]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.describe-image :as di]))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn [] (set! nrepl/cljs-eval-value pristine-eval))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- stub-eval!
  "Answer the preload probe directly; record every other form into
  `captured*` and answer it with `canned`."
  [captured* canned]
  (let [respond (fn [form]
                  (if (and (string? form) (re-find #"__re_frame2_pair_runtime" form))
                    (js/Promise.resolve true)
                    (do (reset! captured* form)
                        (js/Promise.resolve canned))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

(deftest emits-the-describe-image-runtime-form
  ;; Omitted opts stay out of the form, so the runtime resolves the
  ;; operating frame; `include-ns` coerces to the runtime's :include-ns?.
  (async done
    (-> (reduce
          (fn [p [args expected]]
            (.then p (fn [_]
                       (let [captured (atom nil)]
                         (stub-eval! captured {:ok? true :frame :main :images [] :kinds [] :counts {}})
                         (.then (di/describe-image-tool (fresh-conn) (tu/args->js args))
                                (fn [_]
                                  (is (= expected (cljs.reader/read-string @captured))
                                      (pr-str args))))))))
          (js/Promise.resolve nil)
          [[{} '(re-frame2-pair.runtime/describe-image {})]
           [{:frame ":main" :include-ns "true"}
            '(re-frame2-pair.runtime/describe-image {:frame :main :include-ns? true})]])
        (.catch (fn [e] (is false (str "drive rejected: " e))))
        (.then (fn [_] (done))))))
