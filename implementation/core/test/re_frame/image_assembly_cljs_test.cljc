(ns re-frame.image-assembly-cljs-test
  "EP-0023 §Image Validation / §Image Composition / §Image Patching And
  Overrides — the ASSEMBLY slice (rf2-32siq3.4): resolve image values into a
  SEALED, VALIDATED `[kind id]` generation and fail loud before a frame runs.

  Pins the bead's enumerated coverage:

    * successful projection — selected (by `:include-ns`) + inline + framework
      standard → an immutable `[kind id]` resolver;
    * duplicate-id collision with no declared winner FAILS LOUD (order never
      decides the survivor);
    * dedupe — the same registration selected twice is NOT a collision;
    * declared `:replace` winner resolves an application-owned collision;
    * stale / ambiguous replacement winner FAILS LOUD;
    * `:replace` / `:replace-standard` declared for a key with NO actual
      collision (zero or exactly one selected descriptor) FAILS LOUD — a
      replacement resolves an intentional collision, never a silent order
      override (rf2-32siq3.5 winner policy);
    * unsupported descriptor kind FAILS LOUD;
    * a framework STANDARD collision without `:replace-standard` FAILS LOUD;
    * `:replace-standard` on a NON-replaceable standard FAILS LOUD (incl. an
      invariant-coupled standard);
    * `:replace-standard` on a replaceable standard succeeds;
    * missing application interceptor reference FAILS LOUD;
    * capability checking (rf2-32siq3.6): missing capability FAILS LOUD; an
      empty/absent `:rf.image/requires` is a no-op; an absent (nil) or empty
      `:capabilities` map fails any non-empty requires (EP-0013 fail-loud
      parity); a partial-capability map fails on exactly the unmet subset; the
      multi-image requires UNION is collected and checked as one set.

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
                  :include-ns ["shop.cart"]
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
      (testing "the generation carries kinds + (empty) requires"
        (is (= #{:event :sub :fx} (asm/generation-kinds gen)))
        (is (= #{} (:rf.gen/requires gen))))
      (testing "the sealed generation is an inert immutable value"
        (is (map? gen))
        (is (= gen (asm/assemble [img] pool))
            "equal image inputs over the same pool produce an equal generation")))))

(deftest requires-union-carried-onto-the-generation
  (testing "the union of every image's :rf.image/requires is carried on the
            generation for the slice-.6 capability check"
    (let [pool [(reg-desc "a.core" :event :a/e ::a)]
          img  (image/image {:id :a/img
                             :include-ns ["a.core"]
                             :rf.image/requires #{:rf.capability/http
                                                  :rf.capability/schemas}})
          gen  (asm/assemble [img] pool)]
      (is (= #{:rf.capability/http :rf.capability/schemas}
             (:rf.gen/requires gen))))))

;; ===========================================================================
;; 2. Duplicate-id collision — order NEVER silently decides (the central rule)
;; ===========================================================================

(deftest duplicate-id-no-winner-fails-loud
  (testing "two namespaces registering the same (kind, id) with different impls,
            both selected, with no declared winner → :rf.error/image-duplicate-id
            (load order does NOT pick a survivor)"
    (let [pool [(reg-desc "todo.boot"    :event :boot/init ::todo-boot)
                (reg-desc "counter.boot" :event :boot/init ::counter-boot)]
          img  (image/image {:id :both/img :include-ns ["todo.boot" "counter.boot"]})]
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest duplicate-id-order-independence
  (testing "the duplicate-id error fires regardless of selection order — there
            is no last-write that 'wins'"
    (let [a    (reg-desc "todo.boot"    :event :boot/init ::a)
          b    (reg-desc "counter.boot" :event :boot/init ::b)
          img  (image/image {:id :i :include-ns ["todo.boot" "counter.boot"]})]
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble [img] [a b]))))
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble [img] [b a])))))))

