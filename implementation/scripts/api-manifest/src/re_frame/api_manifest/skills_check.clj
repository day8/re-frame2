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
            [re-frame.api-manifest.gen :as gen]
            [re-frame.api-manifest.projection :as proj]))

(def ^:private core-ns "re-frame.core")

(def ^:private excluded-skill-dir
  "The from-v1 migration skill — see ns docstring (names removed old APIs
   by design)."
  "skills/re-frame-migration")

(defn check!
  []
  (let [rows      (proj/manifest-rows)
        ;; `(rf/<var>` is the `re-frame.core` alias surface; resolve against
        ;; the core rows (front-porch + advanced + tooling + testing).
        core-vars (set (map :var (proj/rows-in-ns rows core-ns)))
        allow     (set (:skills-known-unmanifested (gen/read-sidecar)))
        dir       (proj/repo-file "skills")
        files     (->> (proj/markdown-files dir)
                       (remove #(str/includes?
                                 (str/replace (proj/repo-relative %) "\\" "/")
                                 excluded-skill-dir)))
        results   (for [file files
                        ref  (proj/alias-call-references "rf" (proj/numbered-lines file))]
                    (assoc ref :file (proj/repo-relative file)))
        problems  (keep (fn [{:keys [var line raw file]}]
                          (when-not (or (contains? core-vars var)
                                        (contains? allow var))
                            {:file file :line line :raw raw
                             :detail "no re-frame.core manifest row"}))
                        results)]
    (proj/report-result! "skills/ (excl. re-frame-migration)" (count results) problems)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
