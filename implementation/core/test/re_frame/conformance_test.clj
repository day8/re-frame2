(ns re-frame.conformance-test
  "Conformance fixture runner. Loads .edn fixtures from
  ../spec/conformance/fixtures/, realises handler-body DSL
  ops into native fns, runs each fixture's :fixture/dispatches, and
  compares observables against :fixture/expect.

  This is a FIRST PASS runner. Capabilities supported:
    :core/event-handler
    :core/sub
    :core/fx (partially)

  Fixtures whose :fixture/capabilities include kinds outside this set
  are skipped (reported as not-exercised). Per the conformance README,
  conformance is graded against claimed capabilities."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            ;; EP-0025: the imperative add-marks/set-marks API is removed; the
            ;; corpus `:add-marks` / `:set-marks` ops install frame app-db
            ;; classification directly into the elision registry (the kept
            ;; substrate the commit-plane `:sensitive` / `:large` effects write).
            [re-frame.elision :as elision]
            ;; rf2-wxe9t — the always-on error-emit substrate (Spec 009
            ;; §What IS available in production §Error observability)
            ;; is the fan-out path the conformance runner observes for
            ;; the `:error-emit-records` expectation. Requiring the ns
            ;; here makes its registry available without each fixture
            ;; needing to know which artefact owns the substrate.
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.schemas :as schemas]
            ;; Per rf2-t0hq + rf2-qyfie — the Malli adapter ns must be
            ;; required at boot to publish the late-bind hook the
            ;; default validator routes through. The conformance corpus
            ;; expects Malli-backed validation outcomes; absent the
            ;; require the validator soft-passes (no failure traces).
            [re-frame.schemas.malli]
            ;; EP-0012 (rf2-qyb9l1) — the CEDN-1 canonical-identity + path
            ;; algebra are the foundation surfaces the canonical-identity
            ;; golden fixture exercises (the `:canonical-bytes` /
            ;; `:canonical-identical` / `:canonical-distinct` /
            ;; `:path-instantiate` call ops). Both are pure `.cljc` namespaces.
            [re-frame.identity :as identity]
            [re-frame.path :as path]
            ;; EP-0015 (rf2-t55hxg.2) — the host-agnostic data-classification
            ;; corpus drives the centralised egress projector via three pure
            ;; `:call` ops. `re-frame.projection/project-egress` is the
            ;; canonical off-box projector (issue 4 / fail-closed); the HTTP
            ;; header carrier denylist (`re-frame.http.privacy-headers`,
            ;; Spec 014 §Privacy / EP-0015 §3) and the SSR hydration-payload
            ;; allowlist (`re-frame.ssr.payload-policy`, Spec 011 §14) are the
            ;; two boundary-specific pure projectors the §Tests table names.
            ;; All three are pure functions — Mode-B call ops, no frame loop.
            [re-frame.projection :as projection]
            [re-frame.http.privacy-headers :as http-privacy-headers]
            [re-frame.ssr.payload-policy :as ssr-payload-policy]
            [re-frame.subs :as subs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.late-bind :as late-bind]
            [re-frame.conformance :as conformance]
            ;; Spec 014 — :rf.http/managed registers at ns-load time. The
            ;; fixture corpus references the fx (often via :fx-overrides
            ;; redirecting to its canned stubs); requiring here gives the
            ;; runner access to the fx without each fixture re-registering
            ;; it itself.
            [re-frame.http.managed]
            ;; rf2-cdmle — the canned-stub fxs (`:rf.http/managed-canned-success`,
            ;; `:rf.http/managed-canned-failure`) moved out of
            ;; `re-frame.http.managed`'s load-time side effects to the
            ;; sibling `re-frame.http.test-support` namespace. The
            ;; conformance fixtures reference them by id; opt in here
            ;; so the fxs register before any fixture runs.
            [re-frame.http.test-support]
            ;; rf2-dbiv8 — the test-only `:rf.test/simulate-http-resolution`
            ;; fixture event moved out of the `re-frame.routing` production
            ;; façade to the sibling `re-frame.routing.test-support`
            ;; namespace. The routing/stale-nav-token-suppression fixture
            ;; dispatches it; opt in here so the event registers before
            ;; any fixture runs (the reset-runtime fixture also :reloads
            ;; it after clear-all!).
            [re-frame.routing.test-support]
            ;; rf2-v0jwt — the epoch artefact publishes the late-bind
            ;; hooks (`:epoch/settle!`, `:epoch/epoch-history`, …) the
            ;; router calls to commit drain-boundary records. Without
            ;; the require the hooks are nil, the router's halt paths
            ;; degrade to no-op, and `:epoch-records` fixture assertions
            ;; can't observe the halted-cascade contract.
            [re-frame.epoch]
            ;; rf2-p10npe — the optional Resources artefact (Spec 016)
            ;; publishes its public-API + feature-probe late-bind hooks
            ;; (`:resources/reg-resource`, …) at ns-load time. Required
            ;; here so the `:resources` feature probe is populated in the
            ;; JVM core test build — `features-cljs-test` asserts every
            ;; registry feature is loaded, and (unlike the CLJS node-test
            ;; build, where the resources `*-cljs-test` suite requires it)
            ;; nothing else on the JVM test path loads the artefact.
            [re-frame.resources]
            ;; EP-0014 (rf2-k0meap.3) — the `:derivation-graph` call op pins
            ;; the cross-family derivation/process graph (lowering /
            ;; classification / edge roles / parametric markers / refinement)
            ;; in the host-agnostic corpus. Mirror of the CLJS runner.
            [re-frame.derivation.graph :as dgraph]
            [re-frame.subs.tooling :as subs-tooling]
            [re-frame.machines.tooling :as machines-tooling]
            ;; rf2-djofbh — the FULL contributor set so the corpus's
            ;; `:derivation-graph` op composes the whole EP-0014 surface
            ;; (flows / resources / routes), not just subs+machines. A
            ;; family whose fixture registers nothing simply contributes no
            ;; nodes (the composer's present-family-only discipline), so the
            ;; subs+machines subset fixture is unaffected while the
            ;; algebra-full fixture exercises every family.
            [re-frame.flows.tooling :as flows-tooling]
            [re-frame.resources.tooling :as resources-tooling]
            [re-frame.routing.tooling :as routing-tooling]))

;; ---- claimed capability set -----------------------------------------------

(def claimed-capabilities
  "What this implementation currently supports.
  Fixtures requiring capabilities outside this set are skipped."
  #{:core/event-handler
    :core/sub
    :core/fx
    :core/error
    ;; :core/trace + :core/frame — rf2-3pnob. Pattern-required surfaces
    ;; per the README's §Capability tagging list and worked-example table.
    ;; :core/trace is exercised by the structured error-trace fixtures
    ;; and by drain-depth-limit; :core/frame is exercised by
    ;; frame-lifecycle, frame-multi-instance, dispatch-envelope (the
    ;; :frame envelope key surfacing in cofx), routing-multi-frame, and
    ;; http-managed-frame-isolation.
    :core/trace
    :core/frame
    :fsm/flat
    :fsm/eventless-always
    :fsm/hierarchical
    :fsm/delayed-after
    :fsm/tags                                         ;; rf2-ee0d (Nine States Stage 1)
    :fsm/parallel-regions                             ;; rf2-l67o (Nine States Stage 2)
    :fsm/final-states                                 ;; rf2-gn80 — :final? + :on-done + :output-key
    :fsm/history                                      ;; rf2-mle6e — first-class history pseudo-states (:type :history — shallow / deep / default-target)
    :fsm/registration-validation                      ;; rf2-vf5cf — registration-error taxonomy (Spec 009 thrown-error shape) via :reg-machine
    :routing/match-url
    :ssr/render-to-string
    :ssr/hydration
    :ssr/response-contract
    :ssr/head-contract
    :ssr/error-projection
    :schemas/runtime
    :schemas/event-payload                            ;; rf2-jwm4
    :schemas/sub-return                               ;; rf2-wcam
    ;; :schemas/cofx — CLAIMED (rf2-hqwki4). The EP-0017 recordable-cofx
    ;; `:schema` path has landed (`re-frame.cofx/validate-recordable-value!`,
    ;; reached from `deliver-declared-cofx` for supplied/replayed + generated
    ;; values): a declared recordable value that fails its registration's
    ;; `:schema` emits `:rf.error/cofx-value-invalid` and throws during context
    ;; assembly. The `schema-cofx-validates.edn` fixture exercises that path
    ;; (the old `inject-cofx`-time validation it pinned —
    ;; `:rf.error/schema-validation-failure :where :cofx` — is retired with
    ;; `inject-cofx`).
    :schemas/cofx
    :routing/ranking
    :routing/fragment
    :routing/blocking
    :routing/nav-token
    ;; EP-0012 (rf2-qyb9l1) — the CEDN-1 canonical-identity + `:rf/path`
    ;; algebra foundation. The `cedn1-path-algebra-golden.edn` fixture pins
    ;; the canonical-bytes token contract + the instantiate-validation
    ;; boundary cross-host (the frozen byte-contract that survives an encoder
    ;; rewrite, complementing the live dual-host property tests).
    :identity/cedn1
    :actor/spawn-destroy                               ;; rf2-mtq4h — renamed from :actor/spawn to align with spec vocabulary
    :actor/declarative-spawn
    :actor/spawn-and-join                              ;; rf2-6vmw / rf2-er0t
    :actor/system-id                                   ;; rf2-suue / rf2-ecv4
    ;; :actor/timeout retired per rf2-3y3y — :fsm/delayed-after subsumes
    ;; it. The state-level :after primitive covers wall-clock-timeout
    ;; semantics for both pure timed-transition states and :spawn-bearing
    ;; states; the after-*.edn fixtures (after-single-delay, after-hierarchy,
    ;; after-stale-detection, parallel-after-scoped-to-region) exercise the
    ;; canonical primitive. See [spec/005-StateMachines.md §Capability matrix]
    ;; and [migration/from-re-frame-v1/README.md §M-44].
    ;; Flow capabilities — per Spec 013. The flow-*.edn fixtures
    ;; (recompute-on-input-change, multi-input-topo, noop-on-value-equal-
    ;; input, toggle-via-fx, hot-reload-preserves-output) declare these.
    :flow/basic
    :flow/topo
    :flow/dirty-check
    :flow/toggle
    :flow/hot-reload
    ;; Spec 009 §Flow trace events / Spec 013 §Flow tracing (rf2-2s1o) —
    ;; the runtime emits :rf.flow/registered, :rf.flow/computed,
    ;; :rf.flow/skip, :rf.flow/cleared, :rf.flow/failed under :op-type
    ;; :flow. Claimed so `flow-lifecycle-emits-traces.edn` runs (rf2-efjs6).
    :flow/trace
    ;; Spec 013 §Frame-scoping — same flow-id against two frames yields
    ;; two independent definitions; clear-flow is frame-local; sibling
    ;; frames' flows do not walk on cross-frame dispatches. Claimed so
    ;; `flow-frame-scoped.edn` runs through the core corpus too (rf2-29ovh).
    :flow/frame-scoped
    ;; Spec 014 — :rf.http/managed (rf2-z1mw)
    :rf.http/managed
    ;; Spec 015 §Data classification (rf2-s2s3xv) — the `data-classification/`
    ;; fixture category exercises the path-marks redaction contract through
    ;; the t2 pending-db trace egress (`add-marks` / `set-marks` declaring
    ;; sensitive / large paths via `:fixture/app-marks`). Claimed so
    ;; `data-classification-flow-output-inherits-from-input.edn` (Spec 015:568)
    ;; runs through the core corpus.
    :data-classification/marks
    ;; EP-0014 (rf2-k0meap.3) — the cross-family derivation/process graph
    ;; (lowering / classification / edge roles / parametric markers /
    ;; :machine-selector refinement) via the `:derivation-graph` call op.
    ;; The BROAD claim: this reference build proves flows / resources /
    ;; routes / route-owned activation / live mode / authority split
    ;; (derivation-graph-algebra-full.edn).
    :derivation/algebra-graph
    ;; rf2-djofbh — the NARROW subs+machines subset claim. The subset fixture
    ;; (derivation-graph-algebra.edn) proves only the subscription
    ;; :derivation + machine :process members over the static graph; a host
    ;; whose graph spans only those two families claims this and allowlists
    ;; the broad capability as a known-skip.
    :derivation/algebra-graph-subs-machines})

