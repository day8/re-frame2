(ns re-frame.live-frame-reload-cljs-test
  "EP-0023 §Hot Reload / §Default Image Semantics — the IMAGE HOT-RELOAD slice
  (rf2-32siq3.10): `rf/reload-images!` swaps the generation a frame runs WHILE
  PRESERVING FRAME MEMORY, and a source-store change reprojects affected
  EXPLICIT-image frames (not only default-image frames).

  Pins the bead's enumerated coverage:

    * `reload-images!` swaps the generation but PRESERVES frame memory — EP-0024
      (rf2-tu2vr7): the generation lives on the frame record in the ONE
      `frame/frames` registry (the `:generation` slot), swapped by id via
      `frame/set-generation!`; app-db / durable state continue, the id keeps
      naming the same live context, and the returned `:rf.frame/frame` is a fresh
      frame VALUE for that id (compare by `rf/frame-value->id`, not `identical?`);
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
            [re-frame.core         :as rf]
            [re-frame.frame        :as frame]
            [re-frame.image        :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.interop      :as interop]
            [re-frame.registrar    :as registrar]
            [re-frame.source-store :as source-store]
            [re-frame.live-frame   :as lf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; Fixture — clear the framework-standard registry per case. The runtime fixture
;; (`make-reset-runtime-fixture`) snapshots/restores the registrar and resets
;; `frame/frames`, so image-loaded frame records do not leak across cases; the
;; source-store reprojection test snapshots + restores the source store locally.
;;
;; EP-0024 (rf2-tu2vr7): the two-registry model collapsed to ONE — the resolved
;; image GENERATION lives ON the frame record in the single `frame/frames`
;; registry (the `:generation` slot), and the former second live-frame registry
;; dissolved, so `clear-live-frames!` is now a no-op kept for the fixtures that
;; call it. `make-frame` creates/updates a RUNNABLE record (app-db / queue /
;; sub-cache) via `reg-frame`, which needs a substrate adapter — so the
;; plain-atom adapter is installed via the runtime fixture. These cases assert
;; the reload/diff/reproject contract; the backing record is an allocation side
;; effect they do not otherwise inspect.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
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
  (testing "reload-images! swaps ONLY the resolved generation on the frame's
            record; the id keeps naming the same live context and durable frame
            MEMORY (app-db) continues unchanged (EP-0024 §One live frame registry
            / EP-0023 §Hot Reload — not a teardown/recreate)"
    (let [frame-val (lf/make-frame {:id :counter/main
                                    :images [img]
                                    :initial-db {:count 7}
                                    :adapter ::reagent}
                                   pool-v1)
          old-gen (lf/frame-generation frame-val)
          report  (lf/reload-images! :counter/main {:images [img]} pool-v2)
          reloaded (:rf.frame/frame report)]
      (testing "a NEW generation is on the record (read by id) after the swap"
        (is (not (identical? old-gen (lf/frame-generation reloaded))))
        (is (not= old-gen (lf/frame-generation reloaded))))
      (testing "the reloaded handle is a frame VALUE naming the SAME id (the id
                keeps naming the same live context)"
        (is (lf/frame-object? reloaded))
        (is (= :counter/main (rf/frame-value->id reloaded))))
      (testing "durable frame memory continues: the app-db seeded at creation
                survives the reload (only the generation moved, the record was
                NOT torn down and recreated)"
        (is (= {:count 7} (rf/app-db-value :counter/main)))
        (testing "the capabilities the frame was created with persist on the
                  record (re-checkable by id after the swap)"
          (is (nil? (frame/frame-capabilities :counter/main))
              "no :capabilities were supplied, so the record carries none"))))))

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
      (testing "registry lookup resolves the reloaded frame (id keeps naming the
                same live context, now on the new generation — EP-0024:
                live-frame reconstructs a fresh value from the record, so compare
                by id)"
        (is (= :counter/main (rf/frame-value->id (lf/live-frame :counter/main))))
        (is (= (rf/frame-value->id reloaded)
               (rf/frame-value->id (lf/live-frame :counter/main))))
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
  (testing "reload-images! accepts a direct frame VALUE as the target; the
            reloaded value is returned for the caller to hold AND the swap lands
            on the record keyed by the value's PRIVATE runnable-id. A no-id frame
            contributes no PUBLIC id, but its record DOES carry a generation — and
            dispatch/subscribe re-resolve the generation FROM that record by id,
            so the swap must move it to v2 (rf2-3az1vn P1 / EP-0024: the
            generation lives on the ONE record, read by id)"
    (let [frame    (lf/make-frame {:images [img]} pool-v1)
          runnable (:rf.frame/runnable-id frame)
          reloaded (:rf.frame/frame (lf/reload-images! frame {:images [img]} pool-v2))]
      (is (not-any? #(not= "rf.frame" (namespace %)) (lf/live-frame-ids))
          "a no-id frame contributes no PUBLIC id (its private :rf.frame/<gensym>
           id carries a generation and so appears in live-frame-ids — EP-0024)")
      (testing "the record keyed by the gensym runnable-id now resolves the
                RELOADED (v2) generation, not the stale v1 one"
        (is (some? runnable) "a no-id value still carries a private runnable-id")
        (is (= runnable (rf/frame-value->id (lf/live-frame runnable)))
            "(live-frame runnable-id) reconstructs the value for the same record")
        (is (= ::inc-v2 (:handler-fn (asm/resolve-descriptor
                                       (lf/frame-generation (lf/live-frame runnable))
                                       :event :counter/inc)))
            "(live-frame runnable-id) resolves the v2 image — the record is no
             longer stale"))
      (is (= ::inc-v2 (:handler-fn (asm/resolve-descriptor
                                     (lf/frame-generation reloaded) :event :counter/inc))))
      (testing "the ORIGINAL handle also resolves v2: under EP-0024 both handles
                collapse to the same runnable-id, whose ONE record now holds the
                reloaded generation (no stale-handle divergence — the generation
                is read from the record by id, never embedded on the value)"
        (is (= ::inc-v2 (:handler-fn (asm/resolve-descriptor
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

;; ===========================================================================
;; 9. AUTO-reprojection: a `reg-*` change reprojects the affected explicit-image
;;    frame WITHOUT a manual reproject-live-frames! call (rf2-h4q6cy)
;; ===========================================================================
;;
;; The .28 B1 finding: `reproject-live-frames!` implements the EP-0023 headline
;; guarantee but NOTHING called it — no hook fired on `reg-*`, so a hot-reload of
;; an `:include-ns`-selected namespace left the running frame on its STALE
;; generation until some external reproject/reload ran. rf2-h4q6cy wires
;; `registrar/add-registration-hook!` → mark-dirty + coalesced `next-tick`
;; flush, so an ordinary `reg-*` re-eval swaps the affected frame automatically.
;;
;; These tests drive the change through `registrar/register!` (the path every
;; `reg-*` macro funnels through — and the path that FIRES the hook), and they
;; NEVER call `reproject-live-frames!` manually. The deferred flush is forced
;; deterministically via the synchronous `flush-pending-reprojection!` (the same
;; flush the scheduled `next-tick` tick arms). Before the fix the hook did not
;; exist, so `flush-pending-reprojection!` found nothing pending and the frame
;; stayed on ::auto-v1 — RED. After the fix the `register!` marks dirty, the
;; flush reprojects, and the frame resolves ::auto-v2 — GREEN.

(deftest reg-star-change-auto-reprojects-explicit-image-frame
  (testing "a reg-* re-eval (via registrar/register!) in a namespace an explicit
            :include-ns image selects marks the projection dirty and the coalesced
            flush reprojects + swaps THAT frame's generation — WITHOUT a manual
            reproject-live-frames! call (EP-0023 §Default Image Semantics — the
            headline guarantee, wired by rf2-h4q6cy). Uses the LIVE source store
            + the shared registrar; snapshot/restore the source store and clean
            the registrar in finally (NOT clear-all!). Asserts BEHAVIOR (the
            frame resolves the new impl), never the internal dirty flag.

            DETERMINISM (rf2-roou7s): the shared `pending-reprojection?` flag and
            the `interop/next-tick` schedule are process-wide (a `defonce` hook).
            The register!s below schedule a REAL deferred `next-tick` flush that
            races this case's synchronous `flush-pending-reprojection!`: were a
            scheduled tick (this case's own, or one a prior case left in flight on
            the JVM executor thread / a CLJS microtask) to fire first, it would
            DRAIN the pending flag and the assert-time synchronous flush would
            return `{}` — the observed intermittent `(not (contains? {} :auto/main))`.
            So redef `interop/next-tick` to a NO-OP for the whole case: no async
            flush ever runs, the ONLY flush is the explicit synchronous one this
            case drives, and the dirty flag is set by `register!` and drained
            ONLY by that synchronous flush. This is the same `next-tick`-isolation
            every sibling auto-reprojection case below already uses; the assertions
            are unchanged (still the register!-armed synchronous flush)."
    (let [snapshot @source-store/kind->id->ns->descriptor]
      (try
        ;; The next-tick no-op makes the coalesced flush deterministic: no
        ;; scheduled tick can fire and drain the shared pending flag out from
        ;; under the synchronous flush this case asserts on (rf2-roou7s).
        (with-redefs [interop/next-tick (fn [_f] nil)]
          ;; Start from a clean slate: drain any reprojection a prior case left
          ;; pending (the hook is a process-defonce, shared across cases).
          (lf/flush-pending-reprojection!)
          ;; First registration of the selected id, via the SAME register! path a
          ;; reg-* macro funnels through (so the auto-reprojection hook is exercised
          ;; end to end). The hook fires here too (first-time); no frame is live yet,
          ;; so we drain the resulting (move-empty) pending flush to start clean.
          (registrar/register! :event :auto/inc
            {:rf.provenance/ns "auto.feature" :handler-fn ::auto-v1})
          (lf/flush-pending-reprojection!)
          (let [img   (image/image {:id :auto/img :include-ns ["auto.feature"]})
                frame (lf/make-frame {:id :auto/main :images [img]})
                gen-before (lf/frame-generation frame)]
            (testing "the frame resolves the ORIGINAL impl before any re-eval (control)"
              (is (= ::auto-v1
                     (:handler-fn (asm/resolve-descriptor gen-before :event :auto/inc)))))
            ;; The reg-* RE-EVAL — a new impl for the same (kind,id,ns) slot, through
            ;; register!. This FIRES the registration hook → marks dirty + schedules
            ;; (the schedule is the redef'd no-op, so nothing drains the flag early).
            (registrar/register! :event :auto/inc
              {:rf.provenance/ns "auto.feature" :handler-fn ::auto-v2})
            ;; Force the COALESCED flush synchronously (the same flush the next-tick
            ;; tick arms). NO manual reproject-live-frames! — this is the wired path:
            ;; a flush only does work because the register! above marked it pending.
            (let [moved (lf/flush-pending-reprojection!)]
              (testing "the register!-armed flush reprojects the affected
                        explicit-image frame (the hook fired and marked it dirty)"
                (is (contains? moved :auto/main))
                (is (contains? (:changed (get moved :auto/main)) [:event :auto/inc]))))
            (testing "the live frame now resolves the RE-EVAL'd impl through its
                      swapped generation — automatically, no reload-images! call"
              (is (= ::auto-v2
                     (:handler-fn (asm/resolve-descriptor
                                    (lf/frame-generation (lf/live-frame :auto/main))
                                    :event :auto/inc)))))))
        (finally
          ;; Clean the shared registrar slot we wrote + drain any residual pending
          ;; reprojection, then restore the source store. (The hook itself is a
          ;; process-defonce — it stays installed across cases by design.) Keep the
          ;; next-tick no-op over the cleanup too: the synchronous drain stays
          ;; synchronous and no stray real tick can be left scheduled to fire
          ;; mid-sibling and drain its pending flag (the same rf2-roou7s race,
          ;; just relocated to teardown).
          (with-redefs [interop/next-tick (fn [_f] nil)]
            (registrar/unregister! :event :auto/inc)
            (lf/flush-pending-reprojection!))
          (reset! source-store/kind->id->ns->descriptor snapshot))))))

(deftest reg-star-burst-coalesces-to-one-flush
  (testing "a BURST of reg-* (a hot-reloaded namespace re-evaluating N
            registrations) schedules at MOST ONE deferred reprojection flush — the
            coalescing gate (rf2-h4q6cy). Counting the next-tick schedules proves
            the burst reprojects ONCE at the batch boundary, not per reg-*. A live
            explicit-image frame over the reloaded ns must exist for the schedule
            to arm at all (the no-live-frame short-circuit — see the burst-with-no-
            live-frame test below); make-frame it first."
    (let [snapshot  @source-store/kind->id->ns->descriptor
          scheduled (atom 0)
          captured  (atom nil)]
      (try
        ;; A live frame selecting the burst namespace, so the auto-reprojection
        ;; schedule is REACHABLE (the hook short-circuits to a no-op when no live
        ;; image frame exists). Record a descriptor in burst.feature FIRST so the
        ;; image's :include-ns selector matches (a zero-match is fail-loud at
        ;; image construction); construct the image AFTER. make-frame's own backing
        ;; reg-frame fires the hook, so drain afterwards to start the burst from a
        ;; clean (un-pending) flag — the first burst reg-* is then the false→true edge.
        (source-store/record-descriptor!
          :event :burst/seed
          {:rf.provenance/ns "burst.feature" :kind :event :id :burst/seed
           :handler-fn ::burst-seed})
        (let [img (image/image {:id :burst/img :include-ns ["burst.feature"]})]
          (lf/make-frame {:id :burst/main :images [img]})
          (lf/flush-pending-reprojection!)
          ;; Redef next-tick to COUNT schedules and CAPTURE (defer) the flush —
          ;; deliberately NOT run inline, so the dirty flag stays set through the
          ;; whole burst exactly as a real deferred tick would leave it. If the
          ;; coalescing gate works, only the first reg-* (false→true) schedules; the
          ;; other four observe the flag already set and add no second tick. The
          ;; re-arm step below stays INSIDE this redef so its schedule hits the same
          ;; counting next-tick (outside, the real next-tick would not be counted).
          (with-redefs [interop/next-tick (fn [f] (swap! scheduled inc)
                                            (reset! captured f) nil)]
            ;; A burst of 5 reg-* in one synchronous run (a namespace re-eval).
            (doseq [n (range 5)]
              (registrar/register! :event (keyword "burst" (str "e" n))
                {:rf.provenance/ns "burst.feature" :handler-fn (keyword "impl" (str n))}))
            (testing "the 5-reg-* burst scheduled exactly ONE flush (coalesced) —
                      the deferred tick was never run during the burst"
              (is (= 1 @scheduled)
                  "compare-and-set! gates: only the false→true transition schedules"))
            (testing "running the single captured tick drains the whole burst at once,
                      and a post-drain reg-* re-arms a fresh tick (flag re-armable)"
              (when-let [tick @captured] (tick))
              ;; The drain (`tick` → flush) cleared the dirty flag; a new reg-* is the
              ;; next false→true edge, so it schedules again — proving the flag is not
              ;; stuck-set after a flush. (Inside the redef, so this counts here.)
              (registrar/register! :event :burst/e0
                {:rf.provenance/ns "burst.feature" :handler-fn ::e0-again})
              (is (= 2 @scheduled)
                  "a post-drain reg-* re-arms a new tick (flag cleared, re-armable)"))))
        (finally
          ;; Forget the live frame before draining (see register!-during-flush's
          ;; finally): a pending reproject of :burst/main must not re-assemble its
          ;; image after the burst descriptors are unregistered.
          (lf/clear-live-frames!)
          (lf/flush-pending-reprojection!)
          (doseq [n (range 5)]
            (registrar/unregister! :event (keyword "burst" (str "e" n))))
          (reset! source-store/kind->id->ns->descriptor snapshot))))))

;; ---- the HANG GUARD: a burst with NO live frame schedules ZERO flushes -----
;;
;; rf2-h4q6cy fix: the auto-reprojection hook fires on EVERY register! (the
;; EP-0023 collapse made make-frame's backing reg-frame a register! too, so even
;; frame creation funnels through it). Marking dirty + scheduling a next-tick
;; flush on each — when there is NOTHING reprojectable (no PUBLIC-id live frame
;; exists) — floods the microtask queue with one no-op deferred flush per
;; registration. The bundle issues thousands of reg-* with no live image frame
;; (app boot, every handler-only test); the flood interleaved with cljs.test's
;; async scheduling never settled (the observed CI node-test / browser-test /
;; elision hang). The fix short-circuits the hook to a NO-OP when no live frame
;; is reprojectable, so a registration burst with no live frame schedules ZERO
;; flushes — the bounded-flush guarantee.

(deftest reg-star-burst-with-no-live-frame-schedules-nothing
  (testing "a BURST of reg-* with NO live image frame schedules ZERO deferred
            flushes (rf2-h4q6cy hang guard): with nothing reprojectable the hook
            short-circuits, so a hot-reloaded namespace re-evaluating N
            registrations costs no next-tick scheduling at all — bounding the
            flush work that previously hung CI's node-test/browser jobs."
    (let [snapshot  @source-store/kind->id->ns->descriptor
          scheduled (atom 0)]
      (try
        ;; No live frame: clear the registry and drain any residual pending flush a
        ;; prior case left, so the flag starts clean and live-frame-ids is empty.
        (lf/clear-live-frames!)
        (lf/flush-pending-reprojection!)
        (with-redefs [interop/next-tick (fn [_f] (swap! scheduled inc) nil)]
          ;; A 50-reg-* burst with no reprojectable frame — the worst-case flood
          ;; the hang guard suppresses. EVERY one fires the hook.
          (doseq [n (range 50)]
            (registrar/register! :event (keyword "noframe" (str "e" n))
              {:rf.provenance/ns "noframe.feature" :handler-fn (keyword "impl" (str n))}))
          (testing "no live frame ⇒ the hook short-circuits ⇒ ZERO flushes scheduled"
            (is (= 0 @scheduled)
                "no PUBLIC-id live frame is reprojectable, so nothing is marked or scheduled"))
          (testing "the dirty flag was never set (nothing pending to drain)"
            (is (empty? (lf/flush-pending-reprojection!))
                "flush is a no-op — the burst marked nothing dirty")))
        (finally
          (doseq [n (range 50)]
            (registrar/unregister! :event (keyword "noframe" (str "e" n))))
          (lf/flush-pending-reprojection!)
          (reset! source-store/kind->id->ns->descriptor snapshot))))))

;; ---- a flush never re-arms: reprojection swaps generations, never reg-* -----
;;
;; EP-0024 (rf2-tu2vr7) DISSOLVED the former `reprojecting?` re-entrancy guard.
;; It existed only because the EP-0023 two-registry `make-frame` created its
;; backing record via `frame/reg-frame` (a `register!`), raising the defensive
;; worry that a reproject flush might provoke a registration and re-arm itself.
;; Under the unified model reprojection swaps the generation onto the ONE record
;; via `frame/set-generation!` — a plain `swap!`, NOT a `register!` — so the
;; flush can never fire the registration hook and never schedules its own
;; successor. This test proves that property directly: a REAL flush (which does
;; genuine generation-swapping work) arms no extra tick, and a fresh reg-* after
;; it still re-arms (the flag clears and stays re-armable).

(deftest flush-swaps-generations-and-never-re-arms-itself
  (testing "a reprojection flush swaps generations via set-generation! (a plain
            swap!, never reg-*), so running it schedules NO successor flush — the
            former reprojecting? guard dissolved with the second registry
            (EP-0024). A fresh reg-* AFTER the flush still re-arms (the flag is
            cleared and re-armable)."
    (let [snapshot  @source-store/kind->id->ns->descriptor
          scheduled (atom 0)]
      (try
        ;; A live frame so a flush actually runs reproject-live-frames! (and so
        ;; mark-dirty-and-schedule! passes the no-live-frame short-circuit). Record
        ;; the descriptor FIRST so the image's :include-ns selector matches a loaded
        ;; registration (a zero-match is fail-loud at image construction); construct
        ;; the image AFTER.
        (source-store/record-descriptor!
          :event :reentry/inc
          {:rf.provenance/ns "reentry.feature" :kind :event :id :reentry/inc
           :handler-fn ::v1})
        (let [img (image/image {:id :reentry/img :include-ns ["reentry.feature"]})]
          (lf/make-frame {:id :reentry/main :images [img]})
          (lf/flush-pending-reprojection!)
          (with-redefs [interop/next-tick (fn [_f] (swap! scheduled inc) nil)]
            ;; A real reg-* (with the live frame present) marks dirty + schedules
            ;; ONE flush (count → 1). The re-eval changes :reentry/inc's impl, so
            ;; the flush below does GENUINE swap work (the frame moves to v2).
            (registrar/register! :event :reentry/inc
              {:rf.provenance/ns "reentry.feature" :handler-fn ::v2})
            (testing "the live-frame reg-* armed exactly one flush"
              (is (= 1 @scheduled)))
            ;; Run the flush: it re-resolves + swaps :reentry/main's generation
            ;; via set-generation! (a plain swap!). That cannot fire the
            ;; registration hook, so no successor tick is armed.
            (let [moved (lf/flush-pending-reprojection!)]
              (testing "the flush did real work (the frame reprojected to v2)"
                (is (contains? moved :reentry/main))
                (is (= ::v2 (:handler-fn (asm/resolve-descriptor
                                           (lf/frame-generation (lf/live-frame :reentry/main))
                                           :event :reentry/inc)))))
              (testing "the generation swap (set-generation!, not reg-*) scheduled
                        NO successor flush — there is no re-entrancy to guard"
                (is (= 1 @scheduled)
                    "a flush swaps generations only; it never fires the hook"))))
          (testing "after the flush a fresh reg-* re-arms (the flag is cleared and
                    re-armable — no stuck-set guard)"
            (with-redefs [interop/next-tick (fn [_f] (swap! scheduled inc) nil)]
              (registrar/register! :event :reentry/inc
                {:rf.provenance/ns "reentry.feature" :handler-fn ::v3})
              (is (= 2 @scheduled)
                  "a reg-* with a live frame re-arms normally after the flush"))))
        (finally
          ;; Clear the live frame BEFORE draining: the body may leave a flush
          ;; pending, and a reproject of the still-live :reentry/main would
          ;; re-assemble its :include-ns ["reentry.feature"] image AFTER we forget
          ;; the descriptors below — a zero-match. Forgetting the frame first makes
          ;; the drain a no-op (nothing reprojectable).
          (reset! frame/frames {})
          (lf/flush-pending-reprojection!)
          (registrar/unregister! :event :reentry/inc)
          (reset! source-store/kind->id->ns->descriptor snapshot))))))
