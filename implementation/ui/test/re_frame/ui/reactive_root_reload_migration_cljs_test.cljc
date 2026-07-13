(ns re-frame.ui.reactive-root-reload-migration-cljs-test
  "rf2-vxgfnd.168 — `root-cells` is `defonce`, and rf2-mc62sp changed its
  REPRESENTATION from persistent strong sets (`incarnation -> #{cell …}`)
  to host-specific mutable weak sets. A hot reload (CLJS `:after-load`,
  JVM `require :reload`) therefore hands the reloaded code the OLD-shape
  value: without migration the first post-reload root operation crashes
  mid-lifecycle (JVM `UnsupportedOperationException` inside `teardown!`;
  CLJS `TypeError` on the missing `:refs`/`:by-cell` slots) and leaves
  dead cells / incarnations retained.

  The fixture here SIMULATES the reload survivor: cells are mounted
  through the current code, then `seed-legacy-root-cells!` overwrites the
  registry entry with the exact pre-weak persistent-set shape, and
  `migrate-legacy-root-cells!` (the same fn the namespace runs at load)
  converges it. The proofs:

    - connected AND Activity-hidden cells survive the migration and stay
      discoverable — root teardown reaps them without host exceptions;
    - final `teardown!` (the deterministic fast path) works post-migration
      and drops the incarnation entry with its last member;
    - the migration is IDEMPOTENT and a MIXED old/new registry converges
      (already-weak entries untouched — `identical?` preserved);
    - live-member and tracked-incarnation counts return to zero after
      teardown on BOTH hosts;
    - (JVM) ordinary reconciliation-unmounted cells remain COLLECTIBLE
      after migration — the rebuilt weak set retains no strong edge.

  `.cljc` ending `-cljs-test` runs on node (`test:cljs` / `test:ui`) AND
  the JVM (`clojure -M:test`), so the migration is graft-checked on both
  hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.test-support         :as test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.ui.reactive          :as reactive]))

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
                                          (reactive/sub-read [:mig/site i] q))
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

;; ===========================================================================
;; The reload survivor: legacy strong-set entry → migration → full lifecycle
;; ===========================================================================

