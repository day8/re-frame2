(ns re-frame.schemas-reg-elision-race-test
  "Per rf2-naihn1 #1 — JVM regression coverage for the schema-derived
  ELISION RACE.

  THE BUG (pre-fix). A `reg-app-schema` mutates TWO independent atoms in
  TWO unsynchronised steps:

    step 1: (swap! schemas-by-frame assoc-in [frame-id path] meta)  ; CAS
    step 2: populate-from-schemas! → re-reads @schemas-by-frame, computes
            the `:large?` / `:sensitive?` declarations, and installs them
            into the frame's [:rf.runtime/elision …] registry via a
            read-then-replace (NOT CAS — registration is not the drainer).

  Two concurrent registrations against the SAME frame can interleave the
  step-2 refreshes so a STALE snapshot's computation lands last:

    A: writes schema A; reads {A};   computes declarations {A}
    B: writes schema B; reads {A,B}; computes {A,B}; installs {A,B}
    A: installs its older {A} — install first DELETES every :source
       :schema declaration, then merges only A, so B's declaration is
       DROPPED.

  Net: the schema side-table holds {A,B} but the runtime elision /
  sensitive registry holds only {A} until a later full repopulate. A
  `:sensitive?`-marked slot from B is then NOT redacted on a wire / trace
  / epoch / hydration emit that fires before the next dispatch — a
  sensitive value LEAKS OFF-BOX. The size-elision (`:large?`) registry
  loses entries the same way.

  THE FIX (rf2-naihn1). The side-table write + the elision/sensitive
  refresh are LINEARIZED PER FRAME (a per-frame monitor held across both
  steps). Two registrations on the same frame serialise, so the LAST
  refresh under the lock always reflects the LATEST side-table — the
  runtime registry can never be left holding a stale subset while the
  side-table holds the union.

  THE INVARIANT this file pins. After ANY sequence (concurrent or not) of
  registrations against a frame, the live runtime elision + sensitive
  registries MUST equal what `populate-from-schemas!` recomputes from the
  FINAL schema side-table — i.e. the registry reflects the union implied
  by the committed schema table, with NO lost schema-owned declaration.

  SCALE & WHY FRESH PATHS. The staleness window exists only when a NEW
  flagged path is added CONCURRENTLY with a peer's full-frame install
  (the peer's install was computed from a snapshot that PRE-DATES the new
  path, so when it lands last it drops the new path). Re-registering a
  fixed path set does NOT reproduce the bug: after a warmup round every
  install already sees the whole table, so no install is ever a stale
  subset. So each round every thread registers a FRESH disjoint flagged
  path. The side-table therefore grows to `rounds × threads × entries`,
  and each install is O(table) — so the round count is kept MODEST
  (default 600 → a few thousand entries) to stay well under the join
  budget while still opening hundreds of lockstep staleness windows. The
  pre-fix bug surfaces within the first ~hundred rounds across box/JVM
  scheduling; the env override dials it up for a deeper soak.

  CLJS is single-threaded; this race is JVM-only by construction. Round
  count is env-overridable via `RF2_NAIHN1_RACE_ROUNDS`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.malli]
            [re-frame.schemas.test-fixture :as tf])
  (:import [java.util.concurrent CyclicBarrier CountDownLatch]))

(use-fixtures :each tf/reset-runtime)

;; Round count. Each round opens a fresh staleness window (every thread
;; adds a NEW flagged path under a CyclicBarrier release, so a peer's
;; in-flight full-frame install can land last and drop the new path). The
;; pre-fix race surfaces within the first ~hundred rounds; the default is
;; kept modest because each fresh path grows the O(table) install — 600
;; rounds × 6 threads ≈ a few thousand entries, fast enough under the
;; join budget while opening plenty of windows. CI dials down / soaks up
;; via the env override.
(def ^:private rounds
  (or (some-> (System/getenv "RF2_NAIHN1_RACE_ROUNDS") Long/parseLong)
      600))

(def ^:private n-threads 6)

;; ---- the linearization invariant -----------------------------------------

