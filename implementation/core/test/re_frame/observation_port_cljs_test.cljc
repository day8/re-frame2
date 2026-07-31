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
  epoch evidence), which is the headless contract.

  ## Posture split (rf2-d2841)

  Only ten of the ninety-four deftests here needed anything, and the whole
  port — acquire / read / release, ref-counting, disposal, movement
  evidence, retention, provenance — runs identically in both postures. What
  splits is narrow and falls into exactly two shapes.

  1. THE DEV REENTRANCY ASSERT IS THE DRIVER, NOT THE OBSERVATION.
     `observation/assert-not-in-fan-out!` is wrapped in
     `(when interop/debug-enabled? …)` at its source — checked, not assumed —
     so under the gate a forbidden reentrant `release!` from inside the
     fan-out simply SUCCEEDS. Five fixtures use that assert to manufacture a
     DIAGNOSTIC-ONLY typed escape; with no escape there is no callback
     failure, no wrapper record, and nothing to classify. Those scenarios are
     dev-only end to end, so the escape half is guarded together with the
     precondition that creates it. Where such a fixture ALSO carries a
     production claim — the well-behaved sibling owner is still notified,
     the macro registrations captured discriminable `[:sub id]` / `[:event
     id]` coordinates — that claim stays outside the guard.

  2. THE ALWAYS-ON HALF OF A TWO-CHANNEL FAN-OUT ALREADY PASSED.
     `:rf.error/observation-on-change-failed` is a PROMOTED category:
     the drain emits it through `emit-error-both!`, so the record reaches the
     always-on axis under the gate and every assertion about the record —
     count, `:event-id`, exact `:exception` identity, and the resolved
     `[:sub id]` `:source-coord` — runs unchanged. Only the DEV-TRACE twin
     (`:op-type :error` events and their `:tags`) is elided, and only those
     reads are guarded. That is the point of the rf2-q3fmqm suites: the two
     channels are independent, and this pass demonstrates the independence by
     running one of them alone.

  SEVENTEEN VACUOUS PASSES CAME OFF, and the schema gate held twelve of them.
  `schema-gate-rejects-required-key-and-value-drift` reads the dev-trace
  `:tags` into `tags`, which is nil under the gate — and NOTHING it then
  asserts can tell the difference. `(not (valid-… (dissoc tags k)))` is a
  DOSEQ over the eight required keys, and `(dissoc nil k)` is nil, so all
  eight drift-detection assertions passed on nil; so did the `:cause`
  and `:category` spoof rejections (`(assoc nil …)` yields a one-key map that
  is invalid for the wrong reason) and the two exact-identity negatives
  (`(not (identical? boom nil))`). A gate whose entire purpose is to redden on
  schema drift was certifying drift-detection over an absent record. The
  remaining five are ordinary class-1 negatives — \"the diagnostic category is
  NOT promoted onto the always-on axis\" over record streams that carried no
  records at all, in four of the five reentrant-driven fixtures, plus
  `drain-two-channel-fanout-composes-with-first-emission-provenance`'s
  \"no wrapper trace event\" over an empty trace stream."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.string :as str])
            ;; rf2-qyvyes — the canonical ObservationOnChangeFailedTags schema is
            ;; EXTRACTED from spec/Spec-Schemas.md at COMPILE TIME (a JVM-only macro
            ;; that runs for both the CLJ eval and the CLJS compilation), so both
            ;; hosts validate emitted records against the executable canonical form.
            #?(:clj [re-frame.observation-schema-extract
                     :refer [canonical-observation-schema]])
            [re-frame.core                  :as rf]
            #?(:cljs [re-frame.disposable :as rf-disposable])
            [re-frame.error-emit            :as error-emit]
            [re-frame.frame                 :as frame]
            [re-frame.live-frame            :as live-frame]
            [re-frame.interop               :as interop]
            [re-frame.source-coords         :as source-coords]
            [re-frame.subs                  :as subs]
            [re-frame.subs.cache            :as subs-cache]
            [re-frame.substrate.adapter     :as substrate-adapter]
            [re-frame.substrate.observation :as obs]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support])
  #?(:cljs (:require-macros [re-frame.observation-schema-extract
                             :refer [canonical-observation-schema]])))

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

(defprotocol PullWatchHostTest
  (fire-host-change! [host prev nu])
  (host-last-read [host])
  (host-read-count [host]))

#?(:clj
   (deftype PullWatchHost
     [source-containers compute-fn reads last-read watches validator]
     clojure.lang.IDeref
     (deref [_]
       (let [value {:pull     (swap! reads inc)
                    :computed (apply compute-fn (map deref source-containers))}]
         (reset! last-read value)
         value))
     clojure.lang.IRef
     (setValidator [_ vf] (reset! validator vf))
     (getValidator [_] @validator)
     (getWatches [_] @watches)
     (addWatch [this key callback]
       (swap! watches assoc key callback)
       this)
     (removeWatch [this key]
       (swap! watches dissoc key)
       this)
     PullWatchHostTest
     (fire-host-change! [this prev nu]
       (doseq [[key callback] @watches]
         (callback key this prev nu)))
     (host-last-read [_] @last-read)
     (host-read-count [_] @reads))
   :cljs
   (deftype PullWatchHost
     [source-containers compute-fn reads last-read watches on-dispose-fns disposed?]
     IDeref
     (-deref [_]
       (let [value {:pull     (swap! reads inc)
                    :computed (apply compute-fn (map deref source-containers))}]
         (reset! last-read value)
         value))
     IWatchable
     (-notify-watches [this prev nu]
       (doseq [[key callback] @watches]
         (callback key this prev nu)))
     (-add-watch [this key callback]
       (swap! watches assoc key callback)
       this)
     (-remove-watch [this key]
       (swap! watches dissoc key)
       this)
     rf-disposable/IDisposable
     (-add-on-dispose [_ callback]
       (swap! on-dispose-fns conj callback))
     (-dispose [_]
       (when-not @disposed?
         (vreset! disposed? true)
         (let [callbacks @on-dispose-fns]
           (reset! on-dispose-fns [])
           (doseq [callback callbacks]
             (callback)))))
     PullWatchHostTest
     (fire-host-change! [this prev nu]
       (-notify-watches this prev nu))
     (host-last-read [_] @last-read)
     (host-read-count [_] @reads)))

