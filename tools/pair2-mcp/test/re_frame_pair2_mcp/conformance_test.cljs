(ns re-frame-pair2-mcp.conformance-test
  "Per rf2-xkxbv (audit rf2-7hie3 §TE3). Drives the pair2-mcp tool catalogue
  through `tools/invoke` against a stub `conn` and asserts the recorded
  wire-shape EDN — the artefact's contract suite, sibling to:

    - `re-frame.conformance-test`            (core fixtures; rf2-d0wem)
    - `re-frame.ssr-conformance-test`        (ssr fixtures;  rf2-i3qc0)
    - `re-frame.schemas-conformance-test`    (schemas;       rf2-2l08g)
    - `re-frame.flows-conformance-test`      (flows;         rf2-4559c)
    - `re-frame.machines-conformance-test`   (machines;      rf2-d0wem)

  ## Why a conformance corpus for pair2-mcp

  The unit suite covers each tool's INNER logic in isolation
  (`get_path_test`, `snapshot_test`, `subscribe_test`, etc.) and the
  pipeline wire-up (`invoke_test`, `cache_test`, `wire_cap_test`). What
  was missing pre-rf2-xkxbv: a single corpus that pins the
  *outer* shape — for each MCP tool, given a canonical `tools/call`
  input and a deterministic stub `conn`, what wire envelope does
  `tools/invoke` produce?

  That's the contract the agent host actually consumes. Without a
  corpus pinning it, an accidental rename (`:reason :missing-event` →
  `:reason :no-event`) or a return-shape flip (`:value` → `:result`)
  would slip through the unit suite because each unit test only knows
  its own tool's vocabulary. The corpus is the cross-tool ratchet.

  ## Why the corpus lives inline

  The framework's `spec/conformance/fixtures/` directory carries
  data-shaped fixtures for the spec-level core/machines/ssr/schemas/
  flows surfaces. Pair2-mcp's contract is the MCP wire — a
  fundamentally different shape (tool name + JS args + canned eval
  responses → EDN result envelope). Cross-mounting onto the framework
  corpus would mix vocabularies; cross-host runners (a Python pair2-mcp
  port, say) reading the framework fixtures would see noise.

  So this runner keeps its fixtures inline as CLJS data — one map per
  case — and treats THIS namespace as the canonical pair2-mcp wire
  corpus. A follow-on bead (`rf2-???`) may later promote the corpus to
  `tools/pair2-mcp/spec/conformance/fixtures/*.edn` if cross-host
  reuse materialises; the in-memory shape will be the same.

  ## Stub conn — the deterministic seam

  `tools/invoke` calls `nrepl/cljs-eval-value` (via probe / per-tool
  bodies) at every meaningful step:

    1. `probe/runtime-preloaded?` — checks
       `js/globalThis.__re_frame_pair2_runtime`.
    2. The tool's own eval form — `(re-frame-pair2.runtime/...)`.

  We stub `nrepl/cljs-eval-value` with the same `set!` pattern
  `invoke_test` uses (async-friendly; `with-redefs` restores
  synchronously and gets blown away by Promise resolution). Each
  fixture declares `:fixture/eval-script` — a vector of
  `[form-predicate canned-result]` pairs. The stub matches forms
  by substring; first hit wins.

  This gives full control over what each tool sees from the runtime
  without compiling a runtime or opening a socket. The TEST corpus
  pins SHAPE, not runtime semantics — the framework's own fixtures
  pin the runtime semantics."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [applied-science.js-interop :as j]
            [re-frame-pair2-mcp.cache :as cache]
            [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools :as tools]
            [re-frame-pair2-mcp.tools.eval-cljs :as eval-cljs]))

;; ---------------------------------------------------------------------------
;; Test infrastructure — args coercion, result extraction, stub installation.
;;
;; Pair2-mcp passes args as a #js {} object; the tools read each slot via
;; `wire/arg` which does a `j/get`. The corpus carries args as CLJS maps
;; for readability; `args->js` converts on the fly.
;; ---------------------------------------------------------------------------

