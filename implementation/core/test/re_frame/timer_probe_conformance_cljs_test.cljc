(ns re-frame.timer-probe-conformance-cljs-test
  "CONFORMANCE PROBE for the Managed-Effects nine-property inheritance claim
  (rf2-zqefg3.6).

  `spec/Managed-Effects.md` §How new managed-effect surfaces inherit the
  contract asserts that ANY future managed surface can adopt all nine
  properties — and that property 9 (the uniform reply envelope) is a
  framework-wide law a brand-new async writer inherits. No SHIPPED family
  proves this: each co-evolved with the contract. This suite proves it with
  `re-frame.timer-probe` — a MINIMAL, TEST-ONLY managed-timer surface that is
  the FIRST FRESH consumer of the zqefg3.1 reply substrate. If a new surface
  could NOT in fact inherit the nine properties cleanly, this suite would not
  compile / pass.

  The `cljs-test` ns suffix puts it on the consolidated `:node-test` build's
  `cljs-test$` discovery (so `npm run test:cljs` runs it); the `-test` suffix
  also matches the JVM cognitect-test-runner discovery (so `clojure -M:test`
  from `core/` runs it). Pure substrate — no runtime fixture needed beyond
  the probe's own in-flight registry, reset per test.

  Properties exercised (the nine + the EP-0011 / EP-0010 invariants):

    1. fx-id / handler          — `probe-fx-schedules-and-registers`
    2. closed args-map shape    — `args-map-shape-is-closed`
    3. failure taxonomy         — `error-reply-carries-family-kind`
    4. trace facts              — `suppression-emits-data-only-trace`
    5. shared elision walker    — `trace-reply-routes-through-shared-walker`
    6. retry/abort/teardown DATA — `retry-is-data`, `abort-requested-as-data`,
                                    `frame-destroy-cancels-and-records`
    7. in-flight registry       — `registry-is-queryable-by-id`
    8. per-frame scoping         — `registry-and-teardown-are-frame-scoped`
    9. uniform reply envelope    — `ok-reply-validates`, `work-id-tuple-shape`,
                                    `stale-generation-suppresses-no-dispatch`,
                                    `reply-target-functor-law`,
                                    `completed-at-is-causal-not-ambient`"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.reply :as reply]
            [re-frame.timer-probe :as probe]))

(use-fixtures :each (fn [t] (probe/reset-registry!) (t) (probe/reset-registry!)))

;; A stub host scheduler: returns the opaque cancel token + a CAUSAL
;; start-clock reading. The probe stores the token in its registry; the
;; reading later rides the reply as `:started-at`. A real surface would call
;; `setTimeout`; the probe stands that in so the suite needs no real clock.
(defn- stub-scheduler [start-ms]
  (fn [_args] {:handle (gensym "host-timer-") :started-at start-ms}))

(def ^:private base-args
  {:timer/id    :debounce/search
   :after       300
   :generation  1
   ;; The reply target is pinned under the CANONICAL `:rf/reply-to` key (the
   ;; single property-9 target spelling, Managed-Effects §The reply target).
   ;; The probe is the core-surface conformance example a future managed-async
   ;; writer copies, so it carries NO unqualified second spelling.
   :rf/reply-to [:search/timer-elapsed {:q "re-frame"}]
   :frame       :rf/default})

;; ===========================================================================
;; Property 9 — work-id correlation (Managed-Effects §Work-id correlation).
;; ===========================================================================

