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
