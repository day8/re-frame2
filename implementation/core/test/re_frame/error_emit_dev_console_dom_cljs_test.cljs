(ns re-frame.error-emit-dev-console-dom-cljs-test
  "The unowned-error dev console fallback in `re-frame.error-emit`.

  When NOTHING ROUTED a refusal record — no `:errors` listener registered
  (corpus-wide), and the record's own frame delivered it to no REGISTERED
  `:observability :errors` sink (frame-scoped) — a dev build prints it once
  with `console.error`. A DOM host passes
  `[\"[re-frame2]\" <summary> <record> <exception>]`: a readable summary line
  (the category, then the exception's message, else the record's `:reason`,
  then the event or sub id, frame and source coordinate), the record as a
  value, and the original exception when the category carries one. A non-DOM
  host passes `[\"[re-frame2]\" <summary>]` alone. Never `reportError`: the
  browser test runner fails a run on any `pageerror`, and suites elsewhere
  exercise refusals on purpose.

  A no-silent-swallow warning delivered while no `:trace` listener is
  registered prints `[\"[re-frame2]\" <op — reason>]` with `console.warn`, on
  every host.

  The `-dom-cljs-test` suffix puts this namespace on `:browser-test`, and the
  broader `cljs-test$` regexp also puts it on `:node-test`, which has a
  `console` but no DOM. So every row asserting the DOM argument shape reports
  a stated skip under Node, and `node-targeted-cljs-prints-the-line-alone` is
  the mirror image."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            ;; Loaded so `reg-app-schema` reaches a validator; without it the
            ;; app-db rollback row passes vacuously.
            [re-frame.schemas]
            [re-frame.observability :as rf.observability]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     ;; Both ownership registries are `defonce` atoms, and an entry leaked
     ;; from a sibling test would silence the fallback invisibly.
     :init-fn (fn []
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))}))