(deftest work-id-tuple-shape
  (testing "the timer work-id head is [:rf.work/timer logical-id generation]"
    (is (= [:rf.work/timer :debounce/search 1] (probe/work-id base-args))
        "the three-element Timer head per the Managed-Effects work-kind table")
    (is (= [:rf.work/timer :debounce/search 2]
           (probe/work-id (assoc base-args :generation 2)))
        "a fresh schedule bumps the generation — a distinct attempt, distinct work id"))
  (testing "the work id is =-comparable and EDN-serializable"
    (let [wid (probe/work-id base-args)]
      (is (= wid #?(:clj (read-string (pr-str wid)) :cljs (cljs.reader/read-string (pr-str wid))))
          "round-trips through EDN unchanged"))))

;; ===========================================================================
;; Property 2 — the closed args-map shape.
;; ===========================================================================

(deftest args-map-shape-is-closed
  (testing "a well-formed spec validates"
    (is (true? (probe/valid-args? base-args))))
  (testing "the reply target is pinned under the CANONICAL :rf/reply-to key
            (Managed-Effects §The reply target — the single property-9 spelling)"
    (is (true? (probe/valid-args? base-args)) "the canonical :rf/reply-to validates")
    (is (false? (probe/valid-args? (dissoc base-args :rf/reply-to)))
        "the reply target is required under :rf/reply-to")
    (is (false? (probe/valid-args? (-> base-args
                                       (dissoc :rf/reply-to)
                                       (assoc :reply-to [:t]))))
        "an unqualified :reply-to alias is NOT accepted — the probe pins :rf/reply-to,
         so a fresh consumer copying it inherits the canonical spelling"))
  (testing "missing / malformed required fields are rejected as data (no throw)"
    (is (false? (probe/valid-args? (dissoc base-args :timer/id))))
    (is (false? (probe/valid-args? (dissoc base-args :rf/reply-to))))
    (is (false? (probe/valid-args? (assoc base-args :after 0))) "delay must be positive")
    (is (false? (probe/valid-args? (assoc base-args :after -5))))
    (is (false? (probe/valid-args? (assoc base-args :generation :nope))))
    (is (false? (probe/valid-args? "not a map")))))

;; ===========================================================================
;; Property 1 + 7 + 8 — fx handler schedules, registers, frame-scoped.
;; ===========================================================================

(deftest probe-fx-schedules-and-registers
  (testing "the fx handler validates, schedules, and registers the in-flight timer"
    (let [receipt (probe/probe-timer-fx {:frame :rf/default} base-args (stub-scheduler 1000))]
      (is (= [:rf.work/timer :debounce/search 1] (:work/id receipt))
          "the receipt carries the timer work-id")
      (is (some? (:handle receipt)) "an opaque host handle was captured")
      (is (= 1000 (:started-at receipt)) "the causal start reading was captured")
      (is (= [[:rf.work/timer :debounce/search 1]] (probe/in-flight :rf/default))
          "the timer is now in the frame's in-flight registry")))
  (testing "a malformed spec is an fx-arg contract violation (throws), not silent"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (probe/probe-timer-fx {:frame :rf/default}
                                       (dissoc base-args :rf/reply-to)
                                       (stub-scheduler 1000))))))

(deftest registry-is-queryable-by-id
  (testing "property 7 — the framework-private registry is queryable by addressable id"
    (probe/probe-timer-fx {:frame :rf/default} base-args (stub-scheduler 1000))
    (probe/probe-timer-fx {:frame :rf/default}
                          (assoc base-args :timer/id :idle/logout :generation 5)
                          (stub-scheduler 1100))
    (is (= #{[:rf.work/timer :debounce/search 1]
             [:rf.work/timer :idle/logout 5]}
           (set (probe/in-flight :rf/default)))
        "both in-flight timers are listed by their work-id")))

(deftest registry-and-teardown-are-frame-scoped
  (testing "property 8 — two frames' in-flight timers are strictly disjoint"
    (probe/probe-timer-fx {:frame :frame/a} base-args (stub-scheduler 1000))
    (probe/probe-timer-fx {:frame :frame/b}
                          (assoc base-args :timer/id :other) (stub-scheduler 1000))
    (is (= [[:rf.work/timer :debounce/search 1]] (probe/in-flight :frame/a)))
    (is (= [[:rf.work/timer :other 1]] (probe/in-flight :frame/b)))
    (testing "tearing down frame/a leaves frame/b untouched"
      (probe/teardown-frame! :frame/a)
      (is (empty? (probe/in-flight :frame/a)) "frame/a's timers are gone")
      (is (= [[:rf.work/timer :other 1]] (probe/in-flight :frame/b))
          "frame/b's timer survives a sibling-frame teardown"))))

;; ===========================================================================
;; Property 9 — the :ok reply validates against the canonical schema.
;; ===========================================================================

