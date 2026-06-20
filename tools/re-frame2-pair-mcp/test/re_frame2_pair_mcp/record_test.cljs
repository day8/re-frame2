(ns re-frame2-pair-mcp.record-test
  "Unit tests for the signal-recorder triplet — record / read-recording /
  watch-until.

  Three layers, mirroring `read_dom_test`:

    1. Arg coercion — `parse-signals-arg` / `parse-stop-arg` accept the
       JS-array / EDN-string / bare-map shapes the MCP host sends.

    2. Predicate + form composition — `pred-source` compiles a DATA
       predicate into a pure value-comparison fn source (no host source
       crosses the wire); the `record` / `watch-until` eval forms carry
       the load-bearing runtime calls and stay READ-ONLY (no dispatch /
       reset / DOM-write host-forms).

    3. Tool wiring — stub `cljs-eval-value` (no socket) and pin the wire
       shape: record returns the runtime envelope, read-recording forwards
       the change-log, watch-until resolves on the first :held? poll and
       times out with :last-sample, and the gates (:no-signals /
       :missing-pred / :missing-recording-id) short-circuit.

  The rAF/dedup/teardown semantics (does the sampler actually dedup? does
  it tear down at the stop condition?) live in the runtime
  (`re-frame2-pair.runtime/start-recording!` & friends) and are exercised
  by the form running in a real tab — out of scope for a node-runtime
  unit suite. This suite pins the wire contract + the form's internal
  shape; the runtime structural tests cover the sampler."
  (:require [cljs.test :refer-macros [deftest is async testing]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.record :as record]
            [re-frame2-pair-mcp.tools.watch-until :as watch-until]))

;; ---------------------------------------------------------------------------
;; parse-signals-arg — input-shape contract.
;; ---------------------------------------------------------------------------

