(ns re-frame.sub-dispose-view-cljs-test
  "Substrate-level coverage for the `:rf.sub/dispose` trace event —
  exercises the Reagent-side derefer-count round-trip that the JVM-side
  cache tests (`re-frame.sub-dispose-trace-test`) cannot reach.

  Why this exists. The JVM-side cache-eviction tests pin the emit shape
  and reason enum at the cache module's seam — strong coverage of the
  framework contract but blind to the substrate's integration. Two
  substrate-level pins:

    #4 view-unmount → :rf.sub/dispose fires with :reason
       :no-more-derefers when the last derefer drops.
    #5 conditional teardown → the `rf/unsubscribe` a conditionally
       rendered child's cleanup fires evicts that sub and its inputs
       while a sub the surviving render still holds stays cached.
       Pinned by `conditional-teardown-unsubscribe-evicts-only-its-own-slots`.
       Reagent's auto-track leg — a render reaction that stops
       derefing the sub when a condition flips — is not exercised
       here.

  Plus a multi-derefer negative control — two derefers, one drops →
  NO emit; only when the LAST drops does the slot evict.

  Mechanism. The substrate-level integration that drives eviction is
  the per-subscribe ref-count machinery in `re-frame.subs.cache`: every
  `(rf/subscribe ...)` increments ref-count; the matched
  `(rf/unsubscribe ...)` decrements; the 1 → 0 transition fires
  eviction synchronously (per Spec 006 §Reference counting
  and disposal). On the Reagent substrate, a view's mount→render→deref
  path subscribes (one derefer arriving) and the unmount path
  unsubscribes (the derefer dropping). These tests stand in for the
  view by issuing the subscribe/unsubscribe pairs directly with the
  Reagent adapter installed — the cache machinery under test is
  substrate-shared, but the reactive primitives + cache invariants
  are Reagent's. Driving the assertions through bare
  subscribe/unsubscribe keeps the tests headless (no JSDOM /
  Playwright) and matches the test-surface convention used by the
  view-side-capture suite (`install-unmount-hook!` +
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
            [re-frame.interop :as rf.interop]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- helpers ---------------------------------------------------------------

(def ^:private sub-dispose-pred
  "Predicate matching the `:rf.sub/dispose` trace operation."
  #(= :rf.sub/dispose (:operation %)))

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
  (testing "#4: a view-shaped subscribe/unsubscribe pair on
   a layer-2 sub fires :rf.sub/dispose with :reason :no-more-derefers
   for every evicted slot — parent + every input — exactly matching
   the JVM cascade test's emit count + payload shape, but with the
   Reagent adapter installed so the path under test is the substrate
   integration end-to-end"
    (rf/reg-event :rf2-e9g4g/init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :rf2-e9g4g.view/a (fn [db _] (:a db)))
    (rf/reg-sub :rf2-e9g4g.view/b (fn [db _] (:b db)))
    (rf/reg-sub :rf2-e9g4g.view/sum
      {:inputs [[:rf2-e9g4g.view/a] [:rf2-e9g4g.view/b]]}
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:rf2-e9g4g/init])

    (with-trace-recorder! [traces {:pred sub-dispose-pred}]
      ;; Mount: a single "view" subscribes + derefs the sum sub.
      (let [r (rf/subscribe [:rf2-e9g4g.view/sum])]
        (is (= 5 @r) "precondition: sub computes against the seeded app-db")
        (is (empty? @traces)
            "precondition: no :rf.sub/dispose has fired yet — the slot is held"))

      ;; Unmount: the last derefer drops. The eviction
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
                ":rf.sub/query-v is a vector")))))))

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
  (testing "multi-derefer negative control: with two
   derefers (two view subscribes), one unsubscribe does NOT fire
   :rf.sub/dispose — the slot's ref-count drops 2→1 but stays > 0.
   Only the second unsubscribe (the last derefer dropping 1→0)
   emits"
    (rf/reg-event :rf2-e9g4g/init (fn [{:keys [db]} _] {:db {:v 42}}))
    (rf/reg-sub :rf2-e9g4g.multi/v (fn [db _] (:v db)))
    (rf/reg-sub :rf2-e9g4g.multi/doubled
      {:inputs [[:rf2-e9g4g.multi/v]]}
      (fn [[v] _] (* 2 v)))
    (rf/dispatch-sync [:rf2-e9g4g/init])

    (with-trace-recorder! [traces {:pred sub-dispose-pred}]
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
            "every emit carries :reason :no-more-derefers (ref-count-drop path)")))))

