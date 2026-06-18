(ns re-frame.live-frame-cljs-test
  "EP-0024 §One constructor / §One registry — the FRAME IMAGE-LOADING slice
  (rf2-tu2vr7): `rf/make-frame` is the ONE public constructor. It accepts
  `:images` (always a vector), resolves them into ONE sealed image generation,
  and returns the live frame VALUE; an `:id` registers a record in the ONE
  `frames` registry (the resolved generation lives ON that record, NOT embedded
  on the returned value), and a no-id (direct) frame value is local-only,
  bypassing the PUBLIC frame-id space.

  Pins the EP-0024 collapse coverage:

    * `:images` (a vector) resolves to a generation read by id off the record
      (`lf/frame-generation` accepts EITHER a frame value or a frame id);
    * the NO-`:images` path (absent or `[]`) runs the DEFAULT IMAGE — the
      implicit selector over the WHOLE source store (rf2-32siq3.33): the
      generation includes the store's `reg-*` descriptors (+ standards), NOT the
      framework standards alone, and a cross-namespace same-`[kind id]` collision
      in that default projection FAILS LOUD at make-frame time
      (`:rf.error/image-duplicate-id`). Covered for both the explicit-pool
      2-arity and the bare live-store 1-arity;
    * an `:id` registers an image-loaded record in the ONE registry — `lf/
      live-frame` RECONSTRUCTS a fresh value from the record (routing to the same
      id, NOT `identical?` to the originally-returned value) and the id appears in
      `lf/live-frame-ids`;
    * a duplicate live `:id` is IDEMPOTENT REPLACEMENT — re-`make-frame`-ing the
      same id does NOT throw, refreshes config + generation, and PRESERVES durable
      state (app-db / sub-cache / queue) — hot-reload / Story re-evaluation
      friendly (the old fail-loud `:rf.error/live-frame-id-conflict` is GONE);
    * a direct (no-id) frame value BYPASSES the public registry;
    * a non-vector `:images` is REJECTED (`:rf.error/make-frame-bad-images`);
    * the capability frame-boundary check (rf2-32siq3.6): fails loud on a missing
      capability; an image with requirements but NO `:capabilities` map fails
      (the check is unconditional — EP-0013 fail-loud parity); an image with no
      requirements needs no `:capabilities` map; the multi-image requires UNION
      is checked as one set at the frame boundary.

  Each fail-loud assertion checks the `:rf.error/id` discriminator (NEVER the
  message bytes — Spec 009 §The thrown-error shape rule 3).

  Most cases resolve against an explicit synthetic descriptor pool (the
  `make-frame` 2-arity) — the same decoupling idiom `image-assembly-cljs-test`
  uses. The default-image cases additionally exercise the BARE live-store 1-arity
  (`make-frame {}`), which reads the live source store; those snapshot/restore
  the store (never `clear-all!`, which would destroy real authored
  registrations). The ONE `frames` registry, the standard registry, the generation
  cache, and the source store are all process state, so the fixture
  snapshot/restores or clears them per case (`lf/clear-live-frames!` is now a
  no-op kept for fixtures — `frame/frames` is reset by the runtime fixture).
  `.cljc` ends `-cljs-test` so it rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core        :as rf]
            [re-frame.image       :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.live-frame  :as lf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.source-store   :as ss]))

