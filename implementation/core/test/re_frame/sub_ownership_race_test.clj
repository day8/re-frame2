(ns re-frame.sub-ownership-race-test
  "JVM-only regressions for two sub-cache OWNERSHIP races (rf2-fzbj.5, split
  as rf2-gwye.3 and rf2-gwye.4). In both, a release or a disposal batch acted
  on the cache as it stood at that moment rather than on what its own caller
  had acquired or removed.

  1. `subscribe-once` released by ADDRESS. If a `clear-sub-cache!` evicted
     the one-shot's reaction A while it was mid-deref, and another consumer
     then rebuilt the slot as B, the one-shot's release decremented B and
     disposed it under its owner. The eviction had already taken A's
     reference, so the release must be a no-op.
  2. `clear-sub-cache!` and `dispose-all-for-frame-destroy!` read the cache
     and emptied it in two steps. An entry acquired between the two was
     erased by the reset without being in the batch, so it was never
     disposed; and two overlapping batches could each condemn one entry.

  ## Determinism

  No sleeps and no reliance on scheduler luck: every interleaving is pinned
  with promises at a named point.

  - Race 1 holds the one-shot INSIDE its deref, in the sub body itself.
  - Race 2 holds the batch at the moment it empties the cache, through a
    `with-redefs` over `reset!` and `reset-vals!` that matches ONLY this
    frame's cache atom being set to `{}`, holds only the first such call,
    and otherwise delegates unchanged. It covers both primitives so it pins
    the same point whichever one the batch uses: with a read-then-reset
    extraction the hold lands AFTER the batch has taken its snapshot, and
    with a single atomic extraction it lands before it.

  Every wait is bounded, and a timeout fails the test instead of hanging it.

  ## Posture

  Ref-counts, cache identity and dispose counts only, with no trace
  observation, so every assertion holds under `-Dre-frame.debug=false` and
  this namespace runs in the production-gate lane by default."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
  (rf/destroy-adapter!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(def ^:private wait-ms 10000)

(defn- await!
  "Deref `p`, bounded. A timeout throws, so a broken interleaving fails the
  test rather than hanging the lane."
  [p what]
  (let [v (deref p wait-ms ::timeout)]
    (when (= ::timeout v)
      (throw (ex-info (str "timed out waiting for " what) {:waiting-for what})))
    v))

(defn- entry [frame-id query-v]
  (get @(:sub-cache (rf.frame/frame frame-id)) query-v))

(defn- counting-dispose
  "A `rf.interop/dispose!` stand-in that counts calls per reaction in `counts`
  and then delegates. It counts the CALL rather than the on-dispose callbacks
  because a plain-atom reaction's `-dispose` is idempotent, which would hide
  a second disposal."
  [counts]
  (let [real-dispose! rf.interop/dispose!]
    (fn [r]
      (swap! counts update r (fnil inc 0))
      (real-dispose! r))))

(defn- run-batch-held-at-extraction
  "Run `batch!` on a worker thread and hold it at the moment it empties
  `cache`, call `in-window` on this thread while it is held, then release it
  and wait for it to return. See the namespace docstring for the seam."
  [cache batch! in-window]
  (let [held             (promise)
        release          (promise)
        armed            (atom true)
        real-reset!      clojure.core/reset!
        real-reset-vals! clojure.core/reset-vals!
        hold!            (fn [a v]
                           (when (and (identical? a cache)
                                      (= {} v)
                                      (compare-and-set! armed true false))
                             (deliver held true)
                             (await! release "the test to release the batch")))]
    (with-redefs [clojure.core/reset!      (fn [a v] (hold! a v) (real-reset! a v))
                  clojure.core/reset-vals! (fn [a v] (hold! a v) (real-reset-vals! a v))]
      (let [job (future (batch!))]
        (try
          (await! held "the batch to reach its extraction")
          (in-window)
          (deliver release true)
          (await! job "the batch to return")
          (finally
            (deliver release true)
            (future-cancel job)))))))

;; ---- 1. subscribe-once releases the reaction it acquired ------------------

(deftest one-shot-release-does-not-steal-a-successor-reaction
  (testing "an eviction during the one-shot's deref ends its reference, so the
            release that follows must not decrement a successor another
            consumer owns"
    (let [frame-id :own/once
          q        [:own/value]
          entered  (promise)
          resume   (promise)
          disposed (atom 0)]
      (rf/reg-sub :own/value
                  (fn [_db _q]
                    (deliver entered true)
                    (await! resume "the test to resume the one-shot deref")
                    :value))
      (rf/make-frame {:id frame-id})
      (let [job (future (rf.subs/subscribe-once q {:frame frame-id}))]
        (try
          (await! entered "the one-shot to enter its deref")
          (let [a (:reaction (entry frame-id q))]
            (is (some? a) "premise: the one-shot's reaction A is cached while it derefs")
            (rf.subs.cache/clear-sub-cache! frame-id)
            (let [b (rf.subs/subscribe q {:frame frame-id})]
              (rf.interop/add-on-dispose! b #(swap! disposed inc))
              (is (not (identical? a b)) "premise: the clear evicted A and B is its successor")
              (deliver resume true)
              (is (= :value (await! job "the one-shot to return"))
                  "the one-shot still returns the value it read")
              (is (identical? b (:reaction (entry frame-id q)))
                  "THE BUG (rf2-gwye.3): pre-fix the one-shot's address-only release
                   found B at the address, drove it 1 -> 0 and evicted it")
              (is (= 1 (:ref-count (entry frame-id q)))
                  "B keeps exactly its owner's reference")
              (is (zero? @disposed) "B's on-dispose never ran")
              (rf.subs/unsubscribe frame-id q)
              (is (nil? (entry frame-id q)) "B's owner still releases it normally")
              (is (= 1 @disposed) "and that release disposes B exactly once")))
          (finally
            (deliver resume true)
            (future-cancel job)))))))

(deftest one-shot-read-beside-a-live-holder-leaves-the-holder-intact
  (testing "no eviction: the one-shot takes and returns only its own reference"
    (let [frame-id :own/control
          q        [:own/value]
          disposed (atom 0)]
      (rf/reg-sub :own/value (fn [_db _q] :value))
      (rf/make-frame {:id frame-id})
      (let [held (rf.subs/subscribe q {:frame frame-id})]
        (rf.interop/add-on-dispose! held #(swap! disposed inc))
        (is (= :value (rf.subs/subscribe-once q {:frame frame-id})))
        (is (identical? held (:reaction (entry frame-id q)))
            "the holder's reaction is still the cached one")
        (is (= 1 (:ref-count (entry frame-id q)))
            "the one-shot released exactly the reference it took")
        (is (zero? @disposed))
        (rf.subs/unsubscribe frame-id q)
        (is (nil? (entry frame-id q)))
        (is (= 1 @disposed) "the holder's own release disposes exactly once")))))

(deftest one-shot-read-as-the-only-owner-disposes-in-tick
  (testing "the one-shot owned the last reference, so its release evicts and
            disposes before it returns"
    (let [frame-id :own/final
          q        [:own/value]
          seen     (atom nil)
          counts   (atom {})]
      (rf/reg-sub :own/value (fn [_db _q] :value))
      (rf/make-frame {:id frame-id})
      (let [cache (:sub-cache (rf.frame/frame frame-id))]
        (add-watch cache ::seen
                   (fn [_ _ _ m]
                     (when-let [r (get-in m [q :reaction])]
                       (compare-and-set! seen nil r))))
        (with-redefs [rf.interop/dispose! (counting-dispose counts)]
          (is (= :value (rf.subs/subscribe-once q {:frame frame-id}))))
        (remove-watch cache ::seen))
      (is (some? @seen) "premise: the one-shot's reaction was cached")
      (is (nil? (entry frame-id q)) "the slot is evicted before subscribe-once returns")
      (is (= 1 (get @counts @seen 0)) "its reaction is disposed exactly once")))
  (testing "a missing frame is still a nil read, not a throw"
    (is (nil? (rf.subs/subscribe-once [:own/value] {:frame :own/never-made})))))

;; ---- 2. batch disposal disposes exactly what it removes ------------------

(def ^:private batches
  "The two whole-cache disposal paths, each as `frame-id -> thunk`."
  [["clear-sub-cache!"
    (fn [frame-id] #(rf.subs.cache/clear-sub-cache! frame-id))]
   ["dispose-all-for-frame-destroy!"
    (fn [frame-id]
      (let [cache (:sub-cache (rf.frame/frame frame-id))]
        #(rf.subs.cache/dispose-all-for-frame-destroy! cache frame-id)))]])

(defn- fresh-frame! [scenario i]
  (let [frame-id (keyword "own" (str scenario "-" i))]
    (rf/make-frame {:id frame-id})
    frame-id))

(defn- owned-exactly-once?
  "An entry is either still cached and never disposed (it was acquired after
  the extraction), or gone and disposed exactly once (it was in the batch).
  Erased but never disposed, and disposed twice, are both ownership leaks."
  [{:keys [cached? disposes]}]
  (if cached? (zero? disposes) (= 1 disposes)))

(deftest a-concurrent-acquisition-is-never-erased-undisposed
  (rf/reg-sub :own/old (fn [_db _q] :old))
  (rf/reg-sub :own/late (fn [_db _q] :late))
  (doseq [[i [label batch-for]] (map-indexed vector batches)]
    (testing (str label ": an entry acquired while the batch empties the cache
                   is either left live or disposed with the batch")
      (let [frame-id (fresh-frame! "acquire" i)
            cache    (:sub-cache (rf.frame/frame frame-id))
            counts   (atom {})
            old      (rf.subs/subscribe [:own/old] {:frame frame-id})
            late     (promise)]
        (with-redefs [rf.interop/dispose! (counting-dispose counts)]
          (run-batch-held-at-extraction
            cache (batch-for frame-id)
            (fn []
              (let [r (rf.subs/subscribe [:own/late] {:frame frame-id})]
                (deliver late r)
                (is (identical? r (:reaction (get @cache [:own/late])))
                    "premise: B is cached inside the batch's window")))))
        (let [late (await! late "B")
              fate (fn [r q] {:cached?  (identical? r (:reaction (get @cache q)))
                              :disposes (get @counts r 0)})]
          (is (= {:cached? false :disposes 1} (fate old [:own/old]))
              "A, cached when the batch began, is removed and disposed exactly once")
          (is (owned-exactly-once? (fate late [:own/late]))
              (str "THE BUG (rf2-gwye.4): pre-fix B was erased by the reset but was "
                   "absent from the batch's snapshot, so it was never disposed; got "
                   (fate late [:own/late]))))))))

(deftest overlapping-batches-condemn-each-entry-once
  (rf/reg-sub :own/old (fn [_db _q] :old))
  (doseq [[i [label batch-for]] (map-indexed vector batches)]
    (testing (str label ": a batch that runs to completion while another is held
                   at its extraction leaves each entry in exactly one of them")
      (let [frame-id (fresh-frame! "overlap" i)
            cache    (:sub-cache (rf.frame/frame frame-id))
            counts   (atom {})
            old      (rf.subs/subscribe [:own/old] {:frame frame-id})
            batch!   (batch-for frame-id)]
        (with-redefs [rf.interop/dispose! (counting-dispose counts)]
          (run-batch-held-at-extraction cache batch! batch!))
        (is (= {} @cache))
        (is (= 1 (get @counts old 0))
            "THE BUG (rf2-gwye.4): pre-fix both batches had already snapshotted
             the entry, so each of them disposed it")))))

(deftest an-acquisition-after-extraction-stays-live
  (rf/reg-sub :own/old (fn [_db _q] :old))
  (rf/reg-sub :own/late (fn [_db _q] :late))
  (doseq [[i [label batch-for]] (map-indexed vector batches)]
    (testing (str label ": an entry acquired while the batch disposes its
                   members, after it emptied the cache, is not the batch's")
      (let [frame-id (fresh-frame! "after" i)
            cache    (:sub-cache (rf.frame/frame frame-id))
            counts   (atom {})
            old      (rf.subs/subscribe [:own/old] {:frame frame-id})
            late     (atom nil)]
        ;; Acquire from inside A's disposal, i.e. strictly after the extraction:
        ;; the eager-reacquisition shape the React-hook spine uses.
        (rf.interop/add-on-dispose!
          old #(reset! late (rf.subs/subscribe [:own/late] {:frame frame-id})))
        (with-redefs [rf.interop/dispose! (counting-dispose counts)]
          ((batch-for frame-id)))
        (is (some? @late) "premise: A's disposal acquired B")
        (is (identical? @late (:reaction (get @cache [:own/late]))) "B stays cached")
        (is (= 1 (:ref-count (get @cache [:own/late]))) "with its owner's reference")
        (is (zero? (get @counts @late 0)) "and the batch never disposes it")))))
