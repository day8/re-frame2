(ns re-frame.make-frame-generation-pool-contention-jvm-test
  "Under contention, only the attempt the engine admits may move a frame's
  descriptor pool. The pool rides the frame record beside the generation, so a
  same-id contender loses at the engine's claim before it writes either: were
  it to publish its pool on the way to losing, a reprojection on any thread
  could re-resolve the winner's live frame against it.

  The winner parks on `rf.frame/*upsert-decide-probe*`. An atom watch on the
  frames registry fires synchronously inside each `swap!`, recording every
  move of the id's pool slot and whether the id was reserved at that instant,
  and runs the real reader, `reproject-live-frame!`, there. JVM only: CLJS is
  single-threaded."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.frame :as rf.frame]
            [re-frame.image :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- pool-row [id] (:descriptor-pool (rf.frame/frame id)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    (try
      (t)
      (finally
        (remove-watch rf.frame/frames ::row-watch)))))

;; A latch we expect to FIRE waits this long; it only bounds a hang.
(def ^:private ^:const settle-ms 10000)

(defn- await! [^CountDownLatch latch ^long ms]
  (.await latch ms TimeUnit/MILLISECONDS))

(defn- err-id [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:rf.error/id (ex-data e)))))

;; Park the constructor of `target` inside its construction transaction: the
;; per-id reservation is held, and the authoritative registry decision has not
;; been taken yet.
(defn- window-probe [target ^CountDownLatch reached ^CountDownLatch release]
  (fn [id]
    (when (= id target)
      (.countDown reached)
      (.await release settle-ms TimeUnit/MILLISECONDS))))

(defn- exclusively-reserved?
  "Is `id` held by an in-flight construction right now? Answered by
  trying to claim it, and releasing at once if the claim succeeds."
  [id]
  (let [owner (try
                (rf.frame/claim-frame-construction! id ::reservation-probe)
                (catch clojure.lang.ExceptionInfo e
                  (if (= :rf.error/frame-construction-in-progress
                         (:rf.error/id (ex-data e)))
                    nil
                    (throw e))))]
    (if owner
      (do (rf.frame/release-frame-construction! owner) false)
      true)))

(defn- watch-row!
  "Log every move of `id`'s pool slot in the frames registry, with whether the
  id was reserved at that instant, and run `on-change` (or nothing) there. The
  watch fires on the writing thread inside the `swap!`; writes that do not
  move the slot are ignored."
  [id log on-change]
  (add-watch
    rf.frame/frames ::row-watch
    (fn [_ _ old new]
      (let [before (get (get old id) :descriptor-pool ::absent)
            after  (get (get new id) :descriptor-pool ::absent)]
        (when (not= before after)
          (swap! log conj {:from before :to after :reserved? (exclusively-reserved? id)})
          (when on-change (on-change)))))))

(defn- reg-desc [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(def ^:private pool-v1
  [(reg-desc "pool.race.core" :event :pool-race/inc ::inc-v1)])

(def ^:private pool-v2
  [(reg-desc "pool.race.core" :event :pool-race/inc   ::inc-v2)
   (reg-desc "pool.race.core" :event :pool-race/reset ::reset)])

(def ^:private img
  (rf.image/image {:id :pool-race/img :select-ns {:include ["pool.race.core"]}}))

(defn- inc-impl
  "The `:pool-race/inc` impl the frame's current generation resolves:
  `::inc-v1` on pool V1, `::inc-v2` on pool V2."
  [id]
  (:handler-fn (rf.image-assembly/resolve-descriptor (rf.live-frame/frame-generation id) :event :pool-race/inc)))

(deftest rejected-same-id-attempt-neither-publishes-nor-clobbers
  (let [target :pool-race/target]
    (rf.live-frame/make-frame {:id target :images [img]} pool-v2)
    (is (= [::inc-v2 pool-v2] [(inc-impl target) (pool-row target)])
        "control: the frame runs pool V2 and its record names it")
    (let [log     (atom [])
          reached (CountDownLatch. 1)
          release (CountDownLatch. 1)]
      ;; the reader, standing on the instant of any publication
      (watch-row! target log #(rf.live-frame/reproject-live-frame! target))
      (try
        (let [winner (binding [rf.frame/*upsert-decide-probe*
                               (window-probe target reached release)]
                       (future (rf.live-frame/make-frame {:id target :images [img]} pool-v2)))]
          (is (await! reached settle-ms) "the winner parked inside its transaction")
          (is (exclusively-reserved? target) "control: the winner holds the reservation")
          ;; the contender loses, writes nothing (so the reader never ran), and
          ;; the live frame still runs its owner's pool
          (is (= [:rf.error/frame-construction-in-progress [] ::inc-v2]
                 [(err-id #(rf.live-frame/make-frame {:id target :images [img]} pool-v1))
                  @log
                  (inc-impl target)]))
          (.countDown release)
          (is (= [true ::inc-v2 pool-v2]
                 [(not= ::timeout (deref winner settle-ms ::timeout))
                  (inc-impl target)
                  (pool-row target)])
              "the winner completes on its own generation with its pool in step"))
        (finally
          (.countDown release))))))