;; ===========================================================================
;; Reagent reaction-dispose + conditional-deref re-execution
;; ===========================================================================
;;
;; The two deftests above subscribe + unsubscribe directly. That pins the
;; cache module's seam (subscribe/unsubscribe ref-count machinery) but
;; misses the Reagent-side reactive-graph leg of the production path: a
;; surrounding reaction derefs the sub's reaction, then *the surrounding
;; reaction* loses its last watcher and disposes — which calls `subscribe`-
;; side teardown via the `add-on-dispose!` callback installed in
;; `compute-and-cache!`. Test #1 below closes that gap; test #2 does not.
;;
;; Test #1 — reaction-disposal path. Drives the production teardown sequence
;; by disposing the surrounding reaction (NOT by `rf/unsubscribe`). Mirrors
;; the `install-unmount-hook!` pattern (view-side-capture
;; test:184): a per-instance reaction that derefs the sub, then
;; `interop/dispose!` simulates the React unmount signal at the reagent-
;; reaction layer.
;;
;; Test #2 — conditional teardown. Two subs are held; the explicit
;; `rf/unsubscribe` a conditionally-rendered child's cleanup fires releases
;; one of them. It pins that the eviction cascades to that sub's inputs and
;; leaves the still-held sub cached. It does NOT exercise Reagent's
;; auto-track: nothing here is a reactive render that stops derefing the sub.

(deftest reaction-disposal-fires-rf-sub-dispose
  (testing "#1: disposing the cached Reagent reaction directly
   via `interop/dispose!` (the substrate-side reactive-graph reap
   pathway — what Reagent does when a render reaction loses its last
   watcher on componentWillUnmount) drives the production teardown
   sequence: `compute-and-cache!`'s `add-on-dispose!` callback fires,
   input refs release, input slots evict + emit `:rf.sub/dispose`."
    (rf/reg-event :rf2-b2bxk/init (fn [{:keys [db]} _] {:db {:a 11 :b 13}}))
    (rf/reg-sub :rf2-b2bxk.rea/a (fn [db _] (:a db)))
    (rf/reg-sub :rf2-b2bxk.rea/b (fn [db _] (:b db)))
    (rf/reg-sub :rf2-b2bxk.rea/sum
      {:inputs [[:rf2-b2bxk.rea/a] [:rf2-b2bxk.rea/b]]}
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:rf2-b2bxk/init])

    (with-trace-recorder! [traces {:pred sub-dispose-pred}]
      ;; "View" mounts: rf/subscribe returns the cached Reagent
      ;; reaction. The cache layer registered an `add-on-dispose!`
      ;; on this reaction in `compute-and-cache!` — the same callback
      ;; Reagent's reactive-graph reaping fires when a render
      ;; reaction holding the sub loses its last watcher.
      (let [sum-rea (rf/subscribe [:rf2-b2bxk.rea/sum])]
        (is (= 24 @sum-rea)
            "precondition: sub computes against the seeded app-db")
        (is (empty? @traces)
            "precondition: nothing evicted while the reaction is held")

        ;; The reactive-graph teardown signal: dispose the reaction
        ;; directly. This stands in for Reagent's reap of an unwatched
        ;; reaction (componentWillUnmount → render reaction disposed
        ;; → loses watcher on sum-rea → Reagent reaps sum-rea →
        ;; add-on-dispose! callbacks fire). Mirrors the
        ;; install-unmount-hook! test pattern, which similarly
        ;; disposes the reaction directly to drive the on-dispose
        ;; callback chain headlessly.
        (rf.interop/dispose! sum-rea)

        ;; The cache's `add-on-dispose!` cascade ran: each input was
        ;; `unsubscribe`d, dropping their ref-counts to 0, evicting
        ;; their slots, and emitting `:rf.sub/dispose`.
        ;;
        ;; THE PARENT EMITS HERE TOO. This is the substrate-side reap that
        ;; a real componentWillUnmount takes, so it is exactly the path on
        ;; which Spec 006 §Reference counting and disposal promises the
        ;; emit at the eviction site; a silent parent here would violate
        ;; that contract. The callback emits when IT is
        ;; the call that removed the slot, which is the case here and is
        ;; not the case on any cache-driven eviction (those remove the
        ;; slot before disposing the reaction, so the callback finds
        ;; nothing to remove and stays quiet — which is what keeps this
        ;; from double-emitting).
        (let [a-evs   (dispose-by-id @traces :rf2-b2bxk.rea/a)
              b-evs   (dispose-by-id @traces :rf2-b2bxk.rea/b)
              sum-evs (dispose-by-id @traces :rf2-b2bxk.rea/sum)]
          (is (= 1 (count a-evs))
              "input :a evicted via the reaction-dispose cascade")
          (is (= 1 (count b-evs))
              "input :b evicted via the reaction-dispose cascade")
          (is (= 1 (count sum-evs))
              (str "the PARENT's own slot emits exactly one "
                   ":rf.sub/dispose on the substrate-side reap — one, not "
                   "zero (the pre-rf2-ty246 silence) and not two (a double "
                   "emit); got " (count sum-evs)))
          (is (= :no-more-derefers (-> sum-evs first :tags :rf.sub/reason))
              "the parent's emit carries :rf.sub/reason :no-more-derefers")
          (doseq [ev (concat a-evs b-evs)]
            (let [t (:tags ev)]
              (is (= :no-more-derefers (:rf.sub/reason t))
                  ":reason is :no-more-derefers — the ref-count-drop path")
              (is (= :rf/default (:frame t))
                  ":frame is canonical (the Reagent adapter's default frame)"))))))))

