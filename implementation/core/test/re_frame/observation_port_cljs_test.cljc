(ns re-frame.observation-port-cljs-test
  "rf2-vxgfnd.7 — the internal observation port over the REAL sub-cache
  (Spec 006 §The internal observation port). This IS the real-cache graft
  gate: S-3 validated the shapes over a stand-in graph; these fixtures prove
  the six operations against `re-frame.subs`' actual cache — ref-count
  attach/detach, synchronous 1 → 0 disposal, hot-reload eviction, the
  fail-loud error contract, the cold-probe zero-retention rule, and the four
  [S2-CONFIRM] items (no-sync-fan-out; HMR-disposal queue alignment;
  reverse-order rollback release; the cold-probe edge set).

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`
  (the plain-atom adapter is CLJC), so the port is graft-checked on both
  hosts. Host honesty: plain-atom derived values are not watchable, so the
  value-movement `on-change` channel is a reactive-host surface — these
  fixtures pin movement detection at the port's READ points (version +
  epoch evidence), which is the headless contract."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                  :as rf]
            [re-frame.error-emit            :as error-emit]
            [re-frame.frame                 :as frame]
            [re-frame.interop               :as interop]
            [re-frame.subs                  :as subs]
            [re-frame.substrate.observation :as obs]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private fid :rf/default)

(defn- sub-cache []
  (:sub-cache (frame/frame fid)))

(defn- entry [query-v]
  (get @(sub-cache) query-v))

(defn- ref-count [query-v]
  (:ref-count (entry query-v)))

(defn- error-id [e]
  (:rf.error/id (ex-data e)))

(defn- with-error-records
  "Run `thunk` with an always-on error-listener recording every fanned
  record; return `[thunk-result-or-thrown records]` where a throw is
  returned as `[:threw e]`."
  [thunk]
  (let [records (atom [])]
    (error-emit/register-error-listener!
      ::records (fn [record] (swap! records conj record)))
    (try
      (let [result (try [:ok (thunk)]
                        (catch #?(:clj Throwable :cljs :default) e
                          [:threw e]))]
        [result @records])
      (finally
        (error-emit/unregister-error-listener! ::records)))))

(defn- reg-items! []
  (rf/reg-sub :obs/items (fn [db _] (:items db))))

(defn- seed-items! [v]
  (frame/replace-app-db! fid {:items v}))

(defn- items-target []
  (obs/resolve-target {:frame fid :query-v [:obs/items]}))

;; ===========================================================================
;; resolve-target
;; ===========================================================================

(deftest resolve-target-explicit-pin-and-override
  (testing "an explicit frame pin resolves to a :subscription target carrying
            frame identity + the query — no node handle, no value"
    (let [t (obs/resolve-target {:frame fid :query-v [:obs/items]})]
      (is (= {:kind :subscription :frame-id fid :query [:obs/items]} t))))
  (testing "an override HIT resolves to the :story-override target — the
            pinned value IS the resolution"
    (let [t (obs/resolve-target {:query-v  [:obs/items]
                                 :override {:value 99 :override-id :o1 :version 7}})]
      (is (= {:kind :story-override :query [:obs/items]
              :value 99 :override-id :o1 :version 7} t)))))

(deftest resolve-target-ambient-rides-the-scope-chain
  (testing "ambient resolution (no explicit pin) delegates to the EP-0002
            scope/hold chain — the enclosing with-frame scope stamps the
            target's frame; the chain's own no-scope case is
            :rf.error/no-frame-context, pinned by the frame-resolution
            suites"
    (rf/with-frame fid
      (let [t (obs/resolve-target {:query-v [:obs/items]})]
        (is (= :subscription (:kind t)))
        (is (= fid (:frame-id t)))))))

;; ===========================================================================
;; probe — live, cold, evidence, errors
;; ===========================================================================

(deftest cold-probe-computes-pure-and-creates-nothing
  (reg-items!)
  (seed-items! [:a :b])
  (testing "a cold probe returns the computed value with cold-shaped node
            evidence and creates NO cache entry"
    (let [ev (obs/probe (items-target))]
      (is (= [:a :b] (:value ev)))
      (is (nil? (:node-version ev)) "nil node-version = probed cold, first-class")
      (is (nil? (:node-key ev)))
      (is (false? (:live? ev)))
      (is (int? (:frame-epoch ev)))
      (is (int? (:registry-epoch ev)))
      (is (nil? (entry [:obs/items])) "no cache entry materialised by the probe"))))

(deftest live-probe-reads-the-real-cache-node-without-taking-a-ref
  (reg-items!)
  (seed-items! [:a])
  (let [reaction (subs/subscribe [:obs/items] {:frame fid})]
    (is (some? reaction))
    (is (= 1 (ref-count [:obs/items])))
    (testing "probe against the live node reports live evidence and does NOT
              bump the ref-count (ownership-free)"
      (let [ev (obs/probe (items-target))]
        (is (= [:a] (:value ev)))
        (is (true? (:live? ev)))
        (is (int? (:node-version ev)))
        (is (int? (:node-key ev)))
        (is (= 1 (ref-count [:obs/items])) "probe took no reference")))
    (subs/unsubscribe fid [:obs/items])))

(deftest probe-unknown-entry-sub-throws-and-fans-always-on
  (testing "the port is fail-loud on an unknown ENTRY sub: typed throw + the
            always-on record reaches registered error listeners"
    (let [[[outcome e] records]
          (with-error-records
            #(obs/probe (obs/resolve-target {:frame fid :query-v [:obs/nope]})))]
      (is (= :threw outcome))
      (is (= :rf.error/no-such-sub (error-id e)))
      (is (some (fn [r] (and (= :rf.error/no-such-sub (:error r))
                             (= :obs/nope (:event-id r))))
                records)
          "the always-on axis saw the port-surface record"))))

(deftest probe-destroyed-frame-throws-frame-destroyed
  (reg-items!)
  (let [target (obs/resolve-target {:frame :obs/never-registered
                                    :query-v [:obs/items]})
        [[outcome e] records] (with-error-records #(obs/probe target))]
    (is (= :threw outcome))
    (is (= :rf.error/frame-destroyed (error-id e)))
    (is (some #(= :rf.error/frame-destroyed (:error %)) records))))

;; ===========================================================================
;; acquire! / read / release! — the real-cache graft gate
;; ===========================================================================

(deftest acquire-read-release-drive-the-real-cache-ref-count
  (reg-items!)
  (seed-items! [:a])
  (let [target (items-target)
        notes  (atom [])
        lease  (obs/acquire! target (fn [n] (swap! notes conj n)))]
    (testing "acquire! built the REAL cache node and took one reference"
      (is (obs/lease? lease))
      (is (true? (obs/owned? lease)))
      (is (some? (entry [:obs/items])))
      (is (= 1 (ref-count [:obs/items])))
      (is (= #{:reaction :inputs :ref-count} (set (keys (entry [:obs/items]))))
          "the cache entry key-set stays EXACTLY the Spec 006 §Cache shape —
           port bookkeeping never rides inside the entry"))
    (testing "acquire!/release! never invoke on-change synchronously
              ([S2-CONFIRM] no-sync-fan-out)"
      (is (empty? @notes)))
    (testing "read returns value + version + current epochs"
      (let [r (obs/read lease)]
        (is (= [:a] (:value r)))
        (is (= 0 (:version r)))
        (is (int? (:frame-epoch r)))
        (is (int? (:registry-epoch r)))))
    (testing "current? holds for the unchanged live lease"
      (is (true? (obs/current? lease target))))
    (testing "a second acquire shares the SAME canonical node (ref 2)"
      (let [lease2 (obs/acquire! target (fn [_]))]
        (is (= 2 (ref-count [:obs/items])))
        (obs/release! lease2)
        (is (= 1 (ref-count [:obs/items])) "release detached exactly one ref")))
    (testing "release! on the last owner disposes the slot synchronously
              (the 1 → 0 edge, in-tick)"
      (obs/release! lease)
      (is (nil? (entry [:obs/items])) "cache slot evicted in-tick"))
    (testing "release! is idempotent — a second call no-ops"
      (obs/release! lease)
      (is (nil? (entry [:obs/items]))))
    (testing "no notification was ever fanned for this lease"
      (is (empty? @notes)))))

(deftest acquire-shares-with-public-subscribe-refs
  (reg-items!)
  (seed-items! [:a])
  (let [_        (subs/subscribe [:obs/items] {:frame fid})
        target   (items-target)
        lease    (obs/acquire! target (fn [_]))]
    (is (= 2 (ref-count [:obs/items]))
        "port lease + public subscribe share ONE node, two refs")
    (obs/release! lease)
    (is (= 1 (ref-count [:obs/items]))
        "the public subscriber's ref survives the lease release")
    (is (some? (entry [:obs/items])))
    (subs/unsubscribe fid [:obs/items])
    (is (nil? (entry [:obs/items])))))

(deftest acquire-unknown-sub-and-destroyed-frame-throw
  (let [e1 (try (obs/acquire! (obs/resolve-target {:frame fid :query-v [:obs/nope]})
                              (fn [_]))
                (catch #?(:clj Throwable :cljs :default) e e))]
    (is (= :rf.error/no-such-sub (error-id e1))))
  (reg-items!)
  (let [e2 (try (obs/acquire! {:kind :subscription :frame-id :obs/never-registered
                               :query [:obs/items]}
                              (fn [_]))
                (catch #?(:clj Throwable :cljs :default) e e))]
    (is (= :rf.error/frame-destroyed (error-id e2)))))

(deftest read-after-release-is-a-typed-substrate-bug
  (reg-items!)
  (seed-items! [:a])
  (let [lease (obs/acquire! (items-target) (fn [_]))]
    (obs/release! lease)
    (let [[[outcome e] records] (with-error-records #(obs/read lease))]
      (is (= :threw outcome))
      (is (= :rf.error/read-after-release (error-id e)))
      (is (some #(= :rf.error/read-after-release (:error %)) records)
          "always-on record fanned before the throw"))))

;; ===========================================================================
;; acquire! on never-cached recovery reactions — rf2-vxgfnd.27
;;
;; `acquire!` IS the cache's ref-count attach. When the ENTRY node's OWN build
;; cannot produce a canonical cache node — a cyclic entry sub, a parametric
;; input-fn failure, or a frame destroyed mid-build — the build hands back a
;; NON-NIL but NEVER-CACHED, zero-ref recovery reaction. The port MUST NOT lease
;; it: a lying `owned?`-true lease is `current? false` from birth, so every
;; commit retargets and rebuilds a fresh orphan + node record + disposal hook
;; and re-emits — structural churn. The fix: the port classifies the recovery
;; and throws the matching typed error (fail-loud → the ViewCell error boundary;
;; the public subscribe surface keeps its recover-to-nil semantics).

(defn- dispose-trace-counter
  "Register a `:rf.sub/dispose` trace counter; returns `[count-atom unreg-fn]`."
  []
  (let [n (atom 0)]
    (rf/register-listener! :trace ::vxgfnd27-dispose
      (fn [ev] (when (= :rf.sub/dispose (:operation ev)) (swap! n inc))))
    [n #(rf/unregister-listener! :trace ::vxgfnd27-dispose)]))

(deftest acquire-on-cyclic-entry-sub-throws-sub-cycle-no-false-ownership
  (rf/reg-sub :obs/cyc1 :<- [:obs/cyc2] (fn [v _] v))
  (rf/reg-sub :obs/cyc2 :<- [:obs/cyc1] (fn [v _] v))
  (let [target             (obs/resolve-target {:frame fid :query-v [:obs/cyc1]})
        [disposals unreg]  (dispose-trace-counter)]
    (try
      (testing "acquire! fails loud with typed :rf.error/sub-cycle carrying the cycle path"
        (let [[[outcome e] records] (with-error-records #(obs/acquire! target (fn [_])))]
          (is (= :threw outcome) "acquire! did NOT return a (lying) lease")
          (is (= :rf.error/sub-cycle (error-id e)))
          (is (= [:obs/cyc1 :obs/cyc2 :obs/cyc1] (:cycle (ex-data e)))
              "the throw carries the closing-repeat cycle path")
          (is (empty? (filter #(= :rf.error/sub-cycle (:error %)) records))
              "sub-cycle stays DIAGNOSTIC — the port throws the typed carrier but
               does NOT promote it to the always-on axis")))
      (testing "NO false ownership was taken: no cache entry, no orphan node built+disposed"
        (is (nil? (entry [:obs/cyc1])) "the cyclic sub is NOT cached")
        (is (nil? (entry [:obs/cyc2])))
        (is (zero? @disposals) "no orphan node/hook was built and disposed"))
      (testing "repeated acquires each throw once and NEVER accrete ownership churn"
        (dotimes [_ 5]
          (is (= :rf.error/sub-cycle
                 (error-id (try (obs/acquire! target (fn [_]))
                                (catch #?(:clj Throwable :cljs :default) e e))))))
        (is (nil? (entry [:obs/cyc1])) "still no cache entry after repeated acquires")
        (is (zero? @disposals) "still zero disposals — no orphan churn accrued"))
      (finally (unreg)))))

(deftest acquire-on-parametric-input-fn-failure-throws-typed-no-false-ownership
  (testing "input-fn THROWS → :rf.error/sub-input-fn-exception; not cached; ONE always-on record"
    (rf/reg-sub :obs/pthrow
                (fn [_] (throw (ex-info "boom-input-fn" {})))
                (fn [_v _] :unreachable))
    (let [target (obs/resolve-target {:frame fid :query-v [:obs/pthrow]})
          [[outcome e] records] (with-error-records #(obs/acquire! target (fn [_])))]
      (is (= :threw outcome))
      (is (= :rf.error/sub-input-fn-exception (error-id e)))
      (is (nil? (entry [:obs/pthrow])) "the failed parametric node is NOT cached")
      (is (= 1 (count (filter #(= :rf.error/sub-input-fn-exception (:error %)) records)))
          "exactly ONE always-on record — the build's; the port re-throws the
           same id WITHOUT a second fan (no duplicate)")))
  (testing "input-fn RETURNS a bad shape → :rf.error/sub-input-fn-bad-return; not cached; ONE record"
    (rf/reg-sub :obs/pbad
                (fn [_] :not-a-vector-of-query-vectors)
                (fn [_v _] :unreachable))
    (let [target (obs/resolve-target {:frame fid :query-v [:obs/pbad]})
          [[outcome e] records] (with-error-records #(obs/acquire! target (fn [_])))]
      (is (= :threw outcome))
      (is (= :rf.error/sub-input-fn-bad-return (error-id e)))
      (is (nil? (entry [:obs/pbad])))
      (is (= 1 (count (filter #(= :rf.error/sub-input-fn-bad-return (:error %)) records)))
          "one always-on record, no duplicate fan from the port"))))

(deftest acquire-on-frame-destroyed-mid-build-throws-frame-destroyed
  ;; The JVM race: the frame's cache vanishes DURING the build (here an impure
  ;; input-fn destroys the frame), so build-and-cache!* returns a never-cached
  ;; reaction that used to bypass the caller's nil→frame-destroyed guard and be
  ;; leased as canonical. The port now classifies it and throws typed.
  (let [race-fid :obs/race-frame]
    (rf/make-frame {:id race-fid :adapter plain-atom/adapter})
    (rf/reg-sub :obs/race
                (fn [_] (frame/destroy-frame! race-fid) [])  ;; kills the frame mid-materialize
                (fn [_v _] :unreachable))
    (let [target (obs/resolve-target {:frame race-fid :query-v [:obs/race]})
          [[outcome e] records] (with-error-records #(obs/acquire! target (fn [_])))]
      (is (= :threw outcome) "the mid-build destroy no longer slips through as a lease")
      (is (= :rf.error/frame-destroyed (error-id e)))
      (is (some #(= :rf.error/frame-destroyed (:error %)) records)
          "the port fanned the always-on frame-destroyed record before throwing")
      (is (nil? (:sub-cache (frame/frame race-fid)))
          "the destroyed frame's cache is gone — nothing was cached under it"))))

;; ===========================================================================
;; movement evidence — node version, frame epoch, registry epoch
;; ===========================================================================

(deftest value-movement-advances-version-and-frame-epoch-at-read-points
  (reg-items!)
  (seed-items! [:a])
  (let [target (items-target)
        lease  (obs/acquire! target (fn [_]))
        r0     (obs/read lease)]
    (is (= 0 (:version r0)))
    (seed-items! [:a :b])
    (let [r1 (obs/read lease)]
      (is (= [:a :b] (:value r1)))
      (is (= 1 (:version r1))
          "the node version advanced on observed rf=-movement")
      (is (> (:frame-epoch r1) (:frame-epoch r0))
          "the frame commit epoch advanced with the frame-state install"))
    (testing "an rf=-equal reinstall does NOT advance the node version"
      (seed-items! [:a :b])
      (is (= 1 (:version (obs/read lease)))))
    (obs/release! lease)))

(deftest registry-epoch-advances-on-sub-registration
  (reg-items!)
  (seed-items! [:a])
  (let [re0 (:registry-epoch (obs/probe (items-target)))]
    (rf/reg-sub :obs/other (fn [db _] db))
    (is (> (:registry-epoch (obs/probe (items-target))) re0)
        "a :sub registration moved the registry epoch")))

;; ===========================================================================
;; HMR — disposal notification queue, cause :hmr, no pinned disposed node
;; ===========================================================================

(deftest hmr-reregistration-notifies-former-owners-once-with-cause-hmr
  (reg-items!)
  (seed-items! [:a])
  (let [target (items-target)
        notes  (atom [])
        lease  (obs/acquire! target (fn [n] (swap! notes conj n)))
        old-entry (entry [:obs/items])]
    (is (= 1 (ref-count [:obs/items])))
    ;; The re-registration: cache invalidation disposes the canonical node,
    ;; then the port's replacement hook drains the queued former-owner
    ;; notifications — synchronously, at the boundary the re-registration
    ;; closes ([S2-CONFIRM] queue alignment).
    (reg-items!)
    (is (= 1 (count @notes))
        "exactly ONE coalesced notification per lease, delivered by the time
         reg-sub returned")
    (is (= :hmr (:cause (first @notes))))
    (is (= target (:target (first @notes))))
    (testing "current? treats the disposed node as not-current → retarget"
      (is (false? (obs/current? lease target))))
    (testing "the next acquire re-resolves the NEW canonical node"
      (let [lease2 (obs/acquire! target (fn [_]))]
        (is (not (identical? (:reaction old-entry)
                             (:reaction (entry [:obs/items]))))
            "the cache holds a fresh node, not the disposed one")
        (is (= 1 (ref-count [:obs/items])))
        (testing "release! on the stale lease is a no-op (identity-guarded) —
                  it can never decrement the NEW node's ref"
          (obs/release! lease)
          (is (= 1 (ref-count [:obs/items]))))
        (obs/release! lease2)
        (is (nil? (entry [:obs/items])))))))

;; ===========================================================================
;; rf2-vxgfnd.32 — first-owner disposal-hook install races node disposal
;;
;; PR #5710 enrols the first active owner (marking the node record :hooked?)
;; and THEN, as a SEPARATE step, installs the one node-scoped disposal hook via
;; interop/add-on-dispose!. A disposal that linearizes in that gap fires no hook
;; (the callback lands on an already-disposed reaction and is silently lost —
;; every substrate's -dispose snapshot-and-clears its callbacks first; the JVM
;; Reaction's field is even unsynchronized). Pre-fix the acquired lease is left
;; :hooked? with a dead callback and receives NO invalidation. The fix closes
;; the handshake with a canonicality re-check in acquire! that self-drains the
;; staged owners when the reaction is no longer the frame's live cache node.
;;
;; Deterministic race (no sleeps, both hosts): wrap interop/add-on-dispose! so
;; the FIRST-owner hook install — the call that targets the already-CANONICAL
;; cached reaction (the construction-time input-release closure is wired BEFORE
;; the reaction is installed, so the entry does not yet hold it — the identity
;; gate skips it) — first disposes+evicts the node, then registers the dead
;; callback. This linearizes disposal EXACTLY in the enrol → hook-install gap.
;; ===========================================================================

(defn- force-dispose-node!
  "Simulate a real disposal winning a race: evict the cache entry (as EVERY
  real disposal path does — swap the cache atom BEFORE interop/dispose!), then
  dispose the reaction. Bypasses the ref-count guard the way HMR re-registration
  and frame-destroy eviction do."
  [query-v]
  (let [cache    (sub-cache)
        reaction (:reaction (entry query-v))]
    (swap! cache dissoc query-v)
    (interop/dispose! reaction)))

;; Delivery of the :disposed fallback is queued and rides interop/next-tick
;; (an async executor on the JVM; an unfired-mid-run microtask on CLJS-node).
;; Swallow next-tick so no async drain fires during the test, assert the
;; handshake enqueued WITHOUT any synchronous fan-out, then drive the drain
;; boundary deterministically via the port's own drain-pending-disposals!
;; (its documented test seam) — no sleeps as the ordering mechanism, identical
;; on both hosts.

(deftest first-owner-hook-install-races-disposal-invalidation-still-delivered
  (reg-items!)
  (seed-items! [:a])
  (let [target   (items-target)
        notes    (atom [])
        real-add interop/add-on-dispose!
        raced?   (atom false)]
    (with-redefs
      [interop/next-tick (fn [_f] nil)
       interop/add-on-dispose!
       (fn [reaction f]
         ;; Race ONLY the observation node-disposed hook install — it targets
         ;; the reaction once it is the CANONICAL cached node. The
         ;; construction-time input-release closure is wired before the entry
         ;; holds the reaction, so this identity gate skips it.
         (when (and (identical? reaction (:reaction (entry [:obs/items])))
                    (compare-and-set! raced? false true))
           (force-dispose-node! [:obs/items]))
         (real-add reaction f))]
      (let [lease (obs/acquire! target (fn [n] (swap! notes conj n)))]
        (is (true? @raced?) "the race fired at the first-owner hook install")
        (is (nil? (entry [:obs/items]))
            "the racing disposal evicted the canonical node during the gap")
        (testing "acquire! never invokes on-change synchronously — the
                  handshake self-drain only ENQUEUES ([S2-CONFIRM]
                  no-sync-fan-out)"
          (is (empty? @notes) "no notification fired on the acquire stack"))
        (testing "the lease is NOT current — the node was disposed under it"
          (is (false? (obs/current? lease target))))
        ;; Drive the queued drain boundary deterministically.
        (obs/drain-pending-disposals! :disposed)
        (testing "the invalidation is STILL delivered — never silently lost to
                  a dead first-owner hook (rf2-vxgfnd.32)"
          (is (= 1 (count @notes))
              "the raced first owner received exactly one disposal notification")
          (is (= :disposed (:cause (first @notes)))))
        (testing "release! on the raced lease is a clean, identity-guarded no-op"
          (obs/release! lease)
          (is (nil? (entry [:obs/items]))))))))

(deftest first-owner-hook-race-drains-every-staged-owner-exactly-once
  ;; Two concurrent acquirers on ONE node: L2 enrols behind L1's :hooked? flag
  ;; (a cache HIT — registers no hook of its own), and disposal wins the gap
  ;; during L1's hook install. Neither may be left un-notified: acquire!'s
  ;; re-check self-drains ALL current owners (take-owners! is the single-drain
  ;; point), so L1 AND L2 each receive exactly one invalidation.
  (reg-items!)
  (seed-items! [:a])
  (let [target   (items-target)
        notes1   (atom [])
        notes2   (atom [])
        l2       (atom nil)
        real-add interop/add-on-dispose!
        raced?   (atom false)]
    (with-redefs
      [interop/next-tick (fn [_f] nil)
       interop/add-on-dispose!
       (fn [reaction f]
         (when (and (identical? reaction (:reaction (entry [:obs/items])))
                    (compare-and-set! raced? false true))
           ;; A second owner stages behind the first owner's not-yet-installed
           ;; hook, THEN disposal wins the gap.
           (reset! l2 (obs/acquire! target (fn [n] (swap! notes2 conj n))))
           (force-dispose-node! [:obs/items]))
         (real-add reaction f))]
      (let [l1 (obs/acquire! target (fn [n] (swap! notes1 conj n)))]
        (is (true? @raced?))
        (is (nil? (entry [:obs/items])) "the node was disposed during the gap")
        (is (some? @l2) "the second owner staged during the race")
        (testing "no synchronous fan-out from inside either acquire!"
          (is (empty? @notes1))
          (is (empty? @notes2)))
        (obs/drain-pending-disposals! :disposed)
        (testing "BOTH staged owners were notified exactly once — no owner is
                  left behind an uninstalled hook (rf2-vxgfnd.32)"
          (is (= 1 (count @notes1)) "first owner notified once")
          (is (= 1 (count @notes2)) "second owner notified once"))
        (obs/release! l1)
        (obs/release! @l2)))))

(deftest first-owner-non-raced-acquire-installs-live-hook-no-self-drain
  ;; The common path must be untouched: with no racing disposal, acquire!'s
  ;; handshake re-check finds the reaction canonical and does NOT self-drain;
  ;; the installed hook remains the single delivery channel on real disposal.
  (reg-items!)
  (seed-items! [:a])
  (let [target (items-target)
        notes  (atom [])
        lease  (obs/acquire! target (fn [n] (swap! notes conj n)))]
    (testing "canonical acquire took one ref and enqueued nothing"
      (is (= 1 (ref-count [:obs/items])))
      (is (empty? @notes))
      (is (true? (obs/current? lease target))))
    (testing "a real HMR disposal delivers via the installed hook exactly once"
      (reg-items!)
      (is (= 1 (count @notes)))
      (is (= :hmr (:cause (first @notes))))
      (obs/release! lease))))

(deftest reentrant-graph-op-is-dev-asserted-inside-the-fan-out
  (reg-items!)
  (seed-items! [:a])
  (let [caught (atom nil)
        lease-ref (atom nil)
        lease  (obs/acquire! (items-target)
                             (fn [_n]
                               ;; graph mutation from inside the fan-out —
                               ;; must throw :rf.error/reentrant-graph-op
                               (try (obs/release! @lease-ref)
                                    (catch #?(:clj Throwable :cljs :default) e
                                      (reset! caught (error-id e))))))]
    (reset! lease-ref lease)
    ;; drive a fan-out via the HMR path
    (reg-items!)
    (is (= :rf.error/reentrant-graph-op @caught))
    (testing "the reentrant release was rejected — the lease is still live
              and releasable outside the fan-out"
      (obs/release! lease))))

;; ===========================================================================
;; rollback release order ([S2-CONFIRM] — reverse acquisition order)
;; ===========================================================================

(deftest staged-rollback-releases-in-reverse-order-shared-nodes-survive
  (rf/reg-sub :obs/leaf (fn [db _] (:leaf db)))
  (rf/reg-sub :obs/solo (fn [db _] (:solo db)))
  (frame/replace-app-db! fid {:leaf 1 :solo 2})
  ;; A prior owner holds the shared node (the "prior committed set").
  (subs/subscribe [:obs/leaf] {:frame fid})
  (is (= 1 (ref-count [:obs/leaf])))
  ;; Stage-acquire two leases in order (leaf then solo), then unwind in
  ;; REVERSE acquisition order — the k-th-failure rollback shape.
  (let [l1 (obs/acquire! (obs/resolve-target {:frame fid :query-v [:obs/leaf]}) (fn [_]))
        l2 (obs/acquire! (obs/resolve-target {:frame fid :query-v [:obs/solo]}) (fn [_]))]
    (is (= 2 (ref-count [:obs/leaf])))
    (is (= 1 (ref-count [:obs/solo])))
    ;; reverse order: l2 first, then l1
    (obs/release! l2)
    (is (nil? (entry [:obs/solo]))
        "the solo staged node disposed on its zero-owner edge")
    (obs/release! l1)
    (is (= 1 (ref-count [:obs/leaf]))
        "the shared node survived the rollback — its prior owner is intact")
    (subs/unsubscribe fid [:obs/leaf])
    (is (nil? (entry [:obs/leaf])))))

;; ===========================================================================
;; static override lease
;; ===========================================================================

(deftest static-override-lease-honest-ownership-uniform-commit-path
  (let [target {:kind :story-override :query [:obs/items]
                :value 99 :override-id :o1 :version 7}
        lease  (obs/acquire! target (fn [_] (throw (ex-info "never" {}))))]
    (testing "no callback is registered and ownership is reported honestly"
      (is (obs/lease? lease))
      (is (false? (obs/owned? lease))))
    (testing "read yields the pinned value + override version"
      (is (= {:value 99 :version 7} (obs/read lease))))
    (testing "current? holds while the site's override id/version match"
      (is (true? (obs/current? lease target)))
      (is (false? (obs/current? lease (assoc target :version 8)))
          "a moved override version retargets through the normal staged path")
      (is (false? (obs/current? lease {:kind :subscription :frame-id fid
                                       :query [:obs/items]}))
          "a kind flip (override removed) retargets"))
    (testing "release! is a no-op; read still serves the pinned value"
      (obs/release! lease)
      (is (= {:value 99 :version 7} (obs/read lease)))))
  (testing "probe on an override target is cold-shaped pinned evidence"
    (let [ev (obs/probe {:kind :story-override :query [:obs/items]
                         :value 42 :override-id :o2 :version 1})]
      (is (= 42 (:value ev)))
      (is (false? (:live? ev)))
      (is (nil? (:node-version ev))))))

;; ===========================================================================
;; cold-probe edge set ([S2-CONFIRM] — confirmed/corrected)
;; ===========================================================================

(deftest cold-probe-unknown-mid-graph-input-emits-and-substitutes-nil
  (rf/reg-sub :obs/top :<- [:obs/missing-input] (fn [v _] [:got v]))
  (let [[[outcome ev] records]
        (with-error-records
          #(obs/probe (obs/resolve-target {:frame fid :query-v [:obs/top]})))]
    (is (= :ok outcome))
    (is (= [:got nil] (:value ev))
        "nil substituted for the unknown mid-graph input; the body still ran")
    (is (some (fn [r] (and (= :rf.error/no-such-sub (:error r))
                           (= :obs/missing-input (:event-id r))))
              records)
        "one always-on :rf.error/no-such-sub error event — identical to the
         reactive graph's contract")))

(deftest cold-probe-sub-body-throw-follows-graph-recovery
  ;; CORRECTED [S2-CONFIRM]: a body throw during a probe follows the graph's
  ;; own recovery (emit + nil) — identically cold and live, so probe
  ;; temperature is never observable.
  (rf/reg-sub :obs/boom (fn [_ _] (throw (ex-info "boom" {}))))
  (let [[[outcome ev] records]
        (with-error-records
          #(obs/probe (obs/resolve-target {:frame fid :query-v [:obs/boom]})))]
    (is (= :ok outcome) "the probe did not propagate the body throw")
    (is (nil? (:value ev)) "recovered to nil, exactly like the reactive graph")
    (is (some #(= :rf.error/sub-exception (:error %)) records)
        "the always-on :rf.error/sub-exception fired")))

(deftest cold-probe-cycle-recovers-structurally
  (rf/reg-sub :obs/c1 :<- [:obs/c2] (fn [v _] v))
  (rf/reg-sub :obs/c2 :<- [:obs/c1] (fn [v _] v))
  (let [ev (obs/probe (obs/resolve-target {:frame fid :query-v [:obs/c1]}))]
    (is (nil? (:value ev))
        "a :<- cycle under a cold probe recovers to nil via the structured
         :rf.error/sub-cycle — no raw stack overflow")))

;; ===========================================================================
;; the slice-scoped probe memo
;; ===========================================================================

(deftest slice-memo-shares-derivation-parents-within-a-slice
  (let [parent-runs (atom 0)]
    (rf/reg-sub :obs/parent (fn [db _] (swap! parent-runs inc) (:n db)))
    (rf/reg-sub :obs/a :<- [:obs/parent] (fn [n _] [:a n]))
    (rf/reg-sub :obs/b :<- [:obs/parent] (fn [n _] [:b n]))
    (frame/replace-app-db! fid {:n 5})
    (reset! parent-runs 0)
    (let [memo (obs/make-slice-memo)
          eva  (obs/probe (obs/resolve-target {:frame fid :query-v [:obs/a]}) memo)
          evb  (obs/probe (obs/resolve-target {:frame fid :query-v [:obs/b]}) memo)]
      (is (= [:a 5] (:value eva)))
      (is (= [:b 5] (:value evb)))
      (is (= 1 @parent-runs)
          "the shared parent computed ONCE per slice, not once per sibling"))
    (testing "the memo is invalidated on frame-state movement (the
              belt-and-braces (frame, frame-epoch, registry-epoch) tag)"
      (let [memo (obs/make-slice-memo)]
        (obs/probe (obs/resolve-target {:frame fid :query-v [:obs/a]}) memo)
        (let [runs-before @parent-runs]
          (frame/replace-app-db! fid {:n 6})
          (let [ev (obs/probe (obs/resolve-target {:frame fid :query-v [:obs/a]}) memo)]
            (is (= [:a 6] (:value ev)) "no stale memoized value served")
            (is (> @parent-runs runs-before) "the parent recomputed")))))))

;; ===========================================================================
;; the leak fixture — 10k cold probes retain zero
;; ===========================================================================

(deftest ten-thousand-cold-probes-retain-zero
  (rf/reg-sub :obs/leaf2 (fn [db _] (:leaf db)))
  (rf/reg-sub :obs/sum :<- [:obs/leaf2] (fn [v _] [:sum v]))
  (frame/replace-app-db! fid {:leaf 3})
  (let [cache-count-before   (count @(sub-cache))
        #?@(:clj [node-records-before (.size ^java.util.Map @#'obs/node-records)])
        dispose-traces        (atom 0)]
    (rf/register-listener! :trace ::dispose-watch
      (fn [ev] (when (= :rf.sub/dispose (:operation ev))
                 (swap! dispose-traces inc))))
    (try
      ;; alternate shared-memo and memo-less probes; both must retain zero
      (let [memo (obs/make-slice-memo)]
        (dotimes [i 10000]
          (let [ev (obs/probe (obs/resolve-target {:frame fid :query-v [:obs/sum]})
                              (when (even? i) memo))]
            (when (zero? i)
              (is (= [:sum 3] (:value ev)))))))
      (testing "no cache entries, no disposal obligations, no node records"
        (is (= cache-count-before (count @(sub-cache)))
            "10k cold probes created ZERO cache entries")
        (is (nil? (entry [:obs/sum])))
        (is (nil? (entry [:obs/leaf2])))
        (is (zero? @dispose-traces)
            "no disposal obligations were created (nothing disposed)")
        #?(:clj (is (<= (.size ^java.util.Map @#'obs/node-records)
                        node-records-before)
                    "cold probes never touch the weak node-record table")))
      (finally
        (rf/unregister-listener! :trace ::dispose-watch)))))

;; ===========================================================================
;; the released-lease retention fixture — rf2-vxgfnd.15
;;
;; Adversarial to the exact leak the 10k-cold-probe fixture CANNOT catch (cold
;; probes take no lease, register no callback). A permanent owner keeps a
;; shared layer-1 node live while N leases acquire/release against the SAME
;; target — the app-shell-subscription-stays-live-for-the-process shape. Every
;; released lease must retain ZERO disposal callbacks and leave the node's
;; active-owner set at the permanent baseline: disposal work O(current owners),
;; never O(all owners ever acquired). On today's per-lease-hook code the
;; reaction retains one dormant closure per released lease (1000 leaked) and
;; this fixture fails; after the node-scoped-hook fix it stays O(1).
;; ===========================================================================

#?(:clj
   (defn- reaction-dispose-callback-count
     "Count the on-dispose callbacks currently stored on a JVM plain-atom
     `re-frame.interop/Reaction` by reading its private mutable `callbacks`
     field reflectively. JVM-only: the CLJS reify keeps them in a closed-over
     atom that is not externally reachable, so the callback-STORAGE assertion
     is the JVM's; both hosts assert the active-owner set via
     `obs/active-owner-count`."
     [reaction]
     (let [f (.getDeclaredField (class reaction) "callbacks")]
       (.setAccessible f true)
       (count (.get f reaction)))))

(deftest released-leases-retain-no-disposal-callbacks-on-a-shared-live-node
  (reg-items!)
  (seed-items! [:a])
  (let [target    (items-target)
        noop      (fn [_])
        n         1000
        ;; The permanent owner keeps the shared node live (ref-count never
        ;; reaches 0) — the app-shell subscription that lives for the process.
        permanent (obs/acquire! target noop)
        reaction  (:reaction (entry [:obs/items]))
        #?@(:clj [callbacks-baseline (reaction-dispose-callback-count reaction)])]
    (is (= 1 (ref-count [:obs/items])))
    (is (= 1 (obs/active-owner-count reaction))
        "the permanent owner is the only active owner at baseline")
    ;; Churn: N acquire/release pairs against the SAME live node.
    (dotimes [_ n]
      (obs/release! (obs/acquire! target noop)))
    (testing "the node survived the churn on the permanent owner's reference"
      (is (= 1 (ref-count [:obs/items])))
      (is (some? (entry [:obs/items]))))
    (testing "released leases left the active-owner set at the permanent
              baseline — not one historical owner retained"
      (is (= 1 (obs/active-owner-count reaction))
          "active owners = {permanent}, not {permanent + N released}"))
    #?(:clj
       (testing "disposal-callback STORAGE stayed O(1) — released leases
                 retained ZERO dormant closures (the rf2-vxgfnd.15 leak)"
         (is (= callbacks-baseline (reaction-dispose-callback-count reaction))
             (str "the reaction retained a dormant disposal closure per "
                  "released lease: baseline " callbacks-baseline
                  ", after " n " acquire/release pairs "
                  (reaction-dispose-callback-count reaction)
                  " (retained-released-lease-callbacks="
                  (- (reaction-dispose-callback-count reaction) callbacks-baseline)
                  ")"))))
    (testing "the permanent owner is still notified once on eventual disposal —
              the node-scoped hook drains the CURRENT owner, not history"
      (let [notes (atom [])
            keep  (obs/acquire! target (fn [ev] (swap! notes conj ev)))]
        (obs/release! permanent)
        ;; `keep` is now the sole owner; re-registration disposes the node and
        ;; drains its ONE active owner exactly once.
        (reg-items!)
        (is (= 1 (count @notes)) "exactly one former-owner notification")
        (is (= :hmr (:cause (first @notes))))
        (obs/release! keep)))))

;; ===========================================================================
;; ABI guard
;; ===========================================================================

(deftest port-abi-version-guard
  (is (nil? (obs/assert-port-abi-version! obs/port-abi-version))
      "a matching consumer boots clean")
  (let [[[outcome e] records]
        (with-error-records #(obs/assert-port-abi-version! -999))]
    (is (= :threw outcome))
    (is (= :rf.error/observation-port-version-mismatch (error-id e)))
    (is (= {:expected -999 :actual obs/port-abi-version}
           (select-keys (ex-data e) [:expected :actual])))
    (is (some #(= :rf.error/observation-port-version-mismatch (:error %)) records)
        "the boot skew fans the always-on record before throwing")))
