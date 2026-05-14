(ns re-frame.flows-conformance-test
  "Per rf2-4559c (audit rf2-o3hok §TE7). Drives every
  `spec/conformance/fixtures/flow-*.edn` fixture through the live flows
  runtime — `reg-flow`, `clear-flow`, the `:rf.fx/reg-flow` /
  `:rf.fx/clear-flow` runtime fxs, the per-frame flow registry, the
  topological sort, the dirty-check `last-inputs` map, the post-drain
  `run-flows!` walker, and the `:rf.flow/*` trace vocabulary — and
  asserts the conformance-corpus's recorded outcome against what the
  artefact actually produces.

  This is the flows artefact's own conformance gate. Pre-rf2-4559c the
  flow fixtures rode the CORE artefact's `re-frame.conformance-test`
  (the pattern rf2-d0wem set for machines and rf2-i3qc0 set for ssr;
  analogous gap for flows). Two drawbacks:

    1. Several flow-specific assertion channels — `:expect-trace-stream`,
       `:flow-recompute-counts`, `:flow-graph-topology`,
       `:flow-registry-after` — that the corpus documents under
       `spec/conformance/README.md` §Fixture lifecycle were not exercised
       by the core runner. Fixtures asserting only through those channels
       passed silently because the matcher was absent.
    2. The gate ran at the wrong artefact. A flows-touching PR could
       break a fixture and only surface at core's gate — at which
       point the failure has to be triaged across two artefacts.

  This namespace closes both gaps. It runs at the flows artefact's
  gate (so a flow regression fails the flows CI step), and it
  implements the flow-specific matchers (so the corpus's lifecycle /
  topology / dirty-check / hot-reload / trace contracts are checked
  against the live runtime).

  ## What this runner does

  For each `flow-*.edn` fixture:

    1. Resets the runtime (registrar, frames, flow registry,
       last-inputs, schemas, routing, ssr) — the same shape as
       `re-frame.flows-test`'s reset-runtime.
    2. Realises the fixture's `:fixture/handlers` (event, sub, fx) into
       native fns via the `re-frame.conformance` DSL interpreter (the
       interpreter is in `core/src` so the flows artefact has it on the
       classpath without pulling core's test tree).
    3. Registers any static flows declared under
       `:fixture/registry :flow` paired with `:fixture/flow-bodies`
       (via `re-frame.conformance/realise-flow-output-fn`).
    4. Registers the default frame with the fixture's
       `:fixture/frame-config` (including any `:on-create` seed events).
       Re-registers because reset-runtime created a vanilla
       `:rf/default`; the fixture's `:on-create` cascade fires here.
    5. Drives `:fixture/dispatches` through `rf/dispatch-sync`. Dispatches
       are either bare event vectors or envelope maps `{:event [...]
       :frame <id> ...}` (the multi-frame shape per
       `frame-multi-instance.edn`). The flow walker fires post-drain
       per Spec 013 §Drain integration.
    6. Asserts each of:
       - `:final-app-db` — submap match against `rf/get-frame-db`
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
         entry, every dep is a registered flow whose `:path` overlaps
         the dependent's `:inputs`.
       - `:flow-registry-after` — the set of flow ids in the per-frame
         registry after the final dispatch. Two shapes: a bare set
         `#{ids}` reads `:rf/default`'s slot (single-frame), or a map
         `{frame-id #{ids}}` reads each frame's slot (multi-frame).

  Frame topology is declared via `:fixture/frames [{:id ... :on-create ...} ...]`
  (multi-frame, per Spec 013 §Frame-scoping) OR `:fixture/frame-config`
  (single-frame, configures `:rf/default`).

  ## Capability claim

  Per `spec/conformance/README.md` §Capability tagging, the runner
  declares the surface its host implements. A fixture whose
  `:fixture/capabilities` are NOT a subset of `claimed-capabilities`
  is reported as out-of-claim and does not block the suite.

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
            [re-frame.conformance :as conformance]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.subs :as subs]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- runtime reset --------------------------------------------------------
;;
;; Mirrors `re-frame.flows-test/reset-runtime` (and the sibling
;; `flows_trace_test`'s reset). Each fixture in the corpus runs against a
;; clean registrar / frame table / flow registry / last-inputs slot.

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (rf/init! plain-atom/adapter)
  ;; Framework events / fx are registered at namespace-load time in
  ;; routing.cljc / ssr.cljc; clear-all! wiped them. Re-eval those
  ;; registrations so :rf.fx/reg-flow / :rf.fx/clear-flow (registered
  ;; via late-bind from flows.cljc's ns-load body) and any cross-artefact
  ;; framework events resolve.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.flows :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- fixture discovery ----------------------------------------------------

(def fixtures-dir
  "The corpus lives at the repo root under
  `spec/conformance/fixtures/`. The flows artefact's tests run from
  `implementation/flows/`, so the relative path is two-deep."
  (io/file "../../spec/conformance/fixtures"))

(defn- load-fixture
  "Read one EDN fixture, applying the same `::name` rewrite the core
  runner uses so `clojure.edn/read-string` (no reader resolver) accepts
  auto-resolved keywords. Per rf2-lu3f."
  [file]
  (try
    (let [raw   (slurp file)
          fixed (str/replace raw #"::([a-zA-Z][a-zA-Z0-9_-]*)"
                             ":rf.machine.timer/$1")]
      (edn/read-string fixed))
    (catch Throwable e
      {:fixture/load-error (.getMessage e)
       :fixture/file       (.getName file)})))

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
  against materialised flow outputs."
  #{:core/event-handler
    :core/sub
    :core/fx
    :core/trace
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
  {:read-db!  (fn [frame-id] (frame/frame-app-db-value frame-id))
   :write-db! (fn [frame-id new-db]
                (let [container (frame/get-frame-db frame-id)]
                  (substrate-adapter/replace-container! container new-db)))
   :dispatch! (fn [event frame-id] (rf/dispatch event {:frame frame-id}))})

(defn- realise-handlers
  "Register every handler the fixture declares (events / subs / fxs /
  flows). The fx slot reuses any `:rf.fx/reg-flow` / `:rf.fx/clear-flow`
  default handlers wired by `re-frame.flows` at ns-load; the fixture
  body may supply additional fx ids that the events emit."
  [fixture]
  (let [hmap          (or (:fixture/handlers fixture) {})
        event-meta    (get-in fixture [:fixture/registry :event] {})
        sub-meta      (get-in fixture [:fixture/registry :sub] {})
        fx-bodies     (:fx hmap)
        fx-registry   (get-in fixture [:fixture/registry :fx] {})
        helpers       (adapter-helpers)]
    ;; ---- events --------------------------------------------------------
    (doseq [[id steps] (:event hmap)]
      (let [[kind handler] (conformance/realise-event-handler steps)
            meta           (get event-meta id {})]
        (case kind
          :db (if (seq meta)
                (rf/reg-event-db id meta handler)
                (rf/reg-event-db id handler))
          :fx (if (seq meta)
                (rf/reg-event-fx id meta handler)
                (rf/reg-event-fx id handler)))))
    ;; ---- subs ----------------------------------------------------------
    (doseq [[id steps] (:sub hmap)]
      (let [{:keys [kind inputs body]} (conformance/realise-sub steps)
            meta                       (get sub-meta id {})]
        (case kind
          :layer-1 (if (seq meta) (rf/reg-sub id meta body) (rf/reg-sub id body))
          ;; Use the fn-form `subs/reg-sub` — the public `rf/reg-sub`
          ;; is a JVM macro (Spec 001 §Source-coordinate capture); a
          ;; macro var isn't `apply`-able.
          :layer-2 (apply subs/reg-sub id
                          (concat (when (seq meta) [meta])
                                  (interleave (repeat :<-) inputs)
                                  [body])))))
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
              handler (conformance/realise-fx-handler id body helpers)]
          (rf/reg-fx id (assoc meta :handler-fn handler) handler))))
    ;; ---- flows ---------------------------------------------------------
    ;; Per Spec 013 the static flow shapes live under :fixture/registry :flow
    ;; (with :inputs / :path) and the body DSL under :fixture/flow-bodies.
    ;; Dynamic flow registration via :rf.fx/reg-flow is handled by the
    ;; conformance DSL interpreter (resolve-fx-args in conformance.cljc
    ;; lifts the :body field into an :output fn before the fx fires).
    (let [flow-registry (get-in fixture [:fixture/registry :flow] {})
          flow-bodies   (or (:fixture/flow-bodies fixture) {})]
      (doseq [[flow-id flow-meta] flow-registry]
        (when-let [body (get flow-bodies flow-id)]
          (let [output-fn (conformance/realise-flow-output-fn body)]
            (rf/reg-flow (-> flow-meta
                             (assoc :id flow-id)
                             (assoc :output output-fn)))))))))

