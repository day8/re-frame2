(ns re-frame.live-cascade-frame-resolution-cljs-test
  "EP-0023 §Frame-derived live registration resolution — the OPERATIONAL
  acceptance (rf2-uejnt3). Slice .9 (rf2-32siq3.9) landed the resolution SEAM
  (`registrar/*generation*` + `live-frame/call-with-frame-resolution`) and proved
  it routes `registrar/lookup` when bound MANUALLY. This suite proves the seam is
  now invoked from the LIVE cascade: a REAL `(rf/dispatch [...] {:frame F})` (and
  a real `(rf/subscribe F [...])`) resolves the event / sub handler through the
  TARGET frame's resolved image generation END-TO-END — NOT through the global
  registrar, and NOT via a manual `*generation*` binding.

  > target frame -> resolved image generation -> registration resolution

  The headline case: a globally-registered handler/sub for `[:counter/inc]` /
  `[:counter/value]` writes/returns one value; the frame's IMAGE registers the
  SAME ids with a DIFFERENT impl. A frame-targeted dispatch/subscribe must run
  the IMAGE's impl, proving `router/process-event!` and `subs/subscribe` wrap the
  cascade with `call-with-frame-resolution` (rf2-uejnt3). A realm-only / no-image
  frame is unaffected (absence-is-default).

  ## Why two registries, one id

  The runnable frame RECORD (the EP-0013 substrate that owns app-db / queue /
  sub-cache, created by `reg-frame`) and the EP-0023 live-frame OBJECT (which
  carries the resolved image generation, created by `make-frame {:id …}`) are
  DELIBERATELY SEPARATE registries (per `re-frame.live-frame` docstring). This
  suite registers BOTH under the same id `:counter/main`: the router drains the
  frame record; `process-event!` derives the generation from the live-frame
  object of the same id. That is exactly the wiring rf2-uejnt3 ships — the .9/.10
  unification of the two into one runnable image-loaded frame object is a later
  slice; this slice only invokes the resolution seam at the live entry.

  Fixtures snapshot/restore the registrar via `make-reset-runtime-fixture`
  (NOT `registrar/clear-all!`, per the .9 isolation note) and clear the
  process-local live-frame registry between cases. `.cljc` ending `-cljs-test`
  rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.events         :as events]
            [re-frame.image          :as image]
            [re-frame.registrar      :as registrar]
            [re-frame.live-frame     :as lf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support   :as test-support]))

;; ---------------------------------------------------------------------------
;; Fixture: install the plain-atom adapter (so frames are runnable), opt OUT of
;; the ambient `:rf/default` scope (we drive explicit `{:frame …}` targets), and
;; clear the live-frame registry between cases so an `:id` from one case does not
;; collide with the next.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter        plain-atom/adapter
                                            :ambient-frame  nil})
  (fn [t]
    (lf/clear-live-frames!)
    (t)
    (lf/clear-live-frames!)))

;; ---------------------------------------------------------------------------
;; Helpers — build a RUNNABLE event-handler descriptor for an image resolver.
;;
;; The descriptor the image's `:rf.gen/resolver` carries is the SAME shape
;; `register!` stores — for an event that is `events/event-handler-meta` (carries
;; `:handler-fn` + the wrapping `:interceptors`), merged with the provenance /
;; kind / id keys image assembly groups + dedupes by. So a generation-routed
;; `registrar/lookup :event` returns a descriptor the cascade can run directly.
;; ---------------------------------------------------------------------------

(defn- event-desc
  "An image-resolver descriptor for an event id whose handler is `handler-fn` —
  the runnable `events/event-handler-meta` shape merged with the provenance /
  kind / id slots `image-assembly` keys by."
  [provenance-ns id handler-fn]
  (merge (events/event-handler-meta handler-fn)
         {:rf.provenance/ns provenance-ns
          :kind             :event
          :id               id}))

(defn- sub-desc
  "An image-resolver descriptor for a sub id whose computation is `compute-fn`
  (`(fn [db query-v] …)`). `reg-sub` stores the computation under `:handler-fn`,
  so a generation-routed `registrar/lookup :sub` returns this verbatim."
  [provenance-ns id compute-fn]
  {:rf.provenance/ns provenance-ns
   :kind             :sub
   :id               id
   :handler-fn       compute-fn})

;; ===========================================================================
;; 1. THE ACCEPTANCE — a REAL frame-targeted dispatch resolves the event
;;    handler through the TARGET frame's image, not the global registrar.
;; ===========================================================================

