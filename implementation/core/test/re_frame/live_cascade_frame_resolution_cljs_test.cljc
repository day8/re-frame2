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

  ## One runnable image-loaded frame (EP-0024 §One live frame registry, rf2-tu2vr7)

  `make-frame` returns a SINGLE runnable image-loaded frame VALUE: it
  creates/updates its backing runnable record (app-db / queue / sub-cache /
  lifecycle, via `reg-frame`) keyed by the frame's runnable-id, and the resolved
  image GENERATION lives ON that record (the `:generation` slot), so an event
  stream runs against an image with no separate `reg-frame` pairing. EP-0024
  collapsed the two-registry model to ONE: dispatch / subscribe re-derive the
  generation from the record by id (a frame VALUE and its id resolve the same
  generation). The cases below still call `reg-frame` first (harmless —
  `make-frame {:id :counter/main}` idempotently updates the same record); the
  router drains that record and `process-event!` derives the generation from the
  record of the same id. The `two-frames-from-one-image-keep-…` test exercises
  the collapse directly — two RUNNABLE values built from ONE image keep
  INDEPENDENT app-db + sub-cache, no `reg-frame` in sight.

  Fixtures snapshot/restore the registrar via `make-reset-runtime-fixture`
  (NOT `registrar/clear-all!`, per the .9 isolation note) and clear the
  process-local live-frame registry between cases. `.cljc` ending `-cljs-test`
  rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.events         :as events]
            [re-frame.image          :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.registrar      :as registrar]
            [re-frame.live-frame     :as lf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support   :as test-support]))

