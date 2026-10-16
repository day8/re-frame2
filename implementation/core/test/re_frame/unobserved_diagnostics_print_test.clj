(ns re-frame.unobserved-diagnostics-print-test
  "A dev diagnostic that nothing observed prints one `[re-frame2]` line on the
  JVM, written to `*err*`.

  Two prints, each owned by the stream it travels:

  - ERRORS. A promoted `:rf.error/*` record that nothing routed — no `:errors`
    listener registered, and the record's frame delivered it to no
    REGISTERED `:observability :errors` sink — prints
    `[re-frame2] <category>[ — <message>] (event|sub <id>, frame <id>[, at <ns>:<line>])`.
    A policy naming a sink the app never registered routes nowhere, so it
    still prints.
  - WARNINGS. A no-silent-swallow `:rf.warning/*` (Conventions §No silent
    swallow) delivered while no `:trace` listener is registered prints
    `[re-frame2] <op> — <reason>`. Any trace listener receives it instead.
    Every other warning stays trace-only.

  `dispatch-sync` returns nil whether its handler ran or failed, and a
  boot-time warning carries no dispatch id, so no trace ring retains it: for a
  REPL or a test with nothing attached, the line is the only signal.

  Each case binds `*err*` to its own `StringWriter`, which overrides the quiet
  runner's buffer for that extent.

  ## Posture split

  Both prints are dev-only. Under `-Dre-frame.debug=false` every case asserts
  that NOTHING was written; in dev it asserts the line."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- captured-err
  "Run `thunk` with `*err*` bound to a fresh writer; return what it wrote."
  [thunk]
  (let [w (java.io.StringWriter.)]
    (binding [*err* w]
      (thunk))
    (str w)))