(defn- recompute-from-side-table
  "Recompute the schema-derived `:large?` + `:sensitive?` declarations a
  frame's CURRENT side-table implies, by re-running the same populate the
  registration path runs. Returns `{:large {…} :sensitive {…}}` read back
  off the runtime registry AFTER a fresh full repopulate — the canonical
  'union implied by the final schema table'."
  [frame-id]
  (elision/populate-from-schemas! frame-id)
  {:large     (elision/declarations frame-id)
   :sensitive (elision/sensitive-declarations frame-id)})

(defn- live-registry
  "The runtime elision + sensitive registries as they currently stand
  (what an off-box wire / trace emit would redact against), WITHOUT
  recomputing."
  [frame-id]
  {:large     (elision/declarations frame-id)
   :sensitive (elision/sensitive-declarations frame-id)})

;; ---- 1. Concurrent disjoint-flag re-registration on one shared frame ------

(deftest concurrent-disjoint-flag-regs-never-lose-a-declaration
  ;; rf2-naihn1 #1 — the load-bearing race regression.
  ;;
  ;; N threads, ONE shared frame. Each round every thread adds its OWN
  ;; fresh disjoint flagged paths (a `:sensitive?` slot and a `:large?`
  ;; slot under a per-(thread, round) base), in lockstep via a
  ;; CyclicBarrier so the step-1/step-2 interleaving window is maximised.
  ;; A fresh path is essential: the staleness window only exists while a
  ;; NEWLY-added path is concurrent with a peer's in-flight install that
  ;; pre-dates it. Pre-fix, a round where thread A's stale install (built
  ;; from a snapshot lacking B's fresh path) lands after B's newer install
  ;; DROPS B's declaration from the live registry even though B's schema
  ;; is in the side-table — the invariant below catches it.
  ;;
  ;; INVARIANT (the one that catches the bug): after the storm the LIVE
  ;; runtime registry (what an off-box emit redacts against) must equal
  ;; the union implied by the committed side-table. We assert it by
  ;; comparing the live registry to a fresh full recompute: post-fix they
  ;; are always equal (the lock guarantees the last install reflects the
  ;; full table); pre-fix a raced round leaves the live registry a STRICT
  ;; SUBSET (a lost sensitive/large declaration) and the equality fails.
  (testing (str rounds " rounds × " n-threads
                " threads, one shared frame — live elision/sensitive "
                "registry never loses a declaration to a stale install")
    (let [frame-id  :naihn1.race/shared
          barrier   (CyclicBarrier. n-threads)
          latch     (CountDownLatch. 1)]
      (rf/reg-frame frame-id {:doc "shared frame for the elision-race repro"})
      (let [futs
            (vec
              (for [t (range n-threads)]
                (future
                  (.await latch)
                  (dotimes [round rounds]
                    (let [base [:naihn1 (keyword (str "t" t))
                               (keyword (str "r" round))]]
                      (.await barrier)
                      (rf/reg-app-schema
                        (conj base :s)
                        [:map [:tok {:sensitive? true} :string]]
                        {:frame frame-id})
                      (rf/reg-app-schema
                        (conj base :l)
                        [:map [:b {:large? true} :string]]
                        {:frame frame-id}))))))]
        (.countDown latch)
        (doseq [f futs]
          (let [v (deref f 120000 ::timeout)]
            (is (not= ::timeout v)
                "race-reg thread completed within 120s wall-clock")))

        ;; --- The invariant: the live registry == the union implied by
        ;; the committed side-table. Recompute from the final table and
        ;; compare to what is actually installed. Any lost declaration
        ;; shows up as the live registry being a proper subset.
        (let [live       (live-registry frame-id)
              recomputed (recompute-from-side-table frame-id)]
          (is (= live recomputed)
              (str "Live runtime elision/sensitive registry diverged from "
                   "the union implied by the final schema side-table — a "
                   "stale install dropped a schema-owned declaration "
                   "(off-box redaction would leak). Missing large: "
                   (pr-str (apply dissoc (:large recomputed)
                                  (keys (:large live))))
                   "; missing sensitive: "
                   (pr-str (apply dissoc (:sensitive recomputed)
                                  (keys (:sensitive live))))))

          ;; Belt-and-braces: the full union cardinality is present (every
          ;; round contributed one sensitive + one large path per thread).
          (is (= (* rounds n-threads) (count (:sensitive live)))
              "every thread's every-round sensitive path is a live decl")
          (is (= (* rounds n-threads) (count (:large live)))
              "every thread's every-round large path is a live decl"))))))

