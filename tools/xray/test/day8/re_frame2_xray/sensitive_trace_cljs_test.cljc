(ns day8.re-frame2-xray.sensitive-trace-cljs-test
  "Tests for the `:sensitive?` trace-event privacy gate (rf2-azls9).

  Per Spec 009 §Privacy (resolved by rf2-a32kd) Xray, as a
  framework-published trace consumer, MUST default-suppress events
  carrying `:sensitive? true`. This suite covers:

    1. The predicate vocabulary in `config.cljc` —
       `sensitive-event?` / `suppress-sensitive?`.
    2. The flag round-trip — `set-show-sensitive!` / `get-show-sensitive`
       / `configure! {:rf.privacy/show-sensitive? ...}`.
    3. The suppressed-events counter — `note-suppressed!` /
       `suppressed-count` / `reset-suppressed-count!`.
    4. `trace-collector/collect-trace!` default-suppress + opt-in pass-
       through behaviour.
    5. `trace-collector/reset-for-test!` resets the counter alongside the
       buffer.

  Pure-data + JVM-runnable so the algebra runs under the JVM target;
  CLJC keeps the file shadow's `:node-test` target as well."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]])
            [day8.re-frame2-xray.config :as config]
            #?(:cljs [re-frame.frame :as frame])
            #?(:cljs [re-frame.trace :as trace])
            #?(:cljs [day8.re-frame2-xray.trace-collector :as trace-collector])))

;; ---- fixtures -----------------------------------------------------------

(defn- reset-privacy-state [test-fn]
  ;; Each test starts with the defaults: flag off, counter empty.
  (config/set-show-sensitive! false)
  (config/reset-suppressed-count!)
  #?(:cljs (trace-collector/reset-for-test!))
  (test-fn)
  (config/set-show-sensitive! false)
  (config/reset-suppressed-count!)
  #?(:cljs (trace-collector/reset-for-test!)))

(use-fixtures :each reset-privacy-state)

;; ---- (1) predicate vocabulary -------------------------------------------

(deftest sensitive-event?-detects-top-level-flag
  (testing "events with :sensitive? true are sensitive"
    (is (true? (config/sensitive-event? {:sensitive? true})))
    (is (true? (config/sensitive-event?
                 {:op-type :rf.event :operation :rf.event/dispatched
                  :sensitive? true :tags {:rf.trace/event-id :user/login}}))))
  (testing "events without :sensitive? are non-sensitive"
    (is (false? (config/sensitive-event? {})))
    (is (false? (config/sensitive-event? {:op-type :rf.event})))
    (is (false? (config/sensitive-event? {:sensitive? false})))
    (is (false? (config/sensitive-event? {:sensitive? nil}))))
  (testing "non-map inputs are non-sensitive (no NPE)"
    (is (false? (config/sensitive-event? nil)))
    (is (false? (config/sensitive-event? :keyword)))
    (is (false? (config/sensitive-event? [:vector])))))

(deftest suppress-sensitive?-composes-flag-and-gate
  (testing "default flag off + sensitive event = suppressed"
    (is (true? (config/suppress-sensitive? {:sensitive? true}))))
  (testing "default flag off + non-sensitive event = not suppressed"
    (is (false? (config/suppress-sensitive? {})))
    (is (false? (config/suppress-sensitive? {:sensitive? false}))))
  (testing "flag on + sensitive event = not suppressed (opt-in unmask)"
    (config/set-show-sensitive! true)
    (is (false? (config/suppress-sensitive? {:sensitive? true}))))
  (testing "flag on + non-sensitive event = not suppressed"
    (config/set-show-sensitive! true)
    (is (false? (config/suppress-sensitive? {})))))

;; ---- (2) flag round-trip ------------------------------------------------

(deftest default-show-sensitive-is-false
  (testing "Xray defaults to suppressing :sensitive? events"
    (is (false? (config/get-show-sensitive)))))

(deftest set-show-sensitive-round-trips
  (testing "set-show-sensitive! writes and get-show-sensitive reads"
    (config/set-show-sensitive! true)
    (is (true? (config/get-show-sensitive)))
    (config/set-show-sensitive! false)
    (is (false? (config/get-show-sensitive)))))

(deftest set-show-sensitive-coerces-truthy
  (testing "set-show-sensitive! coerces truthy / falsy via boolean"
    (config/set-show-sensitive! "yes")
    (is (true? (config/get-show-sensitive)))
    (config/set-show-sensitive! nil)
    (is (false? (config/get-show-sensitive)))
    (config/set-show-sensitive! 0)
    ;; (boolean 0) is true in Clojure — only nil/false are falsy.
    (is (true? (config/get-show-sensitive)))))

(deftest configure-routes-show-sensitive-through
  (testing "configure! {:rf.privacy/show-sensitive? true} flips the flag"
    (config/configure! {:rf.privacy/show-sensitive? true})
    (is (true? (config/get-show-sensitive))))
  (testing "configure! {:rf.privacy/show-sensitive? false} returns to default"
    (config/set-show-sensitive! true)
    (config/configure! {:rf.privacy/show-sensitive? false})
    (is (false? (config/get-show-sensitive)))))

(deftest configure-without-key-preserves-existing-flag
  (testing "configure! without :rf.privacy/show-sensitive? leaves the flag alone"
    (config/set-show-sensitive! true)
    (config/configure! {:rf.xray/editor :cursor})
    (is (true? (config/get-show-sensitive))
        "configure! ignoring our key must not stomp the flag")))

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
;; the §(7) tests below: a real `trace/emit!` populates the framework's
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
     (testing "with :rf.privacy/show-sensitive? true the buffer receives the event"
       (config/configure! {:rf.privacy/show-sensitive? true})
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
       (config/configure! {:rf.privacy/show-sensitive? true})
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

;; ---- (6) retroactive scrub on toggle-off (rf2-lqmje) --------------------
;;
;; Per Spec 009 §Privacy §Retroactive-scrub on `set-show-sensitive!`
;; false: when the flag transitions true → false, the trace buffer is
;; cleared. The flag is NOT a one-way trapdoor — flipping it false
;; MUST restore the privacy guarantee for events already buffered
;; while the flag was true. The trade-off (non-sensitive history also
;; lost) is intentional and documented in Spec 009.

(deftest set-show-sensitive!-false-runs-toggle-off-callbacks
  (testing "true → false transition invokes registered callbacks"
    (let [called?  (atom false)
          token-id ::scrub-callback-test]
      (config/register-toggle-off-callback! token-id #(reset! called? true))
      (try
        (config/set-show-sensitive! true)
        (is (false? @called?)
            "false → true must NOT invoke callbacks (no buffered sensitive risk)")
        (config/set-show-sensitive! false)
        (is (true? @called?)
            "true → false must invoke every registered callback")
        (finally
          (config/unregister-toggle-off-callback! token-id))))))

(deftest set-show-sensitive!-no-transition-no-callback
  (testing "true → true and false → false are no-ops for the callbacks"
    (let [calls    (atom 0)
          token-id ::scrub-callback-no-transition]
      (config/register-toggle-off-callback! token-id #(swap! calls inc))
      (try
        (config/set-show-sensitive! false) ; default → false, no transition
        (is (= 0 @calls))
        (config/set-show-sensitive! true)
        (config/set-show-sensitive! true)  ; true → true
        (is (= 0 @calls))
        (config/set-show-sensitive! false) ; true → false, the only transition
        (is (= 1 @calls))
        (config/set-show-sensitive! false) ; false → false
        (is (= 1 @calls))
        (finally
          (config/unregister-toggle-off-callback! token-id))))))

(deftest set-show-sensitive!-callback-failure-isolated
  (testing "one buggy callback does not prevent others from running"
    (let [other-called? (atom false)
          token-bad     ::scrub-callback-bad
          token-good    ::scrub-callback-good]
      (config/register-toggle-off-callback!
        token-bad (fn [] (throw (ex-info "boom" {}))))
      (config/register-toggle-off-callback!
        token-good (fn [] (reset! other-called? true)))
      (try
        (config/set-show-sensitive! true)
        (config/set-show-sensitive! false)
        (is (true? @other-called?)
            "the good callback must still run after the bad one throws")
        (finally
          (config/unregister-toggle-off-callback! token-bad)
          (config/unregister-toggle-off-callback! token-good))))))

#?(:cljs
   (deftest toggle-off-clears-trace-buffer
     (testing "true → false clears the trace buffer in lockstep with the flag"
       ;; Scenario: flag on, sensitive cascade lands, flag flipped off.
       (config/set-show-sensitive! true)
       (trace-collector/collect-trace! (sensitive-event))
       (trace-collector/collect-trace! (non-sensitive-event))
       (trace-collector/collect-trace! (sensitive-event))
       (is (= 3 (count (trace-collector/buffer-for-test)))
           "all three events landed while the flag was true")
       ;; User flips off expecting privacy restored.
       (config/set-show-sensitive! false)
       (is (= 0 (count (trace-collector/buffer-for-test)))
           "buffer must be empty — sensitive payloads cannot survive the toggle")
       (is (= 0 (config/suppressed-count))
           "suppressed counter also drops in lockstep with the buffer"))))

#?(:cljs
   (deftest toggle-off-also-drops-non-sensitive-history
     (testing "the simplest correct semantic — clear EVERYTHING, not just sensitive"
       ;; The Spec 009 §Retroactive-scrub trade-off: selective scrubbing
       ;; is unsafe because non-sensitive events can structurally reveal
       ;; the redacted value (sub recomputes, render args, etc). Document
       ;; the intentional loss so a future refactor doesn't try to
       ;; "improve" by filtering instead of clearing.
       (config/set-show-sensitive! true)
       (trace-collector/collect-trace! (non-sensitive-event))
       (trace-collector/collect-trace! (non-sensitive-event))
       (is (= 2 (count (trace-collector/buffer-for-test))))
       (config/set-show-sensitive! false)
       (is (= 0 (count (trace-collector/buffer-for-test)))
           "non-sensitive history is intentionally lost — see Spec 009 §Retroactive-scrub"))))

#?(:cljs
   (deftest toggle-off-no-clear-when-flag-was-already-false
     (testing "false → false transition leaves the buffer alone"
       ;; The flag started false, so no sensitive events ever landed.
       ;; A redundant set-show-sensitive! false call must NOT throw away
       ;; the buffered non-sensitive history.
       (trace-collector/collect-trace! (non-sensitive-event))
       (trace-collector/collect-trace! (non-sensitive-event))
       (is (= 2 (count (trace-collector/buffer-for-test))))
       (config/set-show-sensitive! false) ; redundant; default is false
       (is (= 2 (count (trace-collector/buffer-for-test)))
           "redundant set-show-sensitive! false must not clear the buffer"))))

;; ---- (7) frame-bound sensitive events — the snapshot-read gate (rf2-0ax6f)
;;
;; The §(4) tests drive FRAMELESS events through `collect-trace!` — that
;; path is gated by the listener-side `suppress-sensitive?` check before
;; the secondary ring. But a FRAME-BOUND sensitive event takes a
;; different route: the framework's `trace/emit!` retains it in the
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
     (trace/emit! :rf.event :rf.event/run-end
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
     ;; Register the host frame so `frame/frame-ids` (which
     ;; `snapshot-from-rings` walks) includes it, run the body, then
     ;; drop it so the next test starts clean. We `dissoc` from the
     ;; registry directly rather than `destroy-frame!` — this suite has
     ;; no installed substrate adapter, and destroy fires the dispatch /
     ;; teardown machinery a bare frame doesn't need. `reset-for-test!`
     ;; (in the outer fixture) already clears the per-frame rings.
     (frame/reg-frame host-frame {})
     (try (test-fn)
          (finally (swap! frame/frames dissoc host-frame)))))

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
                  snapshot-from-rings while show-sensitive? is false")
             (is (not-any? :sensitive? buf)
                 "no sensitive event survives the read-side gate")
             (is (every? #(= 1 (get-in % [:tags :rf.trace/dispatch-id])) buf)
                 "the surviving event is the non-sensitive cascade #1")))))))

#?(:cljs
   (deftest snapshot-passes-sensitive-frame-bound-event-when-opted-in
     (with-host-frame
       (fn []
         (testing "with :rf.privacy/show-sensitive? true the snapshot
                   surfaces the retained sensitive frame-bound event"
           (config/configure! {:rf.privacy/show-sensitive? true})
           (emit-frame-bound! 1 false)
           (emit-frame-bound! 2 true)
           (let [buf (host-ring-buffer)]
             (is (= 2 (count buf))
                 "opted-in caller sees BOTH the non-sensitive and the
                  sensitive frame-bound event in the snapshot")
             (is (some :sensitive? buf)
                 "the sensitive event passes through under the opt-in")))))))
