(ns re-frame.conformance-runner
  "Host-neutral conformance corpus runner — the SINGLE authority for the
  re-frame2 conformance-fixture harness (rf2-xurchk).

  Before this namespace the JVM runner (`re-frame.conformance-test`) and
  the CLJS runner (`re-frame.conformance-corpus-cljs-test`) DUPLICATED
  ~1,289 aligned lines of capability claims, fixture realisation, call
  execution, matchers, result assembly, and reporting. The copies drifted
  into a P1 correctness gap: eight runnable fixtures carry `:epoch-records`
  expectations, but ONLY the JVM copy defined/invoked `check-epoch-records`
  — so CLJS reported conformance while SILENTLY IGNORING those
  expectations. `conformance_dsl_*` was a smaller drifting mirror.

  This `.cljc` owns every host-neutral concern:

    - the claimed-capability / claimed-spec-version / known-skipped sets
    - fixture capability classification + spec-version gating
    - handler-body / sub / cofx / fx / route / view / machine / flow /
      classification-effect / app-schema realisation
    - the `:fixture/calls` execution (`run-call`)
    - all `:fixture/expect` matchers (app-db, runtime-db, subs, traces,
      effects-routed, error-emit-records, epoch-records, absent-paths,
      SSR public-error)
    - `run-fixture` orchestration
    - the corpus body (`run-corpus`) with three-way capability
      classification, EXPECTATION-KEY fail-loud (rf2-xurchk), reporting,
      and the pass/floor/count `is` assertions

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

  Production source and bundle graphs are UNCHANGED — this is a test-only
  namespace under `core/test`, `:require`d only by the two leaf runners.

  ## Fail-loud expectation keys (rf2-xurchk)

  The core corpus runner OWNS a fixed set of `:fixture/expect` matchers
  (`corpus-checked-expect-keys`). Sibling conformance runners (flow / SSR /
  frame-lifecycle) own additional expectation keys on the SAME fixtures
  (`sibling-owned-expect-keys`, the delegated allowlist). A runnable
  fixture whose `:fixture/expect` names a key in NEITHER set is a typo /
  drift — the suite FAILS rather than silently ignoring it. This is the
  `rf2-a3q1r` capability-allowlist discipline applied to expectation keys;
  it is the structural guard against re-introducing the exact silent-ignore
  bug this consolidation fixes."
  (:require
    #?(:clj  [clojure.test :refer [is]]
       :cljs [cljs.test :refer-macros [is]])
    [clojure.string :as str]
    [re-frame.core :as rf]
    [re-frame.elision :as elision]
    [re-frame.error-emit :as error-emit]
    [re-frame.frame :as frame]
    [re-frame.registrar :as registrar]
    [re-frame.subs :as subs]
    [re-frame.conformance :as conformance]
    [re-frame.identity :as identity]
    [re-frame.path :as path]
    [re-frame.projection :as projection]
    [re-frame.http.privacy-headers :as http-privacy-headers]
    [re-frame.ssr.payload-policy :as ssr-payload-policy]
    [re-frame.routing :as routing]
    [re-frame.routing.match :as routing-match]
    [re-frame.ssr :as ssr]
    [re-frame.machines :as machines]
    [re-frame.resources :as resources]
    [re-frame.resources.registry :as resources-registry]
    [re-frame.resources.subs :as resources-subs]
    [re-frame.image :as image]
    [re-frame.image-assembly :as image-assembly]
    [re-frame.derivation.graph :as dgraph]
    [re-frame.subs.tooling :as subs-tooling]
    [re-frame.machines.tooling :as machines-tooling]
    [re-frame.flows.tooling :as flows-tooling]
    [re-frame.resources.tooling :as resources-tooling]
    [re-frame.routing.tooling :as routing-tooling]
    ;; Side-effect require: publishing the epoch late-bind hooks
    ;; (`:epoch/settle!`, `:epoch/epoch-history`, `:epoch/clear-history!`,
    ;; …) at ns-load is what makes the router commit `:rf/epoch-record`s and
    ;; `rf/epoch-history` observe them. Required HERE (not just in the JVM
    ;; leaf) so BOTH hosts record + check `:epoch-records` — the crux of
    ;; rf2-xurchk. On the CLJS node-test build the epoch artefact is on the
    ;; classpath (`epoch/src`); on the JVM core test build it is likewise
    ;; reachable (the JVM leaf already required it pre-consolidation).
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
    ;; :core/trace + :core/frame — rf2-3pnob. Pattern-required surfaces per
    ;; the README's §Capability tagging list and worked-example table.
    :core/trace
    :core/frame
    ;; EP-0026 (rf2-qp8qi8) — the image-API surface: `:select-ns` selection,
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
    :fsm/tags                                         ;; rf2-ee0d (Nine States Stage 1)
    :fsm/parallel-regions                             ;; rf2-l67o (Nine States Stage 2)
    :fsm/final-states                                 ;; rf2-gn80 — :final? + :on-done + :output-key
    :fsm/history                                      ;; rf2-mle6e — first-class history pseudo-states
    :fsm/registration-validation                      ;; rf2-vf5cf — registration-error taxonomy via :reg-machine
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
    ;; `:schema` path: a declared recordable value that fails its
    ;; registration's `:schema` emits `:rf.error/cofx-value-invalid` and
    ;; throws during context assembly.
    :schemas/cofx
    :routing/ranking
    ;; rf2-5u1r6a — path-pattern registration-validation (Mode-B :reg-route).
    :routing/pattern-validation
    :routing/fragment
    :routing/blocking
    :routing/nav-token
    ;; EP-0012 (rf2-qyb9l1) — CEDN-1 canonical-identity + `:rf/path` algebra.
    :identity/cedn1
    :actor/spawn-destroy                              ;; rf2-mtq4h — renamed from :actor/spawn to align with spec vocabulary
    :actor/declarative-spawn
    :actor/spawn-and-join                             ;; rf2-6vmw / rf2-er0t
    :actor/system-id                                  ;; rf2-suue / rf2-ecv4
    ;; :actor/timeout retired per rf2-3y3y — :fsm/delayed-after subsumes it.
    :flow/basic
    :flow/topo
    :flow/dirty-check
    :flow/toggle
    :flow/hot-reload
    ;; Spec 009 §Flow trace events / Spec 013 §Flow tracing (rf2-2s1o).
    :flow/trace
    ;; Spec 013 §Frame-scoping (rf2-29ovh).
    :flow/frame-scoped
    ;; Spec 014 — :rf.http/managed (rf2-z1mw).
    :rf.http/managed
    ;; Spec 015 §Data classification (rf2-s2s3xv).
    :data-classification/classification-effects
    ;; EP-0014 (rf2-k0meap.3) — the BROAD cross-family derivation graph.
    :derivation/algebra-graph
    ;; rf2-djofbh — the NARROW subs+machines subset claim.
    :derivation/algebra-graph-subs-machines
    ;; Spec 016 §Resources (rf2-rul3ov).
    :resources/ensure
    :resources/dedupe
    :resources/stale-suppression
    :resources/scope-fail-closed
    :resources/lease-gc
    :resources/keep-previous})