;; ---- 2. Mixed singular + bulk concurrent re-registration ------------------

(deftest concurrent-singular-and-bulk-regs-stay-coherent
  ;; rf2-naihn1 #1 — the bulk path takes the SAME per-frame lock, so a
  ;; concurrent singular `reg-app-schema` cannot slot its install between
  ;; a `reg-app-schemas` batch's writes and the batch's single final
  ;; install. Half the threads run singular regs, half run bulk regs, each
  ;; adding fresh flagged paths every round on the shared frame; the same
  ;; union invariant must hold.
  (testing (str rounds " rounds — concurrent singular + bulk regs on one "
                "frame leave the live registry == the committed-table union")
    (let [frame-id  :naihn1.race/mixed
          barrier   (CyclicBarrier. n-threads)
          latch     (CountDownLatch. 1)]
      (rf/reg-frame frame-id {:doc "shared frame for mixed singular/bulk race"})
      (let [futs
            (vec
              (for [t (range n-threads)]
                (future
                  (.await latch)
                  (dotimes [round rounds]
                    (let [base [:mixed (keyword (str "t" t))
                               (keyword (str "r" round))]]
                      (.await barrier)
                      (if (even? t)
                        (rf/reg-app-schema
                          (conj base :s)
                          [:map [:token {:sensitive? true} :string]]
                          {:frame frame-id})
                        (rf/reg-app-schemas
                          {(conj base :b1)
                           [:map [:secret {:sensitive? true} :string]]
                           (conj base :b2)
                           [:map [:blob {:large? true} :string]]}
                          {:frame frame-id})))))))]
        (.countDown latch)
        (doseq [f futs]
          (is (not= ::timeout (deref f 120000 ::timeout))
              "mixed-reg thread completed within 120s wall-clock"))
        (let [live       (live-registry frame-id)
              recomputed (recompute-from-side-table frame-id)]
          (is (= live recomputed)
              (str "Mixed singular/bulk concurrent registration left the "
                   "live registry diverged from the committed-table "
                   "union — a stale install survived. Missing large: "
                   (pr-str (apply dissoc (:large recomputed)
                                  (keys (:large live))))
                   "; missing sensitive: "
                   (pr-str (apply dissoc (:sensitive recomputed)
                                  (keys (:sensitive live)))))))))))

;; ---- 3. Deterministic single-thread linearization post-condition ----------

(deftest sequential-regs-leave-registry-equal-to-recompute
  ;; A deterministic (non-concurrent) pin of the same post-condition: the
  ;; live registry after a sequence of registrations equals a fresh
  ;; recompute from the side-table. This is the invariant the concurrent
  ;; tests assert under contention; pinning it sequentially documents the
  ;; intended end state and guards against a refactor that lets the per-
  ;; entry refresh drift from the table even single-threaded.
  (testing "sequential registrations leave the live registry == recompute"
    (let [frame-id :naihn1.race/seq]
      (rf/reg-frame frame-id {:doc "deterministic linearization pin"})
      (rf/reg-app-schema [:a] [:map [:secret {:sensitive? true} :string]]
                         {:frame frame-id})
      (rf/reg-app-schema [:b] [:map [:blob {:large? true} :string]]
                         {:frame frame-id})
      (rf/reg-app-schemas {[:c] [:map [:tok {:sensitive? true} :string]]
                           [:d] [:map [:big {:large? true} :string]]}
                          {:frame frame-id})
      ;; Re-registration that REMOVES a flag must prune, leaving the live
      ;; registry still == recompute (the refresh-not-accrete contract).
      (rf/reg-app-schema [:a] [:map [:secret :string]] {:frame frame-id})
      (let [live (live-registry frame-id)]
        ;; Snapshot the live registry, THEN recompute and compare. (The
        ;; recompute is itself a populate, but it is idempotent — running
        ;; it does not change a registry that already reflects the table.)
        (is (= live (recompute-from-side-table frame-id))
            "live registry already reflects the full side-table union")
        (is (not (contains? (:sensitive live) [:a :secret]))
            "the flag-removed re-registration pruned the stale sensitive decl")
        (is (contains? (:sensitive live) [:c :tok]))
        (is (contains? (:large live) [:b :blob]))
        (is (contains? (:large live) [:d :big]))))))