;; ---- trace capture -------------------------------------------------------

(defn- collect-traces
  "Register a trace listener for the fixture's run; the returned atom
  accumulates every captured trace event."
  [fixture-id]
  (let [traces (atom [])]
    (trace/register-trace-cb! [fixture-id]
                              (fn [ev] (swap! traces conj ev)))
    traces))

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

;; ---- flow-specific aggregators -------------------------------------------

(defn- recompute-counts
  "Count `:rf.flow/computed` events per `:flow-id` in the captured trace
  stream. `:rf.flow/skip` events do NOT count toward the recompute
  total — per Spec 013 §Dirty-check semantics, a `:skip` is the absence
  of a recompute. The fixture's `:flow-recompute-counts` map is the
  number of times each flow actually re-ran its `:output` fn."
  [traces]
  (reduce (fn [acc ev]
            (if (= :rf.flow/computed (:operation ev))
              (update acc (get-in ev [:tags :flow-id]) (fnil inc 0))
              acc))
          {}
          traces))

(defn- flow-graph-deps
  "Build the dependency graph from the per-frame flow registry. Flow B
  depends on flow A iff A's `:path` and any of B's `:inputs` share a
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
  (let [registry (get @flows/flows :rf/default {})]
    (into {}
          (for [[id flow] registry]
            [id (into #{}
                      (for [[other-id other-flow] registry
                            :when (not= id other-id)
                            ;; `topo/depends-on?` takes `(b-flow a-flow)`
                            ;; — "b depends on a". We want "this-flow
                            ;; depends on other-flow", so b=flow, a=other.
                            :when (topo/depends-on? flow other-flow)]
                        other-id))]))))

