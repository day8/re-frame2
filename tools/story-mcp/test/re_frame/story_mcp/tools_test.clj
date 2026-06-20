(ns re-frame.story-mcp.tools-test
  "Per-tool semantics + the server dispatcher's `initialize` / `tools/list`
  / `tools/call` plumbing.

  Tests boot Story's canonical vocabulary in a per-test fixture so the
  registrar carries the seven canonical tags + the lifecycle machine,
  then register a small fixture story + variant so each tool has
  something to read."
  (:require [cheshire.core :as cheshire]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame-classification :as frame-class]
            [re-frame.mcp-base.cap :as base-cap]
            [re-frame.mcp-base.overflow :as overflow]
            [re-frame.mcp-base.vocab :as vocab]
            [re-frame.schemas :as schemas]
            [re-frame.story :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.recorder :as recorder]
            [re-frame.story.registrar :as story-registrar]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.protocol :as proto]
            [re-frame.story-mcp.server :as server]
            [re-frame.story-mcp.tools.args :as targs]
            [re-frame.story-mcp.tools.cljs-resolve :as cljs-resolve]
            [re-frame.story-mcp.tools.wire-pipeline :as wire-pipeline]
            [re-frame.story-mcp.tools.dev :as dev]
            [re-frame.story-mcp.tools.egress :as egress]
            [re-frame.story-mcp.tools.recorder :as recorder-tool]
            [re-frame.story-mcp.tools.registry :as registry]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ResultIO mirror over story-mcp's CLJ-map result shape — used by the
;; cap-honours-default test to sum tokens without reaching into cap's
;; private result-io reify. Mirrors the runtime IO instance in
;; `re-frame.story-mcp.tools.wire-pipeline/result-io` (rf2-eyelu / rf2-mzndx) so
;; a drift on the consumer's content-shape would be caught by the
;; assertion sitting on this mirror — INCLUDING the `:structuredContent`
;; sizing path added in rf2-mzndx.
(def ^:private test-io
  (reify base-cap/ResultIO
    (content-texts [_ result]
      (cond-> (mapv :text (:content result))
        (some? (:structuredContent result))
        (conj (pr-str (:structuredContent result)))))
    (build-overflow-result [_ marker _]
      {:content [{:type "text" :text (pr-str marker)}]
       :structuredContent marker})))

;; ---- fixtures ------------------------------------------------------------

;; Per-variant frame-owned classification accumulator (EP-0015 §8). Each
;; `declare-sensitive!` / `declare-large!` call adds ONE `:rf/path` to the
;; variant's classification config; `frame-class/install!` REPLACES the
;; frame's `:source :frame` declarations on each install, so the helpers
;; install the FULL accumulated config every time (a variant that declares
;; both a sensitive and a large path keeps both). Cleared per test by the
;; fixture so a prior test's paths don't bleed in. Defined here (above the
;; fixture) so `reset-story-and-config` can clear it.
(def ^:private declared-class (atom {}))

