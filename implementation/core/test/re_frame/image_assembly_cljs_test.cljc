(ns re-frame.image-assembly-cljs-test
  "EP-0023 §Image Validation / §Image Composition / §Image Patching And
  Overrides — the ASSEMBLY slice (rf2-32siq3.4): resolve image values into a
  SEALED, VALIDATED `[kind id]` generation and fail loud before a frame runs.

  Sections 1-2 + 6+ pin EP-0023 assembly coverage (projection, dedupe,
  unsupported kind, references, structured diagnostics). Sections 3, 3b, 5, 9
  are UPDATED to EP-0026 §Layered Resolution (rf2-6ls85a): the EP-0023
  declared-`:replace`/`:replace-standard` winner model is replaced by
  deterministic IMAGE-ORDER layering. EP-0026 (rf2-dlvmpc) further RETIRED the
  image-capability surface end-to-end — there is no `:rf.image/requires`, no
  `check-capabilities!`, and no `:rf.gen/requires` on the generation — so the
  capability-check coverage that lived here is removed.

  Pins the enumerated coverage:

    * successful projection — selected + inline + framework standard → an
      immutable `[kind id]` resolver;
    * within-image duplicate-id collision FAILS LOUD (selection order never
      decides the survivor);
    * dedupe — the same registration selected twice is NOT a collision;
    * the LATER image wins a cross-image `[kind id]` (EP-0026 image order); a
      cross-image shadow does NOT fail assembly; a chain resolves to the last;
    * a within-image `[kind id]` resolving two ways FAILS LOUD (two selected =
      ambiguous; inline-vs-selected or two inline = within-image collision);
    * unsupported descriptor kind FAILS LOUD;
    * a public app image colliding with a framework STANDARD FAILS LOUD (a
      standard is protected — not part of app layer order, no public opt-in);
    * missing application interceptor reference FAILS LOUD.

  Each fail-loud assertion checks the `:rf.error/id` discriminator (NEVER the
  message bytes — Spec 009 §The thrown-error shape rule 3).

  Pure data — no adapter/runtime state, so no reset-runtime fixture. The
  framework-standard registry IS process state, so a fixture clears it per case.
  `.cljc` ends `-cljs-test` so it rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image          :as image]
            [re-frame.image-assembly :as asm]))

;; ---------------------------------------------------------------------------
;; Fixture — the framework-standard registry is a defonce atom; clear per case.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    (asm/clear-standards!)
    (t)
    (asm/clear-standards!)))

