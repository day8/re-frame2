(ns re-frame.ui.reactive-reconcile-cljs-test
  "rf2-vxgfnd.8 (S2b) — the ViewCell + 8-step commit reconciler over the
  REAL observation port and the REAL sub-cache (plain-atom adapter). These
  are the headless correctness fixtures the S2 conformance rows name:

    - render probes WITHOUT ownership — 10k abandoned renders retain zero
      (G-6 / the S-3 §5 cold-probe exit criterion), mirrored at the
      ViewCell layer;
    - commit installs + reads; the kept-check retains unchanged leases
      UNTOUCHED (same lease object, no re-acquire) so re-commit is a no-op;
    - dependency change drops + acquires; a shared node never churns;
    - transactional multi-acquire: acquisition-3-of-3-throws rolls the
      staged leases back in REVERSE order, the prior committed set stays
      installed, and a shared node survives;
    - moved evidence in the render→commit gap advances the revision (step
      5/8);
    - the static override lease (Story-override door, JVM spelling) — a
      pinned value owns nothing; a version move retargets;
    - the three-state lifecycle facts + qualified retroactive annotations
      (:activity-hidden {:proof :reconnect}; :unmounted {:proof
      :host-teardown}).

  `.cljc` ending `-cljs-test` rides `npm run test:ui` / `test:cljs` (node)
  AND `clojure -M:test` (JVM), so the reconciler is graft-checked on both
  hosts. Host honesty: plain-atom derived values are not watchable, so the
  value-movement `on-change` channel is a reactive-host surface — here
  movement is caught at the commit evidence comparison (step 5), exactly
  the headless contract."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.subs                 :as subs]
            [re-frame.substrate.observation :as obs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private fid :rf/default)

(defn- sub-cache [] (:sub-cache (frame/frame fid)))
(defn- entry [q] (get @(sub-cache) q))
(defn- ref-count [q] (:ref-count (entry q)))
(defn- seed! [db] (frame/replace-app-db! fid db))
(defn- tk [q] [:sub fid q])

(defn- render!
  "Simulate one render pass: run the site probes into a fresh capture under
  the ambient frame. Returns the cell."
  [cell queries]
  (rf/with-frame fid
    (reactive/with-capture cell (fn [] (mapv reactive/sub-read queries))))
  cell)

(defn- render+commit! [cell queries]
  (render! cell queries)
  (reactive/commit! cell)
  cell)

(defn- throws-id [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

;; ===========================================================================
;; Ownership-free render — 10k abandoned renders retain zero (G-6)
;; ===========================================================================

(deftest abandoned-renders-retain-zero
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/reg-sub :r/b (fn [db _] (:b db)))
  (seed! {:a 1 :b 2})
  (let [cell (reactive/make-cell ::v)]
    (testing "10k render passes that never commit acquire NOTHING and
              materialise no cache node (probes are ownership-free)"
      (dotimes [_ 10000]
        (render! cell [[:r/a] [:r/b]]))
      (is (empty? (reactive/committed-target-keys cell))
          "no lease is installed by rendering alone")
      (is (nil? (entry [:r/a])) "no cache entry materialised by the cold probes")
      (is (nil? (entry [:r/b]))
          "the 10k-abandoned-renders-retain-zero property holds at the cell"))))

;; ===========================================================================
;; commit installs + reads; kept-check retains untouched
;; ===========================================================================

(deftest commit-installs-and-reads
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[:r/a]])]
    (is (= #{(tk [:r/a])} (reactive/committed-target-keys cell)))
    (is (= 1 (ref-count [:r/a])) "one owner installed at commit")
    (is (= {(tk [:r/a]) 1} (reactive/committed-values cell)))))

(deftest recommit-retains-lease-untouched
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell   (render+commit! (reactive/make-cell ::v) [[:r/a]])
        lease0 (reactive/committed-lease cell (tk [:r/a]))]
    (is (= 1 (ref-count [:r/a])))
    (render+commit! cell [[:r/a]])
    (is (identical? lease0 (reactive/committed-lease cell (tk [:r/a])))
        "an unchanged live lease is retained UNTOUCHED — same object, no re-acquire")
    (is (= 1 (ref-count [:r/a])) "re-commit does not churn the ref-count")))