(defn reset-story-and-config
  "Each test gets a fresh Story registry + write-gate set to false (the
  documented default per IMPL-SPEC §7.3). Tests that need writes flip
  the gate explicitly.

  Also pins re-frame's substrate to `plain-atom` so tests that exercise
  the full run-variant → assertion-record-into-frame-db → read-failures
  pipeline land assertions where `read-failures` can find them (the
  pipeline requires an initialised substrate adapter; without it
  `dispatch-sync` no-ops and `:rf.story/assertions` never accretes)."
  [t]
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (story/clear-all!)
  (story/install-canonical-vocabulary!)
  (config/set-allow-writes! false)
  ;; rf2-g9fje — sensitive-read gate. Default off everywhere (mirrors
  ;; the `--allow-sensitive-reads` boot-time posture). Tests that
  ;; exercise the opt-in branch flip it explicitly.
  (config/set-allow-sensitive-reads! false)
  (schemas/clear-schemas-by-frame!)
  ;; Frame-owned classification accumulator is per-process — clear between
  ;; tests so a previous test's declared sensitive/large paths don't bleed
  ;; in (EP-0015 §8, rf2-d2r3um).
  (reset! declared-class {})
  ;; Recorder atom is per-process — clear between tests so a previous
  ;; test's captured events don't bleed in.
  (recorder/clear!)
  ;; Disable epoch-ring recording for the duration of each story-mcp test
  ;; (restored below). story-mcp's OWN artefact carries NO epoch dep, so a
  ;; standalone `cd tools/story-mcp && clojure -M:test` never loads
  ;; `re-frame.epoch` and `run-variant`'s `:narrative` projection reads an
  ;; empty tape. Under the tools-root aggregate (`cd tools && clojure
  ;; -M:test`) a STORY test namespace REQUIRES `re-frame.epoch`, installing
  ;; the capture hooks process-wide — so story-mcp's `run-variant` then
  ;; projects a full per-event narrative (each beat carries full :db /
  ;; trace-events), ballooning the wire payload past the MCP token cap (the
  ;; whole run-result is replaced by a `:rf.mcp/overflow` marker, failing the
  ;; shape + elision-indicator assertions). `(rf/configure! {:epoch-history
  ;; {:depth 0}})` reproduces the artefact's own epoch-free posture: it is a
  ;; core-facade knob that no-ops when epoch is absent and disables ring
  ;; recording when present.
  (rf/configure! {:epoch-history {:depth 0}})
  ;; Fixture story + variant.
  (story/reg-story :story.button
    {:doc       "A clickable button."
     :component :app.ui/button
     :tags      #{:dev :docs}
     :args      {:label "Click me"}})
  (story/reg-variant :story.button/primary
    {:doc  "Primary button."
     :args {:label "Save"}
     :tags #{:dev}})
  (story/reg-variant :story.button/secondary
    {:doc  "Secondary button."
     :args {:label "Cancel"}
     :tags #{:docs}})
  (story/reg-mode :Mode.theme/dark
    {:doc  "Dark theme."
     :args {:theme :dark}})
  ;; Decorator fixtures — one of each kind (rf2-mqp1u). The `:wrap`
  ;; closure on the hiccup decorator is the load-bearing case for
  ;; `list-decorators`: the projected EDN must NOT carry the fn, only
  ;; a `:has-wrap?` boolean.
  (story/reg-decorator :dec.test/wrap-card
    {:kind :hiccup
     :doc  "Wrap the variant in a card."
     :wrap (fn [body _args] [:div.card body])})
  (story/reg-decorator :dec.test/seed-cart
    {:kind          :frame-setup
     :doc           "Seed an empty cart at frame creation."
     :app-db-patch  {:cart {:items []}}})
  (story/reg-decorator :dec.test/stub-http
    {:kind     :fx-override
     :doc      "Pin http effect to a known response."
     :fx-id    :http
     :response {:status 200 :body "ok"}})
  ;; rf2-d2r3um (EP-0015 §8) reconciliation — a test helper event the
  ;; privacy tests wire into a variant's `:setup` so the frame-owned
  ;; durable classification (`:sensitive` / `:large {:app-db …}`) is
  ;; RE-INSTALLED on every fresh run. rf2-294yq5.3 made `run-variant`
  ;; reset the variant frame's state IN PLACE on each run, which
  ;; overwrites the runtime-db partition with `{}` — wiping the frame's
  ;; elision registry (the `[:rf.runtime/elision …]` slot frame
  ;; classification installs). For the wire-egress redaction to bite at
  ;; egress (the END of the run), the declarations must be present on the
  ;; reset frame — so we re-install them from a `:setup` event (phase 2,
  ;; after allocation/reset). `frame-class/install!` writes directly to
  ;; the named frame's elision registry, so the re-install lands on the
  ;; live variant frame regardless of the dispatch scope. Idempotent —
  ;; re-installing the same classification REPLACES the prior `:source
  ;; :frame` entries.
  (rf/reg-event
    ::reapply-frame-class
    (fn [{:keys [db]} [_ frame-id classification-config]]
      (frame-class/install! frame-id
        (frame-class/validate+extract frame-id classification-config))
      {:db db}))
  (try
    (t)
    (finally
      ;; Restore the shipped epoch-ring default so a story namespace running
      ;; after this one in the aggregate sees the normal depth-50 posture.
      (rf/configure! {:epoch-history {:depth 50}}))))

(use-fixtures :each reset-story-and-config)

;; ---- helpers -------------------------------------------------------------

(defn- invoke
  "Invoke a tool by name. Returns the result map (success or error).

  ## Why `:dedup false` is the test-helper default

  The three dedup-eligible tools (`preview-variant`, `run-variant`,
  `record-as-variant`, per rf2-90eft) wrap their `:structuredContent`
  under `{:rf.mcp/dedup-table <cache>}` at the wire boundary when
  `:dedup` defaults to `true`. The tests in this corpus assert against
  the raw structured shape (`(:variant-id (:structuredContent r))`,
  etc.) — adding a dedup-expand step on every assertion would burn
  signal-to-noise.

  The wire-boundary transform is covered exhaustively by
  `re-frame.story-mcp.tools.dedup-test` (round-trip property,
  reduction-ratio sanity, `apply-dedup` envelope shape, descriptor
  eligibility gate). With that coverage the per-tool tests are free to
  exercise their domain semantics against the unwrapped payload.

  Callers that want to exercise the live-on-the-wire shape (default
  posture) should call `wire-pipeline/invoke-tool` directly and use
  `dedup/dedup-expand` to unwrap before asserting."
  [tool-name args]
  (wire-pipeline/invoke-tool tool-name (merge {:dedup false} args)))

(defn- success? [result]
  (and (map? result)
       (vector? (:content result))
       (not (true? (:isError result)))))

(defn- error? [result]
  (and (map? result)
       (true? (:isError result))))

;; ---------------------------------------------------------------------------
;; Registry shape
;; ---------------------------------------------------------------------------

(deftest registry-shape
  (testing "tool-registry is a vector of complete entries"
    (doseq [t registry/tool-registry]
      (is (string? (:name t)) (str "tool name: " (:name t)))
      (is (string? (:description t)))
      (is (map? (:inputSchema t)))
      (is (#{:dev :docs :testing :write} (:category t)))
      (is (fn? (:handler t)))))
  (testing "tool-descriptors strips category + handler (MCP wire shape)"
    (let [ds (registry/tool-descriptors)]
      (is (every? #(every? % [:name :description :inputSchema]) ds))
      (is (every? #(not (contains? % :handler)) ds))
      (is (every? #(not (contains? % :category)) ds)))))

(deftest typical-tokens-hint-on-every-tool
  ;; rf2-6sddv — `:typicalTokens` is an informational ballpark of
  ;; response-payload size in tokens; AI clients use it to budget calls.
  ;; Not a cap. Required to be a positive integer on every tool.
  (testing "registry: every tool carries a positive-integer :typicalTokens"
    (doseq [t registry/tool-registry]
      (is (integer? (:typicalTokens t))
          (str "missing :typicalTokens on " (:name t)))
      (is (pos? (:typicalTokens t))
          (str "non-positive :typicalTokens on " (:name t)))))
  (testing "tool-descriptors surfaces :typicalTokens to the wire"
    (let [ds (registry/tool-descriptors)]
      (is (every? #(integer? (:typicalTokens %)) ds))
      (is (every? #(pos? (:typicalTokens %)) ds)))))

(deftest output-schema-on-every-tool
  ;; rf2-3l3be — every tool descriptor MUST declare an `:outputSchema`
  ;; describing its `structuredContent` payload shape. Asserted at
  ;; load time in `registry.cljc` too; this test makes the contract
  ;; visible in the test corpus and pins the wire projection.
  (testing "registry: every tool carries a map :outputSchema"
    (doseq [t registry/tool-registry]
      (is (map? (:outputSchema t))
          (str "missing :outputSchema on " (:name t)))))
  (testing "tool-descriptors surfaces :outputSchema to the wire"
    (let [ds (registry/tool-descriptors)]
      (is (every? :outputSchema ds))
      (is (every? #(map? (:outputSchema %)) ds)))))

(deftest annotations-on-every-tool
  ;; rf2-94p8q — every tool descriptor MUST declare an `:annotations`
  ;; map carrying the MCP tool-annotation hints (`readOnlyHint`,
  ;; `destructiveHint`, `idempotentHint`, `openWorldHint`). Asserted
  ;; at load time in `registry.cljc` too; this test pins the wire
  ;; projection and the load-bearing classification (at least one of
  ;; `readOnlyHint` / `destructiveHint` must be set).
  (testing "registry: every tool carries a map :annotations"
    (doseq [t registry/tool-registry]
      (is (map? (:annotations t))
          (str "missing :annotations on " (:name t)))
      (is (or (true? (get-in t [:annotations :readOnlyHint]))
              (true? (get-in t [:annotations :destructiveHint])))
          (str "annotations on " (:name t)
               " carries no classification — at least one of "
               "readOnlyHint / destructiveHint must be set"))))
  (testing "tool-descriptors surfaces :annotations to the wire"
    (let [ds (registry/tool-descriptors)]
      (is (every? :annotations ds))
      (is (every? #(map? (:annotations %)) ds))))
  (testing "matrix: read-only tools have readOnlyHint"
    (let [by-name (into {} (map (juxt :name identity)) registry/tool-registry)
          ro-tools ["get-story-instructions" "list-substrates"
                    "list-stories" "get-story" "get-variant" "list-tags"
                    "list-modes" "list-decorators" "list-assertions"
                    "get-docs-markdown" "variant->edn" "explain-variant"
                    "snapshot-identity" "read-a11y-violations" "read-failures"]]
      (doseq [n ro-tools]
        (is (true? (get-in (by-name n) [:annotations :readOnlyHint]))
            (str n " should have readOnlyHint true (rf2-94p8q matrix)")))))
  ;; rf2-8h778: preview-variant moves to the destructive list. It dispatches
  ;; events into the variant's frame via the same `story/run-variant`
  ;; lifecycle as `run-variant`; the original `read-only-annotations`
  ;; marking was a wire-mismatch with the actual side-effect surface and
  ;; would have let agent-host auto-approval skip the destructive-write
  ;; ceremony.
  (testing "matrix: destructive tools have destructiveHint"
    (let [by-name (into {} (map (juxt :name identity)) registry/tool-registry)
          dest-tools ["preview-variant" "run-variant" "register-variant"
                      "unregister-variant" "record-as-variant"]]
      (doseq [n dest-tools]
        (is (true? (get-in (by-name n) [:annotations :destructiveHint]))
            (str n " should have destructiveHint true (rf2-94p8q matrix)")))))
  ;; rf2-e6knrq finding 2 — the open-world axis is now LOAD-BEARING and
  ;; must not drift silently. `run-variant` / `preview-variant` run the
  ;; author's lifecycle events/fx, which can reach external systems unless
  ;; the author stubbed them (fx-stubbing is an opt-in authoring surface,
  ;; not a universal default), so they MUST be open-world. EVERY other
  ;; tool is closed-world: reads, registry writes, static docs, and
  ;; `record-as-variant` (which records an externally-driven canvas + an
  ;; on-box registry write, never running the lifecycle itself).
  (testing "matrix: only the lifecycle-run tools are open-world (rf2-e6knrq)"
    (let [by-name      (into {} (map (juxt :name identity)) registry/tool-registry)
          open-world   #{"run-variant" "preview-variant"}]
      (doseq [t registry/tool-registry
              :let [n (:name t)
                    ow (get-in t [:annotations :openWorldHint])]]
        (if (contains? open-world n)
          (is (true? ow)
              (str n " MUST be open-world (openWorldHint true): it runs the "
                   "author's lifecycle events/fx which can reach external "
                   "systems unless explicitly stubbed (rf2-e6knrq finding 2)"))
          (is (false? ow)
              (str n " MUST stay closed-world (openWorldHint false): it does "
                   "not run the author's lifecycle (rf2-e6knrq finding 2)")))))))

(def ^:private tool-names-fixture
  "Canonical tool-name list (rf2-36upq TE7). Single source of truth
  shared with `test/stdio-roundtrip.js` — a registry change updates one
  file, not two. The fixture sits at `test/fixtures/tool-names.json`;
  this def parses it once at ns-load."
  (-> (io/resource "fixtures/tool-names.json")
      slurp
      (cheshire/parse-string true)
      :names
      sort
      vec))

(deftest registry-covers-impl-spec-7-2
  (testing "every tool from IMPL-SPEC §7.2 + §7.3 is present"
    (let [names (set (map :name registry/tool-registry))]
      ;; Per-category coverage (documentation value beyond the fixture
      ;; check: each line names a tool category + an expected slot).
      ;; Dev
      (is (contains? names "get-story-instructions"))
      (is (contains? names "preview-variant"))
      (is (contains? names "list-substrates"))
      ;; Docs
      (is (contains? names "list-stories"))
      (is (contains? names "get-story"))
      (is (contains? names "get-variant"))
      (is (contains? names "list-tags"))
      (is (contains? names "list-modes"))
      (is (contains? names "list-decorators"))
      (is (contains? names "list-assertions"))
      (is (contains? names "get-docs-markdown"))
      (is (contains? names "variant->edn"))
      (is (contains? names "explain-variant"))
      ;; Testing
      (is (contains? names "run-variant"))
      (is (contains? names "snapshot-identity"))
      (is (contains? names "read-a11y-violations"))
      (is (contains? names "read-failures"))
      ;; Write
      (is (contains? names "register-variant"))
      (is (contains? names "unregister-variant"))
      (is (contains? names "record-as-variant"))))
  (testing "registry name set matches the shared fixture exactly (rf2-36upq TE7)"
    ;; The Node `stdio-roundtrip.js` round-trip asserts `tools/list`
    ;; against the same JSON file. A drift between code + tests on either
    ;; side surfaces here AND there in the same edit.
    (let [reg-names (sort (mapv :name registry/tool-registry))]
      (is (= tool-names-fixture reg-names)
          (str "registry vs fixtures/tool-names.json drift — update both: "
               "fixture-only=" (set/difference (set tool-names-fixture) (set reg-names))
               " registry-only=" (set/difference (set reg-names) (set tool-names-fixture)))))))

;; ---------------------------------------------------------------------------
;; Code ↔ skill drift guard (rf2-dxh2s)
;;
;; The `tool-names.json` net above guards code↔test↔conformance (JVM
;; corpus, `stdio-roundtrip.js`, `end-to-end-story.cjs`). The CONSUMING
;; skill leaf — `skills/re-frame2/references/tooling/story-mcp-loop.md` —
;; names tools in PROSE: a count claim ("nineteen tools") and a per-step
;; catalogue table. Nothing asserted those prose-named tools still exist
;; in the registry, so a tool rename/removal left the leaf silently
;; stale. (`scripts/check_skill_mcp_drift.py` covers a DIFFERENT axis:
;; the SKILL.md YAML `allowed-tools:` front-matter, not this reference
;; leaf's prose catalogue.) This deftest closes that arm.
;; ---------------------------------------------------------------------------

(defn- artefact-root
  "Resolve the `tools/story-mcp/` artefact root on disk, cwd-independently.
  The per-tool `:test` alias runs from `tools/story-mcp` (a cwd-relative
  `(io/file …)` works), but the tools-root aggregate (`tools/deps.edn :test`,
  rf2-f2tkbt) runs from `tools/`, where a cwd-relative `spec/API.md` /
  `../../skills/…` would miss. The repo-tree files `spec/API.md` and the
  consuming skill leaf are NOT on the classpath (story-mcp ships only `src`
  + `test`), so we anchor off a known classpath SOURCE resource
  (`re_frame/story_mcp/protocol.cljc` under the `src` `:paths` root) and walk
  its parent chain up to the artefact root. Falls back to the JVM cwd if the
  resource is absent (e.g. a jar). Mirrors the xray guard tests' src-root
  resolution, generalised one level up to the artefact root."
  []
  (let [marker (io/resource "re_frame/story_mcp/protocol.cljc")]
    (if (and marker (= "file" (.getProtocol marker)))
      ;; .../tools/story-mcp/src/re_frame/story_mcp/protocol.cljc
      ;;   → protocol.cljc → story_mcp → re_frame → src → story-mcp (root)
      (-> (io/file (.toURI marker))
          .getParentFile .getParentFile .getParentFile .getParentFile)
      (io/file "."))))

(def ^:private story-mcp-loop-leaf
  "The consuming skill leaf, read relative to the `tools/story-mcp/` artefact
  root (resolved cwd-independently via `artefact-root`). Read once at
  ns-load — if the path drifts, `slurp` throws and the drift test errors
  loudly rather than silently passing on an empty string."
  (delay (slurp (io/file (artefact-root) ".." ".." "skills" "re-frame2"
                         "references" "tooling" "story-mcp-loop.md"))))

(def ^:private number-words
  "Spelled-out integers the leaf's tool-count claim may use. Keyed wide
  enough that a registry that grows/shrinks by a couple of tools still
  resolves the new count word — the assertion then bites on the mismatch
  rather than erroring on an unknown word."
  {"sixteen" 16 "seventeen" 17 "eighteen" 18 "nineteen" 19
   "twenty" 20 "twenty-one" 21 "twenty-two" 22 "twenty-three" 23})

(defn- skill-named-tools
  "Tool names the leaf's per-step catalogue table references. The table
  rows have the shape `| <step> | `<tool-name>` | <category> | <desc> |`
  — pull the backtick-wrapped token from the second cell. Plus the two
  tools named only in surrounding prose (`get-story-instructions`,
  `snapshot-identity`). Returns a set of strings.

  Deliberately table-anchored rather than scanning every backtick span:
  the leaf also backticks non-tool tokens (`reg-variant`, the
  deliberately-omitted `register-story`, `:rf.assert/*`, CLI flags) which
  must NOT be asserted into the registry."
  [leaf]
  (let [table-names (->> (re-seq #"(?m)^\|[^|]*\|\s*`([a-z][a-z0-9-]+(?:->[a-z]+)?)`\s*\|"
                                 leaf)
                         (map second)
                         set)]
    (into table-names ["get-story-instructions" "snapshot-identity"])))

(deftest skill-leaf-tool-names-match-registry
  ;; rf2-dxh2s — code↔skill drift ratchet for the reference leaf prose.
  (let [leaf       @story-mcp-loop-leaf
        reg-names  (set (map :name registry/tool-registry))
        named      (skill-named-tools leaf)]
    (testing "the catalogue actually parsed some tool names (regex didn't silently miss)"
      (is (seq named)
          "skill-named-tools returned empty — the leaf's table shape changed; fix the parser")
      ;; The authoring tools the leaf's per-step catalogue table enumerates
      ;; (one tool per row's second cell) plus the two prose-only tools
      ;; `skill-named-tools` folds in. Pinned explicitly so a table row
      ;; silently dropping a tool is caught even if the registry still
      ;; carries it. NOT pinned: `run-variant` / `read-failures` (and the
      ;; other Testing-category run tools) — rf2-r2xswa reframed this leaf
      ;; into an author/refine recipe and moved the run/self-heal loop to a
      ;; `re-frame2-pair` handoff, so those live in pair's allow-list, not
      ;; this skill's catalogue. The Testing-tools split is asserted below.
      (doseq [t ["register-variant" "unregister-variant" "preview-variant"
                 "get-variant" "explain-variant" "get-story-instructions"
                 "snapshot-identity"]]
        (is (contains? named t)
            (str "skill leaf catalogue no longer names authoring tool '" t
                 "' — table row removed or renamed in the prose"))))
    (testing "every tool the skill leaf names exists in the registry (rename/removal ratchet)"
      (doseq [t (sort named)]
        (is (contains? reg-names t)
            (str "skill leaf names tool '" t "' but the registry has no such tool — "
                 "a rename/removal left "
                 "skills/re-frame2/references/tooling/story-mcp-loop.md stale. "
                 "Update the leaf (and re-verify the count claim)."))))
    (testing "the author/run split is intact: run-side tools are named in handoff prose but kept OUT of the authoring catalogue (rf2-r2xswa)"
      ;; The reframed leaf hands the run/self-heal loop to `re-frame2-pair`.
      ;; `run-variant`/`read-failures` must still appear in the leaf's prose
      ;; (the handoff names them) but must NOT be pulled into this skill's
      ;; authoring catalogue — that would re-imply this skill can drive the
      ;; run loop. Ratchets both directions of the split.
      (doseq [t ["run-variant" "read-failures"]]
        (is (re-find (re-pattern (str "`" t "`")) leaf)
            (str "leaf no longer names run-side tool '" t "' in its prose — "
                 "the re-frame2-pair handoff section was dropped or renamed"))
        (is (not (contains? named t))
            (str "run-side tool '" t "' leaked into this skill's authoring "
                 "catalogue table — it belongs to the re-frame2-pair handoff, "
                 "not this skill's allow-list (rf2-r2xswa split)")))))
  ;; Count-claim ratchet: the leaf's "<count> tools" prose must equal the
  ;; live registry size. Catches an add/remove that updates the table but
  ;; leaves the headline count word stale (or vice-versa).
  (testing "the leaf's spelled-out tool-count claim matches the registry size"
    (let [leaf  @story-mcp-loop-leaf
          n     (count registry/tool-registry)
          ;; Match `<number-word> tools across` — the leaf reads "nineteen
          ;; tools across four categories". The `across` anchor pins this
          ;; to the headline count sentence rather than incidental "the
          ;; story-mcp tools" / "the tools" prose elsewhere in the leaf.
          m     (re-find #"(?i)\b([a-z]+(?:-[a-z]+)?)\s+tools\s+across\b" leaf)
          word  (some-> m second clojure.string/lower-case)
          claimed (get number-words word)]
      (is (some? m) "leaf no longer carries an '<n> tools across' count claim — prose shape changed")
      (is (some? claimed)
          (str "leaf count word '" word "' is not in number-words; "
               "the registry is " n " tools — extend number-words or fix the leaf"))
      (is (= n claimed)
          (str "leaf claims " word " (" claimed ") tools but the registry has " n
               " — update story-mcp-loop.md's count claim")))))

;; ---------------------------------------------------------------------------
;; Dev tools
;; ---------------------------------------------------------------------------

(deftest get-story-instructions-returns-text
  (let [r (invoke "get-story-instructions" {})]
    (is (success? r))
    (let [text (-> r :content first :text)]
      (is (string? text))
      (is (re-find #"reg-story" text))
      (is (re-find #":rf.assert" text))
      (is (re-find #"snapshot-identity" text)))))

(deftest get-story-instructions-covers-the-full-registration-surface
  ;; rf2-4537df — the onboarding text + descriptor are the agent-facing
  ;; contract for the Story registration surface. They previously listed
  ;; only the SEVEN reg-* macros and omitted the public `reg-fragment` /
  ;; `reg-check` composition macros, so an agent following the onboarding
  ;; would never discover the `:compose` reuse surface. These assertions
  ;; pin all nine public macros + the count so a future macro-surface
  ;; change can't silently drift the onboarding again.
  (testing "the onboarding text enumerates all nine reg-* macros, including the composition cohort"
    (let [text (-> (invoke "get-story-instructions" {}) :content first :text)]
      (doseq [m ["reg-story" "reg-variant" "reg-fragment" "reg-check"
                 "reg-workspace" "reg-mode" "reg-story-panel"
                 "reg-decorator" "reg-tag"]]
        (is (re-find (re-pattern m) text)
            (str "onboarding text must mention " m)))
      (is (re-find #"nine `reg-\*` macros" text)
          "onboarding states the count is nine, not seven")
      (is (not (re-find #"seven `reg-\*` macros" text))
          "the stale 'seven' count must be gone")
      (is (re-find #":compose" text)
          "onboarding surfaces the :compose composition mechanism")))
  (testing "the get-story-instructions descriptor description names the composition surface"
    (let [d    (some #(when (= "get-story-instructions" (:name %)) %)
                     registry/tool-registry)
          desc (:description d)]
      (is (re-find #"nine reg-\* macros" desc)
          "descriptor says nine, not seven")
      (is (re-find #"reg-fragment" desc) "descriptor names reg-fragment")
      (is (re-find #"reg-check" desc) "descriptor names reg-check"))))

(deftest get-story-instructions-emits-structured-content
  ;; rf2-vyacl — the descriptor declares an `:outputSchema`, so the
  ;; official MCP SDK's high-level callTool REJECTS a result with no
  ;; `:structuredContent` (JSON-RPC -32600). The handler MUST emit a
  ;; structuredContent slot. Mirrors re-frame2-pair-mcp's sibling
  ;; `get-re-frame2-pair-instructions` (which always emits structured
  ;; content via `wire/ok-text`).
  (testing "the result carries a non-nil :structuredContent matching the text"
    (let [r (invoke "get-story-instructions" {})]
      (is (success? r))
      (is (some? (:structuredContent r))
          "an outputSchema-declaring tool MUST return structuredContent (SDK -32600)")
      (is (= (-> r :content first :text)
             (-> r :structuredContent :instructions))
          "structuredContent mirrors the text slot under :instructions")))
  (testing "the descriptor declares an outputSchema — the invariant that makes structuredContent mandatory"
    (let [d (some #(when (= "get-story-instructions" (:name %)) %)
                  registry/tool-registry)]
      (is (map? (:outputSchema d))
          "get-story-instructions declares an :outputSchema, so it MUST emit structuredContent"))))

(deftest preview-variant-happy
  (let [r (invoke "preview-variant" {:variant-id "story.button/primary"
                                     :base-url "http://localhost:8000/"})
        s (:structuredContent r)]
    (is (success? r))
    (is (= :story.button/primary (:variant-id s)))
    (is (string? (:share-url s)))
    (is (re-find #"story\.button(/|%2F)primary" (:share-url s)))
    ;; :lifecycle is the loader STATE (retained adjunct); the verdict is
    ;; the unified :status — preview speaks the same vocabulary run-variant
    ;; does (rf2-ba86n.17), so a vacuous-pass preview reads :status :pass.
    (is (some? (:lifecycle s)))
    (is (= :pass (:status s)) "no assertions ⇒ vacuously :pass")
    (is (vector? (:checks s)))))

(deftest preview-variant-not-found
  (let [r (invoke "preview-variant" {:variant-id "story.nope/missing"})]
    (is (error? r))
    (is (re-find #"not found" (-> r :content first :text)))))

(deftest preview-variant-missing-arg
  (let [r (invoke "preview-variant" {})]
    (is (error? r))
    (is (re-find #"variant-id" (-> r :content first :text)))))

(deftest list-substrates-returns-vector
  (let [r (invoke "list-substrates" {})]
    (is (success? r))
    (is (vector? (-> r :structuredContent :substrates)))))

;; rf2-4sgak — pin the CLJS-var resolver contract. The senior-review
;; finding suspected the substrate bridge resolved an ALIAS symbol that
;; `clojure.core/resolve` would not honour. In fact `resolve` DOES honour
;; the calling ns's aliases, and the substrate var is nil on the JVM purely
;; because it is CLJS-only (a `#?(:cljs …)` def with no JVM Var). These
;; tests pin both facts so the contract can't silently regress.
(deftest resolve-cljs-var-finds-fully-qualified-jvm-var
  (testing "a fully-qualified symbol for a real JVM-side (CLJC) var resolves"
    (is (= #'re-frame.story/canonical-assertion-ids
           (cljs-resolve/resolve-cljs-var 're-frame.story/canonical-assertion-ids))
        "the resolver returns the underlying Var for a JVM-resident symbol"))
  (testing "an unresolvable symbol yields nil rather than throwing"
    (is (nil? (cljs-resolve/resolve-cljs-var 're-frame.story/no-such-var-xyz)))
    (is (nil? (cljs-resolve/resolve-cljs-var 'no.such.ns/whatever)))))

(deftest registered-substrates-degrades-empty-on-jvm-host
  ;; The CLJS-only `re-frame.story/registered-substrates` has no JVM Var, so
  ;; a JVM host reads the documented empty surface (NOT an error). This is
  ;; the correct degradation, not the "always degrades" bug the finding
  ;; suspected — the `:cljs` accessor branch reads the live registry when
  ;; these namespaces are hosted in a CLJS runtime.
  (testing "the accessor + its set form both read empty on a JVM host"
    (is (= [] (cljs-resolve/registered-substrates)))
    (is (= #{} (cljs-resolve/registered-substrates-set)))))

;; ---------------------------------------------------------------------------
;; Docs tools
;; ---------------------------------------------------------------------------

(deftest list-stories-no-filter
  (let [r (invoke "list-stories" {})
        ss (-> r :structuredContent :stories)]
    (is (success? r))
    (is (= 1 (count ss)))
    (is (= :story.button (-> ss first :id)))
    (is (= 2 (count (-> ss first :variants))))))

(deftest list-stories-tag-filter
  (testing "filtering by :docs returns the button story"
    (let [r (invoke "list-stories" {:tags ["docs"]})]
      (is (success? r))
      (is (= [:story.button]
             (mapv :id (-> r :structuredContent :stories))))))
  (testing "filtering by :test (registered canonical tag, no story matches) returns empty"
    (let [r (invoke "list-stories" {:tags ["test"]})
          s (:structuredContent r)]
      (is (success? r))
      (is (empty? (:stories s)))
      (is (not (contains? s :ignored-tags))
          "a REGISTERED tag that simply matches no story is not 'ignored'")))
  ;; rf2-wu1o2d — an unknown-only tag filter (a typo / stale tag) MUST
  ;; return an empty result, NOT silently widen to the full catalogue.
  ;; The supplied-but-unresolved name rides the `:ignored-tags` diagnostic.
  (testing "filtering by an UNKNOWN-only tag returns empty, never the full catalogue"
    (let [r (invoke "list-stories" {:tags [":docz"]})
          s (:structuredContent r)]
      (is (success? r))
      (is (empty? (:stories s))
          "unknown-only filter must NOT widen to all stories")
      (is (= [":docz"] (:ignored-tags s))
          "the unresolved supplied name is echoed back as a diagnostic")
      (is (nil? (find-keyword "docz"))
          "rf2-lqjbk: the unknown tag id MUST NOT have been interned")))
  ;; A mixed known+unknown filter still applies the KNOWN tag and reports
  ;; the dropped name.
  (testing "mixed known+unknown filter applies the known tag and reports the ignored name"
    (let [r (invoke "list-stories" {:tags [":docs" ":docz"]})
          s (:structuredContent r)]
      (is (success? r))
      (is (= [:story.button] (mapv :id (:stories s)))
          "the known :docs tag still narrows")
      (is (= [":docz"] (:ignored-tags s))
          "the unknown name is reported, the known tag is honoured")
      (is (nil? (find-keyword "docz"))
          "rf2-lqjbk: the unknown tag id MUST NOT have been interned")))
  ;; The no-`:tags` call is the ONLY path that returns the unfiltered
  ;; registry — a supplied filter (even an all-unknown one) always filters.
  (testing "no :tags arg returns the unfiltered catalogue (no :ignored-tags slot)"
    (let [r (invoke "list-stories" {})
          s (:structuredContent r)]
      (is (success? r))
      (is (= [:story.button] (mapv :id (:stories s))))
      (is (not (contains? s :ignored-tags))))))

(deftest get-story-happy
  (let [r (invoke "get-story" {:story-id "story.button"})]
    (is (success? r))
    (is (= :story.button (-> r :structuredContent :id)))
    (is (= "A clickable button." (-> r :structuredContent :body :doc)))))

(deftest get-story-not-found
  (let [r (invoke "get-story" {:story-id "story.nope"})]
    (is (error? r))))

(deftest get-variant-happy
  (let [r (invoke "get-variant" {:variant-id "story.button/primary"})]
    (is (success? r))
    (is (= :story.button/primary (-> r :structuredContent :id)))
    (is (= "Primary button." (-> r :structuredContent :body :doc)))))

(deftest explain-variant-happy
  ;; rf2-ba86n.17 — the agent mirror of the human Explain panel: the
  ;; variant-plan `:explain` projection (spec/017 §Explain API), a thin
  ;; wrapper over the shipped `story/explain` data API.
  (let [r (invoke "explain-variant" {:variant-id "story.button/primary"})
        s (:structuredContent r)
        e (:explain s)]
    (is (success? r))
    (is (= :story.button/primary (:variant-id s)))
    (is (map? e) "the :explain projection is a map")
    ;; The source/merge/runner-requirement slots the human Explain panel
    ;; renders must round-trip — these are the exact slots that gate-check
    ;; this tool is a faithful mirror, not a re-projection.
    (is (= [:story.button/primary] (:source-chain e)))
    (is (= [] (:parent-chain e)))
    (is (contains? e :merge) "the per-field merge rules are surfaced")
    (is (contains? e :effective-args) "arg resolution is surfaced")
    (is (contains? e :required-runner) "the plan's runner requirement is surfaced")))

(deftest explain-variant-unknown
  (let [r (invoke "explain-variant" {:variant-id "story.nope/missing"})]
    (is (error? r))
    (is (re-find #"not found" (-> r :content first :text)))))

(deftest list-tags-includes-canonical
  (let [r (invoke "list-tags" {})
        s (:structuredContent r)]
    (is (success? r))
    (is (every? (set (:canonical s))
                [:dev :docs :test :screenshot :experimental :internal :agent]))
    (testing "the canonical :state/* magnitude axis (rf2-k1k87) is part of the canonical set"
      (is (every? (set (:canonical s))
                  [:state/empty :state/small :state/medium :state/large :state/special])))))

(deftest list-modes-returns-fixture-mode
  (let [r (invoke "list-modes" {})
        ms (-> r :structuredContent :modes)]
    (is (success? r))
    (is (= 1 (count ms)))
    (is (= :Mode.theme/dark (-> ms first :id)))
    (is (= {:theme :dark} (-> ms first :args)))))

;; rf2-mqp1u — `list-decorators` is a read-only enumeration. The
;; `:wrap` closure on `:hiccup` decorators must NOT cross the wire
;; (closures don't serialise); the projection drops the slot in
;; favour of a `:has-wrap?` boolean. The canonical vocabulary
;; pre-registers a handful of decorators (e.g.
;; `:rf.story/layout-debug.measure`); the fixture adds three more,
;; one of each kind, so this test asserts presence rather than count.
(deftest list-decorators-projects-each-kind-safely
  (let [r  (invoke "list-decorators" {})
        ds (-> r :structuredContent :decorators)
        by-id (into {} (map (juxt :id identity)) ds)]
    (is (success? r))
    (is (some? (get by-id :dec.test/wrap-card)))
    (is (some? (get by-id :dec.test/seed-cart)))
    (is (some? (get by-id :dec.test/stub-http)))
    (is (= :hiccup (:kind (get by-id :dec.test/wrap-card))))
    (is (true? (:has-wrap? (get by-id :dec.test/wrap-card)))
        "hiccup decorator surfaces :has-wrap? not the closure")
    (is (not (contains? (get by-id :dec.test/wrap-card) :wrap))
        ":wrap closure MUST NOT be transported over MCP")
    (is (= :frame-setup (:kind (get by-id :dec.test/seed-cart))))
    (is (= {:cart {:items []}}
           (:app-db-patch (get by-id :dec.test/seed-cart))))
    (is (= :fx-override (:kind (get by-id :dec.test/stub-http))))
    (is (= :http   (:fx-id    (get by-id :dec.test/stub-http))))
    (is (= {:status 200 :body "ok"}
           (:response (get by-id :dec.test/stub-http))))))

(deftest list-decorators-kind-filter
  (testing "kind filter narrows to one decorator kind"
    (let [r       (invoke "list-decorators" {:kind "hiccup"})
          ds      (-> r :structuredContent :decorators)
          kinds   (set (map :kind ds))]
      (is (success? r))
      (is (= #{:hiccup} kinds)
          "filter MUST return only the requested kind")
      (is (some #(= :dec.test/wrap-card (:id %)) ds)
          "fixture's hiccup decorator is present")))
  (testing "filter with no canonical-or-fixture matches returns empty vec, not :error"
    (let [r  (invoke "list-decorators" {:kind "frame-setup"})
          ds (-> r :structuredContent :decorators)]
      (is (success? r))
      (is (every? #(= :frame-setup (:kind %)) ds))
      (is (some #(= :dec.test/seed-cart (:id %)) ds)))))

(deftest list-assertions-returns-canonical-seven
  (let [r (invoke "list-assertions" {})
        s (:structuredContent r)]
    (is (success? r))
    ;; rf2-5x1wt.21 — the seven dispatched assertions PLUS the tape-evaluated
    ;; :rf.assert/schema-error (the EXPECTED-schema-violation declaration).
    (is (= 8 (count (:canonical s))))
    (is (some #(= :rf.assert/path-equals (:id %)) (:canonical s)))
    (is (some #(= :rf.assert/no-warnings (:id %)) (:canonical s)))
    (is (some #(= :rf.assert/schema-error (:id %)) (:canonical s)))))

(deftest list-assertions-registered-covers-plan-compiler-vocabulary
  ;; rf2-4sgak — :registered MUST advertise the FULL vocabulary the Story
  ;; plan compiler accepts (`assertions/known-assertion-ids`, the SAME set
  ;; `plan.cljc` validates authored assertion atoms against). Previously it
  ;; mirrored only `canonical-assertion-ids`, so MCP agents could not
  ;; discover the DOM / visual / a11y / reactive-count ids the compiler
  ;; would accept and fell back to stale prose.
  (testing ":registered == the plan compiler's known-assertion-ids set"
    (let [r (invoke "list-assertions" {})
          s (:structuredContent r)]
      (is (success? r))
      (is (= (set assertions/known-assertion-ids)
             (set (:registered s)))
          ":registered must equal the plan compiler's known-assertion-ids"))))

(deftest list-assertions-registered-surfaces-browser-tier-families
  ;; rf2-4sgak — the specific browser-tier ids the canonical doc-vec does
  ;; NOT cover but the plan compiler accepts: DOM, visual, a11y,
  ;; reactive-count. A regression that re-narrowed :registered to the
  ;; canonical eight would drop these and fail here.
  (testing ":registered carries the DOM / visual / a11y / reactive families"
    (let [r        (invoke "list-assertions" {})
          reg      (set (:registered (:structuredContent r)))
          expected #{:rf.assert/dom-visible :rf.assert/dom-hidden
                     :rf.assert/dom-text
                     :rf.assert/visual-snapshot :rf.assert/a11y
                     :rf.assert/a11y-structural
                     :rf.assert/caused :rf.assert/no-cascade-rerender}]
      (is (success? r))
      (is (set/subset? expected reg)
          (str ":registered missing browser-tier ids: "
               (set/difference expected reg)))
      ;; And every canonical id is still present in :registered.
      (is (set/subset? (set (story/canonical-assertion-ids)) reg)
          ":registered must remain a superset of the canonical ids"))))

(deftest variant-edn-roundtrips
  (testing "variant->edn returns readable EDN text"
    (let [r (invoke "variant->edn" {:variant-id "story.button/primary"})]
      (is (success? r))
      (let [text (-> r :content first :text)
            back (clojure.edn/read-string text)]
        (is (map? back))
        (is (= "Primary button." (:doc back))))))
  (testing "rf2-vyacl: variant->edn ALSO emits structuredContent (it declares an outputSchema, so the SDK requires it)"
    ;; `variant->edn` was the only other tool besides get-story-instructions
    ;; that returned text-only while declaring an :outputSchema — the same
    ;; -32600 latent defect. It now mirrors the body into structuredContent.
    (let [r (invoke "variant->edn" {:variant-id "story.button/primary"})]
      (is (some? (:structuredContent r))
          "an outputSchema-declaring tool MUST return structuredContent (SDK -32600)")
      (is (= "Primary button." (-> r :structuredContent :doc))))))

;; rf2-i0kyy — `get-docs-markdown` is the agent-paste shape.
(deftest get-docs-markdown-renders-story-and-variants
  (let [r  (invoke "get-docs-markdown" {:story-id "story.button"})
        s  (:structuredContent r)
        md (:markdown s)]
    (is (success? r))
    (is (string? md))
    (is (re-find #"^# Story `:story\.button`" md)
        "renders an H1 with the story id")
    (is (re-find #"A clickable button\." md)
        "includes the story :doc")
    (is (re-find #":story\.button/primary" md)
        "lists the primary variant")
    (is (re-find #":story\.button/secondary" md)
        "lists the secondary variant")
    (is (re-find #"Primary button\." md)
        "includes per-variant :doc")
    (is (= :story.button (:story-id s)))
    (is (vector? (:variants s)))))

(deftest get-docs-markdown-unknown-story
  (let [r (invoke "get-docs-markdown" {:story-id "story.nope/missing"})]
    (is (error? r))
    (is (re-find #"not found" (-> r :content first :text)))))

(deftest get-docs-markdown-missing-arg
  (let [r (invoke "get-docs-markdown" {})]
    (is (error? r))
    (is (re-find #"story-id" (-> r :content first :text)))))

;; ---------------------------------------------------------------------------
;; Pagination on the Docs `list-*` tools (rf2-76sf6)
;;
;; spec/Principles.md §'Tight token budget' MUST: every read tool whose
;; return size is a function of registry size MUST accept `:limit` +
;; `:cursor`. These tests pin:
;;   - small registries return the bare shape (no pagination metadata)
;;     — the pre-rf2-76sf6 wire shape is preserved
;;   - large registries (>= :limit) return :total :limit :has-more?
;;     :next-cursor
;;   - cursor round-trips across pages
;;   - a stale cursor (registry mutated between pages) returns
;;     :rf.mcp/cursor-stale
;;   - `:limit` is clamped to the documented ceiling
;; ---------------------------------------------------------------------------

(deftest list-stories-small-registry-no-pagination-metadata
  (testing "single-story fixture fits on one page — no :total / :next-cursor"
    (let [r (invoke "list-stories" {})
          s (:structuredContent r)]
      (is (success? r))
      (is (vector? (:stories s)))
      (is (not (contains? s :total))
          "small registry MUST NOT carry pagination metadata (wire shape unchanged)")
      (is (not (contains? s :next-cursor))))))

(deftest list-stories-paginates-when-over-limit
  (testing "with many stories + :limit smaller than total, response is paginated"
    ;; Register additional stories so total > :limit. The fixture leaves
    ;; one story; adding 4 more + :limit 2 produces a 5-entry total.
    (doseq [n (range 4)]
      (story/reg-story (keyword (str "story.pager" n))
        {:doc (str "Pager story " n) :component :app/x :tags #{:dev}}))
    (let [r (invoke "list-stories" {:limit 2})
          s (:structuredContent r)]
      (is (success? r))
      (is (= 2 (count (:stories s))) "first page honours :limit")
      (is (= 5 (:total s)) "five stories total (fixture + 4)")
      (is (= 2 (:limit s)))
      (is (true? (:has-more? s)))
      (is (string? (:next-cursor s)))
      ;; Round-trip the cursor: passing :next-cursor returns the next page.
      (let [r2 (invoke "list-stories" {:limit 2 :cursor (:next-cursor s)})
            s2 (:structuredContent r2)]
        (is (success? r2))
        (is (= 2 (count (:stories s2))) "second page also 2 entries")
        (is (true? (:has-more? s2)))
        (is (string? (:next-cursor s2)))
        ;; Final page: one entry, has-more? false, next-cursor nil.
        (let [r3 (invoke "list-stories" {:limit 2 :cursor (:next-cursor s2)})
              s3 (:structuredContent r3)]
          (is (success? r3))
          (is (= 1 (count (:stories s3))) "final page has the remaining entry")
          (is (false? (:has-more? s3)))
          (is (nil? (:next-cursor s3))))))))

(deftest list-stories-stale-cursor-returns-error
  (testing "a registry mutation between pages stales the cursor"
    (doseq [n (range 3)]
      (story/reg-story (keyword (str "story.stale" n))
        {:doc (str "Stale " n) :component :app/x :tags #{:dev}}))
    (let [r1     (invoke "list-stories" {:limit 1})
          cursor (-> r1 :structuredContent :next-cursor)]
      (is (string? cursor))
      ;; Mutate the registry: register one more story before deref.
      (story/reg-story :story.intruder
        {:doc "Landed mid-pagination" :component :app/x :tags #{:dev}})
      (let [r2 (invoke "list-stories" {:limit 1 :cursor cursor})
            s2 (:structuredContent r2)]
        (is (error? r2))
        (is (= :rf.mcp/cursor-stale (:reason s2)))
        (is (= "list-stories" (:tool s2)))))))

(deftest list-stories-limit-clamped-to-max
  (testing ":limit above the ceiling clamps DOWN to max-limit"
    (let [r (invoke "list-stories" {:limit 99999})
          s (:structuredContent r)]
      (is (success? r))
      ;; With 1 fixture story, no pagination kicks in — but if it did,
      ;; the :limit slot would be 200 (max-limit), not 99999. We verify
      ;; this by registering enough stories to force pagination.
      (doseq [n (range 250)]
        (story/reg-story (keyword (str "story.clamp" n))
          {:doc "" :component :app/x :tags #{:dev}}))
      (let [r2 (invoke "list-stories" {:limit 99999})
            s2 (:structuredContent r2)]
        (is (success? r2))
        ;; Total is fixture + 250 = 251; with :limit clamped to 200,
        ;; first page is 200 entries and :has-more? true.
        (is (<= (count (:stories s2)) 200)
            "first page MUST NOT exceed max-limit 200")))))

(deftest list-stories-malformed-cursor-reads-as-stale
  (testing "a malformed :cursor (bad base64 / wrong shape) returns the stale error"
    (let [r (invoke "list-stories" {:cursor "not-a-valid-cursor!!!"})
          s (:structuredContent r)]
      (is (error? r))
      (is (= :rf.mcp/cursor-stale (:reason s))))))

(deftest list-modes-paginates
  (testing "list-modes honours :limit + :cursor"
    (doseq [n (range 35)]
      ;; Mode ids per spec/007 grammar: `:Mode.<path>/<name>`.
      (story/reg-mode (keyword "Mode.pager" (str "m" n))
        {:doc "" :args {}}))
    (let [r (invoke "list-modes" {:limit 10})
          s (:structuredContent r)]
      (is (success? r))
      (is (= 10 (count (:modes s))))
      (is (true? (:has-more? s)))
      (is (string? (:next-cursor s))))))

(deftest list-decorators-pagination-preserves-kind-filter
  (testing ":kind filter narrows the paginated entry set"
    ;; Build enough hiccup decorators to force pagination of a kind filter.
    (doseq [n (range 30)]
      (story/reg-decorator (keyword (str "dec.page/h" n))
        {:kind :hiccup :doc "" :wrap (fn [child] child)}))
    (let [r (invoke "list-decorators" {:kind "hiccup" :limit 5})
          s (:structuredContent r)]
      (is (success? r))
      (is (= 5 (count (:decorators s))))
      (is (every? #(= :hiccup (:kind %)) (:decorators s)))
      (is (true? (:has-more? s))))))

(deftest list-tags-canonical-stays-full-under-pagination
  (testing "the canonical-tag slot is bounded (7 inclusion + 5 :state/* magnitude = 12) so it never paginates"
    ;; Register lots of custom tags.
    (doseq [n (range 50)]
      (story/reg-tag (keyword (str "tag/pager" n)) {:doc ""}))
    (let [r (invoke "list-tags" {:limit 5})
          s (:structuredContent r)]
      (is (success? r))
      (is (= 12 (count (:canonical s)))
          "the 12-entry canonical set (7 inclusion + 5 :state/* magnitude) always lands in full")
      (is (= 5 (count (:custom s))) ":custom honours :limit")
      (is (true? (:has-more? s))))))

(deftest list-assertions-canonical-doc-stays-full
  (testing "the canonical assertion-doc vector is bounded (8) so it never paginates"
    (let [r (invoke "list-assertions" {:limit 3})
          s (:structuredContent r)]
      (is (success? r))
      ;; rf2-5x1wt.21 — eight: the seven dispatched + the tape-evaluated
      ;; :rf.assert/schema-error.
      (is (= 8 (count (:canonical s)))
          "the canonical-doc vec is the bounded reference; not subject to pagination")
      (is (<= (count (:registered s)) 3) ":registered honours :limit"))))

;; ---------------------------------------------------------------------------
;; Testing tools
;; ---------------------------------------------------------------------------

(deftest run-variant-happy
  (let [r (invoke "run-variant" {:variant-id "story.button/primary"})
        s (:structuredContent r)]
    (is (success? r))
    (is (= :story.button/primary (:frame s)))
    ;; rf2-ba86n.17 clean break — the verdict is the unified :status, not
    ;; the retired :passing? boolean. A zero-assertion run is vacuously :pass.
    (is (= :pass (:status s)) "no assertions ⇒ vacuously :pass")
    (is (not (contains? s :passing?)) "the retired :passing? boolean is gone")
    (is (vector? (:assertions s)))
    (is (vector? (:checks s)) "the unified :checks group is present")))

(deftest run-variant-unknown
  (let [r (invoke "run-variant" {:variant-id "story.nope/missing"})]
    (is (error? r))
    (is (re-find #"not found" (-> r :content first :text)))))

(deftest run-variant-cannot-run-reachable
  ;; rf2-ba86n.17 — the distinct THIRD verdict `:cannot-run` must be
  ;; reachable over the wire (the old :passing? boolean could not express
  ;; it). A `:rf.assert/caused` expectation needs reactive evidence; run
  ;; under the default no-reactive headless runner, the causal matcher
  ;; fails closed to :cannot-run rather than silently passing against an
  ;; empty projection (spec/017 §Causal and cascade assertions).
  (testing "a causal assertion with no reactive evidence drives :status :cannot-run"
    (config/set-allow-writes! true)
    (let [reg (invoke "register-variant"
                      {:variant-id "story.cause/unrunnable"
                       :body (str "{:doc \"A causal expectation with no reactive evidence.\""
                                  " :assertions"
                                  "  [[:rf.assert/caused {:event :some/event :surface [:any] :min 1}]]}")})]
      (is (success? reg)))
    (let [run (invoke "run-variant" {:variant-id "story.cause/unrunnable"})
          s   (:structuredContent run)]
      (is (success? run))
      (is (= :cannot-run (:status s))
          "no reactive evidence ⇒ the causal expectation is :cannot-run, not a silent pass"))
    (invoke "unregister-variant" {:variant-id "story.cause/unrunnable"})))

(deftest snapshot-identity-stable
  (testing "the same args produce the same content-hash"
    (let [r1 (invoke "snapshot-identity" {:variant-id "story.button/primary"})
          r2 (invoke "snapshot-identity" {:variant-id "story.button/primary"})]
      (is (success? r1))
      (is (success? r2))
      (is (= (-> r1 :structuredContent :content-hash)
             (-> r2 :structuredContent :content-hash))))))

(deftest snapshot-identity-unknown
  (let [r (invoke "snapshot-identity" {:variant-id "story.nope/missing"})]
    (is (error? r))))

;; rf2-09rfpu Finding 2 — `snapshot-identity` forwards `:cell-overrides`
;; into `story/snapshot-identity`, where it perturbs the `:content-hash`
;; via the resolved `:effective-args`. The descriptor + API now advertise
;; the slot (it was hidden behind `additionalProperties false`, so a
;; validating client stripped a real identity input while a non-validating
;; client got a different hash). These tests pin: (a) the slot is in the
;; advertised input schema, and (b) an override actually changes the hash.
(deftest snapshot-identity-advertises-cell-overrides
  (testing "the snapshot-identity descriptor exposes :cell-overrides in its input schema"
    (let [desc  (first (filter #(= "snapshot-identity" (:name %)) registry/tool-registry))
          props (-> desc :inputSchema :properties)]
      (is (some? desc) "snapshot-identity must be in the tool registry")
      (is (contains? props :cell-overrides)
          "cell-overrides is an identity input and MUST be advertised, not hidden behind additionalProperties false"))))

(deftest snapshot-identity-cell-overrides-changes-hash
  (testing "a cell-override perturbs the content-hash (it is identity-bearing)"
    (let [bare      (invoke "snapshot-identity" {:variant-id "story.button/primary"})
          over<- (invoke "snapshot-identity" {:variant-id    "story.button/primary"
                                              :cell-overrides {:label "Override"}})]
      (is (success? bare))
      (is (success? over<-))
      (is (not= (-> bare :structuredContent :content-hash)
                (-> over<- :structuredContent :content-hash))
          "the same variant with a different cell-override must hash differently"))))

(deftest read-a11y-violations-jvm-returns-note
  (testing "JVM-standalone deploy returns empty violations with the documented hint"
    (let [r (invoke "read-a11y-violations" {:variant-id "story.button/primary"})
          s (:structuredContent r)]
      (is (success? r))
      (is (vector? (:violations s)))
      (is (string? (:note s)))
      (is (re-find #"CLJS-only" (:note s))))))

(deftest read-a11y-violations-co-hosted-surfaces-stored-violations
  ;; rf2-ynjts.20 — the co-hosted (CLJS var resolved) branch of
  ;; `tool-read-a11y-violations`. The existing test covers ONLY the JVM-standalone
  ;; path (var unresolved ⇒ empty + :note). The populated path — the
  ;; resolved violations atom carries findings for the frame — was
  ;; untested. We stand in for the resolved CLJS var with a var-of-atom
  ;; mirror: the handler does `(deref @violations-by-frame-var)`, so the
  ;; redef must hold a value whose deref is the per-frame violations atom.
  (testing "violations stored for the frame are surfaced; :note is nil"
    (let [vios   [{:id "label" :impact "critical" :nodes [{:html "<input>"}]}]
          ;; `@var` → inner atom; `(deref inner)` → the by-frame map.
          stand-in (atom (atom {:story.button/primary vios}))]
      (with-redefs [re-frame.story-mcp.tools.testing/violations-by-frame-var stand-in]
        (let [r (invoke "read-a11y-violations" {:variant-id "story.button/primary"})
              s (:structuredContent r)]
          (is (success? r))
          (is (= vios (:violations s))
              "the stored violations for the frame ride through verbatim")
          (is (nil? (:note s))
              "co-hosted deploy with a resolved atom emits no JVM-standalone hint")))))
  (testing "a frame with no stored violations returns an empty vec (still co-hosted, :note nil)"
    (let [stand-in (atom (atom {:story.other/frame [{:id "x"}]}))]
      (with-redefs [re-frame.story-mcp.tools.testing/violations-by-frame-var stand-in]
        (let [r (invoke "read-a11y-violations" {:variant-id "story.button/primary"})
              s (:structuredContent r)]
          (is (success? r))
          (is (= [] (:violations s))
              "no entry for this frame ⇒ empty vec, not nil")
          (is (nil? (:note s))
              "the atom resolved, so this is NOT the JVM-standalone path"))))))

(deftest read-failures-empty-after-no-run
  (testing "no run yet ⇒ zero accumulated assertions, vacuously :pass"
    (let [r (invoke "read-failures" {:variant-id "story.button/primary"})
          s (:structuredContent r)]
      (is (success? r))
      (is (= 0 (:total s)))
      (is (empty? (:failures s)))
      (is (empty? (:assertions s)))
      ;; rf2-ba86n.17 clean break — :status is the unified verdict; the
      ;; retired :passing? boolean is gone.
      (is (= :pass (:status s)))
      (is (not (contains? s :passing?))))))

;; ---------------------------------------------------------------------------
;; Self-healing loop — failing :rf.assert/* through run-variant → read-failures
;;
;; Per rf2-6r441: existing tests cover the optimistic (vacuous-pass) flow only.
;; This deftest drives a DELIBERATELY-FAILING `:rf.assert/path-equals` through
;; the MCP tool surface and asserts the AI-visible failure shape — the wire-
;; side contract an agent would consume.
;;
;; The agent self-healing loop has four steps:
;;   1. register-variant with a `:script` body whose assertion will fail
;;   2. run-variant — :status :fail; :assertions carries the failed record
;;   3. read-failures — non-empty :failures vector with structured data
;;   4. (agent proposes a fix — out of scope for this contract test)
;;
;; The failure record's shape (per tools/story/spec/004-Assertions.md +
;; tools/story/src/re_frame/story/assertions.cljc `assertion-record`):
;;
;;     {:assertion :rf.assert/path-equals
;;      :payload   [[:auth :status] :authenticated]
;;      :passed?   false
;;      :expected  :authenticated
;;      :actual    nil
;;      :path      [:auth :status]
;;      :reason    "expected :authenticated at [:auth :status] but got nil"
;;      :elapsed-ms <int>}
;;
;; The MCP wire serialises this as-is on `:structuredContent` (per
;; `tools/testing.cljc` `tool-read-failures` + `tool-run-variant`) —
;; Story keys survive the JSON-RPC round-trip into the agent's view.
;; ---------------------------------------------------------------------------

(deftest self-healing-loop-failing-assertion-shape
  (testing "register → run → read-failures surfaces the :rf.assert/path-equals failure shape"
    (config/set-allow-writes! true)
    ;; Step 1 — agent registers a variant whose :script body asserts a
    ;; slot that no setup step populated. The assertion will fail because
    ;; `(get-in @app-db [:auth :status])` is nil, not :authenticated.
    ;;
    ;; Public vocabulary (spec/017 §Public vocabulary): `:script` is the
    ;; phase-4 play surface. Each assertion event is wrapped as
    ;; `[:dispatch-sync <event-vec>]` so the `:rf.assert/*` event runs
    ;; through the standard re-frame cascade and lands its record on the
    ;; frame's `[:rf.story/assertions]` BEFORE `read-failures` / the
    ;; `run-variant` result is built.
    (let [reg (invoke "register-variant"
                      {:variant-id "story.auth/sad"
                       :body (str "{:doc \"Deliberately-failing assertion.\""
                                  " :script [[:dispatch-sync"
                                  " [:rf.assert/path-equals [:auth :status] :authenticated]]]}")})]
      (is (success? reg) "fixture registration succeeds")
      (is (true? (-> reg :structuredContent :registered?))))

    ;; Step 2 — run-variant. The wire result carries the unified :status
    ;; :fail and a non-empty :assertions vector. The failed record carries
    ;; the assertion-id, payload, and expected/actual slots — enough for the
    ;; agent to localise the failure without re-fetching anything.
    (let [run (invoke "run-variant" {:variant-id "story.auth/sad"})
          s   (:structuredContent run)
          a   (first (:assertions s))]
      (is (success? run))
      (is (= :fail (:status s))
          "a failed assertion drives the unified verdict to :fail")
      (is (= :fail (:status a)) "the failed record carries the derived :status :fail")
      (is (= 1 (count (:assertions s))) "one assertion fired, one record")
      (is (= :rf.assert/path-equals (:assertion a))
          "the failed record names the canonical assertion id")
      (is (false? (:passed? a)) "the record explicitly carries :passed? false")
      (is (= :authenticated (:expected a)))
      (is (nil? (:actual a)))
      (is (= [:auth :status] (:path a))
          "the path slot localises the assertion to a single app-db site")
      (is (string? (:reason a))
          "the :reason slot is the human-readable explanation the AI surfaces back to the LLM")
      (is (re-find #":authenticated" (:reason a))
          "the reason text names the expected value"))

    ;; Step 3 — read-failures (the dedicated agent-facing read of accumulated
    ;; failures without re-running). The shape per `tool-read-failures`:
    ;;   {:variant-id <kw> :status <kw> :total <int> :failures <vec> :assertions <vec>}
    (let [rf (invoke "read-failures" {:variant-id "story.auth/sad"})
          s  (:structuredContent rf)
          f  (first (:failures s))]
      (is (success? rf))
      (is (= :story.auth/sad (:variant-id s))
          ":variant-id round-trips so the agent can correlate the read with its source variant")
      (is (= 1 (:total s)) ":total counts every assertion (passed + failed)")
      (is (= 1 (count (:failures s)))
          ":failures filters to the genuine failure statuses (:fail / :error)")
      (is (= :fail (:status s))
          ":status is the same unified verdict `run-variant` returned — consistent across the read surface")
      ;; The failure record's keys match the run-variant projection — the
      ;; agent sees the same unified record shape regardless of which tool read it.
      (is (= :rf.assert/path-equals (:assertion f)))
      (is (= :fail (:status f)) "the failure record carries the derived :status :fail")
      (is (false? (:passed? f)))
      (is (= :authenticated (:expected f)))
      (is (nil? (:actual f)))
      (is (= [:auth :status] (:path f))))

    ;; Step 4 (out of scope) — an agent would now propose a `:events` slot
    ;; like `[[:test/set-status]]` and re-register, then re-run. The "fix
    ;; passes" half is exercised in tools/story's `path-equals-pass` test.

    ;; Tear-down — keep the read surface clean for any downstream test.
    (config/set-allow-writes! true)
    (invoke "unregister-variant" {:variant-id "story.auth/sad"})))

(deftest self-healing-loop-survives-record-dont-throw
  (testing "play-runner records every failure and continues; read-failures returns all of them"
    ;; Per tools/story/spec/004-Assertions.md the play sequence does NOT
    ;; halt on a failed assertion — failures record into the accumulator and
    ;; the sequence runs to completion. The agent's view of `read-failures`
    ;; therefore reflects EVERY failure observed, not just the first.
    (config/set-allow-writes! true)
    ;; Wrap each `:rf.assert/*` event vector as a `[:dispatch-sync ...]`
    ;; step inside `:script` (the public phase-4 play surface). The runner
    ;; walks both steps even if the first one's assertion fails —
    ;; record-don't-throw lets the play sequence complete and both records
    ;; land on `[:rf.story/assertions]` for `read-failures` to surface.
    (let [reg (invoke "register-variant"
                      {:variant-id "story.auth/double-fail"
                       :body (str "{:doc \"Two failing assertions; both must record.\""
                                  " :script"
                                  " [[:dispatch-sync"
                                  "   [:rf.assert/path-equals [:auth :status] :authenticated]]"
                                  "  [:dispatch-sync"
                                  "   [:rf.assert/path-equals [:user :role] :admin]]]}")})]
      (is (success? reg)))

    (let [run (invoke "run-variant" {:variant-id "story.auth/double-fail"})
          s   (:structuredContent run)]
      (is (success? run))
      (is (= :fail (:status s)))
      (is (= 2 (count (:assertions s)))
          "BOTH assertions recorded — the play sequence ran to completion despite the first fail"))

    (let [rf (invoke "read-failures" {:variant-id "story.auth/double-fail"})
          s  (:structuredContent rf)]
      (is (success? rf))
      (is (= 2 (:total s)))
      (is (= 2 (count (:failures s))))
      (is (= [:auth :status] (-> s :failures first :path))
          "failures preserve registration order")
      (is (= [:user :role] (-> s :failures second :path))))

    (invoke "unregister-variant" {:variant-id "story.auth/double-fail"})))

;; ---------------------------------------------------------------------------
;; Write surface (gating)
;; ---------------------------------------------------------------------------

(deftest register-variant-gated-by-default
  (testing "default config rejects register-variant"
    (is (false? (config/writes-allowed?)))
    (let [r (invoke "register-variant" {:variant-id "story.button/danger"
                                        :body {:doc "Danger button."
                                               :args {:label "Delete"}}})]
      (is (error? r))
      (is (re-find #"Write surface disabled" (-> r :content first :text)))
      (is (true? (-> r :structuredContent :gated))))))

(deftest register-variant-happy-when-allowed
  (testing "with allow-writes? true, registration goes through"
    (config/set-allow-writes! true)
    (let [r (invoke "register-variant" {:variant-id "story.button/danger"
                                        :body {:doc "Danger button."
                                               :args {:label "Delete"}}})]
      (is (success? r))
      (is (= :story.button/danger (-> r :structuredContent :variant-id)))
      (is (true? (-> r :structuredContent :registered?)))
      ;; Variant is now reachable via the read surface.
      (is (some? (story/variant->edn :story.button/danger))))))

(deftest register-variant-edn-string-body
  (testing "body may arrive as an EDN-encoded string"
    (config/set-allow-writes! true)
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/wire"
                     :body "{:doc \"Wire body.\" :args {:label \"OK\"}}"})]
      (is (success? r))
      (is (= "Wire body." (:doc (story/variant->edn :story.button/wire)))))))

;; ---------------------------------------------------------------------------
;; EDN reader hardening on register-variant :body (rf2-g9fje fix 2/3)
;;
;; The EDN-string path through `tool-register-variant` is locked down per
;; the rf2-uaymx audit: no tagged literals, no custom readers, 64KB size
;; cap, 64-level depth cap. Pre-fix, `(edn/read-string body)` would happily
;; eval `#=(...)` evaluator forms (when `*read-eval*` was true) or invoke
;; any data reader on the `*data-readers*` table; post-fix the reader is
;; `:readers {}` with a throwing `:default`, so any tagged-literal form
;; lands in `::edn-error`.
;; ---------------------------------------------------------------------------

(deftest register-variant-rejects-tagged-literal
  (testing "EDN body containing a custom tagged literal is rejected (rf2-g9fje)"
    (config/set-allow-writes! true)
    ;; Custom tags (non-EDN-built-in: not #inst / #uuid) route through the
    ;; reader's :default handler, which throws under the rf2-g9fje
    ;; hardening. The throw lands as ::edn-error → the "must be a map or
    ;; a valid EDN string" error message.
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/tagged"
                     :body "{:doc #my.app/widget {:x 1}}"})]
      (is (error? r))
      (is (re-find #"(?i)must be a map or a valid EDN string" (-> r :content first :text))
          "tagged literals route through the EDN-error message"))))

(deftest register-variant-rejects-reader-eval-form
  (testing "EDN body containing #=() does not evaluate (rf2-g9fje)"
    (config/set-allow-writes! true)
    ;; `#=(...)` is the read-time eval form. `clojure.edn/read-string`
    ;; ignores `*read-eval*` and rejects it as a tagged literal under
    ;; our throwing :default. The body should be refused.
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/eval"
                     :body "{:doc #=(println \"PWNED\") :args {}}"})]
      (is (error? r)
          "the #= eval form must be rejected before any side-effect can fire"))))

(deftest register-variant-rejects-oversize-edn-body
  (testing "EDN body exceeding the 64KB ceiling is rejected (rf2-g9fje)"
    (config/set-allow-writes! true)
    (let [big-doc (apply str (repeat (* 70 1024) \x))
          r       (invoke "register-variant"
                          {:variant-id "story.button/oversize"
                           :body       (str "{:doc \"" big-doc "\"}")})]
      (is (error? r))
      (is (re-find #"(?i)must be a map or a valid EDN string" (-> r :content first :text))
          "oversize payload routes through the EDN-error message"))))

(deftest register-variant-rejects-over-deep-edn-body
  (testing "EDN body exceeding the 64-level depth ceiling is rejected (rf2-g9fje)"
    (config/set-allow-writes! true)
    ;; Build a 100-level nested map by string concatenation; well past the
    ;; 64 ceiling. The depth check runs AFTER `edn/read-string` parses, so
    ;; the rejection happens before the registrar sees the value.
    (let [deep-edn (str (apply str (repeat 100 "{:a "))
                        "1"
                        (apply str (repeat 100 "}")))
          r        (invoke "register-variant"
                           {:variant-id "story.button/deep"
                            :body       deep-edn})]
      (is (error? r))
      (is (re-find #"(?i)must be a map or a valid EDN string" (-> r :content first :text))))))

(deftest register-variant-rejects-bad-shape
  (testing "registration with an invalid body returns a tool-execution error"
    (config/set-allow-writes! true)
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/bad"
                     :body {:tags #{:nonexistent-tag}}})]
      (is (error? r))
      (is (re-find #"(?i)Registration failed" (-> r :content first :text))))))

(deftest register-variant-rejects-non-map-body
  ;; rf2-ynjts.20 — the `coerce-body` `::not-a-map` branch (write.cljc).
  ;; The hardening tests above all cover the `::edn-error` branch (tagged
  ;; literal, oversize, over-deep, malformed). The DISTINCT `::not-a-map`
  ;; branch — a `:body` that PARSES cleanly but isn't a map — emits a
  ;; different error message ("must be a map; got <class>") and was
  ;; untested. A vector/scalar body must not reach the registrar.
  (testing "an EDN-string body that parses to a vector is rejected as not-a-map"
    (config/set-allow-writes! true)
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/vecbody"
                     :body "[:not :a :map]"})]
      (is (error? r))
      (is (re-find #"(?i):body must be a map" (-> r :content first :text))
          "the not-a-map branch emits the map-required message, not the edn-error message")
      (is (nil? (story/variant->edn :story.button/vecbody))
          "a non-map body never reaches the registrar")))
  (testing "an EDN-string body that parses to a scalar is rejected as not-a-map"
    (config/set-allow-writes! true)
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/scalarbody"
                     :body "42"})]
      (is (error? r))
      (is (re-find #"(?i):body must be a map" (-> r :content first :text))))))

;; ---------------------------------------------------------------------------
;; rf2-tag30h — write-side no-intern: an INVALID id that correctly returns
;; an MCP error must leave NO keyword in the JVM keyword table.
;;
;; The write paths minted the keyword via `fresh-keyword` (intern FIRST),
;; then let the registrar's downstream `assert-id!` reject on grammar — so
;; an invalid `:variant-id` / `:new-variant-id` was REJECTED but already
;; INTERNED. A hostile/malfunctioning client could grow the never-shrinking
;; JVM keyword table unboundedly through failed write attempts (a slow-burn
;; DoS), and a wide object-form body could intern many arbitrary keys before
;; the registrar normalised them. The fix validates the id grammar on the
;; STRING shape (`fresh-keyword-checked` + `variant-id-shape?`) and caps the
;; object-body string-key WIDTH — both BEFORE any intern. `find-keyword`
;; (JVM, no-intern lookup) is the no-intern oracle.
;; ---------------------------------------------------------------------------

(deftest register-variant-invalid-id-does-not-intern
  (testing "an invalid :variant-id is rejected with NO interned keyword (rf2-tag30h)"
    (config/set-allow-writes! true)
    ;; precondition: the keyword is not already interned
    (is (nil? (find-keyword "not-story" "tag30h-invalid-A")))
    (let [r (invoke "register-variant"
                    {:variant-id "not-story/tag30h-invalid-A" :body {:args {}}})]
      (is (error? r) "an invalid-grammar :variant-id is rejected")
      (is (= :rf.error/variant-id-shape (-> r :structuredContent :rf.error))
          "the reject carries the structured variant-id-shape error")
      (is (nil? (find-keyword "not-story" "tag30h-invalid-A"))
          "the rejected id MUST NOT leave an interned keyword"))))

(deftest record-as-variant-invalid-new-id-does-not-intern
  (testing "an invalid write-back :new-variant-id is rejected with NO interned keyword (rf2-tag30h)"
    (config/set-allow-writes! true)
    (is (nil? (find-keyword "not-story" "tag30h-wb-invalid-B")))
    (let [r (invoke "record-as-variant"
                    {:variant-id     "story.button/primary"
                     :write-back     true
                     :new-variant-id "not-story/tag30h-wb-invalid-B"
                     :duration-ms    0})]
      (is (error? r) "an invalid-grammar :new-variant-id is rejected")
      (is (= :rf.error/variant-id-shape (-> r :structuredContent :rf.error))
          "the reject carries the structured variant-id-shape error")
      (is (nil? (find-keyword "not-story" "tag30h-wb-invalid-B"))
          "the rejected write-back id MUST NOT leave an interned keyword"))))

(deftest register-variant-wide-object-body-rejected-without-interning
  (testing "an object-form body with too many string keys is rejected BEFORE keywordising (rf2-tag30h)"
    (config/set-allow-writes! true)
    ;; A shallow object with thousands of distinct unknown string keys —
    ;; under the depth cap but over the width cap. Pick a distinctive key.
    (let [distinctive "tag30h-wide-DISTINCTIVE-KEY"
          wide-body   (into {distinctive 1}
                            (map (fn [i] [(str "k-tag30h-" i) i]))
                            (range 2000))]
      (is (nil? (find-keyword distinctive))
          "precondition: distinctive wide key not interned")
      (let [r (invoke "register-variant"
                      {:variant-id "story.button/wide" :body wide-body})]
        (is (error? r) "a too-wide object body is rejected")
        (is (= :rf.story-mcp/body-too-wide (-> r :structuredContent :rf.error))
            "the reject carries the structured body-too-wide error")
        (is (nil? (find-keyword distinctive))
            "a too-wide body MUST NOT intern its arbitrary string keys")
        (is (nil? (story/variant->edn :story.button/wide))
            "the too-wide body never reaches the registrar")))))

(deftest register-variant-narrow-object-body-still-registers
  (testing "a normal object-form body (string keys, under the width cap) still registers (rf2-tag30h regression guard)"
    (config/set-allow-writes! true)
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/objform"
                     :body {"doc" "object-form body" "args" {"label" "Go"}}})]
      (is (success? r) "a legitimate narrow object body registers")
      (is (some? (story/variant->edn :story.button/objform))
          "the variant reached the registry")
      (is (= "object-form body" (:doc (story/variant->edn :story.button/objform)))
          "string keys were keywordised into the registered body"))))

(deftest unregister-variant-gated-by-default
  (let [r (invoke "unregister-variant" {:variant-id "story.button/primary"})]
    (is (error? r))
    (is (re-find #"Write surface disabled" (-> r :content first :text)))))

(deftest unregister-variant-happy-when-allowed
  (config/set-allow-writes! true)
  (let [r (invoke "unregister-variant" {:variant-id "story.button/primary"})]
    (is (success? r))
    (is (true? (-> r :structuredContent :unregistered?)))
    (is (nil? (story/variant->edn :story.button/primary)))))

(deftest unregister-variant-unknown-is-error-not-no-op
  ;; Correctness review (rf2-nce6f): `unregister-variant` resolves
  ;; `:variant-id` via `safe-keyword` against the LIVE registered-variant
  ;; set (rf2-lqjbk `with-variant-id`), so an unregistered id NEVER reaches
  ;; the handler body — it short-circuits to a `Variant not found` error.
  ;; The success path therefore always reports `:unregistered? true` (the
  ;; old `:unregistered? false` "already-gone" branch was structurally
  ;; unreachable dead code; the descriptor example documenting it was
  ;; impossible). This pins the spec-conformant contract (spec/API.md
  ;; §unregister-variant: error when not registered) so the dead branch
  ;; cannot regress back in.
  (testing "an unregistered :variant-id is a tool-execution error, never a false-no-op"
    (config/set-allow-writes! true)
    (let [r (invoke "unregister-variant" {:variant-id "story.no/such"})]
      (is (error? r))
      (is (re-find #"not found" (-> r :content first :text)))
      (is (not (contains? (:structuredContent r) :unregistered?))
          "no :unregistered? slot on the not-found path — it's an error, not a success envelope")))
  (testing "the success path always reports :unregistered? true"
    (config/set-allow-writes! true)
    (let [r (invoke "unregister-variant" {:variant-id "story.button/secondary"})]
      (is (success? r))
      (is (true? (-> r :structuredContent :unregistered?))
          "a resolved (hence registered) variant is always actually removed"))))

(deftest gated-error-tool-slot-pins-caller
  ;; Regression for rf2-c52j0. Pre-fix, `assert-writes-allowed` hardcoded
  ;; `:tool "register-variant"` in its error payload, so the two other
  ;; callers (`unregister-variant`, `record-as-variant`) returned a gated
  ;; error whose `:structuredContent :tool` slot LIED about its origin.
  ;; This test pins the slot to the actual tool name at each callsite so
  ;; the lie cannot regress.
  (testing "gated error's :structuredContent :tool matches the invoking tool"
    (is (false? (config/writes-allowed?))
        "fixture must leave the gate closed for this test")
    (let [r (invoke "register-variant" {:variant-id "story.button/danger"
                                        :body {:doc "x"}})]
      (is (error? r))
      (is (true? (-> r :structuredContent :gated)))
      (is (= "register-variant" (-> r :structuredContent :tool))))
    (let [r (invoke "unregister-variant" {:variant-id "story.button/primary"})]
      (is (error? r))
      (is (true? (-> r :structuredContent :gated)))
      (is (= "unregister-variant" (-> r :structuredContent :tool))))
    (let [r (invoke "record-as-variant" {:variant-id  "story.button/primary"
                                         :write-back  true})]
      (is (error? r))
      (is (true? (-> r :structuredContent :gated)))
      (is (= "record-as-variant" (-> r :structuredContent :tool))))))

;; ---------------------------------------------------------------------------
;; record-as-variant (rf2-luhdu)
;;
;; The recorder normally captures events off the trace bus; for these tests
;; we drive `recorder/record-event!` directly during the tool's blocking
;; window via a worker thread so the assertions exercise the start →
;; capture → snippet plumbing without needing a live trace emitter.
;; ---------------------------------------------------------------------------

(defn- drive-events-during-recording
  "Spawn a worker thread that polls for the recorder's open window, then
  pushes `events` once `recording?` flips true. Replaces the
  `Thread/sleep delay-ms` race the original helper had (TE5, rf2-36upq)
  — on a slow CI runner the worker could either fire BEFORE
  `start-recording!` (events dropped, capture truncated) or AFTER
  `stop-recording!` (same outcome). Polling `recording?` from the worker
  end means we never depend on a sleep window outlasting the tool.

  The worker's poll has a hard 5s upper bound — well past any
  realistic tool latency — and bails silently if the recorder never
  opens (the test asserts `:recorded-event-count` on the result and
  catches a truncated capture there).

  Returns the worker thread so a test can `.join` (with timeout) when
  it needs determinism on whether the worker has finished pushing.
  Most callers just spawn-and-forget — the `:duration-ms` window the
  tool sleeps in is more than long enough for the polled push.

  rf2-l2cn5d (EP-0017): the 2-arity `(drive-events-during-recording
  events cofx-vec)` pushes a parallel, index-aligned vector of captured
  flat `:rf.cofx` maps (the framework `:rf/time-ms` + any provided facts
  a dispatch carried) via the recorder's 2-arity `record-event!`, so a
  test can exercise the capture→write-back cofx-preservation path the
  same way the live trace listener does."
  ([events] (drive-events-during-recording events nil))
  ([events cofx-vec]
   (let [cofx-vec (vec (or cofx-vec []))]
     (doto (Thread.
             ^Runnable
             (fn []
               ;; Poll `recording?` with a 1ms park between probes — far
               ;; finer-grained than the original 20ms sleep. Bails after
               ;; 5s if the recorder never opens (a tool bug; the test
               ;; assertions will catch it).
               (let [deadline (+ (System/nanoTime) (* 5 1000000000))]
                 (loop []
                   (cond
                     (recorder/recording?)
                     (doseq [[i ev] (map-indexed vector events)]
                       (recorder/record-event! ev (get cofx-vec i)))

                     (< (System/nanoTime) deadline)
                     (do (Thread/sleep 1)
                         (recur))

                     ;; Timed out; bail. The test's :recorded-event-count
                     ;; assertion will surface the miss.
                     :else nil)))))
       (.setDaemon true)
       (.start)))))

(deftest record-as-variant-not-found
  (testing "unknown source variant ⇒ tool-execution error"
    (let [r (invoke "record-as-variant" {:variant-id "story.nope/missing"})]
      (is (error? r))
      (is (re-find #"not found" (-> r :content first :text))))))

(deftest record-as-variant-missing-arg
  (testing "missing :variant-id ⇒ tool-execution error"
    (let [r (invoke "record-as-variant" {})]
      (is (error? r))
      (is (re-find #"variant-id" (-> r :content first :text))))))

(deftest record-as-variant-zero-duration-empty-capture
  (testing "duration 0 with no in-flight dispatches ⇒ empty public :script snippet"
    ;; rf2-7mj4z: the recorder's `gen-play-snippet` emits the PUBLIC
    ;; `:script {:auto-run? true :script [...]}` body, NOT the transitional
    ;; `:play-script` spelling. With zero captured events the inner
    ;; `:script` vector is empty.
    (let [r (invoke "record-as-variant" {:variant-id "story.button/primary"})
          s (:structuredContent r)]
      (is (success? r))
      (is (= :story.button/primary (:variant-id s)))
      (is (= 0 (:recorded-event-count s)))
      (is (false? (:written-back? s)))
      (is (string? (:play-snippet s)))
      (is (re-find #":script" (:play-snippet s)))
      (is (not (re-find #":play-script" (:play-snippet s)))
          "the snippet emits the public :script slot, not the transitional :play-script (rf2-7mj4z)")
      (is (re-find #":script\s+\[\]" (:play-snippet s)))
      (is (re-find #":story\.button/primary" (:play-snippet s))))))

(deftest record-as-variant-captures-events-during-window
  (testing "events pushed during the blocking window land in :captured"
    (drive-events-during-recording [[:counter/inc] [:counter/by 7]])
    (let [r (invoke "record-as-variant"
                    {:variant-id  "story.button/primary"
                     :duration-ms 100})
          s (:structuredContent r)]
      (is (success? r))
      (is (= 2 (:recorded-event-count s)))
      (is (= [[:counter/inc] [:counter/by 7]] (:captured s)))
      (is (re-find #":counter/inc" (:play-snippet s)))
      (is (re-find #":counter/by 7" (:play-snippet s))))))

(deftest record-as-variant-write-back-gated-by-default
  (testing "write-back true with allow-writes? false ⇒ gated error"
    (is (false? (config/writes-allowed?)))
    (let [r (invoke "record-as-variant" {:variant-id  "story.button/primary"
                                         :write-back  true})]
      (is (error? r))
      (is (re-find #"Write surface disabled" (-> r :content first :text)))
      (is (true? (-> r :structuredContent :gated))))))

(deftest record-as-variant-write-back-overwrites-source
  (testing "write-back true with gate open re-registers the source variant"
    (config/set-allow-writes! true)
    (drive-events-during-recording [[:counter/inc] [:counter/inc]])
    (let [r (invoke "record-as-variant"
                    {:variant-id  "story.button/primary"
                     :duration-ms 100
                     :write-back  true})
          s (:structuredContent r)
          n (:recorded-event-count s)]
      (is (success? r))
      (is (true? (:written-back? s)))
      (is (= :story.button/primary (:new-variant-id s)))
      (is (pos? n) "the recorder captured at least one event")
      ;; rf2-7mj4z: write-back assocs the PUBLIC `:script` authoring slot,
      ;; which `reg-variant*` lowers to the shipping `:play-script` slot —
      ;; so the STORED body (`variant->edn`) reads `:play-script` carrying
      ;; the captured events as a LIVE, replayable script (NOT the dead
      ;; `:play` slot the schema dropped in rf2-0wrud, which no runner
      ;; executes). Each captured event becomes a `[:dispatch ...]` step.
      ;; The exact count is derived from `:recorded-event-count` because
      ;; the live-recorder capture races the :duration-ms window.
      (let [body (story/variant->edn :story.button/primary)]
        (is (nil? (:play body))
            "the legacy dead :play slot must NOT be written")
        (is (= {:script    (vec (repeat n [:dispatch [:counter/inc]]))
                :auto-run? true}
               (:play-script body))
            "the stored body carries the lowered :play-script shipping slot")
        ;; Pre-existing body keys survive (e.g. :doc).
        (is (= "Primary button." (:doc body)))))))

(deftest record-as-variant-write-back-new-id
  (testing ":new-variant-id lands the capture under a fresh id"
    (config/set-allow-writes! true)
    (drive-events-during-recording [[:counter/inc]])
    (let [r (invoke "record-as-variant"
                    {:variant-id     "story.button/primary"
                     :new-variant-id "story.button/recorded"
                     :duration-ms    100
                     :write-back     true})
          s (:structuredContent r)
          n (:recorded-event-count s)]
      (is (success? r))
      (is (true? (:written-back? s)))
      (is (= :story.button/recorded (:new-variant-id s)))
      (is (pos? n) "the recorder captured at least one event")
      ;; rf2-7mj4z: write-back assocs the public `:script` slot, lowered to
      ;; the shipping `:play-script` slot in storage (count derived from
      ;; `:recorded-event-count` — capture races the :duration-ms window).
      (is (= {:script (vec (repeat n [:dispatch [:counter/inc]])) :auto-run? true}
             (:play-script (story/variant->edn :story.button/recorded))))
      (is (nil? (:play (story/variant->edn :story.button/recorded))))
      ;; Source variant is untouched.
      (is (nil? (:play-script (story/variant->edn :story.button/primary))))
      (is (nil? (:play (story/variant->edn :story.button/primary)))))))

(deftest record-as-variant-write-back-replaces-existing-play-script
  (testing "rf2-f4e1xs: write-back against a source variant that ALREADY
            carries a play surface (a lowered :play-script) must REPLACE
            it, not fail validation. The variant schema rejects a body
            carrying more than one of :script / :play-script / :plays, so
            the pre-fix `(assoc body :script …)` on such a source produced
            a body with BOTH :script and :play-script and the registrar
            rejected it. write-back! now drops the prior play surface
            first."
    (config/set-allow-writes! true)
    ;; A source variant authored with the PUBLIC :script slot — the
    ;; registrar lowers it to the shipping :play-script on store, so the
    ;; stored body the recorder reads back already carries :play-script.
    (story/reg-variant :story.button/with-script
      {:doc    "Has an existing play surface."
       :args   {:label "Scripted"}
       :tags   #{:dev}
       :script [[:dispatch [:noop]]]})
    (is (some? (:play-script (story/variant->edn :story.button/with-script)))
        "precondition: the source variant stores a lowered :play-script")
    (drive-events-during-recording [[:counter/inc] [:counter/inc]])
    (let [r (invoke "record-as-variant"
                    {:variant-id  "story.button/with-script"
                     :duration-ms 100
                     :write-back  true})
          s (:structuredContent r)
          n (:recorded-event-count s)]
      (is (success? r)
          "write-back over an existing :play-script succeeds (was :isError pre-fix)")
      (is (true? (:written-back? s)))
      (is (= :story.button/with-script (:new-variant-id s)))
      (is (pos? n) "the recorder captured at least one event")
      (let [body (story/variant->edn :story.button/with-script)]
        ;; The recorded script REPLACES the prior play surface — the body
        ;; carries the recorded steps under the lowered :play-script slot,
        ;; NOT the old [:dispatch [:noop]] script.
        (is (= {:script    (vec (repeat n [:dispatch [:counter/inc]]))
                :auto-run? true}
               (:play-script body))
            "the stored :play-script is the recorded body, replacing the old script")
        (is (nil? (:script body))
            "no stray public :script slot survives (single play surface)")
        ;; Unrelated body keys survive.
        (is (= "Has an existing play surface." (:doc body)))))))

(deftest record-as-variant-write-back-replaces-existing-plays
  (testing "rf2-f4e1xs: write-back against a source variant carrying the
            multi-play :plays surface also replaces it cleanly rather than
            failing the mutual-exclusion check."
    (config/set-allow-writes! true)
    (story/reg-variant :story.button/with-plays
      {:doc   "Has a :plays multi-play surface."
       :args  {:label "Multiplay"}
       :tags  #{:dev}
       :plays [{:name "happy path" :script [[:dispatch [:noop]]]}]})
    (is (some? (:plays (story/variant->edn :story.button/with-plays)))
        "precondition: the source variant stores :plays")
    (drive-events-during-recording [[:counter/inc]])
    (let [r (invoke "record-as-variant"
                    {:variant-id  "story.button/with-plays"
                     :duration-ms 100
                     :write-back  true})
          s (:structuredContent r)
          n (:recorded-event-count s)]
      (is (success? r)
          "write-back over an existing :plays succeeds (was :isError pre-fix)")
      (is (true? (:written-back? s)))
      (is (pos? n))
      (let [body (story/variant->edn :story.button/with-plays)]
        (is (= {:script    (vec (repeat n [:dispatch [:counter/inc]]))
                :auto-run? true}
               (:play-script body))
            "the recorded :script lowers to :play-script, replacing :plays")
        (is (nil? (:plays body))
            "the prior :plays surface is dropped (single play surface)")))))

(deftest record-as-variant-write-back-round-trips-and-replays
  (testing "rf2-50jzf: a written-back recording's :script body ACTUALLY replays"
    ;; The headline acceptance criterion for rf2-50jzf — the previous
    ;; write-back wrote a dead `:play` slot the schema dropped in
    ;; rf2-0wrud, so a round-tripped recording silently never replayed.
    ;; This test closes the loop end-to-end: record real dispatches →
    ;; write them back under a fresh variant → run THAT variant through
    ;; the MCP `run-variant` tool → assert the captured dispatches fired
    ;; against the frame's app-db (proving the slot the runner executes
    ;; is the one write-back wrote).
    ;;
    ;; The live-recorder capture races the tool's :duration-ms window
    ;; (the worker may push 1–N of the driven events before
    ;; `stop-recording!` closes the window), so the EXPECTED replay count
    ;; is derived from `:recorded-event-count` rather than hard-coded —
    ;; the invariant under test is "`:n` after replay == number of
    ;; captured `:test/bump` steps", which holds regardless of how many
    ;; the race captured. We require at least one capture so the replay
    ;; path is genuinely exercised.
    (config/set-allow-writes! true)
    ;; A real event handler whose effect is observable in app-db.
    (rf/reg-event :test/bump (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (drive-events-during-recording [[:test/bump] [:test/bump] [:test/bump]])
    (let [rec (invoke "record-as-variant"
                      {:variant-id     "story.button/primary"
                       :new-variant-id "story.button/replayed"
                       :duration-ms    100
                       :write-back     true})
          s   (:structuredContent rec)
          n   (:recorded-event-count s)]
      (is (success? rec))
      (is (true? (:written-back? s)))
      (is (pos? n) "the recorder captured at least one :test/bump step")
      ;; The written-back body carries the LIVE play body under the lowered
      ;; `:play-script` shipping slot — n `[:dispatch [:test/bump]]` steps,
      ;; NOT the dead `:play` slot.
      (let [body (story/variant->edn :story.button/replayed)]
        (is (nil? (:play body)) "no dead :play slot is written")
        (is (= {:script    (vec (repeat n [:dispatch [:test/bump]]))
                :auto-run? true}
               (:play-script body))
            "write-back stores the lowered :play-script slot, one :dispatch step per captured event"))
      ;; Run the written-back variant: the replayed :test/bump dispatches
      ;; must land on the frame's app-db. This is the load-bearing
      ;; distinction — a dead `:play` slot is never executed by any runner,
      ;; so :n would be nil; the live `:script` slot replays, so :n is
      ;; a positive count.
      ;;
      ;; The recorder emits `:dispatch` (ASYNC) steps (per
      ;; play-export/event->step), and on the JVM run-variant queues them
      ;; via `rf/dispatch*` without an inter-step yield (runner_events
      ;; run-loop! :clj branch). The single-threaded interop executor
      ;; drains the router queue asynchronously, so the `:app-db` slot
      ;; in `run-variant`'s wire response captures the value at the
      ;; moment the play-promise resolves — which can race the async
      ;; drain (observed CI flake). The qualitative pin under test is
      ;; "the play-script ACTUALLY replays" — settled `:n` is read out-
      ;; of-band by polling the frame's app-db until the drain lands at
      ;; least one `:test/bump`, bounded by a 2-second deadline so a
      ;; genuine dead-slot regression still surfaces as a failure
      ;; rather than a hang. The dead-slot bug would leave :n nil
      ;; forever; the live-slot fix lands :n as a positive integer no
      ;; greater than the captured count.
      (let [run    (invoke "run-variant" {:variant-id "story.button/replayed"})
            run-n  (let [deadline (+ (System/nanoTime) (* 2 1000000000))]
                     (loop []
                       (let [v (:n (rf/app-db-value :story.button/replayed))]
                         (cond
                           (and (integer? v) (pos? v)) v
                           (< (System/nanoTime) deadline)
                           (do (Thread/sleep 1) (recur))
                           :else v))))]
        (is (success? run))
        (is (and (integer? run-n) (pos? run-n) (<= run-n n))
            (str "the recording replayed — :test/bump dispatches incremented :n to "
                 (pr-str run-n) " (captured " n "); a dead :play slot would leave :n nil"))))))

(deftest record-as-variant-preserves-captured-cofx
  (testing "rf2-l2cn5d (EP-0017): a captured :rf.cofx envelope rides into BOTH
            the rendered snippet AND the written-back :script body, so replay
            re-presents the recorded recordable coeffects (provided facts +
            the framework :rf/time-ms) rather than restamping"
    (config/set-allow-writes! true)
    ;; Drive a single dispatch carrying a recorded flat :rf.cofx map.
    (drive-events-during-recording
      [[:counter/inc]]
      [{:rf/time-ms 1781078400123 :counter/delta 7}])
    (let [r (invoke "record-as-variant"
                    {:variant-id     "story.button/primary"
                     :new-variant-id "story.button/cofx-recorded"
                     :duration-ms    100
                     :write-back     true})
          s (:structuredContent r)
          n (:recorded-event-count s)]
      (is (success? r))
      (is (true? (:written-back? s)))
      (is (pos? n) "the recorder captured at least one event")
      ;; The written-back :script body carries the cofx envelope on each
      ;; recorded dispatch step — [:dispatch [:counter/inc] {:rf.cofx {…}}].
      (let [body  (story/variant->edn :story.button/cofx-recorded)
            steps (:script (:play-script body))]
        (is (every? (fn [step]
                      (and (= :dispatch (first step))
                           (= [:counter/inc] (second step))
                           (= {:rf/time-ms 1781078400123 :counter/delta 7}
                              (:rf.cofx (nth step 2 nil)))))
                    steps)
            "every written-back dispatch step carries the recorded :rf.cofx map"))
      ;; The rendered snippet text surfaces the cofx envelope too (it is
      ;; rendered FROM the scrubbed events + parallel cofx).
      (is (re-find #":rf.cofx" (:play-snippet s))
          "the snippet text carries the :rf.cofx envelope")
      (is (re-find #":rf/time-ms 1781078400123" (:play-snippet s))
          "the recorded :rf/time-ms is surfaced verbatim (always-safe per EP-0017)"))))

(deftest record-as-variant-no-cofx-is-byte-identical
  (testing "rf2-l2cn5d: a recording with no captured coeffects writes the
            pre-EP-0017 2-element dispatch steps (zero ceremony)"
    (config/set-allow-writes! true)
    (drive-events-during-recording [[:counter/inc] [:counter/inc]])
    (let [r (invoke "record-as-variant"
                    {:variant-id     "story.button/primary"
                     :new-variant-id "story.button/no-cofx"
                     :duration-ms    100
                     :write-back     true})
          s (:structuredContent r)
          n (:recorded-event-count s)]
      (is (success? r))
      (is (pos? n))
      (let [body (story/variant->edn :story.button/no-cofx)]
        (is (= {:script    (vec (repeat n [:dispatch [:counter/inc]]))
                :auto-run? true}
               (:play-script body))
            "no captured cofx → bare 2-element dispatch steps, unchanged from pre-fix")
        (is (not (re-find #":rf.cofx" (:play-snippet s)))
            "the snippet carries no :rf.cofx slot when nothing was captured")))))

(deftest record-as-variant-snippet-honours-doc-and-alias
  (testing ":doc and :alias flow into the rendered snippet"
    (let [r (invoke "record-as-variant"
                    {:variant-id "story.button/primary"
                     :doc        "Recorded counter run."
                     :alias      "s"})
          snippet (-> r :structuredContent :play-snippet)]
      (is (success? r))
      (is (re-find #"\(s/reg-variant" snippet))
      (is (re-find #"Recorded counter run\." snippet))
      ;; Default :extends = source variant id.
      (is (re-find #":extends :story\.button/primary" snippet)))))

;; ---------------------------------------------------------------------------
;; :origin :story-mcp stamping (rf2-7dnct)
;;
;; Per spec/Cross-Cutting-Designs.md §5 — every write surface tags its
;; writes with a single `:origin` keyword so post-mortem queries can
;; answer "who wrote this?". Story-mcp's `register-variant` and
;; `record-as-variant` (write-back path) stamp `:origin :story-mcp` onto
;; the registered variant body. The keyword value is pinned in
;; `config/origin`; the registrar's open-shape variant schema admits
;; the extra slot.
;; ---------------------------------------------------------------------------

(deftest origin-const-is-story-mcp
  (testing "the origin keyword is `:story-mcp` per Cross-Cutting-Designs §5"
    (is (= :story-mcp config/origin))))

(deftest register-variant-stamps-origin-story-mcp
  (testing "register-variant writes a body carrying :origin :story-mcp"
    (config/set-allow-writes! true)
    (let [r    (invoke "register-variant"
                       {:variant-id "story.button/origin-map"
                        :body       {:doc  "Origin-stamped via map body."
                                     :args {:label "Stamped"}}})
          body (story/variant->edn :story.button/origin-map)]
      (is (success? r))
      (is (= :story-mcp (:origin body))
          "registered body must carry :origin :story-mcp")
      ;; Caller-supplied keys survive alongside the stamp.
      (is (= "Origin-stamped via map body." (:doc body)))
      (is (= {:label "Stamped"} (:args body))))))

(deftest register-variant-edn-string-body-stamps-origin
  (testing "EDN-string body also lands :origin :story-mcp on the registered body"
    (config/set-allow-writes! true)
    (let [r    (invoke "register-variant"
                       {:variant-id "story.button/origin-edn"
                        :body       "{:doc \"Origin via EDN.\" :args {:label \"OK\"}}"})
          body (story/variant->edn :story.button/origin-edn)]
      (is (success? r))
      (is (= :story-mcp (:origin body))))))

(deftest register-variant-overrides-caller-supplied-origin
  (testing "story-mcp owns the :origin slot — caller-supplied values are clobbered"
    (config/set-allow-writes! true)
    (let [r    (invoke "register-variant"
                       {:variant-id "story.button/origin-override"
                        :body       {:doc    "Caller tried to claim :app origin."
                                     :origin :app}})
          body (story/variant->edn :story.button/origin-override)]
      (is (success? r))
      (is (= :story-mcp (:origin body))
          "the write surface owns the :origin slot; an agent cannot claim a different origin"))))

(deftest record-as-variant-write-back-stamps-origin
  (testing "record-as-variant write-back lands :origin :story-mcp on the new body"
    (config/set-allow-writes! true)
    (drive-events-during-recording [[:counter/inc]])
    (let [r    (invoke "record-as-variant"
                       {:variant-id  "story.button/primary"
                        :duration-ms 100
                        :write-back  true})
          n    (-> r :structuredContent :recorded-event-count)
          body (story/variant->edn :story.button/primary)]
      (is (success? r))
      (is (true? (-> r :structuredContent :written-back?)))
      (is (pos? n) "the recorder captured at least one event")
      (is (= :story-mcp (:origin body))
          "write-back body must carry :origin :story-mcp")
      ;; Pre-existing body keys + the captured play body (stored under the
      ;; lowered :play-script shipping slot) still land (step count derived
      ;; from `:recorded-event-count` — capture races the :duration-ms window).
      (is (= "Primary button." (:doc body)))
      (is (= {:script (vec (repeat n [:dispatch [:counter/inc]])) :auto-run? true}
             (:play-script body)))
      (is (nil? (:play body))))))

(deftest record-as-variant-write-back-new-id-stamps-origin
  (testing ":new-variant-id write-back also carries :origin :story-mcp"
    (config/set-allow-writes! true)
    (drive-events-during-recording [[:counter/inc]])
    (let [r    (invoke "record-as-variant"
                       {:variant-id     "story.button/primary"
                        :new-variant-id "story.button/origin-recorded"
                        :duration-ms    100
                        :write-back     true})
          body (story/variant->edn :story.button/origin-recorded)]
      (is (success? r))
      (is (= :story-mcp (:origin body))))))

(deftest record-as-variant-without-write-back-does-not-touch-source
  (testing "without :write-back the source variant is untouched (no :origin landed)"
    ;; This pins the contract: the write happens only on the write-back
    ;; branch. The :origin stamp is the marker of a write — its absence
    ;; on a non-write-back call is the marker of a no-write.
    (let [_    (invoke "record-as-variant" {:variant-id "story.button/primary"})
          body (story/variant->edn :story.button/primary)]
      (is (nil? (:origin body))
          "no write happened, so no :origin stamp lands on the source body"))))

;; ---------------------------------------------------------------------------
;; Server dispatcher (initialize, tools/list, tools/call, error paths)
;; ---------------------------------------------------------------------------

(deftest dispatch-initialize-handshake
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 1 :method "initialize"
                :params {:protocolVersion "2025-06-18"
                         :capabilities {}
                         :clientInfo {:name "test-client" :version "0.0.0"}}})]
    (is (= 1 (:id resp)))
    (is (= config/protocol-version (-> resp :result :protocolVersion)))
    (is (= config/server-name (-> resp :result :serverInfo :name)))
    (is (map? (-> resp :result :capabilities)))))

(deftest dispatch-tools-list-returns-registry
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 2 :method "tools/list"})
        ts (-> resp :result :tools)]
    (is (= 2 (:id resp)))
    (is (vector? ts))
    (is (= (count registry/tool-registry) (count ts)))
    (is (some #(= "list-stories" (:name %)) ts))))

(deftest dispatch-tools-call-happy
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 3 :method "tools/call"
                :params {:name "get-story"
                         :arguments {:story-id "story.button"}}})]
    (is (= 3 (:id resp)))
    (is (some? (:result resp)))
    (is (not (true? (-> resp :result :isError))))))

(deftest dispatch-tools-call-unknown-tool
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 4 :method "tools/call"
                :params {:name "unknown-tool" :arguments {}}})]
    (is (= vocab/code-method-not-found (-> resp :error :code))
        "an unknown tool yields a protocol-level method-not-found")))

(deftest dispatch-tools-call-non-map-arguments-is-invalid-params
  (testing "rf2-2zym5e: a non-map `arguments` (scalar / array / string) is a
            params-CONTAINER shape failure ⇒ -32602 invalid-params, NOT a
            -32603 internal-error (the prior behaviour, where (keys non-map)
            threw and was caught into a misleading server fault)"
    (doseq [[label bad-args] [["a string" "foo"]
                              ["an array" ["a" "b"]]
                              ["a number" 42]
                              ["a boolean" true]]]
      (let [resp (server/dispatch
                   {:jsonrpc "2.0" :id 41 :method "tools/call"
                    :params {:name "list-tags" :arguments bad-args}})]
        (is (= vocab/code-invalid-params (-> resp :error :code))
            (str bad-args " (" label ") ⇒ -32602 invalid-params"))
        (is (not= vocab/code-internal-error (-> resp :error :code))
            (str bad-args " must NOT surface as -32603 internal-error"))
        (is (re-find #"arguments" (-> resp :error :message))
            "the error names the offending `arguments` container")))))

(deftest dispatch-tools-call-absent-arguments-is-ok
  (testing "rf2-2zym5e: omitted `arguments` is legal (no args) — the guard
            only rejects a PRESENT non-map container"
    (let [resp (server/dispatch
                 {:jsonrpc "2.0" :id 42 :method "tools/call"
                  :params {:name "list-tags"}})]
      (is (nil? (:error resp))
          "absent :arguments dispatches cleanly (treated as empty args)")
      (is (some? (:result resp))))))

(deftest dispatch-malformed-envelope
  (testing "missing jsonrpc version yields invalid-request"
    (let [resp (server/dispatch {:method "tools/list" :id 5})]
      (is (= vocab/code-invalid-request (-> resp :error :code))))))

(deftest dispatch-unknown-method
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 6 :method "nope/whatever"})]
    (is (= vocab/code-method-not-found (-> resp :error :code)))
    (is (re-find #"nope/whatever" (-> resp :error :message)))))

(deftest dispatch-notification-no-response
  (testing "a JSON-RPC notification yields nil (no response)"
    (is (nil? (server/dispatch
                {:jsonrpc "2.0" :method "notifications/initialized"})))))

(deftest dispatch-ping-empty-result
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 7 :method "ping"})]
    (is (= {} (:result resp)))))

(deftest dispatch-shutdown-empty-result
  ;; `handle-shutdown` (server.cljc:110-115) — some agent hosts emit a
  ;; `shutdown` request before closing stdin (it's not in the 2025-06-18
  ;; spec, but the server accepts + responds so a well-behaved client
  ;; doesn't see a timeout). Pins the empty-result happy arm + that the
  ;; id is echoed back per JSON-RPC.
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 8 :method "shutdown"})]
    (is (= 8 (:id resp)) "shutdown echoes the request id")
    (is (= {} (:result resp)) "shutdown returns an empty success result")
    (is (nil? (:error resp)) "shutdown is a success, not an error")))

(deftest dispatch-tools-call-non-string-name-invalid-params
  ;; `handle-tools-call` (server.cljc:95-96) — the dispatcher's ONLY
  ;; protocol-level invalid-params emit. A `tools/call` whose `:name` is
  ;; not a string (numeric, or omitted entirely) must yield -32602
  ;; invalid-params, distinct from the method-not-found path that an
  ;; unknown *string* tool name takes (dispatch-tools-call-unknown-tool).
  (testing "numeric :name → invalid-params"
    (let [resp (server/dispatch
                 {:jsonrpc "2.0" :id 9 :method "tools/call"
                  :params {:name 42 :arguments {}}})]
      (is (= 9 (:id resp)))
      (is (= vocab/code-invalid-params (-> resp :error :code))
          "a non-string tool name is a protocol-level invalid-params, not method-not-found")
      (is (re-find #"name" (-> resp :error :message)))))
  (testing "omitted :name → invalid-params"
    (let [resp (server/dispatch
                 {:jsonrpc "2.0" :id 10 :method "tools/call"
                  :params {:arguments {}}})]
      (is (= vocab/code-invalid-params (-> resp :error :code))
          "a missing tool name is invalid-params (nil is not a string)"))))

;; ---------------------------------------------------------------------------
;; Lifecycle state enforcement (rf2-e6knrq finding 1)
;;
;; Before a successful `initialize`, the dispatcher MUST accept only
;; `initialize` + `ping` (and any notification); every other request —
;; `tools/list`, `tools/call`, `shutdown`, an unknown method — MUST be a
;; protocol-level `-32600 invalid-request`. A malformed or hostile client
;; must not be able to enumerate or invoke tools before the handshake.
;; These deftests drive BOTH the direct stateful `dispatch` (2-arity) AND
;; the full `run-loop!` stdio order, per the bead's acceptance criteria.
;; ---------------------------------------------------------------------------

(deftest dispatch-rejects-tools-list-before-initialize
  (testing "tools/list before initialize → -32600 invalid-request"
    (let [state (server/new-lifecycle-state)
          resp  (server/dispatch state {:jsonrpc "2.0" :id 1 :method "tools/list"})]
      (is (= 1 (:id resp)) "the request id is echoed")
      (is (= vocab/code-invalid-request (-> resp :error :code))
          "enumerating tools pre-handshake is a protocol violation, not a success")
      (is (nil? (:result resp)) "no tool registry leaks before initialize")
      (is (re-find #"(?i)initialize" (-> resp :error :message))
          "the error names the missing handshake step"))))

(deftest dispatch-rejects-tools-call-before-initialize
  (testing "tools/call before initialize → -32600 invalid-request (no tool runs)"
    (let [state (server/new-lifecycle-state)
          resp  (server/dispatch state {:jsonrpc "2.0" :id 2 :method "tools/call"
                                        :params {:name "get-story-instructions"
                                                 :arguments {}}})]
      (is (= vocab/code-invalid-request (-> resp :error :code))
          "a tool call pre-handshake is refused at the protocol layer")
      (is (nil? (:result resp)) "the tool handler never ran — no result envelope"))))

(deftest dispatch-rejects-shutdown-and-unknown-before-initialize
  (testing "shutdown + unknown methods are also gated pre-initialize"
    (let [state (server/new-lifecycle-state)]
      (is (= vocab/code-invalid-request
             (-> (server/dispatch state {:jsonrpc "2.0" :id 3 :method "shutdown"}) :error :code))
          "shutdown is not in the pre-init open set")
      (is (= vocab/code-invalid-request
             (-> (server/dispatch state {:jsonrpc "2.0" :id 4 :method "nope/whatever"}) :error :code))
          "an unknown method pre-init is invalid-request (the gate runs before the method case)"))))

(deftest dispatch-allows-initialize-and-ping-before-handshake
  (testing "initialize + ping are the only requests accepted pre-handshake"
    (let [state (server/new-lifecycle-state)
          ping  (server/dispatch state {:jsonrpc "2.0" :id 5 :method "ping"})]
      (is (= {} (:result ping)) "ping is a stateless liveness probe — allowed pre-init")
      (is (nil? (:error ping)))
      ;; ping does NOT mark the session initialized.
      (is (false? (:initialized? @state)) "ping must not flip the lifecycle flag")
      (let [init (server/dispatch state {:jsonrpc "2.0" :id 6 :method "initialize"
                                         :params {:protocolVersion "2025-06-18"}})]
        (is (= config/protocol-version (-> init :result :protocolVersion))
            "initialize succeeds and negotiates the protocol version")
        (is (true? (:initialized? @state))
            "a successful initialize flips the session to initialized")))))

(deftest dispatch-allows-tools-immediately-after-initialize
  ;; The deliberate relaxation: the session is ready the MOMENT the
  ;; initialize response is built — we do NOT require
  ;; `notifications/initialized` first (the reference-SDK posture; a
  ;; client pipelining initialize + tools/list must not race a refusal).
  (testing "tools/list works right after initialize, WITHOUT notifications/initialized"
    (let [state (server/new-lifecycle-state)]
      (server/dispatch state {:jsonrpc "2.0" :id 7 :method "initialize"
                              :params {:protocolVersion "2025-06-18"}})
      (let [resp (server/dispatch state {:jsonrpc "2.0" :id 8 :method "tools/list"})]
        (is (vector? (-> resp :result :tools))
            "tools/list dispatches immediately post-initialize (relaxation, no notification required)")))))

(deftest dispatch-notifications-accepted-in-any-lifecycle-posture
  (testing "a notification (no id) is a silent no-op before AND after initialize"
    (let [state (server/new-lifecycle-state)]
      (is (nil? (server/dispatch state {:jsonrpc "2.0" :method "notifications/initialized"}))
          "notifications/initialized pre-handshake is accepted (no response, no error)")
      ;; It must NOT have flipped the flag — only `initialize` does that.
      (is (false? (:initialized? @state))
          "the relaxation: notifications/initialized is informational, initialize is the trigger")
      (server/dispatch state {:jsonrpc "2.0" :id 9 :method "initialize"
                              :params {:protocolVersion "2025-06-18"}})
      (is (nil? (server/dispatch state {:jsonrpc "2.0" :method "notifications/initialized"}))
          "the same notification post-handshake is still a silent no-op"))))

(deftest run-loop-rejects-pre-initialize-tool-calls
  (testing "stdio order: a tools/call as the FIRST frame is refused, then initialize unlocks"
    ;; Frame 1: tools/call BEFORE any initialize → must be -32600.
    ;; Frame 2: initialize → success.
    ;; Frame 3: tools/list AFTER initialize → success (registry surfaces).
    (let [in-text (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                       "\"params\":{\"name\":\"list-tags\",\"arguments\":{}}}\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\","
                       "\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}\n")
          reader  (java.io.BufferedReader. (java.io.StringReader. in-text))
          sw      (java.io.StringWriter.)
          err     (java.io.StringWriter.)]
      (binding [*err* err]
        (server/run-loop! reader sw))
      (let [out-lines (filter seq (clojure.string/split-lines (.toString sw)))
            frames    (mapv #(cheshire.core/parse-string % true) out-lines)]
        (is (= 3 (count frames)) "three responses (the pre-init refusal + initialize + tools/list)")
        (is (= 1 (:id (nth frames 0))))
        (is (= vocab/code-invalid-request (-> (nth frames 0) :error :code))
            "the pre-initialize tools/call is refused at the protocol layer over stdio")
        (is (nil? (-> (nth frames 0) :result))
            "no tool registry / result leaked before the handshake")
        (is (= 2 (:id (nth frames 1))))
        (is (= config/protocol-version (-> (nth frames 1) :result :protocolVersion))
            "initialize succeeds as the second frame")
        (is (= 3 (:id (nth frames 2))))
        (is (vector? (-> (nth frames 2) :result :tools))
            "tools/list now surfaces the registry — the handshake unlocked the surface")))))

(deftest run-loop-allows-ping-before-initialize
  (testing "stdio order: a ping as the FIRST frame is answered (liveness probe)"
    (let [in-text (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\","
                       "\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n")
          reader  (java.io.BufferedReader. (java.io.StringReader. in-text))
          sw      (java.io.StringWriter.)
          err     (java.io.StringWriter.)]
      (binding [*err* err]
        (server/run-loop! reader sw))
      (let [frames (->> (clojure.string/split-lines (.toString sw))
                        (filter seq)
                        (mapv #(cheshire.core/parse-string % true)))]
        (is (= 2 (count frames)))
        (is (= {} (:result (nth frames 0))) "ping answered with the empty result pre-init")
        (is (= config/protocol-version (-> (nth frames 1) :result :protocolVersion)))))))

;; ---------------------------------------------------------------------------
;; Run-loop end-to-end (in-memory)
;; ---------------------------------------------------------------------------

(deftest run-loop-handles-multi-frame-session
  (testing "handshake + tools/list + tools/call over a pipe of frames"
    (let [in-text (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n"
                       "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list-tags\",\"arguments\":{}}}\n")
          reader (java.io.BufferedReader. (java.io.StringReader. in-text))
          sw     (java.io.StringWriter.)]
      (server/run-loop! reader sw)
      ;; Split written output into frames, parse each.
      (let [out-lines (filter seq (clojure.string/split-lines (.toString sw)))
            frames    (mapv #(cheshire.core/parse-string % true) out-lines)]
        ;; Three responses (initialize, tools/list, tools/call) — the
        ;; `notifications/initialized` notification yielded no response.
        (is (= 3 (count frames)))
        (is (= 1 (:id (nth frames 0))))
        (is (= 2 (:id (nth frames 1))))
        (is (= 3 (:id (nth frames 2))))
        (is (= config/protocol-version
               (-> (nth frames 0) :result :protocolVersion)))
        (is (vector? (-> (nth frames 1) :result :tools)))
        (is (-> (nth frames 2) :result :content vector?))))))

(deftest run-loop-survives-parse-error
  (testing "a malformed frame produces a parse-error response; loop continues"
    (let [in-text (str "{this is garbage\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}\n")
          reader (java.io.BufferedReader. (java.io.StringReader. in-text))
          sw     (java.io.StringWriter.)
          ;; Silent-on-success (rf2-try1x): the server logs the parse
          ;; error to *err* per MCP stdio rules; capture it into a
          ;; throwaway buffer so the green test run stays at the
          ;; canonical 3-line shape.
          err    (java.io.StringWriter.)]
      (binding [*err* err]
        (server/run-loop! reader sw))
      (let [out-lines (filter seq (clojure.string/split-lines (.toString sw)))
            frames    (mapv #(cheshire.core/parse-string % true) out-lines)]
        (is (= 2 (count frames)))
        (is (= vocab/code-parse-error (-> (nth frames 0) :error :code)))
        (is (= 9 (:id (nth frames 1))))))))

;; ---------------------------------------------------------------------------
;; Boot config
;; ---------------------------------------------------------------------------

(deftest boot-config-defaults-locked-down
  (testing "boot config defaults allow-writes? to false"
    (let [cfg (#'server/parse-args [])]
      (is (nil? (:allow-writes? cfg))))))

(deftest boot-config-allow-writes-flag
  (testing "--allow-writes flips the gate"
    (let [cfg (#'server/parse-args ["--allow-writes"])]
      (is (true? (:allow-writes? cfg))))))

(deftest boot-config-unknown-flag-logged-and-ignored
  ;; `parse-args` (server.cljc:236-237) — the log-and-ignore branch for
  ;; an unrecognised flag. The MCP spec doesn't define CLI conventions,
  ;; so the parser is deliberately permissive: an unknown flag is logged
  ;; to *err* and skipped, leaving the config map untouched. Surrounding
  ;; recognised flags must still parse.
  (testing "an unknown flag leaves the config map empty"
    ;; Silent-on-success (rf2-try1x): the log line goes to *err*; capture
    ;; it so the green run keeps the canonical reporter shape.
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (is (= {} (#'server/parse-args ["--no-such-flag"]))
            "unknown flag is ignored — cfg stays empty"))
      (is (re-find #"unknown CLI flag" (.toString err))
          "unknown flag is logged to *err*")))
  (testing "an unknown flag does not swallow an adjacent recognised flag"
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (let [cfg (#'server/parse-args ["--bogus" "--allow-writes" "--also-bogus"])]
          (is (true? (:allow-writes? cfg))
              "recognised flag still parses around the ignored ones"))))))

(deftest read-version-contract
  ;; `read-version` (config.cljc:60-70) — feeds `:serverInfo :version` in
  ;; the `initialize` handshake. Best-effort: reads `VERSION` off the
  ;; classpath, falling back to "dev" when the resource is absent
  ;; (uberjar deploys, REPL hosts). The story-mcp test classpath carries
  ;; no `VERSION` resource (`:paths ["src"]` + test `:extra-paths ["test"]`,
  ;; neither of which ship one), so this run exercises the "dev" fallback.
  (testing "returns a non-blank trimmed string"
    (let [v (config/read-version)]
      (is (string? v))
      (is (not (clojure.string/blank? v)))
      (is (= v (clojure.string/trim v)) "result is already trimmed")))
  (testing "falls back to \"dev\" when no VERSION resource is on the classpath"
    (is (nil? (io/resource "VERSION"))
        "precondition: the test classpath ships no VERSION resource")
    (is (= "dev" (config/read-version))
        "absent-resource path returns the documented \"dev\" fallback"))
  (testing "the handshake's :serverInfo :version is fed by read-version"
    (let [resp (server/dispatch
                 {:jsonrpc "2.0" :id 11 :method "initialize"
                  :params {:protocolVersion config/protocol-version}})]
      (is (= (config/read-version)
             (-> resp :result :serverInfo :version))
          "initialize echoes read-version into :serverInfo :version"))))

;; ---------------------------------------------------------------------------
;; Wire-boundary token-budget cap (rf2-rvyzy / rf2-zavp5).
;;
;; The cap is applied at `invoke-tool` egress — the cumulative
;; `:text`-slot byte count is compared against `:max-tokens` (default
;; `overflow/default-max-tokens`; `0` disables). Over-budget responses
;; are replaced with `{:rf.mcp/overflow {...}}` per the cross-MCP shape
;; pinned in `re-frame.mcp-base.overflow/overflow-payload`.
;; ---------------------------------------------------------------------------

(defn- overflow-marker?
  "Does `result` carry the `{:rf.mcp/overflow {:limit :reached ...}}`
  marker shape? Both the structured-content and the text slot should
  reflect it. The text slot prints via `pr-str` which renders the
  namespaced key as the `#:rf.mcp{:overflow ...}` namespace-map form
  (round-trippable EDN); `read-string`-ing it round-trips to the same
  key. We check the structured shape and that the text slot is the
  round-trippable EDN form."
  [result]
  (and (map? result)
       (= :reached (get-in result [:structuredContent vocab/overflow-key :limit]))
       (string? (-> result :content first :text))
       (let [round-tripped (try (edn/read-string
                                  (-> result :content first :text))
                                (catch Throwable _ nil))]
         (= :reached (get-in round-tripped [vocab/overflow-key :limit])))))

(deftest cap-fires-when-response-exceeds-budget
  (testing "get-story-instructions response is large enough to exceed a 1-token cap"
    (let [r (wire-pipeline/invoke-tool "get-story-instructions" {:max-tokens 1})]
      (is (overflow-marker? r))
      (let [body (get-in r [:structuredContent vocab/overflow-key])]
        (is (= 1 (:cap-tokens body)))
        (is (= "get-story-instructions" (:tool body)))
        (is (pos? (:token-count body)))
        (is (string? (:hint body)))))))

(deftest cap-zero-disables-the-cap
  (testing "`:max-tokens 0` bypasses the cap; the full payload returns intact"
    (let [r (wire-pipeline/invoke-tool "get-story-instructions" {:max-tokens 0})]
      (is (not (overflow-marker? r)))
      (is (clojure.string/includes? (-> r :content first :text)
                                    "re-frame2-story authoring conventions"))))
  (testing "default cap (no `:max-tokens` arg) leaves a small response intact"
    (let [r (wire-pipeline/invoke-tool "list-tags" {})]
      (is (not (overflow-marker? r))))))

(deftest cap-negative-max-tokens-rejected-not-overflow-lockout
  ;; rf2-5rdit — a negative `:max-tokens` resolves to a
  ;; `{:rf.mcp/invalid-arg {...}}` rejection, NOT a negative cap. The
  ;; handler is never dispatched and the result is an actionable
  ;; `isError: true` error — not the `:rf.mcp/overflow` lock-out a
  ;; negative ceiling used to cause (over-cap? trips on any non-negative
  ;; token count against a negative cap, so even a tiny response was
  ;; replaced by the overflow marker). The wire `:minimum 0` schema is the
  ;; first line of defence for validating hosts; this is the egress
  ;; backstop for hosts that don't validate.
  (testing "negative :max-tokens surfaces an :rf.mcp/invalid-arg error, not overflow"
    (let [r    (wire-pipeline/invoke-tool "list-tags" {:max-tokens -1})
          body (get-in r [:structuredContent vocab/invalid-arg-key])]
      (is (true? (:isError r))
          "negative max-tokens surfaces as an isError tool-result")
      (is (not (overflow-marker? r))
          "NOT the overflow lock-out a negative cap used to cause")
      (is (some? body) "result carries the :rf.mcp/invalid-arg rejection payload")
      (is (= :max-tokens (:arg body)))
      (is (= -1 (:value body)))
      (is (re-find #"(?i)0 disables" (:hint body))
          "hint states the disable sentinel so the agent's retry is correct")
      ;; The text slot mirrors the structured payload (round-trips to EDN).
      (is (= :max-tokens
             (get-in (edn/read-string (-> r :content first :text))
                     [vocab/invalid-arg-key :arg]))))))

(deftest cap-honours-default-when-omitted
  (testing "absent `:max-tokens` falls back to `overflow/default-max-tokens` (5000)"
    ;; A tiny payload like `list-tags` is well under 5K tokens; verify
    ;; the cap does not trip on routine reads.
    (let [r (wire-pipeline/invoke-tool "list-tags" {})
          tokens (base-cap/sum-text-tokens test-io r)]
      (is (not (overflow-marker? r)))
      (is (< tokens overflow/default-max-tokens)))))

(deftest cap-marker-shape-is-mcp-base-overflow
  (testing "marker is byte-identical to mcp-base/overflow-payload's shape"
    (let [r (wire-pipeline/invoke-tool "get-story-instructions" {:max-tokens 1})
          body (get-in r [:structuredContent vocab/overflow-key])]
      (is (= #{:limit :token-count :cap-tokens :tool :hint}
             (set (keys body)))))))

(deftest every-tool-schema-accepts-max-tokens
  (testing "every tool's input schema carries the `:max-tokens` slot"
    (doseq [t registry/tool-registry]
      (is (contains? (-> t :inputSchema :properties) :max-tokens)
          (str "tool " (:name t) " missing :max-tokens slot"))
      (is (= "integer" (-> t :inputSchema :properties :max-tokens :type))
          (str "tool " (:name t) " :max-tokens slot is not integer-typed")))))

;; ---------------------------------------------------------------------------
;; Wire-egress privacy posture (rf2-73wuj)
;;
;; Per spec/Tool-Pair.md §Direct-read privacy posture (lines 544-566) every
;; pair-shaped tool that surfaces a live `:app-db` slice MUST route the
;; value through `re-frame.core/elide-wire-value` before egress, with
;; off-box defaults (`:rf.size/include-sensitive?` and
;; `:rf.size/include-large?` both default false). The cross-MCP
;; `:include-sensitive` arg (rf2-vw4sq) is the documented escape hatch.
;;
;; These tests pin the contract at the story-mcp surface: a sensitive
;; slot declared through app-schema metadata on the variant's frame must
;; surface as `:rf/redacted` in the tool's response `:app-db` slot by
;; default, and as the raw value when the caller opts in via
;; `:include-sensitive true`. Assertion records carrying the top-level
;; `:sensitive? true` stamp must be dropped by default and included
;; when opted in.
;;
;; Pattern mirrors `implementation/schemas/test/re_frame/
;; schemas_sensitive_test.clj`: schema metadata is the canonical
;; per-slot declaration surface; story-mcp verifies its wire egress
;; helper refreshes and consumes those declarations.
;; ---------------------------------------------------------------------------

(defn- frame-container [variant-id]
  ;; `re-frame.frame/app-db-container` returns the substrate container (an
  ;; atom under plain-atom); the user-facing `rf/app-db-value` returns
  ;; the dereferenced VALUE. Tests need the container so they can write
  ;; the elision-registry slot back.
  ((requiring-resolve 're-frame.frame/app-db-container) variant-id))

(defn- read-frame-db [variant-id]
  ((requiring-resolve 're-frame.substrate.adapter/read-container)
   (frame-container variant-id)))

(defn- replace-frame-db! [variant-id new-db]
  ;; EP-0001 (rf2-adwcv6): write the app-db PARTITION via swap-frame-db! —
  ;; `frame/app-db-container` is now a read-only projection over the one
  ;; physical frame-state container.
  ((requiring-resolve 're-frame.frame/swap-frame-db!)
   variant-id (constantly new-db)))

(defn- ensure-variant-frame!
  "Allocate `variant-id`'s frame if it doesn't already exist. The fixture
  only `reg-variant`s the variant body; the variant's *frame* is
  allocated lazily by `run-variant` / `preview-variant`. The privacy
  tests need the frame up-front so they can write into its app-db
  before the tool call runs."
  [variant-id]
  (when (nil? (frame-container variant-id))
    (rf/reg-frame variant-id
                  {:doc        (str "test frame for " variant-id)
                   :rf/story?  true
                   :rf/variant variant-id})))

(defn- destroy-variant-frame!
  "Tear down `variant-id`'s frame so the next test starts fresh. The
  `frames` atom is per-process (not cleared by `story/clear-all!`); a
  seeded `:rf.story/assertions` slot would otherwise bleed across
  tests."
  [variant-id]
  (when (some? (frame-container variant-id))
    ((requiring-resolve 're-frame.frame/destroy-frame!) variant-id)))

(defn- classification-config
  "The accumulated `reg-frame` classification map for `variant-id` —
  `{:sensitive {:app-db [..]} :large {:app-db [..]}}`, omitting an empty
  block."
  [variant-id]
  (let [{:keys [sensitive large]} (get @declared-class variant-id)]
    (cond-> {}
      (seq sensitive) (assoc :sensitive {:app-db (vec sensitive)})
      (seq large)     (assoc :large {:app-db (vec large)}))))

(defn- declare-classification!
  "Accumulate `path` under `kind` (`:sensitive` / `:large`) for
  `variant-id` and install the full classification onto the variant's
  frame, in a way that survives `run-variant`'s fresh-run boundary
  (rf2-294yq5.3 → rf2-d2r3um).

  Two seams, mirroring `seed-app-db!`:

  1. DIRECT INSTALL — `frame-class/install!` against the live frame, so a
     non-lifecycle reader (`read-failures`, the direct-`elide-app-db` unit
     tests) sees the declaration immediately. The frame container must
     exist (the elision registry lives in its runtime-db partition), so we
     `ensure-variant-frame!` first.

  2. `:events` RE-INSTALL — append a `[::reapply-frame-class frame config]`
     step to the variant body's `:events` so each fresh run re-installs the
     classification onto the reset frame (phase 2, after allocation/reset).
     Without this the declarations are wiped when `ensure-fresh-frame!`
     resets the pre-run frame's runtime-db to `{}`, and the wire-egress
     walker (which runs at the END of the run) finds no sensitive/large
     paths to redact. The step is idempotent — re-installing REPLACES the
     `:source :frame` entries. Skipped when `variant-id` is not a
     registered variant (nothing to append to). Because the config carries
     ALL accumulated paths, only the LATEST appended re-install step
     matters — the prior steps re-install a subset of the same config."
  [variant-id kind path]
  (swap! declared-class update-in [variant-id kind] (fnil conj #{}) (vec path))
  (ensure-variant-frame! variant-id)
  (let [config (classification-config variant-id)]
    (frame-class/install! variant-id (frame-class/validate+extract variant-id config))
    (when-let [body (story-registrar/handler-meta :variant variant-id)]
      (story-registrar/reg-variant*
        variant-id
        (update body :events (fnil conj [])
                [::reapply-frame-class variant-id config])))))

(defn- declare-sensitive!
  "Declare `path` frame-owned `:sensitive` on the named variant's frame
  (EP-0015 §8). The egress walker returns `:rf/redacted` for a
  frame-declared-sensitive slot."
  [variant-id path]
  (declare-classification! variant-id :sensitive path))

(defn- declare-large!
  "Declare `path` frame-owned `:large` on the named variant's frame
  (EP-0015 §8). The egress walker substitutes the slot's value with the
  `:rf.size/large-elided` marker — the leaf the `:elided-large` indicator
  counts (rf2-koq5m)."
  [variant-id path]
  (declare-classification! variant-id :large path))

(defn- seed-app-db!
  "Establish `db` as `variant-id`'s frame app-db for the privacy tests.

  Two seams, because two classes of reader consume the result:

  1. DIRECT WRITE (`replace-frame-db!`) — the immediate frame app-db. The
     `read-failures` tests read the `:rf.story/assertions` accumulator
     directly (no lifecycle re-run), so they need the value present on the
     live frame right now.

  2. `:db-seed` REGISTRATION — for the lifecycle readers
     (`run-variant` / `preview-variant`). rf2-294yq5.3 added a fresh-run
     boundary: `run-phase-0!` `destroy!`s any pre-existing frame BEFORE
     allocation so a run never inherits a prior run's (or an externally
     hand-written) app-db. That correctly wipes the direct write above. So
     for the seeded state to survive INTO the run result it must be
     re-established BY the lifecycle on every fresh run — which is exactly
     the `:db-seed` rung (`runtime/run-db-seed!`, phase 0.5, applied after
     allocation). We merge `db` onto the registered variant body's
     `:db-seed` slot so each run re-seeds the fresh frame. The merge
     preserves the rest of the registered body (`:args` / `:doc` / …).

  Skips the `:db-seed` registration when `variant-id` is not a registered
  variant (nothing to merge into) — the direct write still applies."
  [variant-id db]
  (ensure-variant-frame! variant-id)
  (replace-frame-db! variant-id db)
  (when-let [body (story-registrar/handler-meta :variant variant-id)]
    (story-registrar/reg-variant*
      variant-id
      (update body :db-seed merge db))))

(defmacro ^:private with-clean-frame
  "Bind `vid` to `variant-kw`, run `body` against a clean variant frame,
  and tear the frame down on exit so the next test sees no residue. The
  `frames` atom is per-process and survives `story/clear-all!`; the
  seeded `:rf.story/assertions` and `[:rf.runtime/elision]` runtime-db slots would
  otherwise leak."
  [[vid variant-kw] & body]
  `(let [~vid ~variant-kw]
     (try ~@body
          (finally (destroy-variant-frame! ~vid)))))

(deftest preview-variant-app-db-redacts-sensitive-by-default
  (testing "sensitive path in variant frame's app-db lands :rf/redacted in the response"
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "preview-variant" {:variant-id "story.button/primary"})
            s (:structuredContent r)]
        (is (success? r))
        (is (= :rf/redacted (get-in s [:app-db :secret]))
            "the :secret slot is redacted by the wire-egress walker")
        (is (= "ok" (get-in s [:app-db :public]))
            "non-sensitive slots survive the walk")))))

(deftest preview-variant-app-db-includes-sensitive-when-opted-in
  (testing ":include-sensitive true forwards the raw value through the walker"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "preview-variant" {:variant-id "story.button/primary"
                                         :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= "TOPSECRET" (get-in s [:app-db :secret]))
            "opt-in surfaces the raw sensitive value")))))

(deftest run-variant-app-db-redacts-sensitive-by-default
  (testing "run-variant's :app-db slot routes through the wire-egress walker"
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "run-variant" {:variant-id "story.button/primary"})
            s (:structuredContent r)]
        (is (success? r))
        ;; The registry slot at `[:rf.runtime/elision :declarations]` survives
        ;; the run (Story doesn't clear it). The redaction must show
        ;; in the response.
        (is (= :rf/redacted (get-in s [:app-db :secret]))
            "the :secret slot is redacted at egress")))))

(deftest run-variant-app-db-includes-sensitive-when-opted-in
  (testing "run-variant's :include-sensitive true forwards the raw value"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "run-variant" {:variant-id "story.button/primary"
                                     :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= "TOPSECRET" (get-in s [:app-db :secret])))))))

(deftest elide-app-db-include?-true-bypasses-walker
  ;; rf2-brehq — the `include? true` branch of `egress/elide-app-db`
  ;; skips `elide-wire-value` entirely. Pins behavioural equivalence
  ;; with the previous walking-then-no-edit implementation:
  ;;
  ;;   1. The return is the input db itself (`identical?`) — the walker
  ;;      would have rebuilt every map / vector via `reduce-kv` and
  ;;      `mapv`, breaking identity even though value would be
  ;;      preserved. The bypass returns the original reference.
  ;;
  ;;   2. The return is value-equal to running the walker with both
  ;;      inclusion knobs flipped (the previous behaviour). Future
  ;;      refactors that reintroduce walker work on this branch will
  ;;      still pass (2) but break (1) — the load-bearing perf invariant
  ;;      this bead fixes.
  ;;
  ;; Calls `egress/elide-app-db` directly so the test pins the helper's
  ;; contract, not a downstream tool's composition of it. Avoids
  ;; coupling to `run-variant`'s lifecycle behaviour.
  (testing ":include? true returns the input ref unchanged AND matches walker-with-both-knobs-on"
    (with-clean-frame [vid :story.button/primary]
      (let [db {:public    "ok"
                :secret    "TOPSECRET"
                :nested    {:also-secret "DEEP"
                            :public-leaf 42}
                :coll      [:a :b :c]
                :empty-map {}}]
        ;; Populate the elision registry on vid's frame so the walker
        ;; has something to consult — the bypass-equivalence proof only
        ;; works if the walker WOULD have visited sensitive paths.
        (seed-app-db! vid db)
        (declare-sensitive! vid [:secret])
        (declare-sensitive! vid [:nested :also-secret])
        (let [frame-db (read-frame-db vid)
              bypass   ((requiring-resolve 're-frame.story-mcp.tools.egress/elide-app-db)
                        frame-db vid true)
              walked   (rf/elide-wire-value frame-db
                                            {:frame                      vid
                                             :rf.size/include-sensitive? true
                                             :rf.size/include-large?     true})]
          (is (identical? frame-db bypass)
              "include? true returns the SAME object — no walker rebuild")
          (is (= walked bypass)
              "bypass output value-equals the previous walking-then-no-edit output")
          (is (= "TOPSECRET" (get bypass :secret))
              "top-level sensitive slot rides through")
          (is (= "DEEP" (get-in bypass [:nested :also-secret]))
              "nested sensitive slot rides through"))))))

;; ---------------------------------------------------------------------------
;; rf2-ee38b.17 (headline P1) — derived-tree wire-egress redaction.
;;
;; `elide-app-db` scrubs the `:app-db` slot by PATH. But the same sensitive
;; value reappears, verbatim, in `:rendered-hiccup` / `:effective-args` /
;; `:snapshot` — at a hiccup-tree position the path-based walker can't reach.
;; `scrub-rendered` closes that leak by VALUE: it collects the live values at
;; the frame's declared-`:sensitive?` paths and substitutes any matching leaf
;; in the derived tree with `:rf/redacted`. These tests pin that a redacted
;; value MUST NOT appear in the rendered-hiccup wire output, and that the
;; `:include-sensitive` opt-out forwards it.
;; ---------------------------------------------------------------------------

(defn- tree-contains?
  "Deep membership: true iff `needle` appears anywhere as a value inside
  `tree` (walking maps/vectors/sets/seqs). Used to assert a secret has
  been scrubbed OUT of a rendered tree regardless of its position."
  [tree needle]
  (cond
    (= tree needle) true
    (map? tree)     (boolean (some (fn [[k v]] (or (tree-contains? k needle)
                                                   (tree-contains? v needle)))
                                   tree))
    (coll? tree)    (boolean (some #(tree-contains? % needle) tree))
    :else           false))

(defn- tree-contains-marker?
  "Deep search for a `:rf.size/large-elided` marker map anywhere in `tree`
  (rf2-9o5ixx). The marker is `{:rf.size/large-elided {…}}`; this asserts a
  large value was elided regardless of which slot/position it landed in."
  [tree]
  (cond
    (and (map? tree) (contains? tree :rf.size/large-elided)) true
    (map? tree)  (boolean (some (fn [[k v]] (or (tree-contains-marker? k)
                                                (tree-contains-marker? v)))
                                tree))
    (coll? tree) (boolean (some tree-contains-marker? tree))
    :else        false))

(deftest scrub-rendered-redacts-sensitive-value-in-derived-tree
  (testing "a value at a declared-sensitive app-db path is redacted wherever it appears in the derived tree"
    (with-clean-frame [vid :story.button/primary]
      (let [db    {:public "ok" :token "TOPSECRET"}
            ;; A rendered hiccup tree that embeds the sensitive value at
            ;; a non-app-db position (an attribute value + a text node).
            hiccup [:div {:class "card"}
                    [:input {:type "password" :value "TOPSECRET"}]
                    [:span "label: " "TOPSECRET"]]]
        (seed-app-db! vid db)
        (declare-sensitive! vid [:token])
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid false)]
          (is (not (tree-contains? out "TOPSECRET"))
              "the sensitive value MUST NOT survive anywhere in the derived tree")
          (is (tree-contains? out :rf/redacted)
              "matching leaves are replaced with the :rf/redacted sentinel")
          (is (tree-contains? out "label: ")
              "non-sensitive leaves are preserved")
          (is (= "card" (get-in out [1 :class]))
              "benign attribute values survive untouched"))))))

(deftest scrub-rendered-include?-true-forwards-raw-value
  (testing ":include? true bypasses the value-redaction walk entirely"
    (with-clean-frame [vid :story.button/primary]
      (let [db     {:token "TOPSECRET"}
            hiccup [:input {:value "TOPSECRET"}]]
        (seed-app-db! vid db)
        (declare-sensitive! vid [:token])
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid true)]
          (is (identical? hiccup out)
              "include? true returns the input tree unchanged (no walk)")
          (is (tree-contains? out "TOPSECRET")
              "the opt-out forwards the raw sensitive value"))))))

(deftest scrub-rendered-no-declarations-is-noop
  (testing "with no declared-sensitive paths the tree is returned unwalked"
    (with-clean-frame [vid :story.button/primary]
      (let [db     {:public "ok"}
            hiccup [:span "ok"]]
        (seed-app-db! vid db)
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid false)]
          (is (identical? hiccup out)
              "no secrets ⇒ no walk, input ref returned"))))))

;; ---------------------------------------------------------------------------
;; rf2-g7cd1 — value-redaction over-scrub guard.
;;
;; The value-based walk substitutes EVERY derived-tree leaf `=` a
;; declared-sensitive value. When a sensitive path holds a short/common
;; scalar (`0`, `200`, `:ok`), naive matching scrubs every benign leaf that
;; merely equals it — degrading the agent's view AND leaking the secret's
;; value-CLASS. `sensitive-values` guards that: a candidate value that ALSO
;; appears, verbatim, in the POST-elision `:app-db` (the actual wire bytes —
;; hardened from the raw db in rf2-f3kf7) is dropped from the secret set,
;; because the path-based `:app-db` egress already ships that value (it is
;; provably already disclosed, so excluding it leaks nothing new). These tests
;; pin BOTH the precision win AND the fail-SAFE invariant (a value that is
;; UNIQUELY secret — absent from the elided db — stays redacted).
;; ---------------------------------------------------------------------------

(deftest scrub-rendered-short-scalar-aliased-to-public-path-is-not-over-scrubbed
  (testing "a short sensitive scalar that ALSO sits at a non-sensitive app-db path is provably public — benign leaves equal to it survive"
    (with-clean-frame [vid :story.button/primary]
      ;; :http-status is sensitive and holds 0; :retry-count holds the SAME
      ;; scalar 0 at a NON-sensitive path, so 0 is already shipped verbatim
      ;; by the path-based :app-db egress — it is not a protectable secret.
      (let [db     {:http-status 0          ; sensitive path
                    :retry-count 0           ; benign path, same scalar
                    :public      "ok"}
            ;; Derived tree with many benign leaves equal to 0 (a tab-index,
            ;; an aria level, a count) that the naive walk would have scrubbed.
            hiccup [:ul {:tabindex 0}
                    [:li {:data-level 0} "first"]
                    [:li {:data-level 0} "second"]]]
        (seed-app-db! vid db)
        (declare-sensitive! vid [:http-status])
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid false)]
          (is (not (tree-contains? out :rf/redacted))
              "0 appears at a non-sensitive app-db path, so it is dropped from the secret set — no benign 0 leaf is over-scrubbed")
          (is (= 0 (get-in out [1 :tabindex]))
              "benign 0 attribute values survive untouched")
          (is (= 0 (get-in out [2 1 :data-level]))
              "benign 0 leaves deep in the tree survive")
          (is (identical? hiccup out)
              "with no remaining secrets the tree is returned unwalked"))))))

(deftest scrub-rendered-uniquely-secret-short-scalar-stays-redacted
  (testing "FAIL-SAFE: a short scalar that sits ONLY at the sensitive path (no benign alias) is still redacted — no under-scrub"
    (with-clean-frame [vid :story.button/primary]
      ;; The sensitive scalar 7 is UNIQUE to the sensitive path — it does NOT
      ;; appear at any non-sensitive app-db path, so the guard must keep it in
      ;; the secret set. (Over-scrub of any benign 7 elsewhere is the
      ;; irreducible value-aliasing residual and is fail-SAFE.)
      (let [db     {:pin    7
                    :public "ok"}
            hiccup [:input {:type "password" :value 7}]]
        (seed-app-db! vid db)
        (declare-sensitive! vid [:pin])
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid false)]
          (is (not (tree-contains? out 7))
              "the uniquely-secret scalar 7 MUST NOT survive on the wire (no under-scrub)")
          (is (= :rf/redacted (get-in out [1 :value]))
              "the sensitive leaf is replaced with the :rf/redacted sentinel"))))))

(deftest scrub-rendered-genuine-long-secret-still-redacted
  (testing "FAIL-SAFE regression: a genuinely-sensitive long secret on its path is still fully redacted (the guard does not weaken distinctive-secret redaction)"
    (with-clean-frame [vid :story.button/primary]
      (let [db     {:public "ok"
                    :token  "sk-live-9f8a7b6c5d4e3f2a1b0c-TOPSECRET"}
            hiccup [:div {:class "card"}
                    [:input {:type "password"
                             :value "sk-live-9f8a7b6c5d4e3f2a1b0c-TOPSECRET"}]
                    [:span "token: " "sk-live-9f8a7b6c5d4e3f2a1b0c-TOPSECRET"]]]
        (seed-app-db! vid db)
        (declare-sensitive! vid [:token])
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid false)]
          (is (not (tree-contains? out "sk-live-9f8a7b6c5d4e3f2a1b0c-TOPSECRET"))
              "the distinctive long secret MUST NOT survive anywhere in the derived tree")
          (is (tree-contains? out :rf/redacted)
              "matching leaves are replaced with the :rf/redacted sentinel")
          (is (= "card" (get-in out [1 :class]))
              "benign attribute values survive untouched"))))))

;; ---------------------------------------------------------------------------
;; rf2-f3kf7 — :large?-blind under-scrub (the headline fix).
;;
;; The g7cd1 guard dropped any candidate that ALSO appeared at a non-sensitive
;; app-db path, on the premise that elide-app-db ships such a value verbatim.
;; That premise is FALSE for a :large?-declared non-sensitive path:
;; elide-wire-value replaces the slot with the :rf.size/large-elided marker, so
;; the value is NOT on the wire — yet the old guard walked the RAW db, saw the
;; secret at the :large? position, classified it public, and dropped it from
;; the secret set, leaking it VERBATIM into the derived trees. The fix
;; classifies "public" against the POST-elision :app-db (the actual wire
;; bytes), so a value masked by ANY elision class stays redacted. These tests
;; pin the no-under-scrub invariant on the in-scope MCP egress.
;; ---------------------------------------------------------------------------

(deftest scrub-rendered-secret-aliased-into-large-subtree-stays-redacted
  (testing "FAIL-SAFE (rf2-f3kf7): a secret at a sensitive path that ALSO lives inside a :large?-declared subtree MUST stay redacted — the :large? slot is the marker on the wire, NOT the verbatim value, so it does not license dropping the secret"
    (with-clean-frame [vid :story.button/primary]
      ;; The repro: the token sits at sensitive [:auth :token] AND nested in
      ;; [:cache :blob], which is :large?-declared. The :app-db egress ships
      ;; [:cache :blob] as the :rf.size/large-elided marker (token NOT
      ;; disclosed), so the token must remain a protected secret everywhere.
      (let [token  "sk-live-DISTINCTIVE-TOPSECRET-9f8a7b6c"
            db     {:public "ok"
                    :auth   {:token token}
                    :cache  {:blob {:size 9001 :payload token}}}
            ;; Derived trees re-embed the token at non-app-db positions
            ;; (narrative :db-before/:after, rendered hiccup, snapshot).
            hiccup [:div [:input {:type "password" :value token}]
                    [:span "narrative db-before: " token]]]
        (seed-app-db! vid db)
        (declare-sensitive! vid [:auth :token])
        (declare-large! vid [:cache :blob])
        ;; Sanity: the :app-db egress masks the :large? subtree (token gone)
        ;; AND redacts the sensitive path — so the token is NOT on the
        ;; :app-db wire via either route, which is exactly why the derived
        ;; tree must keep it redacted.
        (let [elide-app-db (requiring-resolve 're-frame.story-mcp.tools.egress/elide-app-db)
              wire-db      (elide-app-db db vid false)]
          (is (not (tree-contains? wire-db token))
              "the token is NOT shipped verbatim in the :app-db slot (large-masked + sensitive-redacted)")
          (is (= :rf/redacted (get-in wire-db [:auth :token]))
              "the sensitive path is redacted in the wire :app-db")
          (is (contains? (get-in wire-db [:cache :blob]) :rf.size/large-elided)
              "the :large? subtree is replaced by the marker in the wire :app-db"))
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid false)]
          (is (not (tree-contains? out token))
              "the secret MUST NOT survive anywhere in the derived tree (no under-scrub)")
          (is (tree-contains? out :rf/redacted)
              "matching leaves are replaced with the :rf/redacted sentinel")
          (is (= "narrative db-before: " (get-in out [2 1]))
              "benign leaves are preserved"))))))

(deftest scrub-rendered-seq-indexed-sensitive-stays-redacted
  (testing "FAIL-SAFE (rf2-f3kf7 secondary): a seq-indexed :sensitive? declaration [:tokens 0] keeps its element redacted in derived trees — the set/seq same-path walk no longer misclassifies it public"
    (with-clean-frame [vid :story.button/primary]
      ;; [:tokens 0] is sensitive and is a UNIQUE value (no benign alias on
      ;; the wire). The slot is a SEQ (list), the shape the old guard
      ;; mis-walked: `collect-public-values!` walked set/seq elements at the
      ;; PARENT path `[:tokens]`, so `under-prefix? [:tokens 0] [:tokens]`
      ;; returned false (prefix longer than the walk-path) — the element was
      ;; treated as non-governed/public and dropped from the secret set =>
      ;; under-scrub. The fix classifies against the POST-elision db, where
      ;; `elide-wire-value`'s walk-seq HAS indexed + redacted the element, so
      ;; it never appears at a public wire position and stays in the set.
      (let [secret "uniq-seq-secret-TOPSECRET"
            db     {:public "ok"
                    :tokens (list secret "second-public-token")}
            hiccup [:ul [:li {:data-idx 0} secret]
                    [:li {:data-idx 1} "second-public-token"]]]
        (seed-app-db! vid db)
        (declare-sensitive! vid [:tokens 0])
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid false)]
          (is (not (tree-contains? out secret))
              "the seq-indexed secret MUST NOT survive in the derived tree (no under-scrub)")
          (is (tree-contains? out :rf/redacted)
              "the matching leaf is replaced with the :rf/redacted sentinel")
          (is (tree-contains? out "second-public-token")
              "the non-sensitive sibling element survives untouched"))))))

(deftest scrub-rendered-large-aliased-public-scalar-not-over-scrubbed
  (testing "g7cd1 NOT regressed (rf2-f3kf7): a short sensitive scalar aliased to a PLAIN non-sensitive path is still un-over-scrubbed — the fix only tightens against ELIDED positions, not plain ones"
    (with-clean-frame [vid :story.button/primary]
      ;; The g7cd1 common case must survive the elided-db reclassification:
      ;; 0 sits at sensitive :http-status AND at the PLAIN non-sensitive
      ;; :retry-count (no :large?, no :sensitive?), so 0 IS on the wire
      ;; verbatim and must NOT scrub benign 0 leaves.
      (let [db     {:http-status 0     ; sensitive
                    :retry-count 0      ; plain non-sensitive, same scalar
                    :public      "ok"}
            hiccup [:ul {:tabindex 0}
                    [:li {:data-level 0} "first"]
                    [:li {:data-level 0} "second"]]]
        (seed-app-db! vid db)
        (declare-sensitive! vid [:http-status])
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid false)]
          (is (not (tree-contains? out :rf/redacted))
              "0 is on the wire at the plain :retry-count path, so it is dropped from the secret set — no benign 0 over-scrubbed (g7cd1 preserved)")
          (is (= 0 (get-in out [1 :tabindex]))
              "benign 0 attribute values survive untouched")
          (is (= 0 (get-in out [2 1 :data-level]))
              "benign 0 leaves deep in the tree survive"))))))

;; ---------------------------------------------------------------------------
;; rf2-9o5ixx — frame-declared :large values must elide in derived slots, not
;; only in :app-db. EP-0015 treats sensitive + large as peer egress axes; the
;; derived-tree scrubber redacted only the sensitive axis, so a :large blob
;; re-keyed into :rendered-hiccup / :snapshot / evidence / explain value slots
;; crossed the off-box boundary RAW, and the :elided-large count under-reported.
;; ---------------------------------------------------------------------------

(deftest scrub-rendered-large-value-elides-in-derived-tree
  (testing "rf2-9o5ixx: a value at a declared-:large app-db path is elided to
            :rf.size/large-elided wherever it is re-keyed into the derived tree
            — not only in the :app-db slot"
    (with-clean-frame [vid :story.button/primary]
      ;; A large blob lives at [:blob]; the view renders it into [:pre blob].
      ;; The :app-db egress elides [:blob] to the marker, but the rendered copy
      ;; must elide too rather than crossing raw.
      (let [blob   (vec (range 5000))           ; a big, unique payload
            db     {:public "ok" :blob blob}
            hiccup [:div [:pre blob] [:span "label"]]]
        (seed-app-db! vid db)
        (declare-large! vid [:blob])
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid false)]
          (is (not (tree-contains? out blob))
              "the large blob MUST NOT survive verbatim in the derived tree")
          (is (tree-contains-marker? out)
              "the re-keyed large value is replaced with the :rf.size/large-elided marker")
          (is (tree-contains? out "label")
              "benign leaves are preserved"))))))

(deftest scrub-frame-value-large-value-elides
  (testing "rf2-9o5ixx: the non-live captured/runtime scrub (scrub-frame-value)
            also elides a declared-:large value re-keyed into its payload"
    (with-clean-frame [vid :story.button/primary]
      (let [blob   (vec (range 5000))
            db     {:public "ok" :blob blob}
            ;; a captured-event-style payload that echoes the blob
            tree   [[:evt/load {:payload blob}]]]
        (seed-app-db! vid db)
        (declare-large! vid [:blob])
        (let [scrub-frame-value (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-frame-value)
              out               (scrub-frame-value tree vid false)]
          (is (not (tree-contains? out blob))
              "the large blob MUST NOT survive verbatim in the captured payload")
          (is (tree-contains-marker? out)
              "the re-keyed large value is elided to the :rf.size/large-elided marker"))))))

(deftest scrub-explain-values-large-value-elides
  (testing "rf2-9o5ixx: explain value slots elide a declared-:large value that
            is re-surfaced into :effective-args / :network etc."
    (with-clean-frame [vid :story.button/primary]
      (let [blob    (vec (range 5000))
            db      {:public "ok" :blob blob}
            explain {:effective-args {:rows blob}     ; runtime value slot
                     :source-chain   [:a :b]}]        ; plan-STRUCTURE slot (public)
        (seed-app-db! vid db)
        (declare-large! vid [:blob])
        (let [scrub-explain-values (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-explain-values)
              out                  (scrub-explain-values explain vid [:effective-args] false)]
          (is (not (tree-contains? (:effective-args out) blob))
              "the large blob MUST NOT survive in the explain value slot")
          (is (tree-contains-marker? (:effective-args out))
              "the re-keyed large value is elided to the :rf.size/large-elided marker")
          (is (= [:a :b] (:source-chain out))
              "plan-STRUCTURE slots are untouched (intentionally public)"))))))

(deftest scrub-rendered-large-include?-true-forwards-raw
  (testing "rf2-9o5ixx: include? true forwards the raw large value (the
            trusted-local opt-out covers BOTH axes)"
    (with-clean-frame [vid :story.button/primary]
      (let [blob   (vec (range 5000))
            db     {:blob blob}
            hiccup [:pre blob]]
        (seed-app-db! vid db)
        (declare-large! vid [:blob])
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid true)]
          (is (identical? hiccup out) "include? true returns the input unchanged")
          (is (tree-contains? out blob) "the opt-out forwards the raw large value"))))))

(deftest scrub-rendered-sensitive-wins-over-large
  (testing "rf2-9o5ixx: a value that is BOTH sensitive and large redacts to
            :rf/redacted (sensitive wins), never the large marker"
    (with-clean-frame [vid :story.button/primary]
      ;; The same blob sits at a sensitive path AND a large path.
      (let [blob   (vec (range 5000))
            db     {:secret blob :cache blob}
            hiccup [:pre blob]]
        (seed-app-db! vid db)
        (declare-sensitive! vid [:secret])
        (declare-large! vid [:cache])
        (let [scrub-rendered (requiring-resolve 're-frame.story-mcp.tools.egress/scrub-rendered)
              out            (scrub-rendered hiccup db vid false)]
          (is (not (tree-contains? out blob)) "the blob does not survive")
          (is (tree-contains? out :rf/redacted)
              "sensitive wins — the leaf redacts to :rf/redacted, not the large marker"))))))

;; The two integration tests below pin the WIRING — that `preview-variant`
;; and `run-variant` route `:rendered-hiccup` / `:effective-args` / `:snapshot`
;; through `scrub-rendered`. They `with-redefs` `story/run-variant` to a
;; controlled result that embeds the secret in the derived trees, so the
;; assertion is independent of whatever the fixture's render would actually
;; produce (the leak exists regardless of WHICH view renders the secret).

(defn- secret-bearing-run-result
  "A unified-run-result-shaped value whose :app-db carries the secret at a
  declared-sensitive path AND whose derived trees re-embed the same value
  at non-app-db positions. Carries the unified `:status` / `:checks`
  slots (rf2-ba86n.17) so it is a faithful stand-in for what
  `story/run-variant` actually returns."
  [vid]
  {:status         :pass
   :frame          vid
   :lifecycle      :ready
   :elapsed-ms     1
   :app-db         {:public "ok" :token "TOPSECRET"}
   :assertions     []
   :checks         []
   :rendered-hiccup [:input {:type "password" :value "TOPSECRET"}]
   :effective-args {:label "Save" :token "TOPSECRET"}
   :snapshot       {:db {:token "TOPSECRET"}}
   ;; rf2-j90sb — the three evidence slots that previously egressed RAW.
   ;; :narrative is a two-level evidence tree whose inner beats carry
   ;; full :db-before / :db-after app-db snapshots (evidence.cljc
   ;; epoch-beat) — the secret rides those verbatim. :warnings are
   ;; trace-event records (here one carrying the secret in its data).
   ;; :sub-runs carry the subscription :value.
   :narrative      [{:span :epoch
                     :epochs [{:db-before {:token "TOPSECRET" :public "ok"}
                               :db-after  {:token "TOPSECRET" :public "ok"}
                               :trigger-event [:set-token "TOPSECRET"]}]}]
   :warnings       [{:event :rf.trace/warn :data {:token "TOPSECRET"}}]
   :sub-runs       [{:sub [:auth/token] :value "TOPSECRET"}]})

(deftest preview-variant-rendered-hiccup-redacts-sensitive-by-default
  (testing "the secret MUST NOT leak through preview-variant's derived trees"
    (with-clean-frame [vid :story.button/primary]
      (declare-sensitive! vid [:token])
      (with-redefs [story/run-variant
                    (fn [_vk _opts]
                      (java.util.concurrent.CompletableFuture/completedFuture
                        (secret-bearing-run-result vid)))]
        (let [r (invoke "preview-variant" {:variant-id "story.button/primary"})
              s (:structuredContent r)]
          (is (success? r))
          (is (= :rf/redacted (get-in s [:app-db :token]))
              "app-db path-redaction still holds")
          (is (not (tree-contains? (:rendered-hiccup s) "TOPSECRET"))
              "rendered-hiccup MUST NOT carry the redacted value at egress")
          (is (not (tree-contains? (:effective-args s) "TOPSECRET"))
              "effective-args MUST NOT carry the redacted value at egress")
          (is (not (tree-contains? (:snapshot s) "TOPSECRET"))
              "snapshot MUST NOT carry the redacted value at egress"))))))

(deftest run-variant-rendered-hiccup-redacts-sensitive-by-default
  (testing "the secret MUST NOT leak through run-variant's derived trees"
    (with-clean-frame [vid :story.button/primary]
      (declare-sensitive! vid [:token])
      (with-redefs [story/run-variant
                    (fn [_vk _opts]
                      (java.util.concurrent.CompletableFuture/completedFuture
                        (secret-bearing-run-result vid)))]
        (let [r (invoke "run-variant" {:variant-id "story.button/primary"})
              s (:structuredContent r)]
          (is (success? r))
          (is (= :rf/redacted (get-in s [:app-db :token])))
          (is (not (tree-contains? (:rendered-hiccup s) "TOPSECRET"))
              "rendered-hiccup MUST NOT carry the redacted value at egress")
          (is (not (tree-contains? (:snapshot s) "TOPSECRET"))
              "snapshot MUST NOT carry the redacted value at egress"))))))

(deftest run-variant-rendered-hiccup-forwards-secret-when-opted-in
  (testing ":include-sensitive true forwards the raw value through the derived trees"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (declare-sensitive! vid [:token])
      (with-redefs [story/run-variant
                    (fn [_vk _opts]
                      (java.util.concurrent.CompletableFuture/completedFuture
                        (secret-bearing-run-result vid)))]
        (let [r (invoke "run-variant" {:variant-id "story.button/primary"
                                       :include-sensitive true})
              s (:structuredContent r)]
          (is (success? r))
          (is (tree-contains? (:rendered-hiccup s) "TOPSECRET")
              "opt-in surfaces the raw value in rendered-hiccup"))))))

;; ---------------------------------------------------------------------------
;; rf2-j90sb (headline P1, privacy egress) — run-variant's :narrative /
;; :warnings / :sub-runs evidence slots egressed RAW. :narrative is a
;; two-level evidence tree whose inner beats carry FULL :db-before /
;; :db-after app-db snapshots (evidence.cljc epoch-beat), so a declared-
;; sensitive value — correctly redacted in the top-level :app-db slot —
;; escaped the MCP wire verbatim inside the narrative tree. :warnings
;; (trace-event records) and :sub-runs (sub :value) carry the same leak
;; class. These pin that all three are now value-redacted at egress, with
;; the same `:include-sensitive` opt-out as the sibling derived slots.
;; Sibling of pair-mcp's rf2-6wvh5.
;; ---------------------------------------------------------------------------

(deftest run-variant-narrative-redacts-sensitive-by-default
  (testing "the secret MUST NOT leak through run-variant's :narrative / :warnings / :sub-runs"
    (with-clean-frame [vid :story.button/primary]
      (declare-sensitive! vid [:token])
      (with-redefs [story/run-variant
                    (fn [_vk _opts]
                      (java.util.concurrent.CompletableFuture/completedFuture
                        (secret-bearing-run-result vid)))]
        (let [r (invoke "run-variant" {:variant-id "story.button/primary"})
              s (:structuredContent r)]
          (is (success? r))
          (is (= :rf/redacted (get-in s [:app-db :token]))
              "app-db path-redaction still holds")
          (is (not (tree-contains? (:narrative s) "TOPSECRET"))
              ":narrative beats' :db-before/:db-after MUST NOT carry the secret at egress")
          (is (tree-contains? (:narrative s) :rf/redacted)
              "the secret in :narrative is replaced by the :rf/redacted sentinel")
          (is (not (tree-contains? (:warnings s) "TOPSECRET"))
              ":warnings trace-event data MUST NOT carry the secret at egress")
          (is (not (tree-contains? (:sub-runs s) "TOPSECRET"))
              ":sub-runs subscription :value MUST NOT carry the secret at egress"))))))

(deftest run-variant-narrative-forwards-secret-when-opted-in
  (testing ":include-sensitive true forwards the raw value through :narrative / :warnings / :sub-runs"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (declare-sensitive! vid [:token])
      (with-redefs [story/run-variant
                    (fn [_vk _opts]
                      (java.util.concurrent.CompletableFuture/completedFuture
                        (secret-bearing-run-result vid)))]
        (let [r (invoke "run-variant" {:variant-id "story.button/primary"
                                       :include-sensitive true})
              s (:structuredContent r)]
          (is (success? r))
          (is (tree-contains? (:narrative s) "TOPSECRET")
              "opt-in surfaces the raw value in :narrative beats")
          (is (tree-contains? (:warnings s) "TOPSECRET")
              "opt-in surfaces the raw value in :warnings")
          (is (tree-contains? (:sub-runs s) "TOPSECRET")
              "opt-in surfaces the raw value in :sub-runs"))))))

(deftest read-failures-strips-sensitive-assertion-records-by-default
  (testing "an assertion record stamped :sensitive? true is dropped at egress"
    (with-clean-frame [vid :story.button/primary]
      ;; Seed assertion accumulator with one sensitive failure + one
      ;; benign passing record. The default-drop filter (strip-sensitive
      ;; from mcp-base.sensitive) must remove only the sensitive one.
      (seed-app-db! vid
                    {:rf.story/assertions
                     [{:assertion :rf.assert/path-equals
                       :passed?   true
                       :tags      [:public]}
                      {:assertion  :rf.assert/path-equals
                       :passed?    false
                       :sensitive? true
                       :reason     "expected TOPSECRET got something-else"}]})
      (let [r (invoke "read-failures" {:variant-id "story.button/primary"})
            s (:structuredContent r)]
        (is (success? r))
        (is (= 1 (:total s)) "only the non-sensitive record survives")
        (is (empty? (:failures s)) "the sensitive failure is filtered out")
        (is (= :pass (:status s))
            ":status aggregates the scrubbed vec — agent's view is consistent; a dropped sensitive failure doesn't flip the verdict")))))

(deftest read-failures-includes-sensitive-when-opted-in
  (testing ":include-sensitive true preserves sensitive records"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid
                    {:rf.story/assertions
                     [{:assertion :rf.assert/path-equals
                       :passed?   true}
                      {:assertion  :rf.assert/path-equals
                       :passed?    false
                       :sensitive? true
                       :reason     "expected TOPSECRET got something-else"}]})
      (let [r (invoke "read-failures" {:variant-id "story.button/primary"
                                       :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= 2 (:total s)) "both records survive the egress")
        (is (= 1 (count (:failures s))) "the failed sensitive record is visible")
        (is (= :fail (:status s)) "the visible failure drives :status :fail")))))

;; ---------------------------------------------------------------------------
;; Non-live wire-egress privacy posture (rf2-12f2q) — closing the split
;; contract.
;;
;; The wire-elision contract in tools/story/spec/006-MCP-Surface.md
;; promises EVERY Story-MCP payload crosses elided (registry reads +
;; recorder output included). Pre-fix, only the three live-state tools
;; (`preview-variant` / `run-variant` / `read-failures`) routed their
;; value-bearing slots through the egress scrubbers; the NON-live tools
;; (`explain-variant`'s plan-resolved value slots, `record-as-variant`'s
;; captured event vectors + the snippet derived from them) crossed RAW.
;;
;; These tests plant a DISTINCTIVE sensitive literal in those non-live
;; payloads and assert the MCP wire response does NOT include it by
;; default, while the documented `:include-sensitive` opt-in (gated by
;; --allow-sensitive-reads) reveals it. RED before the fix (the literal
;; crossed verbatim); GREEN after.
;;
;; The proof that the WITHOUT-fix path leaks: each test's secret value
;; reaches the wire slot directly from a captured event / plan-resolved
;; arg, so without the value-redaction step it would appear verbatim in
;; `:captured` / `:play-snippet` / the explain value slots.
;; ---------------------------------------------------------------------------

(deftest explain-variant-redacts-sensitive-effective-args-by-default
  (testing "a declared-sensitive value resolved into the explain :effective-args is redacted at egress"
    (with-clean-frame [vid :story.button/primary]
      ;; The frame app-db carries the secret at a declared-sensitive path;
      ;; the explain projection re-surfaces the same VALUE in a runtime-
      ;; resolved value slot. value-redaction matches by VALUE, so we plant
      ;; the same literal in a value-bearing explain slot via a redef.
      (seed-app-db! vid {:auth {:token "DISTINCTIVE-EXPLAIN-SECRET"}})
      (declare-sensitive! vid [:auth :token])
      (with-redefs [story/explain
                    (fn [_vk & _]
                      {:source-chain   [:story.button/primary]   ; structure — public
                       :effective-args {:api-key "DISTINCTIVE-EXPLAIN-SECRET"} ; value — must scrub
                       :network        {[:get "/api/me"] {:reply {:token "DISTINCTIVE-EXPLAIN-SECRET"}}}})]
        (let [r (invoke "explain-variant" {:variant-id "story.button/primary"})
              s (:structuredContent r)]
          (is (success? r))
          (is (not (tree-contains? (:explain s) "DISTINCTIVE-EXPLAIN-SECRET"))
              "WITHOUT the fix this literal crosses verbatim; the value-bearing explain slots MUST NOT carry it by default")
          (is (= [:story.button/primary] (get-in s [:explain :source-chain]))
              "plan-STRUCTURE slots are author-published discovery metadata — intentionally public, untouched")
          (is (= :rf/redacted (get-in s [:explain :effective-args :api-key]))
              ":effective-args value matching a declared-sensitive value is redacted"))))))

(deftest explain-variant-includes-sensitive-when-opted-in
  (testing ":include-sensitive true forwards the raw explain value slots (gate open)"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:auth {:token "DISTINCTIVE-EXPLAIN-SECRET"}})
      (declare-sensitive! vid [:auth :token])
      (with-redefs [story/explain
                    (fn [_vk & _]
                      {:effective-args {:api-key "DISTINCTIVE-EXPLAIN-SECRET"}})]
        (let [r (invoke "explain-variant" {:variant-id "story.button/primary"
                                           :include-sensitive true})
              s (:structuredContent r)]
          (is (success? r))
          (is (= "DISTINCTIVE-EXPLAIN-SECRET" (get-in s [:explain :effective-args :api-key]))
              "the documented opt-in surfaces the raw value"))))))

(deftest explain-variant-gate-closed-ignores-opt-in
  (testing "gate closed: :include-sensitive true is silently ignored — explain value slots stay redacted"
    (is (false? (config/sensitive-reads-allowed?)))
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:auth {:token "DISTINCTIVE-EXPLAIN-SECRET"}})
      (declare-sensitive! vid [:auth :token])
      (with-redefs [story/explain
                    (fn [_vk & _]
                      {:effective-args {:api-key "DISTINCTIVE-EXPLAIN-SECRET"}})]
        (let [r (invoke "explain-variant" {:variant-id "story.button/primary"
                                           :include-sensitive true})
              s (:structuredContent r)]
          (is (success? r))
          (is (= :rf/redacted (get-in s [:explain :effective-args :api-key]))
              "gate closed: the opt-in cannot exfiltrate the declared-sensitive value"))))))

;; ---------------------------------------------------------------------------
;; rf2-q8ebq.1 — explain-variant value-bearing slot COMPOSITION GAP.
;;
;; rf2-12f2q scrubbed only [:effective-args :args :substitutions :network
;; :db-seed], but the SAME `substitute-args` that feeds the scrubbed
;; `:substitutions` also resolves arg values into `:sub-overrides` override
;; values (plan.cljc:1297) and the `:setup-order` / `:script-order` step
;; sequences (plan.cljc:1263/1269). A declared-sensitive arg substituted
;; into any of those crossed the AI/MCP boundary RAW by default — leaving
;; `:setup-order`/`:script-order` unscrubbed is a clean BYPASS of the
;; `:substitutions` scrub (the secret rides the unscrubbed sibling).
;;
;; These tests plant a DISTINCTIVE secret in each of the three newly-scrubbed
;; slots and assert it is redacted on the wire by default. RED before the
;; fix (the slot was absent from `explain-value-bearing-slots` so the literal
;; crossed verbatim); GREEN after.
;; ---------------------------------------------------------------------------

(deftest explain-variant-redacts-sensitive-sub-overrides-and-step-order-by-default
  (testing "a declared-sensitive value resolved into :sub-overrides / :setup-order / :script-order is redacted at egress (rf2-q8ebq.1)"
    (with-clean-frame [vid :story.button/primary]
      ;; The frame app-db carries the secret at a declared-sensitive path;
      ;; the plan re-surfaces the SAME VALUE in each plan-RESOLVED value slot
      ;; (override values + step payloads run the same substitute-args that
      ;; feeds the scrubbed :substitutions). value-redaction matches by VALUE.
      (seed-app-db! vid {:auth {:token "DISTINCTIVE-SUBOVR-SECRET"}})
      (declare-sensitive! vid [:auth :token])
      (with-redefs [story/explain
                    (fn [_vk & _]
                      {:source-chain  [:story.button/primary]      ; structure — public
                       :sub-overrides {:overrides  {[:current-user] {:token "DISTINCTIVE-SUBOVR-SECRET"}}
                                       :validation {:status :ok :violations []}}
                       :setup-order   [[:dispatch [:auth/login {:token "DISTINCTIVE-SUBOVR-SECRET"}]]]
                       :script-order  [[:dispatch [:api/call {:key "DISTINCTIVE-SUBOVR-SECRET"}]]]})]
        (let [r (invoke "explain-variant" {:variant-id "story.button/primary"})
              s (:structuredContent r)]
          (is (success? r))
          (is (not (tree-contains? (get-in s [:explain :sub-overrides]) "DISTINCTIVE-SUBOVR-SECRET"))
              "WITHOUT the fix the secret crosses verbatim in :sub-overrides; it MUST NOT by default")
          (is (not (tree-contains? (get-in s [:explain :setup-order]) "DISTINCTIVE-SUBOVR-SECRET"))
              "WITHOUT the fix the secret crosses verbatim in :setup-order — the substitute-args sibling of :substitutions")
          (is (not (tree-contains? (get-in s [:explain :script-order]) "DISTINCTIVE-SUBOVR-SECRET"))
              "WITHOUT the fix the secret crosses verbatim in :script-order — the same bypass")
          (is (= :rf/redacted (get-in s [:explain :sub-overrides :overrides [:current-user] :token]))
              ":sub-overrides override value matching a declared-sensitive value is redacted")
          (is (= [[:dispatch [:auth/login {:token :rf/redacted}]]] (get-in s [:explain :setup-order]))
              "value-only redaction preserves the public step STRUCTURE while scrubbing the embedded secret")
          (is (= [[:dispatch [:api/call {:key :rf/redacted}]]] (get-in s [:explain :script-order]))
              "ditto for :script-order — fx ids + ordering survive, only the value leaf is redacted")
          (is (= [:story.button/primary] (get-in s [:explain :source-chain]))
              "plan-STRUCTURE slots remain author-published discovery metadata — untouched")
          (is (= :ok (get-in s [:explain :sub-overrides :validation :status]))
              "the non-value :validation structure inside :sub-overrides is preserved"))))))

(deftest explain-variant-includes-sensitive-sub-overrides-when-opted-in
  (testing ":include-sensitive true forwards the raw :sub-overrides / step-order value slots (gate open, rf2-q8ebq.1)"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:auth {:token "DISTINCTIVE-SUBOVR-SECRET"}})
      (declare-sensitive! vid [:auth :token])
      (with-redefs [story/explain
                    (fn [_vk & _]
                      {:sub-overrides {:overrides {[:current-user] {:token "DISTINCTIVE-SUBOVR-SECRET"}}}
                       :setup-order   [[:dispatch [:auth/login {:token "DISTINCTIVE-SUBOVR-SECRET"}]]]})]
        (let [r (invoke "explain-variant" {:variant-id "story.button/primary"
                                           :include-sensitive true})
              s (:structuredContent r)]
          (is (success? r))
          (is (= "DISTINCTIVE-SUBOVR-SECRET" (get-in s [:explain :sub-overrides :overrides [:current-user] :token]))
              "the documented opt-in surfaces the raw override value")
          (is (= "DISTINCTIVE-SUBOVR-SECRET" (get-in s [:explain :setup-order 0 1 1 :token]))
              "and the raw setup-step value crosses too"))))))

;; ---------------------------------------------------------------------------
;; rf2-tag30h — explain-variant PRE-FRAME egress.
;;
;; `explain-variant` is a documented NO-RUN path (spec/API.md §explain-variant:
;; "Plan-derived data — no run, no live :app-db slice"): a caller can read it
;; BEFORE any run-variant / preview-variant allocates the variant frame. The
;; original value-redaction (`scrub-frame-value`) derived candidate secrets
;; ONLY from the LIVE frame app-db (`rf/app-db-value`), which is nil pre-frame
;; — so a declared-sensitive value authored into a plan slot (:db-seed seed
;; data, a stubbed :network reply, a resolved :effective-args / step payload)
;; crossed the AI/off-box boundary RAW with no live source to value-match.
;;
;; The fix collects candidate secrets ALSO from the plan's OWN :db-seed slot
;; at the variant's frame-declared-sensitive PATHS — read from the frame's
;; durable elision registry, which frame-owned classification populates at
;; `reg-frame` time (EP-0015 §8, rf2-d2r3um), so the paths are live from
;; frame creation onward without any RUN. These tests declare a sensitive
;; path PRE-RUN (frame allocated, no run-variant / preview-variant has
;; executed and seeded the live app-db), then assert the secret is redacted
;; by default and surfaced only via the gated :include-sensitive opt-in. RED
;; before the fix (the literal crossed verbatim); GREEN after.
;; ---------------------------------------------------------------------------

(defn- declare-sensitive-prerun!
  "Install a frame-owned `:sensitive` `:app-db` declaration for a slot on
  the named variant's frame PRE-RUN — the no-run posture `explain-variant`
  must defend (rf2-tag30h). Frame-owned classification lives in the frame's
  durable elision registry (its runtime-db partition), so the frame
  container must exist; we `ensure-variant-frame!` (allocate at reg-frame
  time) then `frame-class/install!`. No run-variant / preview-variant has
  executed, so the LIVE app-db is still empty — the candidate secrets come
  from the plan's own :db-seed at these declared paths (EP-0015 §8,
  rf2-d2r3um)."
  [variant-id path]
  (ensure-variant-frame! variant-id)
  (frame-class/install! variant-id
    (frame-class/validate+extract variant-id {:sensitive {:app-db [(vec path)]}})))

(deftest explain-variant-redacts-preframe-db-seed-by-default
  (testing "PRE-RUN: a declared-sensitive value in the plan :db-seed (and re-surfaced in :effective-args / :network) is redacted with NO seeded live app-db (rf2-tag30h)"
    (with-clean-frame [vid :story.button/primary]
      ;; No RUN has executed — the live app-db is still empty, so the
      ;; candidate secrets must come from the plan's own :db-seed at the
      ;; frame's declared-sensitive paths (EP-0015 §8, rf2-d2r3um).
      (declare-sensitive-prerun! vid [:auth :token])
      (is (empty? (rf/app-db-value vid))
          "precondition: no run has seeded the live app-db")
      (with-redefs [story/explain
                    (fn [_vk & _]
                      {:source-chain   [:story.button/primary]                    ; structure — public
                       :db-seed        {:auth {:token "DISTINCTIVE-PREFRAME-SECRET"}}
                       :effective-args {:api-key "DISTINCTIVE-PREFRAME-SECRET"}
                       :network        {[:get "/api/me"] {:reply {:token "DISTINCTIVE-PREFRAME-SECRET"}}}
                       :setup-order    [[:dispatch [:auth/login {:token "DISTINCTIVE-PREFRAME-SECRET"}]]]})]
        (let [r (invoke "explain-variant" {:variant-id "story.button/primary"})
              s (:structuredContent r)]
          (is (success? r))
          (is (not (tree-contains? (:explain s) "DISTINCTIVE-PREFRAME-SECRET"))
              "PRE-RUN the secret MUST NOT cross in ANY value-bearing slot — no seeded live app-db is no excuse")
          (is (= :rf/redacted (get-in s [:explain :db-seed :auth :token]))
              ":db-seed (the candidate source itself) is redacted")
          (is (= :rf/redacted (get-in s [:explain :effective-args :api-key]))
              ":effective-args value matching the seeded secret is redacted")
          (is (= :rf/redacted (get-in s [:explain :network [:get "/api/me"] :reply :token]))
              ":network reply value matching the seeded secret is redacted")
          (is (= [[:dispatch [:auth/login {:token :rf/redacted}]]] (get-in s [:explain :setup-order]))
              ":setup-order value-only redaction preserves the step STRUCTURE")
          (is (= [:story.button/primary] (get-in s [:explain :source-chain]))
              "plan-STRUCTURE slots remain author-published discovery metadata — untouched"))))))

(deftest explain-variant-preframe-includes-sensitive-when-opted-in
  (testing "PRE-RUN: :include-sensitive true forwards the raw plan value slots (gate open, rf2-tag30h)"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (declare-sensitive-prerun! vid [:auth :token])
      (is (empty? (rf/app-db-value vid)))
      (with-redefs [story/explain
                    (fn [_vk & _]
                      {:db-seed {:auth {:token "DISTINCTIVE-PREFRAME-SECRET"}}})]
        (let [r (invoke "explain-variant" {:variant-id "story.button/primary"
                                           :include-sensitive true})
              s (:structuredContent r)]
          (is (success? r))
          (is (= "DISTINCTIVE-PREFRAME-SECRET" (get-in s [:explain :db-seed :auth :token]))
              "the documented opt-in surfaces the raw seed value pre-frame too"))))))

(deftest explain-variant-preframe-gate-closed-ignores-opt-in
  (testing "PRE-RUN gate closed: :include-sensitive true is ignored — plan value slots stay redacted (rf2-tag30h)"
    (is (false? (config/sensitive-reads-allowed?)))
    (with-clean-frame [vid :story.button/primary]
      (declare-sensitive-prerun! vid [:auth :token])
      (is (empty? (rf/app-db-value vid)))
      (with-redefs [story/explain
                    (fn [_vk & _]
                      {:db-seed {:auth {:token "DISTINCTIVE-PREFRAME-SECRET"}}})]
        (let [r (invoke "explain-variant" {:variant-id "story.button/primary"
                                           :include-sensitive true})
              s (:structuredContent r)]
          (is (success? r))
          (is (= :rf/redacted (get-in s [:explain :db-seed :auth :token]))
              "gate closed: the opt-in cannot exfiltrate the seeded secret pre-frame"))))))

;; ---------------------------------------------------------------------------
;; rf2-q8ebq.2 — read-a11y-violations shipped raw axe-core violation nodes (incl. node
;; :html outerHTML) with NO egress scrub. A sensitive value rendered into
;; the DOM (e.g. `<input value="<token>">`) lands verbatim in node :html and
;; crossed the AI/off-box MCP boundary unredacted — and read-a11y-violations is
;; :readOnlyHint true (agent hosts AUTO-APPROVE it), so an unscrubbed runtime
;; read here is the wrong shape. The fix routes :violations through
;; `egress/scrub-frame-value` (the same value-based primitive explain/record
;; use), fail-closed by default + the :include-sensitive opt-in.
;;
;; The helpers (`seed-app-db!` / `declare-sensitive!`) establish the frame's
;; declared-sensitive value; `scrub-frame-value` reads that frame's live
;; app-db itself to collect the secret-candidate set, then redacts any
;; matching leaf in the violations tree. The co-hosted violations atom is
;; supplied via the same var-of-atom stand-in the populated-path test uses.
;; ---------------------------------------------------------------------------

(defn- a11y-stand-in
  "A var-of-atom mirror for `violations-by-frame-var`: the handler does
  `(deref @violations-by-frame-var)`, so `@stand-in` is the inner atom and
  `(deref inner)` is the by-frame violations map."
  [by-frame]
  (atom (atom by-frame)))

(deftest read-a11y-violations-redacts-sensitive-violation-html-by-default
  (testing "a declared-sensitive value rendered into an axe-core node :html is redacted at egress (rf2-q8ebq.2)"
    (with-clean-frame [vid :story.button/primary]
      ;; The frame app-db carries the secret at a declared-sensitive path;
      ;; the rendered DOM (axe-core node :html) embeds the SAME literal.
      ;; value-redaction matches by VALUE, so the leaf carrying it is scrubbed.
      (seed-app-db! vid {:auth {:token "DISTINCTIVE-A11Y-SECRET"}})
      (declare-sensitive! vid [:auth :token])
      (let [vios     [{:id    "label"
                       :impact "critical"
                       :help  "Form elements must have labels"
                       :nodes [{:html           "DISTINCTIVE-A11Y-SECRET"
                                :target         ["#api-key-input"]
                                :failureSummary "Fix any of the following: element has no label"}]}]
            stand-in (a11y-stand-in {:story.button/primary vios})]
        (with-redefs [re-frame.story-mcp.tools.testing/violations-by-frame-var stand-in]
          (let [r (invoke "read-a11y-violations" {:variant-id "story.button/primary"})
                s (:structuredContent r)]
            (is (success? r))
            (is (not (tree-contains? (:violations s) "DISTINCTIVE-A11Y-SECRET"))
                "WITHOUT the fix the secret rides node :html verbatim; it MUST NOT cross by default")
            (is (= :rf/redacted (get-in s [:violations 0 :nodes 0 :html]))
                "the node :html leaf matching a declared-sensitive value is redacted")
            (is (= "label" (get-in s [:violations 0 :id]))
                "the public axe-core finding STRUCTURE (id/impact/help/target) survives — only the value leaf is scrubbed")
            (is (= ["#api-key-input"] (get-in s [:violations 0 :nodes 0 :target]))
                "non-sensitive node fields (CSS target selectors) pass through")
            (is (nil? (:note s))
                "the atom resolved, so this is the co-hosted path, not JVM-standalone")))))))

(deftest read-a11y-violations-includes-sensitive-violation-html-when-opted-in
  (testing ":include-sensitive true forwards the raw axe-core node :html (gate open, rf2-q8ebq.2)"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:auth {:token "DISTINCTIVE-A11Y-SECRET"}})
      (declare-sensitive! vid [:auth :token])
      (let [vios     [{:id "label" :nodes [{:html "DISTINCTIVE-A11Y-SECRET"}]}]
            stand-in (a11y-stand-in {:story.button/primary vios})]
        (with-redefs [re-frame.story-mcp.tools.testing/violations-by-frame-var stand-in]
          (let [r (invoke "read-a11y-violations" {:variant-id        "story.button/primary"
                                      :include-sensitive true})
                s (:structuredContent r)]
            (is (success? r))
            (is (= "DISTINCTIVE-A11Y-SECRET" (get-in s [:violations 0 :nodes 0 :html]))
                "the documented opt-in surfaces the raw node :html")))))))

(deftest read-a11y-violations-gate-closed-ignores-opt-in
  (testing "gate closed: :include-sensitive true is silently ignored — violation :html stays redacted (rf2-q8ebq.2)"
    (is (false? (config/sensitive-reads-allowed?)))
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:auth {:token "DISTINCTIVE-A11Y-SECRET"}})
      (declare-sensitive! vid [:auth :token])
      (let [vios     [{:id "label" :nodes [{:html "DISTINCTIVE-A11Y-SECRET"}]}]
            stand-in (a11y-stand-in {:story.button/primary vios})]
        (with-redefs [re-frame.story-mcp.tools.testing/violations-by-frame-var stand-in]
          (let [r (invoke "read-a11y-violations" {:variant-id        "story.button/primary"
                                      :include-sensitive true})
                s (:structuredContent r)]
            (is (success? r))
            (is (= :rf/redacted (get-in s [:violations 0 :nodes 0 :html]))
                "gate closed: the opt-in cannot exfiltrate the declared-sensitive value")))))))

(deftest record-as-variant-redacts-sensitive-captured-event-by-default
  (testing "a captured event carrying a declared-sensitive value is redacted in :captured AND :play-snippet"
    (with-clean-frame [vid :story.button/primary]
      ;; The frame holds the secret at a declared-sensitive path; the
      ;; recorded event carries the SAME literal in its payload, so the
      ;; value-redaction step (matching by value against the frame's
      ;; declared-sensitive values) must scrub it everywhere it lands.
      (seed-app-db! vid {:auth {:token "DISTINCTIVE-RECORDED-SECRET"}})
      (declare-sensitive! vid [:auth :token])
      (drive-events-during-recording [[:auth/login "DISTINCTIVE-RECORDED-SECRET"]])
      (let [r (invoke "record-as-variant"
                      {:variant-id  "story.button/primary"
                       :duration-ms 100})
            s (:structuredContent r)]
        (is (success? r))
        (is (= 1 (:recorded-event-count s)) "the event was captured")
        (is (not (tree-contains? (:captured s) "DISTINCTIVE-RECORDED-SECRET"))
            "WITHOUT the fix this literal crosses verbatim in :captured; it MUST NOT by default")
        (is (not (re-find #"DISTINCTIVE-RECORDED-SECRET" (:play-snippet s)))
            "the :play-snippet text is rendered from the scrubbed events, so the secret is absent there too")
        (is (tree-contains? (:captured s) :rf/redacted)
            "the matching leaf is replaced with the :rf/redacted sentinel")
        (is (re-find #":auth/login" (:play-snippet s))
            "the non-sensitive event id survives — only the secret value is scrubbed")))))

(deftest record-as-variant-includes-sensitive-when-opted-in
  (testing ":include-sensitive true forwards the raw captured event (gate open)"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:auth {:token "DISTINCTIVE-RECORDED-SECRET"}})
      (declare-sensitive! vid [:auth :token])
      (drive-events-during-recording [[:auth/login "DISTINCTIVE-RECORDED-SECRET"]])
      (let [r (invoke "record-as-variant"
                      {:variant-id        "story.button/primary"
                       :duration-ms       100
                       :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= [[:auth/login "DISTINCTIVE-RECORDED-SECRET"]] (:captured s))
            "the documented opt-in surfaces the raw captured event")
        (is (re-find #"DISTINCTIVE-RECORDED-SECRET" (:play-snippet s))
            "and the raw value rides the snippet text")))))

(deftest record-as-variant-gate-closed-ignores-opt-in
  (testing "gate closed: :include-sensitive true is silently ignored — captured event stays redacted"
    (is (false? (config/sensitive-reads-allowed?)))
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:auth {:token "DISTINCTIVE-RECORDED-SECRET"}})
      (declare-sensitive! vid [:auth :token])
      (drive-events-during-recording [[:auth/login "DISTINCTIVE-RECORDED-SECRET"]])
      (let [r (invoke "record-as-variant"
                      {:variant-id        "story.button/primary"
                       :duration-ms       100
                       :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (not (tree-contains? (:captured s) "DISTINCTIVE-RECORDED-SECRET"))
            "gate closed: the opt-in cannot exfiltrate the declared-sensitive value")))))

;; ---------------------------------------------------------------------------
;; rf2-koq5m (headline P1, privacy/observability MUST at the AI boundary) —
;; egress indicator counts (`:dropped-sensitive` / `:elided-large`).
;;
;; story-mcp drops `:sensitive? true` assertion records and elides
;; over-threshold / schema-`:large?` leaves at the wire egress, but
;; surfaced NEITHER count — the canonical silent-swallow failure mode.
;; spec/Conventions.md §Cross-MCP indicator-field vocabulary is MUST-
;; level: a tool walking a tree-typed payload MUST carry an
;; `:elided-large` count alongside the `:dropped-sensitive` count,
;; omitting each slot when zero. The fix reuses the mcp-base primitives
;; (`envelope/with-indicators` + `elision/count-elided-markers`) the
;; sibling pair-mcp already wires.
;;
;; RED (pre-fix): the response carries no indicator slots even when a
;; sensitive slot is dropped / a large value is elided.
;; GREEN (post-fix): `:dropped-sensitive` / `:elided-large` present with
;; the correct counts; omitted entirely on a clean read.
;; ---------------------------------------------------------------------------

(deftest read-failures-surfaces-dropped-sensitive-indicator
  (testing ":dropped-sensitive count rides the envelope when a sensitive record is dropped"
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid
                    {:rf.story/assertions
                     [{:assertion :rf.assert/path-equals :passed? true :tags [:public]}
                      {:assertion  :rf.assert/path-equals :passed? false
                       :sensitive? true :reason "secret mismatch"}
                      {:assertion  :rf.assert/sub-equals :passed? false
                       :sensitive? true :reason "another secret mismatch"}]})
      (let [r (invoke "read-failures" {:variant-id "story.button/primary"})
            s (:structuredContent r)]
        (is (success? r))
        (is (= 1 (:total s)) "only the non-sensitive record survives")
        (is (= 2 (:dropped-sensitive s))
            "the count of dropped sensitive records rides the envelope (rf2-koq5m MUST)")))))

(deftest read-failures-omits-indicators-when-nothing-dropped
  (testing "neither indicator slot appears on a clean read (omit-when-zero MUST)"
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid
                    {:rf.story/assertions
                     [{:assertion :rf.assert/path-equals :passed? true :tags [:public]}]})
      (let [r (invoke "read-failures" {:variant-id "story.button/primary"})
            s (:structuredContent r)]
        (is (success? r))
        (is (not (contains? s :dropped-sensitive))
            ":dropped-sensitive omitted when zero")
        (is (not (contains? s :elided-large))
            ":elided-large omitted when zero")))))

(deftest read-failures-includes-sensitive-clears-dropped-indicator
  (testing ":include-sensitive true keeps the records, so :dropped-sensitive stays absent"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid
                    {:rf.story/assertions
                     [{:assertion  :rf.assert/path-equals :passed? false
                       :sensitive? true :reason "secret mismatch"}]})
      (let [r (invoke "read-failures" {:variant-id "story.button/primary"
                                       :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= 1 (:total s)) "the sensitive record survives the opt-in")
        (is (not (contains? s :dropped-sensitive))
            "nothing was dropped, so the slot is omitted (omit-when-zero)")))))

(deftest run-variant-surfaces-elided-large-indicator
  (testing ":elided-large count rides the envelope when a large value is elided"
    (with-clean-frame [vid :story.button/primary]
      ;; A schema-declared `:large?` slot whose value the egress walker
      ;; substitutes with `:rf.size/large-elided` — the leaf the
      ;; `:elided-large` indicator counts.
      (seed-app-db! vid {:public "ok" :blob "a-big-uploaded-blob"})
      (declare-large! vid [:blob])
      (let [r (invoke "run-variant" {:variant-id "story.button/primary"})
            s (:structuredContent r)]
        (is (success? r))
        ;; The slot is replaced by the marker in the wire :app-db.
        (is (contains? (get-in s [:app-db :blob]) :rf.size/large-elided)
            "the large slot is replaced by the :rf.size/large-elided marker")
        (is (pos-int? (:elided-large s))
            "the count of elided leaves rides the envelope (rf2-koq5m MUST)")))))

(deftest egress-with-indicators-honours-omit-when-zero
  ;; Unit pin on the egress helper itself — the omit-when-zero rule it
  ;; inherits from mcp-base. Belt-and-braces alongside the tool-level
  ;; tests above so a drift in the helper wiring trips here directly.
  (testing "both zero ⇒ payload unchanged"
    (is (= {:ok? true}
           (egress/with-indicators {:ok? true} {:dropped 0 :elided 0}))))
  (testing "positive counts ⇒ both slots spliced"
    (is (= {:ok? true :dropped-sensitive 3 :elided-large 2}
           (egress/with-indicators {:ok? true} {:dropped 3 :elided 2}))))
  (testing "count-elided walks the payload for :rf.size/large-elided markers"
    (is (= 0 (egress/count-elided {:a 1 :b [2 3]})))
    (is (= 1 (egress/count-elided {:a {:rf.size/large-elided {:path [:a] :bytes 99}}})))))

;; The full set of tools that surface a value-bearing slot (live `:app-db`
;; / assertions OR a non-live runtime/captured value) and so must accept
;; the `:include-sensitive` opt-in. The live three (rf2-73wuj) plus the
;; non-live two closed in rf2-12f2q (`explain-variant`'s plan-resolved
;; value slots, `record-as-variant`'s captured events), plus `read-a11y-violations`'s
;; runtime DOM `:violations` (rf2-q8ebq.2).
(def ^:private include-sensitive-tools
  ["preview-variant" "run-variant" "read-failures"
   "explain-variant" "record-as-variant" "read-a11y-violations"])

(deftest egress-tools-input-schema-carries-include-sensitive
  (testing "every tool surfacing a value-bearing slot accepts :include-sensitive"
    (doseq [tname include-sensitive-tools]
      (let [t     (some #(when (= tname (:name %)) %) registry/tool-registry)
            props (-> t :inputSchema :properties)]
        (is (contains? props :include-sensitive)
            (str tname " missing :include-sensitive slot"))
        (is (= "boolean" (-> props :include-sensitive :type))
            (str tname " :include-sensitive slot is not boolean-typed")))))
  ;; rf2-wu1o2d — pin the EXACT include-sensitive tool set against the
  ;; registry so the spec's "three affected tools" prose (now corrected to
  ;; six) and the descriptor strip can't silently drift apart. The set is
  ;; precisely the descriptors that carry the slot — no more, no less.
  (testing "the include-sensitive set is EXACTLY the descriptors carrying the slot (no drift)"
    (let [carriers (->> registry/tool-registry
                        (filter #(contains? (-> % :inputSchema :properties) :include-sensitive))
                        (map :name)
                        set)]
      (is (= (set include-sensitive-tools) carriers)
          "every descriptor carrying :include-sensitive must be in the pinned set, and vice versa")
      (is (= 6 (count carriers))
          "the affected set is six tools (spec/002 §sensitive-read gate) — not three"))))

(def ^:private api-md
  "The consolidated public-API page, read relative to the `tools/story-mcp/`
  artefact root (resolved cwd-independently via `artefact-root`). Read once
  at ns-load — if the path drifts `slurp` throws and the drift test errors
  loudly rather than silently passing on an empty string."
  (delay (slurp (io/file (artefact-root) "spec" "API.md"))))

(defn- api-section
  "Return the `### \\`<tool-name>\\`` section body from API.md — the text
  from that heading up to the next `### ` (or `## `) heading. Used by the
  docs-drift guard so a per-tool assertion bites on the right slice."
  [tool-name]
  (let [doc     @api-md
        heading (str "### `" tool-name "`")
        start   (clojure.string/index-of doc heading)]
    (when start
      (let [after (subs doc (+ start (count heading)))
            ;; The next `### ` or `## ` heading on its own line bounds the
            ;; section; nil end ⇒ the section runs to EOF.
            end   (->> [(clojure.string/index-of after "\n### ")
                        (clojure.string/index-of after "\n## ")]
                       (remove nil?)
                       (apply min Long/MAX_VALUE))]
        (if (= end Long/MAX_VALUE)
          after
          (subs after 0 end))))))

(deftest api-md-tracks-include-sensitive-descriptor-set
  ;; rf2-ovmc5e Finding #3 — the consolidated API page must list
  ;; `:include-sensitive` for EVERY tool whose descriptor carries the
  ;; slot, so the summary can't silently under-document the gated
  ;; privacy escape hatch (the original drift: read-a11y-violations's API.md input
  ;; omitted it). Derives the expected set from the live registry, so a
  ;; new value-surfacing tool that gains the slot must also gain the
  ;; API.md mention or this trips.
  (testing "API.md documents :include-sensitive for every descriptor that carries it"
    (let [carriers (->> registry/tool-registry
                        (filter #(contains? (-> % :inputSchema :properties) :include-sensitive))
                        (map :name)
                        sort)]
      (doseq [tname carriers]
        (let [section (api-section tname)]
          (is (some? section)
              (str "API.md is missing a `### `" tname "`` section"))
          (is (and section (clojure.string/includes? section ":include-sensitive"))
              (str "API.md §" tname " must document the gated :include-sensitive slot "
                   "(descriptor carries it; the consolidated page must not under-document it)")))))))

;; ---------------------------------------------------------------------------
;; Sensitive-read boot gate (rf2-g9fje)
;;
;; Per the rf2-uaymx (b) decision: the per-call `:include-sensitive` arg
;; is honoured ONLY when the operator opened the server-side gate at boot
;; (`--allow-sensitive-reads`). When the gate is closed:
;;
;;   1. `tools/list` omits `:include-sensitive` from the input schemas of
;;      every affected tool — the six that surface live/plan-resolved
;;      VALUES (preview-variant / run-variant / read-failures / read-a11y-violations /
;;      explain-variant / record-as-variant), i.e. every descriptor that
;;      carries the slot (caller UX — no ghost knob).
;;   2. `:include-sensitive true` on a tool call is silently ignored at
;;      the egress helpers (defence-in-depth — even a caller who learned
;;      about the slot some other way can't exfiltrate raw values).
;; ---------------------------------------------------------------------------

(deftest sensitive-reads-gate-defaults-closed
  (testing "fixture leaves the gate closed by default"
    (is (false? (config/sensitive-reads-allowed?))
        "fixture must reset the gate between tests")))

(deftest sensitive-reads-gate-flag-flips-config
  (testing "--allow-sensitive-reads flag flips the boot config"
    (let [cfg (#'server/parse-args ["--allow-sensitive-reads"])]
      (is (true? (:allow-sensitive-reads? cfg))))
    (let [cfg (#'server/parse-args [])]
      (is (nil? (:allow-sensitive-reads? cfg))
          "absent flag leaves the slot unset so merge respects sysprop/env defaults"))))

(deftest tools-list-strips-include-sensitive-when-gate-closed
  (testing "tools/list omits :include-sensitive from the schema when the gate is closed"
    (is (false? (config/sensitive-reads-allowed?)))
    (let [descriptors (registry/tool-descriptors)]
      (doseq [tname include-sensitive-tools]
        (let [t     (some #(when (= tname (:name %)) %) descriptors)
              props (-> t :inputSchema :properties)]
          (is (not (contains? props :include-sensitive))
              (str "gate closed: " tname " must not advertise :include-sensitive")))))))

(deftest tools-list-surfaces-include-sensitive-when-gate-open
  (testing "tools/list advertises :include-sensitive when the gate is open"
    (config/set-allow-sensitive-reads! true)
    (let [descriptors (registry/tool-descriptors)]
      (doseq [tname include-sensitive-tools]
        (let [t     (some #(when (= tname (:name %)) %) descriptors)
              props (-> t :inputSchema :properties)]
          (is (contains? props :include-sensitive)
              (str "gate open: " tname " must advertise :include-sensitive"))
          (is (= "boolean" (-> props :include-sensitive :type))))))))

(deftest preview-variant-gate-closed-ignores-per-call-flag
  (testing "with gate closed, :include-sensitive true is silently ignored at egress"
    (is (false? (config/sensitive-reads-allowed?)))
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "preview-variant" {:variant-id "story.button/primary"
                                         :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= :rf/redacted (get-in s [:app-db :secret]))
            "gate closed: per-call opt-in is dropped; redaction stands")))))

(deftest run-variant-gate-closed-ignores-per-call-flag
  (testing "with gate closed, :include-sensitive true is silently ignored at egress"
    (is (false? (config/sensitive-reads-allowed?)))
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "run-variant" {:variant-id "story.button/primary"
                                     :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= :rf/redacted (get-in s [:app-db :secret]))
            "gate closed: per-call opt-in is dropped; redaction stands")))))

(deftest read-failures-gate-closed-ignores-per-call-flag
  (testing "with gate closed, :include-sensitive true does not surface sensitive records"
    (is (false? (config/sensitive-reads-allowed?)))
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid
                    {:rf.story/assertions
                     [{:assertion :rf.assert/path-equals :passed? true}
                      {:assertion  :rf.assert/path-equals
                       :passed?    false
                       :sensitive? true
                       :reason     "leak"}]})
      (let [r (invoke "read-failures" {:variant-id "story.button/primary"
                                       :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= 1 (:total s))
            "gate closed: sensitive records remain dropped despite the opt-in")))))

(deftest read-boot-config-sensitive-reads-sysprop
  (testing "JVM sysprop seeds :allow-sensitive-reads? true"
    (let [restore (System/getProperty "rf.story-mcp.allow-sensitive-reads")]
      (try
        (System/setProperty "rf.story-mcp.allow-sensitive-reads" "true")
        (let [cfg (config/read-boot-config)]
          (is (true? (:allow-sensitive-reads? cfg))))
        (finally
          (if restore
            (System/setProperty "rf.story-mcp.allow-sensitive-reads" restore)
            (System/clearProperty "rf.story-mcp.allow-sensitive-reads")))))))

;; ---------------------------------------------------------------------------
;; Agent-onboarding text parity (rf2-36upq S5)
;;
;; `story-instructions-text` (tools/dev.cljc) is hand-copied from the spec.
;; CI must catch drift between the prose's canonical-tag list / assertion-id
;; list and what the registrar reports — otherwise the agent's onboarding
;; doc silently lies as the registry evolves.
;; ---------------------------------------------------------------------------

(deftest story-instructions-text-mentions-every-canonical-tag
  (testing "the onboarding text names every canonical tag the registrar ships"
    (let [text       dev/story-instructions-text
          tag-names  (set (map name story/canonical-tags))]
      (doseq [tag-name tag-names]
        (is (re-find (re-pattern (str ":" tag-name "\\b")) text)
            (str "story-instructions-text missing canonical tag :" tag-name
                 " — keep the onboarding doc in lockstep with `story/canonical-tags`"))))))

(deftest story-instructions-text-mentions-every-canonical-assertion
  (testing "the onboarding text names every canonical assertion the registrar ships"
    (let [text             dev/story-instructions-text
          assertion-names  (->> (story/canonical-assertion-ids)
                                (map name)
                                set)]
      ;; The prose styles assertion ids without the namespace prefix (e.g.
      ;; "path-equals", "state-is") to keep the line under width. Match on
      ;; the bare name with a word boundary on each side.
      (doseq [aname assertion-names]
        (is (re-find (re-pattern (str "\\b" aname "\\b")) text)
            (str "story-instructions-text missing canonical assertion " aname
                 " — keep the onboarding doc in lockstep with `story/canonical-assertion-ids`"))))))

;; ---------------------------------------------------------------------------
;; handle-frame! recovery write — nested catch (rf2-36upq TE2)
;;
;; server.cljc's handle-frame! has a nested try around the recovery write
;; (the "even the recovery write failed" branch). It's unreachable from the
;; happy-path corpus; mock a writer that throws on .write and assert the
;; run-loop survives.
;; ---------------------------------------------------------------------------

(deftest handle-frame-survives-recovery-write-failure
  (testing "writer that throws on every .write yields no propagation"
    ;; Force the dispatch to throw by triggering an error path that the
    ;; outer catch picks up. The cleanest signal: an unknown tool method
    ;; arrives via tools/call AFTER dispatch returns a valid response,
    ;; then proto/write-frame! throws on the writer. We compose this with
    ;; a `tools/call` that succeeds in dispatch but where the writer
    ;; throws.
    (let [throwing-writer (proxy [java.io.Writer] []
                            (write
                              ([_])
                              ([_a _b _c]
                                (throw (RuntimeException. "writer broken"))))
                            (flush [])
                            (close []))
          msg             {:jsonrpc "2.0" :id 1 :method "ping"}]
      ;; The function MUST NOT propagate either throw — both writer
      ;; failures (the response write AND the internal-error recovery
      ;; write) are caught and logged. If propagation regressed, this
      ;; would throw and the test would fail loudly.
      (is (nil? (try
                  ;; `ping` is accepted in EVERY lifecycle posture, so a
                  ;; fresh (uninitialized) state still reaches the writer.
                  (#'server/handle-frame! (server/new-lifecycle-state) throwing-writer msg)
                  nil
                  (catch Throwable e e)))
          "handle-frame! must not propagate writer-side throws"))))

;; ---------------------------------------------------------------------------
;; Boot-config precedence — CLI > sysprop > env (rf2-36upq TE4)
;;
;; Per spec/003-Write-Surface-Gating.md §91-94 the precedence is CLI flag >
;; JVM sysprop > env var. The existing tests cover `parse-args` directly;
;; this test asserts the merged behaviour: when all three sources supply
;; conflicting values, CLI wins, sysprop overrides env, and the env-only
;; case lands too.
;; ---------------------------------------------------------------------------

(deftest boot-config-precedence-cli-over-sysprop-over-env
  ;; Save/restore the sysprop. Env vars are read-only on the JVM, so we
  ;; can't directly mutate `RF_STORY_MCP_ALLOW_WRITES`; the test exercises
  ;; the two-of-three combinations a unit-test environment can stage
  ;; (sysprop alone, CLI overrides sysprop). The third combination —
  ;; pure env var — is exercised by the SDK-driven integration harness
  ;; `tools/mcp-conformance/test/end-to-end-story.cjs` (which boots the
  ;; server with --allow-writes) under the same precedence rule.
  (let [restore (System/getProperty "rf.story-mcp.allow-writes")]
    (try
      (testing "sysprop alone seeds allow-writes? true"
        (System/setProperty "rf.story-mcp.allow-writes" "true")
        (let [cfg (config/read-boot-config)]
          (is (true? (:allow-writes? cfg))
              "sysprop should flip allow-writes? on")))
      (testing "CLI flag wins when present alongside sysprop"
        ;; sysprop is still "true" from the previous step. The merge in
        ;; `boot!` is `(merge (config/read-boot-config) cli-cfg)` so a CLI
        ;; value clobbers the boot-config value — CLI > sysprop. The
        ;; precedence test asserts the merge produces the CLI value, not
        ;; the sysprop.
        (System/setProperty "rf.story-mcp.allow-writes" "true")
        (let [boot-cfg (config/read-boot-config)
              cli-cfg  (#'server/parse-args [])  ; CLI absent ⇒ no slot
              merged   (merge boot-cfg cli-cfg)]
          (is (true? (:allow-writes? merged))
              "CLI absent ⇒ sysprop value rides through")
          ;; Now with CLI explicitly absent of `--allow-writes`, the
          ;; merge yields the sysprop. To prove CLI > sysprop, we flip
          ;; the sysprop OFF and pass `--allow-writes` on the CLI — the
          ;; merge MUST be true (CLI wins).
          (System/setProperty "rf.story-mcp.allow-writes" "false")
          (let [boot-cfg-off (config/read-boot-config)
                cli-cfg-on   (#'server/parse-args ["--allow-writes"])
                merged-on    (merge boot-cfg-off cli-cfg-on)]
            (is (false? (:allow-writes? boot-cfg-off))
                "sysprop=false leaves boot-config false")
            (is (true? (:allow-writes? cli-cfg-on))
                "--allow-writes flips the CLI slot true")
            (is (true? (:allow-writes? merged-on))
                "CLI > sysprop: merge yields the CLI's true value"))))
      (finally
        (if restore
          (System/setProperty "rf.story-mcp.allow-writes" restore)
          (System/clearProperty "rf.story-mcp.allow-writes"))))))

;; ---------------------------------------------------------------------------
;; Boot-config sysprop > env precedence (rf2-09rfpu Finding 1)
;;
;; `read-boot-config` resolves each gate by SOURCE PRESENCE, not by a
;; boolean OR over parsed truthiness. The old `or` form let an inherited
;; env `true` re-enable a gate an operator explicitly disabled with a
;; `-D...=false` sysprop. Env vars are read-only on the JVM, so the
;; precedence rule is unit-tested against the pure `config/resolve-gate`
;; helper (raw source strings as args) — the exact branch the prior
;; `read-boot-config` test (sysprop alone / CLI overrides sysprop) could
;; never stage because it could not set env=true.
;; ---------------------------------------------------------------------------

(deftest resolve-gate-explicit-sysprop-false-overrides-env-true
  (testing "sysprop=false + env=true ⇒ gate OFF (explicit higher-precedence false wins)"
    (is (false? (config/resolve-gate "false" "true"))
        "an explicit -D...=false must disable an inherited env true"))
  (testing "sysprop unset + env=true ⇒ gate ON (falls through to env)"
    (is (true? (config/resolve-gate nil "true"))
        "absent sysprop falls through to the env var"))
  (testing "sysprop=true + env=false ⇒ gate ON (sysprop wins)"
    (is (true? (config/resolve-gate "true" "false"))
        "an explicit sysprop true overrides an env false"))
  (testing "sysprop=true + env unset ⇒ gate ON"
    (is (true? (config/resolve-gate "true" nil))))
  (testing "both sources absent ⇒ gate OFF (default-closed)"
    (is (false? (config/resolve-gate nil nil))))
  (testing "sysprop=false + env unset ⇒ gate OFF"
    (is (false? (config/resolve-gate "false" nil))))
  (testing "truthy-string vocabulary flows through unchanged"
    (is (true?  (config/resolve-gate nil "1")))
    (is (true?  (config/resolve-gate "yes" "false")))
    (is (false? (config/resolve-gate "off" "true")))))

(deftest read-boot-config-honours-explicit-sysprop-false-over-env
  ;; Integration check: with the env var almost certainly unset in CI,
  ;; an explicit `-Drf.story-mcp.allow-writes=false` keeps the gate OFF
  ;; (it does not silently fall through to a parsed `false` that an `or`
  ;; would have discarded). Both gates are exercised.
  (let [restore-w (System/getProperty "rf.story-mcp.allow-writes")
        restore-s (System/getProperty "rf.story-mcp.allow-sensitive-reads")]
    (try
      (System/setProperty "rf.story-mcp.allow-writes" "false")
      (System/setProperty "rf.story-mcp.allow-sensitive-reads" "false")
      (let [cfg (config/read-boot-config)]
        (is (false? (:allow-writes? cfg))
            "explicit sysprop=false keeps allow-writes? off")
        (is (false? (:allow-sensitive-reads? cfg))
            "explicit sysprop=false keeps allow-sensitive-reads? off"))
      (finally
        (if restore-w
          (System/setProperty "rf.story-mcp.allow-writes" restore-w)
          (System/clearProperty "rf.story-mcp.allow-writes"))
        (if restore-s
          (System/setProperty "rf.story-mcp.allow-sensitive-reads" restore-s)
          (System/clearProperty "rf.story-mcp.allow-sensitive-reads"))))))

;; ---------------------------------------------------------------------------
;; record-as-variant write-back failure path (rf2-36upq TE6)
;;
;; The `write-back!` helper wraps the `reg-variant*` call in a try/catch
;; that surfaces the registrar's `ex-data` (`:rf.error`/`:explain`) into
;; the tool's error result. Mirrors `register-variant-rejects-bad-shape`
;; — write-back is the SECOND write-surface tool that needs the same
;; defensive-failure assertion.
;; ---------------------------------------------------------------------------

(deftest record-as-variant-write-back-failure-surfaces-explain
  (testing "write-back failure surfaces the registrar's ex-data into the result"
    (config/set-allow-writes! true)
    ;; Drive the write-back into a registrar-level failure with a
    ;; VALID-GRAMMAR `:new-variant-id` (so it passes the rf2-tag30h
    ;; pre-intern grammar gate and the failure happens DOWNSTREAM in
    ;; `reg-variant*`). We force the registrar to throw with structured
    ;; ex-data and assert `write-back!` surfaces it. (The pre-intern
    ;; grammar reject for a MALFORMED id is covered separately in the
    ;; no-intern tests below — rf2-tag30h.)
    (drive-events-during-recording [[:counter/inc]])
    (with-redefs [story/reg-variant*
                  (fn [_id _body]
                    (throw (ex-info "Registration failed: boom"
                                    {:rf.error :rf.error/variant-shape
                                     :explain  {:why :forced-test-failure}})))]
      (let [r (invoke "record-as-variant"
                      {:variant-id     "story.button/primary"
                       :new-variant-id "story.button/recorded"  ; valid grammar
                       :duration-ms    50
                       :write-back     true})]
        (is (error? r) "a registrar write-back failure must surface as an error")
        (is (re-find #"(?i)Write-back failed" (-> r :content first :text))
            "the error text names the failure surface — agents pattern-match on this")
        (let [s (:structuredContent r)]
          (is (false? (:written-back? s))
              ":written-back? false rides through so callers see the no-op")
          (is (= :story.button/recorded (:new-variant-id s))
              "the failing target id round-trips so the agent can localise")
          (is (= :rf.error/variant-shape (:rf.error s))
              "the registrar's structured ex-data is surfaced for localisation"))))))

;; ---------------------------------------------------------------------------
;; record-as-variant unregistered :extends (rf2-ynjts.20)
;;
;; recorder.cljc resolves the caller-supplied `:extends` id through
;; `safe-keyword` against the registered-variant set; an unregistered id
;; resolves to nil and the tool short-circuits with the structured
;; `:rf.story-mcp/extends-not-registered` error (so the rendered snippet
;; never carries a dangling `:extends` reference). This branch was
;; untested — the only existing :extends test (`…-honours-doc-and-alias`)
;; exercises the DEFAULT (omitted ⇒ source vk), never the reject path.
;; ---------------------------------------------------------------------------

(deftest record-as-variant-rejects-unregistered-extends
  (testing ":extends naming an unregistered variant returns the structured reject"
    (let [r (invoke "record-as-variant"
                    {:variant-id "story.button/primary"
                     :extends    "story.nope/missing"})]
      (is (error? r))
      (is (re-find #"(?i):extends references an unregistered variant"
                   (-> r :content first :text)))
      (let [s (:structuredContent r)]
        (is (= :rf.story-mcp/extends-not-registered (:rf.error s))
            "the structured payload names the canonical reject reason")
        (is (= "record-as-variant" (:tool s)))
        (is (= "story.nope/missing" (:extends s))
            "the offending id round-trips so the agent can localise"))))
  (testing "a registered :extends is accepted (the happy peer of the reject)"
    (let [r (invoke "record-as-variant"
                    {:variant-id "story.button/primary"
                     :extends    "story.button/secondary"})
          snippet (-> r :structuredContent :play-snippet)]
      (is (success? r))
      (is (re-find #":extends :story\.button/secondary" snippet)
          "a registered :extends flows into the rendered snippet"))))

;; ---------------------------------------------------------------------------
;; record-as-variant :duration-ms ceiling (rf2-4yuhi)
;;
;; The MCP server's request loop is single-threaded; a `record-as-variant`
;; call sleeps the whole loop for the full :duration-ms window. The tool
;; validates against a hard ceiling (30000ms) and rejects abusive values.
;; ---------------------------------------------------------------------------

(deftest record-as-variant-rejects-duration-above-ceiling
  (testing ":duration-ms above the ceiling returns a structured error (rf2-4yuhi)"
    (let [over-ceiling (inc recorder-tool/max-duration-ms)
          r            (invoke "record-as-variant"
                               {:variant-id  "story.button/primary"
                                :duration-ms over-ceiling})]
      (is (error? r))
      (is (re-find #"exceeds ceiling" (-> r :content first :text)))
      (let [s (:structuredContent r)]
        (is (= :rf.story-mcp/duration-ms-too-large (:rf.error s)))
        (is (= "record-as-variant" (:tool s)))
        (is (= over-ceiling (:duration-ms s)))
        (is (= recorder-tool/max-duration-ms (:max-allowed s)))))))

(deftest record-as-variant-accepts-duration-at-ceiling-schema
  (testing "the schema's :maximum mirrors the runtime ceiling"
    (let [t      (some #(when (= "record-as-variant" (:name %)) %)
                       registry/tool-registry)
          dur-schema (-> t :inputSchema :properties :duration-ms)]
      (is (= recorder-tool/max-duration-ms (:maximum dur-schema))
          (str "the schema's :maximum slot mirrors the runtime ceiling so MCP "
               "clients can pre-validate without round-tripping a doomed call"))
      (is (zero? (:minimum dur-schema))
          ":minimum stays at 0 — the no-block default is canonical"))))

;; ---------------------------------------------------------------------------
;; Lifecycle :timeout-ms cap (rf2-g9fje fix 3/3 / rf2-ovmc5e)
;;
;; The single-threaded stdio loop parks for the full `:timeout-ms` window
;; — caller-supplied values clamp DOWN to `targs/max-timeout-ms`
;; (30 s, matches rf2-it1cd's `:rf.http/timeout-ms` baseline). A
;; legitimately-slow variant runs against the cap; a hostile caller can't
;; park the loop indefinitely.
;;
;; rf2-ovmc5e: `run-variant` AND `preview-variant` now share the same
;; bounded ceiling + tunable knob (`targs/resolve-timeout-ms` +
;; `s/with-timeout-ms`); both descriptors advertise `:timeout-ms` so the
;; two lifecycle tools cannot drift in their blocking policy.
;; ---------------------------------------------------------------------------

(deftest lifecycle-tools-timeout-ms-schema-advertises-ceiling
  (testing "run-variant + preview-variant :timeout-ms schema carries :maximum mirroring the runtime cap"
    (doseq [tool-name ["run-variant" "preview-variant"]]
      (let [t          (some #(when (= tool-name (:name %)) %) registry/tool-registry)
            ts-schema  (-> t :inputSchema :properties :timeout-ms)]
        (is (some? ts-schema)
            (str tool-name " advertises a :timeout-ms slot so an agent can tune the blocking ceiling"))
        (is (= targs/max-timeout-ms (:maximum ts-schema))
            (str tool-name " schema :maximum tracks the runtime cap so clients can pre-validate"))
        (is (= 1 (:minimum ts-schema))
            (str tool-name " :minimum stays at 1 — a zero-timeout doesn't make sense on a blocking call"))))))

(deftest lifecycle-timeout-ms-resolves-and-clamps
  ;; Pin the behavioural contract on the SHARED resolver both lifecycle
  ;; tools call: it MUST clamp values above the ceiling rather than reject.
  ;; A legitimate slow variant still runs (against the cap), the loop never
  ;; parks past 30 s. Exercising `targs/resolve-timeout-ms` directly proves
  ;; the advertised schema policy matches the runtime timeout policy.
  (testing "the shared resolver clamps, rides-through, and defaults"
    (is (= targs/max-timeout-ms (targs/resolve-timeout-ms {:timeout-ms 60000}))
        "60s caller-supplied → clamped to 30s ceiling")
    (is (= 5000 (targs/resolve-timeout-ms {:timeout-ms 5000}))
        "below-cap values ride through unchanged")
    (is (= targs/default-timeout-ms (targs/resolve-timeout-ms {}))
        "absent :timeout-ms uses the default")
    (is (= targs/default-timeout-ms (targs/resolve-timeout-ms {:timeout-ms "not-a-number"}))
        "unparseable :timeout-ms falls back to the default")))

;; ---------------------------------------------------------------------------
;; Protocol-side frame-length cap (rf2-g9fje fix 3/3)
;;
;; `BufferedReader.readLine` allocates unbounded memory for a one-line
;; frame that never sees a newline. The MCP server's stdio transport is
;; line-delimited per spec/2025-06-18/basic/transports; an attacker (or
;; a runaway producer) sending an unterminated frame would OOM the JVM.
;; `read-frame` now caps each frame at `proto/max-frame-bytes` (4 MB,
;; well above the largest legitimate MCP message); over-cap frames
;; throw `:rf.error/frame-too-large`, which the run-loop catches and
;; converts to a parse-error response.
;; ---------------------------------------------------------------------------

(deftest read-frame-rejects-oversize-frame
  (testing "a frame exceeding max-frame-bytes throws :rf.error/frame-too-large"
    (let [oversize (str (apply str (repeat (inc proto/max-frame-bytes) \x)) "\n")
          reader   (java.io.BufferedReader. (java.io.StringReader. oversize))]
      (try
        (proto/read-frame reader)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :rf.error/story-mcp-frame-too-large (:rf.error/id (ex-data e)))
              "ex-data carries the canonical :rf.error/id the run-loop dispatches on"))))))

(deftest read-frame-survives-after-oversize-frame
  (testing "the next frame after an oversize one is still readable"
    (let [oversize (apply str (repeat (inc proto/max-frame-bytes) \x))
          good     "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":7}"
          input    (str oversize "\n" good "\n")
          reader   (java.io.BufferedReader. (java.io.StringReader. input))]
      ;; First read throws (frame-too-large drains the oversize frame to
      ;; the next newline). Second read lands the good frame.
      (try (proto/read-frame reader) (catch clojure.lang.ExceptionInfo _ nil))
      (is (= {:jsonrpc "2.0" :method "ping" :id 7}
             (proto/read-frame reader))
          "post-cap recovery: stdio loop continues on the next frame"))))

;; ---------------------------------------------------------------------------
;; rf2-sibl4f — the frame cap is a UTF-8 BYTE budget, not a char count.
;;
;; The pre-fix `read-bounded-line` incremented its counter once per
;; decoded Java char and bounded on (>= chars max-bytes). For multibyte
;; UTF-8 input, decoded-char-count < wire-byte-count, so a frame whose
;; UTF-8 byte length exceeded max-frame-bytes could slip under the cap
;; while still being a multi-MB-over-budget wire payload — weakening the
;; DoS bound the spec + error message promise. The fix re-derives each
;; code point's UTF-8 byte width and bounds on the running byte total.
;;
;; The earlier oversize tests use only ASCII \x (char count == byte
;; count), so they could not catch this drift.
;; ---------------------------------------------------------------------------

;; Multibyte fixtures are built from `\uXXXX` / code-point escapes (pure
;; ASCII in the source) so the test is independent of the source file's
;; on-disk charset and the JVM's default charset.

(def ^:private cjk-3byte
  "U+4E2D — a CJK BMP code point that encodes to 3 UTF-8 bytes.
  Built from the code point (not a literal) so the source file's on-disk
  charset can't corrupt the fixture."
  (String. (Character/toChars 0x4E2D)))

(def ^:private emoji-4byte
  "U+1F600 (grinning face) — a supplementary code point: 2 Java chars
  (a surrogate pair) but 4 UTF-8 bytes on the wire."
  (String. (Character/toChars 0x1F600)))

(deftest read-frame-cap-counts-utf8-bytes-not-chars
  (testing "a multibyte frame UNDER the char count but OVER the byte cap is rejected"
    ;; The 3-byte CJK char lets us pick a CHARACTER count comfortably
    ;; BELOW max-frame-bytes (so a char counter would accept the frame)
    ;; yet whose UTF-8 BYTE length is ABOVE the cap (so the byte counter
    ;; rejects it). With a 3-byte char, half the cap in chars is ~1.5x
    ;; the cap in bytes.
    (let [char-count (+ (quot proto/max-frame-bytes 2) 1000)
          byte-count (* 3 char-count)]
      (is (< char-count proto/max-frame-bytes)
          "precondition: a char counter would ACCEPT this frame")
      (is (> byte-count proto/max-frame-bytes)
          "precondition: the frame's UTF-8 byte length EXCEEDS the cap")
      (let [multibyte (str (apply str (repeat char-count cjk-3byte)) "\n")
            reader    (java.io.BufferedReader. (java.io.StringReader. multibyte))]
        (try
          (proto/read-frame reader)
          (is false "should have thrown — the cap must fire on bytes, not chars")
          (catch clojure.lang.ExceptionInfo e
            (is (= :rf.error/story-mcp-frame-too-large (:rf.error/id (ex-data e)))
                "multibyte over-byte-budget frame rejected on the UTF-8 byte count")))))))

(deftest read-frame-cap-accepts-multibyte-frame-under-byte-budget
  (testing "a multibyte frame UNDER the byte cap parses normally"
    ;; A small valid JSON-RPC frame whose `:method` value carries
    ;; multibyte content: well under the cap on both chars and bytes, so
    ;; it must round-trip verbatim (proving the byte counter doesn't
    ;; over-reject legitimate non-ASCII frames). `:method` is an
    ;; allowlisted envelope key, so the value survives normalisation.
    (let [method (str "ping-" cjk-3byte cjk-3byte emoji-4byte)
          good   (proto/write-json {:jsonrpc "2.0" :method method :id 42})
          reader (java.io.BufferedReader. (java.io.StringReader. (str good "\n")))]
      (is (= {:jsonrpc "2.0" :method method :id 42}
             (proto/read-frame reader))
          "a legitimate multibyte frame under the byte budget is read verbatim"))))

(deftest read-frame-cap-counts-supplementary-code-points
  (testing "a frame of supplementary (surrogate-pair) code points caps on UTF-8 bytes"
    ;; U+1F600 is a supplementary code point: 2 Java chars (a surrogate
    ;; pair) but 4 UTF-8 bytes on the wire. A char counter sees 2 units
    ;; per emoji; a byte counter sees 4. We pick a count whose char
    ;; length is under the cap but whose UTF-8 byte length is over it,
    ;; proving the surrogate-pair recombination charges the full 4-byte
    ;; code-point width (not 2x the per-surrogate width).
    (let [emoji-cnt (+ (quot proto/max-frame-bytes 4) 1000)
          char-len  (* 2 emoji-cnt)
          byte-len  (* 4 emoji-cnt)]
      (is (< char-len proto/max-frame-bytes)
          "precondition: a char counter would ACCEPT this frame")
      (is (> byte-len proto/max-frame-bytes)
          "precondition: the frame's UTF-8 byte length EXCEEDS the cap")
      (let [frame  (str (apply str (repeat emoji-cnt emoji-4byte)) "\n")
            reader (java.io.BufferedReader. (java.io.StringReader. frame))]
        (try
          (proto/read-frame reader)
          (is false "should have thrown — supplementary code points count 4 bytes each")
          (catch clojure.lang.ExceptionInfo e
            (is (= :rf.error/story-mcp-frame-too-large (:rf.error/id (ex-data e)))
                "supplementary-plane over-byte-budget frame rejected on the UTF-8 byte count")))))))

;; ---------------------------------------------------------------------------
;; rf2-lqjbk — parse-keyword → safe-keyword sweep
;;
;; Caller-supplied keyword ids on the read surface MUST resolve through
;; `args/safe-keyword` against a bounded set, NOT through the legacy
;; `args/parse-keyword` which interns into the never-shrinking JVM
;; keyword table. The tests below assert the no-intern property by
;; calling each read-side tool with a fresh random-shaped id and
;; verifying that the underlying `find-keyword` returns nil after the
;; call (the rejection path didn't intern the string).
;; ---------------------------------------------------------------------------

(defn- find-kw
  "Find an existing interned keyword by namespace and name without
  interning. Returns nil when no such keyword has been interned —
  the asserting probe for the rf2-lqjbk no-intern contract."
  [ns-str name-str]
  (find-keyword ns-str name-str))

(deftest get-story-unknown-id-does-not-intern
  (testing "unknown :story-id rejects WITHOUT interning a fresh JVM keyword"
    (let [ns-str   "story.rf2-lqjbk-probe"
          name-str (str "unknown-" (System/nanoTime))
          r        (invoke "get-story" {:story-id (str ns-str "/" name-str)})]
      (is (error? r) "unknown story id must error")
      (is (re-find #"(?i)story not found" (-> r :content first :text)))
      (is (nil? (find-kw ns-str name-str))
          "rf2-lqjbk: the unknown id MUST NOT have been interned"))))

(deftest get-variant-unknown-id-does-not-intern
  (testing "unknown :variant-id rejects WITHOUT interning a fresh JVM keyword"
    (let [ns-str   "story.rf2-lqjbk-probe"
          name-str (str "unknown-variant-" (System/nanoTime))
          r        (invoke "get-variant" {:variant-id (str ns-str "/" name-str)})]
      (is (error? r))
      (is (re-find #"(?i)variant not found" (-> r :content first :text)))
      (is (nil? (find-kw ns-str name-str))
          "rf2-lqjbk: the unknown id MUST NOT have been interned"))))

(deftest read-failures-unknown-id-does-not-intern
  (testing "read-failures on an unknown :variant-id rejects WITHOUT interning"
    (let [ns-str   "story.rf2-lqjbk-probe"
          name-str (str "rf-" (System/nanoTime))
          r        (invoke "read-failures" {:variant-id (str ns-str "/" name-str)})]
      (is (error? r))
      (is (nil? (find-kw ns-str name-str))
          "rf2-lqjbk: the unknown id MUST NOT have been interned"))))

(deftest list-stories-unknown-tag-does-not-intern
  (testing "list-stories filter with an unknown :tags entry skips it WITHOUT interning"
    (let [name-str (str "rf2-lqjbk-tag-" (System/nanoTime))
          r        (invoke "list-stories" {:tags [name-str "dev"]})]
      (is (success? r) "the known :dev tag still narrows; the unknown tag is dropped")
      (is (nil? (find-kw nil name-str))
          "rf2-lqjbk: unknown tag id MUST NOT intern"))))

(deftest list-decorators-unknown-kind-rejects
  ;; rf2-cdavyf — a SUPPLIED `:kind` outside the bounded enum is an
  ;; agent-recoverable error, NOT a silent widen to the full catalogue.
  ;; (Pre-fix the typo resolved to nil and was treated as no filter,
  ;; returning EVERY decorator — hiding the caller's mistake behind a
  ;; successful-looking full result.) The no-intern invariant (rf2-lqjbk)
  ;; still holds: the unrecognised kind string never mints a fresh keyword.
  (testing ":kind filter with an unrecognised value REJECTS WITHOUT interning (rf2-cdavyf)"
    (let [name-str (str "rf2-cdavyf-kind-" (System/nanoTime))
          r        (invoke "list-decorators" {:kind name-str})
          s        (:structuredContent r)]
      (is (error? r) "an unrecognised :kind surfaces an isError diagnostic, not a full catalogue")
      (is (= :rf.story-mcp/unknown-decorator-kind (:rf.error s))
          "the structured error carries the unknown-decorator-kind id")
      (is (= name-str (:kind s)) "echoes the bad kind value")
      (is (= ["frame-setup" "fx-override" "hiccup"] (:allowed s))
          "lists the bounded enum so the agent can correct")
      (is (re-find #"(?i)unknown decorator kind" (-> r :content first :text)))
      (is (nil? (find-kw nil name-str))
          "rf2-cdavyf / rf2-lqjbk: unknown kind name MUST NOT intern"))))

(deftest list-decorators-known-kind-and-absent-kind-still-work
  ;; rf2-cdavyf — the reject path is PRESENT-but-unrecognised only. An
  ;; absent `:kind` (no filter requested) and a valid `:kind` both still
  ;; succeed.
  (testing "absent :kind returns the full catalogue; a valid :kind filters (rf2-cdavyf)"
    (let [no-filter (invoke "list-decorators" {})
          valid     (invoke "list-decorators" {:kind "hiccup"})]
      (is (success? no-filter) "absent :kind is the legitimate no-filter path")
      (is (vector? (-> no-filter :structuredContent :decorators)))
      (is (success? valid) "a recognised :kind filters rather than rejecting")
      (is (vector? (-> valid :structuredContent :decorators))))))

(deftest run-variant-unknown-substrate-does-not-intern
  (testing "run-variant :substrate with an unknown value is dropped WITHOUT interning"
    ;; A registered variant; the unknown :substrate is dropped from the
    ;; opts map (run-variant tolerates an absent slot), so the call still
    ;; succeeds but the substrate name never enters the keyword table.
    (let [name-str (str "rf2-lqjbk-sub-" (System/nanoTime))
          r        (invoke "run-variant" {:variant-id "story.button/primary"
                                          :substrate  name-str})]
      (is (success? r))
      (is (nil? (find-kw nil name-str))
          "rf2-lqjbk: unknown substrate id MUST NOT intern"))))

(deftest run-loop-survives-oversize-frame
  (testing "an oversize frame produces a parse-error response and the loop continues"
    (let [oversize (apply str (repeat (inc proto/max-frame-bytes) \x))
          in-text  (str oversize "\n"
                        "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"ping\"}\n")
          reader   (java.io.BufferedReader. (java.io.StringReader. in-text))
          sw       (java.io.StringWriter.)
          err      (java.io.StringWriter.)]
      (binding [*err* err]
        (server/run-loop! reader sw))
      (let [out-lines (filter seq (clojure.string/split-lines (.toString sw)))
            frames    (mapv #(cheshire.core/parse-string % true) out-lines)]
        (is (= 2 (count frames)) "one parse-error + one ping response")
        (is (= vocab/code-parse-error (-> (nth frames 0) :error :code))
            "oversize frame routes through the parse-error response shape")
        (is (= 11 (:id (nth frames 1))) "the loop continued to the next frame")))))

;; ---------------------------------------------------------------------------
;; rf2-3luf3 — MCP JSON ingress must NOT intern attacker-controlled nested
;; keys before the bounded allowlists run.
;;
;; The pre-fix `protocol/parse-json` called `(json/parse-string s true)`,
;; recursively keywordising EVERY object key in the frame — including
;; arbitrary keys under `params.arguments`, `cell-overrides`, and write
;; bodies — and interning them into the never-shrinking JVM keyword table
;; BEFORE `tools.args/safe-keyword` could reject them. That both grows the
;; keyword table without bound (a slow-burn DoS) and can let an unknown
;; string key intern into a keyword a downstream allowlist then resolves.
;;
;; The fix parses the frame string-keyed and keywordises ONLY the finite
;; JSON-RPC envelope keys + the bounded top-level argument-key allowlist
;; (no-intern via `find-keyword`); nested data-bearing maps keep string
;; keys and are routed through each surface's own bounded keyword policy.
;;
;; These tests drive the FULL stdio path (`server/run-loop!` /
;; `proto/read-frame` over a real JSON frame) — the gap the existing
;; direct-`invoke` no-intern tests (rf2-lqjbk) leave open, since those
;; bypass `parse-json` / `read-frame`.
;; ---------------------------------------------------------------------------

(defn- run-frames!
  "Drive `server/run-loop!` over `in-text` (one JSON frame per line) and
  return the parsed response frames (keywordised for assertion
  ergonomics). stderr is captured so the test output stays clean.

  rf2-e6knrq: the dispatcher now enforces the MCP lifecycle — a
  `tools/call` / `tools/list` before `initialize` is refused with
  `-32600`. These no-intern wire tests exercise the TOOL surface, not
  the lifecycle gate, so the helper PREPENDS an `initialize` frame to
  complete the handshake and DROPS its response from the returned vector.
  Callers' `(first frames)` / `(count frames)` assertions are therefore
  unaffected — they still see only the response(s) to `in-text`. The
  prepended handshake uses a string `:id` (`\"rf2-e6knrq-init\"`) that
  cannot collide with any caller's numeric ids."
  [in-text]
  (let [init-frame (str "{\"jsonrpc\":\"2.0\",\"id\":\"rf2-e6knrq-init\","
                        "\"method\":\"initialize\","
                        "\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n")
        reader (java.io.BufferedReader. (java.io.StringReader. (str init-frame in-text)))
        sw     (java.io.StringWriter.)
        err    (java.io.StringWriter.)]
    (binding [*err* err]
      (server/run-loop! reader sw))
    (->> (clojure.string/split-lines (.toString sw))
         (filter seq)
         (mapv #(cheshire.core/parse-string % true))
         ;; Drop the prepended handshake's response so callers see only
         ;; the responses to their own `in-text` frames.
         (drop-while #(= "rf2-e6knrq-init" (:id %)))
         vec)))

(deftest ingress-does-not-intern-unknown-nested-arguments-key
  (testing "a fresh unknown nested :arguments key is NOT interned over the wire (rf2-3luf3)"
    (let [probe-name (str "rf2-3luf3-nested-probe-" (System/nanoTime))
          ;; The attacker slips an unknown key into the arguments map of a
          ;; tools/call. Pre-fix, parse-json interned it immediately.
          frame      (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                          "\"params\":{\"name\":\"get-variant\","
                          "\"arguments\":{\"" probe-name "\":1,"
                          "\"variant-id\":\"story.button/primary\"}}}\n")]
      (is (nil? (find-keyword probe-name))
          "precondition: the probe keyword has not been interned yet")
      (let [frames (run-frames! frame)]
        (is (= 1 (count frames)) "one tools/call response")
        (is (= 1 (:id (first frames))) "the legitimate call still dispatched"))
      (is (nil? (find-keyword probe-name))
          "rf2-3luf3: the attacker-supplied nested arguments key MUST NOT have been interned"))))

(deftest ingress-does-not-intern-unknown-envelope-key
  (testing "a stray unknown top-level envelope key is NOT interned over the wire (rf2-3luf3)"
    (let [probe-name (str "rf2-3luf3-envelope-probe-" (System/nanoTime))
          frame      (str "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"" probe-name "\":99}\n")]
      (is (nil? (find-keyword probe-name)) "precondition")
      (run-frames! frame)
      (is (nil? (find-keyword probe-name))
          "rf2-3luf3: a stray envelope key MUST NOT intern"))))

(deftest ingress-does-not-intern-cell-overrides-key
  (testing "an unknown :cell-overrides KEY is NOT interned over the wire (rf2-3luf3)"
    (let [probe-name (str "rf2-3luf3-cell-probe-" (System/nanoTime))
          frame      (str "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                          "\"params\":{\"name\":\"preview-variant\","
                          "\"arguments\":{\"variant-id\":\"story.button/primary\","
                          "\"cell-overrides\":{\"" probe-name "\":\"x\"}}}}\n")]
      (is (nil? (find-keyword probe-name)) "precondition")
      (run-frames! frame)
      (is (nil? (find-keyword probe-name))
          "rf2-3luf3: an unknown cell-override key (outside the variant's declared args) MUST NOT intern"))))

(deftest read-run-opts-keeps-known-cell-override-key-drops-unknown
  (testing "a declared arg key is kept (keywordised) and an unknown one dropped (rf2-3luf3)"
    ;; story.button/primary declares :args {:label "Save"} — so :label is
    ;; in its bounded arg-key set; a random key is not. Simulate the
    ;; post-parse-json string-keyed cell-overrides shape.
    (let [probe (str "rf2-3luf3-co-" (System/nanoTime))
          opts  (targs/read-run-opts :story.button/primary
                                     {:cell-overrides {"label" "Override"
                                                       probe   "x"}})
          co    (:cell-overrides opts)]
      (is (= "Override" (:label co)) "the declared :label override keywordised + kept")
      (is (= #{:label} (set (keys co))) "the unknown override key was dropped")
      (is (nil? (find-keyword probe)) "and never interned"))))

(deftest read-run-opts-allows-active-mode-introduced-cell-override-key
  (testing "an override for an arg introduced ONLY by an active mode is preserved (rf2-to3q7)"
    ;; story.button/primary declares :args {:label "Save"} — :theme is
    ;; NOT among its base args. The fixture's :Mode.theme/dark mode
    ;; contributes :args {:theme :dark}. Story precedence merges mode
    ;; args before cell-local overrides, so with that mode active a
    ;; caller :theme override is a LEGITIMATE override target. Before
    ;; rf2-to3q7 the allowlist was built from the bare variant (no
    ;; active modes), so the :theme override was dropped as 'unknown'
    ;; and the render fell back to the mode's :dark value.
    (let [probe (str "rf2-to3q7-co-" (System/nanoTime))
          opts  (targs/read-run-opts
                  :story.button/primary
                  {:active-modes   [":Mode.theme/dark"]
                   :cell-overrides {"theme" ":light"   ; arg introduced by the active mode
                                    "label" "Override"  ; arg on the variant itself
                                    probe   "x"}})      ; genuinely-unknown key
          co    (:cell-overrides opts)]
      (is (= [:Mode.theme/dark] (:active-modes opts)) "the mode coerced through the bounded set")
      (is (= ":light" (:theme co))
          "rf2-to3q7: the mode-introduced :theme override is PRESERVED, not dropped")
      (is (= "Override" (:label co)) "the variant's own :label override is kept")
      (is (= #{:theme :label} (set (keys co)))
          "the genuinely-unknown key is still dropped — the allowlist widened only to the mode args")
      (is (nil? (find-keyword probe)) "the unknown key never interned"))))

(deftest read-run-opts-without-active-mode-still-drops-mode-only-key
  (testing "without the active mode, the mode-only arg key is correctly NOT an allowed override (rf2-to3q7)"
    ;; Mirror of the test above with the mode absent: :theme is not in
    ;; the variant's effective args, so the override IS unknown and must
    ;; drop — proving the widening is scoped to the ACTIVE modes, not a
    ;; blanket relaxation.
    (let [opts (targs/read-run-opts
                 :story.button/primary
                 {:cell-overrides {"theme" ":light" "label" "Override"}})
          co   (:cell-overrides opts)]
      (is (= #{:label} (set (keys co)))
          "with no active mode, :theme is not a declared arg and the override drops"))))

(deftest ingress-unknown-variant-id-over-wire-does-not-intern
  (testing "an unknown :variant-id sent over JSON is rejected WITHOUT interning (rf2-3luf3)"
    ;; This is the wire-level peer of the rf2-lqjbk direct-invoke test —
    ;; it proves the allowlist still gates correctly once parse-json no
    ;; longer pre-interns the value.
    (let [ns-str   "story.rf2-3luf3-wire"
          name-str (str "unknown-" (System/nanoTime))
          frame    (str "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                        "\"params\":{\"name\":\"get-variant\","
                        "\"arguments\":{\"variant-id\":\"" ns-str "/" name-str "\"}}}\n")]
      (is (nil? (find-keyword ns-str name-str)) "precondition")
      (let [frames (run-frames! frame)
            result (-> frames first :result)]
        (is (true? (:isError result)) "unknown variant id errors as a tool result")
        (is (re-find #"(?i)variant not found" (-> result :content first :text))))
      (is (nil? (find-keyword ns-str name-str))
          "rf2-3luf3: the unknown variant id MUST NOT have been interned over the wire"))))

(deftest ingress-legitimate-wire-keys-still-dispatch
  (testing "known JSON wire arg keys still normalise + dispatch correctly (rf2-3luf3)"
    ;; variant-id + max-tokens are read by get-variant / the wire-pipeline;
    ;; both must survive the no-intern normalisation. (rf2-an95jj —
    ;; `:include-sensitive` is deliberately NOT exercised here: get-variant
    ;; surfaces no value-bearing slot, so it does not advertise that knob,
    ;; and the per-tool schema check now rejects it for this tool. The
    ;; `:include-sensitive` no-intern path is covered on the tools that DO
    ;; advertise it — preview-variant / run-variant / read-failures etc.)
    (let [frame  (str "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                      "\"params\":{\"name\":\"get-variant\","
                      "\"arguments\":{\"variant-id\":\"story.button/primary\","
                      "\"max-tokens\":4000}}}\n")
          frames (run-frames! frame)
          result (-> frames first :result)]
      (is (= 5 (:id (first frames))))
      (is (not (true? (:isError result))) "the legitimate call succeeds")
      ;; get-variant's structuredContent carries the resolved variant under
      ;; `:id`; over JSON the keyword serialises to a bare string. Its
      ;; presence proves `variant-id` keywordised + resolved through the
      ;; allowlist (an unresolved id would have produced an :isError result).
      (is (= "story.button/primary" (-> result :structuredContent :id))
          "the variant-id arg keywordised + resolved through the allowlist")
      (is (= "Primary button." (-> result :structuredContent :body :doc))
          "the variant body came back, confirming a real dispatch"))))

(deftest register-variant-object-body-over-wire-keywordises-under-gate
  (testing "an object-form :body sent as JSON registers with keyword slots (rf2-3luf3 write-path)"
    ;; Post no-intern ingress the object-form body arrives string-keyed;
    ;; `coerce-body` re-keywordises it under the (operator-gated) write
    ;; path. This proves the spec's documented "object body (preferred)"
    ;; form keeps working over the real wire — its keys become keywords.
    (config/set-allow-writes! true)
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/wire-obj"
                     ;; Simulate the post-parse-json wire shape: a
                     ;; STRING-keyed object body.
                     :body {"doc"  "Object body over wire."
                            "args" {"label" "OK"}}})]
      (is (success? r) "object-form body registers under the write gate")
      (let [edn (story/variant->edn :story.button/wire-obj)]
        (is (= "Object body over wire." (:doc edn))
            "the body's top-level string keys were keywordised")
        (is (= "OK" (-> edn :args :label))
            "nested object-body keys were keywordised recursively")))))

(deftest register-variant-object-body-rejects-overdeep
  (testing "an object-form :body past the depth cap is rejected, not interned (rf2-3luf3 / rf2-g9fje)"
    (config/set-allow-writes! true)
    ;; Build a string-keyed map nested far past max-edn-depth (64). The
    ;; depth check in coerce-body must reject it BEFORE keywordize-body-keys
    ;; walks (and interns) any of the pathological keys.
    (let [probe (str "rf2-3luf3-deep-" (System/nanoTime))
          deep  (reduce (fn [acc i] {(str probe "-" i) acc})
                        {(str probe "-leaf") 1}
                        (range 70))
          r     (invoke "register-variant"
                        {:variant-id "story.button/wire-deep" :body deep})]
      (is (error? r) "an over-deep object body is rejected")
      (is (nil? (find-keyword (str probe "-leaf")))
          "rf2-3luf3: a rejected over-deep body MUST NOT have interned its keys"))))

(deftest normalize-frame-drops-unknown-arg-keys-but-keeps-known
  (testing "normalize-frame keeps allowlisted arg keys (keyword), drops + DIAGNOSES the rest (rf2-3luf3 / rf2-ovmc5e)"
    (let [probe   (str "rf2-3luf3-drop-" (System/nanoTime))
          parsed  (proto/parse-json
                   (str "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                        "\"params\":{\"name\":\"run-variant\","
                        "\"arguments\":{\"variant-id\":\"story.button/primary\","
                        "\"" probe "\":1,\"substrate\":\"reagent\"}}}"))
          norm    (proto/normalize-frame parsed)
          arg-map (-> norm :params :arguments)]
      (is (= "story.button/primary" (:variant-id arg-map)) "known key keywordised + kept")
      (is (= "reagent" (:substrate arg-map)) "known key keywordised + kept")
      (is (= #{:variant-id :substrate} (set (keys arg-map)))
          "ONLY the two allowlisted keys survive as ENTRIES; the unknown key is dropped at both string + keyword form")
      ;; NB: do not call `(keyword probe)` here — that would itself intern
      ;; the probe and defeat the no-intern assertion below.
      (is (nil? (find-keyword probe)) "and the unknown key never interned")
      ;; rf2-ovmc5e — the unknown key is no longer SILENTLY dropped: its
      ;; RAW STRING form is recorded as metadata (not a map entry, so it
      ;; never reaches a handler or interns) for the dispatcher to diagnose.
      (is (= [probe] (get (meta arg-map) proto/unknown-arg-keys-meta))
          "the dropped key's raw string is recorded as metadata for the diagnostic")
      (is (nil? (find-keyword probe))
          "recording the metadata STRING still interns nothing"))))

(deftest invoke-tool-diagnoses-unknown-top-level-argument
  ;; rf2-ovmc5e Finding #1 — a top-level argument typo (a non-schema-
  ;; validating host or hand-rolled agent sending `:timeuot-ms` etc.) must
  ;; surface an agent-recoverable `isError: true` result naming the unknown
  ;; key, the tool, and the allowed key set — NOT a successful-looking call
  ;; that silently defaulted. The server is the authoritative backstop for
  ;; the advertised `additionalProperties false` contract.
  (testing "an unknown top-level arg key surfaces an isError diagnostic before dispatch"
    (let [probe   (str "rf2-ovmc5e-typo-" (System/nanoTime))
          parsed  (proto/parse-json
                   (str "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                        "\"params\":{\"name\":\"run-variant\","
                        "\"arguments\":{\"variant-id\":\"story.button/primary\","
                        "\"" probe "\":1}}}"))
          norm    (proto/normalize-frame parsed)
          arg-map (-> norm :params :arguments)
          r       (wire-pipeline/invoke-tool "run-variant" arg-map)
          s       (:structuredContent r)]
      (is (error? r) "unknown top-level arg ⇒ isError result")
      (is (= :rf.story-mcp/unknown-arguments (:rf.error s))
          "the structured error carries the unknown-arguments id")
      (is (= "run-variant" (:tool s)) "names the tool")
      (is (= [probe] (:unknown s)) "echoes the RAW unknown key string")
      (is (contains? (set (:allowed s)) "variant-id")
          "lists the tool's allowed arg-key set so the agent can correct")
      (is (contains? (set (:allowed s)) "timeout-ms")
          "the allowed set is the tool's full advertised property set, not the global union")
      (is (nil? (find-keyword probe))
          "diagnosing the typo never interned the unknown key"))))

(deftest invoke-tool-no-diagnostic-when-all-args-recognised
  ;; The common path: every top-level key is allowlisted ⇒ no metadata,
  ;; no diagnostic, the handler runs normally.
  (testing "all-recognised args carry no unknown-arg metadata + dispatch normally"
    (let [parsed  (proto/parse-json
                   (str "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
                        "\"params\":{\"name\":\"run-variant\","
                        "\"arguments\":{\"variant-id\":\"story.button/primary\","
                        "\"substrate\":\"reagent\"}}}"))
          arg-map (-> (proto/normalize-frame parsed) :params :arguments)]
      (is (nil? (get (meta arg-map) proto/unknown-arg-keys-meta))
          "no dropped keys ⇒ no unknown-arg metadata")
      (let [r (wire-pipeline/invoke-tool "run-variant" arg-map)]
        (is (success? r) "an all-recognised call dispatches the handler normally")))))

;; ---------------------------------------------------------------------------
;; rf2-an95jj — per-tool argument-schema enforcement AFTER the global
;; no-intern normalisation.
;;
;; `protocol/normalize-frame` only drops keys outside the UNION of every
;; tool's argument keys (`protocol/arg-keys`). A key valid for ANOTHER
;; tool — `:body` (register-variant), `:write-back` (record-as-variant),
;; `:dedup` on a non-eligible tool — therefore SURVIVES normalisation as a
;; keyword entry and used to be silently ignored by the selected handler.
;; The per-tool check (`tool-invalid-arg-keys`) is the descriptor-level
;; `additionalProperties false` backstop: it rejects globally-known keys
;; the SELECTED tool doesn't advertise, with the same
;; `:rf.story-mcp/unknown-arguments` shape as the global-unknown diagnostic.
;; ---------------------------------------------------------------------------

(deftest invoke-tool-rejects-tool-invalid-but-globally-known-arg
  ;; rf2-an95jj acceptance — `get-variant` with `:body`. `:body` is a real
  ;; key (register-variant advertises it) so it survives the global
  ;; allowlist as a keyword entry; but get-variant does NOT advertise it.
  (testing "a globally-known but tool-invalid arg (`:body` on get-variant) rejects (rf2-an95jj)"
    (let [r (wire-pipeline/invoke-tool "get-variant"
                                       {:variant-id "story.button/primary" :body "x"})
          s (:structuredContent r)]
      (is (error? r) "tool-invalid arg ⇒ isError result, not a silent ignore + success")
      (is (= :rf.story-mcp/unknown-arguments (:rf.error s))
          "uses the unknown-arguments diagnostic shape")
      (is (= "get-variant" (:tool s)) "names the tool")
      (is (= ["body"] (:unknown s)) "names the tool-invalid key")
      (is (contains? (set (:allowed s)) "variant-id")
          "lists the tool's advertised arg-key set")
      (is (not (contains? (set (:allowed s)) "body"))
          "the tool's allowed set does NOT include the rejected key"))))

(deftest invoke-tool-rejects-write-back-on-run-variant
  ;; rf2-an95jj acceptance — `run-variant` with `:write-back`. `:write-back`
  ;; is advertised by record-as-variant; run-variant does not advertise it.
  (testing "`:write-back` on run-variant rejects (globally-known, tool-invalid) (rf2-an95jj)"
    (let [r (wire-pipeline/invoke-tool "run-variant"
                                       {:variant-id "story.button/primary"
                                        :write-back true})
          s (:structuredContent r)]
      (is (error? r))
      (is (= :rf.story-mcp/unknown-arguments (:rf.error s)))
      (is (= "run-variant" (:tool s)))
      (is (= ["write-back"] (:unknown s))))))

(deftest invoke-tool-tolerates-dedup-on-non-eligible-tool
  ;; rf2-an95jj — `:dedup` is advertised only on the three dedup-eligible
  ;; tools but is DOCUMENTED as silently ignored elsewhere (it is a
  ;; wire-managed knob the dispatcher gates on `:dedup-eligible?`). So a
  ;; `:dedup` on a non-eligible tool must NOT trip the per-tool diagnostic
  ;; — otherwise the test-helper default (`{:dedup false}` on every call)
  ;; would break every non-eligible-tool test.
  (testing "`:dedup` on a non-eligible tool is tolerated, not rejected (rf2-an95jj)"
    (let [r (wire-pipeline/invoke-tool "get-variant"
                                       {:variant-id "story.button/primary" :dedup false})]
      (is (success? r) "a wire-managed knob on a non-advertising tool dispatches normally"))))

(deftest invoke-tool-tolerates-include-sensitive-on-advertising-tool
  ;; rf2-an95jj — `:include-sensitive` on a tool that DOES advertise it
  ;; (read-failures) is tool-valid and must dispatch (the slot lives in the
  ;; descriptor's properties regardless of the operator gate).
  (testing "`:include-sensitive` on a tool that advertises it dispatches (rf2-an95jj)"
    (let [r (wire-pipeline/invoke-tool "read-failures"
                                       {:variant-id "story.button/primary"
                                        :include-sensitive false})]
      (is (success? r) "an advertised value-knob is tool-valid and dispatches"))))

;; ---------------------------------------------------------------------------
;; rf2-p0eiq3 — pre-dispatch error envelopes ride the response cap.
;;
;; `spec/Principles.md §Tight token budget` bounds EVERY tool response,
;; errors included. The pre-dispatch rejection branches (unknown-arg,
;; invalid-`:max-tokens`, per-tool unknown-arg) used to return BEFORE the
;; cap — so a caller packing many long unknown keys inside the 4 MB frame
;; cap received an uncapped diagnostic echoing them all back. The cap now
;; applies to those envelopes too.
;; ---------------------------------------------------------------------------

(deftest unknown-arg-error-rides-the-response-cap
  ;; rf2-p0eiq3 — many large unknown keys produce an overflow marker under
  ;; a small cap rather than an uncapped echo. The unknown keys ride as
  ;; metadata (no-intern), so we build the frame through normalise-frame.
  (testing "an unknown-argument diagnostic overflows under a small cap (rf2-p0eiq3)"
    (let [big-keys (apply str
                          (for [i (range 200)]
                            (str "\"unknown-key-" i "-"
                                 (apply str (repeat 80 \x))
                                 "\":1,")))
          frame    (str "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                        "\"params\":{\"name\":\"get-variant\","
                        "\"arguments\":{" big-keys
                        "\"variant-id\":\"story.button/primary\"}}}")
          arg-map  (-> (proto/normalize-frame (proto/parse-json frame))
                       :params :arguments)
          ;; sanity: the unknown keys WERE recorded as metadata
          _        (is (seq (get (meta arg-map) proto/unknown-arg-keys-meta))
                       "the many unknown keys are recorded for the diagnostic")
          capped   (wire-pipeline/invoke-tool "get-variant"
                                              (with-meta (assoc arg-map :max-tokens 5)
                                                (meta arg-map)))]
      (is (overflow-marker? capped)
          "the uncapped echo of many long unknown keys is replaced by the overflow marker")
      (is (= "get-variant" (get-in capped [:structuredContent vocab/overflow-key :tool]))
          "the overflow marker names the tool"))))

(deftest invalid-max-tokens-error-is-bounded
  ;; rf2-p0eiq3 — the invalid-`:max-tokens` rejection envelope is small and
  ;; bespoke, but it must still ride the cap path (default cap, since the
  ;; caller's cap was malformed). It is well under the default, so it
  ;; passes through intact — proving the cap path is exercised without
  ;; tripping, and the rejection shape is preserved (rf2-5rdit).
  (testing "the invalid-max-tokens rejection is routed through the cap and preserved (rf2-p0eiq3)"
    (let [r    (wire-pipeline/invoke-tool "list-tags" {:max-tokens -1})
          body (get-in r [:structuredContent vocab/invalid-arg-key])]
      (is (true? (:isError r)))
      (is (not (overflow-marker? r)) "a tiny rejection is under the default cap")
      (is (= :max-tokens (:arg body)) "the rejection shape survives the cap path")
      (is (= -1 (:value body))))))

(deftest tool-invalid-arg-error-rides-the-response-cap
  ;; rf2-p0eiq3 + rf2-an95jj — the per-tool unknown-arg diagnostic is
  ;; capped too. The diagnostic echoes the offending key names + the tool's
  ;; allowed set; throwing every globally-known key get-variant does NOT
  ;; advertise (~18 of them) makes that echo exceed a 1-token cap, so the
  ;; per-tool diagnostic must overflow rather than return uncapped.
  (testing "a per-tool unknown-arg diagnostic overflows under a tiny cap (rf2-p0eiq3)"
    (let [tool-invalid {:story-id "x" :new-variant-id "x" :extends "x"
                        :substrate "x" :active-modes ["x"] :cell-overrides {}
                        :base-url "x" :body "x" :doc "x" :alias "x"
                        :tags ["x"] :kind "x" :write-back true
                        :duration-ms 1 :timeout-ms 1}
          r (wire-pipeline/invoke-tool "get-variant"
                                       (assoc tool-invalid
                                              :variant-id "story.button/primary"
                                              :max-tokens 1))]
      ;; All those keys are globally-known (in `protocol/arg-keys`) but
      ;; tool-invalid for get-variant, so they survive normalisation and
      ;; the per-tool check diagnoses them.
      (is (overflow-marker? r)
          "the capped per-tool diagnostic replaces the uncapped echo — proving it rides the cap path")
      (is (= "get-variant" (get-in r [:structuredContent vocab/overflow-key :tool]))))))

;; ---------------------------------------------------------------------------
;; Cap accounting includes :structuredContent size (rf2-mzndx)
;;
;; Pre-fix, only `:content[*].text` strings counted toward the cap, while
;; `text-result` writes the same payload into BOTH `:content` and
;; `:structuredContent` — the cap underestimated wire by ~50% on every
;; structured tool. The new accounting sums both slots under one budget.
;; ---------------------------------------------------------------------------

(deftest cap-counts-structured-content-size
  (testing "structuredContent contributes to the cap (rf2-mzndx)"
    ;; A list-stories call ships the same payload in both slots. The
    ;; cap with structured accounting must be HIGHER than the cap that
    ;; only counts `:text` — assert the wire-side sum reflects both.
    (let [r          (wire-pipeline/invoke-tool "list-stories" {:max-tokens 0})
          text-only  (let [io (reify base-cap/ResultIO
                                (content-texts [_ result]
                                  (map :text (:content result)))
                                (build-overflow-result [_ _m _o] {}))]
                       (base-cap/sum-text-tokens io r))
          with-struct (base-cap/sum-text-tokens test-io r)]
      ;; The `test-io` mirror counts structured content (see rf2-mzndx
      ;; update at top of file). It MUST report more tokens than the
      ;; text-only baseline whenever the result carries a non-nil
      ;; `:structuredContent`.
      (is (some? (:structuredContent r))
          "list-stories must ship a structured slot for this assertion to bite")
      (is (> with-struct text-only)
          (str "structured slot must contribute extra tokens: "
               "with-struct=" with-struct " text-only=" text-only)))))

(deftest cap-trips-on-structured-content-alone
  (testing "a tiny cap trips when only structuredContent is large (rf2-mzndx)"
    ;; The cap must fire on the combined size — not silently let a
    ;; payload through just because its :text slot fits.
    (let [r (wire-pipeline/invoke-tool "list-stories" {:max-tokens 1})]
      ;; With `:max-tokens 1`, both the text AND structured slots
      ;; combined exceed the cap, so we expect the overflow marker.
      (is (overflow-marker? r)
          "tiny cap must fire on combined text+structured size")
      (let [body (get-in r [:structuredContent vocab/overflow-key])]
        (is (pos? (:token-count body))
            ":token-count reflects the over-budget count")
        (is (= 1 (:cap-tokens body)))))))
