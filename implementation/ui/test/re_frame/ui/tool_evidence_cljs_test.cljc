(ns re-frame.ui.tool-evidence-cljs-test
  "rf2-vxgfnd.75 — the first-party tool projection over the ViewCell DEBUG
  invalidation-evidence plane (`re-frame.ui.tool.evidence`).

  The rows:

    - IDENTITY-OWNED LIFECYCLE — install claims the projection for an owner;
      a second owner's install is REJECTED (the first registration is never
      silently cleared); uninstall is owner-checked and releases the sink
      callback plus every retained entry (zero retention when closed);
    - HMR-SAFE — a same-owner re-install is an idempotent re-arm that keeps
      the accumulated evidence (the `:after-load` path), including recovery
      after a fixture `reset-scheduler!` cleared the raw slot;
    - BATCH EVIDENCE, STABLY KEYED — each flushed batch's bounded record
      accretes into one per-cell accumulator carrying first/latest epoch,
      total occurrence count, batch count, the cause union, the bounded
      shown-target sample, and the honest rf2-vxgfnd.74 loss account — keyed
      by a stable projection ordinal + view id, with NO payload retention;
    - CROSS-BATCH LOSS HONESTY — re-delivering the same overflow targets
      across batches never inflates the distinct-omission loss;
    - PRUNING — dead (torn-down) cells disappear from the projection;
    - OBSERVATIONAL — the scheduler's revisions/notifications are untouched
      and no sink escape is recorded (rf2-vxgfnd.73's containment applies).

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` / `test:ui` (node)
  AND `clojure -M:test` (JVM), so the projection is graft-checked on both."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                  :as rf]
            [re-frame.frame                 :as frame]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]
            [re-frame.ui.reactive           :as reactive]
            [re-frame.ui.tool.evidence      :as evidence]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (evidence/force-release!)
    (try (f) (finally
               (reactive/reset-scheduler!)
               (evidence/force-release!)))))

(def ^:private fid :rf/default)

(defn- tk [id q] [:sub id q])

(defn- seed! [db] (frame/replace-app-db! fid db))

(defn- rc!
  "Render (probe) `queries` under the default frame, then commit — the cell
  ends observing it (the reactive-epoch-test technique for driving REAL
  observation-port fan-out at this tier)."
  [cell queries]
  (let [[_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn [] (mapv (fn [i q]
                                          (reactive/sub-read [:tool-ev/site i] q))
                                        (range) queries))))]
    (reactive/commit! cell capture))
  cell)

(defn- entry-for
  "The projection row for view id `vid`, or nil."
  [vid]
  (some #(when (= vid (:view-id %)) %) (evidence/projection)))

(def ^:private bounded-evidence-keys
  #{:batches :first-epoch :latest-epoch :count :causes :targets
    :targets-exact? :dropped :dropped-exact?})

(defrecord PreReloadWeakCellEntries
  [members by-cell next-ordinal reaper])

(def ^:private one-target-evidence
  {:first-epoch 1
   :latest-epoch 1
   :count 1
   :causes #{:value}
   :targets []
   :dropped #{}
   :dropped-exact? true})

(defn- publish-target!
  [sink cell target]
  (sink cell (assoc one-target-evidence :targets [target])))

(defn- connect-empty!
  "Commit one empty render so `cell` follows the real connected/disconnected
  lifecycle without introducing observation owners into a retention proof."
  [cell]
  (let [[_ capture] (reactive/with-capture cell (fn [] nil))]
    (reactive/commit! cell capture))
  cell)

#?(:clj
   (defn- gc-until
     "Hint a full collection until `pred` observes the weak population gone.
     Bounded retries keep a failure diagnostic rather than hanging the suite."
     [pred]
     (loop [i 0]
       (cond
         (pred)      true
         (>= i 100) (pred)
         :else       (do (System/gc)
                         (Thread/sleep 10)
                         (recur (inc i)))))))

#?(:clj
   (defn- churn-disconnected-cell!
     "Publish one row, then model ordinary reconciliation cleanup: disconnect
     and return only a WeakReference after the render/fiber owner is gone."
     [i]
     (let [cell (connect-empty!
                 (reactive/make-cell (keyword "tool-ev.churn" (str i))))]
       (reactive/mark-dirty! cell (inc i))
       (reactive/flush-pending!)
       (reactive/disconnect! cell)
       (java.lang.ref.WeakReference. cell))))

;; ===========================================================================
;; Identity-owned lifecycle — never clobbers, releases everything
;; ===========================================================================

(deftest install-is-identity-owned-and-never-clobbers
  (is (nil? (evidence/installed-owner)) "unowned at rest")
  (is (true? (evidence/install! ::tool-a)) "a fresh install claims the projection")
  (is (= ::tool-a (evidence/installed-owner)))

  (testing "a SECOND owner's install is rejected — nothing changes (AC3)"
    (is (false? (evidence/install! ::tool-b)))
    (is (= ::tool-a (evidence/installed-owner)) "the first owner still holds it"))

  (testing "…and the first owner's projection is STILL live, not clobbered"
    (let [cell (reactive/make-cell ::v)]
      (reactive/mark-dirty! cell 1)
      (reactive/flush-pending!)
      (is (some? (entry-for ::v)) "the batch reached tool-a's projection")))

  (testing "a non-owner uninstall is refused and releases nothing"
    (is (false? (evidence/uninstall! ::tool-b)))
    (is (= ::tool-a (evidence/installed-owner)))
    (is (some? (entry-for ::v)) "tool-a's accumulated evidence survives"))

  (testing "the owner's uninstall releases the sink AND every entry (AC4)"
    (is (true? (evidence/uninstall! ::tool-a)))
    (is (nil? (evidence/installed-owner)))
    (is (= [] (evidence/projection)) "zero retained cells/evidence")
    ;; the raw slot is free: a flush while uninstalled projects nothing…
    (let [cell (reactive/make-cell ::w)]
      (reactive/mark-dirty! cell 2)
      (reactive/flush-pending!)
      (is (= [] (evidence/projection)) "nothing delivered while uninstalled"))
    ;; …and a different tool can now claim it
    (is (true? (evidence/install! ::tool-b)) "the freed slot is claimable")
    (is (= ::tool-b (evidence/installed-owner)))
    (is (= [] (evidence/projection)) "the new owner starts empty")))

(deftest same-owner-reinstall-is-an-idempotent-rearm-that-keeps-evidence
  ;; The HMR path: `:after-load` re-runs the installing namespace. The
  ;; re-install must neither error, nor clobber, nor lose the accumulation.
  (is (true? (evidence/install! ::tool-a)))
  (let [cell (reactive/make-cell ::v)]
    (doseq [e [1 2 3]] (reactive/mark-dirty! cell e))
    (reactive/flush-pending!)
    (is (= 3 (get-in (entry-for ::v) [:evidence :count])))

    (testing "re-install: true, same owner, evidence KEPT"
      (is (true? (evidence/install! ::tool-a)))
      (is (= ::tool-a (evidence/installed-owner)))
      (is (= 3 (get-in (entry-for ::v) [:evidence :count]))
          "the accumulated evidence survives the re-install"))

    (testing "re-install RE-ARMS after a fixture reset cleared the raw slot"
      ;; `reset-scheduler!` (the test-fixture clean slate) drops the raw
      ;; evidence-sink underneath the tool tier. A same-owner install is the
      ;; documented recovery: it re-arms delivery without losing ownership.
      (reactive/reset-scheduler!)
      (is (true? (evidence/install! ::tool-a)))
      (reactive/mark-dirty! cell 4)
      (reactive/flush-pending!)
      (is (= 4 (get-in (entry-for ::v) [:evidence :count]))
          "delivery resumed into the SAME accumulator (batches accrete)")
      (is (= 2 (get-in (entry-for ::v) [:evidence :batches]))))))

(deftest same-owner-reinstall-survives-weak-store-constructor-churn
  ;; Model a CLJS :after-load precisely: defonce preserves the old record
  ;; value while the namespace reload replaces its record constructor. The
  ;; post-load code must recognize the store structurally, not by constructor
  ;; identity, and must reuse its already-registered host weak machinery.
  (is (true? (evidence/install! ::tool-a)))
  (let [cell   (connect-empty! (reactive/make-cell ::hmr-retained))
        state* @#'evidence/state*]
    (reactive/mark-dirty! cell 1)
    (reactive/flush-pending!)
    (reactive/disconnect! cell)
    (let [before      (entry-for ::hmr-retained)
          store       (:entries @state*)
          old-shape   (->PreReloadWeakCellEntries
                       (:members store)
                       (:by-cell store)
                       (:next-ordinal store)
                       (:reaper store))]
      (swap! state* assoc :entries old-shape)
      (is (true? (evidence/install! ::tool-a))
          "constructor churn is a compatible same-owner re-arm")
      (is (= before (entry-for ::hmr-retained))
          "Activity identity, ordinal and exact loss survive")
      (let [after-store (:entries @state*)]
        (is (identical? (:members store) (:members after-store)))
        (is (identical? (:by-cell store) (:by-cell after-store)))
        (is (identical? (:next-ordinal store) (:next-ordinal after-store)))
        (is (identical? (:reaper store) (:reaper after-store))
            "the existing reaper is retained, not double-armed"))
      (let [fresh (reactive/make-cell ::hmr-fresh)]
        (reactive/mark-dirty! fresh 2)
        (reactive/flush-pending!)
        (is (< (:cell-id before) (:cell-id (entry-for ::hmr-fresh)))
            "the preserved ordinal mint advances exactly once")))))

;; ===========================================================================
;; Batch evidence content — stable identity, bounded record, no payloads
;; ===========================================================================

(deftest the-projection-carries-batch-evidence-with-stable-identity
  (is (true? (evidence/install! ::xray)))
  (let [cell-a (reactive/make-cell ::va)
        cell-b (reactive/make-cell ::vb)
        hits-a (atom 0)]
    (reactive/subscribe cell-a (fn [] (swap! hits-a inc)))
    (doseq [e (range 1 9)] (reactive/mark-dirty! cell-a e))
    (reactive/mark-dirty! cell-b 9)
    (reactive/flush-pending!)

    (testing "one row per cell, in stable ordinal order"
      (let [rows (evidence/projection)]
        (is (= 2 (count rows)))
        (is (= [(:cell-id (first rows)) (:cell-id (second rows))]
               (sort [(:cell-id (first rows)) (:cell-id (second rows))]))
            "rows sort by the stable projection ordinal")
        (is (= #{::va ::vb} (into #{} (map :view-id) rows))
            "each row names its authoring view")))

    (testing "the accumulator exposes the full bounded batch summary (AC2)"
      (let [{:keys [evidence root-id]} (entry-for ::va)]
        (is (= 1 (:first-epoch evidence)) "anchored to the FIRST movement")
        (is (= 8 (:latest-epoch evidence)) "…tracking the LATEST")
        (is (= 8 (:count evidence)) "every occurrence counted")
        (is (= 1 (:batches evidence)) "one flushed batch so far")
        (is (= #{} (:causes evidence)) "epoch-only marks carry no cause")
        (is (= [] (:targets evidence)))
        (is (= #{} (:dropped evidence)) "the rf2-vxgfnd.74 loss field, empty")
        (is (true? (:dropped-exact? evidence)) "…and exact")
        (is (nil? root-id) "no live client root owns a Tier-1 cell")))

    (testing "no payload retention — exactly the bounded keys (AC2)"
      (is (= bounded-evidence-keys
             (set (keys (:evidence (entry-for ::va)))))))

    (testing "a later batch ACCRETES under the SAME identity"
      (let [id-before (:cell-id (entry-for ::va))]
        (doseq [e (range 9 13)] (reactive/mark-dirty! cell-a e))
        (reactive/flush-pending!)
        (let [{:keys [cell-id evidence]} (entry-for ::va)]
          (is (= id-before cell-id) "the projection ordinal is stable")
          (is (= 1 (:first-epoch evidence)) "the anchor never rewrites")
          (is (= 12 (:latest-epoch evidence)))
          (is (= 12 (:count evidence)))
          (is (= 2 (:batches evidence))))
        (is (= 1 (get-in (entry-for ::vb) [:evidence :count]))
            "the sibling row is untouched")))

    (testing "the projection is OBSERVATIONAL (rf2-vxgfnd.73 containment seam)"
      (is (= 2 (reactive/revision cell-a)) "two batches → two advances, as ever")
      (is (= 2 @hits-a) "…and two notifications — scheduling untouched")
      (is (nil? (reactive/last-evidence-sink-escape)) "no contained escape"))))

(deftest real-port-fanout-projects-cause-and-target
  ;; End-to-end through the REAL observation-port fan-out at this tier: an
  ;; HMR re-registration fires each committed site's live lease `on-change`
  ;; with its rich payload; the projection must surface the cause + target.
  (rf/reg-sub :tool-ev/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (is (true? (evidence/install! ::xray)))
  (let [cell (rc! (reactive/make-cell ::v) [[:tool-ev/a]])]
    (rf/reg-sub :tool-ev/a (fn [db _] (:a db)))   ;; re-register → real :hmr fan-out
    (reactive/flush-pending!)
    (let [{:keys [evidence]} (entry-for ::v)]
      (is (= #{:hmr} (:causes evidence)) "the real cause is projected")
      (is (= [(tk fid [:tool-ev/a])] (:targets evidence))
          "…with the moving target key")
      (is (= 1 (:count evidence))))))

(deftest target-projection-is-bounded-truthful-and-never-owns-the-cell
  (is (true? (evidence/install! ::xray)))
  (let [sink        (evidence/installed-sink)
        direct-cell (reactive/make-cell ::cycle-direct)
        nested-cell (reactive/make-cell ::cycle-nested)
        host-cell   (reactive/make-cell ::cycle-host)
        bounded-cell (reactive/make-cell ::bounded)
        ordinary-cell (reactive/make-cell ::ordinary)
        host-value  #?(:clj (Object.) :cljs (js-obj))]
    (publish-target! sink direct-cell [:sub fid [::q direct-cell]])
    (publish-target! sink nested-cell [:sub fid [::q {:nested [nested-cell]}]])
    (publish-target! sink host-cell [:sub fid [::q host-value]])
    (doseq [vid [::cycle-direct ::cycle-nested ::cycle-host]]
      (let [ev (:evidence (entry-for vid))]
        (is (false? (:targets-exact? ev))
            "dynamic identity loss is explicit")
        (is (false? (:dropped-exact? ev))
            "opaque identity makes the cumulative omission floor explicit")
        (is (= {:rf.ui.evidence/opaque :dynamic}
               (get-in ev [:targets 0 2 1]))
            "the query position carries a truthful opaque marker")))
    (publish-target! sink bounded-cell
                     [:sub fid [::q (vec (range 33))]])
    (let [ev (:evidence (entry-for ::bounded))]
      (is (false? (:targets-exact? ev)))
      (is (= {:rf.ui.evidence/opaque :bounded}
             (get-in ev [:targets 0 2 1]))
          "oversized EDN is replaced instead of retained"))
    (publish-target! sink ordinary-cell
                     [:sub fid [::ordinary {:nested [1 :two "three"]}]])
    (is (= [:sub fid [::ordinary {:nested [1 :two "three"]}]]
           (first (:targets (:evidence (entry-for ::ordinary)))))
        "ordinary bounded EDN remains exact")))

#?(:clj
   (deftest direct-and-nested-query-cycles-do-not-defeat-weak-keys
     (is (true? (evidence/install! ::xray)))
     (let [sink (evidence/installed-sink)
           refs (mapv
                 (fn [[vid query-fn]]
                   (let [cell (connect-empty! (reactive/make-cell vid))]
                     (publish-target! sink cell [:sub fid (query-fn cell)])
                     (reactive/disconnect! cell)
                     (java.lang.ref.WeakReference. cell)))
                 [[::weak-cycle-direct (fn [cell] [::q cell])]
                  [::weak-cycle-nested
                   (fn [cell] [::q {:nested {:cell cell}}])]])]
       (is (true? (gc-until #(every?
                              (fn [^java.lang.ref.WeakReference ref]
                                (nil? (.get ref)))
                              refs)))
           "neither a direct nor nested query→cell path owns the weak key")
       (is (= [] (evidence/projection))))))

#?(:cljs
   (deftest cleared-holder-compaction-unregisters-without-touching-replacement
     (is (true? (evidence/install! ::xray)))
     (let [state*      @#'evidence/state*
           base        (:entries @state*)
           survivor    (reactive/make-cell ::replacement)
           cleared     #js {:ref #js {:deref (fn [] nil)}
                            :entry {:cell-id 0 :view-id ::cleared :evidence {}}}
           replacement #js {:ref #js {:deref (fn [] survivor)}
                            :entry {:cell-id 1 :view-id ::replacement
                                    :evidence one-target-evidence}}
           members     (doto (js/Set.) (.add cleared) (.add replacement))
           calls*      (atom [])
           reaper      #js {:unregister (fn [token]
                                          (swap! calls* conj token)
                                          true)}]
       (swap! state* assoc :entries
              (assoc base :members members :by-cell (js/WeakMap.) :reaper reaper))
       (is (= [::replacement] (mapv :view-id (evidence/projection))))
       (is (= 1 (count @calls*)))
       (is (identical? cleared (first @calls*))
           "read-time compaction unregisters the exact holder/token")
       (is (not (.has members cleared)))
       (is (.has members replacement)
           "a stale unique token cannot remove a replacement holder")

       (let [no-reaper-holder #js {:ref #js {:deref (fn [] nil)} :entry {}}
             no-reaper-set    (doto (js/Set.) (.add no-reaper-holder))]
         (swap! state* assoc :entries
                (assoc base :members no-reaper-set
                            :by-cell (js/WeakMap.) :reaper nil))
         (is (= [] (evidence/projection)))
         (is (zero? (.-size no-reaper-set))
             "hosts without FinalizationRegistry compact on read")))))

;; ===========================================================================
;; Cross-batch loss honesty (the rf2-vxgfnd.74 axis, cumulative tier)
;; ===========================================================================

(deftest cross-batch-loss-stays-honest
  ;; 10 distinct targets overflow the shown cap (8) in ONE window: 2 distinct
  ;; omissions. Re-delivering the SAME 10 in a SECOND batch must not inflate
  ;; the cumulative loss — omission identity is already recorded.
  (let [n       10
        ids     (mapv (fn [i] (keyword "tool-ev" (str "s" i))) (range n))
        queries (mapv vector ids)]
    (doseq [i (range n)]
      (rf/reg-sub (ids i) (fn [db _] (get db i))))
    (seed! (into {} (map (fn [i] [i i])) (range n)))
    (is (true? (evidence/install! ::xray)))
    (let [cell (rc! (reactive/make-cell ::v) queries)]
      (doseq [i (range n)]                  ;; batch 1: 10 distinct :hmr targets
        (rf/reg-sub (ids i) (fn [db _] (get db i))))
      (reactive/flush-pending!)
      (let [{:keys [evidence]} (entry-for ::v)]
        (is (= 10 (:count evidence)))
        (is (= 8 (count (:targets evidence))) "shown sample capped")
        (is (= 2 (count (:dropped evidence))) "two distinct omissions")
        (is (true? (:dropped-exact? evidence))))

      ;; a real re-render re-acquires fresh leases on the re-registered
      ;; nodes before any further movement can fan out to this cell
      (rc! cell queries)
      (doseq [i (range n)]                  ;; batch 2: the SAME 10 targets
        (rf/reg-sub (ids i) (fn [db _] (get db i))))
      (reactive/flush-pending!)
      (let [{:keys [evidence]} (entry-for ::v)]
        (is (= 20 (:count evidence)) "occurrences keep counting")
        (is (= 2 (:batches evidence)))
        (is (= 8 (count (:targets evidence))) "the shown sample is stable")
        (is (= 2 (count (:dropped evidence)))
            "re-delivered omissions do NOT inflate the distinct loss (not 4)")
        (is (true? (:dropped-exact? evidence))
            "…and the account stays exact")))))

;; ===========================================================================
;; Pruning — dead cells disappear; nothing survives teardown
;; ===========================================================================

(deftest dead-cells-disappear-from-the-projection
  (is (true? (evidence/install! ::xray)))
  (let [cell-a (reactive/make-cell ::va)
        cell-b (reactive/make-cell ::vb)]
    (reactive/mark-dirty! cell-a 1)
    (reactive/mark-dirty! cell-b 2)
    (reactive/flush-pending!)
    (is (= 2 (count (evidence/projection))) "both cells project")

    (reactive/teardown! cell-a)             ;; host/root teardown → :dead
    (let [rows (evidence/projection)]
      (is (= 1 (count rows)) "the dead cell disappeared (AC4)")
      (is (= ::vb (:view-id (first rows)))))

    (testing "uninstall then releases the survivor too — zero retention"
      (is (true? (evidence/uninstall! ::xray)))
      (is (= [] (evidence/projection))))))

#?(:clj
   (deftest ordinary-unmount-churn-is-weak-while-activity-retention-survives
     ;; The exact ambiguity that forbids pruning merely on :disconnected:
     ;; React runs the same cleanup for an ordinary conditional/keyed unmount
     ;; and an Activity hide. The registry must therefore be NON-OWNING. React
     ;; keeps a hidden fiber/cell strongly reachable; an ordinary unmount drops
     ;; that last owner and the row follows the cell through weak collection.
     (is (true? (evidence/install! ::xray)))
     (let [hidden    (connect-empty! (reactive/make-cell ::activity-hidden))
           _         (reactive/mark-dirty! hidden 1)
           _         (reactive/flush-pending!)
           hidden-id (:cell-id (entry-for ::activity-hidden))
           hidden-ev (:evidence (entry-for ::activity-hidden))
           _         (reactive/disconnect! hidden)
           n         32
           refs      (mapv churn-disconnected-cell! (range n))]
       (is (= :disconnected (reactive/lifecycle hidden)))
       ;; Collection is allowed before the explicit GC hint. Do not assert an
       ;; exact pre-GC population; the strong-map mutation still fails below
       ;; because none of its WeakReferences can ever clear.
       (is (<= 1 (count (evidence/projection)) (inc n)))

       (testing "ordinary unmounts are garbage, not evidence-registry owners"
         ;; RED with the prior persistent map keyed strongly by ViewCell: none
         ;; of these WeakReferences can clear and the projection grows forever.
         (is (true? (gc-until #(and (every? (fn [^java.lang.ref.WeakReference r]
                                               (nil? (.get r)))
                                             refs)
                                          (= 1 (count (evidence/projection))))))
             "all churned cells collected and projection compaction returned
              the still-live tool to its one-row baseline"))

       (testing "Activity hide/reveal retains and reconnects the SAME row"
         (is (= [::activity-hidden] (mapv :view-id (evidence/projection))))
         (is (= hidden-id (:cell-id (entry-for ::activity-hidden))))
         (is (= hidden-ev (:evidence (entry-for ::activity-hidden)))
             "collection changed neither classification nor honest loss")
         (connect-empty! hidden)
         (is (= :connected (reactive/lifecycle hidden)))
         (is (= hidden-id (:cell-id (entry-for ::activity-hidden)))
             "weak membership does not confuse a retained hide with final unmount")
         (is (= hidden-ev (:evidence (entry-for ::activity-hidden)))))

       (testing "explicit teardown still removes the retained row immediately"
         (reactive/teardown! hidden)
         (is (= [] (evidence/projection)))))))
