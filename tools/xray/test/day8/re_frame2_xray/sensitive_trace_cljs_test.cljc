(ns day8.re-frame2-xray.sensitive-trace-cljs-test
  "Tests for the `:sensitive?` trace-event privacy gate (rf2-azls9,
  migrated to the EP-0015 per-(tool,frame) reveal grain by rf2-h40lt2).

  Per Spec 009 §Privacy (resolved by rf2-a32kd) Xray, as a
  framework-published trace consumer, MUST default-suppress events
  carrying `:sensitive? true`. EP-0015 issue 7 (Spec 015 §Cross-tool
  visibility grain) rules on-box visibility per `(tool, frame)`: there is
  NO process-global `show-sensitive?` user toggle. Xray's local-render
  egress PROFILE (`:rf.egress/local-redacted` default / `:rf.egress/local-raw`
  trusted-local reveal) governs the gate. This suite covers:

    1. The predicate vocabulary — the framework-published
       `rf/sensitive?` (Xray composes against it directly since
       rf2-kuky.8 retired the tool-side `sensitive-event?` alias) plus
       `config.cljc`'s own `suppress-sensitive?` / `include-sensitive?`.
    2. The profile round-trip — `set-egress-profile!` / `get-egress-profile`
       / `configure! {:rf.xray/egress-profile ...}`.
    3. The suppressed-events counter — `note-suppressed!` /
       `suppressed-count` / `reset-suppressed-count!`.
    4. `trace-collector/collect-trace!` default-suppress + opt-in pass-
       through behaviour.
    5. `trace-collector/reset-for-test!` resets the counter alongside the
       buffer.
    6. The two INGEST gates rf2-y8doi.13 closed — Xray's `:epoch-history`
       slot (epoch records carry `:trace-events` verbatim) and
       `panels.fresco-reads/trace-windows` (Xray's second, seam-side
       reader of the framework rings).

  Pure-data + JVM-runnable so the algebra runs under the JVM target;
  CLJC keeps the file shadow's `:node-test` target as well."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]])
            [day8.re-frame2-xray.config :as config]
            [re-frame.privacy :as rf.privacy]
            #?(:cljs [re-frame.core :as rf])
            #?(:cljs [re-frame.frame :as rf.frame])
            #?(:cljs [re-frame.trace :as rf.trace])
            #?(:cljs [re-frame.epoch.assembly :as rf.epoch.assembly])
            #?(:cljs [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
            #?(:cljs [re-frame.test-support :as rf.test-support])
            #?(:cljs [day8.re-frame2-xray.epoch :as xray-epoch])
            #?(:cljs [day8.re-frame2-xray.panels.fresco-reads :as fresco-reads])
            #?(:cljs [day8.re-frame2-xray.trace-collector :as trace-collector])))

;; ---- fixtures -----------------------------------------------------------

