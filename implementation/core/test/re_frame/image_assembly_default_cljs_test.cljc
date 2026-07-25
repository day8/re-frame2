(ns re-frame.image-assembly-default-cljs-test
  "EP-0023 §Default Image Semantics / Bead Plan item 6 — the DEFAULT image
  projection over the source store (rf2-32siq3.24).

  > the default image is the implicit selector over [the default registration]
  > source [store] … The default image is the implicit selector over all
  > descriptors in the default source store. It works only while selected ids
  > are globally unique across the loaded namespaces. If two loaded namespaces
  > both register the same `(kind, id)`, the default image does not guess and
  > does not let load order win; default image assembly fails with a collision
  > error.

  Pins the bead's enumerated coverage:

    * the default projection includes ALL source-store descriptors + the
      framework standards;
    * a cross-namespace same-`(kind, id)` collision in the default projection
      FAILS LOUD (`:rf.error/image-duplicate-id`) — no clobber, no
      last-write-wins on the default path;
    * the default generation is CACHED and INVALIDATES on a source-store change
      (the .7 cache, keyed on the source-store generation);
    * a single-namespace default projects cleanly;
    * `assemble` with NO / empty `:images` routes to the default projection
      (the empty-images entry).

  Each fail-loud assertion checks the `:rf.error/id` discriminator, never the
  message bytes (Spec 009 §The thrown-error shape rule 3).

  The deterministic projection cases use the EXPLICIT-POOL form
  `(assemble-default descriptors)` — no live source-store mutation. The
  cache-invalidation case needs the LIVE store, so it SNAPSHOTS the source-store
  atom and RESTORES it (per the bead — no `clear-all!` that would destroy
  authored registrations) and clears only the derived standard registry +
  generation cache. `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND
  `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image          :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.source-store   :as ss]))

;; ---------------------------------------------------------------------------
;; Fixture — SNAPSHOT/RESTORE the live source store (do NOT clear-all! — that
;; would destroy real authored registrations). The standard registry + the
;; generation cache are DERIVED process state, safe to reset per case so a stale
;; generation never leaks across a case that mutated the store.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    (let [store-before @ss/kind->id->ns->descriptor]
      (asm/clear-standards!)
      (asm/clear-generation-cache!)
      (try
        (t)
        (finally
          ;; Restore the source store to exactly its pre-case contents.
          (reset! ss/kind->id->ns->descriptor store-before)
          (asm/clear-standards!)
          (asm/clear-generation-cache!))))))

;; ---------------------------------------------------------------------------
;; Synthetic registered descriptors — same shape the selector consumes. The
;; load-bearing field for collision detection is :rf.provenance/ns (the source
;; coordinate) + :handler-fn (the impl that distinguishes a real collision from
;; a dedupe).
;; ---------------------------------------------------------------------------

(defn- reg-desc
  "A synthetic REGISTERED descriptor authored in `provenance-ns`."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- assembly-error-id
  "Run `thunk`; return the `:rf.error/id` of the thrown ex-info (or nil if it did
  not throw). Branches on the discriminator, never the message."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- record! [provenance-ns kind id impl]
  (ss/record-descriptor! kind id {:ns provenance-ns :kind kind :id id
                                  :handler-fn impl}))

;; ===========================================================================
;; 1. The default projection includes ALL source-store descriptors + standards
;; ===========================================================================