(defn- args->js [m]
  (let [o #js {}]
    (doseq [[k v] m]
      (j/assoc! o (if (keyword? k) (name k) k) v))
    o))

(defn- extract-text [^js result]
  (let [content (j/get result :content)
        item    (when (array? content) (aget content 0))]
    (when item (j/get item :text))))

(defn- extract-edn [^js result]
  (some-> (extract-text result) edn/read-string))

(defn- error? [^js result]
  (true? (j/get result :isError)))

;; ---------------------------------------------------------------------------
;; Stub installation — `set!` the `nrepl/cljs-eval-value` var with an
;; async-friendly Promise-returning fn, restore in `.finally` once the
;; Promise chain settles. Mirror the `invoke_test/with-stubs!` shape.
;;
;; We also stub `nrepl/cljs-eval` (the lower-level frame-returning fn) in
;; case a tool reaches in there directly; today only `cljs-eval-value` is
;; exercised but the safety belt is cheap.
;; ---------------------------------------------------------------------------

(defn- run-eval-script
  "Resolve `form-str` against `eval-script`. Each entry is
  `[match canned]` where `match` is either:

    - a string — substring match against `form-str`
    - `:default` — wildcard, always matches (use as last entry)

  First hit wins; returns the canned value. Throws if no match — that's
  a fixture authoring bug (the script didn't cover all forms the tool
  emits) and should surface loudly, not silently."
  [eval-script form-str]
  (loop [entries eval-script]
    (when (empty? entries)
      (throw (ex-info "eval-script did not match emitted form"
                      {:form    form-str
                       :script  (mapv first eval-script)})))
    (let [[match canned] (first entries)]
      (cond
        (= match :default)            canned
        (and (string? match)
             (str/includes? form-str match))
        canned
        :else (recur (rest entries))))))

(defn- with-stubbed-eval!
  "Install a stub `cljs-eval-value` that resolves per `eval-script`. Run
  `body-fn` (returning a Promise) and restore the original in
  `.finally` so cleanup outlives async resolution."
  [eval-script body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build-id form-str]
                (js/Promise.resolve (run-eval-script eval-script form-str)))
               ([_conn _build-id form-str _opts]
                (js/Promise.resolve (run-eval-script eval-script form-str))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! nrepl/cljs-eval-value orig))))))

;; ---------------------------------------------------------------------------
;; Wire-shape matchers — partial / submap matching against the parsed EDN
;; result.
;; ---------------------------------------------------------------------------