;; ---------------------------------------------------------------------------
;; Fixture — the live-frame registry, the framework-standard registry, and the
;; resolved-generation cache are process-state defonce atoms; clear them per
;; case. The source store is ALSO process state (the DEFAULT-image path reads it
;; live), so SNAPSHOT/RESTORE it — do NOT `clear-all!`, which would destroy real
;; authored registrations (per the bead). The live-store default-image tests
;; mutate the store inside their own snapshot/restore body too.
;;
;; EP-0023 collapse slice 1 (rf2-32siq3.32): `make-frame` now creates a RUNNABLE
;; backing record (app-db / queue / sub-cache) via `reg-frame`, which needs a
;; substrate adapter — so the plain-atom adapter is installed (and the registrar
;; snapshot/restored) via `make-reset-runtime-fixture`. These cases still assert
;; the pure image-resolution / id-conflict / capability-check contract; the
;; backing record is an allocation side effect they do not otherwise inspect.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t]
    (let [store-before @ss/kind->id->ns->descriptor]
      (lf/clear-live-frames!)
      (asm/clear-standards!)
      (asm/clear-generation-cache!)
      (try
        (t)
        (finally
          (lf/clear-live-frames!)
          (asm/clear-standards!)
          (asm/clear-generation-cache!)
          (reset! ss/kind->id->ns->descriptor store-before))))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- reg-desc
  "A synthetic REGISTERED descriptor authored in `provenance-ns` (mirrors the
  source-store output shape the selector consumes)."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- err-id
  "The `:rf.error/id` discriminator of a thrown re-frame2 error, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- err-data
  "The full `ex-data` of a thrown re-frame2 error, or nil. The `:extra` slots
  (`:missing-capabilities` / `:supplied-capabilities`) are merged at the top
  level of the ex-data — see `re-frame.error/throw-error!`."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(def ^:private counter-pool
  [(reg-desc "examples.counter" :event :counter/inc ::inc)
   (reg-desc "examples.counter" :sub   :counter/value ::value)])

(defn- record!
  "Record one registered descriptor into the LIVE source store (the same path a
  `reg-*` walks) so the DEFAULT image — the implicit whole-store selector — can
  project it. The fixture snapshot/restores the store around the case."
  [provenance-ns kind id impl]
  (ss/record-descriptor! kind id {:ns provenance-ns :kind kind :id id
                                  :handler-fn impl}))

;; ===========================================================================
;; 1. :images (a vector) resolves to a generation carried on the frame object
;; ===========================================================================

(deftest images-vector-resolves-to-a-generation-on-the-object
  (testing "make-frame resolves a one-element :images vector into one sealed
            image generation and returns the live frame OBJECT carrying it"
    (let [img   (image/image {:id :examples/counter
                              :include-ns ["examples.counter"]})
          frame (lf/make-frame {:images [img]} counter-pool)]
      (testing "the return value is a live frame OBJECT, not a frame-id keyword"
        (is (lf/frame-object? frame))
        (is (map? frame))
        (is (not (keyword? frame))))
      (testing "the object holds a reference to its resolved generation"
        (let [gen (lf/frame-generation frame)]
          (is (some? gen))
          (is (contains? gen :rf.gen/resolver))
          ;; The selected registrations resolve through the carried generation.
          (is (some? (asm/resolve-descriptor gen :event :counter/inc)))
          (is (some? (asm/resolve-descriptor gen :sub   :counter/value))))))))

(deftest multiple-images-resolve-to-one-generation
  (testing "a multi-image :images vector composes into ONE generation (EP-0023
            §Image Composition — the frame still runs one resolved generation)"
    (let [pool  [(reg-desc "lib.widgets"   :event :widgets/init ::winit)
                 (reg-desc "examples.counter" :event :counter/inc ::inc)]
          a     (image/image {:id :lib/widgets   :include-ns ["lib.widgets"]})
          b     (image/image {:id :examples/counter :include-ns ["examples.counter"]})
          frame (lf/make-frame {:images [a b]} pool)
          gen   (lf/frame-generation frame)]
      (is (some? (asm/resolve-descriptor gen :event :widgets/init)))
      (is (some? (asm/resolve-descriptor gen :event :counter/inc))))))

