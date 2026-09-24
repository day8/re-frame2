(ns re-frame.sub-cycle-cljs-test
  "A declared-input dependency cycle in the sub graph must fail LOUD
  with a structured `:rf.error/sub-cycle` (mirroring flows' typed
  `:rf.error/flow-cycle`) and recover to nil, NOT blow the host stack with a
  raw StackOverflowError.

  Unguarded, `subscribe` → `compute-and-cache!` → `subscribe` (input) … would
  recurse with no build-in-progress marker (the reaction is cached only AFTER
  its inputs resolve), and the pure `compute-sub` per-call memo memoises only
  AFTER the body runs — so the first subscribe/compute of a two-node cycle
  (`:a` over `:b`, `:b` over `:a`) or a self-edge (`:self` over itself) would
  die with a RAW `StackOverflowError`. The guard tracks the per-thread build
  stack (reactive) / per-call memo (`compute-sub`) and detects the re-entry.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`,
  so both the JVM (where an unguarded cycle surfaces as a raw SOE) and the CLJS
  node runtime exercise the guard.

  ## Posture split

  The GUARD is production behaviour; its REPORT is not. `rf.subs/emit-sub-cycle!`
  is a bare `trace/emit-error!` and says so in its own docstring — the
  DIAGNOSTIC trace channel, dev-only, DCE'd under `:advanced` +
  `goog.DEBUG=false` — with no always-on leg. Every row reading the structured
  event's `:cycle` / `:where` / count therefore sits inside a
  `(when rf.interop/debug-enabled? ...)` arm.

  The guard's production-visible behaviour stays OUTSIDE those arms and runs
  under `scripts/test-core-prod-gate.sh`: the cyclic subscribe RECOVERS to a
  nil-yielding reaction instead of blowing the host stack with a raw
  StackOverflowError, `compute-sub` returns nil, the ref-count teeth show the
  earlier input released on the cycle unwind, and the acyclic diamond computes
  `[42 40]`.

  `acyclic-diamond-does-not-trip-the-cycle-guard`'s two `(is (empty? events))`
  rows certify an absence of spurious cycle errors over a stream that is empty
  for EVERY graph under the gate, so they sit in the arm beside the positives
  that give them teeth.

  `reactive-cycle-recovery-is-not-cached` asserts the sub-cache miss itself,
  which is what `not cached` means and is readable off production state —
  rather than relying on the trace count alone, which under the gate would
  leave a deftest that subscribes twice and asserts nothing."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.frame          :as rf.frame]
            [re-frame.interop        :as rf.interop]
            [re-frame.subs           :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support   :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private fid :rf/default)

(defn- capture-sub-cycles
  "Run `thunk` with a `:trace` listener recording every `:rf.error/sub-cycle`
  event; return `[thunk-result recorded-events]`."
  [thunk]
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::rec
      (fn [ev] (when (= :rf.error/sub-cycle (:operation ev))
                 (swap! recorded conj ev))))
    (try
      (let [result (thunk)]
        [result @recorded])
      (finally
        (rf/unregister-listener! :trace ::rec)))))

(defn- register-cyclic-subs! []
  (rf/reg-sub :a {:inputs [[:b]]} (fn [[b] _] b))
  (rf/reg-sub :b {:inputs [[:a]]} (fn [[a] _] a))
  (rf/reg-sub :self {:inputs [[:self]]} (fn [[x] _] x)))

;; ===========================================================================
;; Reactive `subscribe` path
;; ===========================================================================

(deftest reactive-two-node-cycle-emits-structured-error-and-recovers-to-nil
  (testing "subscribing a two-node declared-input cycle (:a<->:b) emits a structured
            :rf.error/sub-cycle carrying the cycle path and recovers to a
            nil-yielding reaction — NOT a raw StackOverflowError"
    (register-cyclic-subs!)
    (let [[reaction events] (capture-sub-cycles #(rf.subs/subscribe [:a] {:frame fid}))]
      (is (nil? (deref reaction))
          "the cyclic subscription recovered to a nil-yielding reaction")
      ;; The structured REPORT is diagnostic-channel only.
      (when rf.interop/debug-enabled?
        (is (= 1 (count events))
            "exactly one structured :rf.error/sub-cycle was emitted")
        (let [ev (first events)]
          (is (= :rf.error/sub-cycle (:operation ev)))
          (is (= :error (:op-type ev)))
          (is (= :subscribe (:where (:tags ev))))
          (is (= [:a :b :a] (:cycle (:tags ev)))
              "the cycle path is the closing-repeat sub-id chain"))))))

(deftest reactive-self-cycle-emits-structured-error-and-recovers-to-nil
  (testing "subscribing a self-edge (:self <- [:self]) emits :rf.error/sub-cycle
            with a [:self :self] path and recovers to nil"
    (register-cyclic-subs!)
    (let [[reaction events] (capture-sub-cycles #(rf.subs/subscribe [:self] {:frame fid}))]
      (is (nil? (deref reaction))
          "the self-cyclic subscription recovered to a nil-yielding reaction")
      ;; Diagnostic-channel report.
      (when rf.interop/debug-enabled?
        (is (= 1 (count events)))
        (is (= [:self :self] (:cycle (:tags (first events))))
            "a self-cycle repeats the one id, e.g. [:self :self]")))))

(deftest reactive-cycle-recovery-is-not-cached
  (testing "the cyclic build is NOT cached (mirroring the no-such-sub miss), so
            a second subscribe re-detects + re-emits rather than silently
            handing back a broken cached reaction"
    (register-cyclic-subs!)
    (let [cache      (:sub-cache (rf.frame/frame fid))
          [_ events] (capture-sub-cycles
                       (fn []
                         (rf.subs/subscribe [:a] {:frame fid})
                         (rf.subs/subscribe [:a] {:frame fid})))]
      ;; ALWAYS-ON: "not cached" is a statement about the sub-cache, and the
      ;; cache is production state. This IS the claim; the re-emission below
      ;; only corroborates it, on a channel a production build does not have.
      (is (nil? (get @cache [:a]))
          "the cyclic build left no cache entry - a later subscribe re-detects
           rather than being handed a broken cached reaction")
      (is (nil? (get @cache [:b]))
          "nor did the other node of the cycle get cached mid-build")
      (when rf.interop/debug-enabled?
        (is (= 2 (count events))
            "each subscribe of the uncached cyclic sub re-emits the structured error")))))

(deftest reactive-multi-input-cycle-releases-earlier-input-refs
  (testing "a >=2-input sub whose NON-FIRST input cycles releases the
            already-acquired earlier input ref on the cycle-unwind path — the
            earlier input's cache ref-count returns to baseline after recovery,
            with no bounded leak. The first input (:leaf) is subscribed (ref
            bumped) before the abandoned :multi build unwinds to the outermost
            recovery WITHOUT ever wiring its on-dispose, so absent an explicit
            release nothing would release :leaf — a monotonic +1 leak."
    ;; :leaf — a layer-1 (`:db`) sub: cacheable + ref-countable, no declared inputs.
    (rf/reg-sub :leaf (fn [db _] (:leaf db 7)))
    ;; :cyc — closes the cycle back to :multi.
    (rf/reg-sub :cyc {:inputs [[:multi]]} (fn [[m] _] m))
    ;; :multi — TWO inputs; the FIRST (:leaf) resolves cleanly, the SECOND
    ;; (:cyc) cycles back to :multi — the multi-input edge case.
    (rf/reg-sub :multi {:inputs [[:leaf] [:cyc]]} (fn [[l c] _] [l c]))
    (let [cache (:sub-cache (rf.frame/frame fid))]
      ;; Baseline: :leaf is not yet in the cache.
      (is (nil? (get @cache [:leaf]))
          "precondition: :leaf has no cache entry before the cyclic subscribe")
      (let [[reaction events] (capture-sub-cycles #(rf.subs/subscribe [:multi] {:frame fid}))]
        (is (nil? (deref reaction))
            "the cyclic multi-input subscription recovered to a nil-yielding reaction")
        ;; Diagnostic-channel report; the TEETH below are always-on.
        (when rf.interop/debug-enabled?
          (is (= 1 (count events))
              "exactly one structured :rf.error/sub-cycle was emitted")
          (is (= [:multi :cyc :multi] (:cycle (:tags (first events))))
              "the cycle path is the closing-repeat sub-id chain through the non-first input"))
        ;; TEETH: :leaf was subscribed (ref bumped) BEFORE :cyc
        ;; cycled. On the cycle-unwind path it must be released so its ref-count
        ;; returns to baseline (entry dropped on the 1→0 transition). Without
        ;; that release the entry would linger with :ref-count 1 and this
        ;; assertion would fail.
        (is (or (nil? (get @cache [:leaf]))
                (zero? (or (get-in @cache [[:leaf] :ref-count]) 0)))
            "the earlier input :leaf's ref-count returned to baseline (released on the cycle unwind) — no bounded leak")))))

;; ===========================================================================
;; Pure `compute-sub` path (per-call memo doubles as the under-construction set)
;; ===========================================================================

(deftest compute-sub-two-node-cycle-emits-structured-error-and-returns-nil
  (testing "compute-sub of a two-node declared-input cycle emits :rf.error/sub-cycle and
            returns nil — NOT a raw StackOverflowError"
    (register-cyclic-subs!)
    (let [[v events] (capture-sub-cycles #(rf/compute-sub [:a] {}))]
      (is (nil? v) "compute-sub recovered the cyclic sub to nil")
      ;; Diagnostic-channel report.
      (when rf.interop/debug-enabled?
        (is (= 1 (count events)))
        (let [ev (first events)]
          (is (= :compute-sub (:where (:tags ev))))
          (is (= [:a :b :a] (:cycle (:tags ev)))))))))

(deftest compute-sub-self-cycle-emits-structured-error-and-returns-nil
  (testing "compute-sub of a self-edge emits :rf.error/sub-cycle and returns nil"
    (register-cyclic-subs!)
    (let [[v events] (capture-sub-cycles #(rf/compute-sub [:self] {}))]
      (is (nil? v))
      ;; Diagnostic-channel report.
      (when rf.interop/debug-enabled?
        (is (= 1 (count events)))
        (is (= [:self :self] (:cycle (:tags (first events)))))))))

;; ===========================================================================
;; Sanity baseline — a NON-cyclic diamond does not trip the guard
;; ===========================================================================

(deftest acyclic-diamond-does-not-trip-the-cycle-guard
  (testing "a legitimate diamond (:c depends on :a and :b; both depend on :root)
            builds cleanly and emits NO :rf.error/sub-cycle — the guard fires on
            genuine cycles only"
    (rf/reg-sub :root (fn [db _] (:root db 41)))
    (rf/reg-sub :a {:inputs [[:root]]} (fn [[r] _] (inc r)))
    (rf/reg-sub :b {:inputs [[:root]]} (fn [[r] _] (dec r)))
    (rf/reg-sub :c {:inputs [[:a] [:b]]} (fn [[a b] _] [a b]))
    (let [[reaction events] (capture-sub-cycles #(rf.subs/subscribe [:c] {:frame fid}))]
      (is (= [42 40] (deref reaction)) "the diamond computed correctly")
      ;; VACUOUS UNDER THE GATE. `events` is empty for EVERY graph
      ;; when the diagnostic channel emits nothing, so outside the arm this row
      ;; would certify "no spurious cycle error" having never been able to see
      ;; one. It sits in the arm beside the positive that gives it teeth.
      (when rf.interop/debug-enabled?
        (is (empty? events) "no spurious :rf.error/sub-cycle for an acyclic graph")))
    (let [[v events] (capture-sub-cycles #(rf/compute-sub [:c] {:root 41}))]
      (is (= [42 40] v) "compute-sub diamond computed correctly")
      (when rf.interop/debug-enabled?
        (is (empty? events) "no spurious cycle from the compute-sub memo path")))))
