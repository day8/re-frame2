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
            [re-frame.cofx :as cofx]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.schemas :as schemas]
            [re-frame.subs :as subs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.trace :as trace]
            [re-frame.conformance :as conformance]
            [re-frame.routing :as routing]
            [re-frame.ssr :as ssr]
            [re-frame.machines :as machines]
            ;; Spec 014 — :rf.http/managed registers at ns-load time. The
            ;; fixture corpus references the fx (often via :fx-overrides
            ;; redirecting to its canned stubs); requiring here gives the
            ;; runner access to the fx without each fixture re-registering
            ;; it itself.
            [re-frame.http-managed :as http-managed])
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
    :fsm/flat
    :fsm/eventless-always
    :fsm/hierarchical
    :fsm/delayed-after
    :fsm/tags
    :fsm/parallel-regions
    :fsm/final-states
    :routing/match-url
    :ssr/render-to-string
    :ssr/hydration
    :ssr/response-contract
    :ssr/head-contract
    :ssr/error-projection
    :schemas/runtime
    :schemas/event-payload
    :schemas/sub-return
    :schemas/cofx
    :routing/ranking
    :routing/fragment
    :routing/blocking
    :routing/nav-token
    :actor/spawn-destroy   ;; rf2-mtq4h — renamed from :actor/spawn to align with spec vocabulary
    :actor/invoke
    :actor/spawn-and-join
    :actor/system-id
    ;; :actor/timeout retired per rf2-3y3y — :fsm/delayed-after subsumes
    ;; it. The state-level :after primitive covers wall-clock-timeout
    ;; semantics for both pure timed-transition states and :invoke-bearing
    ;; states; the after-*.edn fixtures (after-single-delay, after-hierarchy,
    ;; after-stale-detection, parallel-after-scoped-to-region) exercise the
    ;; canonical primitive. See [spec/005-StateMachines.md §Capability matrix]
    ;; and [spec/MIGRATION.md §M-44].
    :flow/basic
    :flow/topo
    :flow/dirty-check
    :flow/toggle
    :flow/hot-reload
    ;; Spec 009 §Flow trace events / Spec 013 §Flow tracing (rf2-2s1o) —
    ;; the runtime emits the :rf.flow/* lifecycle events. Claimed so
    ;; `flow-lifecycle-emits-traces.edn` runs on CLJS too (rf2-efjs6).
    :flow/trace
    :rf.http/managed})

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
  #{})

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
;;    time; `test-support/reset-runtime-fixture` (used by many other
;;    CLJS test namespaces' `use-fixtures :each` blocks) calls
;;    `(trace/clear-trace-cbs!)`, so by the time our deftest runs the
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
  ;; `re-frame.trace/listeners` is `^:private` but CLJS treats
  ;; private metadata as advisory — the symbol resolves through the
  ;; namespace and the atom is reachable for tests that need to
  ;; snapshot framework listeners (the SSR error-projection listener
  ;; in particular). The JVM runner achieves the same effect
  ;; implicitly via `(require :reload)` of `re-frame.ssr`; CLJS has
  ;; no analogue, so this access is the bridge.
  @re-frame.trace/listeners)

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
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  ;; 3. Reset id-allocators so the routing / machine fixtures see
  ;;    deterministic counters.
  (routing/reset-counters!)
  (machines/reset-counters!)
  ;; 4. Drop the in-flight HTTP request registry between fixtures.
  (http-managed/clear-all-in-flight!)
  ;; 5. Dispose the currently-installed adapter (if any) and re-install
  ;;    plain-atom. `init!` is idempotent and creates the :rf/default
  ;;    frame; subsequent reg-frame calls for that id update in-place.
  (substrate-adapter/dispose-adapter!)
  (rf/init! plain-atom/adapter)
  ;; 6. Restore the baseline trace-listener set. This preserves the
  ;;    SSR error-projection listener (and any other ns-load
  ;;    framework listeners) while dropping every per-fixture
  ;;    listener `collect-traces` may have registered. The JVM
  ;;    runner achieves the equivalent via `clear-trace-cbs!` +
  ;;    `(require 're-frame.ssr :reload)`; CLJS has no `:reload`,
  ;;    so the snapshot-restore path is the only correct one.
  (reset! re-frame.trace/listeners baseline-trace-listeners))

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
  Returns a set of K. Used by realise-handlers to auto-wire
  (inject-cofx K) interceptors per the conformance-corpus convention
  (rf2-g25p)."
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

(defn- realise-cofx-handler
  "DSL → cofx handler fn. Per rf2-g25p, the body is realised against
  the inject-cofx ctx so reflection forms (`[:cofx-key K]`, `[:fn :k a b]`)
  resolve against the inbound coeffects/event."
  [cofx-id steps]
  (fn [ctx]
    (let [dsl-ctx {:db    (get-in ctx [:coeffects :db])
                   :event (get-in ctx [:coeffects :event])
                   :cofx  (:coeffects ctx)}]
      (reduce (fn [c step]
                (case (first step)
                  :set  (let [[_ _path value] step
                              v (conformance/eval-value* value dsl-ctx)]
                          (assoc-in c [:coeffects cofx-id] v))
                  :noop c
                  c))
              ctx
              steps))))

;; Forward declaration — realise-machine-handlers is defined alongside
;; the :machine-transition path below.
(declare realise-machine-handlers)

(defn- realise-handlers [fixture]
  (let [handlers-map     (or (:fixture/handlers fixture) {})
        event-registry   (get-in fixture [:fixture/registry :event] {})
        sub-registry     (get-in fixture [:fixture/registry :sub] {})
        cofx-bodies      (get handlers-map :cofx)
        cofx-registry    (get-in fixture [:fixture/registry :cofx] {})
        ;; cofx that should auto-wire as inject-cofx interceptors on
        ;; event handlers. Stable lex order on cofx-id so the last-
        ;; write-wins outcome is deterministic across JVM / CLJS / re-runs.
        cofx-by-key
        (->> cofx-registry
             (sort-by key)
             (group-by (fn [[cofx-id _]] (keyword (namespace cofx-id))))
             (reduce-kv (fn [acc k pairs]
                          (assoc acc k (mapv first pairs)))
                        {}))]
    ;; cofx registrations — bodies + :spec metadata. Per rf2-7leq the
    ;; schema validation runs in inject-cofx; here we register the
    ;; handler-fn so inject-cofx can resolve it.
    (let [all-cofx-ids (into #{} (concat (keys cofx-bodies) (keys cofx-registry)))]
      (doseq [cofx-id all-cofx-ids]
        (let [body    (get cofx-bodies cofx-id [[:noop]])
              meta    (get cofx-registry cofx-id {})
              handler (realise-cofx-handler cofx-id body)]
          (rf/reg-cofx cofx-id (assoc meta :handler-fn handler) handler))))
    ;; event registrations
    (doseq [[id steps] (get handlers-map :event)]
      (let [[kind handler] (conformance/realise-event-handler steps)
            event-meta     (get event-registry id {})
            ks             (collect-cofx-keys steps)
            cofx-ids       (vec
                             (mapcat (fn [k]
                                       (or (get cofx-by-key k)
                                           (when (contains? cofx-registry k) [k])))
                                     ks))
            interceptors   (mapv cofx/inject-cofx cofx-ids)]
        (case kind
          :db (if (seq event-meta)
                (rf/reg-event-db id event-meta interceptors handler)
                (if (seq interceptors)
                  (rf/reg-event-db id interceptors handler)
                  (rf/reg-event-db id handler)))
          :fx (if (seq event-meta)
                (rf/reg-event-fx id event-meta interceptors handler)
                (if (seq interceptors)
                  (rf/reg-event-fx id interceptors handler)
                  (rf/reg-event-fx id handler))))))
    ;; sub registrations
    (doseq [[id steps] (get handlers-map :sub)]
      (let [{:keys [kind inputs body]} (conformance/realise-sub steps)
            sub-meta                   (get sub-registry id {})]
        (case kind
          :layer-1 (if (seq sub-meta)
                     (subs/reg-sub id sub-meta body)
                     (subs/reg-sub id body))
          ;; Use subs/reg-sub (the fn-form) here because rf/reg-sub is a
          ;; macro and macros aren't first-class values for `apply`.
          :layer-2 (apply subs/reg-sub id
                          (concat (when (seq sub-meta) [sub-meta])
                                  (interleave (repeat :<-) inputs)
                                  [body])))))
    ;; fx handlers (bodies + meta)
    (let [adapter-helpers
          {:read-db!  (fn [frame-id]
                        (frame/frame-app-db-value frame-id))
           :write-db! (fn [frame-id new-db]
                        (let [container (frame/get-frame-db frame-id)]
                          (substrate-adapter/replace-container! container new-db)))
           :dispatch! (fn [event frame-id]
                        (rf/dispatch event {:frame frame-id}))}
          fx-bodies   (get handlers-map :fx)
          fx-registry (get-in fixture [:fixture/registry :fx] {})
          all-fx-ids  (into #{} (concat (keys fx-bodies) (keys fx-registry)))]
      (doseq [id all-fx-ids]
        (let [body    (get fx-bodies id [[:noop]])
              meta    (get fx-registry id {})
              handler (conformance/realise-fx-handler id body adapter-helpers)]
          (rf/reg-fx id (assoc meta :handler-fn handler) handler))))
    ;; flow registrations
    (let [flow-registry (get-in fixture [:fixture/registry :flow] {})
          flow-bodies   (or (:fixture/flow-bodies fixture) {})]
      (doseq [[flow-id flow-meta] flow-registry]
        (when-let [body (get flow-bodies flow-id)]
          (let [output-fn (conformance/realise-flow-output-fn body)]
            (rf/reg-flow (-> flow-meta
                             (assoc :id flow-id)
                             (assoc :output output-fn)))))))
    ;; route registrations
    (doseq [[id meta] (get handlers-map :route)]
      (rf/reg-route id meta))
    ;; view registrations
    (doseq [[id steps] (get handlers-map :view)]
      (registrar/register!
        :view id
        {:handler-fn (conformance/realise-view-handler steps)}))
    ;; app-schema registrations
    (doseq [[path schema] (get-in fixture [:fixture/registry :app-schema])]
      (rf/reg-app-schema path schema))
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

(defn- collect-traces [fixture-id]
  (let [traces (atom [])]
    (trace/register-trace-cb! [fixture-id] (fn [ev] (swap! traces conj ev)))
    traces))

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
  successful routing emits :rf.fx/handled with :fx-id and :fx-args;
  handler-throws emit :rf.error/fx-handler-exception with the same
  tag shape)."
  [traces]
  (->> traces
       (filter (fn [t]
                 (let [op (:operation t)]
                   (or (= op :rf.fx/handled)
                       (= op :rf.error/fx-handler-exception)))))
       (mapv (fn [t]
               {:fx-id   (get-in t [:tags :fx-id])
                :fx-args (get-in t [:tags :fx-args])}))))

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
    (rf/reg-route id meta)))

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
                ;; Per Spec 005 §Actions canonical 2-arity: (fn [data event]).
                [id (fn [data event]
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
                [id (fn [data event]
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
    (let [actual (rf/match-url (:url call))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "match-url " (:url call)
                       " expected " expect " got " actual))})

    :route-url
    (let [actual (if (:query call)
                   (rf/route-url (:route-id call) (:params call) (:query call))
                   (rf/route-url (:route-id call) (:params call)))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "route-url " (:route-id call)
                       " expected " expect " got " actual))})

    :round-trip
    (let [matched (rf/match-url (:url call))
          rebuilt (when matched
                    (if (seq (:query matched))
                      (rf/route-url (:route-id matched) (:params matched) (:query matched))
                      (rf/route-url (:route-id matched) (:params matched))))]
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

    :machine-transition
    (let [actions-by-id  (or (:actions fixture-machines) {})
          guards-by-id   (or (:guards  fixture-machines) {})
          on-spawn-by-id (or (:on-spawn-actions fixture-machines) {})
          definition     (-> (:definition call)
                             (update :actions          #(merge actions-by-id %))
                             (update :guards           #(merge guards-by-id %))
                             (update :on-spawn-actions #(merge on-spawn-by-id %)))
          [snap-out fx-out]
          (try (machines/machine-transition definition (:snapshot call) (:event call))
               (catch :default e [nil [:error (ex-message e)]]))
          want-snap (:expect-next-snapshot call)
          want-fx   (or (:expect-effects call) [])
          ok-snap?  (= want-snap snap-out)
          ok-fx?    (= want-fx (vec fx-out))]
      {:passed? (and ok-snap? ok-fx?)
       :detail  (when (not (and ok-snap? ok-fx?))
                  (str "machine-transition\n"
                       "    expected snapshot: " want-snap "\n"
                       "    actual   snapshot: " snap-out "\n"
                       "    expected effects:  " want-fx "\n"
                       "    actual   effects:  " fx-out))})

    {:passed? false :detail (str "unknown :call form: " (:call call))}))

(defn run-fixture [fixture]
  (try
    (reset-runtime!)
    (let [fid          (:fixture/id fixture)
          ;; Register the trace listener FIRST so registration-time
          ;; warnings are captured.
          traces       (collect-traces fid)
          _            (realise-handlers fixture)
          _            (register-routes! fixture)
          ;; `:fixture/runtime :platform` declares the simulated host
          ;; platform under which the fixture runs (e.g. `:server`
          ;; for SSR-style fx-platforms tests). On the JVM the
          ;; default `re-frame.interop/platform` is `:server`, so a
          ;; missing `:platform` in the frame-config still lands on
          ;; the server branch; on CLJS the default is `:client`, so
          ;; honouring `:fixture/runtime :platform` is load-bearing
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
          ;; reset-runtime! created :rf/default WITHOUT an :on-create.
          ;; reg-frame against an existing id is a surgical update; destroy
          ;; first so :on-create fires when re-registered.
          _            (rf/destroy-frame :rf/default)
          _            (cond
                         (seq frames-spec)
                         (doseq [f frames-spec]
                           (rf/reg-frame (:id f) (dissoc f :id)))
                         :else
                         (rf/reg-frame :rf/default frame-config))
          dispatches   (or (:fixture/dispatches fixture) [])]
      (doseq [ev dispatches]
        (cond
          (map? ev)
          (let [{event :event :as opts} ev]
            (rf/dispatch-sync event (dissoc opts :event)))

          (and (vector? ev) (= :rf/hydrate (first ev)))
          (rf/dispatch-sync ev {:source :ssr-hydration})

          :else
          (rf/dispatch-sync ev)))
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
        (try (ssr/apply-pending-error-projection! fid)
             (catch :default _ nil)))
      (let [expect       (or (:fixture/expect fixture) {})
            expected-db  (:final-app-db expect)
            expected-dbs (:final-app-dbs expect)
            final-db     (rf/get-frame-db :rf/default)
            final-dbs    (when expected-dbs
                           (into {}
                                 (for [[fid _] expected-dbs]
                                   [fid (rf/get-frame-db fid)])))
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                (let [[frame-id qv] (resolve-sub query-v)]
                  {:query    query-v
                   :expected expected-val
                   :actual   (rf/subscribe-value frame-id qv)})))
            trace-failures (check-trace-emissions @traces (:trace-emissions expect))
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
        (trace/remove-trace-cb! fid)
        {:fixture-id   fid
         :passed?      (and (or (nil? expected-db) (submap? expected-db final-db))
                            (or (nil? expected-dbs)
                                (every? (fn [[fid db]] (submap? db (get final-dbs fid)))
                                        expected-dbs))
                            (every? #(= (:expected %) (:actual %)) sub-checks)
                            (empty? trace-failures)
                            (empty? effects-failures)
                            (or (nil? public-error-check)
                                (:passed? public-error-check)))
         :final-db     final-db
         :final-dbs    final-dbs
         :expected-db  expected-db
         :expected-dbs expected-dbs
         :sub-checks   sub-checks
         :trace-failures trace-failures
         :effects-failures   effects-failures
         :actual-effects     actual-effects
         :expected-effects   expected-effects
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
      (println)
      (println "Conformance corpus (CLJS):")
      (println "  total fixtures:" (count all))
      (println "  runnable:      " (count run))
      (println "  passed:        " (count passed))
      (println "  failed:        " (count failed))
      (println "  skipped:       " (count skipped))
      (when (seq passed)
        (println)
        (println "Passing:")
        (doseq [p passed]
          (println "  " (:fixture-id p))))
      (when (seq skipped)
        (println)
        (println "Skipped (out-of-claim):")
        (doseq [s skipped]
          (println "  " (:fixture-id s) "—"
                   (or (:capabilities s) (:spec-version s) (:reason s)))))
      (when (seq failed)
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
