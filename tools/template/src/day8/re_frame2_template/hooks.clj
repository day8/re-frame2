(ns day8.re-frame2-template.hooks
  "deps-new hooks for day8/re-frame2-template (rf2-dolpf §2.2-2.4).

   `template.edn` declares this ns's `data-fn`, `template-fn`, and
   `post-process-fn`. deps-new invokes them in that order:

     1. `data-fn`   — augment the substitution data map.
     2. `template-fn` — return a modified template-edn whose `:transform`
                       drives file emission.
     3. `post-process-fn` — final fix-ups after files have been emitted
                            (e.g. dotfile renames deps-new can't do
                            natively).

   Current scope (§2.2-2.4, rf2-c2770):

     - Substrates: Reagent / UIx / Helix (full matrix).
     - Flags: `:include-story?` (Reagent-only in v1; UIx + Helix variants
       follow once Story's adapter coverage matches Reagent's).
     - Pending flags (deferred to later stages): `:css`, `:include-ssr?`.

   ## Substitution engine note

   deps-new uses **simple `{{key}}` substitution** (see
   `org.corfield.new.impl/->subst-map` + `substitute`). There is **no**
   Mustache-style conditional syntax (`{{#flag}}…{{/flag}}`) — `tools.build`'s
   `copy-dir :replace` does a flat string replace.

   This forces the `:include-story?` branch to be implemented as
   **separate template-source files** rather than conditional blocks
   inside one file: `_reagent/deps.edn` (default) vs
   `_reagent/deps_with_story.edn` (with-story), and similarly for
   `package.json`. `template-fn` picks the right source per the
   flag. The output filename is the same (`deps.edn` / `package.json`)
   regardless of which source ran.

   The steady-state shape (see tools/template/spec/003-DepsNew-Rebuild-Plan.md
   §1) is the same matrix; additional flags (`:css`, `:include-ssr?`)
   slot in here once their upstream gates clear (rf2-gthro, rf2-0m5ea)."
  (:require [clojure.string :as string]))

;; -- :substrate coercion ----------------------------------------------------

