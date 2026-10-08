(ns re-frame2-pair-mcp.read-sub-test
  "Unit tests for the read-sub tool — the validated one-shot subscription
  read. The `sub` arg must be an EDN vector (host-form source never reaches
  the runtime), and a runtime `:ok? false` rides back as isError, never a
  silent nil success."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.read-sub :as read-sub]))

;; Stubs are installed by bare `set!` and restored by the fixture, so a late
;; restore cannot clobber a neighbouring test's stub.
(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn [] (set! nrepl/cljs-eval-value pristine-eval))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(deftest rejects-anything-but-a-sub-vector
  ;; No stub is installed: a parse that let these through would reach the
  ;; real socket and fail with a different reason.
  (async done
    (-> (js/Promise.all
          (into-array
            (mapv (fn [[input reason parsed-type]]
                    (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub input})
                        (.then (fn [r]
                                 (let [edn (tu/extract-edn r)]
                                   (is (= [true reason parsed-type]
                                          [(tu/error? r) (:reason edn) (:parsed-type edn)])
                                       input))))))
                  [["(rf/subscribe [:pwn])" :not-a-sub-vector :list]
                   [":current-user"         :not-a-sub-vector :keyword]
                   ["[:foo"                 :invalid-sub-edn  nil]])))
        (.then (fn [_] (done))))))

(defn- stub-eval!
  "Answer the preload probe and the raw-state signal directly; record the
  read-sub! form into `captured*` (may be nil) and answer it with `canned`."
  [captured* canned]
  (let [respond (fn [form]
                  (cond
                    (and (string? form) (re-find #"__re_frame2_pair_runtime" form))
                    (js/Promise.resolve true)

                    (and (string? form) (re-find #"configure-raw-state!" form))
                    (js/Promise.resolve nil)

                    :else
                    (do (when (and captured* (string? form) (re-find #"read-sub!" form))
                          (reset! captured* form))
                        (js/Promise.resolve canned))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

(deftest accepts-edn-vector-and-calls-read-sub
  (async done
    (let [captured (atom nil)]
      (stub-eval! captured {:ok? true :query-v [:current-user] :frame :rf/default :value {:id 42}})
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:current-user]"})
          (.then (fn [r]
                   (is (str/includes? @captured "project-egress")
                       "the value is projected server-side by default")
                   (is (= {:ok? true :query-v [:current-user] :value {:id 42} :elision true}
                          (select-keys (tu/extract-edn r) [:ok? :query-v :value :elision])))
                   (done)))))))

(deftest unknown-sub-id-surfaces-as-error-with-nearest
  ;; A typo'd sub-id must not silently subscribe and answer nil.
  (async done
    (let [runtime-result {:ok? false :reason :unknown-id :kind :sub
                          :id :current-userr :query-v [:current-userr]
                          :nearest [:current-user] :subscribed? false
                          :hint "unknown :sub :current-userr; did you mean :current-user?"}]
      (stub-eval! nil runtime-result)
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:current-userr]"})
          (.then (fn [r]
                   (is (= [true runtime-result] [(tu/error? r) (tu/extract-edn r)]))
                   (done)))))))

(deftest frame-arg-routes-to-named-frame
  ;; The colon-prefixed frame coerces to the well-formed :rf/xray, not the
  ;; malformed ::rf/xray, and rides as read-sub!'s second arg.
  (async done
    (let [captured (atom nil)]
      (stub-eval! captured {:ok? true :query-v [:state] :frame :rf/xray :value 1})
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:state]" :frame ":rf/xray"})
          (.then (fn [_]
                   (is (re-find #"read-sub! \(quote \[:state\]\) :rf/xray" @captured))
                   (done)))))))
