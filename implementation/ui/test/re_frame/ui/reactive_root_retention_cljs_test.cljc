(ns re-frame.ui.reactive-root-retention-cljs-test
  "rf2-mc62sp — root-incarnation membership must not RETAIN ordinarily-
  unmounted ViewCells.

  An ordinary React reconciliation unmount (conditional subtree, route
  change, keyed-list eviction) runs only the effect cleanup → `disconnect!`,
  which deliberately never detaches (hide vs unmount are indistinguishable
  there — 03 §4). A STRONG `root-cells` set therefore pinned every such cell —
  with its retained committed site values — until the whole root tore down:
  unbounded production memory growth per UI churn, contradicting 03 §4's
  ':disconnected … if React does not reconnect the cell, the cell is garbage'.

  Membership is now WEAK (WeakHashMap keyset on the JVM; js/WeakRef set +
  FinalizationRegistry reaper on CLJS), preserving BOTH registry consumers:

    - handle/root/frame TEARDOWN DISCOVERY of hidden cells — a genuinely
      Activity-hidden cell is strongly reachable from React's retained fiber
      (`use-cell`'s useRef), so its weak entry lives exactly as long as
      Activity retention and `teardown-root!`/`teardown-frame!` still find it
      (rf2-vxgfnd.85);
    - the Xray EVIDENCE PROJECTION (rf2-vxgfnd.75), which reads the per-cell
      `:root` field (`reactive/cell-root`) — never this registry — and is
      structurally unaffected (its own suite pins that).

  The leak proof forces GC on the JVM (the graft-checked host where weak
  clearing is deterministic under System/gc); the cross-host fixtures pin the
  survive-hide / deterministic-teardown semantics of the weak rewrite on both
  hosts. `.cljc` ending `-cljs-test` runs on node (`test:cljs`) AND JVM
  (`clojure -M:test`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.test-support         :as test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.ui.reactive          :as reactive]
            #?(:cljs [re-frame.ui.client   :as client])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- render+commit! [cell fid queries]
  (let [[_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn [] (mapv (fn [i q]
                                          (reactive/sub-read [:ret/site i] q))
                                        (range) queries))))]
    (reactive/commit! cell capture))
  cell)

(defn- mount!
  "Graft analogue of a real mount UNDER a root: mint a cell, attach it to
  `incarnation`, commit it connected under `fid` observing `queries`."
  [vid incarnation fid queries]
  (let [cell (reactive/make-cell vid)]
    (reactive/attach-root! cell incarnation)
    (render+commit! cell fid queries)))