;; ===========================================================================
;; 1b. The EMPTY-`:images` path (`:images []`) runs the DEFAULT IMAGE — the
;;     implicit whole-store projection (EP-0023 §Default Image Semantics,
;;     rf2-32siq3.33), NOT the framework standard set alone. EP-0024
;;     (rf2-tu2vr7): only an EXPLICIT `:images` key triggers image resolution —
;;     an ABSENT `:images` is an ordinary configured frame on the shared
;;     registrar (no generation), so the default-image path is keyed by `[]`.
;;     Both the explicit-pool 2-arity and the bare live-store 1-arity are
;;     covered; a cross-namespace collision in the default fails loud at
;;     make-frame time.
;; ===========================================================================

(deftest empty-images-runs-the-default-image-over-the-explicit-pool
  (testing "make-frame with :images [] (and an explicit descriptor pool) runs the
            DEFAULT image — the implicit selector over the WHOLE pool — so the
            frame's generation INCLUDES a reg-*'d descriptor from the store, not
            just the framework standards"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (let [frame (lf/make-frame {:images []} counter-pool)
          gen   (lf/frame-generation frame)]
      (is (lf/frame-object? frame))
      (is (some? gen))
      (testing "the whole-pool reg-* descriptors are projected (default image,
                NOT standards-only)"
        (is (some? (asm/resolve-descriptor gen :event :counter/inc))
            "a reg-*'d descriptor from the source pool is in the default generation")
        (is (= ::inc (:handler-fn (asm/resolve-descriptor gen :event :counter/inc))))
        (is (some? (asm/resolve-descriptor gen :sub :counter/value))))
      (testing "the framework standard is also unioned in (default = pool + standards)"
        (is (some? (asm/resolve-descriptor gen :fx :rf.nav/push-url)))))))

(deftest empty-images-vector-runs-default-but-absent-images-carries-no-generation
  (testing "EP-0024 (rf2-tu2vr7): only an EXPLICIT :images key triggers image
            resolution. :images [] is the default-image path (the implicit
            whole-store selector ⇒ a generation), but an ABSENT :images key is an
            ordinary configured frame on the shared registrar — NO generation"
    (testing ":images [] resolves the whole-store default (a generation)"
      (let [frame (lf/make-frame {:images []} counter-pool)
            gen   (lf/frame-generation frame)]
        (is (lf/frame-object? frame))
        (is (some? (asm/resolve-descriptor gen :event :counter/inc))
            "an empty :images vector projects the whole-store default, not standards-only")))
    (testing "an ABSENT :images key carries NO generation (ordinary configured
              frame — the resolution falls through to the shared registrar)"
      (let [frame (lf/make-frame {} counter-pool)]
        (is (lf/frame-object? frame))
        (is (nil? (lf/frame-generation frame))
            "no :images key ⇒ no image-loaded generation on the record")))))