(def ^:private valid-substrates #{:reagent :uix :helix})

(defn- coerce-substrate
  "Accept the substrate arg as either a keyword (`:reagent`), a string
  (`reagent` / `:reagent`), or a symbol; return one of
  `valid-substrates` or throw with a clear message."
  [raw]
  (let [s (cond
            (nil? raw)     :reagent
            (keyword? raw) raw
            (symbol? raw)  (keyword (name raw))
            (string? raw)  (keyword (string/replace raw #"^:" ""))
            :else
            (throw (ex-info ":rf.error/template-unrecognised-substrate"
                            {:rf.error/id :rf.error/template-unrecognised-substrate
                             :where     'template/coerce-substrate
                             :recovery  :fix-registration
                             :reason    (str "unrecognised :substrate value: " (pr-str raw))
                             :substrate raw})))]
    (when-not (valid-substrates s)
      (throw (ex-info ":rf.error/template-substrate-must-be-one-of"
                      {:rf.error/id :rf.error/template-substrate-must-be-one-of
                       :where     'template/coerce-substrate
                       :recovery  :fix-registration
                       :reason    (str ":substrate must be one of "
                                       (pr-str valid-substrates)
                                       " (got " (pr-str s) ")")
                       :substrate s
                       :valid     valid-substrates})))
    s))

;; -- :include-story? coercion ----------------------------------------------

(defn- coerce-include-story?
  "Coerce the `:include-story?` arg to a boolean. Accepts true / false /
   nil; rejects anything else with a clear message. The flag is
   Reagent-only in v1 — caller-level guard checks the substrate."
  [raw]
  (cond
    (nil? raw)          false
    (true? raw)         true
    (false? raw)        false
    :else
    (throw (ex-info ":rf.error/template-bad-include-story-flag"
                    {:rf.error/id :rf.error/template-bad-include-story-flag
                     :where     'template/coerce-include-story?
                     :recovery  :fix-registration
                     :reason    (str ":include-story? must be true or false (got "
                                     (pr-str raw) ")")
                     :include-story? raw}))))

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
     {{substrate}}    — the chosen substrate name (`reagent` / `uix` /
                        `helix`).
     {{substrate-badge-url}} — shields.io badge URL keyed by substrate.
     {{rf2-version}}  — runtime coord version (kept in lockstep with
                        the repo-root VERSION file via the §3 release
                        pipeline; pinned manually for now).
     {{shadow-version}} — shadow-cljs npm pin.
     {{react-version}}  — react / react-dom npm pin.

   Substrate + include-story? are also stored under `:substrate-kw` and
   `:include-story?` for `template-fn`'s switch (`->subst-map` would
   otherwise coerce the keyword to a string)."
  [data]
  (let [substrate       (coerce-substrate (:substrate data))
        include-story?  (coerce-include-story? (:include-story? data))
        _               (when (and include-story? (not= substrate :reagent))
                          (throw (ex-info
                                   ":rf.error/template-include-story-reagent-only"
                                   {:rf.error/id :rf.error/template-include-story-reagent-only
                                    :where     'template/data-fn
                                    :recovery  :fix-registration
                                    :reason    (str ":include-story? is Reagent-only in v1 "
                                                    "(got :substrate " substrate
                                                    "). UIx + Helix variants follow once "
                                                    "Story's adapter coverage matches "
                                                    "Reagent's.")
                                    :substrate substrate
                                    :include-story? include-story?})))
        substrate-nm    (name substrate)
        top             (:top data)
        main            (:main data)
        top-file        (->file-path top)
        main-file       (->file-path main)
        top-ns          (->ns-form top)
        main-ns         (->ns-form main)]
    {:substrate           substrate-nm
     :substrate-kw        substrate
     :include-story?      include-story?
     :namespace           (str top-ns "." main-ns)
     :nested-dirs         (str top-file "/" main-file)
     :substrate-badge-url (case substrate
                            :reagent "https://img.shields.io/badge/substrate-Reagent-1abc9c.svg"
                            :uix     "https://img.shields.io/badge/substrate-UIx-3498db.svg"
                            :helix   "https://img.shields.io/badge/substrate-Helix-9b59b6.svg")
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
;; Layout (under `<template-dir>` =
;; `resources/day8/re_frame2_template/`):
;;
;;     ├── root/        — bulk-copied content with default placement
;;     │   ├── README.md  · lefthook.yml
;;     │   ├── dev/{user.clj, scratch.cljs}
;;     │   └── resources/public/{index.html, css/app.css}
;;     ├── _shared/     — substrate-agnostic content that needs renames
;;     │                  (dotfile rename + namespace-path rename for
;;     │                   src/test files; includes stories.cljs which
;;     │                   only emits under :include-story? true)
;;     ├── _reagent/    — Reagent-specific content; includes a
;;     │                  with-story core variant + deps/package
;;     │                  variants
;;     ├── _uix/        — UIx-specific content
;;     └── _helix/      — Helix-specific content
;;
;; The underscore-prefix convention signals "not bulk-copied — picked
;; up by a transform with :only". Per-substrate sub-trees emit only
;; for the chosen substrate.

(defn template-fn
  "Build the `:transform` vector and merge it into the template EDN.

   Three transform groups:

     - Shared renames: dotfile rename (e.g. `gitignore` → `.gitignore`)
       + namespace-path rename for src/test source files.
     - Per-substrate: substrate-specific files including the entry-
       point `core.cljs`, the view module, the build configs.
     - Story scaffolding (Reagent-only, under `:include-story? true`):
       picks `core_with_stories.cljs` instead of `core.cljs`, picks
       `deps_with_story.edn` / `package_with_story.json` instead of
       the default versions, and emits `stories.cljs` from `_shared/`.

   All groups use `:only` so only files explicitly listed in the
   file-map emit (the implicit bulk-copy of `<src-dir>/*` is skipped).
   The default placement files (README.md, lefthook.yml, dev/*,
   resources/public/*) are handled by deps-new's `:root` bulk-copy
   from `root/` — they don't need an entry here.

   Dotfile sources live without the leading dot in the source tree;
   the file-map attaches the dot on the output side (same defensive
   pattern the clj-new template used)."
  [edn data]
  (let [nested         (:nested-dirs data)
        substrate      (:substrate data)
        include-story? (:include-story? data)
        ;; Shared transforms — renames only. `:only` skips the bulk
        ;; copy of `_shared/*`, so source files that don't appear in
        ;; the file-map below DO NOT emit. Add explicit entries if
        ;; you need them.
        shared-files   (cond-> {"gitignore"            ".gitignore"
                                "editorconfig"         ".editorconfig"
                                "cljfmt.edn"           ".cljfmt.edn"
                                "clj-kondo/config.edn" ".clj-kondo/config.edn"
                                ;; src/test renames — re-home into the user's namespace
                                ;; path.
                                "events.cljs"          (str "src/" nested "/events.cljs")
                                "subs.cljs"            (str "src/" nested "/subs.cljs")
                                "schema.cljs"          (str "src/" nested "/schema.cljs")
                                "events_test.cljs"     (str "test/" nested "/events_test.cljs")}
                         ;; Story scaffolding lands under
                         ;; `src/<nested>/stories.cljs` when the flag
                         ;; is on. Same file-map entry; the source
                         ;; lives in _shared/ alongside the other
                         ;; substrate-agnostic files.
                         include-story?
                         (assoc "stories.cljs"
                                (str "src/" nested "/stories.cljs")))
        shared         [["_shared" "." shared-files :only]]

        ;; Per-substrate transforms. `:only` keeps each substrate
        ;; transform self-documenting (any new file under
        ;; `_<substrate>/` must be opted in here).
        per-substrate
        (case substrate
          "reagent"
          (let [core-src    (if include-story?
                              "core_with_stories.cljs"
                              "core.cljs")
                deps-src    (if include-story?
                              "deps_with_story.edn"
                              "deps.edn")
                package-src (if include-story?
                              "package_with_story.json"
                              "package.json")]
            [["_reagent" "."
              {deps-src          "deps.edn"
               "shadow-cljs.edn" "shadow-cljs.edn"
               package-src       "package.json"
               core-src          (str "src/" nested "/core.cljs")
               "views.cljs"      (str "src/" nested "/views.cljs")}
              :only]])

          "uix"
          [["_uix" "."
            {"deps.edn"        "deps.edn"
             "shadow-cljs.edn" "shadow-cljs.edn"
             "package.json"    "package.json"
             "core.cljs"       (str "src/" nested "/core.cljs")
             "views.cljs"      (str "src/" nested "/views.cljs")}
            :only]]

          "helix"
          [["_helix" "."
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
   today — `template-fn`'s file-map handles dotfile renames inline."
  [_edn data]
  (let [substrate      (:substrate data)
        include-story? (:include-story? data)
        story-tag      (if include-story? " (with Story playground)" "")]
    (println (str "Generated a re-frame2 application " (:name data)
                  " (" substrate " substrate" story-tag ").")))
  (println "Next steps:")
  ;; `:target-dir` is preprocess-options' computed output dir
  ;; (defaults to `(:main data)` when no `:target-dir` arg is given).
  (println (str "  cd " (:target-dir data)))
  (println "  npm install")
  (println "  npx shadow-cljs watch app")
  (println "Then open http://localhost:8280")
  nil)
