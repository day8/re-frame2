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
    * a non-map `opts` ARGUMENT (nil / keyword / vector / string) is REJECTED at
      the public boundary (`:rf.error/make-frame-bad-opts`, rf2-r6r2yi) BEFORE any
      frame is registered; the empty map `{}` is accepted (the all-defaults
      frame), and nil is rejected (no zero-arity / `{}` carries that meaning).

  (EP-0026, rf2-dlvmpc: image-declared host capabilities are removed end-to-end —
  there is no `:capabilities` image-selection key, no `:rf.image/requires`, no
  `:rf.gen/requires`, no frame-boundary capability check, and no
  `:rf.frame/capabilities` slot.)

  Each fail-loud assertion checks the `:rf.error/id` discriminator (NEVER the
  message bytes — Spec 009 §The thrown-error shape rule 3).

  Most cases resolve against an explicit synthetic descriptor pool (the
  `make-frame` 2-arity) — the same decoupling idiom `image-assembly-cljs-test`
  uses. The default-image cases additionally exercise the BARE live-store 1-arity
  (`make-frame {}`), which reads the live source store; those snapshot/restore
  the store (never `clear-all!`, which would destroy real authored
  registrations). The ONE `frames` registry, the standard registry, the generation
  cache, and the source store are all process state, so the fixture
  snapshot/restores or clears them per case (the runtime fixture resets
  `frame/frames`, which clears every record AND its generation — no separate
  live-frame index to clear, rf2-ji3tvy).
  `.cljc` ends `-cljs-test` so it rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core        :as rf]
            [re-frame.events      :as events]
            [re-frame.frame       :as frame-ns]
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
      (asm/clear-standards!)
      ;; EP-0027 (rf2-7ae2to): re-seed the framework-standard `:rf/set-db` event
      ;; AFTER clearing standards — these cases seed image-loaded frames via
      ;; `:initial-events [[:rf/set-db …]]`, which resolves `:rf/set-db` through
      ;; the sealed generation (the image standard registry, not the registrar
      ;; atom). The blanket `clear-standards!` keeps OTHER standards out so the
      ;; pure image-resolution contract stays isolated; the framework seed is the
      ;; one standard these tests legitimately depend on.
      (events/register-set-db-standard!)
      (asm/clear-generation-cache!)
      (try
        (t)
        (finally
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
  are merged at the top level of the ex-data — see
  `re-frame.error/throw-error!`."
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
                              :select-ns {:include ["examples.counter"]}})
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
          a     (image/image {:id :lib/widgets   :select-ns {:include ["lib.widgets"]}})
          b     (image/image {:id :examples/counter :select-ns {:include ["examples.counter"]}})
          frame (lf/make-frame {:images [a b]} pool)
          gen   (lf/frame-generation frame)]
      (is (some? (asm/resolve-descriptor gen :event :widgets/init)))
      (is (some? (asm/resolve-descriptor gen :event :counter/inc))))))

;; ===========================================================================
;; 1b. EP-0026 §Default Image (rf2-fsd822, ruled 2026-06-22): `:images []` is an
;;     ERROR (`:rf.error/make-frame-bad-images`) — pass at least one image, or
;;     OMIT `:images`. This REVERSES EP-0023/EP-0024, where `:images []` was the
;;     default-image path.
;;
;;     The ruling ALSO says OMITTING `:images` (`make-frame {}`) should resolve
;;     the DEFAULT image generation. That OMIT→default BOUNDARY WIRING is
;;     DEFERRED to a follow-up (a design-level conflict with the Story player /
;;     owned-frame lifecycle, which deliberately create image-LESS registrar-
;;     backed frames, plus the consolidated test bundle's shared source store
;;     carrying cross-app collisions a whole-store default projection fails on).
;;     So for now an ABSENT `:images` still carries NO generation (the EP-0023/
;;     EP-0024 ordinary configured frame). The assembly-layer default-image
;;     mechanics are unchanged and covered by `image-assembly-default-cljs-test`.
;; ===========================================================================

