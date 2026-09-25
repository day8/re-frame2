(ns re-frame.api-manifest.xray-spec-check
  "Xray API-spec projection check.

  `tools/xray/spec/API.md` enumerates Xray's public CLJS surface. This
  check parses the references the spec spells AS public-var symbols — the
  fully-qualified `day8.re-frame2-xray.*/<var>` Panel reg-views and the
  `mount-<panel>!` aggregator family — and reconciles each against the
  manifest's Xray rows (the `:cljs-only` Xray surface, namespace prefix
  `day8.re-frame2-xray`).

  SCOPE (deliberately narrow). The Xray spec is prose-heavy and names
  dozens of keywords, config keys, and CSS vars; a bare-token sweep would
  be all false-positive. This check scopes to three ACTUAL public-var
  reference shapes: (1) fully-namespace-qualified `day8.re-frame2-xray.*/`
  symbols (the canonical `panels.<area>/Panel` inventory), (2) the
  `mount-<panel>!` family the doc names as the mountable-panel contract,
  and (3) call-position `(rf/<var>` references into the re-frame core
  facade (the §What Xray reads table).

  WHY SHAPE (3). The two Xray-namespace shapes alone are not coverage of
  the file: `tools/xray/spec/API.md` also documents the framework surface
  Xray consumes, as a table of call-position `(rf/<var>` forms, and no
  other check reconciles them — `doc-api-check` owns exactly this contract
  but scans `spec/Privacy.md` + `docs/api/**` + `docs/story/api/**`,
  `doc-guide-check` owns `docs/core/`, and `skills-check` owns `skills/`.
  Without shape (3), `tools/xray/spec/` would fall between them, and a
  fault planted in a `(rf/...` form would leave the reference count
  unmoved and the check green.

  WHAT SHAPE (3) DOES NOT COVER, stated so a reader does not over-read
  this gate. Only the bare `rf` alias is anchored, so a call written under
  a sub-namespace alias — `(rf.subs.tooling/sub-topology …)` on the same
  table, `(rf.http/...` — is outside
  this check, the same latitude `doc-api-check` records for its own
  surfaces. Resolution is by BARE VAR NAME against every manifest row, not
  namespace-exact: it answers `does this name still exist as a public
  surface?`, which is what catches a removed name, and leaves
  namespace-exact classification to the manifest drift-check and
  `api-md-check`.
  Prose, keywords, config keys and CSS vars remain unchecked by design.

  TWO RESOLUTION PATHS, TWO ALLOWLISTS. The two reference
  shapes resolve against DIFFERENT manifest indexes, because they carry
  different identity:

    - A fully-qualified `day8.re-frame2-xray.panels.<area>/Panel` symbol
      names BOTH a namespace AND a var. It must resolve against the strict
      `[namespace var]` index — resolving by bare var alone is unsound,
      because the manifest carries the SAME `:var \"Panel\"` for every
      panel namespace, so a stale / renamed / never-manifested
      namespace (e.g. `day8.re-frame2-xray.panels.views/Panel`, or a
      deleted panel ns) would falsely pass the moment ANY Xray namespace
      still exports a var of that bare name: a bare-`:var`-set membership
      test would defeat the renamed/removed-panel drift contract.

    - A bare `mount-<panel>!` reference names only a var (the aggregator
      family all lives in `day8.re-frame2-xray.panels`). It resolves
      against the bare-var index.

  Each path has its own knowingly-unmanifested allowlist in the sidecar:
  `:xray-spec-known-unmanifested-qualified` (a set of `[ns var]` vectors,
  for namespace-qualified prose/removal mentions) and
  `:xray-spec-known-unmanifested` (a set of bare var-name strings, for
  removed bare `mount-*!` names the spec still mentions in removal prose).
  The facade path reads `:xray-spec-known-unmanifested-facade` (bare
  var-name strings), which the sidecar leaves absent and therefore empty —
  the intended state: the repair for a red on this path is to
  reconcile the SPEC TEXT to the live facade, and an allowlist entry is
  reserved for a deliberate removal-prose mention, the way
  `:doc-api-coverage-exempt` is for its check.

  REPORT-ONLY POSTURE. The Xray spec FILE is owned by tools/xray; this
  check does not edit it. It goes RED the moment a
  NEW Xray panel/mount symbol enters the spec, an enumerated one is
  renamed to an unmanifested namespace, or a panel namespace is removed
  from the manifest while the spec still names it — the manifest's
  allowlist discipline applied to the live panel + mount surface."
  (:require [re-frame.api-manifest.gen :as rf.api-manifest.gen]
            [re-frame.api-manifest.projection :as rf.api-manifest.projection]))

