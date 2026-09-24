(ns re-frame.trace-listener-continuation-neutral-cljs-test
  "A PUBLIC trace listener body must run under a NEUTRAL continuation
  scope for its own nested authored work, while the surrounding delivery loop
  still checks the caller's EXACT continuation predicate before and after each
  callback.

  ## The hazard

  When a framework-owned lifecycle trace is fenced to an exact incarnation A
  (`re-frame.trace/call-with-continuation-predicate` bound to
  `rf.frame/event-continuation-live?` — this is what a cold `reg-flow`'s
  first-registration / replacement / clear emit does), the public listener
  fan-out runs INSIDE that binding. Were the listener body to inherit it, a
  listener that destroyed A and created same-id incarnation B with
  `:initial-events` would have B's seed `dispatch-sync!` consult
  `rf.trace/continuation-live?`, inherit A's now-FALSE predicate, and get
  silently dropped at `build-envelope` (`(when (rf.trace/continuation-live?) ...)`
  returns nil ⇒ the seed is never enqueued). B would be left live with `{}`,
  and make-frame's reuse/no-reseed law means a later re-ensure could not
  recover the seed.

  ## The reproduction

  This exercises the SAME code path a cold `reg-flow` uses — a `rf.trace/emit!`
  wrapped in `call-with-continuation-predicate` bound to A's incarnation — but
  synthetically, so the proof lives in core (where the neutral scope lives) and rides both
  `npm run test:cljs` and `clojure -M:test`. The seam is DELIBERATELY the
  public-listener boundary: the fenced emit fires with A live, the FIRST listener
  (the already-entered delivery that may stand) destroys A and creates same-id B
  with an `:initial-events` seed, and the SUBSEQUENT listener must be suppressed.

  Two teeth, each red if the listener body inherits A's predicate:
    * B's seed runs exactly once and its db is initialized (the listener body ran
      under neutral scope), and re-ensuring B does not replay it.
    * an UNRELATED frame's nested `dispatch-sync` from the same listener body —
      issued AFTER A is destroyed — still runs (neutral scope frees it too).

  One guard, green either way: the remaining A listener is suppressed after A
  is destroyed (the loop's before/after checks retain A's exact predicate — the
  outer fence). The companion control
  deftest pins the other side: a NON-destroying first listener does not
  over-suppress the subsequent listener.

  Everything runs SYNCHRONOUSLY on the single host thread — the listener destroys
  A and publishes B reentrantly inside emission — so the ordering is
  deterministic without threads. The two listeners live in a 2-entry array-map,
  which preserves insertion order on both hosts, so the destroyer (registered
  FIRST) fans out first."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]
            [re-frame.trace                :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- reg-test-events! []
  ;; Pure handlers. `:seed` initializes B's db AND bumps a db-level run counter,
  ;; so a wrongful replay would show `:seed-count 2`. `:mark` is the unrelated
  ;; frame's nested-dispatch write.
  (rf/reg-event :trace.neutral/seed
    (fn [{:keys [db]} _]
      {:db (-> db (assoc :seeded :ok) (update :seed-count (fnil inc 0)))}))
  (rf/reg-event :trace.neutral/mark
    (fn [{:keys [db]} _] {:db (assoc db :marked true)})))

(defn- fire-fenced-emit!
  "Fire a synthetic trace emit fenced to `[frame-id token]`'s exact incarnation —
  the same shape a cold `reg-flow` uses for its lifecycle traces. Listeners see
  `:operation :rf.test/fenced-emit`."
  [frame-id token]
  (rf.trace/call-with-continuation-predicate
    #(rf.frame/event-continuation-live? frame-id token)
    (fn []
      (rf.trace/emit! :rf.registry :rf.test/fenced-emit {:frame frame-id}))))

;; ---- Posture: dev-only, declared by `^:requires-debug` ---------------------
;; Trace machinery end to end: under `-Dre-frame.debug=false` `rf.trace/emit` is a
;; no-op, so there is no semantic residue to run under that posture, and a
;; `(when interop/debug-enabled? ...)` split would leave EMPTY deftests
;; reporting green.  Every deftest
;; below is therefore TAGGED, and the production-gate lane skips the tag rather
;; than the file: the namespace is still LOADED there, so a load-time failure
;; under the gate still reddens the job, and an untagged new deftest joins that
;; lane BY DEFAULT.  Mechanism + rationale: `scripts/test-core-prod-gate.sh`.

