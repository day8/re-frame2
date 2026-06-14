(ns re-frame.api-manifest.gen
  "Public-API manifest generator + drift-check (rf2-3nbl5.2 — the
  API-governance keystone).

  THE PROBLEM. re-frame2 has one truth — the public API — with many
  projections (spec/API.md, the per-tool specs, the docs, the MCP tool
  descriptors, the skills). Projections drift. A one-time reconciliation
  fixes today's drift; a generated, CI-guarded MANIFEST prevents
  tomorrow's.

  THE ARTEFACT. `spec/api-manifest.edn` is the machine-readable public-API
  manifest — one row per public var:

      {:namespace ..., :var ..., :tier ..., :kind ..., :owner ...,
       :status ..., :facade? ..., :runtime-verified? ...}

  SINGLE-SOURCE DESIGN — code + curated sidecar.
    - EXISTENCE + :kind + :facade? are DERIVED from live vars (the
      introspectable truth). For every JVM-loadable public namespace
      this generator calls `ns-publics`, drops the documented `^:no-doc`
      internal carve-outs, and derives `:kind` (`:macro` / `:fn` /
      `:var`) and `:facade?` (does the var live in the user-facing
      `re-frame.core` façade, or in its home artefact ns?) from var
      metadata.
    - :tier / :owner / :status are CURATED in the sidecar
      `spec/api-manifest-metadata.edn`, keyed by `[namespace var]`,
      because they are human-classification axes that cannot be derived
      from code (the Tier closed vocabulary lives in spec/API.md §Tier
      taxonomy; the owning Spec is editorial).
    - The manifest is their JOIN. Code owns *what exists*; the sidecar
      owns *how it is classified*; neither fact has two homes.

  DRIFT-CHECK. `--check` regenerates the manifest in memory and compares
  it to the committed `spec/api-manifest.edn`. Any difference — a public
  var added, removed, or renamed in code; a var missing a sidecar entry;
  a stale sidecar entry for a var that no longer exists — fails the
  check. This is the PRIMARY drift-guard: it goes red in CI until the
  manifest + sidecar are updated.

  CLJS-ONLY SURFACES. The Reagent / UIx / Helix adapter namespaces, the
  Xray `mount-*!` family, and the pair-MCP server are ClojureScript-only
  and cannot be `require`d on the JVM. Their rows live in the sidecar
  under `:cljs-only` and are carried through verbatim. The JVM
  existence-check does not reach them; instead a CLJS-side enumeration
  probe (rf2-2mtte, implementation/scripts/api-manifest/probe/, run by
  `npm run test:cljs`) reconciles each covered namespace's live public
  vars against its rows. A `:cljs-only` row carries its own
  `:runtime-verified?` flag — `true` once the probe covers its
  namespace, `false` otherwise — and the generator emits that flag
  verbatim (it does not itself run the probe)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

;; ---------------------------------------------------------------------------
;; Locating the repo root + artefact paths.
;;
;; The generator runs from implementation/scripts/api-manifest/; the repo
;; root is four levels up. We resolve it relative to *this* file's
;; classpath entry so the generator works from any CWD on any platform.
;; ---------------------------------------------------------------------------

(def ^:private here
  "implementation/scripts/api-manifest — the generator's own directory,
  derived from the `user.dir` the clojure CLI sets to the deps.edn dir."
  (io/file (System/getProperty "user.dir")))

(def repo-root
  "Repo root = three dirs above implementation/scripts/api-manifest/
   (api-manifest → scripts → implementation → <repo-root>)."
  (-> here .getParentFile .getParentFile .getParentFile))

(defn- spec-file [name]
  (io/file repo-root "spec" name))

(def manifest-file (delay (spec-file "api-manifest.edn")))
(def sidecar-file  (delay (spec-file "api-manifest-metadata.edn")))

;; ---------------------------------------------------------------------------
;; JVM-loadable public namespaces.
;;
;; Every namespace here is `.cljc` (or `.clj`) and loads on the JVM, so
;; `ns-publics` returns its live public vars. The order is editorial and
;; does not affect output (rows are sorted before emission).
;; ---------------------------------------------------------------------------

