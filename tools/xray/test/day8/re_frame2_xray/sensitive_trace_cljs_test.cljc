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
    7. The spine RE-SEED writers (rf2-3x7nj.26.1) — `:rf.xray/set-frame`
       and a cross-frame `:rf.xray/focus-event` re-seed `:epoch-history`
       from a real framework ring through the same gate.

  Pure-data + JVM-runnable so the algebra runs under the JVM target;
  CLJC keeps the file shadow's `:node-test` target as well."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]])
            #?(:cljs [clojure.string :as str])
            [day8.re-frame2-xray.config :as config]
            [re-frame.privacy :as rf.privacy]
            #?(:cljs [re-frame.core :as rf])
            #?(:cljs [re-frame.frame :as rf.frame])
            #?(:cljs [re-frame.trace :as rf.trace])
            #?(:cljs [re-frame.epoch.assembly :as rf.epoch.assembly])
            ;; The epoch PRODUCER, loaded so the re-seed tests' real
            ;; dispatches record into the framework ring they read back.
            #?(:cljs [re-frame.epoch])
            #?(:cljs [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
            #?(:cljs [re-frame.test-support :as rf.test-support])
            #?(:cljs [day8.re-frame2-xray.epoch :as xray-epoch])
            #?(:cljs [day8.re-frame2-xray.panels.fresco-reads :as fresco-reads])
            #?(:cljs [day8.re-frame2-xray.spine :as spine])
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

;; ---- the missing-rollup fallback (rf2-vaont) -----------------------------
;;
;; The record-level rollup and the event-level stamp are two signals, and
;; the gate used to answer them at two GRAINS: drop the record on the
;; rollup, but merely scrub `:trace-events` when only the stamp was there.
;; `build-record` assembles `:trigger-event`, `:db-before` / `:db-after`,
;; `:sub-runs`, `:renders` and `:effects` in the SAME map as
;; `:trace-events` (`re-frame.epoch.assembly`), so the event-grain scrub
;; left every one of those siblings holding the cascade it had just
;; removed. `trace-collector/bundles-for-frame` had already settled the
;; question for the bundle read — drop whole, because the sibling slots
;; are projections of the same events and "a slot added upstream would
;; silently re-open the leak" — and the record is the same shape.
;;
;; The fixtures below put the secret in `:trigger-event` SPECIFICALLY, a
;; slot the old event-grain scrub never reached. That is what makes the
;; regression discriminating rather than a restatement of the tests
;; above: chasing the event `:id` through `:trace-events` alone passes
;; under BOTH the old gate and the new one.

#?(:cljs
   (def ^:private secret-payload
     "A token that occurs nowhere else in this file or the runtime, so a
     whole-slot search for it cannot match incidental fixture data."
     "vaont-trigger-secret-1f3c"))

#?(:cljs
   (defn- emit-sensitive-trigger!
     "Drive a REAL `:rf.event/run-start` whose event VECTOR carries the
     secret. `find-trigger-event` reads `:rf.event/v` off the first
     run-start and `build-record` pins it as `:trigger-event`, so the
     payload lands in a SIBLING of `:trace-events` exactly as it does at
     runtime — no hand-built record, no hand-placed slot."
     [dispatch-id]
     (rf.trace/emit! :rf.event :rf.event/run-start
                     {:frame                host-frame
                      :rf.trace/dispatch-id dispatch-id
                      :rf.trace/event-id    :user/login
                      :rf.event/v           [:user/login secret-payload]
                      :sensitive?           true})))