(defn- submap?
  "True if every k/v in `expected` appears in `actual` with a matching
  value. Recurses into nested maps."
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

(defn- check-fixture-result
  "Compare a parsed result against the fixture's `:fixture/expect` map.
  Returns `[passed? failure-msg]`.

  Expectation keys (any subset):

    - `:isError?`        true/false against `j/get :isError`
    - `:edn-submap`      map-shaped submap match against parsed EDN
    - `:edn-contains-keys` set of keys required at the top level of
                          the parsed EDN
    - `:reason`          shorthand for `{:reason <kw>}` submap
    - `:ok?`             shorthand for `{:ok? <bool>}` submap
    - `:via`             shorthand for `[:rf.mcp/cache-hit :via]`
                          equality check (cache-hit markers)
    - `:overflow?`       require an `:rf.mcp/overflow` marker"
  [^js result expect]
  (let [edn (extract-edn result)
        e   (j/get result :isError)]
    (cond
      (and (contains? expect :isError?)
           (not= (:isError? expect) (true? e)))
      [false (str "isError mismatch — expected " (:isError? expect)
                  ", actual " (true? e))]

      (and (contains? expect :ok?)
           (not= (:ok? expect) (:ok? edn)))
      [false (str ":ok? mismatch — expected " (:ok? expect)
                  ", actual " (:ok? edn))]

      (and (contains? expect :reason)
           (not= (:reason expect) (:reason edn)))
      [false (str ":reason mismatch — expected " (:reason expect)
                  ", actual " (:reason edn))]

      (and (contains? expect :via)
           (not= (:via expect) (get-in edn [:rf.mcp/cache-hit :via])))
      [false (str ":rf.mcp/cache-hit :via mismatch — expected "
                  (:via expect)
                  ", actual " (get-in edn [:rf.mcp/cache-hit :via]))]

      (and (contains? expect :overflow?)
           (not= (:overflow? expect)
                 (boolean (and (map? edn) (contains? edn :rf.mcp/overflow)))))
      [false (str ":rf.mcp/overflow expectation mismatch — expected "
                  (:overflow? expect))]

      (and (contains? expect :edn-submap)
           (not (submap? (:edn-submap expect) edn)))
      [false (str ":edn-submap mismatch — expected " (pr-str (:edn-submap expect))
                  ", actual " (pr-str edn))]

      (and (contains? expect :edn-contains-keys)
           (let [missing (remove #(contains? edn %) (:edn-contains-keys expect))]
             (seq missing)))
      [false (str ":edn-contains-keys missing: "
                  (pr-str (remove #(contains? edn %)
                                  (:edn-contains-keys expect))))]

      :else [true nil])))

;; ---------------------------------------------------------------------------
;; The corpus — one map per fixture. Inline EDN; readable as a catalogue
;; of "tool X with args Y produces wire shape Z". When the wire shape
;; changes, the corpus is what surfaces it.
;;
;; Each fixture:
;;   :fixture/id           — symbolic id (kebab-case namespaced kw)
;;   :fixture/doc          — one-line description
;;   :fixture/tool         — MCP tool name (string, matches registry)
;;   :fixture/args         — args map (keys → strings or keywords)
;;   :fixture/eval-script  — vector of [match canned] entries (see
;;                           `run-eval-script`). `:default` matches anything.
;;   :fixture/expect       — partial expectation map (see check-fixture-result).
;;   :fixture/cache-reset? — when true, reset the cache before invocation.
;;                           Default true; rare cache-replay fixtures set false.
;; ---------------------------------------------------------------------------

(def corpus
  "Inline conformance corpus for pair2-mcp's tool catalogue.

  Coverage matrix today (rf2-xkxbv, rf2-fnpqg):

    | Tool                   | Happy | Missing-arg | Degraded-runtime |
    |------------------------|-------|-------------|-------------------|
    | discover-app           | yes   | n/a         | yes               |
    | eval-cljs              | yes   | yes         | (covered by ↑ )   |
    | dispatch               | yes   | yes         | yes               |
    | trace-window           | yes   | n/a         | n/a               |
    | watch-epochs           | yes   | n/a         | n/a               |
    | tail-build             | yes   | n/a         | n/a               |
    | snapshot               | yes   | n/a         | yes (no-preload)  |
    | get-path               | yes   | yes         | yes (no-preload)  |
    | subscribe              | n/a   | yes         | n/a               |
    | unsubscribe            | n/a   | yes         | n/a               |
    | subscription-info      | yes   | n/a         | n/a               |
    | get-pair2-instructions | yes   | n/a         | n/a               |
    | (pipeline)             | cache-hit (precheck) ; unknown-tool error  |

  Streaming `subscribe` happy paths are covered exhaustively in
  `subscribe_test`; here we pin only the missing-arg shape because the
  full streaming machinery needs an actual `extra` payload the corpus
  runner doesn't simulate."
  [;; ---------- discover-app ----------------------------------------------
   ;; discover-app routes the runtime health map through several
   ;; precondition gates (`:debug-enabled?`, `:frames`,
   ;; `:ambiguous-frame?`, `:coord-annotation-enabled?`). The happy
   ;; path requires all of them to land in the documented shape, so
   ;; the canned health here mirrors the actual runtime contract.
   {:fixture/id    :discover-app/happy
    :fixture/doc   "discover-app on a healthy runtime adds :ok? true + :build-id to the health map."
    :fixture/tool  "discover-app"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  true]
     ["health"                    {:ok?                       true
                                   :debug-enabled?            true
                                   :coord-annotation-enabled? true
                                   :frames                    [:rf/default]
                                   :version                   "test"}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :build-id :app}}}

   {:fixture/id    :discover-app/preload-missing
    :fixture/doc   "discover-app surfaces :runtime-not-preloaded when the global marker is absent."
    :fixture/tool  "discover-app"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  false]
     [:default                    nil]]
    :fixture/expect
    {:edn-submap {:ok? false :reason :runtime-not-preloaded}}}

   ;; ---------- eval-cljs --------------------------------------------------
   ;; The launch-flag gate (rf2-cxx5s, cascade from rf2-czv3p) ships
   ;; DEFAULT-OFF in published builds — the operator passes `--allow-eval`
   ;; to opt in. Fixtures that drive the post-gate logical paths
   ;; (`:happy`, `:missing-form`) set `:fixture/allow-eval? true`; the
   ;; `:disabled` fixture pins the default-off envelope.
   {:fixture/id    :eval-cljs/disabled-default
    :fixture/doc   "eval-cljs with the launch-flag OFF returns :rf.error/eval-cljs-disabled."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(+ 1 2)"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :rf.error/eval-cljs-disabled}}

   {:fixture/id    :eval-cljs/happy
    :fixture/doc   "eval-cljs with --allow-eval returns {:ok? true :value v} on a successful runtime eval."
    :fixture/tool  "eval-cljs"
    :fixture/allow-eval? true
    :fixture/args  {:form "(+ 1 2)"}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  true]
     ["(+ 1 2)"                   3]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value 3}}}

   {:fixture/id    :eval-cljs/missing-form
    :fixture/doc   "eval-cljs with --allow-eval but without :form surfaces :missing-form."
    :fixture/tool  "eval-cljs"
    :fixture/allow-eval? true
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-form}}

   ;; ---------- dispatch ---------------------------------------------------
   {:fixture/id    :dispatch/happy
    :fixture/doc   "dispatch wraps the runtime's pair-dispatch! return in {:mode :queued ...}."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:counter/inc]"}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  true]
     ["pair-dispatch!"            {:queued? true}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:mode :queued :queued? true}}}

   {:fixture/id    :dispatch/missing-event
    :fixture/doc   "dispatch without :event surfaces :missing-event."
    :fixture/tool  "dispatch"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-event}}

   {:fixture/id    :dispatch/preload-missing
    :fixture/doc   "dispatch surfaces :runtime-not-preloaded when probe fails."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:counter/inc]"}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  false]
     [:default                    nil]]
    :fixture/expect
    {:edn-submap {:ok? false :reason :runtime-not-preloaded}}}

   ;; ---------- trace-window -----------------------------------------------
   {:fixture/id    :trace-window/happy
    :fixture/doc   "trace-window returns the runtime's epoch payload through the wire pipeline."
    :fixture/tool  "trace-window"
    :fixture/args  {:ms 500}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  true]
     ["trace-window-since"        {:epochs [] :since 0 :now 0}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-contains-keys #{:epochs}}}

   ;; ---------- watch-epochs -----------------------------------------------
   ;; watch-epochs wraps the runtime's `epochs-since` result into a
   ;; paged/cursor envelope: `:matches` (rather than raw `:epochs`),
   ;; `:has-more?`, `:next-cursor`, `:limit`, `:count`, `:head-id`,
   ;; `:dedup`, `:epochs-mode`, `:id-aged-out?`. Pin the envelope's
   ;; documented keys so accidental renames break the test.
   {:fixture/id    :watch-epochs/empty
    :fixture/doc   "watch-epochs surfaces the empty-window cursor envelope when the ring is empty."
    :fixture/tool  "watch-epochs"
    :fixture/args  {:max-ms 50 :poll-ms 25}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  true]
     [:default                    {:matches      []
                                   :id-aged-out? false
                                   :requested-id nil
                                   :head-id      nil
                                   :next-id      nil
                                   :remaining    0}]]
    :fixture/expect
    {:isError? false
     :edn-contains-keys #{:matches :has-more? :limit :count}
     :edn-submap        {:ok? true :has-more? false :count 0 :id-aged-out? false}}}

   ;; ---------- tail-build -------------------------------------------------
   {:fixture/id    :tail-build/timeout
    :fixture/doc   "tail-build surfaces its current build status via the runtime."
    :fixture/tool  "tail-build"
    :fixture/args  {:max-ms 10}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  true]
     [:default                    {:status :idle :last-completed 0}]]
    :fixture/expect
    {:isError? false}}

   ;; ---------- snapshot ---------------------------------------------------
   {:fixture/id    :snapshot/happy
    :fixture/doc   "snapshot returns the per-frame map shape through the wire pipeline."
    :fixture/tool  "snapshot"
    :fixture/args  {:frames "all"}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  true]
     ;; rf2-e35a5: the snapshot eval form now wraps its result as
     ;; `{:value <snap> :elided-count N}` so the elision count rides
     ;; back on the same nREPL round-trip; the wire-pipeline reads
     ;; the count from opts instead of re-walking client-side.
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :snapshot/preload-missing
    :fixture/doc   "snapshot surfaces :runtime-not-preloaded when probe fails."
    :fixture/tool  "snapshot"
    :fixture/args  {:frames "all"}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  false]
     [:default                    nil]]
    :fixture/expect
    {:edn-submap {:ok? false :reason :runtime-not-preloaded}}}

   ;; ---------- get-path ---------------------------------------------------
   {:fixture/id    :get-path/happy
    :fixture/doc   "get-path returns {:ok? true :exists? true :path p :value v}."
    :fixture/tool  "get-path"
    :fixture/args  {:path "[:counter]"}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  true]
     ;; rf2-e35a5: eval form pre-counts elision markers; the
     ;; envelope carries `:elided-count` so the wire-pipeline reads
     ;; the count from opts instead of re-walking the scalar.
     [:default                    {:ok? true :exists? true :path [:counter]
                                   :value 42 :elided-count 0}]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :exists? true :value 42}}}

   {:fixture/id    :get-path/missing-path
    :fixture/doc   "get-path without :path surfaces :missing-path."
    :fixture/tool  "get-path"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-path}}

   {:fixture/id    :get-path/path-not-found
    :fixture/doc   "get-path forwards the runtime's :path-not-found envelope."
    :fixture/tool  "get-path"
    :fixture/args  {:path "[:no-such :key]"}
    :fixture/eval-script
    [["__re_frame_pair2_runtime"  true]
     [:default                    {:ok? false :reason :path-not-found
                                   :path [:no-such :key]
                                   :deepest-valid-prefix []}]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? false :reason :path-not-found}}}

   ;; ---------- subscribe (missing-arg only — streaming path not simulated here)
   {:fixture/id    :subscribe/missing-topic
    :fixture/doc   "subscribe without :topic surfaces :missing-topic."
    :fixture/tool  "subscribe"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true}}

   ;; ---------- unsubscribe ------------------------------------------------
   {:fixture/id    :unsubscribe/missing-sub-id
    :fixture/doc   "unsubscribe without :sub-id surfaces :missing-sub-id."
    :fixture/tool  "unsubscribe"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true}}

   ;; ---------- subscription-info -----------------------------------------
   {:fixture/id    :subscription-info/empty
    :fixture/doc   "subscription-info with no active streams returns an empty list envelope."
    :fixture/tool  "subscription-info"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? false}}

   ;; ---------- get-pair2-instructions ------------------------------------
   ;; Inline-text tool (rf2-fnpqg). No nREPL round-trip; the result is a
   ;; pure-data def in the bundle, so the eval-script is irrelevant.
   {:fixture/id    :get-pair2-instructions/happy
    :fixture/doc   "get-pair2-instructions returns {:ok? true :tool ... :text <prose>}."
    :fixture/tool  "get-pair2-instructions"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :tool "get-pair2-instructions"}}}

   ;; ---------- pipeline: unknown tool ------------------------------------
   {:fixture/id    :pipeline/unknown-tool
    :fixture/doc   "invoke against a name not in the registry returns :unknown-tool error."
    :fixture/tool  "no-such-tool"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :unknown-tool}}])

