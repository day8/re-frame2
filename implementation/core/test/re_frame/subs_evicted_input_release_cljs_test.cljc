(ns re-frame.subs-evicted-input-release-cljs-test
  "A layer-2+ sub's disposal releases the input reactions it ACTUALLY
  acquired, never whatever now sits at those addresses.

  The React-hook spine reacquires EAGERLY: when a framework-owned eviction (hot
  reload, `clear-sub-cache!`, a generation change) disposes a mounted hook's
  reaction, its on-dispose callback re-subscribes at once. Both eviction
  primitives remove the whole batch from the cache before disposing any member,
  so a later member is disposed after an earlier one has rebuilt its subtree.
  Two mounted parents P1 and P2 over one child C: disposing old P1 builds new P1
  and new C; an address-only input release from old P2 would then decrement and
  dispose new C under the live P1. The release therefore goes through the
  identity guard, `re-frame.subs/unsubscribe-if-reaction`, carrying the input
  reaction the build acquired.

  `.cljc`, posture-independent: runs under `clojure -M:test`, the production
  gate and `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
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
  ;; COLD-START the adapter slot: this ns shares the node bundle
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
  "Two parents declaring one child, in a frame seeded with `{:n 1}`."
  []
  (rf/reg-event :ev/seed (fn [_ctx [_ v]] {:db {:n v}}))
  (rf/reg-sub :ev/child (fn [db _q] (:n db)))
  (rf/reg-sub :ev/p1 {:inputs [[:ev/child]]} (fn [[c] _q] [:p1 c]))
  (rf/reg-sub :ev/p2 {:inputs [[:ev/child]]} (fn [[c] _q] [:p2 c]))
  (rf/make-frame {:id :ev/frame})
  (rf/dispatch-sync [:ev/seed 1] {:frame :ev/frame}))

;; ---- explicit clear-sub-cache! ------------------------------------------

(deftest evicted-parent-release-does-not-steal-the-successor-childs-ref
  ;; The whole batch leaves the cache before any member is disposed, so the
  ;; second parent's teardown runs against a cache the first parent has
  ;; already repopulated. An address-only release from it would decrement and
  ;; dispose the successor child, and its own reacquisition would build a
  ;; third. (Reactivity is not the discriminator: plain-atom's watch survives a
  ;; premature dispose.)
  (register-two-parents-one-child!)
  (let [h1 (eager-holder! :ev/frame [:ev/p1])
        h2 (eager-holder! :ev/frame [:ev/p2])]
    @@h1
    @@h2
    (rf.subs.cache/clear-sub-cache! :ev/frame)
    (is (= 2 (ref-count :ev/frame [:ev/child])) "both parents share ONE rebuilt child")))

(deftest surviving-parent-keeps-its-child-when-its-sibling-releases
  (register-two-parents-one-child!)
  (let [h1 (eager-holder! :ev/frame [:ev/p1])
        h2 (eager-holder! :ev/frame [:ev/p2])]
    @@h1
    @@h2
    (rf.subs.cache/clear-sub-cache! :ev/frame)
    ;; "Unmount" P2 the way the spine's cleanup does: release the reaction it
    ;; actually holds.
    (let [held2 @h2]
      (reset! h2 nil)
      (rf.subs/unsubscribe-if-reaction :ev/frame [:ev/p2] held2))
    (is (= 1 (ref-count :ev/frame [:ev/child])) "the child keeps P1's ref")))

;; ---- reg-sub replacement (hot reload) -------------------------------------

(deftest reg-sub-replacement-of-the-shared-child-keeps-both-parents-sharing-it
  ;; Re-registering the child evicts its dependent closure, child and both
  ;; parents, as ONE batch (Spec 001 §Hot-reload semantics).
  (register-two-parents-one-child!)
  (let [h1 (eager-holder! :ev/frame [:ev/p1])
        h2 (eager-holder! :ev/frame [:ev/p2])]
    @@h1
    @@h2
    (rf/reg-sub :ev/child (fn [db _q] (* 100 (:n db))))
    (is (= [2 [:p1 100] [:p2 100]] [(ref-count :ev/frame [:ev/child]) @@h1 @@h2]))))
