(ns re-frame.ep0023-conformance-cljs-test
  "EP-0023 (Images And Frame-Loaded Instruction Sets) — the END-TO-END CONTRACT
  CONFORMANCE suite (rf2-32siq3.15). The whole EP-0023 implementation is merged;
  this suite asserts the FULL public contract end-to-end, exercising the public
  surfaces (`rf/image` / `image-assembly/assemble` / `live-frame/make-frame` /
  `reload-images!` / `reproject-live-frames!` / the migration diagnostics) the
  way a consumer reaches them, NOT each merged slice's internals.

  > image -> frame -> event stream

  The per-slice unit suites (`image-cljs-test`, `image-assembly-cljs-test`,
  `live-frame-cljs-test`, `frame-resolution-cljs-test`, `migration-cljs-test`,
  …) prove each slice's internals exhaustively. This suite is the COHESIVE
  conformance proof the EP §Conformance And Tests list demands before
  graduation: each section asserts ONE contract area through the public path and
  cites the EP clause it proves. The sections, in EP order:

    1. Image construction + selection (§Image, §Namespace-Selected Images,
       §Image Fragments) — `:include-ns` glob grammar (`*` one segment / `**`
       zero-or-more, case-sensitive), inline `:registrations`, zero-match
       fail-loud.
    2. Sealed assembly + validation (§Image Validation) — the sealed generation
       shape; reference check; duplicate-id fail-loud; unsupported-kind.
       (EP-0026 retired the image-capability requirement fail-loud with the
       feature.)
    3. Layered resolution (EP-0026 §Layered Resolution / §Framework Standard
       Registrations) — image-order layering (later image wins); cross-image
       conflict; standard-shadow-forbidden (no public opt-in). EP-0026 retired
       the declared `:replace` / `:replace-standard` winner model.
    4. Default-image projection (§Default Image Semantics) — no/empty `:images`
       projects the whole store + standards; cross-namespace collision fails
       loud (no clobber).
    5. make-frame + frame-derived resolution (§Frame, §Frame-derived live
       registration resolution, §Public API) — `:images` attaches the resolved
       generation; live-frame id-conflict; resolution through the TARGET frame's
       generation; absence-is-default; ALL-OR-NOTHING generation scope.
    6. Generation cache + invalidation (§Image — \"MUST cache resolved
       generations\") — cache hit for identical inputs; invalidation on
       `reg-*` / `clear-kind!` (the .34 fix) and on a standard-registry change.
    7. Hot reload (§Hot Reload) — `reload-images!` / `reproject-live-frames!`
       re-resolve every live frame; no frame left stale; reload of an unknown
       frame errors cleanly.
    8. The one frame constructor (EP-0024 §One constructor) — `rf/make-frame`
       is the SINGLE public constructor, returning the frame VALUE (not a bare
       id keyword), with `:initial-events` setup events running AFTER the
       resolved generation and seeded app-db are installed on the record.

  Every fail-loud assertion branches on the `:rf.error/id` DISCRIMINATOR, never
  the message bytes (Spec 009 §The thrown-error shape rule 3).

  ## Fixture posture

  Cases that resolve against an EXPLICIT descriptor pool (the `assemble` /
  `make-frame` 2-arity — the same decoupling idiom the sibling slice suites use)
  need no live source-store wiring; they only reset the DERIVED process state
  (the standard registry + the generation cache + the live-frame registry). The
  LIVE-store cases (the cache-invalidation + `clear-kind!` + reprojection
  sections) drive the REAL `reg-*` → source-store → assemble cascade, so they
  SNAPSHOT/RESTORE the source-store atom (per the bead — NO `registrar/clear-all!`
  / `clear-kind!` in the fixture, which would destroy framework-shipped
  ns-load registrations a sibling test ns depends on; `clear-kind!` appears only
  INSIDE a case proving the .34 invalidation). The registrar baseline is
  snapshot/restored via `make-reset-runtime-fixture`.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image          :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.source-store   :as ss]
            [re-frame.registrar      :as registrar]
            [re-frame.live-frame     :as lf]
            [re-frame.interop        :as interop]
            [re-frame.core           :as rf]
            [re-frame.std-interceptors    :as std]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support   :as ts]))

;; ===========================================================================
;; Fixtures + helpers
;; ===========================================================================
;;
;; `make-reset-runtime-fixture` snapshots/restores the registrar (NOT
;; `clear-all!`). On top of it we reset the EP-0023 OWN process-state atoms (the
;; standard registry + its generation, the resolved-generation cache, the
;; live-frame registry) — these are this wave's own defonces, not framework
;; ns-load state a sibling depends on, so a direct reset is safe and gives every
;; case a known baseline. The plain-atom adapter is installed so the make-frame
;; cases that need a live substrate work; the assembly/selection sections never
;; render.

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t]
    (asm/clear-standards!)
    (asm/clear-generation-cache!)
    (t)
    (asm/clear-standards!)
    (asm/clear-generation-cache!)))