#?(:cljs
   (do
     (def ^:private host-weak-ref
       (aget js/globalThis "WeakRef"))

     (def ^:private host-finalization-registry
       (aget js/globalThis "FinalizationRegistry"))

     (defn- install-global-value! [property value]
       (js/Object.defineProperty
        js/globalThis property
        #js {:value value :configurable true :writable true}))

     (defn- restore-global-property! [property descriptor]
       (if descriptor
         (js/Object.defineProperty js/globalThis property descriptor)
         (js/Reflect.deleteProperty js/globalThis property)))

     (defn- with-platform-capabilities!
       "Run `f` with deterministic JavaScript capability globals and a fresh
       one-shot substrate probe; restore the host exactly afterwards."
       [weak-ref finalization-registry f]
       (let [weak-desc (js/Object.getOwnPropertyDescriptor js/globalThis "WeakRef")
             final-desc (js/Object.getOwnPropertyDescriptor
                         js/globalThis "FinalizationRegistry")]
         (try
           (install-global-value! "WeakRef" weak-ref)
           (install-global-value! "FinalizationRegistry" finalization-registry)
           (reactive/reset-scheduler!)
           (client/reset-live-roots!)
           (f)
           (finally
             (client/reset-live-roots!)
             (restore-global-property! "WeakRef" weak-desc)
             (restore-global-property! "FinalizationRegistry" final-desc)
             (reactive/reset-scheduler!)))))

     (defn- captured-throw [f]
       (try (f) nil
            (catch :default e e)))

     (defn- controlled-weak-ref-constructor
       "A constructable deterministic WeakRef model. Each produced ref exposes
       `clearForTest`, which models collection without relying on host GC."
       [created deref-calls]
       (js/Proxy.
        host-weak-ref
        #js {:construct
             (fn [_ args _]
               (let [target* (volatile! (aget args 0))
                     ref     (js-obj)]
                 (aset ref "deref"
                       (fn []
                         (swap! deref-calls inc)
                         @target*))
                 (aset ref "clearForTest" #(vreset! target* nil))
                 (swap! created conj ref)
                 ref))}))))

#?(:cljs
   (defn- controlled-finalization-registry
     "A constructable FinalizationRegistry MODEL whose queued finalizers fire
     only when the test invokes them explicitly — modelling a DELAYED finalizer
     (rf2-vxgfnd.169). `register` records `{:held :token :callback}` into
     `pending`; `unregister` removes by token and returns true (so the substrate
     probe accepts it). The probe registers+unregisters a keyword-held sentinel,
     so real member finalizers are exactly the map-held entries."
     [pending]
     (js/Proxy.
      host-finalization-registry
      #js {:construct
           (fn [_ args _]
             (let [callback (aget args 0)
                   reg      (js-obj)]
               (aset reg "register"
                     (fn [_target held token]
                       (swap! pending conj {:held held :token token
                                            :callback callback})))
               (aset reg "unregister"
                     (fn [token]
                       (swap! pending
                              (fn [ps] (vec (remove #(identical? (:token %) token) ps))))
                       true))
               reg))})))

;; ===========================================================================
;; rf2-vxgfnd.170 — the three-arm JavaScript capability matrix
;; ===========================================================================

#?(:cljs
   (deftest weakref-and-finalizationregistry-probe-once-and-work
     (is (= "function" (goog/typeOf host-weak-ref))
         "the supported-host fixture requires the JavaScript WeakRef primitive")
     (is (= "function" (goog/typeOf host-finalization-registry))
         "the present/present arm requires the optional accelerator to exist")
     (with-platform-capabilities!
       host-weak-ref host-finalization-registry
       (fn []
         (let [inc-a (reactive/make-root-incarnation)
               cell-a (reactive/make-cell ::present-a)]
           (reactive/attach-root! cell-a inc-a)
           (is (= 1 (reactive/root-cell-count inc-a)))
           ;; The constructors are captured by the first admission. Removing
           ;; the globals afterwards must not cause a second probe per cell.
           (install-global-value! "WeakRef" js/undefined)
           (install-global-value! "FinalizationRegistry" js/undefined)
           (let [inc-b (reactive/make-root-incarnation)
                 cell-b (reactive/make-cell ::present-b)]
             (reactive/attach-root! cell-b inc-b)
             (is (= 1 (reactive/root-cell-count inc-b))
                 "a second attach uses the one cached capability admission")
             (reactive/teardown! cell-b))
           (reactive/teardown! cell-a)
           (is (= 0 (reactive/root-cell-count))))))))

#?(:cljs
   (deftest finalizationregistry-is-optional-with-synchronous-compaction
     (with-platform-capabilities!
       host-weak-ref js/undefined
       (fn []
         (let [incarnation (reactive/make-root-incarnation)
               cell (reactive/make-cell ::no-finalizer)]
           (reactive/attach-root! cell incarnation)
           (is (= 1 (reactive/root-cell-count incarnation)))
           ;; Deterministic teardown is the synchronous boundedness path; weak
           ;; scans also compact collected husks when no reaper exists.
           (reactive/teardown! cell)
           (is (= 0 (reactive/root-cell-count incarnation)))
           (is (= 0 (reactive/root-cell-count))))))))

#?(:cljs
   (deftest missing-weakref-fails-before-cell-or-root-ownership-mutation
     (with-platform-capabilities!
       js/undefined host-finalization-registry
       (fn []
         (let [incarnation (reactive/make-root-incarnation)
               cell (reactive/make-cell ::unsupported)
               attach-error (captured-throw
                             #(reactive/attach-root! cell incarnation))]
           (is (= {:rf.error/id :rf.error/ui-platform-incompatible
                   :where 're-frame.ui.reactive/attach-root!
                   :recovery :use-a-weakref-capable-javascript-runtime
                   :platform :javascript
                   :capability :js/WeakRef}
                  (select-keys
                   (ex-data attach-error)
                   [:rf.error/id :where :recovery :platform :capability])))
           (is (= :fresh (reactive/lifecycle cell))
               "the incompatible attach does not mutate ViewCell lifecycle")
           (is (nil? (reactive/cell-root cell))
               "the incompatible attach does not write root ownership")
           (is (= 0 (reactive/root-cell-count))
               "the incompatible attach does not install a registry entry"))
         (let [root-error
               (captured-throw
                #(client/create-root*
                  {:root-id :unsupported/root :provenance :authored}
                  nil nil))]
           (is (= {:rf.error/id :rf.error/ui-platform-incompatible
                   :where 're-frame.ui/create-root
                   :recovery :use-a-weakref-capable-javascript-runtime
                   :platform :javascript
                   :capability :js/WeakRef}
                  (select-keys
                   (ex-data root-error)
                   [:rf.error/id :where :recovery :platform :capability])))
           (is (empty? (client/live-root-ids))
               "root admission fails before container/React/registry mutation"))))))

#?(:cljs
   (deftest unusable-weakref-shapes-fail-typed-before-any-mutation
     (let [cases
           [[:callable-nonconstructable
             (js/eval "(target) => ({deref: () => target})")]
            [:throwing-constructor
             (js/Function. "throw new Error('weakref constructor failed')")]
            [:missing-deref
             (js/Function. "target" "return {}")]
            [:malformed-deref
             (js/Function. "target" "return {deref: 7}")]
            [:throwing-deref
             (js/Function.
              "target"
              "return {deref: function(){throw new Error('deref failed')}}")]
            [:wrong-referent
             (js/Function.
              "target"
              "return {deref: function(){return {wrong: true}}}")]]]
       (doseq [[label weak-ref] cases]
         (testing (name label)
           (with-platform-capabilities!
             weak-ref host-finalization-registry
             (fn []
               (let [incarnation (reactive/make-root-incarnation)
                     cell        (reactive/make-cell label)
                     attach-error
                     (captured-throw
                      #(reactive/attach-root! cell incarnation))]
                 (is (= {:rf.error/id :rf.error/ui-platform-incompatible
                         :where 're-frame.ui.reactive/attach-root!
                         :recovery :use-a-weakref-capable-javascript-runtime
                         :platform :javascript
                         :capability :js/WeakRef}
                        (select-keys
                         (ex-data attach-error)
                         [:rf.error/id :where :recovery :platform :capability])))
                 (is (= :fresh (reactive/lifecycle cell)))
                 (is (nil? (reactive/cell-root cell)))
                 (is (zero? (reactive/root-cell-count)))
                 (let [root-error
                       (captured-throw
                        #(client/create-root*
                          {:root-id :unsupported/unusable :provenance :authored}
                          nil nil))]
                   (is (= :rf.error/ui-platform-incompatible
                          (:rf.error/id (ex-data root-error))))
                   (is (= 're-frame.ui/create-root
                          (:where (ex-data root-error))))
                   (is (empty? (client/live-root-ids))))))))))))

#?(:cljs
   (deftest unusable-finalizationregistry-is-treated-as-absent
     (let [cases
           [[:throwing-constructor
             (js/Function.
              "callback"
              "throw new Error('finalization registry constructor failed')")]
            [:missing-methods
             (js/Function. "callback" "return {}")]
            [:throwing-register
             (js/Function.
              "callback"
              (str "return {register: function(){throw new Error('register failed')},"
                   "unregister: function(){return true}}"))]
            [:throwing-unregister
             (js/Function.
              "callback"
              (str "return {register: function(){},"
                   "unregister: function(){throw new Error('unregister failed')}}"))]]]
       (doseq [[label finalization-registry] cases]
         (testing (name label)
           (with-platform-capabilities!
             host-weak-ref finalization-registry
             (fn []
               (let [incarnation (reactive/make-root-incarnation)
                     cell        (reactive/make-cell label)]
                 (is (identical? cell
                                 (reactive/attach-root! cell incarnation)))
                 (is (= 1 (reactive/root-cell-count incarnation)))
                 (reactive/teardown! cell)
                 (is (zero? (reactive/root-cell-count)))))))))))

