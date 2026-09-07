(ns re-frame.subs-evicted-input-release-cljs-test
  "rf2-1frc (residual) — a layer-2+ sub's disposal must release the input
  reactions it ACTUALLY ACQUIRED, never whatever now sits at those addresses.

  ## The defect

  PR #9373 gave the React-hook spine EAGER REACQUISITION: when a mounted
  hook's committed reaction is disposed by a framework-owned eviction (hot
  reload, an explicit `clear-sub-cache!`, a frame generation change), the
  `rf.interop/add-on-dispose!` callback re-subscribes IMMEDIATELY and rewires
  onto the successor, so the mount is never left holding a dead node.

  Both eviction primitives remove the whole condemned batch from the cache
  atom BEFORE they dispose any member of it — `invalidate-frame-subs!`
  `swap-vals!`-dissocs the transitive closure and then walks `evicted-keys`,
  and `clear-sub-cache!` `(reset! cache {})` and then walks the pre-clear
  snapshot. Combined with eager reacquisition, that means a LATER member of
  the batch is disposed AFTER an earlier member has already rebuilt its
  subtree into the (now live again) cache.

  `re-frame.subs`' built-in on-dispose callback was asymmetric across exactly
  that window: its cache-dissoc step is IDENTITY-GUARDED (`identical?
  reaction (:reaction (get m k))`), but its declared-input release was an
  address-only `unsubscribe`. So the second parent's teardown decremented —
  and disposed — the SUCCESSOR child the first parent had just built and was
  holding.

  Ordinary topology, no exotica: two mounted parents P1 and P2 declaring the
  same child C. Before: `{C 2, P1 1, P2 1}`. Evict all three. Disposing old P1
  reacquires new P1 and builds new C (count 1). Disposing old P2 then
  unsubscribes C BY ADDRESS, driving new C 1 → 0 and disposing it under the
  live P1; P2's own reacquisition builds a third C. After: `{C 1, P1 1, P2 1}`
  with P1 watching a disposed node — it stops seeing app-db movement, and a
  later release of either parent retires the remaining child's only counted
  ref under its sibling.

  ## The repair under test

  The release is routed through the identity guard that already existed for
  the spine's own holders — `re-frame.subs/unsubscribe-if-reaction` — carrying
  the concrete input reaction the build acquired. A release whose reaction is
  no longer the cache's no-ops (its reference died with the eviction) instead
  of stealing a successor's; a release whose reaction IS the cache's takes the
  ordinary 1 → 0 in-tick disposal exactly as before.

  ## What this namespace does NOT assume

  It does not require a MANUALLY disposed reaction to be reusable. Every
  eviction here is framework-owned (`clear-sub-cache!`, a `reg-sub`
  replacement); the claim is only that a hook which reacquires across such an
  eviction ends up sharing ONE child with its sibling.

  ## Posture split (rf2-d2841)

  Every assertion is posture-independent: ref-counts and reactivity, no
  `:trace` observation, and the seam itself carries no `rf.interop/debug-
  enabled?` gate.

  `.cljc` — runs under both `clojure -M:test` (JVM) and `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.subs :as rf.subs]
            [re-frame.subs.cache :as rf.subs.cache]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; rf2-qj4g — COLD-START the adapter slot: this ns shares the node bundle
  ;; with suites that seat Reagent / UIx / SSR, so a bare `init!` would be a
  ;; no-op whenever one of them ran first.
  (rf/destroy-adapter!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- entry
  [frame-id query-v]
  (get @(:sub-cache (rf.frame/frame frame-id)) query-v))

(defn- ref-count
  [frame-id query-v]
  (:ref-count (entry frame-id query-v)))

(defn- cached-reaction
  [frame-id query-v]
  (:reaction (entry frame-id query-v)))

(defn- eager-holder!
  "A minimal stand-in for the React-hook spine's COMMITTED acquisition
  (`re-frame.substrate.spine`'s `on-committed-disposed`): hold one durable
  reference to `query-v` in `frame-id` and, when the held reaction is disposed
  by a framework-owned eviction, re-subscribe immediately and rewire.

  Deliberately mirrors the spine's guards — it re-acquires only while it is
  still the holder of THAT reaction and only while the frame is alive — so the
  reading here is about `re-frame.subs`' input-release symmetry and not about
  the hook. Returns an atom holding the current reaction."
  [frame-id query-v]
  (let [held (atom nil)]
    (letfn [(wire! [r]
              (reset! held r)
              (rf.interop/add-on-dispose! r
                (fn []
                  (when (and (identical? r @held)
                             (some? (rf.frame/frame frame-id)))
                    (wire! (rf.subs/subscribe query-v {:frame frame-id}))))))]
      (wire! (rf.subs/subscribe query-v {:frame frame-id})))
    held))

(defn- register-two-parents-one-child!
  []
  (rf/reg-event :ev/seed (fn [_ctx [_ v]] {:db {:n v}}))
  (rf/reg-sub :ev/child (fn [db _q] (:n db)))
  (rf/reg-sub :ev/p1 {:inputs [[:ev/child]]} (fn [[c] _q] [:p1 c]))
  (rf/reg-sub :ev/p2 {:inputs [[:ev/child]]} (fn [[c] _q] [:p2 c])))

;; ---- 1. explicit clear-sub-cache! ----------------------------------------

(deftest evicted-parent-release-does-not-steal-the-successor-childs-ref
  (testing "two eagerly-reacquiring parents over ONE shared child survive an
            explicit clear-sub-cache! sharing the SAME rebuilt child"
    (register-two-parents-one-child!)
    (rf/make-frame {:id :ev/frame})
    (rf/dispatch-sync [:ev/seed 1] {:frame :ev/frame})

    (let [h1 (eager-holder! :ev/frame [:ev/p1])
          h2 (eager-holder! :ev/frame [:ev/p2])]
      (is (= [:p1 1] @@h1) "P1 derives from the child")
      (is (= [:p2 1] @@h2) "P2 derives from the same child")
      (is (= 2 (ref-count :ev/frame [:ev/child]))
          "baseline: the shared child carries one ref per parent")

      ;; THE FRAMEWORK-OWNED EVICTION. The whole batch leaves the cache before
      ;; any member of it is disposed, so the second parent's teardown runs
      ;; against a cache the first parent has already repopulated.
      (rf.subs.cache/clear-sub-cache! :ev/frame)

      (is (= 2 (ref-count :ev/frame [:ev/child]))
          "THE BUG (rf2-1frc residual): pre-fix this read 1 — the second
           parent's address-only input release decremented the SUCCESSOR child
           the first parent had just built, disposing it, and the second
           parent's own reacquisition then built a third one")
      (is (some? (cached-reaction :ev/frame [:ev/child]))
          "one child reaction is cached for both parents")

      ;; Reactivity pins. NOT the discriminator — measured, these two PASS
      ;; pre-fix on the plain-atom substrate, whose watch survives the
      ;; premature dispose; the ref-count above is what the defect moves. They
      ;; stay as the regression floor the acceptance asks for, and the
      ;; substrate-visible consequence is pinned in the UIx browser lane.
      (rf/dispatch-sync [:ev/seed 2] {:frame :ev/frame})
      (is (= [:p1 2] @@h1) "P1 remains reactive after the eviction")
      (is (= [:p2 2] @@h2) "P2 remains reactive after the eviction"))))

(deftest surviving-parent-keeps-its-child-when-its-sibling-releases
  (testing "after the eviction storm, unmounting ONE parent leaves the other
            parent's child ref intact"
    (register-two-parents-one-child!)
    (rf/make-frame {:id :ev/frame})
    (rf/dispatch-sync [:ev/seed 1] {:frame :ev/frame})

    (let [h1 (eager-holder! :ev/frame [:ev/p1])
          h2 (eager-holder! :ev/frame [:ev/p2])]
      @@h1
      @@h2
      (rf.subs.cache/clear-sub-cache! :ev/frame)

      ;; "Unmount" P2: release the reaction it actually holds, exactly as the
      ;; spine's cleanup does.
      (let [held2 @h2]
        (reset! h2 nil)                                    ; stop reacquiring
        (rf.subs/unsubscribe-if-reaction :ev/frame [:ev/p2] held2))

      (is (= 1 (ref-count :ev/frame [:ev/child]))
          "the child keeps P1's ref — pre-fix the count was already 1 before
           this release, so P2's unmount retired P1's only child outright")
      (is (some? (cached-reaction :ev/frame [:ev/child]))
          "the child slot survives P2's unmount")

      (rf/dispatch-sync [:ev/seed 3] {:frame :ev/frame})
      (is (= [:p1 3] @@h1)
          "the surviving parent is still reactive after its sibling unmounts"))))

;; ---- 2. reg-sub replacement (hot reload) ---------------------------------

(deftest reg-sub-replacement-of-the-shared-child-keeps-both-parents-sharing-it
  (testing "replacing the shared child's registration evicts child + both
            parents in ONE batch; the eagerly-reacquiring parents must end up
            sharing one successor child"
    (register-two-parents-one-child!)
    (rf/make-frame {:id :ev/frame})
    (rf/dispatch-sync [:ev/seed 1] {:frame :ev/frame})

    (let [h1 (eager-holder! :ev/frame [:ev/p1])
          h2 (eager-holder! :ev/frame [:ev/p2])]
      (is (= [:p1 1] @@h1))
      (is (= [:p2 1] @@h2))

      ;; Spec 001 §Hot-reload semantics: re-registering the child fires the
      ;; replacement hook, which evicts the child's transitive dependent
      ;; closure — child, P1 and P2 — as one batch.
      (rf/reg-sub :ev/child (fn [db _q] (* 100 (:n db))))

      (is (= 2 (ref-count :ev/frame [:ev/child]))
          "THE BUG (rf2-1frc residual): both parents share ONE rebuilt child")
      (is (= [:p1 100] @@h1)
          "P1 runs the replacement child body")
      (is (= [:p2 100] @@h2)
          "P2 runs the replacement child body")

      (rf/dispatch-sync [:ev/seed 2] {:frame :ev/frame})
      (is (= [:p1 200] @@h1) "P1 stays reactive across the replacement")
      (is (= [:p2 200] @@h2) "P2 stays reactive across the replacement"))))
