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

  Resolution runs against an explicit synthetic descriptor pool (the
  `make-frame` 2-arity), so there is no live source-store wiring — the same
  decoupling idiom `image-assembly-cljs-test` uses. The live-frame registry IS
  process state, so a fixture clears it per case. `.cljc` ends `-cljs-test` so
  it rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image       :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.live-frame  :as lf]))

;; ---------------------------------------------------------------------------
;; Fixture — both the live-frame registry and the framework-standard registry
;; are process-state defonce atoms; clear per case.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    (lf/clear-live-frames!)
    (asm/clear-standards!)
    (t)
    (lf/clear-live-frames!)
    (asm/clear-standards!)))

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

(deftest no-images-resolves-the-standard-set-alone
  (testing "make-frame with no :images resolves the framework standard set alone
            — a valid (empty-app) generation, still returning a frame object"
    (let [frame (lf/make-frame {} [])]
      (is (lf/frame-object? frame))
      (is (some? (lf/frame-generation frame))))))

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