(deftest real-dispatch-resolves-event-handler-through-frame-image
  (testing "a globally-registered [:counter/inc] writes :global; the frame's
            IMAGE registers the SAME id writing :image. A REAL
            (rf/dispatch-sync [:counter/inc] {:frame :counter/main}) runs the
            IMAGE handler END-TO-END — proving process-event! wraps the cascade
            with call-with-frame-resolution (rf2-uejnt3), NOT a manual binding."
    ;; A runnable frame RECORD under :counter/main (EP-0013 substrate).
    (rf/reg-frame :counter/main {:doc "image-loaded counter frame"})
    ;; The GLOBAL handler — the value the cascade would write if it (wrongly)
    ;; resolved through the global registrar.
    (rf/reg-event :counter/inc
      (fn [{:keys [db]} _] {:db (assoc db :written-by :global)}))
    ;; The frame's IMAGE registers the SAME id with a DIFFERENT impl.
    (let [pool  [(event-desc "examples.counter" :counter/inc
                             (fn [{:keys [db]} _] {:db (assoc db :written-by :image)}))]
          img   (image/image {:id :examples/counter :include-ns ["examples.counter"]})
          ;; Register the live-frame OBJECT (carrying the image generation) under
          ;; the SAME id as the runnable frame record.
          _     (lf/make-frame {:id :counter/main :images [img]} pool)]
      ;; A REAL frame-targeted dispatch — no manual *generation* binding.
      (rf/dispatch-sync [:counter/inc] {:frame :counter/main})
      (is (= :image (:written-by (rf/app-db-value :counter/main)))
          "the IMAGE handler ran — the cascade resolved [:event :counter/inc]
           through the target frame's resolved image generation, end-to-end")
      (is (nil? registrar/*generation*)
          "the generation binding did NOT leak past the cascade"))))

;; ===========================================================================
;; 2. THE ACCEPTANCE — a REAL frame-targeted subscribe resolves the sub
;;    handler through the TARGET frame's image, not the global registrar.
;; ===========================================================================

(deftest real-subscribe-resolves-sub-handler-through-frame-image
  (testing "a globally-registered [:counter/value] returns :global; the frame's
            IMAGE registers the SAME id returning :image. A REAL
            (rf/subscribe :counter/main [:counter/value]) builds the IMAGE sub
            END-TO-END — proving subscribe wraps the build with
            call-with-frame-resolution (rf2-uejnt3)."
    (rf/reg-frame :counter/main {:doc "image-loaded counter frame"})
    ;; GLOBAL sub.
    (rf/reg-sub :counter/value (fn [_db _] :global))
    ;; The frame's IMAGE registers the SAME sub id with a DIFFERENT computation.
    (let [pool  [(sub-desc "examples.counter" :counter/value (fn [_db _] :image))]
          img   (image/image {:id :examples/counter :include-ns ["examples.counter"]})
          _     (lf/make-frame {:id :counter/main :images [img]} pool)]
      (is (= :image @(rf/subscribe :counter/main [:counter/value]))
          "the IMAGE sub computed — subscribe resolved [:sub :counter/value]
           through the target frame's resolved image generation, end-to-end")
      (is (nil? registrar/*generation*)
          "the generation binding did NOT leak past the build"))))

;; ===========================================================================
;; 3. THE HEADLINE — two frames, two DIFFERENT images, SAME id, via REAL
;;    dispatch: each resolves to its OWN image's handler (no global clobber).
;; ===========================================================================

(deftest two-frames-different-images-same-id-via-real-dispatch
  (testing "two runnable frames each loaded with a DIFFERENT image both
            handling [:boot/init] resolve that id to their OWN image's handler
            under a REAL frame-targeted dispatch (EP-0023 §Independent Surfaces
            On One Page — the heart of the same-id story, end-to-end)"
    (rf/reg-frame :todo/main    {:doc "todo surface"})
    (rf/reg-frame :counter/main {:doc "counter surface"})
    ;; A GLOBAL [:boot/init] neither frame should ever run.
    (rf/reg-event :boot/init
      (fn [{:keys [db]} _] {:db (assoc db :booted-by :global)}))
    (let [todo-pool    [(event-desc "examples.todo" :boot/init
                                    (fn [{:keys [db]} _] {:db (assoc db :booted-by :todo)}))]
          counter-pool [(event-desc "examples.counter" :boot/init
                                    (fn [{:keys [db]} _] {:db (assoc db :booted-by :counter)}))]
          todo-img     (image/image {:id :examples/todo    :include-ns ["examples.todo"]})
          counter-img  (image/image {:id :examples/counter :include-ns ["examples.counter"]})
          _ (lf/make-frame {:id :todo/main    :images [todo-img]}    todo-pool)
          _ (lf/make-frame {:id :counter/main :images [counter-img]} counter-pool)]
      (rf/dispatch-sync [:boot/init] {:frame :todo/main})
      (rf/dispatch-sync [:boot/init] {:frame :counter/main})
      (is (= :todo (:booted-by (rf/app-db-value :todo/main)))
          "the todo frame ran the TODO image's handler")
      (is (= :counter (:booted-by (rf/app-db-value :counter/main)))
          "the counter frame ran the COUNTER image's handler")
      (testing "neither image leaked into the other (no global clobber)"
        (is (not= :global (:booted-by (rf/app-db-value :todo/main))))
        (is (not= :global (:booted-by (rf/app-db-value :counter/main))))))))

;; ===========================================================================
;; 4. ABSENCE-IS-DEFAULT — a frame with NO image-loaded object resolves through
;;    the global registrar EXACTLY as before (every realm-only / single-realm
;;    frame is byte-identical).
;; ===========================================================================

(deftest no-image-frame-resolves-through-the-global-registrar
  (testing "a runnable frame with NO live-frame image OBJECT (a realm-only /
            EP-0013 frame) resolves a frame-targeted dispatch + subscribe through
            the GLOBAL registrar unchanged — the load-bearing absence-is-default
            fall-through that keeps every existing caller byte-identical"
    (rf/reg-frame :plain/main {:doc "no image-loaded object for this frame"})
    (rf/reg-event :plain/set
      (fn [{:keys [db]} _] {:db (assoc db :written-by :global)}))
    (rf/reg-sub :plain/value (fn [db _] (:written-by db)))
    ;; NO `lf/make-frame` — the frame names no image-loaded object.
    (rf/dispatch-sync [:plain/set] {:frame :plain/main})
    (is (= :global (:written-by (rf/app-db-value :plain/main)))
        "with no image generation, the dispatch resolved the GLOBAL handler")
    (is (= :global @(rf/subscribe :plain/main [:plain/value]))
        "with no image generation, the subscribe resolved the GLOBAL sub")
    (is (nil? registrar/*generation*)
        "no generation was ever bound for an image-less frame")))

;; ===========================================================================
;; 5. CHILD DISPATCH coherence — a child dispatch emitted from inside the
;;    image handler's :fx re-enters process-event! for the SAME frame and
;;    re-derives the generation, so it ALSO resolves through the frame's image.
;; ===========================================================================

(deftest child-dispatch-stays-in-the-frames-image
  (testing "a child [:counter/step] dispatched via :fx from the image's
            [:counter/inc] handler re-enters process-event! for :counter/main
            and re-derives the generation — so it ALSO resolves through the
            frame's image (the cascade stays coherent across child dispatches)"
    (rf/reg-frame :counter/main {:doc "image-loaded counter frame"})
    ;; GLOBAL versions both children should NEVER run.
    (rf/reg-event :counter/inc  (fn [{:keys [db]} _] {:db (assoc db :inc :global)}))
    (rf/reg-event :counter/step (fn [{:keys [db]} _] {:db (assoc db :step :global)}))
    (let [pool [(event-desc "examples.counter" :counter/inc
                            ;; image inc: write its own marker, then dispatch the
                            ;; child step via :fx (the fx-walker threads the frame).
                            (fn [{:keys [db]} _]
                              {:db (assoc db :inc :image)
                               :fx [[:dispatch [:counter/step]]]}))
                (event-desc "examples.counter" :counter/step
                            (fn [{:keys [db]} _] {:db (assoc db :step :image)}))]
          img  (image/image {:id :examples/counter :include-ns ["examples.counter"]})
          _    (lf/make-frame {:id :counter/main :images [img]} pool)]
      (rf/dispatch-sync [:counter/inc] {:frame :counter/main})
      (let [db (rf/app-db-value :counter/main)]
        (is (= :image (:inc db))  "the parent resolved the image's inc handler")
        (is (= :image (:step db))
            "the CHILD dispatch re-derived the generation and resolved the
             image's step handler too (coherent across the cascade)")))))