(defn- reset-privacy-state [test-fn]
  ;; Each test starts with the defaults: egress profile redacting, counter
  ;; empty.
  (config/set-egress-profile! config/default-egress-profile)
  (config/reset-suppressed-count!)
  #?(:cljs (trace-collector/reset-for-test!))
  (test-fn)
  (config/set-egress-profile! config/default-egress-profile)
  (config/reset-suppressed-count!)
  #?(:cljs (trace-collector/reset-for-test!)))

(use-fixtures :each reset-privacy-state)

;; ---- (1) predicate vocabulary -------------------------------------------

(deftest the-framework-predicate-detects-the-top-level-flag
  ;; rf2-kuky.8 — Xray composes against `rf/sensitive?` directly; the
  ;; one-line `config/sensitive-event?` alias is deleted.
  (testing "events with :sensitive? true are sensitive"
    (is (true? (rf.privacy/sensitive? {:sensitive? true})))
    (is (true? (rf.privacy/sensitive?
                 {:op-type :rf.event :operation :rf.event/dispatched
                  :sensitive? true :tags {:rf.trace/event-id :user/login}}))))
  (testing "events without :sensitive? are non-sensitive"
    (is (false? (rf.privacy/sensitive? {})))
    (is (false? (rf.privacy/sensitive? {:op-type :rf.event})))
    (is (false? (rf.privacy/sensitive? {:sensitive? false})))
    (is (false? (rf.privacy/sensitive? {:sensitive? nil}))))
  (testing "non-map inputs are non-sensitive (no NPE)"
    (is (false? (rf.privacy/sensitive? nil)))
    (is (false? (rf.privacy/sensitive? :keyword)))
    (is (false? (rf.privacy/sensitive? [:vector]))))
  (testing "a MALFORMED truthy stamp is sensitive — Xray's panels now
            suppress it, matching what the MCP wire already did"
    (is (true? (rf.privacy/sensitive? {:sensitive? "true"})))
    (is (true? (rf.privacy/sensitive? {:sensitive? :yes})))
    (is (true? (rf.privacy/sensitive? {:sensitive? 1})))))

(deftest suppress-sensitive?-composes-profile-and-gate
  (testing "default profile (local-redacted) + sensitive event = suppressed"
    (is (= :rf.egress/local-redacted (config/get-egress-profile)))
    (is (false? (config/include-sensitive?)))
    (is (true? (config/suppress-sensitive? {:sensitive? true}))))
  (testing "default profile + non-sensitive event = not suppressed"
    (is (false? (config/suppress-sensitive? {})))
    (is (false? (config/suppress-sensitive? {:sensitive? false}))))
  (testing "local-raw + sensitive event = not suppressed (trusted-local opt-in)"
    (config/set-egress-profile! :rf.egress/local-raw)
    (is (true? (config/include-sensitive?)))
    (is (false? (config/suppress-sensitive? {:sensitive? true}))))
  (testing "local-raw + non-sensitive event = not suppressed"
    (config/set-egress-profile! :rf.egress/local-raw)
    (is (false? (config/suppress-sensitive? {})))))

;; ---- (2) egress-profile round-trip --------------------------------------

(deftest default-egress-profile-is-local-redacted
  (testing "Xray defaults to the fail-closed redacting profile — sensitive
            display suppressed (EP-0015 issue 7, rf2-h40lt2)"
    (is (= :rf.egress/local-redacted (config/get-egress-profile)))
    (is (false? (config/include-sensitive?)))))

(deftest set-egress-profile-round-trips
  (testing "set-egress-profile! writes and get-egress-profile reads"
    (config/set-egress-profile! :rf.egress/local-raw)
    (is (= :rf.egress/local-raw (config/get-egress-profile)))
    (is (true? (config/include-sensitive?)))
    (config/set-egress-profile! :rf.egress/local-redacted)
    (is (= :rf.egress/local-redacted (config/get-egress-profile)))
    (is (false? (config/include-sensitive?)))))

(deftest set-egress-profile-nil-resets-to-default
  (testing "nil resets to the fail-closed redacting default"
    (config/set-egress-profile! :rf.egress/local-raw)
    (config/set-egress-profile! nil)
    (is (= :rf.egress/local-redacted (config/get-egress-profile)))))

(deftest set-egress-profile-unknown-coerces-to-default
  (testing "a non-member profile keyword coerces to the fail-closed default
            (defence-in-depth — configure! rejects loudly upstream)"
    (config/set-egress-profile! :rf.egress/local-raw)
    (config/set-egress-profile! :rf.egress/not-a-real-profile)
    (is (= :rf.egress/local-redacted (config/get-egress-profile))
        "unknown profile must not silently reveal")))

(deftest configure-routes-egress-profile-through
  (testing "configure! {:rf.xray/egress-profile :rf.egress/local-raw} flips the profile"
    (config/configure! {:rf.xray/egress-profile :rf.egress/local-raw})
    (is (= :rf.egress/local-raw (config/get-egress-profile)))
    (is (true? (config/include-sensitive?))))
  (testing "configure! {:rf.xray/egress-profile :rf.egress/local-redacted} narrows back"
    (config/set-egress-profile! :rf.egress/local-raw)
    (config/configure! {:rf.xray/egress-profile :rf.egress/local-redacted})
    (is (= :rf.egress/local-redacted (config/get-egress-profile))))
  (testing "configure! with an unknown profile raises :rf.error/unknown-egress-profile"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          (config/configure! {:rf.xray/egress-profile :rf.egress/bogus})))))

(deftest configure-without-key-preserves-existing-profile
  (testing "configure! without :rf.xray/egress-profile leaves the profile alone"
    (config/set-egress-profile! :rf.egress/local-raw)
    (config/configure! {:rf.xray/editor :cursor})
    (is (= :rf.egress/local-raw (config/get-egress-profile))
        "configure! ignoring our key must not stomp the profile")))

;; ---- (3) suppressed-events counter --------------------------------------

