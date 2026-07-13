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
  the headless contract. Step 5 reads EVERY acquired lease, RETAINED as well
  as staged, so a retained site's headless movement (which has no watch to
  self-correct) is caught too (rf2-vxgfnd.39)."
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
  the ambient frame. Returns `[render-value immutable-capture]`."
  [cell queries]
  (rf/with-frame fid
    (reactive/with-capture cell
      (fn [] (mapv (fn [i q]
                     (reactive/sub-read [:reconcile/site i] q))
                   (range) queries)))))

(defn- render+commit! [cell queries]
  (let [[_ capture] (render! cell queries)]
    (reactive/commit! cell capture))
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

(deftest commit-is-bound-to-the-exact-render-capture
  ;; rf2-vxgfnd.105 — React retains the selected render's effect closure. A
  ;; later speculative render of the same cell must not replace that closure's
  ;; commit input through shared ViewCell state.
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/reg-sub :r/b (fn [db _] (:b db)))
  (seed! {:a 1 :b 2})
  (let [cell (reactive/make-cell ::v)]
    (let [[a-value cap-a] (render! cell [[:r/a]])
          [b-value cap-b] (render! cell [[:r/b]])]
      (is (= [1] a-value))
      (is (= [2] b-value))
      (is (= [[:reconcile/site 0]] (:order cap-a))
          "A remains immutable after the later speculative B capture")
      (is (= [[:reconcile/site 0]] (:order cap-b))
          "the same lexical site may carry a different query in a later capture")
      ;; Model React selecting A and abandoning B: only A's effect closure runs.
      (reactive/commit! cell cap-a)
      (is (= #{(tk [:r/a])} (reactive/committed-target-keys cell))
          "the selected render commits its own capture, never the later B")
      (is (= 1 (ref-count [:r/a])))
      (is (nil? (entry [:r/b]))
          "the abandoned capture materialises no node and owns nothing"))))

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
  (let [cell (reactive/make-cell ::v)
        [_ capture] (render! cell [[:r/a] [:r/b] [:r/c]])]
    ;; probe all three (all registered, all cold — no cache nodes yet)
    ;; make the THIRD acquire throw: unregister :r/c between render and commit
    (subs/clear-sub :r/c)
    (testing "the k-th acquisition failure rolls the staged leases back and
              leaves the prior (empty) committed set installed"
      (is (= :rf.error/no-such-sub
             (throws-id #(reactive/commit! cell capture)))
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
    (let [[_ capture] (render! cell [[:r/a] [:r/b] [:r/c]])]
      (subs/clear-sub :r/c)
      (is (= :rf.error/no-such-sub
             (throws-id #(reactive/commit! cell capture)))))
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
  (let [cell (reactive/make-cell ::v)
        [_ capture] (render! cell [[:r/a]])] ;; probe observed value 1 (cold)
    (is (= 0 (reactive/revision cell)))
    (seed! {:a 2})                          ;; move in the render→commit gap
    (reactive/commit! cell capture)         ;; acquire+read observes 2 ≠ 1
    (is (= 1 (reactive/revision cell))
        "movement between probe and acquire advances the revision (corrects before paint)")))

(deftest unmoved-commit-does-not-advance-revision
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[:r/a]])]
    (is (= 0 (reactive/revision cell))
        "no movement in the gap ⇒ no revision advance")))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.39 — a RETAINED site's movement is caught at step 5 too. On a
;; NON-WATCHABLE headless host (plain-atom) a retained lease has NO
;; value-movement watch, so the commit evidence comparison is the ONLY
;; correction — and it must read retained leases, not only staged ones.
;; Pre-fix, step 5 read `staged` alone: a retained site's gap-movement was
;; caught by NOTHING (`:values` published the stale render value, the revision
;; never advanced, no watch existed to correct it).
;; ---------------------------------------------------------------------------

(deftest retained-lease-movement-caught-at-commit-step-5
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell   (render+commit! (reactive/make-cell ::v) [[:r/a]])
        lease0 (reactive/committed-lease cell (tk [:r/a]))]
    (is (= 0 (reactive/revision cell)) "precondition: committed, no revision yet")
    (is (= {(tk [:r/a]) 1} (reactive/committed-values cell)))
    (let [[_ capture] (render! cell [[:r/a]])] ;; render B probes value 1
      (seed! {:a 2})                        ;; move in the render→commit gap
      (reactive/commit! cell capture))      ;; kept-check RETAINS the lease
    (is (identical? lease0 (reactive/committed-lease cell (tk [:r/a])))
        "the site was RETAINED (same lease) — a kept, not staged/retargeted, site")
    (is (= 1 (reactive/revision cell))
        "a RETAINED site's gap-movement is caught at the commit evidence
         comparison (step 5) — the revision advances so the host re-renders
         before paint; pre-fix this was caught by nothing (rf2-vxgfnd.39)")))

(deftest retained-lease-unmoved-does-not-false-advance
  ;; The retained step-5 read must not introduce false movement: an unchanged
  ;; retained site reads the same version/node-key across the render→commit gap.
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[:r/a]])
        [_ capture] (render! cell [[:r/a]])] ;; render B — no gap movement
    (reactive/commit! cell capture)
    (is (= 0 (reactive/revision cell))
        "an unchanged retained site reads the same evidence ⇒ no false advance")))

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
    (let [[_ capture] (rf/with-frame :re/frame
                        (reactive/with-capture
                         cell (fn [] (reactive/sub-read ::site [:re/v]))))]
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
      (reactive/commit! cell capture)
      (is (= 1 (reactive/revision cell))
          "same-id reincarnation classified MOVED via :node-key — a corrective
           render before paint (version+epoch alone MISS it — the pre-fix break)")
      (is (= :connected (reactive/lifecycle cell))
          "resolved against the LIVE fresh incarnation it acquired — not torn down")
      (frame/destroy-frame! :re/frame))))

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
    (let [[_ capture] (rf/with-frame :re/frame2
                        (reactive/with-capture
                         cell (fn [] (reactive/sub-read ::site [:re/v]))))]
      (reactive/commit! cell capture))               ;; same live node at commit
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

    (testing "a SETTLED disconnect then reconnect proves a genuine Activity hide"
      ;; Model the host yield of a genuine reveal: the disconnect outlived its
      ;; synchronous commit (on CLJS a microtask settles it; headless we settle
      ;; explicitly). Only a SETTLED disconnect, on reconnect, proves a hide —
      ;; an UNSETTLED same-commit reconnect is a StrictMode replay (rf2-vxgfnd.44,
      ;; see `lifecycle-strictmode-replay-does-not-fabricate-hide-proof`).
      (reactive/settle-disconnect! cell)
      (render+commit! cell [[:r/a]])
      (is (= :connected (reactive/lifecycle cell)))
      (is (= 1 (ref-count [:r/a])) "reveal reacquires")
      (is (= {:state :disconnected :reason :activity-hidden :proof :reconnect}
             (peek (reactive/intervals cell)))
          "the PRIOR interval is annotated — never the present"))))

