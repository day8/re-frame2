(ns re-frame.ui.resource-lease-reconcile-cljs-test
  "rf2-vxgfnd.106 — headless proof of the resource ownership family inside
  the exact ViewCell capture/lifecycle.  Runs on node and JVM without loading
  the optional Resources artefact; registrar-shaped feature rows and the
  router enqueue seam are sufficient to prove planning and ownership."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.features :as features]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.resource-lease-owner :as lease-owner]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.reactive :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(def ^:private fid :rf/default)

(defn- register-feature! [& resources]
  (doseq [resource resources]
    (registrar/register! :resource resource {:rf/resource {}})))

(defn- capture-leases
  ([cell sites] (capture-leases cell fid sites))
  ([cell frame-id sites]
   (reactive/enable-resource-lifecycle! cell)
   (rf/with-frame frame-id
     (reactive/with-capture
      cell
      (fn []
        (doseq [[sid descriptor] sites]
          (reactive/lease-site sid descriptor))
        :element)))))

(defn- commit-leases! [cell sites]
  (let [[_ capture] (capture-leases cell sites)]
    (reactive/commit-resources! cell capture)
    capture))

(defn- throws-id [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- dispatch-standin
  "Redefine router/dispatch! without breaking compiler-optimized CLJS arity
  calls. A single-body CLJS fn has no invoke$arity$2 property."
  [f]
  (fn capture
    ([event] (capture event nil))
    ([event opts] (f event opts))))

(defn- collecting-dispatch [calls]
  (dispatch-standin (fn [event opts]
                      (swap! calls conj [event opts]))))

(deftest lease-free-capture-and-cell-lifecycle-omit-resource-state
  (let [cell (reactive/make-cell ::lease-free)
        [_ cap] (reactive/with-capture cell (constantly :plain))
        lease-free-capture? #(and (not (contains? % :resource-order))
                                  (not (contains? % :resource-by-site)))]
    (is (lease-free-capture? cap))
    (is (not (lease-free-capture? (assoc cap :resource-order [])))
        "mutation tooth: adding resource capture state makes the shape fail")
    (is (false? (reactive/resource-state-installed? cell)))
    (reactive/commit! cell cap)
    (is (false? (reactive/resource-state-installed? cell)))
    (reactive/disconnect! cell)
    (is (false? (reactive/resource-state-installed? cell)))
    (reactive/teardown! cell)
    (is (false? (reactive/resource-state-installed? cell)))))

(deftest resource-lease-frame-does-not-expand-observation-flush-scope
  (let [resource-frame :lease/flush-scope
        _              (live-frame/make-frame {:id resource-frame})
        cell           (reactive/make-cell ::flush-scope)]
    (try
      (rf/reg-sub ::flush-value (fn [db _] (:value db)))
      (frame/replace-app-db! fid {:value 1})
      (reactive/enable-resource-lifecycle! cell)
      (let [[_ cap]
            (reactive/with-capture
             cell
             (fn []
               (rf/with-frame fid
                 (reactive/sub-read ::sub [::flush-value]))
               (rf/with-frame resource-frame
                 (reactive/lease-site ::lease {:resource :feed/items}))
               :element))]
        (reactive/commit-resources! cell cap))
      (reactive/mark-dirty! cell)
      (let [before (reactive/revision cell)]
        (is (zero? (reactive/flush-frame! resource-frame))
            "a B resource lease is liveness, not B read observation")
        (is (= before (reactive/revision cell))
            "flushing resource-only B leaves the A-dirty cell pending")
        (is (= 1 (reactive/flush-frame! fid)))
        (is (= (inc before) (reactive/revision cell))
            "flushing observed subscription frame A advances exactly once"))
      (finally
        (frame/destroy-frame! resource-frame)))))

(deftest render-and-abandon-own-nothing
  (register-feature! :feed/items)
  (let [cell  (reactive/make-cell ::abandoned)
        calls (atom [])
        mints (atom 0)]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)
                  lease-owner/mint! (fn [] [:lease (swap! mints inc)])]
      (dotimes [_ 100]
        (capture-leases cell [[::site {:resource :feed/items}]]))
      (is (zero? @mints))
      (is (empty? @calls))
      (is (empty? (reactive/resource-reservations cell)))
      (is (empty? (reactive/resource-held cell))))))

