(ns re-frame.frame-resolution-cljs-test
  "Frame-derived live registration resolution: inside
  `call-with-frame-resolution`, `rf.registrar/lookup` (the one chokepoint every
  kind's lookup funnels through) and the registrar queries resolve through the
  target frame's own image generation; outside it, or for a target with no
  generation, they fall through to the registrar.

  Frames resolve against explicit synthetic descriptor pools, so nothing here
  depends on the live source store. The fixture snapshots and restores the
  registrar rather than clearing it, which would wipe framework ns-load
  registrations CLJS cannot reload."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.registrar      :as rf.registrar]
            [re-frame.test-support   :as rf.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.live-frame     :as rf.live-frame]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    (rf.image-assembly/clear-standards!)
    (t)
    (rf.image-assembly/clear-standards!)))

(defn- reg-desc
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(deftest lookup-derives-from-the-bound-frame-generation
  (rf.registrar/register! :event :app/boot {:handler-fn ::default-boot})
  (let [pool  [(reg-desc "examples.app" :event :app/boot ::image-boot)]
        img   (rf.image/image {:select-ns {:include ["examples.app"]}})
        frame (rf.live-frame/make-frame {:images [img]} pool)]
    (is (= ::default-boot (:handler-fn (rf.registrar/lookup :event :app/boot))))
    (rf.live-frame/call-with-frame-resolution frame
      (fn []
        (is (= ::image-boot (:handler-fn (rf.registrar/lookup :event :app/boot))))))
    (is (= ::default-boot (:handler-fn (rf.registrar/lookup :event :app/boot)))
        "outside the seam, lookup reverts to the registrar")))

(deftest event-sub-fx-cofx-view-all-derive-from-the-frame-generation
  ;; lookup is generic over kind, so one non-event kind stands for the rest
  (let [pool  [(reg-desc "examples.feat" :sub :feat/value ::sub)]
        img   (rf.image/image {:select-ns {:include ["examples.feat"]}})
        frame (rf.live-frame/make-frame {:images [img]} pool)]
    (rf.registrar/register! :event :other/not-in-image {:handler-fn ::global-only})
    (rf.live-frame/call-with-frame-resolution frame
      (fn []
        (is (= ::sub (rf.registrar/handler :sub :feat/value)))
        (is (nil? (rf.registrar/lookup :event :other/not-in-image))
            "the frame's image is the whole universe: a global-only id is nil")))))

(deftest registrations-and-ids-project-from-the-generation
  (rf.registrar/register! :event :global/only {:handler-fn ::global})
  (let [pool  [(reg-desc "examples.q" :event :q/a ::a)
               (reg-desc "examples.q" :event :q/b ::b)
               (reg-desc "examples.q" :sub   :q/s ::s)]
        img   (rf.image/image {:select-ns {:include ["examples.q"]}})
        frame (rf.live-frame/make-frame {:images [img]} pool)]
    (is (contains? (rf.registrar/registrations :event) :global/only))
    (rf.live-frame/call-with-frame-resolution frame
      (fn []
        (is (= #{:q/a :q/b} (rf.registrar/ids :event)))
        (is (not (contains? (rf.registrar/registrations :event) :global/only)))
        (is (= #{:q/a} (set (keys (rf.registrar/registrations
                                    :event
                                    #(= ::a (:handler-fn %)))))))))
    (is (contains? (rf.registrar/registrations :event) :global/only)
        "after the seam the query reverts to the registrar")))

(deftest absence-is-default-leaves-existing-callers-unaffected
  ;; a target that is not an image-loaded frame binds nothing
  (rf.registrar/register! :event :app/boot {:handler-fn ::default-boot})
  (doseq [target [:counter/main {:rf.frame/object true}]]
    (rf.live-frame/call-with-frame-resolution target
      (fn []
        (is (= ::default-boot (:handler-fn (rf.registrar/lookup :event :app/boot)))
            (pr-str target))))))
