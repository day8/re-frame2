(ns re-frame.drain-test
  "Targeted coverage for Spec 002 §Run-to-completion dispatch (drain
  semantics). The login-machine-flow and dispatch-sync-in-handler-errors
  smoke tests exercise these paths transitively; this namespace pins the
  load-bearing properties directly so a future regression in router.cljc
  surfaces here, not from a far-away cascade test.

  Each deftest's docstring cites the specific Spec 002 anchor."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

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
      (rf/reg-event-fx :outer
        (fn [_ _]
          (swap! order conj :outer-pre)
          ;; Returning :fx with a :dispatch — the inner event is appended
          ;; to the back of the queue. It must NOT execute before this
          ;; handler returns.
          (let [fx-result {:fx [[:dispatch [:inner]]]}]
            (swap! order conj :outer-post)
            fx-result)))
      (rf/reg-event-fx :inner
        (fn [_ _]
          (swap! order conj :inner)
          {}))
      (rf/dispatch-sync [:outer])
      (is (= [:outer-pre :outer-post :inner] @order)
          "the outer handler ran to completion before :inner was processed")))

  (testing "deeper chain — every outer's :fx :dispatch waits for the outer to return"
    (let [order (atom [])]
      (rf/reg-event-fx :a
        (fn [_ _]
          (swap! order conj :a-start)
          (let [r {:fx [[:dispatch [:b]]]}]
            (swap! order conj :a-end)
            r)))
      (rf/reg-event-fx :b
        (fn [_ _]
          (swap! order conj :b-start)
          (let [r {:fx [[:dispatch [:c]]]}]
            (swap! order conj :b-end)
            r)))
      (rf/reg-event-fx :c
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
      (rf/register-listener! ::depth (fn [ev] (swap! traces conj ev)))
      ;; Reg a frame with a small drain-depth so the test runs quickly.
      (rf/reg-frame :drain.test/loop {:drain-depth 8})
      (rf/reg-event-fx :loop-forever
        (fn [_ _]
          {:fx [[:dispatch [:loop-forever]]]}))
      (rf/dispatch-sync [:loop-forever] {:frame :drain.test/loop})
      (rf/unregister-listener! ::depth)
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
      (rf/register-listener! ::depth-2 (fn [ev] (swap! traces conj ev)))
      (rf/reg-frame :drain.test/loop2 {:drain-depth 4})
      (rf/reg-event-fx :loop2
        (fn [_ _]
          {:fx [[:dispatch [:loop2]]]}))
      (rf/dispatch-sync [:loop2] {:frame :drain.test/loop2})
      (rf/unregister-listener! ::depth-2)
      (let [router (:router (frame/frame :drain.test/loop2))]
        (is (zero? (count (:queue @router)))
            "the router queue is drained empty after the depth-exceeded abort")
        (is (false? (:scheduled? @router))
            ":scheduled? is reset so future dispatches re-engage drain")))))