(deftest site-id-loss-mutation-fails-before-commit-or-owner-mint
  (let [cell  (reactive/make-cell ::site-id-loss)
        mints (atom 0)]
    (with-redefs [lease-owner/mint! (fn [] [:lease (swap! mints inc)])]
      (is (= :rf.error/ui-tree-malformed
             (throws-id
              #(rf/with-frame fid
                 (reactive/with-capture
                  cell
                  (fn []
                    ;; Models a compiler mutation that aliases two lexical
                    ;; declarations onto one sid.
                    (reactive/lease-site ::lost {:resource :feed/items})
                    (reactive/lease-site ::lost {:resource :feed/items})))))))
      (is (zero? @mints))
      (is (empty? (reactive/resource-reservations cell))))))

(deftest equal-sites-own-independently-and-rf-equal-rerender-does-not-churn
  (register-feature! :feed/items)
  (let [cell  (reactive/make-cell ::equal-sites)
        calls (atom [])
        mints (atom 0)]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)
                  lease-owner/mint! (fn [] [:lease (swap! mints inc)])]
      (let [cap (commit-leases!
                 cell [[::a {:resource :feed/items}]
                       [::b {:resource :feed/items}]])]
        (reactive/reconcile-resource-leases! cell cap))
      (let [ensures (mapv first @calls)
            owners  (mapv (comp :owner second) ensures)
            evidence (reactive/resource-lease-evidence cell)]
        (is (= [:rf.resource/ensure :rf.resource/ensure]
               (mapv first ensures)))
        (is (= 2 (count (set owners)))
            "equal descriptors at distinct lexical sites have distinct owners")
        (is (every? #(not (contains? (second %) :params)) ensures)
            "omitted params stay omitted in every ensure payload")
        (is (= 2 (count evidence)))
        (is (every? #(= #{:rf.ui/view :rf.ui/commit :rf.ui/site :frame :owner}
                        (set (keys %)))
                    evidence)
            "Xray join evidence is bounded identity, never descriptor data"))
      (reset! calls [])
      (let [cap (commit-leases!
                 cell [[::a (into {} {:resource :feed/items})]
                       [::b (into {} {:resource :feed/items})]])]
        (reactive/reconcile-resource-leases! cell cap))
      (is (= 2 @mints) "rf=-equal same-site descriptors retain owners")
      (is (empty? @calls) "equal re-render queues neither ensure nor release"))))

(deftest retarget-queues-all-ensures-before-releases-and-preserves-explicit-nil
  (register-feature! :feed/items :feed/new)
  (let [cell  (reactive/make-cell ::retarget)
        calls (atom [])
        mints (atom 0)]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)
                  lease-owner/mint! (fn [] [:lease (swap! mints inc)])]
      (let [cap (commit-leases!
                 cell [[::a {:resource :feed/items}]
                       [::b {:resource :feed/items}]])]
        (reactive/reconcile-resource-leases! cell cap))
      (let [owners-before (set (keys (reactive/resource-held cell)))]
        (reset! calls [])
        (let [cap (commit-leases!
                   cell [[::a {:resource :feed/new :scope nil :params nil}]])]
          (reactive/reconcile-resource-leases! cell cap))
        (let [events (mapv first @calls)
              ensure (first events)
              payload (second ensure)]
          (is (= [:rf.resource/ensure
                  :rf.resource/release-owner
                  :rf.resource/release-owner]
                 (mapv first events)))
          (is (contains? payload :scope))
          (is (contains? payload :params))
          (is (nil? (:scope payload)))
          (is (nil? (:params payload)))
          (is (not (contains? owners-before (:owner payload)))
              "retarget mints a fresh owner"))))))

