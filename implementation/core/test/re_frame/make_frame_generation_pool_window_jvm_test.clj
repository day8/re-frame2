(ns re-frame.make-frame-generation-pool-window-jvm-test
  "A frame's generation and the pool it was resolved against are never
  observable out of step. `upsert-frame!` runs the `:initial-events` steps
  inside the call, and their dispatch flushes any pending reprojection, so the
  record those steps run against must already carry the incoming pool beside
  the incoming generation: the flush then re-resolves the frame against the
  pool it was sealed from and swaps nothing over the generation the
  constructor installed.

  The flush is synchronous on both hosts; this JVM namespace arms the dirty
  flag without racing a scheduled CLJS tick. It reads the private flag
  directly."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.events :as rf.events]
            [re-frame.frame :as rf.frame]
            [re-frame.image :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(def ^:private dirty-flag #'rf.live-frame/pending-reprojection?)

(defn- pool-row [id] (:descriptor-pool (rf.frame/frame id)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    ;; a flag left dirty by a case that threw would decide the outcome for the
    ;; wrong reason
    (reset! @dirty-flag false)
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
  (rf/make-frame {:id :pool-window/decoy :images [(rf.image/image {:id :pool-window/empty})]})
  (rf.live-frame/make-frame {:id :pool-window/target :images [img]} pool-v1)
  (is (= ::inc-v1 (inc-impl :pool-window/target))
      "control: the pools resolve differently")
  ;; a re-construction over a live frame runs no :initial-events, so the
  ;; destroy makes the next construction a first one, which runs them
  (rf/destroy-frame! :pool-window/target)
  (reset! @dirty-flag false)
  (rf/reg-event :pool-window/armer (fn [{:keys [db]} _] {:db db}))
  (is (true? @@dirty-flag) "control: a reprojection is pending as the constructor is entered")
  (rf.live-frame/make-frame {:id             :pool-window/target
                             :images         [img]
                             :initial-events [[:rf/set-db {:seeded true}]]}
                            pool-v2)
  ;; the generation, the record's pool and the setup cascade's own write all
  ;; agree on V2
  (is (= [::inc-v2 pool-v2 {:seeded true}]
         [(inc-impl :pool-window/target)
          (pool-row :pool-window/target)
          (rf/app-db-value :pool-window/target)])))

