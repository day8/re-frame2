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
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.string :as str])
            [re-frame.core                  :as rf]
            [re-frame.error-emit            :as error-emit]
            [re-frame.frame                 :as frame]
            [re-frame.live-frame            :as live-frame]
            [re-frame.interop               :as interop]
            [re-frame.source-coords         :as source-coords]
            [re-frame.subs                  :as subs]
            [re-frame.subs.cache            :as subs-cache]
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

(defn- container-watch-count
  "Count the watches currently registered on a `clojure.core/atom` container,
  portably across hosts. The plain-atom frame-state container is a bare atom
  (`re-frame.substrate.atom-container/make-state-container`); a leaking read
  that `add-watch`'d it (a cold probe must never subscribe) would bump this."
  [container]
  #?(:clj  (count (.getWatches ^clojure.lang.IRef container))
     :cljs (count (.-watches container))))

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

#?(:clj
   (deftest canonical-read-holds-one-strong-reaction-across-the-jvm-gc-gap
     (reg-items!)
     (seed-items! [:a])
     (let [target       (items-target)
           lease        (obs/acquire! target (fn [_]))
           expected     (obs/read lease)
           reaction-var (ns-resolve 're-frame.substrate.observation
                                    'lease-reaction)
           original     (var-get reaction-var)
           calls        (atom 0)]
       (try
         ;; Deterministically model the WeakReference clearing between the old
         ;; canonicality check and its second lookup in read. The corrected read
         ;; resolves once and holds that reaction strongly for the whole branch.
         (with-redefs-fn
           {reaction-var
            (fn [state]
              (if (= 1 (swap! calls inc))
                (original state)
                nil))}
           #(is (= expected (obs/read lease))
                "a canonical JVM read cannot deref nil after the GC gap"))
         (is (= 1 @calls) "read resolves the weak reaction exactly once")
         (finally
           (obs/release! lease))))))

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