(defn- reg-desc
  "A synthetic REGISTERED descriptor authored in `provenance-ns` — the shape the
  source store produces and the selector consumes. `:handler-fn` is the
  registrar impl slot, so it is shape-identical to a stored registrar entry."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- err-id
  "Run `thunk`; return the `:rf.error/id` of the thrown ex-info, or nil if it did
  not throw. Branches on the DISCRIMINATOR, never the message bytes."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- ex-data-of
  "Run `thunk`; return the ex-data of the thrown ex-info, or nil if it did not
  throw. For the cases that assert the ACTIONABLE diagnostic names the right
  facts (not the message bytes)."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

;; ===========================================================================
;; SECTION 1 — Image construction + selection
;; (EP-0023 §Image / §Namespace-Selected Images / §Image Fragments)
;; ===========================================================================
;;
;; An image is an inert registration-set VALUE. `:include-ns` is a glob over the
;; source-code provenance namespace (NOT the registration-id namespace);
;; `:registrations` lowers inline descriptors; a zero-match `:include-ns`
;; pattern fails loud.

(deftest s1-image-is-an-inert-value-with-normalized-shape
  (testing "EP-0023 §Public API: rf/image constructs an INERT value — equal spec
            maps produce equal values; no realm, no registrar, no side effect"
    (let [a (rf/image {:id :counter/v2 :select-ns {:include ["docs.counter.v2"]}})
          b (rf/image {:id :counter/v2 :select-ns {:include ["docs.counter.v2"]}})]
      (is (= a b) "two image calls with equal specs are equal values")
      (is (= :counter/v2 (:rf.image/id a)))
      (is (= ["docs.counter.v2"] (:rf.image/include-ns a)))
      (is (vector? (:rf.image/inline a))))))

(deftest s1-include-ns-glob-grammar
  (testing "EP-0023 §Namespace-glob language: `*` matches EXACTLY ONE segment,
            `**` matches ZERO OR MORE segments, case-sensitive whole-ns match;
            selection is BY :rf.provenance/ns, never the registration-id ns"
    ;; Authored in different source namespaces but several SHARE the id ns
    ;; `counter` — selection must NOT pick by the id keyword's namespace.
    (let [pool [(reg-desc "docs.quickstart.counter.v2" :event :counter/inc ::v2)
                (reg-desc "docs.shared.widgets.button"  :view  :widgets/btn ::btn)
                (reg-desc "docs.shared.widgets"         :view  :widgets/root ::root)
                (reg-desc "docs.shared.widgets.forms.input" :view :widgets/input ::input)
                (reg-desc "docs.shared"                  :event :shared/boot ::boot)]
          ids (fn [img] (set (map (juxt :kind :id)
                                  (image/select-descriptors img pool))))]
      (testing "exact inclusion — a pattern with no wildcard"
        (is (= #{[:event :counter/inc]}
               (ids (rf/image {:select-ns {:include ["docs.quickstart.counter.v2"]}})))))
      (testing "`*` = exactly one segment: docs.shared.widgets.* matches
                .button but NOT .widgets (no extra segment) nor .forms.input
                (two extra segments)"
        (is (= #{[:view :widgets/btn]}
               (ids (rf/image {:select-ns {:include ["docs.shared.widgets.*"]}})))))
      (testing "`**` = zero or more segments: docs.shared.** matches
                docs.shared, docs.shared.widgets, and docs.shared.widgets.forms.input"
        (is (= #{[:event :shared/boot]
                 [:view  :widgets/btn]
                 [:view  :widgets/root]
                 [:view  :widgets/input]}
               (ids (rf/image {:select-ns {:include ["docs.shared.**"]}})))))
      (testing "selection is by provenance ns, not the id ns: the `:counter/inc`
                event is NOT selected by a `counter`-id glob — there is no such
                provenance namespace"
        (is (= :rf.error/image-zero-match
               (err-id #(image/select-descriptors
                          (rf/image {:select-ns {:include ["counter.*"]}}) pool)))))
      (testing "case-sensitive whole-ns match: Docs.* does not match docs.*"
        (is (= :rf.error/image-zero-match
               (err-id #(image/select-descriptors
                          (rf/image {:select-ns {:include ["Docs.shared.**"]}}) pool))))))))

(deftest s1-exclude-ns-subtracts-and-intra-segment-glob
  (testing "EP-0023 §Namespace-Selected Images: `:exclude-ns` subtracts from the
            `:include-ns` selection by provenance ns (not zero-match fail-loud,
            never touches inline), and the intra-segment `*` matches within one
            segment — the tool-self-seating narrowing case"
    ;; A production ns alongside its `*-cljs-test` sibling co-registering the
    ;; SAME id (the collision an exclude prevents), plus a deep test ns and a
    ;; test-support subtree.
    (let [pool [(reg-desc "app.feature.editor"          :fx    :editor/open ::prod)
                (reg-desc "app.feature.editor-cljs-test" :fx   :editor/open ::test)
                (reg-desc "app.feature.panels.diff-cljs-test" :sub :diff/x ::deep)
                (reg-desc "app.feature.test-helpers.counter" :event :ctr/inc ::fixture)]
          ids (fn [img] (set (map (juxt :kind :id)
                                  (image/select-descriptors img pool))))]
      (testing "the intra-segment `*-cljs-test` (under `**`) excludes the test
                nss at any depth; only the production descriptor survives"
        (is (= #{[:fx :editor/open]}
               (ids (rf/image {:select-ns {:include ["app.feature.**"] :exclude ["app.feature.**.*-cljs-test"
                                            "app.feature.test-helpers.**"]}})))))
      (testing "the surviving :editor/open is the PRODUCTION provenance, not the
                `*-cljs-test` sibling — the dup-id is gone"
        (let [sel (image/select-descriptors
                    (rf/image {:select-ns {:include ["app.feature.**"] :exclude ["app.feature.**.*-cljs-test"
                                            "app.feature.test-helpers.**"]}})
                    pool)]
          (is (= 1 (count sel)))
          (is (= "app.feature.editor" (:rf.provenance/ns (first sel))))))
      (testing "an :exclude-ns pattern matching nothing is a NO-OP, not
                fail-loud (unlike :include-ns) — the full include selection
                survives (all 4 descriptors, no throw)"
        (is (= 4 (count (image/select-descriptors
                          (rf/image {:select-ns {:include ["app.feature.**"] :exclude ["never.matches.**"]}})
                          pool))))))))

