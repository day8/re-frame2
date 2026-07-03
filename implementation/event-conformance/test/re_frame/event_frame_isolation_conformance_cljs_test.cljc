(ns re-frame.event-frame-isolation-conformance-cljs-test
  "EP-0023 IMAGE-LOADED FRAME DISPATCH ISOLATION conformance (rf2-slvzn3, review
  wave rf2-ks67un).

  The umbrella REGRESSION LOCK that LIVE event dispatch through an EP-0023
  image-loaded frame resolves, runs, and COMMITS only that frame's own handler
  and app-db — through the PUBLIC `rf/dispatch-sync` path, NOT a manual
  `*generation*` binding and NOT a `handler-meta` query.

  > image -> frame -> event stream

  ## Why this tier, and why it is DISTINCT from the frame/query READ lock

  Core's `re-frame.facade-frame-read-cljs-test` (EP-0023, rf2-wkw8na) locks the
  frame-targeted READ contract for the ONE `reg-event` form — `rf/handler-meta`
  / `rf/registrations` / `rf/handler-ids` `{:frame …}` resolving each frame's
  OWN sealed image generation — but it asserts resolution ONLY through those
  QUERIES, never a live dispatch. (Post-EP-0023/EP-0024 there is no realm
  routing: the multi-realm substrate collapsed, rf2-afdlyr / rf2-tu2vr7, and the
  read grammar is frame-targeted over a live frame's image generation.) That
  leaves a gap the review wave (rf2-ks67un) named: a regression where LIVE event
  dispatch falls back to the DEFAULT/GLOBAL registrar — while the frame-targeted
  QUERIES still resolve the per-frame handler correctly — would pass that surface
  GREEN. The generation-routing seam (`router/process-event!` wrapping the
  cascade in `live-frame/call-with-frame-resolution`, rf2-uejnt3) is exactly what
  such a query-only suite cannot see.

  This suite closes that gap at the conformance tier: it drives a REAL
  `rf/dispatch-sync` through two EP-0023 image-loaded frames whose resolved image
  generations carry the SAME event id with DIFFERENT handlers, then asserts the
  observable RUNTIME state (each frame's committed app-db) — so a fall-back to
  the global registrar turns it RED. EP-0023 collapse slice 1 (rf2-32siq3.32)
  made this testable: `make-frame` now returns a SINGLE runnable image-loaded
  frame OBJECT carrying both the resolved image generation AND its own backing
  runnable record (app-db / queue / sub-cache), so an event stream runs against
  an image with no separate `reg-frame` pairing.

  The in-core `re-frame.live-run-frame-resolution-cljs-test` carries the
  narrow per-feature acceptance; THIS tier is the ADVERSARIAL umbrella, shaped so
  every assertion FAILS CLOSED:

    - a GLOBAL handler/sub registered for the SAME id is a sentinel that the
      assertions reject (`(not= :global …)`), so a dispatch that resolved through
      the default registrar (the named regression) is DETECTED, not skipped;
    - the two frames carry GENUINELY different handlers, and each frame's app-db
      is asserted to hold ONLY its own handler's write — a cross-frame bleed,
      a shared mutable app-db, or one handler running for both turns it RED;
    - the `:fx` child dispatch is asserted to STAY in the frame's image (the
      ALL-OR-NOTHING generation scope), so a child that re-resolved against the
      global registrar turns it RED;
    - the absence-is-default leg dispatches with NO image-loaded frame and
      asserts the GLOBAL handler ran AND `registrar/*generation*` is nil — a
      generation that leaked, or absence that stopped falling through, turns it
      RED.

  Every fail-loud assertion branches on the observable runtime VALUE (committed
  app-db, the resolution sentinel), never message bytes.

  ## Fixture posture

  The plain-atom adapter is installed so `make-frame`'s backing runnable record
  is created (rf2-32siq3.32); `:ambient-frame nil` OPTS OUT of the conventional
  `:rf/default` scope because every case drives an EXPLICIT `{:frame …}` target
  (an ambient scope would mask a frame-target-resolution regression). The
  registrar is snapshot/restored via `make-reset-runtime-fixture` (NOT
  `clear-all!`, which would destroy framework ns-load registrations a sibling ns
  depends on); the ONE `frame/frames` registry is reset between cases — clearing
  every record AND its generation — so an `:id` from one case does not collide
  with the next (no separate live-frame index to clear, rf2-ji3tvy).

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`.

  Canonical contract: EP-0023 §Frame / §Frame-derived live registration
  resolution / §Independent Surfaces On One Page / §Public API +
  spec/002-Frames.md §Event handlers."
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
;; Fixture
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter       plain-atom/adapter
                                            :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; Helpers — build a RUNNABLE image-resolver descriptor for an event / sub id.
;;
;; The descriptor an image's `:rf.gen/resolver` carries is the SAME shape
;; `register!` stores: an event is `events/event-handler-meta` (handler-fn + the
;; framework wrapper chain), merged with the provenance / kind / id keys image
;; assembly groups + dedupes by; a layer-1 sub carries `:input-kind :db` so the
;; sub-cache feeds it the frame's app-db projection. A generation-routed
;; `registrar/lookup` returns each verbatim, so the cascade runs it directly.
;; ---------------------------------------------------------------------------

(defn- event-desc
  [provenance-ns id handler-fn]
  (merge (events/event-handler-meta handler-fn)
         {:rf.provenance/ns provenance-ns
          :kind             :event
          :id               id}))

(defn- sub-desc
  [provenance-ns id compute-fn]
  {:rf.provenance/ns provenance-ns
   :kind             :sub
   :id               id
   :input-kind       :db
   :handler-fn       compute-fn})

;; ===========================================================================
;; (1) THE HEADLINE — two image-loaded frames, the SAME event id, DIFFERENT
;;     handlers: a REAL dispatch-sync into each runs ITS OWN handler and commits
;;     ONLY its own app-db. The default/global registrar is NOT used for either.
;; ===========================================================================

(deftest two-image-frames-same-id-different-handlers-dispatch-in-isolation
  (testing "EP-0023 §Frame-derived live registration resolution / §Independent
            Surfaces On One Page: two EP-0023 image-loaded frames whose resolved
            image generations carry the SAME event id with DIFFERENT handlers,
            driven through the PUBLIC `rf/dispatch-sync` path, each run their OWN
            handler and commit ONLY their own app-db. A GLOBAL handler for the
            same id is a SENTINEL — if the live cascade fell back to the
            default/global registrar (the named regression), it would write
            `:global` and the `(not= :global …)` assertions would FAIL CLOSED,
            even though a `handler-meta` query would still resolve the per-frame
            handler"
    ;; The GLOBAL handler — the value the cascade would commit IF it (wrongly)
    ;; resolved [:boot/init] through the default/global registrar. Neither frame
    ;; must ever run it.
    (rf/reg-event :boot/init
      (fn [{:keys [db]} _] {:db (assoc db :booted-by :global)}))
    ;; Two DIFFERENT images, each carrying its OWN handler for the SAME id.
    (let [todo-pool    [(event-desc "examples.todo" :boot/init
                                    (fn [{:keys [db]} _] {:db (assoc db :booted-by :todo)}))]
          counter-pool [(event-desc "examples.counter" :boot/init
                                    (fn [{:keys [db]} _] {:db (assoc db :booted-by :counter)}))]
          todo-img     (image/image {:id :examples/todo    :select-ns {:include ["examples.todo"]}})
          counter-img  (image/image {:id :examples/counter :select-ns {:include ["examples.counter"]}})
          todo-frame    (lf/make-frame {:id :todo/main    :images [todo-img]}    todo-pool)
          counter-frame (lf/make-frame {:id :counter/main :images [counter-img]} counter-pool)]
      ;; REAL frame-targeted dispatches through the PUBLIC path — no manual
      ;; *generation* binding, no handler-meta query.
      (rf/dispatch-sync [:boot/init] {:frame :todo/main})
      (rf/dispatch-sync [:boot/init] {:frame :counter/main})
      (testing "each frame ran ITS OWN image's handler"
        (is (= :todo    (:booted-by (rf/app-db-value :todo/main)))
            "the todo frame ran the TODO image's handler end-to-end")
        (is (= :counter (:booted-by (rf/app-db-value :counter/main)))
            "the counter frame ran the COUNTER image's handler end-to-end"))
      (testing "the default/global registrar was NOT used for either targeted
                frame (FAILS CLOSED on a fall-back-to-global regression)"
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
      ;; The frame objects themselves carry GENUINELY different generations (the
      ;; query side stays coherent too — the same-id handlers are distinct).
      (testing "the two frames carry genuinely different resolved generations for
                the same id (no shared generation)"
        (is (not= (lf/frame-generation todo-frame)
                  (lf/frame-generation counter-frame))
            "the two image-loaded frames resolve the SAME id through DIFFERENT generations")))))

;; A focused companion that the global IS registered (so the `(not= :global)`
;; sentinel above is genuinely armed — a sentinel that never registered would be
;; a vacuous lock).
(deftest the-global-sentinel-is-genuinely-registered
  (testing "the GLOBAL [:boot/init] handler the isolation case relies on as its
            fall-back sentinel IS registered on the default registrar — so the
            `(not= :global …)` assertions are a REAL lock, not vacuous (a
            never-registered sentinel could never have been written, making the
            isolation assertions pass for the wrong reason)"
    (rf/reg-event :boot/init (fn [{:keys [db]} _] {:db (assoc db :booted-by :global)}))
    (is (some? (registrar/lookup :event :boot/init))
        "the global handler is on the default registrar (the armed sentinel)")
    ;; And a dispatch with NO frame image-loaded object DOES run it — proving the
    ;; sentinel is live and would have fired had the frames fallen back.
    (rf/reg-frame :plain/main {:doc "no image-loaded object"})
    (rf/dispatch-sync [:boot/init] {:frame :plain/main})
    (is (= :global (:booted-by (rf/app-db-value :plain/main)))
        "an image-less frame DOES run the global handler — the sentinel fires when reached")))

;; ===========================================================================
;; (2) TWO FRAMES SHARING ONE IMAGE — same generation, INDEPENDENT app-db: a
;;     dispatch into one mutates ONLY that frame; the sibling is untouched.
;; ===========================================================================

(deftest two-frames-sharing-one-image-keep-independent-app-db
  (testing "EP-0023 §Frame (collapse slice 1, rf2-32siq3.32): two runnable frames
            built from the SAME image share ONE resolved generation yet keep
            INDEPENDENT app-db — a dispatch into one mutates ONLY that frame's
            state, the sibling is untouched. A regression that backed both frames
            with one shared mutable app-db (or routed both dispatches to one
            record) would turn the divergent-count assertions RED"
    ;; A GLOBAL inc only so we can prove the IMAGE's inc ran (not the global).
    (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (assoc db :n :global)}))
    (let [pool [(event-desc "ex.counter" :counter/inc
                            (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))]
          img  (image/image {:id :ex/counter :select-ns {:include ["ex.counter"]}})
          ;; TWO direct (no-id) runnable objects from the SAME image, seeded with
          ;; DIFFERENT initial-db. No reg-frame, no shared frame id.
          fa   (lf/make-frame {:images [img] :initial-events [[:rf/set-db {:n 0}]]}   pool)
          fb   (lf/make-frame {:images [img] :initial-events [[:rf/set-db {:n 100}]]} pool)]
      (is (= (lf/frame-generation fa) (lf/frame-generation fb))
          "both frames run the SAME resolved image generation (one shared image)")
      (is (not= (:rf.frame/runnable-id fa) (:rf.frame/runnable-id fb))
          "yet they are DISTINCT runnable frames (distinct backing records)")
      ;; Divergent event streams over divergent seeds — the frame OBJECT is the
      ;; dispatch target via the `{:frame …}` opt (build-envelope normalizes the
      ;; object to its runnable-id).
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

;; ===========================================================================
;; (3) EFFECT ISOLATION + CHILD-DISPATCH COHERENCE — a `:fx` child dispatched
;;     from the image handler re-enters the cascade for the SAME frame and stays
;;     in THAT frame's image (the ALL-OR-NOTHING generation scope), committing
;;     only that frame's app-db.
;; ===========================================================================

(deftest child-dispatch-stays-in-the-frames-image-and-commits-only-that-frame
  (testing "EP-0023 §Frame-derived live registration resolution (ALL-OR-NOTHING):
            a child [:counter/step] dispatched via `:fx` from the image's
            [:counter/inc] handler re-enters the cascade for the SAME frame and
            re-derives the generation — so the child ALSO resolves through the
            frame's image, and the whole cascade (event + its fx child) commits
            ONLY that frame's app-db. A sibling frame on a DIFFERENT image is
            untouched. A regression where the fx child fell back to the global
            registrar (an incoherent half-dispatch) would turn the `:step :image`
            assertion RED"
    ;; GLOBAL versions both children should NEVER run.
    (rf/reg-event :counter/inc  (fn [{:keys [db]} _] {:db (assoc db :inc :global)}))
    (rf/reg-event :counter/step (fn [{:keys [db]} _] {:db (assoc db :step :global)}))
    (let [target-pool
          [(event-desc "examples.counter" :counter/inc
                       ;; image inc: write its own marker, then dispatch the child
                       ;; step via :fx (the fx-walker threads the frame).
                       (fn [{:keys [db]} _]
                         {:db (assoc db :inc :image)
                          :fx [[:dispatch [:counter/step]]]}))
           (event-desc "examples.counter" :counter/step
                       (fn [{:keys [db]} _] {:db (assoc db :step :image)}))]
          ;; A SIBLING frame on a DIFFERENT image that handles the SAME ids — a
          ;; bleed would land here. Its handlers write distinct markers.
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

;; ===========================================================================
;; (4) ABSENCE-IS-DEFAULT — a dispatch into a frame with NO image-loaded object
;;     resolves through the DEFAULT/GLOBAL registrar, byte-identical for every
;;     existing caller, and no generation is ever bound.
;; ===========================================================================

(deftest absence-is-default-no-image-frame-uses-the-global-registrar
  (testing "EP-0023 §Frame-derived live registration resolution (absence-is-
            default): a runnable frame with NO live-frame image OBJECT resolves a
            frame-targeted dispatch through the DEFAULT/GLOBAL registrar
            unchanged, and `registrar/*generation*` is NEVER bound — the
            load-bearing fall-through that keeps every existing (non-EP-0023)
            caller byte-identical. The POSITIVE counterpart to the isolation
            cases: where an image-loaded frame routes to its image, an image-less
            frame routes to the global registrar. A regression where the global
            path stopped being the default (or a generation leaked onto an
            image-less dispatch) would turn this RED"
    (rf/reg-frame :plain/main {:doc "no image-loaded object for this frame"})
    (rf/reg-event :plain/set (fn [{:keys [db]} _] {:db (assoc db :written-by :global)}))
    (rf/reg-sub  :plain/value (fn [db _] (:written-by db)))
    ;; NO `lf/make-frame` — the frame names no image-loaded object.
    (rf/dispatch-sync [:plain/set] {:frame :plain/main})
    (is (= :global (:written-by (rf/app-db-value :plain/main)))
        "with no image generation, the dispatch resolved the GLOBAL handler")
    (is (= :global @(rf/subscribe :plain/main [:plain/value]))
        "with no image generation, the subscribe resolved the GLOBAL sub")
    (is (nil? registrar/*generation*)
        "no generation was ever bound for an image-less frame")))