(def jvm-namespaces
  "Public namespaces this generator introspects on the JVM. Each maps to
  the same façade?/home decision the sidecar's `:owner` records — but the
  generator derives existence + kind from the live vars here, not the
  sidecar."
  '[;; Core façade + the two sibling test namespaces.
    re-frame.core
    re-frame.test-support
    re-frame.test-helpers
    ;; Optional feature artefacts (public home namespaces).
    re-frame.schemas
    re-frame.machines
    re-frame.routing
    re-frame.flows
    re-frame.http
    re-frame.ssr
    re-frame.ssr.ring
    re-frame.epoch
    ;; Tool artefacts with JVM-loadable public surfaces.
    re-frame.story
    ;; MCP support namespaces — the tooling trace/egress surfaces the
    ;; pair-MCP servers consume (per spec/API.md §Tiering of cross-tool
    ;; surfaces). Only the public-var-bearing ones are introspected.
    re-frame.mcp-base.elision
    re-frame.mcp-base.sensitive])

(def extra-vars
  "Individually-named public vars whose HOME namespace is mostly internal
   (so we do NOT enumerate the whole namespace) but which spec/API.md rows
   as a documented public surface. Each is JVM-introspected and
   runtime-verified individually. Shape: `[ns-sym var-sym]`."
  '[;; The two dev-gate Vars rowed in spec/API.md §Tracing. Their home
    ;; namespaces (re-frame.interop / re-frame.performance) are otherwise
    ;; internal plumbing.
    [re-frame.interop     debug-enabled?]
    [re-frame.performance enabled?]])

;; ---------------------------------------------------------------------------
;; Derivation from live vars.
;; ---------------------------------------------------------------------------

(defn- kind-of
  "Derive the manifest `:kind` for a var from its metadata.
   `:macro` — a defmacro; `:fn` — a fn / has an arglist; `:var` — a plain
   value (e.g. an adapter map, a canonical-vocabulary set)."
  [v]
  (let [m (meta v)]
    (cond
      (:macro m)                 :macro
      (or (:arglists m) (fn? @v)) :fn
      :else                      :var)))

(defn- public-vars-of
  "Live `{var-symbol -> kind}` for a namespace, minus the documented
   `^:no-doc` internal carve-outs (per spec/API.md §Not-rowed internal
   carve-outs). Returns a sorted seq of `[var-sym kind]`."
  [ns-sym]
  (require ns-sym)
  (->> (ns-publics ns-sym)
       (remove (fn [[_ v]] (:no-doc (meta v))))
       (map (fn [[sym v]] [sym (kind-of v)]))
       (sort-by first)))

(defn- resolve-extra-var
  "Resolve an individually-named [ns-sym var-sym] from `extra-vars` to a
   live var + its kind, or throw if it no longer exists."
  [[ns-sym var-sym]]
  (require ns-sym)
  (if-let [v (ns-resolve ns-sym var-sym)]
    [var-sym (kind-of v)]
    (throw (ex-info (str "extra-vars names a var that no longer exists: "
                         ns-sym "/" var-sym
                         " — remove it from `extra-vars` in the generator.")
                    {:ns ns-sym :var var-sym}))))

(defn all-jvm-vars
  "Every JVM-introspected `[ns-sym var-sym kind]` triple — the full set of
   whole-namespace public vars PLUS the individually-named `extra-vars`.
   The single source the manifest build + stale-check both read."
  []
  (concat
   (for [ns-sym jvm-namespaces
         [var-sym kind] (public-vars-of ns-sym)]
     [ns-sym var-sym kind])
   (for [[ns-sym var-sym :as ev] extra-vars]
     (let [[_ kind] (resolve-extra-var ev)]
       [ns-sym var-sym kind]))))

;; ---------------------------------------------------------------------------
;; The sidecar — curated classification metadata.
;;
;; Shape:
;;   {:meta {...}
;;    :classification {[ns-str var-str] {:tier ... :owner ... :status ...}
;;                     ...}
;;    :cljs-only [{:namespace ... :var ... :kind ... :facade? ...
;;                 :tier ... :owner ... :status ...} ...]}
;; ---------------------------------------------------------------------------