;; ---- claimed fixture spec version(s) -------------------------------------
;;
;; Per `spec/conformance/README.md` §Versioning: "When the spec changes shape
;; (new required key in `:rf/dispatch-envelope`, new error category), affected
;; fixtures bump their `:spec-version` and the corpus's harness check rejects
;; implementations that haven't moved with the spec."
;;
;; A fixture whose `:fixture/spec-version` is NOT in this set is reported as
;; skipped (with an explicit reason). The fixture is neither failed nor
;; passed — the harness simply does not claim conformance against that
;; version. Implementations advance this set when they move with the spec.

(def claimed-spec-versions
  "Fixture spec versions this implementation claims to conform against."
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
  ;; rf2-ojakd / rf2-olb64 (a) — streaming SSR is gated by the
  ;; ssr-artefact conformance runners (re-frame.ssr-conformance-test +
  ;; re-frame.ssr-streaming-conformance-test). Listed here so the core
  ;; conformance runner reports the ssr-streaming.edn fixture as an
  ;; intentional out-of-claim skip rather than as an unknown-capability
  ;; failure.
  #{:ssr/suspense-boundary
    :ssr/hydration-payload
    :ssr/chunked-response})

;; ---- fixture loader -------------------------------------------------------

(def fixtures-dir
  ;; The conformance corpus lives under spec/conformance/fixtures at the
  ;; repo root.
  ;;
  ;; Anchored to a CLASSPATH RESOURCE, not the working directory
  ;; (rf2-ywrwkl, the same fix rf2-55j4s3 applied to 3 sibling core tests).
  ;; The earlier cwd-relative form ((io/file "../../spec/conformance/
  ;; fixtures") with a "../spec/..." legacy fallback) assumed the JVM cwd
  ;; was implementation/core/. That holds for the canonical per-artefact
  ;; gate (clojure -M:test from implementation/core/, which CI runs) but
  ;; SILENTLY MIS-SCOPES under the combined implementation/deps.edn :test
  ;; alias: run from implementation/, "../../" resolves ABOVE the repo root,
  ;; file-seq returns nothing, and the corpus discovers zero fixtures (the
  ;; rf2-3hamsq floor turns that mis-discovery RED instead of silent-green).
  ;;
  ;; This test namespace's own source file is on the test classpath (the
  ;; artefact's :test {:extra-paths ["test"]}), so resolving it via
  ;; io/resource pins the anchor to the on-disk source location regardless
  ;; of cwd or which alias loaded the namespace. Walking five parents
  ;; (conformance_test.clj → re_frame → test → core → implementation → repo
  ;; root) reaches the repo root, then we descend into
  ;; spec/conformance/fixtures.
  (let [res (io/resource "re_frame/conformance_test.clj")]
    (assert res
            (str "conformance-test cannot locate its own source on the "
                 "classpath — the core test/ dir must be on the test "
                 "classpath for fixture discovery to anchor."))
    (-> (io/file res)        ; .../core/test/re_frame/conformance_test.clj
        .getParentFile       ; .../core/test/re_frame
        .getParentFile       ; .../core/test
        .getParentFile       ; .../core
        .getParentFile       ; .../implementation
        .getParentFile       ; repo root
        (io/file "spec" "conformance" "fixtures")
        .getCanonicalFile)))

(defn- load-fixture [file]
  (try
    ;; A handful of fixtures use `::name` (auto-resolved keyword) which
    ;; pure clojure.edn cannot read without a *reader-resolver*. The
    ;; corpus's only use of `::` is for runtime-internal timer events
    ;; (e.g. ::after-elapsed); we rewrite to a stable namespace so the
    ;; fixture loads. Tracked as rf2-lu3f.
    (let [raw (slurp file)
          ;; Rewrite ONLY a standalone auto-resolved keyword `::name` — one
          ;; that begins a token (preceded by `(` / `[` / `{` / whitespace).
          ;; The lookbehind keeps the rewrite from corrupting a `::` that
          ;; appears INSIDE a value, e.g. a CEDN-1 keyword token string like
          ;; `"k::answer"` in an EP-0012 `:expect` (rf2-qyb9l1), where the
          ;; `::` is preceded by a letter (the type tag) and must stay
          ;; literal. Every fixture relying on the rewrite (the `::after-
          ;; elapsed` timer events) has its `::` at a token boundary.
          fixed (clojure.string/replace raw #"(?<=[\s(\[{])::([a-zA-Z][a-zA-Z0-9_-]*)"
                                        ":rf.machine.timer/$1")]
      (edn/read-string fixed))
    (catch Throwable e
      {:fixture/load-error (.getMessage e)
       :fixture/file       (.getName file)})))

(defn all-fixtures []
  (->> (file-seq fixtures-dir)
       (filter #(.isFile %))
       (filter #(clojure.string/ends-with? (.getName %) ".edn"))
       (map (fn [f] [(.getName f) (load-fixture f)]))))

;; ---- runtime reset --------------------------------------------------------

(defn- reset-runtime! []
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  ;; rf2-wxe9t — drop every corpus-wide error-emit listener so a
  ;; listener installed by `collect-error-emit-records!` for one
  ;; fixture cannot fire against the next fixture's drains. The
  ;; registry is a `defonce` atom inside `re-frame.error-emit`;
  ;; without an explicit clear, hot-reload semantics keep the prior
  ;; fixture's recorder alive.
  (error-emit/clear-error-listeners!)
  ;; rf2-v0jwt — drop the per-frame epoch ring buffer (and the in-flight
  ;; capture buffer) between fixtures so `:epoch-records` assertions
  ;; observe THIS fixture's recorded epochs only.
  (when-let [f (late-bind/get-fn :epoch/clear-history!)]
    (f))
  (when-let [f (late-bind/get-fn :epoch/clear-epoch-listeners!)]
    (f))
  (rf/init! plain-atom/adapter)
  ;; Framework events / fx are registered at namespace-load time in
  ;; routing.cljc / ssr.cljc; clear-all! wiped them. Re-eval those
  ;; registrations so :rf.route/navigate, :rf.route/handle-url-change,
  ;; :rf/hydrate, :rf.nav/push-url, :rf.nav/replace-url all resolve.
  ;; Use the fn-form re-frame.subs/reg-sub here. The public re-frame.core/
  ;; reg-sub is a macro on JVM (per Spec 001 §Source-coordinate capture)
  ;; and a macro var isn't a callable fn. The underlying subs/reg-sub fn
  ;; has the same effect on the registry.
  ((requiring-resolve 're-frame.subs/reg-sub) :rf/route
   (requiring-resolve 're-frame.routing/route-sub-fn))
  ;; Re-evaluate the registration ns-bodies by removing-and-reloading.
  (require 're-frame.routing :reload)
  ;; rf2-dbiv8 — re-seat the test-only `:rf.test/simulate-http-resolution`
  ;; fixture event after clear-all! (it lives in the test-support ns, not
  ;; the production façade; the routing/stale-nav-token-suppression
  ;; fixture dispatches it).
  (require 're-frame.routing.test-support :reload)
  (require 're-frame.ssr :reload)
  ;; Spec 014 — re-register :rf.http/managed and friends after clear-all!.
  (require 're-frame.http.managed :reload)
  ;; rf2-cdmle — also re-fire re-frame.http.test-support's load body so
  ;; its canned-stub fx registrations re-seat (clear-all! above wiped
  ;; them; http-managed reload doesn't reintroduce them under the new
  ;; gate).
  (require 're-frame.http.test-support :reload)
  ;; Spec 005 — re-register :rf.machine/spawn / :rf.machine/destroy fx and the :rf/machine
  ;; sub after clear-all!. Per rf2-suue the spawn/destroy fx now wire the
  ;; live actor handler + snapshot, so the runtime side of the spawn must
  ;; be present for the system-id fixtures to observe app-db state.
  (require 're-frame.machines :reload)
  ;; Reset id-allocators so nav-token / pending-nav / rank-reg / spawn ids
  ;; are stable across runs (the routing/machine fixtures assert against
  ;; literal "nav-1" / "nav-2" / ":http/post#1" strings).
  ((requiring-resolve 're-frame.routing/reset-counters!))
  ;; rf2-oosjmh — the nav-token / pending-nav counters are now host-side
  ;; transient state (not runtime-db), so the `frames` reset above no longer
  ;; clears them; reset the host cache explicitly so "nav-1" / "pn-1" stay
  ;; stable across fixtures.
  ((requiring-resolve 're-frame.routing/reset-nav-counters!))
  ((requiring-resolve 're-frame.machines/reset-timers!))
  ;; Spec 014 — drop the in-flight request registry between fixtures.
  ((requiring-resolve 're-frame.http.managed/clear-all-in-flight!))
  ;; Spec 014 §Middleware (rf2-yhfgf) — the per-frame interceptor chain
  ;; is held in a `defonce` atom inside `re-frame.http.middleware` and
  ;; persists across `:reload` (defonce semantics). The interceptor
  ;; corpus fixtures register chains scoped to their fixture; clear
  ;; between runs so a previous fixture's interceptors don't leak into
  ;; the next fixture's chain walk.
  ((requiring-resolve 're-frame.http.managed/clear-all-http-interceptors!)))

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
  A fixture without an explicit :fixture/spec-version is treated as
  unversioned and accepted (legacy fixtures pre-versioning).

  Per `spec/conformance/README.md` §Versioning — when the spec changes
  shape, fixtures bump `:spec-version` and implementations that haven't
  moved with the spec must reject those fixtures rather than running them
  against an outdated runtime."
  [fixture]
  (let [v (:fixture/spec-version fixture)]
    (or (nil? v) (contains? claimed-spec-versions v))))

(defn- collect-cofx-keys
  "Walk steps and pull every cofx-id referenced via [:cofx-key K]. Used
  by realise-handlers to auto-wire the consuming event's
  `:rf.cofx/requires` declaration (EP-0017 model — rf2-mrp8jg) for events
  whose bodies read coeffects. Returns a set of K."
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
  returns directly; the runtime delivers it FLAT under the cofx-id when a
  handler declares it via `:rf.cofx/requires` (the ctx→ctx `inject-cofx`
  form that placed the value at [:coeffects cofx-id] is retired).

  Per rf2-g25p the `:set` value passes through `eval-value*` so reflection
  forms (e.g. [:fn :k a b]) still resolve; multiple `:set` steps run in
  order and the final step wins (single-injection convention)."
  [steps]
  (fn []
    (let [eval-value (requiring-resolve 're-frame.conformance/eval-value*)]
      (reduce (fn [v step]
                (case (first step)
                  :set  (let [[_ _path value] step]
                          (eval-value value {}))
                  :noop v
                  v))
              nil
              steps))))

;; Forward-declared — realise-machine-handlers is defined below (alongside
;; the run-call :machine-transition path). Per rf2-msd4 the same realised
;; action/guard maps feed both the in-memory `machine-transition` callsite
;; and the registry `reg-machine` registrations.
(declare realise-machine-handlers)

(defn- realise-handlers [fixture]
  ;; Walk :fixture/handlers and register each.
  (let [handlers-map (or (:fixture/handlers fixture) {})
        event-registry (get-in fixture [:fixture/registry :event] {})
        sub-registry   (get-in fixture [:fixture/registry :sub] {})
        cofx-bodies    (get handlers-map :cofx)
        cofx-registry  (get-in fixture [:fixture/registry :cofx] {})
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
    ;; `:rf.cofx/requires`. The `:schema` metadata rides along and the landed
    ;; EP-0017 recordable-cofx `:schema` path validates it (rf2-hqwki4); the
    ;; `schema-cofx-validates.edn` fixture exercises that path (now CLAIMED via
    ;; `:schemas/cofx`).
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
    ;; EP-0017 model (rf2-mrp8jg): a body that reads `[:cofx-key K]` declares
    ;; the consumed coeffect ids via the `:rf.cofx/requires` registration-
    ;; metadata key (the ctx→ctx `inject-cofx` interceptor wiring is retired).
    ;; The runtime runs each declared supplier at context assembly and delivers
    ;; its value flat under the cofx-id. `:rf.cofx/requires` is fx-only — a body
    ;; that reads any cofx routes through `realise-event-handler` to an `:fx`
    ;; handler (`needs-fx-handler?` flags `:cofx-key`), so the requires
    ;; declaration only ever lands on an `:fx`-shaped reg-event handler.
    (doseq [[id steps] (get handlers-map :event)]
      (let [[kind handler] (conformance/realise-event-handler steps)
            ;; Per rf2-g25p: scan the body for [:cofx-key K] references;
            ;; for each K, require every C whose namespace matches K (the
            ;; conformance-corpus convention for the schemas/cofx fixture).
            ks            (collect-cofx-keys steps)
            cofx-ids      (vec
                            (mapcat (fn [k]
                                      (or (get cofx-by-key k)
                                          (when (contains? cofx-registry k) [k])))
                                    ks))
            ;; Per Spec 010 §step 1 (rf2-jwm4): pull :schema / :doc from the
            ;; fixture's :fixture/registry :event meta and pass it through to
            ;; reg-event-* so validate-event! can find it. EP-0017: merge the
            ;; auto-wired `:rf.cofx/requires` declaration onto the same meta.
            event-meta    (cond-> (get event-registry id {})
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
    (doseq [[id steps] (get handlers-map :sub)]
      (let [{:keys [kind inputs body]} (conformance/realise-sub steps)
            ;; Per Spec 010 §step 6 (rf2-wcam): pull :schema from the
            ;; sub's registry meta so validate-sub! sees it.
            sub-meta (get sub-registry id {})]
        (case kind
          :layer-1 (if (seq sub-meta)
                     (rf/reg-sub id sub-meta body)
                     (rf/reg-sub id body))
          ;; EP-0001 (rf2-vzld77): a `[:get [:rf.runtime/… …]]` fixture sub
          ;; reads the runtime-db partition — register via the framework
          ;; `reg-runtime-sub` so its `db`-position arg is runtime-db.
          :runtime-db (if (seq sub-meta)
                        (subs/reg-runtime-sub id sub-meta body)
                        (subs/reg-runtime-sub id body))
          ;; Use the fn-form (subs/reg-sub) here because the public
          ;; rf/reg-sub is a macro on JVM (per Spec 001 §Source-coordinate
          ;; capture) and macros aren't first-class values for `apply`.
          ;; Source-coord capture is intentionally bypassed for these
          ;; fixture-synthesised registrations — fixture data carries no
          ;; meaningful call site.
          :layer-2 (apply subs/reg-sub id
                          (concat (when (seq sub-meta) [sub-meta])
                                  (interleave (repeat :<-) inputs)
                                  [body])))))
    ;; fx handlers — DSL bodies. May :throw, :noop, mutate the frame's
    ;; app-db, or :dispatch a follow-up event (e.g. http stubs).
    ;;
    ;; Two sources combine: :fixture/handlers :fx (bodies) and
    ;; :fixture/registry :fx (metadata, including :platforms / :schema).
    ;;
    ;; Per rf2-yhfgf: an id with NO body in :fixture/handlers but a meta
    ;; in :fixture/registry is "declare the dependency, leave the framework
    ;; registration alone" — the harness DOES NOT overwrite the
    ;; framework-shipped fx with a noop. This lets fixtures rely on the
    ;; real fx behaviour for ids like :rf.fx/reg-http-interceptor where
    ;; the load-bearing logic lives inside the framework registration
    ;; and a fixture-DSL body cannot replicate it. Pre-rf2-yhfgf the
    ;; harness re-registered every registry-declared id with a noop,
    ;; which silently masked the framework registration; the new contract
    ;; respects the "no explicit body = leave the framework alone" rule
    ;; while keeping the existing override mechanism (explicit body
    ;; under :fixture/handlers :fx) unchanged.
    (let [adapter-helpers
          {:read-db!  (fn [frame-id]
                        (frame/frame-app-db-value frame-id))
           ;; EP-0001 (rf2-adwcv6): write the app-db PARTITION of the one
           ;; physical frame-state container. `frame/app-db-container` is now
           ;; a READ-ONLY projection, so a direct `replace-container!` on it
           ;; throws; `swap-frame-db!` (constant fn) installs the new app-db
           ;; into the app-db partition, leaving runtime-db untouched.
           :write-db! (fn [frame-id new-db]
                        (frame/swap-frame-db! frame-id (constantly new-db)))
           :dispatch! (fn [event frame-id]
                        (rf/dispatch event {:frame frame-id}))
           ;; Per Cross-Spec Interaction §14 (rf2-60szl): dispatch-sync
           ;; from an fx handler body trips the router's in-drain guard
           ;; and surfaces :rf.error/dispatch-sync-in-handler. Fixtures
           ;; that pin the ban use [:dispatch-sync event-vec] from their
           ;; fx body; the realise-fx-handler routes that pair here.
           :dispatch-sync! (fn [event frame-id]
                             (rf/dispatch-sync event {:frame frame-id}))}
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
    ;; view registrations — DSL bodies map to fns that realise hiccup with
    ;; reflection forms resolved at call-time.
    (doseq [[id steps] (get handlers-map :view)]
      ((requiring-resolve 're-frame.registrar/register!)
       :view id
       {:handler-fn (conformance/realise-view-handler steps)}))
    ;; Per rf2-msd4: machine registrations. The fixture's
    ;; :fixture/registry :machine is a {machine-id <machine-spec>} map; the
    ;; spec's :actions / :guards / :on-spawn-actions slots may reference
    ;; bodies declared under :fixture/handlers :machine-action /
    ;; :machine-guard. We realise those bodies once here and merge them
    ;; into the spec before calling re-frame.machines/reg-machine, which
    ;; in turn calls reg-event with make-machine-handler. From this
    ;; point dispatching [machine-id <inner-event>] runs through the full
    ;; runtime path, so :rf.error/machine-action-exception (Cross-Spec
    ;; §11/§17) and the post-commit :fx walk (Cross-Spec §12) become
    ;; observable to fixtures.
    (let [machine-registry (get-in fixture [:fixture/registry :machine] {})]
      (when (seq machine-registry)
        (let [{:keys [actions guards on-spawn-actions]}
              (realise-machine-handlers fixture)
              ;; Per Spec 005 §reg-machine vs reg-machine* (rf2-8bp3) the
              ;; runtime registrar is `reg-machine*` (the macro lives at
              ;; the re-frame.core boundary).
              reg-machine (requiring-resolve 're-frame.machines/reg-machine*)]
          (doseq [[machine-id machine-spec] machine-registry]
            (let [merged (-> machine-spec
                             (update :actions          #(merge actions %))
                             (update :guards           #(merge guards %))
                             (update :on-spawn-actions #(merge on-spawn-actions %)))]
              (reg-machine machine-id merged))))))))

(defn- realise-flows!
  "Per Spec 013 flows are FRAME-SCOPED: their lifecycle, evaluation, and
  undo / time-travel semantics all belong to one frame. So flow
  registration must happen AFTER `reg-frame` — otherwise the
  destroy-frame! teardown hook (rf2-wbtjn) clears the flows we just
  registered when the runner destroys :rf/default to fire the
  fixture's `:initial-events` cascade under its declared config.

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

(defn- realise-app-marks!
  "Apply a fixture's `:fixture/app-marks` data-classification declarations
  against the established frame scope.

  EP-0025: the imperative `add-marks` / `set-marks` API is REMOVED. These
  fixture ops are a TEST-ONLY shorthand for installing / removing frame app-db
  classification — equivalent to the four kept commit-plane effects
  (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`, `:source
  :effect`). The harness installs / removes each `{path mark}` directly into the
  frame's durable elision registry. `:add-marks` merges (additive); `:set-marks`
  replaces ALL prior effect-sourced declarations on the frame first
  (frame-sourced survive), so the `set-marks-replaces-not-merges` semantics hold;
  `:clear-marks` removes exactly the named `{path mark}` entries on their named
  axis ONLY (the OTHER axis at the same path survives — the commit-plane
  `:clear-sensitive` / `:clear-large` per-axis independence).

  `:fixture/app-marks` is an ORDERED vector of op-maps; each carries exactly one
  of `{:add-marks {path mark}}` / `{:set-marks {path mark}}` / `{:clear-marks
  {path mark}}`. `path` is a `get-in`-shaped vector; `mark` is `:sensitive` or
  `:large`. Called AFTER `reg-frame` (the elision slot exists) and BEFORE
  `realise-flows!`."
  [fixture scope-frame]
  (letfn [(slot-for [mark]
            (case mark :sensitive :sensitive-declarations :large :declarations))
          (merge-marks [reg path->mark]
            (reduce-kv (fn [r path mark]
                         (assoc-in r [(slot-for mark) (vec path)] {:source :effect}))
                       reg path->mark))
          (clear-marks [reg path->mark]
            (reduce-kv (fn [r path mark]
                         (let [slot (slot-for mark)
                               kept (dissoc (get r slot) (vec path))]
                           (if (seq kept) (assoc r slot kept) (dissoc r slot))))
                       reg path->mark))
          (drop-effect-sourced [reg]
            (reduce (fn [r slot]
                      (let [kept (into {} (remove (fn [[_ d]] (= :effect (:source d)))
                                                  (get r slot)))]
                        (if (seq kept) (assoc r slot kept) (dissoc r slot))))
                    reg [:sensitive-declarations :declarations]))]
    (doseq [op (or (:fixture/app-marks fixture) [])]
      (cond
        (contains? op :add-marks)
        (elision/swap-elision-slot! scope-frame #(merge-marks (or % {}) (:add-marks op)))
        (contains? op :set-marks)
        (elision/swap-elision-slot! scope-frame
          #(merge-marks (drop-effect-sourced (or % {})) (:set-marks op)))
        (contains? op :clear-marks)
        (elision/swap-elision-slot! scope-frame #(clear-marks (or % {}) (:clear-marks op)))))))

(defn- collect-traces [fixture-id]
  (let [traces (atom [])]
    (trace/register-listener! [fixture-id] (fn [ev] (swap! traces conj ev)))
    traces))

(defn- collect-error-emit-records!
  "Per rf2-wxe9t: register a corpus-wide error-emit listener for the
  duration of `fixture-id`'s run; each tight error-record (the
  `re-frame.error-emit/dispatch-on-error!` fan-out shape — see
  `re-frame.error-emit` ns docstring §Record shape) is appended to
  the returned atom in firing order. The conformance harness uses
  the captured records to assert the always-on substrate's
  `:sensitive?` redaction contract host-neutrally.

  The matching `unregister-error-listener!` call happens at the
  end of `run-fixture` so the listener does NOT leak into the next
  fixture's drains. `reset-runtime!` also calls
  `clear-error-listeners!` belt-and-braces for safety."
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
  "True if every key in expected appears in actual with a matching value.
  Recurses into nested maps so partial expectations on nested slices
  work the same way (e.g. :rf/route's :nav-token can be implementation-
  defined yet other slice keys are checked exactly)."
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
  "Fixtures express `:effects-routed` entries in two forms:

    {:fx-id F :args A}                 ;; map form
    [F A]                              ;; pair form

  Normalise to `{:fx-id F :fx-args A}` so they can be matched against the
  trace-derived actual list (which uses the runtime's `:fx-args` key)."
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

  Per `re-frame.fx/handle-one-fx`: every successful routing emits a
  `:rf.fx/handled` trace with `:rf.fx/id` (post-override) and
  `:rf.fx/args`. A handler-throw emits `:rf.error/fx-handler-exception`
  with the same `:rf.fx/id`/`:rf.fx/args` shape — that's still a routing
  for the purposes of the fixture contract (the runtime did attempt the
  handler).

  The order in this returned vector is the order the runtime attempted
  to process the effects, which is what `:effects-routed` asserts (per
  `spec/conformance/README.md` §Handler-body DSL ops and §Fixture
  lifecycle: \"effects routed\")."
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
  `actual` in declaration order. Returns a vector of failure messages,
  empty when all expected entries matched.

  Mirrors the trace-emissions matcher: extras in `actual` are tolerated
  (the runtime may have routed bookkeeping fx the fixture doesn't care
  about), but missing or out-of-order expected entries are failures."
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
  "Per the conformance README §Fixture lifecycle: trace-emissions partial-
  matches each event by its specified keys; absent keys are ignored.
  Returns a vector of failure messages, or empty if all matched."
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
            ;; Find the next actual that partial-matches exp.
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

(defn- check-epoch-records
  "Per rf2-v0jwt — `:epoch-records` lets a fixture assert against the
  recorded `:rf/epoch-record` ring. Entries are partial submaps matched
  positionally against the named frame's history (oldest-first).
  Returns a vector of failure-strings; empty when every entry matches.

  Each `:epoch-records` entry is one of:
    {:frame <id> :record <partial-record-map>}
    {:record <partial-record-map>}                 ;; implicit :rf/default
  The partial-record-map is matched via submap-rule against the actual
  history at the entry's positional index within that frame's ring.

  Pre-rf2-v0jwt history slots (`:event-id`, `:trigger-event`) work
  unchanged; the new rf2-v0jwt slots (`:outcome`, `:halt-reason`) match
  the same way."
  [expected]
  (let [by-frame (group-by #(or (:frame %) :rf/default) expected)]
    (vec
     (mapcat
       (fn [[frame-id entries]]
         (let [actual-history (try (rf/epoch-history frame-id)
                                   (catch Throwable _ []))]
           (keep-indexed
             (fn [i {:keys [record]}]
               (let [actual-record (get actual-history i)]
                 (cond
                   (nil? actual-record)
                   (str "expected epoch-record at position " i
                        " for frame " frame-id
                        " but none recorded")

                   (not (submap? record actual-record))
                   (str "epoch-record mismatch at position " i
                        " for frame " frame-id
                        " — expected (submap) " (pr-str record)
                        " — actual " (pr-str (select-keys actual-record
                                                          (keys record)))))))
             entries)))
       by-frame))))

(defn- register-routes! [fixture]
  ;; EDN maps don't preserve insertion order beyond ~8 entries. Routes
  ;; with structurally-equal rank tuples emit a warning at registration
  ;; whose tags depend on which side registered second, so we register
  ;; in deterministic lex order on the route-id.
  (doseq [[id meta] (sort-by (comp str key)
                             (get-in fixture [:fixture/registry :route]))]
    ;; rf2-wvh95f F1: lift the path pattern into the 3-slot VALUE.
    (rf/reg-route id (dissoc meta :path) (:path meta))))

(defn- register-resources!
  "rf2-djofbh — register a fixture's `:fixture/registry :resource` entries
  (Spec 016 §Resource registration). A resource spec needs a `:request` fn
  (the managed-HTTP args builder), which EDN cannot carry; the corpus only
  asserts the registration-derived STATIC graph (the `:request` rides the
  static node as the OPAQUE `:derive` token, never run by static inspection
  — Derivations §The don't-execute rule), so the runner synthesizes a
  deterministic `:request` stub from the declared `:url-template` (or a
  conventional `/api/<resource-id>` path). The fixture's data-only spec
  carries the load-bearing `:scope` (fail-closed) + `:params-schema`; the
  runner supplies the executable `:request`. Mirror of the CLJS runner."
  [fixture]
  (let [reg-resource (requiring-resolve 're-frame.resources/reg-resource)]
    (doseq [[resource-id spec] (sort-by (comp str key)
                                        (get-in fixture [:fixture/registry :resource]))]
      (let [url-template (:url-template spec)
            request-fn   (fn [params _ctx]
                           {:request {:method :get
                                      :url    (if url-template
                                                (str url-template params)
                                                (str "/api/" (name resource-id)))}})]
        (reg-resource resource-id
                      (dissoc spec :url-template)
                      request-fn)))))

(defn- realise-machine-handlers
  "Build {action-id → fn} and {guard-id → fn} from a fixture's
  :fixture/handlers :machine-action / :machine-guard buckets.

  Action body steps return effects via the apply-step :fx slot — we
  collect those into the {:fx [...]} return shape. Guard body steps
  evaluate to a single boolean — we run the steps and read the last
  reflection's value."
  [fixture]
  (let [handlers-map (or (:fixture/handlers fixture) {})
        eval-value (requiring-resolve 're-frame.conformance/eval-value*)
        actions-by-id
        (into {}
              (for [[id steps] (:machine-action handlers-map)]
                ;; Per Spec 005 §Guards / §Actions (rf2-grw4i / rf2-v0rrr):
                ;; the user-facing fn receives one context-map arg
                ;; `{:keys [data event state meta]}`. The fixture-step
                ;; interpreter still threads a local `ctx` map for its
                ;; own reduction state.
                [id (fn [{:keys [data event]}]
                      (let [final (reduce
                                    (fn [{:keys [data] :as ctx} step]
                                      (case (first step)
                                        :set    (let [[_ path v] step]
                                                  (assoc ctx :data
                                                         (assoc-in data path
                                                                   (eval-value v ctx))))
                                        ;; Per rf2-8vo0: pass :fx args through
                                        ;; eval-value so reflection forms (e.g.
                                        ;; [:get [:child-id]]) resolve against
                                        ;; the snapshot's :data, mirroring how
                                        ;; :set / :dispatch already eval their
                                        ;; values.
                                        :fx     (let [[_ a b] step]
                                                  (update ctx :fx (fnil conj [])
                                                          [a (eval-value b ctx)]))
                                        ;; Per rf2-msd4: machine actions can
                                        ;; throw to exercise Cross-Spec §11
                                        ;; (machine-action-exception). The
                                        ;; runtime's make-machine-handler
                                        ;; catches the throw, halts the cascade
                                        ;; atomically, and emits
                                        ;; :rf.error/machine-action-exception
                                        ;; with the original message.
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
                ;; Per Spec 005 §Guards (rf2-grw4i / rf2-v0rrr): single
                ;; context-map arg `(fn [{:keys [data event state meta]}]
                ;; boolean)`.
                [id (fn [{:keys [data event]}]
                      (let [step (first steps)]
                        (when (and (vector? step) (= :fn (first step)))
                          (boolean
                            (eval-value step {:data data :event event})))))]))
        ;; Same machine-action steps, but realised as on-spawn callbacks.
        ;; Per rf2-grw4i / rf2-v0rrr the on-spawn callback signature is
        ;; `(fn [{:keys [data id]}] _)` and the return is advisory only.
        on-spawn-by-id
        (into {}
              (for [[id steps] (:machine-action handlers-map)]
                [id (conformance/realise-on-spawn-handler steps)]))]
    {:actions    actions-by-id
     :guards     guards-by-id
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

    ;; rank-vs-rank assertion: both winner and loser exist; winner's rank
    ;; tuple compares greater than loser's via lex compare.
    :assert-rank-greater
    (let [meta-fn (requiring-resolve 're-frame.registrar/lookup)
          w-meta  (meta-fn :route (:winner call))
          l-meta  (meta-fn :route (:loser  call))
          w-rank  (:rf.route/rank w-meta)
          l-rank  (:rf.route/rank l-meta)
          ok?     (and w-rank l-rank (pos? (compare w-rank l-rank)))]
      {:passed? ok?
       :detail  (when-not ok?
                  (str "assert-rank-greater " (:winner call)
                       " > " (:loser call)
                       " — winner-rank " w-rank
                       " loser-rank " l-rank))})

    ;; SSR pure render: input is hiccup or [:view-id args ...]; opts may
    ;; carry :doctype?.
    :render-to-string
    (let [r2s   (requiring-resolve 're-frame.ssr/render-to-string)
          opts  (or (:opts call) {})
          out   (try (r2s (:input call) opts)
                     (catch Throwable e (str "<error: " (.getMessage e) ">")))
          want  (:expect call)]
      {:passed? (= want out)
       :detail  (when (not= want out)
                  (str "render-to-string\n"
                       "    expected: " (pr-str want) "\n"
                       "    actual:   " (pr-str out)))})

    ;; pure machine-transition call (used by fsm fixtures). Per
    ;; rf2-aa2rw the engine returns a `re-frame.machines.result/Result`
    ;; — we destructure `::snap` / `::fx` directly to avoid a static
    ;; require on the machines artefact from the conformance test ns.
    :machine-transition
    (let [machine-transition (requiring-resolve 're-frame.machines/machine-transition)
          actions-by-id (or (:actions fixture-machines) {})
          guards-by-id  (or (:guards fixture-machines) {})
          on-spawn-by-id (or (:on-spawn-actions fixture-machines) {})
          ;; Merge fixture-registered handlers into the def's named-binding
          ;; maps. The fixture's bindings live alongside any short-names the
          ;; def already declared. Machines/chase-ref follows
          ;; short-name → registered-id → fn through this combined map.
          definition    (-> (:definition call)
                            (update :actions #(merge actions-by-id %))
                            (update :guards  #(merge guards-by-id %))
                            (update :on-spawn-actions #(merge on-spawn-by-id %)))
          r             (try (machine-transition definition (:snapshot call) (:event call))
                             (catch Throwable e
                               {:re-frame.machines.result/snap nil
                                :re-frame.machines.result/fx   [:error (.getMessage e)]}))
          ;; rf2-y3jv8q — a bounded-depth abort (`:always` / `:raise` depth
          ;; limit tripped on a runaway cycle) now returns a `result/fail`
          ;; carrying the `::depth-abort?` sentinel, NOT an `:ok` rollback
          ;; no-op (XState v5 throws on such a cycle). The fixture's
          ;; `:expect-next-snapshot` / `:expect-effects` capture the
          ;; atomic-rollback contract (the macrostep does not commit), so
          ;; project a depth-abort `:fail` onto the observable rollback shape:
          ;; next-snapshot is the INPUT snapshot, effects empty. Detected via
          ;; the fully-qualified keyword literals so this ns keeps avoiding a
          ;; static require on the machines artefact.
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
    ;; against the pure `validate-machine!` validator — no registrar, no
    ;; substrate. `:expect-error <category-kw>` ⇒ the validator must throw
    ;; an ex-info whose `:rf.error/id` ex-data slot equals the category;
    ;; absent `:expect-error` ⇒ a well-formed control that must NOT throw.
    :reg-machine
    (let [validate-machine! (requiring-resolve 're-frame.machines/validate-machine!)
          want-error (:expect-error call)
          thrown     (try (validate-machine! (:definition call)) nil
                          (catch clojure.lang.ExceptionInfo e e)
                          (catch Throwable e e))]
      (if want-error
        (let [got-id (when (instance? clojure.lang.ExceptionInfo thrown)
                       (:rf.error/id (ex-data thrown)))
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

    ;; EP-0012 (rf2-qyb9l1) — CEDN-1 canonical-identity golden ops. These
    ;; pin the FROZEN byte-contract (`re-frame.identity/canonical-bytes`)
    ;; cross-host: a fixture asserts the exact token stream a value encodes
    ;; to, so an encoder rewrite that changed the bytes would fail the
    ;; corpus on BOTH hosts. Complements the live dual-host property tests
    ;; (identity-cedn1-cljs-test) with a static contract surviving a rewrite.

    ;; `:canonical-bytes` — assert `(canonical-bytes value)` equals `:expect`.
    :canonical-bytes
    (let [actual (try (identity/canonical-bytes (:value call))
                      (catch Throwable e (str "<error: " (.getMessage e) ">")))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "canonical-bytes " (pr-str (:value call))
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    ;; `:canonical-identical` — assert two values share ONE CEDN-1 identity.
    :canonical-identical
    (let [ok? (try (identity/identical-identity? (:a call) (:b call))
                   (catch Throwable _ false))]
      {:passed? (boolean ok?)
       :detail  (when-not ok?
                  (str "canonical-identical expected = identity: "
                       (pr-str (:a call)) " vs " (pr-str (:b call))))})

    ;; `:canonical-distinct` — assert two values are DISTINCT CEDN-1 facts.
    :canonical-distinct
    (let [same? (try (identity/identical-identity? (:a call) (:b call))
                     (catch Throwable _ false))]
      {:passed? (not same?)
       :detail  (when same?
                  (str "canonical-distinct expected DISTINCT identities: "
                       (pr-str (:a call)) " vs " (pr-str (:b call))))})

    ;; `:path-instantiate` — assert `(path/instantiate path bindings)` equals
    ;; `:expect`, OR (when `:expect-error` is supplied) fails closed with that
    ;; `:rf.error/id`. Pins the concrete-path PRODUCER boundary (rf2-ehkut7).
    :path-instantiate
    (let [want-error (:expect-error call)
          result     (try {:ok (path/instantiate (:path call) (:bindings call))}
                          (catch clojure.lang.ExceptionInfo e {:err (:rf.error/id (ex-data e))})
                          (catch Throwable e {:err (.getMessage e)}))]
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

    ;; EP-0012 (rf2-du585y) — `:rf/path` algebra LAW ops. The frozen path
    ;; laws (Conventions §Path laws / EP-0012 §1361-1374) become a NON-CLOJURE
    ;; conformance target: a port that implements only CEDN bytes + template
    ;; instantiation no longer passes EP-0012 conformance. Pure ops over EDN
    ;; values — no runtime, no frame. `:path-over` carries a NAMED transform
    ;; (`:fn`) so the fixture stays pure data: `:inc` increments the focus,
    ;; `:wrap-vec` wraps it in `[:wrapped <focus>]`.
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
                      (catch Throwable e (str "<error: " (.getMessage e) ">")))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str (name (:call call)) " " (pr-str (dissoc call :call :expect))
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    ;; EP-0015 (rf2-t55hxg.2) — `:project-egress`. Pin the centralised
    ;; egress projector's observable contract host-agnostically (Spec 015
    ;; §Tests). The call carries the record/value under `:value`, the
    ;; resolved profile under `:rf.egress/profile`, and an optional `:frame`
    ;; (a frame the FIXTURE registered via `:fixture/frames` /
    ;; `:fixture/app-marks` so its classification is in place). `:expect` is
    ;; the literal projected result. Used by the off-box-omits-event-args
    ;; (issue 4) and fail-closed-no-frame (no `:rf/default` synthesis)
    ;; fixtures. Pure — `project-egress` reads only the frame's installed
    ;; classification registry.
    ;;
    ;; When the call OMITS `:frame`, bind `*current-frame*` to nil so the
    ;; projection is GENUINELY frameless — the fail-closed posture (no
    ;; `:rf/default` synthesis; the delegated walker redacts the whole tree).
    ;; Otherwise the runner's ambient scope frame would leak in and the
    ;; frameless contract would not be exercised. A call WITH `:frame`
    ;; resolves against that registered frame's installed classification.
    :project-egress
    (let [has-frame? (contains? call :frame)
          opts       (cond-> {:rf.egress/profile (:rf.egress/profile call)}
                       has-frame? (assoc :frame (:frame call)))
          run        (fn [] (projection/project-egress (:value call) opts))
          actual     (try (if has-frame?
                            (run)
                            (binding [frame/*current-frame* nil] (run)))
                          (catch Throwable e (str "<error: " (.getMessage e) ">")))
          expect     (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "project-egress " (pr-str (:value call))
                       " under " (:rf.egress/profile call)
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    ;; EP-0015 (rf2-t55hxg.2) — `:redact-headers`. Pin the HTTP header
    ;; carrier denylist (Spec 014 §Privacy / EP-0015 §3): a frame-local
    ;; carrier name redacts IN ADDITION to the immutable built-in defaults,
    ;; and no frame can remove a default. The call carries the headers map
    ;; under `:headers` and the frame's lower-cased carrier extension set
    ;; under `:frame-extras` (or omits it for defaults-only). `:expect` is
    ;; the literal redacted headers map. Pure — `redact-headers` is a leaf fn.
    :redact-headers
    (let [actual (try (http-privacy-headers/redact-headers
                        (:headers call) (:frame-extras call))
                      (catch Throwable e (str "<error: " (.getMessage e) ">")))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "redact-headers " (pr-str (:headers call))
                       " extras " (pr-str (:frame-extras call))
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    ;; EP-0015 (rf2-t55hxg.2) — `:ssr-apply-policy`. Pin the SSR hydration-
    ;; payload allowlist-first projection (Spec 011 §14): an allowlist
    ;; `:payload [<kws>]` ships ONLY the listed top-level app-db keys; an
    ;; unlisted key does not cross even if unclassified. The call carries
    ;; the source app-db under `:app-db` and the policy opts under `:opts`;
    ;; `:expect` is the projected slice, OR `:expect-error` is the
    ;; `:rf.error/id` the fail-closed validator throws on a missing /
    ;; malformed policy. Pure — `apply-policy` is a leaf fn.
    :ssr-apply-policy
    (let [want-error (:expect-error call)
          result     (try {:ok (ssr-payload-policy/apply-policy
                                  (:app-db call) (:opts call))}
                          (catch clojure.lang.ExceptionInfo e
                            {:err (:rf.error/id (ex-data e))})
                          (catch Throwable e {:err (.getMessage e)}))]
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

    ;; EP-0014 (rf2-k0meap.3; rf2-djofbh) — `:derivation-graph`. Compose the
    ;; cross-family derivation/process graph over the FULL contributor set
    ;; (subs + flows + resources + routes + machines — rf2-djofbh: no longer
    ;; hard-coded to subs+machines, which let the broad
    ;; `:derivation/algebra-graph` claim overclaim the EP-0014 surface) and
    ;; assert NORMALIZED node + edge shapes: each `:expect-node` is a SUBMAP
    ;; matched against the composed node at `:id` (lowering +
    ;; storage/evaluation/lifecycle classification + `:refinement`); each
    ;; `:expect-edge` must be PRESENT and each `:expect-absent-edge` ABSENT.
    ;; `:expect-graph` (rf2-ska8zk) is a SUBMAP matched against the WHOLE
    ;; graph map — it pins the GRAPH-LEVEL `:mode`/`:frame` shape so a live
    ;; graph that drops or misreports `{:mode :live :frame …}` fails even
    ;; when its nodes/edges are correct. A family whose fixture registers
    ;; nothing contributes no nodes (the composer's present-family-only
    ;; discipline), so the subs+machines subset fixture is unaffected.
    ;; `:mode :static` (default) or `:live` (defaulting the frame to
    ;; `:rf/default`, the corpus single-frame scope, when the call omits
    ;; `:frame`). Mirror of the CLJS runner.
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
                       (clojure.string/join "\n    " fails)))})

    ;; unknown call op
    {:passed? false :detail (str "unknown :call form: " (:call call))}))

(defn run-fixture [fixture]
  (try
    (reset-runtime!)
    (let [fid          (:fixture/id fixture)
          ;; Register the trace listener FIRST so registration-time warnings
          ;; (e.g. :rf.warning/route-shadowed-by-equal-score from reg-route)
          ;; are captured. realise-handlers and register-routes! run after.
          traces       (collect-traces fid)
          ;; rf2-wxe9t — capture the always-on error-emit substrate's
          ;; tight error-records in parallel with the trace listener.
          ;; The two paths emit from ONE normative site in the router's
          ;; pipeline-exception path (`re-frame.router/emit-pipeline-
          ;; exception!`) but carry DIFFERENT shapes: trace gets the
          ;; `:operation`/`:tags` envelope; the substrate listener gets
          ;; the tight `{:error :event :event-id :frame :time :exception
          ;; :elapsed-ms}` record with handler-meta `:sensitive?`
          ;; redaction already applied to `:event`.
          err-records  (collect-error-emit-records! fid)
          _            (realise-handlers fixture)
          _            (register-routes! fixture)
          ;; rf2-djofbh — resources register before reg-frame / dispatches so
          ;; the route-owned activation static graph + any live fetch see them.
          _            (register-resources! fixture)
          frame-config (or (:fixture/frame-config fixture) {})
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
          ;; Mirror of the CLJS runner.
          scope-frame  (if (seq frames-spec)
                         (:id (first frames-spec))
                         :rf/default)
          ;; reset-runtime! already created :rf/default WITHOUT any :initial-events.
          ;; reg-frame against an existing id is a surgical update that doesn't
          ;; re-fire :initial-events per Spec 002. We destroy first so :initial-events
          ;; fire when re-registered with the fixture's config.
          _            (rf/destroy-frame! :rf/default)
          ;; app-schema registrations — fixture's :fixture/registry :app-schemas
          ;; is a {path schema} map (per rf2-cq1ak the key is :app-schemas;
          ;; app-db schemas are NOT a registrar kind). Per Spec 010 validation
          ;; runs after each :db commit. Must be registered AFTER destroy-frame!
          ;; (which wipes the per-frame schema side-table via the rf2-wkxng /
          ;; rf2-6m0se on-frame-destroyed! hook) and BEFORE reg-frame so
          ;; the fixture's :initial-events run with schemas in place.
          ;; `reg-app-schema` is frame-scoped — establish the scope explicitly.
          _            (rf/with-frame scope-frame
                         (doseq [[path schema] (get-in fixture [:fixture/registry :app-schemas])]
                           (rf/reg-app-schema path {:schema schema})))
          _            (cond
                         (seq frames-spec)
                         ;; Multi-frame fixture: register each declared frame.
                         (doseq [f frames-spec]
                           (rf/reg-frame (:id f) (dissoc f :id)))
                         :else
                         (rf/reg-frame :rf/default frame-config))
          ;; Data-classification path-marks (Spec 015 §App-db marks;
          ;; rf2-s2s3xv) run AFTER reg-frame (the frame's runtime-db elision
          ;; slot exists) and BEFORE realise-flows! so a flow input that
          ;; overlaps a marked path inherits the propagated output mark at
          ;; reg-flow time. `add-marks` / `set-marks` are frame-scoped.
          _            (realise-app-marks! fixture scope-frame)
          ;; Flow registration runs AFTER reg-frame: per Spec 013 flows
          ;; are frame-scoped, and the rf2-wbtjn destroy-frame! teardown
          ;; hook would wipe any flows registered before the destroy.
          ;; `reg-flow` is frame-scoped — establish the scope explicitly.
          _            (rf/with-frame scope-frame
                         (realise-flows! fixture))
          dispatches   (or (:fixture/dispatches fixture) [])
          sub-registry (get-in fixture [:fixture/registry :sub] {})
          ;; EP-0017 (rf2-d8mvke.3): a dispatch map may carry
          ;; `:expect-error <:rf.error/id>` to assert the dispatch THROWS an
          ;; `ex-info` whose `:rf.error/id` ex-data slot equals the expected
          ;; id (the cofx delivery errors + the `:rf.world/inputs` retirement
          ;; throw from context assembly / the dispatch boundary, escaping
          ;; `dispatch-sync`). Mirrors the Mode-B `:reg-machine` /
          ;; `:path-instantiate` `:expect-error` convention. Failures
          ;; (no-throw, wrong id, or generic throw) accumulate here and fail
          ;; the fixture; an UNEXPECTED throw (no `:expect-error`) still
          ;; propagates to the outer fixture-level catch.
          dispatch-error-failures (atom [])]
      ;; EP-0002 (rf2-9o48ih) — bare `dispatch-sync` resolves its target
      ;; from the `scope-frame` established here; see the registration-
      ;; time note above for the carried-invariant rationale.
      (rf/with-frame scope-frame
      (doseq [ev dispatches]
        (cond
          (map? ev)
          (cond
            ;; Harness teardown step `{:destroy-frame <frame-id>}` per
            ;; Spec 002 §Destroy + Spec 005 §Cross-Spec Interactions §1
            ;; — invoke `destroy-frame!` against the named frame; the
            ;; machine-cascade teardown hook + sub-cache disposal +
            ;; `:frame/destroyed` trace all fire here. Mirrors the
            ;; flows-conformance runner's shape (rf2-gmrks).
            (contains? ev :destroy-frame)
            (rf/destroy-frame! (:destroy-frame ev))

            ;; Harness re-registration step `{:reg-sub <sub-id> :body
            ;; <body>}` per Cross-Spec Interaction §18 (rf2-qei5a). The
            ;; runner realises the body via the conformance DSL and
            ;; calls `reg-sub` against the existing sub id — the
            ;; registrar's replacement hook fires (cache invalidation,
            ;; `:rf.registry/handler-replaced` trace). Subsequent
            ;; dispatches resolve against the NEW body.
            (contains? ev :reg-sub)
            (let [sub-id        (:reg-sub ev)
                  steps         (:body ev)
                  {:keys [kind inputs body]} (conformance/realise-sub steps)
                  sub-meta      (get sub-registry sub-id {})
                  ;; Per the realise-handlers branch above (rf2-jwm4):
                  ;; the public re-frame.core/reg-sub is a macro on JVM
                  ;; for source-coord capture, so we route through the
                  ;; fn-form re-frame.subs/reg-sub here. Source-coord
                  ;; capture is intentionally bypassed for this
                  ;; fixture-synthesised re-registration.
                  reg-sub-fn (requiring-resolve 're-frame.subs/reg-sub)
                  reg-runtime-sub-fn (requiring-resolve 're-frame.subs/reg-runtime-sub)]
              (case kind
                :layer-1 (if (seq sub-meta)
                           (reg-sub-fn sub-id sub-meta body)
                           (reg-sub-fn sub-id body))
                ;; EP-0001 (rf2-vzld77) — runtime-db fixture sub.
                :runtime-db (if (seq sub-meta)
                              (reg-runtime-sub-fn sub-id sub-meta body)
                              (reg-runtime-sub-fn sub-id body))
                :layer-2 (apply reg-sub-fn sub-id
                                (concat (when (seq sub-meta) [sub-meta])
                                        (interleave (repeat :<-) inputs)
                                        [body]))))

            ;; EP-0017 (rf2-d8mvke.3): a dispatch asserting a boundary /
            ;; context-assembly THROW. `:expect-error` is the
            ;; `:rf.error/id` the dispatch must raise (e.g. the cofx
            ;; delivery errors, or the `:rf.world/inputs` retirement). The
            ;; throw escapes `dispatch-sync` (the cofx errors throw during
            ;; context assembly, the retirement at the dispatch boundary —
            ;; neither is captured into the chain), so the runner catches it
            ;; here and compares the ex-data `:rf.error/id`. The remaining
            ;; opts (e.g. `:rf.cofx` / `:rf.world/inputs`) pass through to
            ;; `dispatch-sync`.
            (contains? ev :expect-error)
            (let [{event :event want :expect-error} ev
                  opts   (dissoc ev :event :expect-error)
                  got    (try (rf/dispatch-sync event opts) ::no-throw
                              (catch clojure.lang.ExceptionInfo e
                                (:rf.error/id (ex-data e)))
                              (catch Throwable e e))]
              (cond
                (= got ::no-throw)
                (swap! dispatch-error-failures conj
                       (str "dispatch " (pr-str event) " expected to throw "
                            want " but did not throw"))
                (not= got want)
                (swap! dispatch-error-failures conj
                       (str "dispatch " (pr-str event) " expected error " want
                            " but got " (if (instance? Throwable got)
                                          (str "a non-ExceptionInfo throw: "
                                               (some-> ^Throwable got .getMessage))
                                          got)))))

            :else
            (let [{event :event :as opts} ev]
              (rf/dispatch-sync event (dissoc opts :event))))

          ;; Convention: :rf/hydrate events dispatch with :source :ssr-hydration
          ;; per Spec 011 §The :rf/hydrate event. Real clients pass this on the
          ;; hydrate-call site; the conformance runner stamps it for the user.
          (and (vector? ev) (= :rf/hydrate (first ev)))
          (rf/dispatch-sync ev {:source :ssr-hydration})

          :else
          (rf/dispatch-sync ev))))
      ;; :fixture/render-after-hydrate — for SSR hydration fixtures the
      ;; harness simulates the client-side first render. The runtime
      ;; (re-frame.ssr/verify-hydration!) owns the hash comparison and
      ;; :rf.ssr/hydration-mismatch trace; we just feed it the simulated
      ;; client hash. server-hash is read from the
      ;; [:rf.runtime/ssr :hydration] metadata that :rf/hydrate
      ;; stashed in runtime-db.
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
              frame-id        (:rf/frame-id payload :rf/default)
              verify-fn       (requiring-resolve 're-frame.ssr/verify-hydration!)]
          (when (and verify-fn client-hash server-hash)
            (verify-fn frame-id client-hash
                       {:first-diff-path first-diff-path
                        ;; Fixture handlers override :rf/hydrate without
                        ;; stashing metadata, so we feed the server hash
                        ;; explicitly instead of reading
                        ;; [:rf.runtime/ssr :hydration].
                        :server-hash     server-hash}))))
      ;; :fixture/calls — pure-function assertions (match-url, route-url,
      ;; machine-transition, etc.). Run AFTER dispatches so any
      ;; handler-mediated state is in place.
      (let [machines      (realise-machine-handlers fixture)
            calls         (or (:fixture/calls fixture) [])
            call-results  (mapv #(run-call % machines) calls)
            call-failures (filter (complement :passed?) call-results)]
        (when (seq call-failures)
          ;; Surface the first failure as a fixture-level error so the
          ;; reporter shows it.
          (throw (ex-info (str "calls failed: "
                               (clojure.string/join "; "
                                 (map :detail call-failures)))
                          {:call-failures call-failures}))))
      ;; Per Spec 011 §Server error projection — drain any pending
      ;; error projections so :rf/response carries the projector's
      ;; :status before we snapshot final-app-db. The runtime's
      ;; trace listener buffers error events; this flushes them
      ;; just before the conformance check reads app-db.
      (let [apply-fn (requiring-resolve 're-frame.ssr/apply-error-projection!)]
        (when apply-fn
          (doseq [fid (frame/frame-ids)]
            (try (apply-fn fid)
                 (catch Throwable _ nil)))))
      (let [expect       (or (:fixture/expect fixture) {})
            ;; Single-frame: :final-app-db. Multi-frame: :final-app-dbs as
            ;; {frame-id db}.
            expected-db  (:final-app-db expect)
            expected-dbs (:final-app-dbs expect)
            ;; EP-0017 (rf2-d8mvke.3): NEGATIVE app-db assertion. A vector of
            ;; `get-in`-shaped paths that must be ABSENT from the final
            ;; (`:rf/default`) app-db — the key at the path's tip must not be
            ;; present (distinguished from present-with-nil). `:final-app-db`'s
            ;; submap matching tolerates extra keys, so it cannot pin
            ;; declared-only delivery (an over-delivering port still passes);
            ;; this absent-path check is what FAILS such a port.
            expected-absent (:final-app-db-absent expect)
            final-db     (rf/app-db-value :rf/default)
            final-dbs    (when expected-dbs
                           (into {}
                                 (for [[fid _] expected-dbs]
                                   [fid (rf/app-db-value fid)])))
            ;; EP-0001 (rf2-vzld77): durable framework runtime state
            ;; (machine snapshots / route slice / elision regs / SSR
            ;; hydration metadata) lives in the RUNTIME-DB partition, so
            ;; fixtures assert it under `:final-runtime-db` (single frame)
            ;; / `:final-runtime-dbs` (multi-frame), addressed by the
            ;; `:rf.runtime/*` keys. Submap-matched against the live
            ;; runtime-db projection.
            expected-rt  (:final-runtime-db expect)
            expected-rts (:final-runtime-dbs expect)
            final-rt     (rf/runtime-db-value :rf/default)
            final-rts    (when expected-rts
                           (into {}
                                 (for [[fid _] expected-rts]
                                   [fid (rf/runtime-db-value fid)])))
            ;; Realise sub-checks BEFORE trace-failures: subscribing computes
            ;; the reaction body, which may emit :rf.error/sub-exception traces
            ;; that the trace-emissions check expects to see.
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                (let [[frame-id qv] (resolve-sub query-v)]
                  {:query    query-v
                   :expected expected-val
                   :actual   (rf/subscribe-once frame-id qv)})))
            ;; EP-0017 (rf2-d8mvke.3): NEGATIVE app-db path assertions. Each
            ;; path's tip key must be ABSENT from the final app-db. Uses a
            ;; sentinel `get-in` so a present-with-nil leaf is NOT mistaken
            ;; for absent (the over-delivery case might legitimately deliver
            ;; a nil-valued coeffect, which is still a delivery).
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
            ;; rf2-wxe9t — substrate-side error-emit records (the
            ;; corpus-wide listener fan-out). The matcher mirrors
            ;; `check-trace-emissions` (positional partial-submap).
            ;; A fixture without `:error-emit-records` yields nil
            ;; failures and does not constrain the substrate.
            error-emit-failures (when (contains? expect :error-emit-records)
                                  (check-error-emit-records
                                    @err-records
                                    (:error-emit-records expect)))
            ;; rf2-v0jwt — :epoch-records — assert against the named frame's
            ;; recorded :rf/epoch-record ring. Each entry is a partial-shape
            ;; submap; useful for pinning the halted-cascade :outcome /
            ;; :halt-reason contract from fixtures like
            ;; drain-depth-limit.edn.
            epoch-failures (when-let [er (:epoch-records expect)]
                             (check-epoch-records er))
            ;; :effects-routed — per `spec/conformance/README.md` §Fixture
            ;; lifecycle: every fixture MAY assert the fx pairs that the
            ;; runtime routed. The runtime emits `:rf.fx/handled` (and
            ;; `:rf.error/fx-handler-exception` on throw) carrying the
            ;; resolved (post-override) fx-id and the fx-args; we derive
            ;; the actual routings from those and order-preserving subset-
            ;; match against the fixture's expectation.
            actual-effects (effects-routed-from-traces @traces)
            expected-effects (when (contains? expect :effects-routed)
                               (normalise-effects-routed (:effects-routed expect)))
            effects-failures (when expected-effects
                               (check-effects-routed actual-effects expected-effects))
            ;; SSR error-projection contract — Spec 011 §Server error
            ;; projection. Find the most recent :error trace and project
            ;; it via the active projector for :rf/default; assert the
            ;; result equals the fixture's :ssr/public-error.
            expected-public-error (:ssr/public-error expect)
            public-error-check
            (when expected-public-error
              (let [project-error (requiring-resolve 're-frame.ssr/project-error)
                    error-events  (filter #(= :error (:op-type %)) @traces)
                    last-error    (last error-events)]
                (if (and project-error last-error)
                  (let [actual (project-error :rf/default last-error)]
                    {:expected expected-public-error
                     :actual   actual
                     :passed?  (= expected-public-error actual)})
                  {:expected expected-public-error
                   :actual   nil
                   :passed?  false})))]
        (trace/clear-listeners!)
        ;; rf2-wxe9t — drop just this fixture's error-emit recorder so
        ;; it does not leak into the next fixture's drains. The
        ;; reset-runtime! call at the top of the next fixture also
        ;; clears the registry; this is belt-and-braces.
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
                            (empty? epoch-failures)
                            (empty? error-emit-failures)
                            (or (nil? public-error-check)
                                (:passed? public-error-check)))
         :absent-failures         absent-failures
         :dispatch-error-failures @dispatch-error-failures
         :final-db     final-db
         :final-dbs    final-dbs
         :expected-db  expected-db
         :expected-dbs expected-dbs
         :final-rt     final-rt
         :final-rts    final-rts
         :expected-rt  expected-rt
         :expected-rts expected-rts
         :sub-checks   sub-checks
         :trace-failures trace-failures
         :effects-failures   effects-failures
         :actual-effects     actual-effects
         :expected-effects   expected-effects
         :epoch-failures     epoch-failures
         :error-emit-failures error-emit-failures
         :actual-error-emit-records @err-records
         :public-error-check public-error-check}))
    (catch Throwable e
      {:fixture-id (:fixture/id fixture)
       :passed?    false
       :error      (.getMessage e)
       :exception  e})))

;; ---- the test entrypoint --------------------------------------------------

(deftest run-conformance-corpus
  (let [results (atom [])]
    (doseq [[fname fixture] (all-fixtures)]
      (cond
        (:fixture/load-error fixture)
        (swap! results conj {:fixture-id fname
                             :skipped? true
                             :reason "load error"
                             :error (:fixture/load-error fixture)})

        ;; Spec-version compatibility — per `spec/conformance/README.md`
        ;; §Versioning. A fixture targeting a spec version this build
        ;; doesn't claim is skipped with an explicit signal rather than
        ;; run against an outdated runtime.
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
    (let [all       @results
          run       (filter (complement :skipped?) all)
          passed    (filter :passed? run)
          failed    (remove :passed? run)
          skipped   (filter :skipped? all)]
      ;; rf2-3hamsq — non-empty floor. The lone (zero? (count failed))
      ;; below passes GREEN over an empty / fully-skipped / orphaned
      ;; corpus (wrong cwd, fixtures-dir rename, or a capability-vocab
      ;; rename that orphans every fixture) — verifying NOTHING. Assert
      ;; that fixtures actually executed:
      ;;   - (pos? (count run)) catches the fully-empty case;
      ;;   - the expected-minimum (>= 150) catches partial mass-orphaning
      ;;     without pinning an exact count (today's runnable count is 186
      ;;     of 188 total; the corpus grows over time).
      (is (pos? (count run))
          "at least one claim-applicable conformance fixture must have executed")
      (is (>= (count run) 150)
          (str "core conformance corpus runnable-fixture floor (>= 150): only "
               (count run) " executed — a fixtures-dir/cwd fault or a "
               "capability-vocab rename has orphaned the corpus."))
      ;; Silent-on-success (rf2-try1x): the corpus summary only prints
      ;; when there are failures. The `is` assertion below carries the
      ;; pass/fail signal; the stats and skip-list are diagnostic
      ;; context for failure triage. On green, agents reading test
      ;; output don't burn ~30 lines of stats per artefact.
      (when (seq failed)
        (println)
        (println "Conformance corpus:")
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
          (when (seq (:epoch-failures f))
            (doseq [erf (:epoch-failures f)]
              (println "    epoch:" erf)))
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
      ;; claimed-applicable fixture passes. Skipped fixtures (out-of-claim
      ;; capabilities, or fixtures targeting a spec version we don't
      ;; claim) do not count toward pass/fail — they are explicitly
      ;; reported but neither claim conformance nor block it.
      (is (zero? (count failed))
          (str "All claimed-applicable conformance fixtures must pass; "
               (count failed) " failed.")))))

;; rf2-ska8zk — NEGATIVE self-test for the `:expect-graph` graph-level guard.
;;
;; The broad derivation-graph fixture (derivation-graph-algebra-full.edn) now
;; pins the live graph's `{:mode :live :frame :rf/default}` shape via
;; `:expect-graph`. This self-test proves the GUARD itself bites: a
;; `:derivation-graph` call whose `:expect-graph` misreports the live graph's
;; `:mode` or `:frame` must FAIL the runner (`:passed? false`) — otherwise the
;; fixture's live-mode assertion is a no-op and the broad capability claim
;; would silently overclaim against [Derivations.md] §Live graph.
;;
;; With an empty registrar the live composer returns
;; `{:mode :live :frame :rf/default :nodes {} :edges []}`, so the runner's
;; graph-level submap check is exercised directly with no fixture
;; registration needed — the correct shape passes, every wrong/missing-key
;; variant fails.
(deftest derivation-graph-expect-graph-guard
  (reset-runtime!)
  (let [run (fn [call] (run-call call))]
    ;; CORRECT live shape passes.
    (is (:passed? (run {:call :derivation-graph :mode :live
                        :expect-graph {:mode :live :frame :rf/default}}))
        "the true live graph shape must pass")
    ;; WRONG :mode (claims :static for a live graph) must FAIL.
    (is (not (:passed? (run {:call :derivation-graph :mode :live
                             :expect-graph {:mode :static}})))
        "a wrong live graph :mode must fail the runner")
    ;; WRONG :frame must FAIL.
    (is (not (:passed? (run {:call :derivation-graph :mode :live
                             :expect-graph {:mode :live :frame :rf/other}})))
        "a wrong live graph :frame must fail the runner")
    ;; The static graph carries NO :frame; asserting one must FAIL (proves
    ;; the live :frame assertion is not vacuously true on the static path).
    (is (not (:passed? (run {:call :derivation-graph :mode :static
                             :expect-graph {:mode :static :frame :rf/default}})))
        "asserting a :frame on the frame-agnostic static graph must fail")
    ;; CORRECT static shape passes.
    (is (:passed? (run {:call :derivation-graph :mode :static
                        :expect-graph {:mode :static}}))
        "the true static graph shape must pass")))