;; ---------------------------------------------------------------------------
;; Cache reset between fixtures — `cache` is module-level state shared
;; across the entire corpus. A leaked entry from fixture N could mask a
;; bug in fixture N+1's first-call shape (e.g. by short-circuiting the
;; dispatch). Reset before every case.
;; ---------------------------------------------------------------------------

(defn- run-one-fixture
  "Drive one fixture through `tools/invoke` and check its result.
  Returns a Promise resolving to `{:fixture-id ... :passed? bool
  :failure ...}`.

  Honors `:fixture/allow-eval?` — flips the eval-cljs launch-flag gate
  (rf2-cxx5s) for this fixture's invocation and restores it afterward.
  Default OFF mirrors the published-build posture; opt-in fixtures
  exercise the post-gate paths."
  [{:fixture/keys [id tool args eval-script expect allow-eval?]}]
  (cache/clear!)
  (let [js-args   (args->js args)
        prev-gate (eval-cljs/allow-eval-enabled?)]
    (eval-cljs/set-allow-eval! (boolean allow-eval?))
    (with-stubbed-eval! eval-script
      (fn []
        (-> (tools/invoke nil tool js-args nil)
            (.then (fn [result]
                     (let [[ok? msg] (check-fixture-result result expect)]
                       {:fixture-id id
                        :passed?    ok?
                        :failure    msg
                        :result-edn (try (extract-edn result)
                                         (catch :default _ :unparseable))})))
            (.catch (fn [err]
                      {:fixture-id id
                       :passed?    false
                       :failure    (str "invoke threw: " (.-message err))
                       :exception  err}))
            (.finally (fn [] (eval-cljs/set-allow-eval! prev-gate))))))))