(defn read-sidecar []
  (with-open [r (io/reader @sidecar-file)]
    (edn/read (java.io.PushbackReader. r))))

(defn- facade?
  "A var is part of the user-facing `re-frame.core` façade iff it is
   published from that namespace. Everything else lives in its home
   artefact namespace."
  [ns-sym]
  (= 're-frame.core ns-sym))

(defn- classification-for
  "Look up the curated {:tier :owner :status} for a `[ns var]` pair.
   Returns nil (→ a drift error) when the sidecar has no entry."
  [classification ns-sym var-sym]
  (get classification [(name ns-sym) (name var-sym)]))

;; ---------------------------------------------------------------------------
;; Building the manifest rows.
;; ---------------------------------------------------------------------------

(defn build-rows
  "Return `[rows missing]` where rows is the sorted vector of manifest
   maps and `missing` is the vector of `[ns var]` pairs the sidecar does
   not classify (a drift error). Includes both the JVM-introspected rows
   and the curated CLJS-only rows from the sidecar."
  [sidecar]
  (let [classification (:classification sidecar)
        jvm-rows-and-missing
        (for [[ns-sym var-sym kind] (all-jvm-vars)]
          (if-let [c (classification-for classification ns-sym var-sym)]
            {:row {:namespace         (name ns-sym)
                   :var               (name var-sym)
                   :tier              (:tier c)
                   :kind              kind
                   :owner             (:owner c)
                   :status            (:status c)
                   :facade?           (facade? ns-sym)
                   :runtime-verified? true}}
            {:missing [(name ns-sym) (name var-sym)]}))
        jvm-rows (keep :row jvm-rows-and-missing)
        missing  (keep :missing jvm-rows-and-missing)
        cljs-rows
        (for [r (:cljs-only sidecar)]
          {:namespace         (:namespace r)
           :var               (:var r)
           :tier              (:tier r)
           :kind              (:kind r)
           :owner             (:owner r)
           :status            (:status r)
           :facade?           (boolean (:facade? r))
           ;; Per-row flag (rf2-2mtte): a `:cljs-only` row is
           ;; `:runtime-verified? true` once the CLJS-side enumeration
           ;; probe (implementation/scripts/api-manifest/probe/, run by
           ;; `npm run test:cljs`) covers its namespace — the probe is the
           ;; CLJS equivalent of the JVM `ns-publics` existence-check.
           ;; Rows the probe does not (yet) cover stay `false`. The JVM
           ;; generator carries the curated flag through verbatim; it does
           ;; not itself run the CLJS probe.
           :runtime-verified? (boolean (:runtime-verified? r))})
        rows (->> (concat jvm-rows cljs-rows)
                  (sort-by (juxt :namespace :var))
                  vec)]
    [rows (vec missing)]))

(defn- stale-sidecar-entries
  "Curated classification keys whose `[ns var]` no longer resolves to a
   live public var — a stale entry that must be removed (drift)."
  [sidecar]
  (let [live (set (for [[ns-sym var-sym _] (all-jvm-vars)]
                    [(name ns-sym) (name var-sym)]))]
    (->> (:classification sidecar)
         (map key)
         (remove live)
         (sort-by (juxt first second))
         vec)))

(defn duplicate-rows
  "Return a sorted vector of `[[namespace var] count]` for every
   `[namespace var]` key carried by MORE THAN ONE manifest row (rf2-nlnd9y.2).

   The manifest contract is one row per public var (this ns docstring's THE
   ARTEFACT note). JVM-derived rows are unique by construction (a namespace's
   `ns-publics` map has unique var names, and `extra-vars` adds distinct
   pairs). But the curated `:cljs-only` sidecar rows are carried through
   VERBATIM and concatenated with the JVM rows, with no uniqueness check — so
   a duplicated `:cljs-only` entry, or a `:cljs-only` row colliding with a
   JVM-derived row, produced a manifest with two rows for one var (possibly
   with conflicting tier/kind/status/runtime metadata) and an inflated
   `:var-count`. Downstream projections then either silently overwrite one
   row (`index-by-ns+var` `assoc`) or tolerate multiple tiers — masking the
   contradiction. Detecting duplicates HERE, before write / `--check`, keeps
   the one-row-per-var invariant where it is generated."
  [rows]
  (->> rows
       (group-by (juxt :namespace :var))
       (keep (fn [[k group]] (when (> (count group) 1) [k (count group)])))
       (sort-by (comp (juxt first second) first))
       vec))

(defn build-manifest
  "Build the full manifest data structure (the value written to
   spec/api-manifest.edn). Throws on missing / stale sidecar entries with
   an actionable message — that throw is what turns the drift-check red."
  [sidecar]
  (let [[rows missing] (build-rows sidecar)
        stale          (stale-sidecar-entries sidecar)
        dups           (duplicate-rows rows)]
    (when (seq missing)
      (throw (ex-info
              (str "Public vars with no sidecar classification (add a "
                   "`spec/api-manifest-metadata.edn` :classification entry "
                   "with :tier/:owner/:status for each):\n  "
                   (str/join "\n  " (map #(str/join "/" %) missing)))
              {:missing missing})))
    (when (seq stale)
      (throw (ex-info
              (str "Stale sidecar :classification entries — these "
                   "`[namespace var]` pairs no longer resolve to a live "
                   "public var (remove them from "
                   "spec/api-manifest-metadata.edn):\n  "
                   (str/join "\n  " (map #(str/join "/" %) stale)))
              {:stale stale})))
    ;; One-row-per-public-var invariant (rf2-nlnd9y.2). A duplicate
    ;; `[namespace var]` — within `:cljs-only`, or between a `:cljs-only`
    ;; row and a JVM-derived row — must FAIL generation / `--check`, never
    ;; ship two rows for one var (which inflates :var-count and lets
    ;; downstream projections silently overwrite or mask conflicting tiers).
    (when (seq dups)
      (throw (ex-info
              (str "Duplicate manifest rows — these `[namespace var]` keys "
                   "appear MORE THAN ONCE (the manifest is one row per public "
                   "var). A duplicate within `:cljs-only`, or a `:cljs-only` "
                   "row colliding with a JVM-derived row, must be removed from "
                   "spec/api-manifest-metadata.edn (a JVM-loadable var must "
                   "NOT also be hand-rowed under :cljs-only):\n  "
                   (str/join "\n  "
                             (map (fn [[[ns-str var-str] n]]
                                    (str ns-str "/" var-str " (" n " rows)"))
                                  dups)))
              {:duplicates dups})))
    {:meta {:doc        (str "GENERATED public-API manifest — do NOT hand-edit "
                             "the :vars list. Regenerate with: clojure -M -m "
                             "re-frame.api-manifest.gen (run from "
                             "implementation/scripts/api-manifest/). The "
                             "tier/owner/status axes are curated in "
                             "spec/api-manifest-metadata.edn; existence + kind "
                             "+ facade? are derived from live public vars. "
                             "See that generator ns for the design.")
            :keystone   "rf2-3nbl5.2"
            :var-count  (count rows)
            :tier-vocab [:front-porch :advanced :tooling :adapter
                         :testing :internal-public :implementation
                         :deprecated]}
     :vars rows}))

;; ---------------------------------------------------------------------------
;; EDN emission — deterministic, stable, diff-friendly.
;; ---------------------------------------------------------------------------

(def ^:private row-key-order
  [:namespace :var :tier :kind :owner :status :facade? :runtime-verified?])

(defn- ordered-row
  "An array-map preserving the canonical key order so the printed EDN is
   stable across runs (sorted-map would alphabetise; we want :namespace
   first)."
  [row]
  (apply array-map (mapcat (fn [k] [k (get row k)]) row-key-order)))

(defn render-edn
  "Render the manifest to a deterministic EDN string. One row per line
   keeps git diffs surgical — adding a var is a one-line diff.

   Line endings are normalised to bare `\\n` so the output is
   byte-identical on Windows and Linux — `pprint` emits the
   platform line-separator, which would otherwise make the committed
   (Windows) file mismatch a CI (Linux) regeneration and trip a spurious
   drift failure. The committed file is `.gitattributes`-pinned to LF for
   the same reason."
  [manifest]
  (let [{:keys [meta vars]} manifest
        raw (str ";; GENERATED by implementation/scripts/api-manifest — do NOT hand-edit.\n"
                 ";; Regenerate: clojure -M -m re-frame.api-manifest.gen (from\n"
                 ";; implementation/scripts/api-manifest/). Curated tier/owner/status\n"
                 ";; live in spec/api-manifest-metadata.edn. Keystone rf2-3nbl5.2.\n"
                 "{:meta\n "
                 (with-out-str (pprint/pprint meta))
                 " :vars\n ["
                 (str/join "\n  " (map (comp pr-str ordered-row) vars))
                 "]}\n")]
    (str/replace raw "\r\n" "\n")))

;; ---------------------------------------------------------------------------
;; Read-back of the committed manifest (for --check).
;; ---------------------------------------------------------------------------

(defn read-committed-manifest []
  (when (.exists ^java.io.File @manifest-file)
    (with-open [r (io/reader @manifest-file)]
      (edn/read (java.io.PushbackReader. r)))))

;; ---------------------------------------------------------------------------
;; Entry points.
;; ---------------------------------------------------------------------------

(defn generate!
  "Regenerate spec/api-manifest.edn from live vars + the curated sidecar.
   Returns the manifest map."
  []
  (let [manifest (build-manifest (read-sidecar))]
    (spit @manifest-file (render-edn manifest))
    (println (format "Wrote %s (%d public vars)."
                     (.getPath ^java.io.File @manifest-file)
                     (:var-count (:meta manifest))))
    manifest))

(defn check!
  "Regenerate in memory and compare to the committed manifest. Returns
   true when in sync, false (with a printed diff summary) when drifted."
  []
  (let [generated (build-manifest (read-sidecar))
        committed (read-committed-manifest)
        gen-vars  (set (:vars generated))
        com-vars  (set (:vars committed))]
    (cond
      (nil? committed)
      (do (binding [*out* *err*]
            (println "DRIFT: spec/api-manifest.edn does not exist. Run the generator."))
          false)

      ;; Compare LF-normalised so a CRLF working-tree checkout on Windows
      ;; does not trip a spurious drift (the canonical committed file is LF).
      (= (render-edn generated) (str/replace (slurp @manifest-file) "\r\n" "\n"))
      (do (println (format "OK: spec/api-manifest.edn in sync (%d public vars)."
                           (:var-count (:meta generated))))
          true)

      :else
      (let [added   (sort-by (juxt :namespace :var) (set/difference gen-vars com-vars))
            removed (sort-by (juxt :namespace :var) (set/difference com-vars gen-vars))]
        (binding [*out* *err*]
          (println "DRIFT: generated manifest differs from spec/api-manifest.edn.")
          (println "Regenerate with: clojure -M -m re-frame.api-manifest.gen")
          (when (seq added)
            (println "  Rows the generator produced that the committed file lacks"
                     "(new/renamed public var or changed classification):")
            (doseq [r added] (println "    +" (:namespace r) "/" (:var r)
                                      "->" (:tier r) (:kind r) (:status r))))
          (when (seq removed)
            (println "  Rows in the committed file the generator no longer produces"
                     "(removed/renamed public var):")
            (doseq [r removed] (println "    -" (:namespace r) "/" (:var r))))
          (when (and (empty? added) (empty? removed))
            (println "  (var set identical; :meta or formatting differs — regenerate)")))
        false))))

(defn -main [& args]
  (try
    (if (some #{"--check"} args)
      (System/exit (if (check!) 0 1))
      (do (generate!) (System/exit 0)))
    (catch Throwable t
      (binding [*out* *err*]
        (println "api-manifest generator FAILED:")
        (println (.getMessage t)))
      (System/exit 2))))