#?(:cljs
   (defn- slot-mentions-secret?
     "Does the secret survive ANYWHERE in the slot, at any depth?

     The bead's acceptance is \"no record/payload survives anywhere\", and
     enumerating the slots to check is precisely the maintenance burden
     this fix exists to retire — a list of payload slots goes stale the
     moment the producer gains one. So the probe is the printed structure,
     which cannot miss a slot it does not know about."
     [history]
     (str/includes? (pr-str history) secret-payload)))

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
   (deftest epoch-history-ingest-drops-a-record-whose-rollup-is-missing
     (with-runtime
       (fn []
         (with-host-frame
           (fn []
             (testing "rf2-vaont — a record with NO rollup but a sensitive
                       event is dropped WHOLE, at the same grain the rollup
                       gets. Scrubbing `:trace-events` and keeping the record
                       left the cascade's payload standing in the sibling
                       slots `build-record` derived from those same events"
               (emit-frame-bound! 1 false)
               (emit-sensitive-trigger! 2)
               (let [full   (producer-record)
                     ev-id  (sensitive-event-id full)
                     record (dissoc full :rf.epoch/sensitive?)]
                 (is (true? (:rf.epoch/sensitive? full))
                     "precondition: the PRODUCER's own rollup reads the
                      sensitive event, so the fixture is a real record with
                      its rollup REMOVED — not a shape the runtime never
                      makes")
                 (is (some? ev-id)
                     "precondition: the runtime minted an :id for the
                      sensitive emit")
                 (is (= [:user/login secret-payload] (:trigger-event record))
                     "precondition, and the whole point of this test: the
                      producer lifted the secret into `:trigger-event`, a
                      SIBLING of `:trace-events`. The old event-grain scrub
                      never reached this slot, so this is the assertion that
                      discriminates — chasing the event :id through
                      `:trace-events` alone passes either way")
                 (let [history (seed-and-read-history! [record])]
                   (is (= [] (vec history))
                       "the record must NOT reach `:epoch-history`: the
                        absent rollup is not a licence to keep it")
                   (is (not (slot-mentions-secret? history))
                       "and the payload survives NOWHERE in the slot — the
                        bead's acceptance, probed over the whole printed
                        structure rather than a list of slots")
                   (is (not-any? #(= ev-id (:id %))
                                 (mapcat :trace-events history))
                       "no event with that id survives either"))))))))))

#?(:cljs
   (deftest epoch-history-ingest-keeps-an-ordinary-rollup-less-record
     (with-runtime
       (fn []
         (with-host-frame
           (fn []
             (testing "CONTROL against OVER-redaction, the failure direction
                       that is a visible bug rather than a leak: the same
                       rollup-less shape with NO sensitive event is kept
                       VERBATIM. A gate that dropped on the absence of the
                       rollup, or on any event at all, would redden here"
               (emit-frame-bound! 1 false)
               (emit-frame-bound! 2 false)
               (let [full   (producer-record)
                     record (dissoc full :rf.epoch/sensitive?)]
                 (is (false? (:rf.epoch/sensitive? full))
                     "precondition: the producer's rollup reads no sensitive
                      event in this cascade")
                 (let [history (seed-and-read-history! [record])]
                   (is (= 1 (count history))
                       "an ordinary record is untouched by this gate")
                   (is (= record (first history))
                       "and it arrives VERBATIM — not merely present, but
                        unscrubbed, which is what a whole-record grain must
                        not cost the innocent case")
                   (is (= 2 (count (:trace-events (first history))))
                       "both of its cascades survive"))))))))))

#?(:cljs
   (deftest epoch-history-ingest-passes-the-rollup-less-record-when-opted-in
     (with-runtime
       (fn []
         (with-host-frame
           (fn []
             (testing "CONTROL — the trusted-local `:rf.egress/local-raw`
                       opt-in is still the identity for the rollup-less
                       shape too: the whole-record drop is profile-
                       conditional, never a blanket refusal"
               (config/set-egress-profile! :rf.egress/local-raw)
               (emit-frame-bound! 1 false)
               (emit-sensitive-trigger! 2)
               (let [full    (producer-record)
                     record  (dissoc full :rf.epoch/sensitive?)
                     history (seed-and-read-history! [record])]
                 (is (= 1 (count history))
                     "the opted-in operator keeps the record")
                 (is (slot-mentions-secret? history)
                     "including its payload, verbatim — the same probe that
                      must read false under the default profile")
                 (is (= 2 (count (:trace-events (first history))))
                     "and both of its cascades")))))))))

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

;; ---- (8) the spine RE-SEED writers (rf2-3x7nj.26.1) ----------------------
;;
;; `redact-history` gated the three `epoch.cljs` writers, but the spine
;; writes the slot too: the frame picker (`:rf.xray/set-frame` →
;; `spine/set-frame-reducer`) and every committed-focus gesture that crosses
;; frames (`:rf.xray/focus-event`, its prev/next steps, `:rf.xray/focus-epoch`
;; and `:rf.xray/select-dispatch-id` → `spine/reseed-epoch-history-for-frame`).
;; Both reducers wrote `(vec ring)`, so one picker change put every record
;; the gate had dropped straight back into the slot.
;;
;; The ring here is the REAL framework ring, filled by real dispatches on a
;; host frame, and both gestures run through their registered handlers — so
;; the slot is written exactly as a picker change or a row click writes it.

#?(:cljs
   (def ^:private re-seed-secret
     "A token that occurs nowhere else in this file or the runtime."
     "rf2-3x7nj-26-1-reseed-secret-8b2e"))

#?(:cljs (def ^:private re-seed-app ::re-seed-app))
#?(:cljs (def ^:private re-seed-other ::re-seed-other))

#?(:cljs
   (defn- record-sensitive-ring!
     "Fill `re-seed-app`'s framework epoch ring with three REAL records: a
     clean tick, then a login whose handler writes the secret into app-db
     and classifies that path `:sensitive`, then a second tick whose
     `:db-before` / `:db-after` carry the classified leaf. Returns the ring,
     oldest-first."
     []
     (rf/make-frame {:id re-seed-app})
     (rf/reg-event ::re-seed-tick
       (fn [{:keys [db]} _] {:db (update db :ticks (fnil inc 0))}))
     (rf/reg-event ::re-seed-login
       (fn [{:keys [db]} [_ token]]
         {:db        (assoc-in db [:auth :token] token)
          :sensitive [[:auth :token]]}))
     (rf/dispatch-sync [::re-seed-tick] {:frame re-seed-app})
     (rf/dispatch-sync [::re-seed-login re-seed-secret] {:frame re-seed-app})
     (rf/dispatch-sync [::re-seed-tick] {:frame re-seed-app})
     (vec (rf/epoch-history re-seed-app))))

#?(:cljs
   (defn- assert-ring-preconditions!
     "The fixture is producer-made, so pin what the producer made of it."
     [ring]
     (is (= 3 (count ring))
         "precondition: the framework ring holds all three records")
     (is (false? (:rf.epoch/sensitive? (first ring)))
         "precondition: the first tick ran before any classification, and
          the producer's own rollup reads it clean")
     (is (every? true? (map :rf.epoch/sensitive? (rest ring)))
         "precondition: the login and the tick after it carry the classified
          leaf, and the producer's own rollup reads both sensitive")
     (is (str/includes? (pr-str ring) re-seed-secret)
         "precondition: the RAW ring holds the secret, so the gate has
          something to keep out")))

#?(:cljs
   (defn- install-xray-epoch-surface!
     "Register the epoch and spine handlers and seat a bare `:rf/xray`
     frame. No epoch collector is registered, so the only writers of the
     slot are the gestures under test."
     []
     (xray-epoch/install!)
     (spine/install!)
     (rf/make-frame {:id :rf/xray})))

#?(:cljs
   (defn- assert-slot-gated!
     [slot ring]
     (is (= [(:epoch-id (first ring))] (mapv :epoch-id slot))
         "exactly the clean record reaches the slot: both sensitive records
          are dropped, and the clean one is not over-redacted")
     (is (not-any? :rf.epoch/sensitive? slot)
         "no record whose rollup is sensitive is in the slot")
     (is (not (str/includes? (pr-str slot) re-seed-secret))
         "the secret survives NOWHERE in the slot: the raw `:trigger-event`,
          `:db-*` and `:trace-events` left with their records")))

#?(:cljs
   (deftest set-frame-re-seed-keeps-sensitive-records-out
     (with-runtime
       (fn []
         (testing "rf2-3x7nj.26.1 — the frame picker's `:rf.xray/set-frame`
                   re-seeds `:epoch-history` from the picked frame's RAW ring
                   through `redact-history`, under the default profile"
           (let [ring (record-sensitive-ring!)]
             (assert-ring-preconditions! ring)
             (install-xray-epoch-surface!)
             (rf/with-frame :rf/xray
               (rf/dispatch-sync [:rf.xray/set-frame re-seed-app])
               (is (= re-seed-app @(rf/subscribe [:rf.xray/target-frame]))
                   "the picker selected the frame, so the re-seed ran")
               (assert-slot-gated! @(rf/subscribe [:rf.xray/epoch-history])
                                   ring))))))))

#?(:cljs
   (deftest cross-frame-focus-event-re-seed-keeps-sensitive-records-out
     (with-runtime
       (fn []
         (testing "rf2-3x7nj.26.1 — an L2 row click on another frame's event
                   (`:rf.xray/focus-event`) re-keys `:epoch-history` onto that
                   frame's RAW ring through `redact-history`, and cannot
                   resolve a dropped record's epoch-id"
           (let [ring  (record-sensitive-ring!)
                 login (second ring)]
             (assert-ring-preconditions! ring)
             (is (some? (:dispatch-id login))
                 "precondition: the login record names its settling
                  dispatch-id, so the click below targets it")
             (install-xray-epoch-surface!)
             (rf/make-frame {:id re-seed-other})
             (rf/with-frame :rf/xray
               (rf/dispatch-sync [:rf.xray/set-target-frame re-seed-other])
               (is (= [] @(rf/subscribe [:rf.xray/epoch-history]))
                   "precondition: Xray observes ANOTHER frame, which has
                    recorded nothing, so the click below crosses frames")
               (rf/dispatch-sync [:rf.xray/focus-event (:dispatch-id login)
                                  re-seed-app])
               (is (= re-seed-app @(rf/subscribe [:rf.xray/target-frame]))
                   "the click re-keyed the slot onto the row's frame, so the
                    re-seed ran")
               (assert-slot-gated! @(rf/subscribe [:rf.xray/epoch-history])
                                   ring)
               (is (not= (:epoch-id login)
                         (:epoch-id @(rf/subscribe [:rf.xray/focus-slot])))
                   "focusing the sensitive row does not pin the epoch-id of
                    a record the gate keeps out of the slot"))))))))
