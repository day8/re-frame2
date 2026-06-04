(ns re-frame.api-manifest.xray-spec-check
  "Xray API-spec projection check (rf2-gkp0t).

  `tools/xray/spec/API.md` enumerates Xray's public CLJS surface. This
  check parses the references the spec spells AS public-var symbols — the
  fully-qualified `day8.re-frame2-xray.*/<var>` Panel reg-views and the
  `mount-<panel>!` aggregator family — and reconciles each against the
  manifest's Xray rows (the `:cljs-only` Xray surface, namespace prefix
  `day8.re-frame2-xray`).

  SCOPE (deliberately narrow). The Xray spec is prose-heavy and names
  dozens of keywords, config keys, and CSS vars; a bare-token sweep would
  be all false-positive. This check scopes to two ACTUAL public-var
  reference shapes: (1) fully-namespace-qualified `day8.re-frame2-xray.*/`
  symbols (the canonical `panels.<area>/Panel` inventory), and (2) the
  `mount-<panel>!` family the doc names as the mountable-panel contract.

  TWO RESOLUTION PATHS, TWO ALLOWLISTS (rf2-0u8kz). The two reference
  shapes resolve against DIFFERENT manifest indexes, because they carry
  different identity:

    - A fully-qualified `day8.re-frame2-xray.panels.<area>/Panel` symbol
      names BOTH a namespace AND a var. It must resolve against the strict
      `[namespace var]` index — resolving by bare var alone is unsound,
      because the manifest carries the SAME `:var \"Panel\"` for ten
      distinct panel namespaces, so a stale / renamed / never-manifested
      namespace (e.g. `day8.re-frame2-xray.panels.views/Panel` — the
      pre-rf2-wyvf2 spelling rf2-yxw57 corrected, or a deleted panel ns)
      would falsely pass the moment ANY Xray namespace still exports a var
      of that bare name. The bug this check fixes (rf2-0u8kz): a
      bare-`:var`-set membership test defeated the renamed/removed-panel
      drift contract.

    - A bare `mount-<panel>!` reference names only a var (the aggregator
      family all lives in `day8.re-frame2-xray.panels`). It resolves
      against the bare-var index.

  Each path has its own knowingly-unmanifested allowlist in the sidecar:
  `:xray-spec-known-unmanifested-qualified` (a set of `[ns var]` vectors,
  for namespace-qualified prose/removal mentions) and
  `:xray-spec-known-unmanifested` (a set of bare var-name strings, for
  removed bare `mount-*!` names the spec still mentions in removal prose).

  REPORT-ONLY POSTURE (rf2-gkp0t). The Xray spec FILE is owned by a
  concurrent worker; this check does not edit it. It goes RED the moment a
  NEW Xray panel/mount symbol enters the spec, an enumerated one is
  renamed to an unmanifested namespace, or a panel namespace is removed
  from the manifest while the spec still names it — the keystone allowlist
  discipline applied to the live panel + mount surface."
  (:require [re-frame.api-manifest.gen :as gen]
            [re-frame.api-manifest.projection :as proj]))

(def ^:private xray-ns-prefix "day8.re-frame2-xray.")

(def ^:private mount-fn-re
  "The `mount-<panel>!` aggregator family the spec names as the mountable-
   panel contract (007-UX-IA §Mountable panel contract)."
  #"\bmount-[a-z][a-z-]*!")

(defn mount-references
  "Extract bare `mount-<panel>!` references from numbered `lines`. These
   carry only a var (no namespace); they resolve against the bare-var
   index."
  [lines]
  (for [[n text] lines
        m         (re-seq mount-fn-re text)]
    {:var m :line n :raw m}))

(defn reconcile
  "Pure reconciler (rf2-0u8kz — extracted so the namespace-qualified
   resolution is unit-testable with synthetic inputs). Returns the seq of
   problem maps `{:file :line :raw :detail}` for the supplied references.

   `rows`            — manifest rows (each `{:namespace :var ...}`).
   `qualified-refs`  — fully-qualified refs `{:ns :var :line :raw}`,
                       resolved STRICTLY against the `[ns var]` index.
   `bare-refs`       — bare `mount-*!` refs `{:var :line :raw}`, resolved
                       against the bare-var index.
   `qualified-allow` — set of `[ns var]` vectors knowingly unmanifested.
   `bare-allow`      — set of bare var-name strings knowingly unmanifested.
   `rel`             — repo-relative file path for reporting."
  [{:keys [rows qualified-refs bare-refs qualified-allow bare-allow rel]}]
  (let [xray-rows  (filter #(.startsWith ^String (:namespace %) xray-ns-prefix) rows)
        ;; Strict [ns var] index for qualified refs — the fix: a qualified
        ;; symbol resolves ONLY if the manifest carries that exact
        ;; namespace+var pair, never if some OTHER namespace happens to
        ;; export the same bare var.
        ns+var     (set (map (juxt :namespace :var) xray-rows))
        ;; Bare-var index for the mount-*! family.
        bare-vars  (set (map :var xray-rows))
        qual-probs (keep (fn [{:keys [ns var line raw]}]
                           (when-not (or (contains? ns+var [ns var])
                                         (contains? qualified-allow [ns var]))
                             {:file rel :line line :raw raw
                              :detail "no matching day8.re-frame2-xray [namespace var] manifest row"}))
                         qualified-refs)
        bare-probs (keep (fn [{:keys [var line raw]}]
                           (when-not (or (contains? bare-vars var)
                                         (contains? bare-allow var))
                             {:file rel :line line :raw raw
                              :detail "no day8.re-frame2-xray manifest row"}))
                         bare-refs)]
    (concat qual-probs bare-probs)))

(defn check!
  []
  (let [rows            (proj/manifest-rows)
        sidecar         (gen/read-sidecar)
        qualified-allow (set (map vec (:xray-spec-known-unmanifested-qualified sidecar)))
        bare-allow      (set (:xray-spec-known-unmanifested sidecar))
        file            (proj/repo-file "tools" "xray" "spec" "API.md")
        rel             (proj/repo-relative file)
        lines           (proj/numbered-lines file)
        qualified-refs  (distinct (proj/qualified-symbol-references xray-ns-prefix lines))
        bare-refs       (distinct (mount-references lines))
        problems        (reconcile {:rows            rows
                                    :qualified-refs  qualified-refs
                                    :bare-refs       bare-refs
                                    :qualified-allow qualified-allow
                                    :bare-allow      bare-allow
                                    :rel             rel})]
    (proj/report-result! "tools/xray/spec/API.md"
                         (+ (count qualified-refs) (count bare-refs))
                         problems)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
