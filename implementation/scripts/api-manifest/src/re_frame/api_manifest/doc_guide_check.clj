(ns re-frame.api-manifest.doc-guide-check
  "Doc-guide projection check (rf2-gkp0t).

  The human guide (`docs/guide/**/*.md`) teaches re-frame2 through worked
  code. Every code sample names front-porch / advanced vars through the
  `rf` alias. A sample naming a renamed / removed var teaches a call that
  no longer resolves. This check extracts every call-position `(rf/<var>`
  reference and asserts each resolves to a manifest `re-frame.core` row.

  SCOPE — same call-position discipline as the skills check (anchor on the
  leading `(` so the `:rf/*` reserved keyword namespace is excluded, not
  swept in as bogus var references).

  V1-MIGRATION CHAPTER. `docs/guide/25-from-re-frame-v1.md` names removed
  re-frame v1 APIs (`on-changes`, …) as the left-hand side of migration
  guidance, exactly as the migration SKILL does. Its old-name mentions are
  carried on the `:doc-guide-known-unmanifested` allowlist (by bare name)
  rather than excluding the whole chapter, because the chapter ALSO teaches
  live re-frame2 vars worth checking. Template placeholders the guide uses
  in a syntax table (`set-x!` / `install-x!` — `<x>` stand-ins, not real
  vars) ride the same allowlist."
  (:require [re-frame.api-manifest.gen :as gen]
            [re-frame.api-manifest.projection :as proj]))

(def ^:private core-ns "re-frame.core")

(defn check!
  []
  (let [rows      (proj/manifest-rows)
        core-vars (set (map :var (proj/rows-in-ns rows core-ns)))
        allow     (set (:doc-guide-known-unmanifested (gen/read-sidecar)))
        dir       (proj/repo-file "docs" "guide")
        files     (proj/markdown-files dir)
        results   (for [file files
                        ref  (proj/alias-call-references "rf" (proj/numbered-lines file))]
                    (assoc ref :file (proj/repo-relative file)))
        problems  (keep (fn [{:keys [var line raw file]}]
                          (when-not (or (contains? core-vars var)
                                        (contains? allow var))
                            {:file file :line line :raw raw
                             :detail "no re-frame.core manifest row"}))
                        results)]
    (proj/report-result! "docs/guide/" (count results) problems)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
