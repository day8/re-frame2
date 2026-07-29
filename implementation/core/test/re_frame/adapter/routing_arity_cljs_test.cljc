(ns re-frame.adapter.routing-arity-cljs-test
  "Arity fidelity of `substrate-adapter/route-hook!`'s routed closure
  (rf2-2ix22).

  The wrapper used to be spelled `(fn routed-hook [& args] … (apply impl-fn
  args) …)`: one `apply` site shared by every routed hook in the bundle,
  hence callee-POLYMORPHIC and never inlined. It is now spelled with
  explicit 0/1/2-argument paths that call `impl-fn` (or the chained
  `previous` handler) DIRECTLY, plus a variadic tail for 3-and-up.

  The generated shape is not a contract; what IS contract is that the routed
  closure forwards the SAME arguments, answers the SAME value, chains in the
  SAME order, and calls its fallback as a ZERO-argument thunk — at every
  arity, on all three routes:

    * ACTIVE       — this adapter is installed, so `impl-fn` runs
    * CHAIN        — another adapter is installed, so the previously
                     registered routed closure runs, with the args intact
    * FALLBACK     — nothing is installed and there is no previous handler,
                     so `(fallback-fn)` runs with no arguments at all

  Splitting one variadic body into four is exactly the kind of change that
  silently drops an argument or mis-routes an arity, so each route is
  exercised at 0, 1, 2, 3 and 4 arguments: the three explicit paths and two
  that must reach the variadic tail. Repository call sites use 0, 1 or 2
  args routinely and `:adapter/wrap-view` uses 3.

  `.cljc`, so the pin runs on the JVM (`clojure -M:test`) AND in the
  `:node-test` CLJS lane where the `apply` this change removes was actually
  costing bytes. The per-substrate hook wiring is pinned by the adapter
  suites; this ns pins the routing MECHANISM against the plain-atom adapter,
  substrate-agnostically — the same division of labour as
  `routing_token_cljs_test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---- fixture --------------------------------------------------------------
;; Each test installs/disposes explicitly (install-time routing is the unit
;; under test), so the fixture only guarantees a cold adapter slot either side.

(defn- cold-adapter [test-fn]
  (adapter/dispose-adapter!)
  (adapter/reset-lifecycle-state-for-tests!)
  (test-fn)
  (adapter/dispose-adapter!)
  (adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each cold-adapter)

;; A DISTINCT probe key per test: `route-hook!` chains onto whatever is
;; already registered under the key, so a shared key would leave each test
;; running on top of its predecessors' links.
(def ^:private active-key   :rf.test/routing-arity-active)
(def ^:private chain-key    :rf.test/routing-arity-chain)
(def ^:private fallback-key :rf.test/routing-arity-fallback)

;; The five call shapes: the three arities the wrapper spells explicitly,
;; then two that must reach its variadic tail.
(def ^:private arg-vectors [[] [:a] [:a :b] [:a :b :c] [:a :b :c :d]])

(defn- call-at-every-arity
  "Invoke `f` at 0, 1, 2, 3 and 4 arguments — written out rather than
  `apply`d, so each explicit arity path of the routed closure is entered the
  way a real caller enters it — and answer the five results in order."
  [f]
  [(f) (f :a) (f :a :b) (f :a :b :c) (f :a :b :c :d)])

(defn- recorder
  "A hook impl that records the argument vector it received into `log` and
  answers `[tag args]`. Variadic, so it accepts whatever it is handed and
  the ASSERTION — not an arity exception — is what reports a dropped arg."
  [log tag]
  (fn [& args]
    (let [args (vec args)]
      (swap! log conj args)
      [tag args])))

;; ---- ACTIVE: the installed adapter's impl, at every arity ------------------

(deftest routed-hook-forwards-every-arity-to-the-live-impl
  (testing "the ACTIVE adapter's routed hook forwards its arguments verbatim at 0/1/2/3/4 args"
    (let [seen   (atom [])
          _      (adapter/route-hook! plain-atom/adapter active-key
                                      (recorder seen :impl)
                                      (constantly :fell-through))
          routed (late-bind/get-fn active-key)]
      (adapter/install-adapter! plain-atom/adapter)
      (is (= (mapv (fn [args] [:impl args]) arg-vectors)
             (call-at-every-arity routed))
          "the impl's return value is answered unchanged at every arity")
      (is (= arg-vectors @seen)
          "the impl received exactly the arguments the caller passed, at every arity"))))

;; ---- CHAIN: fall-through to the previously-registered routed closure -------

(deftest routed-hook-falls-through-to-the-chain-at-every-arity
  (testing "an INACTIVE adapter's routed hook chains to the previous handler with the args intact"
    (let [inner-seen (atom [])
          outer-seen (atom [])
          ;; INNER link first: plain-atom's hook. Then an OUTER link for a
          ;; different-kind adapter registers on top of it.
          _          (adapter/route-hook! plain-atom/adapter chain-key
                                          (recorder inner-seen :inner)
                                          (constantly :inner-fallback))
          other      (assoc plain-atom/adapter :kind :rf.adapter/uix)
          _          (adapter/route-hook! other chain-key
                                          (recorder outer-seen :outer)
                                          (constantly :outer-fallback))
          routed     (late-bind/get-fn chain-key)]
      ;; plain-atom is installed, so the OUTER (uix-kind) link is inactive and
      ;; must chain rather than fire.
      (adapter/install-adapter! plain-atom/adapter)
      (is (= (mapv (fn [args] [:inner args]) arg-vectors)
             (call-at-every-arity routed))
          "the chained inner impl's value is answered at every arity")
      (is (= arg-vectors @inner-seen)
          "the arguments survived the hop through the inactive outer link, at every arity")
      (is (= [] @outer-seen)
          "the inactive adapter's own impl never ran"))))

;; ---- FALLBACK: the zero-argument thunk, whatever the call arity ------------

(deftest routed-hook-falls-back-to-a-zero-arg-thunk-at-every-arity
  (testing "with nothing installed and no previous handler, every arity calls the fallback with NO args"
    (let [fallback-seen (atom [])
          impl-seen     (atom [])
          _             (adapter/route-hook! plain-atom/adapter fallback-key
                                             (recorder impl-seen :impl)
                                             (recorder fallback-seen :fell-through))
          routed        (late-bind/get-fn fallback-key)]
      ;; No adapter installed: `same-adapter?` is false and `previous` is nil.
      (is (= (repeat 5 [:fell-through []])
             (call-at-every-arity routed))
          "the fallback's value is answered at every arity")
      (is (= (repeat 5 []) @fallback-seen)
          "the fallback is invoked as a ZERO-argument thunk regardless of the call's arity")
      (is (= [] @impl-seen)
          "the impl never ran with no adapter installed"))))