;; ---------------------------------------------------------------------------
;; Fixture: install the plain-atom adapter (so frames are runnable), opt OUT of
;; the ambient `:rf/default` scope (we drive explicit `{:frame …}` targets). The
;; runtime fixture resets the ONE `frame/frames` registry between cases — clearing
;; every record AND its generation — so an `:id` from one case does not collide
;; with the next (no separate live-frame index to clear, rf2-ji3tvy).
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter        plain-atom/adapter
                                            :ambient-frame  nil}))

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
  "An image-resolver descriptor for a LAYER-1 (app-db reader) sub id whose
  computation is `compute-fn` (`(fn [db query-v] …)`). `reg-sub` stores the
  computation under `:handler-fn` and stamps `:input-kind :db` so the sub-cache
  feeds it the frame's app-db projection as the single signal; a generation-
  routed `registrar/lookup :sub` returns this verbatim, so the descriptor must
  carry `:input-kind` to be recognized as a layer-1 reader (a sub that ignores
  `db` works without it, but one that READS app-db needs the discriminator)."
  [provenance-ns id compute-fn]
  {:rf.provenance/ns provenance-ns
   :kind             :sub
   :id               id
   :input-kind       :db
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
          img   (image/image {:id :examples/counter :select-ns {:include ["examples.counter"]}})
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
          img   (image/image {:id :examples/counter :select-ns {:include ["examples.counter"]}})
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
          todo-img     (image/image {:id :examples/todo    :select-ns {:include ["examples.todo"]}})
          counter-img  (image/image {:id :examples/counter :select-ns {:include ["examples.counter"]}})
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
          img  (image/image {:id :examples/counter :select-ns {:include ["examples.counter"]}})
          _    (lf/make-frame {:id :counter/main :images [img]} pool)]
      (rf/dispatch-sync [:counter/inc] {:frame :counter/main})
      (let [db (rf/app-db-value :counter/main)]
        (is (= :image (:inc db))  "the parent resolved the image's inc handler")
        (is (= :image (:step db))
            "the CHILD dispatch re-derived the generation and resolved the
             image's step handler too (coherent across the cascade)")))))

;; ===========================================================================
;; 6. THE COLLAPSE HEADLINE (EP-0023 collapse slice 1, rf2-32siq3.32) — two
;;    RUNNABLE objects built from ONE image keep INDEPENDENT app-db + sub-cache.
;;    This is the previously-impossible `.31 blocker-1` proof: before the
;;    collapse a `make-frame` object had NO runnable state, so it could not run
;;    an event stream at all without a paired `reg-frame` record. Now ONE object
;;    carries both the image generation AND its own runnable record.
;; ===========================================================================

(deftest two-frames-from-one-image-keep-independent-state
  (testing "two runnable frames built from the SAME image — NO reg-frame, NO
            shared id — maintain INDEPENDENT app-db and sub-cache: each frame's
            event stream mutates only its own state, and each frame's subscribe
            builds its own reaction (EP-0023 §Frame — the live frame object owns
            app-db + subscription cache; two frames that run the same generation
            still have independent state)"
    ;; ONE image carries the runnable inc handler + the value sub. A GLOBAL
    ;; version of each exists only so we can prove the image's impl ran (not the
    ;; global) — neither frame is paired with a reg-frame.
    (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (assoc db :n :global)}))
    (rf/reg-sub   :counter/value (fn [_db _] :global))
    (let [pool [(event-desc "ex.counter" :counter/inc
                            (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
                (sub-desc   "ex.counter" :counter/value (fn [db _] (:n db)))]
          img  (image/image {:id :ex/counter :select-ns {:include ["ex.counter"]}})
          ;; TWO direct (no-id) runnable objects from the SAME image, seeded with
          ;; DIFFERENT initial-db. No reg-frame, no shared frame id.
          fa   (lf/make-frame {:images [img] :initial-events [[:rf/set-db {:n 0}]]}   pool)
          fb   (lf/make-frame {:images [img] :initial-events [[:rf/set-db {:n 100}]]} pool)]
      ;; The objects are distinct runnable frames sharing one image generation.
      (is (lf/frame-object? fa))
      (is (lf/frame-object? fb))
      (is (= (lf/frame-generation fa) (lf/frame-generation fb))
          "both frames run the SAME resolved image generation (shared value)")
      (is (not= (:rf.frame/runnable-id fa) (:rf.frame/runnable-id fb))
          "yet they are distinct runnable frames (distinct backing records)")
      ;; Independent app-db: divergent event streams over divergent seeds. The
      ;; frame OBJECT is the dispatch target via the `{:frame …}` opt (the
      ;; canonical object-accepting form — build-envelope normalizes the object
      ;; to its runnable-id). The positional `(rf/dispatch frame event)` sugar is
      ;; also exposed on the facade (slice 2, rf2-32siq3.32) — exercised in the
      ;; `frame-first-positional-dispatch-forms` test below.
      (rf/dispatch-sync [:counter/inc] {:frame fa})
      (rf/dispatch-sync [:counter/inc] {:frame fa})
      (rf/dispatch-sync [:counter/inc] {:frame fb})
      (is (= 2 (:n (rf/app-db-value fa)))
          "frame A: seeded 0, inc'd twice — the IMAGE handler ran on A's OWN app-db")
      (is (= 101 (:n (rf/app-db-value fb)))
          "frame B: seeded 100, inc'd once — fully isolated from A")
      (is (not= :global (:n (rf/app-db-value fa)))
          "the IMAGE inc ran, not the global (generation routed to the frame's image)")
      ;; Independent subscribe: each frame builds its OWN reaction over its OWN
      ;; app-db, and the two reactions are NOT the same cached object.
      (let [ra  (rf/subscribe fa [:counter/value])
            ra2 (rf/subscribe fa [:counter/value])
            rb  (rf/subscribe fb [:counter/value])]
        (is (= 2 @ra)   "frame A's sub reads A's own app-db")
        (is (= 101 @rb) "frame B's sub reads B's own app-db")
        (is (identical? ra ra2)
            "A's repeat subscribe HITS A's own sub-cache (same reaction)")
        (is (not (identical? ra rb))
            "A and B build DISTINCT reactions — independent sub-caches")))))

;; ===========================================================================
;; 7. DIRECT-OBJECT RUNNABILITY (the spec harness form) — a no-id object is
;;    runnable end-to-end without any reg-frame pairing (EP-0023 §Frame).
;; ===========================================================================

(deftest direct-no-id-object-is-runnable-end-to-end
  (testing "the spec's local-harness form: a no-id frame OBJECT is dispatched +
            subscribed directly (object via the `{:frame …}` opt for dispatch and
            as the 2-arity subscribe target) and runs the image's handlers
            against the object's OWN runnable state — NO reg-frame, NO frame id
            (EP-0023 §Frame — a direct object is a local reference a harness uses
            directly)"
    (let [pool  [(event-desc "ex.counter" :counter/inc
                             (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
                 (sub-desc   "ex.counter" :counter/value (fn [db _] (:n db)))]
          img   (image/image {:select-ns {:include ["ex.counter"]}})
          frame (lf/make-frame {:images [img] :initial-events [[:rf/set-db {:n 0}]]} pool)]
      (rf/dispatch-sync [:counter/inc] {:frame frame})
      (is (= 1 @(rf/subscribe frame [:counter/value]))
          "the direct object ran the image's inc and read its own seeded app-db")
      (testing "app-db-value + frame-state-value accept the object directly"
        (is (= 1 (:n (rf/app-db-value frame))))
        (is (= {:n 1} (:rf.db/app (rf/frame-state-value frame)))))
      (testing "destroy-frame! accepts the object and tears its record down"
        (rf/destroy-frame! frame)
        (is (nil? (rf/app-db-value frame))
            "after destroy the object's backing record is gone")))))

;; ===========================================================================
;; 8. FRAME-FIRST POSITIONAL DISPATCH (EP-0023 collapse slice 2, rf2-32siq3.32)
;;    — the public-facade `(rf/dispatch-sync frame [...])` / `(rf/dispatch frame
;;    [...])` sugar routes the carried frame TARGET (a frame-id keyword OR a live
;;    frame OBJECT), discriminated from the event-first `(dispatch [...] opts)`
;;    form purely by the FIRST arg's shape (an event-vec is always a vector; a
;;    frame target never is). Mirrors the 2-arity `(rf/subscribe frame [...])`.
;; ===========================================================================

(deftest frame-first-positional-dispatch-forms
  (testing "EP-0023 §Public API: (rf/dispatch-sync frame [event]) and
            (rf/dispatch frame [event]) target the carried frame — for BOTH a
            live frame OBJECT and a frame-id keyword — END-TO-END through the
            target frame's image, identically to the {:frame …} opt form"
    (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (assoc db :n :global)}))
    (rf/reg-sub   :counter/value (fn [_db _] :global))
    (let [pool [(event-desc "ex.counter" :counter/inc
                            (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
                (sub-desc   "ex.counter" :counter/value (fn [db _] (:n db)))]
          img  (image/image {:id :ex/counter :select-ns {:include ["ex.counter"]}})]
      (testing "frame OBJECT as the FIRST positional dispatch arg"
        (let [obj (lf/make-frame {:images [img] :initial-events [[:rf/set-db {:n 0}]]} pool)]
          ;; frame-first: object is arg-1, event-vec is arg-2.
          (rf/dispatch-sync obj [:counter/inc])
          (rf/dispatch-sync obj [:counter/inc])
          (is (= 2 (:n (rf/app-db-value obj)))
              "the IMAGE inc ran twice on the object's OWN app-db (not the global)")
          (is (= 2 @(rf/subscribe obj [:counter/value]))
              "the 2-arity object-target subscribe reads the same app-db")))
      (testing "frame-id KEYWORD as the FIRST positional dispatch arg"
        (let [_ (lf/make-frame {:id :counter/main :images [img] :initial-events [[:rf/set-db {:n 10}]]}
                               pool)]
          (rf/dispatch-sync :counter/main [:counter/inc])
          (is (= 11 (:n (rf/app-db-value :counter/main)))
              "the keyword frame-first form routes to the registered live frame")))
      (testing "the async (queued) (rf/dispatch frame [event]) form also routes"
        (let [obj (lf/make-frame {:images [img] :initial-events [[:rf/set-db {:n 0}]]} pool)]
          ;; `dispatch` enqueues; drain synchronously via a frame-first sync follow-up
          (rf/dispatch obj [:counter/inc])
          (rf/dispatch-sync obj [:counter/inc])   ;; drains the queue + runs once more
          (is (= 2 (:n (rf/app-db-value obj)))
              "both the queued and the sync frame-first dispatches ran the image inc")))
      (testing "the event-first (dispatch [event] {:frame …}) form is unaffected"
        (let [obj (lf/make-frame {:images [img] :initial-events [[:rf/set-db {:n 0}]]} pool)]
          (rf/dispatch-sync [:counter/inc] {:frame obj})
          (is (= 1 (:n (rf/app-db-value obj)))
              "event-first opts form still routes the object target byte-identically"))))))

;; ===========================================================================
;; 8b. INLINE :registrations FN-BODY ROUTES THROUGH DISPATCH (rf2-ffc6s0)
;;     — the EP-0023 gap: an image built from inline `:registrations` with a
;;     REAL fn body must wire that handler into the dispatch / subscribe /
;;     fx / cofx path identically to a `:include-ns`-selected registration.
;;     Before the fix the inline body lowered ONLY to `:impl` (inert data),
;;     so a frame-targeted dispatch resolved the descriptor but ran nothing
;;     (`registrar/handler` reads `:handler-fn`, an event needs `:interceptors`).
;;     EP-0023 §Image Fragments: "Both paths should lower to the same runtime
;;     descriptor shape."
;; ===========================================================================

(deftest inline-registrations-event-fn-body-runs-through-dispatch
  (testing "an image built from INLINE :registrations with a REAL event fn body
            runs that handler END-TO-END under a frame-targeted dispatch — the
            inline body lowers to the same runnable descriptor shape a
            :include-ns-selected handler carries (rf2-ffc6s0)"
    (rf/reg-frame :inline/main {:doc "inline-image counter frame"})
    ;; A GLOBAL handler the cascade would (wrongly) run if it resolved through
    ;; the global registrar — present so the assertion proves the IMAGE ran.
    (rf/reg-event :counter/inc
      (fn [{:keys [db]} _] {:db (assoc db :written-by :global)}))
    (let [img (image/image
                {:id :inline/counter
                 :registrations
                 {:reg-event [[:counter/inc {:doc "Increment via inline body."}
                               (fn [{:keys [db]} _]
                                 {:db (assoc db :written-by :inline :hit true)})]]}})]
      ;; No explicit pool needed — inline descriptors are selected because the
      ;; image was supplied, not from the source store.
      (lf/make-frame {:id :inline/main :images [img]} [])
      (rf/dispatch-sync [:counter/inc] {:frame :inline/main})
      (let [db (rf/app-db-value :inline/main)]
        (is (true? (:hit db))
            "the INLINE fn body actually executed — app-db mutated")
        (is (= :inline (:written-by db))
            "the inline body ran, not the global handler"))
      (is (nil? registrar/*generation*)
          "the generation binding did NOT leak past the cascade"))))

(deftest inline-registrations-sub-fn-body-runs-through-subscribe
  (testing "an image built from INLINE :registrations with a REAL layer-1 sub
            fn body computes that sub END-TO-END under a frame-targeted
            subscribe (rf2-ffc6s0)"
    (rf/reg-frame :inline/main {:doc "inline-image counter frame"})
    (rf/reg-sub :counter/value (fn [_db _] :global))
    (let [img (image/image
                {:id :inline/counter
                 :registrations
                 {:reg-event [[:counter/inc {}
                               (fn [{:keys [db]} _]
                                 {:db (update db :count (fnil inc 0))})]]
                  :reg-sub   [[:counter/value {:doc "Current counter value."}
                               (fn [db _] (:count db 0))]]}})]
      (lf/make-frame {:id :inline/main :images [img] :initial-events [[:rf/set-db {:count 0}]]} [])
      (rf/dispatch-sync [:counter/inc] {:frame :inline/main})
      (rf/dispatch-sync [:counter/inc] {:frame :inline/main})
      (is (= 2 @(rf/subscribe :inline/main [:counter/value]))
          "the INLINE sub body computed over the frame's own app-db, not the
           global sub")
      (is (nil? registrar/*generation*)
          "the generation binding did NOT leak past the build"))))

(deftest inline-registrations-fx-fn-body-runs-through-cascade
  (testing "an image built from INLINE :registrations with a REAL fx fn body
            runs that effect END-TO-END when an inline event handler emits it
            (rf2-ffc6s0)"
    (rf/reg-frame :inline/main {:doc "inline-image fx frame"})
    (let [fired (atom [])
          img (image/image
                {:id :inline/fx
                 :registrations
                 {:reg-event [[:do/it {}
                               (fn [_ _] {:fx [[:my/side-effect {:n 7}]]})]]
                  :reg-fx    [[:my/side-effect {}
                               (fn [_ctx args] (swap! fired conj args))]]}})]
      (lf/make-frame {:id :inline/main :images [img]} [])
      (rf/dispatch-sync [:do/it] {:frame :inline/main})
      (is (= [{:n 7}] @fired)
          "the INLINE fx handler ran — its fn body executed with the emitted args"))))

;; ===========================================================================
;; 9. rf/reload-images! ON THE FACADE (EP-0023 collapse slice 2, rf2-32siq3.32)
;;    — the public hot-reload export swaps a frame's whole image composition
;;    while PRESERVING FRAME MEMORY (app-db continues; only the generation moves)
;;    and returns the reload report. Reachable as `rf/reload-images!`.
;; ===========================================================================

(deftest reload-images-on-the-facade-swaps-generation-preserving-memory
  (testing "EP-0023 §Hot Reload / §Public API: rf/reload-images! re-assembles the
            new :images into a fresh generation and swaps it onto the live frame
            WITHOUT tearing it down — app-db (frame memory) continues, and the
            target frame's subsequent dispatch resolves through the NEW image"
    (let [;; v1 image: inc by 1. v2 image: inc by 10 (same id, different impl).
          pool-v1 [(event-desc "ex.counter.v1" :counter/inc
                               (fn [{:keys [db]} _] {:db (update db :n (fnil + 0) 1)}))
                   (sub-desc   "ex.counter.v1" :counter/value (fn [db _] (:n db)))]
          pool-v2 [(event-desc "ex.counter.v2" :counter/inc
                               (fn [{:keys [db]} _] {:db (update db :n (fnil + 0) 10)}))
                   (sub-desc   "ex.counter.v2" :counter/value (fn [db _] (:n db)))]
          img-v1 (image/image {:id :ex/counter-v1 :select-ns {:include ["ex.counter.v1"]}})
          img-v2 (image/image {:id :ex/counter-v2 :select-ns {:include ["ex.counter.v2"]}})
          frame  (lf/make-frame {:id :counter/main :images [img-v1] :initial-events [[:rf/set-db {:n 0}]]}
                                pool-v1)]
      ;; Run the v1 image once: n 0 -> 1.
      (rf/dispatch-sync frame [:counter/inc])
      (is (= 1 (:n (rf/app-db-value :counter/main))) "v1 inc by 1")
      ;; HOT RELOAD via the FACADE export — swap the whole composition to v2.
      (let [report (rf/reload-images! :counter/main {:images [img-v2]} pool-v2)]
        (is (map? report) "reload-images! returns a report map")
        (is (contains? report :rf.frame/frame) "report carries the reloaded object")
        (is (contains? report :rf.reload/diff) "report carries the generation diff")
        (is (lf/frame-object? (:rf.frame/frame report))))
      ;; FRAME MEMORY PRESERVED: app-db still holds the v1-computed value.
      (is (= 1 (:n (rf/app-db-value :counter/main)))
          "app-db (frame memory) survived the reload — not torn down + recreated")
      ;; The frame now runs the v2 image: inc by 10.
      (rf/dispatch-sync :counter/main [:counter/inc])
      (is (= 11 (:n (rf/app-db-value :counter/main)))
          "after reload the SAME live frame runs the v2 image's inc (1 + 10)")))
  (testing "rf/reload-images! also targets a direct frame OBJECT and a bad target
            fails loud"
    (let [pool [(event-desc "ex.r" :r/noop (fn [{:keys [db]} _] {:db db}))]
          img  (image/image {:select-ns {:include ["ex.r"]}})
          obj  (lf/make-frame {:images [img] :initial-events [[:rf/set-db {:n 7}]]} pool)
          report (rf/reload-images! obj {:images [img]} pool)]
      (is (lf/frame-object? (:rf.frame/frame report))
          "a direct (no-id) object's reloaded copy is returned in the report")
      (is (= :rf.error/reload-no-such-frame
             (try (rf/reload-images! :no/such-live-frame {:images [img]} pool)
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                    (:rf.error/id (ex-data e)))))
          "an unknown frame id fails loud through the facade export"))))

;; ===========================================================================
;; 10. NO-ID FRAME RELOAD IS REGISTRY-COHERENT (rf2-3az1vn P1) — reloading a
;;     DIRECT (no public :id) frame object must patch the live-frame registry
;;     slot keyed by the object's PRIVATE runnable-id. Dispatch / subscribe
;;     collapse a frame object to its runnable-id keyword and then RE-RESOLVE the
;;     generation from the registry (router/frame-resolution-target reads
;;     (live-frame runnable-id)) — so if the slot still pointed at the OLD object
;;     after a reload, a dispatch / subscribe / child dispatch through the
;;     reloaded object would silently resolve the STALE v1 image. The old
;;     swap-frame-generation! `(when (some? id) …)` guard skipped no-id frames
;;     (their public id is nil), leaving the gensym slot stale; the fix keys the
;;     in-place update by the runnable-id (present on every object) under the same
;;     identical? stale-handle guard.
;; ===========================================================================

(deftest no-id-reload-patches-the-runnable-id-registry-slot
  (testing "(a) (live-frame (:rf.frame/runnable-id reloaded)) carries v2 after a
            no-id reload — the registry slot dispatch/subscribe re-resolve through
            points at the reloaded object, not the stale v1 one (rf2-3az1vn P1)"
    (let [pool-v1 [(event-desc "ex.counter.v1" :counter/inc
                               (fn [{:keys [db]} _] {:db (update db :n (fnil + 0) 1)}))
                   (sub-desc   "ex.counter.v1" :counter/value (fn [db _] (:n db)))]
          pool-v2 [(event-desc "ex.counter.v2" :counter/inc
                               (fn [{:keys [db]} _] {:db (update db :n (fnil + 0) 10)}))
                   (sub-desc   "ex.counter.v2" :counter/value (fn [db _] (:n db)))]
          img-v1  (image/image {:select-ns {:include ["ex.counter.v1"]}})
          img-v2  (image/image {:select-ns {:include ["ex.counter.v2"]}})
          ;; NO :id — a direct object keyed in the registry under a gensym
          ;; runnable-id only.
          frame   (lf/make-frame {:images [img-v1] :initial-events [[:rf/set-db {:n 0}]]} pool-v1)
          runnable (:rf.frame/runnable-id frame)
          reloaded (:rf.frame/frame (rf/reload-images! frame {:images [img-v2]} pool-v2))]
      (is (some? runnable) "a no-id object carries a private runnable-id")
      (is (not-any? #(not= "rf.frame" (namespace %)) (lf/live-frame-ids))
          "but contributes no PUBLIC id — its private :rf.frame/<gensym> id is
           EXCLUDED from live-frame-ids (no-id frames bypass enumeration /
           auto-reprojection; the owner reloads them explicitly — EP-0024)")
      (is (= runnable (rf/frame-value->id (lf/live-frame runnable)))
          "(a) the runnable-id registry slot now resolves the RELOADED v2 frame
           (EP-0024: live-frame reconstructs a fresh value from the record;
           compare by id)")
      (is (= :counter/inc (:id (asm/resolve-descriptor
                                 (lf/frame-generation (lf/live-frame runnable))
                                 :event :counter/inc)))
          "and (live-frame runnable-id) resolves the v2 generation's descriptor"))))

(deftest no-id-reload-dispatch-and-subscribe-resolve-v2 ;; (b)
  (testing "(b) a REAL dispatch + subscribe through the reloaded no-id object
            resolve the v2-only registration — they collapse the object to its
            runnable-id and re-resolve from the (now-patched) registry slot
            (rf2-3az1vn P1)"
    (let [;; v1 has NO :counter/bump; v2 adds it. So resolving the v2-only id at
          ;; all proves the reloaded generation (not the stale v1) is in force.
          pool-v1 [(event-desc "ex.c.v1" :counter/inc
                               (fn [{:keys [db]} _] {:db (assoc db :gen :v1)}))
                   (sub-desc   "ex.c.v1" :counter/which (fn [db _] (:gen db)))]
          pool-v2 [(event-desc "ex.c.v2" :counter/inc
                               (fn [{:keys [db]} _] {:db (assoc db :gen :v2)}))
                   (event-desc "ex.c.v2" :counter/bump ;; v2-ONLY event
                               (fn [{:keys [db]} _] {:db (assoc db :bumped true)}))
                   (sub-desc   "ex.c.v2" :counter/which (fn [db _] (:gen db)))]
          img-v1  (image/image {:select-ns {:include ["ex.c.v1"]}})
          img-v2  (image/image {:select-ns {:include ["ex.c.v2"]}})
          frame   (lf/make-frame {:images [img-v1] :initial-events [[:rf/set-db {}]]} pool-v1)
          reloaded (:rf.frame/frame (rf/reload-images! frame {:images [img-v2]} pool-v2))]
      ;; Dispatch the SHARED id through the reloaded object: it must run v2's impl.
      (rf/dispatch-sync reloaded [:counter/inc])
      (is (= :v2 (:gen (rf/app-db-value reloaded)))
          "dispatch through the reloaded object ran the v2 :counter/inc handler")
      ;; Dispatch the V2-ONLY id: it resolves only if the registry slot is v2.
      (rf/dispatch-sync reloaded [:counter/bump])
      (is (true? (:bumped (rf/app-db-value reloaded)))
          "the v2-only :counter/bump resolved + ran — the runnable-id slot is v2")
      ;; Subscribe through the reloaded object: the v2 sub computes over v2 app-db.
      (is (= :v2 @(rf/subscribe reloaded [:counter/which]))
          "subscribe through the reloaded object resolved the v2 sub")
      (testing "dispatching through the OLD pre-reload object handle ALSO resolves
                v2 — both handles collapse to the same runnable-id, whose slot is
                now the reloaded generation (no stale-handle divergence)"
        (rf/dispatch-sync frame [:counter/bump])
        (is (true? (:bumped (rf/app-db-value frame)))
            "the v2-only id resolved through the original handle too")))))

(deftest no-id-reload-child-dispatch-resolves-v2 ;; (c)
  (testing "(c) a CHILD :dispatch emitted from the reloaded no-id frame's handler
            re-enters process-event! for the SAME runnable-id, re-derives the
            generation from the (patched) registry slot, and resolves the v2-only
            child — proving the whole cascade stays on the reloaded image
            (rf2-3az1vn P1)"
    (let [;; v1 parent has no child wiring. v2 parent emits a v2-only child via :fx.
          pool-v1 [(event-desc "ex.cd.v1" :counter/inc
                               (fn [{:keys [db]} _] {:db (assoc db :parent :v1)}))]
          pool-v2 [(event-desc "ex.cd.v2" :counter/inc
                               (fn [{:keys [db]} _]
                                 {:db (assoc db :parent :v2)
                                  :fx [[:dispatch [:counter/child]]]}))
                   (event-desc "ex.cd.v2" :counter/child ;; v2-ONLY child
                               (fn [{:keys [db]} _] {:db (assoc db :child :v2)}))]
          img-v1  (image/image {:select-ns {:include ["ex.cd.v1"]}})
          img-v2  (image/image {:select-ns {:include ["ex.cd.v2"]}})
          frame   (lf/make-frame {:images [img-v1] :initial-events [[:rf/set-db {}]]} pool-v1)
          reloaded (:rf.frame/frame (rf/reload-images! frame {:images [img-v2]} pool-v2))]
      (rf/dispatch-sync reloaded [:counter/inc])
      (let [db (rf/app-db-value reloaded)]
        (is (= :v2 (:parent db)) "the parent resolved the v2 :counter/inc handler")
        (is (= :v2 (:child db))
            "the CHILD :dispatch re-derived the generation from the patched
             runnable-id slot and resolved the v2-only :counter/child handler")))))
