(ns re-frame.observation-port-watchable-host-cljs-test
  "rf2-vxgfnd.29 (gap a) — the observation port's per-handle VALUE-MOVEMENT
  watch channel (`make-watch-handler`, `re-frame.substrate.observation`),
  exercised end-to-end on a WATCHABLE host.

  The plain-atom port suites (`re-frame.observation-port-cljs-test`) are
  IDeref-only: their derived sub values are NOT `IWatchable`, so the per-handle
  `add-watch` the port installs never fires and the entire `{:cause :subscription}`
  fan-out — the delivered-value node-record advance, the handle `:last` update,
  and that path's dev reentrancy guard — runs in no test there (the untested
  half of `acquire!`). This file grafts the SAME port onto the stock Reagent
  adapter, whose derived sub reactions ARE `IWatchable`
  (`reagent.ratom/Reaction`), so an observed value MOVEMENT actually fires the
  port's watch — the real trigger the reactive-tear gates (.40 / .65) depend on.

  THE DRIVER THAT USED TO STAND HERE, AND WHY IT IS GONE (rf2-8cnxg). Every
  fixture below used to stand a `reagent.ratom/run!` DRIVER — an auto-running
  reaction that eagerly derefs the sub — explained as \"the port watch is a
  passive observer; in production the ViewCell render is the active consumer
  that keeps the lazy reaction on the push path\". BOTH HALVES OF THAT WERE
  WRONG, and the second one was a live P1 bug wearing a test convenience as a
  disguise. A ViewCell is not a Reagent component: it never derefs the node
  inside `*ratom-context*`, so it never supplied the capture the sentence
  credited it with. The reaction stayed uncaptured, `_handle-change` was never
  called, and every Freehand / re-frame.ui cell over a Reagent-hosted
  subscription rendered ONCE and never again. The driver was not standing in
  for a render — it was hand-supplying a runtime step that did not exist.

  It exists now: `build-node-handle!` calls
  `re-frame.interop/activate-derived-value!` before installing its watch, so
  ACQUIRE ALONE puts the node on the push path. The drivers are therefore
  deleted rather than kept: with one in place these fixtures would stay green
  against a regression of that fix. `re-frame.observation-port-activates-
  ratom-node-cljs-test` pins the activation itself.

  The push path: a `dispatch-sync` MOVES the sub's value; the source
  notification reaches the (now capturing) node, `reagent.ratom/flush!` drains
  the recompute, and — the value changed — the node notifies the port watch,
  which advances the node record with the DELIVERED value (no recompute, I-5)
  and fans `{:cause :subscription …}` to the handle's own `on-change`.

  CLJS-only (Reagent is CLJS); ns ends in `-cljs-test` so the consolidated
  shadow-cljs `:node-test` build (`npm run test:cljs`, whose source-paths carry
  `adapters/reagent`) picks it up headlessly — no DOM, no React.

  rf2-r09qj (extension) — NaN moves NaN-STABLY through the PUBLIC acquire and
  the ViewCell. A NaN→NaN recompute is the adversarial case for this channel:
  clojure/cljs `=` (and Reagent's `=`-gated reaction-notify) treat `NaN ≠ NaN`,
  so a NaN→NaN recompute genuinely FIRES the host watch — yet the movement law
  (`node-value=` / `eq/rf=`, NaN self-equal on both hosts) is NO movement, so
  the port must fan NO `:cause :subscription` note, advance NO node version, dirty NO
  ViewCell, and drive NO render. A naive raw `not=` at the make-watch-handler
  seam would spuriously fan a phantom movement against a stable node. These
  proofs (a) stand an INDEPENDENT sentinel watch to show the host callback
  genuinely ran (non-vacuous), (b) drive the movement through public `acquire!`
  AND an integrated ViewCell (`re-frame.ui.reactive` — colocated on the
  consolidated `:node-test` classpath, which carries both `ui/src` and
  `adapters/reagent`), and (c) retain a real-movement positive control so
  upstream suppression cannot make the assertions vacuous.

  rf2-mjpmp (mounted arm) — the SAME fact at a REAL React mount, through the
  PUBLIC compiled path, lives in the sibling namespace
  `re-frame.observation-port-watchable-host-mounted-dom-cljs-test` (the
  `-dom-cljs-test` suffix is what enrols it in the `:browser-test` build; this
  file's `-cljs-test` suffix is `:node-test` only). The two headless arms here
  drive the ViewCell by hand (`with-capture` / `commit!`, and a
  `reactive/subscribe` listener standing in for a render), which pins the
  cell-level contract headlessly but leaves the PUBLIC mount path unexercised.
  The mounted arm closes that gap: it installs the stock Reagent adapter and
  mounts a compiled sub-reading `defview` through the public compiled mount
  path, so a NaN→NaN recompute is judged by the REAL mounted render — the
  compiled view's own body invocation and the React commit — not by a listener
  count. Reagent supplies the WATCHABLE host (its Reaction notifies on
  NaN→NaN); the compiled ViewCell reads that host THROUGH the observation port,
  which is what makes `make-watch-handler`'s suppression load-bearing at a live
  mount. The corrected section header below records why that composition is
  supported."
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

(defn- handle-state [handle] @(@#'obs/handle-state handle))
(defn- handle-last  [handle] (:last (handle-state handle)))

(defn- node-reaction []
  (:reaction (get @(:sub-cache (frame/frame fid)) [:obs/n])))

(defn- register! []
  (rf/reg-sub :obs/n (fn [db _] (:n db)))
  (rf/reg-event :obs/set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)})))


;; --- rf2-r09qj NaN-stability helpers ---------------------------------------

;; The subscription target-key the ViewCell projects committed handles/values by
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
;; handle — the make-watch-handler value-movement channel under test).
(defn- render+commit! [cell]
  (let [[_ cap] (rf/with-frame fid
                  (reactive/with-capture cell
                    (fn [] (reactive/sub-read ::nan-site [:obs/n]))))]
    (reactive/commit! cell cap))
  cell)