(deftest default-projection-includes-the-whole-pool-plus-standards
  (testing "the default image is the implicit selector over the WHOLE source
            store: it projects EVERY descriptor across every namespace + the
            framework standards into a sealed [kind id] resolver"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (let [pool [(reg-desc "shop.cart"    :event :cart/add   ::cart-add)
                (reg-desc "shop.cart"    :sub   :cart/items ::cart-items)
                (reg-desc "shop.auth"    :event :auth/login ::auth-login)
                (reg-desc "shop.catalog" :view  :catalog/grid ::grid)]
          gen  (asm/assemble-default pool)]
      (testing "every namespace's descriptors are selected (no glob — the whole
                store)"
        (is (contains? (:rf.gen/resolver gen) [:event :cart/add]))
        (is (contains? (:rf.gen/resolver gen) [:sub   :cart/items]))
        (is (contains? (:rf.gen/resolver gen) [:event :auth/login]))
        (is (contains? (:rf.gen/resolver gen) [:view  :catalog/grid])))
      (testing "the framework standard is unioned in, exactly as the explicit
                path"
        (is (contains? (:rf.gen/resolver gen) [:fx :rf.nav/push-url]))
        (is (= ::std-nav (:handler-fn (asm/resolve-descriptor gen :fx :rf.nav/push-url)))))
      (testing "resolve-descriptor reads one descriptor per (kind, id)"
        (is (= ::cart-add (:handler-fn (asm/resolve-descriptor gen :event :cart/add))))
        (is (= ::grid (:handler-fn (asm/resolve-descriptor gen :view :catalog/grid)))))
      (testing "the generation carries the kinds present"
        (is (= #{:event :sub :view :fx} (asm/generation-kinds gen)))))))

(deftest empty-store-default-projects-an-empty-generation
  (testing "the default image over an EMPTY source store is a VALID empty
            projection (resolving only framework standards) — no zero-match
            fail-loud, unlike a :select-ns :include glob"
    (let [gen (asm/assemble-default [])]
      (is (map? gen))
      (is (= {} (:rf.gen/resolver gen)))
      (is (= #{} (asm/generation-kinds gen)))
      (testing "with only a framework standard present, the default projects it"
        (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
        (asm/clear-generation-cache!) ;; the standard generation also invalidates
        (let [gen2 (asm/assemble-default [])]
          (is (contains? (:rf.gen/resolver gen2) [:fx :rf.nav/push-url])))))))

;; ===========================================================================
;; 2. Cross-namespace same-(kind, id) collision FAILS LOUD — no clobber, no
;;    last-write-wins on the default path (the central rule).
;; ===========================================================================

(deftest default-projection-cross-namespace-collision-fails-loud
  (testing "two namespaces registering the same (kind, id) with different impls,
            both in the source store, with NO explicit image to disambiguate →
            :rf.error/image-duplicate-id (the default image does NOT guess and
            does NOT let load order win)"
    (let [pool [(reg-desc "examples.todo.boot"    :event :boot/init ::todo-boot)
                (reg-desc "examples.counter.boot" :event :boot/init ::counter-boot)]]
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble-default pool)))))))

(deftest default-projection-collision-order-independent
  (testing "the default-projection duplicate-id error fires regardless of pool
            order — there is no last-write that 'wins' on the default path"
    (let [a (reg-desc "examples.todo.boot"    :event :boot/init ::a)
          b (reg-desc "examples.counter.boot" :event :boot/init ::b)]
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble-default [a b]))))
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble-default [b a])))))))

(deftest default-projection-same-namespace-replacement-is-not-a-collision
  (testing "the same (kind, id) in the SAME namespace is the hot-reload
            replacement path — the source store keeps ONE slot, so the default
            projection sees ONE descriptor and seals cleanly (no spurious
            collision)"
    ;; A genuine same-ns replacement: the store keeps one slot per
    ;; [kind id provenance-ns], so two records for the same ns leave ONE.
    (let [store-before @ss/kind->id->ns->descriptor]
      (try
        (reset! ss/kind->id->ns->descriptor {})
        (record! "counter.core" :event :counter/inc ::v1)
        (record! "counter.core" :event :counter/inc ::v2) ;; replaces ::v1
        (let [gen (asm/assemble-default
                    (into [] (mapcat ss/all-descriptors) (ss/kinds-present)))]
          (is (= ::v2 (:handler-fn (asm/resolve-descriptor gen :event :counter/inc)))
              "the same-namespace re-register replaced the slot; one descriptor seals"))
        (finally (reset! ss/kind->id->ns->descriptor store-before))))))

;; ===========================================================================
;; 3. Single-namespace default projects cleanly
;; ===========================================================================