(deftest lifecycle-strictmode-replay-does-not-fabricate-hide-proof
  ;; rf2-vxgfnd.44 — React StrictMode's dev effect double-invoke is
  ;; mount→cleanup→remount within ONE synchronous commit: connect → disconnect →
  ;; reconnect with NO host yield between them, so the disconnect never settles.
  ;; That reconnect is NOT an Activity hide — the runtime observed neither a hide
  ;; nor an unmount — and it must NOT be annotated `:activity-hidden` (the pre-fix
  ;; false proof). Headless model of the replay: drive the sequence with NO
  ;; `settle-disconnect!` before the reconnect, exactly as the synchronous
  ;; StrictMode replay leaves it (the settle microtask has not yet fired).
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (render+commit! cell [[:r/a]])
    (is (= :connected (reactive/lifecycle cell)))
    (reactive/disconnect! cell)
    (is (= :disconnected (reactive/lifecycle cell)))
    ;; NO settle — the replay reconnects within the same synchronous commit.
    (render+commit! cell [[:r/a]])
    (is (= :connected (reactive/lifecycle cell)) "the replay reconnects")
    (is (= {:state :disconnected :reason :unknown}
           (peek (reactive/intervals cell)))
        "the same-commit replay disconnect stays :unknown — NOT upgraded to
         :activity-hidden; the runtime never observed a hide (rf2-vxgfnd.44)")
    (is (not-any? #(= :activity-hidden (:reason %)) (reactive/intervals cell))
        "no interval carries a fabricated Activity-hide proof")))

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
      (let [[_ capture] (render! cell [[:r/a]])]
        (is (= :rf.error/frame-destroyed
               (throws-id #(reactive/commit! cell capture))))))))

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
    (let [[ret _] (rf/with-frame fid
                    (reactive/with-capture
                     cell (fn [] (reactive/sub-read ::site [:r/coll]))))]
      (is (identical? committed ret)
          "an rf=-stable read returns the prior exact value (stabilized identity)"))))

;; ---------------------------------------------------------------------------
;; G-4 equality no-op (S2f) — an rf=-EQUAL recompute leaves the PORT VERSION
;; un-advanced ⇒ ZERO cell revision change (no React render). The
;; stabilization above proves the returned REFERENCE is stable; this proves the
;; whole commit is inert: the equality gate on the node record's version
;; (advance-node-record! bumps :version only when the value differs by =)
;; suppresses the version bump, so the commit evidence comparison (step 5) finds
;; no movement and the revision never advances.
;; ---------------------------------------------------------------------------

(deftest rf-equal-recompute-leaves-port-version-unadvanced-zero-revision
  (rf/reg-sub :r/coll (fn [db _] (:coll db)))
  (seed! {:coll [1 2 3] :n 1})
  (let [cell  (render+commit! (reactive/make-cell ::v) [[:r/coll]])
        lease (reactive/committed-lease cell (tk [:r/coll]))
        v0    (get (reactive/committed-values cell) (tk [:r/coll]))
        ver0  (:version (obs/read lease))]
    (is (= 0 (reactive/revision cell)) "precondition: committed, no revision yet")
    (is (some? ver0) "the live node has a port version")
    ;; Force the sub to RECOMPUTE — a sibling db key (:n) moves, so the frame
    ;; commits a new epoch and the reaction re-derives — but keep :coll rf=-EQUAL
    ;; (a fresh-identity, structurally-equal vector).
    (seed! {:coll [1 2 3] :n 2})
    (is (= ver0 (:version (obs/read lease)))
        "G-4: the rf=-equal recompute left the PORT VERSION un-advanced —
         advance-node-record!'s equality gate suppressed the version bump")
    ;; Re-render + commit: the retained lease reads an un-advanced version, so
    ;; step 5 finds no movement and step 8 advances NOTHING.
    (render+commit! cell [[:r/coll]])
    (is (= 0 (reactive/revision cell))
        "G-4: an rf=-equal recompute ⇒ ZERO cell revision change ⇒ NO React render")
    (is (identical? v0 (get (reactive/committed-values cell) (tk [:r/coll])))
        "…and the committed value stays the prior exact reference (stable identity)")))

(deftest nan-valued-node-is-version-and-revision-stable
  (rf/reg-sub :r/nan (fn [db _]
                       ;; Depend on :tick so each seed forces a recompute. The
                       ;; JVM constructor prevents boxed-literal identity from
                       ;; hiding the numeric equality boundary under test.
                       (:tick db)
                       #?(:clj  (Double/parseDouble "NaN")
                          :cljs js/NaN)))
  (seed! {:tick 0})
  (let [cell  (render+commit! (reactive/make-cell ::nan) [[:r/nan]])
        lease (reactive/committed-lease cell (tk [:r/nan]))
        ver0  (:version (obs/read lease))]
    (testing "repeated observation of a stable NaN does not move the node"
      (dotimes [i 3]
        (seed! {:tick (inc i)})
        (is (= ver0 (:version (obs/read lease)))
            "NaN is self-equal for observation-node versioning")))
    (testing "render→commit reconciliation therefore cannot self-schedule forever"
      (dotimes [_ 3]
        (render+commit! cell [[:r/nan]]))
      (is (= 0 (reactive/revision cell))
          "stable NaN evidence causes zero corrective revisions"))))