(def claimed-spec-versions
  "Fixture spec versions this implementation claims to conform against.
  Per `spec/conformance/README.md` §Versioning: a fixture whose
  `:fixture/spec-version` is NOT in this set is reported as skipped."
  #{"1.0"})

;; ---- known-skipped capabilities (rf2-a3q1r) ------------------------------
;;
;; A fixture declaring a capability not in `claimed-capabilities` AND not
;; here is a typo / claim-set drift and FAILS the suite (rather than being
;; silently skipped — the pre-rf2-a3q1r behaviour that masked at least one
;; bug). Adding a capability here is an explicit declaration that this build
;; INTENTIONALLY does not claim it.

(def known-skipped-capabilities
  "Capabilities this build INTENTIONALLY does not claim. Fixtures whose
  capabilities fall here are reported as out-of-claim skips but do not block
  the suite. Streaming SSR + render-tree-hash are gated by the dedicated
  ssr-artefact conformance runners, not this core corpus runner."
  #{:ssr/suspense-boundary
    :ssr/hydration-payload
    :ssr/chunked-response
    :ssr/render-tree-hash})

;; ---- fail-loud expectation keys (rf2-xurchk) ------------------------------
;;
;; The core corpus runner OWNS matchers for exactly these `:fixture/expect`
;; keys. This is the SINGLE definition — both hosts check the same keys.

(def corpus-checked-expect-keys
  "The `:fixture/expect` keys this runner has a matcher for. Any runnable
  fixture using ONLY these (plus sibling-owned, below) is fully graded."
  #{:final-app-db
    :final-app-dbs
    :final-app-db-absent
    :final-runtime-db
    :final-runtime-dbs
    :sub-values
    :trace-emissions
    :error-emit-records
    :epoch-records
    :effects-routed
    :ssr/public-error})

(def sibling-owned-expect-keys
  "`:fixture/expect` keys the core corpus runner intentionally does NOT
  check because a DEDICATED sibling conformance runner owns them on the same
  fixture — flow-conformance (`:flow-*`, `:expect-trace-stream`,
  `:trace-absent`), ssr-conformance (`:ssr/request-result`, `:ssr/active-head`,
  `:ssr/rendered-head-contains`, `:ssr/html-attr-present`), and the
  frame-lifecycle / destroy runners (`:on-destroy-*`, `:sub-graph-topology`,
  `:trace-not-emitted`, `:registrar-flow-slots-after`). Every key here was
  observed on a runnable corpus fixture whose OTHER (core-owned)
  expectations this runner grades; the key is delegated, NOT ignored. A
  fixture may only carry a key here or in `corpus-checked-expect-keys` —
  anything else fails loud."
  #{:expect-trace-stream
    :flow-graph-topology
    :flow-last-inputs-after
    :flow-recompute-counts
    :flow-registry-after
    :registrar-flow-slots-after
    :on-destroy-effects
    :on-destroy-emissions
    :sub-graph-topology
    :trace-absent
    :trace-not-emitted
    :ssr/active-head
    :ssr/html-attr-present
    :ssr/rendered-head-contains
    :ssr/request-result})

(defn unknown-expect-keys
  "Return the seq of `:fixture/expect` keys that are neither corpus-checked
  nor sibling-owned — i.e. keys this runner would SILENTLY IGNORE. A
  non-empty result FAILS the fixture (rf2-xurchk fail-loud). Empty for every
  key currently in the corpus."
  [fixture]
  (remove #(or (contains? corpus-checked-expect-keys %)
               (contains? sibling-owned-expect-keys %))
          (keys (:fixture/expect fixture {}))))

;; ---- fixture classification -----------------------------------------------