;; ---------------------------------------------------------------------------
;; The single deftest — walks the corpus serially (each fixture's stub
;; install + cache reset is sequential so cross-talk is impossible) and
;; aggregates results into one is-assertion.
;;
;; Why one deftest, not one per fixture: matches the framework-side
;; runners (`ssr-conformance-test`, `machines-conformance-test`); the
;; corpus is a unit of coverage, not N units. Failure reports name
;; each failing fixture by `:fixture/id` so a CI failure is
;; self-locating without forcing the runner to declare N `deftest`s
;; that would expand at compile time as the corpus grows.
;; ---------------------------------------------------------------------------

(deftest run-pair2-mcp-conformance-corpus
  (async done
    (let [results (atom [])]
      (-> (reduce
            (fn [chain fixture]
              (.then chain
                     (fn [_]
                       (-> (run-one-fixture fixture)
                           (.then (fn [r] (swap! results conj r)))))))
            (js/Promise.resolve nil)
            corpus)
          (.then
            (fn [_]
              (let [all     @results
                    passed  (filter :passed? all)
                    failed  (remove :passed? all)]
                ;; Silent-on-success (rf2-try1x): summary prints only
                ;; on failure.
                (when (seq failed)
                  (println)
                  (println "pair2-mcp conformance corpus:")
                  (println "  total: " (count all))
                  (println "  passed:" (count passed))
                  (println "  failed:" (count failed))
                  (println)
                  (println "Failures:")
                  (doseq [f failed]
                    (println "  " (:fixture-id f))
                    (println "    " (:failure f))
                    (when-let [r (:result-edn f)]
                      (println "     parsed-result:" (pr-str r)))))
                (is (zero? (count failed))
                    (str "All pair2-mcp conformance corpus fixtures must pass; "
                         (count failed) " failed."))
                (cache/clear!)
                (done))))))))