(deftest suppressed-count-starts-at-zero
  (testing "counter is zero before any suppression"
    (is (= 0 (config/suppressed-count)))
    (is (= 0 (config/suppressed-count :rf/default)))
    (is (= 0 (config/suppressed-count :global)))))

(deftest note-suppressed-bumps-the-frame-bucket
  (testing "note-suppressed! adds 1 to the matching frame bucket"
    (config/note-suppressed! :rf/default)
    (is (= 1 (config/suppressed-count :rf/default)))
    (is (= 1 (config/suppressed-count)) "total across all buckets")
    (config/note-suppressed! :rf/default)
    (is (= 2 (config/suppressed-count :rf/default)))
    (is (= 2 (config/suppressed-count))))
  (testing "different frames count independently"
    (config/note-suppressed! :rf/xray)
    (is (= 2 (config/suppressed-count :rf/default)))
    (is (= 1 (config/suppressed-count :rf/xray)))
    (is (= 3 (config/suppressed-count)))))

(deftest note-suppressed-without-frame-counts-as-global
  (testing "nil frame falls under :global"
    (config/note-suppressed! nil)
    (is (= 1 (config/suppressed-count :global)))
    (is (= 1 (config/suppressed-count)))))

(deftest reset-suppressed-count-clears-buckets
  (testing "reset with no arg drops everything"
    (config/note-suppressed! :rf/default)
    (config/note-suppressed! :rf/xray)
    (config/reset-suppressed-count!)
    (is (= 0 (config/suppressed-count)))
    (is (= 0 (config/suppressed-count :rf/default)))
    (is (= 0 (config/suppressed-count :rf/xray))))
  (testing "reset with frame-id drops just that bucket"
    (config/note-suppressed! :rf/default)
    (config/note-suppressed! :rf/xray)
    (config/reset-suppressed-count! :rf/default)
    (is (= 0 (config/suppressed-count :rf/default)))
    (is (= 1 (config/suppressed-count :rf/xray)))
    (is (= 1 (config/suppressed-count)))))

;; ---- (4) collect-trace! default-suppress + opt-in pass-through ----------

;; These envelopes deliberately omit `:frame` / `:tags :frame` so the
;; collector routes them to the frameless secondary ring (per rf2-3g9nw
;; D2=a). For the LISTENER-path gate the frameless-vs-frame-bound
;; distinction is irrelevant — `collect-trace!` drops the sensitive
;; event above the ring split either way.
;;
;; The frame-bound path is the OTHER half of the matrix and lives in
;; the §(7) tests below: a real `rf.trace/emit!` populates the framework's
;; per-frame ring (which retains EVERY event with no `:sensitive?`
;; check), and the gate that matters there is the read-side scrub in
;; `snapshot-from-rings` (rf2-0ax6f). Keeping both halves here pins the
;; symmetry the `collect-trace!` docstring now claims.

(defn- non-sensitive-event []
  {:op-type :rf.event :operation :rf.event/dispatched
   :tags {:rf.trace/event-id :user/click}})

(defn- sensitive-event []
  {:op-type :rf.event :operation :rf.event/dispatched
   :sensitive? true
   :tags {:rf.trace/event-id :user/login}})

;; The collect-trace! tests run only on CLJS — the side-effect path
;; reads `re-frame.interop/debug-enabled?`, which is true under the
;; CLJS dev target but resolves to false / unbound under the JVM
;; target (the interop is browser-runtime-shaped). The pure-data
;; predicate + counter tests above already exercise the algebra
;; under the JVM target; these CLJS-only tests lock the wiring.

#?(:cljs
   (deftest collect-trace-buffers-non-sensitive-by-default
     (testing "non-sensitive events flow into the buffer"
       (trace-collector/collect-trace! (non-sensitive-event))
       (is (= 1 (count (trace-collector/buffer-for-test))))
       (is (= 0 (config/suppressed-count))))))

#?(:cljs
   (deftest collect-trace-suppresses-sensitive-by-default
     (testing "sensitive event is dropped from the buffer and bumps the counter"
       (trace-collector/collect-trace! (sensitive-event))
       (is (= 0 (count (trace-collector/buffer-for-test)))
           "sensitive event must NOT enter the buffer under the default")
       (is (= 1 (config/suppressed-count))
           "the dropped event bumps the counter")
       (is (= 1 (config/suppressed-count :global))
           "frameless event counts under :global"))))

