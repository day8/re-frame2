(ns re-frame.api-manifest.doc-api-check
  "Human-doc API-reference projection check (rf2-vzupmg).

  THE GAP THIS CLOSES. The keystone manifest drift-check (rf2-3nbl5.2) and
  its `spec/API.md` projection (`api-md-check`) plus the four secondary
  projection checks (`doc-guide-check`, `story-spec-check`, `xray-spec-check`,
  `skills-check`) between them scan `spec/API.md`, `docs/guide/**`, the two
  tool API specs, and `skills/`. They do NOT scan the three human-facing API
  REFERENCE trees:

    * `spec/Privacy.md`   — the EP-0015 cross-artefact privacy inventory;
    * `docs/api/**`       — the per-chapter human API reference;
    * `docs/story/api/**` — the Story human API reference.

  EP-0025 stale prose slipped into exactly those trees and passed EVERY CI
  check because nothing reconciled their public-var references against the
  manifest. This check extends the SAME call-position discipline the
  doc-guide check uses to those three trees: every call-position
  `(rf/<var>` / `(story/<var>` reference must resolve to a manifest row, so a
  reference to a renamed / removed / never-manifested public surface goes RED.

  CAPABILITY API DOCS (rf2-earvtz). The #4961 docs reorg moved each
  capability's API REFERENCE out of the flat `docs/api/` tree into
  per-capability `docs/<cap>/api.md` files (machines / resources / routing /
  ssr). Those API docs are the same projection class — they name public vars
  in call position — and so are scanned here via the `docs/*/api.md` glob
  (which auto-covers a future capability dir). Without this, a removed /
  renamed public surface named in a moved API doc would slip through CI.

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
  by ANY manifest row — the same bare-name latitude the secondary projection
  checks take (`projection/index-by-var-name`). This is correct here: the
  API reference legitimately names vars across several public namespaces
  (`re-frame.core`, `re-frame.story`, `re-frame.machines`, …) under the `rf`
  alias, and a REMOVED var has no manifest row in ANY namespace, so it is
  still caught. (The keystone + api-md-check already pin namespace-exact
  classification; this projection only asks `does this name still exist as a
  public surface?`.)

  REMOVAL-NOTE / TOMBSTONE TOLERANCE. Mirroring how `api-md-check` tolerates
  `spec/API.md` removal notes and how `doc-guide-check` file-scopes removed
  names, the `docs/api/15-removed.md` tombstone register names removed
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
  reintroduced retired `:rf.egress/*` profile keyword goes RED here too."
  (:require [re-frame.api-manifest.gen :as gen]
            [re-frame.api-manifest.projection :as proj]))

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
   call-position references today (docs/api ~35, spec/Privacy ~22,
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
        ref   (proj/alias-call-references alias (proj/numbered-lines file))]
    (assoc ref :file (proj/repo-relative file))))

(defn check!
  []
  (let [rows          (proj/manifest-rows)
        ;; Resolution target: bare var names ANY manifest row carries. A
        ;; removed surface has no row in ANY namespace, so it is still caught.
        manifest-vars (set (map :var rows))
        scoped-allow  (or (:doc-api-known-unmanifested-scoped (gen/read-sidecar)) {})
        ;; Directory trees — fail loud if a tree moves/renames (rf2-utvst).
        dir-files     (mapcat (fn [[label segs]]
                                (proj/require-markdown-files
                                  label (apply proj/repo-file segs)))
                              dir-surfaces)
        ;; Per-capability API docs (rf2-earvtz). The #4961 docs reorg moved
        ;; each capability's API reference out of the flat docs/api/ tree into
        ;; docs/<cap>/api.md (machines/resources/routing/ssr). The
        ;; docs/*/api.md glob folds those moved files back under this gate's
        ;; scan so a removed/renamed public surface named there goes RED; it
        ;; auto-covers a future capability dir with no gate edit, and fails
        ;; loudly (require-*) if the layout moves again.
        cap-api-files (proj/require-capability-doc-files
                        "docs/*/api.md" "api.md")
        ;; spec/Privacy.md — a single EXPECTED file; fail loud if it moves.
        privacy-file  (apply proj/repo-file privacy-file-segs)
        _             (when-not (.isFile ^java.io.File privacy-file)
                        (throw (ex-info
                                 (str "spec/Privacy.md: expected file is missing — "
                                      (proj/repo-relative privacy-file)
                                      ". The privacy projection gate cannot run "
                                      "against a non-existent surface; reconcile "
                                      "the path.")
                                 {:file (str privacy-file)})))
        files         (concat [privacy-file] dir-files cap-api-files)
        references    (references-in-files files)
        var-problems  (reconcile {:references    references
                                  :manifest-vars manifest-vars
                                  :scoped-allow  scoped-allow})
        ;; Keyword-drift guards (EP-0017/EP-0011/EP-0015): spec/Privacy.md is
        ;; the EP-0015 surface, so a reintroduced retired `:rf.egress/*`
        ;; profile keyword goes RED here alongside any var-resolution drift.
        kw-problems   (proj/keyword-drift-problems-over-files files)
        problems      (concat var-problems kw-problems)]
    (proj/report-with-floor!
      "spec/Privacy.md + docs/api/ + docs/story/api/ + docs/*/api.md"
      (count references) min-references problems)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
