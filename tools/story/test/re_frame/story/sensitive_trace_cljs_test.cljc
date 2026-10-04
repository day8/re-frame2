(ns re-frame.story.sensitive-trace-cljs-test
  "Privacy / sensitive-trace tests for re-frame2-story.

  Per Spec 009 §Privacy: framework-published
  trace-consuming integrations MUST default-suppress `:sensitive? true`
  events. Story is a framework-published consumer — its play-runner's
  per-frame listener, the recorder's listener, the runtime's
  capture-phase-errors listener, and the UI trace-buffer listener all
  feed accumulators / buffers that the dev panels read.

  ## Coverage

  - **Pure config**: `suppress-sensitive?`, `note-suppressed!`,
    `suppressed-count`, `reset-suppressed-count!` against the
    `egress-profile` (EP-0015 frame-owned egress). Story composes against
    the framework-published `rf/sensitive?` directly; core's own suite
    pins that predicate.
  - **`configure!`**: the `:rf.story/egress-profile` opts key wires
    through to the config atom.
  - **Play listener**: the per-frame trace listener (the Spec 009 privacy
    egress seam) default-drops sensitive events at the gate
    (bumping the redaction counter) before its handler-exception capture
    runs.
  - **Recorder listener**: a sensitive event lands in the recorder's
    captured-events vector as the `[:rf/redacted]` placeholder, keeping its
    row position and dropping its payload.

  Runs on both the JVM (`clojure -M:test`) and the CLJS node-test
  build (shadow's `:node-test` target; the ns ends in `-cljs-test`, which
  the build's `cljs-test$` ns-regexp selects).  The trace-panel listener
  ships as `.cljs` only — its coverage rides on the same
  `suppress-sensitive?` helper exercised here, and the panel-level
  redaction indicator is verified by the CLJS ui-cljs test arm."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story            :as rf.story]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.play       :as rf.story.play]
            [re-frame.story.recorder   :as rf.story.recorder]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn reset-config! [f]
  ;; Always restore the session-pin to the redacting default AND clear every
  ;; per-frame override before AND after every test so a failing
  ;; test can't poison the next one.
  (rf.story.config/reset-all!)
  (try
    (f)
    (finally
      (rf.story.config/reset-all!))))

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
  play-listener's non-privacy job is to capture these into
  `rf.story.play/pending-exceptions`. `sensitive?` flags whether the privacy gate
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

(deftest configure!-wires-egress-profile
  (testing "rf.story/configure! routes :rf.story/egress-profile to the config atom"
    (rf.story/configure! {:rf.story/egress-profile :rf.egress/local-raw})
    (is (= :rf.egress/local-raw @rf.story.config/session-egress-profile)
        "opt-in flips the profile to the trusted-local boundary")
    (is (true? (rf.story.config/include-sensitive? nil)))
    (rf.story/configure! {:rf.story/egress-profile :rf.egress/local-redacted})
    (is (= :rf.egress/local-redacted @rf.story.config/session-egress-profile)
        "passing local-redacted narrows back")
    (is (false? (rf.story.config/include-sensitive? nil))))
  (testing "configure! without the key leaves the profile untouched"
    (rf.story.config/set-egress-profile! :rf.egress/local-raw)
    (rf.story/configure! {:rf.story/editor :cursor})
    (is (= :rf.egress/local-raw @rf.story.config/session-egress-profile)
        "the unrelated key didn't reset the profile")))

(deftest configure!-rejects-unknown-egress-profile
  (testing "an unknown profile keyword raises :rf.error/unknown-egress-profile (closed enum)"
    (let [e (try
              (rf.story/configure! {:rf.story/egress-profile :rf.egress/not-a-profile})
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
    (is (= :rf.egress/local-redacted @rf.story.config/session-egress-profile)
        "the rejected call left the profile at the redacting default")))

(deftest set-egress-profile!-fail-closed-defaults
  (testing "set-egress-profile! resets nil + non-member values to the fail-closed default"
    (rf.story.config/set-egress-profile! :rf.egress/local-raw)
    (rf.story.config/set-egress-profile! nil)
    (is (= :rf.egress/local-redacted @rf.story.config/session-egress-profile)
        "nil resets to the redacting default")
    (rf.story.config/set-egress-profile! :rf.egress/local-raw)
    (rf.story.config/set-egress-profile! :not-a-profile)
    (is (= :rf.egress/local-redacted @rf.story.config/session-egress-profile)
        "a non-member value coerces fail-closed (defence-in-depth)")
    (is (false? (rf.story.config/include-sensitive? nil)))))

;; ---------------------------------------------------------------------------
;; Suppressed-events counter
;; ---------------------------------------------------------------------------

(deftest note-suppressed!-bumps-counter
  (rf.story.config/note-suppressed! :story.x/y)
  (rf.story.config/note-suppressed! :story.x/y)
  (rf.story.config/note-suppressed! :story.a/b)
  (is (= 2 (rf.story.config/suppressed-count :story.x/y)))
  (is (= 1 (rf.story.config/suppressed-count :story.a/b)))
  (is (zero? (rf.story.config/suppressed-count :story.never/seen))))

(deftest note-suppressed!-routes-nil-to-global
  (rf.story.config/note-suppressed! nil)
  (rf.story.config/note-suppressed! nil)
  (is (= 2 (rf.story.config/suppressed-count)))
  (is (= 2 (rf.story.config/suppressed-count :global))))

(deftest reset-suppressed-count!-clears
  (rf.story.config/note-suppressed! :story.x/y)
  (rf.story.config/note-suppressed! :story.a/b)
  (rf.story.config/reset-suppressed-count! :story.x/y)
  (is (zero? (rf.story.config/suppressed-count :story.x/y)))
  (is (= 1 (rf.story.config/suppressed-count :story.a/b)))
  (rf.story.config/reset-suppressed-count!)
  (is (zero? (rf.story.config/suppressed-count :story.a/b))))

;; ---------------------------------------------------------------------------
;; Play listener — the Spec 009 §Privacy egress seam
;;
;; The play-listener has two jobs: the PRIVACY gate (default-drop
;; `:sensitive?` + bump `rf.story.config/note-suppressed!`) and the synchronous
;; handler-exception capture into `rf.story.play/pending-exceptions`. These tests
;; pin the privacy INVARIANT directly against those observable outputs:
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
  (get @rf.story.play/pending-exceptions frame-id []))

(deftest play-listener-drops-sensitive-events-at-the-gate-before-exception-capture
  (let [frame-id :story.sensitive/v
        listen   (@#'rf.story.play/listener-for-frame frame-id)]
    (doseq [[label profile ev captured suppressed]
            [["by default a :sensitive? warning event is dropped at the privacy gate"
              nil (sensitive-warning-event frame-id) 0 1]
             ["by default a :sensitive? handler-exception is dropped before capture"
              nil (handler-exception-event frame-id true) 0 1]
             ["under :rf.egress/local-raw the gate is open: a sensitive handler-exception is captured"
              :rf.egress/local-raw (handler-exception-event frame-id true) 1 0]
             ["control: a non-sensitive handler-exception is captured under default settings"
              nil (handler-exception-event frame-id false) 1 0]
             ["a plain dispatched event is neither suppressed nor captured (no side-table to feed)"
              nil (plain-dispatch-event frame-id [:counter/inc]) 0 0]]]
      (testing label
        (rf.story.config/reset-all!)
        (when profile (rf.story.config/set-egress-profile! profile))
        (swap! rf.story.play/pending-exceptions assoc frame-id [])
        (listen ev)
        (is (= captured (count (pending-for frame-id)))
            "handler-exceptions that reached pending-exceptions")
        (is (= suppressed (rf.story.config/suppressed-count frame-id))
            "the suppressed-events counter, which keeps the redaction hint accurate")))))

;; ---------------------------------------------------------------------------
;; Recorder listener — sensitive events redacted
;; ---------------------------------------------------------------------------

(deftest recorder-listener-preserves-temporal-ordering-around-redacted
  (testing "by default the recorder records-but-redacts :sensitive? events:
            the [:rf/redacted] placeholder sits inline between
            non-sensitive captures, keeping correlation and dropping the
            payload, and the suppressed-events counter bumps once"
    (rf.story.recorder/clear!)
    (rf.story.recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'rf.story.recorder/trace-listener]
      (listen (plain-dispatch-event     :story.recorder/sens [:counter/inc]))
      (listen (sensitive-dispatch-event :story.recorder/sens
                                        [:auth/login {:password "x"}]))
      (listen (plain-dispatch-event     :story.recorder/sens [:counter/inc])))
    (is (= [[:counter/inc] [:rf/redacted] [:counter/inc]]
           (rf.story.recorder/recorded-events))
        "the redacted slot preserves the row position so dev correlation survives")
    (is (= 1 (rf.story.config/suppressed-count :story.recorder/sens))
        "one redaction, one counter bump")
    (rf.story.recorder/clear!)))

(deftest recorder-listener-captures-sensitive-when-opted-in
  (testing "under :rf.egress/local-raw the recorder captures :sensitive? events"
    (rf.story.config/set-egress-profile! :rf.egress/local-raw)
    (rf.story.recorder/clear!)
    (rf.story.recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'rf.story.recorder/trace-listener
          ev     (sensitive-dispatch-event :story.recorder/sens
                                           [:auth/login {:password "x"}])]
      (listen ev)
      (is (= [[:auth/login {:password "x"}]] (rf.story.recorder/recorded-events))
          "the captured-events vector holds the sensitive event verbatim")
      (is (zero? (rf.story.config/suppressed-count :story.recorder/sens))))
    (rf.story.recorder/clear!)))

(deftest recorder-listener-frame-scoped-reveal-rf2-6z4znr
  (testing "revealing the RECORDED frame captures verbatim; revealing a sibling does NOT"
    ;; reveal a DIFFERENT frame — the recording frame stays redacted
    (rf.story.config/set-frame-egress-profile! :story.recorder/sibling :rf.egress/local-raw)
    (rf.story.recorder/clear!)
    (rf.story.recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'rf.story.recorder/trace-listener]
      (listen (sensitive-dispatch-event :story.recorder/sens [:auth/login {:password "x"}]))
      (is (= [[:rf/redacted]] (rf.story.recorder/recorded-events))
          "the recording frame was NOT revealed (only a sibling was) — still redacted"))
    (rf.story.recorder/clear!)
    ;; now reveal the recording frame itself
    (rf.story.config/set-frame-egress-profile! :story.recorder/sens :rf.egress/local-raw)
    (rf.story.recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'rf.story.recorder/trace-listener]
      (listen (sensitive-dispatch-event :story.recorder/sens [:auth/login {:password "x"}]))
      (is (= [[:auth/login {:password "x"}]] (rf.story.recorder/recorded-events))
          "revealing the recording frame captures verbatim"))
    (rf.story.recorder/clear!)))

(deftest play-listener-frame-scoped-reveal-rf2-6z4znr
  (testing "revealing frame A's gate does NOT open frame B's play listener"
    (rf.story.config/set-frame-egress-profile! :story.play/a :rf.egress/local-raw)
    (let [build @#'rf.story.play/listener-for-frame]
      ;; frame A revealed — sensitive exception captured
      (let [listen (build :story.play/a)]
        (swap! rf.story.play/pending-exceptions assoc :story.play/a [])
        (listen (handler-exception-event :story.play/a true))
        (is (= 1 (count (pending-for :story.play/a))) "A revealed → captured"))
      ;; frame B NOT revealed — sensitive exception dropped at the gate
      (let [listen (build :story.play/b)]
        (swap! rf.story.play/pending-exceptions assoc :story.play/b [])
        (listen (handler-exception-event :story.play/b true))
        (is (empty? (pending-for :story.play/b)) "B not revealed → dropped")
        (is (pos? (rf.story.config/suppressed-count :story.play/b)))))))

;; ---------------------------------------------------------------------------
;; recordable-event? gates on the ORIGINAL id, not the [:rf/redacted]
;; placeholder
;;
;; The redact branch calls `record-event!` with the FIXED `redacted-event`
;; placeholder (`[:rf/redacted]`), which `append`'s internal
;; `recordable-event?` filter always passes (its ns "rf" matches none of
;; `recordable-event?`'s internal-namespace exclusions). So the listener
;; tests `recordable-event?` on the ORIGINAL `(:rf.event/v tags)` BEFORE the
;; redact/pass fork; otherwise a sensitive `:rf.assert/*` / `:rf.story/*`
;; event would record a `[:rf/redacted]` row instead of being dropped,
;; contradicting the listener's own docstring ("a sensitive :rf.assert/*
;; event still gets dropped, not redacted-and-recorded, because assertions
;; are an authored not observed surface").
;; ---------------------------------------------------------------------------

(deftest recorder-listener-drops-sensitive-non-recordable-events-rf2-cmjly3
  (testing "a sensitive event in either non-recordable namespace class is
            DROPPED entirely — no [:rf/redacted] row, and the
            suppressed-events counter (the UI's 'count of redacted rows
            actually shown' hint) does not bump for a row that was never
            recorded"
    (doseq [[label frame-id event]
            [[":rf.assert/* — an authored, not observed, surface"
              :story.recorder/sens-drop [:rf.assert/path-equals [:n] 1]]
             [":rf.story/* — an internal-helper event"
              :story.recorder/sens-drop-story [:rf.story/lifecycle-tick]]]]
      (testing label
        (rf.story.recorder/clear!)
        (rf.story.recorder/start-recording! frame-id 0)
        (@#'rf.story.recorder/trace-listener (sensitive-dispatch-event frame-id event))
        (is (= [] (rf.story.recorder/recorded-events))
            "dropped — no spurious [:rf/redacted] row")
        (is (zero? (rf.story.config/suppressed-count frame-id))
            "no row recorded => no suppressed-count bump either")
        (rf.story.recorder/clear!)))))

;; ---------------------------------------------------------------------------
;; Retroactive scrub on egress-profile narrowing (EP-0015)
;; ---------------------------------------------------------------------------
;;
;; Per Spec 009 §Privacy §Retroactive-scrub: narrowing the local-render
;; egress profile from a sensitive-revealing boundary (`:rf.egress/local-raw`)
;; back to the redacting default MUST clear every per-variant trace buffer.
;; The Story config layer exposes a generic callback registry that
;; `ui.trace` (CLJS-only) hooks into; this pure-data shape is JVM-runnable
;; so the algebra is covered here. The CLJS-only buffer-clear is covered in
;; `re-frame.story-ui-cljs-test`.

(deftest toggle-off-callbacks-fire-only-on-reveal-to-redact-rf2-lqmje
  (testing "reveal → reveal and redact → redact are no-ops for the callbacks"
    (let [calls    (atom 0)
          token-id ::scrub-callback-no-transition]
      (rf.story.config/register-toggle-off-callback! token-id (fn [_frame-id] (swap! calls inc)))
      (try
        (rf.story.config/set-egress-profile! :rf.egress/local-redacted) ; default → redact, no transition
        (is (= 0 @calls))
        (rf.story.config/set-egress-profile! :rf.egress/local-raw)
        (rf.story.config/set-egress-profile! :rf.egress/local-raw)  ; reveal → reveal
        (is (= 0 @calls))
        (rf.story.config/set-egress-profile! :rf.egress/local-redacted) ; reveal → redact, the only transition
        (is (= 1 @calls))
        (rf.story.config/set-egress-profile! :rf.egress/local-redacted) ; redact → redact
        (is (= 1 @calls))
        (finally
          (rf.story.config/unregister-toggle-off-callback! token-id))))))

(deftest narrowing-profile-callback-failure-isolated-rf2-lqmje
  (testing "one buggy callback does not prevent others from running"
    (let [other-called? (atom false)
          token-bad     ::scrub-callback-bad
          token-good    ::scrub-callback-good]
      (rf.story.config/register-toggle-off-callback!
        token-bad (fn [_frame-id] (throw (ex-info "boom" {}))))
      (rf.story.config/register-toggle-off-callback!
        token-good (fn [_frame-id] (reset! other-called? true)))
      (try
        (rf.story.config/set-egress-profile! :rf.egress/local-raw)
        (rf.story.config/set-egress-profile! :rf.egress/local-redacted)
        (is (true? @other-called?)
            "the good callback must still run after the bad one throws")
        (finally
          (rf.story.config/unregister-toggle-off-callback! token-bad)
          (rf.story.config/unregister-toggle-off-callback! token-good))))))

;; ---------------------------------------------------------------------------
;; Per-(tool,frame) egress visibility (EP-0015 issue 7)
;; ---------------------------------------------------------------------------
;;
;; The contract: frame A can be local-raw while frame B stays
;; local-redacted, across every Story listener seam; narrowing one frame
;; scrubs only that frame; a frameless / unknown event fails closed. The
;; fixture's `reset-all!` clears every per-frame override and the session
;; pin before and after each test.

(deftest frame-egress-isolation-reveal-one-not-the-other
  (testing "revealing frame A to local-raw does NOT reveal frame B"
    (let [a :story.iso/a
          b :story.iso/b]
      (rf.story.config/set-frame-egress-profile! a :rf.egress/local-raw)
      ;; a sensitive event targeting A passes; the same shape on B suppresses
      (is (false? (rf.story.config/suppress-sensitive? (sensitive-dispatch-event a [:auth/login]) a)))
      (is (true?  (rf.story.config/suppress-sensitive? (sensitive-dispatch-event b [:auth/login]) b)))
      ;; resolved from the event itself (single-arg arity) — same result
      (is (false? (rf.story.config/suppress-sensitive? (sensitive-dispatch-event a [:auth/login]))))
      (is (true?  (rf.story.config/suppress-sensitive? (sensitive-dispatch-event b [:auth/login])))))))

(deftest frameless-event-fails-closed
  (testing "a sensitive event with no resolvable frame fails closed even while a sibling is raw"
    (rf.story.config/set-frame-egress-profile! :story.iso/a :rf.egress/local-raw)
    (is (false? (rf.story.config/include-sensitive? nil)) "unknown frame resolves to the redacting default")
    (is (true? (rf.story.config/suppress-sensitive? {:sensitive? true :tags {}}))
        "a frameless sensitive event is suppressed")))

(deftest narrowing-one-frame-scrubs-only-that-frame
  (testing "set-frame-egress-profile! reveal → redact fires callbacks with THAT frame-id only"
    (let [scrubbed (atom [])
          token    ::per-frame-scrub
          a        :story.iso/a
          b        :story.iso/b]
      (rf.story.config/register-toggle-off-callback! token (fn [frame-id] (swap! scrubbed conj frame-id)))
      (try
        (rf.story.config/set-frame-egress-profile! a :rf.egress/local-raw)
        (rf.story.config/set-frame-egress-profile! b :rf.egress/local-raw)
        (is (= [] @scrubbed) "revealing fires nothing")
        (rf.story.config/set-frame-egress-profile! a :rf.egress/local-redacted)
        (is (= [a] @scrubbed) "narrowing A scrubbed A only")
        (is (true? (rf.story.config/include-sensitive? b)) "B is still revealed — untouched")
        (rf.story.config/set-frame-egress-profile! b :rf.egress/local-redacted)
        (is (= [a b] @scrubbed) "narrowing B then scrubbed B")
        (finally
          (rf.story.config/unregister-toggle-off-callback! token))))))

(deftest per-frame-reveal-wins-over-redacting-session-pin
  (testing "an unoverridden frame inherits the session pin, and a per-frame
            reveal beats a redacting pin"
    ;; pin the session to raw (tool UX)
    (rf.story.config/set-session-egress-profile! :rf.egress/local-raw)
    (is (true? (rf.story.config/include-sensitive? :story.iso/inherits)) "no override → inherits the raw pin")
    ;; Storing local-redacted, the default, stores no override entry, so a
    ;; frame cannot be pinned BELOW the session pin: the pin is the floor for
    ;; unoverridden frames. Reveal direction:
    (rf.story.config/set-session-egress-profile! rf.story.config/default-egress-profile)
    (rf.story.config/set-frame-egress-profile! :story.iso/raised :rf.egress/local-raw)
    (is (true?  (rf.story.config/include-sensitive? :story.iso/raised)) "explicit reveal wins over the redacting pin")
    (is (false? (rf.story.config/include-sensitive? :story.iso/other)) "an unoverridden frame stays at the redacting pin")))