#?(:cljs
   (deftest collect-trace-passes-sensitive-when-opted-in
     (testing "with :rf.xray/egress-profile :rf.egress/local-raw the buffer receives the event"
       (config/configure! {:rf.xray/egress-profile :rf.egress/local-raw})
       (trace-collector/collect-trace! (sensitive-event))
       (is (= 1 (count (trace-collector/buffer-for-test)))
           "opted-in caller sees the sensitive event in the buffer")
       (is (= 0 (config/suppressed-count))
           "the counter does NOT bump when the event passes through"))))

#?(:cljs
   (deftest collect-trace-mixed-flow
     (testing "default-suppress + opt-in flip mid-stream"
       (trace-collector/collect-trace! (non-sensitive-event))      ; in
       (trace-collector/collect-trace! (sensitive-event))          ; dropped
       (trace-collector/collect-trace! (non-sensitive-event))      ; in
       (config/configure! {:rf.xray/egress-profile :rf.egress/local-raw})
       (trace-collector/collect-trace! (sensitive-event))          ; in
       (is (= 3 (count (trace-collector/buffer-for-test)))
           "buffer contains the 2 non-sensitive + 1 opted-in sensitive")
       (is (= 1 (config/suppressed-count))
           "exactly one event was suppressed under the default"))))

#?(:cljs
   (deftest clear-buffer-resets-suppressed-counter
     (testing "retroactive-scrub! drops the buffer AND the redaction counter"
       (trace-collector/collect-trace! (sensitive-event))
       (trace-collector/collect-trace! (sensitive-event))
       (is (= 2 (config/suppressed-count)))
       (trace-collector/retroactive-scrub!)
       (is (= 0 (config/suppressed-count))
           "clearing the buffer also drops the indicator state"))))

;; ---- (6) retroactive scrub on profile-narrowing (rf2-lqmje / rf2-h40lt2) -
;;
;; Per Spec 009 §Privacy §Retroactive-scrub: when the local-render egress
;; profile NARROWS from a sensitive-revealing boundary
;; (`:rf.egress/local-raw`) back to the redacting default, the trace buffer
;; is cleared. The reveal is NOT a one-way trapdoor — narrowing MUST restore
;; the privacy guarantee for events already buffered while the raw profile
;; was active. The trade-off (non-sensitive history also lost) is
;; intentional and documented in Spec 009.

