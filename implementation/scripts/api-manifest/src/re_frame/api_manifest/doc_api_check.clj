(ns re-frame.api-manifest.doc-api-check
  "Human-documentation API-reference projection check.

  The check owns these human-facing API reference surfaces:

    * `spec/Privacy.md`   — the EP-0015 cross-artefact privacy inventory;
    * `docs/core/api/**` — the per-chapter human API reference;
    * `docs/story/api/**` — the Story human API reference.

  It uses the same call-position discipline as the guide check: every
  call-position
  `(rf/<var>` / `(story/<var>` reference must resolve to a manifest row, so a
  reference to a renamed / removed / never-manifested public surface goes RED.

  Per-capability `docs/<cap>/api.md` files are scanned by a one-level glob that
  also discovers future capability docs.

  SCOPE — call-position discipline (same as doc-guide / skills checks).
  References are anchored on the leading `(` so the `:rf/*` reserved keyword
  namespace is excluded (a bare `rf/<token>` sweep would drown in
  `:rf/default`, `:rf.cofx/requires`, `:rf.egress/*`, etc.). Two alias forms
  are extracted, because these reference trees mix them:

    * `(rf/<var>`     — the conventional `re-frame.core` alias, used across
                        all three trees;
    * `(story/<var>`  — the `re-frame.story` alias, used in the Story API
                        reference (`docs/story/api/**`).

  RESOLUTION LATITUDE. A reference resolves when its bare var name is carried
  by ANY manifest row — `check!` below builds exactly that: a BARE-NAME SET
  over every manifest row (`(set (map :var rows))`). This is correct here: the
  API reference legitimately names vars across several public namespaces
  (`re-frame.core`, `re-frame.story`, `re-frame.machines`, …) under the `rf`
  alias, and a REMOVED var has no manifest row in ANY namespace, so it is
  still caught. (The keystone + api-md-check already pin namespace-exact
  classification; this projection only asks `does this name still exist as a
  public surface?`.)

  REMOVAL-NOTE / TOMBSTONE TOLERANCE. Mirroring how `api-md-check` tolerates
  `spec/API.md` removal notes and how `doc-guide-check` file-scopes removed
  names, the `migration/from-re-frame-v1/README.md` removed-symbol register names removed
  surfaces by design. Its removed names appear as bare back-ticked
  identifiers (`` `add-marks` ``, `` `reg-sub-raw` ``), NOT as call-position
  `(rf/<var>` forms, so the call-position scope already excludes them. The
  file-scoped `:doc-api-known-unmanifested-scoped` sidecar allowlist exists
  for the rare legitimate call-position mention of a removed name in approved
  tombstone / migration prose; a call-position reference to a removed name in
  any OTHER file is RED (it leaked into live reference prose).

  KEYWORD-DRIFT GUARDS. The EP-0017 / EP-0011 / EP-0015 keyword-drift guards
  (`projection/keyword-drift-problems-over-files`) are folded in over the same
  scanned files — `spec/Privacy.md` is the EP-0015 privacy surface, so a
  reintroduced retired `:rf.egress/*` profile keyword goes RED here too.

  PAGE + MEMBER COVERAGE (rf2-e5692s). The call-position discipline above
  catches a docs/api/ reference to a REMOVED surface, but it cannot catch the
  opposite drift: a public var the manifest carries that NO page documents
  (docs/api/README.md promises one page per public namespace and an entry per
  eligible var, yet nothing enforced it — `re-frame.ui.test`'s testing surface
  and four `re-frame.ui` vars had no page/member entry). `check-coverage!`
  reconciles the manifest AGAINST the corpus: every manifest var at an
  ELIGIBLE tier (`:front-porch` / `:advanced` / `:adapter` / `:testing`, per
  the README completeness clause) must have a `docs/api/<namespace>.md` page
  and a member heading on it. A member heading is any `##`-`####` whose text is
  one back-ticked identifier, reduced to its final `/`-segment — so a bare
  `### \\`sub\\``, a namespace-qualified `### \\`re-frame.machines/x\\``, and a
  facade-pointer `#### \\`reg-machine\\`` on the owning/facade page all count.
  Deleting an eligible namespace's page (PAGE-MISSING) or a member's heading
  (MEMBER-MISSING) turns this RED. The `:doc-api-coverage-exempt` sidecar key
  (a set of `[namespace var]` pairs, empty/absent today) is the explicit escape
  hatch for a var intentionally documented only as a facade pointer elsewhere."
  (:require [clojure.string :as str]
            [re-frame.api-manifest.gen :as rf.api-manifest.gen]
            [re-frame.api-manifest.projection :as rf.api-manifest.projection]))

