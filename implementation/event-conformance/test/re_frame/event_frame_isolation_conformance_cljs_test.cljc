(ns re-frame.event-frame-isolation-conformance-cljs-test
  "Conformance for live dispatch through image-loaded frames.

  Frame-targeted read tests can pass even if live dispatch incorrectly falls
  back to the default registrar. This suite therefore uses public
  `rf/dispatch-sync`, same-id global sentinels, and committed app-db values to
  prove that handler resolution and effects stay inside the target frame.

  The cases also lock two less obvious boundaries: child `:fx` dispatches retain
  the frame's image generation, while a frame without an image uses the default
  registrar with no generation binding. `:ambient-frame nil` keeps every target
  explicit so ambient resolution cannot conceal a routing error."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.events         :as events]
            [re-frame.image          :as image]
            [re-frame.registrar      :as registrar]
            [re-frame.live-frame     :as lf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support   :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter       plain-atom/adapter
                                            :ambient-frame nil}))

;; Image resolution stores the registration descriptor plus its selection keys.
(defn- event-desc
  [provenance-ns id handler-fn]
  (merge (events/event-handler-meta handler-fn)
         {:rf.provenance/ns provenance-ns
          :kind             :event
          :id               id}))

(deftest two-image-frames-same-id-different-handlers-dispatch-in-isolation
  (testing "same-id handlers resolve and commit within their image-loaded frames"
    ;; A wrong fallback to the default registrar writes this sentinel value.
    (rf/reg-event :boot/init
      (fn [{:keys [db]} _] {:db (assoc db :booted-by :global)}))
    (let [todo-pool    [(event-desc "examples.todo" :boot/init
                                    (fn [{:keys [db]} _] {:db (assoc db :booted-by :todo)}))]
          counter-pool [(event-desc "examples.counter" :boot/init
                                    (fn [{:keys [db]} _] {:db (assoc db :booted-by :counter)}))]
          todo-img     (image/image {:id :examples/todo    :select-ns {:include ["examples.todo"]}})
          counter-img  (image/image {:id :examples/counter :select-ns {:include ["examples.counter"]}})
          todo-frame    (lf/make-frame {:id :todo/main    :images [todo-img]}    todo-pool)
          counter-frame (lf/make-frame {:id :counter/main :images [counter-img]} counter-pool)]
      (rf/dispatch-sync [:boot/init] {:frame :todo/main})
      (rf/dispatch-sync [:boot/init] {:frame :counter/main})
      (testing "each frame ran ITS OWN image's handler"
        (is (= :todo    (:booted-by (rf/app-db-value :todo/main)))
            "the todo frame ran the TODO image's handler end-to-end")
        (is (= :counter (:booted-by (rf/app-db-value :counter/main)))
            "the counter frame ran the COUNTER image's handler end-to-end"))
      (testing "the default registrar was not used"
        (is (not= :global (:booted-by (rf/app-db-value :todo/main)))
            "the todo frame did NOT resolve the global handler")
        (is (not= :global (:booted-by (rf/app-db-value :counter/main)))
            "the counter frame did NOT resolve the global handler"))
      (testing "each frame committed ONLY its own state — no cross-frame bleed"
        (is (= {:booted-by :todo}    (rf/app-db-value :todo/main))
            "the todo frame's app-db holds ONLY the todo handler's write")
        (is (= {:booted-by :counter} (rf/app-db-value :counter/main))
            "the counter frame's app-db holds ONLY the counter handler's write"))
      (testing "the generation binding did NOT leak past either cascade"
        (is (nil? registrar/*generation*)
            "after the dispatches, no generation is bound (the seam unwound)"))
      (testing "the two frames carry genuinely different resolved generations for
                the same id (no shared generation)"
        (is (not= (lf/frame-generation todo-frame)
                  (lf/frame-generation counter-frame))
            "the two image-loaded frames resolve the SAME id through DIFFERENT generations")))))

;; Prove the fallback sentinel is reachable rather than relying on a vacuous
;; `not= :global` assertion.
(deftest the-global-sentinel-is-genuinely-registered
  (testing "the default handler used as the fallback sentinel is live"
    (rf/reg-event :boot/init (fn [{:keys [db]} _] {:db (assoc db :booted-by :global)}))
    (is (some? (registrar/lookup :event :boot/init))
        "the global handler is on the default registrar (the armed sentinel)")
    (rf/reg-frame :plain/main {:doc "no image-loaded object"})
    (rf/dispatch-sync [:boot/init] {:frame :plain/main})
    (is (= :global (:booted-by (rf/app-db-value :plain/main)))
        "an image-less frame DOES run the global handler — the sentinel fires when reached")))

(deftest two-frames-sharing-one-image-keep-independent-app-db
  (testing "frames sharing an image still have independent runnable state"
    ;; Distinguish image resolution from an accidental default lookup.
    (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (assoc db :n :global)}))
    (let [pool [(event-desc "ex.counter" :counter/inc
                            (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))]
          img  (image/image {:id :ex/counter :select-ns {:include ["ex.counter"]}})
          fa   (lf/make-frame {:images [img] :initial-events [[:rf/set-db {:n 0}]]}   pool)
          fb   (lf/make-frame {:images [img] :initial-events [[:rf/set-db {:n 100}]]} pool)]
      (is (= (lf/frame-generation fa) (lf/frame-generation fb))
          "both frames run the SAME resolved image generation (one shared image)")
      (is (not= (:rf.frame/runnable-id fa) (:rf.frame/runnable-id fb))
          "yet they are DISTINCT runnable frames (distinct backing records)")
      (rf/dispatch-sync [:counter/inc] {:frame fa})
      (rf/dispatch-sync [:counter/inc] {:frame fa})
      (rf/dispatch-sync [:counter/inc] {:frame fb})
      (testing "each frame's app-db reflects ONLY its own dispatch stream"
        (is (= 2 (:n (rf/app-db-value fa)))
            "frame A: seeded 0, inc'd twice on A's OWN app-db")
        (is (= 101 (:n (rf/app-db-value fb)))
            "frame B: seeded 100, inc'd once — fully isolated from A"))
      (testing "the IMAGE inc ran on both (generation routed to the shared image),
                NOT the global"
        (is (not= :global (:n (rf/app-db-value fa))))
        (is (not= :global (:n (rf/app-db-value fb))))))))

(deftest child-dispatch-stays-in-the-frames-image-and-commits-only-that-frame
  (testing "a child dispatch keeps the parent frame's image and state boundary"
    ;; A wrong fallback at either cascade step writes a global marker.
    (rf/reg-event :counter/inc  (fn [{:keys [db]} _] {:db (assoc db :inc :global)}))
    (rf/reg-event :counter/step (fn [{:keys [db]} _] {:db (assoc db :step :global)}))
    (let [target-pool
          [(event-desc "examples.counter" :counter/inc
                       (fn [{:keys [db]} _]
                         {:db (assoc db :inc :image)
                          :fx [[:dispatch [:counter/step]]]}))
           (event-desc "examples.counter" :counter/step
                       (fn [{:keys [db]} _] {:db (assoc db :step :image)}))]
          sibling-pool
          [(event-desc "examples.todo" :counter/inc
                       (fn [{:keys [db]} _] {:db (assoc db :inc :sibling)}))
           (event-desc "examples.todo" :counter/step
                       (fn [{:keys [db]} _] {:db (assoc db :step :sibling)}))]
          target-img  (image/image {:id :examples/counter :select-ns {:include ["examples.counter"]}})
          sibling-img (image/image {:id :examples/todo    :select-ns {:include ["examples.todo"]}})
          _ (lf/make-frame {:id :counter/main :images [target-img]}  target-pool)
          _ (lf/make-frame {:id :sibling/main :images [sibling-img]} sibling-pool)]
      (rf/dispatch-sync [:counter/inc] {:frame :counter/main})
      (let [db (rf/app-db-value :counter/main)]
        (testing "the parent AND the fx child both resolved the TARGET frame's image"
          (is (= :image (:inc db))  "the parent resolved the image's inc handler")
          (is (= :image (:step db))
              "the CHILD fx dispatch re-derived the generation and resolved the
               image's step handler too (coherent across the cascade)"))
        (testing "neither the parent nor the child fell back to the global registrar"
          (is (not= :global (:inc db)))
          (is (not= :global (:step db)))))
      (testing "the SIBLING frame on a different image is UNTOUCHED — the cascade
                committed only the target frame's app-db (effect/state isolation)"
        (is (= {} (rf/app-db-value :sibling/main))
            "the sibling frame received no write at all — its app-db is the fresh empty map")
        (is (not= :sibling (:inc (rf/app-db-value :counter/main)))
            "no sibling-image handler ran on the target frame")))))

(deftest absence-is-default-no-image-frame-uses-the-global-registrar
  (testing "an image-less frame uses the default registrar without a generation"
    (rf/reg-frame :plain/main {:doc "no image-loaded object for this frame"})
    (rf/reg-event :plain/set (fn [{:keys [db]} _] {:db (assoc db :written-by :global)}))
    (rf/reg-sub  :plain/value (fn [db _] (:written-by db)))
    (rf/dispatch-sync [:plain/set] {:frame :plain/main})
    (is (= :global (:written-by (rf/app-db-value :plain/main)))
        "with no image generation, the dispatch resolved the GLOBAL handler")
    (is (= :global @(rf/subscribe [:plain/value] {:frame :plain/main}))
        "with no image generation, the subscribe resolved the GLOBAL sub")
    (is (nil? registrar/*generation*)
        "no generation was ever bound for an image-less frame")))
