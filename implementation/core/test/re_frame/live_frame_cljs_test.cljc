(ns re-frame.live-frame-cljs-test
  "EP-0023 §Frame / §Public API — the FRAME IMAGE-LOADING slice (rf2-32siq3.8):
  `rf/make-frame` accepts `:images` (always a vector), resolves them into ONE
  sealed image generation, and returns the live frame OBJECT; an `:id` registers
  in the process-local live-frame registry, a duplicate live id fails loud, and a
  direct (no-id) frame object bypasses the registry.

  Pins the bead's enumerated coverage:

    * `:images` (a vector) resolves to a generation carried on the frame object;
    * the frame object holds a reference to its resolved generation
      (`:rf.frame/generation`);
    * the NO-`:images` path (absent or `[]`) runs the DEFAULT IMAGE — the
      implicit selector over the WHOLE source store (rf2-32siq3.33): the
      generation includes the store's `reg-*` descriptors (+ standards), NOT the
      framework standards alone, and a cross-namespace same-`[kind id]` collision
      in that default projection FAILS LOUD at make-frame time
      (`:rf.error/image-duplicate-id`). Covered for both the explicit-pool
      2-arity and the bare live-store 1-arity;
    * an `:id` registers the object in the live-frame registry;
    * a duplicate live `:id` FAILS LOUD (`:rf.error/live-frame-id-conflict`);
    * a direct (no-id) frame object BYPASSES the registry;
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
  registrations). The live-frame registry, the standard registry, the generation
  cache, and the source store are all process state, so the fixture
  snapshot/restores or clears them per case. `.cljc` ends `-cljs-test` so it
  rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image       :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.live-frame  :as lf]
            [re-frame.source-store   :as ss]))

;; ---------------------------------------------------------------------------
;; Fixture — the live-frame registry, the framework-standard registry, and the
;; resolved-generation cache are process-state defonce atoms; clear them per
;; case. The source store is ALSO process state (the DEFAULT-image path reads it
;; live), so SNAPSHOT/RESTORE it — do NOT `clear-all!`, which would destroy real
;; authored registrations (per the bead). The live-store default-image tests
;; mutate the store inside their own snapshot/restore body too.
;; ---------------------------------------------------------------------------

