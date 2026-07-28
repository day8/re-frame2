(ns re-frame.frame-destroy-incarnation-jvm-test
  "Deterministic JVM barriers for incarnation-owned frame teardown.

  ## Posture split (rf2-d2841)

  THE EPOCH SUBSYSTEM IS TRACE-FED, AND THAT IS THE WHOLE STORY HERE.
  `epoch.capture/observe-trace-event!` fills the capture buffer from the DEV
  TRACE, so under `-Dre-frame.debug=false` no buffer fills, `epoch/settle!`
  never builds a record, `rf/epoch-history` stays empty,
  `epoch-state/buffer-for` stays empty, and no `:rf.epoch/*` /
  `:rf.epoch.cb/*` trace fact is ever emitted. Every read of those stores is
  therefore guarded.

  What survives, and what these barriers are ACTUALLY about, is the
  incarnation fence itself: which token is live, whose db write commits, whose
  child dispatch is scheduled, and which corpus-wide `:errors` record ships.
  All of that is production behaviour and stays outside the guard. The class
  of defect this file exists to catch — a destroyed incarnation leaking into
  a same-id successor and MUTATING it — is pinned by the token / app-db /
  cleanup-count assertions, and those now run under the gate for the first
  time.

  TWO SCENARIOS ARE DEV-ONLY END TO END and are guarded wholesale, because
  their DRIVER does not exist in this posture, not merely their observation:

  - `epoch-digest-owner-loss-cannot-publish-into-successor` destroys A from
    inside the `:schemas/app-schemas-digest` late-bind hook. That hook is
    reached only from `epoch/settle!` (via `assembly/current-schema-digest`),
    which the empty capture buffer never calls — checked by running: the
    fixture's `CountDownLatch` await TIMED OUT under the gate, so A was never
    destroyed at all and the deftest's four negatives passed over a scenario
    that had not happened.
  - the two `vf2qke` deftests are driven by a public TRACE listener reacting
    to `:rf.epoch.cb/silenced-on-frame-destroy`. No such fact is emitted under
    the gate, so the listener never fires and the nested cascade it is about
    never runs.

  ONE CLAIM WAS PROMOTED RATHER THAN GUARDED. A throwing teardown hook already
  ships a bounded always-on `:rf.error/frame-teardown-failed` record whose
  `:hook-failures` NAMES the failing hook — `stale-teardown-report-is-corpus-
  only-after-successor-install` and `snapshot-hook-failure-still-publishes-
  reports-and-spares-successor` both read it already. The two
  `*-hook-exception-crosses-successor-no-emit` scenarios and
  `snapshot-hook-failure-leaves-no-residual-ring` were reading only the
  DEV-TRACE warning; they now assert the always-on report as well, so \"the
  failing hook is named to an off-box shipper\" runs in the posture that ships.

  SEVENTEEN VACUOUS PASSES CAME OFF, and their shape is uniform: a NEGATIVE
  asserting that predecessor A's terminal evidence did NOT leak into successor
  B's ring / buffer / history, evaluated over stores the gate never lets fill.
  `(empty? (epoch-state/buffer-for id))`, `(empty? (rf/epoch-history id))`,
  `(= b-ring-before (rf/trace-buffer id {:flat true}))` and their siblings are
  all true for free when both sides are empty. Those are the assertions this
  file most wants to be honest — a leak audit that passes because nothing was
  ever recorded certifies nothing — so every one of them is now inside the
  guard, next to the precondition that licenses it."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.epoch]
            [re-frame.epoch.state :as epoch-state]
            [re-frame.frame :as frame]
            [re-frame.interceptor :as interceptor]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.router :as router]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- executor-barrier!
  "Wait until work already submitted through `interop/next-tick` has run."
  []
  (let [latch (CountDownLatch. 1)]
    (interop/next-tick #(.countDown latch))
    (is (.await latch 5 TimeUnit/SECONDS)
        "the executor reached the deterministic barrier")))

(deftest expected-incarnation-token-controls-destroy-authority
  (rf/make-frame {:id :destroy/token})
  (let [live-token (frame/frame-incarnation-token :destroy/token)]
    (is (nil? (frame/destroy-frame! :destroy/token (atom false)))
        "a mismatched token is a silent no-op")
    (is (identical? live-token
                    (frame/frame-incarnation-token :destroy/token))
        "a mismatched token leaves the live incarnation unchanged")
    (is (nil? (frame/destroy-frame! :destroy/token live-token))
        "the matching token keeps destroy-frame!'s nil return contract")
    (is (nil? (frame/frame :destroy/token))
        "the matching token destroys its incarnation")))

(deftest stale-destroy-revalidates-after-candidate-capture
  ;; Pause the expected-token destroy AFTER it captures incarnation A. Another
  ;; actor replaces the id with B before the destroy enters drain serialization.
  ;; On resume, core must revalidate the token under the lifecycle gate and no-op
  ;; rather than applying A's stale authority to B.
  (rf/make-frame {:id :destroy/race})
  (let [token-a   (frame/frame-incarnation-token :destroy/race)
        captured  (CountDownLatch. 1)
        release   (CountDownLatch. 1)
        probe-var (ns-resolve 're-frame.frame '*destroy-claim-probe*)
        stale     (with-bindings
                    {probe-var
                     (fn [id token]
                       (when (and (= :destroy/race id)
                                  (identical? token-a token))
                         (.countDown captured)
                         (.await release 10 TimeUnit/SECONDS)))}
                    (future
                      (frame/destroy-frame! :destroy/race token-a)))]
    (is (.await captured 10 TimeUnit/SECONDS)
        "the stale destroy captured incarnation A before the replacement")
    (frame/destroy-frame! :destroy/race)
    (rf/make-frame {:id :destroy/race})
    (let [token-b (frame/frame-incarnation-token :destroy/race)]
      (is (and (some? token-b) (not (identical? token-a token-b)))
          "the actor installed a distinct incarnation B")
      (.countDown release)
      (is (nil? @stale) "the stale expected-token destroy returns a silent no-op")
      (is (identical? token-b
                      (frame/frame-incarnation-token :destroy/race))
          "incarnation B survives stale teardown unchanged"))))

(deftest concurrent-duplicate-destroy-is-a-non-blocking-no-op
  (rf/make-frame {:id :destroy/duplicate
                  :on-destroy [:destroy/duplicate-cleanup]})
  (let [cleanup-runs (atom 0)
        claimed      (CountDownLatch. 1)
        release      (CountDownLatch. 1)
        original     (late-bind/get-fn :ui/on-frame-destroyed!)]
    (rf/reg-event :destroy/duplicate-cleanup
      (fn [_ _]
        (swap! cleanup-runs inc)
        {}))
    (try
      ;; The UI hook is after claim publication and before lifecycle-dead
      ;; publication. Hold the winning destroy there while a second thread
      ;; attempts the same incarnation's destroy.
      (late-bind/set-fn!
        :ui/on-frame-destroyed!
        (fn [id]
          (when original (original id))
          (when (= :destroy/duplicate id)
            (.countDown claimed)
            (.await release 10 TimeUnit/SECONDS))))
      (let [winner (future (frame/destroy-frame! :destroy/duplicate))]
        (is (.await claimed 10 TimeUnit/SECONDS)
            "the winning destroy published its claim")
        (let [duplicate (future (frame/destroy-frame! :destroy/duplicate))]
          (is (nil? (deref duplicate 5000 ::timeout))
              "the duplicate observes the claim and returns without waiting for teardown"))
        (.countDown release)
        (is (nil? (deref winner 5000 ::timeout))
            "the winning destroy preserves the nil return contract"))
      (finally
        (.countDown release)
        (late-bind/set-fn! :ui/on-frame-destroyed! original)))
    (is (= 1 @cleanup-runs)
        "the user cleanup event runs exactly once")
    (is (nil? (frame/frame :destroy/duplicate))
        "the claimed incarnation is fully destroyed")))

(deftest fresh-same-id-destroy-replaces-stale-marker-token-safely
  ;; Pause A after its registry dissoc but before its terminal finally. Install
  ;; B under the reused id, claim B's destroy, then let A's finally run while B
  ;; is paused pre-liveness-flip. B must be destroyable despite A's stale marker,
  ;; and A's finally must not erase B's replacement marker.
  (let [id              :destroy/marker-overlap
        a-after-dissoc  (CountDownLatch. 1)
        release-a       (CountDownLatch. 1)
        b-claimed       (CountDownLatch. 1)
        release-b       (CountDownLatch. 1)
        first-epoch?    (atom true)
        b-installed?    (atom false)
        cleanup-runs    (atom 0)
        b-observer      ::replacement-b-observer
        original-epoch  (late-bind/get-fn :epoch/on-frame-destroyed)
        original-ui     (late-bind/get-fn :ui/on-frame-destroyed!)]
    (rf/make-frame {:id id})
    (try
      (late-bind/set-fn!
        :epoch/on-frame-destroyed
        (fn [& args]
          ;; The epoch hook is after dissoc-frame!. Hold only A's first call.
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch
            (apply original-epoch args))))
      (late-bind/set-fn!
        :ui/on-frame-destroyed!
        (fn [frame-id]
          (when original-ui (original-ui frame-id))
          ;; The UI hook is after claim publication and before B's lifecycle
          ;; flip. Hold every B teardown attempt here: an erroneously-authorised
          ;; duplicate will block, making A-finally marker erasure observable.
          (when (and (= id frame-id) @b-installed?)
            (.countDown b-claimed)
            (.await release-b 10 TimeUnit/SECONDS))))

      (let [destroy-a (future (frame/destroy-frame! id))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "incarnation A is paused post-dissoc with its claim marker live")
        (rf/reg-event :destroy/marker-overlap-cleanup
          (fn [_ _]
            (swap! cleanup-runs inc)
            {}))
        (rf/reg-event :destroy/marker-overlap-epoch
          (fn [{:keys [db]} _]
            {:db (assoc db :replacement :b)}))
        (rf/make-frame {:id id
                        :on-destroy [:destroy/marker-overlap-cleanup]})
        ;; Give B real recorded history + listener observation before its
        ;; destroy claims the incarnation.
        (epoch-state/put-listener! b-observer (fn [_] nil))
        (rf/dispatch-sync [:destroy/marker-overlap-epoch] {:frame id})
        (let [token-b        (frame/frame-incarnation-token id)
              b-buffer-event {:operation :destroy/replacement-b-buffer
                              :tags {:frame id}}
              b-render-key   ::replacement-b-render
              b-sub-id       ::replacement-b-sub]
          (reset! b-installed? true)
          (let [destroy-b (future (frame/destroy-frame! id token-b))]
            (is (.await b-claimed 10 TimeUnit/SECONDS)
                "fresh B replaces A's stale marker and claims its own destroy")

            ;; B's cleanup event and drain check legitimately mutate/clear
            ;; epoch state before the UI hook pause. Install the remaining
            ;; B-owned fixtures only after that pause, then use B's resulting
            ;; newest epoch as the preservation anchor. Nothing in A's stale
            ;; step-11 cleanup may alter any of these four id-keyed stores.
            (let [b-epoch-id   (:epoch-id (last (rf/epoch-history id)))
                  b-generation (get-in (epoch-state/listeners-snapshot)
                                       [b-observer :generation])]
              (epoch-state/buffer-event! id b-buffer-event)
              (epoch-state/record-render-deps! id b-render-key b-sub-id)
              (epoch-state/record-observation! b-observer b-generation id)
              (is (some? (get-in (epoch-state/observations-snapshot)
                                 [b-observer id]))
                  "B's listener observation fixture is armed before A resumes")

              ;; A now reaches its terminal cleanup while B's distinct claim is
              ;; active. Correct compare-remove leaves B's marker untouched.
              (.countDown release-a)
              (is (nil? (deref destroy-a 5000 ::timeout))
                  "A finishes while B remains paused under its own claim")
              (is (= b-epoch-id (:epoch-id (last (rf/epoch-history id))))
                  "A's stale epoch cleanup preserves B's history")
              (is (= [b-buffer-event] (epoch-state/buffer-for id))
                  "A's stale epoch cleanup preserves B's in-flight buffer")
              (is (= #{b-sub-id}
                     (epoch-state/render-deps-for id b-render-key))
                  "A's stale epoch cleanup preserves B's render attribution")
              (is (some? (get-in (epoch-state/observations-snapshot)
                                 [b-observer id]))
                  "A's stale epoch cleanup preserves B's listener observation"))
            (let [duplicate-b (future (frame/destroy-frame! id token-b))]
              (is (nil? (deref duplicate-b 2000 ::timeout))
                  "A's finally did not erase B's marker; duplicate B is a prompt no-op"))

            (.countDown release-b)
            (is (nil? (deref destroy-b 5000 ::timeout))
                "B's owning destroy completes")
            (is (= 1 @cleanup-runs)
                "B cleanup runs once; no duplicate teardown acquired authority")
            (is (nil? (frame/frame id)) "the reused id is fully destroyed"))))
      (finally
        (.countDown release-a)
        (.countDown release-b)
        (epoch-state/drop-listener! b-observer)
        (late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)
        (late-bind/set-fn! :ui/on-frame-destroyed! original-ui)))))

