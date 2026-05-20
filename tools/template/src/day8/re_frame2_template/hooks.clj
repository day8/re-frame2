(ns day8.re-frame2-template.hooks
  "deps-new hooks for day8/re-frame2-template (rf2-dolpf §2.1).

   `template.edn` declares this ns's `data-fn`, `template-fn`, and
   `post-process-fn`. deps-new invokes them in that order:

     1. `data-fn`   — augment the substitution data map.
     2. `template-fn` — return a modified template-edn whose `:transform`
                       drives file emission.
     3. `post-process-fn` — final fix-ups after files have been emitted
                            (e.g. dotfile renames deps-new can't do
                            natively).

   §2.1 spike scope:

     - Substrate: Reagent only (UIx + Helix land in §2.2).
     - Flags: none (`:include-story?` / `:css` / `:include-ssr?` land in
       §2.4 / §2.2-2.3).

   The full three-substrate matrix + three-flag set is the steady-state
   shape (see tools/template/spec/003-DepsNew-Rebuild-Plan.md §1)."
  (:require [clojure.string :as string]))

;; -- :substrate coercion ----------------------------------------------------

(def ^:private valid-substrates #{:reagent})  ; §2.1 spike: Reagent only.

(defn- coerce-substrate
  "Accept the substrate arg as either a keyword (`:reagent`), a string
  (`reagent` / `:reagent`), or a symbol; return one of `valid-substrates`
  or throw with a clear message.

  In §2.1 the only legal value is `:reagent`; passing anything else
  (including the eventual `:uix` / `:helix` values) throws — they land
  in §2.2."
  [raw]
  (let [s (cond
            (nil? raw)     :reagent
            (keyword? raw) raw
            (symbol? raw)  (keyword (name raw))
            (string? raw)  (keyword (string/replace raw #"^:" ""))
            :else
            (throw (ex-info (str "Unrecognised :substrate value: " (pr-str raw))
                            {:substrate raw})))]
    (when-not (valid-substrates s)
      (throw (ex-info (str ":substrate must be one of "
                           (pr-str valid-substrates)
                           " in the §2.1 spike (got " (pr-str s)
                           "). UIx + Helix land in §2.2 (rf2-c2770).")
                      {:substrate s :valid valid-substrates})))
    s))

;; -- data-fn ----------------------------------------------------------------

;; -- name derivations ------------------------------------------------------
;;
;; deps-new's `preprocess-options` populates the opts map with the
;; bare project-name fields BUT NOT the `/ns` / `/file` derivatives —
;; those are computed later by `->subst-map`, after `data-fn` and
;; `template-fn` have already run. So our data-fn computes them
;; locally for use in rename targets (the file-map values are pure
;; Clojure strings, resolved at template-fn time before `->subst-map`
;; gets near them).
;;
;; The transformations match `->subst-map`'s rules:
;;   - `/file` form: dots → slashes, dashes → underscores.
;;   - `/ns`   form: slashes → dots, underscores → dashes.

(defn- ->file-path
  "Convert a name segment to a file-system-path component:
   dots → slashes, dashes → underscores."
  [s]
  (-> s str (string/replace "." "/") (string/replace "-" "_")))

(defn- ->ns-form
  "Convert a name segment to a namespace component:
   slashes → dots, underscores → dashes."
  [s]
  (-> s str (string/replace "/" ".") (string/replace "_" "-")))

;; -- data-fn ----------------------------------------------------------------

(defn data-fn
  "Augment deps-new's substitution data with re-frame2 template fields.

   deps-new auto-derives the project-name keys (from
   `preprocess-options` + `->subst-map`):

     {{name}}         — the qualified raw symbol (e.g. `acme/my-app`)
     {{top}}          — group portion (e.g. `acme`)
     {{main}}         — artifact portion (e.g. `my-app`)
     {{top/ns}}       — namespace-safe top (`acme`)       ; ←
     {{main/ns}}      — namespace-safe main (`my-app`)    ; ← computed by ->subst-map
     {{top/file}}     — file-safe top (`acme`)            ; ←
     {{main/file}}    — file-safe main (`my_app`)         ; ←

   On top of those we add:

     {{namespace}}    — derived `{{top/ns}}.{{main/ns}}` (matches the
                        clj-new template's user-facing var; downstream
                        Selmer-substituted files key off this).
     {{nested-dirs}}  — derived `{{top/file}}/{{main/file}}` (file-path
                        component, e.g. `acme/my_app` — used in
                        `src/<nested-dirs>/core.cljs` rename targets).
     {{substrate}}    — the chosen substrate name (`reagent`).
     {{substrate-badge-url}} — shields.io badge URL keyed by substrate.
     {{rf2-version}}  — runtime coord version (kept in lockstep with
                        the repo-root VERSION file via the §3 release
                        pipeline; pinned manually in the §2.1 spike).
     {{shadow-version}} — shadow-cljs npm pin.
     {{react-version}}  — react / react-dom npm pin.

   Stored in `:substrate-kw` for `template-fn`'s switch."
  [data]
  (let [substrate    (coerce-substrate (:substrate data))
        substrate-nm (name substrate)
        top          (:top data)
        main         (:main data)
        top-file     (->file-path top)
        main-file    (->file-path main)
        top-ns       (->ns-form top)
        main-ns      (->ns-form main)]
    {:substrate           substrate-nm
     :substrate-kw        substrate
     :namespace           (str top-ns "." main-ns)
     :nested-dirs         (str top-file "/" main-file)
     :substrate-badge-url (case substrate
                            :reagent "https://img.shields.io/badge/substrate-Reagent-1abc9c.svg")
     :rf2-version         "0.0.1.alpha"
     :shadow-version      "3.4.10"
     :react-version       "19.2.0"}))

;; -- template-fn ------------------------------------------------------------
;;
;; deps-new's file-emission contract:
;;
;;   1. Bulk copy `<template-dir>/root/` → `<target-dir>/` (the project
;;      root). Substitution applies; no renames.
;;   2. For each entry in the `:transform` vector, run a second copy
;;      with the file-map's rename rules applied.
;;
;; Each transform entry is:
;;
;;     [src-dir target-dir file-map delimiters & flags]
;;
;; - `src-dir` is relative to the template-dir (the directory
;;   containing `template.edn`), NOT relative to `root/`.
;; - `target-dir` is relative to the project root; supports `{{var}}`
;;   substitution.
;; - `file-map` renames files inside the transform (substitution
;;   applies to both keys and values).
;; - Flags: `:only` (copy ONLY files in file-map; skip the implicit
;;   bulk-copy of `src-dir`), `:raw` (no substitution).
;;
;; §2.1 spike layout (under `<template-dir>` =
;; `resources/day8/re_frame2_template/`):
;;
;;     ├── root/        — bulk-copied content with default placement
;;     │   ├── README.md  · lefthook.yml
;;     │   ├── dev/{user.clj, scratch.cljs}
;;     │   └── resources/public/{index.html, css/app.css}
;;     ├── _shared/     — substrate-agnostic content that needs renames
;;     │                  (dotfile rename + namespace-path rename for
;;     │                   src/test files)
;;     └── _reagent/    — Reagent-specific content (renames into the
;;                        namespace path)
;;
;; The underscore-prefix convention signals "not bulk-copied — picked
;; up by a transform with :only". Per-substrate sub-trees emit only
;; for the chosen substrate (only `_reagent/` exists today; `_uix/` +
;; `_helix/` land in §2.2).

(defn template-fn
  "Build the `:transform` vector and merge it into the template EDN.

   Two transform groups:

     - Shared renames: dotfile rename (e.g. `gitignore` → `.gitignore`)
       + namespace-path rename for src/test source files.
     - Per-substrate: substrate-specific files including the entry-
       point `core.cljs`, the view module, the build configs.

   Both groups use `:only` so only files explicitly listed in the
   file-map emit (the implicit bulk-copy of `<src-dir>/*` is skipped).
   The default placement files (README.md, lefthook.yml, dev/*,
   resources/public/*) are handled by deps-new's `:root` bulk-copy
   from `root/` — they don't need an entry here.

   Dotfile sources live without the leading dot in the source tree;
   the file-map attaches the dot on the output side (same defensive
   pattern the clj-new template used)."
  [edn data]
  (let [nested (:nested-dirs data)
        substrate (:substrate data)
        ;; Shared transforms — renames only. `:only` skips the bulk
        ;; copy of `_shared/*`, so source files that don't appear in
        ;; the file-map below DO NOT emit. Add explicit entries if
        ;; you need them.
        shared
        [["_shared" "."
          {"gitignore"            ".gitignore"
           "editorconfig"         ".editorconfig"
           "cljfmt.edn"           ".cljfmt.edn"
           "clj-kondo/config.edn" ".clj-kondo/config.edn"
           ;; src/test renames — re-home into the user's namespace
           ;; path.
           "events.cljs"          (str "src/" nested "/events.cljs")
           "subs.cljs"            (str "src/" nested "/subs.cljs")
           "events_test.cljs"     (str "test/" nested "/events_test.cljs")}
          :only]]

        ;; Per-substrate transforms (Reagent only in §2.1). `:only`
        ;; keeps the substrate transform self-documenting (any new
        ;; file under `_reagent/` must be opted in here).
        per-substrate
        (case substrate
          "reagent"
          [["_reagent" "."
            {"deps.edn"        "deps.edn"
             "shadow-cljs.edn" "shadow-cljs.edn"
             "package.json"    "package.json"
             "core.cljs"       (str "src/" nested "/core.cljs")
             "views.cljs"      (str "src/" nested "/views.cljs")}
            :only]])]
    (assoc edn :transform (into [] (concat shared per-substrate)))))

;; -- post-process-fn --------------------------------------------------------

(defn post-process-fn
  "After file emission, log what landed and where. No fix-ups required
   today — `template-fn`'s file-map handles dotfile renames inline; the
   §2.2 port may grow real post-processing as the matrix expands."
  [_edn data]
  (println (str "Generated a re-frame2 application " (:name data)
                " (" (:substrate data) " substrate)."))
  (println "Next steps:")
  ;; `:target-dir` is preprocess-options' computed output dir
  ;; (defaults to `(:main data)` when no `:target-dir` arg is given).
  (println (str "  cd " (:target-dir data)))
  (println "  npm install")
  (println "  npx shadow-cljs watch app")
  (println "Then open http://localhost:8280")
  nil)
