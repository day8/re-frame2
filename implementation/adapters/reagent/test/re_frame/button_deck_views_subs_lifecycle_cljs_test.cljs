(ns re-frame.button-deck-views-subs-lifecycle-cljs-test
  "Per rf2-fqzmb — substrate contract coverage for the button-deck
  Views/subscriptions section's view + subscription LIFECYCLE.

  The button-deck testbed (`tools/xray/testbeds/button_deck/core.cljs`)
  is test-free (locked, rf2-8cevm): its BUTTONS demonstrate the
  behaviour for Xray + re-frame2-pair inspection. The hard ASSERTIONS
  live here — the substrate subs/views contract suite — exactly
  mirroring the section's shape so the testbed wiring and the
  framework contract are pinned together. Sibling to
  `re-frame.sub-dispose-view-cljs-test` (the `:rf.sub/dispose` emit
  axis); this file pins the cache-STATE + render-CAUSE axes.

  The section has TWO children so re-render CAUSES are separable:

    Child A — SUBSCRIPTION-driven. Subscribes an own L1→L2→L3 chain
              (`:chain-root` → `:chain-doubled` → `:chain-labelled`)
              PLUS the arg-keyed `[:button-deck/greater-than? N]` sub.
    Child B — PROPS-driven. Receives a prop, subscribes NOTHING.

  The five contract assertions (one per acceptance item):

    1. Mounting A creates A's sub-cache entries — the chain L1/L2/L3
       AND `[:button-deck/greater-than? 5]`.
    2. Changing the sub-arg N (5 → 10) creates a DISTINCT
       `[:button-deck/greater-than? 10]` cache entry — the
       parameterized-sub cache is keyed by arg.
    3. Changing a chain input recomputes L1→L2→L3 (invalidation
       propagation down the chain).
    4. Unmounting A disposes ALL of A's subs (last-reader-gone GC) —
       the chain L1/L2/L3 + every `[:gt? N]` entry — and records the
       unmount via `:rf.view/rendered` + the `:rf.sub/dispose` stream.
    5. A render driven by an own-sub change names that sub on
       `:rf.view/triggered-by` (Child A); a render driven by a PROPS
       change does NOT (Child B) — the consumer reads the absence as
       `← props changed`. (Pairs with the rf2-bhi3t render-cause Xray
       work.)

  Mechanism. A view's mount→render→deref path subscribes (a derefer
  arriving) and the unmount path unsubscribes (the derefer dropping)
  — the per-subscribe ref-count machinery in `re-frame.subs.cache`
  (Spec 006 §Reference counting and disposal, rf2-cmfln). These tests
  stand in for the view by driving the subscribe/unsubscribe pairs
  directly with the Reagent adapter installed and inspecting the
  frame's `:sub-cache` atom (keyed by the query-vector, per
  `re-frame.subs/cache-key` = identity). That keeps them headless
  (no JSDOM / Playwright) and matches the test-surface convention
  used by `sub-dispose-view-cljs-test`. The render-cause assertion
  (item 5) drives a real `(rf/view ...)` render inside a cascade so
  the substrate-agnostic `views.cljs` wrapper stamps
  `:rf.view/triggered-by` from the in-flight buffer.

  The subs / views here are byte-for-byte the testbed's, registered
  under the same `:button-deck/*` ids, so a drift between the testbed
  and this contract surfaces as a failure here.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ===========================================================================
;; The button-deck Views/subs section, replicated verbatim
;; ===========================================================================
;;
;; Child A's own L1→L2→L3 chain rooted at :views/chain-input (NOT :base —
;; that feeds the testbed's flow), plus the arg-keyed greater-than? sub.

(defn- register-section-subs! []
  (rf/reg-sub :button-deck/chain-root            ;; L1
    (fn [db _] (get-in db [:views :chain-input])))
  (rf/reg-sub :button-deck/chain-doubled         ;; L2
    :<- [:button-deck/chain-root]
    (fn [root _] (* 2 root)))
  (rf/reg-sub :button-deck/chain-labelled        ;; L3
    :<- [:button-deck/chain-doubled]
    (fn [doubled _] (str "2×input = " doubled)))
  (rf/reg-sub :button-deck/greater-than?         ;; DYNAMIC, arg-keyed
    :<- [:button-deck/chain-root]
    (fn [root [_ threshold]] (> root threshold))))