(deftest destroyed-incarnation-cannot-commit-its-returned-tail-into-replacement
  ;; A handler destroys its own incarnation and pauses after the registry
  ;; dissoc. Install same-id B while that handler is still on the stack, then
  ;; let A return a db effect and a child dispatch. Every part of that returned
  ;; tail belongs to A and must be discarded rather than redirected into B.
  (let [id             :destroy/event-tail-overlap
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        child-runs     (atom 0)
        traces         (atom [])
        original-epoch (late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :destroy/event-tail-child
      (fn [{:keys [db]} _]
        (swap! child-runs inc)
        {:db (assoc db :child :from-a)}))
    (rf/reg-event :destroy/event-tail-a
      (fn [_ _]
        (frame/destroy-frame! id)
        {:db {:owner :a-tail}
         :fx [[:dispatch [:destroy/event-tail-child]]]}))
    (rf/reg-event :destroy/event-tail-b
      (fn [_ _]
        {:db {:owner :b}}))
    (rf/make-frame {:id id})
    (rf/register-listener! :trace ::event-tail-overlap
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (contains? #{:rf.frame/drain-interrupted
                                :rf.epoch/snapshotted
                                :rf.epoch/outcome}
                              (:operation ev)))
          (swap! traces conj ev))))
    (try
      (late-bind/set-fn!
        :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch
            (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:destroy/event-tail-a]
                                           {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A is paused post-dissoc before its handler returns")
        ;; B deliberately suppresses ordinary frame-tagged traces. A's stale
        ;; structural drain-interrupted fact must bypass B's policy without
        ;; entering B's epoch capture buffer.
        (rf/make-frame {:id id :rf.trace/frame-no-emit? true})
        (let [token-b   (frame/frame-incarnation-token id)
              history-b (rf/epoch-history id)]
          (.countDown release-a)
          (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
              "A's already-dequeued handler is allowed to return")
          (executor-barrier!)
          (is (identical? token-b (frame/frame-incarnation-token id))
              "the replacement incarnation remains current")
          (is (= {} (frame/frame-app-db-value id))
              "A's returned db effect cannot mutate B")
          (is (zero? @child-runs)
              "A's returned child dispatch is not scheduled against B")
          ;; VACUOUS PASSES REMOVED (rf2-d2841) — class 3 and class 1: under
          ;; the gate `history-b` and `(rf/epoch-history id)` are BOTH empty,
          ;; so the leak audit passed on `[] = []`, and the capture buffer is
          ;; empty for every frame, leaked-into or not.
          (when interop/debug-enabled?
            (is (= history-b (rf/epoch-history id))
                "A's stale tail cannot append or settle into B's history")
            (is (empty? (epoch-state/buffer-for id))
                "A's structural interruption bypasses B's epoch capture")
            (let [by-op (group-by :operation @traces)]
              (is (= 1 (count (get by-op :rf.frame/drain-interrupted)))
                  "A's structural interruption bypasses B's frame-no-emit policy")
              (is (= [:halted-destroy]
                     (mapv #(get-in % [:tags :outcome])
                           (get by-op :rf.epoch/snapshotted)))
                  "A's terminal detailed epoch fact bypasses B's policy")
              (is (= [:blocked]
                     (mapv #(get-in % [:tags :outcome])
                           (get by-op :rf.epoch/outcome)))
                  "A's terminal consumer outcome bypasses B's policy")))
          (rf/dispatch-sync [:destroy/event-tail-b] {:frame id})
          (is (= {:owner :b} (frame/frame-app-db-value id))
              "B remains independently usable after A's stale tail returns")))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :trace ::event-tail-overlap)
        (late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

(deftest predecessor-halted-destroy-evidence-survives-successor-epoch-claim
  ;; rf2-vxgfnd.151 (red before fix). A is destroyed mid-drain and paused at its
  ;; POST-DISSOC epoch hook. Same-id B is then created and SETTLES an ordinary
  ;; event, claiming the id-keyed epoch stores (which drops A's buffer and
  ;; observation ledger). When A resumes it must STILL publish its terminal
  ;; halted-destroy evidence: the record was snapshotted BEFORE dissoc, and its
  ;; publication is decoupled from winning the compare-cleanup. B's stores stay
  ;; byte-identical.
  ;;
  ;; Before the fix the unfixed path read the buffer AFTER dissoc (B had already
  ;; dropped it → no record) AND gated publication on winning the cleanup
  ;; comparison (B owned → nil → no publication): zero A records, zero trailers.
  (let [id             :destroy/halted-evidence-overlap
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        a-records      (atom [])
        traces         (atom [])
        b-observer     ::b-halted-observer
        original-epoch (late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :destroy/halted-a
      (fn [_ _]
        (frame/destroy-frame! id)
        {:db {:owner :a-tail}}))
    (rf/reg-event :destroy/halted-b-settle
      (fn [{:keys [db]} _]
        {:db (assoc db :owner :b)}))
    (rf/make-frame {:id id})
    ;; Capture A's terminal epoch record + its structural trailer pair.
    (rf/register-listener! :epoch ::a-epoch-watch
      (fn [r] (when (= :halted-destroy (:outcome r)) (swap! a-records conj r))))
    (rf/register-listener! :trace ::halted-evidence-overlap
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (contains? #{:rf.epoch/snapshotted :rf.epoch/outcome}
                              (:operation ev)))
          (swap! traces conj ev))))
    (try
      (late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          ;; The post-dissoc publish hook. Hold only A's first (halted) call.
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:destroy/halted-a] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A is paused at its post-dissoc epoch hook (evidence already
             snapshotted before dissoc)")
        ;; Same-id B, with its own observer, SETTLES an ordinary event — its
        ;; first captured event claims the id-keyed epoch stores, dropping A's
        ;; buffer + observation ledger.
        (rf/make-frame {:id id})
        (epoch-state/put-listener! b-observer (fn [_] nil))
        (rf/dispatch-sync [:destroy/halted-b-settle] {:frame id})
        (let [token-b      (frame/frame-incarnation-token id)
              b-history    (rf/epoch-history id)
              b-buffer     (epoch-state/buffer-for id)
              b-last-epoch (epoch-state/last-settled-epoch-id id)
              b-db         (frame/frame-app-db-value id)]
          ;; The epoch preconditions and the leak audit they license are
          ;; guarded TOGETHER (rf2-d2841). Separating a precondition from its
          ;; claim is how `(= b-history (rf/epoch-history id))` came to certify
          ;; "B's history is untouched" over two empty vectors.
          (when interop/debug-enabled?
            (is (= 1 (count b-history))
                "B settled one ordinary event and now owns the id-keyed stores")
            (is (some? (get-in (epoch-state/observations-snapshot) [b-observer id]))
                "B's observer is armed before A resumes"))

          ;; A resumes and reaches its terminal publish while B owns the stores.
          (.countDown release-a)
          (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
              "A's terminal recipe completes")
          (executor-barrier!)

          ;; ALWAYS-ON (rf2-d2841): the incarnation fence itself. Whatever A
          ;; publishes, A's inert returned tail must not become B's state and
          ;; B must remain the live incarnation — the defect class this file
          ;; exists for, now exercised in the posture that ships.
          (is (identical? token-b (frame/frame-incarnation-token id))
              "B remains the current incarnation")
          (is (= {:owner :b} b-db)
              "A's inert returned tail never mutated B's state")
          (is (= {:owner :b} (frame/frame-app-db-value id))
              "B's state is unchanged after A resumes")

          (when interop/debug-enabled?
            ;; A's terminal evidence is published DESPITE B owning the stores.
            (is (= 1 (count @a-records))
                "exactly one A :halted-destroy record reaches epoch listeners")
            (is (= :destroy/halted-a (:event-id (first @a-records)))
                "the terminal record is A's destroying event")
            ;; B's ordinary settle also emits :ok trailers on the same frame id;
            ;; scope to A's TERMINAL outcomes (B's are :ok, A's are
            ;; :halted-destroy / :blocked).
            (let [by-op (group-by :operation @traces)]
              (is (= 1 (count (filter #(= :halted-destroy (get-in % [:tags :outcome]))
                                      (get by-op :rf.epoch/snapshotted))))
                  "exactly one A :halted-destroy :rf.epoch/snapshotted is published")
              (is (= 1 (count (filter #(= :blocked (get-in % [:tags :outcome]))
                                      (get by-op :rf.epoch/outcome))))
                  "exactly one A blocked :rf.epoch/outcome is published"))

            ;; B's stores are byte-identical: A's compare-cleanup no-op'd.
            ;; VACUOUS PASSES REMOVED (class 3): all four compared empty
            ;; against empty under the gate.
            (is (= b-history (rf/epoch-history id))
                "B's history is untouched by A's terminal cleanup")
            (is (= b-buffer (epoch-state/buffer-for id))
                "B's capture buffer is untouched")
            (is (= b-last-epoch (epoch-state/last-settled-epoch-id id))
                "B's last-settled anchor is untouched")
            (is (some? (get-in (epoch-state/observations-snapshot) [b-observer id]))
                "B's listener observation survives A's terminal cleanup"))

          ;; B keeps handling events normally.
          (rf/dispatch-sync [:destroy/halted-b-settle] {:frame id})
          (is (= {:owner :b} (frame/frame-app-db-value id))
              "B keeps committing its own events after A resumes")
          (when interop/debug-enabled?
            (is (= 2 (count (rf/epoch-history id)))
                "B settles subsequent events normally after A resumes"))))
      (finally
        (.countDown release-a)
        (epoch-state/drop-listener! b-observer)
        (rf/unregister-listener! :epoch ::a-epoch-watch)
        (rf/unregister-listener! :trace ::halted-evidence-overlap)
        (late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

;; ---- rf2-vxgfnd.152 — predecessor terminal diagnostics across B no-emit -----

(defn- run-terminal-listener-exception-scenario
  "Destroy A mid-drain and pause at its post-dissoc epoch hook; install same-id
  B (with the given `no-emit?` policy) during the pause; on resume a terminal
  epoch listener throws while receiving A's halted record. Returns the captured
  `:rf.epoch.cb/listener-exception` traces plus B state. A's terminal
  listener-exception must be delivered structurally regardless of B's policy
  (rf2-vxgfnd.152).

  B is installed on the main thread during the pause (not re-entrantly from the
  listener) so a same-id B is unambiguously live when the diagnostic emits."
  [id no-emit?]
  (let [a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        exceptions     (atom [])
        thrower        (keyword "rf2-152" (str "lx-thrower-" (name id)))
        trace-key      (keyword "rf2-152" (str "lx-trace-" (name id)))
        original-epoch (late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :rf2-152/lx-destroy-self
      (fn [_ _] (frame/destroy-frame! id) {}))
    (rf/make-frame {:id id})
    ;; Terminal listener that throws while receiving A's halted record.
    (rf/register-listener! :epoch thrower
      (fn [r]
        (when (= :halted-destroy (:outcome r))
          (throw (ex-info "terminal listener blew" {})))))
    (rf/register-listener! :trace trace-key
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (= :rf.epoch.cb/listener-exception (:operation ev)))
          (swap! exceptions conj ev))))
    (try
      (late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:rf2-152/lx-destroy-self] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A paused at its post-dissoc epoch hook")
        (rf/make-frame {:id id :rf.trace/frame-no-emit? no-emit?})
        (let [token-b (frame/frame-incarnation-token id)]
          (.countDown release-a)
          (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
              "A's terminal recipe completes")
          (executor-barrier!)
          {:exceptions   @exceptions
           :thrower      thrower
           :b-token-ok?  (identical? token-b (frame/frame-incarnation-token id))
           :b-buffer     (epoch-state/buffer-for id)
           :b-history    (rf/epoch-history id)}))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch thrower)
        (rf/unregister-listener! :trace trace-key)
        (when (frame/frame id) (frame/destroy-frame! id))
        (late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

(deftest terminal-listener-exception-crosses-successor-no-emit
  ;; rf2-vxgfnd.152 (red before fix). A terminal halted-destroy listener that
  ;; throws while a same-id B is live must still yield exactly one A
  ;; :rf.epoch.cb/listener-exception — whether or not B enabled no-emit — and
  ;; that diagnostic must not enter B's epoch capture. Before the fix the
  ;; diagnostic was resolved through B's bare-id policy, so a no-emit B
  ;; suppressed it to zero.
  (let [suppressed (run-terminal-listener-exception-scenario
                     :rf2-152/lx-noemit true)
        control    (run-terminal-listener-exception-scenario
                     :rf2-152/lx-plain false)]
    ;; ALWAYS-ON (rf2-d2841): the incarnation fence. Whatever A's terminal
    ;; fan-out does or fails to do, same-id B must still be the live
    ;; incarnation when it returns.
    (is (true? (:b-token-ok? suppressed))
        "same-id B remains the live incarnation through A's terminal fan-out")
    (is (true? (:b-token-ok? control))
        "and in the control, where B did not enable no-emit")
    ;; The `:rf.epoch.cb/listener-exception` diagnostic is a dev-trace fact
    ;; with no promoted counterpart, and the epoch record that TRIGGERS the
    ;; throwing listener is itself trace-fed, so this scenario is dev-only end
    ;; to end. VACUOUS PASSES REMOVED (class 1) — the two leak audits below
    ;; passed over a buffer and a history the gate never let fill.
    (when interop/debug-enabled?
      (is (= 1 (count (:exceptions suppressed)))
          "A's terminal listener-exception is delivered despite same-id B no-emit")
      (is (= (:thrower suppressed)
             (get-in (first (:exceptions suppressed)) [:tags :cb-id]))
          "the diagnostic names the failing terminal listener")
      (is (= 1 (count (:exceptions control)))
          "the control (B no-emit disabled) also yields exactly one diagnostic")
      (is (empty? (filter #(= :rf.epoch.cb/listener-exception (:operation %))
                          (:b-buffer suppressed)))
          "A's diagnostic never enters B's epoch capture buffer")
      (is (empty? (:b-history suppressed))
          "no A diagnostic is recorded into B's history"))))

(defn- run-terminal-teardown-hook-exception-scenario
  "Destroy A; its post-dissoc epoch teardown hook installs same-id B (with the
  given `no-emit?` policy) then throws. Returns the captured
  `:rf.warning/teardown-hook-exception` traces plus B state. A's post-dissoc
  teardown-hook warning must be delivered structurally regardless of B's policy
  (rf2-vxgfnd.152)."
  [id no-emit?]
  (let [warnings    (atom [])
        ;; ALWAYS-ON (rf2-d2841): a failing teardown hook also ships a bounded
        ;; corpus-wide `:rf.error/frame-teardown-failed` record whose
        ;; `:hook-failures` names the hook. That is the channel an off-box
        ;; shipper reads, and it survives the gate.
        reports     (atom [])
        trace-key   (keyword "rf2-152" (str "tdtrace-" (name id)))
        original-ep (late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/make-frame {:id id})
    (rf/register-listener! :trace trace-key
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (= :rf.warning/teardown-hook-exception (:operation ev)))
          (swap! warnings conj ev))))
    (rf/register-listener! :errors trace-key
      (fn [r]
        (when (and (= :rf.error/frame-teardown-failed (:error r))
                   (= id (:frame r)))
          (swap! reports conj r))))
    (try
      (late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (if (= id (first args))
            (do
              ;; Install same-id B, then fail the post-dissoc epoch hook: B's
              ;; no-emit policy must not suppress A's teardown-hook warning.
              (rf/make-frame {:id id :rf.trace/frame-no-emit? no-emit?})
              (throw (ex-info "epoch teardown blew" {})))
            (when original-ep (apply original-ep args)))))
      (frame/destroy-frame! id)
      (executor-barrier!)
      {:warnings  @warnings
       :reports   @reports
       :b-live?   (some? (frame/frame-incarnation-token id))
       :b-buffer  (epoch-state/buffer-for id)}
      (finally
        ;; Restore the real hook BEFORE destroying B so B's teardown does not
        ;; re-enter the throwing wrapper.
        (late-bind/set-fn! :epoch/on-frame-destroyed original-ep)
        (rf/unregister-listener! :trace trace-key)
        (rf/unregister-listener! :errors trace-key)
        (when (frame/frame id) (frame/destroy-frame! id))))))