(deftest disconnect-releases-held-but-reveal-reuses-reservation
  (register-feature! :feed/items)
  (let [cell  (reactive/make-cell ::activity)
        calls (atom [])
        mints (atom 0)]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)
                  lease-owner/mint! (fn [] [:lease (swap! mints inc)])]
      (let [cap (commit-leases! cell [[::a {:resource :feed/items}]])]
        (reactive/reconcile-resource-leases! cell cap))
      (let [owner (-> (reactive/resource-reservations cell) vals first :owner)]
        (reset! calls [])
        (reactive/disconnect-resources! cell)
        (is (= [:rf.resource/release-owner]
               (mapv (comp first first) @calls)))
        (is (empty? (reactive/resource-held cell)))
        (is (= owner (-> (reactive/resource-reservations cell) vals first :owner)))
        (reset! calls [])
        (let [cap (commit-leases! cell [[::a {:resource :feed/items}]])]
          (reactive/reconcile-resource-leases! cell cap))
        (is (= [:rf.resource/ensure]
               (mapv (comp first first) @calls)))
        (is (= owner (-> @calls first first second :owner)))
        (is (= 1 @mints) "StrictMode/Activity replay reuses its reservation")))))

(deftest missing-optional-feature-fails-before-owner-mint-or-dispatch
  (registrar/register! :resource :feed/items {:rf/resource {}})
  (let [cell  (reactive/make-cell ::missing-feature)
        calls (atom [])
        mints (atom 0)
        cap   (commit-leases! cell [[::a {:resource :feed/items}]])]
    (with-redefs [features/require-feature!
                  (fn [_]
                    (throw (ex-info "missing" {:rf.error/id
                                                :rf.error/feature-not-loaded})))
                  router/dispatch! (collecting-dispatch calls)
                  lease-owner/mint! (fn [] [:lease (swap! mints inc)])]
      (is (= :rf.error/feature-not-loaded
             (throws-id #(reactive/reconcile-resource-leases! cell cap))))
      (is (zero? @mints))
      (is (empty? @calls))
      (is (empty? (reactive/resource-reservations cell)))
      (is (empty? (reactive/resource-held cell))))))

(deftest full-plan-prevalidation-rejects-one-missing-resource-before-any-work
  (register-feature! :feed/items)
  (let [cell  (reactive/make-cell ::missing-resource)
        calls (atom [])
        mints (atom 0)
        cap   (commit-leases!
               cell [[::valid {:resource :feed/items}]
                     [::bad {:resource :feed/not-registered}]])]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)
                  lease-owner/mint! (fn [] [:lease (swap! mints inc)])]
      (is (= :rf.error/resource-not-registered
             (throws-id #(reactive/reconcile-resource-leases! cell cap))))
      (is (zero? @mints)
          "a valid earlier site does not mint before the complete plan validates")
      (is (empty? @calls))
      (is (empty? (reactive/resource-reservations cell)))
      (is (empty? (reactive/resource-held cell))))))

(deftest ensure-enqueue-escape-compensates-exactly-the-attempted-prefix
  (register-feature! :feed/items)
  (let [cell (reactive/make-cell ::ensure-escape)
        calls (atom [])
        ensure-attempt (atom 0)
        cap (commit-leases!
             cell [[::a {:resource :feed/items}]
                   [::b {:resource :feed/items}]
                   [::c {:resource :feed/items}]])]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch!
                  (dispatch-standin
                   (fn [event opts]
                     (swap! calls conj [event opts])
                     (when (= :rf.resource/ensure (first event))
                       (when (= 2 (swap! ensure-attempt inc))
                         (throw (ex-info "injected ensure enqueue escape" {}))))))]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core/ExceptionInfo)
                   (reactive/reconcile-resource-leases! cell cap)))
      (is (= [:rf.resource/ensure :rf.resource/ensure
              :rf.resource/release-owner :rf.resource/release-owner]
             (mapv (comp first first) @calls))
          "exactly the attempted prefix is compensated — the possibly-enqueued
           throwing record included; the never-staged lexical tail was never
           dispatched, so a reentrant cleanup can neither see nor leak it")
      (is (empty? (reactive/resource-reservations cell)))
      (is (empty? (reactive/resource-held cell))))))