(use-fixtures :each
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
;; 1b. The no-`:images` path runs the DEFAULT IMAGE — the implicit whole-store
;;     projection (EP-0023 §Default Image Semantics, rf2-32siq3.33), NOT the
;;     framework standard set alone. Both the explicit-pool 2-arity and the bare
;;     live-store 1-arity are covered; a cross-namespace collision in the default
;;     fails loud at make-frame time.
;; ===========================================================================

(deftest no-images-runs-the-default-image-over-the-explicit-pool
  (testing "make-frame with NO :images (and an explicit descriptor pool) runs the
            DEFAULT image — the implicit selector over the WHOLE pool — so the
            frame's generation INCLUDES a reg-*'d descriptor from the store, not
            just the framework standards"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (let [frame (lf/make-frame {} counter-pool)
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

(deftest empty-images-vector-is-the-default-image-path
  (testing "make-frame with :images [] is the SAME default-image path as no
            :images at all (an empty vector ⇒ the implicit whole-store selector)"
    (let [frame (lf/make-frame {:images []} counter-pool)
          gen   (lf/frame-generation frame)]
      (is (lf/frame-object? frame))
      (is (some? (asm/resolve-descriptor gen :event :counter/inc))
          "an empty :images vector projects the whole-store default, not standards-only"))))

(deftest no-images-default-cross-namespace-collision-fails-loud
  (testing "a cross-namespace same-(kind, id) collision in the DEFAULT projection
            makes make-frame FAIL LOUD (:rf.error/image-duplicate-id) — the
            no-:images default does not guess and does not let load order win"
    (let [colliding-pool
          [(reg-desc "examples.todo.boot"    :event :boot/init ::todo-boot)
           (reg-desc "examples.counter.boot" :event :boot/init ::counter-boot)]]
      (is (= :rf.error/image-duplicate-id
             (err-id #(lf/make-frame {} colliding-pool))))
      (testing "the collision fires regardless of pool order (no last-write-wins)"
        (is (= :rf.error/image-duplicate-id
               (err-id #(lf/make-frame {} (vec (reverse colliding-pool))))))))))

(deftest bare-make-frame-runs-the-default-image-over-the-live-store
  (testing "the BARE 1-arity make-frame (no descriptor pool) resolves the DEFAULT
            image against the LIVE source store, so a frame created with no
            :images runs every reg-*-authored registration in the store"
    ;; Drive from a known-clean live store inside a snapshot/restore body so the
    ;; default projection is deterministic; the fixture also restores the store.
    (let [store-before @ss/kind->id->ns->descriptor]
      (try
        (reset! ss/kind->id->ns->descriptor {})
        (asm/clear-generation-cache!)
        (record! "shop.cart" :event :cart/add   ::cart-add)
        (record! "shop.cart" :sub   :cart/items ::cart-items)
        (let [frame (lf/make-frame {})
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
               (err-id #(lf/make-frame {}))))
        (finally
          (reset! ss/kind->id->ns->descriptor store-before)
          (asm/clear-generation-cache!))))))

;; ===========================================================================
;; 2. :id registers the object in the process-local live-frame registry
;; ===========================================================================

(deftest id-registers-the-object-in-the-live-frame-registry
  (testing "supplying :id registers the returned object in the process-local
            live-frame registry under that id (EP-0023 §Id Spaces)"
    (let [img   (image/image {:include-ns ["examples.counter"]})
          frame (lf/make-frame {:id :counter/main :images [img]} counter-pool)]
      (is (identical? frame (lf/live-frame :counter/main))
          "live-frame lookup returns the SAME object make-frame returned")
      (is (= :counter/main (:rf.frame/id frame)))
      (is (contains? (lf/live-frame-ids) :counter/main)))))

(deftest initial-db-and-adapter-ride-the-object
  (testing "creation inputs (:initial-db, :adapter) are carried on the object
            for the later state/host slices — image is behaviour, frame is state"
    (let [img   (image/image {:include-ns ["examples.counter"]})
          frame (lf/make-frame {:id :counter/seeded
                                :images [img]
                                :initial-db {:count 7}
                                :adapter ::reagent}
                               counter-pool)]
      (is (= {:count 7} (:rf.frame/initial-db frame)))
      (is (= ::reagent (:rf.frame/adapter frame))))))

;; ===========================================================================
;; 3. A duplicate live :id FAILS LOUD
;; ===========================================================================

(deftest duplicate-live-id-fails-loud
  (testing "registering a second live frame under an already-live id throws
            :rf.error/live-frame-id-conflict (a frame id is unique among live
            registered frames — EP-0023 §Id Spaces); the FIRST frame keeps its
            slot, no silent clobber"
    (let [img    (image/image {:include-ns ["examples.counter"]})
          first  (lf/make-frame {:id :counter/main :images [img]} counter-pool)]
      (is (= :rf.error/live-frame-id-conflict
             (err-id #(lf/make-frame {:id :counter/main :images [img]} counter-pool))))
      (testing "the original frame still owns the slot after the rejected dupe"
        (is (identical? first (lf/live-frame :counter/main)))))))

(deftest registration-id-reuse-across-images-is-fine
  (testing "two DIFFERENT frame ids may each carry an image reusing the same
            REGISTRATION ids — registration ids are reusable across images;
            only FRAME ids are unique (the heart of the same-id story)"
    (let [img   (image/image {:include-ns ["examples.counter"]})
          left  (lf/make-frame {:id :counter/left  :images [img]} counter-pool)
          right (lf/make-frame {:id :counter/right :images [img]} counter-pool)]
      ;; Both live frames resolve the SAME registration id :counter/inc; no
      ;; conflict because the FRAME ids differ.
      (is (some? (asm/resolve-descriptor (lf/frame-generation left)  :event :counter/inc)))
      (is (some? (asm/resolve-descriptor (lf/frame-generation right) :event :counter/inc)))
      (is (= #{:counter/left :counter/right} (lf/live-frame-ids))))))

;; ===========================================================================
;; 4. A direct (no-id) frame object BYPASSES the registry
;; ===========================================================================

(deftest direct-no-id-frame-bypasses-the-registry
  (testing "a frame created with NO :id is local-only — the object is returned
            but NOT registered (EP-0023 §Frame — direct frame objects bypass the
            public frame-id space)"
    (let [img   (image/image {:include-ns ["examples.counter"]})
          frame (lf/make-frame {:images [img]} counter-pool)]
      (is (lf/frame-object? frame))
      (is (nil? (:rf.frame/id frame)) "a no-id frame carries no :rf.frame/id")
      (is (empty? (lf/live-frame-ids)) "the registry is untouched by a no-id frame"))))

(deftest two-direct-frames-coexist-without-ids
  (testing "two local direct frame objects can coexist with no public ids
            (EP-0023 conformance — two local direct frame objects can coexist)"
    (let [img (image/image {:include-ns ["examples.counter"]})
          a   (lf/make-frame {:images [img]} counter-pool)
          b   (lf/make-frame {:images [img]} counter-pool)]
      (is (lf/frame-object? a))
      (is (lf/frame-object? b))
      (is (not (identical? a b)) "each make-frame yields a distinct object")
      (is (empty? (lf/live-frame-ids))))))

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