(deftest terminal-teardown-hook-exception-crosses-successor-no-emit
  ;; rf2-vxgfnd.152 (red before fix). A throwing POST-DISSOC epoch teardown hook
  ;; must still yield exactly one A :rf.warning/teardown-hook-exception — whether
  ;; or not the same-id successor B enabled no-emit — and it must not enter B's
  ;; epoch capture. Before the fix the warning went through the ordinary bare-id
  ;; policy path, so a no-emit B suppressed it to zero.
  (let [suppressed (run-terminal-teardown-hook-exception-scenario
                     :rf2-152/td-noemit true)
        control    (run-terminal-teardown-hook-exception-scenario
                     :rf2-152/td-plain false)]
    ;; ALWAYS-ON (rf2-d2841): the bounded corpus-wide report is the shipping
    ;; channel for exactly this failure, and it carries the hook's name. B's
    ;; no-emit policy is a TRACE policy and must not suppress it — which is
    ;; the deftest's thesis, now provable where it matters.
    (is (= 1 (count (:reports suppressed)))
        "exactly one always-on :rf.error/frame-teardown-failed ships despite B no-emit")
    (is (= :epoch/on-frame-destroyed
           (:hook (first (:hook-failures (first (:reports suppressed))))))
        "the always-on report names the failing epoch teardown hook")
    (is (= 1 (count (:reports control)))
        "the control (B no-emit disabled) also ships exactly one report")
    (is (true? (:b-live? suppressed))
        "same-id B remains live after A's teardown hook fails")
    ;; VACUOUS PASS REMOVED (class 1): the buffer negative below passed over
    ;; an epoch capture buffer the gate never lets fill.
    (when interop/debug-enabled?
      (is (= 1 (count (:warnings suppressed)))
          "A's post-dissoc teardown-hook warning is delivered despite B no-emit")
      (is (= :epoch/on-frame-destroyed
             (get-in (first (:warnings suppressed)) [:tags :hook]))
          "the warning names the failing epoch teardown hook")
      (is (= 1 (count (:warnings control)))
          "the control (B no-emit disabled) also yields exactly one warning")
      (is (empty? (filter #(= :rf.warning/teardown-hook-exception (:operation %))
                          (:b-buffer suppressed)))
          "A's teardown warning never enters B's epoch capture buffer"))))

;; ---- rf2-vxgfnd.244 — predecessor terminal facts out of successor RING ------
;;
;; The extension #5850's epoch-only fence missed. #5850 made A's terminal facts
;; bypass B's epoch CAPTURE and B's no-emit POLICY (structural delivery), and
;; its fixtures assert B's epoch buffer / history — but NOT B's per-frame trace
;; RING. Under structural delivery A's terminal facts still carried A's inherited
;; dispatch-id and A's bare frame id, so `trace/tooling.cljc` pushed them into
;; the CURRENT ring for that id — B's, once B is installed. The fix makes
;; structural delivery RETENTIONLESS: global listeners get each terminal fact
;; exactly once, but no ring retains it. These fixtures assert the RING.

