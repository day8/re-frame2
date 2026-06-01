(ns re-frame.story-mcp.descriptor-manifest-gen
  "story-mcp tool-descriptor manifest generator + drift-check (rf2-sofwv).

  Follow-on to the rf2-3nbl5.2 API-governance keystone, mirroring its
  generate-then-drift-check shape on story-mcp's MCP descriptor surface.

  SOURCE OF TRUTH. `re-frame.story-mcp.tools.registry/tool-registry` —
  the ordered vector that bundles every story-mcp tool's name,
  description, inputSchema, outputSchema, annotations + handler. This
  generator reads that registry (NOT the config-dependent
  `tool-descriptors` projection — the manifest must be deterministic, so
  it reads the raw schemas before the operator-gate `:include-sensitive`
  strip) and projects each entry into the stable catalogue row defined
  by `re-frame.mcp-base.descriptor-manifest`.

  THE ARTEFACT. `tool-descriptors.edn` (next to this server's source
  root) — the committed, byte-stable projection of the registry.

  DRIFT-CHECK. `-main --check` regenerates the manifest in memory and
  compares it to the committed file (LF-normalised). Adding / removing /
  renaming a tool in the registry — or changing a tool's input-key
  surface / output? / annotations classification — turns this RED in CI
  until the manifest is regenerated with `-main` (no args).

  Run from tools/story-mcp/:
    clojure -M:gen                  ; regenerate tool-descriptors.edn
    clojure -M:gen --check          ; drift-check (CI)"
  (:require [clojure.java.io :as io]
            [re-frame.mcp-base.descriptor-manifest :as dm]
            [re-frame.story-mcp.tools.registry :as registry]))

(def ^:private server-id :story-mcp)

(defn manifest-file
  "The committed manifest, resolved relative to the JVM `user.dir`
  (the deps.edn directory — tools/story-mcp/) so the generator works
  from any CWD on any platform."
  []
  (io/file (System/getProperty "user.dir") "tool-descriptors.edn"))

(defn build []
  (dm/build-manifest server-id registry/tool-registry))

(defn generate!
  "Regenerate tool-descriptors.edn from the live registry."
  []
  (let [manifest (build)
        f        (manifest-file)
        edn      (dm/render-edn manifest)]
    (spit f edn)
    (println (format "Wrote %s (%d tools)."
                     (.getPath ^java.io.File f)
                     (-> manifest :meta :tool-count)))
    manifest))

(defn check!
  "Regenerate in memory + compare to the committed file. Returns true
  when in sync, false (with a printed diff summary) when drifted."
  []
  (let [manifest  (build)
        edn       (dm/render-edn manifest)
        f         (manifest-file)
        committed (when (.exists ^java.io.File f) (slurp f))
        {:keys [ok? added removed missing-file?]} (dm/check manifest edn committed)]
    (if ok?
      (do (println (format "OK: tool-descriptors.edn in sync (%d tools)."
                           (-> manifest :meta :tool-count)))
          true)
      (do (binding [*out* *err*]
            (if missing-file?
              (println "DRIFT: tool-descriptors.edn does not exist. Run: clojure -M:gen")
              (println "DRIFT: generated manifest differs from tool-descriptors.edn."))
            (println "Regenerate with: clojure -M:gen (from tools/story-mcp/)")
            (when (seq added)
              (println "  Tools the registry has that the committed file lacks"
                       "(new/renamed tool, or changed catalogue shape):")
              (doseq [n added] (println "    +" n)))
            (when (seq removed)
              (println "  Tools in the committed file the registry no longer has"
                       "(removed/renamed tool):")
              (doseq [n removed] (println "    -" n)))
            (when (and (empty? added) (empty? removed))
              (println "  (tool set identical; a descriptor's input-keys / output? /"
                       "annotations changed — regenerate)")))
          false))))

(defn -main [& args]
  (try
    (if (some #{"--check"} args)
      (System/exit (if (check!) 0 1))
      (do (generate!) (System/exit 0)))
    (catch Throwable t
      (binding [*out* *err*]
        (println "story-mcp descriptor-manifest generator FAILED:")
        (println (.getMessage t)))
      (System/exit 2))))
