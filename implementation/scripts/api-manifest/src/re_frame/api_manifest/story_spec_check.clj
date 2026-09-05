(ns re-frame.api-manifest.story-spec-check
  "Story API-spec projection check (rf2-gkp0t).

  `tools/story/spec/API.md` is the consolidated Story public-API surface
  — a human-readable projection of the manifest's `re-frame.story` rows.
  This check parses every var-row in that doc (a table row whose first
  cell is a single back-tick-quoted identifier) and asserts each resolves
  to a manifest `re-frame.story` row. A row naming a var the manifest's
  Story surface does not carry — a renamed / removed facade fn — turns
  this red.

  SCOPE. Story's API.md documents more than the `re-frame.story` facade, and
  two different mechanisms keep the extra material out of this check.

  Skipped BY CONSTRUCTION — any first cell that is not one back-tick-quoted
  simple identifier: the qualified and dotted sub-namespace spellings
  (`re-frame.story.theme.typography`, `xray-preset/wire-cross-host!`,
  `keybindings/bindings`), keyword-id rows (`:story/set-arg`,
  `:rf.assert/*`), namespace cells and prose cells. No allowlist involved.

  Silenced BY NAME — the two sub-namespaces that document their vars with a
  BARE first cell, and so do parse as var-rows while living outside the
  manifest's whole-namespace introspection set: `re-frame.story.test-support`
  (`use-fixtures`, `with-clean-registry`) and `re-frame.story.ui.xray-embed`
  (`xray-embed-panel`, `mount-fn-for`, `popout-full-shell!`). Those five, and
  only those, are the sidecar's `:story-spec-known-unmanifested` set, so any
  OTHER unresolved reference still fails.

  NO FACADE EXPORT IS ON THAT ALLOWLIST, and none may be added. Four once
  were: `re-frame.story` is a split-host `.cljc` whose `#?(:cljs …)` arm
  publishes five facade fns the JVM generator cannot `ns-publics`, and
  silencing them kept those four out of the manifest entirely — beyond the
  reach of the facade-audit invariants (tier / action / justification).
  rf2-i6kh dropped the exemptions and gave all five real `:cljs-only` rows,
  which this check resolves like any other var-row. A facade var missing from
  the manifest is a row to add, never a name to silence here."
  (:require [re-frame.api-manifest.gen :as gen]
            [re-frame.api-manifest.projection :as proj]))

(def ^:private story-ns "re-frame.story")

(def ^:private min-var-rows
  "Non-vacuous floor (rf2-utvst). tools/story/spec/API.md carries ~65
   back-ticked-identifier var-rows; this floor sits well below that, so it
   trips only when the table shape changes (the first-cell back-ticked-
   identifier discriminator stops matching) or the doc is gutted — the
   cases that would turn the gate into a vacuous green — never on ordinary
   row churn."
  20)

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
    (proj/report-with-floor! "tools/story/spec/API.md"
                             (count var-rows) min-var-rows problems)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