(deftest dependency-change-drops-and-acquires
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/reg-sub :r/b (fn [db _] (:b db)))
  (seed! {:a 1 :b 2})
  (let [cell (render+commit! (reactive/make-cell ::v) [[:r/a]])]
    (is (= 1 (ref-count [:r/a])))
    (render+commit! cell [[:r/b]])
    (is (= #{(tk [:r/b])} (reactive/committed-target-keys cell)))
    (is (= 1 (ref-count [:r/b])) ":b is acquired")
    (is (nil? (entry [:r/a])) ":a is dropped — released to its zero-owner disposal edge")))

;; ===========================================================================
;; Transactional multi-acquire — staging + reverse-order rollback
;; ===========================================================================

(deftest acquisition-3-of-3-throws-rolls-back
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/reg-sub :r/b (fn [db _] (:b db)))
  (rf/reg-sub :r/c (fn [db _] (:c db)))
  (seed! {:a 1 :b 2 :c 3})
  (let [cell (reactive/make-cell ::v)]
    ;; probe all three (all registered, all cold — no cache nodes yet)
    (render! cell [[:r/a] [:r/b] [:r/c]])
    ;; make the THIRD acquire throw: unregister :r/c between render and commit
    (subs/clear-sub :r/c)
    (testing "the k-th acquisition failure rolls the staged leases back and
              leaves the prior (empty) committed set installed"
      (is (= :rf.error/no-such-sub
             (throws-id #(reactive/commit! cell)))
          "the acquisition's typed error propagates")
      (is (empty? (reactive/committed-target-keys cell))
          "no lease is installed — the reconcile aborted")
      (is (nil? (entry [:r/a]))
          "staged :r/a was released on rollback (reverse order) → disposed")
      (is (nil? (entry [:r/b])) "staged :r/b likewise disposed"))))

(deftest rollback-preserves-a-shared-node
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/reg-sub :r/b (fn [db _] (:b db)))
  (rf/reg-sub :r/c (fn [db _] (:c db)))
  (seed! {:a 1 :b 2 :c 3})
  (let [cell   (render+commit! (reactive/make-cell ::v) [[:r/a]])
        lease0 (reactive/committed-lease cell (tk [:r/a]))]
    (is (= 1 (ref-count [:r/a])) ":a committed with one owner")
    ;; new render observes :a (retained) + :b (new) + :c (new); :c will throw
    (render! cell [[:r/a] [:r/b] [:r/c]])
    (subs/clear-sub :r/c)
    (is (= :rf.error/no-such-sub (throws-id #(reactive/commit! cell))))
    (testing "the node shared with the prior committed set survives rollback"
      (is (= 1 (ref-count [:r/a])) ":a keeps its prior owner — never re-acquired, never released")
      (is (identical? lease0 (reactive/committed-lease cell (tk [:r/a]))))
      (is (= #{(tk [:r/a])} (reactive/committed-target-keys cell))
          "the prior committed set remains exactly installed")
      (is (nil? (entry [:r/b])) "the solely-rolled-back node disposed on its zero-owner edge"))))

;; ===========================================================================
;; Moved evidence corrects (step 5/8)
;; ===========================================================================

(deftest moved-evidence-advances-revision
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (render! cell [[:r/a]])                 ;; probe observed value 1 (cold)
    (is (= 0 (reactive/revision cell)))
    (seed! {:a 2})                          ;; move in the render→commit gap
    (reactive/commit! cell)                 ;; acquire+read observes 2 ≠ 1
    (is (= 1 (reactive/revision cell))
        "movement between probe and acquire advances the revision (corrects before paint)")))

(deftest unmoved-commit-does-not-advance-revision
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[:r/a]])]
    (is (= 0 (reactive/revision cell))
        "no movement in the gap ⇒ no revision advance")))