;; ===========================================================================
;; the value-movement watch channel — {:cause :subscription} end-to-end
;; ===========================================================================

(deftest watchable-host-value-movement-fires-cause-value-end-to-end
  (testing "on a WATCHABLE (Reagent) host, a sub value MOVEMENT fires the
            port's per-handle watch → {:cause :subscription} with an ADVANCED
            node-version and the handle :last updated to the DELIVERED value —
            the make-watch-handler channel the plain-atom suites cannot reach"
    (register!)
    (rf/dispatch-sync [:obs/set-n 1])
    (let [notes  (atom [])
          target (obs/resolve-target {:frame fid :query-v [:obs/n]})
          handle  (obs/acquire! target (fn [ev] (swap! notes conj ev)))]
      (try
        ;; --- preconditions: the port armed the value-movement channel ---
        (is (obs/handle? handle))
        (is (satisfies? IWatchable (node-reaction))
            "the Reagent sub reaction IS IWatchable — the port installed its
             per-handle value-movement watch (on a non-watchable host this
             whole channel is dead, which is the very gap under test)")
        (is (some? (:watch-key (handle-state handle)))
            "acquire! registered a per-handle change watch key on the node")
        (is (empty? @notes)
            "acquire! on a WARM node fans nothing synchronously (I-5
             no-sync-fan-out holds on a watchable host too)")
        (let [v0    (:version (handle-last handle))
              node0 (:node-key (handle-last handle))]
          (is (= 1 (:value (handle-last handle))) "baseline observed value")
          ;; --- MOVE the value; flush the reaction queue (the push) ---
          (rf/dispatch-sync [:obs/set-n 2])
          (ratom/flush!)
          (testing "the value-movement watch fired the {:cause :subscription} channel"
            (is (pos? (count @notes))
                "the port's per-handle watch fired on the value movement")
            (is (every? #(= :subscription (:cause %)) @notes)
                "every fan-out on this path carries {:cause :subscription}")
            (let [ev (peek @notes)]
              (is (= target (:target ev)) "the payload carries the handle target")
              (is (= node0 (:node-key ev)) "same node — a MOVEMENT, not a rebuild")
              (is (> (:node-version ev) v0)
                  "the delivered-value node-version ADVANCED past the baseline")
              (is (= (:node-version ev) (:version (handle-last handle)))
                  "the handle :last was advanced to the delivered version")
              (is (= 2 (:value (handle-last handle)))
                  "the handle :last holds the DELIVERED new value (no recompute)")
              (is (int? (:frame-epoch ev)))
              (is (int? (:registry-epoch ev))))))
        (finally
          (obs/release! handle))))))

(deftest watchable-host-idempotent-move-does-not-fan-cause-value
  (testing "a commit that leaves the sub value UNMOVED does not fire the
            {:cause :subscription} channel — the reaction's `=` notify gate (and the
            node-record's rf= version hold) mean an equal re-commit is silent"
    (register!)
    (rf/dispatch-sync [:obs/set-n 7])
    (let [notes  (atom [])
          target (obs/resolve-target {:frame fid :query-v [:obs/n]})
          handle  (obs/acquire! target (fn [ev] (swap! notes conj ev)))]
      (try
        (is (empty? @notes) "acquire! on a WARM node is silent")
        (let [v0 (:version (handle-last handle))]
          ;; Re-commit the SAME value: the node re-runs to an equal value, so
          ;; no watch notification and no version advance.
          (rf/dispatch-sync [:obs/set-n 7])
          (ratom/flush!)
          (is (empty? @notes)
              "an unmoved value fans nothing on the :subscription channel")
          (is (= v0 (:version (handle-last handle)))
              "the node-version did not advance for an equal re-commit"))
        (finally
          (obs/release! handle))))))

;; ===========================================================================
;; the reentrancy guard on the value-movement fan-out
;; ===========================================================================

(deftest watchable-host-value-fan-out-holds-the-reentrancy-guard
  (testing "an on-change that mutates graph ownership (release!) from INSIDE
            the {:cause :subscription} fan-out trips the dev reentrancy guard —
            :rf.error/reentrant-graph-op — the make-watch-handler-path leg of
            the guard the plain-atom suites cannot reach"
    (register!)
    (rf/dispatch-sync [:obs/set-n 1])
    (let [target  (obs/resolve-target {:frame fid :query-v [:obs/n]})
          outcome (atom :guard-not-fired)
          ;; The on-change attempts a FORBIDDEN reentrant self-release from
          ;; inside the value fan-out; the guard must throw before any
          ;; teardown, so the handle stays intact and `finally` is a clean op.
          handle   (atom nil)]
      (reset! handle
        (obs/acquire! target
          (fn [_ev]
            (reset! outcome
              (try (obs/release! @handle) :released-no-throw
                   (catch :default e (:rf.error/id (ex-data e))))))))
      (try
        ;; MOVE the value so the measured handle's watch fires on a warm node.
        (rf/dispatch-sync [:obs/set-n 2])
        (ratom/flush!)
        (is (= :rf.error/reentrant-graph-op @outcome)
            "release! from inside the value-movement fan-out threw the dev
             reentrant-graph-op assert (fan-out! bound *in-owner-fan-out?*)")
        (is (= :live (:status (handle-state @handle)))
            "the guard threw BEFORE any teardown — the handle is untouched")
        (finally
          (obs/release! @handle))))))

;; ===========================================================================
;; rf2-r09qj — NaN moves NaN-STABLY through the PUBLIC acquire path
;; ===========================================================================

(deftest watchable-host-nan-to-nan-fires-watch-but-fans-no-value-movement
  (testing "on a WATCHABLE (Reagent) host, a NaN→NaN recompute FIRES the
            underlying host watch (an independent sentinel proves the callback
            ran) yet the port's node-value= gate emits NO {:cause :subscription} note
            and advances NO node version — the make-watch-handler NaN-suppression
            proven through PUBLIC acquire!. A raw not= at the seam (NaN≠NaN
            natively) would spuriously fan a phantom value movement."
    (register!)
    (rf/dispatch-sync [:obs/set-n ##NaN])
    (let [notes            (atom [])
          target           (obs/resolve-target {:frame fid :query-v [:obs/n]})
          handle            (obs/acquire! target (fn [ev] (swap! notes conj ev)))
          reaction         (node-reaction)
          [nan-hits nan-rm] (install-nan-sentinel! reaction)]
      (try
        ;; --- preconditions ---
        (is (obs/handle? handle))
        (is (satisfies? IWatchable reaction)
            "the Reagent sub reaction IS IWatchable — the port armed its
             per-handle value-movement watch")
        (is (empty? @notes) "acquire! on a warm NaN node fans nothing")
        (is (nan-num? (:value (handle-last handle))) "baseline observed the host NaN")
        (let [v0 (:version (handle-last handle))]
          ;; --- MOVE NaN→NaN: app-db moves (NaN≠NaN under map =), the reaction
          ;; re-derives NaN, and Reagent's =-gated notify fires (NaN≠NaN). ---
          (rf/dispatch-sync [:obs/set-n ##NaN])
          (ratom/flush!)
          (testing "the host watch fired but the port fanned nothing"
            (is (pos? @nan-hits)
                "the underlying Reagent host watch FIRED for NaN→NaN — the
                 port's make-watch-handler callback genuinely ran (non-vacuous)")
            (is (empty? @notes)
                "NaN→NaN emitted NO {:cause :subscription} note — node-value= suppressed
                 the fan-out (raw not= would fan a phantom movement)")
            (is (= v0 (:version (handle-last handle)))
                "the node version did NOT advance — NaN=NaN under the movement law"))
          (testing "positive control — a REAL move fans exactly once + advances"
            (rf/dispatch-sync [:obs/set-n 5])
            (ratom/flush!)
            (is (= 1 (count @notes)) "exactly one {:cause :subscription} for a real move")
            (is (= :subscription (:cause (first @notes))))
            ;; EXACT `inc`, not merely `> v0`: `advance-node-record!` advances
            ;; the node version by exactly `(inc (:version rec))` per real
            ;; movement (observation.cljc), and between `v0` and this single
            ;; real move only the NO-move NaN→NaN recompute intervened (version
            ;; held at `v0`, asserted above). A +2 seam increment — the mutation
            ;; the loose `> v0` waves through — fails this assertion.
            (is (= (inc v0) (:version (handle-last handle)))
                "the real value movement advanced the node version EXACTLY once")))
        (finally
          (nan-rm)
          (obs/release! handle))))))

;; ===========================================================================
;; rf2-r09qj — NaN moves NaN-STABLY through an integrated ViewCell
;; ===========================================================================
;;
;; WHY THIS ARM'S "render" IS A HEADLESS `reactive/subscribe` LISTENER — and why
;; that is a COMPLEMENT to, not a substitute for, the mounted arm below.
;;
;; This test drives a real `re-frame.ui.reactive` cell by hand (`with-capture` /
;; `commit!`) and reads the SAME `useSyncExternalStore` `subscribe` seam React
;; calls, so it pins the cell-level contract — version, revision, and listener
;; notification — without a DOM. That is genuinely useful: it runs headlessly in
;; the `:node-test` build, on every PR, with no browser.
;;
;; It is NOT the whole proof, because a hand-driven commit is a PROXY for a
;; render: mutating the public compiled mount path would leave this arm green.
;; The sibling namespace
;; `re-frame.observation-port-watchable-host-mounted-dom-cljs-test` closes that
;; gap through the PUBLIC compiled mount path, at a real React mount.
;;
;; A PRIOR REVISION OF THIS COMMENT CLAIMED THE MOUNTED ARM WAS ARCHITECTURALLY
;; UNREACHABLE. That claim was WRONG on two counts, and is corrected here
;; (rf2-mjpmp) so it does not mislead a future reader:
;;
;;   - It said `re-frame.ui.client/mount!` "refuses a non-`re-frame.ui` adapter
;;     generation". It does not. `mount*` captures the OPAQUE installed-adapter
;;     generation token (`current-adapter-generation`) before its side-effecting
;;     frame preflight and re-asserts that the SAME token is still installed
;;     afterwards (`require-adapter-generation-open!`, rf2-vxgfnd.199). That is a
;;     generic identity check against destroy / destroy-and-replace during
;;     preflight — it reads no `:kind`, and there is no adapter-kind gate
;;     anywhere on the mount path. Compiled roots mount under whatever adapter is
;;     installed; sibling suites already mount them under `:kind :custom`
;;     adapters.
;;   - It said Reagent "never touches the observation port or a ViewCell". That
;;     conflates WHICH ADAPTER SUPPLIES THE WATCHABLE HOST with WHICH RENDERER
;;     DRIVES THE VIEW. They are independent. A compiled `defview` always reads
;;     its subs through the observation port into a ViewCell — that is the
;;     compiled path, not an adapter feature. The installed adapter supplies the
;;     sub-cache's derived nodes; under Reagent those nodes ARE
;;     `reagent.ratom/Reaction`s. So a compiled view mounted while Reagent is
;;     installed reads a REAGENT watchable through a REAL ViewCell.
;;
;; The two properties the proof needs are therefore NOT mutually exclusive:
;;
;;   1. HOST WATCH FIRES ON NaN→NaN — supplied by the REAGENT adapter's Reaction,
;;      whose notify gate is raw `=` (`(= ##NaN ##NaN)` is false). Firing on a
;;      NO-move is the whole point: it is what makes the port's `node-value=`
;;      suppression (make-watch-handler) LOAD-BEARING. (The first-party
;;      re-frame.ui / UIx substrate gates notify on `rf=` —
;;      `Object.is(##NaN,##NaN)` is true, `re_frame/substrate/spine.cljs` — so on
;;      THOSE hosts the watch never fires for NaN→NaN and make-watch-handler is
;;      never reached. A mounted NaN proof on the ui adapter would produce zero
;;      renders for the WRONG reason, proving the spine's notify gate instead.)
;;   2. DRIVES A ViewCell AT A REAL MOUNT — supplied by the COMPILED VIEW, which
;;      routes every `ui/sub` through the observation port → ViewCell →
;;      `useSyncExternalStore` regardless of the installed adapter.
;;
;; Composing the two needs no bridge, no adapter-kind guard, and no runtime
;; change — only the already-public composition the mounted arm exercises.

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
    (let [cell    (reactive/make-cell ::nan-host)
          renders (atom 0)]
      (render+commit! cell)                ;; commit installs the watch-bearing handle
      (let [unsub             (reactive/subscribe cell (fn [] (swap! renders inc)))
            reaction          (node-reaction)
            [nan-hits nan-rm] (install-nan-sentinel! reaction)
            handle             (reactive/committed-handle cell (tk [:obs/n]))
            ver0              (:version (obs/read handle))
            rev0              (reactive/revision cell)]
        (try
          ;; --- preconditions: a real owned, watch-bearing handle at NaN ---
          (is (some? handle) "the ViewCell committed a handle over the watchable host")
          (is (obs/owned? handle)
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
            (is (= ver0 (:version (obs/read handle)))
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
            (is (= (inc ver0) (:version (obs/read handle)))
                "the real move advanced the node version EXACTLY once")
            (is (= (inc rev0) (reactive/revision cell))
                "the real move advanced the cell revision exactly once")
            (is (= 1 @renders)
                "the real move fired exactly one render (listener notification)"))
          (finally
            (nan-rm)
            (unsub)))))))
