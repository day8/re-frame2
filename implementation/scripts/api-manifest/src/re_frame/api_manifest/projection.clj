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
   path for deterministic reporting. Skips a missing dir (returns nil)."
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