;; ===========================================================================
;; rf2-vxgfnd.93 — the reconciler consumes read's :node-key reincarnation axis
;; (completes rf2-vxgfnd.14 / #5774 on the S2b live branch). A same-id frame
;; DESTROY + RECREATE across the render→commit gap must classify as MOVED even
;; when node-version + frame/registry epochs COINCIDE across the incarnations —
;; the S2 invariant break the version+epoch-only comparison would misread as
;; unchanged. Uses a NAMED frame so the destroy+recreate is real; the plain-atom
;; adapter is CLJC so this graft-checks on node (`test:cljs`) AND JVM.
;; ===========================================================================

(deftest reincarnation-in-the-gap-detected-via-node-key-advances-revision
  (rf/reg-sub :re/v (fn [db _] (:v db)))
  (live-frame/make-frame {:id :re/frame})
  (frame/replace-app-db! :re/frame {:v 1})
  (let [cell (reactive/make-cell ::v)]
    ;; render probes a LIVE node (hold a subscribe ref so the ownership-free
    ;; probe reads a canonical node — captures node-key K_A at version 0).
    (subs/subscribe [:re/v] {:frame :re/frame})
    (rf/with-frame :re/frame
      (reactive/with-capture cell (fn [] (reactive/sub-read [:re/v]))))
    (is (= 0 (reactive/revision cell)) "precondition: no revision yet")
    ;; reincarnate in the gap: identical construction + a single replace-app-db!
    ;; makes node-version + frame/registry epochs COINCIDE with the destroyed
    ;; incarnation's (dissoc restarts the commit epoch, fresh node ⟹ version 0,
    ;; no :sub re-registration) — only the FRESH reaction's node-key differs.
    (frame/destroy-frame! :re/frame)
    (live-frame/make-frame {:id :re/frame})
    (frame/replace-app-db! :re/frame {:v 1})
    ;; commit acquires the FRESH node K_B; read K_B ≠ probe K_A ⟹ moved via the
    ;; node-key axis alone (version+epoch tie) ⟹ corrective revision advance.
    (reactive/commit! cell)
    (is (= 1 (reactive/revision cell))
        "same-id reincarnation classified MOVED via :node-key — a corrective
         render before paint (version+epoch alone MISS it — the pre-fix break)")
    (is (= :connected (reactive/lifecycle cell))
        "resolved against the LIVE fresh incarnation it acquired — not torn down")
    (frame/destroy-frame! :re/frame)))

(deftest unchanged-live-node-across-commit-does-not-false-advance
  ;; The node-key fast-path guard (rf2-vxgfnd.14 AC #4, at the ui layer): a
  ;; genuinely-unchanged live node reads the SAME node-key/version/epochs across
  ;; the render probe and the commit read, so the new :node-key clause introduces
  ;; NO false movement.
  (rf/reg-sub :re/v (fn [db _] (:v db)))
  (live-frame/make-frame {:id :re/frame2})
  (frame/replace-app-db! :re/frame2 {:v 7})
  (let [cell (reactive/make-cell ::v)]
    (subs/subscribe [:re/v] {:frame :re/frame2})   ;; a live canonical node
    (rf/with-frame :re/frame2
      (reactive/with-capture cell (fn [] (reactive/sub-read [:re/v]))))
    (reactive/commit! cell)                         ;; same live node at commit
    (is (= 0 (reactive/revision cell))
        "an unchanged live node reads the same node-key — no false movement")
    (frame/destroy-frame! :re/frame2)))

;; ===========================================================================
;; useSyncExternalStore contract — subscribe / snapshot / notify
;; ===========================================================================

(deftest snapshot-and-notify
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell  (reactive/make-cell ::v)
        hits  (atom 0)
        unsub (reactive/subscribe cell (fn [] (swap! hits inc)))]
    (is (= 0 (reactive/get-snapshot cell)) "getSnapshot is the revision integer")
    (testing "mark-dirty coalesces to ONE revision advance + notify per flush"
      (reactive/mark-dirty! cell)
      (reactive/mark-dirty! cell)
      (reactive/flush-dirty! cell)
      (is (= 1 (reactive/get-snapshot cell)))
      (is (= 1 @hits) "two marks, one notification (coalesced)"))
    (unsub)
    (reactive/mark-dirty! cell)
    (reactive/flush-dirty! cell)
    (is (= 1 @hits) "an unsubscribed listener is not notified")))

;; ===========================================================================
;; Static override lease (03 §3; item 5 — the named Tier-3 obligation,
;; headless spelling)
;; ===========================================================================