(deftest single-namespace-default-projects-cleanly
  (testing "the ordinary single-surface case: one namespace's globally-unique
            registrations project into a clean default generation"
    (let [pool [(reg-desc "app.core" :event :counter/inc   ::inc)
                (reg-desc "app.core" :sub   :counter/value ::value)
                (reg-desc "app.core" :view  :counter/view  ::view)]
          gen  (asm/assemble-default pool)]
      (is (= ::inc   (:handler-fn (asm/resolve-descriptor gen :event :counter/inc))))
      (is (= ::value (:handler-fn (asm/resolve-descriptor gen :sub :counter/value))))
      (is (= ::view  (:handler-fn (asm/resolve-descriptor gen :view :counter/view))))
      (is (= #{:event :sub :view} (asm/generation-kinds gen))))))

;; ===========================================================================
;; 4. `assemble` with NO / empty :images routes to the default projection
;; ===========================================================================

(deftest assemble-empty-images-is-the-default-projection
  (testing "`assemble` with an empty :images vector projects the DEFAULT image
            (the implicit whole-store selector) — the no-explicit-image frame
            path produces the same generation as `assemble-default`"
    (let [pool [(reg-desc "app.core" :event :counter/inc ::inc)]
          via-empty   (asm/assemble [] pool)
          via-default (asm/assemble-default pool)]
      (is (contains? (:rf.gen/resolver via-empty) [:event :counter/inc]))
      (is (= via-default via-empty)
          "the empty-:images path equals the explicit default projection")
      (is (identical? via-default via-empty)
          "and shares the one cached default generation object"))))

(deftest assemble-empty-images-collision-fails-loud
  (testing "the empty-:images default path fails loud on a cross-namespace
            collision exactly as `assemble-default` does"
    (let [pool [(reg-desc "todo.boot"    :event :boot/init ::a)
                (reg-desc "counter.boot" :event :boot/init ::b)]]
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble [] pool)))))))

;; ===========================================================================
;; 5. The default generation is CACHED + invalidates on a source-store change
;;    (the .7 cache, keyed on the source-store generation). Uses the LIVE store
;;    so the store-generation invalidation fires for real; snapshot/restore in
;;    the body keeps the live store clean.
;; ===========================================================================

(deftest default-generation-is-cached-and-invalidates-on-store-change
  (testing "the LIVE-store default projection is cached (the SAME sealed object
            on a repeat) and INVALIDATES the instant a `reg-*` bumps the
            source-store generation (EP-0023 §Image — the default generation is
            cached keyed on the source-store generation)"
    (let [store-before @ss/kind->id->ns->descriptor]
      (try
        ;; Start from a known clean live store for a deterministic generation.
        (reset! ss/kind->id->ns->descriptor {})
        (asm/clear-generation-cache!)
        (record! "shop.cart" :event :cart/add ::add)
        (let [gen1 (asm/assemble-default)
              gen1b (asm/assemble-default)]
          (testing "a repeat assembly over the UNCHANGED live store reuses the
                    SAME sealed object (the cache HIT)"
            (is (identical? gen1 gen1b))
            (is (= 1 (asm/cache-size))))
          (is (not (contains? (:rf.gen/resolver gen1) [:sub :cart/items])))
          ;; A new registration bumps the source-store generation.
          (record! "shop.cart" :sub :cart/items ::items)
          (let [gen2 (asm/assemble-default)]
            (testing "the store changed → a re-seal, NOT the stale cached object"
              (is (not (identical? gen1 gen2)))
              (is (contains? (:rf.gen/resolver gen2) [:sub :cart/items])
                  "the re-sealed default generation reflects the new registration")
              (is (= 2 (asm/cache-size))
                  "both the pre- and post-change default generations are cached"))))
        (finally
          (reset! ss/kind->id->ns->descriptor store-before)
          (asm/clear-generation-cache!))))))

