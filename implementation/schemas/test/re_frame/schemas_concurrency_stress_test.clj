(ns re-frame.schemas-concurrency-stress-test
  "JVM stress coverage for two schemas contention surfaces:

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

    2. **Sensitive-path resolution under contention** — the unmemoised
       sensitive-path walk (`walk-sensitive-paths-from-schema`) threads
       an accumulator map through a recursive descent of a Malli EDN
       schema vector. The walk is pure — same input ALWAYS produces the
       same output — so under N concurrent walks of a shared schema input
       the per-call result MUST be identical. A stateful walker would
       silently corrupt between threads; pure recursion with local
       accumulators does not.

  Invariants asserted:

    1. **No event dropped.** Every `reg-app-schemas` call from every
       thread lands in the final per-frame side-table — total entry
       count = `(N × M)` distinct (path, schema-value) pairs.

    2. **No leak.** Concurrent sensitive-path walks of a shared schema
       input MUST be identical across threads (no cross-thread
       accumulator pollution). Every parallel result MUST be a fresh walk
       rather than a cached object, the walks MUST overlap, and a
       post-stress walk on the main thread MUST still equal the baseline.

  Threads start in lockstep via `CountDownLatch.countDown` so contention on the
  shared `schemas-by-frame` atom is maximised.

  Per-thread iterations default to 5000 and are overridable via
  `RF2_UTDXG_STRESS_ITERS`. Default
  thread count is 8 (matches the sibling concurrency stress files).

  CLJS is single-threaded; the JVM is the only runtime where the
  schemas-by-frame atom CAN race across threads. JVM-only by design."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas]
            [re-frame.schemas.storage :as rf.schemas.storage]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.schemas.walker :as rf.schemas.walker])
  (:import [java.util.concurrent CountDownLatch]
           [java.util.concurrent.atomic AtomicLong]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

;; Per-thread iteration count. 5000, the sibling concurrency stress
;; files' standard, so CI stays under ~60s wall-clock with the default
;; thread count. Operators dial up via the env override; CI dials
;; down by lowering it (e.g. `RF2_UTDXG_STRESS_ITERS=500` smoke).
(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_UTDXG_STRESS_ITERS") Long/parseLong)
      5000))