(deftest teardown-during-first-ensure-terminates-the-reconcile
  ;; rf2-vxgfnd.124 blocker 2 — resource dispatch runs its trace listeners
  ;; synchronously, so ensure(A) can tear the cell down mid-reconcile. The
  ;; reconcile must observe the terminal lifecycle after the dispatch returns
  ;; and stop: no release-B/ensure-B continuation, and no stale
  ;; held/reservation republication onto the dead cell. Pre-fix the staged
  ;; candidates were prepublished and the loop continued, producing
  ;; ensure-A, release-A, release-B, ensure-B — leaking B forever.
  (register-feature! :feed/items)
  (let [cell  (reactive/make-cell ::ensure-teardown)
        calls (atom [])
        cap   (commit-leases!
               cell [[::a {:resource :feed/items}]
                     [::b {:resource :feed/items}]])]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch!
                  (dispatch-standin
                   (fn [event opts]
                     (swap! calls conj [event opts])
                     (when (= :rf.resource/ensure (first event))
                       (reactive/teardown! cell))))]
      (reactive/reconcile-resource-leases! cell cap)
      (is (= [:rf.resource/ensure :rf.resource/release-owner]
             (mapv (comp first first) @calls))
          "ensure-A, teardown's release-A — and nothing after")
      (is (= (get-in (first @calls) [0 1 :owner])
             (get-in (second @calls) [0 1 :owner]))
          "teardown releases exactly the one staged/ensured owner")
      (is (= :dead (reactive/lifecycle cell)))
      (is (empty? (reactive/resource-reservations cell))
          "the terminated reconcile publishes no reservation onto the dead cell")
      (is (empty? (reactive/resource-held cell))
          "the terminated reconcile publishes no held state onto the dead cell")
      (is (zero? (reactive/resource-frame-incarnation-count cell fid))
          "no dead frame pin lingers in the teardown index"))))

(deftest reentrant-teardown-during-release-is-terminal-and-idempotent
  ;; rf2-vxgfnd.124 blocker 3 — teardown! publishes :dead and clears every
  ;; generic registry BEFORE dispatching resource cleanup, so a synchronous
  ;; release listener re-entering teardown! observes the terminal state and
  ;; no-ops. Pre-fix the nested call saw a still-connected cell and recursed
  ;; without bound (the entry guard below fails the test deterministically
  ;; instead of blowing the stack).
  (register-feature! :feed/items)
  (let [cell    (reactive/make-cell ::teardown-recursion)
        calls   (atom [])
        entries (atom 0)]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)]
      (let [cap (commit-leases! cell [[::a {:resource :feed/items}]])]
        (reactive/reconcile-resource-leases! cell cap)))
    (reset! calls [])
    (with-redefs [router/dispatch!
                  (dispatch-standin
                   (fn [event opts]
                     (swap! calls conj [event opts])
                     (when (= :rf.resource/release-owner (first event))
                       (when (< 3 (swap! entries inc))
                         (throw (ex-info "unbounded reentrant teardown" {})))
                       (reactive/teardown! cell))))]
      (reactive/teardown! cell)
      (is (= [:rf.resource/release-owner]
             (mapv (comp first first) @calls))
          "one owner, one release — the nested teardown observes :dead and no-ops")
      (is (= :dead (reactive/lifecycle cell)))
      (reactive/teardown! cell)
      (is (= 1 (count @calls))
          "an explicit later teardown is a dispatch-free no-op"))))

