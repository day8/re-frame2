(ns re-frame.frame-resolution-cljs-test
  "EP-0023 §Frame-derived live registration resolution (rf2-32siq3.9): live
  registration lookup — dispatch (event), subscribe (sub), fx, cofx,
  view/resource lookup — derives from the TARGET frame's resolved image
  generation, not the global/default registrar.

  > target frame -> resolved image generation -> registration resolution

  This is the prerequisite that makes the same-id / different-image use cases
  work: two frames running different images resolve the same `[kind id]` to
  their OWN image's descriptor. The default fall-through (absence-is-default)
  leaves every existing caller unaffected.

  The HEADLINE case: two frame objects each carrying a DIFFERENT image both
  registering `[:event :boot/init]` (and friends) resolve that id to their OWN
  image's descriptor when their generation is in scope. `registrar/lookup` is
  the single chokepoint dispatch / subscribe / fx / cofx / view / resource all
  funnel through, so the seam is exercised through `registrar/lookup` /
  `registrar/handler` / `registrar/registrations` / `registrar/ids` for every
  kind — proving the binding routes ALL of them coherently (all-or-nothing).

  Resolution runs against explicit synthetic descriptor pools (the `make-frame`
  2-arity), so there is no live source-store wiring — the same decoupling idiom
  `image-assembly-cljs-test` / `live-frame-cljs-test` use. Fixtures clear the
  framework-standard registry and the process-default registrar between cases.
  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image          :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.registrar      :as registrar]
            [re-frame.test-support   :as test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.live-frame     :as lf]))

;; ---------------------------------------------------------------------------
;; Fixtures.
;;
;; The default registrar is snapshot/restored via `make-reset-runtime-fixture`
;; — NOT `registrar/clear-all!`, which is hostile to CLJS isolation (it wipes
;; framework-shipped ns-load registrations a sibling test ns depends on, and
;; CLJS has no `(require … :reload)` to reinstate them — see
;; `re-frame.test-support`). Snapshot/restore rolls back THIS test's
;; registrations (`:app/boot`, `:global/only`, …) while leaving framework state
;; intact, run-order-independently.
;;
;; EP-0024 (rf2-tu2vr7): `make-frame` returns a RUNNABLE image-loaded frame VALUE
;; — it creates its backing runnable record (app-db / queue / sub-cache) via
;; `reg-frame`, which needs a substrate adapter — so the plain-atom adapter is
;; installed. The resolved generation lives ON that record (the `:generation`
;; slot), read by id; the two-registry model collapsed to ONE, so the runtime
;; fixture's `(reset! frames {})` clears every record AND its generation — no
;; separate live-frame index to clear (rf2-ji3tvy). These cases exercise pure
;; `(kind, id)`
;; resolution through the `call-with-frame-resolution` seam (they do not run a
;; cascade), but constructing the frame now allocates a state container.
;;
;; The framework-standard registry is this wave's OWN process-state defonce atom
;; (not framework ns-load state a sibling depends on), so a direct
;; `clear-standards!` is safe and keeps generation-routing tested against a known
;; baseline.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t]
    (asm/clear-standards!)
    (t)
    (asm/clear-standards!)))

;; ---------------------------------------------------------------------------
;; Helpers — synthetic REGISTERED descriptors (the source-store output shape
;; the selector consumes). `:handler-fn` is the registrar impl slot, so a
;; resolved descriptor is shape-identical to a registered registrar entry.
;; ---------------------------------------------------------------------------