(deftest hmr-disposal-notification-carries-post-bump-registry-epoch
  ;; rf2-vxgfnd.36(a): a re-registration-driven `:hmr` disposal notification must
  ;; carry the SAME registry-epoch a probe issued right AFTER the re-registration
  ;; reports — no PHANTOM movement. The epoch bump for a re-registration rides the
  ;; replacement hook (which also drains `:hmr`) and fires BEFORE the drain, so the
  ;; notification reads the post-bump epoch. Pre-fix the bump rode the registration
  ;; hook (which registrar runs AFTER the replacement/drain phase), so the
  ;; notification read the PRE-bump epoch while the next probe read the POST-bump
  ;; one — a consumer diffing the two saw phantom registry movement for the very
  ;; re-registration that caused the disposal.
  (reg-items!)
  (seed-items! [:a])
  (let [target (items-target)
        notes  (atom [])
        _lease (obs/acquire! target (fn [n] (swap! notes conj n)))]
    (reg-items!)                                   ;; the HMR re-registration
    (is (= 1 (count @notes)))
    (is (= :hmr (:cause (first @notes))))
    (let [note-epoch  (:registry-epoch (first @notes))
          probe-epoch (:registry-epoch (obs/probe target))]
      (is (= note-epoch probe-epoch)
          "the :hmr notification carries the post-bump registry-epoch — the value
           the next probe reports — so no phantom movement is visible"))))

(deftest malformed-target-kind-throws-typed-not-a-bare-host-error
  ;; rf2-vxgfnd.36(b): the target-taking port ops' `(case (:kind target) …)` had
  ;; NO default arm, so a target whose :kind is neither :subscription nor
  ;; :story-override fell through to a BARE host error ("No matching clause"),
  ;; violating Spec 006's "every port op throws typed". Both ops now throw the
  ;; typed :rf.error/observation-malformed-target.
  (reg-items!)
  (testing "probe on a malformed :kind throws the typed error, not a bare host error"
    (let [e (try (obs/probe {:kind :bogus :query [:obs/items]})
                 (catch #?(:clj Throwable :cljs :default) e e))]
      (is (= :rf.error/observation-malformed-target (error-id e)))
      (is (= :bogus (:kind (ex-data e))) "the throw carries the malformed :kind")))
  (testing "acquire! on a malformed :kind throws the same typed error"
    (let [e (try (obs/acquire! {:kind :bogus :query [:obs/items]} (fn [_]))
                 (catch #?(:clj Throwable :cljs :default) e e))]
      (is (= :rf.error/observation-malformed-target (error-id e))))))

;; ===========================================================================
;; rf2-vxgfnd.183 — the CLOSED target + lease grammar at EVERY port boundary
;;
;; #5797 (rf2-vxgfnd.36) typed ONLY the unknown-`:kind` default arm. A
;; KNOWN-discriminator target with a malformed `:query`, an absent / wrong-domain
;; frame identity, or an INCOMPLETE `:story-override` still entered the accepted
;; arm and reached a host op — leaking an untyped `(first query)` / frame-registry
;; error the ViewCell cannot classify. Separately, `read` / `release!` deref the
;; lease state with no validation, so `(read nil)` / `(release! nil)` threw a raw
;; NPE (JVM) / untyped host error (CLJS) with `(:rf.error/id (ex-data e)) == nil`.
;; These are the bead's three repro steps as RED-before-fix fixtures: each throws
;; the TYPED id, never a bare host error.
;; ===========================================================================

(defn- caught
  "Run `thunk`, returning the thrown error, or `::no-throw` when it returns —
  so `error-id` of a non-throw is nil and the typed-id assertion fails loudly
  (a bug that skipped the throw is caught, not silently passed)."
  [thunk]
  (try (thunk) ::no-throw
       (catch #?(:clj Throwable :cljs :default) e e)))

(deftest malformed-subscription-query-throws-typed-target
  ;; Repro 1: a :subscription target whose :query is a non-vector / empty /
  ;; non-keyword-headed vector enters the accepted arm and reaches `(first query)`
  ;; — pre-fix a raw ISeq host error (`42`) or a mis-reported :rf.error/no-such-sub
  ;; (`[]` / `[42]`). It must throw the typed target error BEFORE any host op.
  (reg-items!)
  (doseq [[label q] [[:non-vector  42]
                     [:empty       []]
                     [:non-kw-head [42]]
                     [:string      "not-a-query"]
                     [:list        '(:obs/items)]]]
    (testing (str "probe on a malformed :query (" (name label) ")")
      (is (= :rf.error/observation-malformed-target
             (error-id (caught #(obs/probe {:kind :subscription :frame-id fid :query q}))))))
    (testing (str "acquire! on a malformed :query (" (name label) ")")
      (is (= :rf.error/observation-malformed-target
             (error-id (caught #(obs/acquire! {:kind :subscription :frame-id fid :query q}
                                              (fn [_])))))))))

(deftest malformed-subscription-frame-identity-throws-typed-target
  ;; Repro 1 (missing / invalid frame identity): an ABSENT, nil, or wrong-domain
  ;; :frame-id must throw the typed target error, not fall through to
  ;; `(frame/frame nil)` and mis-report :rf.error/frame-destroyed.
  (reg-items!)
  (doseq [[label t] [[:missing-frame-id     {:kind :subscription :query [:obs/items]}]
                     [:nil-frame-id         {:kind :subscription :frame-id nil :query [:obs/items]}]
                     [:non-keyword-frame-id {:kind :subscription :frame-id "app" :query [:obs/items]}]]]
    (testing (str "probe (" (name label) ")")
      (is (= :rf.error/observation-malformed-target
             (error-id (caught #(obs/probe t))))))
    (testing (str "acquire! (" (name label) ")")
      (is (= :rf.error/observation-malformed-target
             (error-id (caught #(obs/acquire! t (fn [_])))))))))

(deftest non-map-target-throws-typed-target
  ;; A non-map target (nil, a scalar, a vector, a set) is malformed — `(:kind …)`
  ;; on a non-map is nil, which pre-fix hit the freshly-added default only for a
  ;; MAP; the shared validator rejects non-maps up front too.
  (reg-items!)
  (doseq [t [nil 42 "target" [:kind :subscription] #{:kind :subscription}]]
    (is (= :rf.error/observation-malformed-target
           (error-id (caught #(obs/probe t))))
        (str "probe on non-map " (pr-str t)))
    (is (= :rf.error/observation-malformed-target
           (error-id (caught #(obs/acquire! t (fn [_])))))
        (str "acquire! on non-map " (pr-str t)))))

(deftest incomplete-story-override-throws-typed-target
  ;; Repro 2: an incomplete {:kind :story-override} was SILENTLY accepted and
  ;; produced a nil-shaped observation / static lease (no throw at all). It could
  ;; not have come from resolve-target, so it must throw the typed target error.
  (reg-items!)
  (doseq [[label t] [[:bare          {:kind :story-override}]
                     [:query-only    {:kind :story-override :query [:obs/items]}]
                     [:missing-token {:kind :story-override :query [:obs/items] :value 1 :version 0}]
                     [:extra-key     {:kind :story-override :query [:obs/items] :value 1
                                      :override-id :o :version 0 :surplus true}]]]
    (testing (str "probe (" (name label) ")")
      (is (= :rf.error/observation-malformed-target
             (error-id (caught #(obs/probe t))))))
    (testing (str "acquire! (" (name label) ")")
      (is (= :rf.error/observation-malformed-target
             (error-id (caught #(obs/acquire! t (fn [_])))))))))

(deftest complete-story-override-with-nil-value-is-a-value-not-malformed
  ;; Acceptance: presence checks distinguish a legitimate nil override value /
  ;; token (KEY present) from a missing required key. A COMPLETE override with
  ;; :value nil probes to nil evidence and acquires a static lease — no throw.
  (reg-items!)
  (let [t {:kind :story-override :query [:obs/items]
           :value nil :override-id :ov :version 0}]
    (is (nil? (:value (obs/probe t))) "nil override value probes to nil, no throw")
    (let [lease (obs/acquire! t (fn [_]))]
      (is (false? (obs/owned? lease)) "the override lease owns nothing")
      (is (nil? (:value (obs/read lease))) "read yields the pinned nil value")
      (is (true? (obs/current? lease t)) "the override lease is current against its target")
      (obs/release! lease))))

(deftest malformed-target-throws-do-not-fan-the-always-on-axis
  ;; The malformed-target category is DIAGNOSTIC (a programmer defect,
  ;; unreachable in correct generated code) — it must NOT fan the always-on
  ;; error-emit axis (Spec 009).
  (reg-items!)
  (let [[[outcome e] records]
        (with-error-records #(obs/acquire! {:kind :subscription :frame-id fid :query 42}
                                           (fn [_])))]
    (is (= :threw outcome))
    (is (= :rf.error/observation-malformed-target (error-id e)))
    (is (empty? records)
        "diagnostic malformed-target does NOT fan the always-on axis")))

(deftest read-and-release-reject-a-non-lease-typed
  ;; Repro 3: (read nil) / (release! nil) threw a raw NPE (JVM) / untyped host
  ;; error (CLJS) with (:rf.error/id (ex-data e)) == nil — the half-hardened
  ;; boundary. nil, a map, and any arbitrary host object must now throw the typed
  ;; :rf.error/observation-malformed-lease on both hosts.
  (doseq [bad [nil {} {:lease-kind :node} "lease" 42 [:not :a :lease]]]
    (testing (str "read on a non-lease " (pr-str bad))
      (is (= :rf.error/observation-malformed-lease
             (error-id (caught #(obs/read bad))))))
    (testing (str "release! on a non-lease " (pr-str bad))
      (is (= :rf.error/observation-malformed-lease
             (error-id (caught #(obs/release! bad))))))))

(deftest malformed-lease-throws-do-not-fan-the-always-on-axis
  ;; The malformed-lease category is DIAGNOSTIC — it must NOT fan the always-on
  ;; axis (Spec 009), unlike the sibling :rf.error/read-after-release.
  (let [[[outcome e] records] (with-error-records #(obs/read nil))]
    (is (= :threw outcome))
    (is (= :rf.error/observation-malformed-lease (error-id e)))
    (is (empty? records)
        "diagnostic malformed-lease does NOT fan the always-on axis")))

(deftest current?-on-a-non-lease-is-false-no-throw
  ;; current? is a pure no-throw kept-check predicate: a non-lease reads FALSE
  ;; rather than field-accessing lease-state and throwing (its ruled malformed-
  ;; value contract). Pre-fix `@(lease-state nil)` threw a raw NPE.
  (reg-items!)
  (let [target (items-target)]
    (doseq [bad [nil {} {:lease-kind :node} "lease" 42]]
      (is (false? (obs/current? bad target))
          (str "current? on a non-lease " (pr-str bad) " is false, never throws")))))

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

;; ===========================================================================
;; rf2-r8jmdb / rf2-x76af2.34 FINDING 1 — the disposal cause is INTRINSIC to
;; why the node died, not decided by which drain boundary fires.
;;
;; The port queues former-owner notifications; pre-fix the queue stored bare
;; leases and the drain boundary STAMPED the cause (the registrar HMR hook
;; drained the whole queue :hmr; the next-tick fallback drained it :disposed).
;; So a frame-destroy / cache-clear lease STILL PENDING when an unrelated :sub
;; HMR re-registration drained was swept into the :hmr drain and delivered
;; {:cause :hmr} — a documented on-change payload contract violation (a consumer
;; branching :hmr = re-acquire vs :disposed = gone would re-acquire against a
;; destroyed frame → :rf.error/frame-destroyed → view error boundary; the #5752
;; CI symptom: evidence-target saw #{:disposed :hmr}). The fix stores each entry
;; as a [lease cause] pair whose cause is INTRINSIC (captured at enqueue time
;; from the disposing cache site's *disposal-cause*): the :hmr drain takes only
;; :hmr-tagged entries, leaving the :disposed cache-clear lease for the next-tick
;; fallback, which delivers it its OWN :disposed cause.
;; ===========================================================================

(deftest disposed-cause-lease-pending-during-hmr-drain-keeps-its-disposed-cause
  (reg-items!)
  (seed-items! [:a])
  (let [target (items-target)
        notes  (atom [])]
    ;; Swallow next-tick so the :disposed fallback does not auto-drain — we drive
    ;; the boundaries deterministically, identical on both hosts (no sleeps).
    (with-redefs [interop/next-tick (fn [_f] nil)]
      (let [lease (obs/acquire! target (fn [n] (swap! notes conj n)))]
        (is (= 1 (ref-count [:obs/items])))
        ;; A CACHE-CLEAR disposal (intrinsic cause :cache-clear → :disposed)
        ;; enqueues the lease; next-tick is swallowed so it stays PENDING. The
        ;; frame stays live throughout — this is NOT a destruction.
        (subs-cache/clear-sub-cache! fid)
        (is (nil? (entry [:obs/items])) "cache-clear evicted + disposed the node")
        (is (empty? @notes) "the disposal only ENQUEUED — no synchronous fan-out")
        ;; An UNRELATED :sub HMR re-registration fires the :hmr drain boundary
        ;; while the :disposed lease is still pending. It MUST NOT sweep it.
        (reg-items!)
        (is (empty? @notes)
            "the :hmr drain took only :hmr-tagged entries — the :disposed
             cache-clear lease was LEFT pending, never mislabelled :hmr")
        ;; Drive the next-tick :disposed fallback deterministically: the lease
        ;; receives its OWN intrinsic cause.
        (obs/drain-pending-disposals! :disposed)
        (is (= 1 (count @notes)) "the cache-clear lease was notified exactly once")
        (is (= :disposed (:cause (first @notes)))
            "it received {:cause :disposed} — its INTRINSIC cause — NEVER :hmr
             (pre-fix the co-pending :hmr drain delivered :hmr here)")
        (is (= target (:target (first @notes))))
        (obs/release! lease)))))

;; ===========================================================================
;; rf2-vxgfnd.29 (gap b) — the :disposed drain boundary driven END-TO-END from
;; a real frame/destroy-frame!.
;;
;; The sibling :disposed fixtures above drive `drain-pending-disposals!`
;; directly, after a SIMULATED eviction / cache-clear, with `interop/next-tick`
;; swallowed to a no-op. That leaves three legs of the fallback path uncovered:
;;   1. a REAL `frame/destroy-frame!` -> `tear-down-sub-cache!` disposal enqueue
;;      (intrinsic cause `:frame-destroy` -> `:disposed`),
;;   2. the next-tick CAS auto-schedule — `compare-and-set!
;;      disposal-drain-scheduled? false->true` plus the scheduled drain closure
;;      `(reset! disposal-drain-scheduled? false) (drain-pending-disposals!
;;      :disposed)` — which every swallow-next-tick sibling skips, and
;;   3. `frame-commit-epoch` 0 in the delivered payload for the now-destroyed
;;      frame (its epoch counter was dissoc'd by `dissoc-frame!`).
;;
;; This fixture CAPTURES the scheduled next-tick thunk(s) (instead of no-op'ing
;; next-tick) and DRIVES that exact closure, so the CAS transition, the
;; closure's latch reset, and the `:disposed` delivery all genuinely run —
;; deterministically, both hosts, no sleeps.
;; ===========================================================================

(deftest frame-destroy-schedules-and-drives-the-disposed-drain-with-epoch-zero
  (reg-items!)
  (seed-items! [:a])
  (let [pending    @#'obs/pending-disposals
        scheduled? @#'obs/disposal-drain-scheduled?]
    ;; Hermetic start: clear the process-global drain latches so the CAS
    ;; false->true transition is observable regardless of sibling test order.
    (reset! pending [])
    (reset! scheduled? false)
    (let [target   (items-target)
          notes    (atom [])
          captured (atom [])
          lease    (obs/acquire! target (fn [ev] (swap! notes conj ev)))]
      (is (= :live (:status @(@#'obs/lease-state lease))))
      (is (int? (frame/frame-commit-epoch fid)) "the live frame has a commit epoch")
      ;; CAPTURE the next-tick thunk(s) instead of running them — so the CAS
      ;; scheduling is observable and the scheduled drain is driven by hand.
      (with-redefs [interop/next-tick (fn [f] (swap! captured conj f))]
        (frame/destroy-frame! fid))
      (testing "the frame-destroy disposal enqueued a :disposed entry and the
                next-tick CAS scheduled the fallback drain — nothing fanned yet"
        (is (true? @scheduled?)
            "compare-and-set! disposal-drain-scheduled? false->true fired")
        (is (pos? (count @captured)) "interop/next-tick received the drain closure")
        (is (some (fn [[_lease cause]] (= :disposed cause)) @pending)
            "the queued entry carries the INTRINSIC :disposed cause
             (frame-destroy -> :frame-destroy -> :disposed), never :hmr")
        (is (empty? @notes)
            "delivery is QUEUED — no synchronous on-change on the destroy stack"))
      (testing "the destroyed frame's commit-epoch counter is cleared to 0"
        (is (zero? (frame/frame-commit-epoch fid))
            "dissoc-frame! dropped the destroyed frame's commit-epoch"))
      ;; DRIVE the captured next-tick closure(s) = the REAL scheduled path.
      (doseq [thunk @captured] (thunk))
      (testing "the scheduled closure ran the :disposed drain and reset the latch"
        (is (false? @scheduled?)
            "the drain closure reset disposal-drain-scheduled? to false")
        (is (empty? @pending) "the :disposed drain emptied the pending queue"))
      (testing "the still-live destroyed-frame lease received {:cause :disposed}
                carrying frame-commit-epoch 0"
        (is (= 1 (count @notes)) "exactly one coalesced disposal notification")
        (let [ev (first @notes)]
          (is (= :disposed (:cause ev)) "the intrinsic :frame-destroy cause -> :disposed")
          (is (= target (:target ev)))
          (is (zero? (:frame-epoch ev))
              "the payload's :frame-epoch is 0 for the destroyed frame"))))))

;; ===========================================================================
;; rf2-vxgfnd.70 — a follower must not publish a lease behind the FIRST owner's
;; still-installing hook.
;;
;; PR #5737's handshake flips the node record's readiness flag as the lease
;; ENROLS, before interop/add-on-dispose! actually registers the callback. A
;; follower that reads the flag true installs no hook of its own and trusts a
;; hook that does not yet exist. If disposal wins before the first owner
;; resumes, no hook fires and the follower's invalidation is lost (or delayed
;; without bound). The fix publishes readiness (`:hook-installed?`) only AFTER
;; the callback is registered; a follower enrolling in the install window
;; installs its OWN backstop hook — an independent disposal fallback — so no
;; owner is ever published behind a not-yet-installed hook (take-owners! keeps
;; duplicate hooks harmless).
;; ===========================================================================

#?(:clj
   (deftest first-owner-hook-install-window-follower-covered-before-first-owner-resumes
     ;; Three-party JVM barrier (CountDownLatch, no sleeps). Thread A is the
     ;; first owner, PAUSED mid-install (inside add-on-dispose!, before the
     ;; callback registers). Thread B follows during that window. Thread C
     ;; disposes the node BEFORE A resumes. With the fix, B installed its own
     ;; backstop hook, so C's disposal drains BOTH owners here — the
     ;; invalidation never waits for A. Pre-fix B installs no hook (it trusts
     ;; A's not-yet-installed readiness flag), so C's disposal fires nothing and
     ;; the pending queue is empty — the discriminating assertion fails.
     (reg-items!)
     (seed-items! [:a])
     (let [target             (items-target)
           notes-a            (atom [])
           notes-b            (atom [])
           l1                 (atom nil)
           l2                 (atom nil)
           real-add           interop/add-on-dispose!
           a-installing       (java.util.concurrent.CountDownLatch. 1)
           b-done             (java.util.concurrent.CountDownLatch. 1)
           a-proceed          (java.util.concurrent.CountDownLatch. 1)
           canonical-installs (atom 0)
           pending-at-dispose (atom nil)]
       (with-redefs
         [interop/next-tick (fn [_f] nil)
          interop/add-on-dispose!
          (fn [reaction f]
            (if (and (identical? reaction (:reaction (entry [:obs/items])))
                     (= 1 (swap! canonical-installs inc)))
              ;; A's node-disposed-hook install: signal mid-install, then BLOCK
              ;; until released — the paused first installer.
              (do (.countDown a-installing)
                  (.await a-proceed)
                  (real-add reaction f))
              ;; B's backstop install (and the construction-time input-release
              ;; closure, which the entry does not yet hold) proceed at once.
              (real-add reaction f)))]
         (let [fa (future (reset! l1 (obs/acquire! target (fn [n] (swap! notes-a conj n)))))]
           (.await a-installing) ;; A is paused mid-install (no sleeps)
           (let [fb (future
                      (reset! l2 (obs/acquire! target (fn [n] (swap! notes-b conj n))))
                      (.countDown b-done))]
             (.await b-done)
             (is (some? @l2)
                 "B completed the follower acquire while A is paused mid-install")
             ;; C disposes the node BEFORE A resumes.
             (force-dispose-node! [:obs/items])
             (reset! pending-at-dispose (vec @@#'obs/pending-disposals))
             (is (= 2 (count @pending-at-dispose))
                 (str "B's OWN backstop hook drained BOTH owners on disposal — "
                      "the invalidation did not wait for the first owner to "
                      "resume; pre-fix B installs no hook and this queue is empty "
                      "(saw " (count @pending-at-dispose) ")"))
             ;; Release A: it lands its now-dead hook, marks readiness, re-checks
             ;; non-canonical, and self-drains to an already-empty owner set.
             (.countDown a-proceed)
             @fa
             @fb
             (is (some? @l1) "the first owner completed and returned its lease")
             (testing "no synchronous fan-out — the drain only ENQUEUED"
               (is (empty? @notes-a))
               (is (empty? @notes-b)))
             (obs/drain-pending-disposals! :disposed)
             (is (= 1 (count @notes-a)) "first owner notified exactly once")
             (is (= 1 (count @notes-b)) "follower notified exactly once")
             (obs/release! @l1)
             (obs/release! @l2)))))))

(deftest first-owner-hook-install-failure-tears-down-and-recovers
  ;; rf2-vxgfnd.70 — if interop/add-on-dispose! THROWS while an owner installs
  ;; the node hook, acquire! must not leak: tear THIS owner down (ref-count /
  ;; watch / enrolment balanced), leave readiness UNPUBLISHED so the node is
  ;; not poisoned, and propagate the original exception through acquire!'s
  ;; fail-loud path. A later acquire (install healthy) then succeeds — future
  ;; acquirers are never stranded. Both hosts (single-threaded, with-redefs).
  (reg-items!)
  (seed-items! [:a])
  (let [target   (items-target)
        real-add interop/add-on-dispose!
        boom     (ex-info "add-on-dispose! boom" {::boom true})
        fail?    (atom true)]
    (with-redefs
      [interop/next-tick (fn [_f] nil)
       interop/add-on-dispose!
       (fn [reaction f]
         ;; Fail ONLY the observation node-disposed-hook install on the
         ;; canonical node, once; the construction-time input-release closure
         ;; (entry does not yet hold the reaction) and later installs proceed.
         (if (and @fail?
                  (identical? reaction (:reaction (entry [:obs/items]))))
           (do (reset! fail? false) (throw boom))
           (real-add reaction f)))]
      (testing "the install throw propagates through acquire!'s fail-loud path"
        (let [e (try (obs/acquire! target (fn [_])) nil
                     (catch #?(:clj Throwable :cljs :default) e e))]
          (is (identical? boom e) "acquire! rethrew the original install exception")))
      (testing "the failed owner was torn down cleanly — ref-count balanced"
        (is (nil? (entry [:obs/items]))
            "the sole owner's ref was released, disposing the node — no leaked ref"))
      (testing "readiness stayed unpublished — a fresh acquire installs + succeeds"
        (let [lease (obs/acquire! target (fn [_]))]
          (is (obs/lease? lease))
          (is (true? (obs/owned? lease)))
          (is (= 1 (ref-count [:obs/items])) "exactly one reference on the rebuilt node")
          (is (= [:a] (:value (obs/read lease))))
          (is (true? (obs/current? lease target)))
          (obs/release! lease)
          (is (nil? (entry [:obs/items]))))))))

;; ===========================================================================
;; rf2-vxgfnd.28 — the disposal-notification drain contains a throwing owner
;; and SURFACES the escape (it was the one uncontained fan-out)
;;
;; drain-pending-disposals! reset-vals!-empties the queue then notifies every
;; queued owner. Pre-fix a throw from owner k aborted k+1..n, whose
;; notifications were already dequeued and thus LOST — and on the :hmr path the
;; drain runs inside the registrar replacement hook, whose per-hook catch DROPS
;; the throw (no log/trace/record), swallowing even the dev
;; :rf.error/reentrant-graph-op assert exactly where it matters. The fix wraps
;; each lease's notify in its own try/catch (siblings never starve), surfaces the
;; callback failure on the always-on axis (SEEN through the registrar's swallow)
;; via the stable :rf.error/observation-on-change-failed wrapper — the
;; diagnostic-only reentrant-graph-op is NEVER promoted onto the always-on axis
;; under its own id (rf2-w55bh0), it rides as the wrapper's cause — and re-throws
;; after the whole drain (never silent).
;; ===========================================================================

(deftest disposal-drain-contains-a-throwing-owner-and-surfaces-the-escape
  (reg-items!)
  (seed-items! [:a])
  (let [target    (items-target)
        notes-b   (atom [])
        bad-lease (atom nil)
        ;; Owner A: its on-change does a FORBIDDEN reentrant release! from inside
        ;; the fan-out → throws the dev :rf.error/reentrant-graph-op assert,
        ;; escaping the notification (it does NOT catch its own throw).
        la  (obs/acquire! target (fn [_n] (obs/release! @bad-lease)))
        ;; Owner B: a well-behaved sibling that MUST still be notified.
        lb  (obs/acquire! target (fn [n] (swap! notes-b conj n)))]
    (reset! bad-lease la)
    (is (= 2 (obs/active-owner-count (:reaction (entry [:obs/items]))))
        "both owners are enrolled on the shared node")
    ;; The HMR re-registration disposes the shared node and drains BOTH former
    ;; owners at the registrar replacement boundary — the exact swallow-prone
    ;; path. reg-items! itself returns normally: the registrar isolates the
    ;; replacement hook, so the drain's rethrow is swallowed there and it is the
    ;; ALWAYS-ON fan that carries visibility.
    (let [[[outcome _] records] (with-error-records #(reg-items!))]
      (testing "the throwing owner did NOT starve its sibling — B notified once"
        (is (= 1 (count @notes-b)))
        (is (= :hmr (:cause (first @notes-b)))))
      (testing "the escape is SEEN on the always-on axis despite the registrar's
                per-hook swallow — the diagnostic-only reentrant-graph-op is
                wrapped in the stable :rf.error/observation-on-change-failed
                (never promoted onto the always-on axis under its own diagnostic
                id — rf2-w55bh0), carrying the reentrant assert as its cause"
        (let [wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                               records)]
          (is (= 1 (count wrapped))
              "the swallowed callback failure reached the always-on error surface")
          (is (= :rf.error/reentrant-graph-op
                 (error-id (:exception (first wrapped))))
              "the diagnostic reentrant-graph-op rides as the wrapper's cause"))
        (is (empty? (filterv #(= :rf.error/reentrant-graph-op (:error %)) records))
            "the diagnostic category is NOT promoted onto the always-on axis"))
      (testing "reg-items! returned normally — the registrar isolates the hook"
        (is (= :ok outcome))))
    ;; Owner A's release! never completed (it threw); both are cleanly releasable
    ;; outside the fan-out now.
    (obs/release! la)
    (obs/release! lb)))

;; ===========================================================================
;; rf2-6ui49w — an UNTYPED throwable escaping a former-owner on-change must
;; SURFACE before the disposal boundary swallows it (containment preserved)
;;
;; #5766 (rf2-vxgfnd.28) contained a throwing owner per-lease and re-surfaced a
;; TYPED escape on the always-on axis, but only when the throwable carried a
;; catalogued :rf.error/id — an UNTYPED on-change bug (a raw TypeError /
;; AssertionError / host RuntimeException, or a defect before any typed
;; wrapping) was merely re-thrown after the drain, and BOTH real boundaries
;; discard that rethrow (registrar's per-hook catch on :hmr; an unobserved
;; next-tick Future on :disposed). So a genuine consumer-callback failure
;; vanished silently. The fix wraps an untyped escape in the stable catalogued
;; :rf.error/observation-on-change-failed and fans it on the always-on axis
;; BEFORE the boundary drops the throw — a typed escape keeps its own id and is
;; not double-reported. Proven swallowed pre-fix: with the untyped-surface
;; branch reverted, both fixtures below fail on the wrapped-record assertion
;; (count 0), while the sibling-containment assertion stays green (#5766 already
;; contained the throw — this bead is about not LOSING it).
;; ===========================================================================

(deftest disposal-drain-surfaces-an-untyped-on-change-failure-at-the-hmr-boundary
  (reg-items!)
  (seed-items! [:a])
  (let [target  (items-target)
        notes-b (atom [])
        ;; An UNTYPED throwable: its ex-data carries NO :rf.error/id, so it is a
        ;; raw consumer-callback bug, not a typed re-frame condition.
        boom    (ex-info "untyped on-change boom" {::boom true})
        ;; Owner A: its on-change throws the raw untyped throwable, escaping the
        ;; notification (it does NOT catch its own throw).
        la      (obs/acquire! target (fn [_n] (throw boom)))
        ;; Owner B: a well-behaved sibling that MUST still be notified.
        lb      (obs/acquire! target (fn [n] (swap! notes-b conj n)))]
    (is (nil? (error-id boom)) "the fixture throwable is genuinely UNTYPED")
    (is (= 2 (obs/active-owner-count (:reaction (entry [:obs/items]))))
        "both owners are enrolled on the shared node")
    ;; The HMR re-registration disposes the shared node and drains BOTH former
    ;; owners at the registrar replacement boundary — whose per-hook catch
    ;; swallows the drain's rethrow, so the ALWAYS-ON fan is the only visibility.
    (let [[[outcome _] records] (with-error-records #(reg-items!))]
      (testing "containment preserved (#5766) — the untyped throw did NOT starve
                its sibling; B notified exactly once"
        (is (= 1 (count @notes-b)))
        (is (= :hmr (:cause (first @notes-b)))))
      (testing "the UNTYPED escape SURFACES on the always-on axis — wrapped in
                the stable :rf.error/observation-on-change-failed, carrying the
                ORIGINAL throwable as its cause (rf2-6ui49w)"
        (let [wrapped (filter #(= :rf.error/observation-on-change-failed (:error %))
                              records)]
          (is (= 1 (count wrapped))
              "exactly one always-on record for the untyped escape — no double-report")
          (is (identical? boom (:exception (first wrapped)))
              "the record carries the original untyped throwable as its cause")
          (is (= :obs/items (:event-id (first wrapped)))
              "the record is attributed to the failing owner's entry sub")
          (is (= fid (:frame (first wrapped))))))
      (testing "an untyped escape is NOT mislabelled as a typed re-frame error"
        (is (empty? (filter #(= :rf.error/reentrant-graph-op (:error %)) records))))
      (testing "reg-items! returned normally — the registrar isolates the hook,
                so correctness never depended on that rethrow being observed"
        (is (= :ok outcome))))
    (obs/release! la)
    (obs/release! lb)))

(deftest disposal-drain-surfaces-an-untyped-on-change-failure-at-the-disposed-boundary
  (reg-items!)
  (seed-items! [:a])
  (let [target  (items-target)
        notes-b (atom [])
        boom    (ex-info "untyped on-change boom" {::boom true})]
    ;; Swallow next-tick so the :disposed fallback does not auto-drain — we drive
    ;; the boundary deterministically, identical on both hosts (no sleeps).
    (with-redefs [interop/next-tick (fn [_f] nil)]
      (let [la (obs/acquire! target (fn [_n] (throw boom)))
            lb (obs/acquire! target (fn [n] (swap! notes-b conj n)))]
        (is (= 2 (obs/active-owner-count (:reaction (entry [:obs/items]))))
            "both owners are enrolled on the shared node")
        ;; A non-registrar disposal (the frame-destroy / cache-clear class) evicts
        ;; + disposes the node, enqueuing both former owners with the intrinsic
        ;; cause :disposed; next-tick is swallowed so they stay pending.
        (force-dispose-node! [:obs/items])
        (is (nil? (entry [:obs/items])) "the node was disposed")
        (is (empty? @notes-b) "the disposal only ENQUEUED — no synchronous fan-out")
        ;; Drive the :disposed fallback boundary directly. It re-throws the first
        ;; (untyped) escape to its DIRECT caller — caught by with-error-records —
        ;; but the always-on fan already surfaced it BEFORE the boundary.
        (let [[[outcome thrown] records]
              (with-error-records #(obs/drain-pending-disposals! :disposed))]
          (testing "containment preserved (#5766) — B notified exactly once
                    despite A's untyped throw"
            (is (= 1 (count @notes-b)))
            (is (= :disposed (:cause (first @notes-b)))))
          (testing "the UNTYPED escape SURFACES on the always-on axis — wrapped in
                    the stable id, carrying the original throwable (rf2-6ui49w)"
            (let [wrapped (filter #(= :rf.error/observation-on-change-failed (:error %))
                                  records)]
              (is (= 1 (count wrapped))
                  "exactly one always-on record for the untyped escape — no double-report")
              (is (identical? boom (:exception (first wrapped))))
              (is (= :obs/items (:event-id (first wrapped))))
              (is (= fid (:frame (first wrapped))))))
          (testing "the first escape is STILL re-thrown to the DIRECT caller
                    (acceptance: direct callers may observe it), but correctness
                    does not depend on the swallowing boundaries observing it"
            (is (= :threw outcome))
            (is (identical? boom thrown))))
        (obs/release! la)
        (obs/release! lb)))))

;; ===========================================================================
;; rf2-vxgfnd.63 — a live-cache DISPLACEMENT must not be misclassified as frame
;; destruction during acquire!
;;
;; subs/build-and-classify! drives compute-and-cache! then re-checks whether the
;; just-built reaction is still the frame's canonical cache node. If that node is
;; DISPLACED — invalidated-and-rebuilt to a newer canonical node — in that window
;; (a concurrent HMR sub re-registration or an explicit cache clear) while the
;; FRAME STAYS LIVE, the build succeeded (recovery sink nil) yet the node is no
;; longer canonical, so build-and-classify!'s :else fallback returns
;; {:recovery :frame-destroyed}. Pre-fix acquire! throws + fans a FALSE always-on
;; :rf.error/frame-destroyed even though the frame is live. The fix: acquire!
;; disambiguates against the targeted frame's incarnation token
;; (frame/frame-incarnation-token) — a still-live incarnation means the node was
;; merely displaced, so acquire! retargets to the current canonical node (bounded
;; retry) instead of throwing; only a nil/changed incarnation is a verified
;; teardown of the targeted incarnation.
;;
;; Deterministic barrier (no sleeps, both hosts): wrap subs/compute-and-cache!
;; so the FIRST build of the target query displaces the just-built canonical node
;; before returning it — landing the reaction non-canonical exactly in the
;; build→canonical-check window, with the frame record (and its incarnation
;; token) untouched. A compare-and-set! makes it fire once, so the acquire!-side
;; retry settles the next canonical build.
;; ===========================================================================

(defn- evict-node!
  "Evict the cache entry for `query-v` — the swap EVERY real disposal path does
  BEFORE interop/dispose!. Models an explicit cache clear on a still-live frame."
  [query-v]
  (swap! (sub-cache) dissoc query-v))

(defn- displace-node!
  "Model an HMR-style displacement of the just-built canonical `reaction` for
  `query-v`: evict the cache entry then dispose the reaction, leaving the frame
  record — and its incarnation token — untouched (the frame stays LIVE)."
  [query-v reaction]
  (evict-node! query-v)
  (interop/dispose! reaction))

(deftest acquire-live-cache-displacement-retargets-not-frame-destroyed
  (reg-items!)
  (seed-items! [:a])
  (let [target  (items-target)
        real-cc @#'subs/compute-and-cache!
        raced?  (atom false)]
    (with-redefs
      [subs/compute-and-cache!
       (fn [frame-id query-v]
         (let [reaction (real-cc frame-id query-v)]
           ;; Displace the just-built canonical node ONCE, in the
           ;; build→canonical-check window, with the frame left LIVE.
           (when (and (= query-v [:obs/items])
                      (compare-and-set! raced? false true))
             (displace-node! [:obs/items] reaction))
           reaction))]
      (let [[[outcome lease] records] (with-error-records #(obs/acquire! target (fn [_])))]
        (is (true? @raced?) "the displacement fired in the build→check window")
        (testing "acquire! did NOT throw or fan a false frame-destroyed while the frame is live"
          (is (= :ok outcome) "acquire! returned a lease, not a throw")
          (is (obs/lease? lease))
          (is (empty? (filter #(= :rf.error/frame-destroyed (:error %)) records))
              "no false always-on frame-destroyed record was fanned")
          (is (some? (frame/frame fid)) "the frame remained live throughout"))
        (testing "it converged on the CURRENT canonical node — an owned, current lease"
          (is (obs/owned? lease) "the retarget adopted a real cache node")
          (is (true? (obs/current? lease target))
              "the lease covers the live canonical node")
          (is (= 1 (ref-count [:obs/items])) "exactly one reference on the current node")
          (is (= [:a] (:value (obs/read lease)))
              "reads the live value through the adopted canonical node"))
        (testing "no leak — release drops the last ref and disposes the current node"
          (obs/release! lease)
          (is (nil? (entry [:obs/items]))))))))

(deftest acquire-repeated-live-displacement-is-bounded-and-converges
  ;; Repeated displacement (explicit-cache-clear style: evict only) on the first
  ;; K attempts, then quiescence. The bounded retry converges on a canonical
  ;; current lease at attempt K+1 — it does not spin, and never fans a false
  ;; frame-destroyed while the frame stays live (rf2-vxgfnd.63 — the retry is
  ;; bounded and cannot spin forever under repeated HMR).
  (reg-items!)
  (seed-items! [:a])
  (let [target  (items-target)
        real-cc @#'subs/compute-and-cache!
        k       3
        builds  (atom 0)]
    (with-redefs
      [subs/compute-and-cache!
       (fn [frame-id query-v]
         (let [reaction (real-cc frame-id query-v)]
           (when (= query-v [:obs/items])
             ;; Evict the first K builds in-window; the (K+1)th settles.
             (when (<= (swap! builds inc) k)
               (evict-node! [:obs/items])))
           reaction))]
      (let [[[outcome lease] records] (with-error-records #(obs/acquire! target (fn [_])))]
        (is (= (inc k) @builds) "converged on the very next build after K displacements")
        (is (= :ok outcome) "acquire! converged on a lease — it did not spin or throw")
        (is (empty? (filter #(= :rf.error/frame-destroyed (:error %)) records))
            "no false frame-destroyed under repeated live displacement")
        (is (true? (obs/current? lease target)))
        (is (= 1 (ref-count [:obs/items])))
        (obs/release! lease)
        (is (nil? (entry [:obs/items])))))))

(deftest acquire-genuine-destruction-in-window-still-throws-frame-destroyed
  ;; The disambiguation's OTHER side: when the TARGETED incarnation is actually
  ;; destroyed in the build→check window (its incarnation token → nil), acquire!
  ;; MUST still throw + fan exactly one :rf.error/frame-destroyed. Displacement
  ;; retargeting must never swallow a real teardown (rf2-vxgfnd.63 regression).
  (let [race-fid :obs/race-frame-63]
    (rf/make-frame {:id race-fid :adapter plain-atom/adapter})
    (rf/reg-sub :obs/items63 (fn [db _] (:items db)))
    (frame/replace-app-db! race-fid {:items [:a]})
    (let [target  (obs/resolve-target {:frame race-fid :query-v [:obs/items63]})
          real-cc @#'subs/compute-and-cache!
          raced?  (atom false)]
      (with-redefs
        [subs/compute-and-cache!
         (fn [frame-id query-v]
           (let [reaction (real-cc frame-id query-v)]
             (when (and (= query-v [:obs/items63])
                        (compare-and-set! raced? false true))
               ;; Destroy the targeted incarnation in the window — token → nil.
               (frame/destroy-frame! race-fid))
             reaction))]
        (let [[[outcome e] records] (with-error-records #(obs/acquire! target (fn [_])))]
          (is (true? @raced?) "the destruction fired in the build→check window")
          (is (= :threw outcome) "a real teardown still throws — it is not retargeted")
          (is (= :rf.error/frame-destroyed (error-id e)))
          (is (= 1 (count (filter #(= :rf.error/frame-destroyed (:error %)) records)))
              "exactly one always-on frame-destroyed record was fanned")
          (is (nil? (frame/frame race-fid))
              "the targeted incarnation is gone — nothing was leased"))))))

;; ===========================================================================
;; rf2-vxgfnd.79 — retry EXHAUSTION over a still-LIVE incarnation must not lie
;; that the frame was destroyed.
;;
;; acquire!'s bounded live-cache-displacement retry (rf2-vxgfnd.63) distinguishes
;; a displacement from a teardown by checking the frame incarnation. But when the
;; retry budget is EXHAUSTED while the incarnation is STILL LIVE — a pathological-
;; but-legal displacer winning every build→canonical-check window — the pre-fix
;; code passed the unchanged {:recovery :frame-destroyed} to throw-acquire-
;; recovery! and emitted :rf.error/frame-destroyed, telling an implementer / Xray
;; user to recover a destroyed frame it had JUST PROVED alive. The fix reports the
;; TRUTHFUL :rf.error/observation-retry-exhausted livelock instead — fanned on the
;; always-on axis before the throw, carrying frame / query / same-incarnation
;; evidence / attempt count.
;; ===========================================================================

(deftest acquire-retry-exhaustion-over-live-incarnation-is-not-frame-destroyed
  (reg-items!)
  (seed-items! [:a])
  (let [target  (items-target)
        real-cc @#'subs/compute-and-cache!
        builds  (atom 0)]
    (with-redefs
      [subs/compute-and-cache!
       (fn [frame-id query-v]
         (let [reaction (real-cc frame-id query-v)]
           (when (= query-v [:obs/items])
             ;; Displace EVERY build in-window, leaving the frame (and its
             ;; incarnation token) LIVE — the storm never settles, so the bounded
             ;; retry exhausts its budget with the incarnation demonstrably alive.
             (swap! builds inc)
             (evict-node! [:obs/items]))
           reaction))]
      (let [[[outcome e] records] (with-error-records #(obs/acquire! target (fn [_])))]
        (is (= :threw outcome) "the exhausted retry is fail-loud")
        (is (some? (frame/frame fid))
            "the frame stayed LIVE throughout — it was never destroyed")
        (testing "the terminal condition is the TRUTHFUL retry-exhausted, NEVER frame-destroyed"
          (is (= :rf.error/observation-retry-exhausted (error-id e))
              "acquire! does not lie :frame-destroyed for a demonstrably live frame")
          (is (empty? (filter #(= :rf.error/frame-destroyed (:error %)) records))
              "no false always-on frame-destroyed record was fanned"))
        (testing "the truthful condition reached the always-on axis with evidence"
          (is (some #(= :rf.error/observation-retry-exhausted (:error %)) records)
              "the retry-exhausted record fanned through surface #4 before the throw")
          (let [d (ex-data e)]
            (is (= [:obs/items] (:rf.sub/query-v d)) "reports the query")
            (is (= fid (:frame d)) "reports the frame")
            (is (= :live (:frame-incarnation d))
                "reports same-incarnation-live evidence")
            (is (int? (:attempts d)) "reports the attempt count it exhausted")))
        (testing "the retry was BOUNDED — budget+1 build attempts, then the throw"
          (is (= (inc @#'obs/max-displacement-retries) @builds)
              "exactly budget+1 build attempts — it did not spin forever")
          (is (= @builds (:attempts (ex-data e)))
              ":attempts equals the build attempts made"))))))

;; ===========================================================================
;; rf2-vxgfnd.14 — read carries the node's IDENTITY (:node-key) so a same-id
;; frame REINCARNATION between render and commit is detected as movement, EVEN
;; WHEN node-version + frame/registry epochs coincide across the incarnations.
;;
;; `frame/dissoc-frame!` clears the destroyed frame's commit-epoch entry, so a
;; recreate + the same single install RESTARTS the epoch at the value the
;; destroyed incarnation held; a fresh node observed once is version 0 on both;
;; and no :sub re-registration in the gap leaves the registry epoch stable. So
;; the S2b version+epoch-only LIVE comparison ties across incarnations and would
;; misread the reincarnation as UNCHANGED — the frozen S2 invariant break.
;; `read` now also returns :node-key (the same identity `probe` already emits);
;; a reincarnated frame builds a FRESH reaction that mints a strictly-greater
;; key, so the changed key is the movement signal the reconciler needs.
;; Deterministic, no sleeps, both hosts (the plain-atom adapter is CLJC).
;; ===========================================================================

(deftest read-carries-node-key-so-same-id-reincarnation-is-detected
  (let [rfid :obs/reincarnation]
    (rf/reg-sub :obs/rv (fn [db _] (:v db)))
    ;; --- render incarnation: a LIVE node observed by a render-time probe ---
    (rf/make-frame {:id rfid :adapter plain-atom/adapter})
    (frame/replace-app-db! rfid {:v 1})
    (let [target       (obs/resolve-target {:frame rfid :query-v [:obs/rv]})
          ;; Hold a public subscribe ref so the node is canonical + LIVE when the
          ;; render-side probe reads it (probe never takes a ref of its own).
          _render-node (subs/subscribe [:obs/rv] {:frame rfid})
          render-ev    (obs/probe target)]
      (is (true? (:live? render-ev)) "the render probe read a LIVE node")
      (is (= 1 (:value render-ev)))
      (is (= 0 (:node-version render-ev)) "fresh node observed once ⟹ version 0")
      (is (int? (:node-key render-ev)))
      ;; --- reincarnation in the render→commit gap: destroy + recreate the SAME
      ;;     id, install a DIFFERENT value. The identical make-frame + single
      ;;     replace-app-db! sequence (with dissoc-frame! clearing the epoch)
      ;;     makes node-version + frame/registry epochs COINCIDE with the
      ;;     destroyed incarnation's — the coincident-version reincarnation. ---
      (frame/destroy-frame! rfid)
      (rf/make-frame {:id rfid :adapter plain-atom/adapter})
      (frame/replace-app-db! rfid {:v 2})
      (let [_commit-node (subs/subscribe [:obs/rv] {:frame rfid})
            commit-ev    (obs/probe target)          ;; new live node, node-key K2
            lease        (obs/acquire! target (fn [_]))
            r            (obs/read lease)]
        (is (= 2 (:value r)) "acquired + read the reincarnated node's value")
        (testing "the version + epoch axes COINCIDE across the reincarnation"
          (is (= (:node-version render-ev) (:version r))
              "node versions tie — both fresh nodes at version 0")
          (is (= (:frame-epoch render-ev) (:frame-epoch r))
              "frame epochs tie — dissoc-frame! restarted the commit epoch")
          (is (= (:registry-epoch render-ev) (:registry-epoch r))
              "registry epochs tie — no :sub re-registration in the gap"))
        (testing "read now carries the node's IDENTITY, DISTINCT across the reincarnation"
          (is (int? (:node-key r))
              "read carries :node-key (the fix — omitted before, so nil)")
          (is (= (:node-key commit-ev) (:node-key r))
              "read's node-key is the node it actually owns at commit")
          (is (not= (:node-key render-ev) (:node-key r))
              "the reincarnated node has a DISTINCT identity from the destroyed one"))
        (testing "so the S2b evidence comparison classifies it as MOVED, not unchanged"
          ;; Mirror ui.reactive/evidence-moved?'s live branch: version+epoch ALONE
          ;; MISSES it (the bug); the additive :node-key axis catches it.
          (letfn [(version+epoch-moved? [rd pv]
                    (or (not= (:version rd) (:node-version pv))
                        (not= (:frame-epoch rd) (:frame-epoch pv))
                        (not= (:registry-epoch rd) (:registry-epoch pv))))
                  (node-key-moved? [rd pv]
                    (or (version+epoch-moved? rd pv)
                        (not= (:node-key rd) (:node-key pv))))]
            (is (false? (version+epoch-moved? r render-ev))
                "version+epoch-only comparison MISSES the reincarnation (the S2 break)")
            (is (true? (node-key-moved? r render-ev))
                "carrying :node-key makes the reincarnation detectable as movement")))
        (obs/release! lease)))
    (frame/destroy-frame! rfid)))

(deftest unchanged-live-node-reads-the-same-node-key-no-false-movement
  ;; The fast-path guard (rf2-vxgfnd.14 AC #4): a genuinely-unchanged live node
  ;; reads the SAME node-key/version/epochs across a render probe and a commit
  ;; read, so the reconciler's evidence comparison classifies it UNCHANGED — the
  ;; new :node-key axis introduces no false movement.
  (reg-items!)
  (seed-items! [:a])
  (let [target    (items-target)
        _live     (subs/subscribe [:obs/items] {:frame fid})
        render-ev (obs/probe target)
        lease     (obs/acquire! target (fn [_]))
        r         (obs/read lease)]
    (is (true? (:live? render-ev)))
    (is (= (:node-key render-ev) (:node-key r))
        "same live node ⟹ same node-key across render and commit")
    (is (= (:node-version render-ev) (:version r)))
    (is (= (:frame-epoch render-ev) (:frame-epoch r)))
    (is (= (:registry-epoch render-ev) (:registry-epoch r)))
    (letfn [(moved? [rd pv]
              (or (not= (:version rd) (:node-version pv))
                  (not= (:frame-epoch rd) (:frame-epoch pv))
                  (not= (:registry-epoch rd) (:registry-epoch pv))
                  (not= (:node-key rd) (:node-key pv))))]
      (is (false? (moved? r render-ev))
          "no false movement for an unchanged live node — the fast path holds"))
    (obs/release! lease)))

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
  (testing "static version currency follows the complete frozen rf= law"
    (let [target {:kind :story-override :query [:obs/items]
                  :value ##NaN :override-id :o1 :version ##NaN}
          lease  (obs/acquire! target (fn [_] (throw (ex-info "never" {}))))]
      (is (true? (obs/current? lease (assoc target
                                            :value ##NaN
                                            :version ##NaN)))
          "NaN→NaN is stable even though plain = rejects the movement token")))
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

(deftest slice-memo-tag-includes-the-exact-frame-incarnation
  ;; rf2-vxgfnd.160 — the shared cold-probe memo must NOT serve a DESTROYED
  ;; incarnation's value to a COMMIT-FREE consumer. The
  ;; (frame, frame-epoch, registry-epoch) triple TIES across a same-id
  ;; destroy+recreate (frame/dissoc-frame! restarts the commit epoch, and no new
  ;; :sub registration bumps the registry epoch), so without the exact
  ;; incarnation token in the tag a Tier-1 probe outside any ViewCell reuses A's
  ;; memoized parent for B — and there is NO commit step 5 to correct a
  ;; commit-free read.
  (let [mfid   :memo/frame]
    (rf/reg-sub :memo/parent (fn [db _] (:value db)))
    (rf/reg-sub :memo/value :<- [:memo/parent] (fn [v _] v))
    (let [target (fn [] (obs/resolve-target {:frame mfid :query-v [:memo/value]}))]
      ;; incarnation A — seed {:value :A}, ONE state commit
      (live-frame/make-frame {:id mfid})
      (frame/replace-app-db! mfid {:value :A})
      (let [memo    (obs/make-slice-memo)              ;; the shared module handle
            token-a (frame/frame-incarnation-token mfid)
            fe-a    (frame/frame-commit-epoch mfid)
            ev-a    (obs/probe (target) memo)]         ;; seeds A's parent into the memo
        (is (= :A (:value ev-a)) "A's value seeds the memo")
        ;; destroy A; recreate same-id B with an EQUAL-epoch commit
        (frame/destroy-frame! mfid)
        (live-frame/make-frame {:id mfid})
        (frame/replace-app-db! mfid {:value :B})
        (let [token-b (frame/frame-incarnation-token mfid)]
          (testing "precondition — B ties A on the (frame, frame-epoch, registry-epoch) tag"
            (is (not (identical? token-a token-b)) "B is a DISTINCT incarnation")
            (is (= fe-a (frame/frame-commit-epoch mfid))
                "frame-commit-epoch ties across the reincarnation (epoch restarted)"))
          ;; the SECOND probe reuses the SAME memo handle — no reset-scheduler!
          (let [ev-b (obs/probe (target) memo)]
            (is (= (:registry-epoch ev-a) (:registry-epoch ev-b))
                "registry-epoch ties too — the whole pre-token tag is identical")
            (is (= :B (:value ev-b))
                "the commit-free re-probe returns B's value — the incarnation token
                 in the memo tag prevents A's stale parent from being reused")))))))

;; ===========================================================================
;; the leak fixture — 10k cold probes retain zero
;; ===========================================================================

(deftest ten-thousand-cold-probes-retain-zero
  (rf/reg-sub :obs/leaf2 (fn [db _] (:leaf db)))
  (rf/reg-sub :obs/sum :<- [:obs/leaf2] (fn [v _] [:sum v]))
  (frame/replace-app-db! fid {:leaf 3})
  (let [cache-count-before   (count @(sub-cache))
        #?@(:clj [node-records-before (.size ^java.util.Map @#'obs/node-records)])
        watch-count-before   (container-watch-count (frame/frame-state-container fid))
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
        (is (= watch-count-before
               (container-watch-count (frame/frame-state-container fid)))
            "10k cold probes installed ZERO watches — a cold probe is a pure
             read, never a live subscription (which would add-watch the
             frame-state container); no watch-count leak on either host")
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
;; rf2-x76af2.34 FINDING 2 — a released node lease drops its reaction +
;; on-change refs
;;
;; #5753's .37 change broke the JVM node-records value→key strong-pin by
;; holding the reaction WEAKLY in the lease state. This complements it: the
;; live→released transition also nils :reaction and :on-change — both unused
;; after release (read/current?/notify short-circuit on :released;
;; read-after-release needs only :query-v/:frame-id) — so a consumer that
;; retains a released lease pins neither the on-change closure (either host)
;; nor the CLJS reaction. Hygiene, not a true leak, but it drops the dangling
;; refs promptly and matches the "released lease retains nothing" intent.
;; ===========================================================================

(deftest released-node-lease-drops-reaction-and-on-change-refs
  (reg-items!)
  (seed-items! [:a])
  (let [target    (items-target)
        on-change (fn [_] :never)
        lease     (obs/acquire! target on-change)
        state     (@#'obs/lease-state lease)]
    (testing "a live node lease holds its reaction + on-change"
      (is (= :live (:status @state)))
      (is (some? (:reaction @state)))
      (is (some? (:on-change @state))))
    (obs/release! lease)
    (testing "the released lease dropped both refs — nothing dangling"
      (is (= :released (:status @state)))
      (is (nil? (:reaction @state)) "the reaction ref was dropped on release")
      (is (nil? (:on-change @state)) "the on-change closure was dropped on release"))
    (testing "read-after-release still throws from :query-v/:frame-id alone"
      (is (= :rf.error/read-after-release
             (error-id (try (obs/read lease)
                            (catch #?(:clj Throwable :cljs :default) e e))))))
    (testing "release! stays idempotent after the drop"
      (obs/release! lease)
      (is (= :released (:status @state))))))

;; ===========================================================================
;; the JVM WeakHashMap self-reference leak fixture — rf2-vxgfnd.37
;;
;; The JVM node-records table is a process-global java.util.WeakHashMap keyed by
;; REACTION; its VALUE carries :owners, a strong set of ObservationLease objects,
;; and each lease's state references its reaction. java.util.WeakHashMap is NOT
;; an ephemeron map, so a value transitively STRONG-referencing its own weak key
;; pins that key forever:
;;   node-records value → :owners → lease → state → reaction (= the weak key)
;; An interrupted teardown (a committed owner whose cache/frame is dropped
;; WITHOUT release!/dispose) then leaks the reaction + lease for the process
;; lifetime. The fix holds the reaction WEAKLY in the lease state, breaking the
;; value→key edge, so the entry is collectable once the reaction is otherwise
;; unreachable. This is a deterministic WeakReference proof: an enrolled,
;; abandoned reaction becomes collectable, and a matched un-enrolled control
;; demonstrates the fixture can OBSERVE collection. CLJS is unaffected
;; (js/WeakMap has ephemeron semantics) — this leg is JVM-only.
;; ===========================================================================

#?(:clj
   (defn- gc-until-cleared?
     "Force GC (bounded) until `wref`'s referent is collected; report whether it
     was. Allocates transient heap pressure between cycles to prod the
     collector. The bounded loop keeps the fixture deterministic — it never
     blocks unboundedly (a still-strong-reachable referent returns false, which
     is the pre-fix failure signal)."
     [^java.lang.ref.WeakReference wref]
     (loop [i 0]
       (cond
         (nil? (.get wref)) true
         (>= i 40)          false
         :else              (do (System/gc)
                                (System/runFinalization)
                                (make-array Object 200000) ;; transient pressure
                                (recur (inc i)))))))

#?(:clj
   (deftest jvm-weak-node-record-does-not-strong-pin-its-abandoned-reaction
     (reg-items!)
     (seed-items! [:a])
     (testing "CONTROL — an un-enrolled canonical reaction is collectable once
               dropped (proves the fixture can OBSERVE collection in this JVM)"
       (let [box (volatile! (do (subs/subscribe [:obs/items] {:frame fid})
                                (:reaction (entry [:obs/items]))))
             ref (java.lang.ref.WeakReference. ^Object @box)]
         (subs/unsubscribe fid [:obs/items]) ;; ref-count → 0: dispose + evict
         (vreset! box nil)                   ;; drop the last ordinary strong ref
         (is (gc-until-cleared? ref)
             "a dropped, un-enrolled reaction is collectable")))
     (testing "ENROLLED — a committed owner left by an INTERRUPTED teardown does
               NOT pin its reaction through the weak node-records value (rf2-vxgfnd.37)"
       (let [lease-box (volatile! (obs/acquire! (items-target) (fn [_])))
             rx-box    (volatile! (:reaction (entry [:obs/items])))
             rx-ref    (java.lang.ref.WeakReference. ^Object @rx-box)
             lease-ref (java.lang.ref.WeakReference. ^Object @lease-box)]
         (is (= 1 (obs/active-owner-count @rx-box))
             "the lease is enrolled as an active owner in the weak node record")
         ;; Interrupted teardown: evict the cache entry (dropping the cache's
         ;; strong ref to the reaction) WITHOUT release! — so the owner is NEVER
         ;; de-enrolled and :owners still holds the lease. The ONLY remaining
         ;; strong path to the reaction is node-records value → :owners → lease →
         ;; state → reaction. Pre-fix (strong :reaction) that pins the weak key.
         (swap! (sub-cache) dissoc [:obs/items])
         (vreset! lease-box nil) ;; drop the last ordinary strong refs
         (vreset! rx-box nil)
         (is (gc-until-cleared? rx-ref)
             (str "the abandoned reaction is GC-collectable — the weak "
                  "node-records value no longer strong-references its own weak "
                  "key (this assertion FAILS on PR #5710's strong :reaction)"))
         ;; The reaction (weak KEY) is gone; a WeakHashMap operation now expunges
         ;; the stale entry, dropping the map's strong ref to the VALUE (record →
         ;; :owners → lease), which the next GC reclaims.
         (.size ^java.util.Map @#'obs/node-records)
         (is (gc-until-cleared? lease-ref)
             "the abandoned lease was reclaimed once its node record's weak key died")))))

;; ===========================================================================
;; rf2-wbkjk9 — exact-once first-emission provenance in the disposal-notify
;; escape drain: no double emission, no category spoofing
;;
;; #5782 (rf2-6ui49w) made swallowed on-change failures visible, but
;; report-disposal-notify-escape! re-dispatched EVERY throwable carrying a
;; truthy :rf.error/id. Two defects:
;;
;;   1. DOUBLE EMISSION — a callback that calls an observation op which
;;      emits-then-throws (obs/read on a released lease) got its category
;;      fanned TWICE: once at the source (with the source's correct
;;      frame/query attribution) and once by the drain (with the NOTIFYING
;;      owner's context overwriting it). Two always-on records for one
;;      runtime error — a Spec 009 one-runtime-error-law violation.
;;   2. CATEGORY SPOOFING — an application ex-info carrying any truthy
;;      :rf.error/id (`:app/not-catalogued`, a malformed non-keyword, or a
;;      bare imitation of a canonical id) was re-dispatched as though it
;;      were a catalogued framework category.
;;
;; The fix is OPAQUE, CHANNEL-AWARE emission PROVENANCE on the throwable
;; (rf2-w55bh0 supersedes the reconstructible `error-emit/fanned-at-source-key`
;; + `:rf.error/id`-shape scheme): the port's emit-then-throw sites stamp an
;; unforgeable `EmissionProvenance` token recording WHICH channel(s) the source
;; covered, and the drain consults `source-covered-always-on?` — never
;; :rf.error/id truthiness, never a reconstructible ex-data shape, never a global
;; seen-error registry. Two buckets:
;;
;;   - source already covered the ALWAYS-ON axis → nothing more (the source's
;;     record IS the exactly-once record, attribution intact);
;;   - NOT covered on the always-on axis (trace-only coverage, a diagnostic-only
;;     thrown category, an untyped bug, a malformed/imitated id, a spoofed
;;     public marker) → exactly one stable :rf.error/observation-on-change-failed
;;     wrapper, the escape riding as its cause — never promoting the escape's own
;;     category onto the always-on axis.
;;
;; Red-before-fix: with the provenance/choke-point repair reverted, the
;; released-lease fixtures below observe TWO read-after-release records
;; (the second with stolen attribution) and the spoof fixtures observe the
;; application id fanned as a framework category.
;; ===========================================================================

(defn- reg-other! []
  (rf/reg-sub :obs/other (fn [db _] (:items db))))

(defn- other-target []
  (obs/resolve-target {:frame fid :query-v [:obs/other]}))

(deftest hmr-drain-does-not-double-emit-an-already-fanned-typed-escape
  (reg-items!)
  (reg-other!)
  (seed-items! [:a])
  ;; A lease on a DIFFERENT sub, already released — reading it is the
  ;; deterministic emit-then-throw composition: obs/read first fans
  ;; :rf.error/read-after-release (with the RELEASED lease's own
  ;; [:obs/other] attribution), then throws the marked typed error.
  (let [released (obs/acquire! (other-target) (fn [_]))]
    (obs/release! released)
    (let [live (obs/acquire! (items-target) (fn [_n] (obs/read released)))]
      ;; HMR re-registration of :obs/items drains the :hmr boundary; the
      ;; live owner's on-change reads the released lease.
      (let [[[outcome _] records] (with-error-records #(reg-items!))]
        (is (= :ok outcome) "the registrar isolates the replacement hook")
        (let [rar (filterv #(= :rf.error/read-after-release (:error %)) records)]
          (testing "EXACTLY one always-on record for the one runtime error —
                    the source's own emission; the drain does not re-fan an
                    already-fanned typed escape (rf2-wbkjk9)"
            (is (= 1 (count rar))))
          (testing "the surviving record keeps the SOURCE's attribution (the
                    released lease's own sub), never the notifying owner's
                    [:obs/items] context"
            (is (= :obs/other (:event-id (first rar))))
            (is (= [:obs/other] (:event (first rar))))))
        (testing "a typed escape is never ALSO wrapped"
          (is (empty? (filterv #(= :rf.error/observation-on-change-failed (:error %))
                               records)))))
      (obs/release! live))))

(deftest disposed-drain-classifies-mixed-escapes-exactly-once-each
  (reg-items!)
  (reg-other!)
  (seed-items! [:a])
  (with-redefs [interop/next-tick (fn [_f] nil)]
    (let [released (obs/acquire! (other-target) (fn [_]))
          _        (obs/release! released)
          boom     (ex-info "untyped on-change boom" {::boom true})
          notes    (atom [])
          ;; Owner A — already-fanned typed: reads the released lease.
          la (obs/acquire! (items-target) (fn [_n] (obs/read released)))
          ;; Owner B — untyped consumer bug.
          lb (obs/acquire! (items-target) (fn [_n] (throw boom)))
          ;; Owner C — healthy sibling that MUST still be notified.
          lc (obs/acquire! (items-target) (fn [n] (swap! notes conj n)))]
      (is (= 3 (obs/active-owner-count (:reaction (entry [:obs/items])))))
      (force-dispose-node! [:obs/items])
      (let [[[outcome thrown] records]
            (with-error-records #(obs/drain-pending-disposals! :disposed))]
        (testing "the COMPLETE sibling drain ran despite two throwing owners"
          (is (= 1 (count @notes)))
          (is (= :disposed (:cause (first @notes)))))
        (testing "the already-fanned typed escape appears EXACTLY once, with
                  its source attribution"
          (let [rar (filterv #(= :rf.error/read-after-release (:error %)) records)]
            (is (= 1 (count rar)))
            (is (= :obs/other (:event-id (first rar))))))
        (testing "the untyped escape appears EXACTLY once, wrapped, carrying
                  the original throwable"
          (let [wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                 records)]
            (is (= 1 (count wrapped)))
            (is (identical? boom (:exception (first wrapped))))))
        (testing "the FIRST escape is rethrown to the direct caller with
                  identity/cause intact (owner-set order is unordered, so it
                  is ONE of the two originals — never a re-wrap)"
          (is (= :threw outcome))
          (is (or (identical? boom thrown)
                  (= :rf.error/read-after-release (error-id thrown))))))
      (obs/release! la)
      (obs/release! lb)
      (obs/release! lc))))

(deftest disposed-drain-wraps-a-diagnostic-only-typed-escape-without-promoting-it
  (reg-items!)
  (seed-items! [:a])
  (with-redefs [interop/next-tick (fn [_f] nil)]
    (let [bad (atom nil)
          ;; A DIAGNOSTIC-ONLY typed escape: a forbidden reentrant release! from
          ;; inside the fan-out throws the dev :rf.error/reentrant-graph-op assert
          ;; (Spec 009: diagnostic channel — a plain typed throw with NO always-on
          ;; fan of its own). rf2-w55bh0: the drain owns the callback failure's
          ;; always-on coverage and must NOT promote the diagnostic category onto
          ;; the always-on axis under its own id — it wraps it in the stable
          ;; :rf.error/observation-on-change-failed, the reentrant assert riding as
          ;; the cause.
          la  (obs/acquire! (items-target) (fn [_n] (obs/release! @bad)))]
      (reset! bad la)
      (force-dispose-node! [:obs/items])
      (let [[[outcome thrown] records]
            (with-error-records #(obs/drain-pending-disposals! :disposed))]
        (testing "the callback failure surfaces EXACTLY once, wrapped in the
                  stable always-on :rf.error/observation-on-change-failed"
          (let [wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                 records)]
            (is (= 1 (count wrapped)))
            (testing "attributed to the notifying former owner's entry sub, with
                      the diagnostic reentrant assert riding as the record's cause"
              (is (= :obs/items (:event-id (first wrapped))))
              (is (identical? thrown (:exception (first wrapped))))
              (is (= :rf.error/reentrant-graph-op
                     (error-id (:exception (first wrapped))))))))
        (testing "the diagnostic-only category is NEVER promoted onto the
                  always-on axis under its own id (rf2-w55bh0)"
          (is (empty? (filterv #(= :rf.error/reentrant-graph-op (:error %))
                               records))))
        (is (= :threw outcome))
        (is (= :rf.error/reentrant-graph-op (error-id thrown)))))))

(deftest hmr-drain-wraps-spoofed-and-malformed-ids-instead-of-fanning-them
  (reg-items!)
  (seed-items! [:a])
  (let [;; A non-catalogued APPLICATION id — must not ride the always-on
        ;; axis as though it were a canonical framework category.
        app-boom       (ex-info "app boom" {:rf.error/id :app/not-catalogued})
        ;; A MALFORMED id (non-keyword) — same wrapper arm.
        malformed-boom (ex-info "malformed boom" {:rf.error/id "not-a-keyword"})
        ;; A bare IMITATION of a canonical id (reserved namespace but not
        ;; the canonical thrown-error shape — no :reason sentence): id
        ;; truthiness alone must not spoof the category.
        imitation-boom (ex-info "imitation boom"
                                {:rf.error/id :rf.error/handler-exception})
        notes  (atom [])
        la (obs/acquire! (items-target) (fn [_n] (throw app-boom)))
        lb (obs/acquire! (items-target) (fn [_n] (throw malformed-boom)))
        lc (obs/acquire! (items-target) (fn [_n] (throw imitation-boom)))
        ld (obs/acquire! (items-target) (fn [n] (swap! notes conj n)))]
    (let [[[outcome _] records] (with-error-records #(reg-items!))]
      (is (= :ok outcome))
      (testing "the healthy sibling was still notified"
        (is (= 1 (count @notes)))
        (is (= :hmr (:cause (first @notes)))))
      (testing "no spoofed category reaches the always-on axis"
        (is (empty? (filterv #(= :app/not-catalogued (:error %)) records)))
        (is (empty? (filterv #(= "not-a-keyword" (:error %)) records)))
        (is (empty? (filterv #(= :rf.error/handler-exception (:error %)) records))))
      (testing "each throwable produced EXACTLY one stable wrapper record,
                carrying its original throwable as the cause"
        (let [wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                               records)
              causes  (set (map :exception wrapped))]
          (is (= 3 (count wrapped)))
          (is (contains? causes app-boom))
          (is (contains? causes malformed-boom))
          (is (contains? causes imitation-boom))
          (is (every? #(= :obs/items (:event-id %)) wrapped)))))
    (obs/release! la)
    (obs/release! lb)
    (obs/release! lc)
    (obs/release! ld)))

;; ===========================================================================
;; rf2-q3fmqm — drain-owned first emissions ride the shared TWO-CHANNEL
;; fan-out (visible to Xray's trace listener) and resolve SUBSCRIPTION
;; source coordinates
;;
;; The rf2-6ui49w emission called error-emit/dispatch-on-error! directly: it
;; reached the always-on production axis but never the dev diagnostic trace
;; — Xray installs a TRACE-tooling listener, not an always-on error
;; listener, so a real HMR/disposed callback failure produced one always-on
;; record and ZERO trace events (and the registrar swallows the drain's
;; rethrow on :hmr, leaving no alternate diagnostic). The record also
;; mis-resolved source coordinates: its :event-id slot carries the former
;; owner's ENTRY SUB id, but error_emit.cljc did not classify the category
;; subscription-owned, so lookup consulted [:event id] — a same-id EVENT
;; registration could steal attribution, and a macro-registered sub with no
;; colliding event got NO coordinate at all.
;;
;; The fix: when the drain owns the first emission (per rf2-wbkjk9's
;; provenance — an already-fanned typed escape is NOT fanned again on
;; either channel), it uses the shared emit-error-both! two-channel fan-out
;; with category-specific trace tags, and error_emit classifies
;; :rf.error/observation-on-change-failed among the sub-error categories so
;; [:sub id] is the ONLY lookup realm. Red-before-fix: trace count zero,
;; coordinate missing (macro sub) / stolen (event collision).
;; ===========================================================================

(defn- with-both-channels
  "Run `thunk` capturing BOTH error channels: the always-on error-emit
  records AND the dev diagnostic-trace `:op-type :error` events (the surface
  Xray's trace collector consumes). Returns
  `[result-or-thrown records trace-errors]`."
  [thunk]
  (let [records (atom [])
        traces  (atom [])]
    (error-emit/register-error-listener!
      ::records (fn [record] (swap! records conj record)))
    (rf/register-listener! :trace ::traces (fn [ev] (swap! traces conj ev)))
    (try
      (let [result (try [:ok (thunk)]
                        (catch #?(:clj Throwable :cljs :default) e
                          [:threw e]))]
        [result @records (filterv #(= :error (:op-type %)) @traces)])
      (finally
        (rf/unregister-listener! :trace ::traces)
        (error-emit/unregister-error-listener! ::records)))))

(deftest hmr-drain-fans-the-wrapper-on-both-channels-with-the-sub-source-coordinate
  (reg-items!)                       ;; MACRO-registered → coords under [:sub :obs/items]
  (seed-items! [:a])
  (let [boom (ex-info "untyped on-change boom" {::boom true})
        la   (obs/acquire! (items-target) (fn [_n] (throw boom)))]
    ;; A REAL HMR failure: the sub re-registration drains the :hmr boundary
    ;; inside the registrar replacement hook (which swallows the rethrow), and
    ;; the former owner's on-change throws the raw untyped bug.
    (let [[[outcome _] records traces] (with-both-channels #(reg-items!))]
      (is (= :ok outcome) "the registrar isolates the replacement hook")
      (testing "exactly ONE always-on record — the production axis is unchanged"
        (let [wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                               records)]
          (is (= 1 (count wrapped)))
          (is (identical? boom (:exception (first wrapped))))
          (testing "…and it resolves the EXACT macro-registered [:sub id]
                    source coordinate (pre-fix: the slot was absent — lookup
                    consulted [:event :obs/items])"
            (let [coord (source-coords/error-coords-for :sub :obs/items)]
              (is (some? coord) "the macro registration captured coords")
              (is (= coord (:source-coord (first wrapped))))))))
      (testing "exactly ONE dev diagnostic-trace event for the same logical
                failure — Xray's trace collector sees it without registering
                an always-on listener (pre-fix: zero trace events)"
        (let [tev (filterv #(= :rf.error/observation-on-change-failed (:operation %))
                           traces)]
          (is (= 1 (count tev)))
          (let [tags (:tags (first tev))]
            (is (identical? boom (:exception tags))
                "the trace tags carry the original throwable")
            (is (= :hmr (:cause tags))
                "the category-specific tags carry the disposal cause")
            (is (= :obs/items (:rf.sub/id tags)))
            (is (= [:obs/items] (:rf.sub/query-v tags)))
            (is (= fid (:frame tags)))))))
    (obs/release! la)))

(deftest disposed-drain-fans-the-wrapper-on-both-channels
  (reg-items!)
  (seed-items! [:a])
  (with-redefs [interop/next-tick (fn [_f] nil)]
    (let [boom (ex-info "untyped on-change boom" {::boom true})
          la   (obs/acquire! (items-target) (fn [_n] (throw boom)))]
      (force-dispose-node! [:obs/items])
      (let [[[outcome thrown] records traces]
            (with-both-channels #(obs/drain-pending-disposals! :disposed))]
        (is (= :threw outcome))
        (is (identical? boom thrown))
        (is (= 1 (count (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                 records)))
            "exactly one always-on record at the :disposed boundary")
        (let [tev (filterv #(= :rf.error/observation-on-change-failed (:operation %))
                           traces)]
          (is (= 1 (count tev)) "exactly one trace event at the :disposed boundary")
          (is (= :disposed (:cause (:tags (first tev)))))))
      (obs/release! la))))

(deftest drain-two-channel-fanout-composes-with-first-emission-provenance
  ;; An ALREADY-FANNED typed escape (the released-lease read) gains neither a
  ;; second always-on record NOR a second trace event from the drain: the
  ;; source's own emit-error-both! is the one two-channel emission.
  (reg-items!)
  (reg-other!)
  (seed-items! [:a])
  (let [released (obs/acquire! (other-target) (fn [_]))]
    (obs/release! released)
    (let [live (obs/acquire! (items-target) (fn [_n] (obs/read released)))]
      (let [[[outcome _] records traces] (with-both-channels #(reg-items!))]
        (is (= :ok outcome))
        (is (= 1 (count (filterv #(= :rf.error/read-after-release (:error %)) records)))
            "one always-on record — the source's")
        (is (= 1 (count (filterv #(= :rf.error/read-after-release (:operation %)) traces)))
            "one trace event — the source's; the drain adds none")
        (is (empty? (filterv #(= :rf.error/observation-on-change-failed (:operation %))
                             traces))
            "no wrapper trace event for a typed escape"))
      (obs/release! live))))

(deftest disposed-drain-wraps-a-diagnostic-only-typed-escape-on-both-channels
  ;; When the drain owns the callback failure's coverage for a DIAGNOSTIC-ONLY
  ;; typed escape (the dev reentrant assert), the two-channel fan-out applies to
  ;; the stable WRAPPER: one always-on record AND one trace event under
  ;; :rf.error/observation-on-change-failed — the diagnostic category is never
  ;; promoted onto the always-on axis under its own id (rf2-w55bh0).
  (reg-items!)
  (seed-items! [:a])
  (with-redefs [interop/next-tick (fn [_f] nil)]
    (let [bad (atom nil)
          la  (obs/acquire! (items-target) (fn [_n] (obs/release! @bad)))]
      (reset! bad la)
      (force-dispose-node! [:obs/items])
      (let [[[outcome _] records traces]
            (with-both-channels #(obs/drain-pending-disposals! :disposed))]
        (is (= :threw outcome))
        (is (= 1 (count (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                 records)))
            "exactly one always-on wrapper record")
        (is (empty? (filterv #(= :rf.error/reentrant-graph-op (:error %)) records))
            "the diagnostic category is NOT promoted onto the always-on axis")
        (let [tev (filterv #(= :rf.error/observation-on-change-failed (:operation %))
                           traces)]
          (is (= 1 (count tev))
              "the drain-owned wrapper reaches the trace channel too")
          (is (= :disposed (:cause (:tags (first tev))))))))))

(deftest collision-event-registration-cannot-steal-sub-attribution
  ;; PROGRAMMATIC sub registration (no [:sub id] coords) + MACRO event
  ;; registration under the SAME keyword ([:event id] coords present). The
  ;; record must OMIT :source-coord — never steal the event's coordinate.
  (subs/reg-sub :obs/collide (fn [db _] (:items db)))
  (rf/reg-event :obs/collide (fn [_cofx _event] {}))
  (seed-items! [:a])
  (is (nil? (source-coords/error-coords-for :sub :obs/collide))
      "the programmatic sub registration captured NO coords")
  (is (some? (source-coords/error-coords-for :event :obs/collide))
      "the macro event registration DID capture coords under [:event id]")
  (with-redefs [interop/next-tick (fn [_f] nil)]
    (let [boom   (ex-info "untyped on-change boom" {::boom true})
          target (obs/resolve-target {:frame fid :query-v [:obs/collide]})
          la     (obs/acquire! target (fn [_n] (throw boom)))]
      (force-dispose-node! [:obs/collide])
      (let [[[_outcome _] records _traces]
            (with-both-channels #(obs/drain-pending-disposals! :disposed))]
        (let [wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                               records)]
          (is (= 1 (count wrapped)))
          (is (not (contains? (first wrapped) :source-coord))
              "a programmatic sub omits the slot; the same-id EVENT
               registration's coordinate is NOT stolen")))
      (obs/release! la))))

;; ===========================================================================
;; rf2-w55bh0 — the drain's emission provenance is OPAQUE and CHANNEL-AWARE
;;
;; The rf2-wbkjk9 scheme (a public `error-emit/fanned-at-source-key` truthy
;; marker + a `:rf.error/id`-plus-`:reason` SHAPE test) was both CHANNEL-BLIND
;; and RECONSTRUCTIBLE. Three defects the fixtures below pin:
;;
;;   1. CHANNEL-BLIND SUPPRESSION — a source that fanned ONLY the diagnostic
;;      trace axis (the production-elided :rf.error/sub-cycle) was stamped with
;;      the same Boolean "fanned" marker as a source that covered the always-on
;;      axis, so the drain mistook trace coverage for full coverage and emitted
;;      NOTHING. An HMR/disposed callback reaching a cyclic acquire went silent
;;      in production.
;;   2. DIAGNOSTIC-CATEGORY PROMOTION — an unfanned diagnostic-only thrown
;;      category (:rf.error/observation-malformed-target, the dev
;;      :rf.error/reentrant-graph-op assert) was dynamically re-fanned through
;;      emit-error-both! under its OWN id, promoting a category Spec 009
;;      EXCLUDES from the always-on axis.
;;   3. FORGEABLE PROVENANCE — an application ex-info could copy the public
;;      marker (to SUPPRESS reporting) or carry a reserved / imitated
;;      `:rf.error/id` + `:reason` (to INJECT a framework category).
;;
;; The fix: observation stamps an OPAQUE, unforgeable `EmissionProvenance` token
;; recording WHICH channel(s) the source covered, and the drain asks
;; `source-covered-always-on?`. A source that did not cover the always-on axis
;; (trace-only, a diagnostic-only thrown category, an untyped bug, or a spoof)
;; gets exactly one stable :rf.error/observation-on-change-failed wrapper — the
;; escape riding as its cause — never promoting its own category. Red-before-fix
;; is noted per fixture; the fixtures ALSO fail if channel coverage is collapsed
;; back to a Boolean or the opaque token is replaced with a reconstructible map
;; shape.
;; ===========================================================================

(deftest disposed-drain-adds-always-on-callback-failure-when-source-covered-only-trace
  ;; CHANNEL-BLIND SUPPRESSION. A former-owner on-change callback that reaches a
  ;; CYCLIC acquire throws the diagnostic-only :rf.error/sub-cycle, whose only
  ;; emission is the sub build's production-elided TRACE event. In advanced
  ;; production, where the dev reentrancy guard is DCE'd, the on-change's own
  ;; synchronous cyclic acquire throws exactly this; we model that in this debug
  ;; test by acquiring the cyclic target to obtain the REAL observation sub-cycle
  ;; throw (trace-only provenance) and re-raising it from the on-change. Pre-fix
  ;; the port stamped it with a channel-BLIND Boolean, so the drain suppressed
  ;; everything — production got no record. The channel-AWARE fix adds exactly one
  ;; always-on callback-failure record WITHOUT promoting sub-cycle.
  ;; Red-before-fix / channel-collapse-to-Boolean: wrapped count 0.
  (reg-items!)
  (rf/reg-sub :obs/wcyc1 :<- [:obs/wcyc2] (fn [v _] v))
  (rf/reg-sub :obs/wcyc2 :<- [:obs/wcyc1] (fn [v _] v))
  (seed-items! [:a])
  (with-redefs [interop/next-tick (fn [_f] nil)]
    (let [cyclic       (obs/resolve-target {:frame fid :query-v [:obs/wcyc1]})
          sub-cycle-ex (try (obs/acquire! cyclic (fn [_]))
                            (catch #?(:clj Throwable :cljs :default) e e))
          notes        (atom [])
          la (obs/acquire! (items-target) (fn [_n] (throw sub-cycle-ex)))
          lb (obs/acquire! (items-target) (fn [n] (swap! notes conj n)))]
      (is (= :rf.error/sub-cycle (error-id sub-cycle-ex))
          "the fixture carries a REAL observation sub-cycle throw")
      (force-dispose-node! [:obs/items])
      (let [[[outcome thrown] records]
            (with-error-records #(obs/drain-pending-disposals! :disposed))]
        (testing "the healthy sibling still drained"
          (is (= 1 (count @notes))))
        (testing "exactly one production-survivable callback-failure record —
                  trace coverage of the diagnostic sub-cycle is NOT full coverage
                  (pre-fix / channel-collapse-to-Boolean: zero)"
          (let [wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                 records)]
            (is (= 1 (count wrapped)))
            (is (identical? sub-cycle-ex (:exception (first wrapped)))
                "the diagnostic sub-cycle throw rides as the wrapper's cause")
            (is (= :obs/items (:event-id (first wrapped))))))
        (testing "the diagnostic-only sub-cycle category is NEVER promoted onto
                  the always-on axis"
          (is (empty? (filterv #(= :rf.error/sub-cycle (:error %)) records))))
        (testing "the first escape is rethrown to the direct caller with the
                  sub-cycle identity intact"
          (is (= :threw outcome))
          (is (identical? sub-cycle-ex thrown))
          (is (= :rf.error/sub-cycle (error-id thrown)))))
      (obs/release! la)
      (obs/release! lb))))

(deftest disposed-drain-wraps-a-diagnostic-only-malformed-target-escape-without-promoting-it
  ;; DIAGNOSTIC-CATEGORY PROMOTION. A former-owner on-change that probes a
  ;; malformed target throws the diagnostic-only
  ;; :rf.error/observation-malformed-target (probe carries no reentrancy guard, so
  ;; it reaches the closed-target grammar gate). Pre-fix the drain trusted the
  ;; canonical thrown-error SHAPE and dynamically re-fanned malformed-target
  ;; through emit-error-both!, promoting a category Spec 009 EXCLUDES from the
  ;; always-on axis. Red-before-fix: :rf.error/observation-malformed-target
  ;; appears among the always-on records (and the wrapper is absent).
  (reg-items!)
  (seed-items! [:a])
  (with-redefs [interop/next-tick (fn [_f] nil)]
    (let [notes (atom [])
          la (obs/acquire! (items-target) (fn [_n] (obs/probe {:kind :bogus})))
          lb (obs/acquire! (items-target) (fn [n] (swap! notes conj n)))]
      (force-dispose-node! [:obs/items])
      (let [[[outcome thrown] records]
            (with-error-records #(obs/drain-pending-disposals! :disposed))]
        (is (= 1 (count @notes)) "the healthy sibling still drained")
        (testing "the callback failure is wrapped exactly once on the always-on
                  axis, carrying the malformed-target throw as its cause"
          (let [wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                 records)]
            (is (= 1 (count wrapped)))
            (is (= :rf.error/observation-malformed-target
                   (error-id (:exception (first wrapped)))))))
        (testing "the diagnostic-only malformed-target category is NEVER promoted
                  onto the always-on axis"
          (is (empty? (filterv #(= :rf.error/observation-malformed-target (:error %))
                               records))))
        (is (= :threw outcome))
        (is (= :rf.error/observation-malformed-target (error-id thrown))))
      (obs/release! la)
      (obs/release! lb))))

(deftest disposed-drain-cannot-be-suppressed-or-spoofed-by-forged-provenance
  ;; FORGEABLE PROVENANCE. An application on-change can copy any keyword or
  ;; literal into its thrown ex-data, but it cannot construct the
  ;; framework-internal EmissionProvenance token — so no forgery can SUPPRESS the
  ;; always-on callback-failure record or INJECT a category. Red-before-fix: the
  ;; public marker suppressed the drain (wrapped absent) and the reserved /
  ;; imitated ids were fanned as framework categories.
  (reg-items!)
  (seed-items! [:a])
  (with-redefs [interop/next-tick (fn [_f] nil)]
    (let [;; (1) a copy of the OLD public "already-fanned" marker — pre-fix this
          ;;     suppressed the drain entirely (fanned-at-source? true).
          public-marker (ex-info "forged public marker"
                                 {error-emit/fanned-at-source-key true})
          ;; (2) a RECONSTRUCTED provenance-shaped map under the (guessable)
          ;;     framework key — fails the instance? gate; this ALSO fails if the
          ;;     opaque token is replaced with a reconstructible map shape.
          shape-forgery (ex-info "forged provenance shape"
                                 {:re-frame.substrate.observation/emission-provenance
                                  {:channels #{:always-on :trace}}})
          ;; (3) a reserved-but-UNCATALOGUED id + plausible :reason — pre-fix
          ;;     canonical-typed-error? trusted the shape and INJECTED it.
          reserved-boom (ex-info "reserved uncatalogued"
                                 {:rf.error/id :rf.error/not-in-catalogue
                                  :reason      "a plausible framework sentence"})
          ;; (4) an IMITATED existing catalogued id + plausible :reason — pre-fix
          ;;     this SPOOFED :rf.error/frame-destroyed onto the axis.
          imitation     (ex-info "imitated existing id"
                                 {:rf.error/id :rf.error/frame-destroyed
                                  :reason      "a plausible framework sentence"})
          notes (atom [])
          la (obs/acquire! (items-target) (fn [_n] (throw public-marker)))
          lb (obs/acquire! (items-target) (fn [_n] (throw shape-forgery)))
          lc (obs/acquire! (items-target) (fn [_n] (throw reserved-boom)))
          ld (obs/acquire! (items-target) (fn [_n] (throw imitation)))
          le (obs/acquire! (items-target) (fn [n] (swap! notes conj n)))]
      (force-dispose-node! [:obs/items])
      (let [[[_ _] records]
            (with-error-records #(obs/drain-pending-disposals! :disposed))]
        (testing "the healthy sibling was still notified"
          (is (= 1 (count @notes))))
        (testing "no forged marker can SUPPRESS the always-on callback-failure —
                  each forgery yields exactly one stable wrapper carrying its
                  original throwable as the cause"
          (let [wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                 records)
                causes  (set (map :exception wrapped))]
            (is (= 4 (count wrapped)))
            (is (contains? causes public-marker))
            (is (contains? causes shape-forgery))
            (is (contains? causes reserved-boom))
            (is (contains? causes imitation))
            (is (every? #(= :obs/items (:event-id %)) wrapped))))
        (testing "no forged id is INJECTED onto the always-on axis"
          (is (empty? (filterv #(= :rf.error/not-in-catalogue (:error %)) records)))
          (is (empty? (filterv #(= :rf.error/frame-destroyed (:error %)) records)))))
      (obs/release! la) (obs/release! lb) (obs/release! lc)
      (obs/release! ld) (obs/release! le))))

;; ===========================================================================
;; rf2-9m4oy7 — emission provenance is bound to the EXACT throwable
;;
;; The interim rf2-w55bh0 token carried provenance in the thrown ex-data under a
;; framework-internal key and trusted `(instance? EmissionProvenance …)`. Two
;; spoof vectors defeated it and restored production silence:
;;
;;   1. GENERATED-CONSTRUCTOR FORGE — `EmissionProvenance` is a deftype, so CLJ
;;      and CLJS generate a callable cross-namespace `->EmissionProvenance`
;;      factory. An application on-change can construct a PASSING token and stuff
;;      it into its OWN ex-data under the (known) key, suppressing the always-on
;;      callback-failure record — the merged rf2-w55bh0 spoof fixture only tried a
;;      Boolean and a plain map, never the real constructor.
;;   2. AUTHENTIC-TOKEN TRANSPLANT — even hiding the constructor is insufficient:
;;      an app can copy an AUTHENTIC token out of a caught framework throwable's
;;      ex-data into an unrelated exception, again suppressing the record.
;;
;; The fix binds provenance to the EXACT throwable through a PRIVATE weak
;; association (obs/attest-provenance! → obs/source-covered-always-on?), never
;; public ex-data. Only the exact throwable this port minted-and-bound reads
;; covered; a forged token, a transplanted token, and any fresh unrelated
;; throwable read uncovered. Re-throwing the EXACT bound throwable stays
;; exact-once (its source emission stands, no wrapper). This fixture uses DISTINCT
;; throwables so each drain record provably carries ITS throwable's provenance —
;; never a shared/last-write-wins one. Red-before-fix (ex-data + instance?
;; verifier): the forged and transplanted tokens SUPPRESS, so the wrapper count is
;; 0 (criterion 5 — swapping exact-throwable authentication for instance? /
;; channel-membership fails this test).
;; ===========================================================================

(deftest disposed-drain-binds-emission-provenance-to-the-exact-throwable
  (reg-items!)
  (seed-items! [:a])
  ;; An AUTHENTIC port-minted throwable, bound `#{:always-on :trace}` at its
  ;; source: reading a lease AFTER release! throws :rf.error/read-after-release,
  ;; which the port fans on the always-on axis and binds to THAT exact throwable.
  (let [setup-lease (obs/acquire! (items-target) (fn [_]))
        _           (obs/release! setup-lease)
        auth-ex     (try (obs/read setup-lease)
                         (catch #?(:clj Throwable :cljs :default) e e))
        ;; The token an attacker could scrape from the authentic throwable's
        ;; ex-data. Pre-fix it was present there (and transplantable); post-fix it
        ;; is nil — authentic provenance lives ONLY in the private throwable-keyed
        ;; association, never public ex-data.
        scraped     (get (ex-data auth-ex)
                         :re-frame.substrate.observation/emission-provenance)]
    (is (= :rf.error/read-after-release (error-id auth-ex))
        "the fixture carries a REAL port-minted always-on throwable")
    (is (nil? scraped)
        "authentic provenance is NOT carried in public ex-data (rf2-9m4oy7)")
    (with-redefs [interop/next-tick (fn [_f] nil)]
      (let [;; (1) FORGE via the generated constructor: a fresh, REAL
            ;;     EmissionProvenance covering the always-on axis, stuffed into an
            ;;     unrelated exception's ex-data under the guessable framework key.
            ;;     Pre-fix (instance? on ex-data): SUPPRESSED. Post-fix: uncovered.
            forged-ex     (ex-info "forged generated-constructor token"
                                   {:re-frame.substrate.observation/emission-provenance
                                    (obs/->EmissionProvenance #{:always-on :trace})})
            ;; (2) TRANSPLANT: whatever the app scraped from the authentic
            ;;     throwable, copied onto an unrelated exception. Pre-fix (with the
            ;;     token in ex-data): SUPPRESSED. Post-fix: uncovered (a DIFFERENT
            ;;     throwable is absent from the throwable-keyed association).
            transplant-ex (ex-info "transplanted authentic token"
                                   {:re-frame.substrate.observation/emission-provenance
                                    scraped})
            notes (atom [])
            ;; (3) RE-THROW the EXACT bound throwable — its source emission stands,
            ;;     so the drain must add NO wrapper for it (exact-once).
            la (obs/acquire! (items-target) (fn [_n] (throw auth-ex)))
            lb (obs/acquire! (items-target) (fn [_n] (throw forged-ex)))
            lc (obs/acquire! (items-target) (fn [_n] (throw transplant-ex)))
            ld (obs/acquire! (items-target) (fn [n] (swap! notes conj n)))]
        (force-dispose-node! [:obs/items])
        (let [[[_ _] records]
              (with-error-records #(obs/drain-pending-disposals! :disposed))
              wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                               records)
              causes  (set (map :exception wrapped))]
          (testing "the healthy sibling was still notified"
            (is (= 1 (count @notes))))
          (testing "the forged and transplanted tokens CANNOT suppress the
                    always-on callback-failure — each yields exactly one stable
                    wrapper carrying its OWN throwable (pre-fix / ex-data+instance?
                    verifier: zero wrappers)"
            (is (= 2 (count wrapped)))
            (is (contains? causes forged-ex))
            (is (contains? causes transplant-ex))
            (is (every? #(= :obs/items (:event-id %)) wrapped)))
          (testing "the EXACT bound throwable is covered — its source emission
                    stands, so the drain adds NO wrapper for it (exact-once, its
                    provenance is NOT confused with a sibling's)"
            (is (not (contains? causes auth-ex))))
          (testing "no framework category is injected onto the always-on axis by
                    the drain"
            (is (empty? (filterv #(= :rf.error/read-after-release (:error %))
                                 records)))))
        (obs/release! la) (obs/release! lb) (obs/release! lc) (obs/release! ld)))))

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

;; ===========================================================================
;; rf2-xakb4p — the catalogue-to-schema / record validation gate.
;;
;; The disposal-notify wrapper's record shape is now FROZEN by a canonical
;; per-category schema ([Spec-Schemas §ObservationOnChangeFailedTags]). This
;; gate binds that schema to the ACTUAL runtime record fanned at BOTH drain
;; boundaries, in two directions:
;;
;;   1. RECORD → SCHEMA (both hosts): a real :hmr drain and a real :disposed
;;      drain produce dev-trace `:tags` that satisfy the schema's required-key
;;      contract, and the always-on record carries the wire fields the 009
;;      catalogue row lists. Dropping any required key, or drifting the
;;      channel-discriminating :cause / spoofing :category, fails the gate.
;;   2. SCHEMA → MARKDOWN (JVM only): Spec-Schemas.md must declare the schema
;;      enumerating every required key — so removing the row or dropping a key
;;      from the markdown fails too, and the two surfaces cannot drift silently.
;;
;; (The CHANNEL classification — always-on — is already pinned by
;; error-catalogue-channel-conformance-test + always-on-axis-conformance's
;; `always-on-categories` literal, which includes this category.)
;; ===========================================================================

(def ^:private observation-tags-required-keys
  [:category :rf.sub/id :rf.sub/query-v :where :cause :exception
   :exception-message :reason])

(defn- valid-observation-on-change-failed-tags?
  "Structural conformance of a dev-trace `:tags` payload against the canonical
  [Spec-Schemas §ObservationOnChangeFailedTags] required-key contract. Mirrors
  the markdown schema; the JVM leg below pins the markdown itself so the two
  cannot drift silently."
  [tags]
  (and (map? tags)
       (= :rf.error/observation-on-change-failed (:category tags))
       (keyword? (:rf.sub/id tags))
       (vector? (:rf.sub/query-v tags))
       (symbol? (:where tags))
       (contains? #{:hmr :disposed} (:cause tags))
       (some? (:exception tags))
       (string? (:exception-message tags))
       (string? (:reason tags))))

(deftest hmr-and-disposed-records-validate-against-the-canonical-schema
  (testing ":hmr boundary — the record validates against the frozen schema"
    (reg-items!)
    (seed-items! [:a])
    (let [boom (ex-info "untyped on-change boom" {::boom true})
          la   (obs/acquire! (items-target) (fn [_n] (throw boom)))]
      (let [[_ records traces] (with-both-channels #(reg-items!))
            tev (first (filterv #(= :rf.error/observation-on-change-failed (:operation %))
                                traces))
            rec (first (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                records))]
        (is (some? tev) "exactly the one dev-trace event to validate")
        (is (valid-observation-on-change-failed-tags? (:tags tev))
            "the :hmr dev-trace tags satisfy the canonical schema")
        (is (= :hmr (:cause (:tags tev))))
        (testing "the always-on record carries the wire fields the 009 row lists"
          (is (some? rec))
          (is (= [:obs/items] (:event rec)))
          (is (= :obs/items (:event-id rec)))
          (is (= fid (:frame rec)))
          (is (identical? boom (:exception rec)))))
      (obs/release! la)))
  (testing ":disposed boundary — the same schema binds the fallback boundary"
    (reg-items!)
    (seed-items! [:a])
    (with-redefs [interop/next-tick (fn [_f] nil)]
      (let [boom (ex-info "untyped on-change boom" {::boom true})
            la   (obs/acquire! (items-target) (fn [_n] (throw boom)))]
        (force-dispose-node! [:obs/items])
        (let [[_ records traces]
              (with-both-channels #(obs/drain-pending-disposals! :disposed))
              tev (first (filterv #(= :rf.error/observation-on-change-failed (:operation %))
                                  traces))
              rec (first (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                  records))]
          (is (valid-observation-on-change-failed-tags? (:tags tev))
              "the :disposed dev-trace tags satisfy the canonical schema")
          (is (= :disposed (:cause (:tags tev))))
          (is (some? rec))
          (is (identical? boom (:exception rec))))
        (obs/release! la)))))

(deftest schema-gate-rejects-required-key-and-value-drift
  (reg-items!)
  (seed-items! [:a])
  (with-redefs [interop/next-tick (fn [_f] nil)]
    (let [boom (ex-info "untyped on-change boom" {::boom true})
          la   (obs/acquire! (items-target) (fn [_n] (throw boom)))]
      (force-dispose-node! [:obs/items])
      (let [[_ _ traces]
            (with-both-channels #(obs/drain-pending-disposals! :disposed))
            tags (:tags (first (filterv #(= :rf.error/observation-on-change-failed
                                            (:operation %))
                                        traces)))]
        (is (valid-observation-on-change-failed-tags? tags)
            "baseline: the real record validates")
        (testing "removing any required attribution key fails the gate"
          (doseq [k observation-tags-required-keys]
            (is (not (valid-observation-on-change-failed-tags? (dissoc tags k)))
                (str "removing required key " k " must fail"))))
        (testing "a :cause off the :hmr/:disposed channel-discriminator fails"
          (is (not (valid-observation-on-change-failed-tags?
                     (assoc tags :cause :bogus)))))
        (testing "a spoofed :category fails"
          (is (not (valid-observation-on-change-failed-tags?
                     (assoc tags :category :rf.error/handler-exception))))))
      (obs/release! la))))

#?(:clj
   (deftest spec-schemas-declares-the-observation-on-change-failed-schema
     (let [f     (let [nested (io/file "../../spec/Spec-Schemas.md")
                       legacy (io/file "../spec/Spec-Schemas.md")]
                   (if (.exists nested) nested legacy))
           text  (slurp f)
           start (str/index-of text "(def ObservationOnChangeFailedTags")
           block (when start
                   (let [rest*    (subs text start)
                         next-def (str/index-of (subs rest* 5) "(def ")]
                     (subs rest* 0 (if next-def (+ 5 next-def) (count rest*)))))]
       (is (some? block)
           "Spec-Schemas.md must declare ObservationOnChangeFailedTags")
       (when block
         (is (str/includes? block ":rf.error/observation-on-change-failed")
             "the schema pins the canonical category")
         (doseq [k [":category" ":rf.sub/id" ":rf.sub/query-v" ":where"
                    ":cause" ":exception" ":exception-message" ":reason"]]
           (is (str/includes? block k)
               (str "the schema must enumerate the required key " k)))))))
