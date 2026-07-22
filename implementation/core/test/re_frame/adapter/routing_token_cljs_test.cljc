(ns re-frame.adapter.routing-token-cljs-test
  "Stable-token hook routing (rf2-dkl5z1).

  `substrate-adapter/route-hook!` wraps each adapter's late-bind hook impl
  in a closure that fires ONLY when that adapter is the (rf/init!)-installed
  one. The original guard was raw object identity —
  `(identical? adapter-spec (current-adapter-spec))` — which is WRONG against
  a COPIED or wrapped canonical adapter map: a value-equal map installed via
  `assoc`/`merge`/copy has a different identity, so every routed hook silently
  fell through to the chain/fallback (inert), even though the user installed a
  fully-functional adapter (Spec 006 §Frame-provider via React context
  requires `:adapter/current-frame` to resolve to the LIVE routed impl). The
  already-tested adapter-swap pattern (`boot_test/adapter-swap-...`) installs
  exactly such an `assoc`'d copy.

  The fix routes by a STABLE token carried in the installed map — the
  canonical `:rf.adapter/*` `:kind` discriminator — which survives a copy.
  This ns pins the mechanism substrate-agnostically (JVM + the :node-test
  CLJS gate, via .cljc) against the plain-atom adapter, independently of any
  one substrate's hook wiring; the per-substrate observable regressions
  (copied UIx/Reagent/reagent-slim maps still drive their live hooks,
  copied Test-React map still mounts) live in the adapter suites."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---- fixture --------------------------------------------------------------
;; Each test installs/disposes adapters explicitly (the unit under test is
;; the install-time routing token), so the fixture only guarantees a cold
;; adapter slot before and after.

(defn- cold-adapter [test-fn]
  (adapter/dispose-adapter!)
  (adapter/reset-lifecycle-state-for-tests!)
  (test-fn)
  (adapter/dispose-adapter!)
  (adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each cold-adapter)

(def ^:private probe-key :rf.test/routing-token-probe)

(defn- install-probe!
  "Route `impl` for the probe hook against `adapter-spec` (fallback returns
  `:fell-through`). Returns the routed closure read back from the table."
  [adapter-spec impl]
  (adapter/route-hook! adapter-spec probe-key impl (constantly :fell-through))
  (late-bind/get-fn probe-key))

;; ---- same-adapter? token predicate ----------------------------------------

(deftest same-adapter-true-for-copied-canonical-map
  (testing "a copied/assoc'd canonical adapter map is the SAME adapter by token"
    (let [copied (assoc plain-atom/adapter :instrumentation-wrapper true)]
      (is (false? (identical? plain-atom/adapter copied))
          "precondition: the copy is a distinct object (identity differs)")
      (is (= (:kind plain-atom/adapter) (:kind copied))
          "precondition: the copy preserves the canonical :kind token")
      (is (true? (adapter/same-adapter? plain-atom/adapter copied))
          "same canonical :kind ⇒ same adapter for routing, despite distinct identity")
      (is (true? (adapter/same-adapter? copied plain-atom/adapter))
          "the token comparison is symmetric for two canonical maps"))))

(deftest same-adapter-false-across-different-kinds
  (testing "two canonical maps with DIFFERENT :kind are not the same adapter"
    (let [as-uix (assoc plain-atom/adapter :kind :rf.adapter/uix)]
      (is (false? (adapter/same-adapter? plain-atom/adapter as-uix))
          "a different canonical :kind is a different adapter"))))

(deftest same-adapter-falls-back-to-identity-for-non-canonical
  (testing "a non-canonical / :custom / kindless adapter routes by object identity"
    (let [custom-a {:kind :custom :make-state-container identity}
          custom-b {:kind :custom :make-state-container identity}
          kindless (dissoc plain-atom/adapter :kind)
          kindless-copy (assoc kindless :x 1)]
      (is (true? (adapter/same-adapter? custom-a custom-a))
          "a :custom adapter is the same as itself (identity)")
      (is (false? (adapter/same-adapter? custom-a custom-b))
          "two distinct :custom adapters are NOT conflated by the shared :custom keyword")
      (is (true? (adapter/same-adapter? kindless kindless))
          "a kindless adapter is the same as itself (identity)")
      (is (false? (adapter/same-adapter? kindless kindless-copy))
          "a kindless adapter copy is NOT the same — no canonical token to route by"))))

(deftest same-adapter-nil-safe
  (testing "a nil installed-adapter (none installed) is never the same as a real one"
    (is (false? (adapter/same-adapter? plain-atom/adapter nil)))
    (is (false? (adapter/same-adapter? nil plain-atom/adapter)))
    (is (false? (adapter/same-adapter? nil nil)))))

;; ---- route-hook! dispatches a COPIED canonical map to the live impl --------

(deftest routed-hook-fires-for-copied-canonical-map
  (testing "a routed hook fires its LIVE impl when a COPY of its adapter is installed (rf2-dkl5z1)"
    (let [fired  (atom 0)
          routed (install-probe! plain-atom/adapter
                                 (fn [& _] (swap! fired inc) :live-impl))
          ;; Install a COPY (assoc'd instrumentation wrapper) — a distinct
          ;; object, same canonical :kind. This is the bug's exact shape.
          copied (assoc plain-atom/adapter :instrumentation-wrapper true)]
      (adapter/install-adapter! copied)
      ;; Precondition that makes this test meaningful: the ROUTED adapter
      ;; (plain-atom/adapter) is a DIFFERENT object from the installed copy.
      ;; Pre-fix, the routed closure's `(identical? ...)` guard saw this
      ;; mismatch and fell through — the exact defect.
      (is (false? (identical? plain-atom/adapter (adapter/current-adapter-spec)))
          "the installed copy is NOT identical to the routed canonical map")
      (is (= :live-impl (routed))
          "the routed hook dispatched to its LIVE impl for the copied canonical map")
      (is (= 1 @fired)
          "the live impl actually ran (not the :fell-through fallback)"))))

(deftest routed-hook-falls-through-for-different-kind
  (testing "a routed hook does NOT fire when a DIFFERENT-kind adapter is installed"
    (let [fired  (atom 0)
          routed (install-probe! plain-atom/adapter
                                 (fn [& _] (swap! fired inc) :live-impl))]
      ;; Install a map whose :kind differs from the routed adapter's token.
      (adapter/install-adapter! (assoc plain-atom/adapter :kind :rf.adapter/uix))
      (is (= :fell-through (routed))
          "a different-kind installed adapter routes to the fallback")
      (is (zero? @fired)
          "the plain-atom impl did NOT run under a uix-kind install"))))

(deftest routed-hook-falls-through-when-nothing-installed
  (testing "a routed hook falls through to the fallback when no adapter is installed"
    (let [routed (install-probe! plain-atom/adapter (fn [& _] :live-impl))]
      (is (= :fell-through (routed))
          "no adapter installed ⇒ the routed closure returns its fallback"))))
