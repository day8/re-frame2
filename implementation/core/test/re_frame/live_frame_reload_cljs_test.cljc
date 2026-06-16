(ns re-frame.live-frame-reload-cljs-test
  "EP-0023 §Hot Reload / §Default Image Semantics — the IMAGE HOT-RELOAD slice
  (rf2-32siq3.10): `rf/reload-images!` swaps the generation a frame runs WHILE
  PRESERVING FRAME MEMORY, and a source-store change reprojects affected
  EXPLICIT-image frames (not only default-image frames).

  Pins the bead's enumerated coverage:

    * `reload-images!` swaps the generation but PRESERVES frame memory
      (app-db/initial-db, capabilities, adapter, id — only `:rf.frame/generation`
      moves; frame object identity for every other slot is carried through);
    * resolution AFTER reload uses the NEW image (the swapped generation resolves
      the new descriptor; the old is gone / changed);
    * the reload REPORT names the added/changed/removed/retained `[kind id]` diff;
    * reload is FRAME-TARGETED — reloading one registered frame does not move a
      sibling that previously shared a generation object;
    * an `:id`-bearing reload updates the registry slot IN PLACE (the id keeps
      naming the same live context, now running the new generation);
    * a non-vector `:images` is REJECTED (`:rf.error/make-frame-bad-images`),
      and an unknown target FAILS LOUD (`:rf.error/reload-no-such-frame`);
    * a source-store `reg-*` change reprojects an EXPLICIT-`:include-ns` frame —
      `reproject-live-frames!` re-resolves it and swaps the new generation.

  Each fail-loud assertion checks the `:rf.error/id` discriminator (NEVER the
  message bytes — Spec 009 §The thrown-error shape rule 3).

  The reload/diff tests resolve against an explicit synthetic descriptor pool
  (the `reload-images!` 3-arity), so there is no live source-store wiring — the
  same decoupling idiom `live-frame-cljs-test` / `image-assembly-cljs-test` use.
  The reprojection test exercises the LIVE source store and so SNAPSHOTS +
  RESTORES it around the case (NOT `registrar/clear-all!`, which would wipe the
  shared node-test-bundle registrations — slice .9's note). `.cljc` ends
  `-cljs-test` so it rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image        :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.source-store :as source-store]
            [re-frame.live-frame   :as lf]))

;; ---------------------------------------------------------------------------
;; Fixture — clear the EP-0023 process-state atoms (the live-frame registry and
;; the framework-standard registry) per case. Does NOT touch the shared
;; registrar (slice .9 note); the source-store reprojection test snapshots +
;; restores the source store itself, locally.
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

;; Two pools selected by the SAME image (one explicit :include-ns over
;; "counter.core"), mirroring the realistic hot-reload case: a namespace
;; re-evaluates and replaces its OWN registrations. `:counter/inc` changes impl
;; (v1 → v2), `:counter/value` is byte-identical across both (same provenance ns
;; AND impl → retained), and `:counter/reset` is added only in v2. That yields a
;; concrete added/changed/removed/retained diff with honest retained semantics
;; (a descriptor unchanged in BOTH provenance and impl).
(def ^:private value-desc (reg-desc "counter.core" :sub :counter/value ::value))

(def ^:private pool-v1
  [(reg-desc "counter.core" :event :counter/inc ::inc-v1)
   value-desc])

(def ^:private pool-v2
  [(reg-desc "counter.core" :event :counter/inc   ::inc-v2)    ;; changed impl
   value-desc                                                  ;; identical → retained
   (reg-desc "counter.core" :event :counter/reset ::reset)])   ;; added

;; One image selects "counter.core"; the reload changes only the descriptor POOL
;; (the source store), not the image composition — exactly as a same-namespace
;; reg-* re-eval does. (reload-images! also replaces composition; the diff is
;; over the resolved generations either way.)
(def ^:private img (image/image {:id :counter/img :include-ns ["counter.core"]}))

;; ===========================================================================
;; 1. reload-images! swaps the generation but PRESERVES frame memory
;; ===========================================================================