(deftest conditional-teardown-unsubscribe-evicts-only-its-own-slots
  (testing "#2: the conditional teardown — `rf/unsubscribe` fired by the
   production cleanup path (r/with-let :finally, a componentWillUnmount
   hook, an effect cleanup) — evicts the conditional sub's slot and
   cascades to its inputs, while a sub the surviving render still holds
   stays cached.

   Cache ref-counting is explicit subscribe/unsubscribe. This test does
   not drive Reagent's auto-track: no reactive render stops derefing the
   sub, so what it pins is the explicit-unsubscribe half of the
   conditional-render lifecycle."
    (rf/reg-event :rf2-b2bxk/init (fn [{:keys [db]} _] {:db {:n 5 :a 3 :b 4}}))
    (rf/reg-sub :rf2-b2bxk.cond-rea/n (fn [db _] (:n db)))
    (rf/reg-sub :rf2-b2bxk.cond-rea/a (fn [db _] (:a db)))
    (rf/reg-sub :rf2-b2bxk.cond-rea/b (fn [db _] (:b db)))
    (rf/reg-sub :rf2-b2bxk.cond-rea/sum
      {:inputs [[:rf2-b2bxk.cond-rea/a] [:rf2-b2bxk.cond-rea/b]]}
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:rf2-b2bxk/init])

    ;; The surviving render holds `n`; the conditionally-rendered child
    ;; holds `sum`.
    (let [n-rea   (rf/subscribe [:rf2-b2bxk.cond-rea/n])
          sum-rea (rf/subscribe [:rf2-b2bxk.cond-rea/sum])]
      (with-trace-recorder! [traces {:pred sub-dispose-pred}]
        (try
          (is (= 5 @n-rea) "precondition: the held sub reads the seeded app-db")
          (is (= 7 @sum-rea) "precondition: the conditional sub reads the seeded app-db")
          (is (empty? @traces)
              "precondition: nothing evicted while both subs are held")

          ;; Production cleanup fires on the conditionally-rendered child.
          (rf/unsubscribe [:rf2-b2bxk.cond-rea/sum])

          (let [a-evs   (dispose-by-id @traces :rf2-b2bxk.cond-rea/a)
                b-evs   (dispose-by-id @traces :rf2-b2bxk.cond-rea/b)
                n-evs   (dispose-by-id @traces :rf2-b2bxk.cond-rea/n)
                sum-evs (dispose-by-id @traces :rf2-b2bxk.cond-rea/sum)]
            (is (= 1 (count sum-evs))
                "parent :sum evicted on the conditional-teardown unsubscribe")
            (is (= 1 (count a-evs))
                "input :a evicted via the conditional-teardown cascade")
            (is (= 1 (count b-evs))
                "input :b evicted via the conditional-teardown cascade")
            (is (empty? n-evs)
                "the still-held :n sub stays cached — the conditional
                 teardown did NOT touch unrelated derefers")
            (doseq [ev (concat a-evs b-evs sum-evs)]
              (let [t (:tags ev)]
                (is (= :no-more-derefers (:rf.sub/reason t))
                    ":reason is :no-more-derefers — the ref-count-drop path")
                (is (= :rf/default (:frame t))
                    ":frame is canonical"))))

          (finally
            (rf/unsubscribe [:rf2-b2bxk.cond-rea/n])))))))
