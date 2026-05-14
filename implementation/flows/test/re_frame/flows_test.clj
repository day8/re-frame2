(ns re-frame.flows-test
  "JVM smoke coverage for Spec 013 — Flows.

  This file backstops the conformance fixtures in
  spec/conformance/fixtures/flow-*.edn. Where the fixtures
  describe canonical flow shapes as data (skipped by the reference
  harness until the :flow/* capability set is wired into the conformance
  runner), the tests here exercise the same paths against the JVM
  reference implementation directly:

    - reg-flow / clear-flow round-trip
    - dirty-check (=-equal inputs do NOT recompute)
    - topological sort (B reads what A wrote; one drain pass)
    - cycle detection at registration time
    - hot-reload preserves the output value when the new body is
      value-equivalent on current inputs
    - :rf.fx/reg-flow / :rf.fx/clear-flow toggle round-trip
    - clear-all / lifecycle interaction with the per-frame registry"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---- per-test reset -------------------------------------------------------
;;
;; Mirrors the smoke_test.clj fixture so each deftest starts from a clean
;; registrar / frames / flows state. Re-loading routing / ssr restores the
;; framework events that clear-all! wiped (some smoke tests rely on
;; :rf.route/navigate etc. resolving — keep the reset shape consistent so
;; running these tests in any order works).

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  ;; flows.cljc keeps a private last-inputs atom for dirty-checking
  ;; (per Spec 013 §Dirty-check semantics). The smoke-test fixture
  ;; doesn't reset it; left alone, an entry from this namespace's tests
  ;; can leak into a sibling namespace's identically-keyed flow and
  ;; cause its first-evaluation to no-op (new-inputs would =-equal the
  ;; stale last-inputs). Clear it here so cross-namespace test order
  ;; can't introduce hidden flakiness.
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---------------------------------------------------------------------------
;; 1. reg-flow / clear-flow lifecycle (registry side)
;; ---------------------------------------------------------------------------

(deftest reg-flow-populates-registry
  (testing "reg-flow stores the flow under [frame-id flow-id] in the per-frame registry"
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]})
    (is (contains? (get @flows/flows :rf/default) :area)
        "the flow lives under :rf/default's slot of the per-frame registry")
    (is (some? (registrar/lookup :flow :area))
        "the flow is also discoverable via the :flow registrar kind")))

(deftest clear-flow-removes-from-registry-and-vacates-output-slot
  (testing "clear-flow removes the flow and dissoc-in's its output path"
    ;; rf2-aqt7: clear-flow's update-in path math is off-by-one for
    ;; single-element :path vectors. Use a two-element :path here so
    ;; the working branch (>= 2 elements) is exercised.
    (rf/reg-event-db :seed (fn [_ _] {:rect {:w 3 :h 4}}))
    (rf/reg-flow {:id     :area
                  :inputs [[:rect :w] [:rect :h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    (rf/dispatch-sync [:seed])
    (is (= 12 (get-in (rf/get-frame-db :rf/default) [:rect :area]))
        "flow ran on the drain after :seed and materialised :rect/:area")
    (rf/clear-flow :area)
    (is (not (contains? (get @flows/flows :rf/default) :area))
        "the per-frame registry no longer carries :area")
    (is (not (contains? (get (rf/get-frame-db :rf/default) :rect) :area))
        "clear-flow dissoc'd the leaf at the flow's :path"))
  (testing "calling clear-flow on an unknown id is a no-op (does not throw)"
    (rf/clear-flow :no-such-flow)))

(deftest clear-flow-nested-path-before-first-compute-does-not-write-nil-parent
  ;; Regression for rf2-q25os Repro 1. When a flow with a nested `:path`
  ;; (e.g. `[:step-2 :result]`) is cleared BEFORE any drain has run the
  ;; flow's output, the parent slot `:step-2` doesn't exist in app-db.
  ;; Pre-fix, the naïve `(update-in cur [:step-2] dissoc :result)`
  ;; returned `(dissoc nil :result) ⇒ nil`, producing `{:step-2 nil}` — a
  ;; spurious nil parent. The robust path (`dissoc-in-safe`) leaves
  ;; app-db unchanged when the parent was never materialised.
  (testing "clear-flow on nested-path flow before first compute leaves app-db unchanged"
    (rf/reg-flow {:id     :pending
                  :inputs [[:n]]
                  :output (fn [_] "never-runs")
                  :path   [:step-2 :result]})
    (let [db-before (rf/get-frame-db :rf/default)]
      (rf/clear-flow :pending)
      (let [db-after (rf/get-frame-db :rf/default)]
        (is (= db-before db-after)
            "app-db is unchanged when clearing a never-materialised nested-path flow")
        (is (not (contains? db-after :step-2))
            "no spurious `:step-2 nil` parent was created")))))

(deftest clear-flow-non-map-intermediate-is-noop
  ;; Regression for rf2-q25os Repro 2. When an intermediate path step
  ;; holds a non-map value (e.g. someone wrote a scalar at `:step-2`
  ;; before the flow's output ever materialised), pre-fix
  ;; `(update-in cur [:step-2] dissoc :result)` called `(dissoc 1 :result)`
  ;; and threw `ClassCastException`. The robust path treats this as a
  ;; no-op — the flow's `:path` never materialised, so there's nothing
  ;; to clear.
  (testing "clear-flow on a flow whose intermediate path step holds a scalar is a no-op (no throw)"
    (rf/reg-event-db :seed-scalar (fn [_ _] {:step-2 1 :foo 3 :bar 4}))
    (rf/reg-flow {:id     :pending
                  :inputs [[:foo]]
                  :output (fn [_] "never-stored-because-:step-2-is-a-scalar")
                  :path   [:step-2 :result]})
    (rf/dispatch-sync [:seed-scalar])
    ;; At this point the flow tried to write at `[:step-2 :result]` —
    ;; `assoc-in` on a scalar parent actually replaces the scalar with a
    ;; map. So the flow's first drain materialises the path, and a
    ;; subsequent clear would hit the normal path. To exercise the
    ;; never-materialised non-map-intermediate case directly, we reset
    ;; last-inputs and re-seed AFTER reg-flow but BEFORE any drain.
    (when-let [li-var (resolve 're-frame.flows/last-inputs)]
      (reset! (deref li-var) {}))
    (let [db-before (rf/get-frame-db :rf/default)]
      ;; Stamp a non-map at the parent slot via direct app-db write so
      ;; the next clear hits the non-map-intermediate branch.
      (rf/reg-event-db :stamp-non-map (fn [db _] (assoc db :step-2 1)))
      (rf/dispatch-sync [:stamp-non-map])
      ;; Re-register the flow so the per-frame registry has the entry
      ;; (re-stamping app-db dropped the flow's output via the value-
      ;; equal check path).
      (rf/reg-flow {:id     :pending
                    :inputs [[:foo]]
                    :output (fn [_] "never-stored")
                    :path   [:step-2 :result]})
      ;; Clear must NOT throw, and must leave the scalar parent intact.
      (is (nil? (rf/clear-flow :pending))
          "clear-flow returns nil (no throw) when the intermediate is a non-map")
      (is (= 1 (:step-2 (rf/get-frame-db :rf/default)))
          ":step-2 is preserved as its scalar value — clear-flow did not corrupt it")
      ;; Sanity: siblings untouched.
      (is (= 3 (:foo (rf/get-frame-db :rf/default))))
      (is (= 4 (:bar (rf/get-frame-db :rf/default)))))))

(deftest clear-flow-handles-single-element-path
  (testing "rf2-aqt7: clear-flow with a single-element :path dissocs the top-level key"
    ;; The repro from rf2-aqt7: a flow whose :path is a one-element vector
    ;; [:area]. Before the fix, clear-flow's (update-in cur [] dissoc :area)
    ;; left :area in app-db (and silently introduced an {nil nil} entry).
    (rf/reg-event-db :seed (fn [_ _] {:w 3 :h 4}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:area]})
    (rf/dispatch-sync [:seed])
    (is (= 12 (get (rf/get-frame-db :rf/default) :area))
        "flow ran on the drain after :seed and materialised :area")
    (rf/clear-flow :area)
    (let [db (rf/get-frame-db :rf/default)]
      (is (not (contains? db :area))
          "single-element :path is dissoc'd cleanly")
      (is (not (contains? db nil))
          "no spurious {nil nil} entry from update-in on empty path")
      (is (= {:w 3 :h 4} db)
          "siblings of the cleared key are untouched"))))

(deftest reg-flow-validates-required-keys
  (testing "missing :id throws"
    (is (thrown? Throwable
                 (rf/reg-flow {:inputs [[:n]] :output identity :path [:x]}))))
  (testing ":inputs must be a vector"
    (is (thrown? Throwable
                 (rf/reg-flow {:id :bad :inputs :not-a-vec
                               :output identity :path [:x]}))))
  (testing ":output must be a fn"
    (is (thrown? Throwable
                 (rf/reg-flow {:id :bad :inputs [[:n]]
                               :output 42 :path [:x]}))))
  (testing ":path must be a vector"
    (is (thrown? Throwable
                 (rf/reg-flow {:id :bad :inputs [[:n]]
                               :output identity :path :not-a-vec})))))

;; ---------------------------------------------------------------------------
;; 1b. validate-flow well-formedness (rf2-gnl7q)
;;
;; Per audit rf2-o3hok findings Q5 / TE4 — the prior `validate-flow` only
;; checked `(vector? (:inputs flow))` and `(vector? (:path flow))`, so:
;;
;;   - `:inputs [:foo :bar]` (vector of bare keywords, NOT vector-of-paths)
;;     passed validation and then threw deep inside topo-sort's `prefix?`
;;     when it called `(count :foo)`.
;;   - `:inputs [[:foo] :bar]` (mixed) likewise passed, then exploded on
;;     the bare-keyword entry.
;;   - `:path []` passed; `(prefix? [] anything)` returns true, silently
;;     making the empty-path flow a depends-on prerequisite of EVERY other
;;     flow in the frame.
;;
;; These tests pin the tightened contract: each malformation is rejected
;; up front with a stable error id (`:rf.error/flow-bad-inputs` or
;; `:rf.error/flow-bad-path`) and ex-data that names the offending entries
;; so callers can fix their flow map without a stack-trace scavenger hunt.

(defn- flow-bad-inputs? [^Throwable t]
  (= ":rf.error/flow-bad-inputs" (ex-message t)))

(defn- flow-bad-path? [^Throwable t]
  (= ":rf.error/flow-bad-path" (ex-message t)))

(deftest reg-flow-rejects-bare-keyword-inputs
  (testing ":inputs [:foo :bar] is rejected (vector of bare keywords, not vector-of-paths)"
    ;; Pre-fix this would pass validate-flow and then throw with
    ;; (count :foo) somewhere deep in topo's prefix?.
    (let [ex (try
               (rf/reg-flow {:id     :bad
                             :inputs [:foo :bar]
                             :output (fn [_ _] nil)
                             :path   [:out]})
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-inputs? ex)
          "error id is :rf.error/flow-bad-inputs")
      (is (= [:foo :bar] (:bad-entries (ex-data ex)))
          "ex-data names the offending entries (both bare keywords)"))))

(deftest reg-flow-rejects-mixed-input-shapes
  (testing ":inputs [[:foo] :bar] is rejected (one bare keyword among well-formed paths)"
    (let [ex (try
               (rf/reg-flow {:id     :bad
                             :inputs [[:foo] :bar]
                             :output (fn [_ _] nil)
                             :path   [:out]})
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-inputs? ex)
          "error id is :rf.error/flow-bad-inputs")
      (is (= [:bar] (:bad-entries (ex-data ex)))
          "only the bare-keyword entry is named — the vector entry is fine"))))

(deftest reg-flow-rejects-empty-input-path
  (testing ":inputs [[]] is rejected (empty path is not a meaningful app-db read)"
    (let [ex (try
               (rf/reg-flow {:id     :bad
                             :inputs [[]]
                             :output (fn [_] nil)
                             :path   [:out]})
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-inputs? ex)
          "error id is :rf.error/flow-bad-inputs"))))

(deftest reg-flow-rejects-collection-input-elements
  (testing ":inputs [[[:nested]]] is rejected (path step is a vector, not a scalar key)"
    (let [ex (try
               (rf/reg-flow {:id     :bad
                             :inputs [[[:nested]]]
                             :output (fn [_] nil)
                             :path   [:out]})
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-inputs? ex)
          "error id is :rf.error/flow-bad-inputs"))))

(deftest reg-flow-rejects-empty-path
  (testing ":path [] is rejected (would make this flow a prerequisite of every other flow)"
    ;; Pre-fix: (prefix? [] anything) returns true, so an empty-path flow
    ;; becomes depends-on for every other flow in the frame. Per Spec 013
    ;; §Dependency rule this is never what the caller means.
    (let [ex (try
               (rf/reg-flow {:id     :bad
                             :inputs [[:n]]
                             :output identity
                             :path   []})
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-path? ex)
          "error id is :rf.error/flow-bad-path")
      (is (re-find #"non-empty" (:reason (ex-data ex)))
          "ex-data :reason mentions the non-empty requirement"))))

(deftest reg-flow-rejects-collection-path-elements
  (testing ":path [[:nested]] is rejected (path step is a vector, not a scalar key)"
    (let [ex (try
               (rf/reg-flow {:id     :bad
                             :inputs [[:n]]
                             :output identity
                             :path   [[:nested]]})
               (catch Throwable t t))]
      (is (some? ex) "registration threw")
      (is (flow-bad-path? ex)
          "error id is :rf.error/flow-bad-path")
      (is (= [[:nested]] (:bad-elements (ex-data ex)))
          "ex-data names the offending element(s)"))))

(deftest reg-flow-accepts-scalar-path-elements
  (testing "scalar path elements (keyword / string / integer / symbol / boolean) all pass"
    ;; Sanity check for valid-path-element?: each documented scalar key
    ;; type round-trips through reg-flow without throwing. Tighter
    ;; validation must not regress the common case.
    (doseq [[label elt] [[:kw     :kw]
                         [:string "str"]
                         [:int    42]
                         [:symbol 'sym]
                         [:bool   true]]]
      (let [flow-id (keyword "elt" (name label))]
        (is (some? (rf/reg-flow {:id     flow-id
                                 :inputs [[elt]]
                                 :output identity
                                 :path   [elt]}))
            (str "scalar path element " (pr-str elt) " is accepted"))
        (rf/clear-flow flow-id)))))

(deftest reg-flow-accepts-empty-inputs-vector
  (testing ":inputs [] is allowed (one-shot flow with no app-db dependencies)"
    ;; The well-formedness checks reject malformed entries inside :inputs,
    ;; but an empty :inputs vector itself remains valid — a zero-arg flow
    ;; that fires once and stays put (no path can change to dirty it). Pin
    ;; this so the new every?-based checks don't accidentally reject the
    ;; legitimate empty-inputs case.
    (is (some? (rf/reg-flow {:id     :constant
                             :inputs []
                             :output (fn [] 42)
                             :path   [:k]})))))

(deftest reg-flow-detects-cycles-at-registration
  (testing ":a depends on :b, :b depends on :a — registering the second throws"
    (rf/reg-flow {:id :a :inputs [[:b]] :output identity :path [:a]})
    (is (thrown? Throwable
                 (rf/reg-flow {:id :b :inputs [[:a]]
                               :output identity :path [:b]}))
        "the cyclic registration unwinds and throws :rf.error/flow-cycle")
    (is (not (contains? (get @flows/flows :rf/default) :b))
        "cycle-detection rolls back the partial registration of :b")))

(deftest reg-flow-cycle-error-carries-ordered-cycle-path
  ;; Regression for rf2-sny6o. The cycle-error ex-data contract
  ;; (per Spec 013 §Cycle detection / Spec 009 §Error contract) is:
  ;; `:cycle` is an ordered vector of flow ids with a closing repeat
  ;; — e.g. `[:a :b :a]` for the cycle :a → :b → :a. Pre-fix the impl
  ;; threw `(vec (keys @remaining))` (an unordered subset of stuck
  ;; nodes) which was useless for tooling rendering the offending
  ;; chain. This test pins the resolved shape.
  (testing "two-flow cycle: :cycle is [start ... start], length 3"
    (rf/reg-flow {:id :a :inputs [[:b]] :output identity :path [:a]})
    (let [ex (try
               (rf/reg-flow {:id :b :inputs [[:a]] :output identity :path [:b]})
               (catch Throwable t t))
          data (ex-data ex)
          cycle (:cycle data)]
      (is (some? ex)        "registration threw")
      (is (vector? cycle)   ":cycle is a vector")
      (is (= 3 (count cycle))
          "two-flow cycle has length 3 (n+1, including the closing repeat)")
      (is (= (first cycle) (last cycle))
          ":cycle closes on itself (first = last)")
      (is (= #{:a :b} (set cycle))
          ":cycle names both offending flow ids")
      ;; Spec 013 example: {:cycle [:a :b :a]}. Either :a or :b may
      ;; legally be the starting node (impl picks deterministically
      ;; via sort-by hash; spec leaves the starting node
      ;; implementation-defined) — assert one of the two valid
      ;; closures.
      (is (contains? #{[:a :b :a] [:b :a :b]} cycle)
          "the cycle path is one of the two valid two-flow closures")))

  (testing "three-flow cycle: :a → :b → :c → :a"
    ;; Reset and build a longer chain. The reg-flow ordering matters
    ;; because the cycle is detected on the registration that closes
    ;; it — register :a, :b first (no cycle yet), then :c closes.
    (reset! flows/flows {})
    (reset! (deref (resolve 're-frame.flows/last-inputs)) {})
    (rf/reg-flow {:id :a :inputs [[:b]] :output identity :path [:a]})
    (rf/reg-flow {:id :b :inputs [[:c]] :output identity :path [:b]})
    (let [ex (try
               (rf/reg-flow {:id :c :inputs [[:a]] :output identity :path [:c]})
               (catch Throwable t t))
          cycle (:cycle (ex-data ex))]
      (is (some? ex) "three-flow cycle registration threw")
      (is (= 4 (count cycle))
          "three-flow cycle has length 4 (n+1)")
      (is (= (first cycle) (last cycle))
          ":cycle closes on itself")
      (is (= #{:a :b :c} (set (butlast cycle)))
          "all three offending ids appear in the path"))))

(deftest reg-flow-replacement-that-introduces-cycle-preserves-prior-registration
  ;; Regression for rf2-7csri (bug) / rf2-cdh9h (this test). Pinned per
  ;; audit rf2-o3hok finding Exec#2 (TE2). The bug: `reg-flow`'s former
  ;; rollback path wrote the new entry FIRST then ran cycle detection;
  ;; on a cyclic re-registration the rollback dissoc'd by id, which
  ;; DELETED the prior registration as well as the just-written one. A
  ;; hot-reload that accidentally introduced a cycle therefore silently
  ;; vacated the previously-working flow. The fix (rf2-7csri) runs cycle
  ;; detection on a PROSPECTIVE flow-map BEFORE mutating; on failure
  ;; nothing is written and the prior registration stays intact.
  (testing "a cyclic reg-flow REPLACEMENT must not silently delete the prior registration"
    ;; Set up a non-cyclic two-flow graph where REPLACING :b is what
    ;; closes the cycle. Cannot use a self-cycle on :b (topo-sort skips
    ;; the `id = other` self-edge via `(not= id other)`), so we set
    ;; :a's :inputs to point at :b's :path. After replacement of :b's
    ;; inputs to point at :a's :path, the cycle :a → :b → :a closes.
    ;;
    ;; 1. :a reads [:b], writes [:a]. Currently no cycle because :b is
    ;;    not yet registered.
    (rf/reg-flow {:id     :a
                  :inputs [[:b]]
                  :output (fn [b] (str "A-from-B-" b))
                  :path   [:a]})
    (is (contains? (get @flows/flows :rf/default) :a)
        "initial :a registers cleanly")

    ;; 2. :b reads an unrelated path [:source], writes [:b]. Graph is
    ;;    :b → :a (one-way), no cycle. The prior `reg-flow-detects-
    ;;    cycles-at-registration` test pins the INITIAL-cycle case
    ;;    (where :b at first registration closes the cycle). This test
    ;;    pins the REPLACEMENT case — :b registers cleanly first, then
    ;;    its replacement is what would close the cycle.
    (let [original-b-output (fn [src] (str "B-of-" src))]
      (rf/reg-flow {:id     :b
                    :inputs [[:source]]
                    :output original-b-output
                    :path   [:b]})
      (is (contains? (get @flows/flows :rf/default) :b)
          "initial :b registers cleanly (reads [:source]; no cycle)")

      ;; 3. RE-register :b with :inputs [[:a]]. :a already reads [:b],
      ;;    so the prospective graph closes :a → :b → :a. Cycle
      ;;    detection MUST reject the replacement.
      (is (thrown? Throwable
            (rf/reg-flow {:id     :b
                          :inputs [[:a]]
                          :output (fn [a] (str "B-from-A-" a))
                          :path   [:b]}))
          "the cyclic replacement of :b throws :rf.error/flow-cycle")

      ;; 4. THE KEY ASSERTION: the prior :b is STILL in the registry —
      ;;    not silently deleted. The bug today (flows.cljc:149) dissocs
      ;;    by id on rollback, vacating the prior registration along
      ;;    with the just-written one.
      (is (contains? (get @flows/flows :rf/default) :b)
          "after a failed cyclic replacement, the prior :b registration is preserved")
      (let [b-after (get-in @flows/flows [:rf/default :b])]
        (is (= [[:source]] (:inputs b-after))
            "prior :b's :inputs are intact ([[:source]], not the rejected [[:a]])")
        (is (identical? original-b-output (:output b-after))
            "prior :b's :output fn has the SAME identity (not the rejected new fn)"))
      ;; And the registrar slot — flow-id-keyed — must still resolve.
      (is (some? (registrar/lookup :flow :b))
          "the :flow registrar slot for :b is still populated"))))

;; ---------------------------------------------------------------------------
;; 2. Dirty-check / re-evaluation
;; ---------------------------------------------------------------------------

(deftest flow-recomputes-on-input-change
  (testing "mutating an input path causes the flow to fire and the output to update"
    (rf/reg-event-db :init (fn [_ _] {:w 0 :h 0}))
    (rf/reg-event-db :w!   (fn [db [_ w]] (assoc db :w w)))
    (rf/reg-event-db :h!   (fn [db [_ h]] (assoc db :h h)))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    (rf/dispatch-sync [:init])
    (is (= 0 (get-in (rf/get-frame-db :rf/default) [:rect :area]))
        "first drain after :init fires the flow with 0 × 0 = 0")
    (rf/dispatch-sync [:w! 5])
    (is (= 0 (get-in (rf/get-frame-db :rf/default) [:rect :area]))
        ":h is still 0 → 5 × 0 = 0; flow ran")
    (rf/dispatch-sync [:h! 6])
    (is (= 30 (get-in (rf/get-frame-db :rf/default) [:rect :area]))
        "5 × 6 = 30 after both inputs are populated")))

(deftest flow-noop-on-equal-input-rewrite
  (testing "rewriting an input path with an =-equal value does NOT re-fire the flow"
    (let [calls (atom 0)]
      (rf/reg-event-db :init      (fn [_ _] {:n 5}))
      (rf/reg-event-db :replace-n (fn [db [_ v]] (assoc db :n v)))
      (rf/reg-flow {:id     :double
                    :inputs [[:n]]
                    :output (fn [n]
                              (swap! calls inc)
                              (* 2 n))
                    :path   [:derived :doubled]})
      (rf/dispatch-sync [:init])
      (is (= 1 @calls) "first drain fires the flow once (initial evaluation)")
      (is (= 10 (get-in (rf/get-frame-db :rf/default) [:derived :doubled])))
      ;; Replace :n with the same value (5 → 5).
      (rf/dispatch-sync [:replace-n 5])
      (is (= 1 @calls)
          ":n was replaced with =-equal value; flow did NOT recompute")
      (is (= 10 (get-in (rf/get-frame-db :rf/default) [:derived :doubled]))
          "output unchanged")
      ;; Now flip :n to a different value.
      (rf/dispatch-sync [:replace-n 7])
      (is (= 2 @calls)
          ":n changed to 7; flow recomputed")
      (is (= 14 (get-in (rf/get-frame-db :rf/default) [:derived :doubled]))))))

(deftest flow-no-recompute-when-unrelated-path-changes
  (testing "writing an unrelated path does not re-fire a flow whose inputs are stable"
    (let [calls (atom 0)]
      (rf/reg-event-db :init      (fn [_ _] {:user {:name "alice"} :other 0}))
      (rf/reg-event-db :bump-other (fn [db _] (update db :other inc)))
      (rf/reg-flow {:id     :user/uppercase-name
                    :inputs [[:user :name]]
                    :output (fn [n]
                              (swap! calls inc)
                              (when n (.toUpperCase ^String n)))
                    :path   [:user :uppercase-name]})
      (rf/dispatch-sync [:init])
      (is (= 1 @calls) "first evaluation always fires")
      (is (= "ALICE" (get-in (rf/get-frame-db :rf/default)
                             [:user :uppercase-name])))
      (dotimes [_ 5] (rf/dispatch-sync [:bump-other]))
      (is (= 1 @calls)
          ":other changed but [:user :name] did not; flow stayed quiet"))))

;; ---------------------------------------------------------------------------
;; 3. Topological sort — B depends on A; one drain settles both
;; ---------------------------------------------------------------------------

(deftest flow-topo-sort-cascades-in-one-drain
  (testing "B reads what A wrote; topo sort places A first; one drain settles both"
    (rf/reg-event-db :init (fn [_ _] {:w 2 :h 3}))
    (rf/reg-event-db :w!   (fn [db [_ w]] (assoc db :w w)))
    ;; A: :area depends on :w :h, writes :rect/:area.
    (rf/reg-flow {:id     :rect/area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    ;; B: :area*2 depends on :rect/:area, writes :rect/:area*2.
    (rf/reg-flow {:id     :rect/area-doubled
                  :inputs [[:rect :area]]
                  :output (fn [a] (* 2 a))
                  :path   [:rect :area*2]})
    (rf/dispatch-sync [:init])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= 6  (get-in db [:rect :area]))   "A fired with 2 × 3 = 6")
      (is (= 12 (get-in db [:rect :area*2])) "B fired in the same drain with 6 × 2 = 12"))
    (rf/dispatch-sync [:w! 5])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= 15 (get-in db [:rect :area]))   "A re-fired: 5 × 3 = 15")
      (is (= 30 (get-in db [:rect :area*2])) "B saw A's new output and re-fired: 30"))))

(deftest flow-topo-sort-handles-prefix-overlap
  (testing "B's :inputs is a prefix of A's :path — A still runs before B (Spec 013 §Dependency rule)"
    (rf/reg-event-db :init (fn [_ _] {:user {:name "alice"} :note ""}))
    ;; A writes deep at [:user :uppercase] — its :path is rooted in
    ;; the same prefix as B's input.
    (rf/reg-flow {:id     :user/uppercase
                  :inputs [[:user :name]]
                  :output (fn [n] (when n (.toUpperCase ^String n)))
                  :path   [:user :uppercase]})
    ;; B's input is [:user] — a prefix of A's :path. Per Spec 013,
    ;; the dependency rule fires in either prefix direction.
    (rf/reg-flow {:id     :user/note
                  :inputs [[:user]]
                  :output (fn [u]
                            (str "user-keys:"
                                 (pr-str (vec (sort (keys u))))))
                  :path   [:summary :note]})
    (rf/dispatch-sync [:init])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= "ALICE" (get-in db [:user :uppercase]))
          "A wrote :user :uppercase")
      (is (= "user-keys:[:name :uppercase]"
             (get-in db [:summary :note]))
          "B saw both :name and the just-written :uppercase in one drain"))))

;; ---------------------------------------------------------------------------
;; 4. Hot-reload — re-registration preserves output when bodies agree
;; ---------------------------------------------------------------------------

(deftest flow-hot-reload-preserves-equivalent-output
  (testing "re-registering a flow with a body that produces the same output keeps the output stable"
    (rf/reg-event-db :init (fn [_ _] {:n 5}))
    (rf/reg-event-db :tick (fn [db _] (update db :tick (fnil inc 0))))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:init])
    (is (= 10 (get-in (rf/get-frame-db :rf/default) [:derived :doubled])))
    ;; Re-register with a body that produces the SAME output for the
    ;; current input. Per Spec 013 §Re-registration the next drain
    ;; re-evaluates; the user-visible output stays 10.
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (+ n n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:tick])
    (is (= 10 (get-in (rf/get-frame-db :rf/default) [:derived :doubled]))
        "value-equivalent re-registration leaves the output stable")))

(deftest flow-hot-reload-new-body-recomputes-on-next-drain
  (testing "if the new body would produce a different value, the next drain materialises it"
    (rf/reg-event-db :init  (fn [_ _] {:n 5}))
    (rf/reg-event-db :tick  (fn [db _] (update db :tick (fnil inc 0))))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:init])
    (is (= 10 (get-in (rf/get-frame-db :rf/default) [:derived :doubled])))
    ;; Re-register with a 100x body; same input still 5.
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 100 n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:tick])
    (is (= 500 (get-in (rf/get-frame-db :rf/default) [:derived :doubled]))
        "after re-registration the new body produces 5 × 100 = 500")))

;; ---------------------------------------------------------------------------
;; 5. Toggle via :rf.fx/reg-flow / :rf.fx/clear-flow
;; ---------------------------------------------------------------------------

(deftest fx-reg-flow-and-clear-flow-round-trip
  (testing ":rf.fx/reg-flow registers; :rf.fx/clear-flow removes; the output path is dissoc'd"
    (rf/reg-event-db :init  (fn [_ _] {:wizard {:foo 3 :bar 4}}))
    (rf/reg-event-fx :enter (fn [_ _]
                              {:fx [[:rf.fx/reg-flow
                                     {:id     :step-2/computed
                                      :inputs [[:wizard :foo] [:wizard :bar]]
                                      :output (fn [foo bar] (+ foo bar))
                                      :path   [:wizard :result]}]]}))
    (rf/reg-event-db :foo!  (fn [db [_ v]] (assoc-in db [:wizard :foo] v)))
    (rf/reg-event-fx :leave (fn [_ _]
                              {:fx [[:rf.fx/clear-flow :step-2/computed]]}))
    (rf/dispatch-sync [:init])
    (is (nil? (get-in (rf/get-frame-db :rf/default) [:wizard :result]))
        "no flow yet — :result is unset")
    ;; Register the flow during :enter. Per Spec 013 §Sequencing the flow
    ;; first runs on the NEXT event drain.
    (rf/dispatch-sync [:enter])
    (is (contains? (get @flows/flows :rf/default) :step-2/computed)
        "registry now carries :step-2/computed")
    ;; Drive a drain with a benign event; the flow first-fires here.
    (rf/dispatch-sync [:foo! 5])
    (is (= 9 (get-in (rf/get-frame-db :rf/default) [:wizard :result]))
        "flow ran on this drain with 5 + 4 = 9")
    ;; Now clear via fx.
    (rf/dispatch-sync [:leave])
    (is (not (contains? (get @flows/flows :rf/default) :step-2/computed))
        "registry slot removed")
    (is (not (contains? (get (rf/get-frame-db :rf/default) :wizard) :result))
        ":rf.fx/clear-flow dissoc-in'd the output path")))

;; ---------------------------------------------------------------------------
;; 6. clear-all / clean-state interaction with :flow registrar slot
;; ---------------------------------------------------------------------------

(deftest clear-all-clears-flow-registrar-slot
  (testing "registrar/clear-all! removes the :flow kind so subsequent reg-flow starts clean"
    (rf/reg-flow {:id :one :inputs [[:a]] :output identity :path [:slots :one]})
    (rf/reg-flow {:id :two :inputs [[:a]] :output identity :path [:slots :two]})
    (is (some? (registrar/lookup :flow :one)))
    (is (some? (registrar/lookup :flow :two)))
    (registrar/clear-all!)
    (is (nil? (registrar/lookup :flow :one)))
    (is (nil? (registrar/lookup :flow :two)))))

(deftest reset-flows-atom-clears-per-frame-state
  (testing "resetting flows/flows clears the per-frame registry; reg-flow repopulates fresh"
    (rf/reg-flow {:id :one :inputs [[:a]] :output identity :path [:slots :one]})
    (is (contains? (get @flows/flows :rf/default) :one))
    (reset! flows/flows {})
    (reset! schemas/schemas-by-frame {})
    (is (empty? (get @flows/flows :rf/default))
        "per-frame map is empty after reset")
    (rf/reg-flow {:id :one :inputs [[:a]] :output identity :path [:slots :one]})
    (is (contains? (get @flows/flows :rf/default) :one)
        "re-registration after reset works without raising")))

;; ---------------------------------------------------------------------------
;; 7. clear-flow :frame opt routing — multi-frame registrar-slot retention
;;
;; Per audit rf2-0b2eh §TC-2 / rf2-otbub. The cross-artefact
;; `smoke_test.clj` exercises frame-scoped flow REGISTRATION end-to-end,
;; but the flows artefact's own test alias did not pin `clear-flow`'s
;; `:frame` opt routing — three branches in registry.cljc went unverified
;; here:
;;
;; 1. Frame opt routing: `(clear-flow :foo {:frame :left})` removes the
;;    flow from `:left`'s per-frame map only; sibling frame `:right`'s
;;    identically-named flow stays intact.
;; 2. "Last frame holding id" registrar-slot retention (registry.cljc
;;    line ~254 `not-any?`): when the same flow id is registered against
;;    two frames, clearing it from one frame must NOT unregister the
;;    `:flow` registrar slot — the other frame still needs it for
;;    hot-reload tracking. Clearing from the second frame then unregisters.
;; 3. app-db `dissoc-in` is frame-local: clearing on `:left` only
;;    dissoc-in's `:left`'s app-db; `:right`'s app-db is untouched.
;;
;; Spec 013 §Frame-scoping calls all three properties out normatively.
;; This deftest pins them inside the flows slice's own gate so the
;; artefact doesn't rely on smoke_test.clj catching regressions.
;; ---------------------------------------------------------------------------

(deftest clear-flow-routes-via-frame-opt
  (testing "the same flow id registers independently against two frames"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n}))
    ;; Register :compute against both frames with DIFFERENT :output fns
    ;; so sibling-frame-untouched is observable in the materialised output.
    (rf/reg-flow {:id     :compute
                  :inputs [[:n]]
                  :output (fn [n] (* 2 (or n 0)))
                  :path   [:result]}
                 {:frame :left})
    (rf/reg-flow {:id     :compute
                  :inputs [[:n]]
                  :output (fn [n] (* 100 (or n 0)))
                  :path   [:result]}
                 {:frame :right})
    (rf/dispatch-sync [:seed 5] {:frame :left})
    (rf/dispatch-sync [:seed 5] {:frame :right})
    (is (= 10  (:result (rf/get-frame-db :left)))
        "left frame's :compute used the 2x formula (5 * 2)")
    (is (= 500 (:result (rf/get-frame-db :right)))
        "right frame's :compute used the 100x formula (5 * 100)")
    (is (contains? (get @flows/flows :left)  :compute)
        ":left's per-frame registry slot carries :compute")
    (is (contains? (get @flows/flows :right) :compute)
        ":right's per-frame registry slot carries :compute"))

  (testing "clear-flow on one frame leaves the sibling frame's registry slot intact"
    ;; Branch 1: per-frame registry routing.
    (rf/clear-flow :compute {:frame :left})
    (is (not (contains? (get @flows/flows :left)  :compute))
        ":left's slot was removed")
    (is (contains? (get @flows/flows :right) :compute)
        ":right's slot is untouched — flow STILL registered against :right"))

  (testing ":left's app-db output path is dissoc'd; :right's app-db is unchanged"
    ;; Branch 3: app-db dissoc-in is frame-local.
    (is (not (contains? (rf/get-frame-db :left) :result))
        ":left's :result was dissoc'd by the frame-scoped clear")
    (is (= 500 (:result (rf/get-frame-db :right)))
        ":right's :result is preserved (the previous compute's output)"))

  (testing "the :flow registrar slot survives clear-from-one-frame (multi-frame retention)"
    ;; Branch 2: the "last-frame-holding-id" check — the registrar slot is
    ;; flow-id-keyed and shared across frames. Clearing on :left while
    ;; :right still registers the same id MUST keep the slot populated so
    ;; hot-reload tracking continues to work for :right's copy.
    (is (some? (registrar/lookup :flow :compute))
        "the :flow registrar slot is still populated — :right still holds the id"))

  (testing "clearing from the second (last) frame finally unregisters the registrar slot"
    (rf/clear-flow :compute {:frame :right})
    (is (not (contains? (get @flows/flows :right) :compute))
        ":right's slot is now gone")
    (is (nil? (registrar/lookup :flow :compute))
        "registrar slot was unregistered once the LAST frame released the id")))

;; ---------------------------------------------------------------------------
;; 8. `_hot-reload-hook` defonce-idempotency on namespace reload
;;
;; Per audit rf2-0b2eh §TC-3 / rf2-5ay09. The flows registry installs a
;; registrar replacement-hook (`invalidate-flow-on-replace!`) once at
;; namespace load via `(defonce ^:private _hot-reload-hook
;; (registrar/add-replacement-hook! ...))`. If the `defonce` protection
;; ever regressed to a plain `def`, every namespace reload would push a
;; duplicate hook into `re-frame.registrar/replacement-hooks` — every
;; subsequent flow re-registration would invalidate `last-inputs` twice
;; (functionally harmless because `dissoc` is idempotent, but a silent
;; bookkeeping leak that would compound across many hot-reload cycles in
;; long dev sessions).
;;
;; Pin the idempotency: `(require 're-frame.flows.registry :reload)`
;; MUST NOT push a duplicate hook.
;; ---------------------------------------------------------------------------

(deftest hot-reload-hook-is-defonce-idempotent
  (testing "reloading re-frame.flows.registry does NOT install a duplicate replacement-hook"
    (let [hooks-var (resolve 're-frame.registrar/replacement-hooks)
          before    (count @(deref hooks-var))]
      (require 're-frame.flows.registry :reload)
      (let [after (count @(deref hooks-var))]
        (is (= before after)
            "the hook count is unchanged across a namespace reload — `defonce` guards the install")))))