(deftest reload-swaps-generation-preserving-frame-memory
  (testing "reload-images! replaces ONLY :rf.frame/generation; id, initial-db,
            capabilities, and adapter are carried through unchanged (EP-0023
            §Hot Reload — not a teardown/recreate)"
    (let [frame (lf/make-frame {:id :counter/main
                                :images [img]
                                :initial-db {:count 7}
                                :adapter ::reagent}
                               pool-v1)
          old-gen (lf/frame-generation frame)
          report  (lf/reload-images! :counter/main {:images [img]} pool-v2)
          reloaded (:rf.frame/frame report)]
      (testing "a NEW generation is on the reloaded object"
        (is (not (identical? old-gen (lf/frame-generation reloaded))))
        (is (not= old-gen (lf/frame-generation reloaded))))
      (testing "every OTHER frame slot is preserved (frame memory continues)"
        (is (= :counter/main (:rf.frame/id reloaded)))
        (is (= {:count 7} (:rf.frame/initial-db reloaded)))
        (is (= ::reagent (:rf.frame/adapter reloaded)))
        (is (lf/frame-object? reloaded))))))

;; ===========================================================================
;; 2. Resolution AFTER reload uses the NEW image
;; ===========================================================================

(deftest resolution-after-reload-uses-the-new-image
  (testing "the swapped generation resolves the NEW descriptor; the v1 impl is
            gone and v2's added id is present (EP-0023 — code changed, the VM
            kept its memory)"
    (let [frame    (lf/make-frame {:id :counter/main :images [img]} pool-v1)
          gen-v1   (lf/frame-generation frame)
          reloaded (:rf.frame/frame (lf/reload-images! :counter/main {:images [img]} pool-v2))
          gen-v2   (lf/frame-generation reloaded)]
      (testing "the v1 generation resolved the v1 impl (control)"
        (is (= ::inc-v1 (:handler-fn (asm/resolve-descriptor gen-v1 :event :counter/inc)))))
      (testing "after reload, :counter/inc resolves the v2 impl"
        (is (= ::inc-v2 (:handler-fn (asm/resolve-descriptor gen-v2 :event :counter/inc)))))
      (testing "the v2-only :counter/reset is now resolvable"
        (is (some? (asm/resolve-descriptor gen-v2 :event :counter/reset))))
      (testing "registry lookup returns the reloaded object (id keeps naming the
                same live context, now on the new generation)"
        (is (identical? reloaded (lf/live-frame :counter/main)))
        (is (= ::inc-v2 (:handler-fn (asm/resolve-descriptor
                                       (lf/frame-generation (lf/live-frame :counter/main))
                                       :event :counter/inc))))))))

;; ===========================================================================
;; 3. The reload REPORT names a concrete added/changed/removed/retained diff
;; ===========================================================================

(deftest reload-report-names-a-concrete-diff
  (testing "reload-images! returns a diff of [kind id] sets (EP-0023 §Hot
            Reload — \"A good reload result should be a concrete diff\")"
    (let [frame  (lf/make-frame {:id :counter/main :images [img]} pool-v1)
          {diff :rf.reload/diff} (lf/reload-images! :counter/main {:images [img]} pool-v2)]
      (testing ":added names the v2-only id"
        (is (contains? (:added diff) [:event :counter/reset])))
      (testing ":changed names the id whose impl changed (v1 → v2)"
        (is (contains? (:changed diff) [:event :counter/inc])))
      (testing ":retained names the id whose descriptor is unchanged"
        (is (contains? (:retained diff) [:sub :counter/value])))
      (testing ":removed is empty here (v1's ids all survive in v2)"
        (is (empty? (:removed diff))))
      (testing "a changed id is NOT also retained, and vice versa (disjoint)"
        (is (not (contains? (:retained diff) [:event :counter/inc])))
        (is (not (contains? (:changed diff)  [:sub :counter/value])))))))

(deftest reload-report-names-removed-ids
  (testing "reloading to a NARROWER image reports the dropped ids as :removed"
    (let [narrow (image/image {:id :counter/narrow :include-ns ["counter.narrow"]})
          pool-n [(reg-desc "counter.narrow" :event :counter/inc ::inc-n)]
          frame  (lf/make-frame {:id :counter/main :images [img]} pool-v1)
          {diff :rf.reload/diff} (lf/reload-images! :counter/main {:images [narrow]} pool-n)]
      (is (contains? (:removed diff) [:sub :counter/value])
          ":counter/value is gone after reloading to the narrower image")
      (is (contains? (:changed diff) [:event :counter/inc])
          ":counter/inc survives with a different impl → changed"))))

;; ===========================================================================
;; 4. Reload is FRAME-TARGETED — it does not move a sibling frame
;; ===========================================================================

