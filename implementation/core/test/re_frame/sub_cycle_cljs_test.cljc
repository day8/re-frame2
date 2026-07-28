(ns re-frame.sub-cycle-cljs-test
  "rf2-x76af2.24 — a `:<-` dependency cycle in the sub graph must fail LOUD
  with a structured `:rf.error/sub-cycle` (mirroring flows' typed
  `:rf.error/flow-cycle`) and recover to nil, NOT blow the host stack with a
  raw StackOverflowError.

  Before the fix `subscribe` → `compute-and-cache!` → `subscribe` (input) …
  recursed with no build-in-progress marker (the reaction is cached only AFTER
  its inputs resolve), and the pure `compute-sub` per-call memo memoised only
  AFTER the body ran — so the first subscribe/compute of a two-node cycle
  (`:a :<- [:b]`, `:b :<- [:a]`) or a self-edge (`:self :<- [:self]`) died with
  a RAW `StackOverflowError`. The guard tracks the per-thread build stack
  (reactive) / per-call memo (`compute-sub`) and detects the re-entry.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`,
  so both the JVM (where the raw SOE was reproduced) and the CLJS node runtime
  exercise the guard.

  ## Posture split (rf2-d2841)

  The GUARD is production behaviour; its REPORT is not. `subs/emit-sub-cycle!`
  is a bare `trace/emit-error!` and says so in its own docstring — the
  DIAGNOSTIC trace channel, dev-only, DCE'd under `:advanced` +
  `goog.DEBUG=false` — with no always-on leg. Every row reading the structured
  event's `:cycle` / `:where` / count therefore sits inside a
  `(when interop/debug-enabled? ...)` arm.

  Everything that made this bead worth fixing stays OUTSIDE those arms and now
  runs under `scripts/test-core-prod-gate.sh`: the cyclic subscribe RECOVERS to
  a nil-yielding reaction instead of blowing the host stack with a raw
  StackOverflowError, `compute-sub` returns nil, the rf2-t3cpn3 ref-count teeth
  show the earlier input released on the cycle unwind, and the acyclic diamond
  still computes `[42 40]`.

  One vacuous pass was found and moved: `acyclic-diamond-does-not-trip-the-cycle-guard`'s
  two `(is (empty? events))` rows certified an absence of spurious cycle errors
  over a stream that is empty for EVERY graph under the gate. They now sit in
  the arm beside the positives that give them teeth.

  And `reactive-cycle-recovery-is-not-cached` had NOTHING but the trace count —
  guarding it would have left a deftest that subscribed twice and asserted
  nothing. Its claim is directly readable off production state, so it now
  asserts the sub-cache miss itself, which is what `not cached` means."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.frame          :as frame]
            [re-frame.interop        :as interop]
            [re-frame.subs           :as subs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support   :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

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
  (rf/reg-sub :a :<- [:b] (fn [b _] b))
  (rf/reg-sub :b :<- [:a] (fn [a _] a))
  (rf/reg-sub :self :<- [:self] (fn [x _] x)))

;; ===========================================================================
;; Reactive `subscribe` path
;; ===========================================================================

(deftest reactive-two-node-cycle-emits-structured-error-and-recovers-to-nil
  (testing "subscribing a two-node `:<-` cycle (:a<->:b) emits a structured
            :rf.error/sub-cycle carrying the cycle path and recovers to a
            nil-yielding reaction — NOT a raw StackOverflowError (rf2-x76af2.24)"
    (register-cyclic-subs!)
    (let [[reaction events] (capture-sub-cycles #(subs/subscribe [:a] {:frame fid}))]
      (is (nil? (deref reaction))
          "the cyclic subscription recovered to a nil-yielding reaction")
      ;; rf2-d2841 - the structured REPORT is diagnostic-channel only.
      (when interop/debug-enabled?
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
            with a [:self :self] path and recovers to nil (rf2-x76af2.24)"
    (register-cyclic-subs!)
    (let [[reaction events] (capture-sub-cycles #(subs/subscribe [:self] {:frame fid}))]
      (is (nil? (deref reaction))
          "the self-cyclic subscription recovered to a nil-yielding reaction")
      ;; rf2-d2841 - diagnostic-channel report.
      (when interop/debug-enabled?
        (is (= 1 (count events)))
        (is (= [:self :self] (:cycle (:tags (first events))))
            "a self-cycle repeats the one id, e.g. [:self :self]")))))

(deftest reactive-cycle-recovery-is-not-cached
  (testing "the cyclic build is NOT cached (mirroring the no-such-sub miss), so
            a second subscribe re-detects + re-emits rather than silently
            handing back a broken cached reaction (rf2-x76af2.24)"
    (register-cyclic-subs!)
    (let [cache      (:sub-cache (frame/frame fid))
          [_ events] (capture-sub-cycles
                       (fn []
                         (subs/subscribe [:a] {:frame fid})
                         (subs/subscribe [:a] {:frame fid})))]
      ;; ALWAYS-ON (rf2-d2841): "not cached" is a statement about the sub-cache,
      ;; and the cache is production state. This IS the claim; the re-emission
      ;; below is only how it used to be inferred, on a channel a production
      ;; build does not have.
      (is (nil? (get @cache [:a]))
          "the cyclic build left no cache entry - a later subscribe re-detects
           rather than being handed a broken cached reaction")
      (is (nil? (get @cache [:b]))
          "nor did the other node of the cycle get cached mid-build")
      (when interop/debug-enabled?
        (is (= 2 (count events))
            "each subscribe of the uncached cyclic sub re-emits the structured error")))))

(deftest reactive-multi-input-cycle-releases-earlier-input-refs
  (testing "a >=2-input sub whose NON-FIRST input cycles releases the
            already-acquired earlier input ref on the cycle-unwind path — the
            earlier input's cache ref-count returns to baseline after recovery,
            with no bounded leak (rf2-t3cpn3). Before the fix the first input
            (:leaf) was subscribed (ref bumped) then the abandoned :multi build
            unwound to the outermost recovery WITHOUT ever wiring its on-dispose,
            so nothing released :leaf — a monotonic +1 leak."
    ;; :leaf — a layer-1 (`:db`) sub: cacheable + ref-countable, no `:<-` inputs.
    (rf/reg-sub :leaf (fn [db _] (:leaf db 7)))
    ;; :cyc — closes the cycle back to :multi.
    (rf/reg-sub :cyc :<- [:multi] (fn [m _] m))
    ;; :multi — TWO inputs; the FIRST (:leaf) resolves cleanly, the SECOND
    ;; (:cyc) cycles back to :multi. This is the multi-input edge .24 left.
    (rf/reg-sub :multi :<- [:leaf] :<- [:cyc] (fn [[l c] _] [l c]))
    (let [cache (:sub-cache (frame/frame fid))]
      ;; Baseline: :leaf is not yet in the cache.
      (is (nil? (get @cache [:leaf]))
          "precondition: :leaf has no cache entry before the cyclic subscribe")
      (let [[reaction events] (capture-sub-cycles #(subs/subscribe [:multi] {:frame fid}))]
        (is (nil? (deref reaction))
            "the cyclic multi-input subscription recovered to a nil-yielding reaction")
        ;; rf2-d2841 - diagnostic-channel report; the TEETH below are always-on.
        (when interop/debug-enabled?
          (is (= 1 (count events))
              "exactly one structured :rf.error/sub-cycle was emitted")
          (is (= [:multi :cyc :multi] (:cycle (:tags (first events))))
              "the cycle path is the closing-repeat sub-id chain through the non-first input"))
        ;; TEETH (rf2-t3cpn3): :leaf was subscribed (ref bumped) BEFORE :cyc
        ;; cycled. On the cycle-unwind path it must be released so its ref-count
        ;; returns to baseline (entry dropped on the 1→0 transition). Before the
        ;; fix this asserts the leak: the entry lingers with :ref-count 1.
        (is (or (nil? (get @cache [:leaf]))
                (zero? (or (get-in @cache [[:leaf] :ref-count]) 0)))
            "the earlier input :leaf's ref-count returned to baseline (released on the cycle unwind) — no bounded leak")))))

;; ===========================================================================
;; Pure `compute-sub` path (per-call memo doubles as the under-construction set)
;; ===========================================================================

(deftest compute-sub-two-node-cycle-emits-structured-error-and-returns-nil
  (testing "compute-sub of a two-node `:<-` cycle emits :rf.error/sub-cycle and
            returns nil — NOT a raw StackOverflowError (rf2-x76af2.24)"
    (register-cyclic-subs!)
    (let [[v events] (capture-sub-cycles #(rf/compute-sub [:a] {}))]
      (is (nil? v) "compute-sub recovered the cyclic sub to nil")
      ;; rf2-d2841 - diagnostic-channel report.
      (when interop/debug-enabled?
        (is (= 1 (count events)))
        (let [ev (first events)]
          (is (= :compute-sub (:where (:tags ev))))
          (is (= [:a :b :a] (:cycle (:tags ev)))))))))

(deftest compute-sub-self-cycle-emits-structured-error-and-returns-nil
  (testing "compute-sub of a self-edge emits :rf.error/sub-cycle and returns nil
            (rf2-x76af2.24)"
    (register-cyclic-subs!)
    (let [[v events] (capture-sub-cycles #(rf/compute-sub [:self] {}))]
      (is (nil? v))
      ;; rf2-d2841 - diagnostic-channel report.
      (when interop/debug-enabled?
        (is (= 1 (count events)))
        (is (= [:self :self] (:cycle (:tags (first events)))))))))

;; ===========================================================================
;; Sanity baseline — a NON-cyclic diamond does not trip the guard
;; ===========================================================================

(deftest acyclic-diamond-does-not-trip-the-cycle-guard
  (testing "a legitimate diamond (:c depends on :a and :b; both depend on :root)
            builds cleanly and emits NO :rf.error/sub-cycle — the guard fires on
            genuine cycles only (rf2-x76af2.24)"
    (rf/reg-sub :root (fn [db _] (:root db 41)))
    (rf/reg-sub :a :<- [:root] (fn [r _] (inc r)))
    (rf/reg-sub :b :<- [:root] (fn [r _] (dec r)))
    (rf/reg-sub :c :<- [:a] :<- [:b] (fn [[a b] _] [a b]))
    (let [[reaction events] (capture-sub-cycles #(subs/subscribe [:c] {:frame fid}))]
      (is (= [42 40] (deref reaction)) "the diamond computed correctly")
      ;; rf2-d2841 - VACUOUS UNDER THE GATE. `events` is empty for EVERY graph
      ;; when the diagnostic channel emits nothing, so outside the arm this row
      ;; would certify "no spurious cycle error" having never been able to see
      ;; one. Kept verbatim beside the positive that gives it teeth.
      (when interop/debug-enabled?
        (is (empty? events) "no spurious :rf.error/sub-cycle for an acyclic graph")))
    (let [[v events] (capture-sub-cycles #(rf/compute-sub [:c] {:root 41}))]
      (is (= [42 40] v) "compute-sub diamond computed correctly")
      (when interop/debug-enabled?
        (is (empty? events) "no spurious cycle from the compute-sub memo path")))))
