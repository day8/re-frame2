(ns re-frame.epoch-silencing-lineage-aba-test
  "rf2-vxgfnd.265 — delayed predecessor-silencing decided PER callback-generation
  identity across the three events #5872 conflated under one coarse
  `cleanup-frame-owner!` result: store CLAIM, callback DELIVERY/re-arm, and
  terminal SILENCE.

  #5872 gated a destroyed predecessor A's whole `:rf.epoch.cb/silenced-on-frame-
  destroy` fan on A still winning its compare-owned cleanup — equating losing the
  frame-store owner comparison with a same-id successor having re-armed the
  listeners. Those are distinct events, so the coarse gate produces three failure
  modes this suite pins (each red before the fix; reverting to cleanup-result-wide
  gating re-reddens them):

    1. claim-before-delivery — B claims the id-keyed stores (a pre-first-epoch
       render/backfill) but delivers no epoch. A LOSES the comparison yet still
       owes the only silence: cb never observed B. Coarse gate → 0 (false
       negative). Both `remains idle` and `retires` sub-cases.
    2. rearm-then-retire (A→B→nil ABA) — B re-arms cb and DESTROYS before A
       resumes. B emits the one truthful silence and releases the stores; A then
       WINS the comparison and re-emits the identical unqualified signal. Coarse
       gate → 2 (double signal). The monotonic terminal-silence mark, surviving
       B's cleanup, is what lets late A recognise B already silenced cb.
    3. mixed identities — a B-rearmed identity is suppressed, an A-only identity
       is silenced, and a generation re-registered in the gap receives no stale
       signal, ALL in one destroy. The coarse gate can only silence-all-or-none;
       per-identity decision is required.

  These JVM fixtures pause A with a `:epoch/on-frame-destroyed` late-bind wrapper
  (matching the merged .151/.245 fixtures) or compose the successor lineage at the
  epoch-state seam directly; the synchronous/reentrant CLJS peers live in
  `re-frame.epoch-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.listeners :as rf.epoch.listeners]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- executor-barrier!
  "Wait until work already submitted through `interop/next-tick` has run."
  []
  (let [latch (CountDownLatch. 1)]
    (rf.interop/next-tick #(.countDown latch))
    (is (.await latch 5 TimeUnit/SECONDS)
        "the executor reached the deterministic barrier")))