(def ^:private xray-ns-prefix "day8.re-frame2-xray.")

(def ^:private min-references
  "Non-vacuous floor over the AGGREGATE of all three extractors.
   The floor sits far below the live count, so it trips only on a near-total
   collapse (a symbol/alias shape stops matching, or the spec is gutted) —
   never on ordinary panel churn. No live figure is quoted here on purpose:
   a count in a docstring goes stale silently. Run the check and read the
   count it reports.

   KNOWN LIMIT, recorded rather than engineered around. Being an
   AGGREGATE, this floor cannot see ONE extractor collapsing while the others
   keep reporting — the surviving shapes hold the total above it. That is the
   same vacuous-green class the floor exists for, reached one extractor at a
   time. Per-shape floors would catch it; they are deliberately not built
   here, because the failure needs a reference-shape change to occur at all
   and such a change is a reviewed edit to this namespace, not silent drift."
  8)

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
  "Pure reconciler, so the namespace-qualified
   resolution is unit-testable with synthetic inputs. Returns the seq of
   problem maps `{:file :line :raw :detail}` for the supplied references.

   `rows`            — manifest rows (each `{:namespace :var ...}`).
   `qualified-refs`  — fully-qualified refs `{:ns :var :line :raw}`,
                       resolved STRICTLY against the `[ns var]` index.
   `bare-refs`       — bare `mount-*!` refs `{:var :line :raw}`, resolved
                       against the bare-var index.
   `facade-refs`     — call-position `(rf/<var>` refs `{:var :line :raw}`,
                       resolved against a bare-name set over
                       ALL manifest rows — not the Xray-filtered ones,
                       because these name the re-frame CORE facade.
   `qualified-allow` — set of `[ns var]` vectors knowingly unmanifested.
   `bare-allow`      — set of bare var-name strings knowingly unmanifested.
   `facade-allow`    — set of bare var-name strings knowingly unmanifested
                       on the facade path.
   `rel`             — repo-relative file path for reporting."
  [{:keys [rows qualified-refs bare-refs facade-refs
           qualified-allow bare-allow facade-allow rel]}]
  (let [xray-rows  (filter #(.startsWith ^String (:namespace %) xray-ns-prefix) rows)
        ;; Strict [ns var] index for qualified refs: a qualified
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
                         bare-refs)
        ;; Facade path: `(rf/<var>` names the re-frame CORE
        ;; surface, so it resolves against a bare-name set over ALL rows
        ;; — deliberately NOT `xray-rows`, which would redden every
        ;; framework reference in the file.
        all-vars   (set (map :var rows))
        fac-probs  (keep (fn [{:keys [var line raw]}]
                           (when-not (or (contains? all-vars var)
                                         (contains? facade-allow var))
                             {:file rel :line line :raw raw
                              :detail (str "no manifest row for this re-frame.core facade "
                                           "reference (renamed / removed / never-manifested "
                                           "public surface)")}))
                         facade-refs)]
    (concat qual-probs bare-probs fac-probs)))

(defn check!
  []
  (let [rows            (rf.api-manifest.projection/manifest-rows)
        sidecar         (rf.api-manifest.gen/read-sidecar)
        qualified-allow (set (map vec (:xray-spec-known-unmanifested-qualified sidecar)))
        bare-allow      (set (:xray-spec-known-unmanifested sidecar))
        facade-allow    (set (:xray-spec-known-unmanifested-facade sidecar))
        file            (rf.api-manifest.projection/repo-file "tools" "xray" "spec" "API.md")
        rel             (rf.api-manifest.projection/repo-relative file)
        lines           (rf.api-manifest.projection/numbered-lines file)
        qualified-refs  (distinct (rf.api-manifest.projection/qualified-symbol-references xray-ns-prefix lines))
        bare-refs       (distinct (mount-references lines))
        facade-refs     (distinct (rf.api-manifest.projection/alias-call-references "rf" lines))
        problems        (reconcile {:rows            rows
                                    :qualified-refs  qualified-refs
                                    :bare-refs       bare-refs
                                    :facade-refs     facade-refs
                                    :qualified-allow qualified-allow
                                    :bare-allow      bare-allow
                                    :facade-allow    facade-allow
                                    :rel             rel})]
    (rf.api-manifest.projection/report-with-floor! "tools/xray/spec/API.md"
                             (+ (count qualified-refs) (count bare-refs) (count facade-refs))
                             min-references
                             problems)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
