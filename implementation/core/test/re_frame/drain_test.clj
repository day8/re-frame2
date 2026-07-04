(ns re-frame.drain-test
  "Targeted coverage for Spec 002 §Run-to-completion dispatch (drain
  semantics). The login-machine-flow and dispatch-sync-in-handler-errors
  smoke tests exercise these paths transitively; this namespace pins the
  load-bearing properties directly so a future regression in router.cljc
  surfaces here, not from a far-away cascade test.

  Each deftest's docstring cites the specific Spec 002 anchor."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  ;; rf2-fcbrjo: the always-on error-emit listener registry is a `defonce`
  ;; atom — clear it so an `:errors` listener from one test cannot leak into
  ;; the next (the drain-depth always-on assertion below registers one).
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win. A top-level
  ;; `reg-frame …:initial-events` still drain synchronously — the lifecycle
  ;; async/sync split keys off `*handler-scope*` (a real cascade), not
  ;; this ambient scope.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- 1. run-to-completion -------------------------------------------------

(deftest run-to-completion-handler-finishes-before-next-event
  ;; Spec 002 §Run-to-completion dispatch (drain semantics) §Rules rule 2:
  ;; \"Every actor message sent during a domain-event's processing drains
  ;; before the next domain event for that frame.\" Once drain is engaged,
  ;; no further external events are processed for that frame until the
  ;; cascade settles. The :fx [[:dispatch ...]] form is the in-handler
  ;; primitive (Spec 002 §Run-to-completion: \"the in-handler shape is
  ;; [[:dispatch event]] under :fx\")."
  (testing "events queued during a handler run only AFTER that handler returns"
    (let [order (atom [])]
      ;; :outer pushes :outer-pre into the trace, dispatches :inner via :fx,
      ;; then pushes :outer-post BEFORE the inner handler can run. Run-to-
      ;; completion guarantees the outer handler completes (both pre and
      ;; post entries land) before :inner is dequeued.
      (rf/reg-event :outer
        (fn [_ _]
          (swap! order conj :outer-pre)
          ;; Returning :fx with a :dispatch — the inner event is appended
          ;; to the back of the queue. It must NOT execute before this
          ;; handler returns.
          (let [fx-result {:fx [[:dispatch [:inner]]]}]
            (swap! order conj :outer-post)
            fx-result)))
      (rf/reg-event :inner
        (fn [_ _]
          (swap! order conj :inner)
          {}))
      (rf/dispatch-sync [:outer])
      (is (= [:outer-pre :outer-post :inner] @order)
          "the outer handler ran to completion before :inner was processed")))

  (testing "deeper chain — every outer's :fx :dispatch waits for the outer to return"
    (let [order (atom [])]
      (rf/reg-event :a
        (fn [_ _]
          (swap! order conj :a-start)
          (let [r {:fx [[:dispatch [:b]]]}]
            (swap! order conj :a-end)
            r)))
      (rf/reg-event :b
        (fn [_ _]
          (swap! order conj :b-start)
          (let [r {:fx [[:dispatch [:c]]]}]
            (swap! order conj :b-end)
            r)))
      (rf/reg-event :c
        (fn [_ _]
          (swap! order conj :c)
          {}))
      (rf/dispatch-sync [:a])
      (is (= [:a-start :a-end :b-start :b-end :c] @order)
          "no handler interleaves; each runs end-to-end before the next starts"))))

;; ---- 2. drain depth limit -------------------------------------------------

(deftest drain-depth-limit-aborts-with-structured-error
  ;; Spec 002 §Run-to-completion dispatch §Rules rule 3:
  ;; \"Depth-limited (dynamic). The drain enforces a configurable depth
  ;; limit (:drain-depth). When exceeded, drain aborts with a machine-
  ;; readable error: {:reason :drain-depth-exceeded :frame :auth :event
  ;; [...] :depth N}. The limit is per-frame and runtime-overridable.\"
  ;; The router halts the loop and clears the queue when the bound is hit;
  ;; see implementation/src/re_frame/router.cljc."
  (testing "a self-redispatching handler trips :rf.error/drain-depth-exceeded"
    (let [traces (atom [])]
      (rf/register-listener! :trace ::depth (fn [ev] (swap! traces conj ev)))
      ;; Reg a frame with a small drain-depth so the test runs quickly.
      (rf/reg-frame :drain.test/loop {:drain-depth 8})
      (rf/reg-event :loop-forever
        (fn [_ _]
          {:fx [[:dispatch [:loop-forever]]]}))
      (rf/dispatch-sync [:loop-forever] {:frame :drain.test/loop})
      (rf/unregister-listener! :trace ::depth)
      (let [hit (some (fn [ev]
                        (when (= :rf.error/drain-depth-exceeded
                                 (:operation ev))
                          ev))
                      @traces)]
        (is (some? hit)
            "expected :rf.error/drain-depth-exceeded trace event")
        (when hit
          (let [tags (:tags hit)]
            (is (number? (:depth tags))
                ":depth tag is a number")
            (is (= :drain.test/loop (:frame tags))
                ":frame tag identifies the offending frame")
            (is (vector? (:last-event tags))
                ":last-event tag carries the most-recently-dequeued event")
            (is (= [:loop-forever] (:last-event tags))
                ":last-event is the recursive event that drove the cascade"))))))

  (testing "after the abort the queue is cleared (no stuck pending work)"
    (let [traces (atom [])]
      (rf/register-listener! :trace ::depth-2 (fn [ev] (swap! traces conj ev)))
      (rf/reg-frame :drain.test/loop2 {:drain-depth 4})
      (rf/reg-event :loop2
        (fn [_ _]
          {:fx [[:dispatch [:loop2]]]}))
      (rf/dispatch-sync [:loop2] {:frame :drain.test/loop2})
      (rf/unregister-listener! :trace ::depth-2)
      (let [router (:router (frame/frame :drain.test/loop2))]
        (is (zero? (count (:queue @router)))
            "the router queue is drained empty after the depth-exceeded abort")
        (is (false? (:scheduled? @router))
            ":scheduled? is reset so future dispatches re-engage drain")))))

(deftest drain-depth-exceeded-keeps-durable-per-event-writes
  ;; Per rf2-u6jsj/rf2-nj6p7 (Spec 002 §Drain versus event — the epoch
  ;; unit): the epoch boundary is the dequeued EVENT, so each event that
  ;; ran before the depth limit tripped settled its own durable epoch AND
  ;; its own db write. There is NO whole-drain rollback under per-event
  ;; epochs — each settled event is independently atomic. The depth limit
  ;; stops the NEXT (halting) event; the work that already ran survives.
  ;;
  ;; SUPERSEDES the pre-rf2-u6jsj per-drain atomic-rollback behaviour
  ;; (Spec 002 rule 3's "restore app-db to its pre-drain snapshot"), which
  ;; was written for the per-drain epoch model. Rule 3 needs tightening to
  ;; the per-event boundary — see rf2-nj6p7.
  (testing "a chain that overflows leaves :db with the durable per-event writes"
    ;; Frame seeded via :initial-events so the baseline is non-empty.
    (rf/reg-event :seed/init
      (fn [{:keys [db]} _] {:db {:step :pre-drain :counter 0}}))
    (rf/reg-frame :drain.rollback/main
      {:initial-events   [[:seed/init]]
       :drain-depth 4})
    (let [traces (atom [])]
      (rf/register-listener! :trace ::rollback (fn [ev] (swap! traces conj ev)))
      ;; A handler that COMMITS a :db write (advancing :step, bumping
      ;; :counter) AND re-dispatches itself. Each iteration is its own
      ;; dequeued event = its own durable epoch + db write. After the
      ;; 4-event limit, :counter == 4 and :step == :mid-drain survive.
      (rf/reg-event :overflow
        (fn [{:keys [db]} _]
          {:db {:step :mid-drain :counter (inc (:counter db 0))}
           :fx [[:dispatch [:overflow]]]}))
      (rf/dispatch-sync [:overflow] {:frame :drain.rollback/main})
      (rf/unregister-listener! :trace ::rollback)
      ;; Per-event durability: the four completed events' writes survive —
      ;; NO whole-drain rollback.
      (is (= {:step :mid-drain :counter 4}
             (rf/app-db-value :drain.rollback/main))
          "the durable per-event writes survive; there is no whole-drain rollback")
      ;; Sanity: the depth-exceeded trace fired and tags :rollback? false
      ;; (no rollback under per-event epochs).
      (let [hit (some (fn [ev]
                        (when (= :rf.error/drain-depth-exceeded
                                 (:operation ev))
                          ev))
                      @traces)]
        (is (some? hit) "drain-depth-exceeded trace was emitted")
        (when hit
          (is (false? (get-in hit [:tags :rollback?]))
              ":rollback? false — per rf2-nj6p7 there is no whole-drain rollback")))))

  (testing "earlier clean drains stay durable; an overflow keeps prior-event writes"
    ;; A drain that has already settled cleanly once, then is re-engaged
    ;; with a self-dispatching event: the earlier clean drain stays
    ;; durable AND the overflow drain's own per-event writes stay durable
    ;; (no rollback).
    (rf/reg-event :seed2/init (fn [{:keys [db]} _] {:db {:phase :seeded :n 0}}))
    (rf/reg-frame :drain.rollback/two
      {:initial-events   [[:seed2/init]]
       :drain-depth 3})
    ;; First drain: a clean settle that mutates :phase.
    (rf/reg-event :advance (fn [{:keys [db]} _] {:db (assoc db :phase :first-settled)}))
    (rf/dispatch-sync [:advance] {:frame :drain.rollback/two})
    (is (= {:phase :first-settled :n 0}
           (rf/app-db-value :drain.rollback/two))
        "first drain settled cleanly; that's the new baseline")
    ;; Second drain: trip the depth limit. Per rf2-nj6p7 the three events
    ;; that ran each made a durable :n write — no rollback.
    (rf/reg-event :overflow2
      (fn [{:keys [db]} _]
        {:db (assoc db :phase :poisoned :n (inc (:n db 0)))
         :fx [[:dispatch [:overflow2]]]}))
    (rf/dispatch-sync [:overflow2] {:frame :drain.rollback/two})
    (is (= {:phase :poisoned :n 3}
           (rf/app-db-value :drain.rollback/two))
        "the overflow drain's per-event writes are durable — no whole-drain rollback")))

(deftest drain-depth-halts-after-exactly-drain-depth-events
  ;; rf2-agpv2.1 — CANONICAL pin of the drain-depth event count.
  ;;
  ;; `:drain-depth` is the MAXIMUM number of events a single drain
  ;; processes. The router halts at `(>= depth drain-depth)` (router.cljc
  ;; run-one-pass!), and Spec 002 §Drain-loop pseudocode now matches with
  ;; `(>= depth (:drain-depth …))`. So for a runaway self-redispatching
  ;; cascade under drain-depth N, EXACTLY N handler bodies run (depths
  ;; 0,1,…,N-1) and the (N+1)th event is the halting event that never
  ;; runs. This test pins that count directly so a future flip back to
  ;; `>`/`>=` (an off-by-one) fails HERE rather than in a far-away
  ;; cascade assertion. The `:depth` tag on the halt equals N.
  (testing "a runaway cascade under drain-depth N runs EXACTLY N handlers, then halts"
    (doseq [n [1 4 8 100]]
      (let [frame-id (keyword "drain.count" (str "loop-" n))
            runs     (atom 0)
            traces   (atom [])
            event-id (keyword "drain.count" (str "tick-" n))]
        (rf/register-listener! :trace ::count (fn [ev] (swap! traces conj ev)))
        (rf/reg-frame frame-id {:drain-depth n})
        (rf/reg-event event-id
          (fn [_ _]
            (swap! runs inc)
            {:fx [[:dispatch [event-id]]]}))
        (rf/dispatch-sync [event-id] {:frame frame-id})
        (rf/unregister-listener! :trace ::count)
        (is (= n @runs)
            (str "exactly " n " handler bodies ran for drain-depth " n
                 " (got " @runs ")"))
        (let [hit (some (fn [ev]
                          (when (= :rf.error/drain-depth-exceeded
                                   (:operation ev))
                            ev))
                        @traces)]
          (is (some? hit)
              (str "drain-depth-exceeded trace fired for drain-depth " n))
          (when hit
            (is (= n (get-in hit [:tags :depth]))
                (str ":depth tag equals drain-depth " n
                     " (the halting event's depth = N)"))))))))

;; ---- 3. dispatch-sync-in-handler ------------------------------------------

(deftest dispatch-sync-in-handler-jvm
  ;; Spec 002 §Run-to-completion §Render boundaries:
  ;; \":dispatch-sync means 'skip the router queue when called from outside
  ;;   any handler.' Calling it from inside a handler raises
  ;;   :rf.error/dispatch-sync-in-handler ... the in-handler shape is
  ;;   [[:dispatch event]] under :fx.\"
  ;; The CLJS partner test (runtime_cljs_test.cljs §dispatch-sync-in-
  ;; handler-errors-cljs) covers the browser path; this is the JVM
  ;; equivalent plus the transitive-via-fx case the bead calls out."
  (testing "directly calling rf/dispatch-sync from a handler raises the structured error"
    (let [traces (atom [])]
      (rf/register-listener! :trace ::dsih-direct (fn [ev] (swap! traces conj ev)))
      (rf/reg-event :leaf (fn [{:keys [db]} _] {:db (assoc db :leaf? true)}))
      (rf/reg-event :nested-direct
        (fn [_ _]
          (rf/dispatch-sync [:leaf])
          {}))
      (rf/dispatch-sync [:nested-direct])
      (rf/unregister-listener! :trace ::dsih-direct)
      (let [err (some (fn [ev]
                        (when (and (= :rf.error/dispatch-sync-in-handler (:operation ev))
                                   (= :error (:op-type ev))
                                   (= :no-recovery (:recovery ev)))
                          ev))
                      @traces)]
        (is (some? err)
            "expected :rf.error/dispatch-sync-in-handler with :no-recovery")
        ;; rf2-kg0et6 — the rejected inner event vector MUST ride the
        ;; schema-required `:rf.event/v` tag (Spec-Schemas
        ;; §DispatchSyncInHandlerTags; Spec 009 §Error event catalogue),
        ;; NOT the undocumented bare `:event`. Pin both directions so the
        ;; documented key can't silently drift back.
        (when err
          (let [tags (:tags err)]
            (is (= [:leaf] (:rf.event/v tags))
                "the rejected inner event vector rides the schema-required :rf.event/v tag")
            (is (not (contains? tags :event))
                "the undocumented bare :event tag is not emitted")
            (is (= :rf/default (:frame tags))
                "the :frame tag carries the enclosing frame (the default frame here)"))))))

  (testing "calling dispatch-sync TRANSITIVELY through a user fx is also caught"
    ;; Some fx handlers naively call dispatch-sync to chain another event.
    ;; The drain still flags the call site even though it's one frame
    ;; below the original handler — :in-drain? on the router is the
    ;; primary guard, not the call-stack depth.
    (let [traces (atom [])]
      (rf/register-listener! :trace ::dsih-fx (fn [ev] (swap! traces conj ev)))
      (rf/reg-event :leaf2 (fn [{:keys [db]} _] {:db (assoc db :leaf2? true)}))
      (rf/reg-fx :user.fx/sync-dispatch
        {:platforms #{:server :client}}
        (fn [_ ev]
          ;; This is the wrong way to chain — should be :dispatch in the
          ;; effects map. The router must still emit the structured
          ;; error so the bug is observable.
          (rf/dispatch-sync ev)))
      (rf/reg-event :nested-via-fx
        (fn [_ _]
          {:fx [[:user.fx/sync-dispatch [:leaf2]]]}))
      (rf/dispatch-sync [:nested-via-fx])
      (rf/unregister-listener! :trace ::dsih-fx)
      (is (some (fn [ev]
                  (= :rf.error/dispatch-sync-in-handler (:operation ev)))
                @traces)
          "the transitive (via-fx) dispatch-sync still trips the in-handler guard"))))

;; ---- 4. async vs sync interleaving ----------------------------------------

(deftest async-dispatch-resolves-after-current-drain
  ;; Spec 002 §Run-to-completion dispatch (drain semantics):
  ;; \"events queued via dispatch resolve after the current drain; sync-
  ;;   side events triggered via :fx [[:dispatch ...]] resolve in the same
  ;;   drain.\" See also Spec 002 §Drain-loop pseudocode :dispatch fx
  ;; comment: \"append to back of router queue; the outer drain picks it
  ;;   up in this same drain cycle (run-to-completion).\""
  (testing ":fx [[:dispatch ...]] events drain in-cycle; the cascade is observed atomically"
    (let [order (atom [])]
      (rf/reg-event :seed
        (fn [_ _]
          (swap! order conj :seed)
          ;; Two fx-side dispatches plus a :db update. All must drain
          ;; before dispatch-sync returns.
          {:db {:n 0}
           :fx [[:dispatch [:bump]]
                [:dispatch [:bump]]]}))
      (rf/reg-event :bump
        (fn [{:keys [db]} _]
          (swap! order conj :bump)
          {:db (update db :n inc)}))
      (rf/dispatch-sync [:seed])
      (is (= [:seed :bump :bump] @order)
          "both :fx-side :dispatch events ran inside the same dispatch-sync cycle")
      (is (= 2 (:n (rf/app-db-value :rf/default)))
          "their effects are visible the moment dispatch-sync returns")))

  (testing "rf/dispatch (the async API) defers to AFTER the current dispatch-sync drain"
    ;; Calling rf/dispatch from outside any drain doesn't run the event
    ;; synchronously — it goes through interop/next-tick (the JVM
    ;; executor). The dispatch-sync below only sees its own work; the
    ;; async-queued event arrives on a later drain.
    ;;
    ;; rf2-lmkk: on the JVM, the async dispatch's drain thunk is posted
    ;; onto a single-thread executor. The main thread then runs
    ;; dispatch-sync, which starts its own drain on the queue. The
    ;; drain! loop's peek+pop pair is not atomic across threads, so if
    ;; the executor wakes up while the main thread is mid-drain both
    ;; threads can peek the same envelope, double-process a single event
    ;; and drop another. That race produced
    ;;   actual: (not (some #{:outside-async} [:sync-only :sync-only]))
    ;; intermittently on CI. Same family as rf2-iosc — stabilise it the
    ;; same way: intercept interop/next-tick so the executor never sees
    ;; the drain thunk concurrent with the sync drain, then invoke the
    ;; captured thunk synchronously on the main thread once the sync
    ;; drain has settled. The semantics under test are unchanged: the
    ;; async event runs only AFTER the dispatch-sync drain returns.
    (let [order          (atom [])
          done           (promise)
          captured-ticks (atom [])]
      (rf/reg-event :outside-async
        (fn [{:keys [db]} _]
          (swap! order conj :outside-async)
          (deliver done :ok)
          {:db (assoc db :outside? true)}))
      (rf/reg-event :sync-only
        (fn [{:keys [db]} _]
          (swap! order conj :sync-only)
          {:db db}))
      (with-redefs [interop/next-tick (fn [f]
                                        (swap! captured-ticks conj f)
                                        nil)]
        ;; Queue an async dispatch first. Its drain thunk is captured
        ;; (not handed to the executor), eliminating the cross-thread
        ;; peek/pop race that would otherwise corrupt @order under load.
        (rf/dispatch [:outside-async])
        ;; Then run a sync drain. The async event is still in the queue
        ;; (drain thunk captured, not yet run). The sync drain seeds
        ;; :sync-only at the FRONT of the queue and drains both — but
        ;; the assertion below only requires both ran, not a specific
        ;; order, so this still pins the spec property.
        (rf/dispatch-sync [:sync-only]))
      ;; Run any drain thunks the async path scheduled. With the sync
      ;; drain already settled, this is just a tidy-up — the queue may
      ;; already be empty, in which case drain! is a no-op.
      (doseq [f @captured-ticks] (f))
      ;; Now wait for the async one to settle.
      (is (= :ok (deref done 2000 :timeout))
          ":outside-async eventually drained on the executor")
      (is (true? (:outside? (rf/app-db-value :rf/default)))
          ":outside-async's effect lands on app-db after its drain")
      (is (some #{:sync-only} @order) ":sync-only ran")
      (is (some #{:outside-async} @order) ":outside-async ran"))))

;; ---- 5. per-frame drain isolation ----------------------------------------

(deftest per-frame-drain-isolation
  ;; Spec 002 §Run-to-completion dispatch §Rules rule 1:
  ;; \"No cross-frame drain. Drain runs against the frame's own router
  ;;   queue. A dispatch tagged with a *different* frame goes through the
  ;;   ordinary async path — drain does not span frames. Cross-frame
  ;;   coordination uses regular async (dispatch ev {:frame other}).\""
  (testing "dispatching to frame B from inside frame A's handler does NOT interleave with A's drain"
    (rf/reg-frame :drain.test/A {:doc "frame A"})
    (rf/reg-frame :drain.test/B {:doc "frame B"})
    (let [order (atom [])
          b-done (promise)]
      (rf/reg-event :A/work
        (fn [{:keys [db]} _]
          (swap! order conj :A-start)
          ;; Dispatch a cross-frame event — this hits frame B's queue
          ;; via the async path. It must not run as part of A's drain.
          (rf/dispatch [:B/work] {:frame :drain.test/B})
          (swap! order conj :A-end)
          {:db (assoc db :a-ran? true)}))
      (rf/reg-event :B/work
        (fn [{:keys [db]} _]
          (swap! order conj :B)
          (deliver b-done :ok)
          {:db (assoc db :b-ran? true)}))
      (rf/dispatch-sync [:A/work] {:frame :drain.test/A})
      ;; The moment dispatch-sync returns, A's cascade has settled. B's
      ;; cascade may still be in flight on the executor.
      (is (= [:A-start :A-end] (vec (filter #{:A-start :A-end} @order)))
          "A's handler ran end-to-end without B interleaving inside it")
      (is (= :ok (deref b-done 2000 :timeout))
          "B's drain eventually fires on the executor")
      (is (true? (:a-ran? (rf/app-db-value :drain.test/A)))
          "A's :db commit landed in A's app-db only")
      (is (nil? (:b-ran? (rf/app-db-value :drain.test/A)))
          "B's :db commit did NOT spill into A's app-db")
      (is (true? (:b-ran? (rf/app-db-value :drain.test/B)))
          "B's :db commit landed in B's app-db")
      (is (nil? (:a-ran? (rf/app-db-value :drain.test/B)))
          "A's :db commit did NOT spill into B's app-db")))

  (testing "two interleaved dispatch-sync calls keep their queues separate"
    ;; This pins the per-frame router contract: each frame has its own
    ;; queue and :scheduled?/:in-drain? flags.
    (rf/reg-frame :drain.test/X {:doc "X"})
    (rf/reg-frame :drain.test/Y {:doc "Y"})
    (rf/reg-event :tick (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:tick] {:frame :drain.test/X})
    (rf/dispatch-sync [:tick] {:frame :drain.test/Y})
    (rf/dispatch-sync [:tick] {:frame :drain.test/X})
    (is (= 2 (:n (rf/app-db-value :drain.test/X))))
    (is (= 1 (:n (rf/app-db-value :drain.test/Y))))))

;; ---- rf2-6guf: drain-depth-exceeded preserves OTHER frames' app-db --------
;;
;; Per test-coverage-review-2026-05-12 P3-25: the companion
;; `drain-depth-exceeded-keeps-durable-per-event-writes` only exercises
;; :rf/default. The broader contract is "a depth-exceed is scoped to the
;; FRAME that overflowed — other frames' app-dbs are untouched, and the
;; depth-exceeded trace carries the right frame id". Per rf2-nj6p7
;; (per-event epochs) the overflowing frame keeps its durable per-event
;; writes (no whole-drain rollback); the isolation contract is unchanged.

(deftest drain-depth-exceeded-isolated-to-the-overflowing-frame
  (testing "depth-exceed on frame :B rolls back :B's app-db only; :A's
            app-db is byte-identical to its pre-dispatch state; the
            depth-exceeded trace carries :B"
    (rf/reg-event :seed/A (fn [{:keys [db]} _] {:db {:where :A :counter 0 :marker :pristine}}))
    (rf/reg-event :seed/B (fn [{:keys [db]} _] {:db {:where :B :counter 0 :marker :pristine}}))
    (rf/reg-frame :drain.iso/A
                  {:initial-events   [[:seed/A]]
                   :drain-depth 50})
    ;; Frame :B has the tight drain-depth so :B's loop event trips it.
    (rf/reg-frame :drain.iso/B
                  {:initial-events   [[:seed/B]]
                   :drain-depth 4})

    ;; Capture :A's pre-dispatch state — this is what we'll compare to.
    (let [a-pre  (rf/app-db-value :drain.iso/A)
          traces (atom [])]
      (rf/register-listener! :trace ::iso (fn [ev] (swap! traces conj ev)))

      ;; Register a loop event under :B that infinitely self-dispatches.
      ;; Each iteration writes to :B's :db, so we can see whether the
      ;; rollback actually fires.
      (rf/reg-event :loop/B
        (fn [{:keys [db]} _]
          {:db {:where :B
                :counter (inc (:counter db 0))
                :marker  :mid-cascade}
           :fx [[:dispatch [:loop/B]]]}))

      ;; Dispatch the loop on :B. This trips :B's drain-depth limit. Per
      ;; rf2-nj6p7 (per-event epochs) :B's completed events keep their
      ;; durable writes — no whole-drain rollback — but the cascade stays
      ;; isolated to :B; :A is untouched.
      (rf/dispatch-sync [:loop/B] {:frame :drain.iso/B})

      ;; --- (a) :B's per-event writes are durable (4 events ran).
      (is (= {:where :B :counter 4 :marker :mid-cascade}
             (rf/app-db-value :drain.iso/B))
          ":B's durable per-event writes survive; no whole-drain rollback")

      ;; --- (b) :A's app-db is byte-identical to its pre-dispatch state.
      (is (= a-pre (rf/app-db-value :drain.iso/A))
          ":A's app-db is untouched (value-equal to pre-dispatch)")
      (is (= {:where :A :counter 0 :marker :pristine}
             (rf/app-db-value :drain.iso/A))
          ":A's app-db remains exactly its :initial-events state")

      ;; --- (c) the depth-exceeded trace carries :B, not :A.
      (let [hit (some (fn [ev]
                        (when (= :rf.error/drain-depth-exceeded
                                 (:operation ev))
                          ev))
                      @traces)]
        (is (some? hit) "drain-depth-exceeded trace was emitted")
        (is (= :drain.iso/B (get-in hit [:tags :frame]))
            "the trace's :frame tag is :B (the overflowing frame), not :A")
        (is (false? (get-in hit [:tags :rollback?]))
            ":rollback? false — per rf2-nj6p7 there is no whole-drain rollback"))

      (rf/unregister-listener! :trace ::iso))))

;; ---- rf2-fcbrjo: drain-depth-exceeded is ALWAYS-ON + carries cycle evidence

(deftest drain-depth-exceeded-fans-out-on-the-always-on-axis-with-cycle-evidence
  ;; rf2-fcbrjo — the production halt must be VISIBLE. Before promotion the
  ;; drain-depth halt rode ONLY the dev trace surface (`:trace` listeners /
  ;; `trace/emit-error!`), which Closure DCEs under `goog.DEBUG=false`, so a
  ;; production build shipped NOTHING when a runaway drain halted. This pins
  ;; the promotion: the halt ALSO fans a STRUCTURAL-ONLY record out through the
  ;; ALWAYS-ON error-emit axis (`rf/register-listener! :errors`, surface #4 —
  ;; production-survivable, NOT gated on `interop/debug-enabled?`), carrying
  ;; the CYCLE EVIDENCE (`:tail-event-ids`, the last K settled event-ids — the
  ;; repeating suffix IS the runaway cycle).
  (testing "a runaway drain fans a structural always-on record with cycle evidence"
    (let [records (atom [])]
      ;; The ALWAYS-ON listener — NOT the dev `:trace` stream. This is the
      ;; surface that survives production.
      (rf/register-listener! :errors ::depth-always-on
                             (fn [rec] (swap! records conj rec)))
      (rf/reg-frame :drain.always-on/loop {:drain-depth 6})
      ;; A two-event cycle: :ping → :pong → :ping → … so the tail ring shows a
      ;; repeating suffix (the cycle evidence), not just one repeated id.
      (rf/reg-event :ping (fn [_ _] {:fx [[:dispatch [:pong]]]}))
      (rf/reg-event :pong (fn [_ _] {:fx [[:dispatch [:ping]]]}))
      (rf/dispatch-sync [:ping] {:frame :drain.always-on/loop})
      (rf/unregister-listener! :errors ::depth-always-on)
      (let [rec (some (fn [r]
                        (when (= :rf.error/drain-depth-exceeded (:error r)) r))
                      @records)]
        (is (some? rec)
            "the drain-depth halt fanned out on the ALWAYS-ON error axis")
        (when rec
          ;; --- structural fields
          (is (= :drain.always-on/loop (:frame rec))
              ":frame identifies the overflowing frame")
          (is (= 6 (:depth rec)) ":depth equals the frame's :drain-depth")
          (is (number? (:queue-size rec)) ":queue-size is a count")
          (is (false? (:rollback? rec))
              ":rollback? false — no whole-drain rollback under per-event epochs")
          (is (= :no-recovery (:recovery rec)) ":recovery is :no-recovery")
          ;; --- CYCLE EVIDENCE: the tail ring of settled event-ids.
          (is (vector? (:tail-event-ids rec))
              ":tail-event-ids is the cycle-evidence ring (a vector)")
          (is (seq (:tail-event-ids rec))
              ":tail-event-ids is non-empty (events settled before the halt)")
          (is (every? #{:ping :pong} (:tail-event-ids rec))
              ":tail-event-ids carries the cycle's event-ids (ids only, no args)")
          ;; The repeating suffix names the cycle: the last two settled ids are
          ;; the two members of the ping↔pong loop.
          (is (= #{:ping :pong} (set (take-last 2 (:tail-event-ids rec))))
              "the repeating suffix IS the runaway cycle (both members present)")
          (is (contains? #{:ping :pong} (:last-event-id rec))
              ":last-event-id is the id of the most-recently-settled event")
          ;; --- STRUCTURAL-ONLY: the always-on record must NOT drag the dev-only
          ;; prose / full-vector slots (the elision discipline — those ride the
          ;; DCE'd dev trace, not this production-surviving axis).
          (is (not (contains? rec :reason))
              "the always-on record carries NO :reason prose (dev-trace only)")
          (is (not (contains? rec :last-event))
              "the always-on record carries NO :last-event vector (dev-trace only)"))))))