;; Eight parallel threads — matches core's `concurrent-dispatch-
;; stress` (`n-submitters 8`) and the sibling stress files. Higher
;; contention than the typical 4-core CI box; the per-thread
;; partitioning of registered paths means N×M distinct entries land
;; in the registry without per-path collision (so we can assert the
;; final cardinality).
(def ^:private n-threads 8)

;; ---- shared frame setup --------------------------------------------------

(def ^:private stress-frame :utdxg.stress/main)

(defn- setup-frame!
  "Reg the shared stress frame. Each scenario uses a clean frame so
  the `:each` reset-runtime fixture between scenarios re-initialises
  the per-frame side-table to `{}`."
  []
  (rf/make-frame {:id stress-frame :doc "shared frame for schemas concurrency stress"}))

;; ---- 1. Hot-reload race: N threads × M reg-app-schemas -------------------

(deftest reg-app-schemas-hot-reload-race-stress
  ;; Scenario 1.
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
  ;;   - The `app-schema-meta` source-coords ride into every entry
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
      (let [final-entries (rf.schemas.storage/frame-schema-entries stress-frame)
            actual-count  (count final-entries)
            expected      (* n-threads stress-iters)]
        (is (= expected actual-count)
            (str "Expected " expected " (= " n-threads " × "
                 stress-iters ") schema entries in final per-frame "
                 "side-table; got " actual-count
                 ". Drop = some swap! retry lost a write under CAS "
                 "contention (would indicate a regression in atom "
                 "semantics — pinned "
                 "here as the no-event-dropped invariant)."))

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

;; ---- 2. Sensitive-path walker under contention ---------------------------

(deftest sensitive-path-walker-contention-stress
  ;; Scenario 2.
  ;;
  ;; The sensitive-path walk is pure recursion through immutable data —
  ;; every accumulator is local to the call. Under N concurrent walks of a
  ;; shared schema the result MUST be identical across threads; a walker
  ;; carrying shared mutable state surfaces here as divergent results.
  ;;
  ;; The parallel phase calls the UNMEMOISED walk
  ;; (`walk-sensitive-paths-from-schema`, what the
  ;; `:schemas/extract-sensitive-paths-from-schema` hook publishes). The
  ;; public `extract-sensitive-paths-from-schema` memoises on
  ;; `(schema, base-path)`, so behind it every call after the first is a
  ;; cache hit and no walk runs under contention at all.
  ;;
  ;; The schema input is a 4-level nested `:map` carrying a mix of
  ;; `:sensitive?` and ordinary slots — deep enough that the recursion
  ;; has multiple frames and the accumulator transitions through several
  ;; intermediate states (any one of which a stateful walker could leak
  ;; between threads).
  ;;
  ;; Invariants:
  ;;   - Every per-call result equals the main-thread baseline computed
  ;;     before the futures launch.
  ;;   - Every per-call result is a FRESH walk: never `identical?` to the
  ;;     thread's previous result (the baseline, for its first call),
  ;;     because a cache hit hands back the same object.
  ;;   - The walks overlapped: some walk started while another was still
  ;;     in flight, so the phase really ran under contention.
  ;;   - Each per-thread counter equals `stress-iters` — pins that no
  ;;     thread silently skipped iterations.
  (testing (str n-threads " threads × " stress-iters
                " concurrent sensitive-path walks against a shared schema — "
                "identical results per call")
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
          walk     rf.schemas.walker/walk-sensitive-paths-from-schema
          ;; Main-thread baseline — what every parallel call MUST match.
          ;; Computed BEFORE the futures launch so the comparison happens
          ;; against a snapshot fixed in evaluation order (no later
          ;; main-thread mutation could perturb it).
          baseline (walk schema [])
          latch    (CountDownLatch. 1)
          ;; Per-thread divergence record + per-thread call count.
          divergences       (vec (repeatedly n-threads #(atom [])))
          per-thread-counts (vec (repeatedly n-threads #(AtomicLong. 0)))
          ;; Calls whose result was the previous call's object (a cache hit).
          reused            (AtomicLong. 0)
          ;; Walks currently running, and walks that started while another
          ;; was still running.
          in-flight         (AtomicLong. 0)
          overlapped        (AtomicLong. 0)
          futures
          (vec
            (for [t (range n-threads)]
              (future
                (.await latch)
                (loop [i 0 prev baseline]
                  (when (< i stress-iters)
                    (.incrementAndGet ^AtomicLong (nth per-thread-counts t))
                    (when (< 1 (.incrementAndGet in-flight))
                      (.incrementAndGet overlapped))
                    (let [r (walk schema [])]
                      (.decrementAndGet in-flight)
                      (when (identical? r prev)
                        (.incrementAndGet reused))
                      (when (not= r baseline)
                        (swap! (nth divergences t) conj
                               {:got      r
                                :expected baseline}))
                      (recur (inc i) r)))))))]
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

      ;; --- Invariant: every call walked, and the walks overlapped ---
      (is (zero? (.get reused))
          (str (.get reused) " parallel calls returned the previous call's "
               "result object — they were served from a cache, so the walk "
               "did not run under contention"))
      (is (pos? (.get overlapped))
          "at least one walk started while another was still in flight")

      ;; --- Invariant: post-stress walk still equals baseline ---
      ;; The walk is a pure fn over immutable data; the post-stress
      ;; result MUST equal the pre-stress baseline. A discrepancy
      ;; would indicate the contention left behind mutable global state
      ;; the walker reads through (it reads through none; this pins that
      ;; it stays so).
      (let [post (walk schema [])]
        (is (= baseline post)
            (str "Post-stress walker baseline drifted: expected "
                 (pr-str baseline) "; got " (pr-str post)))))))
