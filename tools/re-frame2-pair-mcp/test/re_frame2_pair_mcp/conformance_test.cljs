(ns re-frame2-pair-mcp.conformance-test
  "Drives the re-frame2-pair-mcp tool catalogue
  through `tools/invoke` against a stub `conn` and asserts the recorded
  wire-shape EDN — the artefact's contract suite, sibling to:

    - `re-frame.conformance-test`            (core fixtures)
    - `re-frame.ssr-conformance-test`        (ssr fixtures)
    - `re-frame.schemas-conformance-test`    (schemas)
    - `re-frame.flows-conformance-test`      (flows)
    - `re-frame.machines-conformance-test`   (machines)

  ## Why a conformance corpus for re-frame2-pair-mcp

  The unit suite covers each tool's INNER logic in isolation
  (`get_path_test`, `snapshot_test`, `subscribe_test`, etc.) and the
  pipeline wire-up (`invoke_test`, `cache_test`, `wire_cap_test`). This
  corpus pins the *outer* shape — for each MCP tool, given a canonical
  `tools/call` input and a deterministic stub `conn`, what wire envelope
  does `tools/invoke` produce?

  That's the contract the agent host actually consumes. Without a
  corpus pinning it, an accidental rename (`:reason :missing-event` →
  `:reason :no-event`) or a return-shape flip (`:value` → `:result`)
  would slip through the unit suite because each unit test only knows
  its own tool's vocabulary. The corpus is the cross-tool ratchet.

  ## Why the corpus lives inline

  The framework's `spec/conformance/fixtures/` directory carries
  data-shaped fixtures for the spec-level core/machines/ssr/schemas/
  flows surfaces. re-frame2-pair-mcp's contract is the MCP wire — a
  fundamentally different shape (tool name + JS args + canned eval
  responses → EDN result envelope). Cross-mounting onto the framework
  corpus would mix vocabularies; cross-host runners (a Python re-frame2-pair-mcp
  port, say) reading the framework fixtures would see noise.

  So this runner keeps its fixtures inline as CLJS data — one map per
  case — and treats THIS namespace as the canonical re-frame2-pair-mcp wire
  corpus. The corpus may later promote to
  `tools/re-frame2-pair-mcp/spec/conformance/fixtures/*.edn` if cross-host
  reuse materialises; the in-memory shape will be the same.

  ## Stub conn — the deterministic seam

  `tools/invoke` calls `nrepl/cljs-eval-value` (via probe / per-tool
  bodies) at every meaningful step:

    1. `probe/runtime-preloaded?` — checks
       `js/globalThis.__re_frame2_pair_runtime`.
    2. The tool's own eval form — `(re-frame2-pair.runtime/...)`.

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
            [re-frame2-pair-mcp.cache :as cache]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.eval-cljs :as eval-cljs]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.writes :as writes]))