(deftest parse-signals-arg-shapes
  (testing "JS array of signal objects"
    (is (= [{:focus true} {:dom "#c"}]
           (record/parse-signals-arg #js [#js {:focus true} #js {:dom "#c"}]))))
  (testing "CLJS vector passes through"
    (is (= [{:app-db [:cart]}] (record/parse-signals-arg [{:app-db [:cart]}]))))
  (testing "a bare single signal map ⇒ wrapped in a vector"
    (is (= [{:focus true}] (record/parse-signals-arg {:focus true}))))
  (testing "EDN string ⇒ vector of signal maps"
    (is (= [{:focus true} {:app-db [:a :b]}]
           (record/parse-signals-arg "[{:focus true} {:app-db [:a :b]}]"))))
  (testing "EDN string of a single map ⇒ wrapped"
    (is (= [{:dom "#x"}] (record/parse-signals-arg "{:dom \"#x\"}"))))
  (testing "absent / blank / unparseable ⇒ nil"
    (is (nil? (record/parse-signals-arg nil)))
    (is (nil? (record/parse-signals-arg "   ")))
    (is (nil? (record/parse-signals-arg "(not edn")))))

;; ---------------------------------------------------------------------------
;; parse-stop-arg — keeps only the recognised keys.
;; ---------------------------------------------------------------------------

;; `parse-stop-arg` returns the tagged `[:ok m]` /
;; `[:err :invalid-stop-edn]` shape (mirroring `parse-filter-arg`). A
;; malformed `stop` EDN surfaces as an honest `:err` tag rather than
;; silently collapsing to `{}` (the default wall-clock window).

(deftest parse-stop-arg-shapes
  (is (= [:ok {:ms 5000}] (record/parse-stop-arg #js {:ms 5000})))
  (is (= [:ok {:changes 10}] (record/parse-stop-arg "{:changes 10}")))
  (is (= [:ok {:pred {:signal 0 :equals :done}}]
         (record/parse-stop-arg "{:pred {:signal 0 :equals :done}}")))
  (testing "unrecognised keys dropped"
    (is (= [:ok {:ms 1}] (record/parse-stop-arg #js {:ms 1 :bogus 9}))))
  (testing "absent ⇒ [:ok {}]"
    (is (= [:ok {}] (record/parse-stop-arg nil)))))

(deftest parse-stop-arg-malformed-edn-is-err-tagged
  ;; Regression: a malformed `stop` EDN string must NOT silently
  ;; collapse to `{}` (the default window) — it returns the honest
  ;; `[:err :invalid-stop-edn]` tag so the caller short-circuits.
  (testing "unbalanced delimiters ⇒ :err"
    (is (= [:err :invalid-stop-edn] (record/parse-stop-arg "{:ms 5000"))) ;; missing brace
    (is (= [:err :invalid-stop-edn] (record/parse-stop-arg "((("))))      ;; unbalanced
  (testing "reads clean but not a map ⇒ :err (same swallow-class)"
    (is (= [:err :invalid-stop-edn] (record/parse-stop-arg "[:ms 5000]")))))

;; ---------------------------------------------------------------------------
;; pred-source — DATA predicate compiles to a pure comparison fn source.
;; ---------------------------------------------------------------------------

(deftest pred-source-shapes
  (testing ":equals comparison"
    (let [src (record/pred-source {:signal 0 :equals :done})]
      (is (str/includes? src "(get sample 0)"))
      (is (str/includes? src ":done"))
      (is (str/includes? src "(="))))
  (testing ":path drills into the sampled value (path quoted as data)"
    (let [src (record/pred-source {:signal 1 :path [:id] :equals "x"})]
      (is (str/includes? src "(get-in (get sample 1) (quote [:id]))"))
      (is (str/includes? src "\"x\""))))
  (testing ":changed ⇒ some? check"
    (let [src (record/pred-source {:signal 0 :changed true})]
      (is (str/includes? src "some?"))))
  (testing ":contains ⇒ string includes"
    (let [src (record/pred-source {:signal 0 :contains "load"})]
      (is (str/includes? src "clojure.string/includes?"))
      (is (str/includes? src "\"load\""))))
  (testing "bare {:signal n} ⇒ any non-nil"
    (is (str/includes? (record/pred-source {:signal 2}) "some?")))
  (testing "nil / non-map ⇒ nil"
    (is (nil? (record/pred-source nil)))
    (is (nil? (record/pred-source "not-a-map")))))

(deftest pred-source-no-host-source-injection
  ;; The compiled predicate is pure value comparison — a hostile :equals
  ;; value is pr-str'd as a DATA literal, never spliced as host source.
  (let [src (record/pred-source {:signal 0 :equals '(js/alert "pwn")})]
    ;; The list rides as a quoted EDN literal, not an evaluated call.
    (is (str/includes? src "(quote (js/alert \"pwn\"))"))))

;; ---------------------------------------------------------------------------
;; record form composition — load-bearing runtime call + read-only.
;; ---------------------------------------------------------------------------

(deftest start-recording-form-shape
  (let [form (#'record/start-recording-form
               [{:focus true} {:app-db [:cart]}]
               {:ms 15000 :pred {:signal 0 :equals :done}}
               :rf/default
               2000
               ;; the rendered elision-opts walker map.
               "{:rf.size/include-large? false :rf.size/include-sensitive? false}")]
    (testing "calls the runtime start-recording! with the signals + stop"
      (is (str/includes? form "re-frame2-pair.runtime/start-recording!"))
      (is (str/includes? form ":signals"))
      (is (str/includes? form "{:focus true}"))
      (is (str/includes? form ":ms 15000"))
      (is (str/includes? form ":frame :rf/default"))
      (is (str/includes? form ":max-entries 2000")))
    (testing "the data predicate compiles into a :pred-fn slot (not raw :pred)"
      (is (str/includes? form ":pred-fn"))
      (is (not (str/includes? form ":pred {")) "the data :pred key must not ride verbatim"))
    (testing "the elision-opts ride as :elide-opts (rf2-8fin7.2)"
      (is (str/includes? form ":elide-opts"))
      (is (str/includes? form ":rf.size/include-sensitive? false")))))

(deftest record-and-watch-forms-are-read-only
  ;; READ-ONLY by construction — neither form may carry an app-mutation
  ;; host-form. The whole point of the recorder is observation.
  (let [rec-form   (#'record/start-recording-form [{:focus true}] {:ms 1000} nil nil
                                                   "{:rf.size/include-large? false :rf.size/include-sensitive? false}")
        watch-form (watch-until/watch-form [{:app-db [:x]}] :rf/default
                                           (record/pred-source {:signal 0 :equals 1})
                                           "{:rf.size/include-large? false :rf.size/include-sensitive? false}")]
    (doseq [form [rec-form watch-form]
            mutator ["pair-dispatch" "replace-app-db" "app-db-reset"
                     ".setAttribute" ".dispatchEvent" ".innerHTML"
                     "restore-epoch"]]
      (is (not (str/includes? form mutator))
          (str "recorder form must be read-only — found mutator " mutator)))))

;; ---------------------------------------------------------------------------
;; Tool wiring — preflight + envelope passthrough.
;; ---------------------------------------------------------------------------

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

;; ---- record -----------------------------------------------------------------

(deftest record-returns-recording-id
  (async done
    (let [canned {:ok? true :recording-id "rec-abc"
                  :signals [{:focus true}] :frame :rf/default :stop {:ms 30000}}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (record/record-tool (fresh-conn)
                                  #js {:signals "[{:focus true}]"})))
          (.then (fn [r]
                   (is (not (tu/error? r)))
                   (let [edn (tu/extract-edn r)]
                     (is (true? (:ok? edn)))
                     (is (= "rec-abc" (:recording-id edn)) "recording-id surfaced"))
                   (done)))))))

(deftest record-no-signals-short-circuits
  (async done
    (-> (record/record-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= :no-signals (:reason (tu/extract-edn r))))
                 (done))))))

(deftest record-tool-malformed-stop-errors-honestly
  ;; Regression: a malformed `stop` EDN makes `record-tool`
  ;; short-circuit to an honest `:ok? false` envelope
  ;; (`:reason :invalid-stop-edn`, `:isError true`) — the `cond` branch
  ;; fires BEFORE the `:else` arm reaches `ensure-runtime!` / the nREPL
  ;; socket, so this resolves synchronously with no live runtime and no
  ;; silent default-window recording started. Signals are present so the
  ;; `:no-signals` branch does not fire first.
  (async done
    (-> (record/record-tool (fresh-conn)
                            #js {:signals "[{:focus true}]" :stop "{:ms 5000"})
        (.then (fn [r]
                 (is (tu/error? r)
                     "malformed stop EDN surfaces as :isError true")
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :invalid-stop-edn (:reason edn))
                       "honest :reason, not a silent default-window recording")
                   (is (= "{:ms 5000" (:given edn))))
                 (done))))))

;; ---- read-recording ---------------------------------------------------------

(deftest read-recording-forwards-change-log
  (async done
    (let [canned {:ok? true :recording-id "rec-abc" :status :stopped
                  :stopped-reason :ms :frames-sampled 900 :count 2
                  :entries [{:i 0 :signal {:focus true}
                             :value {:tag "input" :id "q"} :t 1 :frame 0}
                            {:i 0 :signal {:focus true}
                             :value {:tag "button" :id "go"} :t 2 :frame 42}]}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (record/read-recording-tool (fresh-conn)
                                          #js {:recording-id "rec-abc"})))
          (.then (fn [r]
                   (is (not (tu/error? r)))
                   (let [edn (tu/extract-edn r)]
                     (is (true? (:ok? edn)))
                     (is (= :stopped (:status edn)))
                     (is (= 2 (:count edn)) "change-entry count surfaced")
                     (is (= 42 (-> edn :entries second :frame)) "rAF frame counter rides"))
                   (done)))))))

(deftest read-recording-missing-id-short-circuits
  (async done
    (-> (record/read-recording-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= :missing-recording-id (:reason (tu/extract-edn r))))
                 (done))))))

(deftest read-recording-drain-and-stop-ride-the-form
  ;; The :drain / :stop bool args must reach the runtime read-recording
  ;; call as an opts map — capture the emitted form to confirm.
  (async done
    (let [seen   (atom nil)
          orig   nrepl/cljs-eval-value
          ;; Explicit 3- and 4-arity (NOT a variadic / rest-arg fn):
          ;; CLJS direct-arity dispatch calls the var via
          ;; `...$_invoke$arity$3`, which a `(fn [& a])` doesn't expose —
          ;; same reason `tu/with-stubbed-eval!` spells both arities out.
          canned (js/Promise.resolve {:ok? true :recording-id "r" :status :stopped
                                      :count 0 :entries []})
          stub   (fn
                   ([_conn _build form] (reset! seen form) canned)
                   ([_conn _build form _opts] (reset! seen form) canned))]
      (set! nrepl/cljs-eval-value stub)
      (-> (record/read-recording-tool (fresh-conn)
                                      #js {:recording-id "r" :drain true :stop true})
          (.then (fn [_]
                   (is (str/includes? @seen "re-frame2-pair.runtime/read-recording"))
                   (is (str/includes? @seen ":drain true"))
                   (is (str/includes? @seen ":stop true"))))
          (.finally (fn []
                      (tu/restore-eval! stub orig)
                      (done)))))))

;; ---- watch-until ------------------------------------------------------------

(deftest watch-until-resolves-on-held-predicate
  (async done
    ;; The poll form resolves to {:held? true ...} on the first eval.
    (let [canned {:held? true :sample {0 :done} :t 1712}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (watch-until/watch-until-tool
                (fresh-conn)
                #js {:signals "[{:app-db [:upload :status]}]"
                     :pred #js {:signal 0 :equals "done"}})))
          (.then (fn [r]
                   (is (not (tu/error? r)))
                   (let [edn (tu/extract-edn r)]
                     (is (true? (:ok? edn)))
                     (is (true? (:held? edn)) "watch resolved on the predicate")
                     (is (= {0 :done} (:sample edn)) "the satisfying sample rides back"))
                   (done)))))))

(deftest watch-until-times-out-with-last-sample
  (async done
    ;; The poll form never reports :held? — the watch must time out and
    ;; surface the final sample. timeout-ms tiny so the test is fast.
    (let [canned {:held? false :sample {0 "loading"} :t 1}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (watch-until/watch-until-tool
                (fresh-conn)
                #js {:signals "[{:dom \"#spinner\"}]"
                     :pred #js {:signal 0 :equals "done"}
                     :timeout-ms 120})))
          (.then (fn [r]
                   (let [edn (tu/extract-edn r)]
                     (is (false? (:ok? edn)))
                     (is (= :watch-timeout (:reason edn)))
                     (is (true? (:timed-out? edn)))
                     (is (= {0 "loading"} (:last-sample edn)) "final reading surfaced"))
                   (done)))))))

(deftest watch-until-no-signals-short-circuits
  (async done
    (-> (watch-until/watch-until-tool (fresh-conn)
                                      #js {:pred #js {:signal 0 :equals 1}})
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= :no-signals (:reason (tu/extract-edn r))))
                 (done))))))

(deftest watch-until-missing-pred-short-circuits
  (async done
    (-> (watch-until/watch-until-tool (fresh-conn)
                                      #js {:signals "[{:focus true}]"})
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= :missing-pred (:reason (tu/extract-edn r))))
                 (done))))))

(deftest watch-form-applies-pred-server-side
  ;; The poll form must sample server-side AND apply the compiled
  ;; predicate, returning :held? — so the wire payload is a boolean,
  ;; not the whole sample-set re-tested client-side.
  (let [form (watch-until/watch-form
               [{:app-db [:x]}] :rf/default
               (record/pred-source {:signal 0 :equals 1})
               "{:rf.size/include-large? false :rf.size/include-sensitive? false}")]
    (is (str/includes? form "re-frame2-pair.runtime/sample-signals"))
    (is (str/includes? form ":held?"))
    (is (str/includes? form ":sample"))))
