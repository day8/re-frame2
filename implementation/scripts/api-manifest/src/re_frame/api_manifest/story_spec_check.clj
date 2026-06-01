(ns re-frame.api-manifest.story-spec-check
  "Story API-spec projection check (rf2-gkp0t).

  `tools/story/spec/API.md` is the consolidated Story public-API surface
  — a human-readable projection of the manifest's `re-frame.story` rows.
  This check parses every var-row in that doc (a table row whose first
  cell is a single back-tick-quoted identifier) and asserts each resolves
  to a manifest `re-frame.story` row. A row naming a var the manifest's
  Story surface does not carry — a renamed / removed facade fn — turns
  this red.

  SCOPE. Story's API.md mixes `re-frame.story` FACADE var-rows (the
  resolution target) with sub-namespace surfaces (`re-frame.story.theme.*`,
  `re-frame.story.ui.*`, `re-frame.story.xray-preset/*`) that live OUTSIDE
  the manifest's whole-namespace introspection set, plus a handful of
  CLJS-only facade vars the JVM generator cannot `ns-publics`. Those are
  carried on the `:story-spec-known-unmanifested` allowlist in the sidecar
  (silenced once, by name) so any OTHER unresolved facade reference still
  fails. Keyword-id rows (`:story/set-arg`, `:rf.assert/*`), namespace
  cells, and prose cells are not var-rows and are skipped by construction."
  (:require [re-frame.api-manifest.gen :as gen]
            [re-frame.api-manifest.projection :as proj]))

(def ^:private story-ns "re-frame.story")

(defn check!
  []
  (let [rows       (proj/manifest-rows)
        story-vars (set (map :var (proj/rows-in-ns rows story-ns)))
        allow      (set (:story-spec-known-unmanifested (gen/read-sidecar)))
        file       (proj/repo-file "tools" "story" "spec" "API.md")
        rel        (proj/repo-relative file)
        var-rows   (proj/table-var-rows (proj/numbered-lines file))
        problems   (keep (fn [{:keys [var line]}]
                           (when-not (or (contains? story-vars var)
                                         (contains? allow var))
                             {:file rel :line line :raw var
                              :detail "no re-frame.story manifest row"}))
                         var-rows)]
    (proj/report-result! "tools/story/spec/API.md" (count var-rows) problems)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