(deftest same-registration-selected-twice-dedupes
  (testing "the SAME registration (same coordinate + impl) selected by two
            overlapping globs is a DEDUPE, not a collision — it seals cleanly"
    (let [d    (reg-desc "shop.cart" :event :cart/add ::add)
          img  (image/image {:id :i :include-ns ["shop.*" "shop.cart"]})
          gen  (asm/assemble [img] [d])]
      (is (= ::add (:handler-fn (asm/resolve-descriptor gen :event :cart/add)))))))

(deftest inline-vs-selected-same-id-collides
  (testing "an inline :counter/inc and a namespace-selected :counter/inc are the
            same collision class — fail loud without a declared winner"
    (let [pool [(reg-desc "counter.core" :event :counter/inc ::selected)]
          img  (image/image
                 {:id :i
                  :include-ns ["counter.core"]
                  :registrations {:reg-event [[:counter/inc {} ::inline]]}})]
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble [img] pool)))))))

;; ===========================================================================
;; 3. Declared :replace winner (the .5 seam — detection + simple winner rule)
;; ===========================================================================

(deftest replace-winner-resolves-application-collision
  (testing "a declared :replace winner naming one selected descriptor's
            coordinate resolves the collision to that descriptor"
    (let [pool [(reg-desc "checkout.core" :fx :checkout.http/post ::real)
                (reg-desc "checkout.story.http" :fx :checkout.http/post ::fake)]
          img  (image/image
                 {:id :checkout/story
                  :include-ns ["checkout.core" "checkout.story.http"]
                  :replace {[:fx :checkout.http/post] {:ns "checkout.story.http"}}})
          gen  (asm/assemble [img] pool)]
      (is (= ::fake (:handler-fn (asm/resolve-descriptor gen :fx :checkout.http/post)))
          "the declared winner (the story double) is the survivor, not load order"))))

(deftest replace-winner-to-inline-coordinate
  (testing "a :replace winner can name an INLINE descriptor coordinate
            ({:image :inline}) as the survivor of a collision with a selected one"
    (let [pool [(reg-desc "checkout.core" :fx :checkout.http/post ::real)]
          img  (image/image
                 {:id :checkout/story
                  :include-ns ["checkout.core"]
                  :registrations {:reg-fx [[:checkout.http/post {} ::inline-fake]]}
                  :replace {[:fx :checkout.http/post]
                            {:image :checkout/story :inline [:reg-fx :checkout.http/post]}}})
          gen  (asm/assemble [img] pool)]
      (is (= ::inline-fake
             (:impl (asm/resolve-descriptor gen :fx :checkout.http/post)))))))