(deftest reload-is-frame-targeted-does-not-move-siblings
  (testing "two frames created from the SAME image inputs; reloading one does
            NOT move the other (EP-0023 §Image — reload is frame-targeted; a
            reload of :counter/left must not move :counter/right)"
    (let [left  (lf/make-frame {:id :counter/left  :images [img]} pool-v1)
          right (lf/make-frame {:id :counter/right :images [img]} pool-v1)
          right-gen-before (lf/frame-generation right)]
      (lf/reload-images! :counter/left {:images [img]} pool-v2)
      (testing "right's generation is UNTOUCHED (still resolves v1)"
        (is (identical? right-gen-before (lf/frame-generation (lf/live-frame :counter/right))))
        (is (= ::inc-v1 (:handler-fn (asm/resolve-descriptor
                                       (lf/frame-generation (lf/live-frame :counter/right))
                                       :event :counter/inc)))))
      (testing "left moved to v2"
        (is (= ::inc-v2 (:handler-fn (asm/resolve-descriptor
                                       (lf/frame-generation (lf/live-frame :counter/left))
                                       :event :counter/inc))))))))

;; ===========================================================================
;; 5. A direct (no-id) frame object reloads too — caller holds the copy
;; ===========================================================================

(deftest reload-a-direct-frame-object
  (testing "reload-images! accepts a direct frame OBJECT as the target; the
            registry is untouched (a no-id frame never entered it) and the
            reloaded copy is returned for the caller to hold"
    (let [frame    (lf/make-frame {:images [img]} pool-v1)
          reloaded (:rf.frame/frame (lf/reload-images! frame {:images [img]} pool-v2))]
      (is (empty? (lf/live-frame-ids)) "registry stays empty for a no-id reload")
      (is (= ::inc-v2 (:handler-fn (asm/resolve-descriptor
                                     (lf/frame-generation reloaded) :event :counter/inc))))
      (testing "the original object is unchanged (immutable value; caller swaps
                its own reference)"
        (is (= ::inc-v1 (:handler-fn (asm/resolve-descriptor
                                       (lf/frame-generation frame) :event :counter/inc))))))))

;; ===========================================================================
;; 6. Fail-loud — bad :images and unknown target
;; ===========================================================================