(defn- flow-registry-ids
  "Set of flow ids currently registered on `frame-id` (default
  `:rf/default`). The single-arg form preserves the original
  single-frame contract; the two-arg form is used by multi-frame
  fixtures (per Spec 013 §Frame-scoping)."
  ([] (flow-registry-ids :rf/default))
  ([frame-id]
   (into #{} (keys (get @flows/flows frame-id {})))))

;; ---- single-fixture execution -------------------------------------------

(defn- run-fixture
  "Run one fixture; return a result map shaped like the sibling
  conformance runners (`machines_conformance_test`, `ssr_conformance_test`)."
  [fixture]
  (try
    (let [fid          (:fixture/id fixture)
          traces       (collect-traces fid)
          _            (realise-handlers fixture)
          frame-config (or (:fixture/frame-config fixture) {})
          frames-spec  (:fixture/frames fixture)
          ;; `reset-runtime` already created :rf/default WITHOUT an
          ;; :on-create. `reg-frame` against an existing id is a
          ;; surgical update that does NOT re-fire :on-create (Spec 002).
          ;; Destroy first so the fixture's :on-create cascade fires
          ;; under its declared config.
          ;;
          ;; Multi-frame fixtures (per Spec 013 §Frame-scoping) declare
          ;; `:fixture/frames [{:id ...} ...]` and the single `:rf/default`
          ;; seam is bypassed. Single-frame fixtures keep the original
          ;; shape (`:fixture/frame-config` configures `:rf/default`).
          _            (rf/destroy-frame :rf/default)
          _            (if (seq frames-spec)
                         (doseq [f frames-spec]
                           (rf/reg-frame (:id f) (dissoc f :id)))
                         (rf/reg-frame :rf/default frame-config))
          dispatches   (or (:fixture/dispatches fixture) [])]
      ;; Dispatches may be a bare event vector (single-frame default) or
      ;; an envelope map `{:event [...] :frame <id> ...}` (multi-frame,
      ;; mirrors core's runner per spec/conformance/fixtures/frame-multi-instance.edn).
      (doseq [ev dispatches]
        (if (map? ev)
          (let [{event :event :as opts} ev]
            (rf/dispatch-sync event (dissoc opts :event)))
          (rf/dispatch-sync ev)))
      ;; ---- assertion gathering -----------------------------------------
      (let [expect           (or (:fixture/expect fixture) {})
            expected-db      (:final-app-db expect)
            ;; Multi-frame: `:final-app-dbs` is `{frame-id db}`; each db
            ;; submap-matches against that frame's `get-frame-db`.
            expected-dbs     (:final-app-dbs expect)
            final-db         (rf/get-frame-db :rf/default)
            final-dbs        (when expected-dbs
                               (into {}
                                     (for [[fid _] expected-dbs]
                                       [fid (rf/get-frame-db fid)])))
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                {:query    query-v
                 :expected expected-val
                 :actual   (rf/subscribe-value :rf/default query-v)}))
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
                               :else (flow-registry-ids))]
        (trace/clear-trace-cbs!)
        {:fixture-id        fid
         :passed?           (and (or (nil? expected-db)  (submap? expected-db final-db))
                                 (or (nil? expected-dbs) (every? (fn [[fid db]] (submap? db (get final-dbs fid)))
                                                                 expected-dbs))
                                 (every? #(= (:expected %) (:actual %)) sub-checks)
                                 (or (nil? stream-failures) (empty? stream-failures))
                                 (or (nil? emit-failures)   (empty? emit-failures))
                                 (or (nil? expected-counts) (= expected-counts actual-counts))
                                 (or (nil? expected-topo)   (= expected-topo actual-topo))
                                 (or (nil? expected-after)  (= expected-after actual-after)))
         :final-db          final-db
         :expected-db       expected-db
         :final-dbs         final-dbs
         :expected-dbs      expected-dbs
         :sub-checks        sub-checks
         :stream-failures   stream-failures
         :emit-failures     emit-failures
         :expected-counts   expected-counts
         :actual-counts     actual-counts
         :expected-topo     expected-topo
         :actual-topo       actual-topo
         :expected-after    expected-after
         :actual-after      actual-after}))
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
      (reset-runtime
        (fn []
          (cond
            (:fixture/load-error fixture)
            (swap! results conj {:fixture-id fname
                                 :skipped?   true
                                 :reason     "load error"
                                 :error      (:fixture/load-error fixture)})

            (not (spec-version-claimed? fixture))
            (swap! results conj {:fixture-id   (:fixture/id fixture)
                                 :fname        fname
                                 :skipped?     true
                                 :reason       "spec-version not in claimed set"
                                 :spec-version (:fixture/spec-version fixture)})

            (not (runnable-capability-set? fixture))
            (swap! results conj {:fixture-id   (:fixture/id fixture)
                                 :fname        fname
                                 :skipped?     true
                                 :reason       "capabilities outside flows-runner claim"
                                 :capabilities (:fixture/capabilities fixture)})

            :else
            (swap! results conj (assoc (run-fixture fixture) :fname fname))))))
    (let [all     @results
          run     (remove :skipped? all)
          passed  (filter :passed? run)
          failed  (remove :passed? run)
          skipped (filter :skipped? all)]
      (println)
      (println "Flows conformance corpus (flow-*.edn fixtures):")
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
        (println "Skipped:")
        (doseq [s skipped]
          (println "  " (:fixture-id s) "—" (:reason s)
                   (or (:capabilities s) (:spec-version s) (:error s)))))
      (when (seq failed)
        (println)
        (println "Failures:")
        (doseq [f failed]
          (println "  " (:fixture-id f))
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
              (println "    registry-after actual:  " (:actual-after f))))))
      (is (zero? (count failed))
          (str "All claim-runnable flow-*.edn conformance fixtures must pass; "
               (count failed) " failed.")))))