(deftest ok-reply-validates
  (testing "an elapsed timer's :ok reply conforms to the canonical reply-map schema"
    (let [reply (probe/complete-elapsed base-args
                                        {:fired-at 1781078400456}
                                        {:started-at 1781078400156 :completed-at 1781078400456})]
      (is (reply/valid-reply? reply) "validates against re-frame.reply/validate-reply")
      (is (= :ok (:status reply)))
      (is (= :completed (:rf.reply/work-status reply)))
      (is (= :timer (:rf.reply/work-kind reply)))
      (is (= [:rf.work/timer :debounce/search 1] (:rf.reply/work-id reply)))
      (is (= :rf/default (:rf.frame/id reply)) "the carried frame stamp rides the reply")
      (is (= {:fired-at 1781078400456} (:value reply)) "the elapsed payload is :value (EP-0007)")
      (is (not (contains? reply :error)) ":ok carries no :error"))))

;; ===========================================================================
;; Property 3 + 9 — the failure taxonomy: :error replies carry a family :kind.
;; ===========================================================================

(deftest error-reply-carries-family-kind
  (testing "a timer whose elapsed handler failed produces a valid :status :error reply
            carrying an :rf.timer/* family :kind (the reply-map contract requires it)"
    (let [reply (probe/complete-error base-args
                                      {:message "boom"}
                                      {:completed-at 1781078400456})]
      (is (reply/valid-reply? reply))
      (is (= :error (:status reply)))
      (is (= :failed (:rf.reply/work-status reply)))
      (is (= :rf.timer/elapsed-error (-> reply :error :kind))
          "an :error reply MUST carry a family :kind"))
    (testing "an explicitly-kinded error is preserved"
      (let [reply (probe/complete-error base-args
                                        {:kind :rf.timer/clock-unavailable}
                                        {:completed-at 1})]
        (is (= :rf.timer/clock-unavailable (-> reply :error :kind)))))))

;; ===========================================================================
;; Property 9 — stale generation suppresses: :status :stale + suppression
;; trace + NO app dispatch (THE correctness boundary).
;; ===========================================================================

(deftest stale-generation-suppresses-no-dispatch
  (testing "a timer whose carried generation was superseded is SUPPRESSED:
            :status :stale, no :value, NOT delivered, and a data-only
            suppression trace joined to :work/id"
    (let [{:keys [deliver? reply trace] :as outcome}
          (probe/suppress base-args 2 {:completed-at 1781078400456})]
      (is (false? deliver?) "property 9: the app reply target MUST NOT run when stale")
      (is (= :suppressed (:rf.reply/work-status outcome)) "ledger terminal for a stale timer")

      (testing "the reply is a valid, app-state-safe :status :stale reply"
        (is (reply/valid-reply? reply))
        (is (= :stale (:status reply)))
        (is (true? (:stale? reply)))
        (is (= :rf.timer/generation-stale (:rf.reply/stale-reason reply)))
        (is (not (contains? reply :value)) "a stale reply carries NO :value (no app mutation)")
        (is (= :timer (:rf.reply/work-kind reply)))
        (is (= [:rf.work/timer :debounce/search 1] (:rf.reply/work-id reply)))
        (is (= :rf/default (:rf.frame/id reply))))

      (testing "the suppression trace carries the carried + current gates joined to :work/id"
        (is (true? (:rf.reply/suppressed? trace)))
        (is (= [:rf.work/timer :debounce/search 1] (:rf.reply/work-id trace)))
        (is (= {:generation 1} (:rf.reply/carried trace)) "carried gate")
        (is (= {:generation 2} (:rf.reply/current trace)) "current gate")
        (is (= :rf.timer/generation-stale (:rf.reply/stale-reason trace))))))

  (testing "suppress? delegates to the shared re-frame.reply/stale? over the gate
            — the probe does NOT re-implement the comparison"
    (doseq [[carried current] [[1 2] [2 2] [1 1] [3 1]]]
      (is (= (reply/stale? (probe/gate carried) (probe/gate current))
             (probe/suppress? carried current))
          (str "suppress? matches the shared stale? for carried=" carried " current=" current)))
    (is (true?  (probe/suppress? 1 2)) "superseded → stale")
    (is (false? (probe/suppress? 2 2)) "current → live"))

  (testing "a framework test/tool target MAY opt into stale delivery; app targets MUST NOT"
    (is (false? (:deliver? (probe/suppress base-args 2 {} [:t]))) "no opt-in → not delivered")
    (is (false? (:deliver? (probe/suppress base-args 2 {} {:event [:t] :dispatch-stale? false}))))
    (is (true?  (:deliver? (probe/suppress base-args 2 {}
                                           (reply/with-stale-authority {:event [:t] :dispatch-stale? true}))))
        ":dispatch-stale? true on a framework/tool-authorised target → delivered")
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (probe/suppress base-args 2 {} {:event [:t] :dispatch-stale? true}))
        "an APP target setting :dispatch-stale? true without framework authority FAILS LOUD")))

;; ===========================================================================
;; Property 9 — the reply-target functor law (Managed-Effects §Reply mapping
;; and the functor law).
;; ===========================================================================

(deftest reply-target-functor-law
  (testing "complete(map-completed-event(f, t), reply) == f(complete(t, reply)) — naturality"
    (let [target [:search/timer-elapsed {:q "re-frame"}]
          reply  (probe/complete-elapsed base-args {:fired-at 1} {:completed-at 1})
          f      (fn [event] [:wrapped event])]
      (is (= (reply/complete (reply/map-completed-event f target) reply)
             (f (reply/complete target reply)))
          "mapping the target then completing equals completing then mapping")))
  (testing "the identity + composition functor laws hold over the timer target"
    (let [target [:search/timer-elapsed {:q "re-frame"}]
          reply  (probe/complete-elapsed base-args {:fired-at 1} {:completed-at 1})
          f      (fn [e] [:f e])
          g      (fn [e] [:g e])]
      (is (= (reply/complete (reply/map-completed-event identity target) reply)
             (reply/complete target reply))
          "map-completed-event identity is the identity on completion")
      (is (= (reply/complete (reply/map-completed-event f (reply/map-completed-event g target)) reply)
             (reply/complete (reply/map-completed-event (comp f g) target) reply))
          "composition: map f ∘ map g == map (f ∘ g)")))
  (testing "mapping changes ONLY the completed event — not work id / status / stale facts"
    ;; The work id, status, and stale facts live on the REPLY (and the
    ;; suppression outcome), never on the target — so map-completed-event structurally
    ;; cannot touch them. Prove the suppression outcome is identical whether
    ;; or not the target was mapped.
    (let [mapped   (reply/map-completed-event (fn [e] [:wrapped e])
                                     {:event [:search/timer-elapsed]})
          plain-out  (probe/suppress base-args 2 {:completed-at 1} [:search/timer-elapsed])
          mapped-out (probe/suppress base-args 2 {:completed-at 1} mapped)]
      (is (= (:reply plain-out) (:reply mapped-out))
          "the stale reply is identical — mapping the target did not touch status/work-id/stale")
      (is (= (:rf.reply/work-status plain-out) (:rf.reply/work-status mapped-out))))))

;; ===========================================================================
;; Property 6 — retry / abort / teardown AS DATA.
;; ===========================================================================

(deftest retry-is-data
  (testing "the declarative :retry policy is executed as DATA (a delay schedule),
            not as caller code"
    (is (= [300] (probe/retry-plan base-args)) "no :retry ⇒ a single attempt at :after")
    (is (= [100 200 300]
           (probe/retry-plan (assoc base-args :retry {:max-attempts 3 :backoff {:base-ms 100}})))
        "the retry policy is a pure data→data transform — a backoff schedule")))

(deftest abort-requested-as-data
  (testing "property 6 — an explicit abort releases the host handle and RETURNS the
            cancellation outcome AS DATA (a :status :cancelled reply), never a callback"
    (let [receipt (probe/probe-timer-fx {:frame :rf/default} base-args (stub-scheduler 1000))
          outcome (probe/request-abort! :rf/default base-args {:completed-at 1781078400999})]
      (is (true? (:aborted? outcome)) "the in-flight timer was found and aborted")
      (is (= (:handle receipt) (:released outcome))
          "the host handle is RELEASED here — and it lived ONLY in the registry, never a reply")
      (is (empty? (probe/in-flight :rf/default)) "the timer is no longer in-flight")
      (let [reply (:reply outcome)]
        (is (reply/valid-reply? reply))
        (is (= :cancelled (:status reply)))
        (is (true? (:cancelled? reply)))
        (is (= :rf.timer/cancelled (:rf.reply/cancel-reason reply)))
        (is (= 1000 (:started-at reply)) "the causal start reading rides the cancelled reply")
        (is (= 1781078400999 (:completed-at reply))))))
  (testing "aborting a timer that is not in-flight is a no-op recorded as data"
    (let [outcome (probe/request-abort! :rf/default base-args {:completed-at 1})]
      (is (false? (:aborted? outcome)))
      (is (nil? (:reply outcome))))))

(deftest frame-destroy-cancels-and-records
  (testing "property 6 — frame teardown CANCELS in-flight timers and RECORDS the
            cancellation (never silently drops it)"
    (let [r1 (probe/probe-timer-fx {:frame :rf/default} base-args (stub-scheduler 1000))
          r2 (probe/probe-timer-fx {:frame :rf/default}
                                   (assoc base-args :timer/id :idle/logout)
                                   (stub-scheduler 1100))
          records (probe/teardown-frame! :rf/default)]
      (is (= 2 (count records)) "every in-flight timer produced a cancellation record")
      (is (= #{[:rf.work/timer :debounce/search 1] [:rf.work/timer :idle/logout 1]}
             (set (map :work/id records)))
          "the records name the cancelled timers by work-id")
      (is (= #{(:handle r1) (:handle r2)} (set (map :released records)))
          "each host handle was released and recorded")
      (is (every? #(= :rf.timer/frame-destroyed (:reason %)) records)
          "the cancellation reason is recorded")
      (is (empty? (probe/in-flight :rf/default)) "no timers remain in-flight"))))

;; ===========================================================================
;; Property 4 + 5 — trace facts route through the SHARED elision walker.
;; ===========================================================================

(deftest trace-reply-routes-through-shared-walker
  (testing "trace-reply IS the shared re-frame.reply/trace-summary — identity facts
            ride verbatim; wire slots elide through the one shared walker, never a
            family-private elider (Managed-Effects §Tracing)"
    (let [reply   (probe/complete-elapsed base-args {:fired-at 42}
                                          {:started-at 1 :completed-at 2})
          summary (probe/trace-reply reply {:frame :rf/default})]
      (is (= (reply/trace-summary reply {:frame :rf/default}) summary)
          "delegates to the shared trace-summary — never a family-private elider")
      (is (= :ok (:status summary)) "identity facts ride verbatim")
      (is (= [:rf.work/timer :debounce/search 1] (:rf.reply/work-id summary)))
      (is (= :timer (:rf.reply/work-kind summary))))))

(deftest suppression-emits-data-only-trace
  (testing "property 4 — the suppression trace is DATA ONLY (no host handles): it can
            be validated as a managed-async trace row carrying the correlation facts"
    (let [{:keys [trace]} (probe/suppress base-args 2 {:completed-at 1})]
      ;; the suppression trace is plain EDN data the trace stream emits — no fns,
      ;; no host handles. Round-tripping through pr-str proves it.
      (is (= trace #?(:clj (read-string (pr-str trace))
                      :cljs (cljs.reader/read-string (pr-str trace))))
          "the suppression trace is data-only (EDN round-trips unchanged)"))))

;; ===========================================================================
;; EP-0010 — completion facts are CAUSAL, not ambient clock reads.
;; ===========================================================================

(deftest completed-at-is-causal-not-ambient
  (testing "EP-0010 — :completed-at on the reply is the CAUSAL value supplied by the
            host completion, NOT a fresh ambient clock read inside the probe"
    ;; The probe NEVER calls a clock. We supply a fixed causal :completed-at and
    ;; prove the reply carries exactly it — if the probe read an ambient clock the
    ;; value would differ from the supplied one.
    (let [causal-completed 1781078400456
          causal-started   1781078400156
          reply (probe/complete-elapsed base-args {:fired-at causal-completed}
                                        {:started-at causal-started
                                         :completed-at causal-completed})]
      (is (= causal-completed (:completed-at reply))
          ":completed-at is the host-supplied causal reading, verbatim")
      (is (= causal-started (:started-at reply))
          ":started-at is the schedule-time causal reading, verbatim"))
    (testing "the suppression path is causal too"
      (let [{:keys [reply]} (probe/suppress base-args 2 {:completed-at 999999})]
        (is (= 999999 (:completed-at reply))
            "the stale reply's :completed-at is the supplied causal value, not ambient")))
    (testing "a family that does not durably use a timestamp omits it (no placeholder sentinel)"
      (let [reply (probe/complete-elapsed base-args {:fired-at 1} {})]
        (is (not (contains? reply :completed-at)) "omitted, not nil-filled")
        (is (not (contains? reply :started-at)))))))
