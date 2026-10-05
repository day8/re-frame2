(ns re-frame.ep0023-conformance-cljs-test
  "EP-0023 (Images And Frame-Loaded Instruction Sets) — the END-TO-END CONTRACT
  CONFORMANCE suite. It asserts the FULL public contract end-to-end, exercising
  the public surfaces (`rf/image` / `image-assembly/assemble` /
  `live-frame/make-frame` / re-`make-frame` reload / `reproject-live-frames!` /
  the migration diagnostics) the way a consumer reaches them, NOT each slice's
  internals.

  > image -> frame -> event stream

  The per-slice unit suites (`image-cljs-test`, `image-assembly-cljs-test`,
  `image-assembly-cache-cljs-test`, `image-assembly-default-cljs-test`,
  `live-frame-cljs-test`, `live-frame-reload-cljs-test`,
  `frame-resolution-cljs-test`, `ep0026-select-ns-cljs-test`, …) prove each
  slice exhaustively, and a contract they already drive through the public
  path is pinned there alone. This suite holds the end-to-end cases no slice
  suite reaches, each citing the EP clause it proves. The sections, in EP
  order, with the suite that pins whatever a section does not:

    1. Image construction + selection (§Image, §Namespace-Selected Images,
       §Image Fragments) — the inert normalized image value; zero-match
       fail-loud. The `*` / `**` glob grammar and inline `:registrations`
       lowering: `image-cljs-test`.
    2. Sealed assembly + validation (§Image Validation) — the reference check.
       The sealed generation shape, duplicate-id and unsupported-kind:
       `image-assembly-cljs-test`.
    3. Layered resolution (EP-0026 §Layered Resolution / §Framework Standard
       Registrations) — image-order layering (later image wins), within-image
       collisions and standard-shadow-forbidden: `ep0026-select-ns-cljs-test`
       and `image-assembly-cljs-test`. There is no declared `:replace` /
       `:replace-standard` winner model.
    4. Default-image projection (§Default Image Semantics) — no/empty `:images`
       projects the whole store + standards. The cross-namespace collision:
       `image-assembly-default-cljs-test`.
    5. make-frame + frame-derived resolution (§Frame, §Frame-derived live
       registration resolution, §Public API) — `:images` attaches the resolved
       generation. Resolution through the TARGET frame's generation,
       absence-is-default and the ALL-OR-NOTHING scope:
       `frame-resolution-cljs-test`; idempotent re-construction:
       `live-frame-cljs-test`.
    6. Generation cache + invalidation (§Image — \"MUST cache resolved
       generations\") — `image-assembly-cache-cljs-test`,
       `image-assembly-default-cljs-test` and `source-store-cljs-test`.
    7. Hot reload (§Hot Reload) — re-`make-frame` / `reproject-live-frames!`
       re-resolve every live frame; no frame left stale. Reload leaving a
       sibling frame untouched: `live-frame-reload-cljs-test`.
    8. The one frame constructor (EP-0024 §One constructor) — `rf/make-frame`
       is the SINGLE public constructor, returning the frame VALUE (not a bare
       id keyword), with `:initial-events` setup events running AFTER the
       resolved generation and seeded app-db are installed on the record.
    9. Framework-standard registry (§Image — \"+ framework standard
       registrations\") — the boot-seeded `:rf.interceptor/path` standard rides
       into a generation, resolves under it, and no app image can shadow it.
   10. Inline events deliver `:rf.cofx/requires` (§Image Fragments) — the
       inline path lowers to the same runtime descriptor shape as `reg-event`.

  Every fail-loud assertion branches on the `:rf.error/id` DISCRIMINATOR, never
  the message bytes (Spec 009 §The thrown-error shape rule 3).

  ## Fixture posture

  Cases that resolve against an EXPLICIT descriptor pool (the `assemble` /
  `make-frame` 2-arity — the same decoupling idiom the sibling slice suites use)
  need no live source-store wiring; they only reset the DERIVED process state
  (the standard registry + the generation cache + the live-frame registry). The
  LIVE-store reprojection case drives the REAL `reg-*` → source-store →
  assemble cascade, so it SNAPSHOT/RESTOREs the source-store atom (NO
  `rf.registrar/clear-all!` / `clear-kind!` in the fixture, which would destroy
  framework-shipped ns-load registrations a sibling test ns depends on). The
  registrar baseline is snapshot/restored via `make-reset-runtime-fixture`.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.source-store   :as rf.source-store]
            [re-frame.registrar      :as rf.registrar]
            [re-frame.live-frame     :as rf.live-frame]
            [re-frame.frame          :as rf.frame]
            [re-frame.interop        :as rf.interop]
            [re-frame.core           :as rf]
            [re-frame.std-interceptors    :as rf.std-interceptors]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support   :as rf.test-support]))