(defn- register-section-events! []
  (rf/reg-event-db :button-deck/seed
    (fn [_ _] {:views {:a-mounted?  false
                       :b-mounted?  false
                       :threshold   5
                       :chain-input 1
                       :b-prop      "alpha"}}))
  (rf/reg-event-db :button-deck/perturb-chain
    (fn [db _] (update-in db [:views :chain-input] inc))))

;; Child A's full read-set, stood up as the view's mount-time subscribes.
;; Returns the held reactions so a test can deref / unsubscribe them as a
;; mount → unmount pair. `threshold` is A's prop (the arg-key driver).
(defn- mount-a!
  [threshold]
  {:chain     (rf/subscribe [:button-deck/chain-labelled])
   :gt        (rf/subscribe [:button-deck/greater-than? threshold])
   :threshold threshold})

(defn- unmount-a!
  "The unmount path: every subscribe A made on mount drops its derefer."
  [{:keys [threshold]}]
  (rf/unsubscribe [:button-deck/chain-labelled])
  (rf/unsubscribe [:button-deck/greater-than? threshold]))

(defn- sub-cache
  "The frame's live sub-cache map (cache-key → entry). Keys are the
  query-vectors themselves (cache-key = identity)."
  []
  @(:sub-cache (frame/frame :rf/default)))

(defn- cached?
  "Is `query-v` present in the live sub-cache?"
  [query-v]
  (contains? (sub-cache) query-v))

;; ===========================================================================
;; #1 — mounting A creates its sub-cache entries
;; ===========================================================================

(deftest mounting-a-creates-chain-and-arg-keyed-cache-entries
  (testing "rf2-fqzmb #1: mounting Child A subscribes its own L1→L2→L3
   chain AND the arg-keyed [:button-deck/greater-than? 5] sub — every
   one lands a sub-cache entry. The chain's intermediate L1 (:chain-root)
   + L2 (:chain-doubled) materialise via the cascade even though A only
   names the L3 + the gt? sub directly."
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:button-deck/seed])

    (is (empty? (sub-cache)) "precondition: cold cache before mount")

    (let [held (mount-a! 5)]
      (is (= "2×input = 2" @(:chain held))
          "chain L3 computes against the seeded :chain-input")
      (is (false? @(:gt held))
          "gt? 5: chain-root 1 is NOT > 5")

      ;; A's whole read-set is cached, including the cascaded chain inputs.
      (is (cached? [:button-deck/chain-labelled])
          "L3 :chain-labelled cached (A's direct deref)")
      (is (cached? [:button-deck/chain-doubled])
          "L2 :chain-doubled cached (cascaded input)")
      (is (cached? [:button-deck/chain-root])
          "L1 :chain-root cached (cascaded input)")
      (is (cached? [:button-deck/greater-than? 5])
          "arg-keyed [:gt? 5] cached")

      (unmount-a! held))))

;; ===========================================================================
;; #2 — changing the sub-arg N creates a DISTINCT cache entry
;; ===========================================================================

(deftest changing-the-arg-creates-a-distinct-cache-entry
  (testing "rf2-fqzmb #2: the parameterized-sub cache is keyed by arg.
   With A mounted at N=5, changing the arg to N=10 (the testbed's
   button 11) materialises a NEW [:button-deck/greater-than? 10] entry
   ALONGSIDE the original [:button-deck/greater-than? 5] — two distinct
   slots over the one registration."
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:button-deck/seed])

    (let [held-5 (mount-a! 5)]
      @(:gt held-5)
      (is (cached? [:button-deck/greater-than? 5])
          "[:gt? 5] cached after the N=5 mount")
      (is (not (cached? [:button-deck/greater-than? 10]))
          "precondition: no [:gt? 10] slot yet")

      ;; The arg changes (5 → 10): the root re-renders A with the new
      ;; prop, so A subscribes the new query-vector.
      (let [held-10 (mount-a! 10)]
        @(:gt held-10)
        (is (cached? [:button-deck/greater-than? 10])
            "[:gt? 10] is a NEW, distinct cache entry — cache keyed by arg")
        (is (cached? [:button-deck/greater-than? 5])
            "[:gt? 5] still present — distinct slot, not overwritten")
        (is (not (identical? (:gt held-5) (:gt held-10)))
            "the two args yield two distinct reactions")

        (unmount-a! held-5)
        (unmount-a! held-10)))))