(deftest predecessor-terminal-facts-never-retained-in-successor-ring
  ;; rf2-vxgfnd.244 (red before fix). A is destroyed mid-drain and paused at its
  ;; POST-DISSOC epoch hook. Same-id B is then installed and SETTLES an ordinary
  ;; event, populating B's per-frame trace RING with a legitimate sentinel run.
  ;; When A resumes it publishes its terminal facts (:rf.epoch/snapshotted /
  ;; :rf.epoch/outcome, plus :rf.epoch.cb/silenced-on-frame-destroy for the
  ;; observer A accrued) under retentionless structural delivery: each carries
  ;; A's inherited dispatch-id and A's bare frame id (== B's id). B's public flat
  ;; trace buffer must stay byte-identical, no ring must be recreated for
  ;; destroyed A, and every A terminal fact must still reach the global live
  ;; trace listener exactly once.
  ;;
  ;; Before the fix `deliver-to-tooling!` pushed unconditionally, so A's
  ;; :halted-destroy snapshotted / :blocked outcome / silencing landed under a
  ;; new (A-dispatch-id) run slot in B's ring — B's flat buffer grew.
  (let [id             :destroy/ring-evidence-overlap
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        a-observer     ::ring-a-observer
        global-facts   (atom [])
        original-epoch (late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :destroy/ring-a-settle
      (fn [{:keys [db]} _] {:db (assoc db :a-settled true)}))
    (rf/reg-event :destroy/ring-a
      (fn [_ _] (frame/destroy-frame! id) {:db {:owner :a-tail}}))
    (rf/reg-event :destroy/ring-b-settle
      (fn [{:keys [db]} _] {:db (assoc db :owner :b)}))
    (rf/make-frame {:id id})
    ;; A accrues an observer by settling one ordinary event. Because same-id B
    ;; then SETTLES (claiming the id-keyed stores and re-arming this observer with
    ;; B's record), A's silencing fan for the observer is honestly SUPPRESSED
    ;; under rf2-vxgfnd.245 — the observer is live on B, so B silences it on B's
    ;; own destroy, not A. A's :rf.epoch/snapshotted / :rf.epoch/outcome trailers
    ;; still fire (they are A's unconditional terminal evidence) and pin the RING
    ;; boundary this #5865 fixture exists for.
    (rf/register-listener! :epoch a-observer (fn [_] nil))
    (rf/dispatch-sync [:destroy/ring-a-settle] {:frame id})
    ;; Global live trace listener — captures A's frame-tagged terminal facts.
    (rf/register-listener! :trace ::ring-global-facts
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (contains? #{:rf.epoch/snapshotted :rf.epoch/outcome
                                :rf.epoch.cb/silenced-on-frame-destroy}
                              (:operation ev)))
          (swap! global-facts conj ev))))
    (try
      (late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:destroy/ring-a] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A is paused at its post-dissoc epoch hook")
        ;; Same-id B: install and settle an ordinary event so B's RING holds a
        ;; legitimate sentinel run before A publishes its terminal facts.
        (rf/make-frame {:id id})
        (rf/dispatch-sync [:destroy/ring-b-settle] {:frame id})
        (let [token-b       (frame/frame-incarnation-token id)
              b-ring-before (rf/trace-buffer id {:flat true})
              b-bundles-before (rf/trace-buffer id)]
          ;; A resumes and publishes its terminal facts while B owns the id.
          (.countDown release-a)
          (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
              "A's terminal recipe completes")
          (executor-barrier!)

          ;; ALWAYS-ON (rf2-d2841): B stays the live incarnation and keeps its
          ;; own state through A's terminal fan-out. The RING boundary this
          ;; fixture exists for is a dev-trace fact — under the gate B has no
          ;; ring at all, so every ring assertion below (and the `(seq
          ;; b-ring-before)` precondition that licenses them) is guarded
          ;; TOGETHER. VACUOUS PASSES REMOVED (class 3 / class 1): the two
          ;; byte-identity comparisons and the three `empty?` leak audits were
          ;; all true for free over empty rings.
          (is (identical? token-b (frame/frame-incarnation-token id))
              "B remains the current incarnation")
          (is (= {:owner :b} (frame/frame-app-db-value id))
              "A's terminal fan-out never mutated B's state")

          (when interop/debug-enabled?
          (is (seq b-ring-before)
              "B's ring holds its own settle sentinel before A resumes")
          ;; THE RING BOUNDARY: B's public flat buffer is byte-identical.
          (is (= b-ring-before (rf/trace-buffer id {:flat true}))
              "B's flat trace ring is byte-identical after A's terminal fan-out")
          (is (= b-bundles-before (rf/trace-buffer id))
              "B's event-bundle ring view is unchanged too")
          ;; None of A's terminal operations reach B's ring.
          (is (empty? (filter #(contains? #{:rf.epoch/snapshotted :rf.epoch/outcome
                                            :rf.epoch.cb/silenced-on-frame-destroy}
                                          (:operation %))
                              (rf/trace-buffer id {:flat true})))
              "no A terminal operation is retained in B's ring")
          ;; A's inherited dispatch-id never appears as a run slot in B's ring.
          (let [a-dispatch-ids (into #{}
                                     (comp (map #(get-in % [:tags :rf.trace/dispatch-id]))
                                           (remove nil?))
                                     @global-facts)
                b-ring-dispatch-ids (into #{}
                                          (comp (map #(get-in % [:tags :rf.trace/dispatch-id]))
                                                (remove nil?))
                                          (rf/trace-buffer id {:flat true}))]
            (is (empty? (set/intersection a-dispatch-ids b-ring-dispatch-ids))
                "A's dispatch-id is never retained as a run slot in B's ring"))

          ;; GLOBAL DELIVERY still fires: each A terminal fact reaches the live
          ;; listener exactly once (scoped to A's terminal outcomes).
          (is (= 1 (count (filter #(and (= :rf.epoch/snapshotted (:operation %))
                                        (= :halted-destroy (get-in % [:tags :outcome])))
                                  @global-facts)))
              "A's :halted-destroy snapshotted reaches the global listener once")
          (is (= 1 (count (filter #(and (= :rf.epoch/outcome (:operation %))
                                        (= :blocked (get-in % [:tags :outcome])))
                                  @global-facts)))
              "A's :blocked outcome reaches the global listener once")
          ;; rf2-vxgfnd.245: A's silencing fan for its observer is SUPPRESSED —
          ;; same-id B claimed the id and re-armed the observer with B's record,
          ;; so the observer is live on B (B silences it on B's own destroy). A
          ;; publishing a bare silencing here would falsely claim the observer
          ;; went silent. A's other terminal facts (snapshotted / outcome) still
          ;; fire above and demonstrate the retentionless-vs-global boundary.
          (is (empty? (filter #(and (= :rf.epoch.cb/silenced-on-frame-destroy
                                        (:operation %))
                                    (= a-observer (get-in % [:tags :cb-id])))
                              @global-facts))
              "A's silencing for its observer is suppressed after B re-armed it (rf2-vxgfnd.245)")

          ;; B keeps settling normally, and its ring is live rather than frozen.
          (rf/dispatch-sync [:destroy/ring-b-settle] {:frame id})
          (is (< (count b-ring-before)
                 (count (rf/trace-buffer id {:flat true})))
              "B's own subsequent event does grow B's ring (ring is live, not frozen)"))))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch a-observer)
        (rf/unregister-listener! :trace ::ring-global-facts)
        (when (frame/frame id) (frame/destroy-frame! id))
        (late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

(deftest snapshot-hook-failure-leaves-no-residual-ring
  ;; rf2-vxgfnd.244 (red before fix). A throwing `:epoch/snapshot-frame-destroyed`
  ;; during a mid-run destroy emits `:rf.warning/teardown-hook-exception` under
  ;; ORDINARY (non-structural) delivery, carrying A's bare frame id. Ordering the
  ;; snapshot BEFORE A's ring release means that warning's ring push is cleared
  ;; by A's normal ring release — no residual per-frame ring survives for the
  ;; destroyed frame — while the required global diagnostic still fires.
  ;;
  ;; Before the fix the snapshot ran AFTER release-frame-ring!, so the warning
  ;; RECREATED A's already-released ring, leaving a residual ring keyed by the
  ;; destroyed id.
  (let [id           :destroy/ring-snapshot-fail
        warnings     (atom [])
        ;; ALWAYS-ON (rf2-d2841): the same failure ships a bounded corpus-wide
        ;; `:rf.error/frame-teardown-failed` record naming the hook.
        reports      (atom [])
        original-snap (late-bind/get-fn :epoch/snapshot-frame-destroyed)]
    (rf/reg-event :destroy/ring-snap-a
      (fn [_ _] (frame/destroy-frame! id) {:db {:owner :a-tail}}))
    (rf/make-frame {:id id})
    (rf/register-listener! :trace ::ring-snap-warn
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (= :rf.warning/teardown-hook-exception (:operation ev)))
          (swap! warnings conj ev))))
    (rf/register-listener! :errors ::ring-snap-warn
      (fn [r]
        (when (and (= :rf.error/frame-teardown-failed (:error r))
                   (= id (:frame r)))
          (swap! reports conj r))))
    (try
      ;; Fail the pre-dissoc snapshot hook for this mid-run destroy.
      (late-bind/set-fn! :epoch/snapshot-frame-destroyed
        (fn [& args]
          (if (= id (first args))
            (throw (ex-info "snapshot blew" {}))
            (when original-snap (apply original-snap args)))))
      (rf/dispatch-sync [:destroy/ring-snap-a] {:frame id})
      (executor-barrier!)
      ;; ALWAYS-ON (rf2-d2841): the required diagnostic and the teardown
      ;; outcome. The bounded report is what an off-box shipper receives, and
      ;; a failed snapshot hook must not abort the destroy.
      (is (= 1 (count @reports))
          "exactly one always-on :rf.error/frame-teardown-failed ships")
      (is (= :epoch/snapshot-frame-destroyed
             (:hook (first (:hook-failures (first @reports)))))
          "the always-on report names the failing snapshot hook")
      (is (nil? (frame/frame-incarnation-token id))
          "the frame is fully destroyed")
      ;; VACUOUS PASSES REMOVED (class 1): the two residual-ring negatives
      ;; below passed over rings the gate never lets exist for ANY frame,
      ;; destroyed or live.
      (when interop/debug-enabled?
        (is (= 1 (count @warnings))
            "the snapshot-failure teardown-hook warning reaches the global listener")
        (is (= :epoch/snapshot-frame-destroyed
               (get-in (first @warnings) [:tags :hook]))
            "the warning names the failing snapshot hook")
        ;; No residual ring survives for the destroyed frame.
        (is (empty? (rf/trace-buffer id {:flat true}))
            "no residual per-frame trace ring survives destroyed A")
        (is (empty? (rf/trace-buffer id))
            "no residual event-bundle ring survives destroyed A either"))
      (finally
        (rf/unregister-listener! :trace ::ring-snap-warn)
        (rf/unregister-listener! :errors ::ring-snap-warn)
        (when (frame/frame id) (frame/destroy-frame! id))
        (late-bind/set-fn! :epoch/snapshot-frame-destroyed original-snap)))))

(deftest snapshot-hook-failure-still-publishes-reports-and-spares-successor
  ;; rf2-vxgfnd.289 — the integrated full-destroy peer to the epoch-seam unit
  ;; `epoch-test/terminal-publish-noops-on-nil-bundle-no-fabrication` and the
  ;; ring-residue pin `snapshot-hook-failure-leaves-no-residual-ring` above. A
  ;; throwing PRE-dissoc `:epoch/snapshot-frame-destroyed` (step 8a) MUST NOT
  ;; abort teardown. The recipe walks straight on:
  ;;
  ;;   (1) the POST-dissoc publish hook (`:epoch/on-frame-destroyed`, step 11)
  ;;       STILL RUNS — the snapshot/publish split (rf2-vxgfnd.151/.246) threads
  ;;       the (now nil) bundle through to the publish regardless of outcome;
  ;;   (2) exactly ONE bounded always-on `:rf.error/frame-teardown-failed`
  ;;       report ships (EP-0008 R1 finally-flush) and its :hook-failures NAMES
  ;;       the failing `:epoch/snapshot-frame-destroyed` hook;
  ;;   (3) a same-id successor B installed in the post-dissoc window is left
  ;;       PRISTINE — the nil bundle makes the publish fabricate nothing, so it
  ;;       cannot silence the observing cb, and B is a FRESH incarnation, live
  ;;       after the destroy returns, not A's.
  ;;
  ;; An epoch cb OBSERVES A before the destroy, so a SUCCESSFUL snapshot WOULD
  ;; have owed it a terminal silence (per `terminal-publish-noops-on-nil-bundle-
  ;; no-fabrication`); the throwing snapshot must owe — and fire — none. A is
  ;; destroyed via a DIRECT `destroy-frame!` (not mid-drain) so the proven
  ;; make-frame-B-in-publish-window pattern (see
  ;; `run-terminal-teardown-hook-exception-scenario`) is safe.
  (let [id             :destroy/snap-fail-integrated
        cb             ::snap-fail-observer
        reports        (atom [])                       ; :rf.error/frame-teardown-failed
        silencings     (atom [])                       ; :rf.epoch.cb/silenced-on-frame-destroy
        publish-calls  (atom 0)
        a-token        (atom nil)
        b-token        (atom nil)
        original-snap  (late-bind/get-fn :epoch/snapshot-frame-destroyed)
        original-epoch (late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :snap-fail/seed (fn [_ _] {:db {:n 0}}))
    (rf/make-frame {:id id})
    ;; An epoch cb OBSERVES A: delivery of the settled :seed record stamps its
    ;; observation of the frame, so a live snapshot would owe it a silence.
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf/dispatch-sync [:snap-fail/seed] {:frame id})
    (reset! a-token (frame/frame-incarnation-token id))
    (rf/register-listener! :errors cb
      (fn [r] (when (= :rf.error/frame-teardown-failed (:error r))
                (swap! reports conj r))))
    (rf/register-listener! :trace cb
      (fn [ev] (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                 (swap! silencings conj ev))))
    (try
      ;; Fail the PRE-dissoc snapshot for this id (delegate every other id).
      (late-bind/set-fn! :epoch/snapshot-frame-destroyed
        (fn [& args]
          (if (= id (first args))
            (throw (ex-info "snapshot blew" {}))
            (when original-snap (apply original-snap args)))))
      ;; Spy the POST-dissoc publish: prove it ran, install same-id B inside its
      ;; window, then delegate to the real (nil-bundle → no-op) publish.
      (late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (= id (first args))
            (swap! publish-calls inc)
            (rf/make-frame {:id id})
            (reset! b-token (frame/frame-incarnation-token id)))
          (when original-epoch (apply original-epoch args))))
      (frame/destroy-frame! id)
      (executor-barrier!)

      ;; (1) The post-dissoc publish still ran despite the failed snapshot.
      (is (= 1 @publish-calls)
          "the post-dissoc :epoch/on-frame-destroyed publish (step 11) still
           fires after the pre-dissoc snapshot (step 8a) threw")

      ;; (2) Exactly one bounded always-on report, naming the snapshot hook.
      (is (= 1 (count @reports))
          "exactly one bounded :rf.error/frame-teardown-failed ships (finally-flush)")
      (let [r (first @reports)]
        (is (= id (:frame r)) "the report names the destroyed frame")
        (is (= 1 (count (:hook-failures r)))
            "one :hook-failures entry — only the snapshot hook failed")
        (is (= :epoch/snapshot-frame-destroyed (:hook (first (:hook-failures r))))
            "the report names the failing pre-dissoc snapshot hook")
        (is (= :ignored (:recovery r))
            ":recovery :ignored — teardown is best-effort"))

      ;; (3) The same-id successor B is pristine.
      (is (some? @b-token)
          "same-id B was installed in the post-dissoc window")
      (is (= @b-token (frame/frame-incarnation-token id))
          "B is the live incarnation after the destroy returns")
      (is (not= @a-token @b-token)
          "B is a FRESH incarnation, not A's token")
      (is (empty? @silencings)
          "the nil-bundle publish owes no silence — the observing cb is untouched")
      (finally
        ;; Restore the real hooks BEFORE destroying B so B's teardown runs clean.
        (late-bind/set-fn! :epoch/snapshot-frame-destroyed original-snap)
        (late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :errors cb)
        (rf/unregister-listener! :trace cb)
        (when (frame/frame id) (frame/destroy-frame! id))))))

;; ---- rf2-vxgfnd.245 — honest delayed epoch fan-out after same-id rearm -------
;;
;; #5850 snapshots predecessor A's `silenced-cbs` before dissoc and later
;; PUBLISHES that saved set UNCONDITIONALLY — even when a same-id B has claimed
;; the epoch stores and the same current listener generation has already received
;; B's newer record and been re-armed. That makes A emit a bare
;; :rf.epoch.cb/silenced-on-frame-destroy claiming a callback went silent when it
;; is in fact live on B (and B re-emits the identical unqualified signal on its
;; own later destroy). The fix gates A's silencing fan on A still winning its
;; compare-owned cleanup — equivalently, on no successor having re-armed the
;; id-keyed observation state. A's required halted record/trailers stay
;; unconditional (rf2-vxgfnd.151); only the silencing fan is gated.

