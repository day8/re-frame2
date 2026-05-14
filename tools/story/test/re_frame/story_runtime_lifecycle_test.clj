(ns re-frame.story-runtime-lifecycle-test
  "JVM tests closing the docs-promised gap on `watch-variant` /
  `destroy-variant!` / lifecycle edges + teardown semantics.

  Spec coverage (rf2-ub1n4): `tools/story/spec/002-Runtime.md` §
  Programmatic API (watch-variant, unwatch, destroy-variant!), § Per-
  variant frame allocation (unmount path), § Lifecycle state machine.

  The existing `re-frame.story-runtime-test` covers the happy-path
  state transitions + assertion accretion + decorator composition.
  This namespace targets the lifecycle EDGES and TEARDOWN guarantees
  that the browser smoke does not exercise:

  - **Watcher unsubscribe.** The 0-arity fn `watch-variant` returns
    must drop *only* the caller's callback — peer watchers stay live.
  - **`destroy-variant!` clears watchers.** The watcher table is per-
    frame; tearing the frame down must release every registered
    callback so callers don't leak across runs.
  - **Multiple watchers see every transition.** Two concurrent callers
    each receive the full transition sequence.
  - **A throwing watcher does NOT poison peers.** The fire-watchers
    loop catches per-callback exceptions; one bad callback can't
    starve the others.
  - **`destroy-variant!` is idempotent.** Calling it twice in a row,
    or on an unregistered variant, must not throw.
  - **`destroy-variant!` clears the variant frame from the registry.**
    A subsequent `variant-frames` call must not list the destroyed id.
  - **Lifecycle reaches `:ready` after `destroy + run` cycle.** The
    Stage 4 UI shell re-runs variants in place; teardown then
    re-allocation must leave the lifecycle in `:ready` without
    requiring an explicit `reset-watchers!`.

  Per IMPL-SPEC §5.1 the caller (UI shell / test fixture) owns
  teardown — these tests pin the contract the caller relies on."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core            :as rf]
            [re-frame.frame           :as frame]
            [re-frame.machines        :as machines]
            [re-frame.registrar       :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story           :as story]
            [re-frame.story.async     :as async]
            [re-frame.story.config    :as config]
            [re-frame.story.frames    :as frames]
            [re-frame.story.loaders   :as loaders]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [t]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (t))

(use-fixtures :each reset-all)

;; ===========================================================================
;; WATCH-VARIANT — unsubscribe semantics
;; ===========================================================================

(deftest unsubscribe-drops-only-the-caller-callback
  (testing "the 0-arity unsubscribe returned by watch-variant drops only
            the caller's callback; peer watchers stay live"
    (story/reg-variant :story.watch.solo/v {:events []})
    (let [seen-a      (atom [])
          seen-b      (atom [])
          unsub-a     (story/watch-variant :story.watch.solo/v
                        (fn [t] (swap! seen-a conj (:to t))))
          _unsub-b    (story/watch-variant :story.watch.solo/v
                        (fn [t] (swap! seen-b conj (:to t))))
          decs        (story/resolve-decorators :story.watch.solo/v)]
      ;; Drop A; only B should fire.
      (unsub-a)
      (frames/allocate! :story.watch.solo/v decs)
      (loaders/start-loaders! :story.watch.solo/v)
      (loaders/finish-loaders! :story.watch.solo/v)
      (is (= []                                @seen-a)
          "A was unsubscribed before allocation — its log stays empty")
      (is (= [:mounting :loading :ready]        @seen-b)
          "B saw every transition")
      (frames/destroy! :story.watch.solo/v))))

(deftest multiple-watchers-each-see-every-transition
  (testing "two registered watchers each see the full transition sequence"
    (story/reg-variant :story.watch.multi/v {:events []})
    (let [seen-a  (atom [])
          seen-b  (atom [])]
      (story/watch-variant :story.watch.multi/v
        (fn [t] (swap! seen-a conj [(:from t) (:to t)])))
      (story/watch-variant :story.watch.multi/v
        (fn [t] (swap! seen-b conj [(:from t) (:to t)])))
      (frames/allocate! :story.watch.multi/v (story/resolve-decorators :story.watch.multi/v))
      (loaders/start-loaders! :story.watch.multi/v)
      (loaders/finish-loaders! :story.watch.multi/v)
      (is (= [[:pre-mount :mounting] [:mounting :loading] [:loading :ready]]
             @seen-a))
      (is (= @seen-a @seen-b)
          "concurrent watchers see identical transition sequences")
      (frames/destroy! :story.watch.multi/v))))