(deftest nested-teardown-during-disconnect-release-stays-terminal
  ;; rf2-vxgfnd.124 blocker 4 — disconnect publishes :disconnected and parks
  ;; the reusable reservations behind a unique cleanup token BEFORE
  ;; dispatching releases. A release listener that tears the cell down must
  ;; leave it :dead: the outer disconnect performs no unconditional lifecycle
  ;; write after its reentrant cleanup and cannot restore reservations over
  ;; the terminal state. Pre-fix the outer disconnect overwrote :dead with
  ;; :disconnected and resurrected the reservation on a dead cell.
  (register-feature! :feed/items)
  (let [cell  (reactive/make-cell ::disconnect-overwrite)
        calls (atom [])]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)]
      (let [cap (commit-leases! cell [[::a {:resource :feed/items}]])]
        (reactive/reconcile-resource-leases! cell cap)))
    (reset! calls [])
    (with-redefs [router/dispatch!
                  (dispatch-standin
                   (fn [event opts]
                     (swap! calls conj [event opts])
                     (when (= :rf.resource/release-owner (first event))
                       (reactive/teardown! cell))))]
      (reactive/disconnect-resources! cell))
    (is (= [:rf.resource/release-owner]
           (mapv (comp first first) @calls))
        "the nested teardown finds no remaining held owner to re-release")
    (is (= :dead (reactive/lifecycle cell))
        "the outer disconnect never overwrites the nested terminal state")
    (is (empty? (reactive/resource-reservations cell))
        "no reservation is restored onto the dead cell")
    (is (empty? (reactive/resource-held cell)))))

(deftest capture-supersession-during-ensure-stops-the-stale-reconcile
  ;; A synchronous listener committing a NEW capture mid-ensure replaces
  ;; reconciliation authority. The superseded loop must stop after the
  ;; in-flight dispatch (no stale ensure-B), and its compensation must skip
  ;; any owner the newer passive already accounted for — releasing that owner
  ;; again would double-release a lease the live capture rightfully holds.
  (register-feature! :feed/items)
  (let [cell  (reactive/make-cell ::supersession)
        calls (atom [])
        mints (atom 0)
        fired (atom false)
        cap1  (commit-leases!
               cell [[::a {:resource :feed/items}]
                     [::b {:resource :feed/items}]])]
    (with-redefs [features/require-feature! (constantly true)
                  lease-owner/mint! (fn [] [:lease (swap! mints inc)])
                  router/dispatch!
                  (dispatch-standin
                   (fn [event opts]
                     (swap! calls conj [event opts])
                     (when (and (= :rf.resource/ensure (first event))
                                (compare-and-set! fired false true))
                       (let [cap2 (commit-leases!
                                   cell [[::a {:resource :feed/items}]])]
                         (reactive/reconcile-resource-leases! cell cap2)))))]
      (reactive/reconcile-resource-leases! cell cap1)
      (let [events   @calls
            owner-of #(get-in % [0 1 :owner])]
        (is (= [:rf.resource/ensure :rf.resource/ensure
                :rf.resource/release-owner]
               (mapv (comp first first) events))
            "ensure-A1, nested ensure-A2, nested release-A1 — no stale ensure-B
             and no compensating double release-A1 afterwards")
        (is (= (owner-of (first events)) (owner-of (nth events 2)))
            "the nested passive releases exactly the superseded staged owner")
        (is (= [(owner-of (second events))]
               (vec (keys (reactive/resource-held cell))))
            "the newer capture's owner is the only held owner")
        (is (= (owner-of (second events))
               (-> (reactive/resource-reservations cell) (get ::a) :owner))
            "the newer reservation is never clobbered by the stale loop")))))

(deftest passive-reconcile-is-bound-to-the-exact-accepted-capture
  (register-feature! :feed/items :feed/new)
  (let [cell  (reactive/make-cell ::exact-passive)
        calls (atom [])
        mints (atom 0)
        [_ old-cap] (capture-leases cell [[::a {:resource :feed/items}]])
        new-cap (commit-leases! cell [[::a {:resource :feed/new}]])]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)
                  lease-owner/mint! (fn [] [:lease (swap! mints inc)])]
      (reactive/reconcile-resource-leases! cell old-cap)
      (is (zero? @mints) "a passive effect from an unselected capture is inert")
      (is (empty? @calls))
      (reactive/reconcile-resource-leases! cell new-cap)
      (is (= [:rf.resource/ensure]
             (mapv (comp first first) @calls)))
      (is (= :feed/new (-> @calls first first second :resource))))))