(deftest ^:requires-debug listener-body-runs-neutral-while-fence-suppresses-a-tail
  (reg-test-events!)
  (let [a-id       :trace.neutral/subject   ;; A and same-id successor B
        c-id       :trace.neutral/unrelated
        b-live?    (atom nil)
        b-token    (atom nil)
        b-db       (atom ::unset)           ;; B's db AT callback time
        later-hits (atom 0)                 ;; hits on the SUBSEQUENT A listener
        armed?     (atom true)]
    (rf/make-frame {:id c-id})
    (rf/make-frame {:id a-id})
    (let [a-token (rf.frame/frame-incarnation-token a-id)]
      ;; Listener 1 (FIRST → fans out first): the DESTROYER — the already-entered
      ;; delivery. Destroys A, creates same-id B with a seed, then does UNRELATED
      ;; nested work into C. All of this is a listener body: it must run neutral.
      (rf.trace.tooling/register-listener! ::destroyer
        (fn [ev]
          (when (and (= :rf.test/fenced-emit (:operation ev))
                     (compare-and-set! armed? true false))
            (rf.frame/destroy-frame! a-id)
            (rf/make-frame {:id a-id :initial-events [[:trace.neutral/seed]]})
            (reset! b-token (rf.frame/frame-incarnation-token a-id))
            (reset! b-live? (some? (rf.frame/frame-incarnation-token a-id)))
            (reset! b-db (rf/app-db-value a-id))
            ;; Unrelated-frame nested dispatch AFTER A is destroyed — under A's
            ;; now-false predicate this too would be strangled.
            (rf/dispatch-sync [:trace.neutral/mark] {:frame c-id}))))
      ;; Listener 2 (SECOND → subsequent): must NOT fire once A is destroyed.
      (rf.trace.tooling/register-listener! ::later-a
        (fn [ev]
          (when (= :rf.test/fenced-emit (:operation ev))
            (swap! later-hits inc))))
      (try
        (fire-fenced-emit! a-id a-token)

        ;; --- tooth 1: the listener body ran under neutral scope ---
        (is (true? @b-live?) "B is live after the callback")
        (is (not (identical? a-token @b-token))
            "B is a DISTINCT same-id incarnation, not A")
        (is (= {:seeded :ok :seed-count 1} @b-db)
            "B's :initial-events seed ran during the callback and initialized B's
             db — the listener body was NOT strangled by A's dead predicate
             (under A's predicate it would be {})")
        (is (= 1 (:seed-count (rf/app-db-value a-id)))
            "B's seed ran EXACTLY once")

        ;; --- tooth 2: unrelated-frame nested dispatch was unaffected ---
        (is (= {:marked true} (rf/app-db-value c-id))
            "the listener's nested dispatch into an UNRELATED frame ran under
             neutral scope (under A's predicate it would be dropped once A was
             destroyed)")

        ;; --- guard: the outer fence still suppresses A's remaining fan-out ---
        (is (zero? @later-hits)
            "the SUBSEQUENT listener was suppressed after A was destroyed — the
             delivery loop retained A's exact predicate for its before/after
             checks")

        ;; --- tooth 1 (cont.): re-ensuring B does not replay the seed ---
        (rf/make-frame {:id a-id :initial-events [[:trace.neutral/seed]]})
        (is (= 1 (:seed-count (rf/app-db-value a-id)))
            "re-ensuring the LIVE B re-records but does NOT replay :initial-events")
        (is (= {:seeded :ok :seed-count 1} (rf/app-db-value a-id))
            "B's db is unchanged by the re-ensure")
        (finally
          (rf.trace.tooling/unregister-listener! ::destroyer)
          (rf.trace.tooling/unregister-listener! ::later-a))))))

(deftest ^:requires-debug non-destroying-listener-does-not-over-suppress-subsequent-listener
  ;; Mutation guard for the loop's before/after check. When the first listener
  ;; leaves A LIVE (and merely does its own unrelated nested work under neutral
  ;; scope), the subsequent listener MUST still receive A's fenced event — the
  ;; snapshot predicate must not over-fence. A snapshot that always read false
  ;; would silently swallow this.
  (reg-test-events!)
  (let [a-id      :trace.neutral/live-subject
        c-id      :trace.neutral/live-unrelated
        first-hit (atom 0)
        later-hit (atom 0)]
    (rf/make-frame {:id c-id})
    (rf/make-frame {:id a-id})
    (let [a-token (rf.frame/frame-incarnation-token a-id)]
      (rf.trace.tooling/register-listener! ::observer
        (fn [ev]
          (when (= :rf.test/fenced-emit (:operation ev))
            (swap! first-hit inc)
            ;; nested unrelated work, A stays live
            (rf/dispatch-sync [:trace.neutral/mark] {:frame c-id}))))
      (rf.trace.tooling/register-listener! ::subsequent
        (fn [ev]
          (when (= :rf.test/fenced-emit (:operation ev))
            (swap! later-hit inc))))
      (try
        (fire-fenced-emit! a-id a-token)
        (is (= 1 @first-hit) "the first listener received A's fenced event")
        (is (= 1 @later-hit)
            "the SUBSEQUENT listener also received it — the live-owner fan-out is
             not over-fenced by the neutralization")
        (is (identical? a-token (rf.frame/frame-incarnation-token a-id))
            "A remained the live incarnation throughout")
        (is (= {:marked true} (rf/app-db-value c-id))
            "the first listener's nested unrelated dispatch ran normally")
        (finally
          (rf.trace.tooling/unregister-listener! ::observer)
          (rf.trace.tooling/unregister-listener! ::subsequent))))))
