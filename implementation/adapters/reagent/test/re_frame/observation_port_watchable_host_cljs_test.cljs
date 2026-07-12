(ns re-frame.observation-port-watchable-host-cljs-test
  "rf2-vxgfnd.29 (gap a) — the observation port's per-lease VALUE-MOVEMENT
  watch channel (`make-watch-handler`, `re-frame.substrate.observation`),
  exercised end-to-end on a WATCHABLE host.

  The plain-atom port suites (`re-frame.observation-port-cljs-test`) are
  IDeref-only: their derived sub values are NOT `IWatchable`, so the per-lease
  `add-watch` the port installs never fires and the entire `{:cause :value}`
  fan-out — the delivered-value node-record advance, the lease `:last` update,
  and that path's dev reentrancy guard — runs in no test there (the untested
  half of `acquire!`). This file grafts the SAME port onto the stock Reagent
  adapter, whose derived sub reactions ARE `IWatchable`
  (`reagent.ratom/Reaction`), so an observed value MOVEMENT actually fires the
  port's watch — the real trigger the reactive-tear gates (.40 / .65) depend on.

  THE EAGER-CONSUMER REQUIREMENT (why we stand a `ratom/run!` driver). The port
  watch is a PASSIVE observer: it `add-watch`es the node but does not itself
  keep the (lazy) Reagent reaction on the push path. In production the ViewCell
  render is the ACTIVE consumer that re-derefs the node every flush, so a value
  movement re-runs the node and fires the port's watch. A test with only a
  passive port watch never re-runs the node (a lazy deref pulls once — the same
  methodological point the standard-epochs diamond fixture makes), so each
  fixture stands a `reagent.ratom/run!` driver — an auto-running reaction that
  eagerly derefs the sub, standing in for the mounted ViewCell render. It
  warms + activates the node BEFORE the lease's baseline observe (mirroring
  render-then-commit: no first-run priming notification on the measured lease),
  and keeps it on the push path so the genuine value MOVEMENT fires the watch.

  The push path: a `dispatch-sync` MOVES the sub's value; `reagent.ratom/flush!`
  re-runs the driver (and through it the node), and — the value changed — the
  node notifies the port watch, which advances the node record with the
  DELIVERED value (no recompute, I-5) and fans `{:cause :value …}` to the
  lease's own `on-change`.

  CLJS-only (Reagent is CLJS); ns ends in `-cljs-test` so the consolidated
  shadow-cljs `:node-test` build (`npm run test:cljs`, whose source-paths carry
  `adapters/reagent`) picks it up headlessly — no DOM, no React."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.substrate.observation :as obs]
            [re-frame.test-support :as test-support]))

;; A WATCHABLE host: the stock Reagent adapter's derived sub reactions are
;; `reagent.ratom/Reaction`, which reify `IWatchable`. Default ambient
;; `:rf/default` scope (so `dispatch-sync` / `subscribe` / `resolve-target`
;; target it without an explicit frame), mirroring the diamond fixture.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(def ^:private fid :rf/default)

(defn- lease-state [lease] @(@#'obs/lease-state lease))
(defn- lease-last  [lease] (:last (lease-state lease)))

(defn- node-reaction []
  (:reaction (get @(:sub-cache (frame/frame fid)) [:obs/n])))

(defn- register! []
  (rf/reg-sub :obs/n (fn [db _] (:n db)))
  (rf/reg-event :obs/set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)})))