(deftest predecessor-silencing-fan-suppressed-after-successor-rearm
  ;; rf2-vxgfnd.245 (red before fix). A settles (cb observes A + A claims the
  ;; id-keyed stores), A self-destroys mid-drain and pauses at its POST-DISSOC
  ;; epoch hook (silenced-cbs — including cb — already snapshotted before dissoc).
  ;; Same-id B is created and SETTLES an ordinary event: B claims the stores and
  ;; the unchanged cb generation receives B's :ok record, re-arming it for the
  ;; reused id. When A resumes, its silencing fan for cb must be SUPPRESSED (B
  ;; owns the id; cb is live on B). The listener keeps receiving B's records, and
  ;; when B later destroys, exactly ONE truthful silencing for cb fires.
  ;;
  ;; Before the fix A published its stale snapshot unconditionally: a bare A
  ;; silencing for cb fired even though cb is live on B (and B's later destroy
  ;; fired a second identical one).
  (let [id             :vxgfnd245/rearm
        cb             ::vxgfnd245-cb
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        received       (atom [])
        silencings     (atom [])
        original-epoch (late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :vxgfnd245/a-settle
      (fn [{:keys [db]} _] {:db (assoc db :a true)}))
    (rf/reg-event :vxgfnd245/a-destroy
      (fn [_ _] (frame/destroy-frame! id) {:db {:owner :a-tail}}))
    (rf/reg-event :vxgfnd245/b-settle
      (fn [{:keys [db]} _] {:db (assoc db :owner :b)}))
    (rf/make-frame {:id id})
    ;; cb observes A by settling one ordinary event; A claims the id-keyed stores.
    (rf/register-listener! :epoch cb (fn [r] (swap! received conj r)))
    (rf/dispatch-sync [:vxgfnd245/a-settle] {:frame id})
    (rf/register-listener! :trace ::vxgfnd245-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    (try
      (late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:vxgfnd245/a-destroy] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A is paused at its post-dissoc epoch hook (silenced-cbs already
             snapshotted incl cb)")
        ;; Same-id B settles: claims stores + re-arms cb for the reused id.
        (rf/make-frame {:id id})
        (rf/dispatch-sync [:vxgfnd245/b-settle] {:frame id})
        ;; The epoch cb never fires under the gate (the record it receives is
        ;; built from the trace-fed capture buffer), so the receive counts,
        ;; the silencing fan and the precondition that licenses them are
        ;; guarded TOGETHER. VACUOUS PASS REMOVED (class 1): the silencing
        ;; negative passed over a stream carrying no silencings at all.
        (when interop/debug-enabled?
          (is (= 2 (count @received))
              "cb received A's settle and B's settle — re-armed for the reused id"))
        ;; A resumes and reaches its terminal publish while B owns the stores.
        (.countDown release-a)
        (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
            "A's terminal recipe completes")
        (executor-barrier!)
        ;; ALWAYS-ON (rf2-d2841): the incarnation fence. A's inert returned
        ;; tail (`{:owner :a-tail}`) must never become B's state, and B must
        ;; go on committing its own events.
        (is (= {:owner :b} (frame/frame-app-db-value id))
            "A's inert tail never mutated B's state")
        (when interop/debug-enabled?
          ;; THE FIX: A's silencing fan for cb is suppressed — cb is live on B.
          (is (empty? (filter #(= cb (:cb-id (:tags %))) @silencings))
              "no bare A silencing for cb after B claimed the id and re-armed it")
          ;; A's HISTORICAL halted-destroy record is still delivered to cb — the
          ;; .151 record-delivery contract is unchanged by .245 (only the silencing
          ;; fan is gated). cb remains live on B: an extra B settle reaches it.
          (let [before-extra (count @received)]
            (rf/dispatch-sync [:vxgfnd245/b-settle] {:frame id})
            (is (= (inc before-extra) (count @received))
                "cb continues to receive B's records after A resumed — it is live on B")))
        ;; When B (where cb IS live) destroys, exactly one truthful silencing.
        (rf/destroy-frame! id)
        (is (nil? (frame/frame-incarnation-token id))
            "B's own destroy takes effect")
        (when interop/debug-enabled?
          (is (= 1 (count (filter #(= cb (:cb-id (:tags %))) @silencings)))
              "exactly one truthful silencing for cb — fired by B's own destroy")))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace ::vxgfnd245-silencing)
        (when (frame/frame id) (frame/destroy-frame! id))
        (late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

(deftest predecessor-silencing-honours-new-generation-registered-in-gap
  ;; rf2-vxgfnd.245 (red before fix). The re-register-in-the-gap case: cb observes
  ;; A, A self-destroys mid-drain and pauses; during the pause cb is REPLACED by a
  ;; NEW generation and same-id B settles (the new cb generation receives B's
  ;; record and is re-armed). A's stale snapshot silencing must not fire against
  ;; the reused id: cb is live (new generation) on B. B's later destroy emits
  ;; exactly one truthful silencing for the new generation.
  (let [id             :vxgfnd245/regen
        cb             ::vxgfnd245-regen-cb
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        old-received   (atom 0)
        new-received   (atom 0)
        silencings     (atom [])
        original-epoch (late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :vxgfnd245/regen-a-settle
      (fn [{:keys [db]} _] {:db (assoc db :a true)}))
    (rf/reg-event :vxgfnd245/regen-a-destroy
      (fn [_ _] (frame/destroy-frame! id) {:db {:owner :a-tail}}))
    (rf/reg-event :vxgfnd245/regen-b-settle
      (fn [{:keys [db]} _] {:db (assoc db :owner :b)}))
    (rf/make-frame {:id id})
    (rf/register-listener! :epoch cb (fn [_] (swap! old-received inc)))
    (rf/dispatch-sync [:vxgfnd245/regen-a-settle] {:frame id})
    (rf/register-listener! :trace ::vxgfnd245-regen-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    (try
      (late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:vxgfnd245/regen-a-destroy] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A paused post-dissoc")
        ;; Replace cb with a NEW generation in the gap, then B settles.
        (rf/register-listener! :epoch cb (fn [_] (swap! new-received inc)))
        (rf/make-frame {:id id})
        (rf/dispatch-sync [:vxgfnd245/regen-b-settle] {:frame id})
        (.countDown release-a)
        (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
            "A's terminal recipe completes")
        (executor-barrier!)
        ;; ALWAYS-ON (rf2-d2841): the incarnation fence survives the
        ;; re-register-in-the-gap race.
        (is (= {:owner :b} (frame/frame-app-db-value id))
            "A's inert tail never mutated B's state across the gap re-register")
        ;; The cb generations and the silencing fan are epoch-record-driven
        ;; and so dev-only. VACUOUS PASS REMOVED (class 1): the silencing
        ;; negative passed over an empty stream.
        (when interop/debug-enabled?
          (is (= 1 @new-received)
              "the NEW cb generation received B's settled record")
          (is (empty? (filter #(= cb (:cb-id (:tags %))) @silencings))
              "A's stale snapshot never silences the reused id after a gap re-register")
          ;; A delivers its HISTORICAL halted record to its OWN snapshot generation
          ;; (the old callback captured pre-dissoc), NOT the new one. So the old
          ;; callback saw A-settle + A's halted record; the new generation was
          ;; invoked only by B — A never invokes the new generation (rf2-vxgfnd.245
          ;; pins old/new behaviour).
          (is (= 2 @old-received)
              "the old cb generation received A's settle + A's historical halted record")
          (is (= 1 @new-received)
              "A never invoked the new cb generation — only B's settle reached it"))
        ;; B destroys → exactly one truthful silencing for the live new generation.
        (rf/destroy-frame! id)
        (is (nil? (frame/frame-incarnation-token id))
            "B's own destroy takes effect")
        (when interop/debug-enabled?
          (is (= 1 (count (filter #(= cb (:cb-id (:tags %))) @silencings)))
              "exactly one truthful silencing for the new generation on B's destroy")))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace ::vxgfnd245-regen-silencing)
        (when (frame/frame id) (frame/destroy-frame! id))
        (late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

;; ---- rf2-vf2qke — structural scope must not taint listener-triggered work ----
;;
;; #5865 binds the structural-delivery flags (epoch-capture / frame-policy /
;; ring-retention all false) around the WHOLE synchronous outer emit, and the
;; public tooling listener fan-out runs INSIDE that scope. A listener reacting to
;; A's real structural terminal fact that dispatches its OWN legitimate work into
;; unrelated frame C (or a same-id successor B) had that nested cascade INHERIT
;; A's retentionless/no-capture/no-policy scope: it streamed live but bypassed
;; C's epoch capture, C's own ring retention, and C's frame-no-emit policy. The
;; fix restores ordinary delivery defaults around the fan-out while the outer
;; ring push keeps the captured structural retention decision. Merged fixtures
;; used PASSIVE atom listeners and missed this; these use DISPATCHING listeners.

(deftest listener-dispatch-during-structural-terminal-fact-runs-under-normal-scope
  ;; rf2-vf2qke (red before fix). Idle frame A (observed by an epoch listener) is
  ;; destroyed at TOP LEVEL — emitting its terminal
  ;; :rf.epoch.cb/silenced-on-frame-destroy fact under retentionless structural
  ;; delivery, with no drain on the stack so the fan-out is live (continuing?
  ;; true). A public trace listener reacts to that fact by dispatching a
  ;; legitimate ordinary event into UNRELATED frame C. Before the fix C's cascade
  ;; ran under A's inherited structural scope: C's epoch capture was disabled (no
  ;; record) and C's ring retention was off (empty ring), even though C's db
  ;; still committed and its events streamed live. After the fix C's nested work
  ;; is captured and retained normally.
  ;;
  ;; Mutation tooth: NOT restoring ordinary defaults around the fan-out (leaving
  ;; the ambient structural bindings in force for the callbacks) empties C's epoch
  ;; history and trace ring and fails this fixture.
  (let [a-id   :vf2qke/terminal-a
        c-id   :vf2qke/nested-c
        fired? (atom false)
        c-live (atom [])]
    (rf/make-frame {:id c-id})
    (rf/reg-event :vf2qke/c-event
      (fn [{:keys [db]} _] {:db (assoc db :c :ran)}))
    (rf/make-frame {:id a-id})
    (rf/reg-event :vf2qke/seed (fn [_ _] {:db {:n 0}}))
    ;; An epoch observer so A's destroy fans a real structural terminal fact.
    (rf/register-listener! :epoch ::vf2qke-a-obs (fn [_] nil))
    (rf/dispatch-sync [:vf2qke/seed] {:frame a-id})
    ;; The dispatching public listener: on A's structural terminal fact, run
    ;; ordinary nested work into unrelated C exactly once.
    (rf/register-listener! :trace ::vf2qke-dispatcher
      (fn [ev]
        (when (and (= a-id (get-in ev [:tags :frame]))
                   (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                   (compare-and-set! fired? false true))
          (rf/dispatch-sync [:vf2qke/c-event] {:frame c-id}))))
    ;; A raw trace listener capturing C's own run events (proves live delivery).
    (rf/register-listener! :trace ::vf2qke-c-live
      (fn [ev]
        (when (= c-id (get-in ev [:tags :frame]))
          (swap! c-live conj ev))))
    (try
      (rf/destroy-frame! a-id)
      (executor-barrier!)
      ;; DEV-ONLY END TO END (rf2-d2841). The DRIVER here is a public TRACE
      ;; listener reacting to `:rf.epoch.cb/silenced-on-frame-destroy`. No
      ;; such fact is emitted under the gate, so the listener never fires and
      ;; C's nested cascade never runs — there is nothing to observe, not
      ;; merely no way to observe it. The whole body is therefore guarded
      ;; rather than half-asserted; `(true? @fired?)` is the precondition and
      ;; stays with the claims it licenses.
      (when interop/debug-enabled?
        (is (true? @fired?)
            "the listener saw A's structural terminal fact and dispatched")
        ;; UNRELATED C: nested work captured into C's epoch history + retained in
        ;; C's ring, and it actually ran and streamed live.
        (is (= 1 (count (rf/epoch-history c-id)))
            "C's listener-triggered cascade is captured into C's epoch history")
        (is (= {:c :ran} (frame/frame-app-db-value c-id))
            "C's nested event actually ran and committed")
        (is (seq (rf/trace-buffer c-id {:flat true}))
            "C's listener-triggered cascade is retained in C's per-frame ring")
        (is (seq @c-live) "C's nested events streamed live to listeners"))
      (finally
        (rf/unregister-listener! :epoch ::vf2qke-a-obs)
        (rf/unregister-listener! :trace ::vf2qke-dispatcher)
        (rf/unregister-listener! :trace ::vf2qke-c-live)
        (when (frame/frame a-id) (frame/destroy-frame! a-id))
        (when (frame/frame c-id) (frame/destroy-frame! c-id))))))

(deftest listener-triggered-work-obeys-nested-frame-no-emit-policy
  ;; rf2-vf2qke (red before fix). A listener reacting to A's structural terminal
  ;; fact dispatches into a nested frame D that declared its OWN
  ;; :rf.trace/frame-no-emit? policy. That policy must apply to D's nested work:
  ;; before the fix the ambient structural scope disabled frame-policy for the
  ;; callback, so D's events leaked to listeners despite D's no-emit policy.
  (let [a-id   :vf2qke/policy-a
        d-id   :vf2qke/nested-noemit-d
        fired? (atom false)
        d-live (atom [])]
    (rf/make-frame {:id d-id :rf.trace/frame-no-emit? true})
    (rf/reg-event :vf2qke/d-event
      (fn [{:keys [db]} _] {:db (assoc db :d :ran)}))
    (rf/make-frame {:id a-id})
    (rf/reg-event :vf2qke/policy-seed (fn [_ _] {:db {:n 0}}))
    (rf/register-listener! :epoch ::vf2qke-policy-obs (fn [_] nil))
    (rf/dispatch-sync [:vf2qke/policy-seed] {:frame a-id})
    (rf/register-listener! :trace ::vf2qke-policy-dispatcher
      (fn [ev]
        (when (and (= a-id (get-in ev [:tags :frame]))
                   (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                   (compare-and-set! fired? false true))
          (rf/dispatch-sync [:vf2qke/d-event] {:frame d-id}))))
    (rf/register-listener! :trace ::vf2qke-d-live
      (fn [ev]
        (when (= d-id (get-in ev [:tags :frame]))
          (swap! d-live conj ev))))
    (try
      (rf/destroy-frame! a-id)
      (executor-barrier!)
      ;; DEV-ONLY END TO END (rf2-d2841), same reason as the deftest above:
      ;; a TRACE listener is the driver. TWO VACUOUS PASSES REMOVED (class 1)
      ;; — `(empty? @d-live)` and `(empty? (rf/trace-buffer d-id {:flat true}))`
      ;; certified that D's no-emit policy suppressed D's traces, over a frame
      ;; whose event NEVER RAN. A no-leak audit that passes because nothing
      ;; happened is the sharpest form of the false green this pass hunts.
      (when interop/debug-enabled?
        (is (true? @fired?) "the listener dispatched into the no-emit frame D")
        (is (= {:d :ran} (frame/frame-app-db-value d-id))
            "D's nested event ran and committed (no-emit suppresses TRACE, not work)")
        ;; D's own no-emit policy applies to the nested work: no D traces leak.
        (is (empty? @d-live)
            "D's frame-no-emit policy suppresses its nested trace — no leak to listeners")
        (is (empty? (rf/trace-buffer d-id {:flat true}))
            "no D trace is retained under D's own no-emit policy"))
      (finally
        (rf/unregister-listener! :epoch ::vf2qke-policy-obs)
        (rf/unregister-listener! :trace ::vf2qke-policy-dispatcher)
        (rf/unregister-listener! :trace ::vf2qke-d-live)
        (when (frame/frame a-id) (frame/destroy-frame! a-id))
        (when (frame/frame d-id) (frame/destroy-frame! d-id))))))

(deftest owner-loss-unwinds-only-entered-authored-afters
  ;; Mutation tooth: removing execute-chain's continuation predicate would run
  ;; :never/before and the handler after :killer/before destroys A. Removing
  ;; the entered-stack unwind would lose the two cleanup assertions.
  (let [id           :destroy/authored-unwind
        outer-before (atom 0)
        outer-after  (atom 0)
        killer-after (atom 0)
        never-before (atom 0)
        handler-runs (atom 0)]
    (rf/make-frame {:id id})
    (rf/reg-interceptor :destroy/outer
      {:before #(do (swap! outer-before inc) %)
       :after  #(do (swap! outer-after inc) %)})
    (rf/reg-interceptor :destroy/killer
      {:before #(do (frame/destroy-frame! id) %)
       :after  #(do (swap! killer-after inc) %)})
    (rf/reg-interceptor :destroy/never
      {:before #(do (swap! never-before inc) %)})
    (rf/reg-event :destroy/unwind
      {:interceptors [:destroy/outer :destroy/killer :destroy/never]}
      (fn [_ _] (swap! handler-runs inc) {:db {:forbidden true}}))
    (rf/dispatch-sync [:destroy/unwind] {:frame id})
    (is (= 1 @outer-before) "the already-entered outer before ran")
    (is (= 1 @killer-after) "the destroying interceptor's entered after unwound")
    (is (= 1 @outer-after) "the earlier authored after unwound")
    (is (zero? @never-before) "no later before entered after exact-owner loss")
    (is (zero? @handler-runs) "the handler body was never entered")))

(deftest destroy-then-throw-is-inert-and-skips-normal-settlement
  ;; Mutation tooth: reordering interceptor-error ahead of exact-owner loss
  ;; leaks the authored throw; removing the settle fence invokes the normal
  ;; epoch hook after the terminal destroy hook already owned A's snapshot.
  (let [id              :destroy/throw-before
        after-runs      (atom 0)
        handler-runs    (atom 0)
        normal-settles  (atom 0)
        original-settle (late-bind/get-fn :epoch/settle!)]
    (rf/make-frame {:id id})
    (rf/reg-interceptor :destroy/throwing
      {:before (fn [_]
                 (frame/destroy-frame! id)
                 (throw (ex-info "obsolete A failure" {})))
       :after  #(do (swap! after-runs inc) %)})
    (rf/reg-event :destroy/throwing-event
      {:interceptors [:destroy/throwing]}
      (fn [_ _] (swap! handler-runs inc) {}))
    (try
      (late-bind/set-fn!
        :epoch/settle!
        (fn [& args]
          (swap! normal-settles inc)
          (when original-settle (apply original-settle args))))
      (is (nil? (rf/dispatch-sync [:destroy/throwing-event] {:frame id}))
          "destroy+throw does not escape the obsolete continuation")
      (finally
        (late-bind/set-fn! :epoch/settle! original-settle)))
    (is (= 1 @after-runs) "the entered authored after still unwound")
    (is (zero? @handler-runs) "later authored work did not enter")
    (is (zero? @normal-settles) "no normal epoch settlement followed owner loss")))

(deftest first-fx-owner-loss-fences-later-fx-and-resolution-throws
  ;; Mutation teeth: removing the per-entry do-fx owner check runs :second;
  ;; removing the registrar callback wrapper lets the second dispatch escape.
  (let [id          :destroy/fx-tail
        second-runs (atom 0)
        original    registrar/lookup]
    (rf/make-frame {:id id})
    (rf/reg-fx :destroy/first
      (fn [_ _] (frame/destroy-frame! id)))
    (rf/reg-fx :destroy/second
      (fn [_ _] (swap! second-runs inc)))
    (rf/reg-event :destroy/fx-event
      (fn [_ _] {:fx [[:destroy/first nil] [:destroy/second nil]]}))
    (rf/dispatch-sync [:destroy/fx-event] {:frame id})
    (is (zero? @second-runs) "later fx entries are framework-owned stale tail")

    (rf/make-frame {:id id})
    (rf/reg-event :destroy/fx-resolver-event
      (fn [_ _] {:fx [[:destroy/second nil]]}))
    (with-redefs [registrar/lookup
                  (fn [kind key]
                    (if (and (= kind :fx) (= key :destroy/second))
                      (do (frame/destroy-frame! id)
                          (throw (ex-info "resolver lost A" {})))
                      (original kind key)))]
      (is (nil? (rf/dispatch-sync [:destroy/fx-resolver-event] {:frame id}))
          "fx resolver destroy+throw is inert"))
    (is (zero? @second-runs) "resolver loss never invokes the fx body")))

(deftest adapter-read-destroy-throw-stops-before-handler
  ;; Mutation tooth: removing the run-one-pass callback fence leaks this throw
  ;; and/or invokes the handler after the pre-event snapshot read lost A.
  (let [id           :destroy/adapter-read
        armed?       (atom true)
        handler-runs (atom 0)
        original     adapter/read-container]
    (rf/make-frame {:id id})
    (rf/reg-event :destroy/adapter-read-event
      (fn [_ _] (swap! handler-runs inc) {:db {:forbidden true}}))
    (with-redefs [adapter/read-container
                  (fn [container]
                    (if (compare-and-set! armed? true false)
                      (do (frame/destroy-frame! id)
                          (throw (ex-info "read lost A" {})))
                      (original container)))]
      (is (nil? (rf/dispatch-sync [:destroy/adapter-read-event] {:frame id}))
          "adapter read destroy+throw is inert"))
    (is (zero? @handler-runs) "no handler starts after snapshot owner loss")))

(deftest adapter-replace-watch-loss-keeps-only-the-linearized-a-write
  ;; Mutation teeth: the adapter callback performs the physical A install and
  ;; synchronously destroys A. The exact post-callback check must suppress the
  ;; id-keyed commit epoch, change trailers, fx, and normal epoch settlement;
  ;; none may be redirected into B while the obsolete callback is paused.
  (let [id              :destroy/adapter-replace-watch
        replaced-a      (CountDownLatch. 1)
        release-a       (CountDownLatch. 1)
        armed?          (atom true)
        physical-a      (atom nil)
        fx-runs         (atom 0)
        normal-settles  (atom 0)
        traces          (atom [])
        base-read       (:read-container plain-atom/adapter)
        base-replace    (:replace-container! plain-atom/adapter)
        original-settle (late-bind/get-fn :epoch/settle!)
        watching-adapter
        (assoc plain-atom/adapter
               :kind :custom
               :replace-container!
               (fn [container value]
                 (base-replace container value)
                 (when (compare-and-set! armed? true false)
                   (reset! physical-a (base-read container))
                   (frame/destroy-frame! id)
                   (.countDown replaced-a)
                   (.await release-a 10 TimeUnit/SECONDS))))]
    (adapter/dispose-adapter!)
    (reset! frame/frames {})
    (adapter/install-adapter! watching-adapter)
    (rf/make-frame {:id id})
    (rf/reg-fx :destroy/adapter-tail-fx (fn [_ _] (swap! fx-runs inc)))
    (rf/reg-event :destroy/adapter-replace-event
      (fn [_ _]
        {:db {:owner :a-physical}
         :fx [[:destroy/adapter-tail-fx nil]]}))
    (rf/register-listener! :trace ::adapter-replace-watch
      (fn [ev]
        (when (and (= id (get-in ev [:tags :frame]))
                   (contains? #{:rf.event/db-changed
                                :rf.event/frame-state-changed
                                :rf.event/run-end}
                              (:operation ev)))
          (swap! traces conj ev))))
    (try
      (late-bind/set-fn!
        :epoch/settle!
        (fn [& args]
          (swap! normal-settles inc)
          (when original-settle (apply original-settle args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:destroy/adapter-replace-event]
                                           {:frame id}))]
        (is (.await replaced-a 10 TimeUnit/SECONDS)
            "the adapter installed A's value and its synchronous watch destroyed A")
        (rf/make-frame {:id id})
        (let [token-b (frame/frame-incarnation-token id)]
          (.countDown release-a)
          (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
              "the obsolete adapter callback returned")
          (is (= {:rf.db/app {:owner :a-physical}
                  :rf.db/runtime {}}
                 @physical-a)
              "the already-linearized physical A container install stands")
          (is (identical? token-b (frame/frame-incarnation-token id))
              "B remains the installed incarnation")
          (is (= {} (frame/frame-app-db-value id)) "B's app-db is untouched")
          (is (zero? (frame/frame-commit-epoch id))
              "the stale A callback cannot bump B's id-keyed commit epoch")
          (is (zero? @fx-runs) "the returned fx tail is inert")
          (is (zero? @normal-settles) "normal epoch settlement is inert")
          (is (empty? @traces) "no db-change or run-end trailer follows owner loss")))
      (finally
        (.countDown release-a)
        (late-bind/set-fn! :epoch/settle! original-settle)
        (rf/unregister-listener! :trace ::adapter-replace-watch)
        (when (frame/frame id) (frame/destroy-frame! id))
        (reset! frame/frames {})
        (adapter/dispose-adapter!)
        (adapter/install-adapter! plain-atom/adapter)))))

(deftest cofx-supplier-loss-stops-diagnostics-later-resolution-and-handler
  ;; Mutation teeth: the first supplier destroys A and throws. The throw is
  ;; inert, no coeffect-exception diagnostic is emitted against absence/B, the
  ;; second supplier is never resolved, and the handler never starts.
  (let [id           :destroy/cofx-tail
        later-runs   (atom 0)
        handler-runs (atom 0)
        diagnostics  (atom [])]
    (rf/make-frame {:id id})
    (rf/reg-cofx :destroy/cofx-killer
      (fn []
        (frame/destroy-frame! id)
        (throw (ex-info "cofx supplier lost A" {}))))
    (rf/reg-cofx :destroy/cofx-later (fn [] (swap! later-runs inc)))
    (rf/reg-event :destroy/cofx-event
      {:rf.cofx/requires [:destroy/cofx-killer :destroy/cofx-later]}
      (fn [_ _] (swap! handler-runs inc) {:db {:forbidden true}}))
    (rf/register-listener! :trace ::cofx-loss
      (fn [ev]
        (when (= :rf.error/coeffect-exception (:operation ev))
          (swap! diagnostics conj ev))))
    (try
      (is (nil? (rf/dispatch-sync [:destroy/cofx-event] {:frame id}))
          "destroy+throw from a cofx supplier is inert")
      (finally
        (rf/unregister-listener! :trace ::cofx-loss)))
    (is (zero? @later-runs) "later declared cofx are not resolved")
    (is (zero? @handler-runs) "the handler never starts")
    (is (empty? @diagnostics) "no ordinary failure diagnostic follows owner loss")))

(deftest epoch-digest-owner-loss-cannot-publish-into-successor
  ;; Mutation teeth: the normal settle digest callback destroys A, pauses after
  ;; A's private teardown completes, then throws after B is installed. No
  ;; normal record, last-settled anchor, cascade aggregation, or B-era listener
  ;; notification may follow.
  (let [id               :destroy/epoch-digest
        digest-lost-a    (CountDownLatch. 1)
        release-digest   (CountDownLatch. 1)
        b-listener-runs  (atom 0)
        cascade-captures (atom 0)
        original-digest  (late-bind/get-fn :schemas/app-schemas-digest)
        original-capture (late-bind/get-fn :trace.cascade/capture-for-epoch!)]
    (rf/make-frame {:id id})
    (rf/reg-event :destroy/epoch-digest-event
      (fn [_ _] {:db {:owner :a-committed}}))
    (try
      (late-bind/set-fn!
        :schemas/app-schemas-digest
        (fn [frame-id]
          (if (= id frame-id)
            (do
              (frame/destroy-frame! id)
              (.countDown digest-lost-a)
              (.await release-digest 10 TimeUnit/SECONDS)
              (throw (ex-info "digest lost A" {})))
            (when original-digest (original-digest frame-id)))))
      (late-bind/set-fn!
        :trace.cascade/capture-for-epoch!
        (fn [& args]
          (swap! cascade-captures inc)
          (when original-capture (apply original-capture args))))
      ;; DEV-ONLY END TO END (rf2-d2841), and this one was CHECKED BY RUNNING
      ;; rather than reasoned about. The scenario is driven from inside the
      ;; `:schemas/app-schemas-digest` late-bind hook, which is reached only
      ;; from `epoch/settle!` via `assembly/current-schema-digest`. Under the
      ;; gate the trace-fed capture buffer never fills, `settle!` never runs,
      ;; the hook is never invoked, and the `digest-lost-a` latch TIMED OUT —
      ;; A was never destroyed, so the deftest's four negatives were passing
      ;; over a scenario that had not happened. FOUR VACUOUS PASSES REMOVED
      ;; (class 1): `(empty? (rf/epoch-history id))`,
      ;; `(nil? (epoch-state/last-settled-epoch-id id))`,
      ;; `(zero? @cascade-captures)` and `(zero? @b-listener-runs)` are each
      ;; true for free when nothing recorded, aggregated or fanned at all.
      (when interop/debug-enabled?
        (let [dispatch-a (future
                           (rf/dispatch-sync [:destroy/epoch-digest-event]
                                             {:frame id}))]
          (is (.await digest-lost-a 10 TimeUnit/SECONDS)
              "the digest callback destroyed A before normal publication")
          (rf/make-frame {:id id})
          (rf/register-listener! :epoch ::epoch-digest-b
            (fn [_] (swap! b-listener-runs inc)))
          (let [token-b (frame/frame-incarnation-token id)]
            (.countDown release-digest)
            (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
                "the obsolete digest throw is inert")
            (is (identical? token-b (frame/frame-incarnation-token id))
                "B remains installed")
            (is (= {} (frame/frame-app-db-value id)) "B app-db is untouched")
            (is (empty? (rf/epoch-history id)) "no normal A record lands in B history")
            (is (nil? (epoch-state/last-settled-epoch-id id))
                "no normal A anchor lands in B")
            (is (zero? @cascade-captures) "no stale cascade aggregation runs")
            (is (zero? @b-listener-runs) "no B-era epoch listener sees A's record"))))
      (finally
        (.countDown release-digest)
        (rf/unregister-listener! :epoch ::epoch-digest-b)
        (late-bind/set-fn! :schemas/app-schemas-digest original-digest)
        (late-bind/set-fn! :trace.cascade/capture-for-epoch! original-capture)))))

(deftest stale-teardown-report-is-corpus-only-after-successor-install
  ;; Mutation tooth: the bounded report flushes after A's registry dissoc. Its
  ;; corpus-wide fact remains required, but a bare-id frame route at that seam
  ;; would resolve B and feed A's failure into B's declared sinks.
  (let [id             :destroy/teardown-report-overlap
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        b-claimed      (CountDownLatch. 1)
        release-b      (CountDownLatch. 1)
        b-installed?   (atom false)
        first-epoch?   (atom true)
        corpus         (atom [])
        traces         (atom [])
        frame-routes   (atom 0)
        original-ui    (late-bind/get-fn :ui/on-frame-destroyed!)
        original-epoch (late-bind/get-fn :epoch/on-frame-destroyed)
        original-route (late-bind/get-fn :observability/route-error-record)]
    (rf/make-frame {:id id})
    (rf/reg-event :destroy/teardown-overlap-a
      (fn [_ _]
        (frame/destroy-frame! id)
        {}))
    (rf/register-listener! :errors ::teardown-overlap-corpus
      (fn [record]
        (when (= :rf.error/frame-teardown-failed (:error record))
          (swap! corpus conj record))))
    (rf/register-listener! :trace ::teardown-overlap-traces
      (fn [event]
        (when (and (= id (get-in event [:tags :frame]))
                   (contains? #{:rf.epoch/snapshotted :rf.epoch/outcome}
                              (:operation event)))
          (swap! traces conj event))))
    (try
      (late-bind/set-fn!
        :ui/on-frame-destroyed!
        (fn [frame-id]
          (when original-ui (original-ui frame-id))
          (when (= id frame-id)
            (if @b-installed?
              (do
                (.countDown b-claimed)
                (.await release-b 10 TimeUnit/SECONDS))
              (throw (ex-info "forced A teardown hook failure" {}))))))
      (late-bind/set-fn!
        :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (late-bind/set-fn!
        :observability/route-error-record
        (fn [record]
          (when (= :rf.error/frame-teardown-failed (:error record))
            (swap! frame-routes inc))
          (when original-route (original-route record))))
      (let [destroy-a (future
                        (rf/dispatch-sync [:destroy/teardown-overlap-a]
                                          {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A paused after dissoc and before its terminal report flush")
        (rf/make-frame {:id id
                        :rf.trace/frame-no-emit? true
                        :observability {:errors [{:sink ::replacement-b}]}})
        (reset! b-installed? true)
        (let [token-b  (frame/frame-incarnation-token id)
              destroy-b (future (frame/destroy-frame! id))]
          (is (.await b-claimed 10 TimeUnit/SECONDS)
              "B replaced the bare-id marker with its own destroy claim")
          (.countDown release-a)
          (is (not= ::timeout (deref destroy-a 5000 ::timeout))
              "A teardown completed")
          (is (identical? token-b (frame/frame-incarnation-token id))
              "B remains installed")
          (is (= {} (frame/frame-app-db-value id)) "B state is untouched")
          (is (= 1 (count @corpus))
              "the required A teardown report reaches corpus listeners once")
          (is (zero? @frame-routes)
              "A's report never resolves through B's frame-owned error route")
          ;; The corpus assertions above are the always-on half and were
          ;; already posture-independent — this deftest's central claim (the
          ;; required report ships corpus-wide but never routes into B's
          ;; declared sinks) runs under the gate unchanged. VACUOUS PASSES
          ;; REMOVED (class 1): the two `empty?` leak audits below passed over
          ;; an epoch history and a capture buffer the gate never lets fill.
          (when interop/debug-enabled?
            (let [by-op (group-by :operation @traces)]
              (is (= [:halted-destroy]
                     (mapv #(get-in % [:tags :outcome])
                           (get by-op :rf.epoch/snapshotted)))
                  "B's claim cannot revoke A's detailed terminal fact")
              (is (= [:blocked]
                     (mapv #(get-in % [:tags :outcome])
                           (get by-op :rf.epoch/outcome)))
                  "B's claim cannot revoke A's terminal consumer outcome"))
            (is (empty? (rf/epoch-history id))
                "A's terminal record never enters B's ring")
            (is (empty? (epoch-state/buffer-for id))
                "A's terminal traces never enter B's capture buffer"))
          (let [duplicate-b (future (frame/destroy-frame! id token-b))]
            (is (nil? (deref duplicate-b 2000 ::timeout))
                "A's finally preserved B's distinct claim marker"))
          (.countDown release-b)
          (is (not= ::timeout (deref destroy-b 5000 ::timeout))
              "B's independently-owned teardown completes")))
      (finally
        (.countDown release-a)
        (.countDown release-b)
        (rf/unregister-listener! :errors ::teardown-overlap-corpus)
        (rf/unregister-listener! :trace ::teardown-overlap-traces)
        (late-bind/set-fn! :ui/on-frame-destroyed! original-ui)
        (late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)
        (late-bind/set-fn! :observability/route-error-record original-route)))))

(deftest ambient-frame-scope-cannot-replace-the-dequeued-event-owner
  ;; Mutation tooth: dispatch origin authority is the exact dequeue owner A,
  ;; not the ambient `with-frame` target. The same B dispatch is accepted while
  ;; A is live and becomes inert after A destroys itself.
  (let [a-id :destroy/ambient-owner-a
        b-id :destroy/ambient-owner-b]
    (rf/make-frame {:id a-id})
    (rf/make-frame {:id b-id})
    (rf/reg-event :destroy/ambient-b-event
      (fn [{:keys [db]} [_ marker]]
        {:db (update db :seen (fnil conj []) marker)}))
    (rf/reg-event :destroy/ambient-a-event
      (fn [_ _]
        (rf/with-frame b-id
          (rf/dispatch [:destroy/ambient-b-event :before-loss]))
        (frame/destroy-frame! a-id)
        (rf/with-frame b-id
          (rf/dispatch [:destroy/ambient-b-event :after-loss]))
        {}))
    (rf/dispatch-sync [:destroy/ambient-a-event] {:frame a-id})
    (executor-barrier!)
    (is (= {:seen [:before-loss]} (frame/frame-app-db-value b-id))
        "ambient B accepts the pre-loss dispatch but cannot revive A's post-loss tail")))

(deftest async-and-sync-drains-never-retarget-same-id-successor
  ;; Mutation teeth: making drain-try!/drain-block! resolve a bare frame id
  ;; causes the stale A callbacks below to drain B.
  (let [id             :destroy/drain-retarget
        runs           (atom 0)
        ticks          (atom [])
        original-block (var-get (ns-resolve 're-frame.router 'drain-block!))
        block-entered  (CountDownLatch. 1)
        release-block  (CountDownLatch. 1)]
    (rf/reg-event :destroy/drain-retarget-event
      (fn [{:keys [db]} _]
        (swap! runs inc)
        {:db (assoc db :ran true)}))

    ;; Async scheduler callback accepted A, then runs after B replaced it.
    (rf/make-frame {:id id})
    (with-redefs [interop/next-tick #(swap! ticks conj %)]
      (rf/dispatch [:destroy/drain-retarget-event] {:frame id}))
    (frame/destroy-frame! id)
    (rf/make-frame {:id id})
    ((first @ticks))
    (is (zero? @runs) "obsolete async A callback did not drain B")
    (is (= {} (frame/frame-app-db-value id)) "B state stayed untouched")

    ;; Pause dispatch-sync after it captured A but before its exact drain entry.
    (frame/destroy-frame! id)
    (rf/make-frame {:id id})
    (let [dispatch-a
          (with-redefs-fn
            {(ns-resolve 're-frame.router 'drain-block!)
             (fn [& args]
               (.countDown block-entered)
               (.await release-block 10 TimeUnit/SECONDS)
               (apply original-block args))}
            #(let [f (future
                       (rf/dispatch-sync [:destroy/drain-retarget-event]
                                         {:frame id}))]
               (is (.await block-entered 10 TimeUnit/SECONDS)
                   "dispatch-sync captured A before replacement")
               f))]
      (frame/destroy-frame! id)
      (rf/make-frame {:id id})
      (.countDown release-block)
      (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
          "stale synchronous drain returned")
      (is (zero? @runs) "obsolete sync A drain did not execute in B")
      (is (= {} (frame/frame-app-db-value id)) "replacement B stayed pristine"))))

(deftest owner-loss-stops-later-trace-and-always-on-listeners
  ;; Mutation teeth: replacing the per-listener continuation loops with doseq
  ;; invokes listener #2; removing the trailer callback fence invokes the
  ;; frame-owned observation route after corpus listener #1 destroyed A.
  (let [trace-id       :destroy/trace-fanout
        event-id       :destroy/event-fanout
        trace-sibling  (atom 0)
        event-sibling  (atom 0)
        handler-runs   (atom 0)
        routed         (atom 0)
        original-route (late-bind/get-fn :observability/route-handled-event)]
    (rf/make-frame {:id trace-id})
    (rf/reg-event :destroy/trace-fanout-event
      (fn [_ _] (swap! handler-runs inc) {}))
    (rf/register-listener! :trace ::trace-destroyer
      (fn [ev]
        (when (and (= :rf.event/run-start (:operation ev))
                   (= trace-id (get-in ev [:tags :frame])))
          (frame/destroy-frame! trace-id)
          (throw (ex-info "trace listener lost A" {})))))
    (rf/register-listener! :trace ::trace-sibling
      (fn [ev]
        (when (and (= :rf.event/run-start (:operation ev))
                   (= trace-id (get-in ev [:tags :frame])))
          (swap! trace-sibling inc))))
    (rf/dispatch-sync [:destroy/trace-fanout-event] {:frame trace-id})
    (is (zero? @trace-sibling)
        "later snapshotted trace listeners stop after listener #1 loses A")
    ;; rf2-wxy1c: the trace fan-out for a drain-owned emit no longer runs INSIDE
    ;; the drain — it is deferred to the post-drain boundary, because the
    ;; framework must never invoke (nor await) arbitrary listener code while it
    ;; owns a frame's `:drain-lock`. A trace listener therefore observes
    ;; `:rf.event/run-start` AFTER the cascade has settled and can no longer veto
    ;; the handler it names. That back-pressure was never a contract — Spec 009
    ;; listeners are observers of the stream, not participants in the cascade —
    ;; and removing it is precisely what closes the drain-vs-drain overlap
    ;; (`re-frame.trace-listener-concurrent-drain-serialization-test`).
    ;;
    ;; The suppression that IS contract still holds, and is asserted above and
    ;; below: within a single fan-out, listener #1 losing A stops the REMAINING
    ;; listeners (`@trace-sibling`), and the always-on `:events` / `:errors`
    ;; families — which are not deferred — keep their full synchronous
    ;; suppression semantics.
    (is (= 1 @handler-runs)
        (str "the handler runs: a deferred trace listener destroying the frame "
             "at the post-drain boundary cannot retract a cascade that has "
             "already settled (rf2-wxy1c)"))

    (rf/make-frame {:id event-id})
    (rf/reg-event :destroy/event-fanout-event (fn [_ _] {}))
    (rf/register-listener! :events ::event-destroyer
      (fn [_]
        (frame/destroy-frame! event-id)
        (throw (ex-info "event listener lost A" {}))))
    (rf/register-listener! :events ::event-sibling
      (fn [_] (swap! event-sibling inc)))
    (try
      (late-bind/set-fn! :observability/route-handled-event
        (fn [& _] (swap! routed inc)))
      (rf/dispatch-sync [:destroy/event-fanout-event] {:frame event-id})
      (finally
        (late-bind/set-fn! :observability/route-handled-event original-route)))
    (is (zero? @event-sibling)
        "later corpus listeners stop after listener #1 loses A")
    (is (zero? @routed)
        "frame-owned observation routing is later framework tail and stays inert")))

(deftest union-error-fanout-loss-skips-siblings-and-frame-route
  ;; Mutation tooth: an unconditional route-error-record! after corpus fanout
  ;; routes A's union record against a replacement/absent frame.
  (let [id             :destroy/union-error-fanout
        sibling-runs   (atom 0)
        route-runs     (atom 0)
        original-route (late-bind/get-fn :observability/route-error-record)]
    (rf/make-frame {:id id})
    (rf/register-listener! :errors ::union-destroyer
      (fn [_]
        (frame/destroy-frame! id)
        (throw (ex-info "union listener lost A" {}))))
    (rf/register-listener! :errors ::union-sibling
      (fn [_] (swap! sibling-runs inc)))
    (rf/reg-event :destroy/union-error-event
      (fn [_ _]
        (error-emit/dispatch-error-record!
          {:error :rf.error/test-union :frame id :time 0})
        {}))
    (try
      (late-bind/set-fn! :observability/route-error-record
        (fn [& _] (swap! route-runs inc)))
      (rf/dispatch-sync [:destroy/union-error-event] {:frame id})
      (finally
        (late-bind/set-fn! :observability/route-error-record original-route)))
    (is (zero? @sibling-runs) "later union listeners stop on owner loss")
    (is (zero? @route-runs) "the later frame-owned union route is inert")))

;; ---- rf2-vxgfnd.154 — depth-halt fanout + commit exact-incarnation fence ----

(deftest depth-halt-fanout-and-commit-fenced-when-first-listener-loses-a
  ;; rf2-vxgfnd.154 (red before fix). A runaway cascade trips A's drain-depth
  ;; limit. `handle-depth-exceeded!` fans the always-on
  ;; `:rf.error/drain-depth-exceeded` record out through the corpus error-emit
  ;; registry + the frame-owned error route, THEN commits A's terminal
  ;; `:halted-depth` epoch record. The FIRST error listener destroys A and
  ;; publishes a same-id B (with a sentinel in B's capture buffer). Because the
  ;; depth-halt seam runs OUTSIDE the event pipeline's continuation predicate,
  ;; before the fix `trace/continuation-live?` was always-true here: later
  ;; fanout siblings and the frame-owned route ran against B, and the bare-id
  ;; `commit-halt-record!` harvested B's buffer and committed A's halted-depth
  ;; trigger into B's history.
  ;;
  ;; After the fix the depth-halt binds A's EXACT-owner continuation predicate
  ;; and threads A's token into the commit: once the first listener loses A, no
  ;; later sibling, frame route, or halt commit touches B; B's stores stay
  ;; byte-identical; the evidence the first listener received stands exactly
  ;; once.
  (let [id             :drain.incarnation/depth-loss
        depth-records  (atom [])
        sibling-runs   (atom 0)
        frame-routes   (atom 0)
        b-token        (atom nil)
        b-sentinel     {:operation :drain.incarnation/b-buffer :tags {:frame id}}
        original-route (late-bind/get-fn :observability/route-error-record)]
    (error-emit/clear-error-listeners!)
    (rf/reg-event :drain.incarnation/loop
      (fn [_ _] {:fx [[:dispatch [:drain.incarnation/loop]]]}))
    (rf/make-frame {:id id :drain-depth 4})
    (try
      (late-bind/set-fn! :observability/route-error-record
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:error record))
            (swap! frame-routes inc))
          (when original-route (original-route record))))
      ;; Listener #1 (destroyer) is registered FIRST so the small array-map
      ;; corpus registry fans it before the sibling: it destroys A, publishes
      ;; same-id B, and installs a sentinel into B's capture buffer.
      (rf/register-listener! :errors ::depth-destroyer
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:error record))
            (swap! depth-records conj record)
            (frame/destroy-frame! id)
            (rf/make-frame {:id id})
            (reset! b-token (frame/frame-incarnation-token id))
            (epoch-state/buffer-event! id b-sentinel))))
      (rf/register-listener! :errors ::depth-sibling
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:error record))
            (swap! sibling-runs inc))))
      (rf/dispatch-sync [:drain.incarnation/loop] {:frame id})
      (executor-barrier!)
      (let [token-b @b-token]
        (is (= 1 (count @depth-records))
            "the first error listener received the depth record exactly once")
        (is (some? token-b) "the destroyer published a same-id B")
        (is (zero? @sibling-runs)
            "no later corpus error listener runs once the first listener loses A")
        (is (zero? @frame-routes)
            "A's depth record never resolves through B's frame-owned error route")
        (is (identical? token-b (frame/frame-incarnation-token id))
            "B remains the live incarnation")
        (is (= {} (frame/frame-app-db-value id))
            "A's halted-depth commit never mutates B's app-db")
        (is (empty? (rf/epoch-history id))
            "no A halted-depth record is committed into B's history")
        (is (nil? (epoch-state/last-settled-epoch-id id))
            "A's halt commit never claims B's last-settled anchor")
        (is (= [b-sentinel] (epoch-state/buffer-for id))
            "A's halt commit never harvests B's capture buffer"))
      (finally
        (rf/unregister-listener! :errors ::depth-destroyer)
        (rf/unregister-listener! :errors ::depth-sibling)
        (error-emit/clear-error-listeners!)
        (late-bind/set-fn! :observability/route-error-record original-route)
        (when (frame/frame id) (frame/destroy-frame! id))))))

(deftest depth-halt-fans-and-commits-normally-when-a-retains-ownership
  ;; rf2-vxgfnd.154 mutation tooth. When A stays live through the depth halt the
  ;; exact-owner continuation predicate must NOT suppress the ordinary
  ;; behaviour: every corpus error listener still receives the depth record, and
  ;; A's terminal `:halted-depth` epoch record is still committed into A's own
  ;; history. A wrongly always-false predicate would silence both.
  (let [id            :drain.incarnation/depth-live
        depth-records (atom [])
        sibling-runs  (atom 0)]
    (error-emit/clear-error-listeners!)
    (rf/reg-event :drain.incarnation/live-loop
      (fn [_ _] {:fx [[:dispatch [:drain.incarnation/live-loop]]]}))
    (rf/make-frame {:id id :drain-depth 4})
    (try
      (rf/register-listener! :errors ::live-a
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:error record))
            (swap! depth-records conj record))))
      (rf/register-listener! :errors ::live-b
        (fn [record]
          (when (= :rf.error/drain-depth-exceeded (:error record))
            (swap! sibling-runs inc))))
      (rf/dispatch-sync [:drain.incarnation/live-loop] {:frame id})
      (executor-barrier!)
      (is (= 1 (count @depth-records))
          "the always-on depth record fans out when A stays live")
      (is (= id (:frame (first @depth-records)))
          "the depth record names the overflowing frame")
      (is (= 1 @sibling-runs)
          "every corpus error listener runs when A retains ownership")
      ;; The three corpus assertions above already ran in both postures — the
      ;; depth record fans through the always-on `:errors` registry. Only the
      ;; epoch-history half is trace-fed (rf2-d2841).
      (when interop/debug-enabled?
        (is (some #(= :halted-depth (:outcome %)) (rf/epoch-history id))
            "A's terminal halted-depth record is committed into A's own history"))
      (finally
        (rf/unregister-listener! :errors ::live-a)
        (rf/unregister-listener! :errors ::live-b)
        (error-emit/clear-error-listeners!)
        (when (frame/frame id) (frame/destroy-frame! id))))))