(defn- browser?
  "True only on a real DOM host — the discriminator the fallback itself uses."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(def ^:private skip-msg
  "skipped: no DOM (node lane — see ns docstring)")

(defn- capture-console
  "Run `thunk` with `console.error` and `globalThis.reportError` swapped for
  recorders, restoring both. Returns `{:console [[arg …] …] :report-error <int>}`,
  keeping the ARGUMENTS of each call, since the contract is what the framework
  passes rather than how a console renders it."
  [thunk]
  (let [calls       (atom [])
        reports     (atom 0)
        orig-error  (.-error js/console)
        orig-report (.-reportError js/globalThis)]
    (set! (.-error js/console)
          (fn [& args] (swap! calls conj (vec args)) nil))
    (set! (.-reportError js/globalThis)
          (fn [& _] (swap! reports inc) nil))
    (try
      (thunk)
      (finally
        (set! (.-error js/console) orig-error)
        (set! (.-reportError js/globalThis) orig-report)))
    {:console @calls :report-error @reports}))

(defn- register-refusal-handlers! []
  (rf/reg-event :fu75.console/throws
                (fn [_ _] (throw (ex-info "kaboom" {:cause :test})))))

;; ===========================================================================
;; Unowned — the fallback fires once, with the right arguments
;; ===========================================================================

(deftest unowned-refusal-reaches-the-dev-console
  (if-not (browser?)
    (is true skip-msg)
    (do
      (register-refusal-handlers!)
      (testing "a throwing handler: prefix, summary, record, original exception;
                no reportError"
        (let [{:keys [console report-error]}
              (capture-console #(rf/dispatch-sync [:fu75.console/throws]))]
          (is (= 1 (count console))
              (str "exactly one console.error for one refusal; got "
                   (pr-str (mapv first console))))
          (let [[prefix summary record ex] (first console)]
            (is (= "[re-frame2]" prefix))
            (is (re-find #"^:rf\.error/handler-exception\b" summary)
                (str "the summary names the category first; got " (pr-str summary)))
            (is (re-find #"kaboom" summary)
                (str "and carries the exception's own message; got " (pr-str summary)))
            (is (= :rf.error/handler-exception (:error record)))
            (is (identical? ex (:exception record))
                "the original exception rides as its own argument")
            (is (= "kaboom" (ex-message ex))))
          (is (zero? report-error))))

      (testing "an unregistered event id carries no exception and no :reason: three
                arguments, and the summary is the category and the ids"
        (let [{:keys [console]}
              (capture-console #(rf/dispatch-sync [:fu75.console/nothing-here]))]
          (is (= 1 (count console)))
          (let [args (first console)]
            (is (= 3 (count args))
                (str "prefix + summary + record; got " (count args) " arguments"))
            (is (= "[re-frame2]" (first args)))
            (is (= ":rf.error/no-such-handler (event :fu75.console/nothing-here, frame :rf/default)"
                   (second args))
                (str "never an empty string, never invented prose; got "
                     (pr-str (second args))))
            (is (= :rf.error/no-such-handler (:error (nth args 2))))))))))

(deftest unowned-union-record-reaches-the-dev-console
  (if-not (browser?)
    (is true skip-msg)
    (testing "the `dispatch-error-record*` site, through the frame-teardown
              report: no top-level exception, so the summary falls back to the
              record's composed :reason"
      (let [{:keys [console]}
            (capture-console
              (fn []
                (rf.error-emit/dispatch-frame-teardown-report!
                  :rf/default
                  [{:hook :fu75/step :exception (ex-info "teardown" {})
                    :where :safe-call-hook!}]
                  1234)))]
        (is (= 1 (count console)))
        (let [[prefix summary record] (first console)]
          (is (= 3 (count (first console))))
          (is (= "[re-frame2]" prefix))
          (is (re-find #"^:rf\.error/frame-teardown-failed\b" summary))
          (is (re-find #"teardown step\(s\) threw" summary)
              (str "the summary falls back to :reason; got " (pr-str summary)))
          (is (= :rf.error/frame-teardown-failed (:error record))))))))

;; `emit-no-frame-context!` and `emit-bad-frame-provider-arg!` pass no
;; exception (nothing threw), so the record's `:reason` is the only place the
;; composed sentence can reach the always-on axis and the console line.

(deftest no-frame-context-carries-its-ladder-to-the-console
  (if-not (browser?)
    (is true skip-msg)
    (let [payload (rf.frame/no-frame-context-payload :subscribe {:where 'rf/subscribe})]
      (testing "the always-on record carries the payload's own :reason and :recovery"
        (let [seen (atom [])]
          (rf.error-emit/register-error-listener! :fu75/ladder
                                                  (fn [record] (swap! seen conj record)))
          (rf.frame/emit-no-frame-context! payload)
          (rf.error-emit/unregister-error-listener! :fu75/ladder)
          (let [record (first @seen)]
            (is (= :rf.error/no-frame-context (:error record)))
            (is (= (:reason payload) (:reason record)))
            (is (= :supply-frame (:recovery record))))))

      (testing "the frameless record prints even with a sink registered: no frame
                owns it, so no sink policy can — and the ladder leads the line"
        (rf/register-observability-sink! :fu75.sink/collector (fn [_r] nil))
        (let [{:keys [console report-error]}
              (capture-console #(rf.frame/emit-no-frame-context! payload))]
          (is (= 1 (count console))
              (str "a frameless record still reaches the console; got "
                   (pr-str console)))
          (let [[prefix summary record :as args] (first console)]
            (is (= 3 (count args)))
            (is (= "[re-frame2]" prefix))
            (is (re-find #"^:rf\.error/no-frame-context\b" summary))
            (is (re-find #"no frame context" summary)
                (str "the ladder reaches the line; got " (pr-str summary)))
            (is (nil? (:frame record)) "premise: the record really is frameless"))
          (is (zero? report-error)))))))

(deftest bad-frame-provider-arg-carries-its-reason-too
  (if-not (browser?)
    (is true skip-msg)
    (let [payload (rf.frame/bad-frame-provider-arg-payload
                    "not-a-frame" {:where 'rf/frame-provider})
          {:keys [console]}
          (capture-console #(rf.frame/emit-bad-frame-provider-arg! payload))]
      (is (= 1 (count console)))
      (let [[_ summary record] (first console)]
        (is (re-find #"must be a frame id keyword" summary)
            (str "the payload's own sentence reaches the line; got " (pr-str summary)))
        (is (= (:reason payload) (:reason record))
            "and verbatim onto the record for an off-box shipper")))))

;; ===========================================================================
;; Owned by a listener — corpus-wide, and the registration is the ownership
;; ===========================================================================

(deftest ownership-is-implicit-and-corpus-wide
  (if-not (browser?)
    (is true skip-msg)
    (testing "a listener that THROWS still owns the stream: the substrate swallows
              the throw, and the fallback must not read that as nobody-owns-it"
      (register-refusal-handlers!)
      (rf.error-emit/register-error-listener! :fu75/broken
                                              (fn [_record] (throw (ex-info "listener boom" {}))))
      (is (empty? (:console (capture-console #(rf/dispatch-sync [:fu75.console/throws]))))))))

(deftest dropping-the-last-listener-resumes-the-fallback
  (if-not (browser?)
    (is true skip-msg)
    (do
      (register-refusal-handlers!)
      (rf.error-emit/register-error-listener! :fu75/owner (fn [_record] nil))
      (is (empty? (:console (capture-console #(rf/dispatch-sync [:fu75.console/throws]))))
          "quiet while owned, by a listener that ignores the record")
      (rf.error-emit/unregister-error-listener! :fu75/owner)
      (is (= 1 (count (:console (capture-console #(rf/dispatch-sync [:fu75.console/throws])))))
          "the registry is empty again, so the fallback resumes"))))

;; ===========================================================================
;; Owned by the frame's sink policy — frame-scoped
;; ===========================================================================
;;
;; "Routed" means DELIVERED to a registered sink fn. A policy naming a sink the
;; app never registered routes nowhere and does not own the record; a
;; registered sink that throws does own it.

(defn- register-sink-refusal!
  "Make `frame-id` with `entries` as its `:observability :errors` policy, and
  register a handler on it that throws."
  [frame-id entries]
  (rf/make-frame (cond-> {:id frame-id :doc "sink-ownership witness"}
                   (some? entries) (assoc :observability {:errors entries})))
  (rf/reg-event :fu75.sink/throws
                {:frame frame-id}
                (fn [_ _] (throw (ex-info "sink-arm kaboom" {:cause :test}))))
  nil)

(deftest a-policy-naming-an-UNREGISTERED-sink-still-prints
  (if-not (browser?)
    (is true skip-msg)
    (do
      (register-sink-refusal! :fu75.sink/orphan [{:sink :fu75.sink/never-wired}])
      (let [{:keys [console]}
            (capture-console
              #(rf/dispatch-sync [:fu75.sink/throws] {:frame :fu75.sink/orphan}))]
        (is (= 1 (count console))
            (str "an unwired sink id must not buy silence; got " (pr-str console)))
        (is (= :fu75.sink/orphan (:frame (nth (first console) 2))))))))

(deftest sink-ownership-is-frame-scoped-not-page-wide
  (if-not (browser?)
    (is true skip-msg)
    (testing "a frame whose policy delivered the record to a registered sink stays
              quiet; a sibling frame that declared nothing still prints"
      (let [seen (atom [])]
        (rf/register-observability-sink! :fu75.sink/collector
                                         (fn [r] (swap! seen conj r)))
        (register-sink-refusal! :fu75.sink/owned [{:sink :fu75.sink/collector}])
        (rf/make-frame {:id :fu75.sink/bare :doc "no :observability policy"})
        (rf/reg-event :fu75.sink/bare-throws
                      {:frame :fu75.sink/bare}
                      (fn [_ _] (throw (ex-info "bare kaboom" {}))))
        (let [owned (capture-console
                      #(rf/dispatch-sync [:fu75.sink/throws] {:frame :fu75.sink/owned}))
              bare  (capture-console
                      #(rf/dispatch-sync [:fu75.sink/bare-throws] {:frame :fu75.sink/bare}))]
          (is (= 1 (count @seen))
              (str "premise: the sink received the owned frame's record; got "
                   (count @seen)))
          (is (empty? (:console owned))
              (str "the frame that routed stays quiet; got " (pr-str (:console owned))))
          (is (= 1 (count (:console bare)))
              (str "the frame that routed nothing still prints; got "
                   (pr-str (:console bare))))
          (is (= :fu75.sink/bare (:frame (nth (first (:console bare)) 2)))))))))

(deftest a-throwing-registered-sink-still-owns-the-record
  (if-not (browser?)
    (is true skip-msg)
    (let [calls (atom 0)]
      (rf/register-observability-sink! :fu75.sink/broken
                                       (fn [_r]
                                         (swap! calls inc)
                                         (throw (ex-info "sink boom" {}))))
      (register-sink-refusal! :fu75.sink/throwing [{:sink :fu75.sink/broken}])
      (let [{:keys [console]}
            (capture-console
              #(rf/dispatch-sync [:fu75.sink/throws] {:frame :fu75.sink/throwing}))]
        (is (= 1 @calls) "premise: the sink really was invoked")
        (is (empty? console)
            (str "delivery is ownership, whatever the sink then did; got "
                 (pr-str console)))))))

;; ===========================================================================
;; The app-db candidate rejection
;; ===========================================================================
;;
;; The validator routes a rejected candidate onto the `:errors` stream through
;; `dispatch-error-record!`, so this fallback prints it with no second printer:
;; one line per failing registration, naming the registered path and the type
;; of what it found, and silent once anything owns the stream.

(defn- register-rollback-app! []
  (rf/make-frame {:id :fu75.rollback/frame :doc "app-db rollback console witness"})
  (rf/with-frame :fu75.rollback/frame
    (rf/reg-app-schema [:articles] :int)
    (rf/reg-app-schema [:tags]     :int))
  (rf/reg-event :fu75.rollback/write
                (fn [_ _] {:db {:unrelated 1}})))

(deftest unowned-app-db-rollback-reaches-the-dev-console
  (if-not (browser?)
    (is true skip-msg)
    (when (some? (rf.late-bind/get-fn :schemas/validate-app-schema!))
      (register-rollback-app!)
      (testing "unowned: one console.error per failing registration"
        (let [{:keys [console]}
              (capture-console
                #(rf/dispatch-sync [:fu75.rollback/write]
                                   {:frame :fu75.rollback/frame}))
              rollback (filterv (fn [args]
                                  (= :rf.error/schema-validation-failure
                                     (:error (nth args 2 nil))))
                                console)]
          (is (= 2 (count rollback))
              (str "one line per violated registration; got "
                   (pr-str (mapv second console))))
          (doseq [[_ summary] rollback]
            (is (re-find #"got nil" summary)
                (str "the line ends with the TYPE of what it found; got "
                     (pr-str summary))))
          (is (= #{[:articles] [:tags]}
                 (set (map (fn [args] (:registered-path (nth args 2))) rollback)))
              "each line names a distinct registration")))

      (testing "owned: any listener silences it"
        (let [seen (atom [])]
          (rf.error-emit/register-error-listener! :fu75/rollback-owner
                                                  (fn [r] (swap! seen conj r)))
          (let [{:keys [console]}
                (capture-console
                  #(rf/dispatch-sync [:fu75.rollback/write]
                                     {:frame :fu75.rollback/frame}))]
            (is (= 2 (count (filter #(= :rf.error/schema-validation-failure (:error %))
                                    @seen)))
                "premise: the owner got both records")
            (is (empty? console)
                (str "and nothing printed; got " (pr-str console))))
          (rf.error-emit/unregister-error-listener! :fu75/rollback-owner))))))

;; ===========================================================================
;; Host boundary — a non-DOM host prints the line alone
;; ===========================================================================

(deftest node-targeted-cljs-prints-the-line-alone
  (if (browser?)
    (is true "skipped: DOM host present (browser lane — see ns docstring)")
    (testing "same refusal, same empty registry, no DOM host: one console.error
              carrying the prefix and the summary line, no record, no
              reportError"
      (register-refusal-handlers!)
      (let [{:keys [console report-error]}
            (capture-console #(rf/dispatch-sync [:fu75.console/throws]))]
        (is (= 1 (count console))
            (str "exactly one console.error for one refusal; got " (pr-str console)))
        (let [[prefix summary :as args] (first console)]
          (is (= 2 (count args))
              (str "prefix + summary only; got " (count args) " arguments"))
          (is (= "[re-frame2]" prefix))
          (is (re-find #"^:rf\.error/handler-exception — kaboom \(event :fu75\.console/throws, frame :rf/default" summary)
              (str "the category, the message and the ids; got " (pr-str summary))))
        (is (zero? report-error))))))

;; ===========================================================================
;; No-silent-swallow warnings — every host, while no :trace listener exists
;; ===========================================================================

(defn- capture-console-warn
  "Run `thunk` with `console.warn` swapped for a recorder, restoring it.
  Returns the ARGUMENTS of each call."
  [thunk]
  (let [calls     (atom [])
        orig-warn (.-warn js/console)]
    (set! (.-warn js/console)
          (fn [& args] (swap! calls conj (vec args)) nil))
    (try
      (thunk)
      (finally
        (set! (.-warn js/console) orig-warn)))
    @calls))

(deftest an-unobserved-no-silent-swallow-warning-prints-one-line
  (testing "a misspelt configure! key with no :trace listener: one console.warn"
    (let [calls (capture-console-warn #(rf/configure! {:epoch-histroy {:depth 5}}))]
      (is (= 1 (count calls))
          (str "exactly one console.warn; got " (pr-str calls)))
      (let [[prefix line :as args] (first calls)]
        (is (= 2 (count args)))
        (is (= "[re-frame2]" prefix))
        (is (re-find #"^:rf\.warning/unknown-configure-key — .*:epoch-histroy" line)
            (str "the op, then the warning's own :reason; got " (pr-str line))))))
  (testing "a :trace listener receives it instead, and the line stands down"
    (rf/register-listener! :trace ::seen (fn [_] nil))
    (let [calls (capture-console-warn #(rf/configure! {:epoch-histroy {:depth 5}}))]
      (rf/unregister-listener! :trace ::seen)
      (is (empty? calls) (str "nothing printed; got " (pr-str calls))))))
