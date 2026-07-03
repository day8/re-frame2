(ns re-frame.api-manifest.projection
  "Shared scaffolding for the SECONDARY projection drift-checks (rf2-gkp0t,
  follow-on to the rf2-3nbl5.2 keystone + its PRIMARY `spec/API.md`
  projection check `re-frame.api-manifest.api-md-check`).

  THE SHAPE. `spec/api-manifest.edn` is the one machine-readable truth for
  the public API; every human-facing surface that NAMES a public var is a
  PROJECTION of that truth and can drift. The keystone guards the most
  important projection (spec/API.md). This namespace carries the reusable
  parser+reconciler scaffolding the four SECONDARY projection checks share
  (Story API spec, Xray API spec, skills, doc-guide), each modelled on
  `api-md-check`: parse a surface's public-var references, resolve each to
  a manifest row, and go RED on a reference the manifest does not carry
  (a renamed / removed / never-manifested var) unless it is on a curated
  knowingly-unmanifested allowlist.

  WHY AN ALLOWLIST. A surface legitimately names vars the manifest does
  not (and cannot) carry — CLJS-only facade surfaces the JVM generator
  cannot `ns-publics`, sub-namespace surfaces outside the manifest's
  whole-namespace introspection set, and (for the v1-migration surfaces)
  DELIBERATE mentions of removed old names. Each check carries its own
  curated allowlist in the sidecar (`spec/api-manifest-metadata.edn`) so
  the legitimate references are silenced ONCE, by name, and any OTHER
  unresolved reference still turns the check red. This mirrors the
  keystone's `:api-md-known-unmanifested` set exactly.

  SCOPE DISCIPLINE (the bead's caution). A naive grep over backticked
  prose tokens false-positives on every keyword, fx-id, and English word.
  Each check scopes to an ACTUAL public-var reference pattern — a
  call-position `(rf/<var>` form for the alias-qualified surfaces, a
  backticked single-identifier var-row in a typed API table for the spec
  surfaces, a fully-qualified `ns/<var>` symbol for the Xray panel
  inventory — never a bare token sweep."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.api-manifest.gen :as gen]))

;; ---------------------------------------------------------------------------
;; Manifest indexing.
;; ---------------------------------------------------------------------------

(defn manifest-rows
  "The committed manifest's `:vars` vector (the resolution target)."
  []
  (:vars (gen/read-committed-manifest)))

(defn index-by-var-name
  "`{bare-var-name -> #{manifest-row ...}}` over the supplied rows. A bare
   var name can be ambiguous across namespaces (e.g. `configure!` lives on
   both `re-frame.core` and `re-frame.story`); a projection reference that
   does not pin a namespace resolves if ANY manifest row carries the name,
   which is the same name-resolution latitude `api-md-check` takes."
  [rows]
  (reduce (fn [acc {:keys [var] :as row}]
            (update acc var (fnil conj #{}) row))
          {} rows))

(defn index-by-ns+var
  "`{[ns-str var-str] -> manifest-row}` over the supplied rows — the strict
   index for fully-namespace-qualified references (the Xray panel symbols)."
  [rows]
  (reduce (fn [acc {:keys [namespace var] :as row}]
            (assoc acc [namespace var] row))
          {} rows))

(defn rows-in-ns
  "Manifest rows whose `:namespace` is `ns-str`."
  [rows ns-str]
  (filter #(= ns-str (:namespace %)) rows))

;; ---------------------------------------------------------------------------
;; Surface-file discovery (relative to the repo root the generator owns).
;; ---------------------------------------------------------------------------

(defn repo-file
  "An `io/file` under the repo root for a repo-relative path segment seq."
  [& segs]
  (apply io/file gen/repo-root segs))

(defn markdown-files
  "Every `*.md` file under `dir` (an `io/file`), recursively, sorted by
   path for deterministic reporting. Skips a missing dir (returns nil).

   NB (rf2-utvst): a `nil` return is INDISTINGUISHABLE from a present-but-
   empty dir, and silently produces zero work in the downstream check —
   a directory move / rename then turns the projection gate into a
   vacuous green. Callers that own an EXPECTED directory must use
   `require-markdown-files` instead, which fails loudly on a missing dir."
  [dir]
  (when (.isDirectory ^java.io.File dir)
    (->> (file-seq dir)
         (filter #(and (.isFile ^java.io.File %)
                       (str/ends-with? (.getName ^java.io.File %) ".md")))
         (sort-by #(.getPath ^java.io.File %)))))

(defn repo-relative
  "`file` rendered relative to the repo root with forward slashes, for
   stable cross-platform reporting."
  [^java.io.File file]
  (-> (.toPath (io/file gen/repo-root))
      (.relativize (.toPath file))
      str
      (str/replace "\\" "/")))

(defn require-markdown-files
  "Like `markdown-files`, but throws when `dir` does not exist or is not a
   directory (rf2-utvst non-vacuous floor). A check that owns an EXPECTED
   surface directory (skills/, docs/core/) must fail loudly when that
   directory moves or is renamed rather than silently checking zero files
   and reporting a vacuous OK. `label` names the surface for the error."
  [label dir]
  (when-not (.isDirectory ^java.io.File dir)
    ;; Render the dir relative-to-repo-root for the message, but never let
    ;; the diagnostic itself throw (a non-relativizable path falls back to
    ;; the raw path string).
    (let [shown (try (repo-relative dir) (catch Exception _ (str dir)))]
      (throw (ex-info
               (format (str "%s: expected directory is missing — %s. The "
                            "projection gate cannot run against a non-existent "
                            "surface (a moved/renamed dir would otherwise pass "
                            "vacuously). Reconcile the surface path.")
                       label shown)
               {:label label :dir (str dir)}))))
  (markdown-files dir))

(defn require-capability-doc-files
  "Resolve the per-capability documentation files named `filename` (e.g.
   `\"concepts.md\"`, `\"api.md\"`) that live as immediate children of the
   capability directories directly under `docs/` — i.e. the `docs/*/<filename>`
   glob (`docs/machines/concepts.md`, `docs/routing/api.md`, …).

   THE SHAPE (rf2-earvtz). The #4961 docs reorg moved each capability's
   CONCEPT and API reference out of the flat `docs/core/` and top-level
   API trees into per-capability directories `docs/<cap>/{concepts,api}.md`. The
   doc-guide / doc-api projection gates scanned only the old flat trees, so
   the moved files went UNSCANNED — a broken `(rf/<var>` reference in them
   would slip through CI. This resolves the moved files by glob so the gates
   cover them, and AUTO-COVERS future capability dirs (a new
   `docs/<cap>/concepts.md` is picked up with no gate edit).

   SCOPE DISCIPLINE. The glob is anchored on a SINGLE path segment between
   `docs/` and the filename, so it matches ONLY immediate-child capability
   files. It never sweeps in the flat trees the gates already own
   (`docs/core/` / `docs/core/api/` are directories, not `docs/api.md` files) nor
   nested API trees (`docs/story/api/` is a directory; `docs/story/api.md`
   does not exist). The match excludes directories defensively.

   NON-VACUOUS FLOOR (rf2-utvst discipline, mirroring
   `require-markdown-files`). These capability files are an EXPECTED surface
   today, so a zero-match result — a further reorg that renames or relocates
   them — throws loudly rather than silently scanning nothing and turning the
   gate into a vacuous green. `label` names the surface for the error.

   Returns the matched files as an `io/file` seq, sorted by path for
   deterministic reporting."
  [label filename]
  (let [docs-dir (repo-file "docs")
        matches  (when (.isDirectory ^java.io.File docs-dir)
                   (->> (.listFiles ^java.io.File docs-dir)
                        (filter #(.isDirectory ^java.io.File %))
                        (map #(io/file % filename))
                        (filter #(.isFile ^java.io.File %))
                        (sort-by #(.getPath ^java.io.File %))))]
    (when (empty? matches)
      (throw (ex-info
               (format (str "%s: no capability doc files matched docs/*/%s — the "
                            "projection gate cannot run against a non-existent "
                            "surface (a moved/renamed capability-doc layout would "
                            "otherwise pass vacuously). Reconcile the surface path.")
                       label filename)
               {:label label :glob (str "docs/*/" filename)})))
    matches))

;; ---------------------------------------------------------------------------
;; Reference extraction.
;; ---------------------------------------------------------------------------

(defn alias-call-references
  "Extract call-position alias-qualified var references — the
   `(alias/<var>` form authors write for a `:require [re-frame.core :as
   alias]` surface — from `lines` (a seq of `[line-no text]`).

   The leading `(` is the load-bearing scope discriminator: it picks out
   a CALL of an alias-qualified var and EXCLUDES the `:alias/<kw>` keyword
   namespace that shares the alias letters (re-frame2's `:rf/*` reserved
   keyword scheme collides with the conventional `rf` alias — a bare
   `rf/<token>` sweep would drown in `:rf/default`, `:rf/machine`, etc.).
   Markdown table-cell pipes and inline back-ticks are tolerated — the
   regex anchors on `(` + alias + `/`, not on surrounding punctuation.

   Returns `[{:var <bare> :line <n> :raw <alias/var>} ...]`."
  [alias lines]
  (let [re (re-pattern (str "\\(" (java.util.regex.Pattern/quote alias)
                            "/([a-zA-Z][a-zA-Z0-9*!?<>=._+-]*)"))]
    (for [[n text] lines
          [_ v]     (re-seq re text)]
      {:var (last (str/split v #"/")) :line n :raw (str alias "/" v)})))

(defn qualified-symbol-references
  "Extract fully-namespace-qualified symbol references whose namespace
   starts with `ns-prefix` (e.g. `day8.re-frame2-xray.`) from `lines`.
   Used for the Xray panel inventory, whose canonical symbol list spells
   each panel as `day8.re-frame2-xray.panels.<area>/Panel`.

   Returns `[{:ns <ns-str> :var <var-str> :line <n> :raw <full>} ...]`."
  [ns-prefix lines]
  (let [re (re-pattern (str "\\b(" (java.util.regex.Pattern/quote ns-prefix)
                            "[a-zA-Z0-9._-]*)/([a-zA-Z][a-zA-Z0-9*!?<>=+-]*)"))]
    (for [[n text] lines
          [_ ns v]  (re-seq re text)]
      {:ns ns :var v :line n :raw (str ns "/" v)})))

;; ---------------------------------------------------------------------------
;; Markdown table-row var-row extraction (the `api-md-check` shape).
;; ---------------------------------------------------------------------------

(defn- table-row-cells
  "Split a markdown `| a | b | c |` row into trimmed cells, or nil when the
   line is not a table row. Mirrors `api-md-check/table-row-cells`."
  [line]
  (when (str/starts-with? (str/triml line) "|")
    (->> (str/split line #"(?<!\\)\|") (drop 1) (mapv str/trim))))

(defn- separator-row? [cells]
  (and (seq cells)
       (every? #(re-matches #":?-{2,}:?" %) (remove str/blank? cells))))

(defn first-cell-backtick-ident
  "When `cell` is exactly one back-tick-quoted simple identifier (one var,
   no spaces / slashes / call-parens), return the bare identifier; else
   nil. This is the var-row discriminator the spec API tables use — a
   first cell like `` `reg-story` `` is a var-row; `` `re-frame.story` ``
   (namespace), `` `:story/set-arg` `` (keyword), or a prose cell is not."
  [cell]
  (when-let [[_ ident] (re-matches #"`([^`]+)`" (str/trim cell))]
    (when (re-matches #"[a-zA-Z][a-zA-Z0-9*!?<>=+-]*" ident)
      ident)))

(defn table-var-rows
  "Parse `lines` and return `[{:var <bare> :line <n>} ...]` for every
   markdown table row whose FIRST cell is a single back-tick-quoted simple
   identifier. Separator rows and prose / namespace / keyword first-cells
   are skipped. Scoped to var-rows the way `api-md-check` scopes to its
   Tier-column tables — but here the table need not carry a Tier column
   (the spec API tables key on the var name, not a tier)."
  [lines]
  (keep (fn [[n text]]
          (when-let [cells (table-row-cells text)]
            (when-not (separator-row? cells)
              (when-let [ident (first-cell-backtick-ident (first cells))]
                {:var ident :line n}))))
        lines))

;; ---------------------------------------------------------------------------
;; File → numbered lines.
;; ---------------------------------------------------------------------------

(defn numbered-lines
  "`[[1 line] [2 line] ...]` for an `io/file`."
  [^java.io.File file]
  (with-open [r (io/reader file)]
    (vec (map-indexed (fn [i line] [(inc i) line]) (line-seq r)))))

;; ---------------------------------------------------------------------------
;; EP-0017 keyword-drift guard (rf2-tawage).
;;
;; The projection checks above all scope to PUBLIC-VAR references and
;; deliberately exclude the `:rf/*` reserved keyword namespace (the leading
;; `(` discriminator drops `:rf/…` keywords so a `(rf/<var>` sweep does not
;; drown in `:rf/default` etc.). That scoping is correct for var-rename
;; drift, but it means a projection can reintroduce STALE EP-0017 KEYWORD
;; vocabulary — `:rf.world/inputs` (renamed to the flat `:rf.cofx` map),
;; grouped/non-flat `:rf.cofx` sub-maps, or coeffect prose missing the
;; `:rf.cofx/requires` declaration — and every var-resolution check stays
;; green.
;;
;; This guard closes that gap with a targeted KEYWORD-level scan. EP-0017's
;; keyword surface is a small closed set:
;;   - `:rf.world/inputs` is RETIRED (renamed to `:rf.cofx`, no alias). Its
;;     only legitimate appearance is a retirement / rename / migration
;;     mention — a line that ALSO names the `:rf.cofx` replacement or the
;;     words retired/renamed/removed/superseded/migration. (It is a draft-only
;;     name, so it earns no dedicated retired-name error id — it rides the
;;     generic `:rf.warning/unknown-dispatch-opt` surface.) A
;;     `:rf.world/inputs` mention WITHOUT such a marker is fresh stale
;;     vocabulary and is RED.
;;
;; The scan is per-line and prose-level (these surfaces are markdown), and
;; intentionally CONSERVATIVE: it only fires on the retired keyword absent a
;; same-line retirement marker, so historical/migration prose — which always
;; names the replacement on the same line — stays green, while a fresh
;; reintroduction in teaching prose goes red.
;; ---------------------------------------------------------------------------

(def ^:private world-inputs-retirement-markers
  "Same-line tokens that mark a `:rf.world/inputs` mention as a legitimate
   retirement / rename / migration reference (rather than fresh stale
   vocabulary). A `:rf.world/inputs` line carrying ANY of these is approved;
   one carrying NONE is flagged. The replacement keyword `:rf.cofx` is the
   strongest marker; the prose words cover narrative migration mentions.
   (`:rf.world/inputs` was a draft-only name, so it earns no dedicated
   retired-name error id — it rides the generic `:rf.warning/unknown-dispatch-opt`
   surface.)"
  [":rf.cofx"
   "retired" "renamed" "removed" "superseded" "migrat"])

(defn ep0017-keyword-drift-problems
  "Return a seq of EP-0017 keyword-drift problem maps for `lines` (a seq of
   `[line-no text]`), tagged with `file` (repo-relative). Flags each
   `:rf.world/inputs` mention NOT accompanied on its line by a retirement /
   rename / migration marker (see `world-inputs-retirement-markers`).

   Pure over the supplied lines so the contract is unit-testable with
   synthetic inputs (rf2-tawage)."
  [file lines]
  (keep (fn [[n text]]
          (when (str/includes? text ":rf.world/inputs")
            (let [lc (str/lower-case text)]
              (when-not (some #(str/includes? lc (str/lower-case %))
                              world-inputs-retirement-markers)
                {:file file :line n :raw ":rf.world/inputs"
                 :detail (str "stale EP-0017 keyword — :rf.world/inputs was "
                              "renamed to the flat :rf.cofx map (no alias); a "
                              "mention must be an explicit retirement/rename/"
                              "migration reference (name :rf.cofx or a "
                              "retired/renamed/migration marker on the line)")}))))
        lines))

;; ---------------------------------------------------------------------------
;; EP-0011 reply-envelope vocabulary-drift guard (rf2-uhew69).
;;
;; The var-resolution projection checks scope to PUBLIC-VAR references and
;; cannot see the EP-0011 reply-envelope KEYWORD vocabulary at all (`rg` over
;; the scripts surface found no `:work/id`, `:stale-key`, or reply-status
;; vocabulary). EP-0011 settled one canonical attempt identity — namespaced
;; `:work/id` — and explicitly RETIRED two near-duplicate spellings
;; ([EP-0007] one-name-per-fact):
;;
;;   - `:stale-key` — the retired second stale-suppression key. EP-0011 keys
;;     stale suppression on `:work/id`; `:stale-key` is dropped (no synonym).
;;   - bare hyphenated `:work-id` — the unqualified spelling that survived in
;;     resource / mutation internal reply events; EP-0011 lowered them all to
;;     the qualified `:work/id`. (`:work-id` legitimately survives ONLY inside
;;     an `:rf.observe/*` correlation example, where it is a string-valued
;;     correlation-id projection, not the reply-map attempt identity.)
;;
;; A teaching/spec surface that reintroduces `:stale-key` or bare `:work-id`
;; as live reply-envelope vocabulary has drifted, while every var-resolution
;; check stays green. This guard closes that gap with a targeted KEYWORD scan,
;; mirroring the EP-0017 guard above: a stale keyword is flagged UNLESS the
;; line ALSO carries a retirement/rename/migration marker (the spec's own
;; retirement prose always names `:work/id` as the canonical replacement) or,
;; for `:work-id`, an `:rf.observe`/`:correlation` context marker.
;; ---------------------------------------------------------------------------

(def ^:private work-id-retirement-markers
  "Same-line tokens that mark a `:stale-key` / bare `:work-id` mention as a
   legitimate retirement / rename / correlation reference rather than fresh
   stale reply-envelope vocabulary. The canonical replacement key `:work/id`
   and the [EP-0007] one-name-per-fact citation are the strongest markers; the
   prose words cover narrative migration mentions; `:rf.observe` /
   `:correlation` mark the one legitimate non-EP-0011 surface where a bare
   `:work-id` correlation-id projection survives."
  [":work/id"
   "ep-0007" "one-name-per-fact" "one name per fact"
   ":rf.observe" ":correlation"
   "retired" "renamed" "removed" "dropped" "superseded" "synonym" "migrat"])

(defn ep0011-reply-vocab-drift-problems
  "Return a seq of EP-0011 reply-envelope vocabulary-drift problem maps for
   `lines` (a seq of `[line-no text]`), tagged with `file` (repo-relative).
   Flags each `:stale-key` or bare hyphenated `:work-id` mention NOT
   accompanied on its line by a retirement / rename / correlation marker (see
   `work-id-retirement-markers`).

   The bare-`:work-id` match is anchored on a NON-`/` boundary so it never
   fires on the canonical namespaced `:work/id` (the `/` after `work` makes it
   `:work/id`, not `:work-id`).

   Pure over the supplied lines so the contract is unit-testable with
   synthetic inputs (rf2-uhew69)."
  [file lines]
  (let [;; `:work-id` (hyphen) but NOT `:work/id` (slash). Trailing boundary
        ;; keeps `:work-id-foo` from matching as the bare identity key.
        work-id-re #":work-id(?![/a-zA-Z0-9*!?<>=._+-])"]
    (keep (fn [[n text]]
            (let [lc       (str/lower-case text)
                  approved (some #(str/includes? lc (str/lower-case %))
                                 work-id-retirement-markers)
                  stale    (cond
                             (str/includes? text ":stale-key")  ":stale-key"
                             (re-find work-id-re text)           ":work-id")]
              (when (and stale (not approved))
                {:file file :line n :raw stale
                 :detail (str "stale EP-0011 reply-envelope vocabulary — the "
                              "canonical attempt identity is the namespaced "
                              ":work/id; " stale " is a retired near-duplicate "
                              "([EP-0007] one-name-per-fact). A mention must be "
                              "an explicit retirement/rename reference (name "
                              ":work/id on the line) or, for :work-id, an "
                              ":rf.observe/:correlation projection context")})))
          lines)))

;; ---------------------------------------------------------------------------
;; EP-0015 privacy / egress vocabulary-drift guard (rf2-1zjkn8).
;;
;; The var-resolution projection checks are blind to EP-0015's keyword
;; vocabulary. EP-0015 renamed two early `:rf.egress/*` profile spellings for
;; axis consistency and the OLD keyword forms must never reappear as live
;; vocabulary:
;;
;;   - `:rf.egress/on-box-hidden-sensitive` → renamed to `:rf.egress/local-redacted`
;;   - `:rf.egress/trusted-local-raw`       → renamed to `:rf.egress/local-raw`
;;
;; These retired keyword profile forms appear only in the superseded EP-0015
;; proposal doc (not a scanned surface); reintroducing either into a teaching
;; / spec / skills surface is drift while the var-resolution checks stay green.
;;
;; SCOPE DISCIPLINE. The match is on the RETIRED `:rf.egress/`-prefixed
;; keyword FORMS only — NOT the bare English adjectives "on-box" /
;; "trusted-local", which remain current trust-boundary descriptors
;; (`:rf.egress/local-raw` IS "trusted local operator"). A mention carrying a
;; retirement/rename marker on the same line (the proposal-doc rename rows
;; name the replacement) is approved. (The earlier positive closed-enum pin
;; over the spec/Conventions.md owner row was removed as over-strict — see
;; the rf2 toomuch trimming review note in api-md-check; this retired-form
;; scan stands.)
;; ---------------------------------------------------------------------------

(def ^:private egress-profile-retirement-markers
  "Same-line tokens that mark a retired `:rf.egress/*` profile-form mention as
   a legitimate rename reference. The replacement profile keywords and the
   rename prose words approve a line; one carrying NONE is fresh stale
   vocabulary."
  [":rf.egress/local-redacted" ":rf.egress/local-raw"
   "renamed" "rename" "retired" "removed" "superseded" "earlier" "migrat"])

(def ^:private retired-egress-profiles
  "The retired `:rf.egress/*` profile keyword FORMS EP-0015 renamed away for
   axis consistency (each followed by its current replacement, for the report)."
  {":rf.egress/on-box-hidden-sensitive" ":rf.egress/local-redacted"
   ":rf.egress/trusted-local-raw"       ":rf.egress/local-raw"})

(defn ep0015-privacy-vocab-drift-problems
  "Return a seq of EP-0015 privacy/egress vocabulary-drift problem maps for
   `lines` (a seq of `[line-no text]`), tagged with `file` (repo-relative).
   Flags each retired `:rf.egress/*` profile keyword form NOT accompanied on
   its line by a rename / retirement marker (see
   `egress-profile-retirement-markers`).

   Pure over the supplied lines so the contract is unit-testable with
   synthetic inputs (rf2-1zjkn8)."
  [file lines]
  (keep (fn [[n text]]
          (let [lc       (str/lower-case text)
                approved (some #(str/includes? lc (str/lower-case %))
                               egress-profile-retirement-markers)
                stale    (some (fn [[retired _]]
                                 (when (str/includes? text retired) retired))
                               retired-egress-profiles)]
            (when (and stale (not approved))
              {:file file :line n :raw stale
               :detail (str "stale EP-0015 egress-profile keyword — " stale
                            " was renamed to " (get retired-egress-profiles stale)
                            " for axis consistency; the retired form must only "
                            "appear in an explicit rename/retirement reference")})))
        lines))

(defn keyword-drift-problems-over-files
  "Run the keyword-drift guards over every file in `files` (io/file seq),
   returning the concatenated problem seq with repo-relative `:file` tags. The
   driver projection checks fold this into their problem list so a keyword /
   vocabulary reintroduction goes RED alongside any var-resolution drift.

   Folds in three guards over the same scanned surfaces:
     - EP-0017 `:rf.world/inputs` keyword drift (rf2-tawage);
     - EP-0011 reply-envelope vocabulary drift — retired `:stale-key` /
       bare `:work-id` attempt-identity spellings (rf2-uhew69);
     - EP-0015 egress-profile vocabulary drift — retired
       `:rf.egress/on-box-hidden-sensitive` / `:rf.egress/trusted-local-raw`
       profile keyword forms (rf2-1zjkn8)."
  [files]
  (mapcat (fn [^java.io.File f]
            (let [rel   (repo-relative f)
                  lines (numbered-lines f)]
              (concat (ep0017-keyword-drift-problems rel lines)
                      (ep0011-reply-vocab-drift-problems rel lines)
                      (ep0015-privacy-vocab-drift-problems rel lines))))
          files))

;; ---------------------------------------------------------------------------
;; Reporting.
;; ---------------------------------------------------------------------------

(defn report-result!
  "Print a uniform OK/DRIFT report and return the boolean verdict. `label`
   names the surface; `checked` is the count of references reconciled;
   `problems` is a seq of `{:file <rel> :line <n> :raw <ref> :detail <s>}`."
  [label checked problems]
  (if (empty? problems)
    (do (println (format "OK: %s projection in sync (%d public-var references checked against the manifest)."
                         label checked))
        true)
    (do (binding [*out* *err*]
          (println (format "DRIFT: %s names public vars the manifest does not carry." label))
          (println "Each reference must resolve to a spec/api-manifest.edn row (a renamed /")
          (println "removed var, or one never manifested — reconcile the surface or add the")
          (println "var to the manifest + its knowingly-unmanifested allowlist in")
          (println "spec/api-manifest-metadata.edn). Problems:")
          (doseq [{:keys [file line raw detail]} problems]
            (println (format "  %s:%d  `%s`  %s" file line raw detail))))
        false)))

(defn vacuity-floor-problem
  "Return a problem-map describing a non-vacuous-floor violation when
   `checked` (the number of public-var references the extractor actually
   reconciled) is below `min-refs`, else nil (rf2-utvst).

   A projection check reports OK whenever its problem list is empty — even
   if it extracted ZERO references. A docs/skills directory move, a markdown
   table-shape change, an alias-convention change, or parser drift can
   silently drop the extracted-reference count toward 0 and turn the gate
   into a vacuous green: stale public-API references would no longer be
   reconciled against the manifest. `min-refs` is a deliberately low floor —
   set well below the current live count — so it trips ONLY on a near-total
   collapse (the vacuous-green class), never on ordinary content churn."
  [label checked min-refs]
  (when (< checked min-refs)
    {:file  "(extractor)"
     :line  0
     :raw   (format "%d reference(s) extracted" checked)
     :detail (format (str "below the non-vacuous floor of %d for %s — the "
                          "extractor reconciled almost nothing, so an OK "
                          "verdict would be vacuous. Likely a moved/renamed "
                          "surface dir, a table-shape/alias-convention change, "
                          "or parser drift. Investigate before trusting GREEN.")
                     min-refs label)}))

(defn report-with-floor!
  "Like `report-result!`, but first enforces a non-vacuous floor: if fewer
   than `min-refs` references were reconciled, prepend a floor-violation
   problem so the gate goes RED even when the (possibly empty) `problems`
   seq would otherwise report a vacuous OK (rf2-utvst)."
  [label checked min-refs problems]
  (let [floor (vacuity-floor-problem label checked min-refs)]
    (report-result! label checked (if floor (cons floor problems) problems))))