(defn- printed-lines
  "The `[re-frame2]` lines in `s`."
  [s]
  (filterv #(str/starts-with? % "[re-frame2]") (str/split-lines s)))

(defn- expect-one-line
  "In dev, `out` holds exactly one `[re-frame2]` line containing every
  string in `needles`; under the production gate, `out` is empty."
  [out needles]
  (if rf.interop/debug-enabled?
    (let [lines (printed-lines out)]
      (is (= [1 true]
             [(count lines) (every? #(str/includes? (str (first lines)) %) needles)])
          (str "one line containing " (pr-str needles) "; got " (pr-str out))))
    (is (= "" out) (str "the production gate prints nothing; got " (pr-str out)))))

(defn- expect-silence [out why]
  (is (= "" out) (str why "; got " (pr-str out))))

(defn- make-app!
  "Make frame `frame-id` (with `policy` as its `:observability :errors` entries
  when given) and register the refusing handlers against it."
  ([frame-id] (make-app! frame-id nil))
  ([frame-id policy]
   (rf/make-frame (cond-> {:id frame-id :doc "unobserved-diagnostics witness"}
                    (some? policy) (assoc :observability {:errors policy})))
   (rf/reg-event :unobserved/throws
                 (fn [_ _] (throw (ex-info "kaboom" {:cause :test}))))
   (rf/reg-event :unobserved/bad-effect-map
                 (fn [{:keys [db]} _] {:dbb db}))
   (rf/reg-event :unobserved/mark
                 (fn [{:keys [db]} [_ v]] {:db (assoc db :mark v)}))
   nil))

;; ===========================================================================
;; Errors
;; ===========================================================================

(deftest an-unrouted-error-prints-one-line
  (make-app! :unobserved/app)
  (testing "a typo'd event id: the category, the event id and the frame"
    (expect-one-line
      (captured-err #(rf/dispatch-sync [:unobserved/typo] {:frame :unobserved/app}))
      [":rf.error/no-such-handler" "event :unobserved/typo" "frame :unobserved/app"]))
  (testing "a throwing handler: the category, the exception's message and the ids"
    (expect-one-line
      (captured-err #(rf/dispatch-sync [:unobserved/throws] {:frame :unobserved/app}))
      [":rf.error/handler-exception" "kaboom"
       "event :unobserved/throws" "frame :unobserved/app"]))
  (testing "a refused effect map"
    (expect-one-line
      (captured-err #(rf/dispatch-sync [:unobserved/bad-effect-map] {:frame :unobserved/app}))
      [":rf.error/effect-map-shape" "event :unobserved/bad-effect-map"
       "frame :unobserved/app"]))
  (testing "an unknown subscription is labelled as a sub"
    (expect-one-line
      (captured-err #(rf/subscribe-once [:unobserved/no-sub] {:frame :unobserved/app}))
      [":rf.error/no-such-sub" "sub :unobserved/no-sub" "frame :unobserved/app"])))

(deftest an-errors-listener-owns-only-the-records-it-claims
  (make-app! :unobserved/app)
  (testing "a listener that returns `true` claims the record, and nothing prints"
    (let [seen (atom [])]
      (rf.error-emit/register-error-listener! ::owner
                                              (fn [r] (swap! seen conj (:error r)) true))
      (expect-silence
        (captured-err #(rf/dispatch-sync [:unobserved/typo] {:frame :unobserved/app}))
        "a claiming :errors listener owns the record")
      (is (= [:rf.error/no-such-handler] @seen) "premise: the listener received it")
      (rf.error-emit/unregister-error-listener! ::owner)))
  (testing "a listener that returns anything else owns nothing, even a truthy value"
    (let [seen (atom [])]
      (rf.error-emit/register-error-listener! ::bystander
                                              (fn [r] (swap! seen conj (:error r))))
      (expect-one-line
        (captured-err #(rf/dispatch-sync [:unobserved/typo] {:frame :unobserved/app}))
        [":rf.error/no-such-handler" "event :unobserved/typo"])
      (is (= [:rf.error/no-such-handler] @seen) "premise: the listener received it")
      (rf.error-emit/unregister-error-listener! ::bystander)))
  (testing "a listener that throws owns nothing"
    (rf.error-emit/register-error-listener! ::broken
                                            (fn [_r] (throw (ex-info "listener boom" {}))))
    (expect-one-line
      (captured-err #(rf/dispatch-sync [:unobserved/typo] {:frame :unobserved/app}))
      [":rf.error/no-such-handler" "event :unobserved/typo"])
    (rf.error-emit/unregister-error-listener! ::broken)))

(deftest an-emit-recorder-window-claims-what-its-pred-accepts
  (make-app! :unobserved/app)
  (testing "a matching predicate records the error, and nothing prints"
    (let [out (captured-err
                (fn []
                  (rf.test-support/with-emit-recorder!
                    [errs {:pred (fn [r] (= :rf.error/no-such-handler (:error r)))}]
                    (rf/dispatch-sync [:unobserved/typo] {:frame :unobserved/app})
                    (is (= [:rf.error/no-such-handler] (mapv :error @errs))))))]
      (expect-silence out "the window claimed the record its predicate accepted")))
  (testing "a predicate that does not match leaves the record unclaimed, so it prints"
    (let [out (captured-err
                (fn []
                  (rf.test-support/with-emit-recorder!
                    [errs {:pred (fn [r] (= :rf.error/no-such-fx (:error r)))}]
                    (rf/dispatch-sync [:unobserved/typo] {:frame :unobserved/app})
                    (is (empty? @errs)))))]
      (expect-one-line out [":rf.error/no-such-handler" "event :unobserved/typo"]))))

;; A setup step's capture window and an `:on-destroy` window each register a
;; listener for the extent of one dispatch. Each claims only the record it
;; turns into its own outcome, so anything else inside the window still
;; reaches the console.

(deftest a-typod-setup-step-prints-its-no-such-handler-line
  (testing "an :initial-events step naming no handler: one line, and the frame
            is still made (EP-0027 — a handler-less step is not a setup failure)"
    (let [out (captured-err #(rf/make-frame {:id             :unobserved/seeded
                                             :initial-events [[:unobserved/initalise]]}))]
      (expect-one-line out [":rf.error/no-such-handler"
                            "event :unobserved/initalise"
                            "frame :unobserved/seeded"])
      (is (contains? (set (rf/frame-ids)) :unobserved/seeded)
          "construction continued past the handler-less step")))
  (testing "a post-commit fx failure in a setup step: one line, and the frame is made"
    (rf/reg-fx :unobserved/explodes (fn [_] (throw (ex-info "fx kaboom" {}))))
    (rf/reg-event :unobserved/boot-with-fx
                  (fn [_ _] {:db {:booted true} :fx [[:unobserved/explodes nil]]}))
    (let [out (captured-err #(rf/make-frame {:id             :unobserved/fx-seeded
                                             :initial-events [[:unobserved/boot-with-fx]]}))]
      (expect-one-line out [":rf.error/fx-handler-exception" "frame :unobserved/fx-seeded"])
      (is (contains? (set (rf/frame-ids)) :unobserved/fx-seeded)))))

(deftest a-setup-step-failure-is-claimed-by-the-step-and-raised
  (rf/reg-event :unobserved/boot-throws
                (fn [_ _] (throw (ex-info "boot kaboom" {}))))
  (let [thrown (atom nil)
        out    (captured-err
                 #(try
                    (rf/make-frame {:id             :unobserved/torn-down
                                    :initial-events [[:unobserved/boot-throws]]})
                    (catch Exception e (reset! thrown e))))]
    (is (= :rf.error/initial-events-step-failed (:rf.error/id (ex-data @thrown)))
        "construction raises the step failure")
    (is (not (contains? (set (rf/frame-ids)) :unobserved/torn-down))
        "and tears the partial frame down")
    (expect-silence out "the step claimed the record it raised as its own failure")))

(deftest an-on-destroy-throw-prints-its-own-category-once
  (rf/reg-event :unobserved/destroy-throws
                (fn [_ _] (throw (ex-info "destroy kaboom" {}))))
  (rf/make-frame {:id :unobserved/doomed :on-destroy [:unobserved/destroy-throws]})
  (let [out (captured-err #(rf/destroy-frame! :unobserved/doomed))]
    (expect-one-line out [":rf.error/on-destroy-handler-exception" "frame :unobserved/doomed"])
    (when rf.interop/debug-enabled?
      (is (not (str/includes? out ":rf.error/handler-exception"))
          (str "the window claimed the handler exception it re-emitted; got "
               (pr-str out))))))

(deftest a-sink-that-routed-the-record-owns-it
  (let [seen (atom 0)]
    (rf/register-observability-sink! ::collector (fn [_r] (swap! seen inc)))
    (make-app! :unobserved/owned [{:sink ::collector}])
    (expect-silence
      (captured-err #(rf/dispatch-sync [:unobserved/throws] {:frame :unobserved/owned}))
      "the frame's registered sink received the record")
    (is (= 1 @seen) "premise: the sink was invoked")))

(deftest a-policy-naming-an-unregistered-sink-still-prints
  (make-app! :unobserved/orphan [{:sink ::never-registered}])
  (expect-one-line
    (captured-err #(rf/dispatch-sync [:unobserved/throws] {:frame :unobserved/orphan}))
    [":rf.error/handler-exception" "frame :unobserved/orphan"]))

;; ===========================================================================
;; Warnings
;; ===========================================================================

(deftest a-no-silent-swallow-warning-prints-while-no-trace-listener-is-registered
  (testing "a misspelt configure! key"
    (expect-one-line
      (captured-err #(rf/configure! {:epoch-histroy {:depth 5}}))
      [":rf.warning/unknown-configure-key — " ":epoch-histroy"]))
  (testing "an unknown dispatch opt"
    (make-app! :unobserved/app)
    (expect-one-line
      (captured-err #(rf/dispatch-sync [:unobserved/mark 1] {:frame :unobserved/app :bogus 1}))
      [":rf.warning/unknown-dispatch-opt — " ":bogus"]))
  (testing "a trace listener receives the warning instead"
    (let [seen (atom [])]
      (rf/register-listener! :trace ::seen (fn [ev] (swap! seen conj (:operation ev))))
      (expect-silence
        (captured-err #(rf/configure! {:epoch-histroy {:depth 5}}))
        "any :trace listener owns the warning")
      (rf/unregister-listener! :trace ::seen)
      (when rf.interop/debug-enabled?
        (is (some #{:rf.warning/unknown-configure-key} @seen)
            "premise: the listener received it")))))

(deftest configure-epoch-history-without-the-artefact-prints-its-reason
  ;; Dropping the published hook stands in for a build without the epoch
  ;; artefact, which this classpath may carry.
  (let [hook (rf.late-bind/get-fn :epoch/configure!)]
    (try
      (swap! rf.late-bind/hooks dissoc :epoch/configure!)
      (expect-one-line
        (captured-err #(rf/configure! {:epoch-history {:depth 5}}))
        [":rf.warning/unknown-configure-key — rf/configure! applied nothing for"])
      (finally
        (when hook (rf.late-bind/set-fn! :epoch/configure! hook))))))

(deftest a-warning-outside-the-set-stays-trace-only
  (testing "control: an in-set id on the same emit path prints"
    (expect-one-line
      (captured-err #(rf.trace/emit! :warning :rf.warning/unknown-registration-key
                                     {:reason "control reason"}))
      [":rf.warning/unknown-registration-key — control reason"]))
  (expect-silence
    (captured-err #(rf.trace/emit! :warning :rf.warning/missing-doc
                                   {:reason "not a swallowed input"}))
    ":rf.warning/missing-doc is not a no-silent-swallow warning"))

;; ===========================================================================
;; Containment
;; ===========================================================================

(defn- throwing-writer []
  (proxy [java.io.Writer] []
    (write
      ([_] (throw (java.io.IOException. "stderr is broken")))
      ([_ _ _] (throw (java.io.IOException. "stderr is broken"))))
    (flush [] (throw (java.io.IOException. "stderr is broken")))
    (close [] nil)))

(deftest a-throwing-err-never-aborts-the-drain
  (make-app! :unobserved/app)
  (rf/reg-event :unobserved/cascade
                (fn [_ _] {:fx [[:dispatch [:unobserved/typo]]
                                [:dispatch [:unobserved/mark :after-typo]]]}))
  (binding [*err* (throwing-writer)]
    (is (nil? (rf/dispatch-sync [:unobserved/cascade] {:frame :unobserved/app}))
        "the print's failure is contained")
    (is (nil? (rf/configure! {:epoch-histroy {:depth 5}}))
        "and so is the warning print's"))
  (is (= :after-typo (:mark (rf/app-db-value :unobserved/app)))
      "the event queued behind the unrouted error still committed")
  (rf/dispatch-sync [:unobserved/mark :next] {:frame :unobserved/app})
  (is (= :next (:mark (rf/app-db-value :unobserved/app)))
      "and a following dispatch commits"))