(deftest hmr-generation-advance-invalidates-an-older-accepted-passive-closure
  (register-feature! :feed/items)
  (let [cell  (reactive/make-cell ::hmr-stale 0)
        calls (atom [])
        mints (atom 0)
        cap   (commit-leases! cell [[::a {:resource :feed/items}]])]
    ;; Model registration advancing authority before the replacement render is
    ;; selected (or when that render is later abandoned).
    (reactive/advance-generation! cell 1)
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)
                  lease-owner/mint! (fn [] [:lease (swap! mints inc)])]
      (reactive/reconcile-resource-leases! cell cap)
      (is (zero? @mints))
      (is (empty? @calls))
      (is (empty? (reactive/resource-held cell))))))

(deftest hmr-removing-the-last-lease-releases-and-forgets-its-owner
  (register-feature! :feed/items)
  (let [cell  (reactive/make-cell ::hmr-remove 0)
        calls (atom [])]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)]
      (let [cap (commit-leases! cell [[::a {:resource :feed/items}]])]
        (reactive/reconcile-resource-leases! cell cap))
      (is (= 1 (count (reactive/resource-held cell))))
      (reset! calls [])
      (reactive/advance-generation! cell 1)
      (let [cap (commit-leases! cell [])]
        (reactive/reconcile-resource-leases! cell cap))
      (is (= [:rf.resource/release-owner]
             (mapv (comp first first) @calls)))
      (is (empty? (reactive/resource-reservations cell)))
      (is (empty? (reactive/resource-held cell))))))

(deftest ownership-empty-dev-shell-does-not-probe-the-optional-feature
  (let [cell   (reactive/make-cell ::empty-dev-shell)
        probes (atom 0)
        [_ cap] (reactive/with-capture cell (constantly :element))]
    (reactive/commit-resources! cell cap)
    (with-redefs [features/require-feature!
                  (fn [_]
                    (swap! probes inc)
                    (throw (ex-info "must not probe" {})))]
      (reactive/reconcile-resource-leases! cell cap)
      (is (zero? @probes))
      (is (empty? (reactive/resource-held cell))))))

(deftest cross-frame-retarget-prevalidates-then-ensures-all-before-any-release
  (register-feature! :feed/items :feed/new)
  (let [frame-a (live-frame/make-frame {:id :lease/frame-a})
        frame-b (live-frame/make-frame {:id :lease/frame-b})
        cell    (reactive/make-cell ::cross-frame)
        calls   (atom [])
        mints   (atom 0)
        capture (fn [rows]
                  (reactive/with-capture
                   cell
                   (fn []
                     (doseq [[frame-id sid descriptor] rows]
                       (rf/with-frame frame-id
                         (reactive/lease-site sid descriptor)))
                     :element)))]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)
                  lease-owner/mint! (fn [] [:lease (swap! mints inc)])]
      (let [[_ cap] (capture [[frame-a ::a {:resource :feed/items}]
                              [frame-b ::b {:resource :feed/items}]])]
        (reactive/commit-resources! cell cap)
        (reactive/reconcile-resource-leases! cell cap))
      (reset! calls [])
      ;; Both lexical sites change exact frame incarnation and descriptor.
      ;; The desired lexical order deliberately crosses the two frame queues.
      (let [[_ cap] (capture [[frame-b ::a {:resource :feed/new}]
                              [frame-a ::b {:resource :feed/new}]])]
        (reactive/commit-resources! cell cap)
        (reactive/reconcile-resource-leases! cell cap))
      (is (= [:rf.resource/ensure :rf.resource/ensure
              :rf.resource/release-owner :rf.resource/release-owner]
             (mapv (comp first first) @calls)))
      (is (= [:lease/frame-b :lease/frame-a :lease/frame-a :lease/frame-b]
             (mapv (comp :frame second) @calls))
          "desired lexical ensures precede deterministic prior-owner releases")
      (is (= 4 @mints) "each cross-frame retarget mints a fresh owner"))
    (frame/destroy-frame! frame-a)
    (frame/destroy-frame! frame-b)))

