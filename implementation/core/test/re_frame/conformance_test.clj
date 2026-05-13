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
            [re-frame.cofx :as cofx]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.schemas :as schemas]
            [re-frame.subs :as subs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.conformance :as conformance]
            ;; Spec 014 — :rf.http/managed registers at ns-load time. The
            ;; fixture corpus references the fx (often via :fx-overrides
            ;; redirecting to its canned stubs); requiring here gives the
            ;; runner access to the fx without each fixture re-registering
            ;; it itself.
            [re-frame.http-managed]))

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
    :routing/match-url
    :ssr/render-to-string
    :ssr/hydration
    :ssr/response-contract
    :ssr/head-contract
    :ssr/error-projection
    :schemas/runtime
    :schemas/event-payload                            ;; rf2-jwm4
    :schemas/sub-return                               ;; rf2-wcam
    :schemas/cofx                                     ;; rf2-7leq
    :routing/ranking
    :routing/fragment
    :routing/blocking
    :routing/nav-token
    :actor/spawn-destroy                               ;; rf2-mtq4h — renamed from :actor/spawn to align with spec vocabulary
    :actor/invoke
    :actor/spawn-and-join                              ;; rf2-6vmw / rf2-er0t
    :actor/system-id                                   ;; rf2-suue / rf2-ecv4
    ;; :actor/timeout retired per rf2-3y3y — :fsm/delayed-after subsumes
    ;; it. The state-level :after primitive covers wall-clock-timeout
    ;; semantics for both pure timed-transition states and :invoke-bearing
    ;; states; the after-*.edn fixtures (after-single-delay, after-hierarchy,
    ;; after-stale-detection, parallel-after-scoped-to-region) exercise the
    ;; canonical primitive. See [spec/005-StateMachines.md §Capability matrix]
    ;; and [spec/MIGRATION.md §M-44].
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
    ;; Spec 014 — :rf.http/managed (rf2-z1mw)
    :rf.http/managed})

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
  #{})

;; ---- fixture loader -------------------------------------------------------

(def fixtures-dir
  ;; The corpus lives under spec/conformance/fixtures at the repo root.
  ;; Per rf2-0hxm the JVM tests run from implementation/core/, so the
  ;; relative path is ../../spec/conformance/fixtures. Fall back to the
  ;; pre-split layout for transitional REPLs running from
  ;; implementation/ — whichever exists first wins.
  (let [nested  (io/file "../../spec/conformance/fixtures")
        legacy  (io/file "../spec/conformance/fixtures")]
    (if (.exists nested) nested legacy)))