(deftest drain-depth-exceeded-rolls-back-app-db
  ;; Spec 002 §Run-to-completion §Rules rule 3 (atomic rollback): when
  ;; the drain trips its depth limit, the runtime restores app-db to
  ;; its pre-drain snapshot before emitting :rf.error/drain-depth-
  ;; exceeded. Partial writes from the failed cascade are reverted —
  ;; no caller observes a state no single completed event would
  ;; produce. Discovered via rf2-8hi7.
  (testing "a 4-deep chain that overflows leaves :db at the pre-drain value"
    ;; Frame seeded via :on-create so the pre-drain snapshot is non-empty.
    ;; If rollback regressed (partial writes preserved), :step would
    ;; advance to whatever the bound caught — the assertion would fail
    ;; loudly with the depth count so the regression is obvious.
    (rf/reg-event-db :seed/init
      (fn [_ _] {:step :pre-drain :counter 0}))
    (rf/reg-frame :drain.rollback/main
      {:on-create   [:seed/init]
       :drain-depth 4})
    (let [traces (atom [])]
      (rf/register-listener! ::rollback (fn [ev] (swap! traces conj ev)))
      ;; A handler that COMMITS a :db write (advancing :step and bumping
      ;; :counter) AND re-dispatches itself. Without rollback, the final
      ;; :db carries the partial writes from depth-1..depth-4. With
      ;; rollback, the final :db is exactly what :seed/init produced.
      (rf/reg-event-fx :overflow
        (fn [{:keys [db]} _]
          {:db {:step :mid-drain :counter (inc (:counter db 0))}
           :fx [[:dispatch [:overflow]]]}))
      (rf/dispatch-sync [:overflow] {:frame :drain.rollback/main})
      (rf/unregister-listener! ::rollback)
      ;; Atomic rollback: app-db is exactly what :seed/init produced.
      (is (= {:step :pre-drain :counter 0}
             (rf/get-frame-db :drain.rollback/main))
          "app-db is restored to the pre-drain snapshot; partial writes are reverted")
      ;; Sanity: the depth-exceeded trace did fire and tags :rollback? true.
      (let [hit (some (fn [ev]
                        (when (= :rf.error/drain-depth-exceeded
                                 (:operation ev))
                          ev))
                      @traces)]
        (is (some? hit) "drain-depth-exceeded trace was emitted")
        (when hit
          (is (true? (get-in hit [:tags :rollback?]))
              ":rollback? true tag flags the atomic rollback per Spec 002")))))

  (testing "rollback target is the snapshot at drain entry, not :on-create"
    ;; A drain that has already settled cleanly once and then is re-
    ;; engaged with a self-dispatching event must roll back to the
    ;; *post-first-drain* state, not all the way back to :on-create.
    ;; This pins the contract: rollback boundary is the drain, not the
    ;; lifetime of the frame.
    (rf/reg-event-db :seed2/init (fn [_ _] {:phase :seeded}))
    (rf/reg-frame :drain.rollback/two
      {:on-create   [:seed2/init]
       :drain-depth 3})
    ;; First drain: a clean settle that mutates :phase. After this, the
    ;; pre-drain snapshot for any FUTURE drain is {:phase :first-settled}.
    (rf/reg-event-db :advance (fn [db _] (assoc db :phase :first-settled)))
    (rf/dispatch-sync [:advance] {:frame :drain.rollback/two})
    (is (= {:phase :first-settled}
           (rf/get-frame-db :drain.rollback/two))
        "first drain settled cleanly; that's the new baseline")
    ;; Second drain: trip the depth limit. Rollback target is the
    ;; baseline above, not :seed2/init's output.
    (rf/reg-event-fx :overflow2
      (fn [_ _]
        {:db {:phase :poisoned}
         :fx [[:dispatch [:overflow2]]]}))
    (rf/dispatch-sync [:overflow2] {:frame :drain.rollback/two})
    (is (= {:phase :first-settled}
           (rf/get-frame-db :drain.rollback/two))
        "rollback restored the *drain-entry* snapshot, not :on-create's output")))

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
      (rf/register-listener! ::dsih-direct (fn [ev] (swap! traces conj ev)))
      (rf/reg-event-db :leaf (fn [db _] (assoc db :leaf? true)))
      (rf/reg-event-fx :nested-direct
        (fn [_ _]
          (rf/dispatch-sync [:leaf])
          {}))
      (rf/dispatch-sync [:nested-direct])
      (rf/unregister-listener! ::dsih-direct)
      (is (some (fn [ev]
                  (and (= :rf.error/dispatch-sync-in-handler (:operation ev))
                       (= :error (:op-type ev))
                       (= :no-recovery (:recovery ev))))
                @traces)
          "expected :rf.error/dispatch-sync-in-handler with :no-recovery")))

  (testing "calling dispatch-sync TRANSITIVELY through a user fx is also caught"
    ;; Some fx handlers naively call dispatch-sync to chain another event.
    ;; The drain still flags the call site even though it's one frame
    ;; below the original handler — :in-drain? on the router is the
    ;; primary guard, not the call-stack depth.
    (let [traces (atom [])]
      (rf/register-listener! ::dsih-fx (fn [ev] (swap! traces conj ev)))
      (rf/reg-event-db :leaf2 (fn [db _] (assoc db :leaf2? true)))
      (rf/reg-fx :user.fx/sync-dispatch
        {:platforms #{:server :client}}
        (fn [_ ev]
          ;; This is the wrong way to chain — should be :dispatch in the
          ;; effects map. The router must still emit the structured
          ;; error so the bug is observable.
          (rf/dispatch-sync ev)))
      (rf/reg-event-fx :nested-via-fx
        (fn [_ _]
          {:fx [[:user.fx/sync-dispatch [:leaf2]]]}))
      (rf/dispatch-sync [:nested-via-fx])
      (rf/unregister-listener! ::dsih-fx)
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
      (rf/reg-event-fx :seed
        (fn [_ _]
          (swap! order conj :seed)
          ;; Two fx-side dispatches plus a :db update. All must drain
          ;; before dispatch-sync returns.
          {:db {:n 0}
           :fx [[:dispatch [:bump]]
                [:dispatch [:bump]]]}))
      (rf/reg-event-db :bump
        (fn [db _]
          (swap! order conj :bump)
          (update db :n inc)))
      (rf/dispatch-sync [:seed])
      (is (= [:seed :bump :bump] @order)
          "both :fx-side :dispatch events ran inside the same dispatch-sync cycle")
      (is (= 2 (:n (rf/get-frame-db :rf/default)))
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
      (rf/reg-event-db :outside-async
        (fn [db _]
          (swap! order conj :outside-async)
          (deliver done :ok)
          (assoc db :outside? true)))
      (rf/reg-event-db :sync-only
        (fn [db _]
          (swap! order conj :sync-only)
          db))
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
      (is (true? (:outside? (rf/get-frame-db :rf/default)))
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
      (rf/reg-event-db :A/work
        (fn [db _]
          (swap! order conj :A-start)
          ;; Dispatch a cross-frame event — this hits frame B's queue
          ;; via the async path. It must not run as part of A's drain.
          (rf/dispatch [:B/work] {:frame :drain.test/B})
          (swap! order conj :A-end)
          (assoc db :a-ran? true)))
      (rf/reg-event-db :B/work
        (fn [db _]
          (swap! order conj :B)
          (deliver b-done :ok)
          (assoc db :b-ran? true)))
      (rf/dispatch-sync [:A/work] {:frame :drain.test/A})
      ;; The moment dispatch-sync returns, A's cascade has settled. B's
      ;; cascade may still be in flight on the executor.
      (is (= [:A-start :A-end] (vec (filter #{:A-start :A-end} @order)))
          "A's handler ran end-to-end without B interleaving inside it")
      (is (= :ok (deref b-done 2000 :timeout))
          "B's drain eventually fires on the executor")
      (is (true? (:a-ran? (rf/get-frame-db :drain.test/A)))
          "A's :db commit landed in A's app-db only")
      (is (nil? (:b-ran? (rf/get-frame-db :drain.test/A)))
          "B's :db commit did NOT spill into A's app-db")
      (is (true? (:b-ran? (rf/get-frame-db :drain.test/B)))
          "B's :db commit landed in B's app-db")
      (is (nil? (:a-ran? (rf/get-frame-db :drain.test/B)))
          "A's :db commit did NOT spill into B's app-db")))

  (testing "two interleaved dispatch-sync calls keep their queues separate"
    ;; This pins the per-frame router contract: each frame has its own
    ;; queue and :scheduled?/:in-drain? flags.
    (rf/reg-frame :drain.test/X {:doc "X"})
    (rf/reg-frame :drain.test/Y {:doc "Y"})
    (rf/reg-event-db :tick (fn [db _] (update db :n (fnil inc 0))))
    (rf/dispatch-sync [:tick] {:frame :drain.test/X})
    (rf/dispatch-sync [:tick] {:frame :drain.test/Y})
    (rf/dispatch-sync [:tick] {:frame :drain.test/X})
    (is (= 2 (:n (rf/get-frame-db :drain.test/X))))
    (is (= 1 (:n (rf/get-frame-db :drain.test/Y))))))

;; ---- rf2-6guf: drain-depth-exceeded preserves OTHER frames' app-db --------
;;
;; Per test-coverage-review-2026-05-12 P3-25: the existing
;; `drain-depth-exceeded-rolls-back-app-db` only exercises :rf/default. The
;; broader contract is "rollback is scoped to the FRAME that exceeded the
;; depth — other frames' app-dbs are untouched, and the depth-exceeded
;; trace carries the right frame id".

(deftest drain-depth-exceeded-isolated-to-the-overflowing-frame
  (testing "depth-exceed on frame :B rolls back :B's app-db only; :A's
            app-db is byte-identical to its pre-dispatch state; the
            depth-exceeded trace carries :B"
    (rf/reg-event-db :seed/A (fn [_ _] {:where :A :counter 0 :marker :pristine}))
    (rf/reg-event-db :seed/B (fn [_ _] {:where :B :counter 0 :marker :pristine}))
    (rf/reg-frame :drain.iso/A
                  {:on-create   [:seed/A]
                   :drain-depth 50})
    ;; Frame :B has the tight drain-depth so :B's loop event trips it.
    (rf/reg-frame :drain.iso/B
                  {:on-create   [:seed/B]
                   :drain-depth 4})

    ;; Capture :A's pre-dispatch state — this is what we'll compare to.
    (let [a-pre  (rf/get-frame-db :drain.iso/A)
          traces (atom [])]
      (rf/register-listener! ::iso (fn [ev] (swap! traces conj ev)))

      ;; Register a loop event under :B that infinitely self-dispatches.
      ;; Each iteration writes to :B's :db, so we can see whether the
      ;; rollback actually fires.
      (rf/reg-event-fx :loop/B
        (fn [{:keys [db]} _]
          {:db {:where :B
                :counter (inc (:counter db 0))
                :marker  :mid-cascade}
           :fx [[:dispatch [:loop/B]]]}))

      ;; Dispatch the loop on :B. This trips the drain-depth limit; :B's
      ;; app-db rolls back to the pre-drain snapshot.
      (rf/dispatch-sync [:loop/B] {:frame :drain.iso/B})

      ;; --- (a) :B's app-db is rolled back to its :on-create state.
      (is (= {:where :B :counter 0 :marker :pristine}
             (rf/get-frame-db :drain.iso/B))
          ":B's app-db is rolled back to the pre-drain snapshot")

      ;; --- (b) :A's app-db is byte-identical to its pre-dispatch state.
      (is (= a-pre (rf/get-frame-db :drain.iso/A))
          ":A's app-db is untouched (value-equal to pre-dispatch)")
      (is (= {:where :A :counter 0 :marker :pristine}
             (rf/get-frame-db :drain.iso/A))
          ":A's app-db remains exactly its :on-create state")

      ;; --- (c) the depth-exceeded trace carries :B, not :A.
      (let [hit (some (fn [ev]
                        (when (= :rf.error/drain-depth-exceeded
                                 (:operation ev))
                          ev))
                      @traces)]
        (is (some? hit) "drain-depth-exceeded trace was emitted")
        (is (= :drain.iso/B (get-in hit [:tags :frame]))
            "the trace's :frame tag is :B (the overflowing frame), not :A")
        (is (true? (get-in hit [:tags :rollback?]))
            ":rollback? true tag flags the atomic rollback"))

      (rf/unregister-listener! ::iso))))

