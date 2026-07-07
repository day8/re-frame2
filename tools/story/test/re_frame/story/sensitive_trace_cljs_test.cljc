(ns re-frame.story.sensitive-trace-cljs-test
  "Privacy / sensitive-trace tests for re-frame2-story (rf2-bclgj).

  Per Spec 009 §Privacy (resolved by rf2-a32kd): framework-published
  trace-consuming integrations MUST default-suppress `:sensitive? true`
  events. Story is a framework-published consumer — its play-runner's
  per-frame listener, the recorder's listener, the runtime's
  capture-phase-errors listener, and the UI trace-buffer listener all
  feed accumulators / buffers that the dev panels read.

  ## Coverage

  - **Pure config**: `sensitive-event?`, `suppress-sensitive?`,
    `note-suppressed!`, `suppressed-count`, `reset-suppressed-count!`
    against the `egress-profile` (EP-0015 frame-owned egress, rf2-3t26eh).
  - **`configure!`**: the `:rf.story/egress-profile` opts key wires
    through to the config atom.
  - **Play listener**: the per-frame trace listener (the Spec 009 privacy
    egress seam, rf2-luzky) default-drops sensitive events at the gate
    (bumping the redaction counter) before its handler-exception capture
    runs.
  - **Recorder listener**: a sensitive event does not land in the
    recorder's captured-events vector.

  Runs on both the JVM (`clojure -M:test`) and the CLJS node-test
  build (shadow's `:node-test` target; ns ends in `-test.cljc` and
  picks up via the test-runner's regex).  The trace-panel listener
  ships as `.cljs` only — its coverage rides on the same
  `suppress-sensitive?` helper exercised here, and the panel-level
  redaction indicator is verified by the CLJS ui-cljs test arm."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story            :as story]
            [re-frame.story.config     :as config]
            [re-frame.story.play       :as play]
            [re-frame.story.recorder   :as recorder]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn reset-config! [f]
  ;; Always restore the session-pin to the redacting default AND clear every
  ;; per-frame override (rf2-6z4znr) before AND after every test so a failing
  ;; test can't poison the next one.
  (config/reset-all!)
  (try
    (f)
    (finally
      (config/reset-all!))))

(use-fixtures :each reset-config!)

;; ---------------------------------------------------------------------------
;; Helpers — synthetic trace events
;; ---------------------------------------------------------------------------

(defn- sensitive-dispatch-event
  "Build a `:rf.event/dispatched` trace event flagged `:sensitive? true`.
  Mirrors the runtime's emit shape per Spec 009 §Privacy."
  [frame-id event]
  {:op-type    :rf.event
   :operation  :rf.event/dispatched
   :id         1
   :time       1700000000000
   :sensitive? true
   :tags       {:rf.trace/dispatch-id 1
                :frame                frame-id
                :rf.trace/event-id    (first event)
                :rf.event/v           event}})

(defn- plain-dispatch-event
  "Build an ordinary `:rf.event/dispatched` trace event (no `:sensitive?`)."
  [frame-id event]
  {:op-type   :rf.event
   :operation :rf.event/dispatched
   :id        2
   :time      1700000000010
   :tags      {:rf.trace/dispatch-id 2
               :frame                frame-id
               :rf.trace/event-id    (first event)
               :rf.event/v           event}})

(defn- sensitive-warning-event
  "Build a `:warning` trace event flagged `:sensitive? true`."
  [frame-id]
  {:op-type    :warning
   :operation  :rf.warning/example
   :id         3
   :time       1700000000020
   :sensitive? true
   :tags       {:rf.trace/dispatch-id 3
                :frame                frame-id}})

(defn- handler-exception-event
  "Build a `:rf.error/handler-exception` trace event for `frame-id`. The
  play-listener's surviving non-privacy job is to capture these into
  `play/pending-exceptions`. `sensitive?` flags whether the privacy gate
  should drop it before capture."
  [frame-id sensitive?]
  (cond-> {:op-type   :error
           :operation :rf.error/handler-exception
           :id        4
           :time      1700000000030
           :tags      {:rf.trace/dispatch-id 4
                       :frame                frame-id
                       :event                [:auth/login]
                       :exception-message    "boom"}}
    sensitive? (assoc :sensitive? true)))

;; ---------------------------------------------------------------------------
;; Pure config helpers
;; ---------------------------------------------------------------------------

(deftest sensitive-event?-recognises-flag
  (testing "events with :sensitive? true are recognised"
    (is (config/sensitive-event? (sensitive-dispatch-event :v/x [:auth/login])))
    (is (config/sensitive-event? {:sensitive? true})))
  (testing "events without :sensitive? or with :sensitive? false are not"
    (is (not (config/sensitive-event? (plain-dispatch-event :v/x [:counter/inc]))))
    (is (not (config/sensitive-event? {})))
    (is (not (config/sensitive-event? {:sensitive? false})))
    (is (not (config/sensitive-event? {:sensitive? nil}))))
  (testing "non-map inputs are tolerated"
    (is (not (config/sensitive-event? nil)))
    (is (not (config/sensitive-event? "trace event")))
    (is (not (config/sensitive-event? 42)))))

(deftest suppress-sensitive?-default-suppresses
  (testing "by default (:rf.egress/local-redacted) sensitive events are suppressed"
    (is (= :rf.egress/local-redacted @config/session-egress-profile))
    (is (false? (config/include-sensitive? nil)))
    (is (true?  (config/suppress-sensitive?
                  (sensitive-dispatch-event :v/x [:auth/login])))))
  (testing "non-sensitive events are never suppressed"
    (is (false? (config/suppress-sensitive?
                  (plain-dispatch-event :v/x [:counter/inc]))))))

(deftest suppress-sensitive?-opts-out-under-local-raw
  (testing "under :rf.egress/local-raw, sensitive events are NOT suppressed"
    (config/set-egress-profile! :rf.egress/local-raw)
    (is (= :rf.egress/local-raw @config/session-egress-profile))
    (is (true? (config/include-sensitive? nil)))
    (is (false? (config/suppress-sensitive?
                  (sensitive-dispatch-event :v/x [:auth/login])))))
  (testing "non-sensitive events remain unsuppressed regardless of profile"
    (config/set-egress-profile! :rf.egress/local-raw)
    (is (false? (config/suppress-sensitive?
                  (plain-dispatch-event :v/x [:counter/inc]))))))

(deftest configure!-wires-egress-profile
  (testing "story/configure! routes :rf.story/egress-profile to the config atom"
    (is (= :rf.egress/local-redacted @config/session-egress-profile) "default is local-redacted")
    (story/configure! {:rf.story/egress-profile :rf.egress/local-raw})
    (is (= :rf.egress/local-raw @config/session-egress-profile)
        "opt-in flips the profile to the trusted-local boundary")
    (is (true? (config/include-sensitive? nil)))
    (story/configure! {:rf.story/egress-profile :rf.egress/local-redacted})
    (is (= :rf.egress/local-redacted @config/session-egress-profile)
        "passing local-redacted narrows back")
    (is (false? (config/include-sensitive? nil))))
  (testing "configure! without the key leaves the profile untouched"
    (config/set-egress-profile! :rf.egress/local-raw)
    (story/configure! {:rf.story/editor :cursor})
    (is (= :rf.egress/local-raw @config/session-egress-profile)
        "the unrelated key didn't reset the profile")))

(deftest configure!-rejects-unknown-egress-profile
  (testing "an unknown profile keyword raises :rf.error/unknown-egress-profile (closed enum)"
    (let [e (try
              (story/configure! {:rf.story/egress-profile :rf.egress/not-a-profile})
              nil
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (some? e) "expected ex-info to be thrown")
      ;; Canonical thrown-error shape: a human sentence carrying the offending
      ;; profile + the trailing `[:rf.error/<id>]` token — NOT the bare keyword
      ;; string.
      (let [msg #?(:clj (.getMessage ^Exception e) :cljs (ex-message e))]
        (is (re-find #":rf.egress/not-a-profile" msg)
            "the message names the unknown profile the author passed")
        (is (re-find #"known profiles are" msg)
            "the message enumerates the known profiles")
        (is (re-find #"\[:rf.error/unknown-egress-profile\]" msg)
            "the message ends with the machine-readable error token"))
      (let [data (ex-data e)]
        (is (= :rf.error/unknown-egress-profile (:rf.error/id data)))
        (is (= 'rf.story/configure! (:where data)))))
    (is (= :rf.egress/local-redacted @config/session-egress-profile)
        "the rejected call left the profile at the redacting default")))

(deftest set-egress-profile!-fail-closed-defaults
  (testing "set-egress-profile! resets nil + non-member values to the fail-closed default"
    (config/set-egress-profile! :rf.egress/local-raw)
    (config/set-egress-profile! nil)
    (is (= :rf.egress/local-redacted @config/session-egress-profile)
        "nil resets to the redacting default")
    (config/set-egress-profile! :rf.egress/local-raw)
    (config/set-egress-profile! :not-a-profile)
    (is (= :rf.egress/local-redacted @config/session-egress-profile)
        "a non-member value coerces fail-closed (defence-in-depth)")
    (is (false? (config/include-sensitive? nil)))))

;; ---------------------------------------------------------------------------
;; Suppressed-events counter
;; ---------------------------------------------------------------------------

(deftest suppressed-count-defaults-to-zero
  (is (zero? (config/suppressed-count)))
  (is (zero? (config/suppressed-count :story.x/y))))

(deftest note-suppressed!-bumps-counter
  (config/note-suppressed! :story.x/y)
  (config/note-suppressed! :story.x/y)
  (config/note-suppressed! :story.a/b)
  (is (= 2 (config/suppressed-count :story.x/y)))
  (is (= 1 (config/suppressed-count :story.a/b)))
  (is (zero? (config/suppressed-count :story.never/seen))))

(deftest note-suppressed!-routes-nil-to-global
  (config/note-suppressed! nil)
  (config/note-suppressed! nil)
  (is (= 2 (config/suppressed-count)))
  (is (= 2 (config/suppressed-count :global))))

(deftest reset-suppressed-count!-clears
  (config/note-suppressed! :story.x/y)
  (config/note-suppressed! :story.a/b)
  (config/reset-suppressed-count! :story.x/y)
  (is (zero? (config/suppressed-count :story.x/y)))
  (is (= 1 (config/suppressed-count :story.a/b)))
  (config/reset-suppressed-count!)
  (is (zero? (config/suppressed-count :story.a/b))))

;; ---------------------------------------------------------------------------
;; Play listener — the Spec 009 §Privacy egress seam (rf2-luzky)
;;
;; rf2-luzky folded the `trace-accumulators` dev side-table away; the
;; play-listener's surviving jobs are the PRIVACY gate (default-drop
;; `:sensitive?` + bump `config/note-suppressed!`) and the synchronous
;; handler-exception capture into `play/pending-exceptions`. These tests
;; pin the privacy INVARIANT directly against those observable outputs —
;; not via the removed side-table accessors:
;;
;;   - a SENSITIVE event is dropped at the gate (the suppressed-events
;;     counter bumps; the exception capture never sees it);
;;   - a NON-sensitive event passes the gate (the counter stays zero; a
;;     handler-exception reaches `pending-exceptions`);
;;   - under `:rf.egress/local-raw` the gate is open (a sensitive event is
;;     NOT dropped and the counter stays zero).
;;
;; The listener is private; invoke the fn the `listener-for-frame` builder
;; returns directly with synthetic events (the global trace bus spans the
;; whole process).
;; ---------------------------------------------------------------------------

(defn- pending-for [frame-id]
  (get @play/pending-exceptions frame-id []))

(deftest play-listener-suppresses-sensitive-warnings
  (testing "by default a :sensitive? warning event is dropped at the privacy gate"
    (let [frame-id :story.sensitive/v
          build    @#'play/listener-for-frame
          listen   (build frame-id)
          ev       (sensitive-warning-event frame-id)]
      (swap! play/pending-exceptions assoc frame-id [])
      (listen ev)
      (is (empty? (pending-for frame-id))
          "the sensitive warning never reached the listener body")
      (is (pos? (config/suppressed-count frame-id))
          "the suppressed-events counter bumped — the redaction hint stays accurate"))))

(deftest play-listener-suppresses-sensitive-handler-exception
  (testing "by default a :sensitive? handler-exception is dropped before capture"
    (let [frame-id :story.sensitive/v
          build    @#'play/listener-for-frame
          listen   (build frame-id)
          ev       (handler-exception-event frame-id true)]
      (swap! play/pending-exceptions assoc frame-id [])
      (listen ev)
      (is (empty? (pending-for frame-id))
          "the sensitive handler-exception never reached pending-exceptions")
      (is (pos? (config/suppressed-count frame-id))
          "the suppressed-events counter bumped"))))

(deftest play-listener-passes-sensitive-when-opted-in
  (testing "under :rf.egress/local-raw the gate is open — a sensitive handler-exception is captured"
    (config/set-egress-profile! :rf.egress/local-raw)
    (let [frame-id :story.sensitive/v
          build    @#'play/listener-for-frame
          listen   (build frame-id)
          ev       (handler-exception-event frame-id true)]
      (swap! play/pending-exceptions assoc frame-id [])
      (listen ev)
      (is (= 1 (count (pending-for frame-id)))
          "the sensitive handler-exception was captured (the gate is open)")
      (is (zero? (config/suppressed-count frame-id))
          "the suppressed-events counter stays at zero"))))

(deftest play-listener-captures-non-sensitive-handler-exception
  (testing "regression: a non-sensitive handler-exception is captured under default settings"
    (let [frame-id :story.regression/v
          build    @#'play/listener-for-frame
          listen   (build frame-id)
          ev       (handler-exception-event frame-id false)]
      (swap! play/pending-exceptions assoc frame-id [])
      (listen ev)
      (is (= 1 (count (pending-for frame-id)))
          "the non-sensitive handler-exception landed in pending-exceptions")
      (is (zero? (config/suppressed-count frame-id))
          "no suppression — the counter stays at zero"))))

(deftest play-listener-ignores-non-sensitive-non-error
  (testing "a plain dispatched event is neither suppressed nor captured (no side-table to feed)"
    (let [frame-id :story.regression/v
          build    @#'play/listener-for-frame
          listen   (build frame-id)
          ev       (plain-dispatch-event frame-id [:counter/inc])]
      (swap! play/pending-exceptions assoc frame-id [])
      (listen ev)
      (is (empty? (pending-for frame-id))
          "a dispatched event is not a handler-exception — nothing captured")
      (is (zero? (config/suppressed-count frame-id))
          "non-sensitive — no suppression"))))

;; ---------------------------------------------------------------------------
;; Recorder listener — sensitive events skipped
;; ---------------------------------------------------------------------------

(deftest recorder-listener-redacts-sensitive-dispatches
  (testing "by default the recorder records-but-redacts :sensitive? events (rf2-hdadz)"
    (recorder/clear!)
    (recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'recorder/trace-listener
          ev     (sensitive-dispatch-event :story.recorder/sens
                                           [:auth/login {:password "x"}])]
      (listen ev)
      (is (= [[:rf/redacted]] (recorder/recorded-events))
          "the redacted placeholder lands in the captured trace — preserves correlation, drops payload")
      (is (pos? (config/suppressed-count :story.recorder/sens))
          "the suppressed-events counter still bumps so the UI redaction hint stays accurate"))
    (recorder/clear!)))

(deftest recorder-listener-preserves-temporal-ordering-around-redacted
  (testing "redacted placeholder sits inline between non-sensitive captures"
    (recorder/clear!)
    (recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'recorder/trace-listener]
      (listen (plain-dispatch-event     :story.recorder/sens [:counter/inc]))
      (listen (sensitive-dispatch-event :story.recorder/sens
                                        [:auth/login {:password "x"}]))
      (listen (plain-dispatch-event     :story.recorder/sens [:counter/inc])))
    (is (= [[:counter/inc] [:rf/redacted] [:counter/inc]]
           (recorder/recorded-events))
        "the redacted slot preserves the row position so dev correlation survives")
    (is (= 1 (config/suppressed-count :story.recorder/sens))
        "one redaction, one counter bump")
    (recorder/clear!)))

(deftest recorder-listener-captures-sensitive-when-opted-in
  (testing "under :rf.egress/local-raw the recorder captures :sensitive? events"
    (config/set-egress-profile! :rf.egress/local-raw)
    (recorder/clear!)
    (recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'recorder/trace-listener
          ev     (sensitive-dispatch-event :story.recorder/sens
                                           [:auth/login {:password "x"}])]
      (listen ev)
      (is (= [[:auth/login {:password "x"}]] (recorder/recorded-events))
          "the captured-events vector now has the sensitive event verbatim")
      (is (zero? (config/suppressed-count :story.recorder/sens))))
    (recorder/clear!)))

(deftest recorder-listener-frame-scoped-reveal-rf2-6z4znr
  (testing "revealing the RECORDED frame captures verbatim; revealing a sibling does NOT"
    (reset! @#'config/frame-egress-profiles {})
    (config/set-session-egress-profile! config/default-egress-profile)
    ;; reveal a DIFFERENT frame — the recording frame stays redacted
    (config/set-frame-egress-profile! :story.recorder/sibling :rf.egress/local-raw)
    (recorder/clear!)
    (recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'recorder/trace-listener]
      (listen (sensitive-dispatch-event :story.recorder/sens [:auth/login {:password "x"}]))
      (is (= [[:rf/redacted]] (recorder/recorded-events))
          "the recording frame was NOT revealed (only a sibling was) — still redacted"))
    (recorder/clear!)
    ;; now reveal the recording frame itself
    (config/set-frame-egress-profile! :story.recorder/sens :rf.egress/local-raw)
    (recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'recorder/trace-listener]
      (listen (sensitive-dispatch-event :story.recorder/sens [:auth/login {:password "x"}]))
      (is (= [[:auth/login {:password "x"}]] (recorder/recorded-events))
          "revealing the recording frame captures verbatim"))
    (recorder/clear!)
    (reset! @#'config/frame-egress-profiles {})))

(deftest play-listener-frame-scoped-reveal-rf2-6z4znr
  (testing "revealing frame A's gate does NOT open frame B's play listener"
    (reset! @#'config/frame-egress-profiles {})
    (config/set-session-egress-profile! config/default-egress-profile)
    (config/set-frame-egress-profile! :story.play/a :rf.egress/local-raw)
    (let [build @#'play/listener-for-frame]
      ;; frame A revealed — sensitive exception captured
      (let [listen (build :story.play/a)]
        (swap! play/pending-exceptions assoc :story.play/a [])
        (listen (handler-exception-event :story.play/a true))
        (is (= 1 (count (pending-for :story.play/a))) "A revealed → captured"))
      ;; frame B NOT revealed — sensitive exception dropped at the gate
      (let [listen (build :story.play/b)]
        (swap! play/pending-exceptions assoc :story.play/b [])
        (listen (handler-exception-event :story.play/b true))
        (is (empty? (pending-for :story.play/b)) "B not revealed → dropped")
        (is (pos? (config/suppressed-count :story.play/b)))))
    (reset! @#'config/frame-egress-profiles {})))

(deftest recorder-listener-still-captures-non-sensitive
  (testing "regression: ordinary events still land in the recorder under default settings"
    (recorder/clear!)
    (recorder/start-recording! :story.recorder/plain 0)
    (let [listen @#'recorder/trace-listener
          ev     (plain-dispatch-event :story.recorder/plain [:counter/inc])]
      (listen ev)
      (is (= [[:counter/inc]] (recorder/recorded-events)))
      (is (zero? (config/suppressed-count :story.recorder/plain))))
    (recorder/clear!)))

;; ---------------------------------------------------------------------------
;; rf2-cmjly3 finding 13 — recordable-event? must gate on the ORIGINAL id,
;; not the [:rf/redacted] placeholder
;;
;; The redact branch used to call `record-event!` with the FIXED
;; `redacted-event` placeholder (`[:rf/redacted]`), so `append`'s internal
;; `recordable-event?` filter ran against `[:rf/redacted]` — always
;; "recordable" (its ns "rf" matches none of `recordable-event?`'s
;; internal-namespace exclusions) — never against the ORIGINAL event id. A
;; sensitive `:rf.assert/*` / `:rf.story/*` event therefore recorded a
;; `[:rf/redacted]` row instead of being dropped, contradicting the
;; listener's own docstring ("a sensitive :rf.assert/* event still gets
;; dropped, not redacted-and-recorded, because assertions are an authored
;; not observed surface"). The fix tests `recordable-event?` on the
;; ORIGINAL `(:rf.event/v tags)` BEFORE the redact/pass fork.
;; ---------------------------------------------------------------------------

(deftest recorder-listener-drops-sensitive-non-recordable-event-rf2-cmjly3
  (testing "rf2-cmjly3 finding 13: a sensitive :rf.assert/* event is DROPPED
            entirely — no [:rf/redacted] row, and the suppressed-events
            counter (the UI's 'count of redacted rows actually shown' hint)
            does not bump for a row that was never recorded"
    (recorder/clear!)
    (recorder/start-recording! :story.recorder/sens-drop 0)
    (let [listen @#'recorder/trace-listener
          ev     (sensitive-dispatch-event :story.recorder/sens-drop
                                           [:rf.assert/path-equals [:n] 1])]
      (listen ev)
      (is (= [] (recorder/recorded-events))
          "the sensitive :rf.assert/* event is dropped — pre-fix this
           recorded a spurious [:rf/redacted] row")
      (is (zero? (config/suppressed-count :story.recorder/sens-drop))
          "no row recorded => no suppressed-count bump either"))
    (recorder/clear!)))

(deftest recorder-listener-drops-sensitive-non-recordable-story-internal-event-rf2-cmjly3
  (testing "rf2-cmjly3 finding 13 — the same drop applies to a sensitive
            :rf.story/* internal-helper event, the OTHER non-recordable
            namespace class"
    (recorder/clear!)
    (recorder/start-recording! :story.recorder/sens-drop-story 0)
    (let [listen @#'recorder/trace-listener
          ev     (sensitive-dispatch-event :story.recorder/sens-drop-story
                                           [:rf.story/lifecycle-tick])]
      (listen ev)
      (is (= [] (recorder/recorded-events)))
      (is (zero? (config/suppressed-count :story.recorder/sens-drop-story))))
    (recorder/clear!)))

(deftest recorder-listener-still-redacts-sensitive-recordable-event-rf2-cmjly3
  (testing "rf2-cmjly3 finding 13 — no regression: an ORDINARY sensitive
            user event (a recordable id) still record-but-redacts exactly
            as before the fix"
    (recorder/clear!)
    (recorder/start-recording! :story.recorder/sens-redact 0)
    (let [listen @#'recorder/trace-listener
          ev     (sensitive-dispatch-event :story.recorder/sens-redact
                                           [:auth/login {:password "x"}])]
      (listen ev)
      (is (= [[:rf/redacted]] (recorder/recorded-events)))
      (is (pos? (config/suppressed-count :story.recorder/sens-redact))))
    (recorder/clear!)))

;; ---------------------------------------------------------------------------
;; Retroactive scrub on egress-profile narrowing (rf2-lqmje / EP-0015 rf2-3t26eh)
;; ---------------------------------------------------------------------------
;;
;; Per Spec 009 §Privacy §Retroactive-scrub: narrowing the local-render
;; egress profile from a sensitive-revealing boundary (`:rf.egress/local-raw`)
;; back to the redacting default MUST clear every per-variant trace buffer.
;; The Story config layer exposes a generic callback registry that
;; `ui.trace` (CLJS-only) hooks into; this pure-data shape is JVM-runnable
;; so the algebra is covered here. The CLJS-only buffer-clear is covered in
;; `re-frame.story-ui-cljs-test`.

(deftest narrowing-profile-runs-toggle-off-callbacks-rf2-lqmje
  (testing "session-pin reveal → redact transition invokes registered callbacks (with nil frame-id)"
    (let [called?  (atom false)
          token-id ::scrub-callback-test]
      ;; rf2-6z4znr — callbacks now receive the narrowed frame-id; a
      ;; session-pin narrow passes nil (scrub-all signal).
      (config/register-toggle-off-callback! token-id (fn [_frame-id] (reset! called? true)))
      (try
        (config/set-egress-profile! :rf.egress/local-raw)
        (is (false? @called?)
            "redact → reveal must NOT invoke callbacks (no buffered sensitive risk)")
        (config/set-egress-profile! :rf.egress/local-redacted)
        (is (true? @called?)
            "reveal → redact must invoke every registered callback")
        (finally
          (config/unregister-toggle-off-callback! token-id))))))

(deftest profile-no-transition-no-callback-rf2-lqmje
  (testing "reveal → reveal and redact → redact are no-ops for the callbacks"
    (let [calls    (atom 0)
          token-id ::scrub-callback-no-transition]
      (config/register-toggle-off-callback! token-id (fn [_frame-id] (swap! calls inc)))
      (try
        (config/set-egress-profile! :rf.egress/local-redacted) ; default → redact, no transition
        (is (= 0 @calls))
        (config/set-egress-profile! :rf.egress/local-raw)
        (config/set-egress-profile! :rf.egress/local-raw)  ; reveal → reveal
        (is (= 0 @calls))
        (config/set-egress-profile! :rf.egress/local-redacted) ; reveal → redact, the only transition
        (is (= 1 @calls))
        (config/set-egress-profile! :rf.egress/local-redacted) ; redact → redact
        (is (= 1 @calls))
        (finally
          (config/unregister-toggle-off-callback! token-id))))))

(deftest narrowing-profile-callback-failure-isolated-rf2-lqmje
  (testing "one buggy callback does not prevent others from running"
    (let [other-called? (atom false)
          token-bad     ::scrub-callback-bad
          token-good    ::scrub-callback-good]
      (config/register-toggle-off-callback!
        token-bad (fn [_frame-id] (throw (ex-info "boom" {}))))
      (config/register-toggle-off-callback!
        token-good (fn [_frame-id] (reset! other-called? true)))
      (try
        (config/set-egress-profile! :rf.egress/local-raw)
        (config/set-egress-profile! :rf.egress/local-redacted)
        (is (true? @other-called?)
            "the good callback must still run after the bad one throws")
        (finally
          (config/unregister-toggle-off-callback! token-bad)
          (config/unregister-toggle-off-callback! token-good))))))

;; ---------------------------------------------------------------------------
;; Per-(tool,frame) egress visibility (EP-0015 issue 7, rf2-6z4znr)
;; ---------------------------------------------------------------------------
;;
;; The acceptance core: frame A can be local-raw while frame B stays
;; local-redacted, across every Story listener seam; narrowing one frame
;; scrubs only that frame; a frameless / unknown event fails closed.

(defn- clear-frame-overrides! []
  (reset! @#'config/frame-egress-profiles {})
  (config/set-session-egress-profile! config/default-egress-profile))

(deftest frame-egress-isolation-reveal-one-not-the-other
  (testing "revealing frame A to local-raw does NOT reveal frame B"
    (clear-frame-overrides!)
    (let [a :story.iso/a
          b :story.iso/b]
      (config/set-frame-egress-profile! a :rf.egress/local-raw)
      (is (true?  (config/include-sensitive? a)) "frame A revealed")
      (is (false? (config/include-sensitive? b)) "frame B still redacted")
      ;; a sensitive event targeting A passes; the same shape on B suppresses
      (is (false? (config/suppress-sensitive? (sensitive-dispatch-event a [:auth/login]) a)))
      (is (true?  (config/suppress-sensitive? (sensitive-dispatch-event b [:auth/login]) b)))
      ;; resolved from the event itself (single-arg arity) — same result
      (is (false? (config/suppress-sensitive? (sensitive-dispatch-event a [:auth/login]))))
      (is (true?  (config/suppress-sensitive? (sensitive-dispatch-event b [:auth/login]))))
      (clear-frame-overrides!))))

(deftest frameless-event-fails-closed
  (testing "a sensitive event with no resolvable frame fails closed even while a sibling is raw"
    (clear-frame-overrides!)
    (config/set-frame-egress-profile! :story.iso/a :rf.egress/local-raw)
    (is (false? (config/include-sensitive? nil)) "unknown frame resolves to the redacting default")
    (is (true? (config/suppress-sensitive? {:sensitive? true :tags {}}))
        "a frameless sensitive event is suppressed")
    (is (true? (config/suppress-sensitive? {:sensitive? true :tags {:frame :story.iso/never-revealed}}))
        "an unrevealed-frame sensitive event is suppressed")
    (clear-frame-overrides!)))

(deftest narrowing-one-frame-scrubs-only-that-frame
  (testing "set-frame-egress-profile! reveal → redact fires callbacks with THAT frame-id only"
    (clear-frame-overrides!)
    (let [scrubbed (atom [])
          token    ::per-frame-scrub
          a        :story.iso/a
          b        :story.iso/b]
      (config/register-toggle-off-callback! token (fn [frame-id] (swap! scrubbed conj frame-id)))
      (try
        (config/set-frame-egress-profile! a :rf.egress/local-raw)
        (config/set-frame-egress-profile! b :rf.egress/local-raw)
        (is (= [] @scrubbed) "revealing fires nothing")
        (config/set-frame-egress-profile! a :rf.egress/local-redacted)
        (is (= [a] @scrubbed) "narrowing A scrubbed A only")
        (is (true? (config/include-sensitive? b)) "B is still revealed — untouched")
        (config/set-frame-egress-profile! b :rf.egress/local-redacted)
        (is (= [a b] @scrubbed) "narrowing B then scrubbed B")
        (finally
          (config/unregister-toggle-off-callback! token)
          (clear-frame-overrides!))))))

(deftest per-frame-override-wins-over-session-pin
  (testing "a per-frame override beats the session-pin in both directions"
    (clear-frame-overrides!)
    ;; pin the session to raw (tool UX); a frame can still be narrowed below it
    (config/set-session-egress-profile! :rf.egress/local-raw)
    (is (true? (config/include-sensitive? :story.iso/inherits)) "no override → inherits the raw pin")
    (config/set-frame-egress-profile! :story.iso/locked :rf.egress/local-redacted)
    ;; local-redacted is the default → no override entry is stored, so it
    ;; inherits the raw pin too (a frame cannot be pinned BELOW the session
    ;; default by storing the default; redaction-below-pin is out of scope —
    ;; the pin is the floor for unoverridden frames). Reveal direction:
    (config/set-session-egress-profile! config/default-egress-profile)
    (config/set-frame-egress-profile! :story.iso/raised :rf.egress/local-raw)
    (is (true?  (config/include-sensitive? :story.iso/raised)) "explicit reveal wins over the redacting pin")
    (is (false? (config/include-sensitive? :story.iso/other)) "an unoverridden frame stays at the redacting pin")
    (clear-frame-overrides!)))