#?(:cljs
   (deftest no-finalizer-attach-compacts-reconciliation-churn-before-enrolment
     (let [created     (atom [])
           deref-calls (atom 0)
           weak-ref    (controlled-weak-ref-constructor created deref-calls)]
       (with-platform-capabilities!
         weak-ref js/undefined
         (fn []
           ;; Seat the one-shot capability probe, then observe only membership
           ;; refs made by the attach path below.
           (reactive/ensure-platform-compatible! 'retention/churn)
           (reset! created [])
           (reset! deref-calls 0)
           (let [incarnation (reactive/make-root-incarnation)
                 hidden      (reactive/make-cell ::hidden)
                 departed-a  (reactive/make-cell ::departed-a)]
             (reactive/attach-root! hidden incarnation)
             (reactive/disconnect! hidden)
             (reactive/attach-root! departed-a incarnation)
             (reactive/disconnect! departed-a)
             (let [replacement
                   (loop [i        0
                          old-ref  (nth @created 1)]
                     ;; Model ordinary reconciliation departure: React dropped
                     ;; the fiber and the weak member cleared, but no
                     ;; deterministic teardown/root/tool scan ran.
                     (.clearForTest old-ref)
                     (reset! deref-calls 0)
                     (let [next-cell (reactive/make-cell
                                      (keyword "ret" (str "replacement-" i)))]
                       (reactive/attach-root! next-cell incarnation)
                       (is (= 2 @deref-calls)
                           "each no-reaper attach scans the live hidden member
                            and compacts exactly one cleared predecessor husk")
                       (if (< i 15)
                         (do
                           (reactive/disconnect! next-cell)
                           (recur (inc i) (peek @created)))
                         next-cell)))]
               (is (= 2 (reactive/root-cell-count incarnation))
                   "repeated churn stays bounded to hidden + current occurrence")
               (is (identical? incarnation (reactive/cell-root hidden)))
               (is (identical? incarnation (reactive/cell-root replacement)))
               ;; Replaying a stale cleared occurrence cannot remove either
               ;; exact live member.
               (.clearForTest (nth @created 1))
               (is (= 2 (reactive/root-cell-count incarnation)))
               (reactive/teardown! departed-a)
               (reactive/teardown! replacement)
               (reactive/teardown! hidden)
               (is (zero? (reactive/root-cell-count))))))))))

#?(:cljs
   (deftest finalizer-capable-attach-does-not-add-a-membership-scan
     (let [created     (atom [])
           deref-calls (atom 0)
           weak-ref    (controlled-weak-ref-constructor created deref-calls)]
       (with-platform-capabilities!
         weak-ref host-finalization-registry
         (fn []
           (reactive/ensure-platform-compatible! 'retention/reaper)
           (reset! created [])
           (reset! deref-calls 0)
           (let [incarnation (reactive/make-root-incarnation)
                 departed    (reactive/make-cell ::reaper-departed)
                 replacement (reactive/make-cell ::reaper-replacement)]
             (reactive/attach-root! departed incarnation)
             (reactive/disconnect! departed)
             (.clearForTest (first @created))
             (reset! deref-calls 0)
             (reactive/attach-root! replacement incarnation)
             (is (zero? @deref-calls)
                 "the FinalizationRegistry-capable production arm keeps attach O(1)")
             (reactive/teardown! departed)
             (reactive/teardown! replacement)))))))

;; ===========================================================================
;; Weak membership keeps the rf2-vxgfnd.85 semantics: survives a hide,
;; deterministic departure on teardown!, entry dropped with the last member
;; ===========================================================================

(deftest membership-survives-hide-and-drops-deterministically-on-teardown
  (rf/reg-sub :ret/a (fn [db _] (:a db)))
  (let [fid         (make-frame! :ret/frame {:a 1})
        incarnation (reactive/make-root-incarnation)
        base        (reactive/root-cell-count)
        cell        (mount! ::v incarnation fid [[:ret/a]])]
    (testing "attached: one live member under the incarnation"
      (is (= 1 (reactive/root-cell-count incarnation)))
      (is (identical? incarnation (reactive/cell-root cell))
          "the projection's per-cell :root read is untouched by weak membership"))
    (testing "an Activity hide does NOT drop membership (the whole point)"
      (reactive/disconnect! cell)
      (is (= :disconnected (reactive/lifecycle cell)))
      (is (= 1 (reactive/root-cell-count incarnation))
          "the hidden cell — strongly retained by its fiber/test handle —
           stays discoverable through the weak registry"))
    (testing "final teardown! leaves deterministically (the fast path)"
      (reactive/teardown! cell)
      (is (= 0 (reactive/root-cell-count incarnation)))
      (is (= base (reactive/root-cell-count))
          "the incarnation entry dropped with its last member (AC5)"))))

(deftest hidden-cells-stay-discoverable-for-root-teardown
  (rf/reg-sub :ret/a (fn [db _] (:a db)))
  (let [fid         (make-frame! :ret/frame {:a 1})
        incarnation (reactive/make-root-incarnation)
        hidden      (mount! ::hidden incarnation fid [[:ret/a]])]
    ;; Hidden BEFORE any teardown window exists — the .85 shape. The test
    ;; handle stands in for React's retained fiber (Activity keeps the fiber,
    ;; so a real hidden cell is strongly reachable exactly like this).
    (reactive/disconnect! hidden)
    (is (= :disconnected (reactive/lifecycle hidden)))
    (testing "root teardown still reaps the hidden cell through weak membership"
      (reactive/teardown-root! incarnation (fn [] nil))
      (is (= :dead (reactive/lifecycle hidden))
          "handle-teardown discovery of hidden-but-alive cells is preserved")
      (is (= 0 (reactive/root-cell-count incarnation))))))

;; ===========================================================================
;; The leak fixture (JVM — deterministic weak clearing under System/gc):
;; ordinary reconciliation unmounts must leave COLLECTABLE cells, while a
;; hidden sibling under the same root stays retained + reapable
;; ===========================================================================

#?(:clj
   (defn- gc-until
     "Hint GC (bounded retries) until `pred` returns true; returns its final
     value. Weakly-reachable objects clear on the first full GC in practice."
     [pred]
     (loop [i 0]
       (cond
         (pred)     true
         (>= i 100) (pred)
         :else      (do (System/gc)
                        (Thread/sleep 10)
                        (recur (inc i)))))))

#?(:clj
   (deftest reconciliation-unmounted-cells-are-garbage-not-root-retained
     (rf/reg-sub :ret/a (fn [db _] (:a db)))
     (let [fid         (make-frame! :ret/frame {:a 1})
           incarnation (reactive/make-root-incarnation)
           n           32
           ;; one genuinely hidden sibling under the SAME root — must survive
           hidden      (doto (mount! ::hidden incarnation fid [[:ret/a]])
                         (reactive/disconnect!))
           ;; churn: mount + ordinary reconciliation unmount, keyed-list
           ;; style. Hold each churned cell only WEAKLY once its cycle ends —
           ;; React drops the fiber, so nothing else references it (its
           ;; handles were already released at disconnect!).
           refs        (mapv (fn [i]
                               (let [cell (mount! (keyword "ret" (str "row-" i))
                                                  incarnation fid [[:ret/a]])]
                                 (reactive/disconnect! cell)
                                 (java.lang.ref.WeakReference. cell)))
                             (range n))]
       (is (= (+ n 1) (reactive/root-cell-count incarnation))
           "precondition: every churned cell is (weakly) enrolled pre-GC")
       (testing "the churned population is GARBAGE — collectable, not pinned
                 by root ownership (rf2-mc62sp; red under strong membership)"
         (is (true? (gc-until #(every? (fn [^java.lang.ref.WeakReference r]
                                         (nil? (.get r)))
                                       refs)))
             "every reconciliation-unmounted cell was collected — root-cells
              no longer strongly retains the ordinary-unmount population")
         (is (= 1 (reactive/root-cell-count incarnation))
             "membership returned to baseline: exactly the hidden sibling"))
       (testing "…while the hidden sibling stayed reapable for handle teardown"
         (is (= :disconnected (reactive/lifecycle hidden)))
         (reactive/teardown-root! incarnation (fn [] nil))
         (is (= :dead (reactive/lifecycle hidden)))
         (is (= 0 (reactive/root-cell-count incarnation)))))))