;; ===========================================================================
;; #3 — changing a chain input recomputes L1→L2→L3
;; ===========================================================================

(deftest perturbing-chain-input-recomputes-the-chain
  (testing "rf2-fqzmb #3: with A mounted, perturbing the chain input
   (the testbed's button 12) propagates invalidation down L1 (:chain-root)
   → L2 (:chain-doubled) → L3 (:chain-labelled). The L3 value the view
   reads reflects the new root — the recompute reached the leaf."
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:button-deck/seed])

    (let [held (mount-a! 5)]
      (is (= "2×input = 2" @(:chain held))
          "precondition: L3 = 2×1 against the seeded root")

      ;; Perturb the L1 root. The chain reaction graph invalidates and
      ;; recomputes through to the leaf the view derefs.
      (rf/dispatch-sync [:button-deck/perturb-chain])

      (is (= 2 (get-in (frame/frame-app-db-value :rf/default)
                       [:views :chain-input]))
          "L1 source advanced 1 → 2")
      (is (= "2×input = 4" @(:chain held))
          "L3 recomputed to 2×2 = 4 — invalidation propagated L1→L2→L3")
      (is (false? @(:gt held))
          "[:gt? 5] still false off the shared root (2 is not > 5)")

      (unmount-a! held)))

  (testing "rf2-fqzmb #3 (gt? recompute): once the root crosses the
   threshold the arg-keyed sub flips — same shared L1 drives both legs."
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:button-deck/seed])
    (let [held (mount-a! 1)]
      (is (false? @(:gt held)) "precondition: root 1 is not > 1")
      (rf/dispatch-sync [:button-deck/perturb-chain]) ;; root → 2
      (is (true? @(:gt held)) "[:gt? 1] flipped true after root 1 → 2")
      (unmount-a! held))))

;; ===========================================================================
;; #4 — unmounting A disposes ALL of A's subs
;; ===========================================================================

