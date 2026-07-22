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
            [re-frame.ui.tool               :as tool]
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
   :causes #{:subscription}
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
  ;; HMR re-registration fires each committed site's live handle `on-change`
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

(deftest metadata-bearing-symbol-targets-are-stripped-and-marked-inexact
  ;; rf2-vxgfnd.94.17. A symbol is the only metadata-capable scalar leaf, and
  ;; its metadata can hold the ViewCell the entry is weak-keyed by. The copier
  ;; must return a metadata-free value (no back-edge) AND stop claiming exact.
  (is (true? (evidence/install! ::xray)))
  (let [sink          (evidence/installed-sink)
        direct-cell   (reactive/make-cell ::meta-direct)
        nested-cell   (reactive/make-cell ::meta-nested)
        coll-cell     (reactive/make-cell ::meta-coll)
        ordinary-cell (reactive/make-cell ::sym-ordinary)]
    ;; DIRECT: the query IS a metadata-bearing symbol whose meta holds the cell
    (publish-target! sink direct-cell [:sub fid (with-meta 'q {:cell direct-cell})])
    ;; NESTED: the metadata-bearing symbol sits two collections deep
    (publish-target! sink nested-cell
                     [:sub fid [::q [(with-meta 'deep {:cell nested-cell})]]])
    ;; ADVERSARIAL: metadata on the COLLECTION itself (reconstruction must drop it)
    (publish-target! sink coll-cell
                     [:sub fid (with-meta [::q 1] {:cell coll-cell})])
    ;; CONTROL: an ordinary metadata-free symbol stays semantically exact
    (publish-target! sink ordinary-cell [:sub fid [::q 'plain]])

    (testing "a DIRECT metadata-bearing symbol is stripped to a bare value"
      (let [ev  (:evidence (entry-for ::meta-direct))
            sym (get-in ev [:targets 0 2])]
        (is (= 'q sym) "value semantics preserved (symbol equality ignores meta)")
        (is (nil? (meta sym)) "…but no metadata/back-reference is retained")
        (is (false? (:targets-exact? ev)) "dropped metadata is marked as loss")
        (is (false? (:dropped-exact? ev)))))

    (testing "a NESTED metadata-bearing symbol is stripped in place"
      (let [ev  (:evidence (entry-for ::meta-nested))
            sym (get-in ev [:targets 0 2 1 0])]
        (is (= 'deep sym))
        (is (nil? (meta sym)) "the nested symbol carries no metadata")
        (is (false? (:targets-exact? ev)))))

    (testing "collection-level metadata never survives reconstruction"
      (let [ev  (:evidence (entry-for ::meta-coll))
            tk  (get-in ev [:targets 0])
            q   (get tk 2)]
        (is (= [::q 1] q) "the collection value is preserved")
        (is (nil? (meta q)) "…without its metadata")
        (is (nil? (meta tk)) "nor does the target vector keep metadata")
        (is (false? (:targets-exact? ev))
            "dropped container metadata is loss, so exactness is lowered (rf2-1hob1)")
        (is (false? (:dropped-exact? ev)))))

    (testing "an ordinary metadata-free symbol remains exact"
      (let [ev (:evidence (entry-for ::sym-ordinary))]
        (is (= [:sub fid [::q 'plain]] (get-in ev [:targets 0]))
            "ordinary bounded EDN with a plain symbol is verbatim")
        (is (nil? (meta (get-in ev [:targets 0 2 1]))))
        (is (true? (:targets-exact? ev)) "…and honestly exact")
        (is (true? (:dropped-exact? ev)))))))

(deftest container-metadata-is-dropped-and-lowers-exactness
  ;; rf2-1hob1 gap 2. Every collection branch reconstructs a fresh, metadata-free
  ;; value (no leak), but derived `exact?` from its CHILDREN alone — so a
  ;; container that DROPPED its own metadata still reported exact, making
  ;; `:targets-exact?`/`:dropped-exact?` lie. Dropping container metadata is
  ;; information loss exactly like dropping a symbol's, and must lower exactness.
  (let [project #'evidence/project-target-value]
    (testing "a metadata-bearing vector keeps its value but is marked inexact"
      (let [[copy exact?] (project (with-meta [:q 1] {:hidden :lost}))]
        (is (= [:q 1] copy) "the value is reconstructed")
        (is (nil? (meta copy)) "…without its metadata (no back-edge)")
        (is (false? exact?) "…and is no longer claimed exact")))
    (testing "a metadata-bearing list is marked inexact"
      (let [[copy exact?] (project (with-meta (list :q 1) {:hidden :lost}))]
        (is (= (list :q 1) copy))
        (is (nil? (meta copy)))
        (is (false? exact?))))
    (testing "a metadata-bearing map is marked inexact"
      (let [[copy exact?] (project (with-meta {:q 1} {:hidden :lost}))]
        (is (= {:q 1} copy))
        (is (nil? (meta copy)))
        (is (false? exact?))))
    (testing "a metadata-bearing set is marked inexact"
      (let [[copy exact?] (project (with-meta #{:q 1} {:hidden :lost}))]
        (is (= #{:q 1} copy))
        (is (nil? (meta copy)))
        (is (false? exact?))))
    (testing "a metadata-FREE container of exact leaves stays exact (control)"
      (is (= [[:q 1] true] (project [:q 1])))
      (is (= [(list :q 1) true] (project (list :q 1))))
      (is (= [{:q 1} true] (project {:q 1})))
      (is (= [#{:q 1} true] (project #{:q 1}))))
    (testing "nested container metadata lowers the outer projection's exactness"
      (let [[copy exact?] (project [::q (with-meta [1 2] {:hidden :lost})])]
        (is (= [::q [1 2]] copy) "the values reconstruct")
        (is (false? exact?)
            "the outer vector is exact only if every child projected exact")))))

#?(:clj
   (deftest jvm-number-subclass-holding-a-cell-is-projected-opaque
     ;; rf2-1hob1 gap 3. `(number? x)` admits ANY `Number`, but on the JVM a
     ;; `Number` subclass can ALSO be `IObj`: a `proxy [Number IObj]` holding a
     ;; ViewCell in its metadata is `number?`, so the leaf rule returned it
     ;; verbatim as EXACT and rooted the weak key. Admission must be a closed set
     ;; of immutable platform numeric classes; a subclass takes the opaque path.
     (let [cell   (reactive/make-cell ::rogue-number)
           rogue  (proxy [java.lang.Number clojure.lang.IObj] []
                    (intValue    [] 0)
                    (longValue   [] 0)
                    (floatValue  [] (float 0))
                    (doubleValue [] 0.0)
                    (meta        [] {:cell cell})
                    (withMeta    [_] this))
           [copy exact?] (#'evidence/project-target-value rogue)]
       (is (number? rogue) "the rogue really IS a Number (it defeats `number?`)")
       (is (false? exact?) "a Number subclass is never claimed exact")
       (is (= {:rf.ui.evidence/opaque :dynamic} copy)
           "…and is projected opaque, retaining no reference to the cell")
       (testing "the ordinary immutable numeric leaves still project exact"
         (doseq [n [0 -3 (long 7) (int 7) (short 7) (byte 7)
                    1.5 (float 1.5) 1N 1/2 3.14M
                    (bigint 9) (biginteger 9)]]
           (is (= [n true] (#'evidence/project-target-value n))
               (str n " (" (class n) ") is an admitted platform number")))))))

#?(:clj
   (deftest metadata-bearing-symbol-does-not-defeat-weak-keys
     ;; The retention proof for rf2-vxgfnd.94.17. `(with-meta 'q {:cell cell})`
     ;; is a back-edge from the target VALUE, through symbol metadata, to the
     ;; ViewCell the entry is weak-keyed by — exactly the shape a bounded copier
     ;; is supposed to make impossible. RED at PR #5998's head, which returned
     ;; the symbol (and its metadata) unchanged.
     (is (true? (evidence/install! ::xray)))
     (let [sink (evidence/installed-sink)
           refs (mapv
                 (fn [[vid query-fn]]
                   (let [cell (connect-empty! (reactive/make-cell vid))]
                     (publish-target! sink cell [:sub fid (query-fn cell)])
                     (reactive/disconnect! cell)
                     (java.lang.ref.WeakReference. cell)))
                 [[::meta-weak-direct (fn [cell] (with-meta 'q {:cell cell}))]
                  [::meta-weak-nested
                   (fn [cell] [::q [(with-meta 'deep {:cell cell})]])]])]
       (is (true? (gc-until #(every?
                              (fn [^java.lang.ref.WeakReference ref]
                                (nil? (.get ref)))
                              refs)))
           "symbol metadata roots neither a direct nor a nested weak key")
       (is (= [] (evidence/projection))))))

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

#?(:clj
   (deftest rogue-number-target-does-not-defeat-weak-keys
     ;; The retention proof for rf2-1hob1 gap 3. The rogue Number holds the
     ;; ViewCell the entry is weak-keyed by; before the closed-set admission the
     ;; copier returned it verbatim as exact, so the store VALUE reached its own
     ;; KEY and a WeakHashMap never collects such an entry — the row pinned
     ;; forever. RED before the fix; the projected opaque marker holds nothing.
     (is (true? (evidence/install! ::xray)))
     (let [ref (let [cell  (connect-empty! (reactive/make-cell ::rogue-weak))
                     rogue (proxy [java.lang.Number clojure.lang.IObj] []
                             (intValue    [] 0)
                             (longValue   [] 0)
                             (floatValue  [] (float 0))
                             (doubleValue [] 0.0)
                             (meta        [] {:cell cell})
                             (withMeta    [_] this))
                     sink  (evidence/installed-sink)]
                 (publish-target! sink cell [:sub fid rogue])
                 (reactive/disconnect! cell)
                 (java.lang.ref.WeakReference. cell))]
       (is (true? (gc-until #(nil? (.get ^java.lang.ref.WeakReference ref))))
           "a rogue Number target roots no weak key once projected opaque")
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
;; Compatible HMR migration — legacy stores are SANITIZED, not just re-homed
;; ===========================================================================

(defn- legacy-accrual
  "The exact accrual a PRE-projection head accreted: RAW target keys, claimed
  exact — its copier did not exist yet, so it had nothing to be inexact about.
  Every pre-projection head ALSO predates the disposal-cause rename
  (rf2-ao46i), so the true legacy cause spelling is `:value` — seeding
  `#{:subscription}` here would mask the very transition migration must
  canonicalize."
  [targets dropped]
  {:first-epoch    1
   :latest-epoch   2
   :count          3
   :batches        1
   :causes         #{:value}
   :targets        (vec targets)
   :targets-exact? true
   :dropped        (set dropped)
   :dropped-exact? true})

(defn- legacy-entry
  [ord vid evidence]
  {:cell-id ord :view-id vid :evidence evidence})

(def ^:private tag-key @#'evidence/weak-store-tag-key)

;; The intermediate contract tag (`::weak-cell-entries-v2`) — minted while the
;; copier still returned metadata-bearing symbols verbatim, then kept over the
;; copier fix. A store stamped with it must be treated as legacy (rf2-1hob1).
(def ^:private v2-tag :re-frame.ui.tool.evidence/weak-cell-entries-v2)

;; The prior contract tag (`::weak-cell-entries-v3`) — current at the heads
;; BEFORE the disposal-cause rename (rf2-ao46i), so an ordinary same-owner HMR
;; store can carry it over accruals whose cause set still says `:value`.
(def ^:private v3-tag :re-frame.ui.tool.evidence/weak-cell-entries-v3)

(deftest compatible-hmr-sanitizes-a-legacy-strong-map-store
  ;; The pre-weak head kept `:entries` as a STRONG persistent map keyed by
  ;; ViewCell, whose values retained raw query→cell cycles. A compatible reload
  ;; rebuilds that into the weak store — and must run every retained value
  ;; through TODAY's copier on the way. Seeding the rows verbatim would migrate
  ;; each cycle intact, and a weak key its own value reaches is pinned forever.
  (is (true? (evidence/install! ::tool-a)))
  (let [state* @#'evidence/state*
        direct (connect-empty! (reactive/make-cell ::legacy-direct))
        nested (connect-empty! (reactive/make-cell ::legacy-nested))
        plain  (connect-empty! (reactive/make-cell ::legacy-plain))]
    (swap! state* assoc
           :entries      {direct (legacy-entry
                                  7 ::legacy-direct
                                  (legacy-accrual [[:sub fid [::q direct]]] []))
                          nested (legacy-entry
                                  9 ::legacy-nested
                                  (legacy-accrual
                                   [[:sub fid [::q {:nested {:cell nested}}]]] []))
                          plain  (legacy-entry
                                  11 ::legacy-plain
                                  (legacy-accrual [[:sub fid [::q 1]]] []))}
           :next-ordinal 12)

    (is (true? (evidence/install! ::tool-a)) "the reload is a compatible re-arm")

    (testing "raw direct/nested cycles are RE-PROJECTED, not rewrapped"
      (doseq [vid [::legacy-direct ::legacy-nested]]
        (let [ev (:evidence (entry-for vid))]
          (is (= {:rf.ui.evidence/opaque :dynamic} (get-in ev [:targets 0 2 1]))
              "the retained query is opaque — no cell survives the migration")
          (is (false? (:targets-exact? ev))
              "…and the legacy `exact` claim is corrected, not carried over")
          (is (false? (:dropped-exact? ev))))))

    (testing "ordinals and order survive; bounded counters carry over verbatim"
      (is (= [7 9 11] (mapv :cell-id (evidence/projection))))
      (is (= [::legacy-direct ::legacy-nested ::legacy-plain]
             (mapv :view-id (evidence/projection))))
      (let [ev (:evidence (entry-for ::legacy-direct))]
        (is (= 3 (:count ev)) "occurrence counters are already bounded plain data")
        (is (= 1 (:batches ev)))
        (is (= 1 (:first-epoch ev)))
        (is (= 2 (:latest-epoch ev)))
        (is (= #{:subscription} (:causes ev))
            "the pre-rename :value cause reads canonicalized, never verbatim"))
      (is (= bounded-evidence-keys
             (set (keys (:evidence (entry-for ::legacy-direct)))))
          "migration adds no keys"))

    (testing "an already-bounded legacy row is NOT reset, nor falsely degraded"
      (let [ev (:evidence (entry-for ::legacy-plain))]
        (is (= [[:sub fid [::q 1]]] (:targets ev)) "its exact value survives")
        (is (true? (:targets-exact? ev)) "…and its exactness is not thrown away")
        (is (true? (:dropped-exact? ev)))))

    (testing "the preserved ordinal mint advances past the legacy high-water mark"
      (let [fresh (reactive/make-cell ::legacy-fresh)]
        (reactive/mark-dirty! fresh 1)
        (reactive/flush-pending!)
        (is (<= 12 (:cell-id (entry-for ::legacy-fresh))))))

    (testing "repeated HMR is idempotent — the store now carries the current tag"
      (let [before (evidence/projection)]
        (is (true? (evidence/install! ::tool-a)))
        (is (true? (evidence/install! ::tool-a)))
        (is (= before (evidence/projection))
            "re-projecting an already-projected row changes nothing")))))

(deftest compatible-hmr-sanitizes-a-recognized-but-legacy-weak-store
  ;; The shape the prior head left behind: a store recognized by host structure
  ;; (the pre-tag defrecord) or by an OLDER tag. Recognition proves the weak
  ;; MACHINERY is reusable; it proves nothing about the retained VALUES, which
  ;; an earlier copier admitted raw. Re-tagging such a store without
  ;; re-projecting it promotes a lie — the tag is a claim about the values.
  (is (true? (evidence/install! ::tool-a)))
  (let [state* @#'evidence/state*
        cell   (connect-empty! (reactive/make-cell ::legacy-weak))
        store  (:entries @state*)]
    (#'evidence/store-seed-entry!
     store cell
     (legacy-entry 5 ::legacy-weak
                   (legacy-accrual [[:sub fid [::q cell]]]
                                   [[:sub fid [::dropped cell]]])))
    (swap! state* assoc :entries
           (->PreReloadWeakCellEntries (:members store) (:by-cell store)
                                       (:next-ordinal store) (:reaper store)))

    (is (true? (evidence/install! ::tool-a)) "constructor churn is compatible")

    (testing "the raw entry is re-projected IN PLACE"
      (let [row (entry-for ::legacy-weak)
            ev  (:evidence row)]
        (is (= {:rf.ui.evidence/opaque :dynamic} (get-in ev [:targets 0 2 1]))
            "the shown target's cycle is broken")
        (is (every? (fn [tk] (not (reactive/cell? (get-in tk [2 1]))))
                    (:dropped ev))
            "…and so is the OMITTED target's — the loss set retains no cell")
        (is (false? (:targets-exact? ev)))
        (is (false? (:dropped-exact? ev)))
        (is (= 5 (:cell-id row)) "the ordinal survives the migration")))

    (testing "the existing host machinery is REUSED, not rebuilt"
      (let [after (:entries @state*)]
        (is (identical? (:members store) (:members after)))
        (is (identical? (:by-cell store) (:by-cell after)))
        (is (identical? (:next-ordinal store) (:next-ordinal after)))
        (is (identical? (:reaper store) (:reaper after))
            "the existing reaper is retained, not double-armed")))

    (testing "repeated HMR is idempotent"
      (let [before (evidence/projection)]
        (is (true? (evidence/install! ::tool-a)))
        (is (= before (evidence/projection)))))))

#?(:clj
   (deftest legacy-migration-releases-the-raw-cycle-weak-key
     ;; The retention proof (rf2-vxgfnd.94.18). A legacy row's raw query→cell
     ;; cycle is a back-edge from the entry VALUE to its own weak KEY, and a
     ;; WeakHashMap whose value reaches its key never collects that entry. So a
     ;; migration that merely re-homes the rows keeps pinning every one of them
     ;; for the projection's lifetime — the exact retention the weak store was
     ;; built to end. RED when migration seeds entries unchanged.
     (is (true? (evidence/install! ::tool-a)))
     (let [state* @#'evidence/state*
           _      (swap! state* assoc :entries {} :next-ordinal 0)
           refs   (mapv
                   (fn [[vid ord query-fn]]
                     ;; the cell stays inside this lambda: only a WeakReference
                     ;; escapes, so the store is its last strong referent
                     (let [cell (connect-empty! (reactive/make-cell vid))]
                       (reactive/disconnect! cell)
                       (swap! state* update :entries assoc cell
                              (legacy-entry
                               ord vid
                               (legacy-accrual [[:sub fid (query-fn cell)]] [])))
                       (java.lang.ref.WeakReference. cell)))
                   [[::legacy-weak-direct 3 (fn [c] [::q c])]
                    [::legacy-weak-nested 4 (fn [c] [::q {:nested {:cell c}}])]])]
       (is (true? (evidence/install! ::tool-a))
           "the compatible reload migrates the legacy strong map")
       (is (= 2 (count (evidence/projection))) "both legacy rows migrated")
       (is (true? (gc-until #(every?
                              (fn [^java.lang.ref.WeakReference ref]
                                (nil? (.get ref)))
                              refs)))
           "no migrated raw query→cell path survives to own its own weak key")
       (is (= [] (evidence/projection))))))

(deftest compatible-hmr-reprojects-a-v2-tagged-store
  ;; rf2-1hob1 gap 1 [the P1]. `::weak-cell-entries-v2` was minted at an
  ;; intermediate head whose copier still returned metadata-bearing symbols
  ;; verbatim; the copier was FIXED under the same v2 tag. So a store
  ;; HMR-migrated at that head carries the v2 tag over a symbol whose metadata
  ;; holds a ViewCell — and `weak-store-current?` trusted ANY v2 store, skipping
  ;; sanitization forever. The current tag must reject v2 and re-project it.
  (is (true? (evidence/install! ::tool-a)))
  (let [state* @#'evidence/state*
        cell   (connect-empty! (reactive/make-cell ::v2-legacy))
        store  (:entries @state*)]
    (#'evidence/store-seed-entry!
     store cell
     (legacy-entry 5 ::v2-legacy
                   (legacy-accrual [[:sub fid (with-meta 'q {:cell cell})]] [])))
    ;; stamp the intermediate v2 tag over the seeded (metadata-bearing) store
    (swap! state* assoc-in [:entries tag-key] v2-tag)

    (is (true? (evidence/install! ::tool-a)) "the reload re-arms the same owner")

    (testing "the v2 store is treated as legacy and re-projected"
      (let [ev  (:evidence (entry-for ::v2-legacy))
            sym (get-in ev [:targets 0 2])]
        (is (= 'q sym) "value semantics preserved")
        (is (nil? (meta sym)) "…but the metadata/back-reference is gone")
        (is (false? (:targets-exact? ev)) "the dropped metadata is marked loss")
        (is (false? (:dropped-exact? ev)))
        (is (= 5 (:cell-id (entry-for ::v2-legacy))) "the ordinal survives")))

    (testing "the store is now re-tagged current, so repeat HMR is idempotent"
      (let [before (evidence/projection)]
        (is (true? (evidence/install! ::tool-a)))
        (is (true? (evidence/install! ::tool-a)))
        (is (= before (evidence/projection))
            "re-projecting an already-current row changes nothing")))))

#?(:clj
   (deftest v2-tagged-store-migration-releases-the-metadata-symbol-weak-key
     ;; The retention proof for rf2-1hob1 gap 1. A v2-tagged store's
     ;; metadata-bearing symbol is a back-edge from the entry VALUE, through the
     ;; symbol's metadata, to its own weak KEY — and a WeakHashMap entry whose
     ;; value reaches its key never collects. Trusting the v2 tag skipped
     ;; re-projection, so the row pinned forever. RED before the tag bump.
     (is (true? (evidence/install! ::tool-a)))
     (let [state* @#'evidence/state*
           ref    (let [cell  (connect-empty! (reactive/make-cell ::v2-weak))
                        store (:entries @state*)]
                    (#'evidence/store-seed-entry!
                     store cell
                     (legacy-entry 5 ::v2-weak
                                   (legacy-accrual
                                    [[:sub fid (with-meta 'q {:cell cell})]] [])))
                    (swap! state* assoc-in [:entries tag-key] v2-tag)
                    (reactive/disconnect! cell)
                    (java.lang.ref.WeakReference. cell))]
       (is (true? (evidence/install! ::tool-a)) "the compatible reload migrates")
       (is (true? (gc-until #(nil? (.get ^java.lang.ref.WeakReference ref))))
           "the re-projected v2 store no longer roots its own weak key")
       (is (= [] (evidence/projection))))))

(deftest compatible-hmr-canonicalizes-a-v3-tagged-pre-rename-cause-set
  ;; rf2-ao46i [the #6589 audit rider]. `::weak-cell-entries-v3` was current
  ;; BEFORE the disposal-cause rename, so an ordinary same-owner HMR store
  ;; tagged v3 can retain `:causes #{:value}`. `weak-store-current?` trusted
  ;; that tag (skipping sanitation) and `reproject-accrual` carried causes
  ;; verbatim, so the stale row later unioned with a fresh `:subscription`
  ;; delivery and `explain-render` exposed `#{:value :subscription}` — a
  ;; two-vocabulary set the consumer contract (:subscription/:hmr/:disposed)
  ;; forbids. RED before the v4 tag bump + cause canonicalization; observed
  ;; through the PUBLIC explain-render path, the surface whose contract the
  ;; union violated.
  (is (true? (evidence/install! ::tool-a)))
  (let [state* @#'evidence/state*
        cell   (connect-empty! (reactive/make-cell ::v3-pre-rename))
        store  (:entries @state*)]
    (#'evidence/store-seed-entry!
     store cell
     (legacy-entry 5 ::v3-pre-rename
                   (legacy-accrual [[:sub fid [::q 1]]] [])))
    ;; stamp the pre-rename current tag over the seeded store
    (swap! state* assoc-in [:entries tag-key] v3-tag)

    (is (true? (evidence/install! ::tool-a)) "the reload re-arms the same owner")

    ;; fresh post-rename movement unions into the SAME retained accrual
    (publish-target! (evidence/installed-sink) cell [:sub fid [::q 2]])

    (testing "the retained :value canonicalizes — no two-vocabulary union"
      (let [occ (first (:occurrences (tool/explain-render ::v3-pre-rename)))]
        (is (some? occ) "the migrated row reaches the public projection")
        (is (= #{:subscription} (:causes occ))
            "explain-render speaks exactly the unified vocabulary — the
            retained :value reads as :subscription, never alongside it")
        (is (= 5 (:occurrence occ)) "the ordinal survives the migration")))

    (testing "the store is re-tagged current, so repeat HMR is idempotent"
      (let [before (evidence/projection)]
        (is (true? (evidence/install! ::tool-a)))
        (is (= before (evidence/projection)))))))

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

      ;; a real re-render re-acquires fresh handles on the re-registered
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
   (deftest cleared-weak-key-compaction-drops-the-row-and-spares-the-survivor
     ;; The OTHER prune exit (rf2-un54gv). `:dead` is the proven end, but an
     ;; ordinary unmount is never proven — it leaves through weak collection,
     ;; and a WeakHashMap entry OUTLIVES its referent: the read materializes
     ;; entries whose keys can clear before `getKey` runs (the iterator holds
     ;; only the CURRENT key strongly), handing back nil. Model that exactly,
     ;; the way the CLJS sibling fakes a cleared WeakRef — a null-keyed mapping
     ;; beside a live one. RED before the fix: `lifecycle` NPEs on the cleared
     ;; key and takes the whole projection read down with it.
     (is (true? (evidence/install! ::xray)))
     (let [state*   @#'evidence/state*
           base     (:entries @state*)
           survivor (reactive/make-cell ::survivor)
           members  (doto (java.util.HashMap.)
                      (.put nil {:cell-id  0
                                 :view-id  ::cleared
                                 :evidence one-target-evidence})
                      (.put survivor {:cell-id  1
                                      :view-id  ::survivor
                                      :evidence one-target-evidence}))]
       (swap! state* assoc :entries (assoc base :members members))
       (is (= [::survivor] (mapv :view-id (evidence/projection)))
           "a collected weak key prunes the row — it does not throw")
       (is (true? (.containsKey members nil))
           "the cleared mapping is left for the host map to expunge; a nil-key
            `.remove` would target a genuine null-key mapping instead")
       (is (true? (.containsKey members survivor))
           "…and the live row is untouched"))))

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
