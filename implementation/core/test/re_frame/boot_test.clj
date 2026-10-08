(ns re-frame.boot-test
  "JVM coverage for the boot lifecycle: `init!`, `install-adapter!`,
  `dispose-adapter!` and the substrate-delegation throws before install and
  after dispose. Each test starts from a cold, never-installed process (the
  fixture clears the adapter slot and its disposed breadcrumb), because the
  unit under test is boot itself."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn cold-start [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; reset the breadcrumb too, or every later test would see :adapter-disposed
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (test-fn)
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!))

(use-fixtures :each cold-start)

(deftest init-is-idempotent
  (rf/init! rf.substrate.plain-atom/adapter)
  (is (some? (rf.substrate.adapter/current-adapter)))
  (is (empty? @rf.frame/frames) "init! creates no frame, :rf/default included")
  (let [adapter-after-first (rf.substrate.adapter/current-adapter)
        frames-after-first  @rf.frame/frames]
    (rf/init! rf.substrate.plain-atom/adapter)
    (is (identical? adapter-after-first (rf.substrate.adapter/current-adapter))
        "the second init! does not re-install")
    (is (= frames-after-first @rf.frame/frames))))

(deftest init-rejects-a-different-adapter
  ;; init! asks "is this the seated adapter?" (same canonical :kind, else
  ;; identity), not "is anything seated?" — so a different adapter raises
  ;; rather than being silently ignored, while a hot-reload copy is a no-op.
  (testing "a different adapter raises and leaves the seated one in place"
    (rf/init! rf.substrate.plain-atom/adapter)
    (let [other (assoc rf.substrate.plain-atom/adapter :kind ::other)
          data  (try (rf/init! other) nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :rf.error/adapter-already-installed (:rf.error/id data)))
      (is (identical? rf.substrate.plain-atom/adapter (:installed data)))
      (is (identical? other (:attempted data)))
      (is (identical? rf.substrate.plain-atom/adapter
                      (rf.substrate.adapter/current-adapter)))))
  (testing "a structural copy of the seated canonical adapter is the same adapter"
    (rf.substrate.adapter/dispose-adapter!)
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (rf/init! rf.substrate.plain-atom/adapter)
    (let [seated (rf.substrate.adapter/current-adapter)]
      (is (nil? (rf/init! (assoc rf.substrate.plain-atom/adapter :doc "reloaded"))))
      (is (identical? seated (rf.substrate.adapter/current-adapter)))))
  (testing "two distinct kind-less custom adapters are different (identity fallback)"
    (rf.substrate.adapter/dispose-adapter!)
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (let [custom-a (dissoc rf.substrate.plain-atom/adapter :kind)
          custom-b (dissoc rf.substrate.plain-atom/adapter :kind)]
      (rf/init! custom-a)
      (is (= :rf.error/adapter-already-installed
             (:rf.error/id (try (rf/init! custom-b) nil
                                (catch clojure.lang.ExceptionInfo e (ex-data e))))))
      (is (identical? custom-a (rf.substrate.adapter/current-adapter)))
      (is (nil? (rf/init! custom-a)) "the identical map is still an idempotent no-op"))))

(deftest throwing-adapter-cleanup-still-finalizes-the-process-lifecycle
  (let [boom (ex-info "adapter host cleanup failed" {:kind ::cleanup-failed})
        bad  (assoc rf.substrate.plain-atom/adapter
                    :dispose-adapter! (fn [] (throw boom)))]
    (rf.substrate.adapter/install-adapter! bad)
    (is (identical? boom
                    (try (rf.substrate.adapter/dispose-adapter!) nil
                         (catch clojure.lang.ExceptionInfo e e)))
        "the adapter cleanup error remains the primary throw")
    (is (nil? (rf.substrate.adapter/current-adapter))
        "a cleanup throw cannot leave the one-adapter install slot seated")
    (is (true? (rf.substrate.adapter/adapter-disposed?))
        "the lifecycle breadcrumb records the attempted installed teardown")
    (is (= :rf.error/adapter-disposed
           (:rf.error/id
            (ex-data
             (try
               (rf.substrate.adapter/make-state-container {})
               nil
               (catch clojure.lang.ExceptionInfo e e)))))
        "delegation after a throwing cleanup sees terminal disposal, not a half-live adapter")
    (is (identical? rf.substrate.plain-atom/adapter
                    (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))
        "a fresh adapter can install immediately after the failed cleanup")))