;; ---------------------------------------------------------------------------
;; Synthetic registered descriptors (mirror the source-store output shape the
;; selector consumes — slice .3's test helper). The load-bearing field for
;; selection is :rf.provenance/ns; :impl distinguishes a real collision from a
;; dedupe.
;; ---------------------------------------------------------------------------

(defn- reg-desc
  "A synthetic REGISTERED descriptor authored in `provenance-ns`."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- err-id
  "The `:rf.error/id` discriminator of a thrown re-frame2 error, or nil."
  [ex]
  (:rf.error/id (ex-data ex)))

(defn- assembly-error-id
  "Run `thunk`, returning the `:rf.error/id` of the thrown ex-info (or nil if it
  did not throw). Branches on the discriminator, never the message."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (err-id e))))

(defn- assembly-error-data
  "Run `thunk`; return the ex-data of the thrown ex-info (or nil)."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

;; ===========================================================================
;; 1. Successful projection — selected + inline + standard → immutable resolver
;; ===========================================================================

(deftest successful-projection-selected-plus-inline-plus-standard
  (testing "an image selecting one namespace + inline registrations, with a
            framework standard present, projects into a sealed [kind id]
            resolver carrying exactly the selected + inline + standard
            descriptors"
    (asm/register-standard! :fx :rf.nav/push-url
                            {:handler-fn ::std-nav})
    (let [pool [(reg-desc "shop.cart" :event :cart/add ::cart-add)
                (reg-desc "shop.cart" :sub   :cart/items ::cart-items)
                (reg-desc "shop.other" :event :other/noise ::noise)]
          img  (image/image
                 {:id :shop/main
                  :select-ns {:include ["shop.cart"]}
                  :registrations
                  {:reg-fx [[:cart.http/post {:doc "post"} ::http-post]]}})
          gen  (asm/assemble [img] pool)]
      (testing "the resolver is keyed by [kind id], one descriptor each"
        (is (contains? (:rf.gen/resolver gen) [:event :cart/add]))
        (is (contains? (:rf.gen/resolver gen) [:sub :cart/items]))
        (is (contains? (:rf.gen/resolver gen) [:fx :cart.http/post]))
        (testing "the framework standard is unioned in"
          (is (contains? (:rf.gen/resolver gen) [:fx :rf.nav/push-url])))
        (testing "the non-selected namespace is NOT in the generation"
          (is (not (contains? (:rf.gen/resolver gen) [:event :other/noise])))))
      (testing "resolve-descriptor reads one descriptor for a (kind, id)"
        (is (= ::cart-add (:handler-fn (asm/resolve-descriptor gen :event :cart/add))))
        (is (= ::http-post (:impl (asm/resolve-descriptor gen :fx :cart.http/post)))))
      (testing "the generation carries the kinds present (EP-0026 retired
                :rf.gen/requires with the image-capability feature)"
        (is (= #{:event :sub :fx} (asm/generation-kinds gen)))
        (is (not (contains? gen :rf.gen/requires))))
      (testing "the sealed generation is an inert immutable value"
        (is (map? gen))
        (is (= gen (asm/assemble [img] pool))
            "equal image inputs over the same pool produce an equal generation")))))

;; ===========================================================================
;; 2. Duplicate-id collision — order NEVER silently decides (the central rule)
;; ===========================================================================

(deftest duplicate-id-no-winner-fails-loud
  (testing "two namespaces registering the same (kind, id) with different impls,
            both selected, with no declared winner → :rf.error/image-duplicate-id
            (load order does NOT pick a survivor)"
    (let [pool [(reg-desc "todo.boot"    :event :boot/init ::todo-boot)
                (reg-desc "counter.boot" :event :boot/init ::counter-boot)]
          img  (image/image {:id :both/img :select-ns {:include ["todo.boot" "counter.boot"]}})]
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest duplicate-id-order-independence
  (testing "the duplicate-id error fires regardless of selection order — there
            is no last-write that 'wins'"
    (let [a    (reg-desc "todo.boot"    :event :boot/init ::a)
          b    (reg-desc "counter.boot" :event :boot/init ::b)
          img  (image/image {:id :i :select-ns {:include ["todo.boot" "counter.boot"]}})]
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble [img] [a b]))))
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble [img] [b a])))))))

(deftest same-registration-selected-twice-dedupes
  (testing "the SAME registration (same coordinate + impl) selected by two
            overlapping globs is a DEDUPE, not a collision — it seals cleanly"
    (let [d    (reg-desc "shop.cart" :event :cart/add ::add)
          img  (image/image {:id :i :select-ns {:include ["shop.*" "shop.cart"]}})
          gen  (asm/assemble [img] [d])]
      (is (= ::add (:handler-fn (asm/resolve-descriptor gen :event :cart/add)))))))