;; The eager consumer standing in for a mounted ViewCell render: an
;; auto-running reaction that derefs the sub, keeping the node warm + on the
;; push path so a value movement re-runs it (and fires the port's watch).
(defn- make-driver [] (ratom/run! (deref (rf/subscribe [:obs/n]))))

;; ===========================================================================
;; the value-movement watch channel — {:cause :value} end-to-end
;; ===========================================================================

(deftest watchable-host-value-movement-fires-cause-value-end-to-end
  (testing "on a WATCHABLE (Reagent) host, a sub value MOVEMENT fires the
            port's per-lease watch → {:cause :value} with an ADVANCED
            node-version and the lease :last updated to the DELIVERED value —
            the make-watch-handler channel the plain-atom suites cannot reach"
    (register!)
    (rf/dispatch-sync [:obs/set-n 1])
    (let [driver (make-driver)]
      (ratom/flush!)                       ;; settle: node warm + active
      (let [notes  (atom [])
            target (obs/resolve-target {:frame fid :query-v [:obs/n]})
            lease  (obs/acquire! target (fn [ev] (swap! notes conj ev)))]
        (try
          ;; --- preconditions: the port armed the value-movement channel ---
          (is (obs/lease? lease))
          (is (satisfies? IWatchable (node-reaction))
              "the Reagent sub reaction IS IWatchable — the port installed its
               per-lease value-movement watch (on a non-watchable host this
               whole channel is dead, which is the very gap under test)")
          (is (some? (:watch-key (lease-state lease)))
              "acquire! registered a per-lease change watch key on the node")
          (is (empty? @notes)
              "acquire! on a WARM node fans nothing synchronously (I-5
               no-sync-fan-out holds on a watchable host too)")
          (let [v0    (:version (lease-last lease))
                node0 (:node-key (lease-last lease))]
            (is (= 1 (:value (lease-last lease))) "baseline observed value")
            ;; --- MOVE the value; flush the reaction queue (the push) ---
            (rf/dispatch-sync [:obs/set-n 2])
            (ratom/flush!)
            (testing "the value-movement watch fired the {:cause :value} channel"
              (is (pos? (count @notes))
                  "the port's per-lease watch fired on the value movement")
              (is (every? #(= :value (:cause %)) @notes)
                  "every fan-out on this path carries {:cause :value}")
              (let [ev (peek @notes)]
                (is (= target (:target ev)) "the payload carries the lease target")
                (is (= node0 (:node-key ev)) "same node — a MOVEMENT, not a rebuild")
                (is (> (:node-version ev) v0)
                    "the delivered-value node-version ADVANCED past the baseline")
                (is (= (:node-version ev) (:version (lease-last lease)))
                    "the lease :last was advanced to the delivered version")
                (is (= 2 (:value (lease-last lease)))
                    "the lease :last holds the DELIVERED new value (no recompute)")
                (is (int? (:frame-epoch ev)))
                (is (int? (:registry-epoch ev))))))
          (finally
            (obs/release! lease)
            (ratom/dispose! driver)))))))

(deftest watchable-host-idempotent-move-does-not-fan-cause-value
  (testing "a commit that leaves the sub value UNMOVED does not fire the
            {:cause :value} channel — the reaction's `=` notify gate (and the
            node-record's rf= version hold) mean an equal re-commit is silent"
    (register!)
    (rf/dispatch-sync [:obs/set-n 7])
    (let [driver (make-driver)]
      (ratom/flush!)
      (let [notes  (atom [])
            target (obs/resolve-target {:frame fid :query-v [:obs/n]})
            lease  (obs/acquire! target (fn [ev] (swap! notes conj ev)))]
        (try
          (is (empty? @notes) "acquire! on a WARM node is silent")
          (let [v0 (:version (lease-last lease))]
            ;; Re-commit the SAME value: the node re-runs to an equal value, so
            ;; no watch notification and no version advance.
            (rf/dispatch-sync [:obs/set-n 7])
            (ratom/flush!)
            (is (empty? @notes)
                "an unmoved value fans nothing on the :value channel")
            (is (= v0 (:version (lease-last lease)))
                "the node-version did not advance for an equal re-commit"))
          (finally
            (obs/release! lease)
            (ratom/dispose! driver)))))))

;; ===========================================================================
;; the reentrancy guard on the value-movement fan-out
;; ===========================================================================

(deftest watchable-host-value-fan-out-holds-the-reentrancy-guard
  (testing "an on-change that mutates graph ownership (release!) from INSIDE
            the {:cause :value} fan-out trips the dev reentrancy guard —
            :rf.error/reentrant-graph-op — the make-watch-handler-path leg of
            the guard the plain-atom suites cannot reach"
    (register!)
    (rf/dispatch-sync [:obs/set-n 1])
    (let [driver (make-driver)]
      (ratom/flush!)
      (let [target  (obs/resolve-target {:frame fid :query-v [:obs/n]})
            outcome (atom :guard-not-fired)
            ;; The on-change attempts a FORBIDDEN reentrant self-release from
            ;; inside the value fan-out; the guard must throw before any
            ;; teardown, so the lease stays intact and `finally` is a clean op.
            lease   (atom nil)]
        (reset! lease
          (obs/acquire! target
            (fn [_ev]
              (reset! outcome
                (try (obs/release! @lease) :released-no-throw
                     (catch :default e (:rf.error/id (ex-data e))))))))
        (try
          ;; MOVE the value so the measured lease's watch fires on a warm node.
          (rf/dispatch-sync [:obs/set-n 2])
          (ratom/flush!)
          (is (= :rf.error/reentrant-graph-op @outcome)
              "release! from inside the value-movement fan-out threw the dev
               reentrant-graph-op assert (fan-out! bound *in-owner-fan-out?*)")
          (is (= :live (:status (lease-state @lease)))
              "the guard threw BEFORE any teardown — the lease is untouched")
          (finally
            (obs/release! @lease)
            (ratom/dispose! driver)))))))