(deftest reload-non-vector-images-rejected
  (testing "reload-images! :images must be a VECTOR (the one spelling), exactly
            as make-frame; a bare image map throws :rf.error/make-frame-bad-images"
    (let [_ (lf/make-frame {:id :counter/main :images [img]} pool-v1)]
      (is (= :rf.error/make-frame-bad-images
             (err-id #(lf/reload-images! :counter/main {:images img} pool-v2)))))))

(deftest reload-unknown-target-fails-loud
  (testing "reloading a target that names no live frame throws
            :rf.error/reload-no-such-frame (fail-loud — reload targets ONE frame)"
    (is (= :rf.error/reload-no-such-frame
           (err-id #(lf/reload-images! :counter/nope {:images [img]} pool-v2))))))

;; ===========================================================================
;; 7. generation-diff is pure and correct in isolation
;; ===========================================================================

(deftest generation-diff-is-pure-and-correct
  (testing "generation-diff classifies every [kind id] as added/changed/removed
            /retained by descriptor value equality"
    (let [gen-a (asm/assemble [img] pool-v1)
          gen-b (asm/assemble [img] pool-v2)
          diff  (lf/generation-diff gen-a gen-b)]
      (is (= #{[:event :counter/reset]} (:added diff)))
      (is (= #{[:event :counter/inc]}   (:changed diff)))
      (is (= #{[:sub :counter/value]}   (:retained diff)))
      (is (= #{} (:removed diff))))
    (testing "two equal generations diff to all-retained, nothing else"
      (let [g (asm/assemble [img] pool-v1)
            d (lf/generation-diff g g)]
        (is (empty? (:added d)))
        (is (empty? (:changed d)))
        (is (empty? (:removed d)))
        (is (= #{[:event :counter/inc] [:sub :counter/value]} (:retained d)))))))

;; ===========================================================================
;; 8. Source-store change reprojects an EXPLICIT-image frame (not only default)
;; ===========================================================================

(deftest source-store-change-reprojects-an-explicit-image-frame
  (testing "a reg-* change in a namespace an explicit :include-ns image selects
            reprojects THAT frame's generation (EP-0023 §Default Image Semantics
            — reproject affected EXPLICIT-image frames, not only default-image
            frames). Uses the LIVE source store; snapshot + restore around the
            case (NOT registrar/clear-all!)."
    (let [snapshot @source-store/kind->id->ns->descriptor]
      (try
        ;; A registration authored in an explicit namespace, recorded into the
        ;; LIVE source store (the same path reg-* writes through).
        (source-store/record-descriptor!
          :event :explicit/inc
          {:rf.provenance/ns "explicit.feature" :kind :event :id :explicit/inc
           :handler-fn ::inc-original})
        (let [img   (image/image {:id :explicit/img :include-ns ["explicit.feature"]})
              frame (lf/make-frame {:id :explicit/main :images [img]})
              gen-before (lf/frame-generation frame)]
          (testing "the frame resolves the original impl against the live store"
            (is (= ::inc-original
                   (:handler-fn (asm/resolve-descriptor gen-before :event :explicit/inc)))))
          ;; Hot reload of that source: re-eval the SAME (kind,id,namespace) slot
          ;; with a new impl (the same-namespace replacement path).
          (source-store/record-descriptor!
            :event :explicit/inc
            {:rf.provenance/ns "explicit.feature" :kind :event :id :explicit/inc
             :handler-fn ::inc-reloaded})
          (let [moved (lf/reproject-live-frames!)]
            (testing "reproject reports the EXPLICIT-image frame as moved"
              (is (contains? moved :explicit/main))
              (is (contains? (:changed (get moved :explicit/main)) [:event :explicit/inc])))
            (testing "the live frame now resolves the RELOADED impl through its
                      swapped generation"
              (is (= ::inc-reloaded
                     (:handler-fn (asm/resolve-descriptor
                                    (lf/frame-generation (lf/live-frame :explicit/main))
                                    :event :explicit/inc)))))))
        (finally
          (reset! source-store/kind->id->ns->descriptor snapshot))))))

(deftest reproject-leaves-unchanged-frames-untouched
  (testing "reproject-live-frames! does NOT swap a frame whose composition
            re-resolves byte-for-byte — no spurious movement"
    (let [snapshot @source-store/kind->id->ns->descriptor]
      (try
        (source-store/record-descriptor!
          :event :stable/inc
          {:rf.provenance/ns "stable.feature" :kind :event :id :stable/inc
           :handler-fn ::stable})
        (let [img   (image/image {:id :stable/img :include-ns ["stable.feature"]})
              frame (lf/make-frame {:id :stable/main :images [img]})
              gen-before (lf/frame-generation frame)
              ;; No source change between creation and reproject.
              moved (lf/reproject-live-frames!)]
          (testing "no frame moved"
            (is (empty? moved)))
          (testing "the frame's generation is the SAME object (untouched)"
            (is (identical? gen-before (lf/frame-generation (lf/live-frame :stable/main))))))
        (finally
          (reset! source-store/kind->id->ns->descriptor snapshot))))))

;; ---- the REMOVED leg (rf2-32siq3.40 minor a) ------------------------------
;;
;; The .31 review found reproject coverage exercised only the impl-CHANGED
;; leg (source-store-change-reprojects-an-explicit-image-frame) and the
;; UNCHANGED leg (reproject-leaves-unchanged-frames-untouched). The REMOVED
;; leg — a descriptor the frame's image SELECTED is FORGOTTEN from the source
;; store (the `forget-descriptor!` path) — was untested. After the forget,
;; reproject must re-resolve the frame to a NARROWER generation, report the
;; dropped id under the diff's `:removed`, and the swapped generation must no
;; longer resolve it.

(deftest reproject-removed-leg-forgets-a-selected-descriptor
  (testing "reproject-live-frames! after a SELECTED descriptor is forgotten from
            the source store re-resolves the frame to a narrower generation and
            names the dropped id under :removed (EP-0023 §Default Image Semantics
            — a source-store change reprojects affected frames; the removed leg).
            Uses the LIVE source store; snapshot + restore (NOT clear-all!)."
    (let [snapshot @source-store/kind->id->ns->descriptor]
      (try
        ;; Two registrations the explicit image selects: one will be forgotten.
        (source-store/record-descriptor!
          :event :rm/inc
          {:rf.provenance/ns "removal.feature" :kind :event :id :rm/inc
           :handler-fn ::rm-inc})
        (source-store/record-descriptor!
          :sub :rm/value
          {:rf.provenance/ns "removal.feature" :kind :sub :id :rm/value
           :handler-fn ::rm-value})
        (let [img   (image/image {:id :rm/img :include-ns ["removal.feature"]})
              frame (lf/make-frame {:id :rm/main :images [img]})
              gen-before (lf/frame-generation frame)]
          (testing "both selected ids resolve before the forget (control)"
            (is (some? (asm/resolve-descriptor gen-before :event :rm/inc)))
            (is (some? (asm/resolve-descriptor gen-before :sub :rm/value))))
          ;; Forget exactly the selected :sub slot (targeted removal, mirrors a
          ;; registrar/unregister! of a registration the image was selecting).
          (source-store/forget-descriptor! :sub :rm/value "removal.feature")
          (let [moved (lf/reproject-live-frames!)]
            (testing "reproject reports the frame as moved, naming the dropped id
                      under :removed (the removed leg — not :changed/:added)"
              (is (contains? moved :rm/main))
              (let [diff (get moved :rm/main)]
                (is (contains? (:removed diff) [:sub :rm/value])
                    "the forgotten selected id appears under :removed")
                (is (not (contains? (:changed diff) [:sub :rm/value])))
                (is (not (contains? (:added diff)   [:sub :rm/value])))
                (is (contains? (:retained diff) [:event :rm/inc])
                    ":rm/inc was untouched → retained, not removed")))
            (testing "the swapped generation no longer resolves the forgotten id"
              (let [gen-after (lf/frame-generation (lf/live-frame :rm/main))]
                (is (nil? (asm/resolve-descriptor gen-after :sub :rm/value))
                    "the forgotten descriptor is gone from the reprojected generation")
                (is (some? (asm/resolve-descriptor gen-after :event :rm/inc))
                    ":rm/inc still resolves — the frame narrowed, it did not empty")))))
        (finally
          (reset! source-store/kind->id->ns->descriptor snapshot))))))

;; ---- composed multi-image reproject, one member ns changes (minor b) ------
;;
;; The .31 review found the reproject coverage used only a SINGLE-image frame.
;; A COMPOSED frame (two images, each selecting a DIFFERENT member namespace)
;; must reproject when ONLY ONE member ns changes: the changed member's id is
;; :changed in the diff, the untouched member's id stays :retained, and both
;; resolve in the swapped generation (the composition is preserved, only the
;; changed slice moves).

(deftest reproject-composed-frame-on-one-member-ns-change
  (testing "a frame composed of TWO images (each over a distinct member ns)
            reprojects when ONLY ONE member ns's source changes: the changed
            member's id is :changed, the untouched member's id is :retained, and
            both still resolve in the swapped generation (EP-0023 §Default Image
            Semantics — composed images containing the changed slot reproject).
            Uses the LIVE source store; snapshot + restore (NOT clear-all!)."
    (let [snapshot @source-store/kind->id->ns->descriptor]
      (try
        ;; Member A and member B live in DIFFERENT namespaces; the composed
        ;; frame selects both via two images.
        (source-store/record-descriptor!
          :event :compose.a/go
          {:rf.provenance/ns "compose.member-a" :kind :event :id :compose.a/go
           :handler-fn ::a-original})
        (source-store/record-descriptor!
          :event :compose.b/go
          {:rf.provenance/ns "compose.member-b" :kind :event :id :compose.b/go
           :handler-fn ::b-stable})
        (let [img-a (image/image {:id :compose/a :include-ns ["compose.member-a"]})
              img-b (image/image {:id :compose/b :include-ns ["compose.member-b"]})
              frame (lf/make-frame {:id :compose/main :images [img-a img-b]})
              gen-before (lf/frame-generation frame)]
          (testing "both members resolve in the composed generation (control)"
            (is (= ::a-original (:handler-fn (asm/resolve-descriptor gen-before :event :compose.a/go))))
            (is (= ::b-stable   (:handler-fn (asm/resolve-descriptor gen-before :event :compose.b/go)))))
          ;; Re-eval ONLY member A's namespace (the same (kind,id,ns) slot, new impl).
          ;; Member B's source slot is untouched.
          (source-store/record-descriptor!
            :event :compose.a/go
            {:rf.provenance/ns "compose.member-a" :kind :event :id :compose.a/go
             :handler-fn ::a-reloaded})
          (let [moved (lf/reproject-live-frames!)]
            (testing "the composed frame is reported as moved"
              (is (contains? moved :compose/main)))
            (let [diff (get moved :compose/main)]
              (testing "only the changed member's id is :changed"
                (is (contains? (:changed diff) [:event :compose.a/go])))
              (testing "the untouched member's id is :retained (not :changed)"
                (is (contains? (:retained diff) [:event :compose.b/go]))
                (is (not (contains? (:changed diff) [:event :compose.b/go])))))
            (testing "the swapped generation resolves BOTH members — A reloaded,
                      B preserved (composition kept, only the changed slice moved)"
              (let [gen-after (lf/frame-generation (lf/live-frame :compose/main))]
                (is (= ::a-reloaded (:handler-fn (asm/resolve-descriptor gen-after :event :compose.a/go))))
                (is (= ::b-stable   (:handler-fn (asm/resolve-descriptor gen-after :event :compose.b/go))))))))
        (finally
          (reset! source-store/kind->id->ns->descriptor snapshot))))))