(deftest inline-vs-selected-same-id-collides
  (testing "an inline :counter/inc and a namespace-selected :counter/inc in ONE
            image is a within-image collision — fail loud
            (:rf.error/image-within-image-collision); to override, use a later image"
    (let [pool [(reg-desc "counter.core" :event :counter/inc ::selected)]
          img  (image/image
                 {:id :i
                  :select-ns {:include ["counter.core"]}
                  :registrations {:reg-event [[:counter/inc {} ::inline]]}})]
      (is (= :rf.error/image-within-image-collision
             (assembly-error-id #(asm/assemble [img] pool)))))))

;; ===========================================================================
;; 3. Image-order resolution (EP-0026 §Layered Resolution) — the LATER image
;;    WINS for a cross-image [kind id]; the shadow does NOT fail assembly.
;; ===========================================================================

(deftest later-image-wins-cross-image-override
  (testing "a [kind id] in two composed images resolves to the LATER image's
            descriptor (the test-doubles override image composed after the app image)"
    (let [pool [(reg-desc "checkout.core" :fx :checkout.http/post ::real)]
          app-image    (image/image
                         {:id :app/main :select-ns {:include ["checkout.core"]}})
          test-doubles (image/image
                         {:id :test/doubles
                          :registrations {:reg-fx [[:checkout.http/post {} ::stub]]}})
          gen  (asm/assemble [app-image test-doubles] pool)]
      (is (= ::stub (:impl (asm/resolve-descriptor gen :fx :checkout.http/post)))
          "the later image (test-doubles) wins — image order, not the registrar"))))

(deftest reversing-image-order-reverses-the-winner
  (testing "image order is the ONLY precedence: composing the app image LAST makes
            its selected registration the winner instead"
    (let [pool [(reg-desc "checkout.core" :fx :checkout.http/post ::real)]
          app-image    (image/image
                         {:id :app/main :select-ns {:include ["checkout.core"]}})
          test-doubles (image/image
                         {:id :test/doubles
                          :registrations {:reg-fx [[:checkout.http/post {} ::stub]]}})
          gen  (asm/assemble [test-doubles app-image] pool)]
      (is (= ::real (:handler-fn (asm/resolve-descriptor gen :fx :checkout.http/post)))))))

(deftest cross-image-shadow-does-not-fail-assembly
  (testing "a later image overriding an earlier one RESOLVES (later wins) and does
            NOT fail assembly — a cross-image shadow is reported, not failed"
    (let [pool  [(reg-desc "a.core" :fx :checkout.http/post ::a)
                 (reg-desc "b.core" :fx :checkout.http/post ::b)]
          img-a (image/image {:id :img/a :select-ns {:include ["a.core"]}})
          img-b (image/image {:id :img/b :select-ns {:include ["b.core"]}})
          gen   (asm/assemble [img-a img-b] pool)]
      (is (= ::b (:handler-fn (asm/resolve-descriptor gen :fx :checkout.http/post)))))))

;; ===========================================================================
;; 3b. Within-image disjointness — an image must resolve cleanly to ONE
;;     descriptor per [kind id] (EP-0026 §Layered Resolution). To override, the
;;     winner goes in a LATER image; an override within ONE image is an error.
;; ===========================================================================

(deftest within-image-two-inline-is-malformed
  (testing "two inline entries for the same [kind id] in ONE image →
            :rf.error/image-within-image-collision (define each [kind id] once inline)"
    (let [img (image/image
                {:id :i
                 :registrations {:reg-fx [[:checkout.http/post {} ::a]
                                          [:checkout.http/post {} ::b]]}})]
      (is (= :rf.error/image-within-image-collision
             (assembly-error-id #(asm/assemble [img] [])))))))

(deftest noncolliding-image-seals-cleanly
  (testing "the baseline: a single selected registration with no within-image
            collision seals cleanly to that descriptor"
    (let [pool [(reg-desc "checkout.core" :fx :checkout.http/post ::only)]
          img  (image/image {:id :i :select-ns {:include ["checkout.core"]}})
          gen  (asm/assemble [img] pool)]
      (is (= ::only (:handler-fn (asm/resolve-descriptor gen :fx :checkout.http/post)))))))

;; ===========================================================================
;; 4. Unsupported descriptor kind
;; ===========================================================================

(deftest unsupported-kind-fails-loud
  (testing "a selected descriptor with a kind outside the closed registrar set
            → :rf.error/image-unsupported-kind"
    (let [pool [{:rf.provenance/ns "weird.ns" :kind :not-a-kind :id :x/y
                 :handler-fn ::w}]
          img  (image/image {:id :i :select-ns {:include ["weird.ns"]}})]
      (is (= :rf.error/image-unsupported-kind
             (assembly-error-id #(asm/assemble [img] pool)))))))

;; ===========================================================================
;; 5. Framework-standard replacement policy (default non-replaceable)
;; ===========================================================================

(deftest standard-collision-fails-loud
  (testing "a selected descriptor colliding with a framework STANDARD →
            :rf.error/image-standard-replacement-forbidden (a standard must not
            be shadowed — there is no public :replace-standard opt-in, EP-0026
            §Framework Standard Registrations)"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
    (let [pool [(reg-desc "product.story" :fx :rf.nav/push-url ::app-override)]
          img  (image/image {:id :i :select-ns {:include ["product.story"]}})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest inline-app-shadowing-a-standard-fails-loud
  (testing "an INLINE app entry with the same [kind id] as a framework standard
            also fails loud — a public app image must not shadow a standard
            (EP-0026 §Framework Standard Registrations)"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
    (let [img (image/image {:id :i
                            :registrations {:reg-fx [[:rf.nav/push-url {} ::app]]}})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (assembly-error-id #(asm/assemble [img] [])))))))

(deftest later-image-cannot-shadow-a-standard
  (testing "even a LATER image cannot shadow a framework standard — standards are
            not part of app layer order; the app/standard collision fails loud
            regardless of image position"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
    (let [pool  [(reg-desc "app.core" :event :app/boot ::boot)]
          base  (image/image {:id :base :select-ns {:include ["app.core"]}})
          ovr   (image/image {:id :ovr
                              :registrations {:reg-fx [[:rf.nav/push-url {} ::app]]}})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (assembly-error-id #(asm/assemble [base ovr] pool)))))))

(deftest standard-without-app-collision-is-unioned
  (testing "a framework standard with NO colliding app id is simply unioned into
            the generation"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
    (let [pool [(reg-desc "product.story" :event :product/open ::open)]
          img  (image/image {:id :i :select-ns {:include ["product.story"]}})
          gen  (asm/assemble [img] pool)]
      (is (= ::std (:handler-fn (asm/resolve-descriptor gen :fx :rf.nav/push-url))))
      (is (contains? (:rf.gen/resolver gen) [:event :product/open])))))

;; ===========================================================================
;; 6. Missing reference (application interceptor)
;; ===========================================================================

(deftest missing-interceptor-reference-fails-loud
  (testing "an event whose :interceptors chain names an APPLICATION interceptor
            id with no matching :interceptor registration in the generation →
            :rf.error/image-missing-reference"
    (let [pool [(assoc (reg-desc "app.core" :event :cart/add ::add)
                       :interceptors [:my.audit/guard])]
          img  (image/image {:id :i :select-ns {:include ["app.core"]}})]
      (is (= :rf.error/image-missing-reference
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest present-interceptor-reference-passes
  (testing "the same chain succeeds when the referenced :interceptor IS selected"
    (let [pool [(assoc (reg-desc "app.core" :event :cart/add ::add)
                       :interceptors [:my.audit/guard])
                (reg-desc "app.core" :interceptor :my.audit/guard ::guard)]
          img  (image/image {:id :i :select-ns {:include ["app.core"]}})
          gen  (asm/assemble [img] pool)]
      (is (contains? (:rf.gen/resolver gen) [:interceptor :my.audit/guard])))))

(deftest framework-standard-interceptor-reference-skipped
  (testing "a reserved :rf.interceptor/* reference is framework-provided, NOT
            image-supplied — it is not flagged as a missing reference"
    (let [pool [(assoc (reg-desc "app.core" :event :cart/add ::add)
                       :interceptors [[:rf.interceptor/path [:cart]]])]
          img  (image/image {:id :i :select-ns {:include ["app.core"]}})
          gen  (asm/assemble [img] pool)]
      (is (contains? (:rf.gen/resolver gen) [:event :cart/add])
          "assembly succeeds — the standard ref is not an app-supplied reference"))))

;; ===========================================================================
;; 7. Image capabilities — RETIRED (EP-0026 rf2-dlvmpc). The image-declared
;;    host-capability surface (:rf.image/requires / make-frame :capabilities /
;;    :rf.gen/requires) and the assembly check-capabilities! fn are removed
;;    end-to-end, so this section's coverage is gone. The :rf.image/requires key
;;    failing loud at rf/image construction is pinned in image-cljs-test
;;    (retired-ep0023-image-keys-fail-loud).
;; ===========================================================================

;; ===========================================================================
;; 8. Descriptor-coordinate identity (the source coordinate errors/winners use)
;; ===========================================================================

(deftest descriptor-coordinate-by-source
  (testing "the source coordinate distinguishes registered / inline / standard"
    (is (= {:ns "shop.cart"}
           (asm/descriptor-coordinate (reg-desc "shop.cart" :event :x ::f))))
    (is (= {:image :i :inline [:reg-fx :x]}
           (asm/descriptor-coordinate {:kind :fx :id :x
                                       :rf.provenance/image :i
                                       :rf.provenance/inline [:reg-fx :x]})))
    (is (= {:standard true}
           (asm/descriptor-coordinate {:kind :fx :id :x :standard true})))))

;; ===========================================================================
;; 9. Cross-image layering (EP-0026 §Layered Resolution) — the LATER image
;;    WINS; a chain reports the LAST image as the winner. There is NO cross-image
;;    conflict (a cross-image shadow resolves and is reported, never failed).
;; ===========================================================================

(deftest two-images-later-wins
  (testing "two composed images defining the SAME [kind id] resolve to the LATER
            image — image order is the only precedence (no conflict)"
    (let [pool   [(reg-desc "checkout.story.a" :fx :checkout.http/post ::a)
                  (reg-desc "checkout.story.b" :fx :checkout.http/post ::b)]
          img-a  (image/image {:id :img/a :select-ns {:include ["checkout.story.a"]}})
          img-b  (image/image {:id :img/b :select-ns {:include ["checkout.story.b"]}})]
      (is (= ::b (:handler-fn (asm/resolve-descriptor
                                (asm/assemble [img-a img-b] pool)
                                :fx :checkout.http/post)))
          "img-b wins (last)")
      (is (= ::a (:handler-fn (asm/resolve-descriptor
                                (asm/assemble [img-b img-a] pool)
                                :fx :checkout.http/post)))
          "reversing order reverses the winner"))))

(deftest multi-image-chain-last-image-wins
  (testing "a chain [base override-a override-b] resolves the shared [kind id] to
            the LAST image's descriptor"
    (let [base  (image/image {:id :base
                              :registrations {:reg-fx [[:checkout.http/post {} ::base]]}})
          ov-a  (image/image {:id :ov/a
                              :registrations {:reg-fx [[:checkout.http/post {} ::a]]}})
          ov-b  (image/image {:id :ov/b
                              :registrations {:reg-fx [[:checkout.http/post {} ::b]]}})
          gen   (asm/assemble [base ov-a ov-b] [])]
      (is (= ::b (:impl (asm/resolve-descriptor gen :fx :checkout.http/post)))))))

(deftest duplicate-image-id-across-composition-fails-loud
  (testing "two images sharing an :id within one :images composition →
            :rf.error/image-duplicate-image-id (the shadow report names images by id)"
    (let [pool  [(reg-desc "a.core" :fx :checkout.http/post ::a)
                 (reg-desc "b.core" :fx :checkout.http/post ::b)]
          img-a (image/image {:id :dup :select-ns {:include ["a.core"]}})
          img-b (image/image {:id :dup :select-ns {:include ["b.core"]}})
          d     (assembly-error-data #(asm/assemble [img-a img-b] pool))]
      (is (= :rf.error/image-duplicate-image-id (:rf.error/id d)))
      (is (= [:dup] (:duplicate-image-ids d))))))

;; ===========================================================================
;; 10. Resource → resource-scope resolver reference validation (rf2-32siq3.25)
;;     A :resource descriptor whose spec's :scope is {:from-db <id>} references a
;;     :resource-scope resolver that MUST be selected into the generation.
;; ===========================================================================

(defn- resource-desc
  "A synthetic registered :resource descriptor authored in `provenance-ns` whose
  spec carries `scope` (a {:from-db …} reference or a concrete scope)."
  [provenance-ns resource-id scope]
  {:rf.provenance/ns provenance-ns
   :kind             :resource
   :id               resource-id
   :handler-fn       ::request-fn
   :rf/resource      {:scope scope :params-schema [:map] :request ::request-fn}})

(defn- scope-resolver-desc
  "A synthetic registered :resource-scope resolver authored in `provenance-ns`."
  [provenance-ns scope-id]
  {:rf.provenance/ns provenance-ns
   :kind             :resource-scope
   :id               scope-id
   :handler-fn       ::resolve-fn})

(deftest resource-missing-scope-resolver-fails-loud
  (testing "a :resource whose :scope is {:from-db <id>} naming a scope resolver
            absent from the generation → :rf.error/image-missing-reference"
    (let [pool [(resource-desc "shop.articles" :article/by-slug
                               {:from-db :shop/session})]
          img  (image/image {:id :i :select-ns {:include ["shop.articles"]}})]
      (is (= :rf.error/image-missing-reference
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest resource-present-scope-resolver-passes
  (testing "the same resource seals cleanly when the referenced :resource-scope
            resolver IS selected into the generation"
    (let [pool [(resource-desc "shop.articles" :article/by-slug
                               {:from-db :shop/session})
                (scope-resolver-desc "shop.scopes" :shop/session)]
          img  (image/image {:id :i :select-ns {:include ["shop.articles" "shop.scopes"]}})
          gen  (asm/assemble [img] pool)]
      (is (contains? (:rf.gen/resolver gen) [:resource :article/by-slug]))
      (is (contains? (:rf.gen/resolver gen) [:resource-scope :shop/session])))))

(deftest resource-concrete-scope-references-nothing
  (testing "a :resource with a CONCRETE :scope (no {:from-db …} reference) names
            no scope resolver, so assembly seals cleanly — the missing-reference
            check fires ONLY on an unresolved {:from-db …} reference"
    (let [global   (resource-desc "shop.a" :a/global :rf.scope/global)
          tuple    (resource-desc "shop.b" :b/session [:rf.scope/session {:u 1}])
          from-clr (resource-desc "shop.c" :c/from-caller :rf.scope/from-caller)
          pool     [global tuple from-clr]
          img      (image/image {:id :i :select-ns {:include ["shop.a" "shop.b" "shop.c"]}})
          gen      (asm/assemble [img] pool)]
      (is (contains? (:rf.gen/resolver gen) [:resource :a/global]))
      (is (contains? (:rf.gen/resolver gen) [:resource :b/session]))
      (is (contains? (:rf.gen/resolver gen) [:resource :c/from-caller])))))

(deftest resource-missing-scope-ref-ex-data-is-structured
  (testing "the resource missing-scope-resolver diagnostic carries image, [kind
            id], provenance ns, source coordinate, the missing [:resource-scope
            id] reference, and a repair path (rf2-32siq3.26)"
    (let [pool [(resource-desc "shop.articles" :article/by-slug
                               {:from-db :shop/session})]
          img  (image/image {:id :shop/img :select-ns {:include ["shop.articles"]}})
          d    (assembly-error-data #(asm/assemble [img] pool))]
      (is (= :rf.error/image-missing-reference (:rf.error/id d)))
      (is (= :shop/img (:image d)))
      (is (= :resource (:kind d)))
      (is (= :article/by-slug (:id d)))
      (is (= "shop.articles" (:rf.provenance/ns d)))
      (is (= {:ns "shop.articles"} (:coordinate d)))
      (is (= [:resource-scope :shop/session] (:missing-reference d)))
      (is (= :select-the-missing-registration-or-fix-the-reference (:recovery d))))))

;; ===========================================================================
;; 11. Structured diagnostics audit (rf2-32siq3.26) — every enriched assembly
;;     failure carries image/[kind id]/provenance/repair where applicable.
;; ===========================================================================

(deftest interceptor-missing-ref-ex-data-carries-provenance
  (testing "the (existing) interceptor missing-reference diagnostic now also
            carries the referencing descriptor's provenance ns + source
            coordinate alongside image/[kind id]/missing-reference/recovery"
    (let [pool [(assoc (reg-desc "app.core" :event :cart/add ::add)
                       :interceptors [:my.audit/guard])]
          img  (image/image {:id :app/img :select-ns {:include ["app.core"]}})
          d    (assembly-error-data #(asm/assemble [img] pool))]
      (is (= :rf.error/image-missing-reference (:rf.error/id d)))
      (is (= :app/img (:image d)))
      (is (= :event (:kind d)))
      (is (= :cart/add (:id d)))
      (is (= "app.core" (:rf.provenance/ns d)))
      (is (= {:ns "app.core"} (:coordinate d)))
      (is (= [:interceptor :my.audit/guard] (:missing-reference d)))
      (is (= :select-the-missing-registration-or-fix-the-reference (:recovery d))))))

(deftest unsupported-kind-ex-data-carries-provenance
  (testing "the unsupported-kind diagnostic carries image/kind/id/provenance
            ns/coordinate/recovery (rf2-32siq3.26)"
    (let [pool [{:rf.provenance/ns "weird.ns" :kind :not-a-kind :id :x/y
                 :handler-fn ::w}]
          img  (image/image {:id :w/img :select-ns {:include ["weird.ns"]}})
          d    (assembly-error-data #(asm/assemble [img] pool))]
      (is (= :rf.error/image-unsupported-kind (:rf.error/id d)))
      (is (= :w/img (:image d)))
      (is (= :not-a-kind (:kind d)))
      (is (= :x/y (:id d)))
      (is (= "weird.ns" (:rf.provenance/ns d)))
      (is (= {:ns "weird.ns"} (:coordinate d)))
      (is (= :correct-the-descriptor-kind (:recovery d))))))

(deftest standard-forbidden-ex-data-names-the-app-coordinate
  (testing "the standard-replacement-forbidden diagnostic (EP-0026 §Framework
            Standard Registrations) names the standard coordinate, the app source
            coordinate, [kind id], and a rename/deselect recovery"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
    (let [pool [(reg-desc "product.story" :fx :rf.nav/push-url ::app-override)]
          img  (image/image {:id :p/img :select-ns {:include ["product.story"]}})
          d    (assembly-error-data #(asm/assemble [img] pool))]
      (is (= :rf.error/image-standard-replacement-forbidden (:rf.error/id d)))
      (is (= :fx (:kind d)))
      (is (= :rf.nav/push-url (:id d)))
      (is (= {:standard true} (:standard-coordinate d)))
      (is (= {:ns "product.story"} (:app-coordinate d))
          "the app source colliding with the standard is named")
      (is (= :rename-the-app-id-or-deselect-it (:recovery d))))))

(deftest within-image-duplicate-id-ex-data-names-colliding-coordinates
  (testing "the within-image duplicate-id diagnostic carries image/[kind id]/
            colliding source coordinates and the narrow-or-rename recovery
            (EP-0026 §Layered Resolution)"
    (let [pool [(reg-desc "todo.boot"    :event :boot/init ::a)
                (reg-desc "counter.boot" :event :boot/init ::b)]
          img  (image/image {:id :both/img
                             :select-ns {:include ["todo.boot" "counter.boot"]}})
          d    (assembly-error-data #(asm/assemble [img] pool))]
      (is (= :rf.error/image-duplicate-id (:rf.error/id d)))
      (is (= :both/img (:image d)))
      (is (= :event (:kind d)))
      (is (= :boot/init (:id d)))
      (is (= #{{:ns "todo.boot"} {:ns "counter.boot"}}
             (set (:colliding-coordinates d))))
      (is (= :narrow-the-selection-or-rename-the-id (:recovery d))))))
