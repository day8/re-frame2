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
            [re-frame.frame          :as frame]
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

;; A framework-authority variant of `event-desc`: stamps the reserved
;; `:rf/framework-authority? true` registration-meta key (EP-0001 —
;; `events/framework-authority?`) onto the image descriptor. Because `lookup`
;; resolves the descriptor verbatim through the frame's generation and the
;; router feeds it in as `handler-meta`, the resolved handler carries legitimate
;; write-authority over the reserved `:rf.db/runtime` partition — so an
;; image-loaded handler reads + returns the runtime-db partition on the
;; SUPPORTED path, exercising the real runtime-effect commit rather than
;; artificially suppressing the `:rf.warning/app-handler-runtime-effect`
;; diagnostic. A metadata-aware helper over the image-assembly path — it does
;; NOT bypass image assembly.
(defn- runtime-authority-event-desc
  [provenance-ns id handler-fn]
  (assoc (event-desc provenance-ns id handler-fn)
         :rf/framework-authority? true))

(deftest two-image-frames-same-id-different-handlers-dispatch-in-isolation
  (testing "same-id handlers resolve and commit within their image-loaded frames"
    ;; A wrong fallback to the default registrar writes this sentinel value.
    (rf/reg-event :boot/init
      (fn [{:keys [db]} _] {:db (assoc db :booted-by :global)}))
    (let [todo-registrations
          [(event-desc "examples.todo" :boot/init
             (fn [{:keys [db]} _] {:db (assoc db :booted-by :todo)}))]
          counter-registrations
          [(event-desc "examples.counter" :boot/init
             (fn [{:keys [db]} _] {:db (assoc db :booted-by :counter)}))]
          todo-image
          (image/image {:id :examples/todo
                        :select-ns {:include ["examples.todo"]}})
          counter-image
          (image/image {:id :examples/counter
                        :select-ns {:include ["examples.counter"]}})
          todo-frame
          (lf/make-frame {:id :todo/main :images [todo-image]}
                         todo-registrations)
          counter-frame
          (lf/make-frame {:id :counter/main :images [counter-image]}
                         counter-registrations)]
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

(deftest two-image-frames-same-id-isolate-the-runtime-db-partition
  (testing "same-id framework-authority handlers read + commit the RUNTIME-DB
            partition ONLY within their own image-loaded frame"
    ;; Composition under test: image-generation routing × the INDEPENDENT
    ;; runtime-db commit branch (`:rf.db/runtime` — Spec 002 §Write authority).
    ;; The existing same-id cases prove app-db (`:db`) isolation only; a
    ;; regression that keeps app-db routing correct but reads or commits
    ;; runtime-db through the default/sibling frame would leave them all green.
    ;;
    ;; A wrong fallback to the default registrar for this runtime-only event
    ;; runs THIS same-id global handler, whose runtime effect stamps the
    ;; `:global` sentinel into the target frame's runtime-db. It is
    ;; framework-authority so the fallback would be the REAL runtime-effect
    ;; path, not a diagnostic-suppressed one.
    (rf/reg-event :boot/rt-init
      {:rf/framework-authority? true}
      (fn [{runtime-db :rf.db/runtime} _]
        {:rf.db/runtime (assoc runtime-db
                               :rf.runtime/conf-observed (:rf.runtime/conf-seed runtime-db)
                               :rf.runtime/conf-writer   :global)}))
    (let [;; Each image handler is framework-authority (via the descriptor
          ;; helper): it reads its OWN `:rf.db/runtime` coeffect and returns an
          ;; updated `:rf.db/runtime` effect, recording the seed it OBSERVED and
          ;; its own writer marker.
          todo-registrations
          [(runtime-authority-event-desc "conf.rt.todo" :boot/rt-init
             (fn [{runtime-db :rf.db/runtime} _]
               {:rf.db/runtime
                (assoc runtime-db
                       :rf.runtime/conf-observed (:rf.runtime/conf-seed runtime-db)
                       :rf.runtime/conf-writer   :todo)}))]
          counter-registrations
          [(runtime-authority-event-desc "conf.rt.counter" :boot/rt-init
             (fn [{runtime-db :rf.db/runtime} _]
               {:rf.db/runtime
                (assoc runtime-db
                       :rf.runtime/conf-observed (:rf.runtime/conf-seed runtime-db)
                       :rf.runtime/conf-writer   :counter)}))]
          ;; The SIBLING carries its OWN same-id handler yet is NEVER dispatched
          ;; — it is the untouched-sibling sentinel (its runtime-db must stay
          ;; exactly its seed).
          sibling-registrations
          [(runtime-authority-event-desc "conf.rt.sibling" :boot/rt-init
             (fn [{runtime-db :rf.db/runtime} _]
               {:rf.db/runtime
                (assoc runtime-db :rf.runtime/conf-writer :sibling)}))]
          todo-image
          (image/image {:id :conf.rt/todo
                        :select-ns {:include ["conf.rt.todo"]}})
          counter-image
          (image/image {:id :conf.rt/counter
                        :select-ns {:include ["conf.rt.counter"]}})
          sibling-image
          (image/image {:id :conf.rt/sibling
                        :select-ns {:include ["conf.rt.sibling"]}})
          _ (lf/make-frame {:id :conf.rt/todo :images [todo-image]}
                           todo-registrations)
          _ (lf/make-frame {:id :conf.rt/counter :images [counter-image]}
                           counter-registrations)
          _ (lf/make-frame {:id :conf.rt/sibling :images [sibling-image]}
                           sibling-registrations)]
      ;; Seed each frame's runtime-db partition with a UNIQUE marker (the
      ;; framework-authority runtime-db write surface — Spec 002 §Write
      ;; authority). The seed is what each handler reads back as its
      ;; `:rf.db/runtime` coeffect.
      (frame/replace-runtime-db! :conf.rt/todo    {:rf.runtime/conf-seed :todo-seed})
      (frame/replace-runtime-db! :conf.rt/counter {:rf.runtime/conf-seed :counter-seed})
      (frame/replace-runtime-db! :conf.rt/sibling {:rf.runtime/conf-seed :sibling-seed})
      ;; Dispatch ONCE to each explicit target — the sibling is left alone.
      (rf/dispatch-sync [:boot/rt-init] {:frame :conf.rt/todo})
      (rf/dispatch-sync [:boot/rt-init] {:frame :conf.rt/counter})
      (testing "the same-id GLOBAL runtime sentinel is genuinely armed (not a
                vacuous `not= :global`)"
        (is (some? (registrar/lookup :event :boot/rt-init))
            "the same-id global runtime handler is live on the default registrar"))
      (testing "each handler read ITS OWN frame's runtime-db seed as the
                `:rf.db/runtime` coeffect — not a sibling's or the default's"
        (is (= :todo-seed (:rf.runtime/conf-observed (frame/frame-runtime-db-value :conf.rt/todo)))
            "the todo handler observed the TODO frame's runtime-db seed")
        (is (= :counter-seed (:rf.runtime/conf-observed (frame/frame-runtime-db-value :conf.rt/counter)))
            "the counter handler observed the COUNTER frame's runtime-db seed"))
      (testing "each runtime-db effect committed ONLY to its own frame — exact
                per-frame runtime-db, no cross-frame commit bleed"
        (is (= {:rf.runtime/conf-seed     :todo-seed
                :rf.runtime/conf-observed :todo-seed
                :rf.runtime/conf-writer   :todo}
               (frame/frame-runtime-db-value :conf.rt/todo))
            "the todo frame's runtime-db is EXACTLY the todo handler's write over the todo seed")
        (is (= {:rf.runtime/conf-seed     :counter-seed
                :rf.runtime/conf-observed :counter-seed
                :rf.runtime/conf-writer   :counter}
               (frame/frame-runtime-db-value :conf.rt/counter))
            "the counter frame's runtime-db is EXACTLY the counter handler's write over the counter seed"))
      (testing "neither frame fell back to the same-id GLOBAL runtime handler"
        (is (not= :global (:rf.runtime/conf-writer (frame/frame-runtime-db-value :conf.rt/todo)))
            "the todo frame did NOT resolve the global runtime handler")
        (is (not= :global (:rf.runtime/conf-writer (frame/frame-runtime-db-value :conf.rt/counter)))
            "the counter frame did NOT resolve the global runtime handler"))
      (testing "the un-dispatched SIBLING frame's runtime-db is UNCHANGED — no
                todo / counter / global runtime write leaked into it"
        (is (= {:rf.runtime/conf-seed :sibling-seed}
               (frame/frame-runtime-db-value :conf.rt/sibling))
            "the sibling frame's runtime-db is EXACTLY its seed (untouched)"))
      (testing "the runtime-only event left the app-db partition untouched —
                the two partitions commit independently (EP-0001)"
        (is (= {} (rf/app-db-value :conf.rt/todo))
            "the todo frame's app-db partition saw no write from the runtime-only event")
        (is (= {} (rf/app-db-value :conf.rt/counter))
            "the counter frame's app-db partition saw no write from the runtime-only event"))
      (testing "the generation binding did NOT leak past either cascade"
        (is (nil? registrar/*generation*)
            "after the dispatches, no generation is bound (the resolution seam unwound)")))))

;; Prove the fallback sentinel is reachable rather than relying on a vacuous
;; `not= :global` assertion.
(deftest the-global-sentinel-is-genuinely-registered
  (testing "the default handler used as the fallback sentinel is live"
    (rf/reg-event :boot/init (fn [{:keys [db]} _] {:db (assoc db :booted-by :global)}))
    (is (some? (registrar/lookup :event :boot/init))
        "the global handler is on the default registrar (the armed sentinel)")
    (rf/make-frame {:id :plain/main :doc "no image-loaded object"})
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
    (let [target-registrations
          [(event-desc "examples.counter" :counter/inc
                       (fn [{:keys [db]} _]
                         {:db (assoc db :inc :image)
                          :fx [[:dispatch [:counter/step]]]}))
           (event-desc "examples.counter" :counter/step
                       (fn [{:keys [db]} _] {:db (assoc db :step :image)}))]
          sibling-registrations
          [(event-desc "examples.todo" :counter/inc
                       (fn [{:keys [db]} _] {:db (assoc db :inc :sibling)}))
           (event-desc "examples.todo" :counter/step
                       (fn [{:keys [db]} _] {:db (assoc db :step :sibling)}))]
          target-image (image/image {:id :examples/counter
                                     :select-ns {:include ["examples.counter"]}})
          sibling-image (image/image {:id :examples/todo
                                      :select-ns {:include ["examples.todo"]}})
          _ (lf/make-frame {:id :counter/main :images [target-image]}
                           target-registrations)
          _ (lf/make-frame {:id :sibling/main :images [sibling-image]}
                           sibling-registrations)]
      (rf/dispatch-sync [:counter/inc] {:frame :counter/main})
      (let [target-db (rf/app-db-value :counter/main)]
        (testing "the parent AND the fx child both resolved the TARGET frame's image"
          (is (= :image (:inc target-db))
              "the parent resolved the image's inc handler")
          (is (= :image (:step target-db))
              "the CHILD fx dispatch re-derived the generation and resolved the
               image's step handler too (coherent across the cascade)"))
        (testing "neither the parent nor the child fell back to the global registrar"
          (is (not= :global (:inc target-db)))
          (is (not= :global (:step target-db)))))
      (testing "the SIBLING frame on a different image is UNTOUCHED — the cascade
                committed only the target frame's app-db (effect/state isolation)"
        (is (= {} (rf/app-db-value :sibling/main))
            "the sibling frame received no write at all — its app-db is the fresh empty map")
        (is (not= :sibling (:inc (rf/app-db-value :counter/main)))
            "no sibling-image handler ran on the target frame")))))

(deftest absence-is-default-no-image-frame-uses-the-global-registrar
  (testing "an image-less frame uses the default registrar without a generation"
    (rf/make-frame {:id :plain/main :doc "no image-loaded object for this frame"})
    (rf/reg-event :plain/set (fn [{:keys [db]} _] {:db (assoc db :written-by :global)}))
    (rf/reg-sub  :plain/value (fn [db _] (:written-by db)))
    (rf/dispatch-sync [:plain/set] {:frame :plain/main})
    (is (= :global (:written-by (rf/app-db-value :plain/main)))
        "with no image generation, the dispatch resolved the GLOBAL handler")
    (is (= :global @(rf/subscribe [:plain/value] {:frame :plain/main}))
        "with no image generation, the subscribe resolved the GLOBAL sub")
    (is (nil? registrar/*generation*)
        "no generation was ever bound for an image-less frame")))