(defn- load-fixture [file]
  (try
    ;; A handful of fixtures use `::name` (auto-resolved keyword) which
    ;; pure clojure.edn cannot read without a *reader-resolver*. The
    ;; corpus's only use of `::` is for runtime-internal timer events
    ;; (e.g. ::after-elapsed); we rewrite to a stable namespace so the
    ;; fixture loads. Tracked as rf2-lu3f.
    (let [raw (slurp file)
          fixed (clojure.string/replace raw #"::([a-zA-Z][a-zA-Z0-9_-]*)"
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
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
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
  (require 're-frame.ssr :reload)
  ;; Spec 014 — re-register :rf.http/managed and friends after clear-all!.
  (require 're-frame.http-managed :reload)
  ;; Spec 005 — re-register :rf.machine/spawn / :rf.machine/destroy fx and the :rf/machine
  ;; sub after clear-all!. Per rf2-suue the spawn/destroy fx now wire the
  ;; live actor handler + snapshot, so the runtime side of the spawn must
  ;; be present for the system-id fixtures to observe app-db state.
  (require 're-frame.machines :reload)
  ;; Reset id-allocators so nav-token / pending-nav / rank-reg / spawn ids
  ;; are stable across runs (the routing/machine fixtures assert against
  ;; literal "nav-1" / "nav-2" / ":http/post#1" strings).
  ((requiring-resolve 're-frame.routing/reset-counters!))
  ((requiring-resolve 're-frame.machines/reset-timers!))
  ;; Spec 014 — drop the in-flight request registry between fixtures.
  ((requiring-resolve 're-frame.http-managed/clear-all-in-flight!)))

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
  by realise-handlers to auto-wire (inject-cofx K) interceptors onto
  events whose bodies read coeffects. Returns a set of K."
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
  "DSL → a cofx handler fn (ctx) → ctx. The body's :set steps
  declare the value to inject; the runner places that value at
  [:coeffects cofx-id] (canonical convention — the cofx-id is the
  slot key per Spec 010 §Where schemas attach §On every reg-*).

  Per rf2-g25p, the body is realised against the inject-cofx ctx —
  values pass through `eval-value` so reflection forms (e.g.
  [:cofx-key K], [:fn :k a b]) resolve against the inbound coeffects
  /event the same way they do in event handler bodies. Multiple :set
  steps run in order; the final `:set` step wins (last-write semantics
  matching the canonical single-injection convention)."
  [cofx-id steps]
  (fn [ctx]
    (let [eval-value (requiring-resolve 're-frame.conformance/eval-value*)
          dsl-ctx    {:db    (get-in ctx [:coeffects :db])
                      :event (get-in ctx [:coeffects :event])
                      :cofx  (:coeffects ctx)}]
      (reduce (fn [c step]
                (case (first step)
                  :set  (let [[_ _path value] step
                              v (eval-value value dsl-ctx)]
                          (assoc-in c [:coeffects cofx-id] v))
                  :noop c
                  c))
              ctx
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
        ;; cofx that should auto-wire as inject-cofx interceptors on
        ;; event handlers (per rf2-g25p — the runner's first-pass
        ;; auto-injection convention). Stable lex order on cofx-id so
        ;; the last-write-wins outcome is deterministic across JVM /
        ;; CLJS / re-runs.
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
    (doseq [[id steps] (get handlers-map :event)]
      (let [[kind handler] (conformance/realise-event-handler steps)
            ;; Per Spec 010 §step 1 (rf2-jwm4): pull :spec / :doc from
            ;; the fixture's :fixture/registry :event meta and pass it
            ;; through to reg-event-* so validate-event! can find it.
            event-meta (get event-registry id {})
            ;; Per rf2-g25p: scan the body for [:cofx-key K] references;
            ;; for each K, auto-wire (inject-cofx C) for every C whose
            ;; namespace matches K (the conformance-corpus convention
            ;; for the schemas/cofx fixture). With no :spec to flag,
            ;; non-spec'd cofx still wire so the handler can read them.
            ks            (collect-cofx-keys steps)
            cofx-ids      (vec
                            (mapcat (fn [k]
                                      (or (get cofx-by-key k)
                                          (when (contains? cofx-registry k) [k])))
                                    ks))
            interceptors  (mapv cofx/inject-cofx cofx-ids)]
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
    (doseq [[id steps] (get handlers-map :sub)]
      (let [{:keys [kind inputs body]} (conformance/realise-sub steps)
            ;; Per Spec 010 §step 6 (rf2-wcam): pull :spec from the
            ;; sub's registry meta so validate-sub-return! sees it.
            sub-meta (get sub-registry id {})]
        (case kind
          :layer-1 (if (seq sub-meta)
                     (rf/reg-sub id sub-meta body)
                     (rf/reg-sub id body))
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
    ;; :fixture/registry :fx (metadata, including :platforms / :spec).
    ;; A registry-only entry registers as a no-op so fx that the fixture
    ;; merely declares but doesn't body still resolve at do-fx time.
    (let [adapter-helpers
          {:read-db!  (fn [frame-id]
                        (frame/frame-app-db-value frame-id))
           :write-db! (fn [frame-id new-db]
                        (let [container (frame/get-frame-db frame-id)]
                          ((requiring-resolve 're-frame.substrate.adapter/replace-container!)
                           container new-db)))
           :dispatch! (fn [event frame-id]
                        (rf/dispatch event {:frame frame-id}))}
          fx-bodies   (get handlers-map :fx)
          fx-registry (get-in fixture [:fixture/registry :fx] {})
          all-fx-ids  (into #{} (concat (keys fx-bodies) (keys fx-registry)))]
      (doseq [id all-fx-ids]
        (let [body  (get fx-bodies id [[:noop]])
              meta  (get fx-registry id {})
              handler (conformance/realise-fx-handler id body adapter-helpers)]
          (rf/reg-fx id (assoc meta :handler-fn handler) handler))))
    ;; flow registrations — per Spec 013, flows are registered on the
    ;; current frame with positional :output fns. The fixture corpus
    ;; declares flow shape under :fixture/registry :flow (with :inputs /
    ;; :path / :doc) and the body DSL under :fixture/flow-bodies
    ;; (a parallel {flow-id [steps]} map). The harness realises each
    ;; body into a positional output fn and calls reg-flow.
    ;;
    ;; Dynamic flow registration via :rf.fx/reg-flow is handled in the
    ;; conformance DSL interpreter (see resolve-fx-args in conformance.cljc).
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
    ;; view registrations — DSL bodies map to fns that realise hiccup with
    ;; reflection forms resolved at call-time.
    (doseq [[id steps] (get handlers-map :view)]
      ((requiring-resolve 're-frame.registrar/register!)
       :view id
       {:handler-fn (conformance/realise-view-handler steps)}))
    ;; app-schema registrations — fixture's :fixture/registry :app-schema
    ;; is a {path schema} map. Per Spec 010, validation runs after each
    ;; :db commit.
    (doseq [[path schema] (get-in fixture [:fixture/registry :app-schema])]
      (rf/reg-app-schema path schema))
    ;; Per rf2-msd4: machine registrations. The fixture's
    ;; :fixture/registry :machine is a {machine-id <machine-spec>} map; the
    ;; spec's :actions / :guards / :on-spawn-actions slots may reference
    ;; bodies declared under :fixture/handlers :machine-action /
    ;; :machine-guard. We realise those bodies once here and merge them
    ;; into the spec before calling re-frame.machines/reg-machine, which
    ;; in turn calls reg-event-fx with create-machine-handler. From this
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

(defn- collect-traces [fixture-id]
  (let [traces (atom [])]
    (trace/register-trace-cb! [fixture-id] (fn [ev] (swap! traces conj ev)))
    traces))

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
  `:rf.fx/handled` trace with `:fx-id` (post-override) and `:fx-args`. A
  handler-throw emits `:rf.error/fx-handler-exception` with the same
  `:fx-id`/`:fx-args` shape — that's still a routing for the purposes of
  the fixture contract (the runtime did attempt the handler).

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
               {:fx-id   (get-in t [:tags :fx-id])
                :fx-args (get-in t [:tags :fx-args])}))))

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

(defn- register-routes! [fixture]
  ;; EDN maps don't preserve insertion order beyond ~8 entries. Routes
  ;; with structurally-equal rank tuples emit a warning at registration
  ;; whose tags depend on which side registered second, so we register
  ;; in deterministic lex order on the route-id.
  (doseq [[id meta] (sort-by (comp str key)
                             (get-in fixture [:fixture/registry :route]))]
    (rf/reg-route id meta)))

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
                ;; Per Spec 005 §Guards / §Actions canonical 2-arity:
                ;; the user-facing fn receives (data event), not the
                ;; snapshot. The fixture-step interpreter still threads
                ;; a local `ctx` map for its own reduction state.
                [id (fn [data event]
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
                                        ;; runtime's create-machine-handler
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
                ;; Per Spec 005 §Guards canonical 2-arity: (fn [data event]).
                [id (fn [data event]
                      (let [step (first steps)]
                        (when (and (vector? step) (= :fn (first step)))
                          (boolean
                            (eval-value step {:data data :event event})))))]))
        ;; Same machine-action steps, but realised as on-spawn callbacks.
        ;; Per rf2-een2 / rf2-smba: :set paths are data-relative — uniform
        ;; with regular machine actions; the callback signature is
        ;; (fn [data spawned-id] new-data).
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
          snap-out      (:re-frame.machines.result/snap r)
          fx-out        (:re-frame.machines.result/fx r)
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
          _            (realise-handlers fixture)
          _            (register-routes! fixture)
          frame-config (or (:fixture/frame-config fixture) {})
          frames-spec  (:fixture/frames fixture)
          ;; reset-runtime! already created :rf/default WITHOUT an :on-create.
          ;; reg-frame against an existing id is a surgical update that doesn't
          ;; re-fire :on-create per Spec 002. We destroy first so :on-create
          ;; fires when re-registered with the fixture's config.
          _            (rf/destroy-frame :rf/default)
          _            (cond
                         (seq frames-spec)
                         ;; Multi-frame fixture: register each declared frame.
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

          ;; Convention: :rf/hydrate events dispatch with :source :ssr-hydration
          ;; per Spec 011 §The :rf/hydrate event. Real clients pass this on the
          ;; hydrate-call site; the conformance runner stamps it for the user.
          (and (vector? ev) (= :rf/hydrate (first ev)))
          (rf/dispatch-sync ev {:source :ssr-hydration})

          :else
          (rf/dispatch-sync ev)))
      ;; :fixture/render-after-hydrate — for SSR hydration fixtures the
      ;; harness simulates the client-side first render. The runtime
      ;; (re-frame.ssr/verify-hydration!) owns the hash comparison and
      ;; :rf.ssr/hydration-mismatch trace; we just feed it the simulated
      ;; client hash. server-hash is read from the [:rf/hydration]
      ;; metadata that :rf/hydrate stashed in app-db.
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
                        ;; explicitly instead of reading [:rf/hydration].
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
      (let [apply-fn (requiring-resolve 're-frame.ssr/apply-pending-error-projection!)]
        (when apply-fn
          (doseq [fid (frame/frame-ids)]
            (try (apply-fn fid)
                 (catch Throwable _ nil)))))
      (let [expect       (or (:fixture/expect fixture) {})
            ;; Single-frame: :final-app-db. Multi-frame: :final-app-dbs as
            ;; {frame-id db}.
            expected-db  (:final-app-db expect)
            expected-dbs (:final-app-dbs expect)
            final-db     (rf/get-frame-db :rf/default)
            final-dbs    (when expected-dbs
                           (into {}
                                 (for [[fid _] expected-dbs]
                                   [fid (rf/get-frame-db fid)])))
            ;; Realise sub-checks BEFORE trace-failures: subscribing computes
            ;; the reaction body, which may emit :rf.error/sub-exception traces
            ;; that the trace-emissions check expects to see.
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                (let [[frame-id qv] (resolve-sub query-v)]
                  {:query    query-v
                   :expected expected-val
                   :actual   (rf/subscribe-value frame-id qv)})))
            trace-failures (check-trace-emissions @traces (:trace-emissions expect))
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
        (trace/clear-trace-cbs!)
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
      (println)
      (println "Conformance corpus:")
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
      ;; claimed-applicable fixture passes. Skipped fixtures (out-of-claim
      ;; capabilities, or fixtures targeting a spec version we don't
      ;; claim) do not count toward pass/fail — they are explicitly
      ;; reported but neither claim conformance nor block it.
      (is (zero? (count failed))
          (str "All claimed-applicable conformance fixtures must pass; "
               (count failed) " failed.")))))
