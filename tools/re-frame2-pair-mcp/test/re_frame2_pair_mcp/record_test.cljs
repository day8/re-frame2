(ns re-frame2-pair-mcp.record-test
  "Unit tests for the signal-recorder triplet: record, read-recording and
  watch-until. Arg coercion, the data-predicate compile, and the tool
  wiring against a stubbed runtime. Sampling, dedup and teardown live in
  the preload runtime and are not exercised here."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.record :as record]
            [re-frame2-pair-mcp.tools.watch-until :as watch-until]))

(deftest parse-signals-arg-shapes
  (doseq [[in out] [[#js [#js {:focus true} #js {:dom "#c"}] [{:focus true} {:dom "#c"}]]
                    ["[{:focus true} {:app-db [:a :b]}]"    [{:focus true} {:app-db [:a :b]}]]
                    ["{:dom \"#x\"}"                        [{:dom "#x"}]]
                    ;; nil ⇒ the caller's :no-signals refusal
                    ["   "                                  nil]
                    ["(not edn"                             nil]]]
    (is (= out (record/parse-signals-arg in)) (pr-str in))))

(deftest parse-stop-arg-shapes
  ;; A malformed `stop` must not collapse to `{}`, the default window; the
  ;; unreadable case is pinned through the tool below.
  (doseq [[in out] [[nil                                             [:ok {}]]
                    [#js {:ms 1 :bogus 9}                            [:ok {:ms 1}]]
                    ["{:changes 10 :pred {:signal 0 :equals :done}}" [:ok {:changes 10
                                                                           :pred {:signal 0 :equals :done}}]]
                    ["[:ms 5000]"                                    [:err :invalid-stop-edn]]]]
    (is (= out (record/parse-stop-arg in)) (pr-str in))))

(deftest pred-source-shapes
  ;; Every caller comparand rides `(quote …)`: a hostile `:equals` list is
  ;; compared as data, never evaluated.
  (doseq [[pred src]
          [[{:signal 0 :equals '(js/alert "pwn")}
            "(fn [sample] (= (get sample 0) (quote (js/alert \"pwn\"))))"]
           [{:signal 1 :path [:id] :equals "x"}
            "(fn [sample] (= (get-in (get sample 1) (quote [:id])) (quote \"x\")))"]
           [{:signal 0 :changed true}
            "(fn [sample] (some? (get sample 0)))"]
           [{:signal 0 :contains "load"}
            "(fn [sample] (clojure.string/includes? (str (get sample 0)) \"load\"))"]
           [{:signal 2}
            "(fn [sample] (some? (get sample 2)))"]
           [nil         nil]
           ["not-a-map" nil]]]
    (is (= src (record/pred-source pred)) (pr-str pred))))

(deftest start-recording-form-shape
  ;; The signals, the stop bound and the :pred-fn slot are pinned in
  ;; data_arguments_test; the :elide-opts slot in egress_elision_test.
  (let [form (#'record/start-recording-form
               [{:focus true} {:app-db [:cart]}]
               {:ms 15000 :pred {:signal 0 :equals :done}}
               :rf/default
               2000
               "{:rf.egress/include-large? false :rf.egress/include-sensitive? false}")]
    (is (str/includes? form ":frame :rf/default"))
    (is (str/includes? form ":max-entries 2000"))))

;; ---------------------------------------------------------------------------
;; Tool wiring.
;; ---------------------------------------------------------------------------

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(deftest record-runtime-ok-false-is-iserror
  ;; `start-recording!` refuses `:ambiguous-frame` when an `:app-db` signal
  ;; needs a frame and none resolves; it must not ride as a green result.
  (async done
    (let [canned {:ok? false :reason :ambiguous-frame :operation :start-recording}]
      (-> (tu/with-stubbed-eval! canned
            #(record/record-tool (fresh-conn) #js {:signals "[{:app-db [:cart]}]"}))
          (.then (fn [r]
                   (is (tu/error? r))
                   (is (= canned (tu/extract-edn r)) "the refusal rides verbatim")
                   (done)))))))

(deftest record-tool-malformed-stop-errors-honestly
  ;; Regression: a typo'd `stop` must not start a default-window recording.
  ;; The branch fires before the runtime is touched.
  (async done
    (-> (record/record-tool (fresh-conn)
                            #js {:signals "[{:focus true}]" :stop "{:ms 5000"})
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= {:ok? false :reason :invalid-stop-edn :given "{:ms 5000"}
                        (dissoc (tu/extract-edn r) :hint)))
                 (done))))))

(deftest read-recording-missing-id-short-circuits
  (async done
    (-> (record/read-recording-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= :missing-recording-id (:reason (tu/extract-edn r))))
                 (done))))))

(deftest read-recording-drain-and-stop-ride-the-form
  (async done
    (let [seen   (atom nil)
          orig   nrepl/cljs-eval-value
          canned (js/Promise.resolve {:ok? true :recording-id "r" :status :stopped
                                      :count 0 :entries []})
          ;; Both arities spelled out: CLJS direct-arity dispatch skips a variadic fn.
          stub   (fn
                   ([_conn _build form] (reset! seen form) canned)
                   ([_conn _build form _opts] (reset! seen form) canned))]
      (set! nrepl/cljs-eval-value stub)
      (-> (record/read-recording-tool (fresh-conn)
                                      #js {:recording-id "r" :drain true :stop true})
          (.then (fn [_]
                   (is (str/includes? @seen ":drain true"))
                   (is (str/includes? @seen ":stop true"))))
          (.finally (fn []
                      (tu/restore-eval! stub orig)
                      (done)))))))

(deftest watch-until-resolves-on-held-predicate
  (async done
    (-> (tu/with-stubbed-eval! {:held? true :sample {0 :done} :t 1712}
          #(watch-until/watch-until-tool
             (fresh-conn)
             #js {:signals "[{:app-db [:upload :status]}]"
                  :pred    #js {:signal 0 :equals "done"}}))
        (.then (fn [r]
                 (is (= {:ok? true :held? true :sample {0 :done} :t 1712}
                        (dissoc (tu/extract-edn r) :elapsed-ms))
                     "the satisfying sample rides back")
                 (done))))))

(deftest watch-until-times-out-with-last-sample
  ;; The timeout is the watch's normal outcome, so it rides non-isError.
  (async done
    (-> (tu/with-stubbed-eval! {:held? false :sample {0 "loading"} :t 1}
          #(watch-until/watch-until-tool
             (fresh-conn)
             #js {:signals    "[{:dom \"#spinner\"}]"
                  :pred       #js {:signal 0 :equals "done"}
                  :timeout-ms 120}))
        (.then (fn [r]
                 (is (= {:ok?         false
                         :reason      :watch-timeout
                         :timed-out?  true
                         :timeout-ms  120
                         :last-sample {0 "loading"}}
                        (dissoc (tu/extract-edn r) :hint))
                     "the final reading shows how close the predicate got")
                 (done))))))

(deftest watch-until-missing-pred-short-circuits
  (async done
    (-> (watch-until/watch-until-tool (fresh-conn) #js {:signals "[{:focus true}]"})
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= :missing-pred (:reason (tu/extract-edn r))))
                 (done))))))