(defn- silences-for
  "Count `:rf.epoch.cb/silenced-on-frame-destroy` traces captured in `silencings`
  whose `:cb-id` is `cb`."
  [silencings cb]
  (count (filter #(= cb (:cb-id (:tags %))) @silencings)))

;; ---- Mode 1: claim-before-delivery ----------------------------------------

(deftest predecessor-silences-after-successor-claims-without-delivery
  ;; A settles (cb observes A + A claims the id-keyed stores), self-destroys
  ;; mid-drain and pauses at its POST-DISSOC epoch hook (owed identities incl cb
  ;; snapshotted before dissoc). Same-id B is created and CLAIMS the id-keyed
  ;; stores through the exact `claim-frame-owner!` a pre-first-epoch render/
  ;; backfill routes through, but SETTLES nothing — cb never observes B. When A
  ;; resumes it must emit its one owed silence for cb even though it LOST the
  ;; cleanup comparison to B's claim.
  (let [id             :vxgfnd265/claim-idle
        cb             ::vxgfnd265-claim-idle-cb
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        silencings     (atom [])
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :vxgfnd265/claim-idle-settle
      (fn [{:keys [db]} _] {:db (assoc db :a true)}))
    (rf/reg-event :vxgfnd265/claim-idle-destroy
      (fn [_ _] (rf.frame/destroy-frame! id) {:db {:owner :a-tail}}))
    (rf/make-frame {:id id})
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf/dispatch-sync [:vxgfnd265/claim-idle-settle] {:frame id})
    (rf/register-listener! :trace ::vxgfnd265-claim-idle-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    (try
      (rf.late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:vxgfnd265/claim-idle-destroy]
                                           {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A is paused at its post-dissoc epoch hook (owed incl cb snapshotted)")
        ;; Same-id B claims the id-keyed stores (models the pre-first-epoch
        ;; render/backfill claim) but never settles → cb never observes B.
        (rf/make-frame {:id id})
        (rf.epoch.state/claim-frame-owner! id (rf.frame/frame-incarnation-token id))
        (is (nil? (get-in (rf.epoch.state/observations-snapshot) [cb id]))
            "B's claim dropped cb's observation; B delivered nothing to re-arm it")
        (.countDown release-a)
        (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
            "A's terminal recipe completes")
        (executor-barrier!)
        ;; THE FIX: A still owes and emits exactly one silence for cb.
        (is (= 1 (silences-for silencings cb))
            "A emits its one owed silence for cb — B claimed but never delivered"))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace ::vxgfnd265-claim-idle-silencing)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

(deftest predecessor-silences-after-successor-claims-then-retires-without-delivery
  ;; Mode 1 `retires` variant, composed at the epoch-state seam. cb observes A;
  ;; A's owed identities + baseline are snapshotted. B claims (no delivery) and
  ;; then RETIRES (its terminal hook silences nothing — it never observed cb).
  ;; A then resumes and, being the last owner, WINS the comparison; it must emit
  ;; exactly the one owed silence (never observed B, no successor silenced cb).
  (let [id         :vxgfnd265/claim-retire
        cb         ::vxgfnd265-claim-retire-cb
        token-a    (Object.)
        token-b    (Object.)
        silencings (atom [])]
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf/register-listener! :trace ::vxgfnd265-claim-retire-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    (try
      ;; A: claim + cb observes A.
      (rf.epoch.state/claim-frame-owner! id token-a)
      (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
      (let [a-ev (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)]
        ;; B claims (dropping cb's observation) but never delivers, then retires.
        (rf.epoch.state/claim-frame-owner! id token-b)
        (rf.epoch.listeners/on-frame-destroyed! id token-b
          (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil))
        (is (zero? (silences-for silencings cb))
            "B's retire silences nothing — B never delivered to cb")
        ;; A resumes: still owes the one silence.
        (rf.epoch.listeners/on-frame-destroyed! id token-a a-ev)
        (is (= 1 (silences-for silencings cb))
            "A emits its one owed silence after B claimed-then-retired without delivery"))
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace ::vxgfnd265-claim-retire-silencing)))))

;; ---- Mode 2: rearm-then-retire (A→B→nil ABA) ------------------------------

(deftest late-predecessor-does-not-re-emit-silence-a-retired-successor-already-fired
  ;; A settles (cb observes A), self-destroys mid-drain and pauses at its
  ;; POST-DISSOC epoch hook. Same-id B settles an ordinary event (cb receives B's
  ;; record → re-armed), then B RETIRES: its terminal hook emits the one truthful
  ;; silence for cb and releases the stores. When A resumes it now WINS the
  ;; compare-owned cleanup (no owner left), but must NOT re-emit the identical
  ;; unqualified signal — the monotonic terminal-silence mark B left (surviving
  ;; B's cleanup) proves a successor already silenced cb after A's baseline.
  (let [id             :vxgfnd265/aba
        cb             ::vxgfnd265-aba-cb
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)
        first-epoch?   (atom true)
        received       (atom [])
        silencings     (atom [])
        original-epoch (rf.late-bind/get-fn :epoch/on-frame-destroyed)]
    (rf/reg-event :vxgfnd265/aba-settle
      (fn [{:keys [db]} _] {:db (assoc db :a true)}))
    (rf/reg-event :vxgfnd265/aba-destroy
      (fn [_ _] (rf.frame/destroy-frame! id) {:db {:owner :a-tail}}))
    (rf/reg-event :vxgfnd265/aba-b-settle
      (fn [{:keys [db]} _] {:db (assoc db :owner :b)}))
    (rf/make-frame {:id id})
    (rf/register-listener! :epoch cb (fn [r] (swap! received conj r)))
    (rf/dispatch-sync [:vxgfnd265/aba-settle] {:frame id})
    (rf/register-listener! :trace ::vxgfnd265-aba-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    (try
      (rf.late-bind/set-fn! :epoch/on-frame-destroyed
        (fn [& args]
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch (apply original-epoch args))))
      (let [dispatch-a (future
                         (rf/dispatch-sync [:vxgfnd265/aba-destroy] {:frame id}))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "A is paused at its post-dissoc epoch hook")
        ;; Same-id B settles (re-arms cb) then RETIRES at the epoch seam — its
        ;; terminal hook fires cb's one truthful silence and drops the stores.
        (rf/make-frame {:id id})
        (rf/dispatch-sync [:vxgfnd265/aba-b-settle] {:frame id})
        (is (= 2 (count @received))
            "cb received A's settle and B's settle — re-armed for the reused id")
        (let [token-b (rf.frame/frame-incarnation-token id)]
          (rf.epoch.listeners/on-frame-destroyed! id token-b
            (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)))
        (is (= 1 (silences-for silencings cb))
            "B's retire fired exactly one truthful silence for cb")
        ;; A resumes: must add NO further silence for cb (ABA prevented).
        (.countDown release-a)
        (is (not= ::timeout (deref dispatch-a 5000 ::timeout))
            "A's terminal recipe completes")
        (executor-barrier!)
        (is (= 1 (silences-for silencings cb))
            "late A adds no silence — the retired successor already fired the one signal"))
      (finally
        (.countDown release-a)
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace ::vxgfnd265-aba-silencing)
        (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)))))

;; ---- Mode 3: mixed identities decided independently -----------------------

(deftest delayed-silence-decided-per-identity-across-mixed-generations
  ;; One destroy, three owed identities, three outcomes — the per-identity
  ;; decision the coarse gate cannot express. Composed at the epoch-state seam so
  ;; each identity is placed under a distinct successor outcome (a single real
  ;; fan-out re-arms every listener uniformly and cannot diverge them):
  ;;
  ;;   U — re-armed by a still-LIVE successor  → suppressed (live-observer)
  ;;   V — never re-armed by the successor      → silenced (A still owes it)
  ;;   W — re-registered to a fresh generation  → no stale signal (gen mismatch)
  (let [id         :vxgfnd265/mixed
        u          ::vxgfnd265-mixed-u
        v          ::vxgfnd265-mixed-v
        w          ::vxgfnd265-mixed-w
        token-a    (Object.)
        token-b    (Object.)
        silencings (atom [])]
    (rf/register-listener! :epoch u (fn [_] nil))
    (rf/register-listener! :epoch v (fn [_] nil))
    (rf/register-listener! :epoch w (fn [_] nil))
    (rf/register-listener! :trace ::vxgfnd265-mixed-silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    (try
      ;; A claims and fans one record → U, V, W all observe A.
      (rf.epoch.state/claim-frame-owner! id token-a)
      (rf.epoch.listeners/notify-listeners! {:frame id :epoch-id 1})
      (is (= #{u v w} (set (rf.epoch.state/cbs-observing-frame id)))
          "U, V, W all observed A")
      (let [u-gen (:generation (get (rf.epoch.state/listeners-snapshot) u))
            a-ev  (rf.epoch.listeners/snapshot-terminal-destroy-evidence! id nil nil nil)]
        ;; Successor B claims (dropping every observation) and stays LIVE. It
        ;; re-arms ONLY U; V is left un-rearmed; W is re-registered to a fresh
        ;; generation that never observed the frame.
        (rf.epoch.state/claim-frame-owner! id token-b)
        (rf.epoch.state/record-observation! u u-gen id)     ; U live on B
        (rf/register-listener! :epoch w (fn [_] nil))     ; W → fresh generation
        (is (= [u] (rf.epoch.state/cbs-observing-frame id))
            "only U is a live observer of the reused id under the successor")
        ;; A resumes with its stale snapshot (token-a is no longer the owner).
        (rf.epoch.listeners/on-frame-destroyed! id token-a a-ev)
        (is (= 1 (silences-for silencings v))
            "V — A-only identity — is silenced")
        (is (zero? (silences-for silencings u))
            "U — re-armed and live on the successor — is suppressed")
        (is (zero? (silences-for silencings w))
            "W — re-registered to a fresh generation — receives no stale signal")
        (is (= 1 (count @silencings))
            "exactly one silence total across the three mixed identities"))
      (finally
        (rf/unregister-listener! :epoch u)
        (rf/unregister-listener! :epoch v)
        (rf/unregister-listener! :epoch w)
        (rf/unregister-listener! :trace ::vxgfnd265-mixed-silencing)))))
