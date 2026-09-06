(ns re-frame.api-manifest.doc-guide-check
  "Documentation-guide projection check.

  The human guide (`docs/core/**/*.md`) teaches re-frame2 through worked
  code. Every code sample names front-porch / advanced vars through the
  `rf` alias. A sample naming a renamed / removed var teaches a call that
  no longer resolves. This check extracts every call-position `(rf/<var>`
  reference and asserts each resolves to a manifest `re-frame.core` row.

  Per-capability `docs/<cap>/concepts.md` files are scanned alongside
  `docs/core/`; the one-level glob also discovers future capability docs.

  SCOPE — same call-position discipline as the skills check (anchor on the
  leading `(` so the `:rf/*` reserved keyword namespace is excluded, not
  swept in as bogus var references).

  V1-MIGRATION CHAPTER. The migration guide names removed
  re-frame v1 APIs (`on-changes`, …) as the left-hand side of migration
  guidance, exactly as the migration SKILL does. Its old-name mentions are
  carried on the `:doc-guide-known-unmanifested-scoped` allowlist rather
  than excluding the whole chapter, because the chapter ALSO teaches live
  re-frame2 vars worth checking. Template placeholders the guide uses in a
  syntax table (`set-x!` / `install-x!` — `<x>` stand-ins, not real vars)
  ride the same allowlist.

  FILE-SCOPED ALLOWLIST. `:doc-guide-known-unmanifested-scoped` maps each
  removed name to the repo-relative migration files allowed to mention it in
  call position. The same reference in live teaching prose remains an error."
  (:require [clojure.string :as str]
            [re-frame.api-manifest.gen :as rf.api-manifest.gen]
            [re-frame.api-manifest.projection :as rf.api-manifest.projection]))

(def ^:private core-ns "re-frame.core")

(def ^:private min-references
  "Non-vacuous floor (rf2-utvst). The guide carries ~504 live `(rf/<var>`
   references across ~30 chapters; this floor sits an order of magnitude
   below that, so it trips only on a near-total collapse (the guide dir
   moved, the `(rf/<var>` extraction broke, the alias convention changed) —
   the cases that would otherwise turn the gate into a vacuous green —
   never on ordinary content churn."
  100)

(defn reconcile
  "Pure reconciler (rf2-hk6wd2 — extracted so the file-scoped allowlist
   contract is unit-testable with synthetic inputs). Returns the seq of
   problem maps for the supplied call-position references.

   `references`  — `[{:var :line :raw :file} ...]` (`:file` repo-relative).
   `core-vars`   — set of bare var names the manifest carries on
                   `re-frame.core` (these always resolve).
   `scoped-allow`— `{removed-name -> #{approved repo-relative file paths}}`
                   (the `:doc-guide-known-unmanifested-scoped` sidecar key).

   A reference resolves (no problem) when its var is a `re-frame.core`
   manifest row, OR its var is on the scoped allowlist AND its file is in
   that name's approved-file set. A reference to a scoped removed name in a
   NON-approved file is flagged as a removed-API leak into live teaching
   prose; an unknown name with no manifest row and no scope entry is flagged
   as an unresolved reference."
  [{:keys [references core-vars scoped-allow]}]
  (keep (fn [{:keys [var line raw file]}]
          (cond
            (contains? core-vars var) nil
            (contains? scoped-allow var)
            (when-not (contains? (get scoped-allow var) file)
              {:file file :line line :raw raw
               :detail (format (str "removed API named outside its approved "
                                    "migration file(s) %s — live guide prose "
                                    "must not call removed APIs")
                               (vec (sort (get scoped-allow var))))})
            :else
            {:file file :line line :raw raw
             :detail "no re-frame.core manifest row"}))
        references))

(defn check!
  []
  (let [rows         (rf.api-manifest.projection/manifest-rows)
        core-vars    (set (map :var (rf.api-manifest.projection/rows-in-ns rows core-ns)))
        scoped-allow (:doc-guide-known-unmanifested-scoped (rf.api-manifest.gen/read-sidecar))
        dir          (rf.api-manifest.projection/repo-file "docs" "core")
        ;; require-markdown-files (rf2-utvst): fail loudly if docs/core/
        ;; moves or is renamed, rather than silently checking zero files.
        ;;
        ;; EXCLUDE docs/core/api/** — the framework API REFERENCE tree. It
        ;; lives under docs/core/ (Core's API section) but is the
        ;; doc-api-check surface, not guide teaching prose. doc-api-check
        ;; resolves its call-position `(rf/<var>` references with
        ;; CROSS-NAMESPACE bare-name latitude — the reference legitimately
        ;; names re-frame.core, re-frame.machines, … vars under the one `rf`
        ;; alias. doc-guide-check resolves only against re-frame.core, so
        ;; scanning the API reference here would false-positive on every
        ;; non-core surface it names (e.g. `rf/machine-meta`). doc-api-check
        ;; already covers this subtree for BOTH var-resolution and
        ;; keyword-drift, so the exclusion loses no coverage.
        guide-files  (->> (rf.api-manifest.projection/require-markdown-files "docs/core/" dir)
                          (remove #(str/starts-with? (rf.api-manifest.projection/repo-relative %)
                                                     "docs/core/api/")))
        ;; Capability concept docs are a required one-level docs/* surface;
        ;; discovery covers new capabilities and fails loudly if the surface
        ;; disappears.
        concept-files (rf.api-manifest.projection/require-capability-doc-files
                        "docs/*/concepts.md" "concepts.md")
        files        (concat guide-files concept-files)
        references   (for [file files
                           ref  (rf.api-manifest.projection/alias-call-references "rf" (rf.api-manifest.projection/numbered-lines file))]
                       (assoc ref :file (rf.api-manifest.projection/repo-relative file)))
        var-problems (reconcile {:references   references
                                 :core-vars    core-vars
                                 :scoped-allow scoped-allow})
        ;; Var resolution cannot see stale EP-0017
        ;; `:rf.world/inputs`, EP-0011 reply-envelope (`:stale-key` / bare
        ;; `:work-id`), or EP-0015 egress-profile keyword vocabulary creeping
        ;; back into teaching prose. Fold the keyword scan in so a
        ;; reintroduction goes RED here too.
        kw-problems  (rf.api-manifest.projection/keyword-drift-problems-over-files files)
        problems     (concat var-problems kw-problems)]
    (rf.api-manifest.projection/report-with-floor! "docs/core/ + docs/*/concepts.md"
                             (count references) min-references problems)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
