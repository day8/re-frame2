(ns re-frame.make-frame-generation-seal-race-jvm-test
  "A registration that lands while a frame is mid-construction is never lost
  (JVM only; CLJS is single-threaded). `make-frame` seals its generation, then
  publishes the record. A `reg-*` on another thread between the two fires the
  registration hook, which skips when no image-loaded frame is in `frames`, and
  the in-flight one is not yet. So the constructor makes the dirty mark itself
  when the pool moved across its window; otherwise the first frame in a process
  would publish a generation predating the registration and stay stale.

  The constructor is parked inside the window with `rf.frame/*upsert-decide-probe*`,
  a nil-in-production seam fired after the construction transaction is
  acquired and before the registry decision. No sleeps decide anything."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; the coalescing dirty flag, read white-box

(def ^:private dirty-flag #'rf.live-frame/pending-reprojection?)

(defn- reset-runtime [test-fn]
  (try
    (rf.registrar/clear-all!)
    (reset! rf.frame/frames {})
    (rf.flows/reset-flows!)
    (rf.schemas/clear-schemas-by-frame!)
    (rf.trace.tooling/clear-listeners!)
    (rf/init! rf.substrate.plain-atom/adapter)
    ;; restore the ns-load framework registrations clear-all! wiped
    (require 're-frame.routing :reload)
    (require 're-frame.ssr :reload)
    ;; last: clear-all! itself marks dirty when an earlier frame stands, and a
    ;; flag already set would repair the staleness for the wrong reason
    (reset! @dirty-flag false)
    (test-fn)
    (finally
      (rf.registrar/clear-all!)
      (reset! rf.frame/frames {})
      (reset! @dirty-flag false))))

(use-fixtures :each reset-runtime)

;; A latch we expect to FIRE waits this long; it only bounds a hang.
(def ^:private ^:const settle-ms 10000)

(defn- await! [^CountDownLatch latch ^long ms]
  (.await latch ms TimeUnit/MILLISECONDS))

;; Park the constructor of `target` INSIDE the window: the transaction is held,
;; the generation is sealed, the record is not yet in `frames`.
(defn- window-probe [target ^CountDownLatch reached ^CountDownLatch release]
  (fn [id]
    (when (= id target)
      (.countDown reached)
      (.await release settle-ms TimeUnit/MILLISECONDS))))

(deftest registration-racing-the-generation-seal-is-not-lost
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        runs    (atom 0)
        a       (binding [rf.frame/*upsert-decide-probe*
                          (window-probe :seal-race/a reached release)]
                  (future (rf/make-frame {:id  :seal-race/a
                                          :doc "the constructor parked mid-window"})))]
    (is (await! reached settle-ms) "the constructor parked inside the window")
    (is (= [nil #{}] [(rf.frame/frame :seal-race/a) (set (rf.live-frame/live-frame-ids))])
        "precondition: no image-loaded frame is visible, so the hook will skip")
    (rf/reg-event :seal-race/late
      (fn [{:keys [db]} _]
        (swap! runs inc)
        {:db (assoc db :late :ran)}))
    (.countDown release)
    (deref a settle-ms ::timeout)
    ;; a frame resolving through a generation sealed before the registration
    ;; would report :rf.error/no-such-handler and never run it
    (rf/dispatch-sync [:seal-race/late] {:frame :seal-race/a})
    (is (= [1 :ran] [@runs (:late (rf/app-db-value :seal-race/a))]))))

(deftest construction-with-no-racing-registration-leaves-the-projection-clean
  ;; an unconditional construction mark would arm a reprojection sweep over
  ;; every image-loaded frame on each ordinary make-frame
  (rf/reg-event :seal-race/quiet (fn [{:keys [db]} _] {:db db}))
  (rf/make-frame {:id :seal-race/quiet-frame})
  (let [after-first @@dirty-flag]
    (rf/make-frame {:id :seal-race/quiet-frame-2})
    (is (= [false false] [after-first @@dirty-flag])
        "neither the first nor a second unraced construction marks dirty")))
