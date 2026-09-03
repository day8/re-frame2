(ns re-frame.error-emit-dev-console-dom-cljs-test
  "rf2-fu75 — the unowned-error dev console fallback in
  `re-frame.error-emit`.

  RULED (2026-08-13): an untooled dev build DOES surface a framework
  refusal, via a dev-build console.error fallback that fires ONLY when the
  corpus-wide `:errors` listener registry is EMPTY. Not `reportError`; no
  new API knob; browser-hosted dev builds only.

  This suite is the two-way control the ruling asks for, and BOTH halves are
  the contract:

    - with an EMPTY registry a promoted refusal reaches the console exactly
      once, as `[\"[re-frame2]\" <summary> <record> <exception>]` — a readable
      SUMMARY LINE first, then the structured record and the ORIGINAL
      exception as separate console ARGUMENTS;
    - with ANY `:errors` listener attached the fallback is SILENT — that is
      what stops it being the nag-diagnostic the ruling avoided. Ownership
      is corpus-wide and implicit: a listener that ignores the category, or
      one that itself throws, still owns the stream. Dropping the last
      listener resumes the fallback.

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
                (rf.error-emit/clear-error-listeners!))}))

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
        (is (= [:rf.error/handler-exception] (mapv :error @seen)))))))
