(ns re-frame.flows-conformance-test
  "Drives every `spec/conformance/fixtures/flow-*.edn` fixture through the
  live flows runtime — `reg-flow`, `clear-flow`, the `:rf.fx/reg-flow` /
  `:rf.fx/clear-flow` runtime fxs, the per-frame flow registry, the
  topological sort, the dirty-check `last-inputs` map, the
  outermost-`:after` `run-flows-on-db` walker, and the `:rf.flow/*`
  trace vocabulary — and asserts the conformance-corpus's recorded outcome
  against what the artefact actually produces.

  This is the flows artefact's own conformance gate. It runs at the flows
  artefact's gate (so a flow regression fails the flows CI step, not core's),
  and it implements the flow-specific assertion channels — `:expect-trace-stream`,
  `:flow-recompute-counts`, `:flow-graph-topology`, `:flow-registry-after`
  (documented under `spec/conformance/README.md` §Fixture lifecycle) — so the
  corpus's lifecycle / topology / dirty-check / hot-reload / trace contracts
  are checked against the live runtime.

  ## What this runner does

  For each `flow-*.edn` fixture:

    1. Resets the runtime (registrar, frames, flow registry,
       last-inputs, schemas, routing, ssr) — the same shape as
       `re-frame.flows-test`'s reset fixture.
    2. Realises the fixture's `:fixture/handlers` (event, sub, fx) into
       native fns via the `re-frame.conformance` DSL interpreter (the
       interpreter is in `core/src` so the flows artefact has it on the
       classpath without pulling core's test tree).
    3. Registers any static flows declared under
       `:fixture/registry :flow` paired with `:fixture/flow-bodies`
       (via `re-frame.conformance/realise-flow-output-fn`).
    4. Registers the default frame with the fixture's
       `:fixture/frame-config` (including any `:initial-events` seed events).
       Re-registers because the reset fixture created a vanilla
       `:rf/default`; the fixture's `:initial-events` cascade fires here.
    5. Drives `:fixture/dispatches` through `rf/dispatch-sync`. Dispatches
       are either bare event vectors or envelope maps `{:event [...]
       :frame <id> ...}` (the multi-frame shape per
       `frame-multi-instance.edn`). The flow walker fires inside the
       handler's interceptor chain as the outermost `:after` — before the
       `:db` install — per Spec 013 §Drain integration.
    6. Asserts each of:
       - `:final-app-db` — submap match against `rf/app-db-value`
         (single-frame; reads `:rf/default`).
       - `:final-app-dbs` — `{frame-id db}` per-frame submap match
         (multi-frame; per Spec 013 §Frame-scoping).
       - `:sub-values` — exact match per query.
       - `:expect-trace-stream` — order-preserving subset match against
         the captured `:op-type :flow` trace events (partial-match on
         `:operation` + `:tags`).
       - `:trace-emissions` — order-preserving subset match against
         all captured trace events (compatible with the README's
         lifecycle channel; mirrors core's matcher).
       - `:flow-recompute-counts` — exact-count match per flow id
         against the count of `:rf.flow/computed` events captured
         (excludes `:rf.flow/skip`).
       - `:flow-graph-topology` — for each `flow-id #{dep-id ...}`
         entry, every dep is a registered flow whose `:output-path` overlaps
         the dependent's `:inputs`.
       - `:flow-registry-after` — the set of flow ids in the per-frame
         registry after the final dispatch. Two shapes: a bare set
         `#{ids}` reads `:rf/default`'s slot (single-frame), or a map
         `{frame-id #{ids}}` reads each frame's slot (multi-frame).

  Frame topology is declared via `:fixture/frames [{:id ... :initial-events ...} ...]`
  (multi-frame, per Spec 013 §Frame-scoping) OR `:fixture/frame-config`
  (single-frame, configures `:rf/default`).

  ## Capability claim

  Per `spec/conformance/README.md` §Capability tagging, the runner
  declares the surface its host implements via `claimed-capabilities` /
  `claimed-spec-versions`.

  Unlike the corpus-wide machines / ssr / core runners — which iterate
  the WHOLE fixture set and legitimately skip fixtures targeting another
  surface — this runner pre-filters to `flow-*.edn` ONLY (see
  `all-flow-fixtures`). Every fixture it sees is a flow fixture the flows
  artefact is the reference gate for. So an out-of-claim capability (or an
  unclaimed spec-version) on a flow fixture is never \"another surface's
  concern\": it is a newly-added flow capability this runner has not yet
  implemented, or a typo. Either way it must FAIL the gate loudly, not be
  silently skipped — a silent skip would let a new flow fixture's
  contract go entirely unchecked while the gate stayed green.

  Concretely: out-of-claim / unclaimed-spec-version flow fixtures are
  recorded as FAILURES (not non-blocking skips), so the
  suite's `(is (zero? failed))` assertion catches them. The fix is to
  extend `claimed-capabilities` (and implement the matcher) — a reviewed
  edit to this file — never to let the fixture skip. There is no
  known-skips list because the correct number of known skips is zero.

  The claim here covers the `:flow/*` capabilities every flow fixture
  exercises plus the bare `:core/*` capabilities every flow fixture
  cross-cuts (event / sub / fx / trace).

  ## Coverage scope

  Only `flow-*.edn` fixtures. The machines / ssr runners and the core
  artefact's full-corpus runner own everything else; running them
  again here would be redundant and would slow the gate."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.conformance :as rf.conformance]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.topo :as rf.flows.topo]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]))

;; ---- runtime reset --------------------------------------------------------
;;
;; The standard per-process runtime reset (registrar baseline + frames +
;; flows/schemas + plain-atom adapter) is owned by
;; `make-reset-runtime-fixture`. Two fixture-specific choices:
;;
;;   - `:ambient-frame nil` — the corpus's `run-fixture` calls `make-frame`
;;     itself and needs NO in-flight ambient scope so a frame's
;;     `:initial-events` cascade fires SYNCHRONOUSLY (Spec 002 §make-frame
;;     from inside a handler async-queues initial-events when
;;     `*current-frame*` is bound — EP-0002). The adapter install still
;;     ensures `:rf/default`; `run-fixture` pins the scope itself, after
;;     `make-frame`, around `realise-flows!` + the dispatch loop.
;;   - a small concern-specific fixture clears the always-on error-emit
;;     listener registry before each test (the standard reset clears trace
;;     and event listeners but not the error substrate), so an
;;     `:error-emit-records` matcher listener a prior fixture registered
;;     cannot leak into the next.

;; The composed per-fixture reset: the standard reset fixture (registrar
;; baseline + frames + flows/schemas + plain-atom adapter; `:ambient-frame
;; nil` per the rationale above) wrapping the always-on error-listener clear.
;; Bound as the `:each` fixture AND called directly by
;; `run-flows-conformance-corpus` to reset between the fixtures it iterates
;; inside a single deftest.
(def ^:private reset-runtime-fixture
  (let [standard (rf.test-support/make-reset-runtime-fixture
                   {:adapter       rf.substrate.plain-atom/adapter
                    :ambient-frame nil})]
    (fn [test-fn]
      (standard (fn []
                  (rf.error-emit/clear-error-listeners!)
                  (test-fn))))))

(use-fixtures :each reset-runtime-fixture)

;; ---- fixture discovery ----------------------------------------------------

(def fixtures-dir
  "The conformance corpus lives at the repo root under
  `spec/conformance/fixtures/`.

  Anchored to a CLASSPATH RESOURCE, not the working directory (rf2-ywrwkl,
  the same fix rf2-55j4s3 applied to 3 sibling core tests). The earlier
  `(io/file \"../../spec/conformance/fixtures\")` form assumed the JVM cwd
  was `implementation/flows/` so that `../../` reached the repo root. That
  holds for the canonical per-artefact gate (`clojure -M:test` run from
  `implementation/flows/`, which is what CI runs) but SILENTLY MIS-SCOPES
  under the combined `implementation/deps.edn :test` alias: run from
  `implementation/`, `../../` resolves ABOVE the repo root, `file-seq`
  returns nothing, and the corpus discovers zero fixtures (the rf2-3hamsq
  floor turns that mis-discovery RED instead of silent-green).

  This test namespace's own source file is on the test classpath (the
  artefact's `:test {:extra-paths [\"test\"]}`), so resolving it via
  `io/resource` pins the anchor to the on-disk source location regardless
  of cwd or which alias loaded the namespace. Walking five parents
  (`flows_conformance_test.clj → re_frame → test → flows → implementation →
  repo root`) reaches the repo root, then we descend into
  `spec/conformance/fixtures`."
  (let [res (io/resource "re_frame/flows_conformance_test.clj")]
    (assert res
            (str "flows-conformance-test cannot locate its own source on the "
                 "classpath — the flows test/ dir must be on the test "
                 "classpath for fixture discovery to anchor."))
    (-> (io/file res)        ; .../flows/test/re_frame/flows_conformance_test.clj
        .getParentFile       ; .../flows/test/re_frame
        .getParentFile       ; .../flows/test
        .getParentFile       ; .../flows
        .getParentFile       ; .../implementation
        .getParentFile       ; repo root
        (io/file "spec" "conformance" "fixtures")
        .getCanonicalFile)))

(defn- read-one-form
  "Read `text` as EXACTLY ONE top-level EDN form, or throw. `read-string`
  returns only the FIRST and silently discards the rest, so a fixture whose
  expectation block closes early passes having verified less than it claims
  (rf2-5mr6). Throws rather than returning `:fixture/load-error`, which the
  runner classifies as a SKIP — as silent as the defect. Full rationale on
  `re-frame.conformance-test/read-one-form` (rf2-98ni)."
  [text fixture-name]
  (let [eof  (Object.)
        rdr  (java.io.PushbackReader. (java.io.StringReader. text))
        fail (fn [why data]
               (throw (ex-info (str "conformance fixture " fixture-name " " why
                                    " (rf2-98ni, rf2-5mr6)")
                               (assoc data :fixture/file fixture-name))))
        rd   (fn []
               (try (edn/read {:eof eof} rdr)
                    (catch Exception e
                      (fail (str "is not readable EDN: " (.getMessage e))
                            {:fixture/reader-error (.getMessage e)}))))
        form (rd)]
    (when (identical? eof form)
      (fail "holds no top-level EDN form" {:fixture/forms 0}))
    (when-not (identical? eof (rd))
      (fail (str "must hold exactly ONE top-level EDN form — a plain read"
                 " returns the first and silently discards the rest")
            {:fixture/forms :more-than-one}))
    form))

(defn- load-fixture
  "Read one EDN fixture, applying the same `::name` rewrite the core
  runner uses so `clojure.edn/read-string` (no reader resolver) accepts
  auto-resolved keywords. Per rf2-lu3f."
  [file]
  (let [raw   (slurp file)
        fixed (str/replace raw #"::([a-zA-Z][a-zA-Z0-9_-]*)"
                           ":rf.machine.timer/$1")]
    (read-one-form fixed (.getName file))))

(defn- all-flow-fixtures
  "Every fixture file whose name matches `flow-*.edn`. Returns
  `[[filename fixture] ...]` in stable lex order."
  []
  (->> (file-seq fixtures-dir)
       (filter #(.isFile %))
       (filter #(let [n (.getName %)]
                  (and (str/starts-with? n "flow-")
                       (str/ends-with? n ".edn"))))
       (sort-by #(.getName %))
       (mapv (fn [f] [(.getName f) (load-fixture f)]))))

;; ---- claimed capability + spec-version sets ------------------------------

(def claimed-capabilities
  "The flow surface plus the `:core/*` capabilities every flow fixture
  cross-cuts. `:core/sub` is in the set because several fixtures
  (recompute-on-input-change, multi-input-topo) assert `:sub-values`
  against materialised flow outputs. `:core/error` covers the
  flow-eval-exception fixture (rf2-gmrks) which asserts both the
  cascade-level error trace and the always-on error-emit substrate."
  #{:core/event-handler
    :core/sub
    :core/fx
    :core/trace
    :core/error
    :flow/basic
    :flow/topo
    :flow/dirty-check
    :flow/toggle
    :flow/hot-reload
    :flow/trace
    :flow/frame-scoped})

(def claimed-spec-versions
  "Fixture spec versions this runner claims to conform against. Matches
  the core runner's set at rf2-4559c time."
  #{"1.0"})

(defn- runnable-capability-set?
  [fixture]
  (let [caps (or (:fixture/capabilities fixture) #{})]
    (every? claimed-capabilities caps)))

(defn- spec-version-claimed?
  [fixture]
  (let [v (:fixture/spec-version fixture)]
    (or (nil? v) (contains? claimed-spec-versions v))))

;; ---- handler realisation --------------------------------------------------
;;
;; The conformance corpus represents handler bodies as data; the
;; `re-frame.conformance` interpreter (in core/src — already on this
;; artefact's classpath) lifts the DSL into native fns. The wiring here
;; mirrors the relevant slice of `re-frame.conformance-test/realise-handlers`
;; — events, subs, fxs, flows. Cofx / schemas / views / heads /
;; machines are not exercised by any flow-*.edn fixture today; if one
;; lands later, extend this fn.

(defn- adapter-helpers
  "Helper map for `realise-fx-handler` — gives the fx-body DSL access
  to the frame's app-db via the substrate adapter and to the dispatch
  surface. Mirrors core's runner shape."
  []
  {:read-db!  (fn [frame-id] (rf.frame/frame-app-db-value frame-id))
   ;; EP-0001: write the app-db PARTITION via swap-frame-db! — app-db-container
   ;; is a read-only projection over the one physical frame-state container.
   :write-db! (fn [frame-id new-db]
                (rf.frame/swap-frame-db! frame-id (constantly new-db)))
   :dispatch! (fn [event frame-id] (rf/dispatch event {:frame frame-id}))})

(defn- realise-event-sub-fx-handlers
  "Register every event / sub / fx handler the fixture declares. Excludes
  flow registration — flows are FRAME-SCOPED (per Spec 013), so a
  destroy-frame! call between handler-registration and dispatch would
  clear them (rf2-wbtjn). Caller registers flows AFTER the frame is
  re-registered via `realise-flows!`.

  The fx slot reuses any `:rf.fx/reg-flow` / `:rf.fx/clear-flow` default
  handlers wired by `re-frame.flows` at ns-load; the fixture body may
  supply additional fx ids that the events emit."
  [fixture]
  (let [hmap          (or (:fixture/handlers fixture) {})
        event-meta    (get-in fixture [:fixture/registry :event] {})
        sub-meta      (get-in fixture [:fixture/registry :sub] {})
        fx-bodies     (:fx hmap)
        fx-registry   (get-in fixture [:fixture/registry :fx] {})
        helpers       (adapter-helpers)]
    ;; ---- events --------------------------------------------------------
    (doseq [[id steps] (:event hmap)]
      (let [[kind handler] (rf.conformance/realise-event-handler steps)
            meta           (get event-meta id {})]
        (case kind
          ;; EP-0018 Slice Z: one public `reg-event` (cofx-in, effects-map-out).
          ;; A :db-kind fixture handler is `(fn [db event] new-db)`; adapt it to
          ;; the single form by reading db from the coeffects and lowering the
          ;; returned db into a `{:db …}` effect — same observable behaviour.
          :db (let [h (fn [{:keys [db]} ev] {:db (handler db ev)})]
                (if (seq meta)
                  (rf/reg-event id meta h)
                  (rf/reg-event id h)))
          :fx (if (seq meta)
                (rf/reg-event id meta handler)
                (rf/reg-event id handler)))))
    ;; ---- subs ----------------------------------------------------------
    (doseq [[id steps] (:sub hmap)]
      (let [{:keys [kind inputs body]} (rf.conformance/realise-sub steps)
            meta                       (get sub-meta id {})]
        (case kind
          :layer-1 (if (seq meta) (rf/reg-sub id meta body) (rf/reg-sub id body))
          ;; Use the fn-form `subs/reg-sub` — the public `rf/reg-sub`
          ;; is a JVM macro (Spec 001 §Source-coordinate capture).
          ;; A declared dependency list rides the metadata map
          ;; (rf2-kuky.50), so the fixture's inputs go in as DATA.
          :layer-2 (rf.subs/reg-sub id (assoc meta :inputs (vec inputs)) body))))
    ;; ---- fxs -----------------------------------------------------------
    ;; A fixture may declare additional fxs the event handlers emit.
    ;; The reserved :rf.fx/reg-flow / :rf.fx/clear-flow are registered
    ;; via late-bind from re-frame.flows at ns-load time — fixtures
    ;; reference them by id without supplying a body. Skip those here
    ;; (their registry entry exists for meta lookups only).
    (let [reserved-fx-ids #{:rf.fx/reg-flow :rf.fx/clear-flow}
          all-ids         (into #{} (concat (keys fx-bodies) (keys fx-registry)))
          custom-ids      (remove reserved-fx-ids all-ids)]
      (doseq [id custom-ids]
        (let [body    (get fx-bodies id [[:noop]])
              meta    (get fx-registry id {})
              handler (rf.conformance/realise-fx-handler id body helpers)]
          (rf/reg-fx id (assoc meta :handler-fn handler) handler))))))

(defn- realise-flows!
  "Per Spec 013 the static flow shapes live under :fixture/registry :flow
  (with :inputs / :output-path) and the body DSL under :fixture/flow-bodies.
  Dynamic flow registration via :rf.fx/reg-flow is handled by the
  conformance DSL interpreter (resolve-fx-args in conformance.cljc
  lifts the :body field into an :derive fn before the fx fires).

  Called AFTER the frame is (re-)registered — see the
  ordering note in `run-fixture` (rf2-wbtjn)."
  [fixture]
  (let [flow-registry (get-in fixture [:fixture/registry :flow] {})
        flow-bodies   (or (:fixture/flow-bodies fixture) {})]
    (doseq [[flow-id flow-meta] flow-registry]
      (when-let [body (get flow-bodies flow-id)]
        (let [output-fn (rf.conformance/realise-flow-output-fn body)]
          ;; rf2-bqstzr — the 3-slot grammar: `(reg-flow flow-id metadata
          ;; derive-fn)`. The fixture `flow-meta` carries the reflection keys
          ;; (`:inputs` / `:output-path` / …) as the metadata middle slot; the
          ;; realised `output-fn` is the pure `:derive` value slot.
          (rf/reg-flow flow-id flow-meta output-fn))))))

;; ---- trace capture -------------------------------------------------------

(defn- collect-traces
  "Register a trace listener for the fixture's run; the returned atom
  accumulates every captured trace event."
  [fixture-id]
  (let [traces (atom [])]
    (rf.trace/register-listener! [fixture-id]
                              (fn [ev] (swap! traces conj ev)))
    traces))

;; ---- error-emit capture --------------------------------------------------
;;
;; Per Spec 013 §Failure semantics rule 4 + §Resolved decisions
;; §`:rf.error/flow-eval-exception` rides the always-on error substrate:
;; flow-eval failures fire on the always-on production error-emit substrate,
;; which survives CLJS `:advanced` +
;; `goog.DEBUG=false` elision. The `flow-eval-exception.edn` fixture's
;; `:error-emit-records` matcher asserts the listener captured a record
;; of the expected shape — the host-agnostic data-shape equivalent of
;; the JVM-side `re-frame.flows-trace-test` /
;; `re-frame.on-error-elision-prod-test` assertions.

(defn- collect-error-emit-records
  "Register a corpus-wide error-emit listener for the fixture's run;
  returns the captured-records atom."
  [fixture-id]
  (let [records (atom [])]
    (rf.error-emit/register-error-listener!
      [::flows-conformance fixture-id]
      (fn [r] (swap! records conj r)))
    records))

;; ---- matchers ------------------------------------------------------------

(defn- submap?
  "True if every key of `expected` is present in `actual` with a
  matching value. Recurses into nested maps."
  [expected actual]
  (cond
    (and (map? expected) (map? actual))
    (every? (fn [[k v]]
              (let [a (get actual k)]
                (if (and (map? v) (map? a))
                  (submap? v a)
                  (= v a))))
            expected)

    :else (= expected actual)))

(defn- trace-matches?
  "Partial match — every key of `exp` appears in `act` with a matching
  value. Nested-map keys partial-match the same way."
  [exp act]
  (every? (fn [[k v]]
            (let [a (get act k)]
              (cond
                (and (map? v) (map? a))
                (every? (fn [[kk vv]] (= vv (get a kk))) v)
                :else (= v a))))
          exp))

(defn- check-trace-stream
  "Order-preserving subset match — every expected trace must appear in
  `actual` in declaration order. Extras tolerated."
  [actual expected]
  (loop [actual   actual
         expected expected
         failures []]
    (cond
      (empty? expected) failures
      (empty? actual)
      (conj failures (str "expected trace not seen: " (pr-str (first expected))))
      :else
      (let [exp (first expected)
            i   (->> actual
                     (map-indexed vector)
                     (some (fn [[i a]] (when (trace-matches? exp a) i))))]
        (if i
          (recur (drop (inc i) actual) (rest expected) failures)
          (recur actual (rest expected)
                 (conj failures (str "expected trace not seen: " (pr-str exp)))))))))

(defn- check-trace-absent
  "Absence match — every pattern in `forbidden` must NOT appear anywhere
  in `actual`. Each pattern partial-matches like `check-trace-stream`'s
  expected entries (`trace-matches?`). Returns a vector of failure
  strings (empty ⇒ all absent). Powers the `:trace-absent` matcher used
  to pin, e.g., that a flow throw emits NO `:rf.event/db-changed` (the
  event aborted before the install — atomicity contract, rf2-u0zz5)."
  [actual forbidden]
  (reduce (fn [failures pat]
            (if (some #(trace-matches? pat %) actual)
              (conj failures (str "forbidden trace WAS seen: " (pr-str pat)))
              failures))
          []
          forbidden))

;; ---- flow-specific aggregators -------------------------------------------

(defn- recompute-counts
  "Count `:rf.flow/computed` events per `:flow-id` in the captured trace
  stream. `:rf.flow/skip` events do NOT count toward the recompute
  total — per Spec 013 §Dirty-check semantics, a `:skip` is the absence
  of a recompute. The fixture's `:flow-recompute-counts` map is the
  number of times each flow actually re-ran its `:derive` fn."
  [traces]
  (reduce (fn [acc ev]
            (if (= :rf.flow/computed (:operation ev))
              (update acc (get-in ev [:tags :flow-id]) (fnil inc 0))
              acc))
          {}
          traces))

(defn- flow-graph-deps
  "Build the dependency graph from the per-frame flow registry. Flow B
  depends on flow A iff A's `:output-path` and any of B's `:inputs` share a
  path prefix in either direction (the symmetric overlap rule per
  Spec 013 §Topological sort).

  Delegates to `re-frame.flows.topo/depends-on?` — single source of
  truth with the production runtime. Previously this fn inlined a
  local `prefix?` / `overlap?` pair that would silently disagree with
  the runtime if the dependency rule ever evolved (e.g. self-edge
  short-circuit, path-equality fast-path).

  Returns `{flow-id #{dep-id ...}}` for the `:rf/default` frame's
  flows. Fixtures that register on a non-default frame would extend
  this; today's flow-*.edn fixtures all target `:rf/default`."
  []
  (let [registry (get (rf.flows/flows-snapshot) :rf/default {})]
    (into {}
          (for [[id flow] registry]
            [id (into #{}
                      (for [[other-id other-flow] registry
                            :when (not= id other-id)
                            ;; `topo/depends-on?` takes `(b-flow a-flow)`
                            ;; — "b depends on a". We want "this-flow
                            ;; depends on other-flow", so b=flow, a=other.
                            :when (rf.flows.topo/depends-on? flow other-flow)]
                        other-id))]))))

(defn- flow-registry-ids
  "Set of flow ids currently registered on `frame-id` (default
  `:rf/default`). The single-arg form preserves the original
  single-frame contract; the two-arg form is used by multi-frame
  fixtures (per Spec 013 §Frame-scoping)."
  ([] (flow-registry-ids :rf/default))
  ([frame-id]
   (into #{} (keys (get (rf.flows/flows-snapshot) frame-id {})))))

(defn- last-inputs-frame-set
  "Per-flow set of frame-ids currently present in `last-inputs[flow-id]`.
  Per Spec 013 §Frame-destroy teardown: after `destroy-frame!`, the
  destroyed frame's row in every `last-inputs[flow-id]` MUST be dropped;
  the whole flow-id key drops when no other frame still holds an entry."
  [flow-id]
  (into #{} (keys (get (rf.flows/last-inputs-snapshot) flow-id {}))))

(defn- registrar-flow-slot-ids
  "The id set of the REAL `:flow` registrar slot, read from the authoritative
  registrar via the public `registrar/ids` query API.

  Per rf2-en00bk the `:flow` registrar kind is RESERVED but its slot is
  intentionally EMPTY — `reg-flow` writes ONLY the per-frame `flows` store
  (`{frame-id {flow-id flow-map}}`), never the registrar — so this set MUST be
  `#{}` at all times, before and after teardown. This is the authoritative
  source the `:registrar-flow-slots-after` matcher asserts against.

  rf2-3neiv: the prior matcher (`registrar-has-flow?`) read
  `flows/flows-snapshot` (the per-frame STORE — the WRONG source, already
  covered by `:flow-registry-after`) and the runner filtered the result over
  the EXPECTED set, so an empty `#{}` expectation matched UNCONDITIONALLY and a
  `:flow` registrar polluted with a forbidden row went undetected. Reading the
  registrar slot directly and comparing the FULL slot to the expected set (in
  `run-fixture` below) closes that gap — it proves the reserved-empty invariant
  holds against the real registrar, not a proxy. The sibling JVM test
  `re-frame.flows-destroy-frame-teardown-test` reads the registrar the same way
  (`registrar/lookup :flow …`)."
  []
  (rf.registrar/ids :flow))

;; ---- single-fixture execution -------------------------------------------

(defn- run-fixture
  "Run one fixture; return a result map shaped like the sibling
  conformance runners (`machines_conformance_test`, `ssr_conformance_test`)."
  [fixture]
  (try
    (let [fid          (:fixture/id fixture)
          traces       (collect-traces fid)
          ;; Always register an error-emit listener so the
          ;; `:error-emit-records` matcher has a captured
          ;; stream to assert against. Listeners with no expectations
          ;; cost nothing — the registry is cleared in `reset-runtime-fixture`
          ;; between fixtures.
          err-records  (collect-error-emit-records fid)
          frame-config (or (:fixture/frame-config fixture) {})
          frames-spec  (:fixture/frames fixture)
          ;; `reset-runtime-fixture` already created :rf/default WITHOUT any
          ;; :initial-events. `make-frame` against an existing id is a
          ;; surgical update that does NOT re-fire :initial-events (Spec 002).
          ;; Destroy first so the fixture's :initial-events cascade fires
          ;; under its declared config.
          ;;
          ;; Multi-frame fixtures (per Spec 013 §Frame-scoping) declare
          ;; `:fixture/frames [{:id ...} ...]` and the single `:rf/default`
          ;; seam is bypassed. Single-frame fixtures keep the original
          ;; shape (`:fixture/frame-config` configures `:rf/default`).
          ;;
          ;; Order: event / sub / fx handlers must be registered BEFORE
          ;; `make-frame` fires the `:initial-events` cascade — `:initial-events`
          ;; dispatch the fixture's seed event, which needs its handler
          ;; resolved. Flow registration MUST come AFTER `make-frame`: the
          ;; destroy-frame! teardown hook clears any flows registered against
          ;; the frame being destroyed, so registering them before the destroy
          ;; would wipe them.
          _            (rf/destroy-frame! :rf/default)
          _            (realise-event-sub-fx-handlers fixture)
          ;; make-frame runs with NO in-flight scope so its `:initial-events`
          ;; cascade fires SYNCHRONOUSLY (Spec 002 §make-frame from inside a
          ;; handler async-queues initial-events when `*current-frame*` is bound;
          ;; EP-0002). The carried-invariant scope is pinned AFTER make-frame,
          ;; around `realise-flows!` + the dispatch loop.
          _            (if (seq frames-spec)
                         (doseq [f frames-spec]
                           (rf/make-frame (assoc (dissoc f :id) :id (:id f))))
                         (rf/make-frame (assoc frame-config :id :rf/default)))
          dispatches   (or (:fixture/dispatches fixture) [])]
      ;; EP-0002: reg-flow + bare single-frame dispatches are context-required
      ;; frame-local. Pin :rf/default as the established scope for the
      ;; no-explicit-frame calls below. Multi-frame fixtures
      ;; pass explicit `{:frame …}` envelopes (the override), which win over
      ;; this ambient binding.
      (binding [rf.frame/*current-frame* :rf/default]
       (realise-flows! fixture)
      ;; Dispatches may be:
      ;;   - a bare event vector (single-frame default)
      ;;   - an envelope map `{:event [...] :frame <id> ...}` (multi-frame,
      ;;     mirrors core's runner per
      ;;     spec/conformance/fixtures/frame-multi-instance.edn)
      ;;   - a teardown step `{:destroy-frame <frame-id>}`
      ;;     (Spec 013 §Frame-destroy teardown). The runner calls
      ;;     `frame/destroy-frame!` on the named frame; subsequent
      ;;     dispatch / matcher checks see the post-teardown state.
      (doseq [ev dispatches]
        (cond
          (map? ev)
          (cond
            (contains? ev :destroy-frame)
            (rf.frame/destroy-frame! (:destroy-frame ev))

            :else
            (let [{event :event :as opts} ev]
              (rf/dispatch-sync event (dissoc opts :event))))

          :else
          (rf/dispatch-sync ev))))
      ;; ---- assertion gathering -----------------------------------------
      (let [expect           (or (:fixture/expect fixture) {})
            expected-db      (:final-app-db expect)
            ;; Multi-frame: `:final-app-dbs` is `{frame-id db}`; each db
            ;; submap-matches against that frame's `app-db-value`.
            expected-dbs     (:final-app-dbs expect)
            final-db         (rf/app-db-value :rf/default)
            final-dbs        (when expected-dbs
                               (into {}
                                     (for [[fid _] expected-dbs]
                                       [fid (rf/app-db-value fid)])))
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                {:query    query-v
                 :expected expected-val
                 :actual   (rf/subscribe-once query-v {:frame :rf/default})}))
            ;; `:expect-trace-stream` filters to `:op-type :flow` and
            ;; matches order-preserving subset. Mirrors the core runner's
            ;; `:trace-emissions` matcher with the op-type pre-filter.
            flow-traces      (filterv #(= :flow (:op-type %)) @traces)
            expected-stream  (:expect-trace-stream expect)
            stream-failures  (when expected-stream
                               (check-trace-stream flow-traces expected-stream))
            ;; `:trace-emissions` is the README's generic channel — same
            ;; matcher, no op-type filter. (Today's flow fixtures don't
            ;; use it but support is cheap and keeps parity with siblings.)
            expected-emits   (:trace-emissions expect)
            emit-failures    (when expected-emits
                               (check-trace-stream @traces expected-emits))
            ;; `:trace-absent` — a vector of trace patterns that MUST NOT
            ;; appear anywhere in the captured stream (no op-type filter).
            ;; Powers the atomicity-contract assertion that a flow throw
            ;; emits NO `:rf.event/db-changed`.
            forbidden-emits  (:trace-absent expect)
            absent-failures  (when forbidden-emits
                               (check-trace-absent @traces forbidden-emits))
            ;; `:flow-recompute-counts` — strict {flow-id n} match.
            expected-counts  (:flow-recompute-counts expect)
            actual-counts    (when expected-counts (recompute-counts @traces))
            ;; `:flow-graph-topology` — strict {flow-id #{dep-id ...}} match.
            expected-topo    (:flow-graph-topology expect)
            actual-topo      (when expected-topo
                               (select-keys (flow-graph-deps)
                                            (keys expected-topo)))
            ;; `:flow-registry-after` — strict set match. Two shapes:
            ;;   #{ids}             — flow ids on `:rf/default` (single-frame).
            ;;   {frame-id #{ids}}  — per-frame map (multi-frame, per
            ;;                        Spec 013 §Frame-scoping).
            expected-after   (:flow-registry-after expect)
            actual-after     (cond
                               (nil? expected-after) nil
                               (map? expected-after)
                               (into {}
                                     (for [[fid _] expected-after]
                                       [fid (flow-registry-ids fid)]))
                               :else (flow-registry-ids))
            ;; `:flow-last-inputs-after` — `{flow-id
            ;; #{frame-ids}}` strict match. Per Spec 013 §Frame-destroy
            ;; teardown: after `destroy-frame!`, the destroyed frame's
            ;; row in every `last-inputs[flow-id]` MUST be dropped; the
            ;; whole flow-id key drops when no other frame still holds
            ;; an entry.
            expected-li      (:flow-last-inputs-after expect)
            actual-li        (when expected-li
                               (into {}
                                     (for [[flow-id _] expected-li]
                                       [flow-id (last-inputs-frame-set flow-id)])))
            ;; `:registrar-flow-slots-after` — strict set match of the REAL
            ;; `:flow` registrar slot after the fixture's teardown. Per
            ;; rf2-en00bk the `:flow` registrar kind is reserved-EMPTY (flows
            ;; live in the per-frame store, checked by `:flow-registry-after`),
            ;; so this MUST be `#{}`. Read the FULL slot from the authoritative
            ;; registrar and compare it EXACTLY to the expected set below — NOT
            ;; a filter over the expected set (rf2-3neiv: filtering over an
            ;; empty `#{}` expectation matched unconditionally, so a polluted
            ;; registrar slid through undetected).
            expected-slots   (:registrar-flow-slots-after expect)
            actual-slots     (when expected-slots (registrar-flow-slot-ids))
            ;; `:error-emit-records` — order-preserving subset match against
            ;; the captured always-on error-emit substrate stream. Per Spec 013
            ;; §Failure semantics rule 4 + Resolved decisions
            ;; §`:rf.error/flow-eval-exception` rides the always-on error
            ;; substrate: the substrate fires under CLJS `:advanced` +
            ;; `goog.DEBUG=false` — this matcher is the host-agnostic data-shape
            ;; equivalent of the JVM-side prod-elision proof.
            expected-err     (:error-emit-records expect)
            err-failures     (when expected-err
                               (check-trace-stream @err-records expected-err))]
        (rf.trace/clear-listeners!)
        (rf.error-emit/clear-error-listeners!)
        {:fixture-id        fid
         :passed?           (and (or (nil? expected-db)  (submap? expected-db final-db))
                                 (or (nil? expected-dbs) (every? (fn [[fid db]] (submap? db (get final-dbs fid)))
                                                                 expected-dbs))
                                 (every? #(= (:expected %) (:actual %)) sub-checks)
                                 (or (nil? stream-failures) (empty? stream-failures))
                                 (or (nil? emit-failures)   (empty? emit-failures))
                                 (or (nil? absent-failures) (empty? absent-failures))
                                 (or (nil? expected-counts) (= expected-counts actual-counts))
                                 (or (nil? expected-topo)   (= expected-topo actual-topo))
                                 (or (nil? expected-after)  (= expected-after actual-after))
                                 (or (nil? expected-li)     (= expected-li actual-li))
                                 (or (nil? expected-slots)  (= expected-slots actual-slots))
                                 (or (nil? err-failures)    (empty? err-failures)))
         :final-db          final-db
         :expected-db       expected-db
         :final-dbs         final-dbs
         :expected-dbs      expected-dbs
         :sub-checks        sub-checks
         :stream-failures   stream-failures
         :emit-failures     emit-failures
         :absent-failures   absent-failures
         :expected-counts   expected-counts
         :actual-counts     actual-counts
         :expected-topo     expected-topo
         :actual-topo       actual-topo
         :expected-after    expected-after
         :actual-after      actual-after
         :expected-li       expected-li
         :actual-li         actual-li
         :expected-slots    expected-slots
         :actual-slots      actual-slots
         :err-failures      err-failures}))
    (catch Throwable e
      {:fixture-id (:fixture/id fixture)
       :passed?    false
       :error      (.getMessage e)
       :exception  e})))

;; ---- the test entrypoint -------------------------------------------------

(deftest run-flows-conformance-corpus
  (let [results (atom [])]
    (doseq [[fname fixture] (all-flow-fixtures)]
      ;; Each fixture needs a clean runtime — reset between fixtures even
      ;; though `use-fixtures :each` already reset at deftest entry, because
      ;; the deftest itself iterates the whole corpus.
      (reset-runtime-fixture
        (fn []
          (cond
            ;; A flow fixture that will not parse, declares a spec-version
            ;; this runner does not claim, or declares a capability outside
            ;; the claim is NOT a benign skip — this runner is the reference
            ;; gate for every `flow-*.edn` fixture. Record it as
            ;; a FAILURE so `(is (zero? failed))` catches it; the fix is to
            ;; fix the fixture or extend `claimed-*` + implement the matcher
            ;; (a reviewed edit to this file), never to let it skip.
            (:fixture/load-error fixture)
            (swap! results conj {:fixture-id fname
                                 :fname      fname
                                 :passed?    false
                                 :reason     "load error"
                                 :error      (:fixture/load-error fixture)})

            (not (spec-version-claimed? fixture))
            (swap! results conj {:fixture-id   (:fixture/id fixture)
                                 :fname        fname
                                 :passed?      false
                                 :reason       "spec-version not in claimed set"
                                 :spec-version (:fixture/spec-version fixture)})

            (not (runnable-capability-set? fixture))
            (swap! results conj {:fixture-id   (:fixture/id fixture)
                                 :fname        fname
                                 :passed?      false
                                 :reason       "capabilities outside flows-runner claim"
                                 :capabilities (:fixture/capabilities fixture)})

            :else
            (swap! results conj (assoc (run-fixture fixture) :fname fname))))))
    (let [all     @results
          passed  (filter :passed? all)
          failed  (remove :passed? all)]
      ;; rf2-3hamsq — non-empty floor. This runner has no skip bucket:
      ;; every discovered flow-*.edn fixture is runnable (an out-of-claim
      ;; capability or unclaimed spec-version is a FAILURE, not a skip),
      ;; so `all` IS the executed set. The lone (zero? (count failed))
      ;; below passes GREEN over an empty / orphaned corpus (wrong cwd or
      ;; a fixtures-dir rename emptying file-seq) — verifying NOTHING.
      ;; Assert that fixtures actually executed:
      ;;   - (pos? (count all)) catches the fully-empty case;
      ;;   - the expected-minimum (>= 7) catches partial mass-orphaning
      ;;     without pinning an exact count (today's count is 9 flow-*.edn
      ;;     fixtures; the set grows).
      (is (pos? (count all))
          "at least one flow-*.edn fixture must have executed")
      (is (>= (count all) 7)
          (str "flows corpus fixture floor (>= 7): only "
               (count all) " executed — a fixtures-dir/cwd fault has "
               "orphaned the corpus."))
      ;; Silent-on-success: the summary prints only on failure.
      ;; No skip bucket: a flow fixture this runner cannot run — load error,
      ;; unclaimed spec-version, out-of-claim capability — is a FAILURE, not a
      ;; non-blocking skip.
      (when (seq failed)
        (println)
        (println "Flows conformance corpus (flow-*.edn fixtures):")
        (println "  total fixtures:" (count all))
        (println "  passed:        " (count passed))
        (println "  failed:        " (count failed))
        (println)
        (println "Failures:")
        (doseq [f failed]
          (println "  " (:fixture-id f))
          ;; Out-of-claim / load-error failures carry a `:reason` instead of
          ;; matcher diffs — surface it (plus the offending caps / version).
          (when-let [reason (:reason f)]
            (println "    reason:" reason
                     (or (:capabilities f) (:spec-version f) "")))
          (when (:error f)
            (println "    error:" (:error f)))
          (when-let [td (:expected-db f)]
            (when (not (submap? td (:final-db f)))
              (println "    expected app-db:" td)
              (println "    actual   app-db:" (:final-db f))))
          (when-let [tds (:expected-dbs f)]
            (doseq [[fid expected] tds]
              (let [actual (get (:final-dbs f) fid)]
                (when (not (submap? expected actual))
                  (println "    expected app-db" fid ":" expected)
                  (println "    actual   app-db" fid ":" actual)))))
          (doseq [sc (:sub-checks f)]
            (when (not= (:expected sc) (:actual sc))
              (println "    sub" (:query sc)
                       "expected:" (:expected sc)
                       "actual:" (:actual sc))))
          (doseq [tf (:stream-failures f)]
            (println "    flow-trace:" tf))
          (doseq [ef (:emit-failures f)]
            (println "    trace-emit:" ef))
          (doseq [af (:absent-failures f)]
            (println "    trace-absent:" af))
          (when-let [ec (:expected-counts f)]
            (when (not= ec (:actual-counts f))
              (println "    recompute-counts expected:" ec)
              (println "    recompute-counts actual:  " (:actual-counts f))))
          (when-let [et (:expected-topo f)]
            (when (not= et (:actual-topo f))
              (println "    topology expected:" et)
              (println "    topology actual:  " (:actual-topo f))))
          (when (some? (:expected-after f))
            (when (not= (:expected-after f) (:actual-after f))
              (println "    registry-after expected:" (:expected-after f))
              (println "    registry-after actual:  " (:actual-after f))))
          (when (some? (:expected-li f))
            (when (not= (:expected-li f) (:actual-li f))
              (println "    last-inputs-after expected:" (:expected-li f))
              (println "    last-inputs-after actual:  " (:actual-li f))))
          (when (some? (:expected-slots f))
            (when (not= (:expected-slots f) (:actual-slots f))
              (println "    registrar-slots expected:" (:expected-slots f))
              (println "    registrar-slots actual:  " (:actual-slots f))))
          (doseq [ef (:err-failures f)]
            (println "    error-emit:" ef))))
      (is (zero? (count failed))
          (str "Every flow-*.edn conformance fixture must pass (an out-of-claim "
               "capability or unclaimed spec-version is itself a failure — extend "
               "the claim + matcher, don't skip); "
               (count failed) " failed.")))))

;; ---- mutation control: the registrar-slots matcher has teeth --------------
;;
;; rf2-3neiv regression guard. The `:registrar-flow-slots-after` matcher exists
;; to prove teardown leaves NO `:flow` registrar row behind (the reserved-empty
;; invariant, rf2-en00bk). The pre-fix matcher read the per-frame STORE and
;; filtered over the (empty) EXPECTED set, so a `:flow` registrar polluted with
;; a forbidden row passed UNCONDITIONALLY — the channel could not fail. This
;; control seats a forbidden `:flow` registrar row directly in the real
;; registrar, runs the real destroy fixture through `run-fixture`, and asserts
;; the fixture now FAILS specifically on the registrar-slots channel. It is the
;; red-before/green-after neuter: without the fix it would pass (matcher blind
;; to the registrar); with the fix it fails (matcher reads the real registrar).

(defn- destroy-teardown-fixture
  "The `:flow/frame-destroy-teardown` fixture loaded from the corpus."
  []
  (some (fn [[_fname fx]]
          (when (= :flow/frame-destroy-teardown (:fixture/id fx)) fx))
        (all-flow-fixtures)))

(deftest registrar-flow-slot-pollution-fails-the-destroy-fixture
  (let [destroy-fixture (destroy-teardown-fixture)]
    (is (some? destroy-fixture)
        "the flow-frame-destroy-teardown fixture must be discoverable in the corpus")
    ;; Positive control: a clean run reads the real registrar and finds it
    ;; empty — the matcher is now anchored to the authoritative slot.
    (reset-runtime-fixture
      (fn []
        (let [result (run-fixture destroy-fixture)]
          (is (:passed? result)
              (str "precondition: the unmutated destroy fixture passes — "
                   (pr-str (dissoc result :exception))))
          (is (= #{} (:actual-slots result))
              "clean teardown: the real `:flow` registrar slot is empty"))))
    ;; Neuter: seat a forbidden `:flow` registrar row. `reg-flow` never writes
    ;; the `:flow` registrar kind (it writes the per-frame store) and
    ;; `run-fixture` never clears it, so this row survives the whole fixture to
    ;; the matcher — the exact pollution the reserved-empty invariant forbids
    ;; and the pre-fix matcher could not observe.
    (reset-runtime-fixture
      (fn []
        (rf.registrar/register! :flow :rf.test/forbidden-registrar-row
                             {:doc "rf2-3neiv pollution control — teardown must not leave this behind"})
        (let [result (run-fixture destroy-fixture)]
          (is (not (:passed? result))
              "the fixture FAILS when the real `:flow` registrar carries a forbidden row")
          (is (contains? (:actual-slots result) :rf.test/forbidden-registrar-row)
              "the matcher READ the real registrar — the forbidden id appears in actual-slots")
          (is (not= (:expected-slots result) (:actual-slots result))
              "the registrar-slots channel specifically caught the pollution"))))))