(deftest static-override-lease-owns-nothing
  ;; the override door bypasses the node entirely — the pinned value IS the
  ;; resolution; the sub need not even be registered
  (binding [reactive/*sub-overrides* {[:r/a] 99}]
    (let [cell (render+commit! (reactive/make-cell ::v) [[:r/a]])]
      (is (= #{[:override [:r/a]]} (reactive/committed-target-keys cell)))
      (is (= {[:override [:r/a]] 99} (reactive/committed-values cell)))
      (is (false? (obs/owned? (reactive/committed-lease cell [:override [:r/a]])))
          "a pinned override owns no real subscription node")
      (is (nil? (entry [:r/a])) "no cache node is built for an override"))))

(deftest static-override-version-move-retargets
  (let [cell (reactive/make-cell ::v)]
    (binding [reactive/*sub-overrides* {[:r/a] 99}]
      (render+commit! cell [[:r/a]]))
    (let [lease0 (reactive/committed-lease cell [:override [:r/a]])]
      (binding [reactive/*sub-overrides* {[:r/a] 100}]
        (render+commit! cell [[:r/a]]))
      (is (= {[:override [:r/a]] 100} (reactive/committed-values cell))
          "the moved override retargets through the normal staged path")
      (is (not (identical? lease0 (reactive/committed-lease cell [:override [:r/a]])))
          "current? failed on the version move ⇒ a fresh static lease"))))

;; ===========================================================================
;; Three-state lifecycle + retroactive annotations (03 §4; item 4)
;; ===========================================================================

(deftest lifecycle-connect-disconnect-reconnect
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (is (= :fresh (reactive/lifecycle cell)))
    (render+commit! cell [[:r/a]])
    (is (= :connected (reactive/lifecycle cell)))
    (is (= 1 (ref-count [:r/a])))

    (testing "disconnect releases owners and emits :disconnected {:reason :unknown}"
      (reactive/disconnect! cell)
      (is (= :disconnected (reactive/lifecycle cell)))
      (is (nil? (entry [:r/a])) "hidden UI must not poll — owners released")
      (is (= {:state :disconnected :reason :unknown}
             (peek (reactive/intervals cell)))))

    (testing "a reconnect reacquires AND retroactively proves an Activity hide"
      (render+commit! cell [[:r/a]])
      (is (= :connected (reactive/lifecycle cell)))
      (is (= 1 (ref-count [:r/a])) "reveal reacquires")
      (is (= {:state :disconnected :reason :activity-hidden :proof :reconnect}
             (peek (reactive/intervals cell)))
          "the PRIOR interval is annotated — never the present"))))

(deftest lifecycle-host-teardown-proves-unmount
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[:r/a]])]
    (reactive/disconnect! cell)
    (reactive/teardown! cell)
    (is (= :dead (reactive/lifecycle cell)))
    (is (= {:state :disconnected :reason :unmounted :proof :host-teardown}
           (peek (reactive/intervals cell)))
        "an explicit host/root teardown proves the interval ended in unmount")
    (testing "a :dead cell fails loudly on re-commit — no resume"
      (render! cell [[:r/a]])
      (is (= :rf.error/frame-destroyed (throws-id #(reactive/commit! cell)))))))

(deftest teardown-from-connected-records-unmount
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[:r/a]])]
    (reactive/teardown! cell)
    (is (= :dead (reactive/lifecycle cell)))
    (is (nil? (entry [:r/a])) "teardown detaches owners")
    (is (= {:state :unmounted :reason :unmounted :proof :host-teardown}
           (peek (reactive/intervals cell))))))

;; ===========================================================================
;; rf= value stabilization (03 §2, I-8) — a site returns the PRIOR exact
;; value when the new read is rf=
;; ===========================================================================

(deftest value-stabilization-returns-prior-reference
  (rf/reg-sub :r/coll (fn [db _] (:coll db)))
  (seed! {:coll [1 2 3]})
  (let [cell (render+commit! (reactive/make-cell ::v) [[:r/coll]])
        committed (get (reactive/committed-values cell) (tk [:r/coll]))]
    ;; a fresh-but-rf=-equal value on the next render returns the committed
    ;; reference (identical?), so downstream memo sees no change
    (seed! {:coll [1 2 3]})    ;; equal value, different object identity
    (let [ret (rf/with-frame fid
                (reactive/with-capture cell (fn [] (reactive/sub-read [:r/coll]))))]
      (is (identical? committed ret)
          "an rf=-stable read returns the prior exact value (stabilized identity)"))))
