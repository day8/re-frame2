(ns re-frame.story-mcp.descriptor-manifest-gen
  "story-mcp tool-descriptor manifest generator + drift-check.

  Mirrors the API-governance keystone's generate-then-drift-check shape
  on story-mcp's MCP descriptor surface.

  SOURCE OF TRUTH. `re-frame.story-mcp.tools.registry/tool-registry` —
  the ordered vector that bundles every story-mcp tool's name,
  description, inputSchema, outputSchema, annotations + handler. This
  generator reads that registry (NOT the config-dependent
  `tool-descriptors` projection — the manifest must be deterministic, so
  it reads the raw schemas, NOT the live operator gate) and projects
  each entry into the stable catalogue row defined by
  `re-frame.mcp-base.descriptor-manifest`.

  GATED INPUTS. The raw registry descriptor carries the
  FULL `tools/list` input surface — including `:include-sensitive`, the
  knob the default profile strips behind `--allow-sensitive-reads`.
  Projecting it as a flat `:input-keys` would make the manifest claim
  `:include-sensitive` is on the DEFAULT surface, which it is not
  (closed-by-default). The generator passes `registry/gated-input-keys`
  (the config-independent set of keys the server gates off by default —
  the SAME set `registry/strip-include-sensitive` removes at
  `tools/list` time) to `dm/build-manifest`, so each row records the
  gated subset in `:gated-input-keys`. The committed manifest
  distinguishes the two deterministic profiles: the default surface is
  `:input-keys` minus `:gated-input-keys`; the gate-open surface is
  `:input-keys` verbatim. The set is static, so the manifest stays
  deterministic + config-independent.

  THE ARTEFACT. `tool-descriptors.edn` (next to this server's source
  root) — the committed, byte-stable projection of the registry.

  DRIFT-CHECK. `-main --check` regenerates the manifest in memory and
  compares it to the committed file (LF-normalised). Adding / removing /
  renaming a tool in the registry — or changing a tool's input-key
  surface, required argument set, output? / annotations classification,
  or typicalTokens hint — turns this RED in CI until the manifest is
  regenerated with `-main` (no args).

  story-mcp bakes its universal `max-tokens` knob into each descriptor's
  `:inputSchema :properties` at def-time (via `schemas/with-max-tokens`),
  so — unlike re-frame2-pair-mcp, which splices the knob at `tools/list`
  time — the raw registry descriptor already carries the actual
  `tools/list` input surface; no generator-side knob splice is needed.

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
  (dm/build-manifest server-id registry/tool-registry registry/gated-input-keys))

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

(defn check!
  "Regenerate in memory + compare to the committed file. Returns true
  when in sync, false (with a printed diff summary) when drifted."
  []
  (let [manifest  (build)
        edn       (dm/render-edn manifest)
        f         (manifest-file)
        committed (when (.exists ^java.io.File f) (slurp f))
        {:keys [ok? added removed changed missing-file?]} (dm/check manifest edn committed)]
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
                       "(new/renamed tool):")
              (doseq [n added] (println "    +" n)))
            (when (seq removed)
              (println "  Tools in the committed file the registry no longer has"
                       "(removed/renamed tool):")
              (doseq [n removed] (println "    -" n)))
            (when (seq changed)
              (println "  Existing tools whose descriptor catalogue row changed"
                       "(input-keys / gated-input-keys / required / output? / annotations / description / typicalTokens):")
              (doseq [{:keys [name old new]} changed]
                (println "    ~" name)
                (doseq [[slot o n] (row-slot-deltas old new)]
                  (println (format "        %s: %s -> %s" (clojure.core/name slot)
                                   (pr-str o) (pr-str n))))))
            (when (and (empty? added) (empty? removed) (empty? changed))
              (println "  (tool set identical; the committed file is structurally"
                       "broken or its provenance banner/meta drifted — regenerate)")))
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