(deftest narrowing-profile-runs-toggle-off-callbacks
  (testing "reveal → redact narrowing invokes registered callbacks"
    (let [called?  (atom false)
          token-id ::scrub-callback-test]
      (config/register-toggle-off-callback! token-id #(reset! called? true))
      (try
        (config/set-egress-profile! :rf.egress/local-raw)
        (is (false? @called?)
            "redact → reveal widening must NOT invoke callbacks (no buffered sensitive risk)")
        (config/set-egress-profile! :rf.egress/local-redacted)
        (is (true? @called?)
            "reveal → redact narrowing must invoke every registered callback")
        (finally
          (config/unregister-toggle-off-callback! token-id))))))

(deftest non-narrowing-transition-no-callback
  (testing "reveal → reveal and redact → redact are no-ops for the callbacks"
    (let [calls    (atom 0)
          token-id ::scrub-callback-no-transition]
      (config/register-toggle-off-callback! token-id #(swap! calls inc))
      (try
        (config/set-egress-profile! :rf.egress/local-redacted) ; default → redact, no transition
        (is (= 0 @calls))
        (config/set-egress-profile! :rf.egress/local-raw)
        (config/set-egress-profile! :rf.egress/local-raw)  ; reveal → reveal
        (is (= 0 @calls))
        (config/set-egress-profile! :rf.egress/local-redacted) ; reveal → redact, the only narrowing
        (is (= 1 @calls))
        (config/set-egress-profile! :rf.egress/local-redacted) ; redact → redact
        (is (= 1 @calls))
        (finally
          (config/unregister-toggle-off-callback! token-id))))))

(deftest narrowing-callback-failure-isolated
  (testing "one buggy callback does not prevent others from running"
    (let [other-called? (atom false)
          token-bad     ::scrub-callback-bad
          token-good    ::scrub-callback-good]
      (config/register-toggle-off-callback!
        token-bad (fn [] (throw (ex-info "boom" {}))))
      (config/register-toggle-off-callback!
        token-good (fn [] (reset! other-called? true)))
      (try
        (config/set-egress-profile! :rf.egress/local-raw)
        (config/set-egress-profile! :rf.egress/local-redacted)
        (is (true? @other-called?)
            "the good callback must still run after the bad one throws")
        (finally
          (config/unregister-toggle-off-callback! token-bad)
          (config/unregister-toggle-off-callback! token-good))))))

#?(:cljs
   (deftest narrowing-clears-trace-buffer
     (testing "reveal → redact clears the trace buffer in lockstep with the profile"
       ;; Scenario: raw profile on, sensitive cascade lands, profile narrowed.
       (config/set-egress-profile! :rf.egress/local-raw)
       (trace-collector/collect-trace! (sensitive-event))
       (trace-collector/collect-trace! (non-sensitive-event))
       (trace-collector/collect-trace! (sensitive-event))
       (is (= 3 (count (trace-collector/buffer-for-test)))
           "all three events landed while the raw profile was active")
       ;; User narrows expecting privacy restored.
       (config/set-egress-profile! :rf.egress/local-redacted)
       (is (= 0 (count (trace-collector/buffer-for-test)))
           "buffer must be empty — sensitive payloads cannot survive the narrowing")
       (is (= 0 (config/suppressed-count))
           "suppressed counter also drops in lockstep with the buffer"))))

#?(:cljs
   (deftest narrowing-also-drops-non-sensitive-history
     (testing "the simplest correct semantic — clear EVERYTHING, not just sensitive"
       ;; The Spec 009 §Retroactive-scrub trade-off: selective scrubbing
       ;; is unsafe because non-sensitive events can structurally reveal
       ;; the redacted value (sub recomputes, render args, etc). Document
       ;; the intentional loss so a future refactor doesn't try to
       ;; "improve" by filtering instead of clearing.
       (config/set-egress-profile! :rf.egress/local-raw)
       (trace-collector/collect-trace! (non-sensitive-event))
       (trace-collector/collect-trace! (non-sensitive-event))
       (is (= 2 (count (trace-collector/buffer-for-test))))
       (config/set-egress-profile! :rf.egress/local-redacted)
       (is (= 0 (count (trace-collector/buffer-for-test)))
           "non-sensitive history is intentionally lost — see Spec 009 §Retroactive-scrub"))))

#?(:cljs
   (deftest no-clear-when-profile-was-already-redacting
     (testing "redact → redact transition leaves the buffer alone"
       ;; The profile started redacting, so no sensitive events ever landed.
       ;; A redundant narrow to local-redacted must NOT throw away the
       ;; buffered non-sensitive history.
       (trace-collector/collect-trace! (non-sensitive-event))
       (trace-collector/collect-trace! (non-sensitive-event))
       (is (= 2 (count (trace-collector/buffer-for-test))))
       (config/set-egress-profile! :rf.egress/local-redacted) ; redundant; default is redacting
       (is (= 2 (count (trace-collector/buffer-for-test)))
           "redundant narrow to local-redacted must not clear the buffer"))))

;; ---- (7) frame-bound sensitive events — the snapshot-read gate (rf2-0ax6f)
;;
;; The §(4) tests drive FRAMELESS events through `collect-trace!` — that
;; path is gated by the listener-side `suppress-sensitive?` check before
;; the secondary ring. But a FRAME-BOUND sensitive event takes a
;; different route: the framework's `rf.trace/emit!` retains it in the
;; per-frame cascade ring (`re-frame.trace.tooling/push-to-ring!`) with
;; NO `:sensitive?` check — `collect-trace!` only declines to *push*
;; it, it cannot un-retain it. `snapshot-from-rings` reads those rings
;; back directly, so without a read-side gate a later non-sensitive
;; event's mirror-sync would pull the retained sensitive event into the
;; buffer (rf2-0ax6f leak). These tests drive a REAL emit → per-frame
;; ring → `snapshot-from-rings` (`buffer-for-test`) and pin that the
;; sensitive event is scrubbed on the read when the flag is false, and
;; passes through only when opted in. This is the missing half of the
;; matrix (mirrors the rf2-lo28u "green test routed around the gap"
;; lesson — the assertion hits the ACTUAL failing path, the per-frame
;; ring read, not the already-covered listener path).

#?(:cljs
   (def ^:private host-frame ::sensitive-host))

