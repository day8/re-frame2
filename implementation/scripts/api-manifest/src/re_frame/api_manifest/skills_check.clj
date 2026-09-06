(ns re-frame.api-manifest.skills-check
  "Skills projection check (rf2-gkp0t).

  The re-frame2 skills (`skills/**/*.md`) teach authors against the public
  API. A skill that names a renamed / removed var teaches a broken call.
  This check extracts every call-position `(rf/<var>` reference (the
  alias-qualified `re-frame.core` surface authors write under
  `:require [re-frame.core :as rf]`) from the skill content and asserts
  each resolves to a manifest row.

  SCOPE — call position, not keyword namespace. re-frame2's `:rf/*`
  reserved keyword scheme collides with the conventional `rf` alias: a
  bare `rf/<token>` sweep drowns in `:rf/default`, `:rf/machine`,
  `:rf/redacted`, … keyword references that are NOT vars. The check anchors
  on a leading `(` — a CALL of an alias-qualified var — which excludes the
  keyword namespace entirely.

  MIGRATION SKILL EXEMPT. `skills/re-frame-migration/` is the from-v1 /
  v2-pre-rename migration skill; its ENTIRE job is to name removed old
  APIs as the left-hand side of `old → new` rename tables (`reg-sub-raw`,
  `dispatcher`, `register-trace-cb!`, `get-frame-db`, …). Those references
  are CORRECTLY-absent from the manifest by design, and the set is large
  and churny. The skill is excluded wholesale rather than carrying a
  brittle removed-names allowlist; the OTHER skills (implementor /
  improver / pair / pair-retro) teach against the LIVE surface and are the
  ones a stale-var reference would mislead. A bare-name allowlist
  (`:skills-known-unmanifested`) catches any further legitimate
  non-manifest reference in the checked skills."
  (:require [clojure.string :as str]
            [re-frame.api-manifest.gen :as rf.api-manifest.gen]
            [re-frame.api-manifest.projection :as rf.api-manifest.projection]))

(def ^:private core-ns "re-frame.core")

(def ^:private excluded-skill-dir
  "The from-v1 migration skill — see ns docstring (names removed old APIs
   by design)."
  "skills/re-frame-migration")

(def ^:private min-references
  "Non-vacuous floor (rf2-utvst). The skills tree carries ~505 live
   `(rf/<var>` references across ~127 checked `*.md` files; this floor sits
   an order of magnitude below that, so it trips only on a near-total
   collapse (the skills dir moved, the `(rf/<var>` extraction broke, the
   alias convention changed) — the cases that would otherwise turn the gate
   into a vacuous green — never on ordinary content churn."
  100)

(defn check!
  []
  (let [rows      (rf.api-manifest.projection/manifest-rows)
        ;; `(rf/<var>` is the `re-frame.core` alias surface; resolve against
        ;; the core rows (front-porch + advanced + tooling + testing).
        core-vars (set (map :var (rf.api-manifest.projection/rows-in-ns rows core-ns)))
        allow     (set (:skills-known-unmanifested (rf.api-manifest.gen/read-sidecar)))
        dir       (rf.api-manifest.projection/repo-file "skills")
        ;; require-markdown-files (rf2-utvst): fail loudly if skills/ moves
        ;; or is renamed, rather than silently checking zero files.
        files     (->> (rf.api-manifest.projection/require-markdown-files "skills/" dir)
                       (remove #(str/includes?
                                 (str/replace (rf.api-manifest.projection/repo-relative %) "\\" "/")
                                 excluded-skill-dir)))
        results   (for [file files
                        ref  (rf.api-manifest.projection/alias-call-references "rf" (rf.api-manifest.projection/numbered-lines file))]
                    (assoc ref :file (rf.api-manifest.projection/repo-relative file)))
        var-problems (keep (fn [{:keys [var line raw file]}]
                             (when-not (or (contains? core-vars var)
                                           (contains? allow var))
                               {:file file :line line :raw raw
                                :detail "no re-frame.core manifest row"}))
                           results)
        ;; Keyword-drift guards (rf2-tawage / rf2-uhew69 / rf2-1zjkn8): scan
        ;; the SAME checked skill files (the migration skill is already excluded
        ;; above, where retired vocabulary legitimately appears) for stale
        ;; EP-0017 `:rf.world/inputs`, EP-0011 reply-envelope (`:stale-key` /
        ;; bare `:work-id`), and EP-0015 egress-profile vocabulary reintroduced
        ;; outside an explicit retirement/rename mention.
        kw-problems  (rf.api-manifest.projection/keyword-drift-problems-over-files files)
        problems     (concat var-problems kw-problems)]
    (rf.api-manifest.projection/report-with-floor! "skills/ (excl. re-frame-migration)"
                             (count results) min-references problems)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
