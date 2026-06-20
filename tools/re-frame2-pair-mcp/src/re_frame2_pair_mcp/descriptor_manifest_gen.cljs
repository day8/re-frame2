(ns re-frame2-pair-mcp.descriptor-manifest-gen
  "re-frame2-pair-mcp tool-descriptor manifest generator + drift-check.

  An API-governance keystone: a generate-then-drift-check over
  re-frame2-pair-mcp's MCP descriptor surface — the CLJS/Node
  counterpart to story-mcp's JVM generator.

  SOURCE OF TRUTH. `re-frame2-pair-mcp.tools.registry/tool-descriptors`
  — the ordered vector of raw `:descriptor` payloads projected from the
  single `registry/tools` catalogue. This generator reads that vector
  and projects each into the stable catalogue row defined by
  `re-frame.mcp-base.descriptor-manifest`.

  WIRE-SPLICED INPUTS. The raw registry descriptors do NOT
  carry the universal `max-tokens` / `cache` input knobs — those are
  spliced onto every descriptor's `:inputSchema :properties` at
  `tools/list` time by `tools/descriptors.cljs` (`with-budget-knob` /
  `with-cache-knob`). Projecting the RAW descriptors hid that universal
  input surface from the manifest, so a drift in the knob set (or in a
  tool's read/action cacheability classification, which decides whether
  `cache` is spliced) could not trip the drift gate. The splices are
  DETERMINISTIC — they depend only on the descriptor + the static
  `registry/cacheable?` predicate, not on any operator flag — so this
  generator applies them (via `descriptors/with-cache-knob` ∘
  `with-budget-knob`, the SAME composers `tools/list` uses) BEFORE
  projecting. The manifest's `:input-keys` therefore reflect the ACTUAL
  `tools/list` input surface, and the manifest stays deterministic +
  config-independent.

  GATED INPUTS. pair-mcp's `tools/list` surface gates NO
  input key off behind an operator flag — its `eval-cljs` gate is
  default-ON and gates a whole TOOL, not an input property;
  the `max-tokens` / `cache` splices above are deterministic, not gated.
  So this generator passes NO gated-key set to `dm/build-manifest`
  (defaulting to `#{}`), and every pair-mcp row carries
  `:gated-input-keys []` — the uniform empty slot. (Contrast story-mcp,
  which passes `#{\"include-sensitive\"}` for its six value-surfacing
  tools.)

  THE ARTEFACT. `tool-descriptors.edn` at this artefact's root — the
  committed, byte-stable projection of the registry. BYTE-IDENTICAL in
  shape to story-mcp's because both servers route through the same
  `re-frame.mcp-base.descriptor-manifest/render-edn` serialiser.

  CLJS-ONLY. The pair-mcp registry requires CLJS-only namespaces (it
  pulls per-tool handler fns), so — unlike story-mcp's JVM generator —
  this one cannot run on the bare JVM classpath. It compiles to a Node
  script (`:descriptor-gen` build in shadow-cljs.edn) and runs under
  Node, reading / writing the committed file via Node `fs`.

  DRIFT-CHECK. `-main --check` regenerates the manifest in memory and
  compares it to the committed file (LF-normalised). Adding / removing /
  renaming a tool in the registry — or changing a tool's input-key
  surface (including the spliced `max-tokens` / `cache` knobs), required
  argument set, output? / annotations classification, or typicalTokens
  hint — exits non-zero (turns CI red) until the manifest is regenerated
  with `-main` (no args).

  Run (via the npm wrapper, from tools/re-frame2-pair-mcp/):
    node scripts/descriptor-manifest-gen.cjs           ; regenerate
    node scripts/descriptor-manifest-gen.cjs --check    ; drift-check (CI)"
  (:require ["fs" :as fs]
            ["path" :as path]
            [re-frame.mcp-base.descriptor-manifest :as dm]
            [re-frame2-pair-mcp.tools.descriptors :as descriptors]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(def ^:private server-id :re-frame2-pair-mcp)

(defn manifest-path
  "Absolute path to the committed manifest. The compiled Node script
  lives at `out/descriptor-gen.js`; the manifest is one dir up at the
  artefact root, so we resolve it relative to the script's own
  directory (`js/__dirname`) — robust to the launch CWD."
  []
  (path/resolve js/__dirname ".." "tool-descriptors.edn"))

(defn build
  "Build the manifest from the registry descriptors, with the universal
  `max-tokens` / `cache` knobs spliced in FIRST so the
  projected `:input-keys` reflect the actual `tools/list` input surface.
  Reuses the very composers `tools/descriptors/tool-descriptors-js`
  applies at `tools/list` time — `with-cache-knob` ∘ `with-budget-knob`,
  in the same order — so the manifest's input surface and the live wire
  surface can never silently diverge. The composition is deterministic
  (config-independent: it consults only the descriptor + the static
  `registry/cacheable?` predicate), so the manifest stays byte-stable."
  []
  (let [spliced (mapv (comp descriptors/with-cache-knob
                            descriptors/with-budget-knob)
                      registry/tool-descriptors)]
    (dm/build-manifest server-id spliced)))

(defn generate! []
  (let [manifest (build)
        p        (manifest-path)
        edn      (dm/render-edn manifest)]
    (.writeFileSync fs p edn)
    (println (str "Wrote " p " (" (-> manifest :meta :tool-count) " tools)."))
    manifest))

(defn- row-slot-deltas
  "Seq of `[slot old-val new-val]` for the catalogue slots that differ
  between a tool's committed `old` row and regenerated `new` row. Names
  exactly which of the manifest's governed slots drifted so a descriptor-
  only change is actionable without a manual whole-manifest diff."
  [old new]
  (for [slot [:description :input-keys :gated-input-keys :required :output? :annotations :typicalTokens]
        :let [o (get old slot)
              n (get new slot)]
        :when (not= o n)]
    [slot o n]))

(defn check! []
  (let [manifest  (build)
        edn       (dm/render-edn manifest)
        p         (manifest-path)
        committed (when (.existsSync fs p)
                    (.toString (.readFileSync fs p)))
        {:keys [ok? added removed changed missing-file?]} (dm/check manifest edn committed)]
    (if ok?
      (do (println (str "OK: tool-descriptors.edn in sync ("
                        (-> manifest :meta :tool-count) " tools)."))
          true)
      (do (binding [*print-fn* *print-err-fn*]
            (if missing-file?
              (println "DRIFT: tool-descriptors.edn does not exist. Run the generator.")
              (println "DRIFT: generated manifest differs from tool-descriptors.edn."))
            (println "Regenerate with: node scripts/descriptor-manifest-gen.cjs")
            (when (seq added)
              (println "  Tools the registry has that the committed file lacks (new/renamed tool):")
              (doseq [n added] (println "    +" n)))
            (when (seq removed)
              (println "  Tools in the committed file the registry no longer has (removed/renamed tool):")
              (doseq [n removed] (println "    -" n)))
            (when (seq changed)
              (println "  Existing tools whose descriptor catalogue row changed (input-keys / gated-input-keys / required / output? / annotations / description / typicalTokens):")
              (doseq [{:keys [name old new]} changed]
                (println "    ~" name)
                (doseq [[slot o n] (row-slot-deltas old new)]
                  (println (str "        " (cljs.core/name slot) ": "
                                (pr-str o) " -> " (pr-str n))))))
            (when (and (empty? added) (empty? removed) (empty? changed))
              (println "  (tool set identical; the committed file is structurally broken or its provenance banner/meta drifted — regenerate)")))
          false))))

(defn -main [& args]
  (try
    (if (some #{"--check"} args)
      (js/process.exit (if (check!) 0 1))
      (do (generate!) (js/process.exit 0)))
    (catch :default t
      (binding [*print-fn* *print-err-fn*]
        (println "re-frame2-pair-mcp descriptor-manifest generator FAILED:")
        (println (str t)))
      (js/process.exit 2))))
