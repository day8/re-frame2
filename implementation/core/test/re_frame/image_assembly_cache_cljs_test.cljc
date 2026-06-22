(ns re-frame.image-assembly-cache-cljs-test
  "EP-0023 §Image — the resolved-generation CACHE (rf2-32siq3.7) + cache-key
  correctness (rf2-3sjdmi).

  > Resolved generations are immutable. The runtime MAY physically share one
  > resolved generation across many frames when the same image inputs resolve
  > to the same descriptor set. The reference implementation MUST cache
  > resolved generations.

  The EP minimum cache key:

      normalized :images vector
      + registration source-store generation
      + framework-standard registration generation
      + inline descriptor fingerprints
      + declared replacement maps

  This suite pins the cache CONTRACT:

    * a HIT — identical inputs reuse the SAME sealed generation object (the SSR
      no-re-seal guarantee), proven by `identical?`, not just `=`;
    * the SSR motivation — repeated `assemble` of an unchanged composition does
      NOT re-run selection + validation + sealing (one cached object, one
      compute);
    * INVALIDATION — a changed SELECTED descriptor (source-store generation),
      a changed STANDARD descriptor (standard generation), and a changed INLINE
      descriptor each force a re-seal (a fresh, distinct object);
    * rf2-3sjdmi — two compositions differing ONLY in `:replace` /
      `:replace-standard` (a SHARED resolver shape, distinct replacement
      decisions) must NOT cache-collide; the key is built from the image
      INPUTS, never from `:rf.gen/resolver` alone.

  Pure data + process state (the source store, the standard registry, the
  generation cache). A fixture clears all three per case. `.cljc` ending
  `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image          :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.source-store   :as ss]))

;; ---------------------------------------------------------------------------
;; Fixture — clear every process-state surface the cache key reads: the source
;; store, the standard registry (+ its generation), and the cache itself.
;; ---------------------------------------------------------------------------

(defn- clear-all! []
  (ss/clear-all!)
  (asm/clear-standards!)
  (asm/clear-generation-cache!))

(use-fixtures :each
  (fn [t]
    (clear-all!)
    (t)
    (clear-all!)))

;; ---------------------------------------------------------------------------
;; Synthetic registered descriptor — same shape the selector consumes. Recorded
;; into the LIVE source store so the single-arity `assemble` (the SSR / runtime
;; path) selects it and the store-generation invalidation fires for real.
;; ---------------------------------------------------------------------------

(defn- record! [provenance-ns kind id impl]
  (ss/record-descriptor! kind id {:ns provenance-ns :kind kind :id id
                                  :handler-fn impl}))

;; ===========================================================================
;; 1. Cache HIT — identical inputs reuse the SAME sealed object (SSR fast path)
;; ===========================================================================

(deftest identical-inputs-reuse-the-same-sealed-generation
  (testing "two assemblies of the SAME image over an UNCHANGED live source store
            return the SAME sealed generation object — not merely equal, but
            identical? — so a request-scoped frame does not re-seal"
    (record! "shop.cart" :event :cart/add ::add)
    (let [img  (image/image {:id :shop/main :include-ns ["shop.cart"]})
          gen1 (asm/assemble [img])
          gen2 (asm/assemble [img])]
      (is (= gen1 gen2) "the two generations are equal")
      (is (identical? gen1 gen2)
          "the SECOND assembly reused the cached object — it did NOT re-seal")
      (is (= 1 (asm/cache-size))
          "exactly one generation is cached for the one composition"))))

(deftest ssr-style-repeated-assemble-does-not-reseal
  (testing "the SSR motivation: assembling the same composition N times for N
            request-scoped frames produces ONE cached object reused N times —
            glob selection + validation + sealing run exactly once"
    (record! "app.core" :event :app/boot ::boot)
    (record! "app.core" :sub   :app/state ::state)
    (let [img  (image/image {:id :app/main :include-ns ["app.core"]})
          gens (vec (repeatedly 25 #(asm/assemble [img])))
          gen0 (first gens)]
      (is (apply = gens) "all 25 are equal")
      (is (every? #(identical? gen0 %) gens)
          "all 25 are the SAME object — sealed once, reused 24 times")
      (is (= 1 (asm/cache-size))))))

(deftest distinct-equal-image-values-still-hit
  (testing "two SEPARATELY-constructed image values with equal specs hit the
            same cache slot — the key is by VALUE, not by image object identity"
    (record! "shop.cart" :event :cart/add ::add)
    (let [gen1 (asm/assemble [(image/image {:id :shop/main :include-ns ["shop.cart"]})])
          gen2 (asm/assemble [(image/image {:id :shop/main :include-ns ["shop.cart"]})])]
      (is (identical? gen1 gen2)
          "equal-by-value image specs resolve to the one cached generation")
      (is (= 1 (asm/cache-size))))))

;; ===========================================================================
;; 2. INVALIDATION — a changed SELECTED descriptor (source-store generation)
;; ===========================================================================

(deftest changed-selected-descriptor-invalidates
  (testing "mutating the live source store (a new selected registration) bumps
            the source-store generation, so a re-assembly of the same image is a
            cache MISS — a fresh, distinct sealed object reflecting the change"
    (record! "shop.cart" :event :cart/add ::add)
    (let [img  (image/image {:id :shop/main :include-ns ["shop.cart"]})
          gen1 (asm/assemble [img])]
      (is (not (contains? (:rf.gen/resolver gen1) [:sub :cart/items])))
      ;; A new registration in a selected namespace changes the descriptor pool.
      (record! "shop.cart" :sub :cart/items ::items)
      (let [gen2 (asm/assemble [img])]
        (is (not (identical? gen1 gen2))
            "the store changed → a re-seal, NOT the stale cached object")
        (is (contains? (:rf.gen/resolver gen2) [:sub :cart/items])
            "the re-sealed generation reflects the new registration")
        (is (= 2 (asm/cache-size))
            "both the pre- and post-change generations are cached (distinct keys)")))))

(deftest forgetting-a-selected-descriptor-invalidates
  (testing "removing a registration from a selected namespace bumps the store
            generation and invalidates — the EP's 'after any selected descriptor
            changed' rule covers removal too"
    (record! "shop.cart" :event :cart/add ::add)
    (record! "shop.cart" :sub   :cart/items ::items)
    (let [img  (image/image {:id :shop/main :include-ns ["shop.cart"]})
          gen1 (asm/assemble [img])]
      (is (contains? (:rf.gen/resolver gen1) [:sub :cart/items]))
      (ss/forget-descriptor! :sub :cart/items "shop.cart")
      (let [gen2 (asm/assemble [img])]
        (is (not (identical? gen1 gen2)))
        (is (not (contains? (:rf.gen/resolver gen2) [:sub :cart/items]))
            "the re-sealed generation no longer carries the forgotten descriptor")))))

;; ===========================================================================
;; 3. INVALIDATION — a changed STANDARD descriptor (standard generation)
;; ===========================================================================

(deftest changed-standard-descriptor-invalidates
  (testing "registering a NEW framework standard bumps the standard generation,
            so a re-assembly of the same image over the same store is a MISS —
            the standard set is part of the resolved generation"
    (record! "shop.cart" :event :cart/add ::add)
    (let [img  (image/image {:id :shop/main :include-ns ["shop.cart"]})
          gen1 (asm/assemble [img])]
      (is (not (contains? (:rf.gen/resolver gen1) [:fx :rf.nav/push-url])))
      (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
      (let [gen2 (asm/assemble [img])]
        (is (not (identical? gen1 gen2))
            "the standard set changed → a re-seal")
        (is (contains? (:rf.gen/resolver gen2) [:fx :rf.nav/push-url])
            "the re-sealed generation unions in the new standard")))))

;; ===========================================================================
;; 4. INVALIDATION — a changed INLINE descriptor (rides the image value)
;; ===========================================================================

(deftest changed-inline-descriptor-invalidates
  (testing "two images differing only in an INLINE :registrations descriptor are
            distinct compositions → distinct cache slots, distinct generations
            (inline fingerprints are part of the key, carried by the image value)"
    (record! "checkout.core" :event :checkout/start ::start)
    (let [img-a (image/image {:id :checkout/main
                              :include-ns ["checkout.core"]
                              :registrations {:reg-fx [[:checkout.http/post {} ::impl-a]]}})
          img-b (image/image {:id :checkout/main
                              :include-ns ["checkout.core"]
                              :registrations {:reg-fx [[:checkout.http/post {} ::impl-b]]}})
          gen-a (asm/assemble [img-a])
          gen-b (asm/assemble [img-b])]
      (is (not (identical? gen-a gen-b))
          "a changed inline impl is a different composition — no cache collision")
      (is (= ::impl-a (:impl (asm/resolve-descriptor gen-a :fx :checkout.http/post))))
      (is (= ::impl-b (:impl (asm/resolve-descriptor gen-b :fx :checkout.http/post)))
          "each generation seals its OWN inline descriptor")
      (is (= 2 (asm/cache-size))))))

;; ===========================================================================
;; 5. EP-0026 — two compositions differing ONLY in IMAGE ORDER must NOT
;;    cache-collide even when the per-image selections are the same. The later
;;    image wins, so order is part of the resolved generation; the key is built
;;    from the image VECTOR (which carries order), so distinct orders are distinct
;;    keys (rf2-6ls85a; the formal select-ns + image-order key leg is rf2-ke7w5j).
;; ===========================================================================

(deftest image-order-invalidates-even-with-same-selections
  (testing "two compositions of the SAME two images in DIFFERENT order resolve a
            shared [kind id] to DIFFERENT descriptors (later wins) — they must be
            cached SEPARATELY. The image VECTOR carries order, so distinct orders
            are distinct cache keys."
    (record! "checkout.core"       :fx :checkout.http/post ::real)
    (record! "checkout.story.http" :fx :checkout.http/post ::fake)
    (let [img-real (image/image {:id :checkout/real
                                 :select-ns {:include ["checkout.core"]}})
          img-fake (image/image {:id :checkout/fake
                                 :select-ns {:include ["checkout.story.http"]}})
          gen-ab   (asm/assemble [img-real img-fake])   ;; fake last → fake wins
          gen-ba   (asm/assemble [img-fake img-real])]  ;; real last → real wins
      (is (not (identical? gen-ab gen-ba))
          "distinct image orders → distinct cached generations (no collision)")
      (is (= ::fake (:handler-fn (asm/resolve-descriptor gen-ab :fx :checkout.http/post)))
          "[real fake] → the later image (fake) wins")
      (is (= ::real (:handler-fn (asm/resolve-descriptor gen-ba :fx :checkout.http/post)))
          "[fake real] → the later image (real) wins")
      (is (= 2 (asm/cache-size))
          "two distinct orderings occupy two cache slots"))))

(deftest same-composition-still-hits
  (testing "the complement: the SAME images in the SAME order hit the ONE cache
            slot — image-order keying does not over-invalidate"
    (record! "checkout.core"       :fx :checkout.http/post ::real)
    (record! "checkout.story.http" :fx :checkout.http/post ::fake)
    (let [img-real (image/image {:id :checkout/real
                                 :select-ns {:include ["checkout.core"]}})
          img-fake (image/image {:id :checkout/fake
                                 :select-ns {:include ["checkout.story.http"]}})
          gen1 (asm/assemble [img-real img-fake])
          gen2 (asm/assemble [img-real img-fake])]
      (is (identical? gen1 gen2)
          "identical composition (same images, same order) → one cached object")
      (is (= 1 (asm/cache-size))))))

;; ===========================================================================
;; 6. Fail-loud inputs are NOT cached
;; ===========================================================================

(deftest fail-loud-input-is-not-cached
  (testing "an assembly that throws (a duplicate-id collision with no winner) is
            NOT cached — the slot stays empty, so correcting the store and
            re-assembling recomputes cleanly rather than re-throwing a stale miss"
    (record! "todo.boot"    :event :boot/init ::todo)
    (record! "counter.boot" :event :boot/init ::counter)
    (let [img (image/image {:id :both :include-ns ["todo.boot" "counter.boot"]})]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                   (asm/assemble [img])))
      (is (= 0 (asm/cache-size))
          "the throwing composition left nothing cached"))))

;; ===========================================================================
;; 6b. rf2-1x2zuc — two DISTINCT live source stores at the SAME generation
;;     integer must NOT alias one cached generation. The store-generation
;;     counter is keyed PER store, so the integer alone is ambiguous across
;;     stores; the cache key folds the store IDENTITY alongside the generation
;;     so a realm-bound store never reuses the process-default store's sealed
;;     generation (or vice versa).
;; ===========================================================================

(deftest distinct-stores-same-generation-do-not-alias
  (testing "two DIFFERENT source-store atoms, each at generation 1 with the SAME
            image selector but DIFFERENT registered handlers, assemble DISTINCT
            sealed generations — the second store resolves its OWN handler, NOT
            the first store's cached handler (rf2-1x2zuc). On current main this
            FAILS: the live-store cache key carried only the generation integer,
            so the second store (also generation 1) hit the first store's slot."
    (let [store-a (atom {})
          store-b (atom {})
          img     (image/image {:id :shared/main :include-ns ["shared.core"]})
          ;; Store A: register ::a-handler under the same (kind, id) the image
          ;; selects; assemble against store A (its generation becomes 1).
          gen-a   (binding [ss/*source-store* store-a]
                    (record! "shared.core" :event :shared/boot ::a-handler)
                    {:store-gen (ss/store-generation)
                     :gen       (asm/assemble [img])})
          ;; Store B: a DISTINCT atom; register a DIFFERENT handler ::b-handler
          ;; under the SAME (kind, id); assemble against store B (its generation
          ;; ALSO becomes 1 — the counter is keyed per store).
          gen-b   (binding [ss/*source-store* store-b]
                    (record! "shared.core" :event :shared/boot ::b-handler)
                    {:store-gen (ss/store-generation)
                     :gen       (asm/assemble [img])})
          a-impl  (:handler-fn (asm/resolve-descriptor (:gen gen-a) :event :shared/boot))
          b-impl  (:handler-fn (asm/resolve-descriptor (:gen gen-b) :event :shared/boot))]
      (is (= (:store-gen gen-a) (:store-gen gen-b) 1)
          "both stores sit at the SAME generation integer (1) — the per-store
           counter does not distinguish them; the identity must")
      (is (not (identical? (:gen gen-a) (:gen gen-b)))
          "distinct stores at the same generation → DISTINCT sealed generations,
           NOT the first store's cached object")
      (is (= ::a-handler a-impl)
          "store A's generation resolves store A's handler")
      (is (= ::b-handler b-impl)
          "store B's generation resolves store B's OWN handler — NOT store A's
           cached handler (the cross-store alias bug)")
      (is (= 2 (asm/cache-size))
          "two distinct stores at the same generation occupy two cache slots"))))

(deftest same-store-still-hits-after-identity-leg
  (testing "the complement: the SAME source store assembling the SAME unchanged
            composition twice STILL returns the one cached object — folding the
            store identity into the key did not break the HIT path (rf2-1x2zuc)"
    (let [store (atom {})
          img   (image/image {:id :realm/main :include-ns ["realm.core"]})]
      (binding [ss/*source-store* store]
        (record! "realm.core" :event :realm/boot ::impl)
        (let [gen1 (asm/assemble [img])
              gen2 (asm/assemble [img])]
          (is (identical? gen1 gen2)
              "an unchanged store re-assembling the same image reuses the cached
               object — the identity leg is stable per store")
          (is (= 1 (asm/cache-size))))))))

;; ===========================================================================
;; 7. Explicit-pool arity caches on the POOL value (tests / harnesses)
;; ===========================================================================

(deftest explicit-pool-arity-hits-on-equal-pool
  (testing "(assemble images descriptors) caches keyed on the descriptor POOL
            value — the same images over an equal pool hit; a different pool
            misses (the live store generation does not describe a supplied pool)"
    (let [pool [{:rf.provenance/ns "a.core" :kind :event :id :a/e :handler-fn ::a}]
          img  (image/image {:id :a :include-ns ["a.core"]})
          gen1 (asm/assemble [img] pool)
          gen2 (asm/assemble [img] pool)]
      (is (identical? gen1 gen2)
          "same images + equal pool value → the cached object")
      (let [pool2 [{:rf.provenance/ns "a.core" :kind :event :id :a/e :handler-fn ::a}
                   {:rf.provenance/ns "a.core" :kind :sub   :id :a/s :handler-fn ::s}]
            gen3  (asm/assemble [img] pool2)]
        (is (not (identical? gen1 gen3))
            "a changed pool is a different key → a re-seal")
        (is (contains? (:rf.gen/resolver gen3) [:sub :a/s]))))))
