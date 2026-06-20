(ns re-frame.flows-path-overlap-test
  "Overlapping output `:output-path`s between sibling flows are rejected at
  registration.

  The topo dependency rule (`topo/depends-on?`, Spec 013 §Topological sort)
  compares ONLY flow A's `:output-path` against flow B's `:inputs` — never
  `:output-path` vs `:output-path`. So two flows in the SAME frame whose OUTPUT
  `:output-path`s overlap (one a prefix of the other, identical included) but
  whose `:inputs` are disjoint from each other's outputs would get NO
  dependency edge: both 'ready' at topo-sort start, their relative evaluation
  order falling out of `(keys flow-map)` map-iteration order — a non-contract —
  so the shared app-db slot would be written last-write-wins,
  non-deterministically. So `reg-flow` detect-and-rejects such a pair,
  symmetric with the cycle check and inside the same atomic `swap!`
  (`:rf.error/flow-path-overlap`). See `topo/detect-output-path-overlap!` and
  the registry `reg-flow` update fn.

  This file pins both ends:
    - the pure `topo` helpers (`output-paths-overlap?` /
      `detect-output-path-overlap!`) — algorithm-level, no runtime;
    - the integrated `rf/reg-flow` path — the rejection actually fires at
      registration, the prior registration survives, and DISJOINT
      sibling outputs (the common, valid case) still register cleanly.

  TERMINATION NOTE: `detect-output-path-overlap!` scans the upper triangle of
  the frame's flow pairs via `(some ... (for ...))` — terminating by
  construction. The disjoint-map and disjoint-sibling tests below are the
  explicit guard that the scan terminates on any frame with ≥2 disjoint flows."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- per-test reset (mirrors flows_test.clj) ------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  ;; EP-0002: reg-flow is context-required frame-local — an ambient call
  ;; under no scope raises :rf.error/no-frame-context. Pin
  ;; :rf/default (an ordinary frame) as the established scope for the body.
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---------------------------------------------------------------------------
;; 1. topo/output-paths-overlap? — the prefix-relation predicate
;; ---------------------------------------------------------------------------

(deftest output-paths-overlap?-identical-paths
  (testing "identical :paths overlap (a vector is a prefix of itself)"
    (is (true? (topo/output-paths-overlap? [:x] [:x])))
    (is (true? (topo/output-paths-overlap? [:a :b] [:a :b])))))

(deftest output-paths-overlap?-prefix-in-either-direction
  (testing "parent/child overlap — one :output-path is a prefix of the other (Spec 013 §Disjoint output paths)"
    (is (true? (topo/output-paths-overlap? [:x] [:x :y]))
        "[:x] is a prefix of [:x :y] — writing [:x] clobbers the child")
    (is (true? (topo/output-paths-overlap? [:x :y] [:x]))
        "symmetric: [:x] is still a prefix of [:x :y] with args flipped")))

(deftest output-paths-overlap?-disjoint-siblings
  (testing "sibling leaves under a shared parent do NOT overlap — neither is a prefix of the other"
    (is (false? (topo/output-paths-overlap? [:x :y] [:x :z]))
        "[:x :y] and [:x :z] are disjoint (each owns its own leaf)")
    (is (false? (topo/output-paths-overlap? [:a] [:b]))
        "wholly unrelated single-key paths are disjoint")
    (is (false? (topo/output-paths-overlap? [:a :b :c] [:a :b :d]))
        "deeper sibling leaves [:a :b :c] / [:a :b :d] are disjoint too")
    (is (true? (topo/output-paths-overlap? [:a :b] [:a :b :c :d]))
        "but a deep child [:a :b :c :d] DOES overlap its ancestor [:a :b]")))

(deftest output-paths-overlap?-shared-non-prefix-element
  (testing "shared NON-prefix element is not an overlap (prefix-based, not membership-based)"
    ;; [:x :y] vs [:y :x] share both elements but neither is a prefix of
    ;; the other → disjoint. Mirrors depends-on?'s prefix-not-membership
    ;; rule (flows_topo_test.clj depends-on?-false-when-...share-element).
    (is (false? (topo/output-paths-overlap? [:x :y] [:y :x])))))

;; ---------------------------------------------------------------------------
;; 2. topo/detect-output-path-overlap! — the prospective-map scanner
;; ---------------------------------------------------------------------------

(deftest detect-output-path-overlap!-passes-disjoint-map
  (testing "a frame map of pairwise-disjoint output paths passes (returns the map unchanged) — and TERMINATES"
    ;; TERMINATION GUARD: the scan must terminate on a ≥2-flow disjoint map;
    ;; this assertion only completes if it does.
    (let [flow-map {:a {:id :a :inputs [[:w]] :derive identity :output-path [:x :a]}
                    :b {:id :b :inputs [[:h]] :derive identity :output-path [:x :b]}
                    :c {:id :c :inputs [[:q]] :derive identity :output-path [:y]}}]
      (is (= flow-map (topo/detect-output-path-overlap! flow-map))
          "disjoint outputs: no throw, threads the map through"))))

(deftest detect-output-path-overlap!-throws-on-identical-paths
  (testing "two flows with identical output :paths throw :rf.error/flow-path-overlap with the canonical thrown-error shape"
    (let [flow-map {:a {:id :a :inputs [[:w]] :derive identity :output-path [:x]}
                    :b {:id :b :inputs [[:h]] :derive identity :output-path [:x]}}
          thrown   (try (topo/detect-output-path-overlap! flow-map)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "overlapping outputs raise")
      (is (re-find #"\[:rf\.error/flow-path-overlap\]" (.getMessage ^Throwable thrown))
          "the [:rf.error/<id>] greppability token appears in the message (Spec 009 §The thrown-error shape, rule 4)")
      (let [data (ex-data thrown)]
        (is (= :rf.error/flow-path-overlap (:rf.error/id data))
            "ex-data carries the canonical :rf.error/id discriminator (Spec 009 §The thrown-error shape)")
        (is (= 'rf/reg-flow (:where data))
            "ex-data carries :where 'rf/reg-flow — the user-facing call site")
        (is (= :fix-registration (:recovery data))
            "ex-data carries :recovery :fix-registration — caller-fixable like the cycle / validate-flow rejections")
        (is (string? (:reason data))
            "ex-data carries :reason as a human-readable string")
        (let [{:keys [flow-ids paths]} (:overlap data)]
          (is (= #{:a :b} (set flow-ids))
              "ex-data :overlap names the colliding flow-ids")
          (is (= 2 (count paths))
              "ex-data :overlap carries the two colliding paths")
          (is (every? #{[:x]} paths)
              "both colliding paths are [:x]"))
        (is (= #{:rf.error/id :where :recovery :reason :overlap}
               (set (keys data)))
            "ex-data carries exactly the canonical slots + :overlap")))))

(deftest detect-output-path-overlap!-throws-on-prefix-paths
  (testing "two flows where one :output-path is a PREFIX of the other throw :rf.error/flow-path-overlap"
    (let [flow-map {:parent {:id :parent :inputs [[:w]] :derive identity :output-path [:x]}
                    :child  {:id :child  :inputs [[:h]] :derive identity :output-path [:x :y]}}
          thrown   (try (topo/detect-output-path-overlap! flow-map)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "prefix-related outputs raise")
      (is (= :rf.error/flow-path-overlap (:rf.error/id (ex-data thrown))))
      (is (= #{:parent :child} (set (:flow-ids (:overlap (ex-data thrown))))))
      (is (= #{[:x] [:x :y]} (set (:paths (:overlap (ex-data thrown)))))
          "the parent and child paths are both reported"))))

(deftest detect-output-path-overlap!-deterministic-pair-ordering
  (testing "the reported :overlap pair is stable across runs (sort-by hash, not iteration order)"
    (let [flow-map {:a {:id :a :inputs [[:w]] :derive identity :output-path [:x]}
                    :b {:id :b :inputs [[:h]] :derive identity :output-path [:x]}}
          overlap-of (fn [] (-> (try (topo/detect-output-path-overlap! flow-map)
                                     (catch clojure.lang.ExceptionInfo e (ex-data e)))
                                :overlap))]
      (is (= (overlap-of) (overlap-of) (overlap-of))
          "repeated detection yields an identical :overlap (deterministic ordering)"))))

(deftest detect-output-path-overlap!-pair-ordering-invariant-to-iteration-order
  ;; The reported :overlap pair must be GENUINELY deterministic across runs,
  ;; not merely run-stable. A bare `sort-by hash` tie-break would leave the
  ;; lo/hi assignment undefined on a hash collision: `sort-by` is stable and
  ;; would fall back to the INPUT (map-iteration) order, which is not a
  ;; cross-run contract for Clojure hash-maps. The `(juxt hash str)` tie-break
  ;; makes the result depend only on the ids' VALUES, so feeding the same
  ;; logical pair in opposite iteration orders MUST yield the same reported
  ;; pair. Insertion-ordered array-maps below stand in for the two possible
  ;; cross-run iteration orders.
  (testing "the reported pair is invariant to flow-map iteration order"
    (let [flow-a {:id :a :inputs [[:w]] :derive identity :output-path [:x]}
          flow-b {:id :b :inputs [[:h]] :derive identity :output-path [:x]}
          overlap-of (fn [m] (-> (try (topo/detect-output-path-overlap! m)
                                      (catch clojure.lang.ExceptionInfo e (ex-data e)))
                                 :overlap))
          a-first (overlap-of (array-map :a flow-a :b flow-b))
          b-first (overlap-of (array-map :b flow-b :a flow-a))]
      (is (= a-first b-first)
          "the same colliding pair reported identically regardless of iteration order")
      (is (= [:a :b] (:flow-ids a-first))
          "the pair is canonically ordered by (juxt hash str) — value-derived, not iteration-derived")))
  (testing "the tie-break is robust on a genuine hash collision (juxt str fallback)"
    ;; Construct two DISTINCT ids that share a hash so the `hash` key ties
    ;; and the `str` key alone decides the order — the exact case the old
    ;; `sort-by hash` left to iteration order. Strings whose hashCodes
    ;; collide ("Aa"/"BB" is the canonical Java String hashCode collision)
    ;; give us distinct, equal-hash ids.
    (let [id-lo "Aa"
          id-hi "BB"]
      (is (= (hash id-lo) (hash id-hi))
          "precondition: the two ids genuinely collide on hash")
      (let [flow-lo {:id id-lo :inputs [[:w]] :derive identity :output-path [:x]}
            flow-hi {:id id-hi :inputs [[:h]] :derive identity :output-path [:x]}
            overlap-of (fn [m] (-> (try (topo/detect-output-path-overlap! m)
                                        (catch clojure.lang.ExceptionInfo e (ex-data e)))
                                   :overlap))
            lo-first (overlap-of (array-map id-lo flow-lo id-hi flow-hi))
            hi-first (overlap-of (array-map id-hi flow-hi id-lo flow-lo))]
        (is (= lo-first hi-first)
            "on a hash collision the pair is STILL identical across iteration orders")
        (is (= [id-lo id-hi] (:flow-ids lo-first))
            "the str tie-break orders the colliding pair canonically (\"Aa\" < \"BB\")")))))

;; ---------------------------------------------------------------------------
;; 3. integrated rf/reg-flow — THE bug repro: same-frame overlapping
;;    outputs + DISJOINT inputs (no cycle, no edge) must be rejected.
;; ---------------------------------------------------------------------------

(deftest reg-flow-rejects-overlapping-output-paths-with-disjoint-inputs
  (testing "rf2-um6d9 — two same-frame flows whose OUTPUT :paths overlap but whose INPUTS are disjoint are rejected at registration"
    ;; The silent-footgun shape: A reads [:src-a] writes [:dest]; B reads
    ;; [:src-b] writes [:dest]. Inputs are disjoint (neither reads the other's
    ;; output) so the topo dependency rule produces NO edge — both would be
    ;; 'ready' and the shared slot [:dest] would be written in undefined order.
    ;; So the second registration is rejected.
    (rf/reg-flow {:id :a :inputs [[:src-a]] :derive identity :output-path [:dest]})
    (let [thrown (try
                   (rf/reg-flow {:id :b :inputs [[:src-b]] :derive identity :output-path [:dest]})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown)
          "registering a second flow on the same frame with an overlapping output :output-path throws")
      (is (= :rf.error/flow-path-overlap (:rf.error/id (ex-data thrown)))
          "the rejection is :rf.error/flow-path-overlap")
      (is (= #{:a :b} (set (:flow-ids (:overlap (ex-data thrown)))))
          "the offending pair is named in :overlap"))
    ;; The rejected REPLACEMENT must not have vacated / disturbed the
    ;; prior registration (symmetric with the cycle-check atomicity).
    (is (contains? (get (flows/flows-snapshot) :rf/default) :a)
        "the prior flow :a survives the rejected registration")
    (is (not (contains? (get (flows/flows-snapshot) :rf/default) :b))
        "the rejected flow :b never lands in the committed registry")))

(deftest reg-flow-allows-disjoint-sibling-output-paths
  (testing "rf2-um6d9 — flows writing to DISJOINT sibling paths under a shared parent register cleanly (the common valid case is unaffected) — and TERMINATES"
    ;; TERMINATION GUARD: the integrated equivalent of the disjoint-map test —
    ;; this reg-flow call must complete, not hang the suite.
    (rf/reg-flow {:id :w :inputs [[:in-w]] :derive identity :output-path [:rect :w]})
    (rf/reg-flow {:id :h :inputs [[:in-h]] :derive identity :output-path [:rect :h]})
    (let [committed (get (flows/flows-snapshot) :rf/default)]
      (is (contains? committed :w) "the [:rect :w] flow registered")
      (is (contains? committed :h) "the [:rect :h] flow registered — no false-positive overlap"))))

(deftest reg-flow-overlap-is-frame-scoped
  (testing "rf2-um6d9 — overlapping output :paths on DIFFERENT frames are NOT an overlap (frames are isolated app-dbs)"
    (rf/reg-frame :frame-a {:doc "isolated frame a"})
    (rf/reg-frame :frame-b {:doc "isolated frame b"})
    ;; Same flow-id-shape, same output :output-path, but DIFFERENT frames —
    ;; their writes land in different app-dbs, so no collision.
    (rf/reg-flow {:id :x :inputs [[:in]] :derive identity :output-path [:dest]} {:frame :frame-a})
    (rf/reg-flow {:id :x :inputs [[:in]] :derive identity :output-path [:dest]} {:frame :frame-b})
    (is (contains? (get (flows/flows-snapshot) :frame-a) :x)
        "frame-a holds its :x flow")
    (is (contains? (get (flows/flows-snapshot) :frame-b) :x)
        "frame-b holds its own :x flow — overlap detection is per-frame, not cross-frame")))

(deftest reg-flow-re-registration-does-not-self-overlap
  (testing "rf2-um6d9 — re-registering an existing flow-id with the SAME :output-path (hot-reload) is NOT a self-overlap"
    ;; The footgun-adjacent case: a flow keeps its :output-path across a
    ;; hot-reload re-registration. The prospective map is
    ;; (assoc prior-frame flow-id flow) — the same key, so the map still
    ;; holds ONE entry for :a and detect-output-path-overlap! (which
    ;; scans DISTINCT entries) never compares :a's path against itself.
    ;; A false positive here would break hot-reload of any flow.
    (rf/reg-flow {:id :a :inputs [[:src]] :derive identity :output-path [:dest]})
    (is (nil? (try
                (rf/reg-flow {:id :a :inputs [[:src]] :derive (fn [v] v) :output-path [:dest]})
                nil
                (catch clojure.lang.ExceptionInfo e e)))
        "re-registering :a with the same :output-path must not throw :rf.error/flow-path-overlap")
    (is (contains? (get (flows/flows-snapshot) :rf/default) :a)
        "the re-registered flow :a is committed")))

(deftest reg-flow-still-rejects-cycles-and-passes-clean-topologies
  (testing "rf2-um6d9 — adding the overlap check does not disturb the cycle check or the happy path"
    ;; Happy path: a real dependency (B reads what A writes) registers and
    ;; topo-sorts cleanly — disjoint OUTPUTS, a genuine input edge.
    (rf/reg-flow {:id :a :inputs [[:seed]]  :derive identity :output-path [:a-out]})
    (rf/reg-flow {:id :b :inputs [[:a-out]] :derive identity :output-path [:b-out]})
    (is (= #{:a :b} (set (keys (get (flows/flows-snapshot) :rf/default))))
        "a clean dependency topology (disjoint outputs, real edge) registers both flows")
    ;; Cycle check still fires: C reads B's output [:b-out]; re-registering
    ;; B to read C's output [:c-out] closes the cycle b → c → b.
    (rf/reg-flow {:id :c :inputs [[:b-out]] :derive identity :output-path [:c-out]})
    (let [thrown (try
                   (rf/reg-flow {:id :b :inputs [[:a-out] [:c-out]] :derive identity :output-path [:b-out]})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/flow-cycle (:rf.error/id (ex-data thrown)))
          "a cycle-forming re-registration is still rejected with :rf.error/flow-cycle"))))