;; The reference trees scanned. Each is an EXPECTED surface (it exists today
;; and is owned), so it uses `require-markdown-files` — a moved / renamed
;; tree fails loudly rather than turning the gate into a vacuous green.
;;
;; `spec/Privacy.md` is a single file, not a directory; it is handled
;; separately below (a `require`-style existence assertion).
(def ^:private dir-surfaces
  "[[label repo-relative-dir-segs] ...] — directory reference trees."
  [["docs/api/"       ["docs" "api"]]
   ["docs/story/api/" ["docs" "story" "api"]]])

(def ^:private privacy-file-segs ["spec" "Privacy.md"])

(def ^:private min-references
  "Non-vacuous floor (rf2-utvst-style). The three trees together carry ~50
   call-position references today (docs/core/api ~35, spec/Privacy ~22,
   docs/story/api ~15). This floor sits well below that combined live count,
   so it trips only on a near-total collapse (a tree moved/renamed, the
   `(alias/<var>` extraction broke, the alias convention changed) — never on
   ordinary content churn. It is the aggregate floor across all three trees."
  20)

(defn reconcile
  "Pure reconciler (extracted so the file-scoped allowlist contract is
   unit-testable with synthetic inputs). Returns the seq of problem maps for
   the supplied call-position references.

   `references`  — `[{:var :line :raw :file} ...]` (`:file` repo-relative).
   `manifest-vars` — set of bare var names ANY manifest row carries (a name
                     in this set still names a live public surface).
   `scoped-allow`— `{removed-name -> #{approved repo-relative file paths}}`
                   (the `:doc-api-known-unmanifested-scoped` sidecar key).

   A reference resolves (no problem) when its var is carried by some manifest
   row, OR its var is on the scoped allowlist AND its file is in that name's
   approved-file set. A reference to a scoped removed name in a NON-approved
   file is flagged as a removed-API leak into live reference prose; an unknown
   name with no manifest row and no scope entry is flagged as an unresolved
   reference."
  [{:keys [references manifest-vars scoped-allow]}]
  (keep (fn [{:keys [var line raw file]}]
          (cond
            (contains? manifest-vars var) nil
            (contains? scoped-allow var)
            (when-not (contains? (get scoped-allow var) file)
              {:file file :line line :raw raw
               :detail (format (str "removed API named outside its approved "
                                    "removal/migration file(s) %s — live API "
                                    "reference prose must not call removed APIs")
                               (vec (sort (get scoped-allow var))))})
            :else
            {:file file :line line :raw raw
             :detail "no manifest row (renamed / removed / never-manifested public surface)"}))
        references))

(defn- references-in-files
  "Extract both `(rf/<var>` and `(story/<var>` call-position references from
   `files` (io/file seq), each tagged with its repo-relative `:file`."
  [files]
  (for [file  files
        alias ["rf" "story"]
        ref   (rf.api-manifest.projection/alias-call-references alias (rf.api-manifest.projection/numbered-lines file))]
    (assoc ref :file (rf.api-manifest.projection/repo-relative file))))

;; ---------------------------------------------------------------------------
;; Page + member coverage (rf2-e5692s).
;; ---------------------------------------------------------------------------