(deftest resource-only-cells-are-discoverable-while-connected-and-hidden
  (register-feature! :feed/items)
  (let [frame-id :lease/discovery
        _        (live-frame/make-frame {:id frame-id})
        connected (reactive/make-cell ::connected)
        hidden    (reactive/make-cell ::hidden)
        root      (reactive/make-root-incarnation)]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (dispatch-standin (fn [_ _] nil))]
      (doseq [cell [connected hidden]]
        (let [[_ cap] (capture-leases cell frame-id
                                      [[::a {:resource :feed/items}]])]
          (reactive/commit-resources! cell cap)
          (reactive/reconcile-resource-leases! cell cap)))
      (is (= 1 (reactive/resource-frame-incarnation-count connected frame-id))
          "desired/reserved/held copies dedupe to one exact frame token")
      (reactive/attach-root! hidden root)
      (reactive/disconnect-resources! hidden)
      (is (= :disconnected (reactive/lifecycle hidden)))
      (is (= 2 (reactive/teardown-frame! frame-id))
          "frame discovery unions connected desired state and hidden reservations")
      (is (= :dead (reactive/lifecycle connected)))
      (is (= :dead (reactive/lifecycle hidden)))
      (is (empty? (reactive/resource-reservations connected)))
      (is (empty? (reactive/resource-reservations hidden))))
    (frame/destroy-frame! frame-id)))

(deftest transient-same-id-incarnations-remain-distinct-in-frame-index
  (register-feature! :feed/items)
  (let [frame-id :lease/transient-index
        _        (live-frame/make-frame {:id frame-id})
        token-a  (reactive/make-root-incarnation)
        token-b  (reactive/make-root-incarnation)
        current  (atom token-a)
        cell     (reactive/make-cell ::transient-index)]
    (with-redefs [features/require-feature! (constantly true)
                  frame/frame-incarnation-token (fn [_] @current)
                  frame/frame-incarnation-closing? (fn [_ _] false)
                  router/dispatch! (dispatch-standin (fn [_ _] nil))]
      (let [[_ cap-a] (capture-leases cell frame-id
                                      [[::a {:resource :feed/items}]])]
        (reactive/commit-resources! cell cap-a)
        (reactive/reconcile-resource-leases! cell cap-a))
      (reset! current token-b)
      (let [[_ cap-b] (capture-leases cell frame-id
                                      [[::a {:resource :feed/items}]])]
        (reactive/commit-resources! cell cap-b))
      (is (= 2 (reactive/resource-frame-incarnation-count cell frame-id))
          "old held/reserved A and accepted desired B do not collapse")
      ;; Model A's exact teardown callback before passive B reconciliation.
      ;; Discovery must retain A even though desired state already names B.
      (reset! current token-a)
      (is (= 1 (reactive/teardown-frame! frame-id)))
      (is (= :dead (reactive/lifecycle cell))))
    (frame/destroy-frame! frame-id)))

(deftest layout-rejects-resource-capture-from-a-superseded-frame-incarnation
  (register-feature! :feed/items)
  (let [frame-id :lease/reincarnation
        _        (live-frame/make-frame {:id frame-id})
        cell     (reactive/make-cell ::reincarnation)
        [_ cap]  (capture-leases cell frame-id
                                 [[::a {:resource :feed/items}]])]
    (frame/destroy-frame! frame-id)
    (live-frame/make-frame {:id frame-id})
    (reactive/commit-resources! cell cap)
    (is (= :dead (reactive/lifecycle cell))
        "lease-only layout joins A teardown instead of silently targeting B")
    (is (empty? (reactive/resource-reservations cell)))
    (is (empty? (reactive/resource-held cell)))
    (frame/destroy-frame! frame-id)))