(deftest s1-inline-registrations-lower-to-descriptors
  (testing "EP-0023 §Image Fragments: inline :registrations are selected
            UNCONDITIONALLY (their image was supplied) — never by :include-ns —
            and lower to descriptors carrying the inline source coordinate"
    (let [img (rf/image
                {:id :test/small
                 :registrations
                 {:reg-event [[:counter/inc {:doc "Increment."} ::inc-fn]]
                  :reg-sub   [[:counter/value {:doc "Current value."} ::value-fn]]}})
          ;; No :include-ns, so no source pool needed — inline descriptors are
          ;; selected because the image was supplied.
          selected (image/select-descriptors img [])
          by-id    (into {} (map (juxt #(vector (:kind %) (:id %)) identity)) selected)]
      (is (= 2 (count selected)) "both inline entries are selected")
      (is (= ::inc-fn (:impl (get by-id [:event :counter/inc]))))
      (is (= ::value-fn (:impl (get by-id [:sub :counter/value]))))
      (testing "the inline source coordinate names the image + section/id"
        (is (= {:image :test/small :inline [:reg-event :counter/inc]}
               (asm/descriptor-coordinate (get by-id [:event :counter/inc]))))))))

(deftest s1-zero-match-selection-fails-loud
  (testing "EP-0023 §Namespace-Selected Images: a zero-match :include-ns pattern
            fails loud (:rf.error/image-zero-match) naming the image, the
            pattern, and the loaded provenance namespaces considered"
    (let [pool [(reg-desc "shop.cart" :event :cart/add ::add)]
          data (ex-data-of
                 #(image/select-descriptors
                    (rf/image {:id :shop/main :select-ns {:include ["shop.checkout.**"]}})
                    pool))]
      (is (= :shop/main (:image data)) "names the image id")
      (is (= "shop.checkout.**" (:pattern data)) "names the zero-match pattern")
      (is (= ["shop.cart"] (:loaded-ns data))
          "lists the loaded provenance namespaces considered"))))

;; ===========================================================================
;; SECTION 2 — Sealed assembly + validation
;; (EP-0023 §Image Validation)
;; ===========================================================================
;;
;; Assembly projects selected descriptors (+ framework standards) into a sealed,
;; id-disjoint [kind id] resolver generation. Every validation point is
;; fail-loud with a distinct :rf.error/id.

(deftest s2-sealed-generation-shape
  (testing "EP-0023 §Specification Summary: a sealed generation carries
            :rf.gen/resolver (the id-disjoint [kind id] map), :rf.gen/images,
            and :rf.gen/kinds (EP-0026 retired :rf.gen/requires with the
            image-capability feature)"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (let [pool [(reg-desc "shop.cart" :event :cart/add   ::add)
                (reg-desc "shop.cart" :sub   :cart/items ::items)]
          img  (rf/image {:id :shop/main
                          :select-ns {:include ["shop.cart"]}})
          gen  (asm/assemble [img] pool)]
      (is (map? (:rf.gen/resolver gen)))
      (is (= ::add   (:handler-fn (asm/resolve-descriptor gen :event :cart/add))))
      (is (= ::items (:handler-fn (asm/resolve-descriptor gen :sub :cart/items))))
      (testing "the framework standard is unioned in"
        (is (= ::std-nav (:handler-fn (asm/resolve-descriptor gen :fx :rf.nav/push-url)))))
      (is (= [img] (:rf.gen/images gen)) "the normalized image vector is carried")
      (is (not (contains? gen :rf.gen/requires))
          "the retired :rf.gen/requires key is absent (EP-0026)")
      (is (= #{:event :sub :fx} (asm/generation-kinds gen))))))

(deftest s2-duplicate-id-fails-loud
  (testing "EP-0023 §Image Validation: a genuine cross-source (kind, id)
            collision with no declared winner is :rf.error/image-duplicate-id —
            order must NEVER silently decide the survivor"
    (let [img  (rf/image {:id :examples/all :select-ns {:include ["examples.**"]}})
          pool [(reg-desc "examples.todo"    :event :boot/init ::todo)
                (reg-desc "examples.counter" :event :boot/init ::counter)]]
      (is (= :rf.error/image-duplicate-id
             (err-id #(asm/assemble [img] pool)))))))

(deftest s2-unsupported-kind-fails-loud
  (testing "EP-0023 §Image Validation: a descriptor whose :kind is not in the
            closed registry-kind set fails :rf.error/image-unsupported-kind"
    (let [img  (rf/image {:select-ns {:include ["examples.x"]}})
          pool [(reg-desc "examples.x" :not-a-kind :x/y ::y)]]
      (is (= :rf.error/image-unsupported-kind
             (err-id #(asm/assemble [img] pool)))))))

(deftest s2-missing-reference-fails-loud
  (testing "EP-0023 §Image Validation: an event whose :interceptors chain names
            an application interceptor not selected into the image fails
            :rf.error/image-missing-reference"
    (let [img  (rf/image {:select-ns {:include ["app.core"]}})
          ev   (assoc (reg-desc "app.core" :event :counter/inc ::inc)
                      :interceptors [:my.audit/guard])
          pool [ev]]
      (is (= :rf.error/image-missing-reference
             (err-id #(asm/assemble [img] pool))))
      (testing "selecting the interceptor too resolves cleanly"
        (let [pool+ [ev (reg-desc "app.core" :interceptor :my.audit/guard ::guard)]]
          (is (map? (asm/assemble [img] pool+))))))))

;; EP-0026 (rf2-dlvmpc): image-declared host capabilities are removed end-to-end
;; — there is no :rf.image/requires, no make-frame :capabilities, no
;; :rf.gen/requires, and no frame-boundary capability check. The former
;; s2-missing-capability-fails-loud-at-the-frame-boundary conformance test is
;; retired with the feature; EP-0026 acceptance conformance lives in its own bead.

;; ===========================================================================
;; SECTION 3 — Layered resolution (EP-0026 §Layered Resolution / §Framework
;; Standard Registrations — supersedes the EP-0023 :replace policy)
;; ===========================================================================
;;
;; EP-0026 replaces the EP-0023 declared-:replace/:replace-standard winner model
;; with deterministic IMAGE-ORDER layering: the later image in :images wins; a
;; within-image [kind id] collision is an error (an override is always a later
;; image); a framework standard is protected (a public app image must not shadow
;; one).

(deftest s3-later-image-wins-cross-image-override
  (testing "EP-0026 §Layered Resolution: a [kind id] in two composed images
            resolves to the LATER image — image order, not the registrar, decides"
    (let [pool      [(reg-desc "checkout.core" :fx :checkout.http/post ::real)]
          app-image (rf/image {:id :app/main :select-ns {:include ["checkout.core"]}})
          doubles   (rf/image {:id :test/doubles
                               :registrations {:reg-fx [[:checkout.http/post {} ::story]]}})]
      (is (= ::story (:impl (asm/resolve-descriptor
                              (asm/assemble [app-image doubles] pool)
                              :fx :checkout.http/post)))
          "the later image (doubles) wins")
      (is (= ::real (:handler-fn (asm/resolve-descriptor
                                   (asm/assemble [doubles app-image] pool)
                                   :fx :checkout.http/post)))
          "reversing the order makes the app image (now last) win"))))

(deftest s3-within-image-collision-fails-loud
  (testing "EP-0026 §Layered Resolution: an image must resolve cleanly to ONE
            descriptor per [kind id]; two SELECTED registrations within one image
            are ambiguous, and an inline entry colliding with a selected one is a
            within-image collision (an override must be a later image)"
    (let [two-selected (rf/image {:id :app/dup
                                  :select-ns {:include ["app.a" "app.b"]}})
          pool         [(reg-desc "app.a" :fx :app/post ::a)
                        (reg-desc "app.b" :fx :app/post ::b)]]
      (is (= :rf.error/image-duplicate-id
             (err-id #(asm/assemble [two-selected] pool)))))
    (let [inline-vs-sel (rf/image {:id :app/clash
                                   :select-ns {:include ["app.core"]}
                                   :registrations {:reg-fx [[:checkout.http/post {} ::inline]]}})
          pool          [(reg-desc "app.core" :fx :checkout.http/post ::sel)]]
      (is (= :rf.error/image-within-image-collision
             (err-id #(asm/assemble [inline-vs-sel] pool)))))))

(deftest s3-standard-shadowing-forbidden
  (testing "EP-0026 §Framework Standard Registrations: a public app image must
            NOT shadow a framework standard — the app/standard collision fails
            loud (a standard encodes an execution invariant, not an app extension
            point), and there is no public :replace-standard opt-in"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (let [pool [(reg-desc "product.story" :fx :rf.nav/push-url ::story-nav)]
          img  (rf/image {:id :story/x :select-ns {:include ["product.story"]}})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (err-id #(asm/assemble [img] pool)))))
    (testing "a standard with no colliding app id is simply unioned in"
      (asm/clear-standards!)
      (asm/clear-generation-cache!)
      (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
      (let [pool [(reg-desc "app.core" :event :app/boot ::boot)]
            img  (rf/image {:id :app/main :select-ns {:include ["app.core"]}})
            gen  (asm/assemble [img] pool)]
        (is (= ::std-nav (:handler-fn (asm/resolve-descriptor gen :fx :rf.nav/push-url))))))))

(deftest s3-duplicate-image-id-fails-loud
  (testing "EP-0026 §Image Keys: two images sharing an :id within one composition
            fail loud (:rf.error/image-duplicate-image-id) — the shadow report
            identifies images by id, so a shared id is ambiguous"
    (let [pool  [(reg-desc "checkout.core"  :fx :checkout.http/post ::real)
                 (reg-desc "checkout.story" :fx :checkout.http/post ::story)]
          img-a (rf/image {:id :compose/dup :select-ns {:include ["checkout.core"]}})
          img-b (rf/image {:id :compose/dup :select-ns {:include ["checkout.story"]}})]
      (is (= :rf.error/image-duplicate-image-id
             (err-id #(asm/assemble [img-a img-b] pool)))))))

;; ===========================================================================
;; SECTION 4 — Default-image projection
;; (EP-0023 §Default Image Semantics)
;; ===========================================================================
;;
;; No / empty :images projects the WHOLE source store + standards. A
;; cross-namespace same-(kind, id) collision fails loud on the default path too
;; — load order never decides.

(deftest s4-default-image-projects-the-whole-store-plus-standards
  (testing "EP-0023 §Default Image Semantics: assemble with NO / empty :images
            is the DEFAULT image — the implicit selector over the WHOLE store +
            the framework standards"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (let [pool [(reg-desc "shop.cart"    :event :cart/add    ::add)
                (reg-desc "shop.auth"    :event :auth/login  ::login)
                (reg-desc "shop.catalog" :view  :catalog/grid ::grid)]
          gen  (asm/assemble [] pool)]
      (is (contains? (:rf.gen/resolver gen) [:event :cart/add]))
      (is (contains? (:rf.gen/resolver gen) [:event :auth/login]))
      (is (contains? (:rf.gen/resolver gen) [:view  :catalog/grid]))
      (is (contains? (:rf.gen/resolver gen) [:fx :rf.nav/push-url])
          "the framework standard is unioned in on the default path too")
      (testing "and nil :images takes the same default path"
        (is (= gen (asm/assemble nil pool)))))))

(deftest s4-default-image-cross-namespace-collision-fails-loud-no-clobber
  (testing "EP-0023 §Default Image Semantics: a cross-namespace same-(kind, id)
            collision in the default projection fails :rf.error/image-duplicate-id
            REGARDLESS of pool order — the default image does not let load order
            clobber"
    (let [a (reg-desc "examples.todo"    :event :boot/init ::a)
          b (reg-desc "examples.counter" :event :boot/init ::b)]
      (is (= :rf.error/image-duplicate-id (err-id #(asm/assemble [] [a b]))))
      (is (= :rf.error/image-duplicate-id (err-id #(asm/assemble [] [b a])))
          "the OTHER pool order still fails — no last-write-wins"))))

;; ===========================================================================
;; SECTION 5 — make-frame + frame-derived resolution
;; (EP-0023 §Frame / §Frame-derived live registration resolution / §Public API)
;; ===========================================================================
;;
;; make-frame attaches the resolved generation; an :id-bearing duplicate fails
;; loud; dispatch/sub/fx/cofx resolve through the TARGET frame's generation;
;; absence-is-default; the generation scope is ALL-OR-NOTHING across the cascade.

(deftest s5-make-frame-attaches-the-resolved-generation
  (testing "EP-0023 §Frame: make-frame {:images} attaches the sealed generation
            to the returned live frame object; resolution reads from it"
    (let [pool  [(reg-desc "examples.counter" :event :counter/inc ::inc)]
          img   (rf/image {:select-ns {:include ["examples.counter"]}})
          frame (lf/make-frame {:images [img]} pool)]
      (is (lf/frame-object? frame))
      (is (= (asm/assemble [img] pool) (lf/frame-generation frame))
          "the frame carries the SAME sealed generation assemble produces")
      (is (= ::inc (:handler-fn (asm/resolve-descriptor (lf/frame-generation frame)
                                                        :event :counter/inc)))))))

(deftest s5-duplicate-id-is-idempotent-replacement
  (testing "EP-0024 §Duplicate id policy: re-make-frame-ing the same id is
            IDEMPOTENT REPLACEMENT (hot-reload / Story-friendly) — config +
            generation refresh while durable state is preserved; it does NOT
            fail loud. Registration ids stay reusable across images."
    (let [pool [(reg-desc "examples.counter" :event :counter/inc ::inc)]
          img  (rf/image {:select-ns {:include ["examples.counter"]}})
          f1   (lf/make-frame {:id :counter/main :images [img]} pool)]
      ;; seed durable state, then re-make under the SAME id
      (rf/dispatch-sync [:counter/inc] {:frame :counter/main})
      (let [db-before (rf/app-db-value :counter/main)
            f2        (lf/make-frame {:id :counter/main :images [img]} pool)]
        (is (lf/frame-object? f2) "re-make returns a frame value, no throw")
        (is (= :counter/main (rf/frame-value->id f1) (rf/frame-value->id f2))
            "both values route to the same id")
        (is (= db-before (rf/app-db-value :counter/main))
            "durable app-db is preserved across the idempotent re-make"))
      (testing "a no-id (direct) frame value bypasses the public id space entirely"
        (is (lf/frame-object? (lf/make-frame {:images [img]} pool)))
        (is (lf/frame-object? (lf/make-frame {:images [img]} pool)))))))

(deftest s5-resolution-derives-from-the-target-frame
  (testing "EP-0023 §Frame-derived live registration resolution: two frames
            running DIFFERENT images resolve the SAME [kind id] to their OWN
            image's descriptor — the heart of the same-id story"
    (let [todo-pool    [(reg-desc "examples.todo"    :event :boot/init ::todo-boot)]
          counter-pool [(reg-desc "examples.counter" :event :boot/init ::counter-boot)]
          todo-frame    (lf/make-frame {:id :todo/main
                                        :images [(rf/image {:select-ns {:include ["examples.todo"]}})]}
                                       todo-pool)
          counter-frame (lf/make-frame {:id :counter/main
                                        :images [(rf/image {:select-ns {:include ["examples.counter"]}})]}
                                       counter-pool)]
      (lf/call-with-frame-resolution todo-frame
        (fn [] (is (= ::todo-boot (registrar/handler :event :boot/init)))))
      (lf/call-with-frame-resolution counter-frame
        (fn [] (is (= ::counter-boot (registrar/handler :event :boot/init)))))
      (testing "neither frame's id leaks into the other (no global clobber)"
        (lf/call-with-frame-resolution todo-frame
          (fn [] (is (= ::todo-boot (registrar/handler :event :boot/init)))))))))

(deftest s5-absence-is-default
  (testing "EP-0023 §Frame-derived live registration resolution: with NO
            generation bound, resolution falls through to the global registrar —
            byte-identical for every existing caller (absence-is-default)"
    (registrar/register! :event :app/boot {:handler-fn ::default-boot})
    (is (= ::default-boot (:handler-fn (registrar/lookup :event :app/boot)))
        "the default registrar path resolves the globally-registered handler")
    (testing "a nil target / a frame object with no generation binds nothing"
      (lf/call-with-frame-resolution nil
        (fn []
          (is (= ::default-boot (:handler-fn (registrar/lookup :event :app/boot))))
          (is (nil? registrar/*generation*))))
      (lf/call-with-frame-resolution {:rf.frame/object true}
        (fn [] (is (nil? registrar/*generation*)))))))

(deftest s5-generation-scope-is-all-or-nothing
  (testing "EP-0023 §Frame-derived live registration resolution: the binding
            covers the WHOLE cascade — the event handler, its cofx, its fx, and a
            child dispatch ALL resolve in the SAME image generation
            (ALL-OR-NOTHING); an id NOT in the image resolves nil even though it
            IS globally registered"
    (let [pool  [(reg-desc "examples.feat" :event :feat/go   ::ev)
                 (reg-desc "examples.feat" :cofx  :feat/now  ::cofx)
                 (reg-desc "examples.feat" :fx    :feat/save ::fx)
                 (reg-desc "examples.feat" :event :feat/child ::child)]
          frame (lf/make-frame {:images [(rf/image {:select-ns {:include ["examples.feat"]}})]}
                               pool)
          ;; A nested resolution simulating the fx walk / child dispatch deeper
          ;; in the cascade.
          nested-fx    (fn [] (registrar/handler :fx :feat/save))
          nested-child (fn [] (registrar/handler :event :feat/child))]
      ;; A globally-registered id the frame's image does NOT carry.
      (registrar/register! :event :other/global {:handler-fn ::global})
      (lf/call-with-frame-resolution frame
        (fn []
          (is (= ::ev    (registrar/handler :event :feat/go))   "event")
          (is (= ::cofx  (registrar/handler :cofx  :feat/now))  "cofx")
          (is (= ::fx    (nested-fx))                           "fx (nested)")
          (is (= ::child (nested-child))                        "child dispatch (nested)")
          (is (nil? (registrar/lookup :event :other/global))
              "a globally-registered id absent from the image is nil under the frame")))
      (testing "outside the seam, the global id resolves again"
        (is (= ::global (:handler-fn (registrar/lookup :event :other/global))))))))

;; ===========================================================================
;; SECTION 6 — Generation cache + invalidation
;; (EP-0023 §Image — \"The reference implementation MUST cache resolved
;; generations\")
;; ===========================================================================
;;
;; Cache HIT for identical image + store-generation. INVALIDATION on every
;; source-store mutation (reg-* / forget-* / clear-kind! — the .34 fix) and on a
;; standard-registry change. The LIVE-store cases drive the REAL reg-* cascade,
;; so they snapshot/restore the source-store atom.

(deftest s6-cache-hit-for-identical-inputs
  (testing "EP-0023 §Image: a repeat assembly of the SAME image over the SAME
            descriptor pool returns the SAME sealed object (the cache HIT — the
            SSR no-re-seal guarantee)"
    (let [img  (rf/image {:select-ns {:include ["app.core"]}})
          pool [(reg-desc "app.core" :event :counter/inc ::inc)]
          g1   (asm/assemble [img] pool)
          g2   (asm/assemble [img] pool)]
      (is (identical? g1 g2) "the identical inputs hit the cache and reuse the object")
      (testing "an image differing only in :include-ns re-seals (distinct slot)"
        (is (not (identical? g1 (asm/assemble [(rf/image {:select-ns {:include ["app.other"]}})]
                                              [(reg-desc "app.other" :event :x ::x)]))))))))

(deftest s6-live-store-cache-invalidates-on-reg-and-clear-kind
  (testing "EP-0023 §Image + rf2-32siq3.34: the LIVE-store default generation is
            cached, and INVALIDATES on a `reg-*` AND on `registrar/clear-kind!`
            (the .34 fix — clear-kind! must bump the source-store generation so a
            stale cached generation is never returned)"
    (let [store-before @ss/kind->id->ns->descriptor
          reg-before   @registrar/kind->id->metadata]
      (try
        ;; Start from a known-clean LIVE store so the default projection is
        ;; deterministic.
        (reset! ss/kind->id->ns->descriptor {})
        (reset! registrar/kind->id->metadata {})
        (asm/clear-generation-cache!)
        ;; reg-* writes the resolver map AND the provenance source store, bumping
        ;; the store generation.
        (registrar/register! :event :cart/add {:handler-fn ::add :ns "shop.cart"})
        (let [g1  (asm/assemble-default)
              g1b (asm/assemble-default)]
          (testing "a repeat over the UNCHANGED store reuses the cached object"
            (is (identical? g1 g1b)))
          (is (contains? (:rf.gen/resolver g1) [:event :cart/add]))
          (testing "a NEW reg-* bumps the store generation → re-seal, not stale"
            (registrar/register! :sub :cart/items {:handler-fn ::items :ns "shop.cart"})
            (let [g2 (asm/assemble-default)]
              (is (not (identical? g1 g2)))
              (is (contains? (:rf.gen/resolver g2) [:sub :cart/items]))))
          (testing "clear-kind! :event drops the event slot AND invalidates the
                    cache (the .34 fix) — a stale generation still resolving
                    :cart/add must NOT come back"
            (registrar/clear-kind! :event)
            (let [g3 (asm/assemble-default)]
              (is (not (contains? (:rf.gen/resolver g3) [:event :cart/add]))
                  "clear-kind! bumped the store generation; the re-sealed default
                   generation no longer carries the cleared event")
              (is (contains? (:rf.gen/resolver g3) [:sub :cart/items])
                  "the surviving sub is still present"))))
        (finally
          (reset! ss/kind->id->ns->descriptor store-before)
          (reset! registrar/kind->id->metadata reg-before)
          (asm/clear-generation-cache!))))))

(deftest s6-cache-invalidates-on-standard-change
  (testing "EP-0023 §Image: a standard-registry change bumps the standard
            generation, so a re-assembly over the same pool is a MISS (the
            standard set is part of every generation)"
    (let [img  (rf/image {:select-ns {:include ["app.core"]}})
          pool [(reg-desc "app.core" :event :counter/inc ::inc)]
          g1   (asm/assemble [img] pool)]
      (is (not (contains? (:rf.gen/resolver g1) [:fx :rf.nav/push-url])))
      (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
      (let [g2 (asm/assemble [img] pool)]
        (is (not (identical? g1 g2)) "the standard change forced a re-seal")
        (is (contains? (:rf.gen/resolver g2) [:fx :rf.nav/push-url]))))))

;; ===========================================================================
;; SECTION 7 — Hot reload
;; (EP-0023 §Hot Reload)
;; ===========================================================================
;;
;; reload-images! swaps a frame's whole image composition while preserving frame
;; identity; reproject-live-frames! re-resolves every live frame against the
;; current source store; reload of an unknown frame errors cleanly.

(deftest s7-reload-images-swaps-the-generation-and-reports-the-diff
  (testing "EP-0023 §Hot Reload: reload-images! re-resolves the frame's whole
            :images composition into a fresh generation and reports the
            added/changed/removed/retained diff; it is composition-REPLACING"
    (let [v1-pool [(reg-desc "counter.v1" :event :counter/inc ::v1-inc)]
          v2-pool [(reg-desc "counter.v2" :event :counter/inc ::v2-inc)
                   (reg-desc "counter.v2" :event :counter/reset ::v2-reset)]
          frame   (lf/make-frame {:id :counter/main
                                  :images [(rf/image {:select-ns {:include ["counter.v1"]}})]}
                                 v1-pool)
          report  (lf/reload-images! :counter/main
                                     {:images [(rf/image {:select-ns {:include ["counter.v2"]}})]}
                                     v2-pool)
          reloaded (:rf.frame/frame report)
          diff     (:rf.reload/diff report)]
      (is (= ::v2-inc (:handler-fn (asm/resolve-descriptor (lf/frame-generation reloaded)
                                                           :event :counter/inc)))
          "the frame now runs the v2 image")
      (is (contains? (:added diff) [:event :counter/reset]) "the new event is :added")
      (is (contains? (:changed diff) [:event :counter/inc]) "the redefined event is :changed")
      (testing "the live-frame registry slot is updated in place — the same id
                names the same (now-reloaded) context"
        (is (= ::v2-inc (:handler-fn (asm/resolve-descriptor
                                       (lf/frame-generation (lf/live-frame :counter/main))
                                       :event :counter/inc))))))))

(deftest s7-reload-is-frame-targeted-not-sibling-moving
  (testing "EP-0023 §Image: reloading one frame does NOT move a sibling that
            previously shared the same image — reload is frame-targeted"
    (let [pool  [(reg-desc "counter.v1" :event :counter/inc ::v1)]
          v2    [(reg-desc "counter.v2" :event :counter/inc ::v2)]
          img   (rf/image {:select-ns {:include ["counter.v1"]}})
          left  (lf/make-frame {:id :counter/left  :images [img]} pool)
          right (lf/make-frame {:id :counter/right :images [img]} pool)]
      (lf/reload-images! :counter/left
                         {:images [(rf/image {:select-ns {:include ["counter.v2"]}})]} v2)
      (is (= ::v1 (:handler-fn (asm/resolve-descriptor
                                 (lf/frame-generation (lf/live-frame :counter/right))
                                 :event :counter/inc)))
          "the right frame is untouched")
      (is (= ::v2 (:handler-fn (asm/resolve-descriptor
                                 (lf/frame-generation (lf/live-frame :counter/left))
                                 :event :counter/inc)))
          "only the left frame moved"))))

(deftest s7-reproject-live-frames-re-resolves-explicit-image-frames
  (testing "EP-0023 §Default Image Semantics / §Hot Reload: a reg-* re-eval in a
            namespace an explicit :include-ns image selects reprojects + swaps
            THAT frame's generation — explicit-image frames, not only
            default-image frames; a frame whose resolution is unchanged is left
            stale-free (untouched)"
    ;; DETERMINISM (rf2-ssokdr): the auto-reprojection wiring
    ;; (`live-frame/reproject-on-registration-change!`, installed once as a
    ;; process-`defonce` `registrar/add-registration-hook!`) fires on EVERY
    ;; `register!` — including the `reg-frame` inside each `make-frame` below.
    ;; Once a live image-loaded frame exists, that hook MARKS the shared
    ;; process-wide `pending-reprojection?` flag dirty and schedules a REAL
    ;; deferred `interop/next-tick` flush (on the JVM: an async single-thread
    ;; executor). That deferred flush RACES the explicit synchronous
    ;; `reproject-live-frames!` this case asserts on: were a scheduled tick (this
    ;; case's own, armed by the second `make-frame` while the first frame is
    ;; already live, or one a prior case left in flight) to fire in the window
    ;; AFTER the `register! ::v2` and BEFORE the explicit reproject, it would
    ;; already swap the parity frame to ::v2 and DRAIN the dirty flag — so the
    ;; explicit reproject would see the frame UNCHANGED and return `{}`, the
    ;; observed intermittent `(not (contains? {} :docs/parity))` CI failure
    ;; (PR #4895 run 27939575061). It PASSES in isolation only because the idle
    ;; executor happens to fire the deferred flush AFTER the case completes.
    ;;
    ;; So redef `interop/next-tick` to a NO-OP for the whole case (body AND
    ;; teardown): no async flush ever runs, the ONLY flush is the explicit
    ;; synchronous `reproject-live-frames!` this case drives. The assertions are
    ;; unchanged. This is the same `next-tick`-isolation every auto-reprojection
    ;; case in `live-frame-reload-cljs-test` already uses (rf2-roou7s).
    (with-redefs [interop/next-tick (fn [_f] nil)]
      ;; Start from a clean slate: drain any reprojection a prior case left
      ;; pending on the shared process-`defonce` flag (the hook survives across
      ;; cases by design).
      (lf/flush-pending-reprojection!)
      (let [store-before @ss/kind->id->ns->descriptor
            reg-before   @registrar/kind->id->metadata]
        (try
          (reset! ss/kind->id->ns->descriptor {})
          (reset! registrar/kind->id->metadata {})
          (asm/clear-generation-cache!)
          (registrar/register! :event :counter/inc {:handler-fn ::v1 :ns "docs.counter.parity"})
          (registrar/register! :event :other/x     {:handler-fn ::ox :ns "docs.other"})
          ;; Two explicit-image frames over the LIVE store: one selects the
          ;; parity ns, one selects the other ns.
          (let [parity (lf/make-frame {:id :docs/parity
                                       :images [(rf/image {:select-ns {:include ["docs.counter.parity"]}})]})
                other  (lf/make-frame {:id :docs/other
                                       :images [(rf/image {:select-ns {:include ["docs.other"]}})]})
                other-gen-before (lf/frame-generation (lf/live-frame :docs/other))]
            (is (= ::v1 (:handler-fn (asm/resolve-descriptor (lf/frame-generation parity)
                                                            :event :counter/inc))))
            ;; A re-eval of the parity ns (same ns → replaces its source slot).
            (registrar/register! :event :counter/inc {:handler-fn ::v2 :ns "docs.counter.parity"})
            (let [moved (lf/reproject-live-frames!)]
              (testing "the parity frame moved (its selected ns changed)"
                (is (contains? moved :docs/parity))
                (is (= ::v2 (:handler-fn (asm/resolve-descriptor
                                           (lf/frame-generation (lf/live-frame :docs/parity))
                                           :event :counter/inc)))
                    "no frame left stale — the parity frame runs the re-eval'd handler"))
              (testing "the other frame did NOT move (its ns was unchanged)"
                (is (not (contains? moved :docs/other)))
                (is (identical? other-gen-before
                                (lf/frame-generation (lf/live-frame :docs/other)))
                    "an unchanged frame is left untouched — no spurious swap"))))
          (finally
            ;; Drain any residual pending reprojection the case's register!s
            ;; armed, then restore the store/registrar — all still under the
            ;; next-tick no-op so no stray real tick is left scheduled to fire
            ;; mid-next-case (the same race, relocated to teardown).
            (lf/flush-pending-reprojection!)
            (reset! ss/kind->id->ns->descriptor store-before)
            (reset! registrar/kind->id->metadata reg-before)
            (asm/clear-generation-cache!)))))))

(deftest s7-reload-of-unknown-frame-errors-cleanly
  (testing "EP-0023 §Public API: reload-images! targeting an id that names no
            live frame fails :rf.error/reload-no-such-frame (clean error, not a
            nil-resolution crash)"
    (is (= :rf.error/reload-no-such-frame
           (err-id #(lf/reload-images! :no/such-frame
                                       {:images [(rf/image {:select-ns {:include ["x.y"]}})]}
                                       [(reg-desc "x.y" :event :a ::a)]))))))

;; ===========================================================================
;; SECTION 8 — The one frame constructor (EP-0024 §One constructor)
;; ===========================================================================
;;
;; rf/make-frame is the EP-0023 object constructor — the facade exports exactly
;; one make-frame, which returns the frame VALUE.

(deftest s8-make-frame-is-the-one-constructor
  (testing "EP-0024 §One constructor (rf2-tu2vr7): rf/make-frame is the ONE
            public constructor — it returns the frame VALUE, not a gensym keyword
            id, and the value routes to its id through rf/frame-value->id. Built
            against an explicit descriptor pool (the 2-arity) so the pin does not
            project the live store."
    (let [pool    [(reg-desc "examples.counter" :event :counter/inc ::inc)]
          img     (rf/image {:select-ns {:include ["examples.counter"]}})
          created (rf/make-frame {:id :counter/one :images [img]} pool)]
      (is (lf/frame-object? created)
          "rf/make-frame returns the frame VALUE")
      (is (not (keyword? created))
          "it is NOT a bare keyword id (the value is the lifecycle token)")
      (is (= :counter/one (rf/frame-value->id created))
          "the single accessor reads the id from the value")
      (rf/destroy-frame! created))
    (testing "EP-0024 reverses the rf2-32siq3.45 option-(b) fail-loud redirect:
              a record-config key is HONOURED in the same call (option-(a)), not
              rejected with :rf.error/make-frame-record-only-key"
      (let [img (rf/image {:select-ns {:include ["examples.counter"]}})
            f   (rf/make-frame {:id :counter/cfg :images [img]
                                :doc "configured" :preset :test}
                               [(reg-desc "examples.counter" :event :counter/inc ::inc)])]
        (is (lf/frame-object? f) "the record-config keys are accepted, no throw")
        (is (= "configured" (:doc (rf/frame-meta :counter/cfg)))
            "the :doc record-config key landed on the frame's config")
        (rf/destroy-frame! f)))))

(deftest s8-initial-events-seed-installed-before-later-setup-events
  (testing "EP-0027 §Construction ordering (was EP-0024 rf2-tu2vr7): when
            make-frame is given :initial-events that SEED app-db first
            ([:rf/set-db …]) then run a setup event, the resolved generation AND
            the seeded app-db are installed on the record BEFORE the later setup
            event fires — so the setup cascade resolves through the frame's image
            generation and observes the seed, and its writes are NOT clobbered by
            the seed."
    ;; A real event handler that reads the seeded db (proving the seed is live
    ;; when it runs) and writes a key (proving the write survives — not clobbered
    ;; by the seed). No `:images` ⇒ an ordinary configured frame whose setup
    ;; event resolves via the shared registrar where the handler is registered;
    ;; this pins the seed-before-setup ordering (bug fix rf2-tu2vr7) without
    ;; depending on default-image whole-store projection.
    (rf/reg-event :ep0024.oc/init
      (fn [{:keys [db]} _] {:db (assoc db :saw-seed (:seed db) :booted? true)}))
    (let [f   (rf/make-frame {:id      :ep0024/oc-frame
                              :initial-events [[:rf/set-db {:seed 42}]
                                               [:ep0024.oc/init]]})
          db  (rf/app-db-value :ep0024/oc-frame)]
      (is (lf/frame-object? f))
      (is (= 42 (:seed db))   "the :rf/set-db seed survives the later setup event (not clobbered)")
      (is (= 42 (:saw-seed db)) "the setup event OBSERVED the seeded app-db (seed installed first)")
      (is (true? (:booted? db))
          "the setup event resolved and ran")
      (rf/destroy-frame! f))))

;; ===========================================================================
;; SECTION 9 — Framework-standard registry populated
;; (EP-0023 §Image — "+ framework standard registrations"; rf2-32siq3.41)
;; ===========================================================================
;;
;; The framework-standard interceptor (`:rf.interceptor/path`) is contributed
;; into the EP-0023 framework-standard registry at boot, not ONLY into the
;; regular registrar. Without this (rf2-32siq3.41, .27 Finding 2) the standard
;; registry shipped EMPTY, so an image-loaded frame whose event references a
;; standard interceptor BY REFERENCE under a bound `*generation*` could not
;; resolve it (generation-routed `lookup` reads ONLY the generation's resolver,
;; no registrar fallback), and the framework-standard protection / invariant-
;; coupled machinery was dead code (no standard ever sat in a generation to
;; protect).
;;
;; The fixture clears the standard registry per case (it is this wave's own
;; process state), so these cases RE-SEED via the boot fn `register-standard-
;; interceptors!` — exactly what `re-frame.core/init!` does — then assert the
;; standard rides into a generation and resolves under it.

(defn- with-standard-interceptors-seeded
  "Re-seed the framework-standard interceptors (the boot path
  `register-standard-interceptors!` runs at ns-load + from `init!`) after the
  fixture's `clear-standards!`, run `thunk`, returning its value."
  [thunk]
  (std/register-standard-interceptors!)
  (thunk))

(deftest s9-standard-interceptor-is-in-the-framework-standard-registry
  (testing "EP-0023 §Image: register-standard-interceptors! contributes
            :rf.interceptor/path into the framework-standard registry, marked
            invariant-coupled (non-replaceable) — NOT only into the regular
            registrar (rf2-32siq3.41)"
    (with-standard-interceptors-seeded
      (fn []
        (let [by-kid (into {} (map (juxt (juxt :kind :id) identity))
                           (asm/standard-descriptors))
              std-desc (get by-kid [:interceptor :rf.interceptor/path])]
          (is (some? std-desc)
              "the standard registry is NO LONGER empty — it carries :rf.interceptor/path")
          (is (true? (:standard std-desc)) "stamped :standard true")
          (is (seq (:rf.standard/requires-conformance std-desc))
              "invariant-coupled — a non-empty :rf.standard/requires-conformance")
          (is (false? (asm/standard-replaceable? std-desc))
              "an invariant-coupled standard is NOT replaceable (the rule-4 commit no-op lock)")
          (is (contains? std-desc :rf/interceptor-descriptor)
              "carries the SAME interceptor descriptor the regular registrar stores"))))))

(deftest s9-standard-interceptor-ref-resolves-under-a-generation
  (testing "EP-0023 §Frame-derived live registration resolution: an image-loaded
            frame whose event references [:rf.interceptor/path …] resolves the
            standard UNDER the frame's generation — no
            :rf.error/unregistered-interceptor (rf2-32siq3.41). Before the fix the
            generation lacked the standard and resolution under *generation* threw."
    (with-standard-interceptors-seeded
      (fn []
        (let [pool  [(reg-desc "shop.cart" :event :cart/add ::cart-add)]
              img   (rf/image {:select-ns {:include ["shop.cart"]}})
              frame (lf/make-frame {:images [img]} pool)
              gen   (lf/frame-generation frame)]
          (testing "the standard rides into the resolved generation"
            (is (contains? (:rf.gen/resolver gen) [:interceptor :rf.interceptor/path])
                "the framework standard is unioned into the frame's generation"))
          (testing "the standard interceptor REF resolves under the frame's generation"
            (lf/call-with-frame-resolution frame
              (fn []
                (is (some? registrar/*generation*) "a generation is bound")
                ;; The bug: generation-routed lookup of a standard ref threw
                ;; :rf.error/unregistered-interceptor because the generation
                ;; lacked the standard. Now it resolves the factory-built path.
                (let [resolved (icpt-reg/resolve-ref [:rf.interceptor/path [:cart :items]])]
                  (is (= :rf.interceptor/path (:id resolved))
                      "the standard path interceptor is built from the generation's descriptor")
                  (is (fn? (:before resolved)) "an executable path interceptor")))))
          (testing "absent the fix, a bare missing ref under a generation DOES throw
                    — proving the resolution path is genuinely generation-routed"
            (lf/call-with-frame-resolution frame
              (fn []
                (is (= :rf.error/unregistered-interceptor
                       (err-id #(icpt-reg/resolve-ref :app/never-registered))))))))))))

(deftest s9-app-image-cannot-shadow-the-invariant-locked-path-interceptor
  (testing "EP-0026 §Framework Standard Registrations: the framework-standard
            :rf.interceptor/path is invariant-coupled and PROTECTED — a public app
            image selecting a same-[kind id] registration FAILS LOUD
            (:rf.error/image-standard-replacement-forbidden). EP-0026 retired the
            :replace-standard opt-in: standards are not an app extension point
            (rf2-dlvmpc)"
    (with-standard-interceptors-seeded
      (fn []
        (let [pool [(reg-desc "naive.override" :interceptor :rf.interceptor/path ::naive)]
              img  (rf/image {:id :i
                              :select-ns {:include ["naive.override"]}})]
          (is (= :rf.error/image-standard-replacement-forbidden
                 (err-id #(asm/assemble [img] pool)))))))))

(deftest s9-public-app-image-cannot-shadow-a-standard
  (testing "EP-0026 §Framework Standard Registrations: there is NO public
            app-facing standard-replacement opt-in — a public app image colliding
            with a framework standard FAILS LOUD regardless of the standard's
            internal replaceable? flag (the no-shadowing rule binds public app
            images; the framework keeps its own internal define/revise path)"
    (asm/register-standard! :fx :rf.nav/push-url
                            {:handler-fn ::std-nav :rf.standard/replaceable? true})
    (let [pool [(reg-desc "product.story" :fx :rf.nav/push-url ::app-nav)]
          img  (rf/image {:id :i :select-ns {:include ["product.story"]}})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (err-id #(asm/assemble [img] pool)))
          "even a replaceable standard is not shadowable by a public app image"))))

;; ===========================================================================
;; SECTION 10 — Inline (image-loaded) events deliver :rf.cofx/requires
;; (EP-0023 §Image Fragments — "Both paths should lower to the same runtime
;;  descriptor shape"; Spec 002 §Satisfaction; EP-0017 §5 declared-only
;;  delivery; rf2-khi7xr)
;; ===========================================================================
;;
;; The bug (rf2-khi7xr): the normal `reg-event` path parses `:rf.cofx/requires`
;; into `:rf.cofx/requires-parsed` on the registrar entry, and the satisfaction
;; step (`router/assemble-initial-ctx`) reads that TOP-LEVEL slot to deliver the
;; declared facts. The EP-0023 inline-image path lowers a `:reg-event` descriptor
;; through `events/lower-inline-event`, which emitted ONLY `{:handler-fn
;; :interceptors}` and NEVER parsed the inline `:metadata`'s `:rf.cofx/requires`
;; — so an image-loaded event declaring `:rf.cofx/requires` ran with the declared
;; facts MISSING (a fail-open silent drop). EP-0017 §5 requires uniform
;; declared-only delivery for EVERY event regardless of registration path.
;;
;; The cofx supplier is ALSO declared inline (in the same image's
;; `:registrations`) so it rides into the frame's generation — EP-0023
;; §Frame-derived resolution is ALL-OR-NOTHING (a bound generation resolves
;; ONLY its own descriptors, no registrar fallback; proven by
;; `s5-generation-scope-is-all-or-nothing`), so a framework-registrar-only cofx
;; would not be resolvable under the frame's generation. The whole event +
;; cofx pair lives in the one image, exactly as a real image-loaded feature
;; would ship them.

(deftest s10-inline-image-event-receives-declared-cofx-requires
  (testing "EP-0023 §Image Fragments: an INLINE (image-loaded) event declaring
            `:rf.cofx/requires` RECEIVES the declared coeffect when dispatched —
            the inline path lowers to the SAME runtime descriptor shape (with
            `:rf.cofx/requires-parsed`) the `reg-event` path installs, so
            declared-only delivery is uniform across both registration paths
            (rf2-khi7xr)"
    (let [seen (atom ::unset)
          ;; The inline handler reads the DECLARED :inline.cofx/locale and
          ;; stashes it (so the test can assert delivery) while writing app-db.
          handler (fn [{:keys [db inline.cofx/locale]} _]
                    (reset! seen locale)
                    {:db (assoc db :ran? true)})
          img   (rf/image
                  {:id :inline/cofx
                   :registrations
                   {;; The ambient cofx supplier rides into the SAME generation.
                    :reg-cofx
                    [[:inline.cofx/locale
                      {:doc "Inline ambient supplier."}
                      (fn [] "en-AU")]]
                    :reg-event
                    [[:inline.cofx/touch
                      {:doc "Inline event declaring a cofx requirement."
                       :rf.cofx/requires [:inline.cofx/locale]}
                      handler]]}})
          ;; No :include-ns → no source pool needed; the inline entries are
          ;; selected because the image was supplied.
          frame (lf/make-frame {:id :inline/cofx-frame :images [img]} [])]
      (rf/dispatch-sync [:inline.cofx/touch] {:frame :inline/cofx-frame})
      (is (true? (:ran? (rf/app-db-value :inline/cofx-frame)))
          "the inline image-loaded handler ran")
      (is (= "en-AU" @seen)
          "the DECLARED :inline.cofx/locale coeffect was delivered FLAT to the
           inline handler (was silently DROPPED before rf2-khi7xr —
           :rf.cofx/requires-parsed was never emitted by lower-inline-event)")
      (rf/destroy-frame! frame))))