;; ===========================================================================
;; rf2-vxgfnd.169 — the ALL-MEMBERS-COLLECTED path: after the LAST member of an
;; incarnation collects, the now-empty OUTER registry entry must be pruned too,
;; on BOTH hosts. The hidden-sibling fixture above keeps one member alive, so it
;; only ever exercises last-EXPLICIT-departure — it masks this path.
;; ===========================================================================

#?(:clj
   (deftest all-members-collected-drops-the-empty-incarnation-entry
     ;; Forced-GC JVM coverage (the deterministic weak-clearing host): an
     ;; incarnation whose SOLE cell is collected must leave the outer registry,
     ;; and the global tracked count must return to baseline (rf2-vxgfnd.169).
     (rf/reg-sub :ret/a (fn [db _] (:a db)))
     (let [fid         (make-frame! :ret/frame {:a 1})
           base        (reactive/root-cell-count)
           incarnation (reactive/make-root-incarnation)
           ;; the SOLE cell, held only WEAKLY — no hidden sibling, no strong ref.
           ref         (let [cell (mount! ::solo incarnation fid [[:ret/a]])]
                         (reactive/disconnect! cell)
                         (java.lang.ref.WeakReference. cell))]
       (is (= (inc base) (reactive/root-cell-count))
           "precondition: the incarnation is tracked pre-GC")
       (is (= 1 (reactive/root-cell-count incarnation)))
       (testing "after the LAST cell collects, the empty incarnation entry is pruned"
         (is (true? (gc-until #(nil? (.get ^java.lang.ref.WeakReference ref))))
             "the sole member was collected — nothing strong retains it")
         (is (= base (reactive/root-cell-count))
             "the 0-arg GLOBAL tracked count returned to baseline — the now-empty
              outer entry was pruned, not retained forever. RED pre-fix: the JVM
              `weak-live` / `root-cell-count[]` never removed the outer entry")
         (is (= 0 (reactive/root-cell-count incarnation))
             "…and the per-incarnation live count is zero")))))

#?(:cljs
   (deftest all-members-collected-drops-empty-entry-without-a-reaper
     ;; Compiled-CLJS coverage with FinalizationRegistry UNAVAILABLE: the
     ;; opportunistic synchronous scan must compact the cleared husk AND drop the
     ;; now-empty incarnation entry (rf2-vxgfnd.169).
     (let [created     (atom [])
           deref-calls (atom 0)
           weak-ref    (controlled-weak-ref-constructor created deref-calls)]
       (with-platform-capabilities!
         weak-ref js/undefined                       ;; FinalizationRegistry ABSENT
         (fn []
           (reactive/ensure-platform-compatible! 'retention/prune)
           (reset! created [])
           (let [base        (reactive/root-cell-count)
                 incarnation (reactive/make-root-incarnation)
                 cell        (reactive/make-cell ::solo)]
             (reactive/attach-root! cell incarnation)
             (is (= (inc base) (reactive/root-cell-count)))
             (is (= 1 (reactive/root-cell-count incarnation)))
             ;; Model ordinary reconciliation collection: the sole member's weak
             ;; ref clears, but NO deterministic teardown and NO reaper ran.
             (.clearForTest (first @created))
             (testing "opportunistic compaction drops the husk AND the empty entry"
               (is (= base (reactive/root-cell-count))
                   "the 0-arg global scan compacted the now-empty incarnation entry")
               (is (= 0 (reactive/root-cell-count incarnation))))))))))

#?(:cljs
   (deftest explicit-teardown-of-a-member-empty-incarnation-removes-it
     ;; The entry is present but its membership is already empty (collected):
     ;; explicit root teardown must remove it deterministically (rf2-vxgfnd.169).
     (let [created     (atom [])
           deref-calls (atom 0)
           weak-ref    (controlled-weak-ref-constructor created deref-calls)]
       (with-platform-capabilities!
         weak-ref js/undefined
         (fn []
           (reactive/ensure-platform-compatible! 'retention/empty-teardown)
           (reset! created [])
           (let [base        (reactive/root-cell-count)
                 incarnation (reactive/make-root-incarnation)
                 cell        (reactive/make-cell ::solo)]
             (reactive/attach-root! cell incarnation)
             ;; the member collects, leaving the entry PRESENT but member-empty
             (.clearForTest (first @created))
             (testing "explicit teardown of an already member-empty incarnation removes it"
               (reactive/teardown-root! incarnation (fn [] nil))
               (is (= base (reactive/root-cell-count)))
               (is (= 0 (reactive/root-cell-count incarnation))))))))))

#?(:cljs
   (deftest delayed-finalizer-after-synchronous-removal-is-harmless
     ;; A late FinalizationRegistry callback that fires AFTER synchronous
     ;; compaction already removed the incarnation must not delete a replacement
     ;; entry / new incarnation (rf2-vxgfnd.169).
     (let [created     (atom [])
           deref-calls (atom 0)
           pending     (atom [])
           weak-ref    (controlled-weak-ref-constructor created deref-calls)
           final-reg   (controlled-finalization-registry pending)]
       (with-platform-capabilities!
         weak-ref final-reg
         (fn []
           (reactive/ensure-platform-compatible! 'retention/delayed)
           (reset! created [])
           (reset! pending [])
           (let [inc-x  (reactive/make-root-incarnation)
                 base   (reactive/root-cell-count)
                 cell-a (reactive/make-cell ::a)]
             (reactive/attach-root! cell-a inc-x)
             (is (= 1 (reactive/root-cell-count inc-x)))
             (let [fin (first (filter #(map? (:held %)) @pending))]
               (is (some? fin) "the reaper registered A's collection finalizer")
               ;; A collects; a SYNCHRONOUS scan removes X's now-empty entry NOW,
               ;; BEFORE the (delayed) finalizer runs.
               (.clearForTest (first @created))
               (is (= base (reactive/root-cell-count))
                   "synchronous compaction already dropped the empty incarnation")
               ;; A NEW cell B re-attaches to X, re-creating the entry with a
               ;; DIFFERENT membership set.
               (let [cell-b (reactive/make-cell ::b)]
                 (reactive/attach-root! cell-b inc-x)
                 (is (= 1 (reactive/root-cell-count inc-x)))
                 (testing "the DELAYED finalizer for A is harmless — identity-guarded"
                   ((:callback fin) (:held fin))
                   (is (= 1 (reactive/root-cell-count inc-x))
                       "the late finalizer did NOT remove the replacement entry")
                   (is (identical? inc-x (reactive/cell-root cell-b))
                       "…nor the new incarnation's live member"))
                 (reactive/teardown! cell-b)
                 (is (= base (reactive/root-cell-count)))))))))))
