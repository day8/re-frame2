(ns re-frame.error-emit-dev-console-dom-cljs-test
  "rf2-fu75 — the unowned-error dev console fallback in
  `re-frame.error-emit`.

  RULED (2026-08-13): an untooled dev build DOES surface a framework
  refusal, via a dev-build console.error fallback that fires ONLY when
  NOTHING ROUTED the record. Not `reportError`; no new API knob;
  browser-hosted dev builds only.

  This suite is the two-way control the ruling asks for, and BOTH halves are
  the contract:

    - with nothing owning it a promoted refusal reaches the console exactly
      once, as `[\"[re-frame2]\" <summary> <record> <exception>]` — a readable
      SUMMARY LINE first, then the structured record and the ORIGINAL
      exception as separate console ARGUMENTS;
    - with ANY `:errors` listener attached the fallback is SILENT — that is
      what stops it being the nag-diagnostic the ruling avoided. Ownership
      is corpus-wide and implicit: a listener that ignores the category, or
      one that itself throws, still owns the stream. Dropping the last
      listener resumes the fallback.

  rf2-kuky.18 added the SECOND ownership arm, and its block sits beside the
  listener block below: the record's owning frame having routed it to a
  REGISTERED `:observability :errors` sink also owns it, frame-scoped rather
  than corpus-wide. The listener arm above is unchanged in every respect.

  ## The summary argument (rf2-6sqv)

  The record rides as a VALUE, and that is right — but for a while it was
  the ONLY thing passed, and a CLJS map is not a JS object. Chrome renders
  its interior fields, so an ordinary page load of a routed example showed
  `[re-frame2] {meta: null, cnt: 7, arr: Array(14), __hash: null, …}` and no
  message at all. Measured in headless Chromium, not asserted in a unit test.

  So a readable line now LEADS, and the assertions below pin both halves of
  that: the summary is text and names the category, AND the record and
  exception still ride as their own arguments so a structured consumer and
  the DevTools inspector are unaffected. The summary composes no new error
  prose — it is the exception's `ex-message`, else the record's `:reason`,
  else the bare category keyword.

  And, in every case, ZERO `reportError` calls. `reportError` reports \"in
  the same fashion as an unhandled exception\" (HTML Standard), which
  Chromium turns into a `pageerror` — and
  `implementation/scripts/run-browser-tests.cjs` fails an otherwise-green
  run on any `pageerror` while treating console output as diagnostic-only
  (\"only pageerror is fatal\", rf2-mwx08). Suites elsewhere in this bundle
  exercise promoted refusals deliberately, so the no-`reportError`
  assertion is what keeps this addition off the runner's fatal channel.

  ## Why the ns is `-dom-cljs-test`

  The `-dom-cljs-test$` suffix puts this namespace on the `:browser-test`
  build (the only lane with a real browser host, where the fallback is
  live), and the broader `cljs-test$` regexp ALSO puts it on the always-on
  `:node-test` build — where it runs the other half of the host boundary:
  Node-targeted CLJS has a `console` too and must stay listener-only, so
  the Node lane asserts SILENCE for the same refusal. One file, both sides
  of the boundary, neither able to drift from the other.

  The JVM / SSR lane is listener-only by the same rule and needs no
  counterpart here: `#?(:clj …)` in `report-unowned-error!` is `nil`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            ;; rf2-xpd8: the app-db rejection arm below needs the OPTIONAL
            ;; schemas artefact actually loaded — without the require,
            ;; `reg-app-schema` writes into a registry no validator consults
            ;; and the whole deftest passes vacuously.
            [re-frame.schemas]
            ;; rf2-vkn8: the machine-data rollback arm below drives a real
            ;; machine, so the machines artefact has to be LOADED — its
            ;; late-bind hooks are what the candidate walker resolves, and
            ;; without them the deftest would pass vacuously.
            [re-frame.machines]
            [re-frame.observability :as rf.observability]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                ;; The listener registry is a `defonce` atom that survives
                ;; test re-runs, and EMPTINESS is the whole condition under
                ;; test here — a listener leaked from a sibling test would
                ;; silently invert every assertion below.
                (rf.error-emit/clear-error-listeners!)
                ;; The sink registry is the SECOND ownership arm and a
                ;; `defonce` atom for the same reason (rf2-kuky.18), so a
                ;; sink leaked from a sibling test silences the fallback
                ;; just as invisibly.
                (rf.observability/clear-observability-sinks!))}))