(deftest adapter-cleanup-claim-is-one-way-and-generation-safe
  (testing "a re-entrant destroy cannot invoke one generation's cleanup twice"
    (let [calls (atom 0)
          old   (assoc rf.substrate.plain-atom/adapter
                       :dispose-adapter!
                       (fn []
                         (swap! calls inc)
                         (rf.substrate.adapter/dispose-adapter!)))]
      (rf.substrate.adapter/install-adapter! old)
      (rf.substrate.adapter/dispose-adapter!)
      (is (= 1 @calls)
          "the terminal claim makes a nested destroy an idempotent no-op")
      (is (true? (rf.substrate.adapter/adapter-disposed?)))))
  (testing "a stale finalizer never clears a replacement generation"
    (let [replacement (assoc rf.substrate.plain-atom/adapter :kind ::replacement)
          old         (assoc rf.substrate.plain-atom/adapter
                             :dispose-adapter!
                             (fn []
                               ;; Adversarial test bypass: simulate a replacement
                               ;; generation appearing before the old cleanup's
                               ;; finally boundary. Public install cannot race into
                               ;; an occupied slot; this seam proves the stale
                               ;; generation guard itself rather than timing.
                               (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
                               (rf.substrate.adapter/install-adapter! replacement)))]
      (rf.substrate.adapter/install-adapter! old)
      (rf.substrate.adapter/dispose-adapter!)
      (is (identical? replacement (rf.substrate.adapter/current-adapter))
          "the old generation's finally leaves the replacement seated")
      (is (false? (rf.substrate.adapter/adapter-disposed?))
          "the replacement generation's successful install owns the breadcrumb"))))

(deftest concurrent-adapter-destroy-has-one-cleanup-owner-and-atomic-state
  (let [entered (promise)
        release (promise)
        calls   (atom 0)
        old     (assoc rf.substrate.plain-atom/adapter
                       :dispose-adapter!
                       (fn []
                         (swap! calls inc)
                         (deliver entered :entered)
                         (when (= ::timeout (deref release 5000 ::timeout))
                           (throw (ex-info "cleanup release timed out" {})))))
        first   (do
                  (rf.substrate.adapter/install-adapter! old)
                  (future
                    (try
                      (rf.substrate.adapter/dispose-adapter!)
                      :destroyed
                      (catch Throwable e e))))]
    (try
      (is (= :entered (deref entered 5000 ::timeout))
          "the first destroy owns cleanup and reaches the held phase")
      (let [second (future (rf.substrate.adapter/dispose-adapter!))]
        (is (nil? (deref second 5000 ::timeout))
            "a concurrent destroy observes the claimed generation and does no cleanup"))
      (is (= 1 @calls) "exactly one cleanup owner ran")
      (is (nil? (rf.substrate.adapter/current-adapter))
          "the terminal claim removes the generation from public introspection")
      (is (true? (rf.substrate.adapter/adapter-disposed?))
          "the disposed breadcrumb flips atomically with the terminal claim")
      (let [delegation (try
                         (rf.substrate.adapter/make-state-container {})
                         nil
                         (catch clojure.lang.ExceptionInfo e e))]
        (is (= :rf.error/adapter-disposed
               (:rf.error/id (ex-data delegation)))
            "delegation cannot enter cleanup's partly torn-down generation")
        (is (= :install-a-fresh-adapter
               (:recovery (ex-data delegation)))))
      (is (= :rf.error/adapter-already-installed
             (:rf.error/id
              (ex-data
               (try
                 (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
                 nil
                 (catch clojure.lang.ExceptionInfo e e)))))
          "the internal exact-generation claim blocks replacement until cleanup settles")
      (finally
        (deliver release :release)))
    (is (= :destroyed (deref first 5000 ::timeout)))
    (is (nil? (rf.substrate.adapter/current-adapter)))
    (is (true? (rf.substrate.adapter/adapter-disposed?)))
    (is (identical? rf.substrate.plain-atom/adapter
                    (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))
        "a fresh install succeeds after the exact cleanup owner settles")
    (is (false? (rf.substrate.adapter/adapter-disposed?)))))

(deftest init-keyword-arg-raises-no-adapter-specified
  (let [data (try (rf/init! :reagent) nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:rf.error/id :rf.error/no-adapter-specified
            :received    :reagent
            :expected    "adapter spec map"}
           (select-keys data [:rf.error/id :received :expected]))))
  (is (nil? (rf.substrate.adapter/current-adapter))))

(defn- thrown-data [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def ^:private delegation-calls
  "Every delegation fn reaches the adapter through one `require-adapter!`;
  one required fn, replace-container! (which inspects its container first)
  and one optional fn stand for the set."
  [['rf/make-state-container  #(rf.substrate.adapter/make-state-container {:k :v})]
   ['rf/replace-container!    #(rf.substrate.adapter/replace-container! ::dummy-container {:new :value})]
   ['rf/subscribe-container   #(rf.substrate.adapter/subscribe-container ::dummy-container (fn [_]))]])

(deftest substrate-delegation-uniform-no-adapter-throw
  (doseq [[where-sym thunk] delegation-calls]
    (is (= {:rf.error/id :rf.error/no-adapter-installed :where where-sym :recovery :no-recovery}
           (select-keys (thrown-data thunk) [:rf.error/id :where :recovery])))))

(deftest replace-container-nil-container-skips-adapter-check
  ;; the nil-container guard runs before the adapter lookup, so a drain hitting
  ;; a destroyed frame never reports a misleading no-adapter-installed
  (is (nil? (rf.substrate.adapter/replace-container! nil {:any :value}))))

(deftest dispose-with-nothing-installed-does-not-mark-the-process-disposed
  (rf.substrate.adapter/dispose-adapter!)
  (is (false? (rf.substrate.adapter/adapter-disposed?))))

(deftest substrate-delegation-after-dispose-throws-adapter-disposed
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.substrate.adapter/dispose-adapter!)
  (doseq [[where-sym thunk] delegation-calls]
    (is (= {:rf.error/id :rf.error/adapter-disposed :where where-sym :recovery :install-a-fresh-adapter}
           (select-keys (thrown-data thunk) [:rf.error/id :where :recovery])))))
