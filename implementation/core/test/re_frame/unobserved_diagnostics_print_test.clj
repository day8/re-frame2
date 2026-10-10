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
      (is (= 1 (count lines))
          (str "exactly one [re-frame2] line; got " (pr-str out)))
      (doseq [needle needles]
        (is (str/includes? (str (first lines)) needle)
            (str "the line names " needle "; got " (pr-str (first lines))))))
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

(deftest an-errors-listener-owns-the-record
  (make-app! :unobserved/app)
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::owner (fn [r] (swap! seen conj (:error r))))
    (expect-silence
      (captured-err #(rf/dispatch-sync [:unobserved/typo] {:frame :unobserved/app}))
      "a registered :errors listener owns the record")
    (is (= [:rf.error/no-such-handler] @seen) "premise: the listener received it")))

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
