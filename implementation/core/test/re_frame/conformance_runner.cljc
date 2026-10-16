(ns re-frame.conformance-runner
  "Host-neutral conformance corpus runner — the SINGLE authority for the
  re-frame2 conformance-fixture harness.

  The JVM runner (`re-frame.conformance-test`) and the CLJS runner
  (`re-frame.conformance-corpus-cljs-test`) share this namespace rather than
  each carrying its own copy of capability claims, fixture realisation, call
  execution, matchers, result assembly, and reporting. Separate copies
  drift: a host copy that never invoked `check-epoch-records` would report
  conformance while SILENTLY IGNORING every `:epoch-records` expectation.

  This `.cljc` owns every host-neutral concern:

    - the claimed-capability / claimed-spec-version / known-skipped sets
    - fixture capability classification + spec-version gating
    - handler-body / sub / cofx / fx / route / view / machine / flow /
      classification-effect / app-schema realisation
    - the `:fixture/calls` execution (`run-call`)
    - the `:fixture/compute-subs` invocation (each query computed before
      grading)
    - the `:fixture/clock` controlled host clock (`run-clock-steps!`)
    - the matcher tables (`expect-matchers`, `call-matchers`)
    - `run-fixture` orchestration
    - the corpus body (`run-corpus`) with three-way capability
      classification, reporting, and the pass/floor/count `is` assertions
    - the production-posture predicate (`prod-posture-fixture?`): the
      fixtures whose grading reads no dev-only surface, which a host can
      run under `-Dre-frame.debug=false`

  The JVM / CLJS LEAVES are limited to genuinely host-specific operations:
  fixture loading (JVM fs `slurp`/`file-seq`; CLJS compile-time inlining),
  inter-fixture reset/isolation (JVM `clear-all!` + `(require :reload)`;
  CLJS registrar snapshot/restore), and trace-listener registry access
  (the production-DCE split — `re-frame.trace` on JVM vs
  `re-frame.trace.tooling` on CLJS). Each leaf hands the runner a HOST
  MAP:

      {:reset-runtime!             (fn [] …)   ;; full inter-fixture reset
       :register-trace-listener!   (fn [fixture-id listener-fn] …)
       :unregister-trace-listener! (fn [fixture-id] …)}

  This is a test-only namespace under `core/test`, `:require`d only by test
  namespaces, so production source and bundle graphs never see it.

  ## One matcher per expectation key

  The fixture grammar, `spec/conformance/fixture-file.edn`, declares every
  expectation a fixture may carry and the law it is compared by. This runner
  grades each one with exactly one matcher: `expect-matchers` for the
  `:fixture/expect` keys and `call-matchers` for the `:fixture/calls`
  records, and every matcher runs on every runnable fixture carrying its key,
  whatever the fixture's filename. `re-frame.conformance-test` ties both
  tables to the grammar, so a grammar key without a matcher, or a matcher
  without a grammar key, fails the suite. The one exception is
  `sibling-graded`: an expectation whose fixture claims a capability this
  runner does not, graded by the family runner that does; the same test
  proves that runner loads every fixture carrying it.

  A `:fixture/expect` key with no matcher fails the fixture naming it, on
  both hosts. An unknown top-level key is caught by the JVM grammar
  validation over the same corpus (`re-frame.conformance-grammar-test`)."
  (:require
    #?(:clj  [clojure.test :refer [is]]
       :cljs [cljs.test :refer-macros [is]])
    [clojure.string :as str]
    [re-frame.core :as rf]
    [re-frame.elision :as rf.elision]
    [re-frame.error-emit :as rf.error-emit]
    [re-frame.events :as rf.events]
    [re-frame.fx :as rf.fx]
    [re-frame.cofx :as rf.cofx]
    [re-frame.core-ssr :as rf.core-ssr]
    [re-frame.frame :as rf.frame]
    [re-frame.registrar :as rf.registrar]
    [re-frame.source-store :as rf.source-store]
    [re-frame.subs :as rf.subs]
    [re-frame.conformance :as rf.conformance]
    [re-frame.identity :as rf.identity]
    ;; `:fixture/clock` replaces the Spec 005 §Clock-abstraction
    ;; primitives (`schedule-after!` / `cancel-scheduled!`) with a recording
    ;; realisation, and collapses the router's `next-tick` drain while a
    ;; captured host callback runs.
    [re-frame.interop :as rf.interop]
    [re-frame.path :as rf.path]
    [re-frame.projection :as rf.projection]
    [re-frame.http.privacy-headers :as rf.http.privacy-headers]
    [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
    [re-frame.routing :as rf.routing]
    [re-frame.routing.match :as rf.routing.match]
    [re-frame.ssr :as rf.ssr]
    [re-frame.machines :as rf.machines]
    [re-frame.resources :as rf.resources]
    [re-frame.resources.registry :as rf.resources.registry]
    [re-frame.resources.subs :as rf.resources.subs]
    [re-frame.image :as rf.image]
    [re-frame.image-assembly :as rf.image-assembly]
    [re-frame.derivation.graph :as rf.derivation.graph]
    [re-frame.subs.tooling :as rf.subs.tooling]
    [re-frame.machines.tooling :as rf.machines.tooling]
    [re-frame.flows :as rf.flows]
    [re-frame.flows.topo :as rf.flows.topo]
    [re-frame.flows.tooling :as rf.flows.tooling]
    [re-frame.resources.tooling :as rf.resources.tooling]
    [re-frame.routing.tooling :as rf.routing.tooling]
    ;; Side-effect require: publishing the epoch late-bind hooks
    ;; (`:epoch/settle!`, `:epoch/epoch-history`, `:epoch/clear-history!`,
    ;; …) at ns-load is what makes the router commit `:rf/epoch-record`s and
    ;; `rf/epoch-history` observe them. Required HERE (not just in the JVM
    ;; leaf) so BOTH hosts record + check `:epoch-records`. On the CLJS
    ;; node-test build the epoch artefact is on the classpath (`epoch/src`);
    ;; on the JVM core test build it is likewise reachable (the JVM leaf
    ;; requires it too).
    [re-frame.epoch]))

;; ---- claimed capability set -----------------------------------------------

(def claimed-capabilities
  "What this reference implementation claims to support. Fixtures requiring
  capabilities outside this set are classified per `classify-capabilities`.
  The corpus is graded against claimed capabilities, not host — both the
  JVM and CLJS leaves claim EXACTLY this surface (that is the point of a
  single shared runner)."
  #{:core/event-handler
    :core/sub
    :core/fx
    :core/error
    ;; :core/trace + :core/frame — pattern-required surfaces per
    ;; the README's §Capability tagging list and worked-example table.
    :core/trace
    :core/frame
    ;; EP-0026 — the image-API surface: `:select-ns` selection,
    ;; image-order layering, the shadow report, the fail-loud collision /
    ;; retired-key / inline-grammar taxonomy, and the omit-`:images` default
    ;; path. Exercised by the `image-*.edn` fixtures via `:assemble-image`.
    :core/image
    :fsm/flat
    :fsm/eventless-always
    :fsm/hierarchical
    :fsm/delayed-after
    :fsm/timeout                                      ;; EP-0029 A4 — state + spawn :timeout / :on-timeout (lowers onto :after)
    :fsm/choice                                       ;; EP-0029 A5 — :type :choice transient / choice states (lowers onto :always)
    :fsm/internal-events                              ;; EP-0029 A6 — public / private :internal-events (dispatch-boundary refusal)
    :fsm/tags
    :fsm/parallel-regions
    :fsm/final-states                                 ;; :final? + :on-done + :output-key
    :fsm/history                                      ;; first-class history pseudo-states
    :fsm/registration-validation                      ;; registration-error taxonomy via :reg-machine
    :routing/match-url
    :ssr/render-to-string
    :ssr/hydration
    :ssr/response-contract
    :ssr/head-contract
    :ssr/error-projection
    :schemas/runtime
    :schemas/event-payload
    :schemas/sub-return
    ;; :schemas/cofx — the EP-0017 recordable-cofx
    ;; `:schema` path: a declared recordable value that fails its
    ;; registration's `:schema` emits `:rf.error/cofx-value-invalid` and
    ;; throws during context assembly.
    :schemas/cofx
    :routing/ranking
    ;; Path-pattern registration-validation (Mode-B :reg-route).
    :routing/pattern-validation
    :routing/fragment
    :routing/blocking
    :routing/nav-token
    ;; EP-0012 — CEDN-1 canonical-identity + `:rf/path` algebra.
    :identity/cedn1
    :actor/spawn-destroy
    :actor/declarative-spawn
    :actor/spawn-and-join
    ;; There is no :actor/timeout capability — :fsm/delayed-after covers it.
    :flow/basic
    :flow/topo
    :flow/dirty-check
    :flow/toggle
    :flow/hot-reload
    ;; Spec 009 §Flow trace events / Spec 013 §Flow tracing.
    :flow/trace
    ;; Spec 013 §Frame-scoping.
    :flow/frame-scoped
    ;; Spec 014 — :rf.http/managed.
    :rf.http/managed
    ;; Spec 015 §Data classification.
    :data-classification/classification-effects
    ;; EP-0014 — the BROAD cross-family derivation graph.
    :derivation/algebra-graph
    ;; The NARROW subs+machines subset claim.
    :derivation/algebra-graph-subs-machines
    ;; Spec 016 §Resources.
    :resources/ensure
    :resources/dedupe
    :resources/stale-suppression
    :resources/scope-fail-closed
    :resources/owner-gc
    :resources/keep-previous})

