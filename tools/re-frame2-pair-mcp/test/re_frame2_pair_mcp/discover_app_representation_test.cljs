(ns re-frame2-pair-mcp.discover-app-representation-test
  "discover-app's output contract.

  In the canonical EDN `:content` text every build/frame id is a full
  keyword, and the `:note` / `:hint` strings restate ids in the same colon
  form; `:structuredContent` is the documented lossy JSON projection, so
  these tests read the EDN text. Every `:ok? false` precondition failure
  rides isError."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:examples/step-deck} :resolved-build-id nil)
    conn))

(def ^:private args (tu/args->js {:build "examples/step-deck"}))

(deftest discover-app-frames-are-full-keywords-in-canonical-text
  ;; An ambiguous-frame payload with no `:app-frames`: the note restates
  ;; `:frames` themselves, in the same colon form as the value.
  (async done
    (-> (tu/with-stubbed-eval! {:ok?                        true
                                :debug-enabled?             true
                                :coord-annotation-enabled?  true
                                :frames                     [:rf/default :step-deck :rf/xray]
                                :ambiguous-frame?           true}
          (fn [] (discover-app/discover-app (fresh-conn) args)))
        (.then
          (fn [result]
            (let [edn (tu/extract-edn result)]
              (is (= [:rf/default :step-deck :rf/xray] (:frames edn)))
              (is (str/includes? (:note edn) "[:rf/default :step-deck :rf/xray]")))
            (done))))))

(deftest discover-app-precondition-failures-are-isError
  ;; A known-tool failure is never a success carrying bad news, and a build
  ;; that failed its precondition is not cached as the session default.
  (async done
    (-> (reduce
          (fn [p [health reason]]
            (.then p
                   (fn [_]
                     (let [conn (fresh-conn)]
                       (-> (tu/with-stubbed-eval! health
                             (fn [] (discover-app/discover-app conn args)))
                           (.then (fn [result]
                                    (let [edn (tu/extract-edn result)]
                                      (is (= {:isError true :ok? false :reason reason :cached nil}
                                             {:isError (tu/error? result) :ok? (:ok? edn)
                                              :reason (:reason edn) :cached (:resolved-build-id @conn)})
                                          (str reason))))))))))
          (js/Promise.resolve nil)
          [[{:ok? false :reason :rf.error/some-runtime-fault :hint "the runtime reported a fault"}
            :rf.error/some-runtime-fault]
           [{:ok? true :debug-enabled? false :frames [:rf/default]}
            :debug-disabled]
           [{:ok? true :debug-enabled? true :coord-annotation-enabled? true :frames []}
            :no-frames-registered]])
        (.then (fn [_] (done))))))
