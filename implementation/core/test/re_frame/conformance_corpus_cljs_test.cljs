(ns re-frame.conformance-corpus-cljs-test
  "CLJS port of the JVM conformance corpus runner (per rf2-3oi9x).

  The JVM runner (`re-frame.conformance-test`) walks 111 EDN fixtures
  under `spec/conformance/fixtures/` and validates each against the
  re-frame2 runtime — bootstrapping the registrar, realising handler
  bodies via the `re-frame.conformance` DSL interpreter, dispatching
  events, and comparing observables. Until this port the corpus was
  validated against ONE host (JVM); per the sweep-test-coverage-rigour
  finding the CLJS counterpart (`conformance_dsl_cljs_test`) only
  tested the DSL's `resolve-value*` micro-shape. This file closes that
  gap: every claimed-applicable fixture must pass on CLJS too — the
  conformance corpus's portability story finally has two hosts behind
  it.

  ## Shape

  The DSL interpreter is `.cljc` (`re-frame.conformance`) so the heavy
  lifting (realising handler bodies, evaluating reflection forms,
  builtins) is shared with the JVM runner. The CLJS-specific seams
  this file owns are:

  - Loading fixtures: at compile time via the `conformance-fixtures`
    macro ns. The .edn files are inlined into the CLJS bytecode; no
    runtime fs.
  - Reset: snapshot/restore the registrar between fixtures rather
    than `registrar/clear-all!` + `:reload`. CLJS has no
    `(require :reload)` analogue, so wiping the registrar would
    permanently lose framework registrations (per
    `re-frame.test-support`'s rf2-am9d rationale).
  - Exception handling: `:default` rather than `Throwable`;
    `ex-message` rather than `(.getMessage e)`.
  - Adapter: plain-atom (the same one the JVM runner uses, so the
    fixture run isolates CLJS-vs-JVM language differences rather
    than substrate differences). A Reagent-substrate variant is a
    separate follow-up bead.

  ## What still applies from the JVM runner

    - The claimed-capability and claimed-spec-version sets are
      identical — this build claims the same surface as the JVM
      build.
    - The submap / trace-emissions / effects-routed matchers are
      ported byte-for-byte. Their semantics are spec-defined
      (conformance/README.md §Fixture lifecycle) and host-agnostic.
    - The cofx-key auto-injection convention (rf2-g25p), the
      machine-handler realisation (rf2-msd4), and the realise-fx
      adapter-helpers wiring are all preserved."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            ;; EP-0025: durable app-db classification is the four commit-plane
            ;; effects; the corpus `:fixture/classification-effects` ops install
            ;; frame app-db classification directly into the elision registry.
            [re-frame.elision :as elision]
            ;; rf2-wxe9t — the always-on error-emit substrate is the
            ;; fan-out path the conformance runner observes for the
            ;; `:error-emit-records` expectation. Mirror of the JVM
            ;; runner's require.
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.schemas :as schemas]
            [re-frame.subs :as subs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.trace :as trace]
            [re-frame.conformance :as conformance]
            ;; EP-0012 (rf2-qyb9l1) — CEDN-1 canonical-identity + `:rf/path`
            ;; algebra foundation, for the `:canonical-bytes` /
            ;; `:canonical-identical` / `:canonical-distinct` /
            ;; `:path-instantiate` call ops. Mirror of the JVM runner.
            [re-frame.identity :as identity]
            [re-frame.path :as path]
            ;; EP-0015 (rf2-t55hxg.2) — the three pure data-classification
            ;; `:call` ops mirror the JVM runner: the centralised egress
            ;; projector (`project-egress`), the HTTP header carrier denylist,
            ;; and the SSR hydration-payload allowlist. All pure fns.
            [re-frame.projection :as projection]
            [re-frame.http.privacy-headers :as http-privacy-headers]
            [re-frame.ssr.payload-policy :as ssr-payload-policy]
            [re-frame.routing :as routing]
            ;; rf2-dbiv8 — the test-only `:rf.test/simulate-http-resolution`
            ;; fixture event moved out of the `re-frame.routing` production
            ;; façade to this test-support ns. The routing/stale-nav-token-
            ;; suppression fixture dispatches it; require here so it
            ;; registers at ns-load (CLJS has no `:reload`, so it must be
            ;; live before `pretest-registrar` snapshots it).
            [re-frame.routing.test-support]
            [re-frame.ssr :as ssr]
            [re-frame.machines :as machines]
            ;; Spec 014 — :rf.http/managed registers at ns-load time. The
            ;; fixture corpus references the fx (often via :fx-overrides
            ;; redirecting to its canned stubs); requiring here gives the
            ;; runner access to the fx without each fixture re-registering
            ;; it itself.
            [re-frame.http.managed :as http-managed]
            ;; rf2-cdmle — canned-stub fxs (`:rf.http/managed-canned-success`,
            ;; `:rf.http/managed-canned-failure`) gate on explicit
            ;; test-support require. Fixtures use them by id via
            ;; :fx-overrides; opt in here so they register before any
            ;; fixture runs.
            [re-frame.http.test-support]
            ;; EP-0014 (rf2-k0meap.3) — the `:derivation-graph` call op pins
            ;; the cross-family derivation/process graph (lowering /
            ;; classification / edge roles / parametric markers / refinement)
            ;; in the host-agnostic corpus. The composer + the loaded tooling
            ;; siblings it composes over (subs is in-core; machines is loaded
            ;; above) build the contributor map for the assertion.
            [re-frame.derivation.graph :as dgraph]
            [re-frame.subs.tooling :as subs-tooling]
            [re-frame.machines.tooling :as machines-tooling]
            ;; rf2-djofbh — the FULL contributor set + the resources façade so
            ;; the corpus's `:derivation-graph` op composes the whole EP-0014
            ;; surface (flows / resources / routes), not just subs+machines,
            ;; and `reg-resource` is available for fixture resource
            ;; registration. A family whose fixture registers nothing
            ;; contributes no nodes (present-family-only), so the subs+machines
            ;; subset fixture is unaffected. Test-only requires — these never
            ;; leak into a production bundle.
            [re-frame.resources :as resources]
            [re-frame.flows.tooling :as flows-tooling]
            [re-frame.resources.tooling :as resources-tooling]
            [re-frame.routing.tooling :as routing-tooling]
            ;; EP-0026 (rf2-qp8qi8) — the image-API conformance ops. `re-frame.image`
            ;; (the `rf/image` constructor) + `re-frame.image-assembly` (the
            ;; explicit-pool assembler) back the Mode-B `:assemble-image` call op.
            ;; CLJS has no `:reload`, so requiring at ns-load is enough — the four
            ;; inline-lowering hooks publish at ns-load via events/subs/fx/cofx,
            ;; already on the require graph through `re-frame.core`. Mirror of the
            ;; JVM runner's requires.
            [re-frame.image :as image]
            [re-frame.image-assembly :as image-assembly])
  ;; Compile-time fixture inlining (see conformance_fixtures.clj). The
  ;; macro ns is .clj — shadow-cljs picks it up via :require-macros
  ;; (a CLJS-only form; cannot live in the top-level :require above).
  (:require-macros [re-frame.conformance-fixtures :refer [all-fixtures]]))

;; ---- claimed capability set -----------------------------------------------

