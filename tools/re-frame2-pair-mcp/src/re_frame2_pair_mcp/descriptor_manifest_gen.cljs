(ns re-frame2-pair-mcp.descriptor-manifest-gen
  "re-frame2-pair-mcp tool-descriptor manifest generator + drift-check
  (rf2-sofwv).

  Follow-on to the rf2-3nbl5.2 API-governance keystone, mirroring its
  generate-then-drift-check shape on re-frame2-pair-mcp's MCP descriptor
  surface — the CLJS/Node counterpart to story-mcp's JVM generator.

  SOURCE OF TRUTH. `re-frame2-pair-mcp.tools.registry/tool-descriptors`
  — the ordered vector of raw `:descriptor` payloads projected from the
  single `registry/tools` catalogue. This generator reads that vector
  (the RAW descriptors, BEFORE the universal `max-tokens` / `cache` knob
  splices applied at `tools/list` time — the manifest must be
  deterministic and config-independent) and projects each into the
  stable catalogue row defined by
  `re-frame.mcp-base.descriptor-manifest`.

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
  surface / output? / annotations classification — exits non-zero
  (turns CI red) until the manifest is regenerated with `-main` (no
  args).

  Run (via the npm wrapper, from tools/re-frame2-pair-mcp/):
    node scripts/descriptor-manifest-gen.cjs           ; regenerate
    node scripts/descriptor-manifest-gen.cjs --check    ; drift-check (CI)"
  (:require ["fs" :as fs]
            ["path" :as path]
            [re-frame.mcp-base.descriptor-manifest :as dm]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(def ^:private server-id :re-frame2-pair-mcp)

(defn manifest-path
  "Absolute path to the committed manifest. The compiled Node script
  lives at `out/descriptor-gen.js`; the manifest is one dir up at the
  artefact root, so we resolve it relative to the script's own
  directory (`js/__dirname`) — robust to the launch CWD."
  []
  (path/resolve js/__dirname ".." "tool-descriptors.edn"))

(defn build []
  (dm/build-manifest server-id registry/tool-descriptors))

(defn generate! []
  (let [manifest (build)
        p        (manifest-path)
        edn      (dm/render-edn manifest)]
    (.writeFileSync fs p edn)
    (println (str "Wrote " p " (" (-> manifest :meta :tool-count) " tools)."))
    manifest))

(defn check! []
  (let [manifest  (build)
        edn       (dm/render-edn manifest)
        p         (manifest-path)
        committed (when (.existsSync fs p)
                    (.toString (.readFileSync fs p)))
        {:keys [ok? added removed missing-file?]} (dm/check manifest edn committed)]
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
              (println "  Tools the registry has that the committed file lacks (new/renamed tool, or changed catalogue shape):")
              (doseq [n added] (println "    +" n)))
            (when (seq removed)
              (println "  Tools in the committed file the registry no longer has (removed/renamed tool):")
              (doseq [n removed] (println "    -" n)))
            (when (and (empty? added) (empty? removed))
              (println "  (tool set identical; a descriptor's input-keys / output? / annotations changed — regenerate)")))
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
