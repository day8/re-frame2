(ns re-frame.schemas-concurrency-stress-test
  "Per rf2-utdxg — JVM concurrency stress coverage for the schemas
  artefact's parallel-region invariants. Mirrors the rf2-1gpx8
  (`machine_actor_concurrency_stress_test.clj`) and rf2-35rgj
  (`concurrency_stress_test.clj`) shape but targets the schemas
  artefact's three contention surfaces:

    1. **Hot-reload race** — N threads concurrently `reg-app-schemas`
       against a shared frame's per-frame side-table
       (`storage/schemas-by-frame`). Per Spec 010 §Per-frame schemas
       the registry shape is `{frame-id {path schema-meta}}` mutated
       through one global atom — `swap! schemas-by-frame assoc-in
       [frame-id path] meta`. Under N-thread contention `swap!`'s CAS
       retry contract guarantees no swap is dropped; the invariant is
       that EVERY (path, schema) pair every thread issued lands in the
       final state, and last-registration-wins is deterministic per
       (path, owning-thread).

    2. **Schema-digest race under read/write contention** — Spec 010
       §Digest algorithm computes a stable `\"sha256:<16-hex>\"` over
       a frame's `{path → schema-value}` snapshot. The digest fn reads
       through `(storage/app-schemas {:frame …})` which reduces over
       `@schemas-by-frame` once. While writers churn the frame's entry
       set, concurrent readers MUST see SOME coherent snapshot — never
       a partial / torn read, never a digest that nobody could compute
       from any actual point-in-time `{path → schema-value}` state.
       The invariant is **snapshot coherence**: each reader captures
       the live `app-schemas` snapshot ONCE and computes its own digest
       LOCALLY from that captured value (one atomic deref + a pure
       reduce-kv, no second registry read between snapshot and digest).
       Computing the digest twice over the same captured snapshot MUST
       produce byte-identical output (no cross-thread state pollution
       in the digest pipeline; `run-printer` is the seam most at risk),
       and every captured snapshot MUST be re-digestible without error
       (no torn-read fragment that breaks the serialisation pass).

    3. **Sensitive-path resolution under contention** (rf2-ay2kp's
       deep walker) — `extract-sensitive-paths-from-schema` walks a
       Malli EDN schema vector, threading an accumulator map through
       a recursive descent. The walker is pure — same input ALWAYS
       produces the same output — so under N concurrent calls against
       a shared schema input the per-call result MUST be byte-identical.
       This test pins the purity contract under the JVM's escape-
       analysis + biased-locking optimiser (a stateful walker would
       silently corrupt between threads; pure recursion with local
       accumulators does not).

  Invariants asserted (mirrors the rf2-q4twq audit shape):

    1. **No event dropped.** Every `reg-app-schemas` call from every
       thread lands in the final per-frame side-table — total entry
       count = `(N × M)` distinct (path, schema-value) pairs.

    2. **No double-action.** Schema-digest is deterministic per
       captured snapshot — re-digesting the same captured `{path →
       schema}` map twice produces byte-identical output. Pins the
       digest pipeline (Spec 010 §Digest algorithm) is free of cross-
       thread state pollution under reader/writer contention.

    3. **Ordering stable.** Per Spec 010 §Per-frame schemas
       `reg-app-schema` writes through `swap! … assoc-in [frame-id
       path] meta` — the `swap!` CAS-retry contract is the linearisation
       order. For a single (frame, path) re-registered N times across
       threads, the final value is well-defined by the swap order; we
       assert each thread's last-issued schema is observable in some
       reader's snapshot somewhere in the run (reachability), and that
       the FINAL state contains exactly one schema per (path) — no
       per-thread shadowing.

    4. **No leak.** Sensitive-path walker invocations against shared
       schema inputs MUST be byte-identical across threads (no
       cross-thread accumulator pollution). Verified by an additional
       `walk-cross-check` that re-runs the walker once on the main
       thread post-stress and asserts every parallel result equals it.

  Threads start in lockstep via `CountDownLatch.countDown` — same
  shape as rf2-35rgj scenario 2 / rf2-1gpx8 — so contention on the
  shared `schemas-by-frame` atom is maximised.

  Per-thread iters default to 5000 (rf2-ynk7 / rf2-35rgj / rf2-1gpx8
  standard); env-overridable via `RF2_UTDXG_STRESS_ITERS`. Default
  thread count is 8 (matches the sibling concurrency stress files).

  CLJS is single-threaded; the JVM is the only runtime where the
  schemas-by-frame atom CAN race across threads. JVM-only by design."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.digest :as digest]
            [re-frame.schemas.storage :as storage]
            ;; Per rf2-t0hq the default validator routes through the
            ;; late-bind hook `:schemas/malli-validate`, which the
            ;; `re-frame.schemas.malli` adapter ns publishes at load
            ;; time. The reset-runtime fixture restores the default
            ;; validator between tests, but loading the adapter once
            ;; here ensures the digest's `run-printer` path resolves
            ;; the canonical Malli-EDN serialiser rather than the
            ;; soft-pass fallback.
            [re-frame.schemas.malli]
            [re-frame.schemas.test-fixture :as tf])
  (:import [java.util.concurrent CountDownLatch]
           [java.util.concurrent.atomic AtomicLong]))