(deftest empty-images-default-cross-namespace-collision-fails-loud
  (testing "a cross-namespace same-(kind, id) collision in the DEFAULT projection
            makes make-frame FAIL LOUD (:rf.error/image-duplicate-id) — the
            :images [] default does not guess and does not let load order win"
    (let [colliding-pool
          [(reg-desc "examples.todo.boot"    :event :boot/init ::todo-boot)
           (reg-desc "examples.counter.boot" :event :boot/init ::counter-boot)]]
      (is (= :rf.error/image-duplicate-id
             (err-id #(lf/make-frame {:images []} colliding-pool))))
      (testing "the collision fires regardless of pool order (no last-write-wins)"
        (is (= :rf.error/image-duplicate-id
               (err-id #(lf/make-frame {:images []} (vec (reverse colliding-pool))))))))))

(deftest bare-make-frame-runs-the-default-image-over-the-live-store
  (testing "the BARE 1-arity make-frame (no descriptor pool) resolves the DEFAULT
            image against the LIVE source store, so a frame created with :images
            [] runs every reg-*-authored registration in the store"
    ;; Drive from a known-clean live store inside a snapshot/restore body so the
    ;; default projection is deterministic; the fixture also restores the store.
    (let [store-before @ss/kind->id->ns->descriptor]
      (try
        (reset! ss/kind->id->ns->descriptor {})
        (asm/clear-generation-cache!)
        (record! "shop.cart" :event :cart/add   ::cart-add)
        (record! "shop.cart" :sub   :cart/items ::cart-items)
        (let [frame (lf/make-frame {:images []})
              gen   (lf/frame-generation frame)]
          (is (lf/frame-object? frame))
          (is (some? (asm/resolve-descriptor gen :event :cart/add))
              "the bare make-frame default projection includes the live-store reg-*")
          (is (= ::cart-add (:handler-fn (asm/resolve-descriptor gen :event :cart/add))))
          (is (some? (asm/resolve-descriptor gen :sub :cart/items))))
        (finally
          (reset! ss/kind->id->ns->descriptor store-before)
          (asm/clear-generation-cache!))))))

(deftest bare-make-frame-default-live-store-collision-fails-loud
  (testing "the BARE 1-arity make-frame fails loud (:rf.error/image-duplicate-id)
            when the LIVE source store carries a cross-namespace same-(kind, id)
            collision in the default projection"
    (let [store-before @ss/kind->id->ns->descriptor]
      (try
        (reset! ss/kind->id->ns->descriptor {})
        (asm/clear-generation-cache!)
        (record! "examples.todo.boot"    :event :boot/init ::todo-boot)
        (record! "examples.counter.boot" :event :boot/init ::counter-boot)
        (is (= :rf.error/image-duplicate-id
               (err-id #(lf/make-frame {:images []}))))
        (finally
          (reset! ss/kind->id->ns->descriptor store-before)
          (asm/clear-generation-cache!))))))

;; ===========================================================================
;; 2. :id registers an image-loaded record in the ONE `frames` registry
;; ===========================================================================

(deftest id-registers-an-image-loaded-record-in-the-one-registry
  (testing "supplying :id registers an image-loaded record in the ONE `frames`
            registry under that id (EP-0024 §One registry). `lf/live-frame`
            RECONSTRUCTS a fresh value from the record (routing to the same id,
            NOT identical? to the originally-returned value)"
    (let [img   (image/image {:include-ns ["examples.counter"]})
          frame (lf/make-frame {:id :counter/main :images [img]} counter-pool)]
      (is (= :counter/main (rf/frame-value->id (lf/live-frame :counter/main)))
          "live-frame lookup reconstructs a value routing to the same id")
      (is (= :counter/main (:rf.frame/id frame)))
      (is (contains? (lf/live-frame-ids) :counter/main)))))

(deftest initial-db-and-adapter-ride-the-value
  (testing "creation inputs (:initial-db, :adapter) are carried on the frame
            value for the state/host slices — image is behaviour, frame is state"
    (let [img   (image/image {:include-ns ["examples.counter"]})
          frame (lf/make-frame {:id :counter/seeded
                                :images [img]
                                :initial-db {:count 7}
                                :adapter ::reagent}
                               counter-pool)]
      (is (= {:count 7} (:rf.frame/initial-db frame)))
      (is (= ::reagent (:rf.frame/adapter frame))))))

;; ===========================================================================
;; 3. A duplicate live :id is IDEMPOTENT REPLACEMENT (hot-reload friendly)
;; ===========================================================================

(deftest duplicate-live-id-is-idempotent-replacement
  (testing "re-`make-frame`-ing an already-live id does NOT throw — EP-0024
            §Duplicate id policy makes it IDEMPOTENT REPLACEMENT: the
            record-config + generation refresh while DURABLE STATE (app-db /
            sub-cache / queue) is PRESERVED (the old fail-loud
            :rf.error/live-frame-id-conflict is GONE). Seed durable state via
            :initial-db, re-make under the SAME id with NO :initial-db, and assert
            the app-db survived (a fresh record would have reset it to {})"
    (let [img    (image/image {:include-ns ["examples.counter"]})
          first  (lf/make-frame {:id :counter/main :images [img]
                                 :initial-db {:count 7}}
                                counter-pool)]
      (is (lf/frame-object? first))
      (is (= {:count 7} (rf/app-db-value :counter/main))
          "the first frame seeded durable app-db")
      ;; Re-making the SAME id does NOT throw — it returns a frame value routing
      ;; to the same id (idempotent replacement, NOT a conflict).
      (let [again (lf/make-frame {:id :counter/main :images [img]} counter-pool)]
        (is (lf/frame-object? again)
            "re-making the same id returns a frame value (no throw)")
        (is (= :counter/main (rf/frame-value->id again))
            "the re-made frame value routes to the same id")
        (is (some? (lf/frame-generation :counter/main))
            "the record is still image-loaded after the replacement")
        (is (contains? (lf/live-frame-ids) :counter/main))
        (is (= {:count 7} (rf/app-db-value :counter/main))
            "durable app-db is PRESERVED across the idempotent replacement
             (NOT reset to {} — the record's runtime state survives)")))))

(deftest registration-id-reuse-across-images-is-fine
  (testing "two DIFFERENT frame ids may each carry an image reusing the same
            REGISTRATION ids — registration ids are reusable across images;
            only FRAME ids are unique (the heart of the same-id story)"
    (let [img   (image/image {:include-ns ["examples.counter"]})
          left  (lf/make-frame {:id :counter/left  :images [img]} counter-pool)
          right (lf/make-frame {:id :counter/right :images [img]} counter-pool)]
      ;; Both live frames resolve the SAME registration id :counter/inc; no
      ;; conflict because the FRAME ids differ. `frame-generation` reads each
      ;; record's generation by id (EP-0024 — accepts a frame value or an id).
      (is (some? (asm/resolve-descriptor (lf/frame-generation left)  :event :counter/inc)))
      (is (some? (asm/resolve-descriptor (lf/frame-generation right) :event :counter/inc)))
      (is (= #{:counter/left :counter/right} (lf/live-frame-ids))))))

;; ===========================================================================
;; 4. A direct (no-id) frame object BYPASSES the registry
;; ===========================================================================

(defn- public-frame-id?
  "A PUBLIC frame id — one NOT minted under the reserved `:rf.frame/` namespace.
  EP-0024: a no-id (direct) frame is keyed by a private `:rf.frame/<gensym>`
  runnable-id and is EXCLUDED from `live-frame-ids` (which enumerates only
  public image-loaded frame ids, as the dissolved registry's `live-frame-ids`
  did) — so it contributes no public id and the reprojection / enumeration path
  never touches a harness-local frame the owner reloads explicitly."
  [id]
  (not= "rf.frame" (namespace id)))

(deftest direct-no-id-frame-bypasses-the-public-frame-id-space
  (testing "a frame created with NO :id is local-only — the VALUE carries no
            public :rf.frame/id and the record is keyed by a PRIVATE
            :rf.frame/<gensym> runnable-id, so it contributes NO public frame id
            (EP-0024 §Frame value — direct frame values bypass the PUBLIC
            frame-id space; the gensym record itself is image-loaded)"
    (let [img   (image/image {:include-ns ["examples.counter"]})
          frame (lf/make-frame {:images [img]} counter-pool)]
      (is (lf/frame-object? frame))
      (is (nil? (:rf.frame/id frame)) "a no-id frame value carries no public :rf.frame/id")
      (is (= "rf.frame" (namespace (rf/frame-value->id frame)))
          "the record is keyed by a private :rf.frame/<gensym> runnable-id")
      (is (not-any? public-frame-id? (lf/live-frame-ids))
          "a no-id frame contributes NO public frame id")
      (is (not (contains? (lf/live-frame-ids) (rf/frame-value->id frame)))
          "the private gensym id is excluded from live-frame-ids (no-id frames
           bypass enumeration/auto-reprojection — EP-0024)"))))

(deftest two-direct-frames-coexist-without-public-ids
  (testing "two local direct frame values can coexist with no PUBLIC ids — each
            gets its own private :rf.frame/<gensym> runnable-id (EP-0024 — two
            local direct frame values can coexist, distinct records)"
    (let [img (image/image {:include-ns ["examples.counter"]})
          a   (lf/make-frame {:images [img]} counter-pool)
          b   (lf/make-frame {:images [img]} counter-pool)]
      (is (lf/frame-object? a))
      (is (lf/frame-object? b))
      (is (not= (rf/frame-value->id a) (rf/frame-value->id b))
          "each make-frame yields a distinct record (distinct runnable-ids)")
      (is (not-any? public-frame-id? (lf/live-frame-ids))
          "neither no-id frame contributes a public frame id"))))

;; ===========================================================================
;; 5. A non-vector :images is REJECTED
;; ===========================================================================

(deftest non-vector-images-rejected
  (testing ":images must be a VECTOR (the one spelling, always a vector — EP-0023
            §Image Composition); a bare image map, a seq, or any non-vector throws
            :rf.error/make-frame-bad-images"
    (let [img (image/image {:include-ns ["examples.counter"]})]
      (testing "a bare (unwrapped) image map is rejected"
        (is (= :rf.error/make-frame-bad-images
               (err-id #(lf/make-frame {:images img} counter-pool)))))
      (testing "a seq (list) is rejected — even when it contains image values"
        (is (= :rf.error/make-frame-bad-images
               (err-id #(lf/make-frame {:images (list img)} counter-pool)))))
      (testing "a set is rejected"
        (is (= :rf.error/make-frame-bad-images
               (err-id #(lf/make-frame {:images #{img}} counter-pool))))))))

;; ===========================================================================
;; 6. The capability frame-boundary check (EP-0023 §Public API)
;; ===========================================================================

(deftest missing-capability-fails-loud-at-the-frame-boundary
  (testing "when an image requires a capability the supplied :capabilities map
            does not provide, frame creation fails loud
            (:rf.error/image-missing-capability) BEFORE the generation is runnable"
    (let [img (image/image {:id :articles/browser
                            :include-ns ["examples.counter"]
                            :rf.image/requires #{:rf.capability/http}})]
      (is (= :rf.error/image-missing-capability
             (err-id #(lf/make-frame {:id :articles/main
                                      :images [img]
                                      :capabilities {}}
                                     counter-pool))))
      (testing "the conflicting frame id was NOT registered (creation aborted)"
        (is (not (contains? (lf/live-frame-ids) :articles/main)))))))

(deftest supplied-capability-satisfies-the-requirement
  (testing "when :capabilities provides the required capability, frame creation
            succeeds and the capabilities ride the object"
    (let [img   (image/image {:include-ns ["examples.counter"]
                              :rf.image/requires #{:rf.capability/http}})
          frame (lf/make-frame {:images [img]
                                :capabilities {:rf.capability/http ::http-impl}}
                               counter-pool)]
      (is (lf/frame-object? frame))
      (is (= {:rf.capability/http ::http-impl} (:rf.frame/capabilities frame))))))

(deftest absent-capabilities-map-fails-a-required-image
  (testing "an image declaring :rf.image/requires but supplied NO :capabilities
            map fails loud at the frame boundary — an absent map provides
            nothing (EP-0013 fail-loud parity), so the check is NOT skipped"
    (let [img (image/image {:id :articles/browser
                            :include-ns ["examples.counter"]
                            :rf.image/requires #{:rf.capability/http}})]
      (is (= :rf.error/image-missing-capability
             (err-id #(lf/make-frame {:id :articles/main
                                      :images [img]}
                                     counter-pool)))
          "no :capabilities key at all still fails the non-empty requires")
      (testing "the frame id was NOT registered (creation aborted before insert)"
        (is (not (contains? (lf/live-frame-ids) :articles/main)))))))

(deftest no-requirements-needs-no-capabilities-map
  (testing "an image with empty/absent :rf.image/requires creates a frame with
            NO :capabilities map — the unconditional check is a no-op when the
            generation requires nothing"
    (let [img   (image/image {:include-ns ["examples.counter"]})
          frame (lf/make-frame {:images [img]} counter-pool)]
      (is (lf/frame-object? frame))
      (is (= #{} (:rf.gen/requires (lf/frame-generation frame))))
      (is (not (contains? frame :rf.frame/capabilities))
          "no :capabilities supplied → the slot is absent on the object"))))

(deftest multi-image-requires-union-checked-at-the-frame-boundary
  (testing "two images each declaring a distinct capability: the frame's
            generation carries the UNION, and the :capabilities map must satisfy
            ALL of it or creation fails loud"
    (let [pool  [(reg-desc "examples.counter" :event :counter/inc ::inc)
                 (reg-desc "examples.cart"    :event :cart/add    ::add)]
          img-a (image/image {:id :counter/img :include-ns ["examples.counter"]
                              :rf.image/requires #{:rf.capability/http}})
          img-b (image/image {:id :cart/img :include-ns ["examples.cart"]
                              :rf.image/requires #{:rf.capability/schemas}})]
      (testing "a :capabilities map satisfying only ONE image fails the union"
        (is (= :rf.error/image-missing-capability
               (err-id #(lf/make-frame {:images [img-a img-b]
                                        :capabilities {:rf.capability/http ::http-impl}}
                                       pool)))))
      (testing "a :capabilities map satisfying the FULL union creates the frame,
                and the union rides the generation"
        (let [frame (lf/make-frame
                      {:images [img-a img-b]
                       :capabilities {:rf.capability/http    ::http-impl
                                      :rf.capability/schemas ::schemas}}
                      pool)]
          (is (lf/frame-object? frame))
          (is (= #{:rf.capability/http :rf.capability/schemas}
                 (:rf.gen/requires (lf/frame-generation frame)))))))))

;; ---- capability-union BOUNDARY diagnostic is structured (rf2-32siq3.40
;;      minor c) -------------------------------------------------------------
;;
;; The .31 review found the make-frame BOUNDARY capability failures asserted
;; only the `:rf.error/id` discriminator, while the bare `check-capabilities!`
;; unit test (image_assembly_cljs_test/partial-capabilities-fail-on-the-unmet-
;; subset) asserts the structured `:missing-capabilities` / `:supplied-
;; capabilities` slots. The frame-boundary failure rides the SAME error helper,
;; so the boundary diagnostic must carry the SAME structured slots — pinning
;; that the union check at the frame boundary names the unmet subset and the
;; supplied set, not just the error id.

(deftest capability-boundary-diagnostic-carries-structured-slots
  (testing "the make-frame frame-boundary capability failure carries the SAME
            structured :missing-capabilities / :supplied-capabilities slots as
            the bare check-capabilities! unit test — the diagnostic names the
            unmet subset (sorted) and what the frame DID supply, at the boundary"
    (let [pool  [(reg-desc "examples.counter" :event :counter/inc ::inc)
                 (reg-desc "examples.cart"    :event :cart/add    ::add)]
          ;; Two images → a UNION requires of {http schemas}; the frame supplies
          ;; only http, so the boundary check fails on the unmet schemas subset.
          img-a (image/image {:id :counter/img :include-ns ["examples.counter"]
                              :rf.image/requires #{:rf.capability/http}})
          img-b (image/image {:id :cart/img :include-ns ["examples.cart"]
                              :rf.image/requires #{:rf.capability/schemas
                                                   :rf.capability/storage}})
          d (err-data #(lf/make-frame {:id :articles/main
                                       :images [img-a img-b]
                                       :capabilities {:rf.capability/http ::http-impl}}
                                      pool))]
      (is (= :rf.error/image-missing-capability (:rf.error/id d))
          "the boundary union failure is :rf.error/image-missing-capability")
      (testing ":missing-capabilities names exactly the unmet union subset, sorted"
        (is (= [:rf.capability/schemas :rf.capability/storage]
               (:missing-capabilities d))))
      (testing ":supplied-capabilities names what the frame DID provide (a
                CAPABILITY gap, not a registration gap)"
        (is (= [:rf.capability/http]
               (:supplied-capabilities d))))
      (testing "the conflicting frame id was NOT registered (creation aborted)"
        (is (not (contains? (lf/live-frame-ids) :articles/main)))))))

;; ===========================================================================
;; 7. Host-handle exclusion — the EP-0023 §Host Boundary two-boundaries
;;    invariant at the OBJECT boundary (rf2-32siq3.40 MAJOR-2)
;; ===========================================================================
;;
;; The .31 review found the EP two-boundaries invariant (host handles / adapter
;; binding NEVER enter the frame-state value) untested at the EP-0023 OBJECT
;; boundary. make-frame stores :rf.frame/adapter + :rf.frame/capabilities on the
;; frame object (the host-handle slots); existing tests assert they are
;; PRESERVED across reload, but none asserts they are EXCLUDED from the
;; serializable frame-state projection.
;;
;; The runnable state container (the app-db/cache atoms) is deferred to .32, so
;; the serializable frame-state SEED available at this slice is :rf.frame/
;; initial-db. The invariant tested here: the adapter binding and the capability
;; map handed to make-frame are confined to their OWN object slots and never
;; bleed into :rf.frame/initial-db (the serializable state), and the host-handle
;; slots are a distinct, non-serializable concern from the state slot.

(deftest host-handles-excluded-from-serializable-frame-state
  (testing "the adapter binding + capability map ride DEDICATED object slots
            (:rf.frame/adapter, :rf.frame/capabilities) and are EXCLUDED from the
            serializable frame-state seed (:rf.frame/initial-db) — the EP-0023
            §Host Boundary two-boundaries invariant: host handles never enter the
            frame-state value (MAJOR-2)"
    (let [img        (image/image {:include-ns ["examples.counter"]
                                   :rf.image/requires #{:rf.capability/http}})
          adapter    {:rf.adapter/kind :reagent :rf.adapter/render-root ::host-handle}
          caps       {:rf.capability/http ::http-impl}
          state-seed {:count 7 :user/name "ada"}
          frame      (lf/make-frame {:images     [img]
                                     :initial-db state-seed
                                     :adapter    adapter
                                     :capabilities caps}
                                    counter-pool)]
      (testing "the host handles ride their OWN object slots (preserved)"
        (is (= adapter (:rf.frame/adapter frame)))
        (is (= caps    (:rf.frame/capabilities frame))))
      (testing "the serializable frame-state seed is EXACTLY :initial-db — no
                adapter binding, no capability map bled into it"
        (is (= state-seed (:rf.frame/initial-db frame)))
        (let [serializable (:rf.frame/initial-db frame)]
          (is (not (contains? serializable :rf.frame/adapter))
              "the adapter binding is NOT in the serializable state value")
          (is (not (contains? serializable :rf.frame/capabilities))
              "the capability map is NOT in the serializable state value")
          (is (not-any? #{::host-handle ::http-impl} (vals serializable))
              "no host-handle VALUE leaked into the serializable state value")))
      (testing "the host-handle slots are a DISTINCT concern from the state slot
                (object slots ≠ the serializable frame-state seed)"
        (is (not= (:rf.frame/adapter frame)      (:rf.frame/initial-db frame)))
        (is (not= (:rf.frame/capabilities frame) (:rf.frame/initial-db frame)))))))