#?(:cljs
   (defn- emit-frame-bound!
     "Drive a REAL `re-frame.trace/emit!` into `host-frame`'s per-frame
     ring. A `:frame` + `:rf.trace/dispatch-id` in `tags` is what
     `push-to-ring!` keys on to retain the event (frameless emits skip
     the ring per the B3 ruling). `:sensitive?` in `tags` is hoisted to
     a top-level `:sensitive? true` stamp by `build-event` — the same
     stamp a schema-sensitive handler scope produces at runtime. The
     `dispatch-id` keys the cascade slot; distinct ids = distinct
     cascades so the count assertions are unambiguous."
     [dispatch-id sensitive?]
     (rf.trace/emit! :rf.event :rf.event/run-end
                  (cond-> {:frame host-frame
                           :rf.trace/dispatch-id dispatch-id
                           :rf.trace/event-id :user/login}
                    sensitive? (assoc :sensitive? true)))))

#?(:cljs
   (defn- host-ring-buffer
     "The snapshot every consumer sees, restricted to `host-frame`'s
     events — `buffer-for-test` merges all frames + the frameless ring;
     filter to the host frame so the assertions don't depend on
     incidental cross-frame noise."
     []
     (filterv #(= host-frame (get-in % [:tags :frame]))
              (trace-collector/buffer-for-test))))

#?(:cljs
   (defn- with-host-frame [test-fn]
     ;; Register the host frame so `rf.frame/frame-ids` (which
     ;; `snapshot-from-rings` walks) includes it, run the body, then
     ;; drop it so the next test starts clean. We `dissoc` from the
     ;; registry directly rather than `destroy-frame!` — this suite has
     ;; no installed substrate adapter, and destroy fires the dispatch /
     ;; teardown machinery a bare frame doesn't need. `reset-for-test!`
     ;; (in the outer fixture) already clears the per-frame rings.
     ;; Deliberate ENGINE seat (rf.frame/upsert-frame!) — this suite runs
     ;; without a substrate adapter and manages the bare record directly;
     ;; the public rf/make-frame constructor is not the surface under test.
     (rf.frame/upsert-frame! host-frame {})
     (try (test-fn)
          (finally (swap! rf.frame/frames dissoc host-frame)))))

#?(:cljs
   (deftest snapshot-suppresses-sensitive-frame-bound-event-by-default
     (with-host-frame
       (fn []
         (testing "a sensitive FRAME-BOUND event retained in the per-frame
                   ring is scrubbed from the snapshot when the flag is false
                   (rf2-0ax6f — the bug: it leaked through snapshot-from-rings)"
           ;; A non-sensitive event lands first — the leak surfaces
           ;; precisely when a *later* benign event's mirror-sync reads
           ;; the ring back and drags the retained sensitive cascade along.
           (emit-frame-bound! 1 false)
           (emit-frame-bound! 2 true)   ; sensitive — retained in the ring
           (let [buf (host-ring-buffer)]
             (is (= 1 (count buf))
                 "only the non-sensitive event reaches the snapshot — the
                  sensitive frame-bound event MUST NOT leak through
                  snapshot-from-rings while the profile redacts
                  (:rf.egress/local-redacted)")
             (is (not-any? :sensitive? buf)
                 "no sensitive event survives the read-side gate")
             (is (every? #(= 1 (get-in % [:tags :rf.trace/dispatch-id])) buf)
                 "the surviving event is the non-sensitive cascade #1")))))))

#?(:cljs
   (deftest snapshot-passes-sensitive-frame-bound-event-when-opted-in
     (with-host-frame
       (fn []
         (testing "with :rf.xray/egress-profile :rf.egress/local-raw the snapshot
                   surfaces the retained sensitive frame-bound event"
           (config/configure! {:rf.xray/egress-profile :rf.egress/local-raw})
           (emit-frame-bound! 1 false)
           (emit-frame-bound! 2 true)
           (let [buf (host-ring-buffer)]
             (is (= 2 (count buf))
                 "opted-in caller sees BOTH the non-sensitive and the
                  sensitive frame-bound event in the snapshot")
             (is (some :sensitive? buf)
                 "the sensitive event passes through under the opt-in")))))))

;; ---- (8) the two INGEST gates (rf2-y8doi.13) -----------------------------
;;
;; §(7) above pins the FLAT read (`snapshot-from-rings` → `:trace-buffer`).
;; Two other paths carried the same retained-but-sensitive events into
;; Xray's surfaces without passing any gate, and this section pins both.
;;
;;   (a) `:epoch-history`. The framework's per-frame EPOCH ring retains RAW
;;       records by design ("redaction happens at off-box egress" —
;;       `re-frame.epoch.assembly`), and every record carries
;;       `:trace-events` verbatim. Xray copied the ring into its app-db
;;       unfiltered, so the Epoch panel, Issues ribbon, Reactive panel,
;;       Machine Inspector and Trace feed — all of which read
;;       `:trace-events` off the focused record — saw a sensitive cascade
;;       the whole trace side was hiding. `epoch/redact-history` is now the
;;       one gate every write to the slot passes through.
;;
;;   (b) `panels.fresco-reads/trace-windows`. Xray's SECOND, seam-side
;;       reader of the framework rings: it called
;;       `re-frame.trace.tooling/trace-buffer` bare, so the same events
;;       reached the Fresco advisor's ranking and the causal slice. It now
;;       reads `trace-collector/bundles-for-frame`, the gated
;;       bundle-shaped sibling of `snapshot-from-rings`.
;;
;; THE FIXTURES ARE PRODUCER-DERIVED, not hand-written maps: the trace
;; events are the ones `rf.trace/emit!` actually pushed into the per-frame
;; ring (read back through the public `rf/trace-buffer` door), and the
;; epoch record is assembled by the producer's own
;; `re-frame.epoch.assembly/build-record` over them — so `:trace-events`,
;; the `:rf.epoch/sensitive?` rollup and the structured projections are
;; exactly the shapes the runtime retains.

#?(:cljs
   (defn- ring-events
     "The REAL trace events `emit-frame-bound!` pushed into `host-frame`'s
     per-frame ring, oldest-first, read back through the public flat door.
     The `:sensitive?` stamp, the `:id` and the `:tags` are the runtime's."
     []
     (vec (rf/trace-buffer host-frame {:flat true}))))