(def claimed-spec-versions
  "Fixture spec versions this implementation claims to conform against.
  Per `spec/conformance/README.md` §Versioning: a fixture whose
  `:fixture/spec-version` is NOT in this set is reported as skipped."
  #{"1.0"})

;; ---- known-skipped capabilities -------------------------------------------
;;
;; A fixture declaring a capability not in `claimed-capabilities` AND not
;; here is a typo / claim-set drift and FAILS the suite (rather than being
;; silently skipped, which would mask a bug). Adding a capability here is an
;; explicit declaration that this build
;; INTENTIONALLY does not claim it.

(def known-skipped-capabilities
  "Capabilities this build INTENTIONALLY does not claim. Fixtures whose
  capabilities fall here are reported as out-of-claim skips but do not block
  the suite. The render-tree hash is graded by the ssr artefact's own
  conformance runner (see `sibling-graded`)."
  #{:ssr/render-tree-hash})

;; ---- fixture classification -----------------------------------------------

(defn classify-capabilities
  "Partition a fixture's `:fixture/capabilities` into
  `{:claimed … :allowed … :unknown …}`. RUNNABLE iff `:unknown` and
  `:allowed` are both empty; SKIPPED (out-of-claim) iff `:unknown` empty and
  `:allowed` non-empty; FAILURE iff `:unknown` non-empty."
  [fixture]
  (let [caps (or (:fixture/capabilities fixture) #{})]
    {:claimed (into #{} (filter claimed-capabilities) caps)
     :allowed (into #{} (filter (fn [c]
                                  (and (contains? known-skipped-capabilities c)
                                       (not (contains? claimed-capabilities c))))
                                caps))
     :unknown (into #{} (remove (fn [c]
                                  (or (contains? claimed-capabilities c)
                                      (contains? known-skipped-capabilities c)))
                                caps))}))

(defn spec-version-claimed?
  "True if the fixture targets a spec version this build claims. A fixture
  without an explicit `:fixture/spec-version` is treated as unversioned and
  accepted."
  [fixture]
  (let [v (:fixture/spec-version fixture)]
    (or (nil? v) (contains? claimed-spec-versions v))))

;; ---- handler-body realisation ---------------------------------------------
;;
;; `collect-cofx-keys`, `realise-cofx-supplier`, and `normalize-event-handler`
;; are the SHARED handler-realisation primitives owned by `re-frame.conformance`
;; (core/src) — consumed here and by the per-feature artefact runners alike so
;; the cofx-key walk / supplier lift / body-shape collapse live in one place.

;; Forward declaration — realise-machine-handlers is defined below (alongside
;; the run-call :machine-transition path). The same realised
;; action/guard maps feed both the in-memory `machine-transition` callsite
;; and the registry `reg-machine` registrations.
(declare realise-machine-handlers)

(defn- claim-fixture-id!
  "Bundle co-load hygiene: before a fixture registers `(kind,
  id)`, drop every NON-nil-provenance source-store row for the id — a
  co-loaded app/testbed namespace registering the same id would otherwise
  sit beside the fixture's row and fail default-image assembly loud. The
  nil-provenance slot is deliberately KEPT: the fixture's fn-form
  registration REPLACES it in place, so a framework registration (e.g. the
  `:rf/time-ms` cofx) is never erased from the store — a plain
  `forget-id!` here would poison every LATER suite's lazily-captured
  source-store baseline with the framework row missing."
  [kind id]
  (doseq [pns (keys (get-in @rf.source-store/kind->id->ns->descriptor [kind id]))
          :when (some? pns)]
    (rf.source-store/forget-descriptor! kind id pns))
  nil)

(defn- realise-handlers [fixture]
  (let [handlers-map   (or (:fixture/handlers fixture) {})
        event-registry (get-in fixture [:fixture/registry :event] {})
        sub-registry   (get-in fixture [:fixture/registry :sub] {})
        cofx-bodies    (get handlers-map :cofx)
        cofx-registry  (get-in fixture [:fixture/registry :cofx] {})
        ;; cofx that auto-wire onto a consuming event's `:rf.cofx/requires`
        ;; declaration (EP-0017 model). Stable lex order on cofx-id so the
        ;; last-write-wins outcome is deterministic across JVM / CLJS.
        cofx-by-key
        (->> cofx-registry
             (sort-by key)
             (group-by (fn [[cofx-id _]] (keyword (namespace cofx-id))))
             (reduce-kv (fn [acc k pairs]
                          (assoc acc k (mapv first pairs)))
                        {}))]
    ;; cofx registrations — value-returning suppliers + metadata (EP-0017).
    (let [all-cofx-ids (into #{} (concat (keys cofx-bodies) (keys cofx-registry)))]
      (doseq [cofx-id all-cofx-ids]
        (let [body (get cofx-bodies cofx-id [[:noop]])
              meta (get cofx-registry cofx-id {})]
          ;; A `:provided?` cofx is a boundary-supplied fact with NO supplier
          ;; — its VALUE rides the dispatch token via `:rf.cofx`.
          ;; `reg-cofx` rejects `provided? true` + a supplier, so register
          ;; without one.
          (claim-fixture-id! :cofx cofx-id) ;; claim (see claim-fixture-id!)
          (if (:provided? meta)
            (rf.cofx/reg-cofx cofx-id meta)
            (rf.cofx/reg-cofx cofx-id meta (rf.conformance/realise-cofx-supplier body))))))
    ;; event registrations. A body that reads `[:cofx-key K]` declares the
    ;; consumed coeffect ids via `:rf.cofx/requires` (fx-only — a cofx-reading
    ;; body routes to an `:fx` handler).
    ;;
    ;; EP-0018: there is ONE public `reg-event` (cofx-in, effects-map-out).
    ;; `rf.conformance/normalize-event-handler` collapses the DSL `[body-shape
    ;; handler]` pair to that single form — a :db-kind body `(fn [db event]
    ;; new-db)` is lifted to `(fn [cofx event] {:db …})`, an :fx-kind body passes
    ;; through — so the registration site never branches on the body-shape.
    ;; Use the FN form (`rf.events/reg-event`, no source-coord capture) — same
    ;; rationale as the subs below, PLUS provenance: a fixture that overrides
    ;; a framework event id (e.g. :rf/hydrate, registered fn-form by
    ;; re-frame.ssr with nil provenance ns) must land in the SAME
    ;; source-store slot and REPLACE it, not sit beside it as a
    ;; cross-namespace duplicate that fails default-image assembly loud
    ;; (:rf.error/image-duplicate-id), because fixture frames resolve
    ;; through the default image.
    (doseq [[id steps] (get handlers-map :event)]
      (let [handler        (rf.conformance/normalize-event-handler
                             (rf.conformance/realise-event-handler steps))
            ks             (rf.conformance/collect-cofx-keys steps)
            cofx-ids       (vec
                             (mapcat (fn [k]
                                       (or (get cofx-by-key k)
                                           (when (contains? cofx-registry k) [k])))
                                     ks))
            event-meta     (cond-> (get event-registry id {})
                             (seq cofx-ids) (assoc :rf.cofx/requires cofx-ids))]
        ;; CLAIM the id first (bundle co-load hygiene): a fixture
        ;; owns its registration vocabulary for the run; a co-loaded testbed
        ;; app registering the same id from ITS namespace (e.g. the story
        ;; counter testbed's :counter/inc) would otherwise sit beside the
        ;; fixture's row and fail default-image assembly loud. The reset
        ;; fixture restores the suite baseline afterward.
        (claim-fixture-id! :event id)
        (if (seq event-meta)
          (rf.events/reg-event id event-meta handler)
          (rf.events/reg-event id handler))))
    ;; sub registrations. Use the fn-form `rf.subs/reg-sub` because the public
    ;; `rf/reg-sub` is a macro (source-coord capture) and a macro var isn't a
    ;; callable value; source-coord capture is intentionally bypassed for
    ;; these fixture-synthesised registrations.
    (doseq [[id steps] (get handlers-map :sub)]
      (claim-fixture-id! :sub id) ;; claim (see claim-fixture-id!)
      (let [{:keys [kind inputs body]} (rf.conformance/realise-sub steps)
            sub-meta (get sub-registry id {})]
        (case kind
          :layer-1 (if (seq sub-meta)
                     (rf.subs/reg-sub id sub-meta body)
                     (rf.subs/reg-sub id body))
          ;; EP-0001: a `[:get [:rf.runtime/… …]]` fixture sub
          ;; reads the runtime-db partition — register via reg-runtime-sub.
          :runtime-db (if (seq sub-meta)
                        (rf.subs/reg-runtime-sub id sub-meta body)
                        (rf.subs/reg-runtime-sub id body))
          ;; A declared dependency list rides the metadata map:
          ;; the fixture's `:inputs` go in as DATA rather than spliced
          ;; positionally, so the corpus registers through the one public form.
          :layer-2 (rf.subs/reg-sub id (assoc sub-meta :inputs (vec inputs)) body))))
    ;; fx handlers — DSL bodies. An id with NO body in
    ;; :fixture/handlers but a meta in :fixture/registry is "declare the
    ;; dependency, leave the framework registration alone" — the harness does
    ;; NOT overwrite the framework-shipped fx with a noop.
    (let [adapter-helpers
          {:read-db!  (fn [frame-id]
                        (rf.frame/frame-app-db-value frame-id))
           ;; EP-0001: write the app-db PARTITION via
           ;; `swap-frame-db!` — `rf.frame/app-db-container` is a READ-ONLY
           ;; projection over the one physical frame-state container.
           :write-db! (fn [frame-id new-db]
                        (rf.frame/swap-frame-db! frame-id (constantly new-db)))
           :dispatch! (fn [event frame-id]
                        (rf/dispatch event {:frame frame-id}))
           ;; Per Cross-Spec Interaction §14: dispatch-sync from an
           ;; fx handler body trips the router's in-drain guard.
           :dispatch-sync! (fn [event frame-id]
                             (rf/dispatch-sync event {:frame frame-id}))
           ;; Per EP-0027 §Handler-time guard: frame construction invoked
           ;; from an fx body (mid-cascade) trips the construction guard.
           :make-frame! (fn [frame-id config]
                          (rf/make-frame (assoc config :id frame-id)))}
          fx-bodies   (get handlers-map :fx)
          fx-registry (get-in fixture [:fixture/registry :fx] {})
          all-fx-ids  (into #{} (concat (keys fx-bodies) (keys fx-registry)))]
      (doseq [id all-fx-ids]
        (let [explicit-body (contains? fx-bodies id)
              body          (get fx-bodies id [[:noop]])
              meta          (get fx-registry id {})
              handler       (rf.conformance/realise-fx-handler id body adapter-helpers)]
          (when explicit-body
            ;; FN form (no source-coord capture): a fixture stub of a
            ;; framework fx id (e.g. :rf.http/managed, :rf.nav/*) replaces
            ;; the framework's nil-provenance source-store slot instead of
            ;; colliding at default-image assembly. CLAIM the
            ;; id first so a co-loaded app-namespace row can't collide.
            (claim-fixture-id! :fx id)
            (rf.fx/reg-fx id (assoc meta :handler-fn handler) handler)))))
    ;; route registrations. The path pattern is the 3-slot
    ;; VALUE; lift it out of the fixture meta map so the middle slot is a pure
    ;; metadata map.
    (doseq [[id meta] (get handlers-map :route)]
      (do (claim-fixture-id! :route id) ;; claim (see claim-fixture-id!)
          (rf/reg-route id (dissoc meta :path) (:path meta))))
    ;; view registrations — DSL bodies map to fns that realise hiccup with
    ;; reflection forms resolved at call-time.
    (doseq [[id steps] (get handlers-map :view)]
      (rf.registrar/register!
        :view id
        {:handler-fn (rf.conformance/realise-view-handler steps)}))
    ;; head registrations — a head body is `[[:return-head model]]`, a head
    ;; fn returning that literal model. Routes name it through `:head`.
    (doseq [[id [[_ model]]] (get handlers-map :head)]
      (claim-fixture-id! :head id) ;; claim (see claim-fixture-id!)
      (rf.core-ssr/-reg-head id (fn [_db _route] model)))
    ;; machine registrations. Merge the fixture's realised action /
    ;; guard bodies into each machine-spec before reg-machine*.
    (let [machine-registry (get-in fixture [:fixture/registry :machine] {})]
      (when (seq machine-registry)
        (let [{:keys [actions guards]} (realise-machine-handlers fixture)
              reg-machine rf.machines/reg-machine*]
          (doseq [[machine-id machine-spec] machine-registry]
            (let [merged (-> machine-spec
                             (update :actions #(merge actions %))
                             (update :guards  #(merge guards %)))]
              (reg-machine machine-id merged))))))))

(defn- realise-app-schemas!
  "Register the fixture's app-db schemas. Called AFTER the runner's
  destroy-frame! step (so the `:schemas/on-frame-destroyed!` hook doesn't
  wipe them) and BEFORE `make-frame` (so the :initial-events cascade validates
  the seeded state). The fixture key is
  `:app-schemas` — app-db schemas are NOT a registrar kind."
  [fixture]
  (doseq [[path schema] (get-in fixture [:fixture/registry :app-schemas])]
    (rf/reg-app-schema path schema)))

(defn- realise-flows!
  "Register the fixture's static flows. Called AFTER `make-frame` — per Spec
  013 flows are FRAME-SCOPED, so the destroy-frame! teardown hook
  clears any flows registered before the destroy step. Static shape lives
  under `:fixture/registry :flow`; body DSL under `:fixture/flow-bodies`."
  [fixture]
  (let [flow-registry (get-in fixture [:fixture/registry :flow] {})
        flow-bodies   (or (:fixture/flow-bodies fixture) {})]
    (doseq [[flow-id flow-meta] flow-registry]
      (when-let [body (get flow-bodies flow-id)]
        (let [output-fn (rf.conformance/realise-flow-output-fn body)]
          ;; The 3-slot grammar: (reg-flow flow-id metadata
          ;; derive-fn). `flow-meta` is the reflection metadata middle slot.
          (rf/reg-flow flow-id flow-meta output-fn))))))

(def classification-effect-axes
  "The four EP-0025 commit-plane axes a `:fixture/classification-effects`
  op-map may carry. An op carries EXACTLY ONE of them — see
  `:conformance/classification-op` in `spec/conformance/fixture-file.edn`
  and `spec/conformance/README.md` §The `:fixture/classification-effects`
  harness step."
  #{:sensitive :large :clear-sensitive :clear-large})

(defn- realise-classification-effects!
  "Apply a fixture's `:fixture/classification-effects` declarations against
  the established frame scope.

  EP-0025: durable app-db classification is the four commit-plane effects
  (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`, `:source
  :effect`). Because pure-EDN fixtures cannot return effects from a handler
  body, these ops are a TEST-ONLY shorthand writing the frame's durable
  elision registry EXACTLY as those effects would. `:sensitive` / `:large`
  are additive; `:clear-*` remove the named paths on their named axis ONLY.
  Called AFTER `make-frame` and BEFORE `realise-flows!`.

  Each op carries EXACTLY ONE of the four axes, and that is CHECKED here
  before anything is applied. The `cond` below is
  priority-ordered, so without the check a multi-axis op would silently
  apply only its first arm and an empty or unknown-key op would silently
  apply nothing — a fixture author writing two axes would get one, with no
  error, and a setup key the harness ignores changes the scenario without
  saying so."
  [fixture scope-frame]
  (letfn [(slot-for [axis]
            (case axis :sensitive :sensitive-declarations :large :declarations))
          ;; Delegate to the SAME core multi-owner ops the real commit-plane
          ;; effects use, so the seed writes the effect owner exactly as
          ;; `apply-classification-effects` would.
          (add-paths [reg axis paths]
            (rf.elision/add-claims reg (slot-for axis) {:source :effect} (map vec paths)))
          (clear-paths [reg axis paths]
            (rf.elision/remove-claims reg (slot-for axis) {:source :effect} (map vec paths)))]
    (doseq [op (or (:fixture/classification-effects fixture) [])]
      (let [ks (set (keys op))]
        (when-not (and (= 1 (count ks))
                       (contains? classification-effect-axes (first ks)))
          (throw (ex-info (str "unrecognised :fixture/classification-effects op-map — "
                               "each op carries EXACTLY ONE of "
                               (pr-str classification-effect-axes))
                          {:fixture-id (:fixture/id fixture)
                           :op         op
                           :keys       ks}))))
      (cond
        (contains? op :sensitive)
        (rf.elision/swap-elision-slot! scope-frame #(add-paths (or % {}) :sensitive (:sensitive op)))
        (contains? op :large)
        (rf.elision/swap-elision-slot! scope-frame #(add-paths (or % {}) :large (:large op)))
        (contains? op :clear-sensitive)
        (rf.elision/swap-elision-slot! scope-frame #(clear-paths (or % {}) :sensitive (:clear-sensitive op)))
        (contains? op :clear-large)
        (rf.elision/swap-elision-slot! scope-frame #(clear-paths (or % {}) :large (:clear-large op)))))))

;; ---- trace / error-emit collection ----------------------------------------

(defn- collect-traces
  "Register a fixture-scoped trace listener via the HOST (the trace registry
  differs by host per the production-DCE split — `re-frame.trace` on JVM,
  `re-frame.trace.tooling` on CLJS). Returns the accumulating atom."
  [host fixture-id]
  (let [traces (atom [])]
    ((:register-trace-listener! host) fixture-id (fn [ev] (swap! traces conj ev)))
    traces))

(defn- collect-error-emit-records!
  "Register a corpus-wide error-emit listener for the duration
  of `fixture-id`'s run; each tight error-record fanned out by
  `rf.error-emit/dispatch-on-error!` is appended to the returned atom in firing
  order. Host-neutral — `re-frame.error-emit` is a shared core ns."
  [fixture-id]
  (let [records (atom [])]
    (rf.error-emit/register-error-listener!
      [fixture-id ::records]
      (fn [record] (swap! records conj record)))
    records))

;; ---- expectation matchers -------------------------------------------------

(defn- check-error-emit-records
  "Order-preserving `submap?` matcher for `:error-emit-records`
  (mirror of `check-trace-emissions`). Returns a vector of failure strings."
  [actual-records expected-records]
  (loop [actual   actual-records
         expected expected-records
         failures []]
    (cond
      (empty? expected)
      failures

      (empty? actual)
      (conj failures (str "expected error-emit record not seen: "
                          (pr-str (first expected))))

      :else
      (let [exp (first expected)
            match-idx (->> actual
                           (map-indexed vector)
                           (some (fn [[i a]]
                                   (when (rf.conformance/submap? exp a) i))))]
        (if match-idx
          (recur (drop (inc match-idx) actual) (rest expected) failures)
          (recur actual (rest expected)
                 (conj failures (str "expected error-emit record not seen: "
                                     (pr-str exp)))))))))

;; `submap?` (recursive submap match), `check-trace-emissions` (order-preserving
;; partial trace subset), and `resolve-sub` (`:sub-values` query-frame
;; resolution) are the SHARED expectation-matcher primitives owned by
;; `re-frame.conformance` (core/src) — consumed here and by the per-feature
;; artefact runners alike.

(defn- normalise-effects-routed
  "Normalise `:effects-routed` entries (`{:fx-id F :args A}` map form OR
  `[F A]` pair form) to `{:fx-id F :fx-args A}` (the runtime's trace key)."
  [entries]
  (mapv (fn [e]
          (cond
            (and (map? e) (contains? e :fx-id))
            {:fx-id (:fx-id e) :fx-args (:args e)}

            (and (vector? e) (= 2 (count e)))
            {:fx-id (first e) :fx-args (second e)}

            :else
            (throw (ex-info "unrecognised :effects-routed entry" {:entry e}))))
        entries))

(defn- effects-routed-from-traces
  "Derive the actual fx routings from the trace stream. Per
  `re-frame.fx/handle-one-fx`, every successful routing emits `:rf.fx/handled`
  (and a handler-throw emits `:rf.error/fx-handler-exception`) carrying
  `:rf.fx/id` (post-override) + `:rf.fx/args`."
  [traces]
  (->> traces
       (filter (fn [t]
                 (let [op (:operation t)]
                   (or (= op :rf.fx/handled)
                       (= op :rf.error/fx-handler-exception)))))
       (mapv (fn [t]
               {:fx-id   (get-in t [:tags :rf.fx/id])
                :fx-args (get-in t [:tags :rf.fx/args])}))))

(defn- check-effects-routed
  "Order-preserving subset match — every expected entry must appear in
  `actual` in declaration order. Extras are tolerated; missing / out-of-order
  are failures."
  [actual expected]
  (loop [actual   actual
         expected expected
         failures []]
    (cond
      (empty? expected) failures

      (empty? actual)
      (conj failures (str "expected effect not routed: " (pr-str (first expected))))

      :else
      (let [exp       (first expected)
            match-idx (->> actual
                           (map-indexed vector)
                           (some (fn [[i a]] (when (= exp a) i))))]
        (if match-idx
          (recur (drop (inc match-idx) actual) (rest expected) failures)
          (recur actual (rest expected)
                 (conj failures (str "expected effect not routed: " (pr-str exp)))))))))

(defn- check-trace-not-emitted
  "`:trace-not-emitted` — no captured trace may match any of the fixture's
  shapes, each matched by `submap?` exactly as `:trace-emissions` matches
  its rows. Returns a vector of failure strings."
  [actual-traces not-traces]
  (vec
    (keep (fn [nt]
            (when (some #(rf.conformance/submap? nt %) actual-traces)
              (str "trace that should NOT have fired did: " (pr-str nt))))
          not-traces)))

(defn- check-epoch-records
  "`:epoch-records` asserts against the recorded
  `:rf/epoch-record` ring. Each entry (`{:frame <id> :record <partial>}` or
  `{:record <partial>}` for implicit :rf/default) is a partial submap matched
  positionally against the named frame's history (oldest-first). Returns a
  vector of failure-strings.

  Shared, so both hosts check it — a host without this matcher would pass
  every `:epoch-records` fixture while ignoring its expectation."
  [expected]
  (let [by-frame (group-by #(or (:frame %) :rf/default) expected)]
    (vec
      (mapcat
        (fn [[frame-id entries]]
          (let [actual-history (try (rf/epoch-history frame-id)
                                    (catch #?(:clj Throwable :cljs :default) _ []))]
            (keep-indexed
              (fn [i {:keys [record]}]
                (let [actual-record (get actual-history i)]
                  (cond
                    (nil? actual-record)
                    (str "expected epoch-record at position " i
                         " for frame " frame-id " but none recorded")

                    (not (rf.conformance/submap? record actual-record))
                    (str "epoch-record mismatch at position " i
                         " for frame " frame-id
                         " — expected (submap) " (pr-str record)
                         " — actual " (pr-str (select-keys actual-record
                                                           (keys record)))))))
              entries)))
        by-frame))))

(defn- submap-failures
  "The grammar's `:submap` law, as a failure seq: empty when `expected` is a
  submap of `actual`."
  [what expected actual]
  (when-not (rf.conformance/submap? expected actual)
    [(str what " expected (submap) " (pr-str expected) "\n      actual " (pr-str actual))]))

(defn- equal-failures
  "The grammar's `:equal` law, as a failure seq."
  [what expected actual]
  (when (not= expected actual)
    [(str what " expected " (pr-str expected) "\n      actual   " (pr-str actual))]))

(defn- absent-path-failures
  "The grammar's `:absent-paths` law. A sentinel `get-in` default tells an
  absent tip key from one present with a nil value."
  [db paths]
  (let [sentinel ::absent]
    (keep (fn [path]
            (let [v (get-in db path sentinel)]
              (when-not (identical? v sentinel)
                (str "expected app-db path " (pr-str path) " to be ABSENT but found " (pr-str v)))))
          paths)))

(defn- flow-ids
  "The ids of the flows registered on `frame-id`."
  [frame-id]
  (set (keys (get (rf.flows/flows-snapshot) frame-id))))

(defn- flow-dependencies
  "`{flow-id #{dep-id …}}` over `:rf/default`'s flows, by the runtime's own
  `topo/depends-on?`, so the matcher cannot drift from the dependency rule."
  []
  (let [registry (get (rf.flows/flows-snapshot) :rf/default {})]
    (into {} (for [[id flow] registry]
               [id (into #{} (for [[other-id other] registry
                                   :when (and (not= id other-id)
                                              (rf.flows.topo/depends-on? flow other))]
                               other-id))]))))

(defn- active-head
  "The head model `:rf/default`'s active route resolves to, or nil when
  resolution throws (an unregistered `:head`, an absent frame): the channel
  asserts a model, not a failure mode."
  []
  (try (rf.ssr/head-model :rf/default)
       (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- routed-failures
  "`check-effects-routed`, with the routed list appended when it fails."
  [actual expected]
  (when-let [failures (seq (check-effects-routed actual (normalise-effects-routed expected)))]
    (conj (vec failures) (str "routed: " (pr-str actual)))))

;; ---- the `:fixture/expect` matcher table ----------------------------------

(def expect-matchers
  "The ONE matcher for each `:fixture/expect` key the fixture grammar
  declares, as `[key {:match f :dev-only? bool}]` pairs in grading order.
  `(f run expected)` returns a seq of failure strings, empty on a pass;
  `run` carries the fixture's `:traces` and `:err-records` atoms and its
  `:destroy-traces` delay. Each matcher applies the law the grammar names
  for its key (`spec/conformance/fixture-file.edn` §`:laws`).

  The order matters twice. Subscribing for `:sub-values` can emit the
  sub-exception trace a `:trace-emissions` row expects, so state and subs
  are read before any trace. And the frame-destroy window is opened last,
  so every other matcher reads the live frame and creation-time traces
  cannot satisfy a destroy-time claim.

  `:dev-only?` marks a matcher reading a surface only the debug build emits:
  the trace bus, the effects and public error read off it, and the
  trace-fed epoch ring."
  [[:final-app-db
    {:match (fn [_ want] (submap-failures "app-db" want (rf/app-db-value :rf/default)))}]
   [:final-app-dbs
    {:match (fn [_ want]
              (mapcat (fn [[fid db]] (submap-failures (str fid " app-db") db (rf/app-db-value fid)))
                      want))}]
   [:final-app-db-absent
    {:match (fn [_ paths] (absent-path-failures (rf/app-db-value :rf/default) paths))}]
   ;; EP-0001: durable framework runtime state lives in the runtime-db
   ;; partition.
   [:final-runtime-db
    {:match (fn [_ want]
              (submap-failures "runtime-db" want
                               (:rf.db/runtime (rf/frame-state-value :rf/default))))}]
   [:final-runtime-dbs
    {:match (fn [_ want]
              (mapcat (fn [[fid rt]]
                        (submap-failures (str fid " runtime-db") rt
                                         (:rf.db/runtime (rf/frame-state-value fid))))
                      want))}]
   [:sub-values
    {:match (fn [_ want]
              (mapcat (fn [[query-v expected]]
                        (let [[frame-id qv] (rf.conformance/resolve-sub :rf/default query-v)]
                          (equal-failures (str "sub " (pr-str query-v)) expected
                                          (rf/subscribe-once qv {:frame frame-id}))))
                      want))}]
   ;; Spec 011's per-request response accumulator — the one slot of the
   ;; request result the corpus observes. The host adapter builds the body
   ;; and payload from it, and its own tests grade them.
   [:ssr/request-result
    {:match (fn [_ want]
              (submap-failures "request result" want
                               {:response (rf.ssr/get-response :rf/default)}))}]
   [:ssr/active-head
    {:match (fn [_ want] (equal-failures "head model" want (active-head)))}]
   [:ssr/rendered-head-contains
    {:match (fn [_ wanted]
              (let [html (or (some-> (active-head) rf.ssr/head-model->html) "")]
                (for [s wanted :when (not (str/includes? html s))]
                  (str "rendered head lacks " (pr-str s) "\n      html " (pr-str html)))))}]
   [:flow-graph-topology
    {:match (fn [_ want]
              (equal-failures "flow dependencies" want
                              (select-keys (flow-dependencies) (keys want))))}]
   ;; A bare set reads `:rf/default`; a map reads each named frame.
   [:flow-registry-after
    {:match (fn [_ want]
              (equal-failures "registered flows" want
                              (if (map? want)
                                (into {} (for [[fid _] want] [fid (flow-ids fid)]))
                                (flow-ids :rf/default))))}]
   [:flow-last-inputs-after
    {:match (fn [_ want]
              (equal-failures "frames holding last inputs" want
                              (into {} (for [[id _] want]
                                         [id (set (keys (get (rf.flows/last-inputs-snapshot) id)))]))))}]
   ;; The whole `:flow` registrar slot, which `reg-flow` never writes —
   ;; neither the per-frame store nor the expected ids, either of which
   ;; would let an empty expectation match a polluted slot.
   [:registrar-flow-slots-after
    {:match (fn [_ want] (equal-failures "registrar :flow ids" want (set (rf.registrar/ids :flow))))}]
   [:trace-emissions
    {:dev-only? true
     :match     (fn [{:keys [traces]} want] (rf.conformance/check-trace-emissions @traces want))}]
   [:trace-not-emitted
    {:dev-only? true
     :match     (fn [{:keys [traces]} shapes] (check-trace-not-emitted @traces shapes))}]
   [:trace-absent
    {:dev-only? true
     :match     (fn [{:keys [traces]} shapes] (check-trace-not-emitted @traces shapes))}]
   [:expect-trace-stream
    {:dev-only? true
     :match     (fn [{:keys [traces]} want]
                  (rf.conformance/check-trace-emissions (filterv #(= :flow (:op-type %)) @traces) want))}]
   ;; A `:rf.flow/skip` is the absence of a recompute, so it does not count.
   [:flow-recompute-counts
    {:dev-only? true
     :match     (fn [{:keys [traces]} want]
                  (equal-failures "flow recomputes" want
                                  (frequencies (for [t @traces :when (= :rf.flow/computed (:operation t))]
                                                 (get-in t [:tags :flow-id])))))}]
   [:effects-routed
    {:dev-only? true
     :match     (fn [{:keys [traces]} want] (routed-failures (effects-routed-from-traces @traces) want))}]
   [:error-emit-records
    {:match (fn [{:keys [err-records]} want]
              (when-let [failures (seq (check-error-emit-records @err-records want))]
                (conj (vec failures) (str "records: " (pr-str @err-records)))))}]
   ;; Both hosts read the recorded `:rf/epoch-record` ring.
   [:epoch-records
    {:dev-only? true
     :match     (fn [_ want] (check-epoch-records want))}]
   ;; Spec 011: the run's last error, projected by the active error projector.
   [:ssr/public-error
    {:dev-only? true
     :match     (fn [{:keys [traces]} want]
                  (if-let [last-error (last (filter #(= :error (:op-type %)) @traces))]
                    (equal-failures "public error" want (rf.ssr/project-error :rf/default last-error))
                    [(str "expected public error " (pr-str want) " but the run emitted no error")]))}]
   [:on-destroy-emissions
    {:dev-only? true
     :match     (fn [{:keys [destroy-traces]} want]
                  (rf.conformance/check-trace-emissions @destroy-traces want))}]
   [:on-destroy-effects
    {:dev-only? true
     :match     (fn [{:keys [destroy-traces]} want]
                  (routed-failures (effects-routed-from-traces @destroy-traces) want))}]])

(def ^:private expect-matcher-index (into {} expect-matchers))

(defn- grade-expect
  "Grade a fixture's `:fixture/expect` block against the finished `run`:
  `{key failures}` for each key that failed. A key with no matcher fails
  naming itself, so an expectation this runner cannot read is never a pass."
  [run expect]
  (into (into {} (for [k (keys expect) :when (not (contains? expect-matcher-index k))]
                   [k ["no matcher grades this key"]]))
        (keep (fn [[k {:keys [match]}]]
                (when (contains? expect k)
                  (when-let [failures (seq (match run (get expect k)))]
                    [k (vec failures)]))))
        expect-matchers))

;; ---- production-posture subset --------------------------------------------

(def dev-posture-expect-keys
  "The `:fixture/expect` keys whose matcher reads a surface only the debug
  build emits (`:dev-only?` in `expect-matchers`). Under
  `-Dre-frame.debug=false` none of them is emitted, so a fixture asserting a
  present one fails there by design, and a negative one (`:trace-not-emitted`,
  `:trace-absent`) would pass over an empty trace stream whatever the
  runtime did."
  (into #{} (keep (fn [[k {:keys [dev-only?]}]] (when dev-only? k))) expect-matchers))

(def dev-posture-capabilities
  "Capabilities whose fixtures read a dev-only surface through
  `:fixture/calls` rather than through an expectation key.
  `:derivation/algebra-graph` asserts the live derivation graph, whose
  projections are gated on `interop/debug-enabled?`."
  #{:derivation/algebra-graph})

(defn prod-posture-fixture?
  "True when grading the fixture reads no dev-only surface, so it grades the
  same under `-Dre-frame.debug=false` as in the debug build. Capability tags
  cannot answer this: every `:core/*` capability is required of every port,
  so a fixture asserting trace emissions need not claim `:core/trace`."
  [fixture]
  (and (not-any? dev-posture-expect-keys (keys (:fixture/expect fixture)))
       (not-any? dev-posture-capabilities (:fixture/capabilities fixture))))

;; ---- fixture registration helpers -----------------------------------------

(defn- register-routes! [fixture]
  ;; EDN maps don't preserve insertion order beyond ~8 entries; register in
  ;; deterministic lex order on the route-id so structurally-equal rank tuples
  ;; emit a deterministic shadow warning.
  (doseq [[id meta] (sort-by (comp str key)
                             (get-in fixture [:fixture/registry :route]))]
    (do (claim-fixture-id! :route id) ;; claim (see claim-fixture-id!)
          (rf/reg-route id (dissoc meta :path) (:path meta)))))

(defn- register-resources!
  "Register a fixture's `:fixture/registry :resource` entries
  (Spec 016). A resource spec needs a `:request` fn, which EDN cannot carry;
  the corpus only asserts the registration-derived STATIC graph, so the
  runner synthesises a deterministic `:request` stub from the declared
  `:url-template` (or `/api/<resource-id>`)."
  [fixture]
  (doseq [[resource-id spec] (sort-by (comp str key)
                                      (get-in fixture [:fixture/registry :resource]))]
    (let [url-template (:url-template spec)
          request-fn   (fn [params _ctx]
                         {:request {:method :get
                                    :url    (if url-template
                                              (str url-template params)
                                              (str "/api/" (name resource-id)))}})]
      (rf.resources/reg-resource resource-id
                              (dissoc spec :url-template)
                              request-fn))))

(defn- realise-machine-handlers
  "Build `{action-id → fn}` / `{guard-id → fn}` from a
  fixture's `:machine-action` / `:machine-guard` buckets. Per Spec 005
  §Guards / §Actions the user-facing fn receives one
  context-map arg."
  [fixture]
  (let [handlers-map (or (:fixture/handlers fixture) {})
        actions-by-id
        (into {}
              (for [[id steps] (:machine-action handlers-map)]
                [id (fn [{:keys [data event]}]
                      (let [final (reduce
                                    (fn [{:keys [data] :as ctx} step]
                                      (case (first step)
                                        :set    (let [[_ path v] step]
                                                  (assoc ctx :data
                                                         (assoc-in data path
                                                                   (rf.conformance/eval-value* v ctx))))
                                        ;; :fx args pass through
                                        ;; eval-value* so reflection forms
                                        ;; resolve against the snapshot's :data.
                                        :fx     (let [[_ a b] step]
                                                  (update ctx :fx (fnil conj [])
                                                          [a (rf.conformance/eval-value* b ctx)]))
                                        ;; A throwing action exercises
                                        ;; Cross-Spec §11 machine-action-exception.
                                        :throw  (throw (ex-info (str (second step))
                                                                {:from-fixture? true}))
                                        ctx))
                                    {:data data :event event :fx []}
                                    steps)]
                        (cond-> {}
                          (not= data (:data final)) (assoc :data (:data final))
                          (seq (:fx final)) (assoc :fx (:fx final)))))]))
        guards-by-id
        (into {}
              (for [[id steps] (:machine-guard handlers-map)]
                [id (fn [{:keys [data event]}]
                      (let [step (first steps)]
                        (when (and (vector? step) (= :fn (first step)))
                          (boolean
                            (rf.conformance/eval-value* step {:data data :event event})))))]))]
    {:actions actions-by-id
     :guards  guards-by-id}))

;; ---- the `:fixture/calls` matcher table -----------------------------------

(defn- on-value
  "A call matcher that grades an operation's returned value. An operation
  that threw is graded by `:expect-error`, or failed once by `run-call`."
  [f]
  (fn [outcome want call]
    (when (contains? outcome :value)
      (f (:value outcome) want call))))

(defn- equal-to
  "A call matcher applying the `:equal` law to the returned value, or to
  `(observe value)`."
  ([] (equal-to identity))
  ([observe]
   (on-value (fn [v want _] (equal-failures "" want (observe v))))))

(defn- thrown-error-id
  "The grammar's `:thrown-error-id` law: the operation threw, and the
  throw's ex-data `:rf.error/id` is the expected id."
  [outcome want _]
  (let [e   (:thrown outcome)
        got (some-> e ex-data :rf.error/id)]
    (when-not (= want got)
      [(str "expected a throw with :rf.error/id " want ", got "
            (cond (nil? e)    "no throw"
                  (some? got) got
                  :else       (str "a throw without one: " (ex-message e))))])))

(defn- member-failures
  "The grammar's `:member` / `:non-member` laws over the collection `coll`."
  [what coll wanted member?]
  (for [x wanted :when (not= member? (contains? coll x))]
    (str (pr-str x) (if member? " expected in " " expected absent from ") what)))

(defn- realise-image-spec
  "An `rf/image` spec with each inline registration body realised to a
  FRESH host no-op: EDN cannot carry fns, and a body shared between two
  inline entries for one `[kind id]` would dedupe them and mask the
  within-image collision a fixture pins."
  [spec]
  (letfn [(realise-entry [entry]
            (cond
              (and (vector? entry) (= 3 (count entry)))
              (assoc entry 2 (fn [& _] nil))
              (and (vector? entry) (= 2 (count entry)) (not (map? (nth entry 1))))
              (assoc entry 1 (fn [& _] nil))
              :else entry))]
    (cond-> spec
      (contains? spec :registrations)
      (update :registrations
              #(reduce-kv (fn [m section entries] (assoc m section (mapv realise-entry entries)))
                          {} %)))))

(defn- derivation-contributors
  "The FULL contributor set the `:derivation-graph` op composes."
  []
  {:subs      {:static-fn  rf.subs.tooling/sub-algebra-view
               :live-fn    rf.subs.tooling/sub-cache-algebra-view
               :live-shape :map}
   :flows     {:static-fn  rf.flows.tooling/flow-algebra-view
               :live-fn    rf.flows.tooling/flow-algebra-view
               :live-shape :map}
   :resources {:static-fn  rf.resources.tooling/resource-algebra-view
               :live-fn    rf.resources.tooling/resource-cache-algebra-view
               :live-shape :map}
   :routes    {:static-fn  rf.routing.tooling/route-algebra-view
               :live-fn    rf.routing.tooling/route-slice-algebra-view
               :live-shape :node}
   :machines  {:static-fn        rf.machines.tooling/machine-algebra-view
               :live-fn          rf.machines.tooling/machine-instance-algebra-view
               :live-shape       :map
               :selector-targets rf.machines.tooling/machine-selector-targets}})

(defn- path-op
  "A `:path-*` law op: `(f call)` is the `:rf/path` operation under test."
  [f]
  {:observe  (fn [call _] (f call))
   :matchers {:expect (equal-to)}})

(def call-matchers
  "The ONE matcher for each expectation a `:fixture/calls` record carries,
  keyed by `:call` op. `:observe` runs the operation once, as `(observe
  call fixture-machines)`; `run-call` captures a throw as the outcome.
  `:matchers` maps each expectation key the grammar declares on the op's
  record to `(f outcome expected call)`, a seq of failure strings. `:holds`
  grades a record whose whole claim is its observation (the grammar's
  `:holds` law), as `(f value call)`."
  {:match-url
   ;; The result's `:validation-error` is an implementation-specific
   ;; explanation; the `:validation-failed?` flag is the normative bit
   ;; (Spec 012 §Bidirectional URL ↔ params).
   {:observe  (fn [call _]
                (some-> (rf.routing/match-url (:url call)) (dissoc :validation-error)))
    :matchers {:expect (equal-to)}}

   :route-url
   {:observe  (fn [call _]
                (rf.routing/route-url
                  (cond-> {:to (:route-id call) :params (:params call)}
                    (or (:query call) (contains? call :fragment))
                    (assoc :query (or (:query call) {}))
                    (contains? call :fragment)
                    (assoc :fragment (:fragment call)))))
    :matchers {:expect (equal-to)}}

   :round-trip
   {:observe (fn [call _]
               (when-let [matched (rf.routing/match-url (:url call))]
                 (rf.routing/route-url {:to       (:route-id matched)
                                        :params   (:params matched)
                                        :query    (or (:query matched) {})
                                        :fragment (:fragment matched)})))
    :holds   (fn [rebuilt call]
               (when (not= (:url call) rebuilt)
                 [(str "route-url of match-url rebuilt " (pr-str rebuilt))]))}

   :assert-rank-greater
   {:observe (fn [call _]
               [(:rf.route/rank (rf.registrar/lookup :route (:winner call)))
                (:rf.route/rank (rf.registrar/lookup :route (:loser call)))])
    :holds   (fn [[w-rank l-rank] call]
               (when-not (and w-rank l-rank (pos? (compare w-rank l-rank)))
                 [(str (:winner call) " rank " (pr-str w-rank) " does not compare greater than "
                       (:loser call) " rank " (pr-str l-rank))]))}

   ;; The pure path-pattern validator: no registrar mutation, no navigation.
   :reg-route
   {:observe  (fn [call _]
                (rf.routing.match/validate-route-pattern! :rf.test/pattern (:pattern call)))
    :matchers {:expect-error thrown-error-id}}

   ;; A view is named by the portable `[:view-ref <id> & args]` marker (a
   ;; keyword head is a DOM element on every host); `realise-view-refs`
   ;; turns it into this host's callable-head form.
   :render-to-string
   {:observe  (fn [call _]
                (rf.ssr/render-to-string (rf.conformance/realise-view-refs (:input call))
                                         (or (:opts call) {})))
    :matchers {:expect (equal-to)}}

   ;; The public fn returns Spec 005 §Level 1's `{:status :ok :snapshot …
   ;; :fx …}` or `{:status :error :error {:kind …}}`. A bounded-depth abort
   ;; projects onto the observable atomic-rollback shape: the input
   ;; snapshot, no effects.
   :machine-transition
   {:observe  (fn [call {:keys [actions guards]}]
                (let [definition (-> (:definition call)
                                     (update :actions #(merge actions %))
                                     (update :guards  #(merge guards %)))
                      r          (rf.machines/machine-transition
                                   definition (:snapshot call) (:event call))
                      abort?     (and (= :error (:status r))
                                      (contains? #{:rf.error/machine-always-depth-exceeded
                                                   :rf.error/machine-raise-depth-exceeded}
                                                 (get-in r [:error :kind])))]
                  (if abort?
                    {:snapshot (:snapshot call) :fx []}
                    {:snapshot (:snapshot r) :fx (vec (:fx r))})))
    :matchers {:expect-next-snapshot (equal-to :snapshot)
               :expect-effects       (equal-to :fx)}}

   ;; Registration-time validation of the raw definition; handler bodies
   ;; are not realised.
   :reg-machine
   {:observe  (fn [call _] (rf.machines/validate-machine! (:definition call)))
    :matchers {:expect-error thrown-error-id}}

   ;; EP-0027 construction. The frame is destroyed afterward (best-effort).
   :make-frame
   {:observe  (fn [call _]
                (let [frame-id (or (:frame-id call) :rf.test/construction)]
                  (try (rf/make-frame (assoc (:config call) :id frame-id))
                       nil
                       (finally
                         (try (rf/destroy-frame! frame-id)
                              (catch #?(:clj Throwable :cljs :default) _ nil))))))
    :matchers {:expect-error thrown-error-id}}

   ;; Spec 016 registration validation. The registration is cleared
   ;; afterward.
   :reg-resource
   {:observe  (fn [call _]
                (let [resource-id (or (:resource-id call) :rf.test/resource)]
                  (try (rf.resources/reg-resource
                         resource-id (:spec call)
                         (fn [params _ctx]
                           {:request {:method :get :url (str "/api/probe" params)}}))
                       nil
                       (finally
                         (try (rf/clear :resource resource-id)
                              (catch #?(:clj Throwable :cljs :default) _ nil))))))
    :matchers {:expect-error thrown-error-id}}

   ;; Spec 016 §Scope resolution, fail-closed: `:side` picks the write-side
   ;; event resolver (the default) or the read-side sub resolver.
   :resolve-scope
   {:observe  (fn [call _]
                (let [resource-id (:resource-id call)]
                  (case (:side call :event)
                    :sub (rf.resources.subs/resolve-scoped-key
                           (cond-> {:resource resource-id :params (:params call {})}
                             (contains? call :payload-scope) (assoc :scope (:payload-scope call)))
                           {})
                    (rf.resources.registry/resolve-scope-for-event
                      resource-id (rf.resources.registry/resource-meta resource-id)
                      {:payload-scope (:payload-scope call)}
                      'rf.test/resolve-scope))))
    :matchers {:expect       (equal-to)
               :expect-error thrown-error-id}}

   ;; EP-0012 — the CEDN-1 canonical-identity golden ops, the frozen byte
   ;; contract.
   :canonical-bytes
   {:observe  (fn [call _] (rf.identity/canonical-bytes (:value call)))
    :matchers {:expect (equal-to)}}

   ;; The exact normalized value `canonical` returns, distinct from the byte
   ;; token `:canonical-bytes` pins.
   :canonical-value
   {:observe  (fn [call _] (rf.identity/canonical (:value call)))
    :matchers {:expect (equal-to)}}

   :canonical-identical
   {:observe (fn [call _] (rf.identity/identical-identity? (:a call) (:b call)))
    :holds   (fn [same? call]
               (when-not same?
                 [(str "expected one identity: " (pr-str (:a call)) " vs " (pr-str (:b call)))]))}

   :canonical-distinct
   {:observe (fn [call _] (rf.identity/identical-identity? (:a call) (:b call)))
    :holds   (fn [same? call]
               (when same?
                 [(str "expected distinct identities: " (pr-str (:a call)) " vs " (pr-str (:b call)))]))}

   ;; The concrete-path producer boundary.
   :path-instantiate
   {:observe  (fn [call _] (rf.path/instantiate (:path call) (:bindings call)))
    :matchers {:expect       (equal-to)
               :expect-error thrown-error-id}}

   ;; EP-0012 — the `:rf/path` algebra laws. `:path-over` names its
   ;; transform so the record stays data.
   :path-get     (path-op #(if (contains? % :not-found)
                             (rf.path/get (:value %) (:path %) (:not-found %))
                             (rf.path/get (:value %) (:path %))))
   :path-lookup  (path-op #(rf.path/lookup (:value %) (:path %)))
   :path-put     (path-op #(rf.path/put (:value %) (:path %) (:x %)))
   :path-over    (path-op #(rf.path/over (:value %) (:path %)
                                         (case (:fn %)
                                           :inc      (fn [v] (inc (or v 0)))
                                           :wrap-vec (fn [v] [:wrapped v]))))
   :path-compose (path-op #(rf.path/compose (:p %) (:q %)))
   :path-prefix  (path-op #(rf.path/prefix? (:p %) (:q %)))
   :path-overlap (path-op #(rf.path/overlap? (:p %) (:q %)))

   ;; EP-0015. `:frame` is read by key presence: a record that omits it
   ;; projects with `*current-frame*` bound to nil, genuinely frameless (the
   ;; fail-closed posture), so the ambient scope frame cannot leak in.
   :project-egress
   {:observe  (fn [call _]
                (let [opts (cond-> {:rf.egress/profile (:rf.egress/profile call)}
                             (contains? call :frame) (assoc :frame (:frame call)))]
                  (if (contains? call :frame)
                    (rf.projection/project-egress (:value call) opts)
                    (binding [rf.frame/*current-frame* nil]
                      (rf.projection/project-egress (:value call) opts)))))
    :matchers {:expect (equal-to)}}

   ;; EP-0015 — a frame-local carrier extends the immutable built-in
   ;; defaults; no frame can remove a default.
   :redact-headers
   {:observe  (fn [call _]
                (rf.http.privacy-headers/redact-headers (:headers call) (:frame-extras call)))
    :matchers {:expect (equal-to)}}

   ;; EP-0015 — the allowlist-first SSR hydration-payload projection, which
   ;; fails closed on a missing or malformed policy.
   :ssr-apply-policy
   {:observe  (fn [call _] (rf.ssr.payload-policy/apply-policy (:app-db call) (:opts call)))
    :matchers {:expect       (equal-to)
               :expect-error thrown-error-id}}

   ;; EP-0026 — pure: a function of the record's `:pool`, `:standards` and
   ;; `:images`, assembled against the live `rf/image` constructor and
   ;; `image-assembly`. Who-won is read from the descriptor coordinate, pure
   ;; EDN. `:images-literal` is the `make-frame` boundary case: it hands its
   ;; value to `make-frame`, whose `:images` validation fires before any
   ;; frame record exists. Standards and the generation cache are reset
   ;; around each call so nothing leaks into the next.
   :assemble-image
   {:observe
    (fn [call _]
      (if (contains? call :images-literal)
        (rf/make-frame {:images (:images-literal call)})
        (try
          (rf.image-assembly/clear-standards!)
          (rf.image-assembly/clear-generation-cache!)
          (doseq [[kind id] (:standards call)]
            (rf.image-assembly/register-standard! kind id {:handler-fn :rf.std/sentinel}))
          (let [pool   (vec (:pool call))
                images (mapv #(rf.image/image (realise-image-spec %)) (:images call))]
            (if (or (:default? call) (empty? images))
              (rf.image-assembly/assemble-default pool)
              (rf.image-assembly/assemble images pool)))
          (finally (rf.image-assembly/clear-standards!)))))
    :matchers
    {:expect-error      thrown-error-id
     :expect-resolves   (on-value
                          (fn [gen want _]
                            (keep (fn [{:keys [kind id coordinate]}]
                                    (let [d (get (:rf.gen/resolver gen) [kind id])]
                                      (cond
                                        (nil? d)
                                        (str "[" kind " " id "] does not resolve")
                                        (not= coordinate (rf.image-assembly/descriptor-coordinate d))
                                        (str "[" kind " " id "] resolved to coordinate "
                                             (pr-str (rf.image-assembly/descriptor-coordinate d))
                                             ", expected " (pr-str coordinate)))))
                                  want)))
     :expect-present    (on-value (fn [gen want _]
                                    (member-failures "the resolver" (:rf.gen/resolver gen)
                                                     (map vec want) true)))
     :expect-absent     (on-value (fn [gen want _]
                                    (member-failures "the resolver" (:rf.gen/resolver gen)
                                                     (map vec want) false)))
     :expect-kinds      (equal-to :rf.gen/kinds)
     ;; Top-level generation keys that must not appear on the sealed
     ;; generation map.
     :expect-gen-absent (on-value (fn [gen want _]
                                    (member-failures "the generation" gen want false)))
     :expect-shadows    (equal-to :rf.gen/shadows)}}

   ;; EP-0014 — the cross-family derivation/process graph, composed over
   ;; the full contributor set, `:static` (the default) or `:live`.
   ;; `:expect-graph` reads the WHOLE graph map, so a live graph that drops
   ;; or misreports its graph-level `{:mode :live :frame …}` fails even when
   ;; its nodes and edges are right.
   :derivation-graph
   {:observe
    (fn [call _]
      (if (= :live (:mode call))
        (rf.derivation.graph/live-derivation-graph :rf/default (derivation-contributors))
        (rf.derivation.graph/derivation-graph (derivation-contributors))))
    :matchers
    {:expect-graph        (on-value (fn [graph want _] (submap-failures "graph" want graph)))
     :expect-nodes        (on-value
                            (fn [graph want _]
                              (mapcat (fn [{:keys [id] :as expected}]
                                        (if-let [node (get (:nodes graph) id)]
                                          (submap-failures (str "node " id) (dissoc expected :id) node)
                                          [(str "node " id " is absent")]))
                                      want)))
     :expect-edges        (on-value (fn [graph want _]
                                      (member-failures "the edges" (set (:edges graph)) want true)))
     :expect-absent-edges (on-value (fn [graph want _]
                                      (member-failures "the edges" (set (:edges graph)) want false)))}}})

(def sibling-graded
  "Call-record expectations this runner does not grade, because every
  fixture carrying one claims a capability in `known-skipped-capabilities`:
  `[op key]` → the family runner that grades them and the filename prefix of
  the fixtures it loads. `re-frame.conformance-test` proves every fixture
  carrying one has that prefix."
  {[:render-tree-hash :expect] {:runner 're-frame.ssr-conformance-test :prefix "ssr-"}})

(defn- describe-call
  "The record's inputs — everything but `:call` and its expectation keys —
  cut short for a failure line."
  [call matchers]
  (let [s (pr-str (into {} (remove (fn [[k _]] (or (= :call k) (contains? matchers k)))) call))]
    (if (< 160 (count s)) (str (subs s 0 160) "…") s)))

(defn run-call
  "Run one `:fixture/calls` record and grade it through `call-matchers`.
  Returns `{:passed? bool :detail …}`. `fixture-machines` is the fixture's
  realised `{:actions … :guards …}` map, built once by `run-fixture`.

  The operation runs once, and each expectation key the record carries is
  graded by its op's matcher. A throw fails the record unless the record
  carries `:expect-error`, which is the `:thrown-error-id` law's other half:
  a record that omits `:expect-error` asserts the operation does not throw.
  An op with no entry FAILS."
  [call & [fixture-machines]]
  (let [op (:call call)]
    (if-let [{:keys [observe holds matchers]} (get call-matchers op)]
      (let [outcome  (try {:value (observe call fixture-machines)}
                          (catch #?(:clj Throwable :cljs :default) e {:thrown e}))
            failures (concat
                       (when (and (contains? outcome :thrown) (not (contains? call :expect-error)))
                         [(str "threw: " (ex-message (:thrown outcome)))])
                       (when (and holds (contains? outcome :value))
                         (holds (:value outcome) call))
                       (mapcat (fn [[k match]]
                                 (when (contains? call k)
                                   (map #(str k " " %) (match outcome (get call k) call))))
                               matchers))]
        {:passed? (empty? failures)
         :detail  (when (seq failures)
                    (str (name op) " " (describe-call call matchers) "\n    "
                         (str/join "\n    " failures)))})
      {:passed? false
       :detail  (str "unknown :call form: " op
                     (some (fn [[[sibling-op _] {:keys [runner]}]]
                             (when (= sibling-op op) (str " (graded by " runner ")")))
                           sibling-graded))})))

;; ---- fixture execution ----------------------------------------------------

;; ---- `:fixture/clock` — a controlled host clock ---------------------------
;;
;; Spec 005 §Clock abstraction names ONE host seam for delayed work:
;; `re-frame.interop/schedule-after!` and `cancel-scheduled!`. A fixture that
;; declares `:fixture/clock` runs its dispatches with that seam replaced by a
;; RECORDING realisation, so the harness can observe how many timers the host
;; armed and still holds, and can fire one on demand instead of waiting out
;; wall time.
;;
;; Everything the steps grade is host-observable AT THE SPEC'S OWN CLOCK
;; BOUNDARY — no port-internal timer table is read, so a port that arms
;; through a different internal seam conforms so long as it arms through the
;; clock primitive Spec 005 names.
;;
;; LIVE, not merely ARMED. An arm records a handle; a `cancel-scheduled!` of
;; that handle marks it dead. `:assert-timers :live` therefore counts what the
;; host is still HOLDING, which is what "arms exactly one timer per active
;; declaration" means — a host that arms and then cancels holds none.
;;
;; The affordance is general: any fixture whose contract is about delayed host
;; work may declare it. It is engaged ONLY for a fixture carrying the key, so
;; every other fixture keeps the real host clock.

(defn- new-clock
  "Fresh recording-clock state. `:timers` is arm-ordered; each entry is
  `{:id n :delay ms :thunk f :cancelled? bool}`."
  []
  (atom {:next-id 0 :timers []}))

(defn- clock-arm-fn
  "The recording `schedule-after!`. Records the arm and returns an opaque
  handle `clock-cancel-fn` recognises."
  [clock]
  (fn [thunk delay-ms]
    (let [state (swap! clock (fn [c]
                               (-> c
                                   (update :next-id inc)
                                   (update :timers conj
                                           {:id         (:next-id c)
                                            :delay      delay-ms
                                            :thunk      thunk
                                            :cancelled? false}))))]
      [::clock-handle (:id (peek (:timers state)))])))

(defn- clock-cancel-fn
  "The recording `cancel-scheduled!`. Marks the named arm dead. A handle this
  clock did not issue is ignored, exactly as the real host primitives ignore
  a handle of the wrong kind."
  [clock]
  (fn [handle]
    (when (and (vector? handle) (= ::clock-handle (first handle)))
      (let [id (second handle)]
        (swap! clock update :timers
               (fn [ts]
                 (mapv #(if (= id (:id %)) (assoc % :cancelled? true) %) ts)))))
    nil))

(defn- clock-live-timers
  "The timers the host armed and has NOT cancelled, in arm order."
  [clock]
  (into [] (remove :cancelled?) (:timers @clock)))

(defn- run-clock-step!
  "Execute ONE `:fixture/clock` step. Returns a failure string, or nil on
  success. Three steps, each orthogonal:

    `{:step :assert-timers :live N :delays [ms …]}`
        the host holds exactly N live host-clock timers, armed for exactly
        those delays in arm order. Either key may be omitted.

    `{:step :assert-runtime-db :expect {…} :frame <id>}`
        a MID-RUN runtime-db assertion (submap, like `:final-runtime-db`), so
        a fixture can pin the state before a timer expires as well as after.
        `:frame` defaults to `:rf/default`.

    `{:step :fire :index i}`
        invoke the i-th LIVE timer's host callback. The callback dispatches
        through the ASYNC router, whose drain is scheduled on `next-tick`;
        collapsing that to an inline call is the established seam for
        observing an async drain deterministically on both hosts."
  [step clock]
  (case (:step step)
    :assert-timers
    (let [all    (:timers @clock)
          live   (clock-live-timers clock)
          want-n (:live step)
          want-d (:delays step)]
      (cond
        (and want-n (not= want-n (count live)))
        (str ":assert-timers expected " want-n " live host-clock timer(s); the host holds "
             (count live) " (armed " (count all) ", cancelled "
             (count (filter :cancelled? all)) ")")

        (and want-d (not= (vec want-d) (mapv :delay live)))
        (str ":assert-timers expected live delays " (pr-str (vec want-d))
             "; the host holds " (pr-str (mapv :delay live)))))

    :assert-runtime-db
    (let [frame-id (:frame step :rf/default)
          expected (:expect step)
          actual   (:rf.db/runtime (rf/frame-state-value frame-id))]
      (when-not (rf.conformance/submap? expected actual)
        (str ":assert-runtime-db on frame " frame-id " expected " (pr-str expected)
             " to be a submap of " (pr-str actual))))

    :fire
    (let [idx  (:index step 0)
          live (clock-live-timers clock)]
      (if-let [timer (get live idx)]
        (do (with-redefs [rf.interop/next-tick (fn [f] (f) nil)]
              ((:thunk timer)))
            nil)
        (str ":fire index " idx " but the host holds only " (count live)
             " live host-clock timer(s)")))

    (str "unknown :fixture/clock step " (pr-str (:step step)))))

(defn- run-clock-steps!
  "Run the fixture's `:fixture/clock` steps in order, stopping at the first
  failure. Throws so `run-fixture`'s catch reports it as a fixture failure,
  matching how `:fixture/calls` failures surface."
  [steps clock]
  (when-let [failure (some #(run-clock-step! % clock) steps)]
    (throw (ex-info (str "clock step failed: " failure) {:clock-failure failure}))))

(defn- dispatch-fixture-event!
  "Execute ONE `:fixture/dispatches` entry. Extracted from `run-fixture` so the
  dispatch phase can be run inside the `:fixture/clock` redefs
  without duplicating it. Must run inside the fixture's established frame
  scope."
  [ev sub-registry dispatch-error-failures]
  (cond
    (map? ev)
    (cond
      ;; Harness teardown `{:destroy-frame <frame-id>}`.
      (contains? ev :destroy-frame)
      (rf/destroy-frame! (:destroy-frame ev))

      ;; `{:queue [<event-vec> …]}` — queue each event with the ordinary
      ;; async `dispatch` while the scheduler records the drain instead of
      ;; running it, then run that drain once. Every event after the first
      ;; is external input waiting behind a running family, which no
      ;; `dispatch-sync` entry can produce (Spec 002 §Run-to-completion).
      (contains? ev :queue)
      (let [drains (atom [])]
        (with-redefs [rf.interop/next-tick (fn [f] (swap! drains conj f) nil)]
          (doseq [event (:queue ev)]
            (rf/dispatch event)))
        (doseq [drain @drains]
          (drain)))

      ;; Harness re-registration `{:reg-sub <sub-id> :body <body>}`
      ;; (Cross-Spec Interaction §18). The realised sub's
      ;; :kind MUST drive the registration form.
      (contains? ev :reg-sub)
      (let [sub-id (:reg-sub ev)
            steps  (:body ev)
            {:keys [kind inputs body]} (rf.conformance/realise-sub steps)
            sub-meta (get sub-registry sub-id {})]
        (case kind
          :layer-1 (if (seq sub-meta)
                     (rf.subs/reg-sub sub-id sub-meta body)
                     (rf.subs/reg-sub sub-id body))
          :runtime-db (if (seq sub-meta)
                        (rf.subs/reg-runtime-sub sub-id sub-meta body)
                        (rf.subs/reg-runtime-sub sub-id body))
          :layer-2 (rf.subs/reg-sub
                     sub-id (assoc sub-meta :inputs (vec inputs)) body)))

      ;; EP-0017: a dispatch asserting a boundary /
      ;; context-assembly THROW. The throw escapes `dispatch-sync`, so
      ;; catch it here and compare the ex-data `:rf.error/id`.
      (contains? ev :expect-error)
      (let [{event :event want :expect-error} ev
            opts (dissoc ev :event :expect-error)
            got  (try (rf/dispatch-sync event opts) ::no-throw
                      (catch #?(:clj Throwable :cljs :default) e
                        (or (:rf.error/id (ex-data e)) e)))]
        (cond
          (= got ::no-throw)
          (swap! dispatch-error-failures conj
                 (str "dispatch " (pr-str event) " expected to throw "
                      want " but did not throw"))
          (not= got want)
          (swap! dispatch-error-failures conj
                 (str "dispatch " (pr-str event) " expected error " want
                      " but got " (if (keyword? got)
                                    got
                                    (str "a non-rf.error/id throw: "
                                         (some-> got ex-message)))))))

      :else
      (let [{event :event :as opts} ev]
        (rf/dispatch-sync event (dissoc opts :event))))

    ;; :rf/hydrate dispatches with :source :ssr-hydration (Spec 011).
    (and (vector? ev) (= :rf/hydrate (first ev)))
    (rf/dispatch-sync ev {:source :ssr-hydration})

    :else
    (rf/dispatch-sync ev)))

(defn run-fixture
  "Realise, dispatch, and grade one fixture against `:fixture/expect`. `host`
  supplies the genuinely host-specific seams (`:reset-runtime!`,
  `:register-trace-listener!`, `:unregister-trace-listener!`). Returns a
  result map with `:passed?`, the `:expect-failures` `grade-expect` reports,
  and the `:dispatch-error-failures` and `:compute-sub-failures`."
  [fixture host]
  (try
    ((:reset-runtime! host))
    (let [fid          (:fixture/id fixture)
          ;; Register the trace listener FIRST so registration-time warnings
          ;; (e.g. route-shadowed-by-equal-score) are captured.
          traces       (collect-traces host fid)
          ;; Capture the always-on error-emit substrate's tight
          ;; error-records in parallel with the trace listener.
          err-records  (collect-error-emit-records! fid)
          _            (realise-handlers fixture)
          _            (register-routes! fixture)
          ;; Resources register before make-frame / dispatches.
          _            (register-resources! fixture)
          ;; `:fixture/runtime :platform` declares the simulated host platform.
          ;; On CLJS the default `interop/active-platform` is `:client`, so
          ;; honouring the fixture value is load-bearing for parity; on JVM the
          ;; default is already `:server` (the corpus's only such fixture),
          ;; so the merge is a no-op there.
          runtime-platform (get-in fixture [:fixture/runtime :platform])
          frame-config (cond-> (or (:fixture/frame-config fixture) {})
                         (and runtime-platform
                              (not (contains? (:fixture/frame-config fixture) :platform)))
                         (assoc :platform runtime-platform))
          frames-spec  (:fixture/frames fixture)
          ;; EP-0002 — the carried-invariant: registration-time
          ;; frame-local surfaces and bare `dispatch-sync` resolve their target
          ;; from the established frame scope. Single-frame fixtures use
          ;; :rf/default; multi-frame fixtures carry explicit :frame opts.
          scope-frame  (if (seq frames-spec)
                         (:id (first frames-spec))
                         :rf/default)
          ;; reset-runtime! created :rf/default WITHOUT :initial-events;
          ;; destroy first so they fire on re-registration with the config.
          _            (rf/destroy-frame! :rf/default)
          ;; app-schema registrations — AFTER destroy (the destroy hook wipes
          ;; the frame's schema entries) and BEFORE make-frame (so the
          ;; :initial-events cascade validates the seeded state).
          _            (rf/with-frame scope-frame
                         (realise-app-schemas! fixture))
          _            (cond
                         (seq frames-spec)
                         (doseq [f frames-spec]
                           (rf/make-frame f))
                         :else
                         (rf/make-frame (assoc frame-config :id :rf/default)))
          ;; Data-classification commit-plane effects run AFTER make-frame and
          ;; BEFORE realise-flows!.
          _            (realise-classification-effects! fixture scope-frame)
          ;; Flow registration runs AFTER make-frame (frame-scoped teardown).
          _            (rf/with-frame scope-frame
                         (realise-flows! fixture))
          dispatches   (or (:fixture/dispatches fixture) [])
          sub-registry (get-in fixture [:fixture/registry :sub] {})
          ;; EP-0017: per-dispatch `:expect-error` assertions.
          dispatch-error-failures (atom [])
          ;; `:fixture/clock`. Present ⇒ the dispatch phase runs
          ;; with the Spec 005 clock primitives recording rather than really
          ;; scheduling, and the declared steps run immediately after it,
          ;; still inside those redefs (a fired callback releases its own
          ;; handle through `cancel-scheduled!`).
          clock-steps  (:fixture/clock fixture)
          clock        (when clock-steps (new-clock))
          run-dispatches!
          (fn []
            (rf/with-frame scope-frame
              (doseq [ev dispatches]
                (dispatch-fixture-event! ev sub-registry dispatch-error-failures))))]
      (if clock
        (with-redefs [rf.interop/schedule-after!   (clock-arm-fn clock)
                      rf.interop/cancel-scheduled! (clock-cancel-fn clock)]
          (run-dispatches!)
          (rf/with-frame scope-frame
            (run-clock-steps! clock-steps clock)))
        (run-dispatches!))
      ;; :fixture/render-after-hydrate — simulate the client-side first render
      ;; so `verify-hydration!` can compare hashes.
      (when-let [render-spec (:fixture/render-after-hydrate fixture)]
        (let [client-hash     (:simulated-client-render-hash render-spec)
              first-diff-path (:first-diff-path render-spec)
              hydrate-ev      (some (fn [e]
                                      (when (and (vector? e) (= :rf/hydrate (first e))) e))
                                    dispatches)
              payload         (when hydrate-ev (second hydrate-ev))
              server-hash     (:rf/render-hash payload)
              frame-id        (:rf/frame-id payload :rf/default)]
          (when (and client-hash server-hash)
            (rf.ssr/verify-hydration! frame-id client-hash
                                   {:first-diff-path first-diff-path
                                    :server-hash     server-hash}))))
      ;; :fixture/calls — pure-function assertions, run AFTER dispatches.
      (let [machines      (realise-machine-handlers fixture)
            calls         (or (:fixture/calls fixture) [])
            call-results  (mapv #(run-call % machines) calls)
            call-failures (filter (complement :passed?) call-results)]
        (when (seq call-failures)
          (throw (ex-info (str "calls failed: "
                               (str/join "; " (map :detail call-failures)))
                          {:call-failures call-failures}))))
      ;; Drain any pending error projections so :rf/response carries the
      ;; projector's :status before anything is graded.
      (doseq [fid (rf.frame/frame-ids)]
        (try (rf.ssr/apply-error-projection! fid)
             (catch #?(:clj Throwable :cljs :default) _ nil)))
      (let [;; `:fixture/compute-subs` — invoke each query before grading, so
            ;; a sub that throws surfaces as the sub-exception trace the
            ;; trace-emissions check grades. A query naming no registered
            ;; sub has nothing to compute, so it fails the fixture.
            compute-sub-failures
            (vec
              (keep (fn [query-v]
                      (if (rf.registrar/lookup :sub (first query-v))
                        (do (rf/subscribe-once query-v {:frame :rf/default}) nil)
                        (str ":fixture/compute-subs query " (pr-str query-v)
                             " names no registered sub")))
                    (:fixture/compute-subs fixture)))
            run {:traces         traces
                 :err-records    err-records
                 ;; The runner's own destroy of the fixture's frame. Only the
                 ;; `:on-destroy-*` matchers open it, and they grade last.
                 :destroy-traces (delay (let [before (count @traces)]
                                          (rf/destroy-frame! scope-frame)
                                          (subvec @traces before)))}
            expect-failures (grade-expect run (or (:fixture/expect fixture) {}))]
        ;; Drop just this fixture's trace listener (host-specific registry).
        ((:unregister-trace-listener! host) fid)
        ;; Drop just this fixture's error-emit recorder.
        (rf.error-emit/unregister-error-listener! [fid ::records])
        {:fixture-id              fid
         :passed?                 (and (empty? expect-failures)
                                       (empty? @dispatch-error-failures)
                                       (empty? compute-sub-failures))
         :expect-failures         expect-failures
         :dispatch-error-failures @dispatch-error-failures
         :compute-sub-failures    compute-sub-failures}))
    (catch #?(:clj Throwable :cljs :default) e
      {:fixture-id (:fixture/id fixture)
       :passed?    false
       :error      (ex-message e)
       :exception  e})))

;; ---- the corpus body ------------------------------------------------------

(defn- print-failures [label all run passed failed skipped]
  (println)
  (println (str "Conformance corpus (" label "):"))
  (println "  total fixtures:" (count all))
  (println "  runnable:      " (count run))
  (println "  passed:        " (count passed))
  (println "  failed:        " (count failed))
  (println "  skipped:       " (count skipped))
  (when (seq skipped)
    (println)
    (println "Skipped (out-of-claim):")
    (doseq [s skipped]
      (println "  " (:fixture-id s) "—"
               (or (:capabilities s) (:spec-version s) (:reason s)))))
  (println)
  (println "Failures:")
  (doseq [f failed]
    (println "  " (:fixture-id f))
    (when (:unknown-caps f)
      (println "    unknown capabilities:" (:unknown-caps f)))
    (when (:error f)
      (println "    error:" (:error f)))
    (doseq [de (:dispatch-error-failures f)]
      (println "    expect-error:" de))
    (doseq [cf (:compute-sub-failures f)]
      (println "    compute-subs:" cf))
    (doseq [[k failures] (:expect-failures f)
            failure      failures]
      (println (str "    " k ": " failure)))))

(defn run-corpus-fixture
  "The corpus verdict for one `[fname fixture]` entry: a skip for an
  unclaimed spec version or an allowlisted capability; a failure
  for an unknown capability; otherwise `run-fixture`'s result."
  [fname fixture host]
  (cond
    (not (spec-version-claimed? fixture))
    {:fixture-id   (:fixture/id fixture)
     :skipped?     true
     :reason       "spec-version not in claimed set"
     :spec-version (:fixture/spec-version fixture)}

    :else
    (let [{:keys [allowed unknown]} (classify-capabilities fixture)]
      (cond
        (seq unknown)
        {:fixture-id   (:fixture/id fixture)
         :passed?      false
         :unknown-caps unknown
         :error        (str "unknown capabilities: " unknown
                            " — capability is neither in "
                            "claimed-capabilities nor in "
                            "known-skipped-capabilities. Either claim "
                            "it (and implement it) or add to the "
                            "known-skipped-capabilities allowlist.")}

        (seq allowed)
        {:fixture-id   (:fixture/id fixture)
         :skipped?     true
         :reason       "capabilities intentionally not claimed (allowlisted)"
         :capabilities (:fixture/capabilities fixture)
         :allowed      allowed}

        :else
        (assoc (run-fixture fixture host) :fname fname)))))

(defn run-corpus
  "Run the whole conformance corpus. `fixtures` is a seq of `[filename
  fixture-map]` pairs (loaded host-specifically); `host` supplies the reset /
  trace-listener seams; `label` names the host in diagnostics (\"JVM\" /
  \"CLJS\"); `floor` is the fewest fixtures that must execute, 150 for the
  whole corpus. Grades each fixture through `run-corpus-fixture`, then emits
  the pass / floor / count `is` assertions. Silent on green: the failure
  report only prints when there are failures."
  ([fixtures host label]
   (run-corpus fixtures host label 150))
  ([fixtures host label floor]
   (let [all     (mapv (fn [[fname fixture]] (run-corpus-fixture fname fixture host))
                       fixtures)
         run     (filter (complement :skipped?) all)
         passed  (filter :passed? run)
         failed  (remove :passed? run)
         skipped (filter :skipped? all)]
     ;; Non-empty floor. The lone (zero? (count failed)) below
     ;; passes GREEN over an empty / fully-skipped / orphaned corpus,
     ;; verifying NOTHING. Assert fixtures actually executed.
     (is (pos? (count run))
         (str "at least one claim-applicable conformance fixture must have executed (" label ")"))
     (is (>= (count run) floor)
         (str label " conformance corpus runnable-fixture floor (>= " floor "): only "
              (count run) " executed — a fixture-discovery fault or a "
              "capability-vocab rename has orphaned the corpus."))
     (when (seq failed)
       (print-failures label all run passed failed skipped))
     ;; The suite fails unless EVERY claimed-applicable fixture
     ;; passes. Skipped fixtures neither claim conformance nor block it.
     (is (zero? (count failed))
         (str "All claimed-applicable " label " conformance fixtures must pass; "
              (count failed) " failed.")))))
