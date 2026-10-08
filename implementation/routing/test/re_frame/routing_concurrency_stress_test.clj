(ns re-frame.routing-concurrency-stress-test
  "JVM concurrency stress for routing. `n-threads` threads each drive
  `stress-iters` `:rf.route/handle-url-change` events at their OWN frame,
  released together by one latch. Each frame's drain serialises its own work
  (Spec 002 §Rules rule 1); what can tangle under contention is the state the
  frames share — the registrar's route table and its identity-keyed
  `route-table-cache` — and the per-frame slice writes beside it.

  Every scenario asserts, per thread: its `:on-match` fired exactly once per URL
  change (no drops, no doubles), and its frame's route slice settled on the
  route and slug of the last URL that thread sent.

    - `popstate-mid-push-stress` alternates the `:link` and `:popstate` causes
      of the one URL-driven door.
    - `reg-route-race-during-dispatch-stress` adds a churn thread registering
      and unregistering noise routes throughout, so the route-table cache is
      rebuilt under the dispatchers; no churn step may throw.

  CLJS is single-threaded, so these races exist only on the JVM.

  Tagged `^:stress`: the default `:test` alias excludes them and the
  `:slow-test` alias (the nightly `expensive-tests.yml` job and
  `scripts/test-rigorous-local.sh`) runs them. `RF2_KSBUR_STRESS_ITERS`
  overrides the per-thread iteration count."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [java.util.concurrent CountDownLatch]))

(defn- reset-runtime [test-fn]
  (rf.trace.tooling/clear-listeners!)
  (rf.routing-test-support/reset-runtime test-fn))

(use-fixtures :each reset-runtime)

(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_KSBUR_STRESS_ITERS") Long/parseLong)
      5000))

(def ^:private n-threads 8)

(defn- seat-threads!
  "One frame, one `:on-match` tick event and one route per thread. Each thread
  needs its own route: `:on-match` dispatches to the calling frame, and each
  tick event is registered for one frame."
  [kw-ns path-prefix]
  (vec (for [i (range n-threads)]
         (let [frame-id (keyword kw-ns (str "f" i))
               tick     (keyword kw-ns (str "tick-f" i))
               route-id (keyword kw-ns (str "route-" i))
               counter  (atom 0)]
           (rf/make-frame {:id frame-id :url-bound? false})
           (rf/reg-event tick {:frame frame-id}
                         (fn [{:keys [db]} _]
                           (swap! counter inc)
                           {:db (update db :n (fnil inc 0))}))
           (rf/reg-route route-id {:on-match [[tick]]} (str "/" path-prefix i "/:slug"))
           {:frame-id   frame-id
            :route-id   route-id
            :url-prefix (str "/" path-prefix i "/")
            :counter    counter}))))

(defn- dispatch-all!
  "Release every thread at once on `latch`; each sends `stress-iters` URL
  changes to its own frame, `cause-of` choosing each one's cause. The join is
  bounded so a hung drain fails rather than stalling the run."
  [^CountDownLatch latch threads cause-of]
  (let [futures (mapv (fn [{:keys [frame-id url-prefix]}]
                        (future
                          (.await latch)
                          (dotimes [k stress-iters]
                            (rf/dispatch-sync [:rf.route/handle-url-change (str url-prefix k)
                                               {:rf.route/cause (cause-of k)}]
                                              {:frame frame-id}))))
                      threads)]
    (.countDown latch)
    (doseq [f futures]
      (is (not= ::timeout (deref f 120000 ::timeout)) "thread completed within 120s"))))

(defn- assert-each-frame-settled! [threads]
  (is (= (mapv (fn [{:keys [route-id]}] [stress-iters route-id (str (dec stress-iters))]) threads)
         (mapv (fn [{:keys [frame-id counter]}]
                 (let [slice (get-in (:rf.db/runtime (rf/frame-state-value frame-id))
                                     [:rf.runtime/routing :current])]
                   [@counter (:route-id slice) (get-in slice [:params :slug])]))
               threads))
      "per thread: [on-match firings, settled route, settled slug]"))

(deftest ^:stress popstate-mid-push-stress
  (testing (str n-threads " threads × " stress-iters " URL changes, alternating :link / :popstate")
    (let [threads (seat-threads! "ksbur.pop" "q")]
      (dispatch-all! (CountDownLatch. 1) threads #(if (even? %) :link :popstate))
      (assert-each-frame-settled! threads))))

(deftest ^:stress reg-route-race-during-dispatch-stress
  (testing (str n-threads " threads × " stress-iters " URL changes against concurrent route churn")
    (let [threads (seat-threads! "ksbur.race" "r")
          noise   (mapv #(keyword "ksbur.race" (str "noise-" %)) (range 32))
          latch   (CountDownLatch. 1)
          stop?   (atom false)
          errors  (atom [])
          churn   (Thread.
                    ^Runnable
                    (fn []
                      (try
                        (.await latch)
                        (loop [p 0]
                          (when-not @stop?
                            (let [i (mod p (count noise))]
                              (if (even? p)
                                (rf/reg-route (nth noise i) {} (str "/noise-" i "/:x"))
                                (rf.registrar/unregister! :route (nth noise i))))
                            (Thread/yield)
                            (recur (inc p))))
                        (catch Throwable t (swap! errors conj t)))))]
      (.start churn)
      (try
        (dispatch-all! latch threads (constantly :link))
        (finally
          (reset! stop? true)
          (.join churn 10000)))
      (is (= [] (mapv #(.getMessage ^Throwable %) @errors)) "the churn thread never threw")
      (assert-each-frame-settled! threads))))