;; ---------------------------------------------------------------------------
;; Test infrastructure — args coercion, result extraction, stub installation.
;;
;; re-frame2-pair-mcp passes args as a #js {} object; the tools read each slot via
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
  `.finally` so cleanup outlives async resolution.

  The `forms-seen` atom records every form-string the stub is asked to
  resolve so a fixture's `:fixture/eval-form-must-contain` slot can
  pin the SHAPE of the form sent over nREPL — used by the raw-state
  fixtures to verify the gate forces
  `:rf.size/include-sensitive? false` server-side (the walker-option
  namespaced keyword, NOT the wire-key — the wire-key is the
  unqualified `:include-sensitive`).

  Also stubs `nrepl/jvm-eval` so the preload-failure
  diagnostic ladder doesn't short-circuit to `:nrepl-unreachable` on
  every preload-missing fixture. The default JVM stub:
    - returns `{:value \"1\"}` for the `1` health-probe so
      `jvm-reachable?` resolves true.
    - returns `{:value \"[:app]\"}` for `active-builds` so the
      ladder treats `:app` as a running build.
  Together these mean a fixture scripting the cljs probe form to
  `false` lands on `:runtime-loaded-but-preload-missing` — the case
  the original hint was for. Fixtures wanting the other ladder
  rungs (`:build-not-running`, `:no-runtime-connected`,
  `:nrepl-unreachable`) override via the new `:fixture/jvm-eval-script`
  slot."
  ([eval-script forms-seen body-fn]
   (with-stubbed-eval! eval-script forms-seen nil body-fn))
  ([eval-script forms-seen jvm-eval-script body-fn]
   (let [orig-cljs nrepl/cljs-eval-value
         orig-jvm  nrepl/jvm-eval
         cljs-stub (fn
                     ([_conn _build-id form-str]
                      (swap! forms-seen conj form-str)
                      (js/Promise.resolve (run-eval-script eval-script form-str)))
                     ([_conn _build-id form-str _opts]
                      (swap! forms-seen conj form-str)
                      (js/Promise.resolve (run-eval-script eval-script form-str))))
         default-jvm-script
         ;; First-match wins; this is the failure-path default the
         ;; diagnostic ladder relies on.
         [["active-builds" {:value "[:app]"}]
          [:default        {:value "1"}]]
         effective-jvm-script (or jvm-eval-script default-jvm-script)
         jvm-stub (fn
                    ([_conn form-str]
                     (js/Promise.resolve (run-eval-script effective-jvm-script form-str)))
                    ([_conn form-str _opts]
                     (js/Promise.resolve (run-eval-script effective-jvm-script form-str))))]
     (set! nrepl/cljs-eval-value cljs-stub)
     (set! nrepl/jvm-eval         jvm-stub)
     (-> (js/Promise.resolve nil)
         (.then (fn [_] (body-fn)))
         (.finally (fn []
                     (tu/restore-eval! cljs-stub orig-cljs)
                     (tu/restore-jvm-eval! jvm-stub orig-jvm)))))))

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
  "Inline conformance corpus for re-frame2-pair-mcp's tool catalogue.

  Coverage matrix. Every advertised tool (see
  `test/fixtures/tool-names.json` — 30 today) appears below: either with a
  fixture in THIS inline corpus, or in the `Not exercised here` note that
  follows — so a tool cannot silently drop out of the map by omission:

    | Tool                   | Happy | Missing-arg | Degraded-runtime |
    |------------------------|-------|-------------|-------------------|
    | discover-app           | yes   | n/a         | yes (+port-unresolved) |
    | eval-cljs              | yes   | yes         | (covered by ↑ )   |
    | dispatch               | yes   | yes         | yes               |
    | dispatch-dry-run       | yes   | yes         | n/a (+gate/elision variants) |
    | restore-epoch          | yes   | yes         | gated-default     |
    | replace-app-db         | yes   | yes         | gated-default     |
    | trace-window           | yes   | n/a         | n/a               |
    | watch-epochs           | yes   | n/a         | n/a               |
    | tail-build             | yes   | n/a         | n/a               |
    | snapshot               | yes   | n/a         | yes (no-preload)  |
    | get-path               | yes   | yes         | yes (no-preload)  |
    | read-dom               | yes   | yes         | bad-selector      |
    | subscribe              | n/a   | yes         | n/a               |
    | unsubscribe            | n/a   | yes         | n/a               |
    | list-subscriptions     | yes   | n/a         | n/a               |
    | list-streams           | yes   | n/a         | n/a               |
    | get-stream-controls    | yes   | n/a         | n/a (no nREPL round-trip) |
    | handler-meta           | yes   | yes         | (short-circuit)   |
    | list-handlers          | yes   | yes         | (short-circuit)   |
    | set-operating-frame    | yes   | yes         | no-such-frame     |
    | reset-operating-frame  | yes   | n/a         | n/a               |
    | get-operating-frame    | yes   | n/a         | ambiguous (nil)   |
    | get-re-frame2-pair-instructions | yes   | n/a         | n/a               |
    | (pipeline)             | cache-hit (precheck) ; unknown-tool error  |

  Not exercised here (the 7 advertised tools with NO fixture in this
  corpus; coverage lives in the named dedicated per-tool test namespaces):
  `describe-image` (describe_image_test), `orient` (orient_test),
  `read-sub` (read_sub_test), `read-ui` (read_ui_test), `record` +
  `read-recording` (record_test), `watch-until` (watch_until_test).

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
    [["__re_frame2_pair_runtime"  true]
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
    :fixture/doc   "discover-app surfaces :runtime-loaded-but-preload-missing when the marker is absent (rf2-7tgfk — the specific rung of the diagnostic ladder)."
    :fixture/tool  "discover-app"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  false]
     [:default                    nil]]
    :fixture/expect
    {:edn-submap {:ok? false :reason :runtime-loaded-but-preload-missing}}}

   ;; A `:port` that maps to no build short-circuits BEFORE any
   ;; cljs-eval-value round-trip: `resolve-build-by-port` reads the
   ;; `:dev-http` map via `jvm-eval`; the corpus's default JVM stub answers
   ;; the non-`active-builds` lookup form with `{:value "1"}`, which
   ;; `read-string`s to `1` — not a keyword build-id — so the resolver
   ;; returns nil and discover-app emits `:port-unresolved`. rf2-bcayt7
   ;; pins that this rides as an `isError` result (a known-tool `:ok? false`
   ;; failure), NOT a success-shaped ok-text envelope that the response
   ;; cache could retain and mask a later valid port→build mapping.
   {:fixture/id    :discover-app/port-unresolved
    :fixture/doc   "discover-app with a :port that maps to no build rides as isError carrying :port-unresolved (rf2-bcayt7 — every :ok? false is isError; never a cacheable ok-text)."
    :fixture/tool  "discover-app"
    :fixture/args  {:port 9999}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :port-unresolved :port 9999}}}

   ;; ---------- eval-cljs --------------------------------------------------
   ;; The launch-flag gate ships DEFAULT-ON in published builds — the
   ;; operator passes `--no-eval` to opt OUT. Fixtures that drive the
   ;; post-gate logical paths (`:happy`, `:missing-form`,
   ;; `:no-runtime-for-build`) rely on the default ON state; the
   ;; `:disabled-via-no-eval` fixture pins the opt-out envelope via
   ;; `:fixture/eval-allowed? false`.
   {:fixture/id    :eval-cljs/disabled-via-no-eval
    :fixture/doc   "eval-cljs with --no-eval (gate flipped OFF) returns :rf.error/eval-cljs-disabled."
    :fixture/tool  "eval-cljs"
    :fixture/eval-allowed? false
    :fixture/args  {:form "(+ 1 2)"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :rf.error/eval-cljs-disabled}}

   {:fixture/id    :eval-cljs/happy
    :fixture/doc   "eval-cljs (gate at default ON) with explicit :build returns {:ok? true :value v} on a successful runtime eval."
    :fixture/tool  "eval-cljs"
    ;; Explicit :build short-circuits the auto-detect (which would
    ;; jvm-eval `active-builds` — not stubbed by this cljs-eval-only
    ;; harness). The runtime-sentinel preflight still runs.
    :fixture/args  {:form "(+ 1 2)" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["(+ 1 2)"                   3]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value 3 :build :app}}}

   ;; The typed result envelope distinguishes a GENUINE nil, an
   ;; eval-error, and an unserializable value rather than collapsing all
   ;; three to a bare null. The runtime classifies the result into a
   ;; tagged `:rf.mcp/result` map; the server projects it.
   {:fixture/id    :eval-cljs/typed-nil
    :fixture/doc   "eval-cljs of a form that genuinely returns nil rides back as :ok? true :value nil — a tagged :rf.mcp/result :nil, NOT a collapsed null (rf2-qobqy)."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(get {} :missing)" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["(get {} :missing)"         {:rf.mcp/result :nil}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value nil :build :app}}}

   {:fixture/id    :eval-cljs/typed-eval-error
    :fixture/doc   "eval-cljs of a form that throws (e.g. an unresolved symbol) surfaces a structured :rf.error/eval-cljs-threw, NOT a silent nil (rf2-qobqy)."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(undefined-symbol)" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["(undefined-symbol)"        {:rf.mcp/result :eval-error
                                   :reason :rf.error/eval-cljs-threw
                                   :ex "#error {:message \"undefined-symbol is not defined\"}"
                                   :message "undefined-symbol is not defined"}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :rf.error/eval-cljs-threw :build :app}}}

   {:fixture/id    :eval-cljs/typed-unserializable
    :fixture/doc   "eval-cljs of a form returning a #object/#js/Function surfaces :rf.error/unserializable with a :preview, NOT a collapsed null (rf2-qobqy)."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "js/console" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["js/console"                {:rf.mcp/result :unserializable
                                   :type "object"
                                   :preview "#object[Object [object console]]"}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :rf.error/unserializable
                  :type "object" :build :app}}}

   {:fixture/id    :eval-cljs/no-runtime-for-build
    :fixture/doc   "eval-cljs against a build with no live runtime fails loud (:no-runtime-for-build), never :ok? true :value nil (rf2-ivlb3). A preflight rejection routes through probe/err->result, so it surfaces as isError: true per the known-tool-failure contract (API §Result shape)."
    :fixture/tool  "eval-cljs"
    ;; Explicit :build so the path is deterministic against the
    ;; cljs-eval-only stub; sentinel probe returns false → fail loud.
    :fixture/args  {:form "(count [1 2 3])" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  false]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :no-runtime-for-build}}}

   {:fixture/id    :eval-cljs/missing-form
    :fixture/doc   "eval-cljs without :form surfaces :missing-form."
    :fixture/tool  "eval-cljs"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-form}}

   ;; ---- await opt-in ----
   ;; :await false (the default) sends the form verbatim — no await
   ;; wrapper. Pin that via :fixture/eval-form-must-not-contain.
   {:fixture/id    :eval-cljs/await-default-off-no-wrap
    :fixture/doc   "eval-cljs without :await sends the form verbatim — no await wrapper, no mailbox slot (rf2-xn4f9 default :await false)."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(+ 1 2)" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["(+ 1 2)"                   3]
     [:default                    nil]]
    :fixture/eval-form-must-not-contain ["__rf2pair_await__"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value 3 :build :app}}}

   ;; Await + non-thenable: the wrapper's synchronous arm reports
   ;; `:rf.mcp/await-direct v`; the server short-circuits with v as
   ;; the :value. Same shape as the no-await path.
   {:fixture/id    :eval-cljs/await-direct-passthrough
    :fixture/doc   "eval-cljs :await true on a non-thenable form returns the value via the wrapper's synchronous fast path (rf2-xn4f9)."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(+ 1 2)" :await true :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"           true]
     ;; The await wrapper for a non-thenable returns the
     ;; `:rf.mcp/await-direct` sentinel; the server pulls v out of it.
     [":rf.mcp/await-mailbox"  {:rf.mcp/await-direct 3}]
     [:default                  nil]]
    :fixture/eval-form-must-contain ["__rf2pair_await__"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value 3 :build :app}}}

   ;; Await + resolved thenable: wrapper returns the mailbox sentinel;
   ;; the server polls the read-mailbox form which resolves immediately.
   ;;
   ;; Substring-match ordering: the wrap form contains
   ;; `:rf.mcp/await-mailbox` (the keyword it emits as part of the
   ;; sentinel literal) AND `\"resolved\"` (in the `.then` handler that
   ;; writes the resolved status into the mailbox). The mailbox-read
   ;; form contains `cljs.reader/read-string` (the EDN re-read of the
   ;; mailbox value) — uniquely identifying it without false-matching
   ;; the wrap form. The script orders wrap-match first to keep
   ;; first-match-wins deterministic.
   {:fixture/id    :eval-cljs/await-resolved
    :fixture/doc   "eval-cljs :await true on a thenable that resolves returns {:ok? true :value <resolved>} after polling the mailbox (rf2-xn4f9)."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(-> (js/Promise.resolve {:hello \"world\"}) (.then identity))"
                    :await true :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"   true]
     [":rf.mcp/await-mailbox"      {:rf.mcp/await-mailbox "fixture-mbx"}]
     ["cljs.reader/read-string"    {:status :resolved :value {:hello "world"}}]
     [:default                     nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value {:hello "world"} :build :app}}}

   ;; Await + rejected thenable: the mailbox-read returns :rejected;
   ;; the server surfaces :rf.error/eval-cljs-rejected. rf2-acckgr: this
   ;; is a known-tool failure and MUST be isError: true per spec/003's
   ;; universal isError rule — it used to ride back as ok-text
   ;; (isError: false), masking the rejection as a success.
   {:fixture/id    :eval-cljs/await-rejected
    :fixture/doc   "eval-cljs :await true on a thenable that rejects returns {:ok? false :reason :rf.error/eval-cljs-rejected :rejection <pr-str>} as an isError envelope (rf2-xn4f9 / rf2-acckgr)."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(js/Promise.reject (ex-info \"nope\" {}))"
                    :await true :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"   true]
     [":rf.mcp/await-mailbox"      {:rf.mcp/await-mailbox "fixture-mbx"}]
     ["cljs.reader/read-string"    {:status :rejected :rejection "#error {:message \"nope\"}"}]
     [:default                     nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false
                  :reason :rf.error/eval-cljs-rejected
                  :rejection "#error {:message \"nope\"}"
                  :build :app}}}

   ;; ---------- dispatch ---------------------------------------------------
   ;; The DEFAULT dispatch returns the CONSEQUENCE via
   ;; `dispatch-consequence!`: `:db-changed? :changed-paths :effects-fired
   ;; :no-op?` so a no-op is VISIBLE, plus `:resolved` echoing the parsed
   ;; event. The runtime fn is `dispatch-consequence!`, distinct from the
   ;; `pair-dispatch!` transport ack used by the :queued path.
   {:fixture/id    :dispatch/happy
    :fixture/doc   "default dispatch returns the consequence via dispatch-consequence! (rf2-3bu3d.2 / rf2-3bu3d.3)."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:counter/inc]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-consequence!"     {:ok? true :epoch-id 7
                                   :db-changed? true :changed-paths [[:counter]]
                                   :effects-fired [:db] :no-op? false
                                   :resolved [:counter/inc]}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["dispatch-consequence!"]
    :fixture/expect
    {:isError? false
     :edn-submap {:mode :sync :ok? true :db-changed? true :no-op? false
                  :resolved [:counter/inc]}}}

   ;; A genuine NO-OP is VISIBLE: `:db-changed? false :effects-fired []
   ;; :no-op? true` rather than a bare success ack.
   {:fixture/id    :dispatch/no-op-visible
    :fixture/doc   "a no-op dispatch returns :db-changed? false :effects-fired [] :no-op? true (rf2-3bu3d.2)."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:noop/event]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-consequence!"     {:ok? true :epoch-id 8
                                   :db-changed? false :changed-paths []
                                   :effects-fired [] :no-op? true
                                   :resolved [:noop/event]}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:mode :sync :ok? true :db-changed? false
                  :effects-fired [] :no-op? true}}}

   ;; An unknown event-id is VALIDATED at the wire boundary and returns a
   ;; structured :unknown-id error with :nearest matches, WITHOUT
   ;; dispatching (the runtime `dispatch-consequence!` short-circuits on
   ;; the validation miss). No silent no-op success.
   {:fixture/id    :dispatch/unknown-id-validated
    :fixture/doc   "dispatch of an unregistered event-id surfaces :reason :unknown-id + :nearest, never a silent success (rf2-3bu3d.3)."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:rf/xrayy]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-consequence!"     {:ok? false :reason :unknown-id :kind :event
                                   :id :rf/xrayy :event [:rf/xrayy]
                                   :nearest [:rf/xray :rf/default]
                                   :resolved [:rf/xrayy] :dispatched? false
                                   :hint "unknown :event :rf/xrayy; did you mean :rf/xray, :rf/default?"}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :unknown-id :id :rf/xrayy
                  :nearest [:rf/xray :rf/default] :resolved [:rf/xrayy]}}}

   ;; `:queued true` opts into the async transport-ack: the runtime
   ;; `pair-dispatch!` return rides back with `:mode :queued` and
   ;; `:settled? false` when the cascade hasn't drained.
   {:fixture/id    :dispatch/queued-settled-false
    :fixture/doc   "dispatch :queued true returns the async ack (:mode :queued :settled? false) (rf2-3bu3d.2)."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:counter/inc]" :queued true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["pair-dispatch!"            {:ok? true :queued? true
                                   :cascade-summary-pending? true
                                   :before-epoch-id 12}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["pair-dispatch!"]
    :fixture/expect
    {:isError? false
     :edn-submap {:mode :queued :settled? false :cascade-summary-pending? true}}}

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
    :fixture/doc   "dispatch surfaces :runtime-loaded-but-preload-missing when the marker is absent (rf2-7tgfk diagnostic ladder)."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:counter/inc]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  false]
     [:default                    nil]]
    :fixture/expect
    {:edn-submap {:ok? false :reason :runtime-loaded-but-preload-missing}}}

   ;; Frame-targeted dispatch routes to the named frame. The documented
   ;; `frame` arg is the colon-prefixed id (":rf/xray"; Tool-Catalogue
   ;; §Id representation). The emitted runtime call MUST carry the
   ;; well-formed `:frame :rf/xray` opt and MUST NOT mint the malformed
   ;; `::rf/xray` (namespace ":rf") that raw `(keyword ...)` would produce
   ;; — which would be a silent no-op.
   {:fixture/id    :dispatch/frame-targeted-routes
    :fixture/doc   "dispatch frame ':rf/xray' emits {:frame :rf/xray} in the runtime opts — NOT the malformed ::rf/xray (rf2-ldfnx). Routes through dispatch-consequence! (rf2-3bu3d.2)."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:rf.xray/focus-event 85]" :frame ":rf/xray" :sync true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-consequence!"     {:ok? true :epoch-id 7 :frame :rf/xray
                                   :resolved [:rf.xray/focus-event 85]}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["dispatch-consequence!" ":frame :rf/xray"]
    :fixture/eval-form-must-not-contain
    ["::rf/xray"]
    :fixture/expect
    {:isError? false
     :edn-submap {:mode :sync :ok? true :frame :rf/xray}}}

   ;; When the named frame cannot be targeted (head did not advance), the
   ;; runtime returns {:ok? false :reason :no-new-epoch}. The tool MUST
   ;; surface a structured ERROR envelope, never a {:mode :sync} success
   ;; merged over the failure (which would be a silent wrong-success).
   {:fixture/id    :dispatch/frame-untargetable-error
    :fixture/doc   "dispatch surfaces the runtime's {:ok? false :reason :no-new-epoch} as an :isError envelope with NO :mode slot (rf2-ldfnx)."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:rf.xray/focus-event 85]" :frame ":rf/xray" :sync true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-consequence!"     {:ok? false :reason :no-new-epoch
                                   :event [:rf.xray/focus-event 85]
                                   :frame :rf/xray
                                   :resolved [:rf.xray/focus-event 85]
                                   :hint "dispatch-sync returned, but epoch-history head did not advance."}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :no-new-epoch :frame :rf/xray}}}

   ;; Render-settle. `:await-render true` wraps the settle Promise in the
   ;; await mailbox; the wrap-form eval returns the mailbox sentinel and
   ;; the poll read resolves to the dispatch envelope with
   ;; `:settled? true`. The emitted settle form MUST route
   ;; the flush through the substrate-agnostic adapter primitive
   ;; (`re-frame.interop/after-render`) + the paint boundary
   ;; (`requestAnimationFrame`) and force synchronous dispatch.
   {:fixture/id    :dispatch/await-render-settles
    :fixture/doc   "dispatch :await-render true resolves to the dispatch envelope with :settled? true after the render flush, routed through the adapter contract (rf2-gfu33)."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:counter/inc]" :await-render true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; The wrap form (contains the await-mailbox sentinel literal)
     ;; returns the mailbox sentinel; first-match-wins orders it before
     ;; the read form.
     [":rf.mcp/await-mailbox"     {:rf.mcp/await-mailbox "settle-mbx"}]
     ;; The poll read resolves immediately to the settled dispatch
     ;; envelope.
     ["cljs.reader/read-string"   {:status :resolved
                                   :value {:ok? true :epoch-id 9
                                           :frame :rf/default
                                           :settled? true
                                           :cascade-summary {:event-id :counter/inc
                                                             :renders 1}}}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame.interop/after-render"
     "requestAnimationFrame"
     "dispatch-consequence!"
     ":settled? true"]
    :fixture/eval-form-must-not-contain
    ["reagent" "setTimeout"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :mode :sync :settled? true :epoch-id 9}}}

   ;; ---------- dispatch-dry-run ------------------------------------------
   ;; Dry-run is NOT --allow-writes-gated: the override-set ensures no
   ;; observable effect escapes, and restore-epoch rewinds the would-be
   ;; epoch. No gate needed because the contract IS "no state change".
   {:fixture/id    :dispatch-dry-run/happy
    :fixture/doc   "dispatch-dry-run wraps the runtime's dispatch-dry-run envelope through unchanged — :cascade-summary + :would-fire-effects + :db-state-after-simulation."
    :fixture/tool  "dispatch-dry-run"
    :fixture/args  {:event "[:cart/checkout]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-dry-run"          {:ok? true :dry-run? true :rolled-back? true
                                   :event [:cart/checkout]
                                   :frame :rf/default
                                   :before-epoch-id 41
                                   :cascade-summary {:epoch-id 42
                                                     :event-id :cart/checkout
                                                     :event-vector [:cart/checkout]
                                                     :frame :rf/default
                                                     :outcome :ok
                                                     :db-diff {:changed-paths [[:cart]]
                                                               :added-paths []
                                                               :removed-paths []}
                                                     :fx-fired [:http]
                                                     :subs-recomputed 2
                                                     :renders 1}
                                   :would-fire-effects [{:fx-id :http :args {:url "/checkout"}}]
                                   :db-state-after-simulation {:cart {} :order {:id 1}}}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["dispatch-dry-run [:cart/checkout]"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :dry-run? true :rolled-back? true}}}

   {:fixture/id    :dispatch-dry-run/missing-event
    :fixture/doc   "dispatch-dry-run without :event surfaces :missing-event (mirrors dispatch's arg-parse gate)."
    :fixture/tool  "dispatch-dry-run"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-event}}

   {:fixture/id    :dispatch-dry-run/rejects-host-form
    :fixture/doc   "dispatch-dry-run rejects host-form source (rf2-vflrg posture inherited from dispatch)."
    :fixture/tool  "dispatch-dry-run"
    :fixture/args  {:event "(println :pwn)"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :not-an-event-vector}}

   ;; Dry-run is an AI-facing READ surface. Gate OFF (the default
   ;; published posture) MUST run the app-db-rooted egress slot
   ;; (:db-state-after-simulation) through the elision walker server-side,
   ;; with sensitive slots forced to redact and large slots forced to
   ;; elide. The :would-fire-effects[*].args slot is NOT app-db-rooted, so
   ;; it FAILS CLOSED (assoc :rf/redacted) rather than running through the
   ;; walker. The eval form must carry the walker call
   ;; (for the db slot) + the safe walker opts + the fx-args redaction
   ;; (touching :would-fire-effects), and must signal the raw-state tap
   ;; posture (configure-raw-state!) before the dispatch.
   {:fixture/id    :dispatch-dry-run/gate-off-redacts-egress
    :fixture/doc   "dispatch-dry-run with --allow-sensitive-reads OFF (default): the form runs :db-state-after-simulation through elide-wire-value with :rf.size/include-sensitive? false, and FAILS CLOSED the :would-fire-effects[*].args to :rf/redacted (rf2-z7roa + rf2-6to9xj)."
    :fixture/tool  "dispatch-dry-run"
    :fixture/allow-raw-state? false
    :fixture/args  {:event "[:auth/login]"
                    ;; caller tries to bypass; the gate forces safe.
                    :elision false
                    :include-sensitive true
                    :include-fx-args true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["dispatch-dry-run"          {:value {:ok? true :dry-run? true :rolled-back? true
                                           :would-fire-effects [{:fx-id :http :args :rf/redacted}]
                                           :db-state-after-simulation {:user {:token :rf/redacted}}}
                                   :elided-count 0}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame.core/elide-wire-value"
     ":db-state-after-simulation"
     ":would-fire-effects"
     ":rf.size/include-sensitive? false"
     ":rf.size/include-large? false"
     "configure-raw-state!"
     ":allow-raw-state? false"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :dry-run? true :elision true}}}

   ;; Fail-CLOSED. Gate ON + a BARE `:elision false` (no
   ;; `:include-sensitive true`) MUST still walk the app-db-rooted
   ;; `:db-state-after-simulation` slot: large content passes
   ;; (`include-large? true`) but a declared-sensitive db slot redacts.
   {:fixture/id    :dispatch-dry-run/gate-on-bare-elision-false-still-walks
    :fixture/doc   "dispatch-dry-run ON + :elision false (no sensitive opt-in) ⇒ form STILL runs :db-state-after-simulation through elide-wire-value with :rf.size/include-sensitive? false, :rf.size/include-large? true (rf2-t55hxg.13)."
    :fixture/tool  "dispatch-dry-run"
    :fixture/allow-raw-state? true
    :fixture/args  {:event "[:cart/checkout]" :elision false}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["dispatch-dry-run"          {:value {:ok? true :dry-run? true :db-state-after-simulation {:user {:token :rf/redacted}}}
                                   :elided-count 0}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame.core/elide-wire-value"
     ":rf.size/include-sensitive? false"
     ":rf.size/include-large? true"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :elision false}}}

   ;; The deliberate full-raw opt-in (`:elision false` AND
   ;; `:include-sensitive true`) is the ONLY combination that skips the db
   ;; walker. With `:include-fx-args` unset the fx args still fail closed,
   ;; so the form still touches `:would-fire-effects`.
   {:fixture/id    :dispatch-dry-run/gate-on-full-raw-opt-out
    :fixture/doc   "dispatch-dry-run ON + :elision false + :include-sensitive true ships the raw simulation details — NO walker wrap for the db slot (rf2-z7roa / rf2-t55hxg.13)."
    :fixture/tool  "dispatch-dry-run"
    :fixture/allow-raw-state? true
    :fixture/args  {:event "[:cart/checkout]" :elision false :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["dispatch-dry-run"          {:value {:ok? true :dry-run? true :db-state-after-simulation {:user {:token "raw"}}}
                                   :elided-count 0}]
     [:default                    nil]]
    :fixture/eval-form-must-not-contain
    ["elide-wire-value"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :elision false}}}

   ;; The :would-fire-effects[*].args slot carries RAW fx-handler
   ;; arguments (HTTP bodies, dispatched event vectors, payment maps) NOT
   ;; rooted at app-db, so the schema-path walker cannot prove
   ;; them safe. Off-box egress FAILS CLOSED: :args redacts to :rf/redacted
   ;; for every fx by default, while :fx-id rides through. The emitted form
   ;; carries the fail-close (assoc :args :rf/redacted on :would-fire-effects).
   ;; Gate ON (--allow-sensitive-reads) + :include-fx-args true is the
   ;; trusted-local opt-in that keeps the raw args — the form then does NOT
   ;; touch the :args slot.
   {:fixture/id    :dispatch-dry-run/gate-on-include-fx-args-reveals
    :fixture/doc   "dispatch-dry-run with --allow-sensitive-reads ON + :include-fx-args true keeps the raw :would-fire-effects[*].args — the emitted form does NOT assoc :rf/redacted onto the args (rf2-6to9xj)."
    :fixture/tool  "dispatch-dry-run"
    :fixture/allow-raw-state? true
    :fixture/args  {:event "[:auth/login]" :include-fx-args true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["dispatch-dry-run"          {:value {:ok? true :dry-run? true :rolled-back? true
                                           :would-fire-effects [{:fx-id :http :args {:headers {:authorization "Bearer raw-token"}}}]
                                           :db-state-after-simulation {:user {:token "raw"}}}
                                   :elided-count 0}]
     [:default                    nil]]
    :fixture/eval-form-must-not-contain
    ["assoc e :args :rf/redacted"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :dry-run? true
                  :would-fire-effects [{:fx-id :http :args {:headers {:authorization "Bearer raw-token"}}}]}}}

   {:fixture/id    :dispatch-dry-run/no-new-epoch
    :fixture/doc   "dispatch-dry-run surfaces the runtime's {:ok? false :reason :no-new-epoch} as an isError envelope (rf2-wdxyx3 finding 2 — a non-landed dry-run is never a silent success; parity with dispatch/no-new-epoch)."
    :fixture/tool  "dispatch-dry-run"
    :fixture/args  {:event "[:noop]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["dispatch-dry-run"          {:value {:ok? false :reason :no-new-epoch
                                           :event [:noop] :frame :rf/default}
                                   :elided-count 0}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :no-new-epoch}}}

   ;; ---------- restore-epoch (gated write) -------------------------------
   ;; The --allow-writes gate ships DEFAULT-OFF. The disabled fixture pins
   ;; the gate-closed envelope (no nREPL round-trip); the happy fixture
   ;; flips :fixture/allow-writes? and pins the success shape. epoch-id is
   ;; parsed as EDN so the integer id "7" reads as the number 7.
   {:fixture/id    :restore-epoch/disabled-default
    :fixture/doc   "restore-epoch with --allow-writes OFF returns :rf.error/writes-disabled without touching the runtime."
    :fixture/tool  "restore-epoch"
    :fixture/args  {:epoch-id "7"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :rf.error/writes-disabled}}

   {:fixture/id    :restore-epoch/happy
    :fixture/doc   "restore-epoch with --allow-writes ON wraps the runtime's true into {:ok? true :restored? true :epoch-id <int>}."
    :fixture/tool  "restore-epoch"
    :fixture/allow-writes? true
    :fixture/args  {:epoch-id "7"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["restore-epoch"             true]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["restore-epoch 7"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :restored? true :epoch-id 7}}}

   {:fixture/id    :restore-epoch/missing-id
    :fixture/doc   "restore-epoch with --allow-writes ON but no :epoch-id surfaces :missing-epoch-id."
    :fixture/tool  "restore-epoch"
    :fixture/allow-writes? true
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-epoch-id}}

   ;; A restore that the runtime REJECTS (a bare `false`: aged-out id,
   ;; drain-in-flight) means the write did not land. It MUST ride as
   ;; isError carrying :restore-rejected, NOT a success-shaped envelope
   ;; the host reads as a landed write.
   {:fixture/id    :restore-epoch/rejected-false
    :fixture/doc   "restore-epoch with --allow-writes ON whose runtime returns false (rejected restore) rides as isError :restore-rejected (rf2-or8s29)."
    :fixture/tool  "restore-epoch"
    :fixture/allow-writes? true
    :fixture/args  {:epoch-id "999"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["restore-epoch"             false]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :restored? false :reason :restore-rejected :epoch-id 999}}}

   ;; ---------- replace-app-db (gated write) ------------------------------
   {:fixture/id    :replace-app-db/disabled-default
    :fixture/doc   "replace-app-db with --allow-writes OFF returns :rf.error/writes-disabled without touching the runtime."
    :fixture/tool  "replace-app-db"
    :fixture/args  {:db "{:k :v}"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :rf.error/writes-disabled}}

   {:fixture/id    :replace-app-db/happy
    :fixture/doc   "replace-app-db with --allow-writes ON passes the runtime's app-db-reset! envelope through; db rides as EDN data. Signals configure-raw-state! before app-db-reset! (rf2-z7roa raw-state tap posture)."
    :fixture/tool  "replace-app-db"
    :fixture/allow-writes? true
    :fixture/allow-raw-state? false
    :fixture/args  {:db "{:counter 0}"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["app-db-reset!"             {:ok? true :frame :rf/default}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["app-db-reset! {:counter 0}"
     ;; The raw-state tap posture is signalled (the unit suite pins it
     ;; lands BEFORE app-db-reset!; the corpus pins it IS emitted on the
     ;; write path with the gate-OFF posture).
     "configure-raw-state!"
     ":allow-raw-state? false"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :frame :rf/default}}}

   {:fixture/id    :replace-app-db/missing-db
    :fixture/doc   "replace-app-db with --allow-writes ON but no :db surfaces :missing-db."
    :fixture/tool  "replace-app-db"
    :fixture/allow-writes? true
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-db}}

   ;; A replace the runtime REJECTS
   ;; ({:ok? false :reason :reset-rejected ...}: no-such-frame,
   ;; replace-during-drain, schema-mismatch) means the injection did NOT
   ;; land. It MUST ride as isError carrying the reason, NOT a
   ;; success-shaped envelope.
   {:fixture/id    :replace-app-db/reset-rejected
    :fixture/doc   "replace-app-db with --allow-writes ON whose runtime returns {:ok? false :reason :reset-rejected} rides as isError (rf2-or8s29)."
    :fixture/tool  "replace-app-db"
    :fixture/allow-writes? true
    :fixture/allow-raw-state? false
    :fixture/args  {:db "{:bad :shape}"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["app-db-reset!"             {:ok? false :reason :reset-rejected :frame :rf/default}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :reset-rejected :frame :rf/default}}}

   ;; ---------- trace-window -----------------------------------------------
   {:fixture/id    :trace-window/happy
    :fixture/doc   "trace-window returns the runtime's epoch payload through the wire pipeline."
    :fixture/tool  "trace-window"
    :fixture/args  {:ms 500}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
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
    [["__re_frame2_pair_runtime"  true]
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
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:status :idle :last-completed 0}]]
    :fixture/expect
    {:isError? false}}

   ;; ---------- snapshot ---------------------------------------------------
   {:fixture/id    :snapshot/happy
    :fixture/doc   "snapshot returns the per-frame map shape through the wire pipeline."
    :fixture/tool  "snapshot"
    :fixture/args  {:frames "all"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; The snapshot eval form wraps its result as
     ;; `{:value <snap> :elided-count N}` so the elision count rides
     ;; back on the same nREPL round-trip; the wire-pipeline reads
     ;; the count from opts instead of re-walking client-side.
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :snapshot/preload-missing
    :fixture/doc   "snapshot surfaces :runtime-loaded-but-preload-missing when the marker is absent (rf2-7tgfk diagnostic ladder)."
    :fixture/tool  "snapshot"
    :fixture/args  {:frames "all"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  false]
     [:default                    nil]]
    :fixture/expect
    {:edn-submap {:ok? false :reason :runtime-loaded-but-preload-missing}}}

   ;; ---------- get-path ---------------------------------------------------
   {:fixture/id    :get-path/happy
    :fixture/doc   "get-path returns {:ok? true :exists? true :path p :value v}."
    :fixture/tool  "get-path"
    :fixture/args  {:path "[:counter]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; The eval form pre-counts elision markers; the envelope carries
     ;; `:elided-count` so the wire-pipeline reads the count from opts
     ;; instead of re-walking the scalar.
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
    :fixture/doc   "get-path surfaces the runtime's :path-not-found failure as an isError envelope (rf2-wdxyx3 finding 2 — a known-tool :ok? false is never a silent success)."
    :fixture/tool  "get-path"
    :fixture/args  {:path "[:no-such :key]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok? false :reason :path-not-found
                                   :path [:no-such :key]
                                   :deepest-valid-prefix []}]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :path-not-found}}}

   ;; ---------- read-dom (raw DOM plane read) -----------------------------
   ;; The whole read runs browser-side in the preloaded runtime fn
   ;; (re-frame2-pair.runtime/dom-read — shared plumbing with read-ui);
   ;; the corpus stubs the form's canned envelope, matching on
   ;; the `dom-read` runtime call. Pins the outer wire shape: matched
   ;; :count + per-node {:tag :text :attrs}, the large-text elision marker,
   ;; the missing-arg gate, and the bad-selector error reason.
   {:fixture/id    :read-dom/happy
    :fixture/doc   "read-dom returns {:ok? true :count N :nodes [{:tag :text :attrs}]} from the browser-side dom-read runtime fn."
    :fixture/tool  "read-dom"
    :fixture/args  {:selector "#app .counter"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dom-read"                  {:ok? true :selector "#app .counter"
                                   :count 1 :truncated? false
                                   :nodes [{:tag "div" :text "Count: 3"
                                            :attrs {"class" "counter" "data-count" "3"}}]}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame2-pair.runtime/dom-read" "#app .counter" ":selector"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :count 1 :truncated? false}
     :edn-contains-keys #{:nodes :selector}}}

   {:fixture/id    :read-dom/large-text-elided
    :fixture/doc   "read-dom replaces over-:max-text node text with the {:rf.size/large-elided {:type :dom-text ...}} marker (rf2-urjnc convention)."
    :fixture/tool  "read-dom"
    :fixture/args  {:selector "pre" :max-text 100}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dom-read"                  {:ok? true :selector "pre" :count 1 :truncated? false
                                   :nodes [{:tag "pre"
                                            :text {:rf.size/large-elided
                                                   {:type :dom-text :chars 54000 :preview "lorem..."}}
                                            :attrs {}}]}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :count 1}}}

   {:fixture/id    :read-dom/missing-selector
    :fixture/doc   "read-dom without :selector surfaces :missing-selector before any nREPL round-trip."
    :fixture/tool  "read-dom"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-selector}}

   {:fixture/id    :read-dom/bad-selector
    :fixture/doc   "read-dom forwards the browser-side :rf.error/read-dom-bad-selector envelope (querySelectorAll threw SyntaxError inside dom-read) as an :isError envelope — a thrown malformed-selector is a caller FAULT, so per spec/003-Tool-Catalogue.md §381 (\"every :ok? false is isError:true\") map-result-or-blank branches its map arm on :ok? (rf2-q7cavs). Keeping it isError also keeps the transient failure OUT of the response cache (cache eligibility bypasses isError)."
    :fixture/tool  "read-dom"
    :fixture/args  {:selector "###"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dom-read"                  {:ok? false :reason :rf.error/read-dom-bad-selector
                                   :selector "###" :message "bad selector"}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :rf.error/read-dom-bad-selector}}}

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

   ;; ---------- list-subscriptions (reactive sub-cache) -------------------
   {:fixture/id    :list-subscriptions/empty
    :fixture/doc   "list-subscriptions on a frame with no live reactive subs returns an empty list envelope. GENUINE emptiness is the runtime's OWN `{:ok? true :subs []}` MAP (a real answer), not a blank eval — so it rides isError:false. (rf2-21vvfs: a blank/non-map eval is a DEGRADED read, not emptiness — see the /degraded-blank sibling.)"
    :fixture/tool  "list-subscriptions"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok? true :frame :rf/default :count 0 :subs []}]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :subs []}}}

   {:fixture/id    :list-subscriptions/degraded-blank
    :fixture/doc   "rf2-21vvfs: a BLANK/non-map eval (the runtime sentinel is present, but the browser tab closed/navigated in the race between the liveness re-check and the sub-cache drain) is a DEGRADED read — NOT an empty listing. It surfaces as :unexpected-shape (isError:true), never a fabricated `{:ok? true :subs []}` masking a dead read."
    :fixture/tool  "list-subscriptions"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :reason  :unexpected-shape}}

   ;; ---------- list-streams (streaming-tap diagnostic) -------------------
   {:fixture/id    :list-streams/empty
    :fixture/doc   "list-streams with no active streaming-tap subscriptions returns an empty list envelope. GENUINE emptiness is the runtime's OWN `{:ok? true :subs []}` MAP, not a blank eval — so it rides isError:false. (rf2-21vvfs: a blank/non-map eval is a DEGRADED read — see the /degraded-blank sibling.)"
    :fixture/tool  "list-streams"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok? true :subs []}]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :subs []}}}

   {:fixture/id    :list-streams/degraded-blank
    :fixture/doc   "rf2-21vvfs: a BLANK/non-map eval on the very tool that diagnoses a dead/quiet stream is a DEGRADED read, not 'zero streams'. It surfaces as :unexpected-shape (isError:true), never a fabricated `{:ok? true :subs []}` reporting a false-clean state."
    :fixture/tool  "list-streams"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :reason  :unexpected-shape}}

   ;; ---------- operating-frame trio --------------------------------------
   ;; The three Tool-Pair §Tool-surface-obligations ops. The runtime
   ;; surfaces them via `frames-list` (the {:frames :selected :operating}
   ;; triple) and `select-frame!`; the tools compose validate-then-pin /
   ;; clear-then-reread / read forms over those. The corpus pins the
   ;; outer wire shape + the escape contract: a SET in a multi-frame
   ;; session makes the next frame-consuming op resolve to the pinned
   ;; frame instead of refusing with :ambiguous-frame.
   {:fixture/id    :set-operating-frame/happy
    :fixture/doc   "set-operating-frame pins a registered frame and returns the resolved triple with :selected = the pinned frame (the tier-2 escape from :ambiguous-frame)."
    :fixture/tool  "set-operating-frame"
    :fixture/args  {:frame ":stories"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; The validate-then-pin form reads frames-list, finds :stories
     ;; registered, pins it, and re-reads the triple — now :selected
     ;; :stories, :operating :stories.
     ["select-frame!"             {:ok? true
                                   :frames [:rf/default :stories]
                                   :selected :stories
                                   :operating :stories}]
     [:default                    nil]]
    ;; The emitted form MUST validate against the registered list AND
    ;; pin via select-frame! — the escape mechanism. The keyword rides
    ;; as a well-formed :stories, never the malformed ::stories.
    :fixture/eval-form-must-contain
    ["frames-list" "select-frame!" ":stories"]
    :fixture/eval-form-must-not-contain
    ["::stories"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :selected :stories :operating :stories}
     :edn-contains-keys #{:frames :selected :operating}}}

   {:fixture/id    :set-operating-frame/no-such-frame
    :fixture/doc   "set-operating-frame on an unregistered frame refuses with :no-such-frame as an isError envelope (Tool-Pair §Tool-surface obligations + rf2-wdxyx3 finding 2 — the failed pin is not a silent success, so the invoke chokepoint won't flush the cache)."
    :fixture/tool  "set-operating-frame"
    :fixture/args  {:frame ":nope"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; :nope is not in frames-list → the form's else branch returns the
     ;; :no-such-frame envelope WITHOUT calling select-frame!.
     [:default                    {:ok? false :reason :no-such-frame
                                   :frame :nope
                                   :frames [:rf/default :stories]}]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :no-such-frame :frame :nope}}}

   {:fixture/id    :set-operating-frame/reserved-tool-frame
    :fixture/doc   "set-operating-frame refuses to pin a reserved :rf/* TOOL frame as the operating frame (rf2-wdxyx3 finding 1). Refused BEFORE any nREPL round-trip — a reserved frame is never the operating frame, so an omitted-:frame read can never resolve to it. :rf/default (an app frame) is allowed."
    :fixture/tool  "set-operating-frame"
    :fixture/args  {:frame ":rf/xray"}
    ;; No eval expected — the refusal short-circuits before the round-trip.
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :reserved-tool-frame :frame :rf/xray}}}

   {:fixture/id    :set-operating-frame/missing-frame
    :fixture/doc   "set-operating-frame without :frame surfaces :missing-frame before any nREPL round-trip."
    :fixture/tool  "set-operating-frame"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-frame}}

   ;; EP-0023 §Specification — the public address is the FRAME id (a single
   ;; process-local frame-id space). The pin form pins the frame via
   ;; select-frame!.
   {:fixture/id    :set-operating-frame/frame-pin
    :fixture/doc   "set-operating-frame pins a FRAME (the public address, EP-0023); the emitted form pins via select-frame!."
    :fixture/tool  "set-operating-frame"
    :fixture/args  {:frame ":shop/cart"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; The form validates :shop/cart against (:frames (frames-list)) and
     ;; pins it via select-frame!, then frames-list reports it as :selected
     ;; / :operating.
     ["select-frame!"             {:ok? true
                                   :frames [:rf/default :shop/cart]
                                   :app-frames [:shop/cart]
                                   :selected :shop/cart
                                   :operating :shop/cart}]
     [:default                    nil]]
    ;; The emitted form MUST pin the FRAME via select-frame!.
    :fixture/eval-form-must-contain
    ["select-frame!" ":frames" ":shop/cart"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :selected :shop/cart :operating :shop/cart}
     :edn-contains-keys #{:frames :selected :operating}}}

   {:fixture/id    :reset-operating-frame/clears-pin
    :fixture/doc   "reset-operating-frame clears the session frame pin (select-frame! nil), returning the post-reset map with :selected nil and :operating back at the tier-3/4 resolution."
    :fixture/tool  "reset-operating-frame"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; The clear-then-reread form: select-frame! nil, then frames-list reports
     ;; the pin cleared — :selected nil (multi-frame app → :operating nil, back
     ;; to tier-4 ambiguous). The substring match on "select-frame!" matches
     ;; the single clear-then-reread form.
     ["select-frame!"             {:ok? true
                                   :frames [:rf/default :stories]
                                   :selected nil
                                   :operating nil}]
     [:default                    nil]]
    ;; The emitted reset form MUST clear the frame pin and re-read.
    :fixture/eval-form-must-contain
    ["select-frame! nil" "frames-list"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :selected nil :operating nil}
     :edn-contains-keys #{:frames :selected :operating}}}

   {:fixture/id    :get-operating-frame/reports-triple
    :fixture/doc   "get-operating-frame returns the normative {:frames :selected :operating} triple; :selected reflects a prior set."
    :fixture/tool  "get-operating-frame"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["frames-list"               {:ok? true
                                   :frames [:rf/default :stories]
                                   :selected :stories
                                   :operating :stories}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :selected :stories :operating :stories}
     :edn-contains-keys #{:frames :selected :operating}}}

   {:fixture/id    :get-operating-frame/ambiguous
    :fixture/doc   "get-operating-frame reports :operating nil (AMBIGUOUS) when two-plus frames are registered and nothing is pinned — the tier-4 state the trio escapes."
    :fixture/tool  "get-operating-frame"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["frames-list"               {:ok? true
                                   :frames [:rf/default :stories]
                                   :selected nil
                                   :operating nil}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :selected nil :operating nil}}}

   ;; ---------- get-re-frame2-pair-instructions ------------------------------------
   ;; Inline-text tool. No nREPL round-trip; the result is a
   ;; pure-data def in the bundle, so the eval-script is irrelevant.
   {:fixture/id    :get-re-frame2-pair-instructions/happy
    :fixture/doc   "get-re-frame2-pair-instructions returns {:ok? true :tool ... :text <prose>}."
    :fixture/tool  "get-re-frame2-pair-instructions"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :tool "get-re-frame2-pair-instructions"}}}

   ;; ---------- raw-state boot-gate ---------------------------------------
   ;; The default-OFF gate forces `:include-sensitive false` AND
   ;; `:elision true` on every snapshot / get-path / subscribe call,
   ;; regardless of the per-call arg. The gate-ON path defers to the
   ;; caller's args. The wire-key carries no trailing `?`; the namespaced
   ;; walker-option keyword `:rf.size/include-sensitive?` retains it
   ;; (internal framework key, not on the wire).
   {:fixture/id    :raw-state/snapshot-gated-default-forces-redact
    :fixture/doc   "Gate OFF + caller passes :include-sensitive true ⇒ form must carry :rf.size/include-sensitive? false."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? false
    :fixture/args  {:frames "all" :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    [":rf.size/include-sensitive? false"
     ":rf.size/include-large? false"]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :raw-state/snapshot-opt-in-honours-arg
    :fixture/doc   "Gate ON + caller passes :include-sensitive true ⇒ form must carry :rf.size/include-sensitive? true."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? true
    :fixture/args  {:frames "all" :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    [":rf.size/include-sensitive? true"]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :raw-state/snapshot-gated-default-forces-elision
    :fixture/doc   "Gate OFF + caller passes :elision false ⇒ form must still walk via elide-wire-value."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? false
    :fixture/args  {:frames "all" :elision false}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    ["re-frame.core/elide-wire-value"]
    :fixture/expect
    {:isError? false}}

   ;; Fail-CLOSED. A BARE `:elision false` (no `:include-sensitive true`)
   ;; MUST still walk: large content passes (`include-large? true`) but a
   ;; declared-sensitive `:app-db` / `:sub-cache` slot redacts to
   ;; `:rf/redacted`.
   {:fixture/id    :raw-state/snapshot-bare-elision-false-still-walks
    :fixture/doc   "Gate ON + :elision false (no sensitive opt-in) ⇒ form must STILL call elide-wire-value with include-sensitive? false (sensitive redacts, large passes)."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? true
    :fixture/args  {:frames "all" :elision false}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    ["re-frame.core/elide-wire-value"
     ":rf.size/include-sensitive? false"
     ":rf.size/include-large? true"]
    :fixture/expect
    {:isError? false}}

   ;; The ONLY combination that skips the walker is the deliberate
   ;; full-raw local opt-in: `:elision false` AND `:include-sensitive true`
   ;; (the operator's `:rf.egress/local-raw` act).
   {:fixture/id    :raw-state/snapshot-full-raw-opt-in-skips-walker
    :fixture/doc   "Gate ON + :elision false + :include-sensitive true ⇒ full-raw opt-in, form must NOT call elide-wire-value over the slices."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? true
    :fixture/args  {:frames "all" :elision false :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-not-contain
    ["re-frame.core/elide-wire-value"]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :raw-state/get-path-gated-default-forces-redact
    :fixture/doc   "get-path: gate OFF + caller passes :include-sensitive true ⇒ form must carry :rf.size/include-sensitive? false."
    :fixture/tool  "get-path"
    :fixture/allow-raw-state? false
    :fixture/args  {:path "[:user :token]" :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok? true :exists? true :path [:user :token]
                                   :value :rf/redacted :elided-count 1}]]
    :fixture/eval-form-must-contain
    [":rf.size/include-sensitive? false"
     ":rf.size/include-large? false"]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :raw-state/get-path-opt-in-honours-arg
    :fixture/doc   "get-path: gate ON + caller passes :include-sensitive true ⇒ form must carry :rf.size/include-sensitive? true."
    :fixture/tool  "get-path"
    :fixture/allow-raw-state? true
    :fixture/args  {:path "[:user :token]" :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok? true :exists? true :path [:user :token]
                                   :value "raw" :elided-count 0}]]
    :fixture/eval-form-must-contain
    [":rf.size/include-sensitive? true"]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :raw-state/signal-runtime-fires-once-on-first-call
    :fixture/doc   "Boot-gate state is signalled to the runtime via configure-raw-state! on the first state-emitting tool call per build."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? false
    :fixture/args  {:frames "all"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    ["configure-raw-state!"
     ":allow-raw-state? false"]
    :fixture/expect
    {:isError? false}}

   ;; ---------- handler-meta ----------------------------------------------
   ;; These fixtures put handler-meta / list-handlers inside the "every
   ;; tool" cross-tool ratchet the corpus promises, pinning their
   ;; outer-wire shape (the unit suite covers their error paths).
   {:fixture/id    :handler-meta/happy
    :fixture/doc   "handler-meta on a registered (kind,id) merges :ok? true + the requested kind/id onto the runtime meta map."
    :fixture/tool  "handler-meta"
    :fixture/args  {:kind "event" :id ":user/login"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["registrar-describe"        {:ns "user.app" :line 12 :doc "login event"}]
     [:default                    nil]]
    :fixture/expect
    {:isError?          false
     :edn-submap        {:ok? true :kind :event :id :user/login}
     :edn-contains-keys #{:ns :line :doc}}}

   {:fixture/id    :handler-meta/missing-kind
    :fixture/doc   "handler-meta with no :kind short-circuits on :invalid-kind before any nREPL round-trip."
    :fixture/tool  "handler-meta"
    :fixture/args  {:id ":user/login"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :invalid-kind}}

   ;; ---------- list-handlers ---------------------------------------------
   {:fixture/id    :list-handlers/happy
    :fixture/doc   "list-handlers returns the sorted id vector + :count for a kind, wrapped :ok? true."
    :fixture/tool  "list-handlers"
    :fixture/args  {:kind "event"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["registrar-list"            [:user/login :user/logout]]
     [:default                    nil]]
    :fixture/expect
    {:isError?   false
     :edn-submap {:ok? true :kind :event
                  :ids [:user/login :user/logout] :count 2}}}

   {:fixture/id    :list-handlers/missing-kind
    :fixture/doc   "list-handlers with no :kind short-circuits on :invalid-kind."
    :fixture/tool  "list-handlers"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :invalid-kind}}

   ;; ---------- get-stream-controls ---------------------------------------
   ;; Server-side resource-control diagnostic. No nREPL round-trip — the
   ;; tool reads in-process atoms — so the eval-script is a bare default
   ;; (the tool never asks the stub anything). Pins the outer envelope
   ;; shape: :ok? true + the four control sections.
   {:fixture/id    :get-stream-controls/happy
    :fixture/doc   "get-stream-controls reports server-side caps + active/limit + rate-limit + abuse-window, no nREPL round-trip."
    :fixture/tool  "get-stream-controls"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError?          false
     :edn-submap        {:ok? true
                         :concurrent-streams {:active 0 :limit 10 :at-capacity? false}
                         :abuse-window {:count 0 :threshold 50 :tripped? false}}
     :edn-contains-keys #{:config :rate-limit :cross-check}}}

   ;; ---------- pipeline: unknown tool ------------------------------------
   {:fixture/id    :pipeline/unknown-tool
    :fixture/doc   "invoke against a name not in the registry returns :unknown-tool error carrying a recovery :hint + the :available-tools catalogue (rf2-tkmik)."
    :fixture/tool  "no-such-tool"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :unknown-tool
     :edn-contains-keys #{:hint :available-tools}}}])

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

  Honors `:fixture/eval-allowed?` — flips the eval-cljs launch-flag gate
  for this fixture's invocation and restores it afterward. Default ON
  mirrors the published-build posture (eval-cljs is the REPL primitive);
  opt-out fixtures (`:disabled-via-no-eval`) explicitly set `false` to
  exercise the gate-closed envelope. When the key is absent, the fixture
  inherits the default-ON state.

  Honors `:fixture/allow-raw-state?` — flips the raw-state boot gate.
  Default OFF mirrors the published-build posture; opt-in fixtures verify
  that an operator who passed `--allow-sensitive-reads` gets the
  per-call-arg-wins behaviour."
  [{:fixture/keys [id tool args eval-script expect eval-allowed?
                    allow-raw-state? allow-writes?
                    eval-form-must-contain eval-form-must-not-contain]}]
  (cache/clear!)
  (let [;; eval-allowed? defaults true (mirrors the published-build gate
        ;; state); a fixture sets `false` to exercise the --no-eval
        ;; opt-out path.
        eval-allowed?-effective (if (nil? eval-allowed?) true (boolean eval-allowed?))
        js-args         (args->js args)
        forms-seen      (atom [])
        prev-eval-gate  (eval-cljs/eval-allowed-enabled?)
        prev-raw-gate   (raw-state/allow-raw-state-enabled?)
        prev-write-gate (writes/allow-writes-enabled?)]
    (eval-cljs/set-eval-allowed! eval-allowed?-effective)
    (raw-state/set-allow-raw-state! (boolean allow-raw-state?))
    (writes/set-allow-writes! (boolean allow-writes?))
    ;; Reset the per-build runtime-signal cache so each fixture exercises
    ;; the signal path freshly. `signal-runtime!` rides one extra nREPL
    ;; round-trip on first call per build per server-lifetime — the
    ;; corpus's stub eval-script accepts the `configure-raw-state!` form
    ;; via the `:default` catch-all.
    (raw-state/reset-runtime-signal-cache!)
    (with-stubbed-eval! eval-script forms-seen
      (fn []
        (-> (tools/invoke nil tool js-args nil)
            (.then (fn [result]
                     (let [[ok? msg]    (check-fixture-result result expect)
                           ;; Pin the gate-induced shape of the eval form
                           ;; sent over nREPL. A `must-contain` substring
                           ;; missing from EVERY form observed = fail.
                           form-strs    @forms-seen
                           any-has?     (fn [needle]
                                          (some #(str/includes? % needle) form-strs))
                           missing      (when ok?
                                          (remove any-has?
                                                  (or eval-form-must-contain [])))
                           contains-bad (when ok?
                                          (filter any-has?
                                                  (or eval-form-must-not-contain [])))
                           [ok2? msg2]  (cond
                                          (seq missing)
                                          [false
                                           (str "eval-form-must-contain — missing substring(s) from any observed form: "
                                                (pr-str missing))]
                                          (seq contains-bad)
                                          [false
                                           (str "eval-form-must-not-contain — substring(s) appeared in some observed form: "
                                                (pr-str contains-bad))]
                                          :else [ok? msg])]
                       {:fixture-id id
                        :passed?    ok2?
                        :failure    msg2
                        :result-edn (try (extract-edn result)
                                         (catch :default _ :unparseable))})))
            (.catch (fn [err]
                      {:fixture-id id
                       :passed?    false
                       :failure    (str "invoke threw: " (.-message err))
                       :exception  err}))
            (.finally (fn []
                        (eval-cljs/set-eval-allowed! prev-eval-gate)
                        (raw-state/set-allow-raw-state! prev-raw-gate)
                        (writes/set-allow-writes! prev-write-gate))))))))

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

(deftest run-re-frame2-pair-mcp-conformance-corpus
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
                ;; Silent-on-success: summary prints only on failure.
                (when (seq failed)
                  (println)
                  (println "re-frame2-pair-mcp conformance corpus:")
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
                    (str "All re-frame2-pair-mcp conformance corpus fixtures must pass; "
                         (count failed) " failed."))
                (cache/clear!)
                (done))))))))