#?(:cljs
   (defn- producer-record
     "ONE epoch record assembled by the PRODUCER over those events. The
     `{}` frame states and the `0` `:committed-at` are the only synthetic
     parts and neither is read by the gate."
     []
     (rf.epoch.assembly/build-record host-frame {} {} (ring-events) 0)))

#?(:cljs
   (defn- sensitive-event-id
     "The `:id` the runtime minted for the sensitive emit — the id the
     assertions below chase through the ingest paths."
     [record]
     (:id (first (filter :sensitive? (:trace-events record))))))

#?(:cljs
   (def ^:private with-runtime
     ;; The core fn-form runtime fixture, INVOKED DIRECTLY around the
     ;; end-to-end bodies rather than registered with `use-fixtures`: this
     ;; file's algebra is deliberately adapter-less and JVM-runnable, and a
     ;; file-wide runtime fixture would change what every test above
     ;; exercises. `:ambient-frame nil` because these bodies make their own
     ;; top-level frame (per the option's own docstring).
     (rf.test-support/make-reset-runtime-fixture
       {:adapter       rf.substrate.plain-atom/adapter
        :ambient-frame nil})))

#?(:cljs
   (defn- seed-and-read-history!
     "The `:epoch-history` ingest path end to end: register Xray's epoch
     surface, seat a bare `:rf/xray` frame, seed `history` through the real
     `:rf.xray/sync-epoch-history` event, and read the slot back through
     its own sub — no hand-reached-into db, no reducer called directly."
     [history]
     (xray-epoch/install!)
     (rf/make-frame {:id :rf/xray})
     (rf/with-frame :rf/xray
       (rf/dispatch-sync [:rf.xray/sync-epoch-history history])
       @(rf/subscribe [:rf.xray/epoch-history]))))