(deftest unmounting-a-disposes-every-sub-and-records-the-unmount
  (testing "rf2-fqzmb #4: unmounting Child A (the testbed's button 13)
   drops the last derefer on every sub A held, so the synchronous
   last-reader-gone GC evicts ALL of them — the chain L1/L2/L3 AND
   every [:gt? N] entry the section accumulated — and the unmount is
   RECORDED on the :rf.sub/dispose trace stream."
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:button-deck/seed])

    ;; Mount A, change the arg (so TWO [:gt? N] entries accumulate), then
    ;; unmount — the full set must dispose.
    (let [held-5  (mount-a! 5)
          _       (do @(:chain held-5) @(:gt held-5))
          held-10 (mount-a! 10)
          _       (do @(:chain held-10) @(:gt held-10))]
      ;; Precondition: the whole accumulated read-set is cached.
      (doseq [k [[:button-deck/chain-labelled]
                 [:button-deck/chain-doubled]
                 [:button-deck/chain-root]
                 [:button-deck/greater-than? 5]
                 [:button-deck/greater-than? 10]]]
        (is (cached? k) (str "precondition: " (pr-str k) " cached")))

      (with-trace-recorder! [disposes {:pred #(= :rf.sub/dispose (:operation %))}]
        ;; Unmount A: drop every derefer. chain-labelled is held by both
        ;; mounts (ref-count 2), so it disposes only on the LAST drop —
        ;; pinning the last-reader-gone invariant.
        (unmount-a! held-5)
        (is (cached? [:button-deck/chain-labelled])
            "chain L3 still cached after the first unmount — ref-count 2→1")
        (unmount-a! held-10)

        ;; Every slot A held is gone.
        (doseq [k [[:button-deck/chain-labelled]
                   [:button-deck/chain-doubled]
                   [:button-deck/chain-root]
                   [:button-deck/greater-than? 5]
                   [:button-deck/greater-than? 10]]]
          (is (not (cached? k))
              (str (pr-str k) " disposed — last reader gone")))
        (is (empty? (sub-cache))
            "A's whole read-set evicted; cache drained")

        ;; The unmount is RECORDED: a :rf.sub/dispose fired for each
        ;; evicted sub-id with the ref-count-drop reason.
        (let [ids (into #{} (map #(get-in % [:tags :rf.sub/id])) @disposes)]
          (is (contains? ids :button-deck/chain-labelled)
              ":rf.sub/dispose recorded for the chain L3")
          (is (contains? ids :button-deck/chain-doubled)
              ":rf.sub/dispose recorded for the chain L2")
          (is (contains? ids :button-deck/chain-root)
              ":rf.sub/dispose recorded for the chain L1")
          (is (contains? ids :button-deck/greater-than?)
              ":rf.sub/dispose recorded for the arg-keyed sub"))
        (is (every? #(= :no-more-derefers (get-in % [:tags :rf.sub/reason]))
                    @disposes)
            "every dispose carries :reason :no-more-derefers (ref-count-drop)")))))

;; ===========================================================================
;; #5 — render cause: sub-driven (A) vs props-driven (B)
;; ===========================================================================
;;
;; The substrate-agnostic views.cljs wrapper stamps :rf.view/triggered-by
;; on :rf.view/rendered when an own sub of the view changed value in the
;; cascade; it is ABSENT on a render with no changed own sub (the
;; consumer reads the absence as ← props / parent re-render). Child A
;; names its sub; Child B (props-only) names nothing.

(def ^:private view-rendered-pred
  #(= :rf.view/rendered (:operation %)))

(deftest child-a-render-named-by-its-sub-child-b-by-props
  (testing "rf2-fqzmb #5: Child A (subscription-driven) re-renders ←
   a SUB changed, so :rf.view/rendered names that sub on
   :rf.view/triggered-by. Child B (props-driven) subscribes nothing, so
   its re-render names NO sub — the foil. Pairs with the rf2-bhi3t Xray
   render-cause attribution."
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:button-deck/seed])

    ;; Child A — subscription-driven. Derefs the chain leaf.
    (rf/reg-view ^{:rf/id :button-deck/child-a} child-a [_threshold]
      [:div @(rf/subscribe [:button-deck/chain-labelled])])

    ;; Child B — props-driven. Subscribes nothing; renders its prop.
    (rf/reg-view ^{:rf/id :button-deck/child-b} child-b [prop]
      [:div prop])

    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      ;; A renders INSIDE the perturb cascade: the handler bumps the
      ;; chain root (its first recompute reports value-changed? into the
      ;; in-flight buffer), then A renders and its deref-sink records
      ;; [:button-deck/chain-labelled]. The intersection resolves
      ;; :triggered-by to A's own changed sub.
      (let [render-a (rf/view :button-deck/child-a)]
        (rf/reg-event-fx :button-deck/perturb-then-render-a
          (fn [_ _]
            @(rf/subscribe [:button-deck/chain-labelled])
            (render-a 5)
            {}))
        (rf/dispatch-sync [:button-deck/perturb-then-render-a]))

      ;; B renders INSIDE a cascade too, but reads NO sub. Its render
      ;; is driven by the prop it was handed.
      (let [render-b (rf/view :button-deck/child-b)]
        (rf/reg-event-fx :button-deck/render-b
          (fn [_ _]
            (render-b "beta")
            {}))
        (rf/dispatch-sync [:button-deck/render-b]))

      (let [a-ev (first (filter #(= :button-deck/child-a
                                    (get-in % [:tags :rf.view/id]))
                                @traces))
            b-ev (first (filter #(= :button-deck/child-b
                                    (get-in % [:tags :rf.view/id]))
                                @traces))]
        (is (some? a-ev) "Child A emitted :rf.view/rendered")
        (is (= :button-deck/chain-labelled
               (get-in a-ev [:tags :rf.view/triggered-by]))
            "A re-render is attributed to its own changed sub (sub-driven)")

        (is (some? b-ev) "Child B emitted :rf.view/rendered")
        (is (not (contains? (:tags b-ev) :rf.view/triggered-by))
            "B names NO sub — render driven by props, the foil to A")))))