(defn- make-pull-watch-host
  [source-containers compute-fn]
  #?(:clj  (PullWatchHost. source-containers compute-fn
                           (atom 0) (atom nil) (atom {}) (atom nil))
     :cljs (PullWatchHost. source-containers compute-fn
                           (atom 0) (atom nil) (atom {}) (atom []) (volatile! false))))

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
           handle        (obs/acquire! target (fn [_]))
           expected     (obs/read handle)
           reaction-var (ns-resolve 're-frame.substrate.observation
                                    'handle-reaction)
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
           #(is (= expected (obs/read handle))
                "a canonical JVM read cannot deref nil after the GC gap"))
         (is (= 1 @calls) "read resolves the weak reaction exactly once")
         (finally
           (obs/release! handle))))))

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
    (is (some #(= :rf.error/frame-destroyed (:error %)) records))
    ;; rf2-alk8a / rf2-chpoe — the observation port is subscribe-realm BY
    ;; CONSTRUCTION, so `throw-frame-destroyed!` stamps `:op :subscribe` on the
    ;; always-on record. A subscription's query vector is public IDENTITY, not
    ;; payload (rf2-zwgqe / Spec 015), so `error-emit`'s raw-identity
    ;; discriminator (keyed on that `:op :subscribe`) SKIPS elision — the query
    ;; vector egresses on `:event` RAW, VERBATIM, even under THIS unresolvable
    ;; frame (an identity slot never consults frame policy, so there is no
    ;; empty / missing policy to fail closed against). WHAT-STAYS
    ;; (rf2-t55hxg.18): a DISPATCH realm keeps its `:rf/redacted` fail-close —
    ;; the port emits NO dispatch realm, so that counter-pin lives in
    ;; on_error_cljs_test.cljc (`listener-fires-on-frame-destroyed-dispatch`)
    ;; and the ui frames reincarnation test; the `not=` below pins that this
    ;; subscribe record is never that sentinel.
    (let [r (first (filter #(= :rf.error/frame-destroyed (:error %)) records))]
      (is (= :subscribe (:op r))
          ":op :subscribe stamps the always-on subscribe-realm record")
      (is (= [:obs/items] (:event r))
          ":event egresses the query vector RAW (identity), never elided")
      (is (not= :rf/redacted (:event r))
          "the subscribe realm is NEVER the fail-closed dispatch sentinel"))))

;; ===========================================================================
;; acquire! / read / release! — the real-cache graft gate
;; ===========================================================================

(deftest acquire-read-release-drive-the-real-cache-ref-count
  (reg-items!)
  (seed-items! [:a])
  (let [target (items-target)
        notes  (atom [])
        handle  (obs/acquire! target (fn [n] (swap! notes conj n)))]
    (testing "acquire! built the REAL cache node and took one reference"
      (is (obs/handle? handle))
      (is (true? (obs/owned? handle)))
      (is (some? (entry [:obs/items])))
      (is (= 1 (ref-count [:obs/items])))
      (is (= #{:reaction :inputs :ref-count} (set (keys (entry [:obs/items]))))
          "the cache entry key-set stays EXACTLY the Spec 006 §Cache shape —
           port bookkeeping never rides inside the entry"))
    (testing "acquire!/release! never invoke on-change synchronously
              ([S2-CONFIRM] no-sync-fan-out)"
      (is (empty? @notes)))
    (testing "read returns value + version + current epochs"
      (let [r (obs/read handle)]
        (is (= [:a] (:value r)))
        (is (= 0 (:version r)))
        (is (int? (:frame-epoch r)))
        (is (int? (:registry-epoch r)))))
    (testing "current? holds for the unchanged live handle"
      (is (true? (obs/current? handle target))))
    (testing "a second acquire shares the SAME canonical node (ref 2)"
      (let [handle2 (obs/acquire! target (fn [_]))]
        (is (= 2 (ref-count [:obs/items])))
        (obs/release! handle2)
        (is (= 1 (ref-count [:obs/items])) "release detached exactly one ref")))
    (testing "release! on the last owner disposes the slot synchronously
              (the 1 → 0 edge, in-tick)"
      (obs/release! handle)
      (is (nil? (entry [:obs/items])) "cache slot evicted in-tick"))
    (testing "release! is idempotent — a second call no-ops"
      (obs/release! handle)
      (is (nil? (entry [:obs/items]))))
    (testing "no notification was ever fanned for this handle"
      (is (empty? @notes)))))

(deftest acquire-shares-with-public-subscribe-refs
  (reg-items!)
  (seed-items! [:a])
  (let [_        (subs/subscribe [:obs/items] {:frame fid})
        target   (items-target)
        handle    (obs/acquire! target (fn [_]))]
    (is (= 2 (ref-count [:obs/items]))
        "port handle + public subscribe share ONE node, two refs")
    (obs/release! handle)
    (is (= 1 (ref-count [:obs/items]))
        "the public subscriber's ref survives the handle release")
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
  (let [handle (obs/acquire! (items-target) (fn [_]))]
    (obs/release! handle)
    (let [[[outcome e] records] (with-error-records #(obs/read handle))]
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
;; NON-NIL but NEVER-CACHED, zero-ref recovery reaction. The port MUST NOT handle
;; it: a lying `owned?`-true handle is `current? false` from birth, so every
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
          (is (= :threw outcome) "acquire! did NOT return a (lying) handle")
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
  ;; acquired as canonical. The port now classifies it and throws typed.
  (let [race-fid :obs/race-frame]
    (rf/make-frame {:id race-fid :adapter plain-atom/adapter})
    (rf/reg-sub :obs/race
                (fn [_] (frame/destroy-frame! race-fid) [])  ;; kills the frame mid-materialize
                (fn [_v _] :unreachable))
    (let [target (obs/resolve-target {:frame race-fid :query-v [:obs/race]})
          [[outcome e] records] (with-error-records #(obs/acquire! target (fn [_])))]
      (is (= :threw outcome) "the mid-build destroy no longer slips through as a handle")
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
        handle  (obs/acquire! target (fn [_]))
        r0     (obs/read handle)]
    (is (= 0 (:version r0)))
    (seed-items! [:a :b])
    (let [r1 (obs/read handle)]
      (is (= [:a :b] (:value r1)))
      (is (= 1 (:version r1))
          "the node version advanced on observed rf=-movement")
      (is (> (:frame-epoch r1) (:frame-epoch r0))
          "the frame commit epoch advanced with the frame-state install"))
    (testing "an rf=-equal reinstall does NOT advance the node version"
      (seed-items! [:a :b])
      (is (= 1 (:version (obs/read handle)))))
    (obs/release! handle)))

;; ===========================================================================
;; rf2-vxgfnd.185 — the watchable change-watch fan-out obeys the SAME movement
;; law (`node-value=`, NaN-inclusive) that governs node-version advancement, so
;; the two cannot drift. A watchable host whose derived value recomputes NaN→NaN
;; fires its `add-watch` UNCONDITIONALLY (clojure/cljs atoms notify on every
;; reset!, value-blind), but NaN=NaN under the movement law: there is no value
;; movement, so the port must emit NO `:cause :subscription` notification. Raw `not=`
;; treated NaN≠NaN and spuriously fanned out — a value-movement notification
;; without value movement, dirtying a downstream ViewCell while the node version
;; (governed by the same `node-value=`) stayed put.
;;
;; The plain-atom adapter's derived values are NOT watchable, so the port's real
;; acquire path never installs this watch; the value-movement fan-out is a
;; reactive/watchable-host surface. This fixture wires the PRODUCTION
;; make-watch-handler onto a raw watchable atom EXACTLY as build-node-handle!
;; does (weak-ref'd reaction in the state + add-watch + baseline observe), so it
;; exercises the real callback on both hosts.
;; ===========================================================================

(defn- wire-node-watch!
  "Install a node handle's change watch onto watchable `host`, mirroring
  build-node-handle!'s wiring: the PRODUCTION make-watch-handler over the host, a
  weak-ref'd reaction in the state map, and a baseline observation seeding the
  node record at the host's current value. Notifications flow to `on-change`.
  Returns the handle `state` atom (its `:last` holds the observed version)."
  [host frame-id target on-change]
  (let [state (atom {:handle-kind :node
                     :target     target
                     :frame-id   frame-id
                     :query-v    (:query target)
                     :reaction   (#'obs/weak-reaction-ref host)
                     :on-change  on-change
                     :status     :live})]
    (add-watch host (gensym "rf-obs-handle")
               (#'obs/make-watch-handler state))
    (let [[rec v] (#'obs/observe-node! host)]
      (swap! state assoc :last {:value    v
                                :version  (:version rec)
                                :node-key (:node-key rec)}))
    state))

(deftest watchable-pull-host-delivers-callback-value-without-a-reread
  (reg-items!)
  (seed-items! [:a])
  (let [hosts (atom [])
        notes (atom [])]
    ;; Replace only the adapter's derived-value constructor. The observation
    ;; port still builds, acquires, watches, and releases the real cache node
    ;; through its public operations; the test host makes every pull observable.
    (with-redefs [substrate-adapter/make-derived-value
                  (fn [source-containers compute-fn]
                    (let [host (make-pull-watch-host source-containers compute-fn)]
                      (swap! hosts conj host)
                      host))]
      (let [target (items-target)
            handle  (obs/acquire! target (fn [event] (swap! notes conj event)))
            host   (first @hosts)]
        (try
          (is (= 1 (count @hosts))
              "the entry sub built one instrumented pull-derived cache node")
          (is (some? host))
          (let [baseline       (host-last-read host)
                reads-before   (host-read-count host)
                delivered-value {:pull :delivered :computed [:b]}]
            (is (pos? reads-before)
                "acquire! performed the expected baseline observation")
            (is (= [:a] (:computed baseline))
                "the instrumented host really pulls through the sub compute fn")
            ;; Fire the host's registered watch with a value that a fresh pull
            ;; can never produce (:pull is numeric on every deref). The port must
            ;; consume this callback-provided `nu`, not dereference the host.
            (fire-host-change! host baseline delivered-value)
            (is (= reads-before (host-read-count host))
                "value delivery performed ZERO additional observable reads")
            (is (= 1 (count @notes))
                "the public handle callback received exactly one movement")
            (is (= :subscription (:cause (first @notes))))
            (is (= target (:target (first @notes)))))
          (finally
            (obs/release! handle)))))))

(deftest watchable-nan-to-nan-recompute-does-not-fan-out-value-movement
  (let [target (items-target)
        notes  (atom [])
        host   (atom ##NaN)
        state  (wire-node-watch! host fid target
                                 (fn [n] (swap! notes conj n)))
        base-v (:version (:last @state))]
    (is (= 0 base-v) "the baseline observation minted the node at version 0")
    (testing "a NaN→NaN host recompute fires the watch but is NO movement"
      ;; clojure/cljs atoms notify watches on EVERY reset!, value-blind — so the
      ;; watch DOES fire with prev=NaN, nu=NaN, exercising the fan-out gate.
      (reset! host ##NaN)
      (is (empty? @notes)
          "no :cause :subscription notification for a NaN→NaN no-movement recompute
           (raw not= would spuriously fan out — NaN≠NaN natively)")
      (is (= base-v (:version (:last @state)))
          "the node version did not advance — NaN=NaN under the movement law"))
    (testing "a REAL value movement fans out exactly once + advances the version"
      (let [n-before (count @notes)]
        (reset! host 5)
        (is (= 1 (- (count @notes) n-before))
            "exactly one :cause :subscription notification for real movement")
        (let [note (last @notes)]
          (is (= :subscription (:cause note)))
          (is (= target (:target note)))
          (is (= (inc base-v) (:node-version note))
              "the notification carries the once-advanced node version")
          (is (= (inc base-v) (:version (:last @state)))
              "the handle's last-observed version advanced exactly once"))))))

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
        handle  (obs/acquire! target (fn [n] (swap! notes conj n)))
        old-entry (entry [:obs/items])]
    (is (= 1 (ref-count [:obs/items])))
    ;; The re-registration: cache invalidation disposes the canonical node,
    ;; then the port's replacement hook drains the queued former-owner
    ;; notifications — synchronously, at the boundary the re-registration
    ;; closes ([S2-CONFIRM] queue alignment).
    (reg-items!)
    (is (= 1 (count @notes))
        "exactly ONE coalesced notification per handle, delivered by the time
         reg-sub returned")
    (is (= :hmr (:cause (first @notes))))
    (is (= target (:target (first @notes))))
    (testing "current? treats the disposed node as not-current → retarget"
      (is (false? (obs/current? handle target))))
    (testing "the next acquire re-resolves the NEW canonical node"
      (let [handle2 (obs/acquire! target (fn [_]))]
        (is (not (identical? (:reaction old-entry)
                             (:reaction (entry [:obs/items]))))
            "the cache holds a fresh node, not the disposed one")
        (is (= 1 (ref-count [:obs/items])))
        (testing "release! on the stale handle is a no-op (identity-guarded) —
                  it can never decrement the NEW node's ref"
          (obs/release! handle)
          (is (= 1 (ref-count [:obs/items]))))
        (obs/release! handle2)
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
        _handle (obs/acquire! target (fn [n] (swap! notes conj n)))]
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
      (is (= :unrecognized (:kind-class (ex-data e)))
          "the throw carries the bounded kind-class (:unrecognized), never the
           raw :kind value (rf2-vxgfnd.241)")))
  (testing "acquire! on a malformed :kind throws the same typed error"
    (let [e (try (obs/acquire! {:kind :bogus :query [:obs/items]} (fn [_]))
                 (catch #?(:clj Throwable :cljs :default) e e))]
      (is (= :rf.error/observation-malformed-target (error-id e))))))

;; ===========================================================================
;; rf2-vxgfnd.183 — the CLOSED target + handle grammar at EVERY port boundary
;;
;; #5797 (rf2-vxgfnd.36) typed ONLY the unknown-`:kind` default arm. A
;; KNOWN-discriminator target with a malformed `:query`, an absent / wrong-domain
;; frame identity, or an INCOMPLETE `:story-override` still entered the accepted
;; arm and reached a host op — leaking an untyped `(first query)` / frame-registry
;; error the ViewCell cannot classify. Separately, `read` / `release!` deref the
;; handle state with no validation, so `(read nil)` / `(release! nil)` threw a raw
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
  ;; produced a nil-shaped observation / static handle (no throw at all). It could
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
  ;; :value nil probes to nil evidence and acquires a static handle — no throw.
  (reg-items!)
  (let [t {:kind :story-override :query [:obs/items]
           :value nil :override-id :ov :version 0}]
    (is (nil? (:value (obs/probe t))) "nil override value probes to nil, no throw")
    (let [handle (obs/acquire! t (fn [_]))]
      (is (false? (obs/owned? handle)) "the override handle owns nothing")
      (is (nil? (:value (obs/read handle))) "read yields the pinned nil value")
      (is (true? (obs/current? handle t)) "the override handle is current against its target")
      (obs/release! handle))))

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

(deftest read-and-release-reject-a-non-handle-typed
  ;; Repro 3: (read nil) / (release! nil) threw a raw NPE (JVM) / untyped host
  ;; error (CLJS) with (:rf.error/id (ex-data e)) == nil — the half-hardened
  ;; boundary. nil, a map, and any arbitrary host object must now throw the typed
  ;; :rf.error/observation-malformed-handle on both hosts.
  (doseq [bad [nil {} {:handle-kind :node} "handle" 42 [:not :a :handle]]]
    (testing (str "read on a non-handle " (pr-str bad))
      (is (= :rf.error/observation-malformed-handle
             (error-id (caught #(obs/read bad))))))
    (testing (str "release! on a non-handle " (pr-str bad))
      (is (= :rf.error/observation-malformed-handle
             (error-id (caught #(obs/release! bad))))))))

(deftest malformed-handle-throws-do-not-fan-the-always-on-axis
  ;; The malformed-handle category is DIAGNOSTIC — it must NOT fan the always-on
  ;; axis (Spec 009), unlike the sibling :rf.error/read-after-release.
  (let [[[outcome e] records] (with-error-records #(obs/read nil))]
    (is (= :threw outcome))
    (is (= :rf.error/observation-malformed-handle (error-id e)))
    (is (empty? records)
        "diagnostic malformed-handle does NOT fan the always-on axis")))

(deftest current?-on-a-non-handle-is-false-no-throw
  ;; current? is a pure no-throw kept-check predicate: a non-handle reads FALSE
  ;; rather than field-accessing handle-state and throwing (its ruled malformed-
  ;; value contract). Pre-fix `@(handle-state nil)` threw a raw NPE.
  (reg-items!)
  (let [target (items-target)]
    (doseq [bad [nil {} {:handle-kind :node} "handle" 42]]
      (is (false? (obs/current? bad target))
          (str "current? on a non-handle " (pr-str bad) " is false, never throws")))))

;; ===========================================================================
;; rf2-vxgfnd.241 — finish the CLOSED grammar at every REAL boundary
;;
;; #5847 typed the target/handle REJECTS but left four boundaries half-hardened:
;;   1. resolve-target inspected `(first query-v)` before validating the query
;;      and could MINT an empty/non-keyword target the downstream validator only
;;      later rejected (a scalar query-v even leaked a raw host `(first …)`).
;;   2. owned? was not covered by the handle validator and raw-errored on a
;;      non-handle.
;;   3. valid-target? built `(set (keys target))` on every hot probe/acquire —
;;      allocating + hashing every attacker-controllable key, letting a hostile
;;      key's hashing escape as an untyped error and scaling with extras.
;;   4. throw-malformed-target! serialized the raw `:kind` + the FULL key vector
;;      — a 10k-key map produced an unbounded message and structured/secret keys
;;      leaked.
;; These fixtures are RED-before-fix witnesses that each boundary now emits the
;; canonical closed grammar and rejects an open shape.
;; ===========================================================================

(defn- includes-substr?
  "Portable substring test (the test ns's clojure.string is CLJ-only)."
  [haystack needle]
  #?(:clj  (.contains (str haystack) (str needle))
     :cljs (not= -1 (.indexOf (str haystack) (str needle)))))

;; A key that is INSERTABLE into a small map (a PersistentArrayMap linear-scans
;; by equality, never hashing on insert) but whose HASHING throws — the exact
;; pre-fix escape `(set (keys target))` triggered. Identity-only equality keeps
;; array-map insertion from ever touching its hash.
#?(:cljs
   (deftype HostileHashKey []
     Object
     (toString [_] "<hostile-hash-key>")
     IHash
     (-hash [_] (throw (js/Error. "hostile -hash invoked")))
     IEquiv
     (-equiv [this o] (identical? this o))))

(defn- make-hostile-hash-key []
  #?(:clj  (reify Object
             (hashCode [_] (throw (ex-info "hostile hashCode invoked" {})))
             (equals [this o] (identical? this o))
             (toString [_] "<hostile-hash-key>"))
     :cljs (HostileHashKey.)))

;; ---- gap 1: resolve-target validates the query BEFORE sequence access -------

(deftest resolve-target-rejects-a-malformed-query-before-sequence-access
  ;; resolve-target is the port's ONLY resolution point. A malformed query-v
  ;; must throw the typed :rf.error/observation-malformed-target at resolve-target
  ;; — never mint an open target for the downstream gate, never `(first query-v)`
  ;; a scalar. Both hosts (this .cljc rides node CLJS + JVM).
  (reg-items!)
  (doseq [[label q] [[:non-vector  42]
                     [:nil         nil]
                     [:empty       []]
                     [:non-kw-head [42]]
                     [:string      "not-a-query"]
                     [:list        '(:obs/items)]]]
    (testing (str "explicit-pin resolve-target on a malformed :query-v ("
                  (name label) ") rejects here, never mints an open target")
      (is (= :rf.error/observation-malformed-target
             (error-id (caught #(obs/resolve-target {:frame fid :query-v q}))))))
    (testing (str "ambient resolve-target on a malformed :query-v (" (name label)
                  ") rejects BEFORE require-current-frame! reads (first query-v)")
      (is (= :rf.error/observation-malformed-target
             (error-id (caught #(obs/resolve-target {:query-v q}))))))
    (testing (str "override resolve-target on a malformed :query-v ("
                  (name label) ") rejects before minting a :story-override")
      (is (= :rf.error/observation-malformed-target
             (error-id (caught #(obs/resolve-target
                                  {:query-v q
                                   :override {:value 1 :override-id :o :version 0}}))))))))

(deftest resolve-target-malformed-query-does-not-fan-the-always-on-axis
  ;; The malformed-query rejection is DIAGNOSTIC (a substrate/consumer bug,
  ;; unreachable in correct generated code) — like malformed-target it must NOT
  ;; fan the always-on axis, and its evidence is bounded (query-class, never the
  ;; query contents, which for a valid query can carry app values).
  (reg-items!)
  (let [[[outcome e] records]
        (with-error-records #(obs/resolve-target {:frame fid :query-v [42]}))]
    (is (= :threw outcome))
    (is (= :rf.error/observation-malformed-target (error-id e)))
    (is (= :non-keyword-head (:query-class (ex-data e)))
        "bounded query-class evidence — not the raw query")
    (is (empty? records)
        "diagnostic malformed-query does NOT fan the always-on axis")))

;; ---- gap 2: owned? is total (false/no-throw for a non-handle) -----------------

(deftest owned?-on-a-non-handle-is-false-no-throw
  ;; owned? was not covered by the handle validator and raw-errored on a
  ;; non-handle. Its ruled total contract mirrors current?: a non-handle is simply
  ;; "owns nothing" → false, never a raw host throw. Both hosts.
  (doseq [bad [nil {} {:handle-kind :node} "handle" 42 [:not :a :handle]]]
    (is (false? (obs/owned? bad))
        (str "owned? on a non-handle " (pr-str bad) " is false, never throws"))))

;; ---- gap 3 + 4: fixed-vocabulary validation + bounded, leak-free evidence ----

(deftest exact-key-validation-rejects-extra-keys-typed-with-bounded-evidence
  ;; valid-target? now checks the key-set by fixed vocabulary (count + contains?
  ;; of the port's OWN keys) — an EXTRA key fails the count without a set being
  ;; built. The rejection evidence is bounded + normalized.
  (reg-items!)
  (let [t {:kind :subscription :frame-id fid :query [:obs/items] :surplus true}]
    (testing "an extra key is rejected typed on both target-taking ops"
      (is (= :rf.error/observation-malformed-target (error-id (caught #(obs/probe t)))))
      (is (= :rf.error/observation-malformed-target
             (error-id (caught #(obs/acquire! t (fn [_])))))))
    (testing "evidence is BOUNDED + normalized — kind-class, key-count, known-key
              presence; never the raw key vector"
      (let [d (ex-data (caught #(obs/probe t)))]
        (is (= :subscription (:kind-class d)) "kind-class is the recognized kind")
        (is (= 4 (:key-count d)) "total key count is reported")
        (is (= #{:kind :frame-id :query} (:known-keys-present d))
            "only the port's OWN known keys are named — the :surplus extra is absent")
        (is (nil? (:target-keys d)) "the raw key vector is GONE from evidence")))))

(deftest malformed-target-evidence-never-leaks-secret-keys-or-values
  ;; A target carrying a secret KEY and secret VALUE must reject with the secret
  ;; nowhere in the message or the ex-data (rf2-vxgfnd.241 gap 4).
  (reg-items!)
  (let [secret-key   :app.secret/api-token
        secret-value "sk-live-DEADBEEF-do-not-log"
        t            {:kind :subscription :frame-id fid :query [:obs/items]
                      secret-key secret-value}
        e            (caught #(obs/probe t))
        d            (ex-data e)
        msg          (ex-message e)]
    (is (= :rf.error/observation-malformed-target (error-id e)))
    (is (= :subscription (:kind-class d)))
    (is (= 4 (:key-count d)))
    (is (= #{:kind :frame-id :query} (:known-keys-present d))
        "the secret extra key is NOT named in evidence")
    (is (not (includes-substr? msg (name secret-key)))
        "the secret KEY does not appear in the human message")
    (is (not (includes-substr? msg secret-value))
        "the secret VALUE does not appear in the human message")
    (is (not (includes-substr? (pr-str d) secret-value))
        "the secret VALUE does not appear anywhere in the ex-data")
    (is (not (includes-substr? (pr-str d) (name secret-key)))
        "the secret KEY does not appear anywhere in the ex-data")))

(deftest ten-thousand-extra-keys-reject-typed-with-bounded-evidence
  ;; A 10k-key target rejects by count alone — no set allocated, no key
  ;; enumerated into evidence, message bounded (rf2-vxgfnd.241 gap 3 + 4).
  (reg-items!)
  (let [t   (into {:kind :subscription :frame-id fid :query [:obs/items]}
                  (map (fn [i] [(keyword (str "extra" i)) i]))
                  (range 10000))
        e   (caught #(obs/probe t))
        d   (ex-data e)
        msg (ex-message e)]
    (is (= :rf.error/observation-malformed-target (error-id e)))
    (is (= :subscription (:kind-class d)))
    (is (= 10003 (:key-count d)) "the total count is reported, not the keys")
    (is (= #{:kind :frame-id :query} (:known-keys-present d)))
    (is (nil? (:target-keys d)) "the 10k-key vector is GONE from evidence")
    (is (< (count msg) 1200) "the message is bounded — no 10k keys serialized")
    (is (not (includes-substr? (pr-str d) "extra5000"))
        "no attacker key leaks into the ex-data")))

(deftest hostile-hash-extra-key-cannot-escape-as-a-raw-error
  ;; The (set (keys target)) path HASHED every key; a small map can carry an
  ;; extra key whose hashing THROWS (array-map insert never hashes). Pre-fix the
  ;; untyped host error ESCAPED on the JVM; the fixed-vocabulary count+contains?
  ;; check never hashes the attacker's extra key, so the port rejects TYPED
  ;; (rf2-vxgfnd.241 gap 3). The typed-reject property holds on BOTH hosts; the
  ;; JVM additionally witnesses the exact pre-fix escape (a JVM WeakHashMap-style
  ;; set-build invokes the throwing hashCode where CLJS's set tolerated it).
  (reg-items!)
  (let [hostile (make-hostile-hash-key)
        t       (assoc {:kind :subscription :frame-id fid :query [:obs/items]}
                       hostile true)]
    #?(:clj
       (testing "the pre-fix JVM escape vector is real — hashing the target's
                 keys into a set invokes the hostile hashCode and throws untyped"
         (is (thrown? clojure.lang.ExceptionInfo (set (keys t)))
             "building a key-set over the target DOES invoke the hostile hashCode")))
    (testing "the port rejects TYPED — count+contains? never hashes the extra key"
      (is (= :rf.error/observation-malformed-target (error-id (caught #(obs/probe t)))))
      (is (= :rf.error/observation-malformed-target
             (error-id (caught #(obs/acquire! t (fn [_])))))))))

;; ===========================================================================
;; rf2-vxgfnd.32 — first-owner disposal-hook install races node disposal
;;
;; PR #5710 enrols the first active owner (marking the node record :hooked?)
;; and THEN, as a SEPARATE step, installs the one node-scoped disposal hook via
;; interop/add-on-dispose!. A disposal that linearizes in that gap fires no hook
;; (the callback lands on an already-disposed reaction and is silently lost —
;; every substrate's -dispose snapshot-and-clears its callbacks first; the JVM
;; Reaction's field is even unsynchronized). Pre-fix the acquired handle is left
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
;; (an async executor on the JVM; an unfired-mid-run next-turn task on CLJS-node).
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
      (let [handle (obs/acquire! target (fn [n] (swap! notes conj n)))]
        (is (true? @raced?) "the race fired at the first-owner hook install")
        (is (nil? (entry [:obs/items]))
            "the racing disposal evicted the canonical node during the gap")
        (testing "acquire! never invokes on-change synchronously — the
                  handshake self-drain only ENQUEUES ([S2-CONFIRM]
                  no-sync-fan-out)"
          (is (empty? @notes) "no notification fired on the acquire stack"))
        (testing "the handle is NOT current — the node was disposed under it"
          (is (false? (obs/current? handle target))))
        ;; Drive the queued drain boundary deterministically.
        (obs/drain-pending-disposals! :disposed)
        (testing "the invalidation is STILL delivered — never silently lost to
                  a dead first-owner hook (rf2-vxgfnd.32)"
          (is (= 1 (count @notes))
              "the raced first owner received exactly one disposal notification")
          (is (= :disposed (:cause (first @notes)))))
        (testing "release! on the raced handle is a clean, identity-guarded no-op"
          (obs/release! handle)
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
        handle  (obs/acquire! target (fn [n] (swap! notes conj n)))]
    (testing "canonical acquire took one ref and enqueued nothing"
      (is (= 1 (ref-count [:obs/items])))
      (is (empty? @notes))
      (is (true? (obs/current? handle target))))
    (testing "a real HMR disposal delivers via the installed hook exactly once"
      (reg-items!)
      (is (= 1 (count @notes)))
      (is (= :hmr (:cause (first @notes))))
      (obs/release! handle))))

;; ===========================================================================
;; rf2-r8jmdb / rf2-x76af2.34 FINDING 1 — the disposal cause is INTRINSIC to
;; why the node died, not decided by which drain boundary fires.
;;
;; The port queues former-owner notifications; pre-fix the queue stored bare
;; handles and the drain boundary STAMPED the cause (the registrar HMR hook
;; drained the whole queue :hmr; the next-tick fallback drained it :disposed).
;; So a frame-destroy / cache-clear handle STILL PENDING when an unrelated :sub
;; HMR re-registration drained was swept into the :hmr drain and delivered
;; {:cause :hmr} — a documented on-change payload contract violation (a consumer
;; branching :hmr = re-acquire vs :disposed = gone would re-acquire against a
;; destroyed frame → :rf.error/frame-destroyed → view error boundary; the #5752
;; CI symptom: evidence-target saw #{:disposed :hmr}). The fix stores each entry
;; as a [handle cause] pair whose cause is INTRINSIC (captured at enqueue time
;; from the disposing cache site's *disposal-cause*): the :hmr drain takes only
;; :hmr-tagged entries, leaving the :disposed cache-clear handle for the next-tick
;; fallback, which delivers it its OWN :disposed cause.
;; ===========================================================================

(deftest disposed-cause-handle-pending-during-hmr-drain-keeps-its-disposed-cause
  (reg-items!)
  (seed-items! [:a])
  (let [target (items-target)
        notes  (atom [])]
    ;; Swallow next-tick so the :disposed fallback does not auto-drain — we drive
    ;; the boundaries deterministically, identical on both hosts (no sleeps).
    (with-redefs [interop/next-tick (fn [_f] nil)]
      (let [handle (obs/acquire! target (fn [n] (swap! notes conj n)))]
        (is (= 1 (ref-count [:obs/items])))
        ;; A CACHE-CLEAR disposal (intrinsic cause :cache-clear → :disposed)
        ;; enqueues the handle; next-tick is swallowed so it stays PENDING. The
        ;; frame stays live throughout — this is NOT a destruction.
        (subs-cache/clear-sub-cache! fid)
        (is (nil? (entry [:obs/items])) "cache-clear evicted + disposed the node")
        (is (empty? @notes) "the disposal only ENQUEUED — no synchronous fan-out")
        ;; An UNRELATED :sub HMR re-registration fires the :hmr drain boundary
        ;; while the :disposed handle is still pending. It MUST NOT sweep it.
        (reg-items!)
        (is (empty? @notes)
            "the :hmr drain took only :hmr-tagged entries — the :disposed
             cache-clear handle was LEFT pending, never mislabelled :hmr")
        ;; Drive the next-tick :disposed fallback deterministically: the handle
        ;; receives its OWN intrinsic cause.
        (obs/drain-pending-disposals! :disposed)
        (is (= 1 (count @notes)) "the cache-clear handle was notified exactly once")
        (is (= :disposed (:cause (first @notes)))
            "it received {:cause :disposed} — its INTRINSIC cause — NEVER :hmr
             (pre-fix the co-pending :hmr drain delivered :hmr here)")
        (is (= target (:target (first @notes))))
        (obs/release! handle)))))

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

(deftest disposed-drain-coalesces-multiple-nodes-and-owners-one-thunk-per-window
  (reg-items!)
  (rf/reg-sub :obs/item-count (fn [db _] (count (:items db))))
  (seed-items! [:a :b])
  (let [pending    @#'obs/pending-disposals
        scheduled? @#'obs/disposal-drain-scheduled?]
    ;; Hermetic start only: scheduling and delivery assertions below stay at the
    ;; public port + captured-host boundary rather than reading CAS internals.
    (reset! pending [])
    (reset! scheduled? false)
    (let [items-target (items-target)
          count-target (obs/resolve-target {:frame fid :query-v [:obs/item-count]})
          items-notes  (atom [])
          count-notes  (atom [])
          captured     (atom [])
          l1           (obs/acquire! items-target
                                     (fn [event] (swap! items-notes conj event)))
          l2           (obs/acquire! items-target
                                     (fn [event] (swap! items-notes conj event)))
          l3           (obs/acquire! count-target
                                     (fn [event] (swap! count-notes conj event)))]
      (with-redefs [interop/next-tick (fn [thunk] (swap! captured conj thunk))]
        ;; Window 1: two independently disposed nodes, one with two owners.
        (subs-cache/clear-sub-cache! fid)
        (testing "one coalescing window owns exactly one scheduled drain"
          (is (= 1 (count @captured))
              "three independent handle enqueues across two nodes schedule ONE thunk")
          (is (empty? @items-notes))
          (is (empty? @count-notes)
              "disposal only enqueues; callbacks do not run on the cache-clear stack"))
        ((first @captured))
        (testing "the one scheduled thunk drains every queued owner exactly once"
          (is (= 2 (count @items-notes)) "both owners of the shared node delivered")
          (is (= 1 (count @count-notes)) "the independently cached node delivered")
          (is (every? #(= :disposed (:cause %))
                      (concat @items-notes @count-notes))))
        (doseq [handle [l1 l2 l3]] (obs/release! handle))

        ;; Window 2: after the first scheduled thunk returned, a freshly rebuilt
        ;; node must own exactly one NEW thunk. Destroying the frame retains the
        ;; original end-to-end epoch-zero assertion.
        (reset! captured [])
        (let [later-notes (atom [])
              later-handle (obs/acquire! items-target
                                        (fn [event] (swap! later-notes conj event)))]
          (frame/destroy-frame! fid)
          (is (= 1 (count @captured))
              "a later independent window schedules exactly one new thunk")
          (is (empty? @later-notes) "the later destroy is still queued")
          ((first @captured))
          (is (= 1 (count @later-notes)) "the later window drains exactly once")
          (let [event (first @later-notes)]
            (is (= :disposed (:cause event)))
            (is (= items-target (:target event)))
            (is (zero? (:frame-epoch event))
                "the real frame-destroy delivery retains epoch-zero evidence"))
          (obs/release! later-handle))))))

(deftest scheduled-disposal-drain-recovers-its-latch-after-callback-failure
  (reg-items!)
  (seed-items! [:a])
  (let [pending    @#'obs/pending-disposals
        scheduled? @#'obs/disposal-drain-scheduled?
        captured   (atom [])
        target     (items-target)
        healthy    (atom [])
        boom       (ex-info "scheduled on-change boom" {::scheduled-boom true})]
    (reset! pending [])
    (reset! scheduled? false)
    (with-redefs [interop/next-tick (fn [thunk] (swap! captured conj thunk))]
      (let [bad-handle  (obs/acquire! target (fn [_event] (throw boom)))
            good-handle (obs/acquire! target (fn [event] (swap! healthy conj event)))]
        (force-dispose-node! [:obs/items])
        (is (= 1 (count @captured)) "the failing window owns one real scheduled thunk")
        (let [[[outcome thrown] records]
              (with-error-records #((first @captured)))
              wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                               records)]
          (is (= :threw outcome) "the manually driven next-tick boundary sees the escape")
          (is (identical? boom thrown) "the drain rethrows the original callback failure")
          (is (= 1 (count @healthy))
              "per-owner containment delivers the healthy sibling exactly once")
          (is (= :disposed (:cause (first @healthy))))
          (is (= 1 (count wrapped))
              "the swallowed-in-production boundary failure is always-on visible once")
          (is (identical? boom (:exception (first wrapped)))))
        (testing "the scheduled thunk recovers all drain state even though delivery threw"
          (is (empty? @pending) "the failing window still drained the complete queue")
          (is (false? @scheduled?)
              "the scheduling latch was reset before the potentially throwing drain"))
        (obs/release! bad-handle)
        (obs/release! good-handle)

        (testing "later work owns a fresh scheduling window and drains normally"
          (reset! captured [])
          (let [later-notes (atom [])
                later-handle (obs/acquire! target
                                          (fn [event] (swap! later-notes conj event)))]
            (force-dispose-node! [:obs/items])
            (is (= 1 (count @captured))
                "callback failure did not strand the latch or suppress later scheduling")
            (when-let [thunk (first @captured)]
              (thunk)
              (is (= 1 (count @later-notes)))
              (is (= :disposed (:cause (first @later-notes))))
              (is (empty? @pending))
              (is (false? @scheduled?)))
            (obs/release! later-handle)))))))

;; ===========================================================================
;; rf2-vxgfnd.70 — a follower must not publish a handle behind the FIRST owner's
;; still-installing hook.
;;
;; PR #5737's handshake flips the node record's readiness flag as the handle
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
             (is (some? @l1) "the first owner completed and returned its handle")
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
        (let [handle (obs/acquire! target (fn [_]))]
          (is (obs/handle? handle))
          (is (true? (obs/owned? handle)))
          (is (= 1 (ref-count [:obs/items])) "exactly one reference on the rebuilt node")
          (is (= [:a] (:value (obs/read handle))))
          (is (true? (obs/current? handle target)))
          (obs/release! handle)
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
;; each handle's notify in its own try/catch (siblings never starve), surfaces the
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
        bad-handle (atom nil)
        ;; Owner A: its on-change does a FORBIDDEN reentrant release! from inside
        ;; the fan-out → throws the dev :rf.error/reentrant-graph-op assert,
        ;; escaping the notification (it does NOT catch its own throw).
        la  (obs/acquire! target (fn [_n] (obs/release! @bad-handle)))
        ;; Owner B: a well-behaved sibling that MUST still be notified.
        lb  (obs/acquire! target (fn [n] (swap! notes-b conj n)))]
    (reset! bad-handle la)
    (is (= 2 (obs/active-owner-count (:reaction (entry [:obs/items]))))
        "both owners are enrolled on the shared node")
    ;; The HMR re-registration disposes the shared node and drains BOTH former
    ;; owners at the registrar replacement boundary — the exact swallow-prone
    ;; path. reg-items! itself returns normally: the registrar isolates the
    ;; replacement hook, so the drain's rethrow is swallowed there and it is the
    ;; ALWAYS-ON fan that carries visibility.
    (let [[[outcome _] records] (with-error-records #(reg-items!))]
      ;; ALWAYS-ON (rf2-d2841): containment. Whether or not owner A's callback
      ;; throws, it must not starve its sibling — that is the drain's real
      ;; contract and it holds in both postures.
      (testing "the throwing owner did NOT starve its sibling — B notified once"
        (is (= 1 (count @notes-b)))
        (is (= :hmr (:cause (first @notes-b)))))
      ;; The ESCAPE ITSELF is manufactured by the dev reentrancy assert
      ;; (`assert-not-in-fan-out!` is `(when interop/debug-enabled? …)`), so
      ;; under the gate owner A's reentrant release! simply succeeds and there
      ;; is no callback failure to wrap. VACUOUS PASS REMOVED (class 1): the
      ;; "not promoted onto the always-on axis" negative passed over a record
      ;; stream that carried no records at all.
      (when interop/debug-enabled?
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
              "the diagnostic category is NOT promoted onto the always-on axis")))
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
;; #5766 (rf2-vxgfnd.28) contained a throwing owner per-handle and re-surfaced a
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
       ;; rf2-7w1im: `compute-and-cache!` is now 2/3-arity — the captured-
       ;; incarnation token rides the optional 3rd arg. Mirror BOTH fixed arities
       ;; (the acquire path calls the 2-arity via static dispatch; the internal
       ;; token-carrying self-call / recursive input calls the 3-arity). The
       ;; displacer acts ONLY on the 2-arity ENTRY; the 3-arity is a pure
       ;; pass-through, so it never re-triggers.
       (fn
         ([frame-id query-v]
          (let [reaction (real-cc frame-id query-v nil)]
            ;; Displace the just-built canonical node ONCE, in the
            ;; build→canonical-check window, with the frame left LIVE.
            (when (and (= query-v [:obs/items])
                       (compare-and-set! raced? false true))
              (displace-node! [:obs/items] reaction))
            reaction))
         ([frame-id query-v expected-incarnation]
          (real-cc frame-id query-v expected-incarnation)))]
      (let [[[outcome handle] records] (with-error-records #(obs/acquire! target (fn [_])))]
        (is (true? @raced?) "the displacement fired in the build→check window")
        (testing "acquire! did NOT throw or fan a false frame-destroyed while the frame is live"
          (is (= :ok outcome) "acquire! returned a handle, not a throw")
          (is (obs/handle? handle))
          (is (empty? (filter #(= :rf.error/frame-destroyed (:error %)) records))
              "no false always-on frame-destroyed record was fanned")
          (is (some? (frame/frame fid)) "the frame remained live throughout"))
        (testing "it converged on the CURRENT canonical node — an owned, current handle"
          (is (obs/owned? handle) "the retarget adopted a real cache node")
          (is (true? (obs/current? handle target))
              "the handle covers the live canonical node")
          (is (= 1 (ref-count [:obs/items])) "exactly one reference on the current node")
          (is (= [:a] (:value (obs/read handle)))
              "reads the live value through the adopted canonical node"))
        (testing "no leak — release drops the last ref and disposes the current node"
          (obs/release! handle)
          (is (nil? (entry [:obs/items]))))))))

(deftest acquire-repeated-live-displacement-is-bounded-and-converges
  ;; Repeated displacement (explicit-cache-clear style: evict only) on the first
  ;; K attempts, then quiescence. The bounded retry converges on a canonical
  ;; current handle at attempt K+1 — it does not spin, and never fans a false
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
       ;; rf2-7w1im: 2/3-arity shim (see acquire-live-cache-displacement) — act
       ;; only on the 2-arity entry; the 3-arity is a pass-through.
       (fn
         ([frame-id query-v]
          (let [reaction (real-cc frame-id query-v nil)]
            (when (= query-v [:obs/items])
              ;; Evict the first K builds in-window; the (K+1)th settles.
              (when (<= (swap! builds inc) k)
                (evict-node! [:obs/items])))
            reaction))
         ([frame-id query-v expected-incarnation]
          (real-cc frame-id query-v expected-incarnation)))]
      (let [[[outcome handle] records] (with-error-records #(obs/acquire! target (fn [_])))]
        (is (= (inc k) @builds) "converged on the very next build after K displacements")
        (is (= :ok outcome) "acquire! converged on a handle — it did not spin or throw")
        (is (empty? (filter #(= :rf.error/frame-destroyed (:error %)) records))
            "no false frame-destroyed under repeated live displacement")
        (is (true? (obs/current? handle target)))
        (is (= 1 (ref-count [:obs/items])))
        (obs/release! handle)
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
         ;; rf2-7w1im: 2/3-arity shim (see acquire-live-cache-displacement) — act
         ;; only on the 2-arity entry; the 3-arity is a pass-through.
         (fn
           ([frame-id query-v]
            (let [reaction (real-cc frame-id query-v nil)]
              (when (and (= query-v [:obs/items63])
                         (compare-and-set! raced? false true))
                ;; Destroy the targeted incarnation in the window — token → nil.
                (frame/destroy-frame! race-fid))
              reaction))
           ([frame-id query-v expected-incarnation]
            (real-cc frame-id query-v expected-incarnation)))]
        (let [[[outcome e] records] (with-error-records #(obs/acquire! target (fn [_])))]
          (is (true? @raced?) "the destruction fired in the build→check window")
          (is (= :threw outcome) "a real teardown still throws — it is not retargeted")
          (is (= :rf.error/frame-destroyed (error-id e)))
          (is (= 1 (count (filter #(= :rf.error/frame-destroyed (:error %)) records)))
              "exactly one always-on frame-destroyed record was fanned")
          (is (nil? (frame/frame race-fid))
              "the targeted incarnation is gone — nothing was acquired"))))))

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
       ;; rf2-7w1im: 2/3-arity shim (see acquire-live-cache-displacement) — act
       ;; only on the 2-arity entry; the 3-arity is a pass-through.
       (fn
         ([frame-id query-v]
          (let [reaction (real-cc frame-id query-v nil)]
            (when (= query-v [:obs/items])
              ;; Displace EVERY build in-window, leaving the frame (and its
              ;; incarnation token) LIVE — the storm never settles, so the bounded
              ;; retry exhausts its budget with the incarnation demonstrably alive.
              (swap! builds inc)
              (evict-node! [:obs/items]))
            reaction))
         ([frame-id query-v expected-incarnation]
          (real-cc frame-id query-v expected-incarnation)))]
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
            handle        (obs/acquire! target (fn [_]))
            r            (obs/read handle)]
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
        (obs/release! handle)))
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
        handle     (obs/acquire! target (fn [_]))
        r         (obs/read handle)]
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
    (obs/release! handle)))

(deftest reentrant-graph-op-is-dev-asserted-inside-the-fan-out
  (reg-items!)
  (seed-items! [:a])
  (let [caught (atom nil)
        handle-ref (atom nil)
        handle  (obs/acquire! (items-target)
                             (fn [_n]
                               ;; graph mutation from inside the fan-out —
                               ;; must throw :rf.error/reentrant-graph-op
                               (try (obs/release! @handle-ref)
                                    (catch #?(:clj Throwable :cljs :default) e
                                      (reset! caught (error-id e))))))]
    (reset! handle-ref handle)
    ;; drive a fan-out via the HMR path
    (reg-items!)
    ;; DEV-ASSERTED BY NAME AND BY SOURCE (rf2-d2841):
    ;; `observation/assert-not-in-fan-out!` is wrapped in
    ;; `(when interop/debug-enabled? …)`, so under the gate the reentrant
    ;; release! succeeds and nothing is caught. The deftest's own name states
    ;; the posture.
    (when interop/debug-enabled?
      (is (= :rf.error/reentrant-graph-op @caught)))
    (testing "the reentrant release was rejected — the handle is still live
              and releasable outside the fan-out"
      (obs/release! handle))))

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
  ;; Stage-acquire two handles in order (leaf then solo), then unwind in
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
;; static override handle
;; ===========================================================================

(deftest static-override-handle-honest-ownership-uniform-commit-path
  (let [target {:kind :story-override :query [:obs/items]
                :value 99 :override-id :o1 :version 7}
        handle  (obs/acquire! target (fn [_] (throw (ex-info "never" {}))))]
    (testing "no callback is registered and ownership is reported honestly"
      (is (obs/handle? handle))
      (is (false? (obs/owned? handle))))
    (testing "read yields the pinned value + override version"
      (is (= {:value 99 :version 7} (obs/read handle))))
    (testing "current? holds while the site's override id/version match"
      (is (true? (obs/current? handle target)))
      (is (false? (obs/current? handle (assoc target :version 8)))
          "a moved override version retargets through the normal staged path")
      (is (false? (obs/current? handle {:kind :subscription :frame-id fid
                                       :query [:obs/items]}))
          "a kind flip (override removed) retargets"))
    (testing "release! is a no-op; read still serves the pinned value"
      (obs/release! handle)
      (is (= {:value 99 :version 7} (obs/read handle)))))
  (testing "static version currency follows the complete frozen rf= law"
    (let [target {:kind :story-override :query [:obs/items]
                  :value ##NaN :override-id :o1 :version ##NaN}
          handle  (obs/acquire! target (fn [_] (throw (ex-info "never" {}))))]
      (is (true? (obs/current? handle (assoc target
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
;; current? is TOTAL across a throwing opaque-token equality (rf2-sbfqy)
;; ===========================================================================
;;
;; A :story-override handle's :override-id / :version are OPAQUE app-supplied
;; tokens — :override-id compared by plain `=`, :version by the core-local
;; `node-value=` `rf=` spelling (which calls `=`). Their HOST equality is app
;; code and MAY THROW. current? is the commit kept-check and is documented TOTAL
;; and never-throw (Spec 006 §Error contract; Ownership; its docstring): a
;; comparison that cannot ESTABLISH sameness classifies the site as NOT current,
;; so the throw never escapes the predicate — the site retargets through the
;; normal staged commit path instead. `BoomEq` throws from its equality on BOTH
;; hosts (JVM `Object.equals`; CLJS `-equiv`), so the same adversarial fixture
;; runs under `clojure -M:test` AND `npm run test:cljs`.

(deftype BoomEq []
  #?@(:clj  [Object
             (equals [_ _] (throw (ex-info "boom-from-equals" {})))]
      :cljs [IEquiv
             (-equiv [_ _] (throw (ex-info "boom-from-equals" {})))]))

(deftest current?-is-total-across-a-throwing-opaque-token-equality
  ;; The bead's exact repro shape: a SUPPORTED :story-override target whose
  ;; :version opaque token throws through host equality. It passes the port
  ;; grammar and acquisition (validate-target! checks only key PRESENCE + the
  ;; query shape, never a token's equality — acquire! stores the target as-is),
  ;; so pre-fix the exception escaped LATER through current?'s node-value=
  ;; version compare.
  (testing "acquisition of a throwing-token override target succeeds"
    (let [target {:kind :story-override :query [:obs/items]
                  :value nil :override-id :slot :version (->BoomEq)}
          handle  (obs/acquire! target (fn [_] (throw (ex-info "never" {}))))]
      (is (obs/handle? handle))
      (testing "current? returns false, never throws, when the VERSION compare throws"
        (is (false? (obs/current? handle (assoc target :version (->BoomEq))))
            "a throwing opaque-version equality → conservatively NOT current"))))
  (testing "current? is total for a throwing OVERRIDE-ID equality too"
    ;; :override-id is compared by plain `=` before the version compare; a
    ;; throwing id token is likewise caught and read as not-current.
    (let [target {:kind :story-override :query [:obs/items]
                  :value nil :override-id (->BoomEq) :version 1}
          handle  (obs/acquire! target (fn [_] (throw (ex-info "never" {}))))]
      (is (false? (obs/current? handle (assoc target :override-id (->BoomEq)))))))
  (testing "the guard does NOT blanket-swallow — the SAME opaque instance still reads current"
    ;; node-value='s `identical?` arm short-circuits before `=` is ever called,
    ;; so a version token IDENTICAL in both the handle and the compared target
    ;; keeps the well-formed identity path unchanged and the site stays current.
    (let [tok    (->BoomEq)
          target {:kind :story-override :query [:obs/items]
                  :value nil :override-id :slot :version tok}
          handle  (obs/acquire! target (fn [_] (throw (ex-info "never" {}))))]
      (is (true? (obs/current? handle target))
          "an identical opaque version token retains without invoking `=`"))))

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
        watch-installs        (atom 0)
        real-add-watch        #?(:clj  clojure.core/add-watch
                                 :cljs cljs.core/add-watch)
        dispose-traces        (atom 0)]
    (rf/register-listener! :trace ::dispose-watch
      (fn [ev] (when (= :rf.sub/dispose (:operation ev))
                 (swap! dispose-traces inc))))
    (try
      ;; Instrument the HOST registration seam for the whole probe loop. Final
      ;; cardinality alone cannot see add-then-remove churn; this counter does.
      (with-redefs [#?(:clj  clojure.core/add-watch
                       :cljs cljs.core/add-watch)
                    (fn [reference key callback]
                      (swap! watch-installs inc)
                      (real-add-watch reference key callback))]
        ;; alternate shared-memo and memo-less probes; both must retain zero
        (let [memo (obs/make-slice-memo)]
          (dotimes [i 10000]
            (let [ev (obs/probe (obs/resolve-target {:frame fid :query-v [:obs/sum]})
                                (when (even? i) memo))]
              (when (zero? i)
                (is (= [:sum 3] (:value ev))))))))
      (testing "no cache entries, no disposal obligations, no node records"
        (is (= cache-count-before (count @(sub-cache)))
            "10k cold probes created ZERO cache entries")
        (is (nil? (entry [:obs/sum])))
        (is (nil? (entry [:obs/leaf2])))
        (is (zero? @dispose-traces)
            "no disposal obligations were created (nothing disposed)")
        (is (zero? @watch-installs)
            "10k cold probes attempted ZERO transient watch registrations")
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
;; the released-handle retention fixture — rf2-vxgfnd.15
;;
;; Adversarial to the exact leak the 10k-cold-probe fixture CANNOT catch (cold
;; probes take no handle, register no callback). A permanent owner keeps a
;; shared layer-1 node live while N handles acquire/release against the SAME
;; target — the app-shell-subscription-stays-live-for-the-process shape. Every
;; released handle must retain ZERO disposal callbacks and leave the node's
;; active-owner set at the permanent baseline: disposal work O(current owners),
;; never O(all owners ever acquired). On today's per-handle-hook code the
;; reaction retains one dormant closure per released handle (1000 leaked) and
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

(deftest released-handles-retain-no-disposal-callbacks-on-a-shared-live-node
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
    (testing "released handles left the active-owner set at the permanent
              baseline — not one historical owner retained"
      (is (= 1 (obs/active-owner-count reaction))
          "active owners = {permanent}, not {permanent + N released}"))
    #?(:clj
       (testing "disposal-callback STORAGE stayed O(1) — released handles
                 retained ZERO dormant closures (the rf2-vxgfnd.15 leak)"
         (is (= callbacks-baseline (reaction-dispose-callback-count reaction))
             (str "the reaction retained a dormant disposal closure per "
                  "released handle: baseline " callbacks-baseline
                  ", after " n " acquire/release pairs "
                  (reaction-dispose-callback-count reaction)
                  " (retained-released-handle-callbacks="
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
;; rf2-x76af2.34 FINDING 2 — a released node handle drops its reaction +
;; on-change refs
;;
;; #5753's .37 change broke the JVM node-records value→key strong-pin by
;; holding the reaction WEAKLY in the handle state. This complements it: the
;; live→released transition also nils :reaction and :on-change — both unused
;; after release (read/current?/notify short-circuit on :released;
;; read-after-release needs only :query-v/:frame-id) — so a consumer that
;; retains a released handle pins neither the on-change closure (either host)
;; nor the CLJS reaction. Hygiene, not a true leak, but it drops the dangling
;; refs promptly and matches the "released handle retains nothing" intent.
;; ===========================================================================

(deftest released-node-handle-drops-reaction-and-on-change-refs
  (reg-items!)
  (seed-items! [:a])
  (let [target    (items-target)
        on-change (fn [_] :never)
        handle     (obs/acquire! target on-change)
        state     (@#'obs/handle-state handle)]
    (testing "a live node handle holds its reaction + on-change"
      (is (= :live (:status @state)))
      (is (some? (:reaction @state)))
      (is (some? (:on-change @state))))
    (obs/release! handle)
    (testing "the released handle dropped both refs — nothing dangling"
      (is (= :released (:status @state)))
      (is (nil? (:reaction @state)) "the reaction ref was dropped on release")
      (is (nil? (:on-change @state)) "the on-change closure was dropped on release"))
    (testing "read-after-release still throws from :query-v/:frame-id alone"
      (is (= :rf.error/read-after-release
             (error-id (try (obs/read handle)
                            (catch #?(:clj Throwable :cljs :default) e e))))))
    (testing "release! stays idempotent after the drop"
      (obs/release! handle)
      (is (= :released (:status @state))))))

;; ===========================================================================
;; the JVM WeakHashMap self-reference leak fixture — rf2-vxgfnd.37
;;
;; The JVM node-records table is a process-global java.util.WeakHashMap keyed by
;; REACTION; its VALUE carries :owners, a strong set of ObservationHandle objects,
;; and each handle's state references its reaction. java.util.WeakHashMap is NOT
;; an ephemeron map, so a value transitively STRONG-referencing its own weak key
;; pins that key forever:
;;   node-records value → :owners → handle → state → reaction (= the weak key)
;; An interrupted teardown (a committed owner whose cache/frame is dropped
;; WITHOUT release!/dispose) then leaks the reaction + handle for the process
;; lifetime. The fix holds the reaction WEAKLY in the handle state, breaking the
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
       (let [handle-box (volatile! (obs/acquire! (items-target) (fn [_])))
             rx-box    (volatile! (:reaction (entry [:obs/items])))
             rx-ref    (java.lang.ref.WeakReference. ^Object @rx-box)
             handle-ref (java.lang.ref.WeakReference. ^Object @handle-box)]
         (is (= 1 (obs/active-owner-count @rx-box))
             "the handle is enrolled as an active owner in the weak node record")
         ;; Interrupted teardown: evict the cache entry (dropping the cache's
         ;; strong ref to the reaction) WITHOUT release! — so the owner is NEVER
         ;; de-enrolled and :owners still holds the handle. The ONLY remaining
         ;; strong path to the reaction is node-records value → :owners → handle →
         ;; state → reaction. Pre-fix (strong :reaction) that pins the weak key.
         (swap! (sub-cache) dissoc [:obs/items])
         (vreset! handle-box nil) ;; drop the last ordinary strong refs
         (vreset! rx-box nil)
         (is (gc-until-cleared? rx-ref)
             (str "the abandoned reaction is GC-collectable — the weak "
                  "node-records value no longer strong-references its own weak "
                  "key (this assertion FAILS on PR #5710's strong :reaction)"))
         ;; The reaction (weak KEY) is gone; a WeakHashMap operation now expunges
         ;; the stale entry, dropping the map's strong ref to the VALUE (record →
         ;; :owners → handle), which the next GC reclaims.
         (.size ^java.util.Map @#'obs/node-records)
         (is (gc-until-cleared? handle-ref)
             "the abandoned handle was reclaimed once its node record's weak key died")))))

;; ===========================================================================
;; rf2-wbkjk9 — exact-once first-emission provenance in the disposal-notify
;; escape drain: no double emission, no category spoofing
;;
;; #5782 (rf2-6ui49w) made swallowed on-change failures visible, but
;; report-disposal-notify-escape! re-dispatched EVERY throwable carrying a
;; truthy :rf.error/id. Two defects:
;;
;;   1. DOUBLE EMISSION — a callback that calls an observation op which
;;      emits-then-throws (obs/read on a released handle) got its category
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
;; released-handle fixtures below observe TWO read-after-release records
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
  ;; A handle on a DIFFERENT sub, already released — reading it is the
  ;; deterministic emit-then-throw composition: obs/read first fans
  ;; :rf.error/read-after-release (with the RELEASED handle's own
  ;; [:obs/other] attribution), then throws the marked typed error.
  (let [released (obs/acquire! (other-target) (fn [_]))]
    (obs/release! released)
    (let [live (obs/acquire! (items-target) (fn [_n] (obs/read released)))]
      ;; HMR re-registration of :obs/items drains the :hmr boundary; the
      ;; live owner's on-change reads the released handle.
      (let [[[outcome _] records] (with-error-records #(reg-items!))]
        (is (= :ok outcome) "the registrar isolates the replacement hook")
        (let [rar (filterv #(= :rf.error/read-after-release (:error %)) records)]
          (testing "EXACTLY one always-on record for the one runtime error —
                    the source's own emission; the drain does not re-fan an
                    already-fanned typed escape (rf2-wbkjk9)"
            (is (= 1 (count rar))))
          (testing "the surviving record keeps the SOURCE's attribution (the
                    released handle's own sub), never the notifying owner's
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
          ;; Owner A — already-fanned typed: reads the released handle.
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
        ;; DEV-ONLY DRIVER (rf2-d2841): the diagnostic escape this fixture
        ;; classifies is manufactured by the reentrancy assert, which is
        ;; `(when interop/debug-enabled? …)` at its source. Under the gate the
        ;; reentrant release! succeeds, the drain sees no failure, and there is
        ;; no wrapper to check. VACUOUS PASS REMOVED (class 1): the
        ;; never-promoted negative passed over an empty record stream.
        (when interop/debug-enabled?
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
          (is (= :rf.error/reentrant-graph-op (error-id thrown))))))))

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
      ;; The always-on half above needs no guard and never did: the drain emits
      ;; the wrapper through `emit-error-both!`, so the record, its exact
      ;; throwable and its resolved `[:sub id]` coordinate all reach the
      ;; production axis under the gate. Only the DEV-TRACE twin is elided, and
      ;; the independence of the two channels is exactly this suite's thesis
      ;; (rf2-q3fmqm / rf2-d2841) — so running one alone is a demonstration of
      ;; it, not a hole in it.
      (when interop/debug-enabled?
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
              (is (= fid (:frame tags))))))))
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
        ;; The always-on half runs unguarded (rf2-d2841); only the dev-trace
        ;; twin is elided.
        (when interop/debug-enabled?
          (let [tev (filterv #(= :rf.error/observation-on-change-failed (:operation %))
                             traces)]
            (is (= 1 (count tev)) "exactly one trace event at the :disposed boundary")
            (is (= :disposed (:cause (:tags (first tev))))))))
      (obs/release! la))))

(deftest drain-two-channel-fanout-composes-with-first-emission-provenance
  ;; An ALREADY-FANNED typed escape (the released-handle read) gains neither a
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
        ;; ALWAYS-ON (rf2-d2841): the provenance rule's production half — an
        ;; already-fanned typed escape gains no SECOND always-on record from
        ;; the drain. That is the arm an off-box shipper would see duplicated.
        (is (= 1 (count (filterv #(= :rf.error/read-after-release (:error %)) records)))
            "one always-on record — the source's")
        (is (empty? (filterv #(= :rf.error/observation-on-change-failed (:error %))
                             records))
            "and no wrapper RECORD for a typed escape either")
        ;; VACUOUS PASS REMOVED (class 1): the trace-side no-wrapper negative
        ;; below passed over a trace stream that carried nothing at all.
        (when interop/debug-enabled?
          (is (= 1 (count (filterv #(= :rf.error/read-after-release (:operation %)) traces)))
              "one trace event — the source's; the drain adds none")
          (is (empty? (filterv #(= :rf.error/observation-on-change-failed (:operation %))
                               traces))
              "no wrapper trace event for a typed escape")))
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
        ;; DEV-ONLY DRIVER (rf2-d2841), same as the fixture above: the
        ;; diagnostic escape comes from the dev reentrancy assert. VACUOUS PASS
        ;; REMOVED (class 1): the never-promoted negative over an empty stream.
        (when interop/debug-enabled?
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
            (is (= :disposed (:cause (:tags (first tev)))))))))))

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
;; rf2-gsxe1z — a DRAIN-OWNED TYPED escape resolves the SUBSCRIPTION source
;; realm even when a same-id EVENT registration collides
;;
;; RE-VERIFY of the bead's premise against current main. PR #5836's authored
;; head (554cfc52c) forwarded an UNFANNED typed escape's OWN category
;; dynamically (`error-id (if typed? (:rf.error/id (ex-data exception)) …)`),
;; e.g. `:rf.error/reentrant-graph-op`, with the former owner's SUB id in
;; `:event-id`. That category is NOT among error_emit's sub-error-categories,
;; so its source-coord would have resolved under `[:event sub-id]` — a same-id
;; EVENT registration could steal attribution, and a macro sub with no colliding
;; event got no coordinate at all. rf2-w55bh0's always-wrap refactor SUPERSEDED
;; that arm: the drain now ALWAYS wraps an uncovered escape — typed OR untyped —
;; in the stable `:rf.error/observation-on-change-failed`, which error_emit
;; classifies subscription-owned, so `[:sub id]` is the ONLY lookup realm and the
;; dynamic-category mis-routing cannot arise (the drain never emits a non-sub
;; category carrying a sub-id). This fixture PINS the bead's exact combined
;; counterexample the rf2-q3fmqm suites split apart: a MACRO sub PLUS a colliding
;; MACRO event under one keyword AND a drain-owned DIAGNOSTIC-TYPED escape — the
;; wrapper resolves the SUB coordinate, never the unrelated EVENT one. Removing
;; `:rf.error/observation-on-change-failed` from error_emit's sub-error-categories
;; flips the lookup to `[:event id]` and reddens this on BOTH hosts.
;; ===========================================================================

(deftest drain-owned-typed-escape-resolves-the-sub-realm-over-a-colliding-event
  ;; MACRO sub AND colliding MACRO event under one keyword — BOTH capture coords,
  ;; on DISTINCT source lines, so `[:sub id]` and `[:event id]` are discriminable
  ;; (the coord is `{:ns :file :line}`; same-line registrations would collide).
  (rf/reg-sub :obs/collide-typed (fn [db _] (:items db)))
  (rf/reg-event :obs/collide-typed (fn [_cofx _event] {}))
  (seed-items! [:a])
  (let [sub-coord   (source-coords/error-coords-for :sub :obs/collide-typed)
        event-coord (source-coords/error-coords-for :event :obs/collide-typed)]
    (is (some? sub-coord)   "the macro sub registration captured [:sub id] coords")
    (is (some? event-coord) "the colliding macro event captured [:event id] coords")
    (is (not= sub-coord event-coord)
        "the two registrations sit on distinct lines → discriminable coords")
    (with-redefs [interop/next-tick (fn [_f] nil)]
      (let [bad    (atom nil)
            target (obs/resolve-target {:frame fid :query-v [:obs/collide-typed]})
            ;; Owner A's on-change does a FORBIDDEN reentrant release! from inside
            ;; the fan-out → throws the dev :rf.error/reentrant-graph-op assert:
            ;; a DIAGNOSTIC-ONLY typed escape the drain owns coverage for (no
            ;; source emit, so source-covered-always-on? reads FALSE → wrapped).
            la     (obs/acquire! target (fn [_n] (obs/release! @bad)))]
        (reset! bad la)
        (force-dispose-node! [:obs/collide-typed])
        (let [[[outcome _] records]
              (with-error-records #(obs/drain-pending-disposals! :disposed))]
          ;; The three coordinate assertions above are always-on and stay
          ;; there: `source-coords/remember-error-coords!` runs unconditionally
          ;; at registration (pass 5's finding), so "the macro sub and the
          ;; colliding macro event captured DISCRIMINABLE coordinates" — the
          ;; premise the whole counterexample rests on — is proved under the
          ;; gate. What is dev-only is the ESCAPE: the diagnostic typed throw
          ;; comes from the reentrancy assert (rf2-d2841). VACUOUS PASSES
          ;; REMOVED (class 1 + class 4): the never-promoted negative over an
          ;; empty record stream, and `(not= event-coord (:source-coord …))`
          ;; over a nil record, where any coordinate differs from nil.
          (when interop/debug-enabled?
            (is (= :threw outcome)
                "the drain re-throws the first escape after draining every sibling")
            (let [wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                   records)]
              (is (= 1 (count wrapped)) "exactly one drain-owned wrapper record")
              (is (empty? (filterv #(= :rf.error/reentrant-graph-op (:error %)) records))
                  "the diagnostic TYPED category is NEVER promoted onto the always-on axis")
              (is (= :obs/collide-typed (:event-id (first wrapped)))
                  "the wrapper is attributed to the former owner's ENTRY sub id")
              (is (= sub-coord (:source-coord (first wrapped)))
                  "the drain-owned TYPED escape resolves the [:sub id] coordinate")
              (is (not= event-coord (:source-coord (first wrapped)))
                  "the unrelated same-id EVENT coordinate is never selected"))))
        (obs/release! la)))))

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
  ;; source: reading a handle AFTER release! throws :rf.error/read-after-release,
  ;; which the port fans on the always-on axis and binds to THAT exact throwable.
  (let [setup-handle (obs/acquire! (items-target) (fn [_]))
        _           (obs/release! setup-handle)
        auth-ex     (try (obs/read setup-handle)
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
;; rf2-fs99nq — provenance is keyed by genuine WEAK OBJECT IDENTITY on the JVM,
;; never by the throwable's own `.equals`/`.hashCode`.
;;
;; rf2-9m4oy7's JVM map was a `java.util.WeakHashMap` keyed by the throwable
;; directly. `WeakHashMap` keys by `.equals`/`.hashCode`, and `Throwable` leaves
;; both VIRTUAL — so an APPLICATION throwable that OVERRIDES them defeats the
;; exact-identity contract two ways:
;;
;;   1. COLLISION — a distinct throwable whose `hashCode` equals a bound throwable's
;;      and whose `equals` accepts it reads that bound throwable's `#{:always-on}`
;;      provenance, so the drain treats a raw application exception as already
;;      covered and SUPPRESSES its required :rf.error/observation-on-change-failed
;;      record (production silence).
;;   2. THROWING CLASSIFICATION — a throwable whose `hashCode`/`equals` THROWS makes
;;      `provenance-by-throwable.get` itself escape `source-covered-always-on?`,
;;      propagating out of the drain's per-handle catch, aborting the reduce before
;;      healthy siblings drain and replacing the first-original rethrow — breaking
;;      the full-drain / first-original-identity law.
;;
;; The fix keys the JVM association by a WEAK IDENTITY key (a ReferenceQueue-backed
;; `WeakReference` subclass hashing by `System/identityHashCode`, comparing by
;; referent `identical?`), so provenance classification NEVER invokes an application
;; throwable's `hashCode`/`equals`. Red-before-fix (revert to `WeakHashMap<Throwable>`
;; lookup): the collision fixture SUPPRESSES B (zero wrappers), and the throwing-
;; method fixture aborts the drain before the trailing siblings.
;;
;; CLJS is inherently identity-keyed — `js/WeakMap` uses reference keys with no
;; `.equals`/`.hashCode` seam — and its exact-throwable binding is already pinned
;; cross-host by `disposed-drain-binds-emission-provenance-to-the-exact-throwable`
;; (the transplanted-token leg reads uncovered on CLJS too). So this leg is JVM-only.
;; ===========================================================================

#?(:clj
   (deftest jvm-forged-equality-throwable-does-not-read-covered
     ;; A DISTINCT custom RuntimeException B whose hash collides with an authentic
     ;; bound throwable A and whose equality accepts A must NOT read covered: it is a
     ;; different object, so the drain owns its production record (rf2-fs99nq).
     (reg-items!)
     (seed-items! [:a])
     ;; A = an AUTHENTIC port-minted throwable bound `#{:always-on :trace}` at its
     ;; source (read-after-release! fans always-on and binds THAT exact throwable).
     (let [setup (obs/acquire! (items-target) (fn [_]))
           _     (obs/release! setup)
           A     (try (obs/read setup)
                      (catch Throwable e e))
           ;; B forges A's identity hash and reports equality with A — exactly the
           ;; keys a WeakHashMap<Throwable> would collide on. `System/identityHashCode`
           ;; discrimination + `identical?` ignore both, so B stays uncovered.
           B     (proxy [RuntimeException] ["forged-equality boom"]
                   (hashCode [] (System/identityHashCode A))
                   (equals [o] (identical? o A)))]
       (is (= :rf.error/read-after-release (error-id A))
           "the fixture carries a REAL port-minted always-on throwable")
       (with-redefs [interop/next-tick (fn [_f] nil)]
         (let [notes (atom [])
               ;; la RE-THROWS the exact bound A (covered → no wrapper);
               ;; lb throws the forged-equality B (uncovered → exactly one wrapper).
               la (obs/acquire! (items-target) (fn [_n] (throw A)))
               lb (obs/acquire! (items-target) (fn [_n] (throw B)))
               lc (obs/acquire! (items-target) (fn [n] (swap! notes conj n)))]
           (force-dispose-node! [:obs/items])
           (let [[_ records]
                 (with-error-records #(obs/drain-pending-disposals! :disposed))
                 wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                  records)
                 causes  (mapv :exception wrapped)]
             (testing "the healthy sibling was still notified"
               (is (= 1 (count @notes))))
             (testing "the forged-equality throwable CANNOT read covered — it gets
                       exactly one stable wrapper carrying B (pre-fix / WeakHashMap:
                       B collides onto A and is suppressed, zero wrappers)"
               (is (= 1 (count wrapped)))
               (is (some #(identical? % B) causes))
               (is (every? #(= :obs/items (:event-id %)) wrapped)))
             (testing "the EXACT bound A is covered — its source emission stands, so
                       the drain adds NO wrapper for it (exact-once)"
               (is (not (some #(identical? % A) causes)))))
           (obs/release! la) (obs/release! lb) (obs/release! lc))))))

#?(:clj
   (deftest jvm-throwing-hashcode-or-equals-throwable-is-classified-by-identity
     ;; Provenance classification must call NEITHER `hashCode` NOR `equals` on an
     ;; application throwable (rf2-fs99nq): a throwable whose method throws must not
     ;; make the lookup escape the drain's catch. Every healthy sibling — including
     ;; ones queued AFTER the throwing throwables — is notified; each throwing
     ;; throwable is wrapped exactly once carrying itself; and the DIRECT drain
     ;; rethrows the FIRST original only after the COMPLETE drain.
     (reg-items!)
     (seed-items! [:a])
     (with-redefs [interop/next-tick (fn [_f] nil)]
       (let [notes    (atom [])
             hc-boom  (proxy [RuntimeException] ["hashCode-must-not-be-called"]
                        (hashCode [] (throw (IllegalStateException.
                                              "application hashCode was invoked during provenance classification"))))
             eq-boom  (proxy [RuntimeException] ["equals-must-not-be-called"]
                        (equals [_o] (throw (IllegalStateException.
                                              "application equals was invoked during provenance classification"))))
             ;; Queue order: sibling, hc-boom (FIRST escape), eq-boom, sibling. The
             ;; trailing sibling proves the drain did not abort mid-reduce.
             s1 (obs/acquire! (items-target) (fn [n] (swap! notes conj n)))
             lb (obs/acquire! (items-target) (fn [_n] (throw hc-boom)))
             lc (obs/acquire! (items-target) (fn [_n] (throw eq-boom)))
             s2 (obs/acquire! (items-target) (fn [n] (swap! notes conj n)))]
         (force-dispose-node! [:obs/items])
         (let [[[outcome thrown] records]
               (with-error-records #(obs/drain-pending-disposals! :disposed))
               wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                records)
               causes  (mapv :exception wrapped)]
           (testing "every healthy sibling is notified — including the one queued
                     AFTER the throwing throwables (pre-fix: the throwing hashCode
                     escapes classification and aborts the reduce before s2)"
             (is (= 2 (count @notes))))
           (testing "each throwing-method throwable is wrapped exactly once carrying
                     its OWN original throwable"
             (is (= 2 (count wrapped)))
             (is (some #(identical? % hc-boom) causes))
             (is (some #(identical? % eq-boom) causes)))
           (testing "the direct drain rethrows the first ORIGINAL throwable only
                     after the complete drain (identity/cause intact — never a
                     classification-derived exception; drain order rides the
                     :owners set so either original may be first)"
             (is (= :threw outcome))
             (is (or (identical? hc-boom thrown)
                     (identical? eq-boom thrown))
                 "the rethrow is one of the two ORIGINAL throwables, not a
                  hashCode/equals-classification escape")))
         (obs/release! s1) (obs/release! lb) (obs/release! lc) (obs/release! s2)))))

;; ===========================================================================
;; rf2-qqvgk1 — the JVM provenance STORAGE is reload-safe and its weak lifecycle
;; is PROVEN.
;;
;; #5884 keeps the same `defonce` storage Var while changing its representation
;; (predecessor rf2-9m4oy7: a synchronized `WeakHashMap<Throwable>`; current: a
;; weak-identity `HashMap<WeakReference-key>` + `ReferenceQueue`). A normal reload
;; retains the old `defonce` root, so predecessor entries become unreachable through
;; the new key and a freshly-`defonce`'d queue desyncs from the retained map. The fix
;; bundles map + queue in ONE versioned holder and RECONCILES it at load — recognizing
;; + replacing an incompatible predecessor root — while a same-version reload is an
;; idempotent no-op that preserves entries. These fixtures PROVE the reload-safety and
;; the weak lifecycle (GC + expunge), rather than assuming them. JVM-only: CLJS uses
;; `js/WeakMap` (ephemeron, fresh realm per page reload).
;; ===========================================================================

#?(:clj
   (deftest jvm-provenance-storage-reload-replaces-an-incompatible-predecessor-root
     ;; acceptance 1 + revert-detection: seed the PREDECESSOR representation
     ;; (rf2-9m4oy7's synchronized WeakHashMap<Throwable>, unversioned) under the
     ;; defonce'd storage Var, simulate a namespace reload by re-running the load-time
     ;; reconciliation WITHOUT remove-ns, and prove the CURRENT versioned representation
     ;; is installed and the port round-trips over it. A bare `defonce` (no
     ;; reconciliation) would RETAIN the predecessor WeakHashMap, so this fixture
     ;; reddens if the fix is reverted ("mutation back to an unversioned defonce
     ;; retaining the predecessor map fails the reload fixture").
     (let [saved @#'obs/provenance-storage]
       (try
         ;; A reload keeps the defonce root; seed the predecessor shape under the Var.
         (alter-var-root #'obs/provenance-storage
                         (constantly (java.util.Collections/synchronizedMap
                                       (java.util.WeakHashMap.))))
         (is (not (#'obs/current-provenance-storage? @#'obs/provenance-storage))
             "precondition: the seeded predecessor root is NOT current-version")
         ;; The reload's load-time reconciliation re-runs (as the top-level form does).
         (#'obs/ensure-current-provenance-storage!)
         (let [installed @#'obs/provenance-storage]
           (is (#'obs/current-provenance-storage? installed)
               "the current versioned representation is installed after reload")
           (is (= @#'obs/provenance-storage-version (:version installed)))
           (is (instance? java.util.HashMap (:by-throwable installed))
               "the inner map is the current plain HashMap, not the predecessor WeakHashMap")
           (is (instance? java.lang.ref.ReferenceQueue (:queue installed))
               "the current holder carries its own paired ReferenceQueue"))
         ;; The port round-trips over the reload-installed storage.
         (let [t (ex-info "post-reload boom" {})]
           (#'obs/attest-provenance! t @#'obs/provenance-both-channels)
           (is (true? (#'obs/source-covered-always-on? t))
               "attest/lookup round-trips over the reload-installed storage")
           (is (false? (#'obs/source-covered-always-on? (ex-info "unbound" {})))
               "an unbound throwable still reads uncovered"))
         ;; Repeated reconciliation is idempotent — the SAME current holder is kept.
         (let [before @#'obs/provenance-storage]
           (#'obs/ensure-current-provenance-storage!)
           (is (identical? before @#'obs/provenance-storage)
               "a same-version reconciliation is a NO-OP (idempotent, entries preserved)"))
         (finally
           (alter-var-root #'obs/provenance-storage (constantly saved)))))))

#?(:clj
   (deftest jvm-provenance-survives-gc-while-throwable-strongly-held-no-duplicate-wrapper
     ;; acceptance 2: a strongly-retained throwable's provenance SURVIVES bounded GC
     ;; (the weak key is pinned by its strong referent), so the disposal drain still
     ;; reads it covered and adds NO duplicate callback-failure wrapper.
     (reg-items!)
     (seed-items! [:a])
     (let [setup (obs/acquire! (items-target) (fn [_]))
           _     (obs/release! setup)
           ;; A REAL port-minted always-on throwable, bound #{:always-on :trace} by
           ;; read-after-release's emit-then-throw.
           A     (try (obs/read setup) (catch Throwable e e))]
       (is (= :rf.error/read-after-release (error-id A)))
       (is (true? (#'obs/source-covered-always-on? A))
           "authentic provenance reads covered before GC")
       ;; Force bounded GC while A is strongly reachable (this local + the closure
       ;; below both retain it); provenance MUST survive.
       (dotimes [_ 10]
         (System/gc) (System/runFinalization) (make-array Object 200000))
       (is (true? (#'obs/source-covered-always-on? A))
           "provenance survives GC while its throwable is strongly reachable")
       (with-redefs [interop/next-tick (fn [_f] nil)]
         (let [notes (atom [])
               la (obs/acquire! (items-target) (fn [_n] (throw A)))
               lc (obs/acquire! (items-target) (fn [n] (swap! notes conj n)))]
           (force-dispose-node! [:obs/items])
           (let [[_ records] (with-error-records #(obs/drain-pending-disposals! :disposed))
                 wrapped (filterv #(= :rf.error/observation-on-change-failed (:error %))
                                  records)]
             (is (= 1 (count @notes)) "the healthy sibling was still notified")
             (is (zero? (count wrapped))
                 "the covered source stands — NO duplicate wrapper (exact-once)"))
           (obs/release! la) (obs/release! lc))))))

#?(:clj
   (deftest jvm-provenance-entry-is-weak-and-expunged-when-its-throwable-dies
     ;; acceptance 3 (the weak-lifecycle PROOF): release the throwable, retain only a
     ;; WeakReference, force bounded GC + a later map access, and prove the referent
     ;; CLEARS (the storage retains it via NEITHER key NOR value) and THIS entry is
     ;; expunged from the private storage. A strong-map mutation — retaining the
     ;; referent by key OR value — reddens gc-until-cleared?; a mutation that leaks
     ;; the entry (never enqueued, never removed) reddens the expunge poll below.
     ;;
     ;; BOTH map assertions name THIS ONE ENTRY, never the map's total size
     ;; (rf2-8vvdo). `provenance-storage` is PROCESS-WIDE and shrinks asynchronously:
     ;; a Reference is CLEARED during GC but ENQUEUED afterwards by the
     ;; ReferenceHandler thread, so any `baseline` sampled after a drain over-counts
     ;; by whatever backlog is still pending — and that backlog grows with machine
     ;; load. The size-based form this replaced therefore reddened BECAUSE UNRELATED
     ;; COLLECTION SUCCEEDED: nothing here adds an entry after the attestation, so the
     ;; size only falls, and a sibling landing mid-poll drops it BELOW the sampled
     ;; baseline, after which `(= baseline (.size m))` can never hold again and the
     ;; bounded loop times out into false. Relaxing that to `(<= (.size m) baseline)`
     ;; would cure the false red at the cost of a false GREEN under the very same
     ;; load: a sibling expunging in our entry's place satisfies it while our entry is
     ;; still leaked. Asking the map about OUR OWN KEY is immune to both, and says
     ;; exactly what the test is named for.
     (let [prov  @#'obs/provenance-both-channels
           m     ^java.util.Map (:by-throwable @#'obs/provenance-storage)
           t-box (volatile! (ex-info "weak-lifecycle boom" {}))
           t-ref (java.lang.ref.WeakReference. ^Object @t-box)]
       (#'obs/attest-provenance! @t-box prov)
       ;; The STORED key objects for this throwable, found by identity over the live
       ;; key set. A key is a WeakReference subclass, so holding one strongly retains
       ;; the KEY and never its referent — the entry stays free to be cleared,
       ;; enqueued and expunged exactly as in production (the HashMap holds its keys
       ;; strongly anyway). Its `hashCode` is the identityHashCode captured at
       ;; construction and its `equals` short-circuits on `identical? this o`, so
       ;; `.containsKey` answers about THIS entry alone — before or after clearing,
       ;; and without ever dereferencing a dead referent.
       (let [our-keys (locking m
                        (into [] (filter #(identical? @t-box
                                                      (.get ^java.lang.ref.WeakReference %)))
                              (.keySet m)))
             our-key  (first our-keys)]
         (is (= 1 (count our-keys))
             "the attestation bound EXACTLY one entry, keyed by this throwable")
         (is (true? (#'obs/source-covered-always-on? @t-box))
             "the bound throwable reads covered")
         (vreset! t-box nil) ;; drop the last strong ref — only t-ref (weak) remains
         (is (gc-until-cleared? t-ref)
             (str "the throwable is GC-collectable — the storage retains it via "
                  "NEITHER the weak key NOR the provenance value"))
         ;; A later map access polls the ReferenceQueue and expunges the cleared key;
         ;; poll until the async reference-enqueue lands (bounded, deterministic).
         (is (loop [i 0]
               (#'obs/source-covered-always-on? (ex-info "post-gc access" {}))
               (cond
                 (not (.containsKey m our-key)) true
                 (>= i 40)                      false
                 :else (do (System/gc) (System/runFinalization) (recur (inc i)))))
             (str "the cleared entry was expunged — THIS key is gone from the private "
                  "storage"))))))

;; ===========================================================================
;; rf2-kia9st — the JVM provenance is TRULY reload-safe: a REAL namespace reload
;; (not merely the reconciliation helper) keeps a pre-reload attestation covered,
;; and every operation reads its map + queue from ONE coherent holder snapshot.
;;
;; #5910 preserved a versioned holder but two hazards remained on current main:
;;   1. A real `(require … :reload)` REDEFINES the `EmissionProvenance` deftype's
;;      class. The defonce'd holder retained the provenance VALUE as an OLD-class
;;      instance, so `(instance? EmissionProvenance …)` read FALSE afterward and a
;;      throwable attested before the reload silently flipped covered→uncovered —
;;      an exact-once-coverage violation the reconciliation-helper-only test never
;;      exercised. The fix stores the reload-STABLE channel SET.
;;   2. `attest-provenance!` / `source-covered-always-on?` fetched the map and the
;;      ReferenceQueue through SEPARATE Var reads; a reload reconciliation
;;      interleaving between them paired one holder's map with another's queue,
;;      orphaning the entry / mismatching the queue. The fix takes ONE coherent
;;      holder snapshot per operation ([[provenance-holder]]).
;; JVM-only: CLJS uses `js/WeakMap` (ephemeron, fresh realm per page reload).
;; ===========================================================================

#?(:clj
   (deftest jvm-provenance-survives-a-real-namespace-reload-covered-exactly-once
     ;; rf2-kia9st acceptance 1 + 3: attest a REAL port-minted throwable, perform a
     ;; REAL `(require … :reload)` (redefining the EmissionProvenance class), and prove
     ;; the SAME throwable still reads covered afterward + the disposal drain adds NO
     ;; duplicate wrapper (exact-once). Reverting the stored value to the deftype
     ;; instance reddens this: the retained old-class instance reads instance? FALSE
     ;; after the reload, flipping covered→uncovered.
     (reg-items!)
     (seed-items! [:a])
     (let [setup         (obs/acquire! (items-target) (fn [_]))
           _             (obs/release! setup)
           ;; A REAL port-minted always-on throwable, bound #{:always-on :trace} by
           ;; read-after-release's emit-then-throw. Held STRONGLY across the reload so
           ;; only the class-redefinition — never GC — can flip its coverage.
           a-throwable   (try (obs/read setup) (catch Throwable e e))
           holder-before @#'obs/provenance-storage]
       (is (= :rf.error/read-after-release (error-id a-throwable)))
       (is (true? (#'obs/source-covered-always-on? a-throwable))
           "covered before the reload")
       ;; THE RELOAD — redefines EmissionProvenance's class; defonce keeps the holder
       ;; and its entries, ensure-current-provenance-storage! reconciles (a no-op).
       (require 're-frame.substrate.observation :reload)
       (is (identical? holder-before @#'obs/provenance-storage)
           "a same-version reload keeps the defonce'd holder — entries preserved")
       (is (#'obs/current-provenance-storage? @#'obs/provenance-storage)
           "the reload-reconciled holder is current-version")
       (is (true? (#'obs/source-covered-always-on? a-throwable))
           (str "the throwable attested BEFORE the reload STILL reads covered AFTER "
                "it — the reload-stable channel-set value survives the deftype-class "
                "redefinition (a retained EmissionProvenance instance would read "
                "instance? FALSE against the new class and flip covered→uncovered)"))
       (is (false? (#'obs/source-covered-always-on? (ex-info "unbound post-reload" {})))
           "an unbound throwable still reads uncovered after the reload")
       ;; Exact-once across the reload: a former owner whose on-change throws the
       ;; pre-reload-attested throwable during a disposal drain must NOT add a
       ;; duplicate callback-failure wrapper — its source coverage survived the reload.
       (with-redefs [interop/next-tick (fn [_f] nil)]
         (let [notes (atom [])
               la    (obs/acquire! (items-target) (fn [_n] (throw a-throwable)))
               lc    (obs/acquire! (items-target) (fn [n] (swap! notes conj n)))]
           (force-dispose-node! [:obs/items])
           (let [[_ records] (with-error-records
                               #(obs/drain-pending-disposals! :disposed))
                 wrapped     (filterv #(= :rf.error/observation-on-change-failed
                                          (:error %))
                                      records)]
             (is (= 1 (count @notes)) "the healthy sibling was still notified")
             (is (zero? (count wrapped))
                 "the covered source stands across the reload — NO duplicate wrapper"))
           (obs/release! la) (obs/release! lc))))))

#?(:clj
   (deftest jvm-attest-takes-one-coherent-holder-snapshot-under-a-reconciliation-interleave
     ;; rf2-kia9st acceptance 2 + 4: attest reads its map + queue from ONE holder
     ;; snapshot. Here a reconciliation is forced to interleave EXACTLY at attest's
     ;; single holder read — the stub returns the live holder A and, as its side
     ;; effect, moves the live storage Var forward to a fresh B. With one coherent
     ;; snapshot the entry AND its weak-identity key both belong to A, so A expunges
     ;; the entry when the throwable dies. The pre-fix two-read shape (map via one Var
     ;; read, queue via a second) would read A then B — registering the key on B's
     ;; queue while the entry landed in A's map, an orphan A can never expunge; that
     ;; reversion reddens both the single-read count and the expunge-from-A proof.
     (let [saved @#'obs/provenance-storage]
       (try
         (let [holder-a ^clojure.lang.IPersistentMap (#'obs/fresh-provenance-storage)
               holder-b ^clojure.lang.IPersistentMap (#'obs/fresh-provenance-storage)
               a-map    ^java.util.Map (:by-throwable holder-a)
               a-queue  ^java.lang.ref.ReferenceQueue (:queue holder-a)
               reads    (atom 0)]
           (alter-var-root #'obs/provenance-storage (constantly holder-a))
           (let [t-box (volatile! (ex-info "coherent-snapshot boom" {}))
                 t-ref (java.lang.ref.WeakReference. ^Object @t-box)]
             (with-redefs [obs/provenance-holder
                           (fn []
                             (swap! reads inc)
                             ;; whichever holder is LIVE at this read (A on the first
                             ;; read); then interleave a reconciliation to a fresh B.
                             (let [live @#'obs/provenance-storage]
                               (alter-var-root #'obs/provenance-storage
                                               (constantly holder-b))
                               live))]
               (#'obs/attest-provenance! @t-box @#'obs/provenance-both-channels))
             (is (= 1 @reads)
                 "attest read the holder EXACTLY once — one coherent snapshot")
             (is (= 1 (.size a-map))
                 "the entry landed in the snapshotted holder A (no orphan)")
             (is (zero? (.size ^java.util.Map (:by-throwable holder-b)))
                 "the concurrently-installed live holder B never received this attest")
             ;; No mismatched queue: the key was registered on A's OWN queue, so
             ;; collecting the throwable + expunging A reclaims it. A key on B's queue
             ;; (the pre-fix split) would leave a-map at size 1 forever.
             (vreset! t-box nil)
             (is (gc-until-cleared? t-ref)
                 "the throwable is collectable — stored via neither key nor value")
             (is (loop [i 0]
                   (#'obs/expunge-stale-provenance! a-map a-queue)
                   (cond
                     (zero? (.size a-map)) true
                     (>= i 40)             false
                     :else (do (System/gc) (System/runFinalization) (recur (inc i)))))
                 (str "expunge from the OWNING holder A reclaims the entry — its key "
                      "was registered on A's queue, not B's (no mismatched queue)"))))
         (finally
           (alter-var-root #'obs/provenance-storage (constantly saved)))))))

;; ===========================================================================
;; rf2-b0afn — the JVM provenance value-shape upgrade is VERSIONED, so a
;; preserved IMMEDIATE-PREDECESSOR v2 holder is reconciled as INCOMPATIBLE,
;; never `contains?`-thrown mid-read.
;;
;; #6047 (rf2-kia9st) changed the stored VALUE from an `EmissionProvenance`
;; deftype INSTANCE to its reload-stable raw channel SET, but LEFT the storage
;; version at 2 — the SAME version the immediate predecessor declared. So a
;; reload over a pre-#6047 v2 holder read it current (`map?` + `:version 2`) and
;; PRESERVED it, retaining deftype-instance values; the new set-shaped
;; `source-covered-always-on?` reader then called `contains?` on a retained
;; deftype instance and threw `IllegalArgumentException` MID-READ — an in-flight
;; disposal-drain read escaping instead of preserving full-drain/exact-once
;; coverage. The existing real-reload fixture only attests with the NEW
;; representation before reloading, so it proves new→new reload, NOT the shipped
;; old-v2→new-v2 value-shape upgrade. Bumping 2→3 makes the value-shape upgrade an
;; INCOMPATIBLE-holder transition: a v2 root reads uncurrent and is REPLACED (its
;; deftype-valued entries dropped; any in-flight predecessor throwable reads
;; uncovered so the drain fails LOUD), never silently `contains?`-thrown.
;; JVM-only: CLJS uses `js/WeakMap` (fresh realm per page reload).
;; ===========================================================================

#?(:clj
   (deftest jvm-provenance-storage-reload-replaces-an-immediate-predecessor-v2-holder
     ;; RED-before / GREEN-after: seed the IMMEDIATE PREDECESSOR v2 holder — the
     ;; SAME holder SHAPE (`map?` + `:version 2` + HashMap + ReferenceQueue), but
     ;; whose stored VALUES are `EmissionProvenance` deftype INSTANCES (the pre-#6047
     ;; representation) rather than raw channel sets. Before the version bump this
     ;; holder read current, was preserved, and `source-covered-always-on?` threw
     ;; `contains?`-on-`EmissionProvenance` mid-read; after the bump it reads
     ;; INCOMPATIBLE and is reconciled (replaced) WITHOUT throwing.
     (let [saved @#'obs/provenance-storage]
       (try
         (let [q         (java.lang.ref.ReferenceQueue.)
               m         (java.util.HashMap.)
               ;; A throwable held STRONGLY so its entry survives to be read — only
               ;; the version reconciliation, never GC, decides its fate here.
               t         (ex-info "predecessor-shaped boom" {})
               ;; The predecessor VALUE shape: the `EmissionProvenance` deftype
               ;; INSTANCE (what pre-#6047 attest-provenance! stored), NOT a set.
               pred-val  @#'obs/provenance-both-channels]
           ;; The predecessor value is the deftype instance, NOT a set — so the
           ;; current set-shaped reader's `contains?` throws on it (the bug symptom).
           (is (not (set? pred-val))
               "the seeded predecessor value is an EmissionProvenance instance, not a set")
           (.put m (#'obs/weak-identity-key t q) pred-val)
           ;; The pre-#6047 v2 holder: identical SHAPE, version 2, deftype values.
           (alter-var-root #'obs/provenance-storage
                           (constantly {:version 2 :by-throwable m :queue q}))
           ;; With the version bumped 2→3 this predecessor v2 holder is recognized
           ;; as INCOMPATIBLE (its value shape changed); before the bump it read
           ;; current (`map?` + `:version 2` matched) — so this precondition reddens
           ;; if the fix is reverted.
           (is (not (#'obs/current-provenance-storage? @#'obs/provenance-storage))
               (str "the immediate-predecessor v2 holder is recognized as an "
                    "INCOMPATIBLE root (its stored VALUE representation changed), "
                    "not a compatible same-version holder"))
           ;; The reload's load-time reconciliation REPLACES the incompatible holder
           ;; with a fresh current-version one — WITHOUT reading (and `contains?`-
           ;; throwing on) the retained deftype values.
           (#'obs/ensure-current-provenance-storage!)
           (let [installed @#'obs/provenance-storage]
             (is (#'obs/current-provenance-storage? installed)
                 "a fresh current-version holder is installed after the upgrade reload")
             (is (= @#'obs/provenance-storage-version (:version installed))
                 "the installed holder carries the CURRENT storage version")
             (is (instance? java.util.HashMap (:by-throwable installed))
                 "the fresh holder's inner map is the current plain HashMap")
             (is (instance? java.lang.ref.ReferenceQueue (:queue installed))
                 "the fresh holder carries its own paired ReferenceQueue"))
           ;; Post-upgrade coverage behavior is EXPLICIT: the dropped predecessor
           ;; throwable reads UNCOVERED — a total, non-throwing read that lets the
           ;; disposal drain fail LOUD (its own catalogued
           ;; :rf.error/observation-on-change-failed record), never a silent
           ;; `contains?`-on-`EmissionProvenance` throw escaping mid-drain.
           (is (false? (#'obs/source-covered-always-on? t))
               (str "the dropped predecessor throwable reads uncovered WITHOUT "
                    "throwing — the value-shape upgrade reconciles via the "
                    "incompatible-holder path (fail-loud), not a mid-read throw"))
           ;; And the port round-trips over the reload-installed storage — a fresh
           ;; attestation reads covered exactly-once.
           (let [t2 (ex-info "post-upgrade boom" {})]
             (#'obs/attest-provenance! t2 @#'obs/provenance-both-channels)
             (is (true? (#'obs/source-covered-always-on? t2))
                 "a fresh attestation round-trips over the reconciled storage")
             (is (false? (#'obs/source-covered-always-on? (ex-info "unbound" {})))
                 "an unbound throwable still reads uncovered")))
         (finally
           (alter-var-root #'obs/provenance-storage (constantly saved)))))))

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
;; rf2-xakb4p + rf2-qyvyes — the catalogue-to-schema / record validation gate,
;; bound to the EXECUTABLE canonical schema.
;;
;; The disposal-notify wrapper's record shape is FROZEN by a canonical per-category
;; schema ([Spec-Schemas §ObservationOnChangeFailedTags]). rf2-qyvyes replaces the
;; earlier HAND-COPIED predicate (which merely MIRRORED the markdown and could drift
;; from it silently — required→optional, enum→keyword, vector→any, symbol→keyword
;; would all stay green while real HMR/disposed records stopped conforming) with the
;; actual schema form EXTRACTED from Spec-Schemas.md at compile time
;; ([[canonical-observation-schema-form]]) and a minimal structural interpreter. The
;; gate binds that executable schema in three directions:
;;
;;   1. RECORD → SCHEMA (both hosts): a real :hmr drain and a real :disposed drain
;;      produce dev-trace `:tags` that VALIDATE against the extracted `[:map …]`
;;      form, and the always-on record carries the wire fields the 009 catalogue row
;;      lists. Dropping any required key, or drifting the channel-discriminating
;;      :cause / spoofing :category, fails the gate.
;;   2. SCHEMA SHAPE (both hosts): the extracted form itself is pinned to the strict
;;      shape ([[canonical-schema-form-pins-the-strict-observation-shape]]) — so a
;;      required→optional / enum→keyword / vector→any / field-type weakening in the
;;      markdown reddens deterministically, and removing the def is a compile error.
;;   3. SCHEMA ↔ SPEC 009 (JVM only): the extracted schema's required keys are the
;;      SAME keys the Spec 009 catalogue row enumerates as DEV-TRACE :tags, so a
;;      tag-key drift between the two surfaces reddens.
;;
;; (The CHANNEL classification — always-on — is already pinned by
;; error-catalogue-channel-conformance-test + always-on-axis-conformance's
;; `always-on-categories` literal, which includes this category.)
;; ===========================================================================

(def ^:private canonical-observation-schema-form
  "The `ObservationOnChangeFailedTags` `[:map …]` schema, EXTRACTED at compile time
  from spec/Spec-Schemas.md (rf2-qyvyes) — the executable canonical form, identical on
  both hosts. Runtime records are validated against THIS, so markdown schema drift
  reddens the gate (and removing the def is a build error)."
  (canonical-observation-schema))

(declare valid-against-map-schema?)

(defn- leaf-schema-valid?
  "Minimal structural validator for the leaf Malli forms the extracted
  ObservationOnChangeFailedTags schema uses — `:any` / `:keyword` / `:string` /
  `:symbol` / `:boolean` / `:int`, `[:= v]`, `[:enum …]`, `[:vector inner]`, and a
  nested `[:map …]`. Intentionally NOT a general Malli engine (rf2-qyvyes)."
  [schema v]
  (cond
    (= schema :any)     true
    (= schema :keyword) (keyword? v)
    (= schema :string)  (string? v)
    (= schema :symbol)  (symbol? v)
    (= schema :boolean) (boolean? v)
    (= schema :int)     (integer? v)
    (vector? schema)
    (case (first schema)
      :=      (= v (second schema))
      :enum   (contains? (set (rest schema)) v)
      :vector (and (vector? v) (every? #(leaf-schema-valid? (second schema) %) v))
      :map    (valid-against-map-schema? schema v)
      false)
    :else false))

(defn- map-schema-entries
  "Parse an extracted Malli `[:map [:k schema] [:k {:optional true} schema] …]` form
  into a seq of `{:key k :optional? bool :schema s}` (rf2-qyvyes)."
  [map-schema]
  (for [entry (rest map-schema)
        :let  [k (first entry)
               r (rest entry)
               [opts s] (if (map? (first r)) [(first r) (second r)] [nil (first r)])]]
    {:key k :optional? (boolean (:optional opts)) :schema s}))

(defn- valid-against-map-schema?
  "True when record map `m` conforms to the extracted Malli `[:map …]` `map-schema`:
  every REQUIRED key present and leaf-valid, every present OPTIONAL key leaf-valid.
  Extra keys are allowed (Malli maps are open by default). rf2-qyvyes."
  [map-schema m]
  (and (map? m)
       (every? (fn [{:keys [key optional? schema]}]
                 (if (contains? m key)
                   (leaf-schema-valid? schema (get m key))
                   optional?))
               (map-schema-entries map-schema))))

(def ^:private observation-tags-required-keys
  "The required keys of the EXTRACTED canonical schema (rf2-qyvyes) — DERIVED, not
  hand-copied, so removing a required runtime field fails the same causal gate."
  (->> (map-schema-entries canonical-observation-schema-form)
       (remove :optional?)
       (mapv :key)))

(defn- valid-observation-on-change-failed-tags?
  "Validate a dev-trace `:tags` payload against the EXECUTABLE canonical
  ObservationOnChangeFailedTags schema extracted from Spec-Schemas.md (rf2-qyvyes) —
  no longer a hand-copied predicate. Markdown schema drift (required→optional,
  enum→keyword, vector→any, a field type, the category) changes what conforms, so the
  record-validation tests below redden on drift."
  [tags]
  (valid-against-map-schema? canonical-observation-schema-form tags))

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
        ;; The record half needs no guard (rf2-d2841) — the wrapper category is
        ;; PROMOTED, so the 009 wire fields are checked in the posture that
        ;; ships. Only the dev-trace `:tags` the schema validates are elided.
        (testing "the always-on record carries the wire fields the 009 row lists"
          (is (some? rec))
          (is (= [:obs/items] (:event rec)))
          (is (= :obs/items (:event-id rec)))
          (is (= fid (:frame rec)))
          (is (identical? boom (:exception rec))))
        (when interop/debug-enabled?
          (is (some? tev) "exactly the one dev-trace event to validate")
          (is (valid-observation-on-change-failed-tags? (:tags tev))
              "the :hmr dev-trace tags satisfy the canonical schema")
          (is (= :hmr (:cause (:tags tev))))
          (testing "the dev-trace :tags carry the EXACT original throwable and frame
                    keyword — not only the always-on record (rf2-5iud0a false-greens
                    #1 + #3: :exception is schema-typed :any and :frame is an OPEN
                    optional key, so the schema alone accepts nil / a wrong throwable /
                    a dropped frame in the trace tags; the fixtures assert real values)"
            (is (identical? boom (:exception (:tags tev)))
                "trace :exception is the exact original throwable, not nil / a copy")
            (is (= fid (:frame (:tags tev)))
                "trace :frame carries the exact emitting frame keyword"))))
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
          ;; Record half always-on; trace half guarded (rf2-d2841).
          (is (some? rec))
          (is (identical? boom (:exception rec)))
          (when interop/debug-enabled?
            (is (valid-observation-on-change-failed-tags? (:tags tev))
                "the :disposed dev-trace tags satisfy the canonical schema")
            (is (= :disposed (:cause (:tags tev))))
            (testing "the :disposed dev-trace :tags likewise carry the EXACT throwable
                      and frame keyword, independently of the always-on record
                      (rf2-5iud0a false-greens #1 + #3)"
              (is (identical? boom (:exception (:tags tev)))
                  "trace :exception is the exact original throwable, not nil / a copy")
              (is (= fid (:frame (:tags tev)))
                  "trace :frame carries the exact emitting frame keyword"))))
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
        ;; THE DRIFT MATRIX RUNS OVER A SYNTHETIC BASELINE (rf2-d2841), not
        ;; over the emitted record, and that is a strengthening rather than a
        ;; concession. `valid-observation-on-change-failed-tags?` is a PURE
        ;; function of a tag map and the extracted schema; the drift claims
        ;; below are claims about THE SCHEMA, so they need A conforming map,
        ;; not THE emitted one. Driving them off the dev trace made every one
        ;; of them VACUOUS under the gate: `tags` is nil there, `(dissoc nil k)`
        ;; is nil, and "removing a required key must fail" was TRUE for all
        ;; eight keys for the wrong reason — a gate whose whole purpose is to
        ;; redden on schema drift was certifying drift-detection over an absent
        ;; record. Twelve vacuous passes came off this way (eight from the
        ;; doseq, the :cause and :category spoof rejections, and the two
        ;; exact-identity negatives).
        ;;
        ;; The synthetic baseline cannot itself drift silently: if the markdown
        ;; schema gains a required key or tightens a leaf type, this literal
        ;; stops validating and the FIRST assertion reddens. The complementary
        ;; direction — that the schema has not been WEAKENED — is pinned
        ;; structurally and posture-independently by
        ;; `canonical-schema-form-pins-the-strict-observation-shape`.
        (let [synthetic-boom {:synthetic true}
              synthetic {:category          :rf.error/observation-on-change-failed
                         :rf.sub/id         :obs/items
                         :rf.sub/query-v    [:obs/items]
                         :where             'rf/drain-pending-disposals!
                         :cause             :disposed
                         :exception         synthetic-boom
                         :exception-message "synthetic on-change boom"
                         :reason            "a synthetic conforming tag map"
                         :frame             fid}]
          (is (valid-observation-on-change-failed-tags? synthetic)
              "baseline: a conforming tag map validates (a required-key or leaf-type
               TIGHTENING in the markdown reddens here)")
          (testing "removing any required attribution key fails the gate"
            (doseq [k observation-tags-required-keys]
              (is (not (valid-observation-on-change-failed-tags? (dissoc synthetic k)))
                  (str "removing required key " k " must fail"))))
          (testing "a :cause off the :hmr/:disposed channel-discriminator fails"
            (is (not (valid-observation-on-change-failed-tags?
                       (assoc synthetic :cause :bogus)))))
          (testing "a spoofed :category fails"
            (is (not (valid-observation-on-change-failed-tags?
                       (assoc synthetic :category :rf.error/handler-exception)))))
          (testing "false-green #1 — :exception is schema-typed :any, so the SCHEMA
                    alone accepts a mutated :exception; the real fixtures instead
                    assert exact throwable identity, which catches nil / a substituted
                    throwable (rf2-5iud0a)"
            (is (valid-observation-on-change-failed-tags? (assoc synthetic :exception nil))
                "the schema (:any) still validates a nil'd :exception — schema alone is a false green")
            (is (valid-observation-on-change-failed-tags?
                  (assoc synthetic :exception (ex-info "different" {})))
                "the schema (:any) still validates a DIFFERENT throwable")
            (is (not (identical? synthetic-boom (:exception (assoc synthetic :exception nil))))
                "the exact-identity assertion the fixtures use catches the nil mutation")
            (is (not (identical? synthetic-boom
                                 (:exception (assoc synthetic :exception (ex-info "different" {})))))
                "…and catches a substituted throwable"))
          (testing "false-green #3 — a legitimately-absent OPTIONAL :frame stays valid
                    (the optional row must not silently become required) (rf2-5iud0a)"
            (is (valid-observation-on-change-failed-tags? (dissoc synthetic :frame))
                "an absent optional :frame remains valid (frame is optional in the schema)")))
        ;; The REAL emitted record is still bound to the same schema — that
        ;; half is a runtime claim about the dev trace and is guarded.
        (when interop/debug-enabled?
          (is (valid-observation-on-change-failed-tags? tags)
              "baseline: the real record validates")
          (is (identical? boom (:exception tags))
              "baseline: the real trace tags carry the exact throwable")
          (is (= fid (:frame tags)) "baseline: the real record emits the frame tag")))
      (obs/release! la))))

(defn- schema-entry-of
  [map-schema k]
  (first (filter #(= k (:key %)) (map-schema-entries map-schema))))

(defn- schema-of
  [map-schema k]
  (:schema (schema-entry-of map-schema k)))

(defn- schema-required?
  [map-schema k]
  (let [e (schema-entry-of map-schema k)]
    (and (some? e) (not (:optional? e)))))

(deftest canonical-schema-form-pins-the-strict-observation-shape
  ;; rf2-qyvyes — bind each drift class to the EXTRACTED schema form (structural, not
  ;; a substring scan of the markdown): weakening the markdown schema reddens here
  ;; deterministically, and removing the def is already a compile error (the extract
  ;; macro throws), so the schema and its consumers cannot drift silently.
  (let [s canonical-observation-schema-form]
    (testing "category / channel — pinned to the exact = literal (not weakened to :keyword)"
      (is (= [:= :rf.error/observation-on-change-failed] (schema-of s :category)))
      (is (schema-required? s :category)))
    (testing "enum→keyword drift — :cause stays the [:enum :hmr :disposed] discriminator"
      (is (= [:enum :hmr :disposed] (schema-of s :cause)))
      (is (schema-required? s :cause)))
    (testing "vector→any drift — :rf.sub/query-v stays [:vector :any]"
      (is (= [:vector :any] (schema-of s :rf.sub/query-v))))
    (testing "field-type drift — :where is a :symbol, :rf.sub/id a :keyword"
      (is (= :symbol (schema-of s :where)))
      (is (= :keyword (schema-of s :rf.sub/id))))
    (testing ":exception-message / :reason are :string, :exception :any"
      (is (= :string (schema-of s :exception-message)))
      (is (= :string (schema-of s :reason)))
      (is (= :any (schema-of s :exception))))
    (testing "required→optional drift — the required-key SET is exactly these eight"
      (is (= #{:category :rf.sub/id :rf.sub/query-v :where :cause
               :exception :exception-message :reason}
             (set (map :key (remove :optional? (map-schema-entries s)))))))
    (testing "false-green #3 — the OPTIONAL :frame row is pinned to
              [:frame {:optional true} :keyword]; because the map is open, deleting
              the row or weakening it to :any leaves real records + every OTHER shape
              assertion green even though runtime emits the frame tag, so pin it
              explicitly (rf2-5iud0a)"
      (let [e (schema-entry-of s :frame)]
        (is (some? e)
            "the :frame row is PRESENT (deleting it from the markdown reddens here)")
        (is (:optional? e) "the :frame row is optional")
        (is (= :keyword (:schema e))
            "the :frame row is :keyword, not weakened to :any")))))

#?(:clj
   (defn- dev-trace-backtick-keywords
     "Extract the EXACT backtick-delimited keyword tokens from a Spec 009 DEV-TRACE
     `:tags` fragment (rf2-5iud0a false-green #2): each `` `:kw` `` span parsed to a
     keyword, so `:exception` and `:exception-message` are DISTINCT tokens and a
     substring / prefix match or an un-backticked prose mention never counts. A
     focused extraction helper, NOT a general Markdown parser."
     [fragment]
     (->> (re-seq #"`([^`]+)`" fragment)
          (map second)
          (filter #(str/starts-with? % ":"))
          (map #(keyword (subs % 1)))
          set)))

#?(:clj
   (deftest spec-009-catalogue-lists-the-canonical-observation-tag-keys
     ;; rf2-qyvyes — bind the EXTRACTED schema's required keys to the Spec 009
     ;; catalogue row's DEV-TRACE :tags list, so a tag-key drift between the two
     ;; surfaces reddens. Structural on the schema side (the extracted form, not a
     ;; substring scan of Spec-Schemas.md — that surface is now pinned by the compile-
     ;; time extraction + canonical-schema-form-pins-the-strict-observation-shape).
     (let [required (->> (map-schema-entries canonical-observation-schema-form)
                         (remove :optional?)
                         (map :key))
           f        (let [nested (io/file "../../spec/009-Instrumentation.md")
                          legacy (io/file "../spec/009-Instrumentation.md")]
                      (if (.exists nested) nested legacy))
           row      (->> (str/split-lines (slurp f))
                         (filter #(str/includes?
                                    % "`:rf.error/observation-on-change-failed`"))
                         first)
           tags     (when (and row (str/index-of row "DEV-TRACE"))
                      (subs row (str/index-of row "DEV-TRACE")))]
       (is (some? row)
           "the Spec 009 catalogue must carry the observation-on-change-failed row")
       (is (some? tags)
           "the 009 row must carry a DEV-TRACE :tags list")
       (when tags
         ;; rf2-5iud0a false-green #2 — the prior check was a SUBSTRING scan, so
         ;; deleting `:exception` while keeping `:exception-message` still passed
         ;; (the shorter token is a prefix of the longer). Compare EXACT
         ;; backtick-delimited keyword tokens instead.
         (let [row-tokens (dev-trace-backtick-keywords tags)]
           (doseq [k required]
             (is (contains? row-tokens k)
                 (str "the Spec 009 DEV-TRACE :tags list must enumerate the canonical "
                      "schema key " k " as an exact backtick-delimited token")))
           (testing "exact-token extraction rejects a PREFIX — `:exception-message`
                     alone does NOT satisfy the required `:exception` token (the
                     false-green #2 this closes), and never matches un-backticked prose"
             (is (contains? row-tokens :exception))
             (is (contains? row-tokens :exception-message))
             (let [only-message (dev-trace-backtick-keywords
                                  "DEV-TRACE `:exception-message`, :exception in prose")]
               (is (not (contains? only-message :exception))
                   "a fragment with only `:exception-message` (plus prose `:exception`)
                    does NOT yield the `:exception` token")
               (is (contains? only-message :exception-message)))))))))