(def claimed-capabilities
  "What this CLJS build claims to support. Matches the JVM runner's
  claimed set; the corpus is graded against capabilities, not host."
  #{:core/event-handler
    :core/sub
    :core/fx
    :core/error
    ;; :core/trace + :core/frame — rf2-3pnob. See JVM runner for rationale.
    :core/trace
    :core/frame
    ;; EP-0026 (rf2-qp8qi8) — the image-API surface. Matches the JVM runner's
    ;; claim so the `image-*.edn` fixtures run on CLJS too via `:assemble-image`.
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
    :fsm/final-states
    :fsm/history                                      ;; rf2-mle6e — first-class history pseudo-states (:type :history)
    :fsm/registration-validation                      ;; rf2-vf5cf — registration-error taxonomy via :reg-machine
    :routing/match-url
    :ssr/render-to-string
    :ssr/hydration
    :ssr/response-contract
    :ssr/head-contract
    :ssr/error-projection
    :schemas/runtime
    :schemas/event-payload
    :schemas/sub-return
    ;; :schemas/cofx — CLAIMED (rf2-hqwki4): the landed EP-0017 recordable-cofx
    ;; `:schema` path. A declared recordable value that fails its
    ;; registration's `:schema` emits `:rf.error/cofx-value-invalid` and throws
    ;; during context assembly; the `schema-cofx-validates.edn` fixture
    ;; exercises it (the old inject-cofx-time validation is retired).
    :schemas/cofx
    :routing/ranking
    :routing/fragment
    :routing/blocking
    :routing/nav-token
    ;; EP-0012 (rf2-qyb9l1) — CEDN-1 canonical-identity + `:rf/path` algebra
    ;; foundation. Matches the JVM runner's claim so
    ;; `cedn1-path-algebra-golden.edn` runs on CLJS too — the frozen
    ;; cross-host byte-contract.
    :identity/cedn1
    :actor/spawn-destroy   ;; rf2-mtq4h — renamed from :actor/spawn to align with spec vocabulary
    :actor/declarative-spawn
    :actor/spawn-and-join
    :actor/system-id
    ;; :actor/timeout retired per rf2-3y3y — :fsm/delayed-after subsumes
    ;; it. The state-level :after primitive covers wall-clock-timeout
    ;; semantics for both pure timed-transition states and :spawn-bearing
    ;; states; the after-*.edn fixtures (after-single-delay, after-hierarchy,
    ;; after-stale-detection, parallel-after-scoped-to-region) exercise the
    ;; canonical primitive. See [spec/005-StateMachines.md §Capability matrix]
    ;; and [migration/from-re-frame-v1/README.md §M-44].
    :flow/basic
    :flow/topo
    :flow/dirty-check
    :flow/toggle
    :flow/hot-reload
    ;; Spec 009 §Flow trace events / Spec 013 §Flow tracing (rf2-2s1o) —
    ;; the runtime emits the :rf.flow/* lifecycle events. Claimed so
    ;; `flow-lifecycle-emits-traces.edn` runs on CLJS too (rf2-efjs6).
    :flow/trace
    ;; Spec 013 §Frame-scoping — same flow-id against two frames yields
    ;; two independent definitions; clear-flow is frame-local; sibling
    ;; frames' flows do not walk on cross-frame dispatches. Claimed so
    ;; `flow-frame-scoped.edn` runs on CLJS too (rf2-29ovh).
    :flow/frame-scoped
    :rf.http/managed
    ;; Spec 015 §Data classification (rf2-s2s3xv) — the `data-classification-`
    ;; fixture category exercises commit-plane classification-effect redaction
    ;; through the t2 pending-db trace egress (the `:sensitive` / `:large` /
    ;; `:clear-sensitive` / `:clear-large` declarations installed via
    ;; `:fixture/classification-effects`). Matches the JVM runner's claim.
    :data-classification/classification-effects
    ;; EP-0014 (rf2-k0meap.3) — the cross-family derivation/process graph
    ;; (lowering / classification / edge roles / parametric markers /
    ;; :machine-selector refinement) via the `:derivation-graph` call op.
    ;; The BROAD claim (derivation-graph-algebra-full.edn — flows / resources
    ;; / routes / route-owned activation / live mode / authority split).
    :derivation/algebra-graph
    ;; rf2-djofbh — the NARROW subs+machines subset claim
    ;; (derivation-graph-algebra.edn).
    :derivation/algebra-graph-subs-machines})

(def claimed-spec-versions
  "Fixture spec versions this CLJS build claims to conform against."
  #{"1.0"})

;; ---- known-skipped capabilities (rf2-a3q1r) ------------------------------
;;
;; A fixture declaring `:fixture/capabilities` that name a capability not in
;; `claimed-capabilities` AND not in `known-skipped-capabilities` is treated
;; as a typo / claim-set drift and FAILS the suite. The pre-rf2-a3q1r runner
;; silently skipped any out-of-claim fixture, which masked at least one bug
;; (`:flow/trace` missing from the claim-set hid `flow-lifecycle-emits-traces.edn`
;; from the suite — see the sweep-test-coverage-rigour finding).
;;
;; Adding a capability here is an explicit declaration that this build
;; INTENTIONALLY does not claim it; the corresponding fixtures are reported
;; as out-of-claim skips and do not block the suite. A capability appearing
;; in both sets is a configuration error (resolve by removing from one).
;;
;; Today this set is empty: every capability referenced by a fixture is
;; also in `claimed-capabilities`. The allowlist exists so future divergence
;; between corpus and host requires an explicit decision rather than silent
;; rot.

(def known-skipped-capabilities
  "Capabilities this build INTENTIONALLY does not claim. Fixtures whose
  capabilities fall here are reported as out-of-claim skips but do not
  block the suite."
  ;; rf2-ojakd / rf2-olb64 (a) — streaming SSR ships in the JVM-only
  ;; `re-frame.ssr.streaming` ns + `re-frame.ssr.ring.streaming` host
  ;; adapter (chunked HTTP needs Ring + a writer thread). The CLJS
  ;; conformance runner cannot exercise the wire shape; the JVM-side
  ;; `re-frame.ssr-conformance-test` + `re-frame.ssr-streaming-conformance-test`
  ;; gate the fixture. Listed here so the CLJS runner reports the
  ;; streaming fixture as an intentional out-of-claim skip rather than
  ;; failing the suite.
  #{:ssr/suspense-boundary
    :ssr/hydration-payload
    :ssr/chunked-response})

;; ---- fixture loading (compile-time inlined) -------------------------------

(def fixtures
  "Vector of `[filename fixture-map]` pairs, materialised at compile
  time by `re-frame.conformance-fixtures/all-fixtures`. Sorted by
  filename so reporting order is stable."
  (all-fixtures))

;; ---- runtime reset --------------------------------------------------------

;; ---- baseline snapshots ---------------------------------------------------
;;
;; Two snapshots, captured at DIFFERENT times — both are load-bearing.
;;
;;  * `baseline-trace-listeners` is captured at NS-LOAD. The SSR
;;    artefact registers its `error-projection-listener` at ns-load
;;    time; `test-support/make-reset-runtime-fixture` (used by many other
;;    CLJS test namespaces' `use-fixtures :each` blocks) calls
;;    `(trace/clear-listeners!)`, so by the time our deftest runs the
;;    listener registry is already empty. Capturing at ns-load is
;;    the only point at which the framework listeners are guaranteed
;;    live.
;;
;;  * `pretest-registrar` is captured at DEFTEST START (lazy). Other
;;    example apps and test namespaces (e.g. `nine-states.core`)
;;    register their handlers at ns-load. CLJS has no
;;    `(require :reload)` analogue, so wiping the registrar before
;;    them would permanently destroy those registrations and break
;;    every downstream `:each` fixture that snapshots-and-restores
;;    around its own tests. Capturing AT DEFTEST START — after all
;;    example namespaces have completed their ns-load registrations
;;    — guarantees our inter-fixture reset doesn't strand them.
;;
;; The combination is the CLJS-equivalent of the JVM runner's
;; `(registrar/clear-all!) + (require :reload)` pattern: framework
;; trace listeners survive because we captured them early; example
;; registrations survive because we captured them late.

(def ^:private baseline-trace-listeners
  ;; Per rf2-qwm0a the listener registry atom moved from
  ;; `re-frame.trace/listeners` to `re-frame.trace.tooling/listeners`
  ;; (the production-DCE split). CLJS treats `^:private` metadata as
  ;; advisory — the symbol resolves through the namespace and the
  ;; atom is reachable for tests that need to snapshot framework
  ;; listeners (the SSR error-projection listener in particular). The
  ;; JVM runner achieves the same effect implicitly via
  ;; `(require :reload)` of `re-frame.ssr`; CLJS has no analogue, so
  ;; this access is the bridge.
  @re-frame.trace.tooling/listeners)

(def ^:private pretest-registrar
  ;; Mutable cell, set on deftest entry.
  (atom nil))

(defn- reset-runtime! []
  ;; 1. Roll the registrar back to the pretest-snapshot (every
  ;;    framework AND example-app registration that existed when our
  ;;    deftest started survives; every per-fixture user-test
  ;;    registration is dropped). Then drop `:route` specifically —
  ;;    example apps (realworld, routing, todomvc) register routes at
  ;;    ns-load whose `:rf.route/rank` tuples can collide with the
  ;;    fixture's equal-score test cases (route-ranking-precedence
  ;;    asserts the `:shadowed` tag names `:rf.route/equal.first`,
  ;;    which only holds when no other route shares its structural
  ;;    rank). The JVM runner achieves the same isolation via
  ;;    `clear-all!` + `(require 're-frame.routing :reload)`; CLJS
  ;;    cannot reload, so a targeted `:route` purge is the
  ;;    equivalent path. The fixture re-registers every route it
  ;;    needs via `register-routes!`.
  (reset! registrar/kind->id->metadata @pretest-registrar)
  (registrar/clear-kind! :route)
  ;; 2. Clear per-process state held outside the registrar.
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  ;; 3. Reset id-allocators so the routing / machine fixtures see
  ;;    deterministic counters.
  (routing/reset-counters!)
  ;; rf2-oosjmh — the nav-token / pending-nav counters are host-side
  ;; transient state now (not runtime-db), so the `frames` reset above no
  ;; longer clears them; reset the host cache explicitly to keep the
  ;; nav-N / pn-N id assertions deterministic across fixtures.
  (routing/reset-nav-counters!)
  (machines/reset-timers!)
  ;; 4. Drop the in-flight HTTP request registry between fixtures.
  (http-managed/clear-all-in-flight!)
  ;; 4a. Spec 014 §Middleware (rf2-yhfgf) — drop the per-frame request-
  ;;     side interceptor chain. The atom holding the chain is `defonce`
  ;;     in `re-frame.http.middleware`; without an explicit clear, a
  ;;     fixture that registers an interceptor leaks it into the next
  ;;     fixture's chain walk.
  (http-managed/clear-all-http-interceptors!)
  ;; 5. Dispose the currently-installed adapter (if any) and re-install
  ;;    plain-atom. `init!` is idempotent and creates the :rf/default
  ;;    frame; subsequent reg-frame calls for that id update in-place.
  (substrate-adapter/dispose-adapter!)
  (rf/init! plain-atom/adapter)
  ;; 6. Restore the baseline trace-listener set. This preserves the
  ;;    SSR error-projection listener (and any other ns-load
  ;;    framework listeners) while dropping every per-fixture
  ;;    listener `collect-traces` may have registered. The JVM
  ;;    runner achieves the equivalent via `clear-listeners!` +
  ;;    `(require 're-frame.ssr :reload)`; CLJS has no `:reload`,
  ;;    so the snapshot-restore path is the only correct one.
  (reset! re-frame.trace.tooling/listeners baseline-trace-listeners)
  ;; 7. rf2-wxe9t — drop every corpus-wide error-emit listener so
  ;;    a recorder installed by `collect-error-emit-records!` for
  ;;    one fixture cannot fire against the next fixture's drains.
  ;;    The registry is a `defonce` atom inside
  ;;    `re-frame.error-emit`; without an explicit clear, the prior
  ;;    fixture's recorder stays live across fixtures.
  (error-emit/clear-error-listeners!))

;; ---- fixture execution ----------------------------------------------------

(defn- runnable?
  "True if the fixture's claimed capabilities are a subset of ours."
  [fixture]
  (let [caps (or (:fixture/capabilities fixture) #{})]
    (every? claimed-capabilities caps)))

(defn- classify-capabilities
  "Per rf2-a3q1r, partition a fixture's :fixture/capabilities into
  {:claimed   #{...}    ;; in `claimed-capabilities`
   :allowed   #{...}    ;; in `known-skipped-capabilities` but not claimed
   :unknown   #{...}}   ;; in neither — typo or claim-set drift

  A fixture is RUNNABLE iff `:unknown` and `:allowed` are both empty.
  A fixture is SKIPPED (out-of-claim) iff `:unknown` is empty and
  `:allowed` is non-empty.
  A fixture is a FAILURE iff `:unknown` is non-empty — the suite must
  fail rather than silently mask the typo."
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

(defn- spec-version-claimed?
  "True if the fixture targets a spec version this build claims.
  Per `spec/conformance/README.md` §Versioning: a fixture without an
  explicit `:fixture/spec-version` is treated as unversioned and
  accepted (legacy fixtures pre-versioning)."
  [fixture]
  (let [v (:fixture/spec-version fixture)]
    (or (nil? v) (contains? claimed-spec-versions v))))

(defn- collect-cofx-keys
  "Walk steps and pull every cofx-id referenced via [:cofx-key K].
  Returns a set of K. Used by realise-handlers to auto-wire the
  consuming event's `:rf.cofx/requires` declaration per the
  conformance-corpus convention (rf2-g25p; EP-0017 model — rf2-mrp8jg)."
  [steps]
  (let [out (atom #{})]
    ((fn walk [form]
       (cond
         (and (vector? form) (= :cofx-key (first form)))
         (swap! out conj (second form))

         (coll? form)
         (doseq [x form] (walk x))))
     steps)
    @out))

(defn- realise-cofx-supplier
  "DSL → a value-returning cofx supplier `(fn [] value)` (EP-0017 model —
  rf2-mrp8jg). The body's `:set` steps declare the value the supplier
  returns directly; the runtime delivers it FLAT under the cofx-id in the
  consuming handler's coeffects map (the ctx→ctx `inject-cofx` form is
  retired). Per rf2-g25p the `:set` value passes through `eval-value*` so
  reflection forms (`[:fn :k a b]`) still resolve; the last `:set` wins
  (single-injection convention)."
  [steps]
  (fn []
    (reduce (fn [v step]
              (case (first step)
                :set  (let [[_ _path value] step]
                        (conformance/eval-value* value {}))
                :noop v
                v))
            nil
            steps)))

;; Forward declaration — realise-machine-handlers is defined alongside
;; the :machine-transition path below.
(declare realise-machine-handlers)

(defn- realise-handlers [fixture]
  (let [handlers-map     (or (:fixture/handlers fixture) {})
        event-registry   (get-in fixture [:fixture/registry :event] {})
        sub-registry     (get-in fixture [:fixture/registry :sub] {})
        cofx-bodies      (get handlers-map :cofx)
        cofx-registry    (get-in fixture [:fixture/registry :cofx] {})
        ;; cofx that should auto-wire onto a consuming event's
        ;; `:rf.cofx/requires` declaration (EP-0017 model — rf2-mrp8jg).
        ;; Stable lex order on cofx-id so the last-write-wins outcome is
        ;; deterministic across JVM / CLJS / re-runs.
        cofx-by-key
        (->> cofx-registry
             (sort-by key)
             (group-by (fn [[cofx-id _]] (keyword (namespace cofx-id))))
             (reduce-kv (fn [acc k pairs]
                          (assoc acc k (mapv first pairs)))
                        {}))]
    ;; cofx registrations — value-returning suppliers + metadata (EP-0017
    ;; model). The supplier returns the coeffect VALUE; the runtime delivers
    ;; it flat under the cofx-id when a handler declares it via
    ;; `:rf.cofx/requires`. The `:schema` metadata still rides along (its
    ;; validation step is slice-B-built; the only fixture exercising it,
    ;; `schema-cofx-validates.edn`, is an intentional out-of-claim skip via
    ;; `known-skipped-capabilities`).
    (let [all-cofx-ids (into #{} (concat (keys cofx-bodies) (keys cofx-registry)))]
      (doseq [cofx-id all-cofx-ids]
        (let [body (get cofx-bodies cofx-id [[:noop]])
              meta (get cofx-registry cofx-id {})]
          ;; A `:provided?` cofx is a boundary-supplied fact with NO supplier —
          ;; its VALUE rides the dispatch token via `:rf.cofx`, not a generator.
          ;; Post-#4104, `reg-cofx` rejects `provided? true` + a supplier as
          ;; `:rf.error/cofx-registration-invalid`, so register without one. The
          ;; `cofx/missing-vs-unregistered` fixture relies on this: the value is
          ;; absent from the token ⇒ `missing-required-cofx` at delivery.
          (if (:provided? meta)
            (rf/reg-cofx cofx-id meta)
            (rf/reg-cofx cofx-id meta (realise-cofx-supplier body))))))
    ;; event registrations
    ;;
    ;; EP-0017 model (rf2-mrp8jg): a body that reads `[:cofx-key K]` declares
    ;; the consumed coeffect ids via the `:rf.cofx/requires` registration-
    ;; metadata key (the ctx→ctx `inject-cofx` interceptor wiring is retired).
    ;; The runtime runs each declared supplier at context assembly and delivers
    ;; its value flat under the cofx-id. `:rf.cofx/requires` is fx-only — a
    ;; body that reads any cofx routes through `realise-event-handler` to an
    ;; `:fx` handler (`needs-fx-handler?` flags `:cofx-key`), so the requires
    ;; declaration only ever lands on an `:fx`-shaped reg-event handler.
    (doseq [[id steps] (get handlers-map :event)]
      (let [[kind handler] (conformance/realise-event-handler steps)
            ks             (collect-cofx-keys steps)
            cofx-ids       (vec
                             (mapcat (fn [k]
                                       (or (get cofx-by-key k)
                                           (when (contains? cofx-registry k) [k])))
                                     ks))
            event-meta     (cond-> (get event-registry id {})
                             (seq cofx-ids) (assoc :rf.cofx/requires cofx-ids))]
        (case kind
          ;; EP-0018 Slice Z: one public `reg-event` (cofx-in, effects-map-out).
          ;; A :db-kind fixture handler is `(fn [db event] new-db)`; adapt it to
          ;; the single form by reading db from the coeffects and lowering the
          ;; returned db into a `{:db …}` effect — same observable behaviour.
          :db (let [h (fn [{:keys [db]} ev] {:db (handler db ev)})]
                (if (seq event-meta)
                  (rf/reg-event id event-meta h)
                  (rf/reg-event id h)))
          :fx (if (seq event-meta)
                (rf/reg-event id event-meta handler)
                (rf/reg-event id handler)))))
    ;; sub registrations
    (doseq [[id steps] (get handlers-map :sub)]
      (let [{:keys [kind inputs body]} (conformance/realise-sub steps)
            sub-meta                   (get sub-registry id {})]
        (case kind
          :layer-1 (if (seq sub-meta)
                     (subs/reg-sub id sub-meta body)
                     (subs/reg-sub id body))
          ;; EP-0001 (rf2-vzld77): a `[:get [:rf.runtime/… …]]` fixture sub
          ;; reads the runtime-db partition — register via reg-runtime-sub.
          :runtime-db (if (seq sub-meta)
                        (subs/reg-runtime-sub id sub-meta body)
                        (subs/reg-runtime-sub id body))
          ;; Use subs/reg-sub (the fn-form) here because rf/reg-sub is a
          ;; macro and macros aren't first-class values for `apply`.
          :layer-2 (apply subs/reg-sub id
                          (concat (when (seq sub-meta) [sub-meta])
                                  (interleave (repeat :<-) inputs)
                                  [body])))))
    ;; fx handlers (bodies + meta).
    ;;
    ;; Per rf2-yhfgf — an id with NO body in :fixture/handlers but a meta
    ;; in :fixture/registry is "declare the dependency, leave the framework
    ;; registration alone": the harness DOES NOT overwrite the framework-
    ;; shipped fx with a noop. Mirrors the JVM runner's contract.
    (let [adapter-helpers
          {:read-db!  (fn [frame-id]
                        (frame/frame-app-db-value frame-id))
           ;; EP-0001 (rf2-adwcv6): write the app-db PARTITION via
           ;; `swap-frame-db!` — `frame/app-db-container` is now a READ-ONLY
           ;; projection over the one physical frame-state container.
           :write-db! (fn [frame-id new-db]
                        (frame/swap-frame-db! frame-id (constantly new-db)))
           :dispatch! (fn [event frame-id]
                        (rf/dispatch event {:frame frame-id}))
           ;; Per Cross-Spec Interaction §14 (rf2-60szl): dispatch-sync
           ;; from an fx handler body trips the router's in-drain guard.
           :dispatch-sync! (fn [event frame-id]
                             (rf/dispatch-sync event {:frame frame-id}))
           ;; Per EP-0027 §Handler-time guard (rf2-emqiqk): reg-frame /
           ;; reset-frame! invoked from an fx body (mid-cascade, *handler-scope*
           ;; bound) trips the construction / reset guard. The
           ;; [:reg-frame-capture …] / [:reset-frame-capture …] fx-body ops
           ;; call these and capture the thrown :rf.error/id into app-db.
           :reg-frame! (fn [frame-id config]
                         (rf/reg-frame frame-id config))
           :reset-frame! (fn [frame-id]
                           (frame/reset-frame! frame-id))}
          fx-bodies   (get handlers-map :fx)
          fx-registry (get-in fixture [:fixture/registry :fx] {})
          all-fx-ids  (into #{} (concat (keys fx-bodies) (keys fx-registry)))]
      (doseq [id all-fx-ids]
        (let [explicit-body (contains? fx-bodies id)
              body          (get fx-bodies id [[:noop]])
              meta          (get fx-registry id {})
              handler       (conformance/realise-fx-handler id body adapter-helpers)]
          (when explicit-body
            (rf/reg-fx id (assoc meta :handler-fn handler) handler)))))
    ;; Flow registration is intentionally NOT done here — flows are
    ;; FRAME-SCOPED (per Spec 013), so the destroy-frame! call later in
    ;; `run-fixture` would wipe them via the rf2-wbtjn teardown hook.
    ;; `realise-flows!` (called after `reg-frame`) handles them.
    ;; route registrations
    (doseq [[id meta] (get handlers-map :route)]
      ;; rf2-wvh95f F1: the path pattern is the 3-slot VALUE; lift it out of
      ;; the fixture meta map so the middle slot is a pure metadata map.
      (rf/reg-route id (dissoc meta :path) (:path meta)))
    ;; view registrations
    (doseq [[id steps] (get handlers-map :view)]
      (registrar/register!
        :view id
        {:handler-fn (conformance/realise-view-handler steps)}))
    ;; NOTE: app-schema registrations are intentionally NOT registered here
    ;; — see `realise-app-schemas!` below. Per rf2-wkxng / rf2-6m0se,
    ;; `destroy-frame!` now drops the frame's app-db schemas (parity with
    ;; the machines / SSR / privacy destroy hooks). Schemas registered
    ;; before the runner's destroy+reg-frame cycle would be cleared by
    ;; the new hook; the runner registers them after `destroy-frame!` and
    ;; before `reg-frame` so the :initial-events cascade fires with the
    ;; fixture's declared schema slate.
    ;; machine registrations
    (let [machine-registry (get-in fixture [:fixture/registry :machine] {})]
      (when (seq machine-registry)
        (let [{:keys [actions guards on-spawn-actions]}
              (realise-machine-handlers fixture)
              reg-machine machines/reg-machine*]
          (doseq [[machine-id machine-spec] machine-registry]
            (let [merged (-> machine-spec
                             (update :actions          #(merge actions %))
                             (update :guards           #(merge guards %))
                             (update :on-spawn-actions #(merge on-spawn-actions %)))]
              (reg-machine machine-id merged))))))))

(defn- realise-app-schemas!
  "Register the fixture's app-db schemas. Called AFTER the runner's
  destroy-frame! step (so the new `:schemas/on-frame-destroyed!` hook
  doesn't wipe them) and BEFORE `reg-frame` (so the :initial-events cascade
  validates the seeded state against the schemas). Per rf2-wkxng /
  rf2-6m0se."
  [fixture]
  ;; Per rf2-cq1ak the fixture key is `:app-schemas` (plural) — app-db
  ;; schemas are NOT a registrar kind, the key names the data section
  ;; that maps `path → schema` for the runner's realisation step.
  (doseq [[path schema] (get-in fixture [:fixture/registry :app-schemas])]
    (rf/reg-app-schema path {:schema schema})))

(defn- realise-flows!
  "Register the fixture's static flows. Called AFTER `reg-frame` — per
  Spec 013 flows are FRAME-SCOPED, so the destroy-frame! teardown hook
  (rf2-wbtjn) clears any flows registered before the destroy step.

  The static flow shape lives under `:fixture/registry :flow` (with
  `:inputs` / `:output-path` / `:doc`) and the body DSL under
  `:fixture/flow-bodies`. Dynamic flow registration via
  `:rf.fx/reg-flow` is handled in the conformance DSL interpreter."
  [fixture]
  (let [flow-registry (get-in fixture [:fixture/registry :flow] {})
        flow-bodies   (or (:fixture/flow-bodies fixture) {})]
    (doseq [[flow-id flow-meta] flow-registry]
      (when-let [body (get flow-bodies flow-id)]
        (let [output-fn (conformance/realise-flow-output-fn body)]
          (rf/reg-flow (-> flow-meta
                           (assoc :id flow-id)
                           (assoc :derive output-fn))))))))

(defn- realise-classification-effects!
  "Apply a fixture's `:fixture/classification-effects` data-classification
  declarations against the established frame scope. Mirror of the JVM runner.

  EP-0025: durable app-db classification is the four commit-plane effects
  (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`, `:source
  :effect`). Because pure-EDN fixtures cannot return effects from a handler body,
  these fixture ops are a TEST-ONLY shorthand that writes the frame's durable
  elision registry EXACTLY as the four commit-plane effects would. `:sensitive` /
  `:large` are additive; `:clear-sensitive` / `:clear-large` remove the named
  paths on their named axis ONLY (the other axis at the same path survives —
  commit-plane per-axis independence). Each op-map carries exactly one of
  `{:sensitive [path …]}` / `{:large [path …]}` / `{:clear-sensitive [path …]}` /
  `{:clear-large [path …]}`. Called AFTER `reg-frame` and BEFORE `realise-flows!`."
  [fixture scope-frame]
  (letfn [(slot-for [axis]
            (case axis :sensitive :sensitive-declarations :large :declarations))
          (add-paths [reg axis paths]
            (reduce (fn [r path]
                      (assoc-in r [(slot-for axis) (vec path)] {:source :effect}))
                    reg paths))
          (clear-paths [reg axis paths]
            (let [slot (slot-for axis)]
              (reduce (fn [r path]
                        (let [kept (dissoc (get r slot) (vec path))]
                          (if (seq kept) (assoc r slot kept) (dissoc r slot))))
                      reg paths)))]
    (doseq [op (or (:fixture/classification-effects fixture) [])]
      (cond
        (contains? op :sensitive)
        (elision/swap-elision-slot! scope-frame #(add-paths (or % {}) :sensitive (:sensitive op)))
        (contains? op :large)
        (elision/swap-elision-slot! scope-frame #(add-paths (or % {}) :large (:large op)))
        (contains? op :clear-sensitive)
        (elision/swap-elision-slot! scope-frame #(clear-paths (or % {}) :sensitive (:clear-sensitive op)))
        (contains? op :clear-large)
        (elision/swap-elision-slot! scope-frame #(clear-paths (or % {}) :large (:clear-large op)))))))

(defn- collect-traces [fixture-id]
  (let [traces (atom [])]
    (re-frame.trace.tooling/register-listener! [fixture-id] (fn [ev] (swap! traces conj ev)))
    traces))

(defn- collect-error-emit-records!
  "Per rf2-wxe9t: register a corpus-wide error-emit listener for the
  duration of `fixture-id`'s run; each tight error-record fanned out
  by `re-frame.error-emit/dispatch-on-error!` is appended to the
  returned atom in firing order. The conformance harness uses the
  captured records to assert the always-on substrate's `:sensitive?`
  handler-meta redaction contract host-neutrally. Mirror of the JVM
  runner."
  [fixture-id]
  (let [records (atom [])]
    (error-emit/register-error-listener!
      [fixture-id ::records]
      (fn [record] (swap! records conj record)))
    records))

(defn- check-error-emit-records
  "Per rf2-wxe9t: partial-submap matcher for `:error-emit-records`,
  mirror of `check-trace-emissions`. Expected entries are matched in
  declaration order; each entry's key/value pairs must appear on an
  actual record at-or-after the previous match. Returns a vector of
  failure strings (empty when all matched)."
  [actual-records expected-records]
  (loop [actual    actual-records
         expected  expected-records
         failures  []]
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
                                   (when (every? (fn [[k v]]
                                                   (= v (get a k)))
                                                 exp)
                                     i))))]
        (if match-idx
          (recur (drop (inc match-idx) actual) (rest expected) failures)
          (recur actual (rest expected)
                 (conj failures (str "expected error-emit record not seen: "
                                     (pr-str exp)))))))))

(defn- submap?
  "True if every key in expected appears in actual with a matching
  value. Recurses into nested maps so partial expectations on nested
  slices work (mirror of the JVM runner)."
  [expected actual]
  (cond
    (and (map? expected) (map? actual))
    (every? (fn [[k v]]
              (let [a (get actual k)]
                (cond
                  (and (map? v) (map? a)) (submap? v a)
                  :else                   (= v a))))
            expected)

    :else (= expected actual)))

(defn- normalise-effects-routed
  "Two forms accepted for `:effects-routed` entries:
    {:fx-id F :args A}                ;; map form
    [F A]                             ;; pair form
  Both normalise to `{:fx-id F :fx-args A}` (the runtime's trace key)."
  [entries]
  (mapv (fn [e]
          (cond
            (and (map? e) (contains? e :fx-id))
            {:fx-id (:fx-id e) :fx-args (:args e)}

            (and (vector? e) (= 2 (count e)))
            {:fx-id (first e) :fx-args (second e)}

            :else
            (throw (ex-info "unrecognised :effects-routed entry"
                            {:entry e}))))
        entries))

(defn- effects-routed-from-traces
  "Derive the actual list of fx routings from the trace stream.
  Mirror of the JVM runner (per re-frame.fx/handle-one-fx every
  successful routing emits :rf.fx/handled with :rf.fx/id and :rf.fx/args;
  handler-throws emit :rf.error/fx-handler-exception with the same
  tag shape)."
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
  `actual` in declaration order. Returns a vector of failure
  messages, empty when all matched."
  [actual expected]
  (loop [actual    actual
         expected  expected
         failures  []]
    (cond
      (empty? expected) failures

      (empty? actual)
      (conj failures (str "expected effect not routed: "
                          (pr-str (first expected))))

      :else
      (let [exp        (first expected)
            match-idx  (->> actual
                            (map-indexed vector)
                            (some (fn [[i a]]
                                    (when (= exp a) i))))]
        (if match-idx
          (recur (drop (inc match-idx) actual) (rest expected) failures)
          (recur actual (rest expected)
                 (conj failures (str "expected effect not routed: "
                                     (pr-str exp)))))))))

(defn- check-trace-emissions
  "Per the conformance README §Fixture lifecycle: trace-emissions
  partial-matches each expected event by its specified keys; absent
  keys are ignored. Returns a vector of failure messages, empty when
  all matched."
  [actual-traces expected-traces]
  (loop [actual    actual-traces
         expected  expected-traces
         failures  []]
    (cond
      (empty? expected)
      failures

      (empty? actual)
      (conj failures (str "expected trace not seen: "
                          (pr-str (first expected))))

      :else
      (let [exp (first expected)
            match-idx (->> actual
                           (map-indexed vector)
                           (some (fn [[i a]]
                                   (when (every? (fn [[k v]]
                                                   (let [actual-v (get a k)]
                                                     (cond
                                                       (map? v)
                                                       (every? (fn [[kk vv]]
                                                                 (= vv (get actual-v kk)))
                                                               v)
                                                       :else (= v actual-v))))
                                                 exp)
                                     i))))]
        (if match-idx
          (recur (drop (inc match-idx) actual) (rest expected) failures)
          (recur actual (rest expected)
                 (conj failures (str "expected trace not seen: "
                                     (pr-str exp)))))))))

(defn- resolve-sub
  "A sub query in :sub-values may be either:
    [query-v]                 — implicit :rf/default frame
    [frame-id [query-v]]      — explicit frame
  Returns [frame-id query-v]."
  [entry]
  (if (and (vector? entry)
           (= 2 (count entry))
           (vector? (second entry)))
    [(first entry) (second entry)]
    [:rf/default entry]))

(defn- register-routes! [fixture]
  ;; EDN maps don't preserve insertion order beyond ~8 entries. Routes
  ;; with structurally-equal rank tuples emit a warning at registration
  ;; whose tags depend on which side registered second; register in
  ;; deterministic lex order on the route-id.
  (doseq [[id meta] (sort-by (comp str key)
                             (get-in fixture [:fixture/registry :route]))]
    ;; rf2-wvh95f F1: lift the path pattern into the 3-slot VALUE.
    (rf/reg-route id (dissoc meta :path) (:path meta))))

(defn- register-resources!
  "rf2-djofbh — register a fixture's `:fixture/registry :resource` entries
  (Spec 016 §Resource registration). A resource spec needs a `:request` fn
  (the managed-HTTP args builder), which EDN cannot carry; the corpus only
  asserts the registration-derived STATIC graph (the `:request` rides the
  static node as the OPAQUE `:derive` token, never run by static inspection),
  so the runner synthesizes a deterministic `:request` stub. The fixture's
  data-only spec carries the load-bearing `:scope` (fail-closed) +
  `:params-schema`; the runner supplies the executable `:request`. Mirror of
  the JVM runner."
  [fixture]
  (doseq [[resource-id spec] (sort-by (comp str key)
                                      (get-in fixture [:fixture/registry :resource]))]
    (let [url-template (:url-template spec)
          request-fn   (fn [params _ctx]
                         {:request {:method :get
                                    :url    (if url-template
                                              (str url-template params)
                                              (str "/api/" (name resource-id)))}})]
      (resources/reg-resource resource-id
                              (dissoc spec :url-template)
                              request-fn))))

(defn- realise-machine-handlers
  "Build {action-id → fn} and {guard-id → fn} from a fixture's
  :fixture/handlers :machine-action / :machine-guard buckets. Mirror
  of the JVM runner; the action / guard bodies share the conformance
  DSL evaluator."
  [fixture]
  (let [handlers-map (or (:fixture/handlers fixture) {})
        actions-by-id
        (into {}
              (for [[id steps] (:machine-action handlers-map)]
                ;; Per Spec 005 §Actions (rf2-grw4i / rf2-v0rrr): single
                ;; context-map argument `(fn [{:keys [data event ...]}]
                ;; effects)`.
                [id (fn [{:keys [data event]}]
                      (let [final (reduce
                                    (fn [{:keys [data] :as ctx} step]
                                      (case (first step)
                                        :set    (let [[_ path v] step]
                                                  (assoc ctx :data
                                                         (assoc-in data path
                                                                   (conformance/eval-value* v ctx))))
                                        :fx     (let [[_ a b] step]
                                                  (update ctx :fx (fnil conj [])
                                                          [a (conformance/eval-value* b ctx)]))
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
                            (conformance/eval-value* step {:data data :event event})))))]))
        on-spawn-by-id
        (into {}
              (for [[id steps] (:machine-action handlers-map)]
                [id (conformance/realise-on-spawn-handler steps)]))]
    {:actions          actions-by-id
     :guards           guards-by-id
     :on-spawn-actions on-spawn-by-id}))

(defn- run-call
  "Dispatch a :fixture/calls entry. Returns {:passed? bool :detail ...}.
  fixture-machines is the realised {:actions ... :guards ...} map for
  the fixture (built once by run-fixture)."
  [call & [fixture-machines]]
  (case (:call call)
    :match-url
    ;; Per Spec 012 §Bidirectional URL ↔ params the match-url result
    ;; map carries an implementation-specific :validation-error
    ;; explanation alongside :validation-failed? — explanation shape
    ;; varies by validator (Spec 010 §Non-Malli validators), so the
    ;; conformance comparator dissocs it before equality. The
    ;; :validation-failed? flag is the normative bit.
    (let [actual (some-> (routing/match-url (:url call)) (dissoc :validation-error))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "match-url " (:url call)
                       " expected " expect " got " actual))})

    :route-url
    (let [actual (cond
                   (contains? call :fragment)
                   (routing/route-url (:route-id call) (:params call)
                                 (or (:query call) {}) (:fragment call))
                   (:query call)
                   (routing/route-url (:route-id call) (:params call) (:query call))
                   :else
                   (routing/route-url (:route-id call) (:params call)))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "route-url " (:route-id call)
                       " expected " expect " got " actual))})

    :round-trip
    (let [matched (routing/match-url (:url call))
          rebuilt (when matched
                    (routing/route-url (:route-id matched)
                                  (:params matched)
                                  (or (:query matched) {})
                                  (:fragment matched)))]
      {:passed? (= (:url call) rebuilt)
       :detail  (when (not= (:url call) rebuilt)
                  (str "round-trip " (:url call) " → " rebuilt))})

    :assert-rank-greater
    (let [w-meta  (registrar/lookup :route (:winner call))
          l-meta  (registrar/lookup :route (:loser  call))
          w-rank  (:rf.route/rank w-meta)
          l-rank  (:rf.route/rank l-meta)
          ok?     (and w-rank l-rank (pos? (compare w-rank l-rank)))]
      {:passed? ok?
       :detail  (when-not ok?
                  (str "assert-rank-greater " (:winner call)
                       " > " (:loser call)
                       " — winner-rank " w-rank
                       " loser-rank " l-rank))})

    :render-to-string
    (let [opts  (or (:opts call) {})
          out   (try (ssr/render-to-string (:input call) opts)
                     (catch :default e (str "<error: " (ex-message e) ">")))
          want  (:expect call)]
      {:passed? (= want out)
       :detail  (when (not= want out)
                  (str "render-to-string\n"
                       "    expected: " (pr-str want) "\n"
                       "    actual:   " (pr-str out)))})

    ;; Per rf2-aa2rw the engine returns a `re-frame.machines.result/Result`;
    ;; destructure `::snap` / `::fx` by keyword literal so we don't have to
    ;; add a require on the result ns from this fixture-runner.
    :machine-transition
    (let [actions-by-id  (or (:actions fixture-machines) {})
          guards-by-id   (or (:guards  fixture-machines) {})
          on-spawn-by-id (or (:on-spawn-actions fixture-machines) {})
          definition     (-> (:definition call)
                             (update :actions          #(merge actions-by-id %))
                             (update :guards           #(merge guards-by-id %))
                             (update :on-spawn-actions #(merge on-spawn-by-id %)))
          r             (try (machines/machine-transition definition (:snapshot call) (:event call))
                             (catch :default e
                               {:re-frame.machines.result/snap nil
                                :re-frame.machines.result/fx   [:error (ex-message e)]}))
          ;; rf2-y3jv8q — a bounded-depth abort (:always / :raise depth limit
          ;; tripped on a runaway cycle) now returns a result/fail carrying the
          ;; ::depth-abort? sentinel, NOT an :ok rollback no-op (XState v5 throws
          ;; on such a cycle). The fixture's :expect-next-snapshot /
          ;; :expect-effects capture the atomic-rollback contract; project the
          ;; depth-abort :fail onto the observable rollback shape (input
          ;; snapshot, empty effects). Detected via the fully-qualified keyword
          ;; literals so this fixture-runner keeps avoiding a require on the
          ;; result ns.
          depth-abort?  (and (= :fail (:re-frame.machines.result/tag r))
                             (true? (get-in r [:re-frame.machines.result/info
                                               :re-frame.machines.result/depth-abort?])))
          snap-out      (if depth-abort? (:snapshot call) (:re-frame.machines.result/snap r))
          fx-out        (if depth-abort? [] (:re-frame.machines.result/fx r))
          want-snap     (:expect-next-snapshot call)
          want-fx       (or (:expect-effects call) [])
          ok-snap?      (= want-snap snap-out)
          ok-fx?        (= want-fx (vec fx-out))]
      {:passed? (and ok-snap? ok-fx?)
       :detail  (when (not (and ok-snap? ok-fx?))
                  (str "machine-transition\n"
                       "    expected snapshot: " want-snap "\n"
                       "    actual   snapshot: " snap-out "\n"
                       "    expected effects:  " want-fx "\n"
                       "    actual   effects:  " fx-out))})

    ;; pure registration-validation call (rf2-vf5cf). Pins the machine
    ;; registration-error taxonomy (Spec 009 §The thrown-error shape)
    ;; against the pure `validate-machine!` validator. `:expect-error
    ;; <category-kw>` ⇒ the validator must throw an ex-info whose
    ;; `:rf.error/id` ex-data slot equals the category; absent
    ;; `:expect-error` ⇒ a well-formed control that must NOT throw.
    :reg-machine
    (let [want-error (:expect-error call)
          thrown     (try (machines/validate-machine! (:definition call)) nil
                          (catch :default e e))]
      (if want-error
        (let [got-id (:rf.error/id (ex-data thrown))
              ok?    (= want-error got-id)]
          {:passed? ok?
           :detail  (when-not ok?
                      (str "reg-machine\n"
                           "    expected error :rf.error/id: " want-error "\n"
                           "    actual   error :rf.error/id: " got-id "\n"
                           "    thrown:                       " (some-> thrown ex-message)))})
        {:passed? (nil? thrown)
         :detail  (when (some? thrown)
                    (str "reg-machine\n"
                         "    expected: no error (well-formed machine)\n"
                         "    thrown:   " (ex-message thrown)))}))

    ;; EP-0027 construction-engine registration call (rf2-kmk9z4). Pins the
    ;; CONSTRUCTION fail-loud discriminators (Spec 009 §The thrown-error shape)
    ;; against the live `reg-frame`. `:config` is the frame-config passed to
    ;; `reg-frame`; `:expect-error <:rf.error/id>` ⇒ the construction must
    ;; throw an ex-info whose `:rf.error/id` ex-data slot equals the id (the
    ;; `:on-create` / `:initial-db` RETIREMENT ids); absent `:expect-error` ⇒
    ;; a well-formed config that must NOT throw. The frame is destroyed
    ;; afterward (best-effort) so a control doesn't leak into final-app-db.
    ;; Mirror of the JVM runner + the `:reg-machine` Mode-B convention.
    :reg-frame
    (let [frame-id   (or (:frame-id call) :rf.test/construction)
          want-error (:expect-error call)
          thrown     (try (rf/reg-frame frame-id (:config call)) nil
                          (catch :default e e))
          _          (try (rf/destroy-frame! frame-id) (catch :default _ nil))]
      (if want-error
        (let [got-id (:rf.error/id (ex-data thrown))
              ok?    (= want-error got-id)]
          {:passed? ok?
           :detail  (when-not ok?
                      (str "reg-frame\n"
                           "    expected error :rf.error/id: " want-error "\n"
                           "    actual   error :rf.error/id: " got-id "\n"
                           "    thrown:                       " (some-> thrown ex-message)))})
        {:passed? (nil? thrown)
         :detail  (when (some? thrown)
                    (str "reg-frame\n"
                         "    expected: no error (well-formed config)\n"
                         "    thrown:   " (ex-message thrown)))}))

    ;; EP-0012 (rf2-qyb9l1) — CEDN-1 canonical-identity golden ops. Mirror
    ;; of the JVM runner: a fixture pins the FROZEN byte-contract
    ;; (`canonical-bytes`) so an encoder rewrite that changed the bytes
    ;; fails the corpus on BOTH hosts.
    :canonical-bytes
    (let [actual (try (identity/canonical-bytes (:value call))
                      (catch :default e (str "<error: " (ex-message e) ">")))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "canonical-bytes " (pr-str (:value call))
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    :canonical-identical
    (let [ok? (try (identity/identical-identity? (:a call) (:b call))
                   (catch :default _ false))]
      {:passed? (boolean ok?)
       :detail  (when-not ok?
                  (str "canonical-identical expected = identity: "
                       (pr-str (:a call)) " vs " (pr-str (:b call))))})

    :canonical-distinct
    (let [same? (try (identity/identical-identity? (:a call) (:b call))
                     (catch :default _ false))]
      {:passed? (not same?)
       :detail  (when same?
                  (str "canonical-distinct expected DISTINCT identities: "
                       (pr-str (:a call)) " vs " (pr-str (:b call))))})

    :path-instantiate
    (let [want-error (:expect-error call)
          result     (try {:ok (path/instantiate (:path call) (:bindings call))}
                          (catch :default e
                            {:err (or (:rf.error/id (ex-data e)) (ex-message e))}))]
      (if want-error
        {:passed? (= want-error (:err result))
         :detail  (when (not= want-error (:err result))
                    (str "path-instantiate expected error " want-error
                         " got " (pr-str result)))}
        {:passed? (= (:expect call) (:ok result))
         :detail  (when (not= (:expect call) (:ok result))
                    (str "path-instantiate " (pr-str (:path call))
                         "\n    expected: " (pr-str (:expect call))
                         "\n    actual:   " (pr-str result)))}))

    ;; EP-0012 (rf2-du585y) — `:rf/path` algebra LAW ops. Mirror of the JVM
    ;; runner: the frozen path laws (Conventions §Path laws) become a
    ;; non-Clojure conformance target so a port implementing only CEDN bytes +
    ;; template instantiation no longer passes EP-0012 conformance. `:path-over`
    ;; carries a NAMED transform (`:fn`) so the fixture stays pure data.
    (:path-get :path-lookup :path-put :path-over :path-compose :path-prefix :path-overlap)
    (let [run-path-op
          (fn []
            (case (:call call)
              :path-get     (if (contains? call :not-found)
                              (path/get (:value call) (:path call) (:not-found call))
                              (path/get (:value call) (:path call)))
              :path-lookup  (path/lookup (:value call) (:path call))
              :path-put     (path/put (:value call) (:path call) (:x call))
              :path-over    (path/over (:value call) (:path call)
                                       (case (:fn call)
                                         :inc       (fn [v] (inc (or v 0)))
                                         :wrap-vec  (fn [v] [:wrapped v])))
              :path-compose (path/compose (:p call) (:q call))
              :path-prefix  (path/prefix? (:p call) (:q call))
              :path-overlap (path/overlap? (:p call) (:q call))))
          actual (try (run-path-op)
                      (catch :default e (str "<error: " (ex-message e) ">")))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str (name (:call call)) " " (pr-str (dissoc call :call :expect))
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    ;; EP-0015 (rf2-t55hxg.2) — `:project-egress`. Mirror of the JVM runner:
    ;; pin the centralised egress projector's observable contract (Spec 015
    ;; §Tests — off-box-omits-event-args / fail-closed-no-frame). When the
    ;; call OMITS `:frame`, bind `*current-frame*` to nil so the projection
    ;; is genuinely frameless (the fail-closed posture); otherwise the
    ;; runner's ambient scope frame would leak in.
    :project-egress
    (let [has-frame? (contains? call :frame)
          opts       (cond-> {:rf.egress/profile (:rf.egress/profile call)}
                       has-frame? (assoc :frame (:frame call)))
          run        (fn [] (projection/project-egress (:value call) opts))
          actual     (try (if has-frame?
                            (run)
                            (binding [frame/*current-frame* nil] (run)))
                          (catch :default e (str "<error: " (ex-message e) ">")))
          expect     (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "project-egress " (pr-str (:value call))
                       " under " (:rf.egress/profile call)
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    ;; EP-0015 (rf2-t55hxg.2) — `:redact-headers`. Mirror of the JVM
    ;; runner: pin the HTTP header carrier denylist (Spec 014 §Privacy /
    ;; EP-0015 §3 — frame-local carrier extends the immutable defaults).
    :redact-headers
    (let [actual (try (http-privacy-headers/redact-headers
                        (:headers call) (:frame-extras call))
                      (catch :default e (str "<error: " (ex-message e) ">")))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "redact-headers " (pr-str (:headers call))
                       " extras " (pr-str (:frame-extras call))
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    ;; EP-0015 (rf2-t55hxg.2) — `:ssr-apply-policy`. Mirror of the JVM
    ;; runner: pin the SSR hydration-payload allowlist-first projection
    ;; (Spec 011 §14 — only the allowlisted slice crosses; fail-closed).
    :ssr-apply-policy
    (let [want-error (:expect-error call)
          result     (try {:ok (ssr-payload-policy/apply-policy
                                  (:app-db call) (:opts call))}
                          (catch :default e
                            {:err (or (:rf.error/id (ex-data e)) (ex-message e))}))]
      (if want-error
        {:passed? (= want-error (:err result))
         :detail  (when (not= want-error (:err result))
                    (str "ssr-apply-policy expected error " want-error
                         " got " (pr-str result)))}
        {:passed? (= (:expect call) (:ok result))
         :detail  (when (not= (:expect call) (:ok result))
                    (str "ssr-apply-policy " (pr-str (:opts call))
                         "\n    expected: " (pr-str (:expect call))
                         "\n    actual:   " (pr-str result)))}))

    ;; EP-0026 (rf2-qp8qi8) — `:assemble-image`. Mirror of the JVM runner: pin
    ;; the EP-0026 image-API surface (selection, image-order layering, the
    ;; shadow report, the fail-loud collision / retired-key / inline-grammar
    ;; taxonomy) host-agnostically against the live `re-frame.image` constructor
    ;; + `re-frame.image-assembly` assembler. PURE — a function of the call's
    ;; descriptor `:pool` + `:images` specs. See the JVM runner for the full
    ;; call-shape contract (`:pool` / `:standards` / `:images` / `:default?` +
    ;; `:expect-error` OR `:expect-resolves` / `:expect-present` / `:expect-absent`
    ;; / `:expect-kinds` / `:expect-shadows`). Inline bodies are realised to a
    ;; host no-op (EDN cannot carry fns; who-won is read from the descriptor
    ;; coordinate, which is pure EDN).
    :assemble-image
    (let [;; A FRESH fn per entry (see JVM runner) so two inline entries for one
          ;; [kind id] stay DISTINCT registrations — a shared body would dedupe
          ;; them and mask the two-inline within-image collision.
          realise-entry
          (fn [entry]
            (cond
              (and (vector? entry) (= 3 (count entry)))
              (assoc entry 2 (fn [& _] nil))
              (and (vector? entry) (= 2 (count entry)) (not (map? (nth entry 1))))
              (assoc entry 1 (fn [& _] nil))
              :else entry))
          realise-regs
          (fn [regs]
            (reduce-kv (fn [m section entries]
                         (assoc m section (mapv realise-entry entries)))
                       {} regs))
          realise-spec
          (fn [spec]
            (cond-> spec
              (contains? spec :registrations)
              (update :registrations realise-regs)))
          want-error (:expect-error call)
          ;; `:images-literal` — the `make-frame` BOUNDARY case (EP-0026 §Default
          ;; Image — `:images []` is an error). `validate-images!` fires inside
          ;; `make-frame` BEFORE any frame record is created, so the error case is
          ;; pure (no live frame seated). Used ONLY for the `:images []` →
          ;; `:rf.error/make-frame-bad-images` fixture. Mirror of the JVM runner.
          outcome
          (if (contains? call :images-literal)
            (try (rf/make-frame {:images (:images-literal call)})
                 {:err :no-error}
                 (catch :default e
                   (if-let [id (:rf.error/id (ex-data e))]
                     {:err id}
                     {:err-msg (ex-message e)})))
          (try
            (image-assembly/clear-standards!)
            (image-assembly/clear-generation-cache!)
            (doseq [[kind id] (:standards call)]
              (image-assembly/register-standard! kind id {:handler-fn :rf.std/sentinel}))
            (let [pool   (vec (:pool call))
                  images (mapv (fn [spec] (image/image (realise-spec spec)))
                               (:images call))
                  gen    (if (or (:default? call) (empty? images))
                           (image-assembly/assemble-default pool)
                           (image-assembly/assemble images pool))]
              {:gen gen})
            (catch :default e
              (if-let [id (:rf.error/id (ex-data e))]
                {:err id}
                {:err-msg (ex-message e)}))
            (finally (image-assembly/clear-standards!))))]
      (if want-error
        (let [got (:err outcome)]
          {:passed? (= want-error got)
           :detail  (when (not= want-error got)
                      (str "assemble-image expected error " want-error
                           " got " (pr-str (or got (:err-msg outcome)
                                               (when (:gen outcome) :no-error)))))})
        (if-let [gen (:gen outcome)]
          (let [resolver (:rf.gen/resolver gen)
                fails
                (concat
                  (keep (fn [{:keys [kind id coordinate]}]
                          (let [d (get resolver [kind id])]
                            (cond
                              (nil? d)
                              (str "expected [" kind " " id "] to resolve, but it is absent")
                              (not= coordinate (image-assembly/descriptor-coordinate d))
                              (str "[" kind " " id "] resolved to coordinate "
                                   (pr-str (image-assembly/descriptor-coordinate d))
                                   " — expected " (pr-str coordinate)))))
                        (:expect-resolves call))
                  (keep (fn [k+id]
                          (when-not (contains? resolver (vec k+id))
                            (str "expected resolver key " (pr-str (vec k+id)) " present")))
                        (:expect-present call))
                  (keep (fn [k+id]
                          (when (contains? resolver (vec k+id))
                            (str "expected resolver key " (pr-str (vec k+id)) " ABSENT")))
                        (:expect-absent call))
                  (when (contains? call :expect-kinds)
                    (let [got (:rf.gen/kinds gen)]
                      (when (not= (set (:expect-kinds call)) got)
                        [(str "expected kinds " (pr-str (set (:expect-kinds call)))
                              " got " (pr-str got))])))
                  ;; `:expect-gen-absent` — top-level GENERATION keys that MUST
                  ;; NOT appear on the sealed generation map (e.g. the retired
                  ;; `:rf.gen/requires` image-capability slot).
                  (keep (fn [k]
                          (when (contains? gen k)
                            (str "generation key " (pr-str k) " must be ABSENT (got "
                                 (pr-str (get gen k)) ")")))
                        (:expect-gen-absent call))
                  (when (contains? call :expect-shadows)
                    (let [got (:rf.gen/shadows gen)]
                      (when (not= (:expect-shadows call) got)
                        [(str "expected shadows\n      " (pr-str (:expect-shadows call))
                              "\n    got\n      " (pr-str got))]))))]
            {:passed? (empty? fails)
             :detail  (when (seq fails)
                        (str "assemble-image:\n    "
                             (str/join "\n    " fails)))})
          {:passed? false
           :detail  (str "assemble-image: assembly threw unexpectedly — "
                         (pr-str (or (:err outcome) (:err-msg outcome))))})))

    ;; EP-0014 (rf2-k0meap.3; rf2-djofbh) — `:derivation-graph`. Compose the
    ;; cross-family derivation/process graph over the FULL contributor set
    ;; (subs + flows + resources + routes + machines — rf2-djofbh: no longer
    ;; hard-coded to subs+machines, which let the broad
    ;; `:derivation/algebra-graph` claim overclaim the EP-0014 surface) and
    ;; assert NORMALIZED node + edge shapes: each `:expect-node` is a SUBMAP
    ;; matched against the composed node at `:id` (lowering +
    ;; storage/evaluation/lifecycle classification + `:refinement`); each
    ;; `:expect-edge` must be PRESENT (membership) and each
    ;; `:expect-absent-edge` ABSENT. `:expect-graph` (rf2-ska8zk) is a SUBMAP
    ;; matched against the WHOLE graph map — it pins the GRAPH-LEVEL
    ;; `:mode`/`:frame` shape so a live graph that drops or misreports
    ;; `{:mode :live :frame …}` fails even when its nodes/edges are correct.
    ;; A family whose fixture registers nothing contributes no nodes
    ;; (present-family-only), so the subs+machines subset fixture is
    ;; unaffected. `:mode :static` (default) or `:live` (defaulting the frame
    ;; to `:rf/default` when the call omits `:frame`). Mirror of the JVM
    ;; runner.
    :derivation-graph
    (let [contributors  {:subs      {:static-fn  subs-tooling/sub-algebra-view
                                     :live-fn    subs-tooling/sub-cache-algebra-view
                                     :live-shape :map}
                         :flows     {:static-fn  flows-tooling/flow-algebra-view
                                     :live-fn    flows-tooling/flow-algebra-view
                                     :live-shape :map}
                         :resources {:static-fn  resources-tooling/resource-algebra-view
                                     :live-fn    resources-tooling/resource-cache-algebra-view
                                     :live-shape :map}
                         :routes    {:static-fn  routing-tooling/route-algebra-view
                                     :live-fn    routing-tooling/route-slice-algebra-view
                                     :live-shape :node}
                         :machines  {:static-fn        machines-tooling/machine-algebra-view
                                     :live-fn          machines-tooling/machine-instance-algebra-view
                                     :live-shape       :map
                                     :selector-targets machines-tooling/machine-selector-targets}}
          graph         (if (= :live (:mode call))
                          (dgraph/live-derivation-graph (:frame call :rf/default) contributors)
                          (dgraph/derivation-graph contributors))
          nodes         (:nodes graph)
          edges         (set (:edges graph))
          submap?       (fn [sub m] (every? (fn [[k v]] (= v (get m k))) sub))
          ;; rf2-ska8zk — GRAPH-LEVEL expectations. The static/live composers
          ;; carry top-level `:mode` (`:static`/`:live`) and, for the live
          ;; graph, `:frame`. Without this check a port returning the right
          ;; live machine node while omitting/misreporting `{:mode :live
          ;; :frame …}` would still pass the broad fixture (false positive
          ;; against [Derivations.md] §Live graph). `:expect-graph` is a
          ;; SUBMAP matched against the whole graph map, so it asserts only
          ;; the keys the fixture pins (`:mode`, `:frame`) and ignores
          ;; `:nodes`/`:edges` (asserted by `:expect-nodes`/`:expect-edges`).
          graph-fails   (let [want (:expect-graph call)]
                          (when (and want (not (submap? want graph)))
                            [(str "graph expected superset of " want
                                  " got " (select-keys graph (keys want)))]))
          node-fails    (keep (fn [{:keys [id] :as expect-node}]
                                (let [node (get nodes id)
                                      want (dissoc expect-node :id)]
                                  (when-not (and node (submap? want node))
                                    (str "node " id " expected superset of " want
                                         " got " (select-keys (or node {}) (keys want))))))
                              (:expect-nodes call))
          edge-fails    (keep (fn [edge]
                                (when-not (contains? edges edge)
                                  (str "missing edge " edge)))
                              (:expect-edges call))
          absent-fails  (keep (fn [edge]
                                (when (contains? edges edge)
                                  (str "edge should be ABSENT but present: " edge)))
                              (:expect-absent-edges call))
          fails         (concat graph-fails node-fails edge-fails absent-fails)]
      {:passed? (empty? fails)
       :detail  (when (seq fails)
                  (str "derivation-graph (" (or (:mode call) :static) "):\n    "
                       (str/join "\n    " fails)))})

    {:passed? false :detail (str "unknown :call form: " (:call call))}))

(defn run-fixture [fixture]
  (try
    (reset-runtime!)
    (let [fid          (:fixture/id fixture)
          ;; Register the trace listener FIRST so registration-time
          ;; warnings are captured.
          traces       (collect-traces fid)
          ;; rf2-wxe9t — capture the always-on error-emit substrate's
          ;; tight error-records alongside the trace listener. Mirror
          ;; of the JVM runner.
          err-records  (collect-error-emit-records! fid)
          _            (realise-handlers fixture)
          _            (register-routes! fixture)
          ;; rf2-djofbh — resources register before reg-frame / dispatches so
          ;; the route-owned activation static graph + any live fetch see them.
          _            (register-resources! fixture)
          ;; `:fixture/runtime :platform` declares the simulated host
          ;; platform under which the fixture runs (e.g. `:server`
          ;; for SSR-style fx-platforms tests). On the JVM the
          ;; default `re-frame.interop/active-platform` is `:server`,
          ;; so a missing `:platform` in the frame-config still lands
          ;; on the server branch; on CLJS the default is `:client`,
          ;; so honouring `:fixture/runtime :platform` is load-bearing
          ;; for parity. Merging into the frame-config (where
          ;; `run-fx-effects!` reads `:platform` first per
          ;; `re-frame.router/run-fx-effects!`) is the minimal
          ;; intervention.
          runtime-platform (get-in fixture [:fixture/runtime :platform])
          frame-config (cond-> (or (:fixture/frame-config fixture) {})
                         (and runtime-platform
                              (not (contains? (:fixture/frame-config fixture)
                                              :platform)))
                         (assoc :platform runtime-platform))
          frames-spec  (:fixture/frames fixture)
          ;; EP-0002 (rf2-9o48ih) — the carried-invariant: registration-
          ;; time frame-local surfaces (`reg-app-schema`, static
          ;; `reg-flow`) and bare `dispatch-sync` with no explicit
          ;; `{:frame …}` opt resolve their target from the established
          ;; frame scope, never from an invented `:rf/default` floor (per
          ;; Spec 002 §Frame target resolution + EP §6 registration-time
          ;; rule). Single-frame fixtures register / dispatch against
          ;; `:rf/default`; multi-frame fixtures (`:fixture/frames`) carry
          ;; an explicit `:frame` opt on every dispatch (the override tier)
          ;; and register flows dynamically via `:rf.fx/reg-flow` inside a
          ;; per-frame cascade, so the static registration path is empty
          ;; and the scope is only the default for the single-frame case.
          scope-frame  (if (seq frames-spec)
                         (:id (first frames-spec))
                         :rf/default)
          ;; reset-runtime! created :rf/default WITHOUT any :initial-events.
          ;; reg-frame against an existing id is a surgical update; destroy
          ;; first so :initial-events fire when re-registered.
          _            (rf/destroy-frame! :rf/default)
          ;; Per rf2-wkxng / rf2-6m0se: register schemas AFTER the
          ;; destroy (the new `:schemas/on-frame-destroyed!` hook drops
          ;; the frame's schema entries on destroy) and BEFORE the
          ;; re-create so the :initial-events cascade validates against the
          ;; fixture's declared slate. `reg-app-schema` is frame-scoped,
          ;; so establish the scope explicitly.
          _            (rf/with-frame scope-frame
                         (realise-app-schemas! fixture))
          _            (cond
                         (seq frames-spec)
                         (doseq [f frames-spec]
                           (rf/reg-frame (:id f) (dissoc f :id)))
                         :else
                         (rf/reg-frame :rf/default frame-config))
          ;; Data-classification commit-plane effects (Spec 015 §Durable app-db;
          ;; rf2-s2s3xv) run AFTER reg-frame and BEFORE realise-flows!. The
          ;; classification-effect ops are frame-scoped (they write the frame's
          ;; durable elision registry under `:source :effect`).
          _            (realise-classification-effects! fixture scope-frame)
          ;; Flow registration runs AFTER reg-frame: per Spec 013 flows
          ;; are frame-scoped, and the rf2-wbtjn destroy-frame! teardown
          ;; hook would wipe any flows registered before the destroy.
          ;; `reg-flow` is frame-scoped — establish the scope explicitly.
          _            (rf/with-frame scope-frame
                         (realise-flows! fixture))
          dispatches   (or (:fixture/dispatches fixture) [])
          sub-registry (get-in fixture [:fixture/registry :sub] {})
          ;; EP-0017 (rf2-d8mvke.3): per-dispatch `:expect-error` assertions —
          ;; mirror of the JVM runner. See the JVM runner's binding comment.
          dispatch-error-failures (atom [])]
      (rf/with-frame scope-frame
      (doseq [ev dispatches]
        (cond
          (map? ev)
          (cond
            ;; Harness teardown step `{:destroy-frame <frame-id>}` per
            ;; Spec 002 §Destroy + Spec 005 §Cross-Spec Interactions §1.
            ;; Mirrors the JVM runner.
            (contains? ev :destroy-frame)
            (rf/destroy-frame! (:destroy-frame ev))

            ;; Harness re-construction step `{:reset-frame <frame-id>}` per
            ;; Spec 002 §reset-frame! + EP-0027 §Reset (rf2-kmk9z4) — re-run
            ;; the named frame's recorded `:initial-events` against the
            ;; current handlers (a destroy + re-register; no snapshot).
            ;; Mirror of the JVM runner.
            (contains? ev :reset-frame)
            (frame/reset-frame! (:reset-frame ev))

            ;; Harness re-registration step `{:reg-sub <sub-id> :body
            ;; <body>}` per Cross-Spec Interaction §18 (rf2-qei5a). Mirror
            ;; of the JVM runner — the realised sub's `:kind` MUST drive
            ;; the registration form: a layer-2 body re-registered as a
            ;; layer-1 sub would receive app-db (not the input-sub value)
            ;; at evaluation time, silently producing NaN / nonsense.
            ;; Use the fn-form subs/reg-sub (rf/reg-sub is a macro and
            ;; not first-class for `apply`).
            (contains? ev :reg-sub)
            (let [sub-id        (:reg-sub ev)
                  steps         (:body ev)
                  {:keys [kind inputs body]} (conformance/realise-sub steps)
                  sub-meta      (get sub-registry sub-id {})]
              (case kind
                :layer-1 (if (seq sub-meta)
                           (subs/reg-sub sub-id sub-meta body)
                           (subs/reg-sub sub-id body))
                :layer-2 (apply subs/reg-sub sub-id
                                (concat (when (seq sub-meta) [sub-meta])
                                        (interleave (repeat :<-) inputs)
                                        [body]))))

            ;; EP-0017 (rf2-d8mvke.3): a dispatch asserting a boundary /
            ;; context-assembly THROW. `:expect-error` is the `:rf.error/id`
            ;; the dispatch must raise (cofx delivery errors, or the
            ;; `:rf.world/inputs` retirement). The throw escapes
            ;; `dispatch-sync`, so the runner catches it here and compares
            ;; the ex-data `:rf.error/id`. Mirror of the JVM runner.
            (contains? ev :expect-error)
            (let [{event :event want :expect-error} ev
                  opts   (dissoc ev :event :expect-error)
                  got    (try (rf/dispatch-sync event opts) ::no-throw
                              (catch :default e
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

          (and (vector? ev) (= :rf/hydrate (first ev)))
          (rf/dispatch-sync ev {:source :ssr-hydration})

          :else
          (rf/dispatch-sync ev))))
      ;; :fixture/render-after-hydrate — simulate the client-side first
      ;; render so verify-hydration! can compare hashes.
      (when-let [render-spec (:fixture/render-after-hydrate fixture)]
        (let [client-hash     (:simulated-client-render-hash render-spec)
              first-diff-path (:first-diff-path render-spec)
              hydrate-ev      (some (fn [e]
                                      (when (and (vector? e)
                                                 (= :rf/hydrate (first e)))
                                        e))
                                    dispatches)
              payload         (when hydrate-ev (second hydrate-ev))
              server-hash     (:rf/render-hash payload)
              frame-id        (:rf/frame-id payload :rf/default)]
          (when (and client-hash server-hash)
            (ssr/verify-hydration! frame-id client-hash
                                   {:first-diff-path first-diff-path
                                    :server-hash     server-hash}))))
      ;; :fixture/calls — pure-function assertions, run after dispatches
      ;; so any handler-mediated state is in place.
      (let [machines      (realise-machine-handlers fixture)
            calls         (or (:fixture/calls fixture) [])
            call-results  (mapv #(run-call % machines) calls)
            call-failures (filter (complement :passed?) call-results)]
        (when (seq call-failures)
          (throw (ex-info (str "calls failed: "
                               (str/join "; "
                                 (map :detail call-failures)))
                          {:call-failures call-failures}))))
      ;; Drain any pending error projections so :rf/response carries
      ;; the projector's :status before snapshotting final-app-db.
      (doseq [fid (frame/frame-ids)]
        (try (ssr/apply-error-projection! fid)
             (catch :default _ nil)))
      (let [expect       (or (:fixture/expect fixture) {})
            expected-db  (:final-app-db expect)
            expected-dbs (:final-app-dbs expect)
            ;; EP-0017 (rf2-d8mvke.3): NEGATIVE app-db path assertions —
            ;; mirror of the JVM runner. Each path's tip key must be ABSENT.
            expected-absent (:final-app-db-absent expect)
            final-db     (rf/app-db-value :rf/default)
            final-dbs    (when expected-dbs
                           (into {}
                                 (for [[fid _] expected-dbs]
                                   [fid (rf/app-db-value fid)])))
            ;; EP-0001 (rf2-vzld77): durable framework runtime state lives in
            ;; the runtime-db partition; fixtures assert it under
            ;; :final-runtime-db / :final-runtime-dbs (paths under :rf.runtime/*).
            expected-rt  (:final-runtime-db expect)
            expected-rts (:final-runtime-dbs expect)
            final-rt     (rf/runtime-db-value :rf/default)
            final-rts    (when expected-rts
                           (into {}
                                 (for [[fid _] expected-rts]
                                   [fid (rf/runtime-db-value fid)])))
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                (let [[frame-id qv] (resolve-sub query-v)]
                  {:query    query-v
                   :expected expected-val
                   :actual   (rf/subscribe-once frame-id qv)})))
            ;; EP-0017 (rf2-d8mvke.3): NEGATIVE app-db path assertions —
            ;; mirror of the JVM runner. A sentinel `get-in` distinguishes
            ;; absent from present-with-nil.
            absent-failures
            (when expected-absent
              (vec
                (keep (fn [path]
                        (let [sentinel ::absent
                              v        (get-in final-db path sentinel)]
                          (when-not (identical? v sentinel)
                            (str "expected app-db path " (pr-str path)
                                 " to be ABSENT but found " (pr-str v)))))
                      expected-absent)))
            trace-failures (check-trace-emissions @traces (:trace-emissions expect))
            ;; rf2-wxe9t — substrate-side error-emit records mirror of
            ;; the JVM runner. A fixture without `:error-emit-records`
            ;; yields nil failures and does not constrain the substrate.
            error-emit-failures (when (contains? expect :error-emit-records)
                                  (check-error-emit-records
                                    @err-records
                                    (:error-emit-records expect)))
            actual-effects (effects-routed-from-traces @traces)
            expected-effects (when (contains? expect :effects-routed)
                               (normalise-effects-routed (:effects-routed expect)))
            effects-failures (when expected-effects
                               (check-effects-routed actual-effects expected-effects))
            expected-public-error (:ssr/public-error expect)
            public-error-check
            (when expected-public-error
              (let [error-events (filter #(= :error (:op-type %)) @traces)
                    last-error   (last error-events)]
                (if last-error
                  (let [actual (ssr/project-error :rf/default last-error)]
                    {:expected expected-public-error
                     :actual   actual
                     :passed?  (= expected-public-error actual)})
                  {:expected expected-public-error
                   :actual   nil
                   :passed?  false})))]
        ;; Remove just this fixture's collect-traces listener so the
        ;; framework's SSR error-projection listener (and any other
        ;; ns-load-time framework listener) survives into the next
        ;; fixture. `reset-runtime!` restores the full baseline-trace-
        ;; listener snapshot at the next fixture's start, so this is
        ;; mostly belt-and-braces — but it keeps the in-fixture-end
        ;; state from leaking error traces against a missing :rf/route.
        (re-frame.trace.tooling/unregister-listener! fid)
        ;; rf2-wxe9t — drop just this fixture's error-emit recorder so
        ;; it does not leak into the next fixture's drains. The
        ;; reset-runtime! call also clears the registry on next entry;
        ;; this is belt-and-braces (mirror of the JVM runner).
        (error-emit/unregister-error-listener! [fid ::records])
        {:fixture-id   fid
         :passed?      (and (or (nil? expected-db) (submap? expected-db final-db))
                            (or (nil? expected-dbs)
                                (every? (fn [[fid db]] (submap? db (get final-dbs fid)))
                                        expected-dbs))
                            ;; EP-0001 (rf2-vzld77) — runtime-db partition assertions.
                            (or (nil? expected-rt) (submap? expected-rt final-rt))
                            (or (nil? expected-rts)
                                (every? (fn [[fid rt]] (submap? rt (get final-rts fid)))
                                        expected-rts))
                            ;; EP-0017 (rf2-d8mvke.3) — negative app-db paths +
                            ;; per-dispatch expect-error assertions.
                            (empty? absent-failures)
                            (empty? @dispatch-error-failures)
                            (every? #(= (:expected %) (:actual %)) sub-checks)
                            (empty? trace-failures)
                            (empty? effects-failures)
                            (empty? error-emit-failures)
                            (or (nil? public-error-check)
                                (:passed? public-error-check)))
         :absent-failures         absent-failures
         :dispatch-error-failures @dispatch-error-failures
         :final-db     final-db
         :final-dbs    final-dbs
         :expected-db  expected-db
         :expected-dbs expected-dbs
         :sub-checks   sub-checks
         :trace-failures trace-failures
         :effects-failures   effects-failures
         :actual-effects     actual-effects
         :expected-effects   expected-effects
         :error-emit-failures error-emit-failures
         :actual-error-emit-records @err-records
         :public-error-check public-error-check}))
    (catch :default e
      {:fixture-id (:fixture/id fixture)
       :passed?    false
       :error      (ex-message e)
       :exception  e})))

;; ---- the test entrypoint --------------------------------------------------

(defn- run-conformance-corpus-cljs-body []
  (let [results (atom [])]
    (doseq [[fname fixture] fixtures]
      (cond
        (:fixture/load-error fixture)
        (swap! results conj {:fixture-id fname
                             :skipped?   true
                             :reason     "load error"
                             :error      (:fixture/load-error fixture)})

        (not (spec-version-claimed? fixture))
        (swap! results conj {:fixture-id   (:fixture/id fixture)
                             :skipped?     true
                             :reason       "spec-version not in claimed set"
                             :spec-version (:fixture/spec-version fixture)})

        ;; Per rf2-a3q1r: three-way classification of fixture capabilities.
        ;; A fixture whose caps include any capability that is neither
        ;; CLAIMED nor explicitly KNOWN-SKIPPED is a typo / claim-set drift
        ;; — it FAILS the suite rather than being silently skipped.
        :else
        (let [{:keys [allowed unknown]} (classify-capabilities fixture)]
          (cond
            (seq unknown)
            (swap! results conj
                   {:fixture-id   (:fixture/id fixture)
                    :passed?      false
                    :unknown-caps unknown
                    :error        (str "unknown capabilities: " unknown
                                       " — capability is neither in "
                                       "claimed-capabilities nor in "
                                       "known-skipped-capabilities. "
                                       "Either claim it (and ensure the host "
                                       "implements it) or add to the "
                                       "known-skipped-capabilities allowlist "
                                       "to document an intentional gap.")})

            (seq allowed)
            (swap! results conj
                   {:fixture-id   (:fixture/id fixture)
                    :skipped?     true
                    :reason       "capabilities intentionally not claimed (allowlisted)"
                    :capabilities (:fixture/capabilities fixture)
                    :allowed      allowed})

            :else
            (swap! results conj (assoc (run-fixture fixture)
                                  :fname fname))))))
    (let [all     @results
          run     (filter (complement :skipped?) all)
          passed  (filter :passed? run)
          failed  (remove :passed? run)
          skipped (filter :skipped? all)]
      ;; rf2-3hamsq — non-empty floor. This is the DEFAULT `npm run
      ;; test:cljs` PR gate. The CLJS variant inlines fixtures at COMPILE
      ;; time via the all-fixtures macro, so a wrong test-RUNTIME cwd
      ;; can't empty it — but a build-time glob returning empty, or a
      ;; capability-vocab rename that orphans every fixture, would still
      ;; pass the lone (zero? (count failed)) vacuously. Assert that
      ;; fixtures actually executed:
      ;;   - (pos? (count run)) catches the fully-empty case;
      ;;   - the expected-minimum (>= 150) catches partial mass-orphaning
      ;;     (today's runnable count is 186 of 188; the corpus grows).
      (is (pos? (count run))
          "at least one claim-applicable CLJS conformance fixture must have executed")
      (is (>= (count run) 150)
          (str "CLJS conformance corpus runnable-fixture floor (>= 150): only "
               (count run) " executed — a build-time glob fault or a "
               "capability-vocab rename has orphaned the corpus."))
      ;; Silent-on-success (rf2-try1x): the corpus summary only prints
      ;; when there are failures. See the JVM mirror in
      ;; conformance_test.clj for the rationale.
      (when (seq failed)
        (println)
        (println "Conformance corpus (CLJS):")
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
            (println "    unknown capabilities (rf2-a3q1r):" (:unknown-caps f)))
          (when (:error f)
            (println "    error:" (:error f)))
          (when-let [td (:expected-db f)]
            (when (not= td (:final-db f))
              (println "    expected app-db:" td)
              (println "    actual   app-db:" (:final-db f))))
          (when-let [tds (:expected-dbs f)]
            (when (not= tds (:final-dbs f))
              (println "    expected app-dbs:" tds)
              (println "    actual   app-dbs:" (:final-dbs f))))
          (when (seq (:absent-failures f))
            (doseq [af (:absent-failures f)]
              (println "    absent:" af)))
          (when (seq (:dispatch-error-failures f))
            (doseq [def (:dispatch-error-failures f)]
              (println "    expect-error:" def)))
          (doseq [sc (:sub-checks f)]
            (when (not= (:expected sc) (:actual sc))
              (println "    sub" (:query sc) "expected:" (:expected sc) "actual:" (:actual sc))))
          (when (seq (:trace-failures f))
            (doseq [tf (:trace-failures f)]
              (println "    trace:" tf)))
          (when (seq (:effects-failures f))
            (doseq [ef (:effects-failures f)]
              (println "    fx:" ef))
            (println "    actual effects routed:")
            (doseq [a (:actual-effects f)]
              (println "      " (pr-str a))))
          (when (seq (:error-emit-failures f))
            (doseq [eef (:error-emit-failures f)]
              (println "    error-emit:" eef))
            (println "    actual error-emit records:")
            (doseq [r (:actual-error-emit-records f)]
              (println "      " (pr-str r))))
          (when-let [pec (:public-error-check f)]
            (when-not (:passed? pec)
              (println "    public-error expected:" (:expected pec))
              (println "    public-error actual:  " (:actual pec))))))
      ;; Per rf2-3xt7: the corpus is the verification mechanism for this
      ;; build's claimed capability set. The suite fails unless EVERY
      ;; claimed-applicable fixture passes.
      (is (zero? (count failed))
          (str "All claimed-applicable CLJS conformance fixtures must pass; "
               (count failed) " failed.")))))

(deftest run-conformance-corpus-cljs
  ;; Capture the live registrar NOW (after every example / framework
  ;; ns-load has had a chance to register). Use try / finally so that
  ;; even if a fixture-level assertion throws mid-suite, we restore
  ;; the registrar on the way out — leaving subsequent test
  ;; namespaces' state intact. `baseline-trace-listeners` was
  ;; captured at ns-load time; no need to re-grab here.
  (reset! pretest-registrar @registrar/kind->id->metadata)
  (try
    (run-conformance-corpus-cljs-body)
    (finally
      ;; Restore the registrar to what was live before our deftest
      ;; ran. Any per-fixture registrations we made during the
      ;; suite are dropped; every example / framework registration
      ;; survives — so subsequent test namespaces' `:each` fixtures
      ;; see the same baseline our predecessors did.
      (reset! registrar/kind->id->metadata @pretest-registrar))))

;; rf2-ska8zk — NEGATIVE self-test for the `:expect-graph` graph-level guard.
;; Mirror of the JVM `derivation-graph-expect-graph-guard`. Proves the
;; runner's graph-level submap check bites: a `:derivation-graph` call whose
;; `:expect-graph` misreports the live graph's `:mode`/`:frame` must FAIL
;; (`:passed? false`), otherwise the broad fixture's live-mode assertion is a
;; no-op and the capability claim would silently overclaim against
;; [Derivations.md] §Live graph. With an empty registrar the live composer
;; returns `{:mode :live :frame :rf/default :nodes {} :edges []}`, so the
;; check is exercised directly with no fixture registration. Saves / restores
;; the registrar (like the corpus runner) so it doesn't leak into siblings.
(deftest derivation-graph-expect-graph-guard-cljs
  (reset! pretest-registrar @registrar/kind->id->metadata)
  (try
    (reset-runtime!)
    (let [run (fn [call] (run-call call))]
      (is (:passed? (run {:call :derivation-graph :mode :live
                          :expect-graph {:mode :live :frame :rf/default}}))
          "the true live graph shape must pass")
      (is (not (:passed? (run {:call :derivation-graph :mode :live
                               :expect-graph {:mode :static}})))
          "a wrong live graph :mode must fail the runner")
      (is (not (:passed? (run {:call :derivation-graph :mode :live
                               :expect-graph {:mode :live :frame :rf/other}})))
          "a wrong live graph :frame must fail the runner")
      (is (not (:passed? (run {:call :derivation-graph :mode :static
                               :expect-graph {:mode :static :frame :rf/default}})))
          "asserting a :frame on the frame-agnostic static graph must fail")
      (is (:passed? (run {:call :derivation-graph :mode :static
                          :expect-graph {:mode :static}}))
          "the true static graph shape must pass"))
    (finally
      (reset! registrar/kind->id->metadata @pretest-registrar))))