(defn- browser?
  "True only on a real DOM host. `js/document` presence is the same
  discriminator the fallback itself uses."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- capture-console
  "Run `thunk` with `console.error` swapped for a recorder and
  `globalThis.reportError` swapped for a counter. Returns

      {:console      [[arg …] …]   ;; one entry per call, ARGUMENTS not text
       :report-error <int>}

  Restores both, including when `thunk` throws. Arguments are kept
  unflattened on purpose: the contract is what the framework PASSES, not
  how a console chooses to render it."
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
                (fn [_ _] (throw (ex-info "kaboom" {:cause :test}))))
  (rf/reg-event :fu75.console/foreign-fx
                (fn [_ _] {:db {} :fu75.console/not-an-fx-key 1})))

;; ===========================================================================
;; EMPTY REGISTRY — the fallback fires, exactly once, with the right arguments
;; ===========================================================================

(deftest unowned-refusal-reaches-the-dev-console
  (when (browser?)
    (register-refusal-handlers!)
    (testing "a throwing handler — one structured diagnostic, no reportError"
      (let [{:keys [console report-error]}
            (capture-console #(rf/dispatch-sync [:fu75.console/throws]))]
        (is (= 1 (count console))
            (str "exactly one console.error for one refusal; got "
                 (pr-str (mapv first console))))
        (let [[prefix summary record ex] (first console)]
          (is (= "[re-frame2]" prefix) "the stable prefix leads")
          (is (string? summary)
              "a READABLE line comes before the record (rf2-6sqv) — without it
               Chrome renders the CLJS map's interior fields and the reader
               sees no message at all")
          (is (re-find #"^:rf\.error/handler-exception\b" summary)
              (str "the summary names the category first — the greppable "
                   "discriminator; got " (pr-str summary)))
          (is (re-find #"kaboom" summary)
              (str "and carries the exception's OWN message, not a synthesised "
                   "one; got " (pr-str summary)))
          (is (map? record) "the structured record still rides as its OWN argument")
          (is (= :rf.error/handler-exception (:error record)))
          (is (= :fu75.console/throws (:event-id record)))
          (is (= [:fu75.console/throws] (:event record)))
          (is (= :rf/default (:frame record)))
          (is (identical? ex (:exception record))
              "the ORIGINAL exception object rides as its own argument")
          (is (= "kaboom" (ex-message ex))
              "and it is the handler's exception, not a synthesised one"))
        (is (zero? report-error)
            "no reportError — so no window `error` event and no pageerror")))

    (testing "the effect-map envelope refusal — same shape"
      (let [{:keys [console report-error]}
            (capture-console #(rf/dispatch-sync [:fu75.console/foreign-fx]))]
        (is (= 1 (count console)))
        (let [[prefix summary record] (first console)]
          (is (= "[re-frame2]" prefix))
          (is (re-find #"^:rf\.error/effect-map-shape\b" summary))
          (is (= :rf.error/effect-map-shape (:error record))))
        (is (zero? report-error))))

    (testing "an unregistered event id — the typo case. It carries NO
              exception, so there is no fourth argument; the summary still
              names the category, which is what a reader greps"
      (let [{:keys [console report-error]}
            (capture-console #(rf/dispatch-sync [:fu75.console/nothing-here]))]
        (is (= 1 (count console)))
        (let [args (first console)]
          (is (= 3 (count args))
              (str "prefix + summary + record — nothing synthesised to stand in "
                   "for the absent exception; got " (count args) " arguments"))
          (is (= "[re-frame2]" (first args)))
          (is (= ":rf.error/no-such-handler" (second args))
              (str "no exception and no :reason on this category, so the summary "
                   "is the bare category keyword — never an empty string, never "
                   "invented prose; got " (pr-str (second args))))
          (is (= :rf.error/no-such-handler (:error (nth args 2))))
          (is (nil? (:exception (nth args 2)))))
        (is (zero? report-error))))))

(deftest unowned-diagnostic-does-not-change-control-flow
  (when (browser?)
    (register-refusal-handlers!)
    (let [outcome (atom nil)]
      (capture-console
        (fn []
          (reset! outcome
                  (try (rf/dispatch-sync [:fu75.console/throws]) ::returned
                       (catch :default e e)))))
      (is (= ::returned @outcome)
          "the refusal was still CAPTURED — printing it re-raises nothing"))))

;; ===========================================================================
;; NON-EVENT UNION RECORD — the second fan-out site follows the same rule
;; ===========================================================================

(deftest unowned-union-record-reaches-the-dev-console
  (when (browser?)
    (testing "`dispatch-error-record*`'s site — reached here through the
              genuine frame-teardown report caller, a non-event union record
              that carries no top-level `:exception`"
      (let [{:keys [console report-error]}
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
              (str "this union record carries no top-level :exception but DOES "
                   "carry a composed :reason — the summary must fall back to it "
                   "rather than stopping at the category; got " (pr-str summary)))
          (is (= :rf.error/frame-teardown-failed (:error record)))
          (is (= 1 (count (:hook-failures record)))))
        (is (zero? report-error))))))

;; ===========================================================================
;; NO-FRAME-CONTEXT — the category the rf2-6sqv measurement was OF
;; ===========================================================================
;;
;; This is the one that motivated the bead, and it is the one a summary-only
;; fix could NOT have repaired. `emit-no-frame-context!` passes no exception
;; (nothing threw — the operation is invalid), so before rf2-6sqv the record
;; was seven slots of pure metadata and the recovery ladder
;; `no-frame-context-payload` had just composed reached the always-on axis
;; nowhere at all. The console printed `{meta: null, cnt: 7, arr: Array(14),
;; …}` — `cnt: 7` being exactly that record — and no formatter reading only
;; the record could have recovered the sentence, because it was not there.
;;
;; So both halves are asserted here: the ladder is ON THE RECORD (the emit
;; site carries it through `dispatch-on-error!`'s existing attribution seam),
;; and it REACHES THE CONSOLE LINE.

(deftest no-frame-context-carries-its-ladder-to-the-console
  (when (browser?)
    (let [payload (rf.frame/no-frame-context-payload :subscribe
                                                  {:where 'rf/subscribe})]
      (is (string? (:reason payload))
          "premise: the payload composes the ladder in the first place")

      (testing "the always-on record carries the payload's OWN :reason and
                :recovery — this category throws nothing, so without them the
                record holds no message anywhere"
        (let [seen (atom [])]
          (rf/register-listener! :errors :fu75/ladder
                                 (fn [record] (swap! seen conj record)))
          (rf.frame/emit-no-frame-context! payload)
          (rf/unregister-listener! :errors :fu75/ladder)
          (let [record (first @seen)]
            (is (= :rf.error/no-frame-context (:error record)))
            (is (= (:reason payload) (:reason record))
                "the composed ladder, verbatim — not a re-derivation")
            (is (= :supply-frame (:recovery record))
                "and the machine-readable repair beside it"))))

      (testing "and the console line leads with the category and the ladder,
                so a reader who opens devtools sees the text FIRST"
        (rf.error-emit/clear-error-listeners!)
        (let [{:keys [console report-error]}
              (capture-console #(rf.frame/emit-no-frame-context! payload))]
          (is (= 1 (count console)))
          (let [args (first console)
                [prefix summary record] args]
            (is (= 3 (count args))
                (str "prefix + summary + record; no exception on this "
                     "category. Got " (count args)))
            (is (= "[re-frame2]" prefix))
            (is (re-find #"^:rf\.error/no-frame-context\b" summary))
            (is (re-find #"no frame context" summary)
                (str "the ladder itself reaches the line — this is the exact "
                     "text the rf2-6sqv page load could not show; got "
                     (pr-str summary)))
            (is (map? record) "and the record still rides for the inspector"))
          (is (zero? report-error)))))))

(deftest bad-frame-provider-arg-carries-its-reason-too
  (when (browser?)
    (testing "`emit-bad-frame-provider-arg!` says it MIRRORS
              `emit-no-frame-context!`, and it has the same shape for the
              same reason — no exception, a composed :reason on the payload.
              Pinned so the two cannot drift into one carrying its message
              and the other not"
      (rf.error-emit/clear-error-listeners!)
      (let [payload (rf.frame/bad-frame-provider-arg-payload
                      "not-a-frame" {:where 'rf/frame-provider})
            {:keys [console]}
            (capture-console #(rf.frame/emit-bad-frame-provider-arg! payload))]
        (is (= 1 (count console)))
        (let [[_ summary record] (first console)]
          (is (re-find #"^:rf\.error/bad-frame-provider-arg\b" summary))
          (is (re-find #"must be a frame id keyword" summary)
              (str "the payload's own sentence reaches the line; got "
                   (pr-str summary)))
          (is (= (:reason payload) (:reason record))
              "and verbatim onto the record for an off-box shipper"))))))

;; ===========================================================================
;; OWNED — a listener suppresses the fallback, on BOTH fan-out sites
;; ===========================================================================

(deftest an-attached-listener-suppresses-the-fallback
  (when (browser?)
    (register-refusal-handlers!)
    (let [seen (atom [])]
      (rf/register-listener! :errors :fu75/owner
                            (fn [record] (swap! seen conj record)))
      (testing "the listener receives the record exactly once and the console
                stays silent"
        (let [{:keys [console report-error]}
              (capture-console #(rf/dispatch-sync [:fu75.console/throws]))]
          (is (= [:rf.error/handler-exception] (mapv :error @seen))
              "the owner got its record")
          (is (empty? console)
              (str "and the fallback stayed quiet; got " (pr-str console)))
          (is (zero? report-error))))

      (testing "the union-record site is suppressed by the same ownership"
        (reset! seen [])
        (let [{:keys [console]}
              (capture-console
                (fn []
                  (rf.error-emit/dispatch-frame-teardown-report!
                    :rf/default
                    [{:hook :fu75/step :where :safe-call-hook!}]
                    1234)))]
          (is (= [:rf.error/frame-teardown-failed] (mapv :error @seen)))
          (is (empty? console)))))))

(deftest ownership-is-implicit-and-corpus-wide
  (when (browser?)
    (register-refusal-handlers!)
    (testing "a listener that IGNORES the category still owns the stream —
              ownership is the registration, not the handling"
      (rf/register-listener! :errors :fu75/indifferent (fn [_record] nil))
      (let [{:keys [console]}
            (capture-console #(rf/dispatch-sync [:fu75.console/throws]))]
        (is (empty? console))))

    (testing "and so does one that THROWS — the substrate swallows the
              listener throw, and the fallback must not read that as
              nobody-owns-it"
      (rf.error-emit/clear-error-listeners!)
      (rf/register-listener! :errors :fu75/broken
                            (fn [_record] (throw (ex-info "listener boom" {}))))
      (let [{:keys [console]}
            (capture-console #(rf/dispatch-sync [:fu75.console/throws]))]
        (is (empty? console))))))

(deftest dropping-the-last-listener-resumes-the-fallback
  (when (browser?)
    (register-refusal-handlers!)
    (rf/register-listener! :errors :fu75/owner (fn [_record] nil))
    (let [owned (capture-console #(rf/dispatch-sync [:fu75.console/throws]))]
      (is (empty? (:console owned)) "quiet while owned"))
    (rf/unregister-listener! :errors :fu75/owner)
    (let [unowned (capture-console #(rf/dispatch-sync [:fu75.console/throws]))]
      (is (= 1 (count (:console unowned)))
          "the registry is empty again, so the fallback resumes")
      (is (= "[re-frame2]" (ffirst (:console unowned))))
      (is (zero? (:report-error unowned))))))

;; ===========================================================================
;; OWNED BY THE FRAME'S SINK POLICY — the second ownership arm (rf2-kuky.18)
;; ===========================================================================
;;
;; The fallback fires when NOTHING ROUTED THIS RECORD, which is two arms, not
;; one. Arm (a) — any corpus-wide `:errors` listener — is the block above and
;; is unchanged. Arm (b) is here: the record's OWNING FRAME declared an
;; `:observability :errors` policy and at least one of its entries resolved to
;; a REGISTERED sink fn, which was invoked. That is the NORMAL production
;; door (Spec 015 §Frame-owned observability sink policy), and keying the
;; fallback on the listener registry alone meant a frame whose sink already
;; had the record still got a console line — while a *listener* that never
;; looked at the category silenced records belonging to frames it had never
;; heard of.
;;
;; The two arms differ in SCOPE on purpose: a listener owns corpus-wide, a
;; sink policy owns only the frame that declared it. So a sibling frame with
;; no policy on the same page keeps its console line — the property the
;; page-wide claim could not offer, and the reason a no-op listener
;; registered purely to buy silence is no longer anybody's idiom.
;;
;; "Routed" is exact: DELIVERED to a registered sink fn. A policy naming a
;; sink the app never registered routes nowhere and does NOT own the record
;; (fail-visible — the console is the only place it would otherwise appear).
;; A registered sink that THROWS does own it: the sink author has the record,
;; and swallowing their throw must not read as nobody-owns-it — the same
;; posture `ownership-is-implicit-and-corpus-wide` pins for a throwing
;; listener.

(defn- register-sink-refusal!
  "Register a throwing handler on `frame-id`, whose frame declares
  `entries` as its `:observability :errors` policy. Returns nil."
  [frame-id entries]
  (rf/make-frame (cond-> {:id frame-id :doc "rf2-kuky.18 sink-ownership witness"}
                   (some? entries) (assoc :observability {:errors entries})))
  (rf/reg-event :fu75.sink/throws
                {:frame frame-id}
                (fn [_ _] (throw (ex-info "sink-arm kaboom" {:cause :test}))))
  nil)

(deftest a-registered-frame-sink-suppresses-the-fallback
  (when (browser?)
    (testing "the frame's :errors policy delivered the record to a REGISTERED
              sink, so the record was routed and the console stays silent —
              with NO :errors listener anywhere"
      (let [seen (atom [])]
        ;; Arm (a) must be provably OUT of the picture, or the silence below
        ;; could be a leaked listener rather than the sink route.
        (rf.error-emit/clear-error-listeners!)
        (rf/register-observability-sink! :fu75.sink/collector
                                         (fn [r] (swap! seen conj r)))
        (register-sink-refusal! :fu75.sink/frame [{:sink :fu75.sink/collector}])
        (let [{:keys [console report-error]}
              (capture-console
                #(rf/dispatch-sync [:fu75.sink/throws] {:frame :fu75.sink/frame}))]
          (is (= 1 (count @seen))
              (str "premise: the sink genuinely received the record — without "
                   "this the silence below would be vacuous; got " (count @seen)))
          (is (= :rf.observe/error (:kind (first @seen))))
          (is (empty? console)
              (str "the record was routed, so nothing is unowned; got "
                   (pr-str console)))
          (is (zero? report-error)))))))

(deftest a-policy-naming-an-UNREGISTERED-sink-still-prints
  (when (browser?)
    (testing "declaring a policy is not routing. The named sink was never
              registered, so `deliver-to-sink!` no-op'd and the record went
              NOWHERE — fail-visible: the console is the only channel left"
      (register-sink-refusal! :fu75.sink/orphan [{:sink :fu75.sink/never-wired}])
      (let [{:keys [console report-error]}
            (capture-console
              #(rf/dispatch-sync [:fu75.sink/throws] {:frame :fu75.sink/orphan}))]
        (is (= 1 (count console))
            (str "an unwired sink id must not buy silence; got " (pr-str console)))
        (let [[prefix summary record] (first console)]
          (is (= "[re-frame2]" prefix))
          (is (re-find #"^:rf\.error/handler-exception\b" summary))
          (is (= :fu75.sink/orphan (:frame record))))
        (is (zero? report-error))))))

(deftest sink-ownership-is-frame-scoped-not-page-wide
  (when (browser?)
    (testing "one frame's policy silences ONLY its own records. A sibling
              frame on the same page that declared nothing keeps its console
              line — this is the property a corpus-wide listener claim could
              not express, and the whole reason the key moved"
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
          (is (empty? (:console owned))
              (str "the frame that routed stays quiet; got "
                   (pr-str (:console owned))))
          (is (= 1 (count (:console bare)))
              (str "the frame that routed NOTHING still prints; got "
                   (pr-str (:console bare))))
          (is (= :fu75.sink/bare (:frame (nth (first (:console bare)) 2)))
              "and the line that printed is the bare frame's own record"))))))

(deftest a-throwing-registered-sink-still-owns-the-record
  (when (browser?)
    (testing "the sink was invoked and threw. `deliver-to-sink!` swallows it
              for sibling isolation, and that swallow must not read as
              nobody-owns-it — the sink author HAS the record. Same posture
              as the throwing listener in `ownership-is-implicit-and-corpus-wide`"
      (let [calls (atom 0)]
        (rf/register-observability-sink! :fu75.sink/broken
                                         (fn [_r]
                                           (swap! calls inc)
                                           (throw (ex-info "sink boom" {}))))
        (register-sink-refusal! :fu75.sink/throwing [{:sink :fu75.sink/broken}])
        (let [{:keys [console report-error]}
              (capture-console
                #(rf/dispatch-sync [:fu75.sink/throws] {:frame :fu75.sink/throwing}))]
          (is (= 1 @calls) "premise: the sink really was invoked")
          (is (empty? console)
              (str "delivery is ownership, whatever the sink then did; got "
                   (pr-str console)))
          (is (zero? report-error)))))))

(deftest a-frameless-record-has-no-sink-arm-and-still-prints
  (when (browser?)
    (testing "a `:frame nil` record carries no frame-owned policy BY
              DEFINITION, so arm (b) can never fire for it. With no listener
              either, the fallback is unchanged — this is the untooled case
              rf2-fu75 exists for, and the arm added here must not erode it"
      (rf/register-observability-sink! :fu75.sink/collector (fn [_r] nil))
      (let [payload (rf.frame/no-frame-context-payload :subscribe
                                                       {:where 'rf/subscribe})
            {:keys [console report-error]}
            (capture-console #(rf.frame/emit-no-frame-context! payload))]
        (is (= 1 (count console))
            (str "a frameless record still reaches the console; got "
                 (pr-str console)))
        (let [[prefix _summary record] (first console)]
          (is (= "[re-frame2]" prefix))
          (is (nil? (:frame record))
              "premise: this record really is frameless"))
        (is (zero? report-error))))))

;; ===========================================================================
;; THE APP-DB CANDIDATE REJECTION — rf2-xpd8's whole point (PR1)
;; ===========================================================================
;;
;; This is the refusal the rf2-fu75 mechanism could not reach, and the reason
;; is structural rather than an oversight: a rejected `app-db` candidate
;; emitted on the DEV TRACE only, and the fallback hangs off the `:errors`
;; stream. So the one refusal that discards a WHOLE transaction was the one
;; refusal an untooled dev build could not see — measured on a live page as an
;; application-wide permanent rollback loop producing 0 page errors, 0 console
;; messages of any level, 0 failed requests, and an empty screen.
;;
;; rf2-xpd8 routes that rejection onto the `:errors` stream from inside the
;; validator's own `debug-enabled?` gate, and this fallback then fires FOR
;; FREE — which is what makes Option A dominate a second printer in the
;; validator. So the assertions here are about the seam, not about a new
;; printer: one line per failing registration, naming the registered path and
;; the TYPE of what it found, and silent the moment anything owns the stream.

(defn- register-rollback-app! []
  (rf/make-frame {:id :fu75.rollback/frame :doc "rf2-xpd8 console witness"})
  (rf/with-frame :fu75.rollback/frame
    (rf/reg-app-schema [:articles] :int)
    (rf/reg-app-schema [:tags]     :int))
  (rf/reg-event :fu75.rollback/write
                (fn [_ _] {:db {:unrelated 1}})))

(deftest unowned-app-db-rollback-reaches-the-dev-console
  (when (and (browser?)
             (some? (rf.late-bind/get-fn :schemas/validate-app-schema!)))
    (register-rollback-app!)
    (testing "an EMPTY registry — one console.error per FAILING registration,
              each naming the registered path and what was found there"
      (let [{:keys [console report-error]}
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
        (doseq [[prefix summary record] rollback]
          (is (= "[re-frame2]" prefix))
          (is (re-find #"^:rf\.error/schema-validation-failure\b" summary)
              (str "the summary names the category first; got " (pr-str summary)))
          (is (re-find #"got nil" summary)
              (str "and ends with the TYPE of what it found — the half that "
                   "makes a wall of these lines self-diagnosing; got "
                   (pr-str summary)))
          (is (= :app-db (:where record)))
          (is (true? (:rollback? record)))
          (is (nil? (:value record))
              "the offending value stays on the dev trace"))
        (is (= #{[:articles] [:tags]}
               (set (map (fn [args] (:registered-path (nth args 2))) rollback)))
            "each line names a DISTINCT registration, so seventeen of them read
             as seventeen broken declarations rather than one repeated noise")
        (is (zero? report-error)
            "console.error, never reportError — a rejected candidate is a
             framework verdict, not an unhandled exception")))

    (testing "ANY listener owns the stream and the fallback goes quiet — the
              rf2-fu75 ownership rule applies unchanged to this category"
      (rf.error-emit/clear-error-listeners!)
      (let [seen (atom [])]
        (rf/register-listener! :errors :fu75/rollback-owner
                               (fn [r] (swap! seen conj r)))
        (let [{:keys [console]}
              (capture-console
                #(rf/dispatch-sync [:fu75.rollback/write]
                                   {:frame :fu75.rollback/frame}))]
          (is (= 2 (count (filter #(= :rf.error/schema-validation-failure (:error %))
                                  @seen)))
              "the owner got both records")
          (is (empty? console)
              (str "and nothing printed; got " (pr-str console))))
        (rf/unregister-listener! :errors :fu75/rollback-owner)))))

;; ===========================================================================
;; THE OTHER THREE ROLLBACK ARMS — rf2-vkn8 (PR2)
;; ===========================================================================
;;
;; One ruling, four `:rollback? true` producers. PR1 wired `:where :app-db`
;; above and this fallback then fired for it FOR FREE — no second printer, no
;; second gate. That is the claim these two deftests keep honest for the rest
;; of the set: a machine transaction discarded by a `[:schemas :data]`
;; violation, and a candidate rejected because a REGISTERED app-db schema is
;; itself malformed, must reach the same console by the same route, and must
;; go equally quiet the moment anything owns the `:errors` stream.

(def ^:private vkn8-machine-id :fu75.rollback/machine)

(defn- register-machine-rollback-app! []
  (rf/make-frame {:id :fu75.machine/frame :doc "rf2-vkn8 console witness"})
  (rf/reg-machine vkn8-machine-id
    {:initial :idle
     :data    {:n 1}
     :schemas {:data [:map [:n pos-int?]]}
     :actions {:break (fn [_] {:data {:n 0}})}
     :states  {:idle {:on {:break {:target :idle :action :break}}}}}))

(defn- register-malformed-rollback-app! []
  (rf/make-frame {:id :fu75.malformed/frame :doc "rf2-vkn8 console witness"})
  ;; A childless `[:vector]` registers cleanly (Malli validates schema FORMS
  ;; lazily) and then makes the registered validator THROW on the first
  ;; candidate validation.
  (rf/with-frame :fu75.malformed/frame
    (rf/reg-app-schema [:broken] [:vector]))
  (rf/reg-event :fu75.malformed/write (fn [_ _] {:db {:broken [1]}})))

(deftest unowned-machine-data-rollback-reaches-the-dev-console
  (when (and (browser?)
             (some? (rf.late-bind/get-fn :machines/validate-machine-data!)))
    (register-machine-rollback-app!)
    ;; Settle the machine with its conforming initial `:data` first, so the
    ;; line under test comes from the MACROSTEP and not the bootstrap.
    (rf/dispatch-sync [vkn8-machine-id [:noop]] {:frame :fu75.machine/frame})

    (testing "an EMPTY registry — one console.error naming the machine whose
              `:data` broke its schema and the lifecycle phase it broke at"
      (let [{:keys [console report-error]}
            (capture-console
              #(rf/dispatch-sync [vkn8-machine-id [:break]]
                                 {:frame :fu75.machine/frame}))
            rollback (filterv (fn [args]
                                (= :machine-data (:where (nth args 2 nil))))
                              console)]
        (is (= 1 (count rollback))
            (str "one line for the rejected machine transition; got "
                 (pr-str (mapv second console))))
        (let [[prefix summary record] (first rollback)]
          (is (= "[re-frame2]" prefix))
          (is (re-find #"^:rf\.error/schema-validation-failure\b" summary)
              (str "the summary names the category first; got " (pr-str summary)))
          (is (re-find #":macrostep" summary)
              (str "and the lifecycle phase, which is where the blast radius "
                   "is read off; got " (pr-str summary)))
          (is (= vkn8-machine-id (:machine-id record)))
          (is (= :fu75.machine/frame (:frame record))
              "the frame threaded by rf2-vkn8 — without it the record could
               never reach this frame's :observability :errors sink")
          (is (true? (:rollback? record)))
          (is (nil? (:value record))
              "the machine's `:data` stays on the dev trace"))
        (is (zero? report-error)
            "console.error, never reportError — a rejected candidate is a
             framework verdict, not an unhandled exception")))

    (testing "ANY listener owns the stream and the fallback goes quiet"
      (rf.error-emit/clear-error-listeners!)
      (let [seen (atom [])]
        (rf/register-listener! :errors :fu75/machine-owner
                               (fn [r] (swap! seen conj r)))
        (let [{:keys [console]}
              (capture-console
                #(rf/dispatch-sync [vkn8-machine-id [:break]]
                                   {:frame :fu75.machine/frame}))]
          (is (= 1 (count (filter #(= :machine-data (:where %)) @seen)))
              "the owner got the record")
          (is (empty? console)
              (str "and nothing printed; got " (pr-str console))))
        (rf/unregister-listener! :errors :fu75/machine-owner)))))

(deftest unowned-malformed-schema-rollback-reaches-the-dev-console
  (when (and (browser?)
             (some? (rf.late-bind/get-fn :schemas/validate-app-schema!)))
    (register-malformed-rollback-app!)

    (testing "an EMPTY registry — one console.error naming the registration
              the developer has to fix"
      (let [{:keys [console report-error]}
            (capture-console
              #(rf/dispatch-sync [:fu75.malformed/write]
                                 {:frame :fu75.malformed/frame}))
            rollback (filterv (fn [args]
                                (= :rf.error/malformed-schema
                                   (:error (nth args 2 nil))))
                              console)]
        (is (= 1 (count rollback))
            (str "one line for the malformed registration; got "
                 (pr-str (mapv second console))))
        (let [[prefix summary record] (first rollback)]
          (is (= "[re-frame2]" prefix))
          (is (re-find #"^:rf\.error/malformed-schema\b" summary)
              (str "the summary names the category first; got " (pr-str summary)))
          (is (= [:broken] (:registered-path record)))
          (is (= :fu75.malformed/frame (:frame record)))
          (is (true? (:rollback? record)))
          (is (nil? (:schema record))
              "the malformed registration FORM stays on the dev trace — it
               `pr-str`s unbounded"))
        (is (zero? report-error))))

    (testing "ANY listener owns the stream and the fallback goes quiet"
      (rf.error-emit/clear-error-listeners!)
      (let [seen (atom [])]
        (rf/register-listener! :errors :fu75/malformed-owner
                               (fn [r] (swap! seen conj r)))
        (let [{:keys [console]}
              (capture-console
                #(rf/dispatch-sync [:fu75.malformed/write]
                                   {:frame :fu75.malformed/frame}))]
          (is (= 1 (count (filter #(= :rf.error/malformed-schema (:error %)) @seen))))
          (is (empty? console)
              (str "and nothing printed; got " (pr-str console))))
        (rf/unregister-listener! :errors :fu75/malformed-owner)))))

;; ===========================================================================
;; HOST BOUNDARY — Node-targeted CLJS stays listener-only
;; ===========================================================================

(deftest node-targeted-cljs-stays-quiet
  (when-not (browser?)
    (register-refusal-handlers!)
    (testing "same refusal, same empty registry, no DOM host: the fallback is
              a browser-DEVELOPMENT diagnostic, not a generic CLJS print. A
              Node lane's caller observes the dispatch directly and attaches
              a listener in one line, exactly as the JVM lane does."
      (let [{:keys [console report-error]}
            (capture-console #(rf/dispatch-sync [:fu75.console/throws]))]
        (is (empty? console)
            (str "no console output off a DOM host; got " (pr-str console)))
        (is (zero? report-error))))

    (testing "and the record still reaches an attached listener — the
              always-on axis is unchanged off-browser"
      (let [seen (atom [])]
        (rf/register-listener! :errors :fu75/owner
                              (fn [record] (swap! seen conj record)))
        (rf/dispatch-sync [:fu75.console/throws])
        (is (= [:rf.error/handler-exception] (mapv :error @seen)))))

    ;; rf2-vkn8: the same boundary for PR2's arms. The host rule is a property
    ;; of the FALLBACK, not of a category, so a new producer must inherit it
    ;; rather than acquire its own exemption — this is the half that would
    ;; catch a second printer being added beside the seam.
    (testing "the machine-data and malformed-schema rollbacks obey the same
              host boundary: silent off a DOM host, and still delivered to an
              attached listener"
      (when (some? (rf.late-bind/get-fn :machines/validate-machine-data!))
        (register-machine-rollback-app!)
        (rf/dispatch-sync [vkn8-machine-id [:noop]] {:frame :fu75.machine/frame})
        (rf.error-emit/clear-error-listeners!)
        (let [{:keys [console report-error]}
              (capture-console
                #(rf/dispatch-sync [vkn8-machine-id [:break]]
                                   {:frame :fu75.machine/frame}))]
          (is (empty? console)
              (str "no console output off a DOM host; got " (pr-str console)))
          (is (zero? report-error)))
        (let [seen (atom [])]
          (rf/register-listener! :errors :fu75/machine-owner
                                 (fn [r] (swap! seen conj r)))
          (rf/dispatch-sync [vkn8-machine-id [:break]] {:frame :fu75.machine/frame})
          (is (= 1 (count (filter #(= :machine-data (:where %)) @seen)))
              "the always-on axis is unchanged off-browser")
          (rf/unregister-listener! :errors :fu75/machine-owner)))

      (when (some? (rf.late-bind/get-fn :schemas/validate-app-schema!))
        (register-malformed-rollback-app!)
        (rf.error-emit/clear-error-listeners!)
        (let [{:keys [console report-error]}
              (capture-console
                #(rf/dispatch-sync [:fu75.malformed/write]
                                   {:frame :fu75.malformed/frame}))]
          (is (empty? console)
              (str "no console output off a DOM host; got " (pr-str console)))
          (is (zero? report-error)))
        (let [seen (atom [])]
          (rf/register-listener! :errors :fu75/malformed-owner
                                 (fn [r] (swap! seen conj r)))
          (rf/dispatch-sync [:fu75.malformed/write] {:frame :fu75.malformed/frame})
          (is (= 1 (count (filter #(= :rf.error/malformed-schema (:error %)) @seen))))
          (rf/unregister-listener! :errors :fu75/malformed-owner))))))
