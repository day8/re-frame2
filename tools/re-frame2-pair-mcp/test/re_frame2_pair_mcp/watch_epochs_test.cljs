(ns re-frame2-pair-mcp.watch-epochs-test
  "Unit tests for the `watch-epochs` MCP tool: the empty-result advisory
  and the aged-out cursor-stale path, driven through the real tool.

  An empty `:matches` has three causes the advisory tells apart: a
  genuinely empty history (no advisory), a `:since-id` at the head
  (`:no-events-since-id`, pinned in `epoch_frame_test`), and a `:pred`
  that excluded everything since the id (`:pred-excludes-history`)."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.watch-epochs :as we]))

(defn- with-substr-eval!
  [script body-fn]
  (let [orig nrepl/cljs-eval-value
        match (fn [form-str]
                (some (fn [[k v]]
                        (when (or (= k :default)
                                  (and (string? k) (re-find (re-pattern k) form-str)))
                          v))
                      script))
        stub (fn
               ([_conn _build-id form-str]
                (js/Promise.resolve (match form-str)))
               ([_conn _build-id form-str _opts]
                (js/Promise.resolve (match form-str))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- watch!
  "Run watch-epochs on `args` against a runtime whose poll answers `answer`."
  [args answer]
  (with-substr-eval! [["__re_frame2_pair_runtime" true] [:default answer]]
    #(we/watch-epochs-tool nil (tu/args->js args))))

(deftest empty-history-no-advisory
  (async done
    (-> (watch! {} {:matches [] :id-aged-out? false :history-count 0 :since-count 0 :remaining 0})
        (.then (fn [result]
                 (let [edn (tu/extract-edn result)]
                   (is (= 0 (:count edn)))
                   (is (not (contains? edn :advisory)))
                   (done)))))))

(deftest pred-filters-all-events-since-id
  (async done
    (-> (watch! {:since-id ":epoch-3" :pred #js {:event-id ":no/match"}}
                {:matches [] :id-aged-out? false :requested-id :epoch-3 :head-id :epoch-9
                 :history-count 9 :since-count 6 :remaining 0})
        (.then (fn [result]
                 (is (= {:reason :pred-excludes-history :epochs-in-history 9 :epochs-since-id 6}
                        (select-keys (:advisory (tu/extract-edn result))
                                     [:reason :epochs-in-history :epochs-since-id])))
                 (done))))))

(deftest json-pred-reaches-the-runtime-as-keywords-and-a-path
  ;; JSON has no keywords, and `epoch-matches?` compares ids with `=`, so a
  ;; pred sent as JSON must arrive naming keywords or it never matches.
  (async done
    (let [forms (atom [])
          orig  nrepl/cljs-eval-value
          run   (fn [form]
                  (swap! forms conj form)
                  (js/Promise.resolve (when (str/includes? form "__re_frame2_pair_runtime") true)))
          stub  (fn ([_ _ form] (run form)) ([_ _ form _] (run form)))
          quoted-pred (fn []
                        (let [form   (some #(when (str/includes? % "epoch-matches?") %) @forms)
                              marker "epoch-matches? (quote "]
                          (cljs.reader/read-string
                            (subs form (+ (str/index-of form marker) (count marker))))))]
      (set! nrepl/cljs-eval-value stub)
      (-> (we/watch-epochs-tool nil (tu/args->js {:pred #js {:event-id     ":cart/checkout"
                                                             :effects      "http"
                                                             :touches-path #js [":cart" ":items"]
                                                             :timing-ms    ">100"}}))
          (.then (fn [_]
                   (is (= {:event-id :cart/checkout :effects :http
                           :touches-path [:cart :items] :timing-ms ">100"}
                          (quoted-pred)))
                   (reset! forms [])
                   (we/watch-epochs-tool nil (tu/args->js {:pred "{:event-id :cart/checkout}"}))))
          (.then (fn [_]
                   (is (= {:event-id :cart/checkout} (quoted-pred)) "an EDN-string pred reads as EDN")))
          (.catch (fn [e] (is false (str "rejected: " (.-message e)))))
          (.then (fn [_] (tu/restore-eval! stub orig) (done)))))))

(deftest non-empty-matches-no-advisory
  (async done
    (-> (watch! {} {:matches [{:epoch-id :e1}] :id-aged-out? false :head-id :e1
                    :history-count 5 :since-count 5 :remaining 0})
        (.then (fn [result]
                 (let [edn (tu/extract-edn result)]
                   (is (= 1 (:count edn)))
                   (is (not (contains? edn :advisory)))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; An aged-out id comes back as cursor-stale, never as an empty page. A
;; cursor the codec cannot use is pinned in `cursor_pagination_test`.
;; ---------------------------------------------------------------------------

(deftest aged-out-ring-returns-cursor-stale
  (async done
    (-> (watch! {:since-id ":epoch-99"}
                {:matches [] :id-aged-out? true :requested-id :epoch-99 :head-id :epoch-149
                 :history-count 50 :since-count 0 :remaining 0})
        (.then (fn [result]
                 (is (tu/error? result))
                 (is (= {:reason       :rf.mcp/cursor-stale
                         :tool         "watch-epochs"
                         :requested-id :epoch-99
                         :head-id      :epoch-149}
                        (select-keys (tu/extract-edn result)
                                     [:reason :tool :requested-id :head-id]))
                     "the dead id and the current head ride back for a rewind")
                 (done))))))