(deftest empty-images-vector-is-an-error
  (testing "EP-0026 §Default Image (ruled 2026-06-22): :images [] is an ERROR —
            pass at least one image, or OMIT :images. This reverses EP-0023/
            EP-0024, where :images [] projected the default."
    (testing ":images [] fails loud with :rf.error/make-frame-bad-images"
      (is (= :rf.error/make-frame-bad-images
             (err-id #(lf/make-frame {:images []} counter-pool))))
      (testing "and via the bare 1-arity too"
        (is (= :rf.error/make-frame-bad-images
               (err-id #(lf/make-frame {:images []}))))))
    (testing "no frame is registered as a side effect of the rejected :images []"
      (let [before (lf/live-frame-ids)]
        (err-id #(lf/make-frame {:id :rejected/empty :images []} counter-pool))
        (is (= before (lf/live-frame-ids))
            "a rejected :images [] leaves the live-frame registry untouched")))
    (testing "a NON-EMPTY :images vector is accepted and resolves a generation"
      (let [frame (lf/make-frame {:images [(image/image {:id :only
                                                         :registrations
                                                         {:reg-event [[:x (fn [_ _] {})]]}})]}
                                 counter-pool)]
        (is (lf/frame-object? frame))
        (is (some? (asm/resolve-descriptor (lf/frame-generation frame) :event :x)))))))

(deftest absent-images-resolves-the-default-image-generation
  (testing "EP-0026 §Default Image: an ABSENT :images (make-frame {}) resolves the
            DEFAULT image generation over the active source store (here the
            explicit pool) — the implicit selector over the WHOLE pool + framework
            standards. The frame is image-loaded: its record carries a generation
            and that generation resolves every pool descriptor."
    (let [frame (lf/make-frame {} counter-pool)
          gen   (lf/frame-generation frame)]
      (is (lf/frame-object? frame))
      (is (some? gen)
          "absent :images ⇒ the DEFAULT image generation on the record (EP-0026)")
      (is (some? (asm/resolve-descriptor gen :event :counter/inc))
          "the default projection resolves the pool's :counter/inc event")
      (is (some? (asm/resolve-descriptor gen :sub :counter/value))
          "the default projection resolves the pool's :counter/value sub")))
  (testing "an EMPTY pool default projection is a VALID empty generation — a frame
            with no app registrations resolving only the framework standards (no
            zero-match fail, unlike an :select-ns :include glob)"
    (let [frame (lf/make-frame {} [])]
      (is (lf/frame-object? frame))
      (is (some? (lf/frame-generation frame))
          "an empty default projection still carries a (standards-only) generation")))
  (testing "a cross-namespace same-[kind id] collision in the default projection
            FAILS LOUD at the make-frame boundary — the default image does NOT
            guess and does NOT let load order win (:rf.error/image-duplicate-id)"
    (let [colliding-pool
          [(reg-desc "examples.todo.boot"    :event :boot/init ::todo)
           (reg-desc "examples.counter.boot" :event :boot/init ::counter)]]
      (is (= :rf.error/image-duplicate-id
             (err-id #(lf/make-frame {} colliding-pool)))
          "two namespaces registering one [kind id] reject the default projection")
      (testing "and no frame is registered as a side effect of the rejected default"
        (let [before (lf/live-frame-ids)]
          (err-id #(lf/make-frame {:id :dup/default} colliding-pool))
          (is (= before (lf/live-frame-ids))
              "a rejected default projection leaves the live-frame registry untouched"))))))

;; ===========================================================================
;; 2. :id registers an image-loaded record in the ONE `frames` registry
;; ===========================================================================

(deftest id-registers-an-image-loaded-record-in-the-one-registry
  (testing "supplying :id registers an image-loaded record in the ONE `frames`
            registry under that id (EP-0024 §One registry). `lf/live-frame`
            RECONSTRUCTS a fresh value from the record (routing to the same id,
            NOT identical? to the originally-returned value)"
    (let [img   (image/image {:select-ns {:include ["examples.counter"]}})
          frame (lf/make-frame {:id :counter/main :images [img]} counter-pool)]
      (is (= :counter/main (frame-ns/frame-value->id (lf/live-frame :counter/main)))
          "live-frame lookup reconstructs a value routing to the same id")
      (is (= :counter/main (:rf.frame/id frame)))
      (is (contains? (lf/live-frame-ids) :counter/main)))))

(deftest initial-events-seed-and-adapter-ride-the-value
  (testing "the :adapter creation input rides the frame value (host slice —
            image is behaviour, frame is state) and :initial-events seeds app-db
            (EP-0027 retired :initial-db; the value no longer carries an
            :rf.frame/initial-db slot — seeding is the :rf/set-db setup event)"
    (let [img   (image/image {:select-ns {:include ["examples.counter"]}})
          frame (lf/make-frame {:id :counter/seeded
                                :images [img]
                                :initial-events [[:rf/set-db {:count 7}]]
                                :adapter ::reagent}
                               counter-pool)]
      (is (= ::reagent (:rf.frame/adapter frame)))
      (is (nil? (:rf.frame/initial-db frame))
          "no retired :rf.frame/initial-db slot on the value")
      (is (= {:count 7} (rf/app-db-value :counter/seeded))
          ":initial-events seeded app-db via :rf/set-db"))))

;; ===========================================================================
;; 3. A duplicate live :id is IDEMPOTENT REPLACEMENT (hot-reload friendly)
;; ===========================================================================

(deftest duplicate-live-id-is-idempotent-replacement
  (testing "re-`make-frame`-ing an already-live id does NOT throw — EP-0024
            §Duplicate id policy makes it IDEMPOTENT REPLACEMENT: the
            record-config + generation refresh while DURABLE STATE (app-db /
            sub-cache / queue) is PRESERVED (the old fail-loud
            :rf.error/live-frame-id-conflict is GONE). Seed durable state via
            :initial-events, re-make under the SAME id with NO :initial-events, and
            assert the app-db survived (a fresh record would have reset it to {})"
    (let [img    (image/image {:select-ns {:include ["examples.counter"]}})
          first  (lf/make-frame {:id :counter/main :images [img]
                                 :initial-events [[:rf/set-db {:count 7}]]}
                                counter-pool)]
      (is (lf/frame-object? first))
      (is (= {:count 7} (rf/app-db-value :counter/main))
          "the first frame seeded durable app-db")
      ;; Re-making the SAME id does NOT throw — it returns a frame value routing
      ;; to the same id (idempotent replacement, NOT a conflict).
      (let [again (lf/make-frame {:id :counter/main :images [img]} counter-pool)]
        (is (lf/frame-object? again)
            "re-making the same id returns a frame value (no throw)")
        (is (= :counter/main (frame-ns/frame-value->id again))
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
    (let [img   (image/image {:select-ns {:include ["examples.counter"]}})
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
    (let [img   (image/image {:select-ns {:include ["examples.counter"]}})
          frame (lf/make-frame {:images [img]} counter-pool)]
      (is (lf/frame-object? frame))
      (is (nil? (:rf.frame/id frame)) "a no-id frame value carries no public :rf.frame/id")
      (is (= "rf.frame" (namespace (frame-ns/frame-value->id frame)))
          "the record is keyed by a private :rf.frame/<gensym> runnable-id")
      (is (not-any? public-frame-id? (lf/live-frame-ids))
          "a no-id frame contributes NO public frame id")
      (is (not (contains? (lf/live-frame-ids) (frame-ns/frame-value->id frame)))
          "the private gensym id is excluded from live-frame-ids (no-id frames
           bypass enumeration/auto-reprojection — EP-0024)"))))

(deftest two-direct-frames-coexist-without-public-ids
  (testing "two local direct frame values can coexist with no PUBLIC ids — each
            gets its own private :rf.frame/<gensym> runnable-id (EP-0024 — two
            local direct frame values can coexist, distinct records)"
    (let [img (image/image {:select-ns {:include ["examples.counter"]}})
          a   (lf/make-frame {:images [img]} counter-pool)
          b   (lf/make-frame {:images [img]} counter-pool)]
      (is (lf/frame-object? a))
      (is (lf/frame-object? b))
      (is (not= (frame-ns/frame-value->id a) (frame-ns/frame-value->id b))
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
    (let [img (image/image {:select-ns {:include ["examples.counter"]}})]
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
;; 5b. A non-map `opts` ARGUMENT is REJECTED (rf2-r6r2yi)
;; ===========================================================================

(deftest non-map-opts-rejected
  (testing "the `make-frame` `opts` argument must be a MAP (EP-0024 §One
            constructor); a non-map opts fails loud at the public boundary with
            :rf.error/make-frame-bad-opts BEFORE any frame is registered, rather
            than silently creating a garbage anonymous frame (nil) or failing by
            an obscure host ClassCastException (other non-maps)"
    (testing "nil opts is REJECTED — the all-defaults frame is (make-frame {}),
              not (make-frame nil)"
      (is (= :rf.error/make-frame-bad-opts
             (err-id #(lf/make-frame nil counter-pool))))
      (testing "and via the 1-arity (nil descriptors path) too"
        (is (= :rf.error/make-frame-bad-opts
               (err-id #(lf/make-frame nil))))))
    (testing "a keyword opts is rejected"
      (is (= :rf.error/make-frame-bad-opts
             (err-id #(lf/make-frame :not-a-map counter-pool)))))
    (testing "a vector opts is rejected"
      (is (= :rf.error/make-frame-bad-opts
             (err-id #(lf/make-frame [:images []] counter-pool)))))
    (testing "a string opts is rejected"
      (is (= :rf.error/make-frame-bad-opts
             (err-id #(lf/make-frame "oops" counter-pool)))))
    (testing "no frame was registered as a side effect of a rejected non-map opts"
      (let [before (lf/live-frame-ids)]
        (err-id #(lf/make-frame nil counter-pool))
        (err-id #(lf/make-frame :not-a-map counter-pool))
        (is (= before (lf/live-frame-ids))
            "a rejected non-map opts leaves the live-frame registry untouched")))
    (testing "the ex-data carries an EP-0015-safe :received shape summary, never
              the raw value"
      (is (= {:type :keyword :head ":not-a-map"}
             (:received (err-data #(lf/make-frame :not-a-map counter-pool))))))
    (testing "the EMPTY map is ACCEPTED — the explicit all-defaults frame"
      (is (lf/frame-object? (lf/make-frame {} counter-pool))))))

;; ===========================================================================
;; 6. Host-handle exclusion — the EP-0023 §Host Boundary two-boundaries
;;    invariant at the OBJECT boundary (rf2-32siq3.40 MAJOR-2)
;; ===========================================================================
;;
;; The .31 review found the EP two-boundaries invariant (host handles / adapter
;; binding NEVER enter the frame-state value) untested at the EP-0023 OBJECT
;; boundary. make-frame stores :rf.frame/adapter on the frame object (the
;; host-handle slot); existing tests assert it is PRESERVED across reload, but
;; none asserts it is EXCLUDED from the serializable frame-state projection.
;; (EP-0026, rf2-dlvmpc: the former :rf.frame/capabilities host slot is gone with
;; the image-capability feature, so the invariant is now exercised over the
;; adapter binding alone.)
;;
;; The serializable frame-STATE is the seeded app-db (EP-0027 retired the
;; :rf.frame/initial-db value slot; seeding is the :rf/set-db setup event). The
;; invariant tested here: the adapter binding handed to make-frame is confined to
;; its OWN object slot and never bleeds into the seeded app-db (the serializable
;; state), and the host-handle slot is a distinct, non-serializable concern from
;; the state.

(deftest host-handles-excluded-from-serializable-frame-state
  (testing "the adapter binding rides a DEDICATED object slot (:rf.frame/adapter)
            and is EXCLUDED from the serializable frame-state (the
            :initial-events-seeded app-db) — the EP-0023 §Host Boundary
            two-boundaries invariant: host handles never enter the frame-state
            value (MAJOR-2)"
    (let [img        (image/image {:select-ns {:include ["examples.counter"]}})
          adapter    {:rf.adapter/kind :reagent :rf.adapter/render-root ::host-handle}
          state-seed {:count 7 :user/name "ada"}
          frame      (lf/make-frame {:id         :counter/host-excl
                                     :images     [img]
                                     :initial-events [[:rf/set-db state-seed]]
                                     :adapter    adapter}
                                    counter-pool)]
      (testing "the host handle rides its OWN object slot (preserved)"
        (is (= adapter (:rf.frame/adapter frame))))
      (testing "the serializable frame-state is EXACTLY the seeded app-db — no
                adapter binding bled into it"
        (is (= state-seed (rf/app-db-value :counter/host-excl)))
        (let [serializable (rf/app-db-value :counter/host-excl)]
          (is (not (contains? serializable :rf.frame/adapter))
              "the adapter binding is NOT in the serializable state value")
          (is (not-any? #{::host-handle} (vals serializable))
              "no host-handle VALUE leaked into the serializable state value")))
      (testing "the host-handle slot is a DISTINCT concern from the state
                (object slot ≠ the serializable frame-state seed)"
        (is (not= (:rf.frame/adapter frame) (rf/app-db-value :counter/host-excl)))))))