(deftest layout-rejects-mixed-same-id-incarnations-before-publish
  (let [frame-id :lease/mixed-incarnations
        _        (live-frame/make-frame {:id frame-id})
        cell     (reactive/make-cell ::mixed-incarnations)
        calls    (atom [])
        mints    (atom 0)
        phases   (atom [])
        root     (reactive/make-root-incarnation)
        [_ cap]
        (do
          (reactive/enable-resource-lifecycle! cell)
          (rf/with-frame frame-id
            (reactive/with-capture
             cell
             (fn []
               (reactive/lease-site ::a {:resource :feed/items})
               (frame/destroy-frame! frame-id)
               (live-frame/make-frame {:id frame-id})
               (reactive/lease-site ::b {:resource :feed/items})
               :element))))]
    (with-redefs [lease-owner/mint! (fn [] [:lease (swap! mints inc)])
                  router/dispatch! (collecting-dispatch calls)]
      (binding [reactive/*commit-barrier*
                (fn [phase _] (swap! phases conj phase))]
        (reactive/commit-resources! cell cap))
      (reactive/reconcile-resource-leases! cell cap))
    (is (= :dead (reactive/lifecycle cell)))
    (is (empty? @phases)
        "conflicting pins reject before the generic commit can publish/connect")
    (is (zero? @mints))
    (is (empty? @calls))
    (is (empty? (reactive/resource-reservations cell)))
    (is (empty? (reactive/resource-held cell)))
    (reactive/attach-root! cell root)
    (is (nil? (reactive/cell-root cell)))
    (is (zero? (reactive/root-cell-count root))
        "the later layout hook cannot reattach a rejected dead cell")
    (frame/destroy-frame! frame-id)))

(deftest final-teardown-releases-and-forgets-reservation
  (register-feature! :feed/items)
  (let [cell  (reactive/make-cell ::final-teardown)
        calls (atom [])]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! (collecting-dispatch calls)]
      (let [cap (commit-leases! cell [[::a {:resource :feed/items}]])]
        (reactive/reconcile-resource-leases! cell cap))
      (reset! calls [])
      (reactive/teardown! cell)
      (is (= [:rf.resource/release-owner]
             (mapv (comp first first) @calls)))
      (is (empty? (reactive/resource-reservations cell)))
      (is (empty? (reactive/resource-held cell)))
      (is (= :dead (reactive/lifecycle cell))))))

(deftest throwing-host-cleanup-attempts-every-owner-and-cell-and-clears-state
  (register-feature! :feed/items)
  (let [a (reactive/make-cell ::cleanup-a)
        b (reactive/make-cell ::cleanup-b)
        calls (atom [])
        release-attempt (atom 0)]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch!
                  (dispatch-standin
                   (fn [event opts]
                     (swap! calls conj [event opts])
                     (when (= :rf.resource/release-owner (first event))
                       (when (= 1 (swap! release-attempt inc))
                         (throw (ex-info "injected first release escape" {}))))))]
      (doseq [cell [a b]]
        (let [cap (commit-leases!
                   cell [[::x {:resource :feed/items}]
                         [::y {:resource :feed/items}]])]
          (reactive/reconcile-resource-leases! cell cap)))
      (reset! calls [])
      (is (= 2 (reactive/teardown-frame! fid)))
      (is (= 4 @release-attempt)
          "the first throwing owner cannot short-circuit later owners/cells")
      (doseq [cell [a b]]
        (is (= :dead (reactive/lifecycle cell)))
        (is (empty? (reactive/resource-reservations cell)))
        (is (empty? (reactive/resource-held cell)))))))