(defn- reg-desc
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

;; ===========================================================================
;; 1. The binding seam routes registrar/lookup through a frame's generation
;; ===========================================================================

(deftest lookup-derives-from-the-bound-frame-generation
  (testing "with no frame generation in scope, registrar/lookup resolves
            through the registrar atom (the absence-is-default path)"
    ;; Register a descriptor into the process-default registrar.
    (registrar/register! :event :app/boot {:handler-fn ::default-boot})
    (is (= ::default-boot (:handler-fn (registrar/lookup :event :app/boot)))
        "the default registrar path resolves the globally-registered handler")
    (testing "binding a frame's generation routes the SAME id to the FRAME'S
              own image descriptor instead"
      (let [pool  [(reg-desc "examples.app" :event :app/boot ::image-boot)]
            img   (image/image {:select-ns {:include ["examples.app"]}})
            frame (lf/make-frame {:images [img]} pool)]
        (lf/call-with-frame-resolution frame
          (fn []
            (is (= ::image-boot (:handler-fn (registrar/lookup :event :app/boot)))
                "inside the seam, lookup resolves the frame's image descriptor")))
        (testing "OUTSIDE the seam, lookup reverts to the registrar atom path"
          (is (= ::default-boot (:handler-fn (registrar/lookup :event :app/boot)))))))))

;; ===========================================================================
;; 2. THE HEADLINE — two frames running DIFFERENT images resolve the same
;;    [kind id] to their OWN image's descriptor
;; ===========================================================================

(deftest two-frames-different-images-resolve-same-id-to-own-descriptor
  (testing "two frame objects running DIFFERENT images both registering
            [:event :boot/init] resolve that id to their OWN image's descriptor
            (EP-0023 §Independent Surfaces On One Page — the heart of the
            same-id story)"
    (let [todo-pool    [(reg-desc "examples.todo"    :event :boot/init ::todo-boot)]
          counter-pool [(reg-desc "examples.counter" :event :boot/init ::counter-boot)]
          todo-img     (image/image {:id :examples/todo
                                     :select-ns {:include ["examples.todo"]}})
          counter-img  (image/image {:id :examples/counter
                                     :select-ns {:include ["examples.counter"]}})
          todo-frame    (lf/make-frame {:id :todo/main    :images [todo-img]}    todo-pool)
          counter-frame (lf/make-frame {:id :counter/main :images [counter-img]} counter-pool)]
      (testing "the TODO frame resolves :boot/init to the todo image's handler"
        (lf/call-with-frame-resolution todo-frame
          (fn []
            (is (= ::todo-boot (registrar/handler :event :boot/init))))))
      (testing "the COUNTER frame resolves the SAME id to the counter image's handler"
        (lf/call-with-frame-resolution counter-frame
          (fn []
            (is (= ::counter-boot (registrar/handler :event :boot/init))))))
      (testing "neither frame's id leaks into the other (no global clobber):
                resolving each frame again still yields its own descriptor"
        (lf/call-with-frame-resolution todo-frame
          (fn [] (is (= ::todo-boot (registrar/handler :event :boot/init)))))
        (lf/call-with-frame-resolution counter-frame
          (fn [] (is (= ::counter-boot (registrar/handler :event :boot/init)))))))))

;; ===========================================================================
;; 3. dispatch / sub / fx / cofx all derive from the target frame
;;    (registrar/lookup is the single chokepoint for every kind — all-or-nothing)
;; ===========================================================================

(deftest event-sub-fx-cofx-view-all-derive-from-the-frame-generation
  (testing "every registration KIND dispatch / subscribe / fx / cofx /
            view / resource resolves through (event :sub :fx :cofx :view
            :resource) derives from the bound frame generation — proving the
            single binding routes them ALL coherently (all-or-nothing)"
    (let [pool  [(reg-desc "examples.feat" :event    :feat/go        ::ev)
                 (reg-desc "examples.feat" :sub      :feat/value     ::sub)
                 (reg-desc "examples.feat" :fx       :feat/save      ::fx)
                 (reg-desc "examples.feat" :cofx     :feat/now       ::cofx)
                 (reg-desc "examples.feat" :view     :feat/root      ::view)
                 (reg-desc "examples.feat" :resource :feat/by-id     ::resource)]
          img   (image/image {:select-ns {:include ["examples.feat"]}})
          frame (lf/make-frame {:images [img]} pool)]
      (lf/call-with-frame-resolution frame
        (fn []
          (testing "event (dispatch)"    (is (= ::ev       (registrar/handler :event    :feat/go))))
          (testing "sub (subscribe)"     (is (= ::sub      (registrar/handler :sub      :feat/value))))
          (testing "fx"                  (is (= ::fx       (registrar/handler :fx       :feat/save))))
          (testing "cofx"                (is (= ::cofx     (registrar/handler :cofx     :feat/now))))
          (testing "view"                (is (= ::view     (registrar/handler :view     :feat/root))))
          (testing "resource"            (is (= ::resource (registrar/handler :resource :feat/by-id))))
          (testing "an id NOT in the image resolves nil (the frame's image is
                    the registration universe, not the global registrar)"
            (registrar/register! :event :other/not-in-image {:handler-fn ::global-only})
            (is (nil? (registrar/lookup :event :other/not-in-image))
                "a globally-registered id absent from the frame's image is nil
                 under generation routing")))))))

;; ===========================================================================
;; 4. registrations / ids project from the bound generation
;; ===========================================================================

(deftest registrations-and-ids-project-from-the-generation
  (testing "the registrar query API (registrations / ids) projects from the
            bound generation — a frame-scoped query sees only the frame's image
            registrations of that kind"
    ;; A globally-registered :event the frame's image does NOT carry.
    (registrar/register! :event :global/only {:handler-fn ::global})
    (let [pool  [(reg-desc "examples.q" :event :q/a ::a)
                 (reg-desc "examples.q" :event :q/b ::b)
                 (reg-desc "examples.q" :sub   :q/s ::s)]
          img   (image/image {:select-ns {:include ["examples.q"]}})
          frame (lf/make-frame {:images [img]} pool)]
      (testing "the default (unbound) registrations sees the global registry"
        (is (contains? (registrar/registrations :event) :global/only))
        (is (not (contains? (registrar/registrations :event) :q/a))))
      (lf/call-with-frame-resolution frame
        (fn []
          (testing "the generation-scoped query sees ONLY the frame's image ids"
            (is (= #{:q/a :q/b} (registrar/ids :event))
                "ids :event projects the frame's image events, not the global :global/only")
            (is (= #{:q/s} (registrar/ids :sub)))
            (is (not (contains? (registrar/registrations :event) :global/only))
                "the globally-registered id is invisible under the frame's generation")
            (testing "the filtered arity also projects from the generation"
              (is (= #{:q/a} (set (keys (registrar/registrations
                                          :event
                                          #(= ::a (:handler-fn %)))))))))))
      (testing "after the seam, the query reverts to the global registry"
        (is (contains? (registrar/registrations :event) :global/only))))))

;; ===========================================================================
;; 5. Absence-is-default — a nil / non-object / no-generation target binds
;;    nothing; resolution falls through to the registrar atom path
;; ===========================================================================

(deftest absence-is-default-leaves-existing-callers-unaffected
  (testing "a nil target, a non-object value, or an object with no generation
            binds NOTHING — resolution falls through to the registrar atom path
            (the load-bearing absence-is-default fall-through)"
    (registrar/register! :event :app/boot {:handler-fn ::default-boot})
    (testing "a nil frame target runs the thunk on the default registrar path"
      (lf/call-with-frame-resolution nil
        (fn []
          (is (= ::default-boot (:handler-fn (registrar/lookup :event :app/boot))))
          (is (nil? registrar/*generation*) "no generation is bound for a nil target"))))
    (testing "a non-object value (a frame-id keyword) binds nothing"
      (lf/call-with-frame-resolution :counter/main
        (fn []
          (is (= ::default-boot (:handler-fn (registrar/lookup :event :app/boot))))
          (is (nil? registrar/*generation*)))))
    (testing "a frame object carrying NO generation binds nothing"
      ;; A hand-built frame-object marker with no :rf.frame/generation slot.
      (let [no-gen-obj {:rf.frame/object true}]
        (lf/call-with-frame-resolution no-gen-obj
          (fn []
            (is (= ::default-boot (:handler-fn (registrar/lookup :event :app/boot))))
            (is (nil? registrar/*generation*))))))))

(deftest frame-resolution-generation-reads-the-target-generation
  (testing "frame-resolution-generation returns a frame object's generation and
            nil for any non-frame-object / no-generation target"
    (let [pool  [(reg-desc "examples.g" :event :g/go ::go)]
          img   (image/image {:select-ns {:include ["examples.g"]}})
          frame (lf/make-frame {:images [img]} pool)]
      (is (= (lf/frame-generation frame) (lf/frame-resolution-generation frame)))
      (is (nil? (lf/frame-resolution-generation nil)))
      (is (nil? (lf/frame-resolution-generation :counter/main)))
      (is (nil? (lf/frame-resolution-generation {:rf.frame/object true}))
          "a frame object with no generation slot yields nil (absence-is-default)"))))

;; ===========================================================================
;; 6. Same image in two frames resolves identically (shared behaviour) — the
;;    complement of the same-id/different-image case
;; ===========================================================================

(deftest same-image-two-frames-resolve-identically
  (testing "two frames running the SAME image resolve the same id to the same
            descriptor (same behaviour, different memory — EP-0023 §Same
            Behavior, Many Instances)"
    (let [pool  [(reg-desc "examples.counter" :event :counter/inc ::inc)]
          img   (image/image {:select-ns {:include ["examples.counter"]}})
          left  (lf/make-frame {:id :counter/left  :images [img]} pool)
          right (lf/make-frame {:id :counter/right :images [img]} pool)]
      (lf/call-with-frame-resolution left
        (fn [] (is (= ::inc (registrar/handler :event :counter/inc)))))
      (lf/call-with-frame-resolution right
        (fn [] (is (= ::inc (registrar/handler :event :counter/inc))))))))

;; ===========================================================================
;; 7. Coherence — a nested resolution stays in the frame's generation across
;;    the cascade (the binding covers the WHOLE thunk, not just the top lookup)
;; ===========================================================================

(deftest nested-resolution-stays-in-the-frames-generation
  (testing "the binding covers the WHOLE thunk: a lookup issued from a nested
            call inside the seam still resolves through the frame's generation
            (ALL-OR-NOTHING coherence — the event handler, its cofx, and its fx
            all resolve in the same image)"
    (let [pool  [(reg-desc "examples.c" :event :c/go   ::go)
                 (reg-desc "examples.c" :cofx  :c/now  ::now)
                 (reg-desc "examples.c" :fx    :c/save ::save)]
          img   (image/image {:select-ns {:include ["examples.c"]}})
          frame (lf/make-frame {:images [img]} pool)
          ;; A nested fn that resolves a DIFFERENT kind, simulating the cofx
          ;; injection / fx walk that runs deeper in the cascade.
          resolve-fx (fn [] (registrar/handler :fx :c/save))]
      (lf/call-with-frame-resolution frame
        (fn []
          (is (= ::go (registrar/handler :event :c/go)))
          ;; cofx injection (runs as an interceptor :before inside the cascade)
          (is (= ::now (registrar/handler :cofx :c/now)))
          ;; the fx walk (runs post-commit inside the SAME call) — nested resolve
          (is (= ::save (resolve-fx))
              "a nested fx resolution stays in the frame's generation"))))))