(deftest migration-preserves-connected-and-hidden-cells-through-root-teardown
  (rf/reg-sub :mig/a (fn [db _] (:a db)))
  (let [fid         (make-frame! :mig/frame {:a 1})
        incarnation (reactive/make-root-incarnation)
        base        (reactive/root-cell-count)
        connected   (mount! ::connected incarnation fid [[:mig/a]])
        hidden      (doto (mount! ::hidden incarnation fid [[:mig/a]])
                      (reactive/disconnect!))]
    ;; Simulate the defonce reload survivor: the pre-rf2-mc62sp namespace
    ;; left `incarnation -> #{connected hidden}` (a persistent strong set);
    ;; the reloaded code now runs against it.
    (reactive/seed-legacy-root-cells! incarnation [connected hidden])
    (reactive/migrate-legacy-root-cells!)

    (testing "both members survived the representation change"
      (is (= 2 (reactive/root-cell-count incarnation))
          "connected + Activity-hidden cells are retained through migration")
      (is (identical? incarnation (reactive/cell-root connected)))
      (is (identical? incarnation (reactive/cell-root hidden))))

    (testing "root teardown reaps BOTH through the migrated weak registry —
              no UnsupportedOperationException (JVM) / TypeError (CLJS)"
      ;; The unmount thunk is the host React `.unmount` analogue: it sweeps
      ;; the still-mounted tree's effect cleanups (`disconnect!`), which the
      ;; armed teardown window captures; the already-hidden sibling is
      ;; discovered through the (migrated) weak registry.
      (reactive/teardown-root! incarnation (fn [] (reactive/disconnect! connected)))
      (is (= :dead (reactive/lifecycle connected)))
      (is (= :dead (reactive/lifecycle hidden))
          "the already-hidden cell stayed discoverable across the migration")
      (is (= 0 (reactive/root-cell-count incarnation)))
      (is (= base (reactive/root-cell-count))
          "tracked-incarnation count returned to baseline — no retained
           incarnation, no partial registry state"))))

(deftest final-teardown-fast-path-works-on-a-migrated-entry
  (rf/reg-sub :mig/a (fn [db _] (:a db)))
  (let [fid         (make-frame! :mig/frame {:a 1})
        incarnation (reactive/make-root-incarnation)
        base        (reactive/root-cell-count)
        cell        (mount! ::solo incarnation fid [[:mig/a]])]
    (reactive/seed-legacy-root-cells! incarnation [cell])
    (reactive/migrate-legacy-root-cells!)
    (is (= 1 (reactive/root-cell-count incarnation)))
    ;; `teardown!` → `detach-root!` → `weak-remove!` — the deterministic
    ;; departure that crashed on the JVM's persistent set pre-migration.
    (reactive/teardown! cell)
    (is (= :dead (reactive/lifecycle cell)))
    (is (= 0 (reactive/root-cell-count incarnation)))
    (is (= base (reactive/root-cell-count))
        "the incarnation entry dropped with its last member (AC5 holds
         through the migrated representation)")))

;; ===========================================================================
;; Idempotence + mixed-state convergence
;; ===========================================================================

(deftest migration-is-idempotent-and-converges-a-mixed-registry
  (rf/reg-sub :mig/a (fn [db _] (:a db)))
  (let [fid      (make-frame! :mig/frame {:a 1})
        base     (reactive/root-cell-count)
        ;; OLD-shape incarnation: seeded with the legacy persistent set.
        old-inc  (reactive/make-root-incarnation)
        old-cell (mount! ::old old-inc fid [[:mig/a]])
        ;; NEW-shape incarnation: enrolled through the current weak path.
        new-inc  (reactive/make-root-incarnation)
        new-cell (mount! ::new new-inc fid [[:mig/a]])]
    (reactive/seed-legacy-root-cells! old-inc [old-cell])

    (testing "one run converges exactly the legacy entries"
      (reactive/migrate-legacy-root-cells!)
      (is (= 1 (reactive/root-cell-count old-inc)))
      (is (= 1 (reactive/root-cell-count new-inc))
          "the already-weak entry is untouched"))

    (testing "a re-run is an idempotent no-op — repeated reloads are safe"
      (reactive/migrate-legacy-root-cells!)
      (reactive/migrate-legacy-root-cells!)
      (is (= 1 (reactive/root-cell-count old-inc)))
      (is (= 1 (reactive/root-cell-count new-inc))))

    (testing "the converged registry drives the full lifecycle on both entries"
      (reactive/teardown-root! old-inc (fn [] (reactive/disconnect! old-cell)))
      (reactive/teardown-root! new-inc (fn [] (reactive/disconnect! new-cell)))
      (is (= :dead (reactive/lifecycle old-cell)))
      (is (= :dead (reactive/lifecycle new-cell)))
      (is (= base (reactive/root-cell-count))
          "live cell + tracked-incarnation counts return to zero"))))

;; ===========================================================================
;; The weak economy survives migration (JVM — deterministic GC clearing):
;; a migrated entry must not strongly pin reconciliation-unmounted cells
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
   (deftest migrated-members-remain-collectible-not-strongly-pinned
     (rf/reg-sub :mig/a (fn [db _] (:a db)))
     (let [fid         (make-frame! :mig/frame {:a 1})
           incarnation (reactive/make-root-incarnation)
           n           16
           hidden      (doto (mount! ::hidden incarnation fid [[:mig/a]])
                         (reactive/disconnect!))
           ;; Hold the churned population in an atom so the test can DROP
           ;; its last strong references deterministically (a bare local
           ;; would keep them reachable for the whole test body).
           churned*    (atom (mapv (fn [i]
                                     (doto (mount! (keyword "mig" (str "row-" i))
                                                   incarnation fid [[:mig/a]])
                                       (reactive/disconnect!)))
                                   (range n)))]
       ;; The legacy strong set retained the WHOLE churned population; seed
       ;; it exactly so, migrate, then drop the test's own strong refs.
       (reactive/seed-legacy-root-cells! incarnation (conj @churned* hidden))
       (reactive/migrate-legacy-root-cells!)
       (is (= (+ n 1) (reactive/root-cell-count incarnation))
           "precondition: every seeded member survived migration")
       (let [refs (mapv (fn [c] (java.lang.ref.WeakReference. c)) @churned*)]
         (reset! churned* nil)   ;; the last strong holder lets go
         (testing "post-migration, the churned population is GARBAGE again"
           (is (true? (gc-until #(every? (fn [^java.lang.ref.WeakReference r]
                                           (nil? (.get r)))
                                         refs)))
               "the migrated weak set retains no strong edge — the
                pre-migration strong-set pinning did not survive the rebuild")
           (is (= 1 (reactive/root-cell-count incarnation))
               "exactly the hidden sibling remains")))
       (testing "…and the hidden sibling stays reapable"
         (is (= :disconnected (reactive/lifecycle hidden)))
         (reactive/teardown-root! incarnation (fn [] nil))
         (is (= :dead (reactive/lifecycle hidden)))
         (is (= 0 (reactive/root-cell-count incarnation)))))))