(deftest stale-replace-winner-fails-loud
  (testing "a :replace winner naming a coordinate that no selected descriptor
            has (a stale ns, a typo) → :rf.error/image-replacement-winner-unresolved"
    (let [pool [(reg-desc "checkout.core" :fx :checkout.http/post ::real)
                (reg-desc "checkout.story.http" :fx :checkout.http/post ::fake)]
          img  (image/image
                 {:id :i
                  :include-ns ["checkout.core" "checkout.story.http"]
                  :replace {[:fx :checkout.http/post] {:ns "checkout.story.STALE"}}})]
      (is (= :rf.error/image-replacement-winner-unresolved
             (assembly-error-id #(asm/assemble [img] pool)))))))

;; ===========================================================================
;; 3b. Replacement declares a REAL collision only (rf2-32siq3.5 winner policy):
;;     a :replace / :replace-standard for a key with no actual collision is an
;;     error — replacement resolves an intentional collision, it is NEVER a
;;     silent order override. The complement of the winner-unresolved check.
;; ===========================================================================

(deftest replace-on-noncolliding-single-selection-fails-loud
  (testing "a :replace declared for a (kind, id) with exactly ONE selected
            descriptor (no collision) → :rf.error/image-replacement-no-collision
            — naming a winner where there is nothing to replace is an order
            override, not a collision resolution"
    (let [pool [(reg-desc "checkout.core" :fx :checkout.http/post ::only)]
          img  (image/image
                 {:id :i
                  :include-ns ["checkout.core"]
                  :replace {[:fx :checkout.http/post] {:ns "checkout.core"}}})]
      (is (= :rf.error/image-replacement-no-collision
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest replace-on-absent-key-fails-loud
  (testing "a :replace declared for a (kind, id) that NO selected descriptor has
            (a typo / a stale id) → :rf.error/image-replacement-no-collision —
            there is no collision (zero descriptors) to resolve"
    (let [pool [(reg-desc "checkout.core" :fx :checkout.http/post ::only)]
          img  (image/image
                 {:id :i
                  :include-ns ["checkout.core"]
                  :replace {[:fx :checkout.http/TYPO] {:ns "checkout.core"}}})]
      (is (= :rf.error/image-replacement-no-collision
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest replace-standard-on-noncolliding-key-fails-loud
  (testing "a :replace-standard declared for a key with no actual collision is
            ALSO a no-collision error (the non-collision check runs BEFORE the
            standard-policy guard — there is no real collision to police)"
    ;; A replaceable standard exists, but the app does NOT select a colliding
    ;; descriptor — so the standard alone occupies the (kind, id): no collision.
    (asm/register-standard! :fx :rf.nav/push-url
                            {:handler-fn ::std :rf.standard/replaceable? true})
    (let [pool [(reg-desc "product.core" :event :app/boot ::boot)]
          img  (image/image
                 {:id :i
                  :include-ns ["product.core"]
                  :replace-standard {[:fx :rf.nav/push-url] {:ns "product.core"}}})]
      (is (= :rf.error/image-replacement-no-collision
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest noncolliding-image-without-replacement-seals-cleanly
  (testing "the baseline: the SAME single selection with NO :replace declaration
            seals cleanly — the no-collision error fires ONLY on a spurious
            replacement declaration, never on an ordinary single registration"
    (let [pool [(reg-desc "checkout.core" :fx :checkout.http/post ::only)]
          img  (image/image {:id :i :include-ns ["checkout.core"]})
          gen  (asm/assemble [img] pool)]
      (is (= ::only (:handler-fn (asm/resolve-descriptor gen :fx :checkout.http/post)))))))

(deftest check-replacement-keys-collide-is-callable-directly
  (testing "the .5 seam fn rejects a non-colliding key and accepts a real
            collision when called against a post-dedupe distinct-by-id view"
    (let [real-collision {[:fx :x] [(reg-desc "a" :fx :x ::a)
                                    (reg-desc "b" :fx :x ::b)]}
          single         {[:fx :x] [(reg-desc "a" :fx :x ::a)]}]
      (testing "a real collision passes the key check (returns nil, no throw)"
        (is (nil? (asm/check-replacement-keys-collide!
                    :i real-collision {[:fx :x] {:ns "a"}} {}))))
      (testing "a single (uncollided) selection fails loud"
        (is (= :rf.error/image-replacement-no-collision
               (assembly-error-id
                 #(asm/check-replacement-keys-collide!
                    :i single {[:fx :x] {:ns "a"}} {}))))))))

;; ===========================================================================
;; 4. Unsupported descriptor kind
;; ===========================================================================

(deftest unsupported-kind-fails-loud
  (testing "a selected descriptor with a kind outside the closed registrar set
            → :rf.error/image-unsupported-kind"
    (let [pool [{:rf.provenance/ns "weird.ns" :kind :not-a-kind :id :x/y
                 :handler-fn ::w}]
          img  (image/image {:id :i :include-ns ["weird.ns"]})]
      (is (= :rf.error/image-unsupported-kind
             (assembly-error-id #(asm/assemble [img] pool)))))))

;; ===========================================================================
;; 5. Framework-standard replacement policy (default non-replaceable)
;; ===========================================================================

(deftest standard-collision-without-replace-standard-fails-loud
  (testing "a selected descriptor colliding with a framework STANDARD, with no
            :replace-standard, → :rf.error/image-standard-replacement-forbidden
            (a standard must not be shadowed accidentally)"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
    (let [pool [(reg-desc "product.story" :fx :rf.nav/push-url ::app-override)]
          img  (image/image {:id :i :include-ns ["product.story"]})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest replace-standard-on-nonreplaceable-fails-loud
  (testing "a :replace-standard declaration against a standard that is NOT
            marked :rf.standard/replaceable? (the default) → forbidden"
    (asm/register-standard! :fx :rf.nav/push-url
                            {:handler-fn ::std :rf.standard/replaceable? false})
    (let [pool [(reg-desc "product.story" :fx :rf.nav/push-url ::app-override)]
          img  (image/image
                 {:id :i
                  :include-ns ["product.story"]
                  :replace-standard {[:fx :rf.nav/push-url] {:ns "product.story"}}})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest replace-standard-on-invariant-coupled-fails-loud
  (testing "an invariant-coupled standard (:rf.standard/requires-conformance
            non-empty) is NOT image-replaceable even with :replace-standard +
            :rf.standard/replaceable? true — the EP keeps :rf.interceptor/path
            non-replaceable until a conformance profile exists"
    (asm/register-standard! :interceptor :rf.interceptor/path
                            {:handler-fn ::path
                             :rf.standard/replaceable? true
                             :rf.standard/requires-conformance #{:identical-no-op}})
    (let [pool [(reg-desc "naive.override" :interceptor :rf.interceptor/path ::naive)]
          img  (image/image
                 {:id :i
                  :include-ns ["naive.override"]
                  :replace-standard {[:interceptor :rf.interceptor/path]
                                     {:ns "naive.override"}}})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest replace-standard-on-replaceable-succeeds
  (testing "a :replace-standard against a standard explicitly marked replaceable
            (no conformance requirement) resolves to the app descriptor"
    (asm/register-standard! :fx :rf.nav/push-url
                            {:handler-fn ::std :rf.standard/replaceable? true})
    (let [pool [(reg-desc "product.story" :fx :rf.nav/push-url ::app-override)]
          img  (image/image
                 {:id :i
                  :include-ns ["product.story"]
                  :replace-standard {[:fx :rf.nav/push-url] {:ns "product.story"}}})
          gen  (asm/assemble [img] pool)]
      (is (= ::app-override
             (:handler-fn (asm/resolve-descriptor gen :fx :rf.nav/push-url)))))))

;; ===========================================================================
;; 6. Missing reference (application interceptor)
;; ===========================================================================

(deftest missing-interceptor-reference-fails-loud
  (testing "an event whose :interceptors chain names an APPLICATION interceptor
            id with no matching :interceptor registration in the generation →
            :rf.error/image-missing-reference"
    (let [pool [(assoc (reg-desc "app.core" :event :cart/add ::add)
                       :interceptors [:my.audit/guard])]
          img  (image/image {:id :i :include-ns ["app.core"]})]
      (is (= :rf.error/image-missing-reference
             (assembly-error-id #(asm/assemble [img] pool)))))))

(deftest present-interceptor-reference-passes
  (testing "the same chain succeeds when the referenced :interceptor IS selected"
    (let [pool [(assoc (reg-desc "app.core" :event :cart/add ::add)
                       :interceptors [:my.audit/guard])
                (reg-desc "app.core" :interceptor :my.audit/guard ::guard)]
          img  (image/image {:id :i :include-ns ["app.core"]})
          gen  (asm/assemble [img] pool)]
      (is (contains? (:rf.gen/resolver gen) [:interceptor :my.audit/guard])))))

(deftest framework-standard-interceptor-reference-skipped
  (testing "a reserved :rf.interceptor/* reference is framework-provided, NOT
            image-supplied — it is not flagged as a missing reference"
    (let [pool [(assoc (reg-desc "app.core" :event :cart/add ::add)
                       :interceptors [[:rf.interceptor/path [:cart]]])]
          img  (image/image {:id :i :include-ns ["app.core"]})
          gen  (asm/assemble [img] pool)]
      (is (contains? (:rf.gen/resolver gen) [:event :cart/add])
          "assembly succeeds — the standard ref is not an app-supplied reference"))))

;; ===========================================================================
;; 7. Capability check (the .6 seam fn — fail-loud point)
;; ===========================================================================

(deftest missing-capability-fails-loud
  (testing "check-capabilities! throws :rf.error/image-missing-capability when a
            required capability is absent from the frame's supplied map"
    (is (= :rf.error/image-missing-capability
           (assembly-error-id
             #(asm/check-capabilities! #{:rf.capability/http :rf.capability/schemas}
                                       {:rf.capability/http ::http-impl}))))))

(deftest present-capabilities-pass
  (testing "check-capabilities! returns when every required capability is supplied"
    (is (= #{:rf.capability/http}
           (asm/check-capabilities! #{:rf.capability/http}
                                    {:rf.capability/http ::http-impl
                                     :rf.capability/schemas ::s})))))

(deftest empty-requires-is-a-no-op
  (testing "check-capabilities! with NO requirements is a no-op regardless of the
            supplied map — including an empty or nil capability map"
    (is (= #{} (asm/check-capabilities! #{} {:rf.capability/http ::http-impl})))
    (is (= #{} (asm/check-capabilities! #{} {})))
    (is (= #{} (asm/check-capabilities! #{} nil)))))

(deftest absent-capability-map-fails-non-empty-requires
  (testing "a nil :capabilities map provides nothing, so ANY non-empty requires
            fails loud — an absent map is read as {} (EP-0013 fail-loud parity)"
    (is (= :rf.error/image-missing-capability
           (assembly-error-id
             #(asm/check-capabilities! #{:rf.capability/http} nil))))
    (testing "an empty {} map behaves identically to nil"
      (is (= :rf.error/image-missing-capability
             (assembly-error-id
               #(asm/check-capabilities! #{:rf.capability/http} {})))))))

(deftest partial-capabilities-fail-on-the-unmet-subset
  (testing "when SOME required capabilities are supplied but not all, the check
            fails loud and the diagnostic names exactly the missing subset"
    (let [ex (try (asm/check-capabilities!
                    #{:rf.capability/http :rf.capability/schemas :rf.capability/storage}
                    {:rf.capability/http ::http-impl})
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
          d  (ex-data ex)]
      (is (= :rf.error/image-missing-capability (:rf.error/id d)))
      (testing "the missing subset is exactly the unmet capabilities, sorted
                (the :extra slots are merged at the top level of the ex-data)"
        (is (= [:rf.capability/schemas :rf.capability/storage]
               (:missing-capabilities d))))
      (testing "the supplied set names what the frame DID provide (a CAPABILITY
                gap, not a registration gap)"
        (is (= [:rf.capability/http]
               (:supplied-capabilities d)))))))

(deftest multi-image-requires-union-then-checked
  (testing "the generation's :rf.gen/requires is the UNION across all images'
            :rf.image/requires, and check-capabilities! is satisfied only when
            EVERY image's requirement is supplied"
    (let [pool [(reg-desc "a.core" :event :a/e ::a)
                (reg-desc "b.core" :event :b/e ::b)]
          img-a (image/image {:id :a/img :include-ns ["a.core"]
                              :rf.image/requires #{:rf.capability/http}})
          img-b (image/image {:id :b/img :include-ns ["b.core"]
                              :rf.image/requires #{:rf.capability/schemas}})
          gen   (asm/assemble [img-a img-b] pool)]
      (testing "the union of both images' requires rides the generation"
        (is (= #{:rf.capability/http :rf.capability/schemas}
               (:rf.gen/requires gen))))
      (testing "a map satisfying only ONE image's requirement still fails loud
                — the union must be fully satisfied"
        (is (= :rf.error/image-missing-capability
               (assembly-error-id
                 #(asm/check-capabilities! (:rf.gen/requires gen)
                                           {:rf.capability/http ::http-impl})))))
      (testing "a map satisfying the FULL union passes"
        (is (= #{:rf.capability/http :rf.capability/schemas}
               (asm/check-capabilities! (:rf.gen/requires gen)
                                        {:rf.capability/http    ::http-impl
                                         :rf.capability/schemas ::schemas
                                         :rf.capability/extra   ::ignored})))))))

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