(deftest default-generation-invalidates-on-standard-change
  (testing "registering a NEW framework standard bumps the standard generation,
            so a re-assembly of the default image over the same store is a MISS
            — the standard set is part of the default generation too"
    (let [store-before @ss/kind->id->ns->descriptor]
      (try
        (reset! ss/kind->id->ns->descriptor {})
        (asm/clear-generation-cache!)
        (record! "app.core" :event :app/boot ::boot)
        (let [gen1 (asm/assemble-default)]
          (is (not (contains? (:rf.gen/resolver gen1) [:fx :rf.nav/push-url])))
          (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
          (let [gen2 (asm/assemble-default)]
            (is (not (identical? gen1 gen2))
                "the standard set changed → a re-seal of the default generation")
            (is (contains? (:rf.gen/resolver gen2) [:fx :rf.nav/push-url]))))
        (finally
          (reset! ss/kind->id->ns->descriptor store-before)
          (asm/clear-generation-cache!))))))

;; ===========================================================================
;; 6. `default-image?` predicate + the default-image marker value
;; ===========================================================================

(deftest default-image-marker-and-predicate
  (testing "`default-image` is the marker value; `default-image?` recognizes it
            and rejects an ordinary explicit image value"
    (is (asm/default-image? asm/default-image))
    (is (true? (:rf.image/default? asm/default-image)))
    (is (not (asm/default-image? {:rf.image/include-ns ["a.b"]})))
    (is (not (asm/default-image? {})))))

;; ===========================================================================
;; 7. A PROVENANCED app descriptor colliding with a framework STANDARD FAILS
;;    LOUD on the DEFAULT path too — symmetric with the explicit path
;;    (rf2-x76af2.29). The default-image standard-shadow filter drops ONLY the
;;    framework's OWN no-provenance registrar shadow; a provenanced app
;;    descriptor must survive selection so it reaches check-standard-collision!.
;; ===========================================================================

(deftest default-projection-provenanced-app-colliding-with-standard-fails-loud
  (testing "a PROVENANCED app descriptor in the default pool whose (kind, id)
            collides with a registered framework standard FAILS LOUD on the
            DEFAULT path with :rf.error/image-standard-replacement-forbidden —
            the standard is protected and the app registration is NOT silently
            dropped (rf2-x76af2.29)"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (let [pool [(reg-desc "shop.nav"  :fx    :rf.nav/push-url ::app-nav)
                (reg-desc "shop.cart" :event :cart/add        ::cart-add)]]
      (is (= :rf.error/image-standard-replacement-forbidden
             (assembly-error-id #(asm/assemble-default pool)))
          "the default path throws the SAME error the explicit path throws")
      (is (= :rf.error/image-standard-replacement-forbidden
             (assembly-error-id #(asm/assemble [] pool)))
          "the empty-:images default route throws it too"))))

(deftest default-and-explicit-paths-fail-loud-symmetrically-on-standard-collision
  (testing "the SAME misconfiguration — a provenanced app descriptor colliding
            with a standard — yields the SAME
            :rf.error/image-standard-replacement-forbidden on BOTH the default
            and the explicit path. Before rf2-x76af2.29 the default path
            silently dropped the app descriptor and resolved to the standard
            (the documented fail-loud asymmetry)"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (let [pool     [(reg-desc "shop.nav" :fx :rf.nav/push-url ::app-nav)]
          explicit (image/image {:id :shop/nav :select-ns {:include ["shop.nav"]}})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (assembly-error-id #(asm/assemble-default pool))))
      (is (= :rf.error/image-standard-replacement-forbidden
             (assembly-error-id #(asm/assemble [explicit] pool))))
      (is (= (assembly-error-id #(asm/assemble-default pool))
             (assembly-error-id #(asm/assemble [explicit] pool)))
          "same error id on both paths — the default path is no longer the odd one out"))))

(deftest default-projection-drops-framework-own-no-provenance-standard-shadow
  (testing "the framework's OWN no-provenance registrar shadow of a standard
            (nil :rf.provenance/ns) is STILL filtered out of the default
            selection — it is the standard's own copy, unioned in separately, so
            it must NOT reach check-standard-collision! and must NOT throw. The
            standard still resolves and ordinary app descriptors still project
            (rf2-x76af2.29 NARROWED the filter, it did not remove it)"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (let [own-shadow {:rf.provenance/ns nil :kind :fx :id :rf.nav/push-url
                      :handler-fn ::std-nav}
          pool       [own-shadow
                      (reg-desc "shop.cart" :event :cart/add ::cart-add)]
          gen        (asm/assemble-default pool)]
      (is (contains? (:rf.gen/resolver gen) [:fx :rf.nav/push-url])
          "the standard is still present (unioned in)")
      (is (= ::std-nav (:handler-fn (asm/resolve-descriptor gen :fx :rf.nav/push-url)))
          "resolves to the standard's own copy; the no-provenance shadow was dropped")
      (is (contains? (:rf.gen/resolver gen) [:event :cart/add])
          "ordinary app descriptors still project into the default generation"))))

;; ===========================================================================
;; 5. Framework REPLACEABLE DEFAULTS (rf2-0r6q4) — the framework's own
;;    no-provenance seeding of an id the APPLICATION is invited to register
;; ===========================================================================
;;
;; A framework DEFAULT is the mirror image of a framework STANDARD. A standard
;; encodes an execution invariant and is PROTECTED — an app must not shadow it.
;; A default (`:rf.route/entry-denied`, Spec 012 §Entry is terminal) is the
;; framework's stand-in for a decision the app is invited to make, so an app
;; registration of the same id is the DOCUMENTED override. Before rf2-0r6q4 the
;; documented recipe was the broken path: the default image selected the
;; framework's own copy alongside the app's and refused to let selection order
;; decide (`:rf.error/image-duplicate-id`).

(defn- fw-default-desc
  "The framework's OWN copy of a replaceable default — carries the reserved
  `:rf/framework-default?` marker and NO `:rf.provenance/ns` (the framework
  seeds through its internal registration path, which captures no provenance)."
  [kind id impl]
  {:rf.provenance/ns      nil
   :kind                  kind
   :id                    id
   :handler-fn            impl
   :rf/framework-default? true})

(deftest framework-default-alone-resolves-normally
  (testing "with NO application registration the framework's own default is the
            only descriptor for its [kind id] and projects unchanged — denial
            stays safe for an app that registers nothing"
    (let [pool [(fw-default-desc :event :rf.route/entry-denied ::fw-noop)
                (reg-desc "shop.cart" :event :cart/add ::cart-add)]
          gen  (asm/assemble-default pool)]
      (is (= ::fw-noop
             (:handler-fn (asm/resolve-descriptor gen :event :rf.route/entry-denied)))
          "the framework default resolves")
      (is (contains? (:rf.gen/resolver gen) [:event :cart/add])))))

(deftest application-registration-supersedes-the-framework-default
  (testing "a PROVENANCED application registration of a framework-default id
            assembles cleanly and WINS — the framework's own no-provenance copy
            is not projected into the app layer once the app supplied its own.
            This is the rf2-0r6q4 fix: before it, this threw
            :rf.error/image-duplicate-id"
    (let [pool [(fw-default-desc :event :rf.route/entry-denied ::fw-noop)
                (reg-desc "shop.auth" :event :rf.route/entry-denied ::app-denial)]
          gen  (asm/assemble-default pool)]
      (is (= ::app-denial
             (:handler-fn (asm/resolve-descriptor gen :event :rf.route/entry-denied)))
          "the application's handler is what the frame runs"))))

(deftest two-application-registrations-of-a-default-id-still-collide
  (testing "the seam is NOT a winner rule. Two DISTINCT application namespaces
            registering the same framework-default id remain ambiguous — image
            assembly still refuses to let selection order decide"
    (let [pool [(fw-default-desc :event :rf.route/entry-denied ::fw-noop)
                (reg-desc "shop.auth"  :event :rf.route/entry-denied ::a)
                (reg-desc "shop.admin" :event :rf.route/entry-denied ::b)]]
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble-default pool)))))))

(deftest the-framework-default-marker-is-not-forgeable-from-app-code
  (testing "the marker only identifies the FRAMEWORK's own copy: it is read
            together with nil provenance. An app descriptor stamping the
            reserved key on itself is still an ordinary provenanced app
            registration, so a genuine app-vs-app collision still fails loud"
    (let [pool [(assoc (reg-desc "shop.auth"  :event :cart/add ::a)
                       :rf/framework-default? true)
                (reg-desc "shop.admin" :event :cart/add ::b)]]
      (is (= :rf.error/image-duplicate-id
             (assembly-error-id #(asm/assemble-default pool))))
      (is (false? (asm/framework-default-descriptor?
                    (assoc (reg-desc "shop.auth" :event :cart/add ::a)
                           :rf/framework-default? true)))
          "a provenanced descriptor is never the framework's own copy"))))

(deftest superseded-framework-default-keys-is-precise
  (testing "the key set names ONLY the framework defaults an application has
            actually registered over — not every framework default, and not
            every colliding id"
    (is (= #{} (asm/superseded-framework-default-keys
                 [(reg-desc "shop.cart" :event :cart/add ::a)]))
        "no framework defaults in the pool → no keys, one pass")
    (is (= #{} (asm/superseded-framework-default-keys
                 [(fw-default-desc :event :rf.route/entry-denied ::fw)]))
        "an unsuperseded framework default is NOT in the set")
    (is (= #{[:event :rf.route/entry-denied]}
           (asm/superseded-framework-default-keys
             [(fw-default-desc :event :rf.route/entry-denied ::fw)
              (reg-desc "shop.auth" :event :rf.route/entry-denied ::app)
              (reg-desc "shop.cart" :event :cart/add ::a)]))
        "only the superseded default's [kind id]")))

(deftest an-explicit-image-is-unaffected-by-the-default-seam
  (testing "an explicit :select-ns image selects by provenance namespace, so the
            framework's own no-provenance default was never selectable there.
            The app's registration is simply the image's descriptor"
    (let [pool     [(fw-default-desc :event :rf.route/entry-denied ::fw-noop)
                    (reg-desc "shop.auth" :event :rf.route/entry-denied ::app-denial)]
          explicit (image/image {:id :shop/auth :select-ns {:include ["shop.auth"]}})
          gen      (asm/assemble [explicit] pool)]
      (is (= ::app-denial
             (:handler-fn (asm/resolve-descriptor gen :event :rf.route/entry-denied)))))))