(defn runnable?
  "True if the fixture's claimed capabilities are a subset of ours."
  [fixture]
  (let [caps (or (:fixture/capabilities fixture) #{})]
    (every? claimed-capabilities caps)))

(defn classify-capabilities
  "Per rf2-a3q1r, partition a fixture's `:fixture/capabilities` into
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
  accepted (legacy fixtures pre-versioning)."
  [fixture]
  (let [v (:fixture/spec-version fixture)]
    (or (nil? v) (contains? claimed-spec-versions v))))

;; ---- handler-body realisation ---------------------------------------------

(defn- collect-cofx-keys
  "Walk steps and pull every cofx-id referenced via `[:cofx-key K]`. Used by
  `realise-handlers` to auto-wire the consuming event's `:rf.cofx/requires`
  declaration (EP-0017 model — rf2-mrp8jg / rf2-g25p). Returns a set of K."
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
  handler declares it via `:rf.cofx/requires`. The `:set` value passes
  through `eval-value*` (rf2-g25p) so reflection forms resolve; the last
  `:set` wins (single-injection convention)."
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

;; Forward declaration — realise-machine-handlers is defined below (alongside
;; the run-call :machine-transition path). Per rf2-msd4 the same realised
;; action/guard maps feed both the in-memory `machine-transition` callsite
;; and the registry `reg-machine` registrations.
(declare realise-machine-handlers)

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
          ;; — its VALUE rides the dispatch token via `:rf.cofx`. Post-#4104,
          ;; `reg-cofx` rejects `provided? true` + a supplier, so register
          ;; without one.
          (if (:provided? meta)
            (rf/reg-cofx cofx-id meta)
            (rf/reg-cofx cofx-id meta (realise-cofx-supplier body))))))
    ;; event registrations. A body that reads `[:cofx-key K]` declares the
    ;; consumed coeffect ids via `:rf.cofx/requires` (fx-only — a cofx-reading
    ;; body routes to an `:fx` handler).
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
          ;; A :db-kind handler is `(fn [db event] new-db)`; adapt it to the
          ;; single form by reading db from coeffects and lowering the returned
          ;; db into a `{:db …}` effect — same observable behaviour.
          :db (let [h (fn [{:keys [db]} ev] {:db (handler db ev)})]
                (if (seq event-meta)
                  (rf/reg-event id event-meta h)
                  (rf/reg-event id h)))
          :fx (if (seq event-meta)
                (rf/reg-event id event-meta handler)
                (rf/reg-event id handler)))))
    ;; sub registrations. Use the fn-form `subs/reg-sub` because the public
    ;; `rf/reg-sub` is a macro (source-coord capture) and a macro var isn't a
    ;; callable value; source-coord capture is intentionally bypassed for
    ;; these fixture-synthesised registrations.
    (doseq [[id steps] (get handlers-map :sub)]
      (let [{:keys [kind inputs body]} (conformance/realise-sub steps)
            sub-meta (get sub-registry id {})]
        (case kind
          :layer-1 (if (seq sub-meta)
                     (subs/reg-sub id sub-meta body)
                     (subs/reg-sub id body))
          ;; EP-0001 (rf2-vzld77): a `[:get [:rf.runtime/… …]]` fixture sub
          ;; reads the runtime-db partition — register via reg-runtime-sub.
          :runtime-db (if (seq sub-meta)
                        (subs/reg-runtime-sub id sub-meta body)
                        (subs/reg-runtime-sub id body))
          :layer-2 (apply subs/reg-sub id
                          (concat (when (seq sub-meta) [sub-meta])
                                  (interleave (repeat :<-) inputs)
                                  [body])))))
    ;; fx handlers — DSL bodies. Per rf2-yhfgf: an id with NO body in
    ;; :fixture/handlers but a meta in :fixture/registry is "declare the
    ;; dependency, leave the framework registration alone" — the harness does
    ;; NOT overwrite the framework-shipped fx with a noop.
    (let [adapter-helpers
          {:read-db!  (fn [frame-id]
                        (frame/frame-app-db-value frame-id))
           ;; EP-0001 (rf2-adwcv6): write the app-db PARTITION via
           ;; `swap-frame-db!` — `frame/app-db-container` is a READ-ONLY
           ;; projection over the one physical frame-state container.
           :write-db! (fn [frame-id new-db]
                        (frame/swap-frame-db! frame-id (constantly new-db)))
           :dispatch! (fn [event frame-id]
                        (rf/dispatch event {:frame frame-id}))
           ;; Per Cross-Spec Interaction §14 (rf2-60szl): dispatch-sync from an
           ;; fx handler body trips the router's in-drain guard.
           :dispatch-sync! (fn [event frame-id]
                             (rf/dispatch-sync event {:frame frame-id}))
           ;; Per EP-0027 §Handler-time guard (rf2-emqiqk): reg-frame invoked
           ;; from an fx body (mid-cascade) trips the construction guard.
           :reg-frame! (fn [frame-id config]
                         (rf/reg-frame frame-id config))}
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
    ;; route registrations. rf2-wvh95f F1: the path pattern is the 3-slot
    ;; VALUE; lift it out of the fixture meta map so the middle slot is a pure
    ;; metadata map.
    (doseq [[id meta] (get handlers-map :route)]
      (rf/reg-route id (dissoc meta :path) (:path meta)))
    ;; view registrations — DSL bodies map to fns that realise hiccup with
    ;; reflection forms resolved at call-time.
    (doseq [[id steps] (get handlers-map :view)]
      (registrar/register!
        :view id
        {:handler-fn (conformance/realise-view-handler steps)}))
    ;; machine registrations (rf2-msd4). Merge the fixture's realised action /
    ;; guard / on-spawn bodies into each machine-spec before reg-machine*.
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
  destroy-frame! step (so the `:schemas/on-frame-destroyed!` hook doesn't
  wipe them) and BEFORE `reg-frame` (so the :initial-events cascade validates
  the seeded state). Per rf2-wkxng / rf2-6m0se / rf2-cq1ak the fixture key is
  `:app-schemas` — app-db schemas are NOT a registrar kind."
  [fixture]
  (doseq [[path schema] (get-in fixture [:fixture/registry :app-schemas])]
    (rf/reg-app-schema path schema)))

(defn- realise-flows!
  "Register the fixture's static flows. Called AFTER `reg-frame` — per Spec
  013 flows are FRAME-SCOPED, so the destroy-frame! teardown hook (rf2-wbtjn)
  clears any flows registered before the destroy step. Static shape lives
  under `:fixture/registry :flow`; body DSL under `:fixture/flow-bodies`."
  [fixture]
  (let [flow-registry (get-in fixture [:fixture/registry :flow] {})
        flow-bodies   (or (:fixture/flow-bodies fixture) {})]
    (doseq [[flow-id flow-meta] flow-registry]
      (when-let [body (get flow-bodies flow-id)]
        (let [output-fn (conformance/realise-flow-output-fn body)]
          ;; rf2-bqstzr — the 3-slot grammar: (reg-flow flow-id metadata
          ;; derive-fn). `flow-meta` is the reflection metadata middle slot.
          (rf/reg-flow flow-id flow-meta output-fn))))))

(defn- realise-classification-effects!
  "Apply a fixture's `:fixture/classification-effects` declarations against
  the established frame scope.

  EP-0025: durable app-db classification is the four commit-plane effects
  (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`, `:source
  :effect`). Because pure-EDN fixtures cannot return effects from a handler
  body, these ops are a TEST-ONLY shorthand writing the frame's durable
  elision registry EXACTLY as those effects would. `:sensitive` / `:large`
  are additive; `:clear-*` remove the named paths on their named axis ONLY.
  Called AFTER `reg-frame` and BEFORE `realise-flows!`."
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
  "Per rf2-wxe9t: register a corpus-wide error-emit listener for the duration
  of `fixture-id`'s run; each tight error-record fanned out by
  `error-emit/dispatch-on-error!` is appended to the returned atom in firing
  order. Host-neutral — `re-frame.error-emit` is a shared core ns."
  [fixture-id]
  (let [records (atom [])]
    (error-emit/register-error-listener!
      [fixture-id ::records]
      (fn [record] (swap! records conj record)))
    records))

;; ---- expectation matchers -------------------------------------------------

(defn- check-error-emit-records
  "Per rf2-wxe9t: positional partial-submap matcher for `:error-emit-records`
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
                                   (when (every? (fn [[k v]] (= v (get a k))) exp)
                                     i))))]
        (if match-idx
          (recur (drop (inc match-idx) actual) (rest expected) failures)
          (recur actual (rest expected)
                 (conj failures (str "expected error-emit record not seen: "
                                     (pr-str exp)))))))))

(defn- submap?
  "True if every key in expected appears in actual with a matching value.
  Recurses into nested maps so partial expectations on nested slices work."
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

(defn- check-trace-emissions
  "Per the conformance README §Fixture lifecycle: partial-match each expected
  event by its specified keys (absent keys ignored, nested-map keys matched
  submap-wise). Returns a vector of failure messages."
  [actual-traces expected-traces]
  (loop [actual   actual-traces
         expected expected-traces
         failures []]
    (cond
      (empty? expected)
      failures

      (empty? actual)
      (conj failures (str "expected trace not seen: " (pr-str (first expected))))

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
                 (conj failures (str "expected trace not seen: " (pr-str exp)))))))))

(defn- resolve-sub
  "A sub query in `:sub-values` may be `[query-v]` (implicit :rf/default
  frame) or `[frame-id [query-v]]` (explicit frame). Returns `[frame-id
  query-v]`."
  [entry]
  (if (and (vector? entry)
           (= 2 (count entry))
           (vector? (second entry)))
    [(first entry) (second entry)]
    [:rf/default entry]))

(defn- check-epoch-records
  "Per rf2-v0jwt — `:epoch-records` asserts against the recorded
  `:rf/epoch-record` ring. Each entry (`{:frame <id> :record <partial>}` or
  `{:record <partial>}` for implicit :rf/default) is a partial submap matched
  positionally against the named frame's history (oldest-first). Returns a
  vector of failure-strings.

  THIS is the matcher whose CLJS absence was the rf2-xurchk correctness gap:
  eight runnable fixtures carry `:epoch-records`, and pre-consolidation only
  the JVM copy invoked it. Now shared, both hosts check it."
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

                    (not (submap? record actual-record))
                    (str "epoch-record mismatch at position " i
                         " for frame " frame-id
                         " — expected (submap) " (pr-str record)
                         " — actual " (pr-str (select-keys actual-record
                                                           (keys record)))))))
              entries)))
        by-frame))))

;; ---- fixture registration helpers -----------------------------------------

(defn- register-routes! [fixture]
  ;; EDN maps don't preserve insertion order beyond ~8 entries; register in
  ;; deterministic lex order on the route-id so structurally-equal rank tuples
  ;; emit a deterministic shadow warning.
  (doseq [[id meta] (sort-by (comp str key)
                             (get-in fixture [:fixture/registry :route]))]
    (rf/reg-route id (dissoc meta :path) (:path meta))))

(defn- register-resources!
  "rf2-djofbh — register a fixture's `:fixture/registry :resource` entries
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
      (resources/reg-resource resource-id
                              (dissoc spec :url-template)
                              request-fn))))

(defn- realise-machine-handlers
  "Build `{action-id → fn}` / `{guard-id → fn}` / `{on-spawn-id → fn}` from a
  fixture's `:machine-action` / `:machine-guard` buckets. Per Spec 005
  §Guards / §Actions (rf2-grw4i / rf2-v0rrr) the user-facing fn receives one
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
                                                                   (conformance/eval-value* v ctx))))
                                        ;; rf2-8vo0: :fx args pass through
                                        ;; eval-value* so reflection forms
                                        ;; resolve against the snapshot's :data.
                                        :fx     (let [[_ a b] step]
                                                  (update ctx :fx (fnil conj [])
                                                          [a (conformance/eval-value* b ctx)]))
                                        ;; rf2-msd4: a throwing action exercises
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
                            (conformance/eval-value* step {:data data :event event})))))]))
        on-spawn-by-id
        (into {}
              (for [[id steps] (:machine-action handlers-map)]
                [id (conformance/realise-on-spawn-handler steps)]))]
    {:actions          actions-by-id
     :guards           guards-by-id
     :on-spawn-actions on-spawn-by-id}))

;; ---- :fixture/calls execution ---------------------------------------------

(defn run-call
  "Execute one `:fixture/calls` entry. Returns `{:passed? bool :detail …}`.
  `fixture-machines` is the realised `{:actions … :guards …}` map for the
  fixture (built once by run-fixture). An unrecognised `:call` FAILS (the
  default branch) — the call-op fail-loud counterpart of the expectation-key
  fail-loud."
  [call & [fixture-machines]]
  (case (:call call)
    :match-url
    ;; Spec 012 §Bidirectional URL ↔ params: the result carries an
    ;; implementation-specific :validation-error explanation; dissoc it before
    ;; equality (the :validation-failed? flag is the normative bit).
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

    ;; Mode-B route-pattern validation (rf2-5u1r6a). `:pattern` is the raw
    ;; `:path` string; `:expect-error :rf.error/invalid-route-pattern` ⇒ the
    ;; pure validator must throw an ex-info whose `:rf.error/id` equals that
    ;; id; absent `:expect-error` ⇒ a well-formed pattern must NOT throw.
    :reg-route
    (let [want-error (:expect-error call)
          thrown     (try (routing-match/validate-route-pattern!
                            (:route-id call :rf.test/pattern) (:pattern call)) nil
                          (catch #?(:clj Throwable :cljs :default) e e))]
      (if want-error
        (let [got-id (:rf.error/id (ex-data thrown))
              ok?    (= want-error got-id)]
          {:passed? ok?
           :detail  (when-not ok?
                      (str "reg-route " (pr-str (:pattern call)) "\n"
                           "    expected error :rf.error/id: " want-error "\n"
                           "    actual   error :rf.error/id: " got-id "\n"
                           "    thrown:                       " (some-> thrown ex-message)))})
        {:passed? (nil? thrown)
         :detail  (when (some? thrown)
                    (str "reg-route " (pr-str (:pattern call)) "\n"
                         "    expected: no error (well-formed pattern)\n"
                         "    thrown:   " (ex-message thrown)))}))

    ;; rank-vs-rank assertion: winner's rank tuple compares greater than loser's.
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
                       " — winner-rank " w-rank " loser-rank " l-rank))})

    ;; SSR pure render: input is hiccup or [:view-id args …]; opts may carry
    ;; :doctype?.
    :render-to-string
    (let [opts (or (:opts call) {})
          out  (try (ssr/render-to-string (:input call) opts)
                    (catch #?(:clj Throwable :cljs :default) e
                      (str "<error: " (ex-message e) ">")))
          want (:expect call)]
      {:passed? (= want out)
       :detail  (when (not= want out)
                  (str "render-to-string\n"
                       "    expected: " (pr-str want) "\n"
                       "    actual:   " (pr-str out)))})

    ;; pure machine-transition call (fsm fixtures). Per rf2-aa2rw the engine
    ;; returns a `machines.result/Result`; destructure `::snap`/`::fx` by
    ;; keyword literal to avoid a static require on the result ns.
    :machine-transition
    (let [actions-by-id  (or (:actions fixture-machines) {})
          guards-by-id   (or (:guards  fixture-machines) {})
          on-spawn-by-id (or (:on-spawn-actions fixture-machines) {})
          definition     (-> (:definition call)
                             (update :actions          #(merge actions-by-id %))
                             (update :guards           #(merge guards-by-id %))
                             (update :on-spawn-actions #(merge on-spawn-by-id %)))
          r             (try (machines/machine-transition definition (:snapshot call) (:event call))
                             (catch #?(:clj Throwable :cljs :default) e
                               {:re-frame.machines.result/snap nil
                                :re-frame.machines.result/fx   [:error (ex-message e)]}))
          ;; rf2-y3jv8q — a bounded-depth abort returns a result/fail carrying
          ;; the ::depth-abort? sentinel; project it onto the observable
          ;; atomic-rollback shape (input snapshot, empty effects).
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

    ;; pure registration-validation call (rf2-vf5cf). `:expect-error
    ;; <category>` ⇒ the pure validator must throw that `:rf.error/id`.
    :reg-machine
    (let [want-error (:expect-error call)
          thrown     (try (machines/validate-machine! (:definition call)) nil
                          (catch #?(:clj Throwable :cljs :default) e e))]
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

    ;; EP-0027 construction-engine registration call (rf2-kmk9z4). `:config`
    ;; is passed to `reg-frame`; `:expect-error <:rf.error/id>` ⇒ construction
    ;; must throw that id. The frame is destroyed afterward (best-effort).
    :reg-frame
    (let [frame-id   (or (:frame-id call) :rf.test/construction)
          want-error (:expect-error call)
          thrown     (try (rf/reg-frame frame-id (:config call)) nil
                          (catch #?(:clj Throwable :cljs :default) e e))
          _          (try (rf/destroy-frame! frame-id)
                          (catch #?(:clj Throwable :cljs :default) _ nil))]
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

    ;; Spec 016 §Resources (rf2-rul3ov) — `:reg-resource` Mode-B registration-
    ;; validation. `:expect-error <:rf.error/id>` ⇒ registration must throw
    ;; that id; absent ⇒ a well-formed spec must NOT throw. Cleared afterward.
    :reg-resource
    (let [resource-id (or (:resource-id call) :rf.test/resource)
          request-fn  (fn [params _ctx]
                        {:request {:method :get
                                   :url    (str (:request-url call "/api/probe") params)}})
          want-error  (:expect-error call)
          thrown      (try (resources/reg-resource resource-id (:spec call) request-fn) nil
                           (catch #?(:clj Throwable :cljs :default) e e))
          _           (try (resources/clear-resource resource-id)
                           (catch #?(:clj Throwable :cljs :default) _ nil))]
      (if want-error
        (let [got-id (:rf.error/id (ex-data thrown))
              ok?    (= want-error got-id)]
          {:passed? ok?
           :detail  (when-not ok?
                      (str "reg-resource\n"
                           "    expected error :rf.error/id: " want-error "\n"
                           "    actual   error :rf.error/id: " got-id "\n"
                           "    thrown:                       " (some-> thrown ex-message)))})
        {:passed? (nil? thrown)
         :detail  (when (some? thrown)
                    (str "reg-resource\n"
                         "    expected: no error (well-formed spec)\n"
                         "    thrown:   " (ex-message thrown)))}))

    ;; Spec 016 §Scope resolution (rf2-rul3ov) — `:resolve-scope` fail-closed.
    ;; `:side` selects the write-side event resolver or the read-side sub
    ;; resolver; `:expect-error` ⇒ that id; absent ⇒ `:expect` is the returned
    ;; canonical scope. Pure — the fail-closed THROW is captured directly.
    :resolve-scope
    (let [resource-id (:resource-id call)
          side        (:side call :event)
          want-error  (:expect-error call)
          result      (try
                        {:ok (case side
                               :sub   (resources-subs/resolve-scoped-key
                                        (cond-> {:resource resource-id
                                                 :params   (:params call {})}
                                          (contains? call :payload-scope)
                                          (assoc :scope (:payload-scope call)))
                                        (:db call {}))
                               (resources-registry/resolve-scope-for-event
                                 resource-id (resources-registry/resource-meta resource-id)
                                 {:payload-scope (:payload-scope call)
                                  :db            (:db call)}
                                 'rf.test/resolve-scope))}
                        (catch #?(:clj Throwable :cljs :default) e
                          (if-let [id (:rf.error/id (ex-data e))]
                            {:err id}
                            {:err (ex-message e)})))]
      (if want-error
        {:passed? (= want-error (:err result))
         :detail  (when (not= want-error (:err result))
                    (str "resolve-scope " side " " resource-id
                         "\n    expected error: " want-error
                         "\n    actual:         " (pr-str result)))}
        {:passed? (= (:expect call) (:ok result))
         :detail  (when (not= (:expect call) (:ok result))
                    (str "resolve-scope " side " " resource-id
                         "\n    expected: " (pr-str (:expect call))
                         "\n    actual:   " (pr-str result)))}))

    ;; EP-0012 (rf2-qyb9l1) — CEDN-1 canonical-identity golden ops (the FROZEN
    ;; byte-contract, so an encoder rewrite fails the corpus on BOTH hosts).
    :canonical-bytes
    (let [actual (try (identity/canonical-bytes (:value call))
                      (catch #?(:clj Throwable :cljs :default) e
                        (str "<error: " (ex-message e) ">")))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "canonical-bytes " (pr-str (:value call))
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    :canonical-identical
    (let [ok? (try (identity/identical-identity? (:a call) (:b call))
                   (catch #?(:clj Throwable :cljs :default) _ false))]
      {:passed? (boolean ok?)
       :detail  (when-not ok?
                  (str "canonical-identical expected = identity: "
                       (pr-str (:a call)) " vs " (pr-str (:b call))))})

    :canonical-distinct
    (let [same? (try (identity/identical-identity? (:a call) (:b call))
                     (catch #?(:clj Throwable :cljs :default) _ false))]
      {:passed? (not same?)
       :detail  (when same?
                  (str "canonical-distinct expected DISTINCT identities: "
                       (pr-str (:a call)) " vs " (pr-str (:b call))))})

    ;; `:path-instantiate` — `(path/instantiate path bindings)` = `:expect`,
    ;; OR fails closed with `:expect-error`. Concrete-path PRODUCER boundary.
    :path-instantiate
    (let [want-error (:expect-error call)
          result     (try {:ok (path/instantiate (:path call) (:bindings call))}
                          (catch #?(:clj Throwable :cljs :default) e
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

    ;; EP-0012 (rf2-du585y) — `:rf/path` algebra LAW ops. `:path-over` carries
    ;; a NAMED transform (`:fn`) so the fixture stays pure data.
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
                      (catch #?(:clj Throwable :cljs :default) e
                        (str "<error: " (ex-message e) ">")))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str (name (:call call)) " " (pr-str (dissoc call :call :expect))
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    ;; EP-0015 (rf2-t55hxg.2) — `:project-egress`. When the call OMITS
    ;; `:frame`, bind `*current-frame*` to nil so the projection is genuinely
    ;; frameless (the fail-closed posture); otherwise the ambient scope frame
    ;; would leak in.
    :project-egress
    (let [has-frame? (contains? call :frame)
          opts       (cond-> {:rf.egress/profile (:rf.egress/profile call)}
                       has-frame? (assoc :frame (:frame call)))
          run        (fn [] (projection/project-egress (:value call) opts))
          actual     (try (if has-frame?
                            (run)
                            (binding [frame/*current-frame* nil] (run)))
                          (catch #?(:clj Throwable :cljs :default) e
                            (str "<error: " (ex-message e) ">")))
          expect     (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "project-egress " (pr-str (:value call))
                       " under " (:rf.egress/profile call)
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    ;; EP-0015 (rf2-t55hxg.2) — `:redact-headers`. Frame-local carrier extends
    ;; the immutable built-in defaults; no frame can remove a default.
    :redact-headers
    (let [actual (try (http-privacy-headers/redact-headers
                        (:headers call) (:frame-extras call))
                      (catch #?(:clj Throwable :cljs :default) e
                        (str "<error: " (ex-message e) ">")))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "redact-headers " (pr-str (:headers call))
                       " extras " (pr-str (:frame-extras call))
                       "\n    expected: " (pr-str expect)
                       "\n    actual:   " (pr-str actual)))})

    ;; EP-0015 (rf2-t55hxg.2) — `:ssr-apply-policy`. Allowlist-first SSR
    ;; hydration-payload projection; `:expect-error` for the fail-closed
    ;; validator on a missing / malformed policy.
    :ssr-apply-policy
    (let [want-error (:expect-error call)
          result     (try {:ok (ssr-payload-policy/apply-policy
                                  (:app-db call) (:opts call))}
                          (catch #?(:clj Throwable :cljs :default) e
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

    ;; EP-0026 (rf2-qp8qi8) — `:assemble-image`. Pin the EP-0026 image-API
    ;; surface host-agnostically against the live `image/image` constructor +
    ;; `image-assembly` assembler. PURE — a function of the call's `:pool` +
    ;; `:images` specs. Inline bodies are realised to a host no-op (EDN cannot
    ;; carry fns; who-won is read from the descriptor coordinate, pure EDN).
    :assemble-image
    (let [;; A FRESH fn per entry so two inline entries for one [kind id] stay
          ;; DISTINCT registrations (a shared body would dedupe them and mask
          ;; the two-inline within-image collision the fixture pins).
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
          ;; `:images-literal` — the `make-frame` BOUNDARY case (`:images []`
          ;; is an error). `validate-images!` fires inside `make-frame` BEFORE
          ;; any frame record is created, so the error case is pure.
          outcome
          (if (contains? call :images-literal)
            (try (rf/make-frame {:images (:images-literal call)})
                 {:err :no-error}
                 (catch #?(:clj Throwable :cljs :default) e
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
              (catch #?(:clj Throwable :cljs :default) e
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
                  ;; NOT appear on the sealed generation map.
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
                        (str "assemble-image:\n    " (str/join "\n    " fails)))})
          {:passed? false
           :detail  (str "assemble-image: assembly threw unexpectedly — "
                         (pr-str (or (:err outcome) (:err-msg outcome))))})))

    ;; EP-0014 (rf2-k0meap.3; rf2-djofbh) — `:derivation-graph`. Compose the
    ;; cross-family derivation/process graph over the FULL contributor set and
    ;; assert normalized node + edge shapes. `:expect-graph` (rf2-ska8zk) is a
    ;; SUBMAP over the WHOLE graph (the graph-level `:mode`/`:frame` shape).
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
          sub-map?      (fn [sub m] (every? (fn [[k v]] (= v (get m k))) sub))
          graph-fails   (let [want (:expect-graph call)]
                          (when (and want (not (sub-map? want graph)))
                            [(str "graph expected superset of " want
                                  " got " (select-keys graph (keys want)))]))
          node-fails    (keep (fn [{:keys [id] :as expect-node}]
                                (let [node (get nodes id)
                                      want (dissoc expect-node :id)]
                                  (when-not (and node (sub-map? want node))
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

    ;; unknown call op — fail loud (call-op counterpart of the expectation-key
    ;; fail-loud). A fixture reaching here uses a `:call` no host implements.
    {:passed? false :detail (str "unknown :call form: " (:call call))}))

;; ---- fixture execution ----------------------------------------------------

(defn run-fixture
  "Realise, dispatch, and grade one fixture against `:fixture/expect`. `host`
  supplies the genuinely host-specific seams (`:reset-runtime!`,
  `:register-trace-listener!`, `:unregister-trace-listener!`). Returns a
  result map with `:passed?` and per-check diagnostics."
  [fixture host]
  (try
    ((:reset-runtime! host))
    (let [fid          (:fixture/id fixture)
          ;; Register the trace listener FIRST so registration-time warnings
          ;; (e.g. route-shadowed-by-equal-score) are captured.
          traces       (collect-traces host fid)
          ;; rf2-wxe9t — capture the always-on error-emit substrate's tight
          ;; error-records in parallel with the trace listener.
          err-records  (collect-error-emit-records! fid)
          _            (realise-handlers fixture)
          _            (register-routes! fixture)
          ;; rf2-djofbh — resources register before reg-frame / dispatches.
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
          ;; EP-0002 (rf2-9o48ih) — the carried-invariant: registration-time
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
          ;; the frame's schema entries) and BEFORE reg-frame (so the
          ;; :initial-events cascade validates the seeded state).
          _            (rf/with-frame scope-frame
                         (realise-app-schemas! fixture))
          _            (cond
                         (seq frames-spec)
                         (doseq [f frames-spec]
                           (rf/reg-frame (:id f) (dissoc f :id)))
                         :else
                         (rf/reg-frame :rf/default frame-config))
          ;; Data-classification commit-plane effects run AFTER reg-frame and
          ;; BEFORE realise-flows!.
          _            (realise-classification-effects! fixture scope-frame)
          ;; Flow registration runs AFTER reg-frame (frame-scoped teardown).
          _            (rf/with-frame scope-frame
                         (realise-flows! fixture))
          dispatches   (or (:fixture/dispatches fixture) [])
          sub-registry (get-in fixture [:fixture/registry :sub] {})
          ;; EP-0017 (rf2-d8mvke.3): per-dispatch `:expect-error` assertions.
          dispatch-error-failures (atom [])]
      (rf/with-frame scope-frame
        (doseq [ev dispatches]
          (cond
            (map? ev)
            (cond
              ;; Harness teardown `{:destroy-frame <frame-id>}`.
              (contains? ev :destroy-frame)
              (rf/destroy-frame! (:destroy-frame ev))

              ;; Harness re-registration `{:reg-sub <sub-id> :body <body>}`
              ;; (Cross-Spec Interaction §18, rf2-qei5a). The realised sub's
              ;; :kind MUST drive the registration form.
              (contains? ev :reg-sub)
              (let [sub-id (:reg-sub ev)
                    steps  (:body ev)
                    {:keys [kind inputs body]} (conformance/realise-sub steps)
                    sub-meta (get sub-registry sub-id {})]
                (case kind
                  :layer-1 (if (seq sub-meta)
                             (subs/reg-sub sub-id sub-meta body)
                             (subs/reg-sub sub-id body))
                  :runtime-db (if (seq sub-meta)
                                (subs/reg-runtime-sub sub-id sub-meta body)
                                (subs/reg-runtime-sub sub-id body))
                  :layer-2 (apply subs/reg-sub sub-id
                                  (concat (when (seq sub-meta) [sub-meta])
                                          (interleave (repeat :<-) inputs)
                                          [body]))))

              ;; EP-0017 (rf2-d8mvke.3): a dispatch asserting a boundary /
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
            (rf/dispatch-sync ev))))
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
            (ssr/verify-hydration! frame-id client-hash
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
      ;; projector's :status before snapshotting final-app-db.
      (doseq [fid (frame/frame-ids)]
        (try (ssr/apply-error-projection! fid)
             (catch #?(:clj Throwable :cljs :default) _ nil)))
      (let [expect       (or (:fixture/expect fixture) {})
            expected-db  (:final-app-db expect)
            expected-dbs (:final-app-dbs expect)
            expected-absent (:final-app-db-absent expect)
            final-db     (rf/app-db-value :rf/default)
            final-dbs    (when expected-dbs
                           (into {}
                                 (for [[fid _] expected-dbs]
                                   [fid (rf/app-db-value fid)])))
            ;; EP-0001 (rf2-vzld77): durable framework runtime state lives in
            ;; the runtime-db partition; fixtures assert it under
            ;; :final-runtime-db / :final-runtime-dbs.
            expected-rt  (:final-runtime-db expect)
            expected-rts (:final-runtime-dbs expect)
            final-rt     (:rf.db/runtime (rf/frame-state-value :rf/default))
            final-rts    (when expected-rts
                           (into {}
                                 (for [[fid _] expected-rts]
                                   [fid (:rf.db/runtime (rf/frame-state-value fid))])))
            ;; Realise sub-checks BEFORE trace-failures: subscribing computes
            ;; the reaction body, which may emit sub-exception traces the
            ;; trace-emissions check expects to see.
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                (let [[frame-id qv] (resolve-sub query-v)]
                  {:query    query-v
                   :expected expected-val
                   :actual   (rf/subscribe-once qv {:frame frame-id})})))
            ;; EP-0017 (rf2-d8mvke.3): NEGATIVE app-db path assertions. A
            ;; sentinel `get-in` distinguishes absent from present-with-nil.
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
            error-emit-failures (when (contains? expect :error-emit-records)
                                  (check-error-emit-records
                                    @err-records (:error-emit-records expect)))
            ;; rf2-v0jwt / rf2-xurchk — :epoch-records. Now shared, both hosts
            ;; assert against the recorded :rf/epoch-record ring.
            epoch-failures (when-let [er (:epoch-records expect)]
                             (check-epoch-records er))
            actual-effects (effects-routed-from-traces @traces)
            expected-effects (when (contains? expect :effects-routed)
                               (normalise-effects-routed (:effects-routed expect)))
            effects-failures (when expected-effects
                               (check-effects-routed actual-effects expected-effects))
            ;; SSR error-projection contract — Spec 011.
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
        ;; Drop just this fixture's trace listener (host-specific registry).
        ((:unregister-trace-listener! host) fid)
        ;; rf2-wxe9t — drop just this fixture's error-emit recorder.
        (error-emit/unregister-error-listener! [fid ::records])
        {:fixture-id   fid
         :passed?      (and (or (nil? expected-db) (submap? expected-db final-db))
                            (or (nil? expected-dbs)
                                (every? (fn [[fid db]] (submap? db (get final-dbs fid)))
                                        expected-dbs))
                            (or (nil? expected-rt) (submap? expected-rt final-rt))
                            (or (nil? expected-rts)
                                (every? (fn [[fid rt]] (submap? rt (get final-rts fid)))
                                        expected-rts))
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
         :trace-failures     trace-failures
         :effects-failures   effects-failures
         :actual-effects     actual-effects
         :expected-effects   expected-effects
         :epoch-failures     epoch-failures
         :error-emit-failures error-emit-failures
         :actual-error-emit-records @err-records
         :public-error-check public-error-check}))
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
      (println "    unknown capabilities (rf2-a3q1r):" (:unknown-caps f)))
    (when (:unknown-expect-keys f)
      (println "    unknown :fixture/expect keys (rf2-xurchk):" (:unknown-expect-keys f)))
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
      (doseq [de (:dispatch-error-failures f)]
        (println "    expect-error:" de)))
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

(defn run-corpus
  "Run the whole conformance corpus. `fixtures` is a seq of `[filename
  fixture-map]` pairs (loaded host-specifically); `host` supplies the reset /
  trace-listener seams; `label` names the host in diagnostics (\"JVM\" /
  \"CLJS\"). Performs the three-way capability classification (rf2-a3q1r), the
  EXPECTATION-KEY fail-loud (rf2-xurchk), runs each claim-applicable fixture,
  and emits the pass / floor / count `is` assertions. Silent on green
  (rf2-try1x): the failure report only prints when there are failures."
  [fixtures host label]
  (let [results (atom [])]
    (doseq [[fname fixture] fixtures]
      (cond
        (:fixture/load-error fixture)
        (swap! results conj {:fixture-id fname :skipped? true :reason "load error"
                             :error (:fixture/load-error fixture)})

        (not (spec-version-claimed? fixture))
        (swap! results conj {:fixture-id   (:fixture/id fixture)
                             :skipped?     true
                             :reason       "spec-version not in claimed set"
                             :spec-version (:fixture/spec-version fixture)})

        :else
        (let [{:keys [allowed unknown]} (classify-capabilities fixture)
              bad-keys (unknown-expect-keys fixture)]
          (cond
            (seq unknown)
            (swap! results conj
                   {:fixture-id   (:fixture/id fixture)
                    :passed?      false
                    :unknown-caps unknown
                    :error        (str "unknown capabilities: " unknown
                                       " — capability is neither in "
                                       "claimed-capabilities nor in "
                                       "known-skipped-capabilities. Either claim "
                                       "it (and implement it) or add to the "
                                       "known-skipped-capabilities allowlist.")})

            (seq allowed)
            (swap! results conj
                   {:fixture-id   (:fixture/id fixture)
                    :skipped?     true
                    :reason       "capabilities intentionally not claimed (allowlisted)"
                    :capabilities (:fixture/capabilities fixture)
                    :allowed      allowed})

            ;; rf2-xurchk fail-loud — a runnable fixture whose :fixture/expect
            ;; names a key this runner would silently ignore (neither
            ;; corpus-checked nor delegated to a sibling runner) FAILS.
            (seq bad-keys)
            (swap! results conj
                   {:fixture-id          (:fixture/id fixture)
                    :passed?             false
                    :unknown-expect-keys (vec bad-keys)
                    :error               (str "unknown :fixture/expect keys: " (vec bad-keys)
                                              " — key is neither a corpus-checked "
                                              "expectation nor a sibling-owned "
                                              "(delegated) expectation. Add a matcher "
                                              "to the shared runner, or add the key to "
                                              "sibling-owned-expect-keys if a dedicated "
                                              "conformance runner owns it. Silent-ignore "
                                              "is the rf2-xurchk drift class and is refused.")})

            :else
            (swap! results conj (assoc (run-fixture fixture host) :fname fname))))))
    (let [all     @results
          run     (filter (complement :skipped?) all)
          passed  (filter :passed? run)
          failed  (remove :passed? run)
          skipped (filter :skipped? all)]
      ;; rf2-3hamsq — non-empty floor. The lone (zero? (count failed)) below
      ;; passes GREEN over an empty / fully-skipped / orphaned corpus,
      ;; verifying NOTHING. Assert fixtures actually executed.
      (is (pos? (count run))
          (str "at least one claim-applicable conformance fixture must have executed (" label ")"))
      (is (>= (count run) 150)
          (str label " conformance corpus runnable-fixture floor (>= 150): only "
               (count run) " executed — a fixture-discovery fault or a "
               "capability-vocab rename has orphaned the corpus."))
      (when (seq failed)
        (print-failures label all run passed failed skipped))
      ;; Per rf2-3xt7: the suite fails unless EVERY claimed-applicable fixture
      ;; passes. Skipped fixtures neither claim conformance nor block it.
      (is (zero? (count failed))
          (str "All claimed-applicable " label " conformance fixtures must pass; "
               (count failed) " failed.")))))
