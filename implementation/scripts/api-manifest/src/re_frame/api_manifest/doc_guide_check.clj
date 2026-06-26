(ns re-frame.api-manifest.doc-guide-check
  "Doc-guide projection check (rf2-gkp0t).

  The human guide (`docs/guide/**/*.md`) teaches re-frame2 through worked
  code. Every code sample names front-porch / advanced vars through the
  `rf` alias. A sample naming a renamed / removed var teaches a call that
  no longer resolves. This check extracts every call-position `(rf/<var>`
  reference and asserts each resolves to a manifest `re-frame.core` row.

  CAPABILITY CONCEPT DOCS (rf2-earvtz). The #4961 docs reorg moved each
  capability's CONCEPT doc out of the flat `docs/guide/` tree into
  per-capability `docs/<cap>/concepts.md` files (machines / resources /
  routing / ssr). Those concept docs teach the same worked code and so are
  scanned by this gate alongside `docs/guide/` — the `docs/*/concepts.md`
  glob folds them in (and auto-covers a future capability dir). Without this,
  a broken `(rf/<var>` reference in a moved concept doc would slip through CI.

  SCOPE — same call-position discipline as the skills check (anchor on the
  leading `(` so the `:rf/*` reserved keyword namespace is excluded, not
  swept in as bogus var references).

  V1-MIGRATION CHAPTER. `docs/guide/25-from-re-frame-v1.md` names removed
  re-frame v1 APIs (`on-changes`, …) as the left-hand side of migration
  guidance, exactly as the migration SKILL does. Its old-name mentions are
  carried on the `:doc-guide-known-unmanifested-scoped` allowlist rather
  than excluding the whole chapter, because the chapter ALSO teaches live
  re-frame2 vars worth checking. Template placeholders the guide uses in a
  syntax table (`set-x!` / `install-x!` — `<x>` stand-ins, not real vars)
  ride the same allowlist.

  FILE-SCOPED ALLOWLIST (rf2-hk6wd2). The removed-name allowlist used to be
  a BARE-NAME set: a name listed there was silenced ANYWHERE in the guide
  tree. EP-0017 removed `inject-cofx`, and live teaching prose must use
  `:rf.cofx/requires` — but a bare allowlist would let a future live
  `(rf/inject-cofx …)` in any teaching chapter pass green, since the bare
  name `inject-cofx` was unconditionally silenced. The allowlist is now
  `:doc-guide-known-unmanifested-scoped` — `{removed-name -> #{approved
  repo-relative file paths}}`. A call-position reference to a removed name
  is silenced ONLY in its approved migration file(s); the SAME reference in
  any other guide file is RED (it leaked into live teaching prose). This is
  the negative check the bead asks for: live guide chapters cannot call
  removed EP-0017 (or EP-0015/EP-0018) APIs outside approved migration
  contexts."
  (:require [re-frame.api-manifest.gen :as gen]
            [re-frame.api-manifest.projection :as proj]))

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
  (let [rows         (proj/manifest-rows)
        core-vars    (set (map :var (proj/rows-in-ns rows core-ns)))
        scoped-allow (:doc-guide-known-unmanifested-scoped (gen/read-sidecar))
        dir          (proj/repo-file "docs" "guide")
        ;; require-markdown-files (rf2-utvst): fail loudly if docs/guide/
        ;; moves or is renamed, rather than silently checking zero files.
        guide-files  (proj/require-markdown-files "docs/guide/" dir)
        ;; Per-capability CONCEPT docs (rf2-earvtz). The #4961 docs reorg moved
        ;; each capability's concept doc out of the flat docs/guide/ tree into
        ;; docs/<cap>/concepts.md (machines/resources/routing/ssr). The
        ;; docs/*/concepts.md glob folds those moved files back under this
        ;; gate's scan so a broken `(rf/<var>` reference in them goes RED; it
        ;; auto-covers a future capability dir with no gate edit, and fails
        ;; loudly (require-*) if the layout moves again.
        concept-files (proj/require-capability-doc-files
                        "docs/*/concepts.md" "concepts.md")
        files        (concat guide-files concept-files)
        references   (for [file files
                           ref  (proj/alias-call-references "rf" (proj/numbered-lines file))]
                       (assoc ref :file (proj/repo-relative file)))
        var-problems (reconcile {:references   references
                                 :core-vars    core-vars
                                 :scoped-allow scoped-allow})
        ;; Keyword-drift guards (rf2-tawage / rf2-uhew69 / rf2-1zjkn8): the
        ;; var-resolution reconcile above cannot see stale EP-0017
        ;; `:rf.world/inputs`, EP-0011 reply-envelope (`:stale-key` / bare
        ;; `:work-id`), or EP-0015 egress-profile keyword vocabulary creeping
        ;; back into teaching prose. Fold the keyword scan in so a
        ;; reintroduction goes RED here too.
        kw-problems  (proj/keyword-drift-problems-over-files files)
        problems     (concat var-problems kw-problems)]
    (proj/report-with-floor! "docs/guide/ + docs/*/concepts.md"
                             (count references) min-references problems)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