(deftest throwing-watcher-does-not-starve-peers
  (testing "a watcher callback that throws does NOT starve peer watchers —
            the per-callback try/catch in fire-watchers! keeps the loop alive
            so a misbehaving subscriber can't break the rest"
    (story/reg-variant :story.watch.boom/v {:events []})
    (let [peer-seen (atom [])]
      (story/watch-variant :story.watch.boom/v
        (fn [_t] (throw (ex-info "boom" {:why :test}))))
      (story/watch-variant :story.watch.boom/v
        (fn [t] (swap! peer-seen conj (:to t))))
      (frames/allocate! :story.watch.boom/v (story/resolve-decorators :story.watch.boom/v))
      (loaders/start-loaders! :story.watch.boom/v)
      (loaders/finish-loaders! :story.watch.boom/v)
      (is (= [:mounting :loading :ready] @peer-seen)
          "the peer watcher received every transition despite the
           throwing watcher registered ahead of it")
      (frames/destroy! :story.watch.boom/v))))

;; ===========================================================================
;; DESTROY-VARIANT! — teardown contract
;; ===========================================================================

(deftest destroy-variant-clears-the-watcher-table-for-the-frame
  (testing "destroy-variant! releases every watcher registered against the
            variant's frame — a subsequent allocate! does not see stale
            watchers carry over from the previous run"
    (story/reg-variant :story.destroy.watchers/v {:events []})
    (let [seen (atom [])]
      (story/watch-variant :story.destroy.watchers/v
        (fn [t] (swap! seen conj (:to t))))
      (frames/allocate! :story.destroy.watchers/v
                        (story/resolve-decorators :story.destroy.watchers/v))
      (loaders/start-loaders! :story.destroy.watchers/v)
      (loaders/finish-loaders! :story.destroy.watchers/v)
      (let [before-destroy (count @seen)]
        (story/destroy-variant! :story.destroy.watchers/v)
        ;; Re-allocate; the previous watcher must NOT fire on this fresh run.
        (frames/allocate! :story.destroy.watchers/v
                          (story/resolve-decorators :story.destroy.watchers/v))
        (loaders/start-loaders! :story.destroy.watchers/v)
        (loaders/finish-loaders! :story.destroy.watchers/v)
        (is (= before-destroy (count @seen))
            "destroyed-frame watchers do not fire on the next run")
        (frames/destroy! :story.destroy.watchers/v)))))

(deftest destroy-variant-removes-from-variant-frames
  (testing "after destroy-variant! the variant id no longer appears in
            (story/variant-frames) — the registry is in step with the runtime"
    (rf/reg-event-db :test/nothing (fn [db _] db))
    (story/reg-variant :story.destroy.list/v
      {:events [[:test/nothing]]})
    (let [_ (async/deref-blocking (story/run-variant :story.destroy.list/v) 5000)]
      (is (contains? (story/variant-frames) :story.destroy.list/v)
          "the running variant is listed before destroy")
      (story/destroy-variant! :story.destroy.list/v)
      (is (not (contains? (story/variant-frames) :story.destroy.list/v))
          "the destroyed variant is gone from the listing"))))

(deftest destroy-variant-idempotent-on-running-frame
  (testing "calling destroy-variant! twice in a row does not throw"
    (rf/reg-event-db :test/nothing (fn [db _] db))
    (story/reg-variant :story.destroy.twice/v
      {:events [[:test/nothing]]})
    (async/deref-blocking (story/run-variant :story.destroy.twice/v) 5000)
    (is (nil? (story/destroy-variant! :story.destroy.twice/v)))
    (is (nil? (story/destroy-variant! :story.destroy.twice/v))
        "second destroy is a no-op (returns nil, no throw)")))

(deftest destroy-variant-on-unallocated-id-does-not-throw
  (testing "destroy-variant! on a never-allocated id is a no-op"
    (is (nil? (story/destroy-variant! :story.never/allocated))
        "no frame, no watchers, no assertion accumulators — nothing to do")))

(deftest run-then-destroy-then-run-cycles-cleanly
  (testing "destroy + re-run leaves the lifecycle in :ready — the Stage 4
            UI shell relies on this for the 'reset' button affordance"
    (rf/reg-event-db :test/inc (fn [db _] (update db :n (fnil inc 0))))
    (story/reg-variant :story.cycle/v
      {:events [[:test/inc]]})
    ;; First run.
    (let [r1 (async/deref-blocking (story/run-variant :story.cycle/v) 5000)]
      (is (= :ready (:lifecycle r1)))
      (is (= 1 (:n (:app-db r1)))))
    ;; Tear down + re-run via destroy + run-variant (cycle the Stage 4
    ;; reset button takes when re-running fully from scratch).
    (story/destroy-variant! :story.cycle/v)
    (let [r2 (async/deref-blocking (story/run-variant :story.cycle/v) 5000)]
      (is (= :ready (:lifecycle r2))
          "second run lands :ready cleanly — no residual lifecycle state")
      (is (= 1 (:n (:app-db r2)))
          "fresh app-db; counter starts from 0 then ticks to 1"))
    (story/destroy-variant! :story.cycle/v)))

(deftest watch-variant-during-run-receives-finalising-transitions
  (testing "a watcher registered between phases sees the remaining transitions
            — the watcher table is consulted on every fire, not snapshotted
            at allocate-time"
    (story/reg-variant :story.watch.late/v {:events []})
    (let [seen (atom [])
          decs (story/resolve-decorators :story.watch.late/v)]
      ;; Allocate first — :pre-mount → :mounting transition fires here.
      (frames/allocate! :story.watch.late/v decs)
      ;; Now register the watcher. It missed the first transition.
      (story/watch-variant :story.watch.late/v
        (fn [t] (swap! seen conj [(:from t) (:to t)])))
      (loaders/start-loaders! :story.watch.late/v)
      (loaders/finish-loaders! :story.watch.late/v)
      (is (= [[:mounting :loading] [:loading :ready]] @seen)
          "late-registered watcher caught the two transitions after registration")
      (frames/destroy! :story.watch.late/v))))