#?(:cljs
   (deftest epoch-history-ingest-drops-the-sensitive-record
     (with-runtime
       (fn []
         (with-host-frame
           (fn []
             (testing "a record whose `:rf.epoch/sensitive?` rollup is true is
                       dropped WHOLE on its way into `:epoch-history` while the
                       profile redacts — the envelope leaks too (Spec 009
                       §Privacy), and the record is the unit the panels key on"
               (emit-frame-bound! 1 false)
               (emit-frame-bound! 2 true)
               (let [record (producer-record)
                     ev-id  (sensitive-event-id record)]
                 (is (true? (:rf.epoch/sensitive? record))
                     "precondition: the PRODUCER's own rollup reads the
                      sensitive event — the fixture is not hand-stamped")
                 (is (= 2 (count (:trace-events record)))
                     "precondition: both cascades are in the record")
                 (is (some? ev-id)
                     "precondition: the runtime minted an :id for the
                      sensitive emit")
                 (let [history (seed-and-read-history! [record])]
                   (is (= [] (vec history))
                       "the sensitive record must NOT reach `:epoch-history`
                        under the `:rf.egress/local-redacted` default")
                   (is (not-any? #(= ev-id (:id %))
                                 (mapcat :trace-events history))
                       "and no event with that id survives anywhere in the
                        slot"))))))))))

#?(:cljs
   (deftest epoch-history-ingest-scrubs-events-on-a-record-carrying-no-rollup
     (with-runtime
       (fn []
         (with-host-frame
           (fn []
             (testing "the event-grain half: a record with NO rollup — a
                       synthetic history seed, or one assembled before the
                       rollup shipped — keeps the record and loses the
                       sensitive EVENT"
               (emit-frame-bound! 1 false)
               (emit-frame-bound! 2 true)
               (let [full   (producer-record)
                     ev-id  (sensitive-event-id full)
                     record (dissoc full :rf.epoch/sensitive?)]
                 (let [history (seed-and-read-history! [record])]
                   (is (= 1 (count history))
                       "no rollup to drop the record on, so the record stands")
                   (is (= 1 (count (:trace-events (first history))))
                       "but its `:trace-events` is scrubbed to the
                        non-sensitive cascade")
                   (is (not-any? #(= ev-id (:id %))
                                 (mapcat :trace-events history))
                       "no event with that id survives"))))))))))

#?(:cljs
   (deftest epoch-history-ingest-passes-everything-when-opted-in
     (with-runtime
       (fn []
         (with-host-frame
           (fn []
             (testing "CONTROL — under the trusted-local `:rf.egress/local-raw`
                       opt-in the gate is the identity: the same record and the
                       same two events land in the slot"
               (config/set-egress-profile! :rf.egress/local-raw)
               (emit-frame-bound! 1 false)
               (emit-frame-bound! 2 true)
               (let [record  (producer-record)
                     ev-id   (sensitive-event-id record)
                     history (seed-and-read-history! [record])]
                 (is (= 1 (count history))
                     "the opted-in operator keeps the record")
                 (is (= 2 (count (:trace-events (first history))))
                     "and both of its cascades")
                 (is (some #(= ev-id (:id %))
                           (mapcat :trace-events history))
                     "including the sensitive one, verbatim")))))))))

#?(:cljs
   (defn- window-bundles
     "`trace-windows` for `host-frame`, driven through the same
     `[:explain-render :window :frames]` envelope slot the Fresco panel
     reads. `trace-windows` is `soft`-wrapped, so a nil answer means it
     THREW — asserted separately rather than folded into a count."
     []
     (let [w (fresco-reads/trace-windows
               {:explain-render {:window {:frames [host-frame]}}})]
       (is (map? w) "trace-windows answered a window map (it did not throw)")
       (get w host-frame))))

#?(:cljs
   (deftest trace-windows-drops-the-sensitive-cascade-by-default
     (with-host-frame
       (fn []
         (testing "the Fresco advisor's window must not carry a cascade the
                   whole trace side is hiding (rf2-y8doi.13 — the second,
                   seam-side reader of the framework rings)"
           (emit-frame-bound! 1 false)
           (emit-frame-bound! 2 true)
           (let [bundles (window-bundles)]
             (is (= 1 (count bundles))
                 "only the non-sensitive cascade reaches the window under the
                  `:rf.egress/local-redacted` default")
             (is (= [1] (mapv :dispatch-id bundles))
                 "and it is cascade #1, the non-sensitive one")
             (is (not-any? #(some :sensitive? (:trace-events %)) bundles)
                 "no sensitive event survives in any surviving bundle")))))))

#?(:cljs
   (deftest trace-windows-passes-the-sensitive-cascade-when-opted-in
     (with-host-frame
       (fn []
         (testing "CONTROL — the gate is profile-conditional, not a blanket
                   drop: `:rf.egress/local-raw` restores the verbatim window"
           (config/set-egress-profile! :rf.egress/local-raw)
           (emit-frame-bound! 1 false)
           (emit-frame-bound! 2 true)
           (let [bundles (window-bundles)]
             (is (= 2 (count bundles))
                 "both cascades reach the opted-in window")
             (is (some #(some :sensitive? (:trace-events %)) bundles)
                 "including the sensitive one")))))))
