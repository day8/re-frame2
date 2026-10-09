(ns re-frame.flows-lifecycle-drain-race-test
  "A flow lifecycle op (`clear-flow`, a `reg-flow` replacement) runs its
  multi-step registry and app-db mutation under the frame's drain lock, so a
  concurrent drain on the same frame never observes it half applied.

  Each test parks the op mid-region, or parks a drain that holds the lock,
  and races the other side from another thread; the racer blocks on the lock
  until the holder finishes, and the post-condition shows it saw the whole
  change. JVM-only: CLJS has no threads."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- await! [^CountDownLatch latch where]
  (is (.await latch 30 TimeUnit/SECONDS) (str "latch '" where "' did not trip within 30s")))

(defn- db [] (rf/app-db-value :rf/default))

(defn- seed-scaled!
  "`:scaled` [:n] -> `out-path`, as 2 × :n, seeded with :n 5."
  [out-path]
  (rf/reg-event :seed (fn [_ _] {:db {:n 5}}))
  (rf/reg-flow :scaled {:inputs [[:n]] :output-path [out-path]} (fn [n] (* 2 n)))
  (rf/dispatch-sync [:seed]))

(deftest clear-flow-output-stays-vacated-when-a-drain-races-the-window
  ;; `clear-flow` parks after vacating its output, still holding the lock. A
  ;; drain slipping into that window would recompute the still-registered flow
  ;; and re-commit the output.
  (let [vacate-var  #'rf.flows.registry/vacate-output-path!
        orig-vacate @vacate-var
        vacated     (CountDownLatch. 1)
        release     (CountDownLatch. 1)]
    (seed-scaled! :out)
    (rf/reg-event :bump (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
    ;; `with-redefs` cannot bind a private var from another namespace.
    (alter-var-root vacate-var
                    (constantly
                      (fn [& args]
                        (apply orig-vacate args)
                        (.countDown vacated)
                        (await! release "clear-flow release"))))
    (try
      (let [clearer (future (rf/clear :flow :scaled {:frame :rf/default}))
            racer   (future (await! vacated "vacate")
                            (rf/dispatch-sync [:bump 99] {:frame :rf/default}))]
        (await! vacated "vacate (main)")
        ;; Give the racer a real chance to slip into the window: with the lock
        ;; held it blocks, and this bounded wait times out.
        (deref racer 3000 nil)
        (.countDown release)
        (is (not= ::timeout (deref clearer 30000 ::timeout)))
        (is (not= ::timeout (deref racer 30000 ::timeout))))
      (finally
        (alter-var-root vacate-var (constantly orig-vacate))))
    (is (= [{:n 99} {} {}]
           [(db) (rf.flows/flows-snapshot) (rf.flows/last-inputs-snapshot)]))))

(deftest re-registration-recomputes-when-a-drain-races-the-invalidate-window
  ;; A replacement parks after publishing the new flow and before dropping
  ;; the stale dirty-check row. A drain slipping into that window would see
  ;; the new flow with the old row and skip it on equal inputs.
  (let [orig-drop @#'rf.flows.registry/drop-frame-flow-last-inputs!
        published (CountDownLatch. 1)
        release   (CountDownLatch. 1)]
    (seed-scaled! :out)
    (rf/reg-event :unrelated (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
    (with-redefs [rf.flows.registry/drop-frame-flow-last-inputs!
                  (fn [frame-id flow-id]
                    (.countDown published)
                    (await! release "reg-flow release")
                    (orig-drop frame-id flow-id))]
      (let [replacer (future (rf/reg-flow :scaled {:frame :rf/default :inputs [[:n]] :output-path [:out]}
                                          (fn [n] (* 100 n))))
            racer    (future (await! published "publish")
                             (rf/dispatch-sync [:unrelated] {:frame :rf/default}))]
        (await! published "publish (main)")
        (deref racer 3000 nil)
        (.countDown release)
        (is (not= ::timeout (deref replacer 30000 ::timeout)))
        (is (not= ::timeout (deref racer 30000 ::timeout)))))
    (is (= {:n 5 :out 500 :touched true} (db)))))

(deftest clear-flow-lookup-happens-under-the-lock-vs-a-racing-path-change-replacement
  ;; A drain parks in its handler holding the lock, then re-registers
  ;; `:scaled` from `[:out-a]` to `[:out-b]`. A clear started meanwhile must
  ;; read the flow under the lock, so it vacates the live `[:out-b]`; a read
  ;; taken before the lock would vacate the stale `[:out-a]`. The in-drain
  ;; move itself vacates `[:out-a]` through the pending `:db`, even though the
  ;; handler returns its db unchanged.
  (let [in-handler (CountDownLatch. 1)
        release    (CountDownLatch. 1)]
    (seed-scaled! :out-a)
    (rf/reg-event :replace-scaled
                  (fn [{:keys [db]} _]
                    (.countDown in-handler)
                    (await! release "replace-scaled handler release")
                    (rf/reg-flow :scaled {:frame :rf/default :inputs [[:n]] :output-path [:out-b]}
                                 (fn [n] (* 100 n)))
                    {:db db}))
    (let [replacer (future (rf/dispatch-sync [:replace-scaled] {:frame :rf/default}))]
      (await! in-handler "in-handler (main)")
      (let [clearer (future (rf/clear :flow :scaled {:frame :rf/default}))]
        (is (= ::still-blocked (deref clearer 1000 ::still-blocked))
            "the clear waits on the lock the drain holds")
        (.countDown release)
        (is (not= ::timeout (deref replacer 30000 ::timeout)))
        (is (not= ::timeout (deref clearer 30000 ::timeout)))))
    (is (= [{:n 5} {} {}]
           [(db) (rf.flows/flows-snapshot) (rf.flows/last-inputs-snapshot)]))))
