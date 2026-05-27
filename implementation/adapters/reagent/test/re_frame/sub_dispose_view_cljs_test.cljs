(ns re-frame.sub-dispose-view-cljs-test
  "Per rf2-e9g4g: substrate-level coverage for the `:rf.sub/dispose`
  trace event (landed via rf2-mrnur, PR #2204) — exercises the
  Reagent-side derefer-count round-trip that the JVM-side cache tests
  (`re-frame.sub-dispose-trace-test`) cannot reach.

  Why this exists. rf2-mrnur shipped JVM-side cache-eviction tests
  pinning the emit shape and reason enum at the cache module's seam —
  strong coverage of the framework contract but blind to the
  substrate's integration. The bead's acceptance items #4 + #5 call
  for substrate-level pins:

    #4 view-unmount → :rf.sub/dispose fires with :reason
       :no-more-derefers when the last derefer drops.
    #5 conditional-deref flip → :rf.sub/dispose fires when the
       condition stops the deref (the runtime ref-count drops to 0
       even though the component stays mounted).

  Plus a multi-derefer negative control — two derefers, one drops →
  NO emit; only when the LAST drops does the slot evict.

  Mechanism. The substrate-level integration that drives eviction is
  the per-subscribe ref-count machinery in `re-frame.subs.cache`: every
  `(rf/subscribe ...)` increments ref-count; the matched
  `(rf/unsubscribe ...)` decrements; the 1 → 0 transition fires
  eviction synchronously (per rf2-cmfln, Spec 006 §Reference counting
  and disposal). On the Reagent substrate, a view's mount→render→deref
  path subscribes (one derefer arriving) and the unmount path
  unsubscribes (the derefer dropping). These tests stand in for the
  view by issuing the subscribe/unsubscribe pairs directly with the
  Reagent adapter installed — the cache machinery under test is
  substrate-shared, but the reactive primitives + cache invariants
  are Reagent's. Driving the assertions through bare
  subscribe/unsubscribe keeps the tests headless (no JSDOM /
  Playwright) and matches the test-surface convention used by the
  rf2-9hoos view-side-capture suite (`install-unmount-hook!` +
  manual reaction dispose).

  Layer-2 setup. The `add-on-dispose!` callback installed in
  `re-frame.subs/compute-and-cache!` releases input refs symmetrically
  on the cached reaction's disposal, so a layer-2 sub's input subs
  evict through the `unsubscribe!` seam — that emit IS observable
  here. The layer-2 shape mirrors the JVM cascade test
  (`dispose-cascade-emits-per-evicted-layer`) for one-to-one
  semantic parity across substrates.

  Production elision. The emit sits inside `interop/debug-enabled?`
  in `re-frame.subs.cache/emit-dispose!`, so the production CLJS
  bundle DCEs it (pinned by `npm run test:elision` per Spec 009
  §Production builds). This test runs under the dev-build
  `:node-test` target where the emit fires.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it
  up; no DOM required (the contract under test is the substrate's
  ref-count machinery, not a React render)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- collect-dispose-traces!
  "Attach a listener that captures every `:rf.sub/dispose` trace into
  an atom. Returns the atom; caller is responsible for unregistering."
  [k]
  (let [recorded (atom [])]
    (trace-tooling/register-listener! k
      (fn [ev]
        (when (= :rf.sub/dispose (:operation ev))
          (swap! recorded conj ev))))
    recorded))

(defn- dispose-by-id
  "Filter a collected trace-event vector down to the dispose events for
  one `sub-id`."
  [traces sub-id]
  (filterv #(= sub-id (-> % :tags :rf.sub/id)) traces))

;; ===========================================================================
;; Acceptance #4 — view-unmount path
;; ===========================================================================
;;
;; The view's "subscribe on mount, unsubscribe on unmount" pair, stood
;; up here directly through `rf/subscribe` + `rf/unsubscribe`. The
;; layer-2 setup observes the cascading input evictions through the
;; `unsubscribe!` seam (the parent's `add-on-dispose!` callback
;; releases input refs symmetrically).

(deftest view-unmount-emits-rf-sub-dispose-on-input-cascade
  (testing "rf2-e9g4g #4: a view-shaped subscribe/unsubscribe pair on
   a layer-2 sub fires :rf.sub/dispose with :reason :no-more-derefers
   for every evicted slot — parent + every input — exactly matching
   the JVM cascade test's emit count + payload shape, but with the
   Reagent adapter installed so the path under test is the substrate
   integration end-to-end"
    (rf/reg-event-db :rf2-e9g4g/init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :rf2-e9g4g.view/a (fn [db _] (:a db)))
    (rf/reg-sub :rf2-e9g4g.view/b (fn [db _] (:b db)))
    (rf/reg-sub :rf2-e9g4g.view/sum
      :<- [:rf2-e9g4g.view/a]
      :<- [:rf2-e9g4g.view/b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:rf2-e9g4g/init])

    (let [traces (collect-dispose-traces! ::view-unmount)]
      (try
        ;; Mount: a single "view" subscribes + derefs the sum sub.
        (let [r (rf/subscribe [:rf2-e9g4g.view/sum])]
          (is (= 5 @r) "precondition: sub computes against the seeded app-db")
          (is (empty? @traces)
              "precondition: no :rf.sub/dispose has fired yet — the slot is held"))

        ;; Unmount: the last derefer drops. Per rf2-cmfln the eviction
        ;; lands synchronously and the trace lands before this returns.
        (rf/unsubscribe [:rf2-e9g4g.view/sum])

        ;; The cascade chain — sum's `add-on-dispose!` callback releases
        ;; its input refs through `unsubscribe`, each input drops 1→0
        ;; and evicts through `unsubscribe!`. Both inputs emit.
        (let [a-evs (dispose-by-id @traces :rf2-e9g4g.view/a)
              b-evs (dispose-by-id @traces :rf2-e9g4g.view/b)]
          (is (= 1 (count a-evs))
              "input sub :a fired exactly one :rf.sub/dispose on the cascade")
          (is (= 1 (count b-evs))
              "input sub :b fired exactly one :rf.sub/dispose on the cascade")
          (doseq [ev (concat a-evs b-evs)]
            (let [t (:tags ev)]
              (is (= :rf.sub (:op-type ev))
                  ":op-type rides the :rf.sub family")
              (is (= :no-more-derefers (:rf.sub/reason t))
                  ":reason is :no-more-derefers — the ref-count-drop path")
              (is (= :rf/default (:frame t))
                  ":frame is canonical (the Reagent adapter's default frame)")
              (is (vector? (:rf.sub/query-v t))
                  ":rf.sub/query-v is a vector"))))
        (finally
          (trace-tooling/unregister-listener! ::view-unmount))))))

;; ===========================================================================
;; Acceptance #5 — conditional-deref path
;; ===========================================================================
;;
;; A view conditionally derefs a sub: `(when @cond? @sub)`. While
;; `cond?` is true the sub is held; flipping `cond?` to false stops
;; the deref — the runtime derefer drops even though the surrounding
;; component stays mounted. The substrate seam under test is the
;; same `unsubscribe!` ref-count machinery the view-unmount path
;; uses — what changes is the call site (conditional unsub from
;; within a re-render rather than unmount cleanup).

(deftest conditional-deref-flip-emits-rf-sub-dispose
  (testing "rf2-e9g4g #5: when a view conditionally derefs a sub and
   the condition flips false, the runtime derefer drops and the slot
   evicts with :reason :no-more-derefers. The component-stays-mounted
   shape is the rf2-mrnur audit's explicit gap from the JVM-only
   cache tests"
    (rf/reg-event-db :rf2-e9g4g/init (fn [_ _] {:n 7 :a 1 :b 2}))
    (rf/reg-sub :rf2-e9g4g.cond/n (fn [db _] (:n db)))
    (rf/reg-sub :rf2-e9g4g.cond/a (fn [db _] (:a db)))
    (rf/reg-sub :rf2-e9g4g.cond/b (fn [db _] (:b db)))
    (rf/reg-sub :rf2-e9g4g.cond/sum
      :<- [:rf2-e9g4g.cond/a]
      :<- [:rf2-e9g4g.cond/b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:rf2-e9g4g/init])

    (let [traces (collect-dispose-traces! ::cond-flip)]
      (try
        ;; Initial mount: condition is true. The "view" derefs both
        ;; `:rf2-e9g4g.cond/n` and `:rf2-e9g4g.cond/sum` (a layer-2 with
        ;; two inputs).
        (let [n-rea   (rf/subscribe [:rf2-e9g4g.cond/n])
              sum-rea (rf/subscribe [:rf2-e9g4g.cond/sum])]
          (is (= 7 @n-rea))
          (is (= 3 @sum-rea))
          (is (empty? @traces)
              "precondition: nothing evicted while both derefers are held"))

        ;; Condition flips false. The "view" re-renders WITHOUT
        ;; dereffing `:rf2-e9g4g.cond/sum` any more — the surrounding
        ;; component stays mounted, but the conditional sub is now
        ;; an orphan from the view's perspective. The substrate's
        ;; unsub call captures the conditional-deref-drop signal.
        (rf/unsubscribe [:rf2-e9g4g.cond/sum])

        ;; The orphaned layer-2 sub's input refs are released by the
        ;; cascade. The unconditional `:rf2-e9g4g.cond/n` deref is
        ;; still held; its sub MUST NOT evict.
        (let [a-evs (dispose-by-id @traces :rf2-e9g4g.cond/a)
              b-evs (dispose-by-id @traces :rf2-e9g4g.cond/b)
              n-evs (dispose-by-id @traces :rf2-e9g4g.cond/n)]
          (is (= 1 (count a-evs))
              "input :a evicted via the cascade — the conditional sub's
               input refs were released")
          (is (= 1 (count b-evs))
              "input :b evicted via the cascade")
          (is (empty? n-evs)
              "the unconditionally-held :n sub stays cached — the conditional
               flip did NOT touch unrelated derefers")
          (doseq [ev (concat a-evs b-evs)]
            (let [t (:tags ev)]
              (is (= :no-more-derefers (:rf.sub/reason t)))
              (is (= :rf/default (:frame t))))))
        (finally
          (trace-tooling/unregister-listener! ::cond-flip))))))

;; ===========================================================================
;; Multi-derefer negative control
;; ===========================================================================
;;
;; Two derefers (think: two mounted views) hold the same sub. The
;; first derefer dropping must NOT fire :rf.sub/dispose — the slot
;; is still held. Only when the LAST derefer drops does the slot
;; evict. The ref-count machinery is the seam, and this assertion
;; pins it from the substrate's perspective.

(deftest multi-derefer-emits-only-on-last-drop
  (testing "rf2-e9g4g multi-derefer negative control: with two
   derefers (two view subscribes), one unsubscribe does NOT fire
   :rf.sub/dispose — the slot's ref-count drops 2→1 but stays > 0.
   Only the second unsubscribe (the last derefer dropping 1→0)
   emits"
    (rf/reg-event-db :rf2-e9g4g/init (fn [_ _] {:v 42}))
    (rf/reg-sub :rf2-e9g4g.multi/v (fn [db _] (:v db)))
    (rf/reg-sub :rf2-e9g4g.multi/doubled
      :<- [:rf2-e9g4g.multi/v]
      (fn [v _] (* 2 v)))
    (rf/dispatch-sync [:rf2-e9g4g/init])

    (let [traces (collect-dispose-traces! ::multi-derefer)]
      (try
        ;; Two "views" both subscribe to the layer-2 sub. The first
        ;; subscribe creates the slot at ref-count=1 AND subscribes
        ;; the input — input ref-count=1. The second subscribe bumps
        ;; the slot to 2 (cache-hit path) and does NOT re-subscribe
        ;; the input. So we end at parent ref-count=2 / input
        ;; ref-count=1.
        (let [r1 (rf/subscribe [:rf2-e9g4g.multi/doubled])
              r2 (rf/subscribe [:rf2-e9g4g.multi/doubled])]
          (is (= 84 @r1))
          (is (= 84 @r2))
          (is (identical? r1 r2)
              "precondition: two subscribes return the SAME reaction (cache reuse)"))

        ;; First view unmounts → first unsubscribe. Parent ref-count
        ;; drops 2→1 — NOT to zero. No emit fires.
        (rf/unsubscribe [:rf2-e9g4g.multi/doubled])
        (is (empty? @traces)
            "first derefer drop: slot's ref-count is still 1; NO :rf.sub/dispose
             — pins the multi-derefer invariant")

        ;; Second view unmounts → second unsubscribe. Parent drops
        ;; 1→0, evicts, cascades to input, input drops 1→0, evicts.
        ;; Two emits fire (parent + input).
        (rf/unsubscribe [:rf2-e9g4g.multi/doubled])
        (let [v-evs (dispose-by-id @traces :rf2-e9g4g.multi/v)
              d-evs (dispose-by-id @traces :rf2-e9g4g.multi/doubled)]
          (is (= 1 (count d-evs))
              "parent :doubled fired :rf.sub/dispose when its LAST derefer dropped")
          (is (= 1 (count v-evs))
              "input :v fired :rf.sub/dispose via the cascade after the parent
               released its input ref")
          (is (every? #(= :no-more-derefers (-> % :tags :rf.sub/reason))
                      (concat v-evs d-evs))
              "every emit carries :reason :no-more-derefers (ref-count-drop path)"))
        (finally
          (trace-tooling/unregister-listener! ::multi-derefer))))))