;; ===========================================================================
;; Fixtures + helpers
;; ===========================================================================
;;
;; `make-reset-runtime-fixture` snapshots/restores the registrar (NOT
;; `clear-all!`). On top of it we reset the EP-0023 OWN process-state atoms (the
;; standard registry + its generation, the resolved-generation cache, the
;; live-frame registry) — these are EP-0023's own defonces, not framework
;; ns-load state a sibling depends on, so a direct reset is safe and gives every
;; case a known baseline. The plain-atom adapter is installed so the make-frame
;; cases that need a live substrate work; the assembly/selection sections never
;; render.

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    (rf.image-assembly/clear-standards!)
    (rf.image-assembly/clear-generation-cache!)
    (t)
    (rf.image-assembly/clear-standards!)
    (rf.image-assembly/clear-generation-cache!)))

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

(deftest s1-zero-match-selection-fails-loud
  (testing "EP-0023 §Namespace-Selected Images: a zero-match :include-ns pattern
            fails loud (:rf.error/image-zero-match) naming the image, the
            pattern, and the loaded provenance namespaces considered"
    (let [pool [(reg-desc "shop.cart" :event :cart/add ::add)]
          data (ex-data-of
                 #(rf.image/select-descriptors
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

(deftest s2-missing-reference-fails-loud
  (testing "EP-0023 §Image Validation: an event whose :interceptors chain names
            an application interceptor not selected into the image fails
            :rf.error/image-missing-reference"
    (let [img  (rf/image {:select-ns {:include ["app.core"]}})
          ev   (assoc (reg-desc "app.core" :event :counter/inc ::inc)
                      :interceptors [:my.audit/guard])
          pool [ev]]
      (is (= :rf.error/image-missing-reference
             (err-id #(rf.image-assembly/assemble [img] pool))))
      (testing "selecting the interceptor too resolves cleanly"
        (let [pool+ [ev (reg-desc "app.core" :interceptor :my.audit/guard ::guard)]]
          (is (map? (rf.image-assembly/assemble [img] pool+))))))))

;; EP-0026: images declare no host capabilities — there is no
;; :rf.image/requires, no make-frame :capabilities, no :rf.gen/requires, and no
;; frame-boundary capability check.

;; ===========================================================================
;; SECTION 3 — Layered resolution (EP-0026 §Layered Resolution / §Framework
;; Standard Registrations)
;; ===========================================================================
;;
;; EP-0026 resolves by deterministic IMAGE-ORDER layering (there is no declared
;; :replace / :replace-standard winner model): the later image in :images wins; a
;; within-image [kind id] collision is an error (an override is always a later
;; image); a framework standard is protected (a public app image must not shadow
;; one). Pinned by `ep0026-select-ns-cljs-test` and `image-assembly-cljs-test`;
;; the boot-seeded standard's protection is SECTION 9.

;; ===========================================================================
;; SECTION 4 — Default-image projection
;; (EP-0023 §Default Image Semantics)
;; ===========================================================================
;;
;; No / empty :images projects the WHOLE source store + standards. A
;; cross-namespace same-(kind, id) collision fails loud on the default path too
;; — load order never decides (`image-assembly-default-cljs-test`).

(deftest s4-default-image-projects-the-whole-store-plus-standards
  (testing "EP-0023 §Default Image Semantics: assemble with NO / empty :images
            is the DEFAULT image — the implicit selector over the WHOLE store +
            the framework standards"
    (rf.image-assembly/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (let [pool [(reg-desc "shop.cart"    :event :cart/add    ::add)
                (reg-desc "shop.auth"    :event :auth/login  ::login)
                (reg-desc "shop.catalog" :view  :catalog/grid ::grid)]
          gen  (rf.image-assembly/assemble [] pool)]
      (is (contains? (:rf.gen/resolver gen) [:event :cart/add]))
      (is (contains? (:rf.gen/resolver gen) [:event :auth/login]))
      (is (contains? (:rf.gen/resolver gen) [:view  :catalog/grid]))
      (is (contains? (:rf.gen/resolver gen) [:fx :rf.nav/push-url])
          "the framework standard is unioned in on the default path too")
      (testing "and nil :images takes the same default path"
        (is (= gen (rf.image-assembly/assemble nil pool)))))))

;; ===========================================================================
;; SECTION 5 — make-frame + frame-derived resolution
;; (EP-0023 §Frame / §Frame-derived live registration resolution / §Public API)
;; ===========================================================================
;;
;; make-frame attaches the resolved generation. Resolution through the TARGET
;; frame's generation, absence-is-default and the ALL-OR-NOTHING scope are
;; `frame-resolution-cljs-test`'s; idempotent re-construction under one :id is
;; `live-frame-cljs-test`'s.

(deftest s5-make-frame-attaches-the-resolved-generation
  (testing "EP-0023 §Frame: make-frame {:images} attaches the sealed generation
            to the returned live frame object; resolution reads from it"
    (let [pool  [(reg-desc "examples.counter" :event :counter/inc ::inc)]
          img   (rf/image {:select-ns {:include ["examples.counter"]}})
          frame (rf.live-frame/make-frame {:images [img]} pool)]
      (is (rf.live-frame/frame-object? frame))
      (is (= (rf.image-assembly/assemble [img] pool) (rf.live-frame/frame-generation frame))
          "the frame carries the SAME sealed generation assemble produces")
      (is (= ::inc (:handler-fn (rf.image-assembly/resolve-descriptor (rf.live-frame/frame-generation frame)
                                                        :event :counter/inc)))))))

;; ===========================================================================
;; SECTION 6 — Generation cache + invalidation
;; (EP-0023 §Image — \"The reference implementation MUST cache resolved
;; generations\")
;; ===========================================================================
;;
;; Cache HIT for identical image + store-generation. INVALIDATION on every
;; source-store mutation (reg-* / forget-* / clear-kind!) and on a
;; standard-registry change. Pinned by `image-assembly-cache-cljs-test`,
;; `image-assembly-default-cljs-test` and `source-store-cljs-test`.

;; ===========================================================================
;; SECTION 7 — Hot reload
;; (EP-0023 §Hot Reload)
;; ===========================================================================
;;
;; Re-`make-frame`-ing an `:id`-bearing frame with a new `:images` vector swaps
;; the frame's whole image composition while preserving frame identity
;; (re-construction is the reload verb);
;; reproject-live-frames! re-resolves every live frame against the current
;; source store.

(deftest s7-reload-images-swaps-the-generation-and-reports-the-diff
  (testing "EP-0023 §Hot Reload: re-`make-frame`-ing re-resolves the frame's
            whole :images composition into a fresh generation; generation-diff
            over the before/after generations reports the
            added/changed/removed/retained diff; it is composition-REPLACING"
    (let [v1-pool [(reg-desc "counter.v1" :event :counter/inc ::v1-inc)]
          v2-pool [(reg-desc "counter.v2" :event :counter/inc ::v2-inc)
                   (reg-desc "counter.v2" :event :counter/reset ::v2-reset)]
          frame   (rf.live-frame/make-frame {:id :counter/main
                                  :images [(rf/image {:select-ns {:include ["counter.v1"]}})]}
                                 v1-pool)
          before   (rf.live-frame/frame-generation frame)
          reloaded (rf.live-frame/make-frame {:id :counter/main
                                   :images [(rf/image {:select-ns {:include ["counter.v2"]}})]}
                                  v2-pool)
          after    (rf.live-frame/frame-generation reloaded)
          diff     (rf.live-frame/generation-diff before after)]
      (is (= ::v2-inc (:handler-fn (rf.image-assembly/resolve-descriptor after :event :counter/inc)))
          "the frame now runs the v2 image")
      (is (contains? (:added diff) [:event :counter/reset]) "the new event is :added")
      (is (contains? (:changed diff) [:event :counter/inc]) "the redefined event is :changed")
      (testing "the live-frame registry slot is updated in place — the same id
                names the same (now-reloaded) context"
        (is (= ::v2-inc (:handler-fn (rf.image-assembly/resolve-descriptor
                                       (rf.live-frame/frame-generation (rf.live-frame/live-frame :counter/main))
                                       :event :counter/inc))))))))

(deftest s7-reproject-live-frames-re-resolves-explicit-image-frames
  (testing "EP-0023 §Default Image Semantics / §Hot Reload: a reg-* re-eval in a
            namespace an explicit :include-ns image selects reprojects + swaps
            THAT frame's generation — explicit-image frames, not only
            default-image frames; a frame whose resolution is unchanged is left
            stale-free (untouched)"
    ;; DETERMINISM: the auto-reprojection wiring
    ;; (`live-frame/reproject-on-registration-change!`, installed once as a
    ;; process-`defonce` `rf.registrar/add-registration-hook!`) fires on EVERY
    ;; `register!` — the re-evals below (frame seating does not route through
    ;; `register!`).
    ;; Once a live image-loaded frame exists, that hook MARKS the shared
    ;; process-wide `pending-reprojection?` flag dirty and schedules a REAL
    ;; deferred `rf.interop/next-tick` flush (on the JVM: an async single-thread
    ;; executor). That deferred flush RACES the explicit synchronous
    ;; `reproject-live-frames!` this case asserts on: were a scheduled tick (this
    ;; case's own, armed by the second `make-frame` while the first frame is
    ;; already live, or one a prior case left in flight) to fire in the window
    ;; AFTER the `register! ::v2` and BEFORE the explicit reproject, it would
    ;; already swap the parity frame to ::v2 and DRAIN the dirty flag — so the
    ;; explicit reproject would see the frame UNCHANGED and return `{}` — an
    ;; intermittent `(not (contains? {} :docs/parity))` failure. In isolation
    ;; the idle executor happens to fire the deferred flush AFTER the case
    ;; completes, so the race only shows under load.
    ;;
    ;; So redef `rf.interop/next-tick` to a NO-OP for the whole case (body AND
    ;; teardown): no async flush ever runs, the ONLY flush is the explicit
    ;; synchronous `reproject-live-frames!` this case drives. This is the same
    ;; `next-tick`-isolation every auto-reprojection case in
    ;; `live-frame-reload-cljs-test` uses.
    (with-redefs [rf.interop/next-tick (fn [_f] nil)]
      ;; Start from a clean slate: drain any reprojection a prior case left
      ;; pending on the shared process-`defonce` flag (the hook survives across
      ;; cases by design).
      (rf.live-frame/flush-pending-reprojection!)
      (let [store-before @rf.source-store/kind->id->ns->descriptor
            reg-before   @rf.registrar/kind->id->metadata]
        (try
          (reset! rf.source-store/kind->id->ns->descriptor {})
          (reset! rf.registrar/kind->id->metadata {})
          (rf.image-assembly/clear-generation-cache!)
          (rf.registrar/register! :event :counter/inc {:handler-fn ::v1 :ns "docs.counter.parity"})
          (rf.registrar/register! :event :other/x     {:handler-fn ::ox :ns "docs.other"})
          ;; Two explicit-image frames over the LIVE store: one selects the
          ;; parity ns, one selects the other ns.
          (let [parity (rf.live-frame/make-frame {:id :docs/parity
                                       :images [(rf/image {:select-ns {:include ["docs.counter.parity"]}})]})
                other  (rf.live-frame/make-frame {:id :docs/other
                                       :images [(rf/image {:select-ns {:include ["docs.other"]}})]})
                other-gen-before (rf.live-frame/frame-generation (rf.live-frame/live-frame :docs/other))]
            (is (= ::v1 (:handler-fn (rf.image-assembly/resolve-descriptor (rf.live-frame/frame-generation parity)
                                                            :event :counter/inc))))
            ;; A re-eval of the parity ns (same ns → replaces its source slot).
            (rf.registrar/register! :event :counter/inc {:handler-fn ::v2 :ns "docs.counter.parity"})
            (let [moved (rf.live-frame/reproject-live-frames!)]
              (testing "the parity frame moved (its selected ns changed)"
                (is (contains? moved :docs/parity))
                (is (= ::v2 (:handler-fn (rf.image-assembly/resolve-descriptor
                                           (rf.live-frame/frame-generation (rf.live-frame/live-frame :docs/parity))
                                           :event :counter/inc)))
                    "no frame left stale — the parity frame runs the re-eval'd handler"))
              (testing "the other frame did NOT move (its ns was unchanged)"
                (is (not (contains? moved :docs/other)))
                (is (identical? other-gen-before
                                (rf.live-frame/frame-generation (rf.live-frame/live-frame :docs/other)))
                    "an unchanged frame is left untouched — no spurious swap"))))
          (finally
            ;; Drain any residual pending reprojection the case's register!s
            ;; armed, then restore the store/registrar — all still under the
            ;; next-tick no-op so no stray real tick is left scheduled to fire
            ;; mid-next-case (the same race, relocated to teardown).
            (rf.live-frame/flush-pending-reprojection!)
            (reset! rf.source-store/kind->id->ns->descriptor store-before)
            (reset! rf.registrar/kind->id->metadata reg-before)
            (rf.image-assembly/clear-generation-cache!)))))))

;; Re-`make-frame`-ing an unknown `:id` simply CREATES a fresh frame under that
;; id — reload is re-construction, so there is no separate "unknown target"
;; failure mode to pin.

;; ===========================================================================
;; SECTION 8 — The one frame constructor (EP-0024 §One constructor)
;; ===========================================================================
;;
;; rf/make-frame is the EP-0023 object constructor — the facade exports exactly
;; one make-frame, which returns the frame VALUE.

(deftest s8-make-frame-is-the-one-constructor
  (testing "EP-0024 §One constructor: rf/make-frame is the ONE
            public constructor — it returns the frame VALUE, not a gensym keyword
            id, and every public surface accepts the value directly (no facade
            accessor needed to unwrap it). Built
            against an explicit descriptor pool (the 2-arity) so the pin does not
            project the live store."
    (let [pool    [(reg-desc "examples.counter" :event :counter/inc ::inc)]
          img     (rf/image {:select-ns {:include ["examples.counter"]}})
          created (rf/make-frame {:id :counter/one :images [img]} pool)]
      (is (rf.live-frame/frame-object? created)
          "rf/make-frame returns the frame VALUE")
      (is (not (keyword? created))
          "it is NOT a bare keyword id (the value is the lifecycle token)")
      (is (= :counter/one (rf.frame/frame-value->id created))
          "the internal normalization primitive reads the id from the value")
      (rf/destroy-frame! created))
    (testing "EP-0024: a record-config key is HONOURED in the same call, not
              rejected"
      (let [img (rf/image {:select-ns {:include ["examples.counter"]}})
            f   (rf/make-frame {:id :counter/cfg :images [img]
                                :doc "configured" :preset :test}
                               [(reg-desc "examples.counter" :event :counter/inc ::inc)])]
        (is (rf.live-frame/frame-object? f) "the record-config keys are accepted, no throw")
        (is (= "configured" (:doc (rf/frame-meta :counter/cfg)))
            "the :doc record-config key landed on the frame's config")
        (rf/destroy-frame! f)))))

(deftest s8-initial-events-seed-installed-before-later-setup-events
  (testing "EP-0027 §Construction ordering: when
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
    ;; this pins the seed-before-setup ordering without
    ;; depending on default-image whole-store projection.
    (rf/reg-event :ep0024.oc/init
      (fn [{:keys [db]} _] {:db (assoc db :saw-seed (:seed db) :booted? true)}))
    (let [f   (rf/make-frame {:id      :ep0024/oc-frame
                              :initial-events [[:rf/set-db {:seed 42}]
                                               [:ep0024.oc/init]]})
          db  (rf/app-db-value :ep0024/oc-frame)]
      (is (rf.live-frame/frame-object? f))
      (is (= 42 (:seed db))   "the :rf/set-db seed survives the later setup event (not clobbered)")
      (is (= 42 (:saw-seed db)) "the setup event OBSERVED the seeded app-db (seed installed first)")
      (is (true? (:booted? db))
          "the setup event resolved and ran")
      (rf/destroy-frame! f))))

;; ===========================================================================
;; SECTION 9 — Framework-standard registry populated
;; (EP-0023 §Image — "+ framework standard registrations")
;; ===========================================================================
;;
;; The framework-standard interceptor (`:rf.interceptor/path`) is contributed
;; into the EP-0023 framework-standard registry at boot, not ONLY into the
;; regular registrar. Without that the standard registry would be EMPTY, so an
;; image-loaded frame whose event references a standard interceptor BY
;; REFERENCE under a bound `*generation*` could not resolve it
;; (generation-routed `lookup` reads ONLY the generation's resolver, no
;; registrar fallback), and the framework-standard protection / invariant-
;; coupled machinery would be dead code (no standard would ever sit in a
;; generation to protect).
;;
;; The fixture clears the standard registry per case (it is EP-0023's own
;; process state), so these cases RE-SEED via the boot fn `register-standard-
;; interceptors!` — exactly what `re-frame.core/init!` does — then assert the
;; standard rides into a generation and resolves under it.

(defn- with-standard-interceptors-seeded
  "Re-seed the framework-standard interceptors (the boot path
  `register-standard-interceptors!` runs at ns-load + from `init!`) after the
  fixture's `clear-standards!`, run `thunk`, returning its value."
  [thunk]
  (rf.std-interceptors/register-standard-interceptors!)
  (thunk))

(deftest s9-standard-interceptor-is-in-the-framework-standard-registry
  (testing "EP-0023 §Image: register-standard-interceptors! contributes
            :rf.interceptor/path into the framework-standard registry, marked
            invariant-coupled (non-replaceable) — NOT only into the regular
            registrar"
    (with-standard-interceptors-seeded
      (fn []
        (let [by-kid (into {} (map (juxt (juxt :kind :id) identity))
                           (rf.image-assembly/standard-descriptors))
              std-desc (get by-kid [:interceptor :rf.interceptor/path])]
          (is (some? std-desc)
              "the standard registry is NOT empty — it carries :rf.interceptor/path")
          (is (true? (:standard std-desc)) "stamped :standard true")
          (is (seq (:rf.standard/requires-conformance std-desc))
              "invariant-coupled — a non-empty :rf.standard/requires-conformance")
          (is (false? (rf.image-assembly/standard-replaceable? std-desc))
              "an invariant-coupled standard is NOT replaceable (the rule-4 commit no-op lock)")
          (is (contains? std-desc :rf/interceptor-descriptor)
              "carries the SAME interceptor descriptor the regular registrar stores"))))))

(deftest s9-standard-interceptor-ref-resolves-under-a-generation
  (testing "EP-0023 §Frame-derived live registration resolution: an image-loaded
            frame whose event references [:rf.interceptor/path …] resolves the
            standard UNDER the frame's generation — no
            :rf.error/unregistered-interceptor. A generation lacking the
            standard would make resolution under *generation* throw."
    (with-standard-interceptors-seeded
      (fn []
        (let [pool  [(reg-desc "shop.cart" :event :cart/add ::cart-add)]
              img   (rf/image {:select-ns {:include ["shop.cart"]}})
              frame (rf.live-frame/make-frame {:images [img]} pool)
              gen   (rf.live-frame/frame-generation frame)]
          (testing "the standard rides into the resolved generation"
            (is (contains? (:rf.gen/resolver gen) [:interceptor :rf.interceptor/path])
                "the framework standard is unioned into the frame's generation"))
          (testing "the standard interceptor REF resolves under the frame's generation"
            (rf.live-frame/call-with-frame-resolution frame
              (fn []
                (is (some? rf.registrar/*generation*) "a generation is bound")
                ;; Generation-routed lookup of a standard ref would throw
                ;; :rf.error/unregistered-interceptor were the standard missing
                ;; from the generation; it resolves the factory-built path.
                (let [resolved (rf.interceptor-registry/resolve-ref [:rf.interceptor/path [:cart :items]])]
                  (is (= :rf.interceptor/path (:id resolved))
                      "the standard path interceptor is built from the generation's descriptor")
                  (is (fn? (:before resolved)) "an executable path interceptor")))))
          (testing "a bare missing ref under a generation DOES throw — proving
                    the resolution path is genuinely generation-routed"
            (rf.live-frame/call-with-frame-resolution frame
              (fn []
                (is (= :rf.error/unregistered-interceptor
                       (err-id #(rf.interceptor-registry/resolve-ref :app/never-registered))))))))))))

(deftest s9-public-app-image-cannot-shadow-a-standard
  (testing "EP-0026 §Framework Standard Registrations: there is NO public
            app-facing standard-replacement opt-in — a public app image colliding
            with a framework standard FAILS LOUD regardless of the standard's
            internal replaceable? flag (the no-shadowing rule binds public app
            images; the framework keeps its own internal define/revise path)"
    (rf.image-assembly/register-standard! :fx :rf.nav/push-url
                            {:handler-fn ::std-nav :rf.standard/replaceable? true})
    (let [pool [(reg-desc "product.story" :fx :rf.nav/push-url ::app-nav)]
          img  (rf/image {:id :i :select-ns {:include ["product.story"]}})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (err-id #(rf.image-assembly/assemble [img] pool)))
          "even a replaceable standard is not shadowable by a public app image"))))

;; ===========================================================================
;; SECTION 10 — Inline (image-loaded) events deliver :rf.cofx/requires
;; (EP-0023 §Image Fragments — "Both paths should lower to the same runtime
;;  descriptor shape"; Spec 002 §Satisfaction; EP-0017 §5 declared-only
;;  delivery)
;; ===========================================================================
;;
;; The normal `reg-event` path parses `:rf.cofx/requires`
;; into `:rf.cofx/requires-parsed` on the registrar entry, and the satisfaction
;; step (`router/assemble-initial-ctx`) reads that TOP-LEVEL slot to deliver the
;; declared facts. The EP-0023 inline-image path lowers a `:reg-event` descriptor
;; through `events/lower-inline-event`, which parses the inline `:metadata`'s
;; `:rf.cofx/requires` into the same slot; a lowering that emitted ONLY
;; `{:handler-fn :interceptors}` would run an image-loaded event declaring
;; `:rf.cofx/requires` with the declared facts MISSING (a fail-open silent
;; drop). EP-0017 §5 requires uniform
;; declared-only delivery for EVERY event regardless of registration path.
;;
;; The cofx supplier is ALSO declared inline (in the same image's
;; `:registrations`) so it rides into the frame's generation — EP-0023
;; §Frame-derived resolution is ALL-OR-NOTHING (a bound generation resolves
;; ONLY its own descriptors, no registrar fallback; proven by
;; `frame-resolution-cljs-test/event-sub-fx-cofx-view-all-derive-from-the-frame-generation`),
;; so a framework-registrar-only cofx
;; would not be resolvable under the frame's generation. The whole event +
;; cofx pair lives in the one image, exactly as a real image-loaded feature
;; would ship them.

(deftest s10-inline-image-event-receives-declared-cofx-requires
  (testing "EP-0023 §Image Fragments: an INLINE (image-loaded) event declaring
            `:rf.cofx/requires` RECEIVES the declared coeffect when dispatched —
            the inline path lowers to the SAME runtime descriptor shape (with
            `:rf.cofx/requires-parsed`) the `reg-event` path installs, so
            declared-only delivery is uniform across both registration paths"
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
          frame (rf.live-frame/make-frame {:id :inline/cofx-frame :images [img]} [])]
      (rf/dispatch-sync [:inline.cofx/touch] {:frame :inline/cofx-frame})
      (is (true? (:ran? (rf/app-db-value :inline/cofx-frame)))
          "the inline image-loaded handler ran")
      (is (= "en-AU" @seen)
          "the DECLARED :inline.cofx/locale coeffect was delivered FLAT to the
           inline handler (lower-inline-event emits :rf.cofx/requires-parsed)")
      (rf/destroy-frame! frame))))
