(ns re-frame.flows-lifecycle-drain-race-test
  "JVM regression coverage for the two flow-lifecycle vs drain interleaving
  races.

  Both surfaces are window races between a flow LIFECYCLE op (`clear-flow` /
  `reg-flow` replacement) and a concurrent EVENT DRAIN on the SAME frame. The
  lifecycle op runs its multi-step registry+app-db mutation under
  `frame/call-serialized-with-drain!`, which takes the frame's `:drain-lock`
  (the single-drainer serialization primitive) so the mutation is atomic
  w.r.t. any concurrent drain — a drain can NEVER observe a half-applied
  lifecycle change. Without that serialization a drain could interleave
  between steps:

    SURFACE 1 (clear-flow stale output). `clear-flow` vacates the output
    `:output-path` then removes the flow from the per-frame registry. A
    same-frame drain interleaving between those two steps would see the
    STILL-registered flow, recompute it, and re-commit the output `clear-flow`
    had just vacated — leaving stale derived state in app-db (violating Spec
    013's clear-flow cleanup contract). Serialization makes that window
    unreachable.

    SURFACE 2 (re-registration skips recompute). A same-frame `reg-flow`
    REPLACEMENT publishes the new flow into `flows` (visible to a drain) in
    the `swap!`, and the stale-`last-inputs` invalidation fires via
    `registrar/register!` → `invalidate-flow-on-replace!`. A drain
    interleaving after the new flow was visible but before `last-inputs` was
    dropped would see the new flow with the OLD input cache and skip recompute
    on `=`-equal inputs, keeping the stale output (violating Spec 013's
    re-registration contract that the new flow re-evaluates on the next event
    regardless of input equality). Serializing the whole publish→invalidate
    sequence makes that window unreachable too.

  (Reentrant: a mid-drain `:rf.fx/clear-flow` / `:rf.fx/reg-flow` runs
  directly inside the single-drainer window rather than self-deadlocking on
  the lock.)

  TEST STRATEGY. Each test reproduces the dangerous interleaving
  DETERMINISTICALLY by pausing the lifecycle op mid-region (via `with-redefs`
  of a private fn the op calls — NO production test-seam) while a DIFFERENT
  thread attempts the racing dispatch. The lifecycle op holds the drain-lock
  across the pause, so the racing dispatch BLOCKS on the lock until the op
  fully completes; the post-condition (clean app-db / fresh recompute) then
  holds.

  CLJS is single-threaded; this race is JVM-only by construction."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.flows.registry :as registry]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; ---- per-test reset (mirrors flows_concurrency_stress_test.clj) -----------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  ;; EP-0002: reg-flow is context-required frame-local — an ambient call
  ;; under no scope raises :rf.error/no-frame-context. Pin :rf/default (an
  ;; ordinary frame) as the established scope for the body.
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- await! [^CountDownLatch latch where]
  (is (.await latch 30 TimeUnit/SECONDS)
      (str "latch '" where "' did not trip within 30s — a deadlock or a "
           "racing thread that never reached the rendezvous")))

;; ---------------------------------------------------------------------------
;; Surface 1 — clear-flow must not leave stale output when a drain races the
;; vacate→deregister window.
;; ---------------------------------------------------------------------------

(deftest clear-flow-output-stays-vacated-when-a-drain-races-the-window
  ;; Pause `clear-flow` AFTER it has vacated the output path, launch an
  ;; input-changing dispatch on another thread, then resume `clear-flow` and
  ;; assert that after it returns the registry / last-inputs rows are gone AND
  ;; the output path is absent.
  ;;
  ;; The pause is injected by redefining the private `vacate-output-path!`
  ;; so it does its real work, signals "vacated", then blocks on a release
  ;; latch — leaving `clear-flow` parked in the window while still HOLDING the
  ;; drain-lock. The racing dispatch on thread B therefore cannot acquire the
  ;; lock and run until `clear-flow` completes; it observes no flow and never
  ;; re-commits the vacated output.
  (testing "a same-frame dispatch racing the vacate window cannot re-commit cleared output"
    (let [;; `vacate-output-path!` is private to the registry ns; reach it
          ;; through the var-quote reader (privacy gates bare-symbol
          ;; resolution, not `#'`).
          vacate-var  #'re-frame.flows.registry/vacate-output-path!
          orig-vacate @vacate-var
          vacated     (CountDownLatch. 1)
          raced       (CountDownLatch. 1)
          release     (CountDownLatch. 1)]
      (rf/reg-event :seed       (fn [{:keys [db]} _] {:db {:n 5}}))
      (rf/reg-event :bump-input (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
      (rf/reg-flow {:id     :doubled
                    :inputs [[:n]]
                    :derive (fn [n] (* 2 (or n 0)))
                    :output-path   [:out]})
      (rf/dispatch-sync [:seed])
      (is (= 10 (:out (rf/app-db-value :rf/default)))
          "precondition: the flow materialised :out = 2 × :n")

      ;; `with-redefs` can't bind a PRIVATE var from another ns (it expands
      ;; to `(var sym)`, which enforces privacy). Patch the var root directly
      ;; via the var-quote (which reads private vars) and restore in finally.
      (alter-var-root vacate-var
                      (constantly
                        (fn [frame-id path]
                          ;; Real work first (vacate, THEN the registry removal
                          ;; clear-flow does next), then park in the window
                          ;; with the drain-lock held.
                          (orig-vacate frame-id path)
                          (.countDown vacated)
                          (await! release "clear-flow release"))))
      (try
        (let [clearer
              (future
                (flows/clear-flow :doubled {:frame :rf/default}))
              ;; Thread B: once clear-flow has vacated (and is parked under
              ;; the drain-lock), fire an input-changing event. It
              ;; spin-CAS-waits on the drain-lock until clear-flow finishes,
              ;; then drains against an empty flow registry and leaves :out
              ;; absent (without serialization it would slip into the window,
              ;; recompute the still-registered flow, and re-commit :out).
              racer
              (future
                (await! vacated "vacate")
                (rf/dispatch-sync [:bump-input 99] {:frame :rf/default})
                (.countDown raced))]
          ;; Give the racer a real chance to RUN its dispatch inside the
          ;; vacate window before releasing clear-flow. Bounded wait, not a
          ;; hard rendezvous: the racer BLOCKS on the drain-lock (held by the
          ;; parked clear-flow), so `raced` never trips here and we fall
          ;; through after the timeout to release clear-flow — the racer then
          ;; drains against an empty registry. (If the window were observable,
          ;; the racer would complete its drain and re-commit :out, tripping
          ;; `raced` fast, and the final assert below would catch the stale
          ;; output.) Either way we must not hang.
          (await! vacated "vacate (main)")
          (.await raced 3 TimeUnit/SECONDS)
          (.countDown release)
          (is (not= ::timeout (deref clearer 30000 ::timeout))
              "clear-flow returned within 30s")
          (is (not= ::timeout (deref racer 30000 ::timeout))
              "the racing dispatch completed within 30s"))
        (finally
          (alter-var-root vacate-var (constantly orig-vacate))))

      ;; --- The load-bearing post-conditions (bead acceptance) -----------
      (is (not (contains? (get (flows/flows-snapshot) :rf/default) :doubled))
          "registry row gone: clear-flow removed :doubled from the per-frame registry")
      (is (not (contains? (flows/last-inputs-snapshot) :doubled))
          "last-inputs row gone: clear-flow dropped the dirty-check row")
      (is (not (contains? (rf/app-db-value :rf/default) :out))
          (str "output path ABSENT after clear-flow returned — the racing "
               "drain did NOT re-commit the vacated :out. Pre-fix it would "
               "hold the stale value " (:out (rf/app-db-value :rf/default))))
      (is (nil? (registrar/lookup :flow :doubled))
          "the :flow registrar slot was vacated (last owner released the id)"))))

;; ---------------------------------------------------------------------------
;; Surface 2 — a re-registration must re-evaluate on the next drain even when
;; a drain races the publish→invalidate window.
;; ---------------------------------------------------------------------------

(deftest re-registration-recomputes-when-a-drain-races-the-invalidate-window
  ;; Pause the `reg-flow` REPLACEMENT after it has published the new flow into
  ;; `flows` but BEFORE `registrar/register!` fires the
  ;; `invalidate-flow-on-replace!` hook that drops the stale `last-inputs`
  ;; row, dispatch an UNRELATED event on another thread, then resume and
  ;; assert the first post-replacement drain materialises the NEW output.
  ;;
  ;; The pause is injected by redefining `registrar/register!` to signal
  ;; "published" then block on a release latch on the `:flow` kind only — so
  ;; reg-flow parks in the window while still HOLDING the drain-lock. The
  ;; racing dispatch on thread B cannot acquire the lock and run until
  ;; reg-flow completes its invalidation; the drain then sees the new flow
  ;; with a DROPPED last-inputs row and recomputes regardless of input
  ;; equality.
  (testing "an unrelated dispatch racing the invalidate window still gets a fresh recompute"
    (let [orig-register registrar/register!
          published     (CountDownLatch. 1)
          raced         (CountDownLatch. 1)
          release       (CountDownLatch. 1)]
      (rf/reg-event :seed     (fn [{:keys [db]} _] {:db {:n 5}}))
      (rf/reg-event :unrelated (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
      ;; Original flow: :out = 2 × :n.
      (rf/reg-flow {:id     :scaled
                    :inputs [[:n]]
                    :derive (fn [n] (* 2 (or n 0)))
                    :output-path   [:out]})
      (rf/dispatch-sync [:seed])
      (is (= 10 (:out (rf/app-db-value :rf/default)))
          "precondition: the original flow materialised :out = 2 × :n = 10")

      (with-redefs [registrar/register!
                    (fn [kind id metadata]
                      (if (= kind :flow)
                        (do
                          ;; The new flow is ALREADY published into `flows`
                          ;; by reg-flow's swap! before this call; parking
                          ;; here opens the publish→invalidate window.
                          ;; Signal, block, THEN run the real register!
                          ;; (which fires invalidate-flow-on-replace!).
                          (.countDown published)
                          (await! release "reg-flow release")
                          (orig-register kind id metadata))
                        (orig-register kind id metadata)))]
        (let [;; Thread A: re-register :scaled as :out = 100 × :n. Publishes
              ;; the new flow, then parks at register! under the drain-lock.
              replacer
              (future
                (rf/reg-flow {:id     :scaled
                              :inputs [[:n]]
                              :derive (fn [n] (* 100 (or n 0)))
                              :output-path   [:out]}
                             {:frame :rf/default}))
              ;; Thread B: once the new flow is published (but invalidation
              ;; not yet applied), dispatch an UNRELATED event. Its inputs
              ;; ([:n]) are =-equal to the previous run; it spin-CAS-waits on
              ;; the drain-lock until reg-flow drops the stale last-inputs row,
              ;; then recomputes :out = 500. (Without serialization the drain
              ;; would skip recompute on =-equal inputs and KEEP :out = 10.)
              racer
              (future
                (await! published "publish")
                (rf/dispatch-sync [:unrelated] {:frame :rf/default})
                (.countDown raced))]
          ;; Bounded wait (not a hard rendezvous): the racer BLOCKS on the
          ;; drain-lock held by the parked reg-flow, so `raced` never trips and
          ;; we fall through to release after the timeout. (If the window were
          ;; observable, the racer would drain within it — skipping recompute
          ;; on =-equal inputs, keeping :out = 10 — tripping `raced` fast, and
          ;; the final assert would catch the stale output.) Either way we must
          ;; not hang.
          (await! published "publish (main)")
          (.await raced 3 TimeUnit/SECONDS)
          (.countDown release)
          (is (not= ::timeout (deref replacer 30000 ::timeout))
              "reg-flow replacement returned within 30s")
          (is (not= ::timeout (deref racer 30000 ::timeout))
              "the racing unrelated dispatch completed within 30s")))

      ;; --- The load-bearing post-condition (bead acceptance) ------------
      ;; The first post-replacement drain (the unrelated event) MUST have
      ;; materialised the NEW output. Inputs were =-equal across the
      ;; replacement, so a recompute only happens if the stale last-inputs
      ;; row was invalidated BEFORE the drain could observe the new flow —
      ;; which the serialization guarantees.
      (is (= 500 (:out (rf/app-db-value :rf/default)))
          (str "first post-replacement drain materialised the NEW output "
               "(100 × 5 = 500). Pre-fix the =-equal-inputs skip kept the "
               "stale 10. Got " (:out (rf/app-db-value :rf/default))))
      (is (:touched (rf/app-db-value :rf/default))
          "the unrelated event's own write also landed"))))

;; ---------------------------------------------------------------------------
;; clear-flow's flow lookup + `:output-path` capture happen UNDER the
;; drain-lock, not before it — folded into the serialized region so the whole
;; op runs over the SAME live flow definition. A stale PRE-LOCK read could
;; otherwise race a same-id same-frame `reg-flow` replacement that moves the
;; output to a new `:output-path`, leaving clear-flow vacating the OLD
;; (now-empty) path while the replacement's NEW path stays materialised after
;; the flow is removed from the registry — a stale-derived-state leak
;; (violating Spec 013's clear-flow cleanup contract) plus a misleading
;; `:rf.flow/cleared` for the old path.
;; ---------------------------------------------------------------------------

(deftest clear-flow-lookup-happens-under-the-lock-vs-a-racing-path-change-replacement
  ;; Deterministic reproduction of the stale PRE-LOCK read, using a REAL
  ;; drain as the lock-holder so the reentrancy / materialisation are natural
  ;; (no manual lock-juggling, no reentrancy deadlock).
  ;;
  ;; A replacement event's HANDLER pauses early in the drain — the drain
  ;; already holds the frame's `:drain-lock` and the OLD flow (`:output-path
  ;; [:out-a]`) is still the live definition (the replacement `reg-flow`
  ;; runs LATER, after the handler resumes). While the handler is parked we
  ;; start `clear-flow` on thread B (its PRE-LOCK read, if any, captures the
  ;; OLD `[:out-a]` here) and let B reach the lock-wait. We then resume the
  ;; handler: it `reg-flow`s `:scaled` to `:output-path [:out-b]` (reentrant — the
  ;; drainer already holds the lock) and the drain's flow transform
  ;; MATERIALISES :out-b. The drain completes, releases the lock, and B's
  ;; serialized region finally runs.
  ;;
  ;;   B reads the LIVE flow UNDER the lock — `[:out-b]` — and vacates THAT,
  ;;   so no stale derived state remains. (A stale pre-lock read would vacate
  ;;   the already-empty `[:out-a]` and leave the replacement's materialised
  ;;   `[:out-b]` STALE in app-db.)
  (testing "clear-flow vacates the replacement's NEW path, not the stale pre-lock OLD path"
    (let [in-handler (CountDownLatch. 1) ;; drain parked in handler, OLD flow live
          release    (CountDownLatch. 1)] ;; resume the handler → swap + materialise
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 5}}))
      ;; OLD flow: :out-a = 2 × :n.
      (rf/reg-flow {:id     :scaled
                    :inputs [[:n]]
                    :derive (fn [n] (* 2 (or n 0)))
                    :output-path   [:out-a]})
      (rf/dispatch-sync [:seed])
      (is (= 10 (:out-a (rf/app-db-value :rf/default)))
          "precondition: the OLD flow materialised :out-a = 2 × :n = 10")

      ;; The replacement handler: park early in the drain (lock held, OLD
      ;; flow still live), then on resume re-register :scaled to :out-b. The
      ;; reg-flow runs reentrantly inside the single-drainer window; the
      ;; drain's flow transform then materialises :out-b = 100 × :n = 500.
      ;;
      ;; The handler returns its db UNCHANGED — a PLAIN replacement, NO
      ;; `(dissoc db :out-a)` workaround. The IN-DRAIN `reg-flow`
      ;; `:output-path` move records :out-a as a pending abandoned path; the
      ;; drain's flow transform dissocs it from the pending `:db` BEFORE the
      ;; deferred commit publishes that value, so the old-path value cannot be
      ;; resurrected by the handler's returned db. The `:out-a absent`
      ;; assertion below holding with a plain handler is the load-bearing
      ;; regression proof — a reentrant DIRECT vacate write would be clobbered
      ;; by this deferred commit.
      (rf/reg-event :replace-scaled
                       (fn [{:keys [db]} _]
                         (.countDown in-handler)
                         (await! release "replace-scaled handler release")
                         (rf/reg-flow {:id     :scaled
                                       :inputs [[:n]]
                                       :derive (fn [n] (* 100 (or n 0)))
                                       :output-path   [:out-b]}
                                      {:frame :rf/default})
                         {:db db}))

      (let [;; Thread A: the replacement drain. Parks in the handler holding
            ;; the drain-lock with the OLD flow still live.
            replacer (future (rf/dispatch-sync [:replace-scaled] {:frame :rf/default}))]
        ;; Wait until A's drain is parked in the handler.
        (await! in-handler "in-handler (main)")
        ;; Thread B: clear :scaled. A pre-lock read would capture the OLD
        ;; `[:out-a]` now (A hasn't swapped yet); B then blocks on the
        ;; drain-lock A's drain holds.
        (let [clearer (future (flows/clear-flow :scaled {:frame :rf/default}))]
          ;; B must NOT complete while A holds the lock — it's blocked on the
          ;; drain-lock (bounded wait; B's pre-lock read, if any, is a few
          ;; instructions before the blocking acquire).
          (is (= ::still-blocked (deref clearer 1000 ::still-blocked))
              "clear-flow is blocked on the drain-lock A's drain holds")
          ;; Resume A: it swaps :scaled → :out-b, the flow transform
          ;; materialises :out-b = 500, the drain commits + releases the
          ;; lock. B's serialized region then runs.
          (.countDown release)
          (is (not= ::timeout (deref replacer 30000 ::timeout))
              "the replacement drain completed within 30s")
          (is (not= ::timeout (deref clearer 30000 ::timeout))
              "clear-flow completed within 30s")))

      ;; --- The load-bearing post-conditions (bead acceptance) -----------
      (is (not (contains? (get (flows/flows-snapshot) :rf/default) :scaled))
          "registry row gone: clear-flow removed :scaled")
      (is (not (contains? (flows/last-inputs-snapshot) :scaled))
          "last-inputs row gone")
      (is (nil? (registrar/lookup :flow :scaled))
          "the :flow registrar slot was vacated (last owner released)")
      ;; THE LOAD-BEARING ASSERT: clear-flow vacated the REPLACEMENT's live
      ;; path (:out-b), not the stale pre-lock :out-a. A stale pre-lock read
      ;; would leave :out-b lingering (500) after the flow was removed from
      ;; the registry.
      (is (not (contains? (rf/app-db-value :rf/default) :out-b))
          (str ":out-b ABSENT — clear-flow read the LIVE flow under the lock "
               "and vacated the replacement's new path. Pre-fix it vacated the "
               "stale pre-lock :out-a and left :out-b = "
               (:out-b (rf/app-db-value :rf/default)) " in app-db."))
      (is (not (contains? (rf/app-db-value :rf/default) :out-a))
          ":out-a also absent (the replacement vacated it on the path change)"))))

;; ---------------------------------------------------------------------------
;; An IN-DRAIN same-frame `reg-flow` `:output-path` move must NOT resurrect
;; the OLD output through the deferred `:db` commit.
;; ---------------------------------------------------------------------------

(deftest reg-flow-path-move-in-drain-does-not-resurrect-old-output
  ;; A same-frame `reg-flow` REPLACEMENT that MOVES the output `:output-path`
  ;; from [:out-a] to [:out-b], issued from INSIDE an event handler
  ;; (reentrantly, mid-drain), must vacate [:out-a].
  ;;
  ;; The router runs flows over the PENDING `:db` (the handler's returned
  ;; value, which still carries :out-a) and PUBLISHES that pending value via
  ;; its single DEFERRED commit AFTER the handler returns. A DIRECT app-db
  ;; vacate write during the reentrant `reg-flow` would therefore be
  ;; overwritten by that deferred commit, RESURRECTING :out-a — so the
  ;; framework makes the vacate participate in the pending-`:db` transform the
  ;; commit publishes. The handler here returns its db UNCHANGED (no manual
  ;; `(dissoc db :out-a)`), so the framework vacate is the only thing keeping
  ;; :out-a gone.
  ;;
  ;; Single-threaded — no race, no latch: the path is deterministic on the
  ;; drain thread (the reentrant write then the deferred commit on the same
  ;; thread). The load-bearing post-condition: :out-a is absent and
  ;; :out-b = 500 is present.
  (testing "in-drain reg-flow :output-path move vacates the old path through the deferred commit"
    (rf/reg-event :seed (fn [_ _] {:db {:n 5}}))
    ;; OLD flow: :out-a = 2 × :n.
    (rf/reg-flow {:id     :scaled
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 (or n 0)))
                  :output-path   [:out-a]})
    (rf/dispatch-sync [:seed])
    (is (= 10 (:out-a (rf/app-db-value :rf/default)))
        "precondition: the OLD flow materialised :out-a = 2 × :n = 10")

    ;; A handler that, mid-drain, re-registers :scaled to move its output to
    ;; [:out-b] (100 × :n) and returns its db UNCHANGED — a PLAIN replacement.
    (rf/reg-event :move-scaled
                  (fn [{:keys [db]} _]
                    (rf/reg-flow {:id     :scaled
                                  :inputs [[:n]]
                                  :derive (fn [n] (* 100 (or n 0)))
                                  :output-path   [:out-b]}
                                 {:frame :rf/default})
                    {:db db}))
    (rf/dispatch-sync [:move-scaled] {:frame :rf/default})

    (let [db (rf/app-db-value :rf/default)]
      (is (not (contains? db :out-a))
          (str ":out-a ABSENT — the in-drain :output-path move vacated the old path "
               "through the deferred commit. Pre-fix the direct vacate was "
               "clobbered by the handler's returned :db and :out-a resurrected "
               "as " (:out-a db) "."))
      (is (= 500 (:out-b db))
          ":out-b = 100 × :n = 500 — the moved flow materialised on its new path")
      (is (= [:out-b] (:output-path (get-in (flows/flows-snapshot) [:rf/default :scaled])))
          "the live registry points :scaled at its new path [:out-b]"))))

(deftest reg-flow-path-move-out-of-drain-vacates-directly
  ;; The OUT-of-drain branch: a top-level (not reentrant) `reg-flow`
  ;; `:output-path` move vacates the old path with a DIRECT app-db write —
  ;; there is no pending deferred commit to clobber it. Pins that the
  ;; call-shape split (`frame/in-drain?`) keeps the direct-vacate path intact.
  (testing "out-of-drain reg-flow :output-path move vacates the old path immediately"
    (rf/reg-event :seed (fn [_ _] {:db {:n 5}}))
    (rf/reg-flow {:id     :scaled
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 (or n 0)))
                  :output-path   [:out-a]})
    (rf/dispatch-sync [:seed])
    (is (= 10 (:out-a (rf/app-db-value :rf/default)))
        "precondition: :out-a = 10")

    ;; Top-level re-registration (NOT inside a drain) that moves the path.
    (rf/reg-flow {:id     :scaled
                  :inputs [[:n]]
                  :derive (fn [n] (* 100 (or n 0)))
                  :output-path   [:out-b]}
                 {:frame :rf/default})
    (is (not (contains? (rf/app-db-value :rf/default) :out-a))
        ":out-a vacated immediately by the direct out-of-drain write")

    ;; The new path materialises on the next drain.
    (rf/reg-event :touch (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:touch] {:frame :rf/default})
    (is (= 500 (:out-b (rf/app-db-value :rf/default)))
        ":out-b materialised on the next drain after the move")))
