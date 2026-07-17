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
  `adapters/reagent`) picks it up headlessly — no DOM, no React.

  rf2-r09qj (extension) — NaN moves NaN-STABLY through the PUBLIC acquire and
  the ViewCell. A NaN→NaN recompute is the adversarial case for this channel:
  clojure/cljs `=` (and Reagent's `=`-gated reaction-notify) treat `NaN ≠ NaN`,
  so a NaN→NaN recompute genuinely FIRES the host watch — yet the movement law
  (`node-value=` / `eq/rf=`, NaN self-equal on both hosts) is NO movement, so
  the port must fan NO `:cause :value` note, advance NO node version, dirty NO
  ViewCell, and drive NO render. A naive raw `not=` at the make-watch-handler
  seam would spuriously fan a phantom movement against a stable node. These
  proofs (a) stand an INDEPENDENT sentinel watch to show the host callback
  genuinely ran (non-vacuous), (b) drive the movement through public `acquire!`
  AND an integrated ViewCell (`re-frame.ui.reactive` — colocated on the
  consolidated `:node-test` classpath, which carries both `ui/src` and
  `adapters/reagent`), and (c) retain a real-movement positive control so
  upstream suppression cannot make the assertions vacuous."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.substrate.observation :as obs]
            [re-frame.ui.reactive :as reactive]
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

;; --- rf2-r09qj NaN-stability helpers ---------------------------------------

;; The subscription target-key the ViewCell projects committed leases/values by
;; (`re-frame.ui.reactive/target-key` of a `[:sub fid query]` target).
(defn- tk [q] [:sub fid q])

(defn- nan-num? [x] (and (number? x) (js/isNaN x)))

;; An INDEPENDENT watch on the shared node reaction that counts ONLY NaN→NaN
;; fires. It proves the underlying host callback actually RAN for the no-movement
;; recompute (the port's make-watch-handler is a sibling watch on the same
;; reaction, fired in the same notify sweep) — so a green "no fan-out" assertion
;; cannot be vacuously green because the reaction never fired. Returns the
;; sentinel counter atom and a `remove!` thunk.
(defn- install-nan-sentinel! [reaction]
  (let [hits (atom 0)
        k    (gensym "rf-nan-sentinel")]
    (add-watch reaction k
               (fn [_ _ prev nu]
                 (when (and (nan-num? prev) (nan-num? nu))
                   (swap! hits inc))))
    [hits (fn remove! [] (remove-watch reaction k))]))

;; One ViewCell render+commit over the ambient frame, reading the sub through
;; the reactive host (so the commit's `acquire!` installs the watch-bearing
;; lease — the make-watch-handler value-movement channel under test).
(defn- render+commit! [cell]
  (let [[_ cap] (rf/with-frame fid
                  (reactive/with-capture cell
                    (fn [] (reactive/sub-read ::nan-site [:obs/n]))))]
    (reactive/commit! cell cap))
  cell)

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

;; ===========================================================================
;; rf2-r09qj — NaN moves NaN-STABLY through the PUBLIC acquire path
;; ===========================================================================

(deftest watchable-host-nan-to-nan-fires-watch-but-fans-no-value-movement
  (testing "on a WATCHABLE (Reagent) host, a NaN→NaN recompute FIRES the
            underlying host watch (an independent sentinel proves the callback
            ran) yet the port's node-value= gate emits NO {:cause :value} note
            and advances NO node version — the make-watch-handler NaN-suppression
            proven through PUBLIC acquire!. A raw not= at the seam (NaN≠NaN
            natively) would spuriously fan a phantom value movement."
    (register!)
    (rf/dispatch-sync [:obs/set-n ##NaN])
    (let [driver (make-driver)]
      (ratom/flush!)                         ;; settle: node warm + active at NaN
      (let [notes            (atom [])
            target           (obs/resolve-target {:frame fid :query-v [:obs/n]})
            lease            (obs/acquire! target (fn [ev] (swap! notes conj ev)))
            reaction         (node-reaction)
            [nan-hits nan-rm] (install-nan-sentinel! reaction)]
        (try
          ;; --- preconditions ---
          (is (obs/lease? lease))
          (is (satisfies? IWatchable reaction)
              "the Reagent sub reaction IS IWatchable — the port armed its
               per-lease value-movement watch")
          (is (empty? @notes) "acquire! on a warm NaN node fans nothing")
          (is (nan-num? (:value (lease-last lease))) "baseline observed the host NaN")
          (let [v0 (:version (lease-last lease))]
            ;; --- MOVE NaN→NaN: app-db moves (NaN≠NaN under map =), the reaction
            ;; re-derives NaN, and Reagent's =-gated notify fires (NaN≠NaN). ---
            (rf/dispatch-sync [:obs/set-n ##NaN])
            (ratom/flush!)
            (testing "the host watch fired but the port fanned nothing"
              (is (pos? @nan-hits)
                  "the underlying Reagent host watch FIRED for NaN→NaN — the
                   port's make-watch-handler callback genuinely ran (non-vacuous)")
              (is (empty? @notes)
                  "NaN→NaN emitted NO {:cause :value} note — node-value= suppressed
                   the fan-out (raw not= would fan a phantom movement)")
              (is (= v0 (:version (lease-last lease)))
                  "the node version did NOT advance — NaN=NaN under the movement law"))
            (testing "positive control — a REAL move fans exactly once + advances"
              (rf/dispatch-sync [:obs/set-n 5])
              (ratom/flush!)
              (is (= 1 (count @notes)) "exactly one {:cause :value} for a real move")
              (is (= :value (:cause (first @notes))))
              ;; EXACT `inc`, not merely `> v0`: `advance-node-record!` advances
              ;; the node version by exactly `(inc (:version rec))` per real
              ;; movement (observation.cljc), and between `v0` and this single
              ;; real move only the NO-move NaN→NaN recompute intervened (version
              ;; held at `v0`, asserted above). A +2 seam increment — the mutation
              ;; the loose `> v0` waves through — fails this assertion.
              (is (= (inc v0) (:version (lease-last lease)))
                  "the real value movement advanced the node version EXACTLY once")))
          (finally
            (nan-rm)
            (obs/release! lease)
            (ratom/dispose! driver)))))))

;; ===========================================================================
;; rf2-r09qj — NaN moves NaN-STABLY through an integrated ViewCell
;; ===========================================================================
;;
;; WHY THE "render" IS A HEADLESS `reactive/subscribe` LISTENER, NOT A REACT
;; MOUNT (rf2-en6qm). An audit (rf2-en6qm) asked to re-establish this fact at a
;; REAL mounted render (a live React component's render count) rather than a
;; `useSyncExternalStore` listener. That is architecturally UNREACHABLE for THIS
;; fact without a runtime change, because the two properties the proof needs are
;; mutually exclusive across every supported adapter:
;;
;;   1. HOST WATCH FIRES ON NaN→NaN. Only the ratom Reaction (Reagent /
;;      reagent-slim) notifies on NaN→NaN — its notify gate is raw `=`
;;      (`(= ##NaN ##NaN)` is false). That firing on a NO-move is the whole
;;      point: it is what makes the observation port's `node-value=` suppression
;;      (make-watch-handler) LOAD-BEARING. The first-party re-frame.ui / UIx /
;;      Helix watchable substrate gates notify on `rf=`
;;      (`Object.is(##NaN,##NaN)` is true — `re_frame/substrate/spine.cljs`
;;      `rf=`), so its host watch does NOT fire on NaN→NaN and the
;;      make-watch-handler is never even reached.
;;   2. DRIVES A ViewCell AT A REAL MOUNT. Only re-frame.ui / UIx / Helix render
;;      through the observation port → ViewCell → `useSyncExternalStore`. Reagent
;;      and reagent-slim render NATIVELY through `re-frame.views` /
;;      `create-class`; a mounted Reagent view never touches the observation port
;;      or a ViewCell. And `re-frame.ui.client/mount!` refuses a non-`re-frame.ui`
;;      adapter generation, so "Reagent host + ViewCell real mount" is not a
;;      supported configuration.
;;
;; So the ONLY host that fires on NaN→NaN (property 1) is exactly the family that
;; never drives a ViewCell (property 2). At a real mounted render on the ui
;; adapter a NaN→NaN produces zero renders too — but for the WRONG reason (the
;; host's own `rf=` gate suppresses at source; the sentinel would NOT fire), so
;; it would prove the spine's notify gate, not make-watch-handler suppression.
;; This integrated ViewCell (real `re-frame.ui.reactive` cell, driven by
;; `with-capture`/`commit!` and observed via the SAME `useSyncExternalStore`
;; `subscribe` seam React calls) is therefore the HIGHEST-fidelity SUPPORTED
;; proof of the make-watch-handler NaN suppression over a Reagent watchable host;
;; the render is the listener-notification count that a mounted React component
;; would consume. Moving it to a live React mount would require either a runtime
;; change to the ui substrate notify gate or an unsupported Reagent+ViewCell
;; bridge — both out of scope for a proof.

(deftest viewcell-over-watchable-host-nan-to-nan-is-version-revision-render-stable
  (testing "an integrated observation→ViewCell path over a WATCHABLE (Reagent)
            host: a NaN→NaN recompute FIRES the host watch yet leaves the node
            version, the cell revision, AND the render (listener notification)
            count UNMOVED; a real value movement moves all three EXACTLY once.
            This is the downstream contract the source bead names that the
            plain-atom ViewCell coverage (non-watchable, no make-watch-handler)
            cannot reach."
    (register!)
    (rf/dispatch-sync [:obs/set-n ##NaN])
    (let [driver (make-driver)]
      (ratom/flush!)
      (let [cell    (reactive/make-cell ::nan-host)
            renders (atom 0)]
        (render+commit! cell)                ;; commit installs the watch-bearing lease
        (let [unsub             (reactive/subscribe cell (fn [] (swap! renders inc)))
              reaction          (node-reaction)
              [nan-hits nan-rm] (install-nan-sentinel! reaction)
              lease             (reactive/committed-lease cell (tk [:obs/n]))
              ver0              (:version (obs/read lease))
              rev0              (reactive/revision cell)]
          (try
            ;; --- preconditions: a real owned, watch-bearing lease at NaN ---
            (is (some? lease) "the ViewCell committed a lease over the watchable host")
            (is (obs/owned? lease)
                "…a REAL owned observation node (so acquire! installed the watch)")
            (is (nan-num? (get (reactive/committed-values cell) (tk [:obs/n])))
                "the committed value is the host's NaN")
            (is (= 0 rev0) "precondition: committed, no revision yet")
            (is (= 0 @renders) "precondition: no render notifications yet")
            ;; --- NaN→NaN: host watch fires, the port suppresses everything ---
            (rf/dispatch-sync [:obs/set-n ##NaN])
            (ratom/flush!)
            (reactive/flush-dirty! cell)     ;; drain any pending notification
            (testing "NaN→NaN leaves version + revision + render UNMOVED"
              (is (pos? @nan-hits)
                  "the host watch FIRED for NaN→NaN (the callback genuinely ran)")
              (is (= ver0 (:version (obs/read lease)))
                  "node version UNMOVED — NaN=NaN under the movement law")
              (is (= rev0 (reactive/revision cell))
                  "cell revision UNMOVED — make-watch-handler dirtied nothing")
              (is (= 0 @renders) "ZERO renders — no listener notification fired"))
            (testing "a re-render+commit finds no movement at step 5 either"
              (render+commit! cell)
              (reactive/flush-dirty! cell)
              (is (= rev0 (reactive/revision cell))
                  "a re-commit over the un-advanced node advances NO revision")
              (is (= 0 @renders) "…and still no render"))
            (testing "positive control — a REAL move moves all three EXACTLY once"
              (rf/dispatch-sync [:obs/set-n 5])
              (ratom/flush!)
              (reactive/flush-dirty! cell)
              ;; EXACT `inc`, matching the revision assertion just below: only
              ;; the NO-move NaN→NaN recompute(s) intervened between `ver0` and
              ;; this single real move, so `advance-node-record!` advanced the
              ;; node version by exactly one. A +2 seam increment fails here.
              (is (= (inc ver0) (:version (obs/read lease)))
                  "the real move advanced the node version EXACTLY once")
              (is (= (inc rev0) (reactive/revision cell))
                  "the real move advanced the cell revision exactly once")
              (is (= 1 @renders)
                  "the real move fired exactly one render (listener notification)"))
            (finally
              (nan-rm)
              (unsub)
              (ratom/dispose! driver))))))))
