(ns re-frame.source-store-cljs-test
  "EP-0023 foundation slice (rf2-32siq3.2): the provenance-preserving
  registration source store.

  Pins the store behaviour the EP requires before any image-assembly slice:

    - same kind + id + DIFFERENT namespace  -> both descriptors retained;
    - same kind + id + SAME namespace        -> replacement (hot reload);
    - `:rf.provenance/ns` recorded canonically as a string, with ONE shared
      string instance per source namespace (the EP's no-per-descriptor-copy
      requirement);
    - `registrar/register!` populates the store as a side surface alongside the
      unchanged resolver map, and the removal paths keep the two consistent.

  The store deliberately makes NO assembly / selection / collision decision in
  this slice — these tests assert PRESERVATION, not resolution.

  `.cljc` so the suite runs under BOTH the bounded core JVM gate and
  `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.registrar      :as registrar]
            [re-frame.source-coords  :as source-coords]
            [re-frame.image          :as image]
            [re-frame.image-assembly :as assembly]
            [re-frame.source-store   :as ss]))

;; Each case starts from a clean process-default store + ns-string pool.
;; `registrar/clear-all!` resets the source store in lockstep (EP-0023 wiring),
;; so clearing the registrar is enough; we also clear directly for clarity.
(use-fixtures :each
  (fn [t]
    (ss/clear-all!)
    (reset! registrar/kind->id->metadata {})
    ;; The assembly-path cases (rf2-32siq3.21) read the live standard registry
    ;; + generation cache; reset both so a stale generation never leaks across
    ;; a case that mutated the store.
    (assembly/clear-standards!)
    (assembly/clear-generation-cache!)
    (t)
    (ss/clear-all!)
    (reset! registrar/kind->id->metadata {})
    (assembly/clear-standards!)
    (assembly/clear-generation-cache!)))

;; ===========================================================================
;; 1. Same id, different namespaces — BOTH descriptors retained
;; ===========================================================================

(deftest same-id-different-namespace-both-survive
  (testing "two namespaces registering the same (kind, id) both survive in the
            source store keyed by [kind id provenance-namespace] (EP-0023
            §Registration Source Store — the image-isolation path)"
    (ss/record-descriptor! :event :boot/init
                           {:ns 'examples.todo.boot    :handler-fn :todo-fn})
    (ss/record-descriptor! :event :boot/init
                           {:ns 'examples.counter.boot :handler-fn :counter-fn})
    (let [slots (ss/descriptors-for :event :boot/init)]
      (is (= 2 (count slots))
          "both provenance-distinct descriptors are retained, not clobbered")
      (is (= #{"examples.todo.boot" "examples.counter.boot"}
             (set (keys slots)))
          "the two leaves are keyed by their source namespace strings")
      (is (= :todo-fn
             (:handler-fn (ss/descriptor-for :event :boot/init
                                             'examples.todo.boot)))
          "the todo descriptor is reachable by its source slot")
      (is (= :counter-fn
             (:handler-fn (ss/descriptor-for :event :boot/init
                                             'examples.counter.boot)))
          "the counter descriptor is reachable by its source slot"))))

(deftest same-id-three-namespaces-all-survive
  (testing "the store scales past two namespaces — every provenance-distinct
            descriptor for the same (kind, id) is preserved"
    (doseq [n ["a.one" "a.two" "a.three"]]
      (ss/record-descriptor! :sub :counter/value {:ns n :handler-fn n}))
    (is (= 3 (count (ss/descriptors-for :sub :counter/value)))
        "all three same-id different-namespace subs are retained")))

;; ===========================================================================
;; 2. Same namespace — replacement (the hot-reload path)
;; ===========================================================================

(deftest same-namespace-replacement
  (testing "a re-eval of the SAME namespace replaces its own source slot — the
            ordinary hot-reload path (EP-0023 retains Spec 001's same-namespace
            replacement contract)"
    (ss/record-descriptor! :event :counter/inc
                           {:ns 'docs.counter.v2 :handler-fn :v1 :gen 1})
    (ss/record-descriptor! :event :counter/inc
                           {:ns 'docs.counter.v2 :handler-fn :v2 :gen 2})
    (let [slots (ss/descriptors-for :event :counter/inc)]
      (is (= 1 (count slots))
          "the same namespace occupies ONE source slot — no duplication")
      (is (= :v2 (:handler-fn (get slots "docs.counter.v2")))
          "the re-eval replaced the slot with the new descriptor")
      (is (= 2 (:gen (get slots "docs.counter.v2")))
          "the replacement is the LATEST registration for that namespace"))))

(deftest same-namespace-replacement-does-not-disturb-siblings
  (testing "replacing one namespace's slot leaves sibling-namespace descriptors
            for the same (kind, id) intact"
    (ss/record-descriptor! :event :boot/init {:ns 'a.boot :handler-fn :a1})
    (ss/record-descriptor! :event :boot/init {:ns 'b.boot :handler-fn :b1})
    (ss/record-descriptor! :event :boot/init {:ns 'a.boot :handler-fn :a2})
    (let [slots (ss/descriptors-for :event :boot/init)]
      (is (= 2 (count slots)) "still two namespaces present")
      (is (= :a2 (:handler-fn (get slots "a.boot"))) "a.boot replaced")
      (is (= :b1 (:handler-fn (get slots "b.boot"))) "b.boot untouched"))))

;; ===========================================================================
;; 3. :rf.provenance/ns recorded canonically (string, one instance per ns)
;; ===========================================================================

(deftest provenance-ns-recorded-as-string
  (testing "every recorded descriptor carries :rf.provenance/ns as a canonical
            STRING derived from the macro-captured :ns symbol (EP-0023 — a
            production descriptor field, equality is string equality)"
    (let [stored (ss/record-descriptor! :event :counter/inc
                                        {:ns 'docs.counter.v2 :handler-fn :f})]
      (is (= "docs.counter.v2" (:rf.provenance/ns stored))
          "provenance ns stamped as a string on the stored descriptor")
      (is (string? (:rf.provenance/ns stored))
          "it is a string, not a symbol")
      (is (= "docs.counter.v2"
             (:rf.provenance/ns (ss/descriptor-for :event :counter/inc
                                                   'docs.counter.v2)))
          "the stored slot carries the canonical provenance string"))))

(deftest provenance-ns-canonical-one-string-per-namespace
  (testing "the canonical namespace string is POOLED — every descriptor from
            the same source namespace shares ONE string instance, not an
            independently allocated copy (EP-0023 §Relationship To The Earlier
            Tag-And-Scan Rejection — one string per namespace, not per
            descriptor)"
    (let [a (ss/record-descriptor! :event :one {:ns 'shared.feature :handler-fn :a})
          b (ss/record-descriptor! :sub   :two {:ns 'shared.feature :handler-fn :b})
          c (ss/canonical-ns 'shared.feature)
          d (ss/canonical-ns "shared.feature")]
      (is (= "shared.feature"
             (:rf.provenance/ns a) (:rf.provenance/ns b) c d)
          "all spellings resolve to the same value")
      ;; identical? is the load-bearing assertion: the pool returns the SAME
      ;; instance for the same namespace regardless of how it was spelled.
      (is (identical? (:rf.provenance/ns a) (:rf.provenance/ns b))
          "descriptors from the same namespace share one string instance")
      (is (identical? (:rf.provenance/ns a) c)
          "the symbol spelling pools to the same instance")
      (is (identical? (:rf.provenance/ns a) d)
          "the string spelling pools to the same instance"))))

(deftest provenance-ns-honours-explicit-override
  (testing "an explicit :rf.provenance/ns already on the descriptor wins over
            the :ns symbol (a tool / generated-code path stamping its own
            provenance), still canonicalized to the pool"
    (let [stored (ss/record-descriptor! :event :gen/handler
                                        {:ns 'macro.captured.ns
                                         :rf.provenance/ns "tool.synthesised.ns"
                                         :handler-fn :f})]
      (is (= "tool.synthesised.ns" (:rf.provenance/ns stored))
          "explicit provenance overrides the :ns symbol")
      (is (identical? (ss/canonical-ns "tool.synthesised.ns")
                      (:rf.provenance/ns stored))
          "the explicit provenance is pooled too"))))

(deftest programmatic-no-provenance-recorded-once
  (testing "a programmatic registration with no :ns (REPL / non-macro path)
            records under the nil-provenance slot and is NOT silently dropped;
            a later programmatic re-register of the same (kind, id) replaces it"
    (let [stored (ss/record-descriptor! :event :prog/handler {:handler-fn :v1})]
      (is (nil? (:rf.provenance/ns stored))
          "no provenance key stamped when there is no source namespace")
      (is (= :v1 (:handler-fn (get (ss/descriptors-for :event :prog/handler) nil)))
          "the nil-provenance descriptor is retained, not dropped"))
    (ss/record-descriptor! :event :prog/handler {:handler-fn :v2})
    (is (= 1 (count (ss/descriptors-for :event :prog/handler)))
        "a programmatic re-register replaces the single nil-provenance slot")
    (is (= :v2 (:handler-fn (get (ss/descriptors-for :event :prog/handler) nil)))
        "the replacement is the latest programmatic registration")))

;; ===========================================================================
;; 4. registrar/register! populates the store and removal stays consistent
;; ===========================================================================

(deftest register-bang-populates-source-store
  (testing "registrar/register! writes the descriptor into the source store as
            a side surface, alongside the unchanged resolver map (EP-0023
            foundation wiring)"
    (registrar/register! :event :wired/handler
                         {:ns 'wired.ns :handler-fn :f :doc "d"})
    (is (some? (registrar/lookup :event :wired/handler))
        "the resolver map is written exactly as before")
    (let [slot (ss/descriptor-for :event :wired/handler 'wired.ns)]
      (is (some? slot) "the source store is also written")
      (is (= "wired.ns" (:rf.provenance/ns slot))
          "register! stamps canonical provenance via the source store"))))

(deftest register-bang-cross-namespace-both-survive-in-store
  (testing "two register! calls for the same (kind, id) from different
            namespaces both survive in the source store, even though the
            resolver map (last-write-wins) shows only the latter — this is the
            collision the later image-assembly slice must see (EP-0023)"
    (registrar/register! :event :boot/init {:ns 'todo.boot    :handler-fn :todo})
    (registrar/register! :event :boot/init {:ns 'counter.boot :handler-fn :counter})
    ;; Resolver map: last-write-wins (unchanged historical behaviour).
    (is (= :counter (:handler-fn (registrar/lookup :event :boot/init)))
        "the resolver map still last-write-wins (default-image path unchanged)")
    ;; Source store: BOTH retained.
    (is (= 2 (count (ss/descriptors-for :event :boot/init)))
        "the source store retains BOTH provenance-distinct descriptors")))

(deftest unregister-bang-forgets-source-slots
  (testing "registrar/unregister! drops every provenance slot for (kind, id) in
            the source store, mirroring the resolver-map id removal"
    (registrar/register! :event :x/y {:ns 'a.ns :handler-fn :a})
    (registrar/register! :event :x/y {:ns 'b.ns :handler-fn :b})
    (is (= 2 (count (ss/descriptors-for :event :x/y))) "both recorded first")
    (registrar/unregister! :event :x/y)
    (is (nil? (registrar/lookup :event :x/y)) "resolver map cleared")
    (is (= 0 (count (ss/descriptors-for :event :x/y)))
        "every provenance slot for the id is forgotten")))

(deftest clear-all-resets-source-store
  (testing "registrar/clear-all! resets the source store in lockstep with the
            resolver map"
    (registrar/register! :event :a/b {:ns 'n.s :handler-fn :f})
    (is (seq (ss/descriptors-for :event :a/b)) "recorded")
    (registrar/clear-all!)
    (is (= 0 (count (ss/descriptors-for :event :a/b)))
        "source store reset by clear-all!")
    (is (empty? (ss/kinds-present)) "no kinds remain in the store")))

;; ===========================================================================
;; 5. forget-descriptor! — targeted single-slot removal
;; ===========================================================================

(deftest forget-descriptor-removes-one-slot
  (testing "forget-descriptor! removes one (kind, id, ns) slot and leaves
            sibling namespaces intact"
    (ss/record-descriptor! :event :boot/init {:ns 'a.ns :handler-fn :a})
    (ss/record-descriptor! :event :boot/init {:ns 'b.ns :handler-fn :b})
    (ss/forget-descriptor! :event :boot/init 'a.ns)
    (let [slots (ss/descriptors-for :event :boot/init)]
      (is (= 1 (count slots)) "one slot removed")
      (is (contains? slots "b.ns") "the sibling namespace survives")
      (is (not (contains? slots "a.ns")) "the forgotten slot is gone"))))

(deftest all-descriptors-flattens-across-slots
  (testing "all-descriptors returns every retained descriptor for a kind across
            all (id, namespace) slots — the input set a later assembly slice
            selects from (no selection decision made here)"
    (ss/record-descriptor! :event :boot/init {:ns 'a.ns :handler-fn :a})
    (ss/record-descriptor! :event :boot/init {:ns 'b.ns :handler-fn :b})
    (ss/record-descriptor! :event :other     {:ns 'c.ns :handler-fn :c})
    (is (= 3 (count (ss/all-descriptors :event)))
        "all three event descriptors across two ids and three namespaces")
    (is (= #{:a :b :c} (set (map :handler-fn (ss/all-descriptors :event))))
        "every descriptor is present")))

;; ===========================================================================
;; 6. Stored descriptor carries :kind / :id (rf2-32siq3.21) — image assembly
;;    reads (:kind d) / (:id d), so a registered descriptor MUST carry them
;; ===========================================================================

(deftest stored-descriptor-carries-kind-and-id
  (testing "record-descriptor! stamps :kind and :id onto the stored descriptor
            (the store KEY, so deterministic). Image assembly reads (:kind d) /
            (:id d) via descriptor-kind+id / check-supported-kinds!, so a real
            registered descriptor selected by :include-ns must carry them
            (rf2-32siq3.21)"
    (let [stored (ss/record-descriptor! :event :counter/inc
                                        {:ns 'docs.counter.v2 :handler-fn :f})]
      (is (= :event (:kind stored)) "stored descriptor carries its :kind")
      (is (= :counter/inc (:id stored)) "stored descriptor carries its :id"))
    ;; Reachable through the query surfaces too, not just the return value.
    (let [slot (ss/descriptor-for :event :counter/inc 'docs.counter.v2)]
      (is (= :event (:kind slot)))
      (is (= :counter/inc (:id slot))))
    (is (= [:event :counter/inc]
           ((juxt :kind :id) (first (ss/all-descriptors :event))))
        "the flattened all-descriptors entry carries :kind / :id")))

(deftest registrar-recorded-descriptor-assembles-via-all-descriptors
  (testing "a descriptor recorded via registrar/register! carries :kind / :id
            and assembles through image-assembly (selecting from the live source
            store's all-descriptors) WITHOUT a kind/id error — the real
            default-image / :include-ns path the synthetic-descriptor tests
            masked (rf2-32siq3.21)"
    (registrar/register! :event :counter/inc
                         {:ns 'docs.counter.v2 :handler-fn (fn [_ _] {})})
    (let [slot (ss/descriptor-for :event :counter/inc 'docs.counter.v2)]
      (is (= :event (:kind slot)) "register!-recorded descriptor carries :kind")
      (is (= :counter/inc (:id slot)) "register!-recorded descriptor carries :id"))
    ;; Assemble a :select-ns image against the live source store. Without the
    ;; :kind/:id stamp this threw :rf.error/image-unsupported-kind (nil kind).
    (let [img (image/image {:id :docs.counter/v2
                            :select-ns {:include ["docs.counter.v2"]}})
          gen (assembly/assemble [img])]
      (is (some? (assembly/resolve-descriptor gen :event :counter/inc))
          "the registered descriptor resolves in the sealed generation")
      (is (= #{:event} (assembly/generation-kinds gen))
          "the sealed generation reports :event as a present kind"))))

;; ===========================================================================
;; 7. Provenance survives production elision (rf2-32siq3.22) — derive the
;;    provenance ns from the elision-surviving *pending-coords* binding when
;;    the descriptor's :ns slot has been stripped (the production path)
;; ===========================================================================

(deftest provenance-from-pending-coords-when-ns-stripped
  (testing "in production (`:advanced` + goog.DEBUG=false) source-coords/
            merge-coords returns the user metadata UNCHANGED, so the descriptor
            reaching record-descriptor! carries NO :ns. The reg-* macro's
            *pending-coords* BINDING still carries :ns (prod-coords-form keeps
            it), so provenance is derived from the live binding — :rf.provenance/
            ns must survive optimized builds or :include-ns assembly cannot work
            (rf2-32siq3.22, EP-0023 §Namespace-Selected Images)"
    ;; Model the production descriptor shape: NO :ns slot (merge-coords
    ;; stripped it), but the macro's *pending-coords* binding is in flight.
    (binding [source-coords/*pending-coords* {:ns "docs.counter.prod"}]
      (let [stored (ss/record-descriptor! :event :counter/inc
                                          {:handler-fn :f})]
        (is (= "docs.counter.prod" (:rf.provenance/ns stored))
            "provenance derived from the surviving *pending-coords* :ns")
        (is (string? (:rf.provenance/ns stored))
            "it is a canonical string")
        (is (identical? (ss/canonical-ns "docs.counter.prod")
                        (:rf.provenance/ns stored))
            "the *pending-coords*-derived provenance is pooled too")))
    ;; The slot is keyed by that provenance namespace, so :include-ns selection
    ;; (the production image-assembly path) reaches it.
    (is (some? (ss/descriptor-for :event :counter/inc "docs.counter.prod"))
        "the descriptor is recorded under its provenance namespace slot")))

(deftest provenance-precedence-descriptor-ns-wins-over-pending
  (testing "the descriptor's own :ns slot (DEV path, present when merge-coords
            folded it in) takes precedence over the *pending-coords* fallback —
            the fallback is a production safety net, not an override"
    (binding [source-coords/*pending-coords* {:ns "from.pending"}]
      (let [stored (ss/record-descriptor! :event :counter/inc
                                          {:ns 'from.descriptor :handler-fn :f})]
        (is (= "from.descriptor" (:rf.provenance/ns stored))
            "the descriptor :ns slot wins over the *pending-coords* fallback")))))

(deftest explicit-provenance-wins-over-pending-coords
  (testing "an explicit :rf.provenance/ns on the descriptor still wins over both
            the :ns slot and the *pending-coords* fallback"
    (binding [source-coords/*pending-coords* {:ns "from.pending"}]
      (let [stored (ss/record-descriptor! :event :gen/handler
                                          {:ns 'macro.captured.ns
                                           :rf.provenance/ns "tool.synthesised.ns"
                                           :handler-fn :f})]
        (is (= "tool.synthesised.ns" (:rf.provenance/ns stored))
            "explicit provenance overrides both the :ns slot and *pending-coords*")))))

(deftest no-provenance-when-no-source-anywhere
  (testing "a truly programmatic registration — no descriptor :ns, no
            *pending-coords* binding — records under the nil-provenance slot,
            unchanged by the production-elision fallback"
    (let [stored (ss/record-descriptor! :event :prog/handler {:handler-fn :v1})]
      (is (nil? (:rf.provenance/ns stored))
          "no provenance stamped when no source namespace is available anywhere")
      (is (some? (get (ss/descriptors-for :event :prog/handler) nil))
          "recorded under the nil-provenance slot, not dropped"))))

;; ===========================================================================
;; 8. clear-kind! bumps the store generation (rf2-32siq3.34) — the
;;    resolved-image-generation cache MUST invalidate after a clear-kind!
;; ===========================================================================

(deftest clear-kind-bumps-generation
  (testing "source-store/clear-kind! bumps the active store's generation, like
            every other mutation, so a generation read after the clear differs
            (rf2-32siq3.34)"
    (ss/record-descriptor! :event :a/x {:ns 'n.s :handler-fn :f})
    (let [before (ss/store-generation)]
      (ss/clear-kind! :event)
      (is (not= before (ss/store-generation))
          "clear-kind! bumped the source-store generation")
      (is (empty? (ss/descriptors-for :event :a/x))
          "the kind's slots were dropped from the store"))))

(deftest registrar-clear-kind-invalidates-resolved-generation-cache
  (testing "registrar/clear-kind! routes through source-store/clear-kind!, which
            bumps the store generation, so a re-`assemble` after the clear is a
            cache MISS that returns a FRESH generation no longer resolving the
            cleared id. On the unfixed path (raw `dissoc kind`, no bump) the
            store had fewer descriptors but the SAME generation int, so the
            re-`assemble` HIT the resolved-image-generation cache and returned a
            stale generation that still resolved the cleared (kind, id)
            (rf2-32siq3.34, EP-0023 §Image — resolved-generation cache).

            Fixtures: this case relies ONLY on the :each fixture's snapshot/
            restore (ss/clear-all! + clear-generation-cache! at the boundaries);
            it does NOT call registrar/clear-all! between the two assembles —
            doing so would reset the generation cache and mask the very stale-hit
            path under test."
    (registrar/register! :event :counter/inc
                         {:ns 'docs.counter.v2 :handler-fn (fn [_ _] {})})
    ;; First assembly fills the resolved-image-generation cache, keyed on the
    ;; current source-store generation. Default image (empty :images) projects
    ;; the whole live store and keys on (source-store/store-generation).
    (let [gen-before (assembly/assemble [])
          size-before (assembly/cache-size)]
      (is (some? (assembly/resolve-descriptor gen-before :event :counter/inc))
          "the registered descriptor resolves in the first sealed generation")
      ;; Clear the whole :event kind via the PUBLIC registrar path (this backs
      ;; clear-handlers / resources teardown).
      (registrar/clear-kind! :event)
      (is (empty? (ss/descriptors-for :event :counter/inc))
          "the source store no longer holds the cleared descriptor")
      ;; Re-assemble the SAME (empty) image vector. With the generation bump this
      ;; is a cache MISS → a fresh generation; without it, a stale cache HIT.
      (let [gen-after (assembly/assemble [])]
        (is (not (identical? gen-before gen-after))
            "re-assemble after clear-kind! returns a FRESH, non-identical
             generation (a cache MISS) — NOT the stale cached object")
        (is (nil? (assembly/resolve-descriptor gen-after :event :counter/inc))
            "the cleared (kind, id) no longer resolves in the fresh generation —
             the cache invalidation hole is closed")
        (is (not (contains? (assembly/generation-kinds gen-after) :event))
            "no :event kind survives the clear in the fresh generation")
        (is (> (assembly/cache-size) size-before)
            "a new cache entry was sealed (the post-clear generation), not a
             reuse of the stale one")))))