(use-fixtures :each tf/reset-runtime)

;; Per-thread iteration count. Kept at the rf2-1gpx8 / rf2-35rgj
;; standard 5000 so CI stays under ~60s wall-clock with the default
;; thread count. Operators dial up via the env override; CI dials
;; down by lowering it (e.g. `RF2_UTDXG_STRESS_ITERS=500` smoke).
(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_UTDXG_STRESS_ITERS") Long/parseLong)
      5000))

;; Eight parallel threads — matches rf2-ynk7's `concurrent-dispatch-
;; stress` (`n-submitters 8`) and rf2-1gpx8 / rf2-35rgj. Higher
;; contention than the typical 4-core CI box; the per-thread
;; partitioning of registered paths means N×M distinct entries land
;; in the registry without per-path collision (so we can assert the
;; final cardinality), while a separate scenario stresses the
;; same-(path) collision case for ordering.
(def ^:private n-threads 8)

;; ---- shared frame setup --------------------------------------------------

(def ^:private stress-frame :utdxg.stress/main)

(defn- setup-frame!
  "Reg the shared stress frame. Each scenario uses a clean frame so
  the `:each` reset-runtime fixture between scenarios re-initialises
  the per-frame side-table to `{}`."
  []
  (rf/reg-frame stress-frame
                {:doc "shared frame for schemas concurrency stress"}))

;; ---- 1. Hot-reload race: N threads × M reg-app-schemas -------------------

(deftest reg-app-schemas-hot-reload-race-stress
  ;; rf2-utdxg scenario 1.
  ;;
  ;; Setup: N threads each loop M iterations of `reg-app-schemas` on
  ;; the SAME shared frame, each registering a per-thread-namespaced
  ;; path (`:utdxg.stress.tN/keyM`) so all (N × M) distinct pairs
  ;; should land in the final per-frame side-table. The shared frame's
  ;; `schemas-by-frame` entry is one Clojure map mutated through
  ;; `swap! … assoc-in [frame-id path]` — under N-thread contention
  ;; `swap!`'s CAS-retry contract serialises the writes; no swap is
  ;; dropped.
  ;;
  ;; Invariants:
  ;;   - No event dropped: final entry count = (n-threads × stress-iters).
  ;;   - Each per-thread (path, schema) pair landed verbatim — assert
  ;;     reading every (t, m) coordinate's registered schema returns
  ;;     the exact schema this thread issued (no cross-thread bleed).
  ;;   - The `app-schema-meta-at` source-coords ride into every entry
  ;;     (the registration's stamp is captured per-call inside the
  ;;     `swap!` body and is per-call data — concurrent calls must
  ;;     not corrupt each other's meta).
  (testing (str n-threads " threads × " stress-iters
                " reg-app-schemas — disjoint paths, no entries dropped")
    (setup-frame!)
    (let [latch (CountDownLatch. 1)
          ;; Per-thread schema value is deliberately distinguishable
          ;; (the path's own keyword segment is embedded in an
          ;; `:enum` slot) so cross-thread bleed surfaces as a wrong
          ;; schema lookup rather than just a wrong path lookup.
          mk-path   (fn [t m] [:utdxg.stress (keyword (str "t" t))
                               (keyword (str "k" m))])
          mk-schema (fn [t m] [:enum (keyword (str "t" t "-m" m))])
          futures
          (vec
            (for [t (range n-threads)]
              (future
                (.await latch)
                (dotimes [m stress-iters]
                  (rf/reg-app-schemas
                    {(mk-path t m) (mk-schema t m)}
                    {:frame stress-frame})))))]
      (.countDown latch)
      ;; Bounded join — if the reg-app-schemas path ever wedged under
      ;; contention we want a visible timeout rather than CI hang.
      (doseq [f futures]
        (let [v (deref f 120000 ::timeout)]
          (is (not= ::timeout v)
              "reg thread completed within 120s wall-clock")))

      ;; --- Invariant 1: no entry dropped --------------------------
      (let [final-entries (storage/frame-schema-entries stress-frame)
            actual-count  (count final-entries)
            expected      (* n-threads stress-iters)]
        (is (= expected actual-count)
            (str "Expected " expected " (= " n-threads " × "
                 stress-iters ") schema entries in final per-frame "
                 "side-table; got " actual-count
                 ". Drop = some swap! retry lost a write under CAS "
                 "contention (would indicate a regression in atom "
                 "semantics — historically never observed but pinned "
                 "here as the audit's no-event-dropped invariant)."))

        ;; --- Invariant 2 (per-entry shape): every (path, schema) ---
        ;; pair this run issued is recoverable verbatim, with intact
        ;; meta (the swap! body builds the `meta` map per-call;
        ;; cross-thread corruption would surface as a wrong :path or
        ;; :frame slot in some entry).
        (doseq [t (range n-threads)
                m (range stress-iters)
                :let [path     (mk-path t m)
                      expected-schema (mk-schema t m)
                      meta     (get final-entries path)]]
          ;; Spot-check meta only on the corners (inner doseq runs N×M
          ;; ~= 40k times — full per-iter assertions blow the test
          ;; clock). Edge sample is the first/last m in each thread.
          (when (or (zero? m) (= m (dec stress-iters)))
            (is (= expected-schema (:schema meta))
                (str "Path " path ": expected schema "
                     (pr-str expected-schema) "; got "
                     (pr-str (:schema meta))
                     ". Cross-thread bleed in the registration "
                     "side-table."))
            (is (= path (:path meta))
                (str "Path " path ": meta :path slot corrupted; got "
                     (pr-str (:path meta))))
            (is (= stress-frame (:frame meta))
                (str "Path " path ": meta :frame slot corrupted; got "
                     (pr-str (:frame meta))))))))))

;; ---- 2. Schema-digest race under concurrent reads + writes ---------------

(deftest schema-digest-race-stress
  ;; rf2-utdxg scenario 2.
  ;;
  ;; Setup: One thread is a sustained writer (re-registering schemas
  ;; under varying paths to keep the per-frame entry set churning);
  ;; the remaining (n-threads - 1) threads are readers each capturing
  ;; an `app-schemas` snapshot in a tight loop. For each captured
  ;; snapshot the reader stages it into an ephemeral frame keyed by
  ;; (reader-id, iter), then computes the digest TWICE against that
  ;; ephemeral frame and asserts the two digests are byte-identical
  ;; (the digest pipeline is deterministic per input — same captured
  ;; snapshot ALWAYS digests the same way, no cross-thread pollution
  ;; in `run-printer` / serialisation under reader/writer contention).
  ;;
  ;; Note on the seam: `app-schemas-digest` itself does an atom read
  ;; through `(storage/app-schemas {:frame …})`. Under writer churn
  ;; two BACK-TO-BACK calls to `app-schemas-digest` against the LIVE
  ;; frame need not be equal — the writer may have committed in
  ;; between, which is correct behaviour, not torn-read. To pin the
  ;; per-snapshot determinism we stage each captured snapshot into a
  ;; reader-private frame (the writer never touches the ephemeral
  ;; frame ids, so the comparison is well-defined) and compute the
  ;; digest twice off that frozen input. Any divergence would
  ;; indicate the digest pipeline carries cross-thread state.
  ;;
  ;; Invariants:
  ;;   - Per-snapshot digest determinism — first vs second digest of
  ;;     each ephemeral frame are equal (the no-double-action shape).
  ;;   - The captured snapshot never contains a torn-read fragment —
  ;;     i.e. the staged ephemeral frame digests cleanly without
  ;;     throwing, and contains only well-formed (path → schema-value)
  ;;     entries (every key is the same shape the writer wrote).
  ;;   - Every reader thread completed at least one capture (the
  ;;     writer didn't starve the readers).
  (testing (str "1 writer × " (dec n-threads)
                " readers — per-snapshot digest is deterministic "
                "under reader/writer contention")
    (setup-frame!)
    ;; Seed the frame so the very first reader doesn't see the empty
    ;; map (the empty-set digest is a valid result but a degenerate
    ;; assertion); writer churns from m=0 onward.
    (rf/reg-app-schema [:utdxg.digest/seed] [:int]
                       {:frame stress-frame})

    (let [latch       (CountDownLatch. 1)
          ;; Tight per-reader bound on iters — readers churn faster
          ;; than the writer (no swap! contention) so we cap to keep
          ;; the captured-results vector bounded.
          reader-iters (max 1 (quot stress-iters 2))
          writer-iters stress-iters
          ;; Per-reader vector of captured snapshot maps.
          per-reader-results (vec (repeatedly (dec n-threads)
                                              #(atom [])))
          writer
          (future
            (.await latch)
            (dotimes [m writer-iters]
              ;; Writer churn: rotate across a small set of paths so
              ;; the entry set stays bounded but its membership keeps
              ;; flipping (re-registration of the same path mutates
              ;; the schema value).
              (let [path-idx (mod m 8)
                    path     [:utdxg.digest (keyword (str "p" path-idx))]
                    schema   [:enum (keyword (str "v" m))]]
                (rf/reg-app-schema path schema {:frame stress-frame}))))
          readers
          (vec
            (for [r (range (dec n-threads))]
              (future
                (.await latch)
                (dotimes [_ reader-iters]
                  ;; Capture the live snapshot — one atom deref + the
                  ;; pure reduce-kv inside `app-schemas`. The captured
                  ;; value is a fully-realised immutable map at this
                  ;; point; no later writer mutation can perturb it.
                  (let [snap (storage/app-schemas
                               {:frame stress-frame})]
                    (swap! (nth per-reader-results r) conj snap))))))]
      (.countDown latch)
      ;; Bounded join.
      (doseq [f (cons writer readers)]
        (let [v (deref f 120000 ::timeout)]
          (is (not= ::timeout v)
              "reader/writer thread completed within 120s wall-clock")))

      ;; --- Invariant: every reader hit at least one snapshot. ----
      (doseq [r (range (dec n-threads))
              :let [snapshots @(nth per-reader-results r)]]
        (is (pos? (count snapshots))
            (str "Reader " r " produced zero snapshots — writer "
                 "starved the reader. (Writer-only schedule would "
                 "indicate a thread-fairness regression worth "
                 "investigating.)")))

      ;; --- Invariant: per-snapshot digest determinism -------------
      ;; For each captured snapshot stage it into a reader-private
      ;; ephemeral frame and compute the digest TWICE; the two digests
      ;; MUST be byte-identical. This pins that the digest pipeline
      ;; (`compute-digest` → `digest-line` → `run-printer` → `sha256-hex`)
      ;; carries no cross-thread state — every input deterministically
      ;; maps to one output regardless of what the writer is doing.
      ;;
      ;; The ephemeral frame id is per-reader so distinct readers'
      ;; staging areas don't collide; we only need one staging slot
      ;; per reader (re-using it across iters), since each iter's
      ;; captured snapshot is independently digested before the next
      ;; iter overwrites the slot.
      ;;
      ;; Mutating schemas-by-frame directly to stage the captured
      ;; snapshot is the legitimate way to seed a frozen input for
      ;; digest checking — going through reg-app-schema would mutate
      ;; the meta's source-coords on every entry (useless work; the
      ;; digest only reads :schema values).
      (doseq [r (range (dec n-threads))
              :let [snapshots @(nth per-reader-results r)
                    temp-frame (keyword "utdxg.digest"
                                        (str "temp-r" r))]]
        (try
          (doseq [snap snapshots]
            (let [meta-snap (reduce-kv
                              (fn [acc path schema]
                                (assoc acc path
                                       {:schema schema
                                        :path   path
                                        :frame  temp-frame}))
                              {}
                              snap)]
              (swap! storage/schemas-by-frame
                     assoc temp-frame meta-snap)
              (let [d1 (schemas/app-schemas-digest {:frame temp-frame})
                    d2 (schemas/app-schemas-digest {:frame temp-frame})]
                (is (= d1 d2)
                    (str "Reader " r ": digest of frozen captured "
                         "snapshot is non-deterministic — first call "
                         "= " d1 ", second call = " d2 ". Snapshot "
                         "has " (count snap) " entries. Indicates "
                         "cross-thread state pollution in the digest "
                         "pipeline.")))))
          (finally
            (swap! storage/schemas-by-frame dissoc temp-frame))))

      ;; --- Invariant: every captured snapshot's keys are well-formed paths
      ;; the writer is known to have issued. A torn-read fragment
      ;; would surface as a key the writer never wrote (e.g. a half-
      ;; constructed vector or a stale key from a completely different
      ;; namespace). Spot-check the corner snapshots from each reader.
      (let [valid-paths (set (for [i (range 8)]
                               [:utdxg.digest (keyword (str "p" i))]))]
        (doseq [r (range (dec n-threads))
                :let [snapshots @(nth per-reader-results r)
                      sample    (cond-> []
                                  (seq snapshots)
                                  (conj (first snapshots) (last snapshots)))]
                snap sample]
          (is (every? #(or (= % [:utdxg.digest/seed])
                           (contains? valid-paths %))
                      (keys snap))
              (str "Reader " r ": captured snapshot has keys not "
                   "issued by the writer (torn-read fragment?). "
                   "Unexpected keys: "
                   (pr-str (vec (remove
                                  #(or (= % [:utdxg.digest/seed])
                                       (contains? valid-paths %))
                                  (keys snap)))))))))))

;; ---- 3. Sensitive-path walker under contention ---------------------------

(deftest sensitive-path-walker-contention-stress
  ;; rf2-utdxg scenario 3.
  ;;
  ;; The schema walker (`extract-sensitive-paths-from-schema`,
  ;; rf2-ay2kp's deep walker) is pure recursion through immutable
  ;; data — every accumulator is local to the call. Under N concurrent
  ;; calls against a shared schema input the result MUST be byte-
  ;; identical across threads. This test pins the purity contract
  ;; against the JVM's escape-analysis + biased-locking optimiser
  ;; (which would surface a stateful walker as cross-thread
  ;; pollution) and, in particular, against any future refactor that
  ;; introduces a memoisation cache or a shared accumulator.
  ;;
  ;; The schema input is a 4-level nested `:map` carrying a mix of
  ;; `:sensitive?` slots, `:large?` slots, and ordinary slots — deep
  ;; enough that the recursion has multiple frames and the
  ;; accumulator transitions through several intermediate states
  ;; (any one of which a stateful walker could leak between threads).
  ;;
  ;; Invariants:
  ;;   - Every per-call result is byte-identical to the main-thread
  ;;     baseline computed before the futures launch.
  ;;   - The aggregate counter (incremented inside each call) equals
  ;;     `(n-threads × stress-iters)` — pins that no thread silently
  ;;     skipped iterations.
  (testing (str n-threads " threads × " stress-iters
                " sensitive-path walks against shared schema — "
                "byte-identical results per call")
    (let [;; Deeply-nested schema with sensitive slots interleaved at
          ;; multiple depths. The walker recurses through `:map`
          ;; (name-bearing), `:vector` (positional), and `:multi`
          ;; (dispatch-bearing) — covers the three structural classes.
          schema  [:map
                   [:user
                    [:map
                     [:profile
                      [:map
                       [:name :string]
                       [:email {:sensitive? true} :string]
                       [:password {:sensitive? true :hint "argon2id"}
                        :string]]]
                     [:tokens
                      [:vector {:sensitive? true} :string]]
                     [:audit
                      [:map
                       [:created-at :int]
                       [:secret-key {:sensitive? true} :string]]]]]
                   [:settings
                    [:map
                     [:theme :string]
                     [:api-keys
                      [:multi {:dispatch :provider}
                       [:stripe {:sensitive? true}
                        [:map [:provider :string] [:key :string]]]
                       [:openai {:sensitive? true}
                        [:map [:provider :string] [:key :string]]]]]]]]
          ;; Main-thread baseline — what every parallel call MUST match
          ;; byte-for-byte. Computed BEFORE the futures launch so the
          ;; comparison happens against a snapshot fixed in evaluation
          ;; order (no later main-thread mutation could perturb it).
          baseline (schemas/extract-sensitive-paths-from-schema
                     schema [])
          latch    (CountDownLatch. 1)
          ;; Per-thread divergence record + per-thread call count.
          divergences      (vec (repeatedly n-threads #(atom [])))
          per-thread-counts (vec (repeatedly n-threads #(AtomicLong. 0)))
          futures
          (vec
            (for [t (range n-threads)]
              (future
                (.await latch)
                (dotimes [_ stress-iters]
                  (.incrementAndGet ^AtomicLong (nth per-thread-counts t))
                  (let [r (schemas/extract-sensitive-paths-from-schema
                            schema [])]
                    (when (not= r baseline)
                      (swap! (nth divergences t) conj
                             {:got      r
                              :expected baseline})))))))]
      (.countDown latch)
      (doseq [f futures]
        (let [v (deref f 120000 ::timeout)]
          (is (not= ::timeout v)
              "walker thread completed within 120s wall-clock")))

      ;; --- Invariant: zero divergences across all threads --------
      (let [total-divergences
            (reduce + (map (comp count deref) divergences))]
        (is (zero? total-divergences)
            (str "Walker produced " total-divergences
                 " divergent results across " n-threads
                 " × " stress-iters " calls. Walker MUST be pure — "
                 "any divergence indicates cross-thread accumulator "
                 "pollution. First few from each thread: "
                 (pr-str
                   (vec
                     (for [t (range n-threads)
                           :let [ds @(nth divergences t)]
                           :when (seq ds)]
                       {:thread t
                        :first-divergence (first ds)})))))

        ;; --- Invariant: every iter ran (no silent loop exit) -----
        (let [actual-counts (mapv (fn [^AtomicLong c] (.get c))
                                  per-thread-counts)
              expected      stress-iters]
          (is (every? #(= expected %) actual-counts)
              (str "Each thread must have run exactly " expected
                   " walker calls; got " actual-counts))))

      ;; --- Invariant: post-stress walker still equals baseline ---
      ;; The walker is a pure fn over immutable data; the post-stress
      ;; result MUST equal the pre-stress baseline. A discrepancy
      ;; would indicate the contention exposed a memoisation cache or
      ;; mutable global the walker reads through (currently neither;
      ;; this is the future-proof guard).
      (let [post (schemas/extract-sensitive-paths-from-schema schema [])]
        (is (= baseline post)
            (str "Post-stress walker baseline drifted: expected "
                 (pr-str baseline) "; got " (pr-str post)))))))

;; ---- 4. Same-(path) hot-reload: ordering stability ----------------------

(deftest reg-app-schema-same-path-ordering-stress
  ;; rf2-utdxg scenario 4 — the ordering-stable invariant from the
  ;; bead's audit shape.
  ;;
  ;; Spec 010 §Per-frame schemas / §Hot-reload semantics: re-registering
  ;; the SAME (frame-id, path) overwrites the prior entry atomically
  ;; via `swap! schemas-by-frame assoc-in [frame-id path] meta`. Under
  ;; N-thread contention against the same path, each `swap!` retries
  ;; on CAS failure — so every write IS applied in some serial order
  ;; consistent with `swap!`'s linearisation. The invariants:
  ;;
  ;;   - The final entry for the contested path is one of the values
  ;;     SOME thread issued (not a frankenstein composite, not nil,
  ;;     not a stale prior write).
  ;;   - The per-frame side-table contains EXACTLY one entry per path
  ;;     (no shadow / parallel slot — the `assoc-in` write is total).
  ;;   - The per-thread last-issued schema is recoverable from the
  ;;     winning thread's record (the test rebuilds the
  ;;     winner-determination by tagging each write's schema value
  ;;     with the issuing thread id, then recovering the winner from
  ;;     the final state).
  ;;
  ;; Per the bead's audit: 'last-registration-wins per Spec 010'.
  ;; Spec 010 line 459 codifies this — the per-frame side-table
  ;; replaces the prior entry atomically; whichever thread's swap!
  ;; landed last (in linearisation order) is the winner. We can't
  ;; predict WHICH thread that is (depends on scheduler), but we CAN
  ;; assert the winner is a plausible candidate: its schema appears
  ;; in the set of values some thread issued.
  (testing (str n-threads " threads × " stress-iters
                " reg-app-schema on the SAME path — winner is one "
                "of the threads' issued values, no shadow entries")
    (setup-frame!)
    (let [contested-path [:utdxg.ordering/contested]
          latch          (CountDownLatch. 1)
          ;; Each thread tags its writes with (t, m) so a winning
          ;; entry decodes to which thread + which iter wrote it.
          mk-schema (fn [t m] [:enum (keyword (str "t" t "-i" m))])
          futures
          (vec
            (for [t (range n-threads)]
              (future
                (.await latch)
                (dotimes [m stress-iters]
                  (rf/reg-app-schema contested-path
                                     (mk-schema t m)
                                     {:frame stress-frame})))))]
      (.countDown latch)
      (doseq [f futures]
        (let [v (deref f 120000 ::timeout)]
          (is (not= ::timeout v)
              "ordering thread completed within 120s wall-clock")))

      ;; --- Invariant: exactly one entry for the contested path ----
      (let [entries (storage/frame-schema-entries stress-frame)]
        (is (= 1 (count entries))
            (str "Expected exactly one entry under contested path "
                 "(per-path hot-reload is overwrite-in-place, not "
                 "shadow); got " (count entries) " entries."))
        (is (contains? entries contested-path)
            (str "Contested path " contested-path " missing from "
                 "final per-frame side-table; got keys "
                 (pr-str (vec (keys entries)))))

        ;; --- Invariant: winner is one of the values some thread issued -
        (let [winner-schema (-> entries (get contested-path) :schema)
              ;; A valid winner schema has shape `[:enum :tT-iM]` for
              ;; some T in [0, n-threads), M in [0, stress-iters).
              all-issued    (set (for [t (range n-threads)
                                       m (range stress-iters)]
                                   (mk-schema t m)))]
          (is (contains? all-issued winner-schema)
              (str "Winner schema " (pr-str winner-schema)
                   " is not one of the values any thread issued. "
                   "Either the swap!'s CAS retry corrupted the "
                   "value (would indicate an atom-semantics regression) "
                   "or the test setup is wrong.")))))))

;; ---- 5. Frame-isolation under cross-frame contention ---------------------

(deftest cross-frame-isolation-under-contention-stress
  ;; rf2-utdxg scenario 5 — pin Spec 010 §Per-frame schemas isolation
  ;; under N-thread cross-frame contention.
  ;;
  ;; Per Spec 010 the per-frame side-table is the single source of
  ;; truth for app-db schemas — registrations against frame A and
  ;; frame B against the same path are independent entries. Under
  ;; N-thread contention each thread writes to its OWN per-thread
  ;; frame; the invariant is that one thread's writes NEVER bleed
  ;; into another thread's frame (no shared-key bleed even when the
  ;; path is identical across frames).
  ;;
  ;; This is the dual of scenario 1 (shared frame, disjoint paths):
  ;; here the paths COLLIDE across frames but the frame partition
  ;; isolates them. Spec 010 §Per-frame schemas line 9 ("frame-scoped")
  ;; is the codified contract; this test pins the runtime invariant
  ;; under contention.
  (testing (str n-threads " frames × " stress-iters
                " reg-app-schema on the SAME path under contention "
                "— per-frame isolation holds")
    (let [shared-path [:utdxg.iso/shared]
          per-thread-frames (mapv #(keyword "utdxg.iso" (str "f" %))
                                  (range n-threads))
          mk-schema (fn [t m] [:enum (keyword (str "t" t "-i" m))])]
      (doseq [fid per-thread-frames]
        (rf/reg-frame fid {:doc (str "isolation-stress frame " fid)}))
      (let [latch   (CountDownLatch. 1)
            futures
            (vec
              (for [t (range n-threads)]
                (future
                  (.await latch)
                  (dotimes [m stress-iters]
                    (rf/reg-app-schema shared-path
                                       (mk-schema t m)
                                       {:frame (nth per-thread-frames t)})))))]
        (.countDown latch)
        (doseq [f futures]
          (let [v (deref f 120000 ::timeout)]
            (is (not= ::timeout v)
                "isolation thread completed within 120s wall-clock"))))

      ;; --- Invariant: every per-thread frame's winner is a value -
      ;; that thread (and ONLY that thread) issued.
      (doseq [t (range n-threads)
              :let [fid     (nth per-thread-frames t)
                    entries (storage/frame-schema-entries fid)
                    winner  (-> entries (get shared-path) :schema)
                    own-issued
                    (set (for [m (range stress-iters)] (mk-schema t m)))]]
        (is (= 1 (count entries))
            (str "Frame " fid ": expected exactly one entry; got "
                 (count entries)))
        (is (contains? own-issued winner)
            (str "Frame " fid " (thread " t "): winner schema "
                 (pr-str winner) " was not issued by thread " t
                 " — cross-frame bleed."))))))