(def ^:private eligible-tiers
  "The tiers docs/api/ is expected to document (README completeness clause).
   `:tooling`, `:implementation`, `:internal-public`, and `:deprecated` are out
   of scope for the human API reference."
  #{:front-porch :advanced :adapter :testing})

(def ^:private coverage-min-rows
  "Non-vacuous floor (rf2-utvst-style) for the coverage reconciler. The manifest
   carries ~180 eligible-tier rows today; this floor sits well below that, so it
   trips ONLY if the committed manifest itself collapsed (which the primary
   `gen --check` also catches) — never on ordinary corpus churn. Below the floor
   the coverage check refuses a vacuous OK."
  100)

(defn- member-heading-names
  "The set of bare member-var names a docs/api page documents: every markdown
   heading (`##`-`####`) whose text is exactly ONE back-ticked identifier,
   reduced to its final `/`-separated segment. So a bare `### \\`sub\\``, an
   owning-namespace-qualified `### \\`re-frame.machines/machine-transition\\``,
   and a facade-pointer `#### \\`reg-machine\\`` all cover their bare var name."
  [^java.io.File file]
  (into #{}
        (keep (fn [[_ line]]
                (when-let [[_ ident] (re-matches #"#{2,4}\s+`([^`]+)`.*"
                                                 (str/trim line))]
                  (last (str/split ident #"/")))))
        (rf.api-manifest.projection/numbered-lines file)))

(defn- page-members
  "`{namespace-str -> #{covered-bare-var-name ...}}` for each namespace in
   `namespaces` whose `docs/api/<namespace>.md` EXISTS. A namespace ABSENT from
   the returned map has no page (→ a PAGE-MISSING problem downstream)."
  [namespaces]
  (reduce (fn [acc ns-str]
            (let [f (rf.api-manifest.projection/repo-file "docs" "api" (str ns-str ".md"))]
              (if (.isFile ^java.io.File f)
                (assoc acc ns-str (member-heading-names f))
                acc)))
          {} namespaces))

(defn coverage-problems
  "Pure coverage reconciler (extracted so the page/member contract is
   unit-testable with synthetic inputs). Returns the seq of problem maps.

   `eligible-rows` — `[{:namespace :var} ...]` (already filtered to eligible tiers).
   `members`       — `{namespace-str -> #{covered-var-name}}`; a namespace absent
                     from this map has NO docs/api page.
   `exempt`        — `#{[namespace-str var-str] ...}` explicit facade-pointer
                     exemptions (the `:doc-api-coverage-exempt` sidecar key).

   A namespace carrying eligible vars but no page yields ONE `:page-missing`
   problem (the page problem subsumes its members). Otherwise each eligible var
   with neither a covering member heading nor an exempt entry yields a
   `:member-missing` problem."
  [{:keys [eligible-rows members exempt]}]
  (mapcat
    (fn [[ns-str rows]]
      (if-not (contains? members ns-str)
        [{:kind :page-missing :namespace ns-str}]
        (let [covered (get members ns-str)]
          (keep (fn [{:keys [var]}]
                  (when-not (or (contains? covered var)
                                (contains? exempt [ns-str var]))
                    {:kind :member-missing :namespace ns-str :var var}))
                rows))))
    (sort-by key (group-by :namespace eligible-rows))))

(defn- report-coverage!
  "Print a uniform OK/DRIFT report for the coverage reconciler and return the
   boolean verdict. Enforces the non-vacuous floor before trusting an empty
   problem list."
  [problems eligible-count]
  (cond
    (< eligible-count coverage-min-rows)
    (do (binding [*out* *err*]
          (println (format (str "DRIFT: docs/api coverage extracted only %d eligible "
                                 "manifest rows, below the non-vacuous floor of %d — "
                                 "the committed manifest likely collapsed. Investigate "
                                 "before trusting GREEN (regenerate the manifest).")
                           eligible-count coverage-min-rows)))
        false)

    (empty? problems)
    (do (println (format (str "OK: docs/api page+member coverage in sync (%d eligible "
                              "manifest vars each have a page + member entry).")
                         eligible-count))
        true)

    :else
    (do (binding [*out* *err*]
          (println "DRIFT: docs/api is missing required namespace pages or member entries.")
          (println "Every eligible manifest var (tier :front-porch/:advanced/:adapter/:testing")
          (println "under re-frame.*) needs a docs/api/<namespace>.md page AND a member heading")
          (println "(### `var`, or a #### `var` facade-pointer entry on the owning/facade page).")
          (println "Add the page/heading, or an explicit :doc-api-coverage-exempt sidecar entry")
          (println "for a var documented only as a facade pointer elsewhere. Problems:")
          (doseq [{:keys [kind namespace var]} (sort-by (juxt :namespace :var) problems)]
            (case kind
              :page-missing
              (println (format "  PAGE:   docs/api/%s.md is missing (its namespace has eligible manifest vars)"
                               namespace))
              :member-missing
              (println (format "  MEMBER: %s/%s has no member heading on docs/api/%s.md"
                               namespace var namespace)))))
        false)))

(defn check-coverage!
  "Reconcile every eligible manifest var against docs/api/ page + member
   coverage. Returns true when every eligible namespace has a page and every
   eligible var has a member heading (or an explicit facade-pointer exemption);
   false (with a printed report) otherwise."
  []
  (let [rows          (rf.api-manifest.projection/manifest-rows)
        eligible-rows (filter #(contains? eligible-tiers (:tier %)) rows)
        namespaces    (distinct (map :namespace eligible-rows))
        members       (page-members namespaces)
        exempt        (set (:doc-api-coverage-exempt (rf.api-manifest.gen/read-sidecar)))
        problems      (coverage-problems {:eligible-rows eligible-rows
                                          :members       members
                                          :exempt        exempt})]
    (report-coverage! problems (count eligible-rows))))

(defn check!
  []
  (let [rows          (rf.api-manifest.projection/manifest-rows)
        ;; Resolution target: bare var names ANY manifest row carries. A
        ;; removed surface has no row in ANY namespace, so it is still caught.
        manifest-vars (set (map :var rows))
        scoped-allow  (or (:doc-api-known-unmanifested-scoped (rf.api-manifest.gen/read-sidecar)) {})
        ;; Directory trees — fail loud if a tree moves/renames (rf2-utvst).
        dir-files     (mapcat (fn [[label segs]]
                                (rf.api-manifest.projection/require-markdown-files
                                  label (apply rf.api-manifest.projection/repo-file segs)))
                              dir-surfaces)
        ;; The API reference is one doc per namespace under docs/api/ (covered
        ;; by the dir-surfaces tree above). spec/Privacy.md — a single
        ;; EXPECTED file; fail loud if it moves.
        privacy-file  (apply rf.api-manifest.projection/repo-file privacy-file-segs)
        _             (when-not (.isFile ^java.io.File privacy-file)
                        (throw (ex-info
                                 (str "spec/Privacy.md: expected file is missing — "
                                      (rf.api-manifest.projection/repo-relative privacy-file)
                                      ". The privacy projection gate cannot run "
                                      "against a non-existent surface; reconcile "
                                      "the path.")
                                 {:file (str privacy-file)})))
        files         (concat [privacy-file] dir-files)
        references    (references-in-files files)
        var-problems  (reconcile {:references    references
                                  :manifest-vars manifest-vars
                                  :scoped-allow  scoped-allow})
        ;; Keyword-drift guards (EP-0017/EP-0011/EP-0015): spec/Privacy.md is
        ;; the EP-0015 surface, so a reintroduced retired `:rf.egress/*`
        ;; profile keyword goes RED here alongside any var-resolution drift.
        kw-problems   (rf.api-manifest.projection/keyword-drift-problems-over-files files)
        problems      (concat var-problems kw-problems)
        ;; (1) Call-position reference discipline + keyword-drift over the
        ;; reference trees (the original rf2-vzupmg contract).
        refs-ok       (rf.api-manifest.projection/report-with-floor!
                        "spec/Privacy.md + docs/api/ + docs/story/api/"
                        (count references) min-references problems)
        ;; (2) Page + member coverage of the manifest by docs/api/ (rf2-e5692s).
        ;; Both reports print; the check is RED if EITHER fails.
        coverage-ok   (check-coverage!)]
    (and refs-ok coverage-ok)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
