(ns re-frame.make-frame-generation-pool-window-jvm-test
  "A frame's generation and the pool it was resolved against are never
  observable out of step. `make-frame` writes the frame's generation-provenance
  row before the engine commit, because `upsert-frame!` runs the
  `:initial-events` steps inside the call, and their dispatch flushes any
  pending reprojection. Written after the commit, a stale row from a previous
  incarnation of the id would let that flush reproject the frame onto the old
  pool and swap it over the generation the constructor just installed.

  The ordering is common code (the flush is synchronous on both hosts); this
  JVM namespace arms the dirty flag without racing a scheduled CLJS tick. It
  reads the private flag and provenance table directly."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.events :as rf.events]
            [re-frame.image :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(def ^:private dirty-flag #'rf.live-frame/pending-reprojection?)
(def ^:private provenance #'rf.live-frame/frame-generation-pool)

(defn- pool-row [id] (get (deref @provenance) id))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    ;; a flag left dirty, or a row left by a case that threw, would decide the
    ;; outcome for the wrong reason
    (reset! @dirty-flag false)
    (swap! @provenance dissoc :pool-window/target :pool-window/decoy)
    (t)
    (reset! @dirty-flag false)))

(defn- reg-desc [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(def ^:private pool-v1
  [(reg-desc "pool.window.core" :event :pool-window/inc ::inc-v1)])

(def ^:private pool-v2
  [(reg-desc "pool.window.core" :event :pool-window/inc   ::inc-v2)
   (reg-desc "pool.window.core" :event :pool-window/reset ::reset)])

(def ^:private img
  (rf.image/image {:id :pool-window/img :select-ns {:include ["pool.window.core"]}}))

(defn- inc-impl
  "The `:pool-window/inc` impl the frame's current generation resolves:
  `::inc-v1` on pool V1, `::inc-v2` on pool V2."
  [id]
  (:handler-fn (rf.image-assembly/resolve-descriptor (rf.live-frame/frame-generation id) :event :pool-window/inc)))

(deftest initial-events-cascade-does-not-reproject-onto-the-previous-pool
  (rf.events/register-set-db-standard!)
  ;; a standing image-loaded frame, so the registration hook does not skip
  (rf/make-frame {:id :pool-window/decoy})
  (rf.live-frame/make-frame {:id :pool-window/target :images [img]} pool-v1)
  (is (= ::inc-v1 (inc-impl :pool-window/target))
      "control: the pools resolve differently")
  (rf/destroy-frame! :pool-window/target)
  ;; Teardown released the row, so the previous incarnation's row is planted
  ;; back: an absent row reads as the live store, whose zero-match the flush
  ;; swallows, and a re-construction over a live frame runs no :initial-events.
  ;; The destroy makes the next construction a first one, which runs them.
  (swap! @provenance assoc :pool-window/target pool-v1)
  (reset! @dirty-flag false)
  (rf/reg-event :pool-window/armer (fn [{:keys [db]} _] {:db db}))
  (is (true? @@dirty-flag) "control: a reprojection is pending as the constructor is entered")
  (rf.live-frame/make-frame {:id             :pool-window/target
                             :images         [img]
                             :initial-events [[:rf/set-db {:seeded true}]]}
                            pool-v2)
  ;; the generation, the row and the setup cascade's own write all agree on V2
  (is (= [::inc-v2 pool-v2 {:seeded true}]
         [(inc-impl :pool-window/target)
          (pool-row :pool-window/target)
          (rf/app-db-value :pool-window/target)])))

