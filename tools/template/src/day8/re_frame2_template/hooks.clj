(ns day8.re-frame2-template.hooks
  "deps-new hooks for day8/re-frame2-template.

   `template.edn` declares this ns's `data-fn`, `template-fn`, and
   `post-process-fn`. deps-new invokes them in that order:

     1. `data-fn`   — augment the substitution data map.
     2. `template-fn` — return a modified template-edn whose `:transform`
                       drives file emission.
     3. `post-process-fn` — final fix-ups after files have been emitted
                            (e.g. dotfile renames deps-new can't do
                            natively).

   Current scope (003-DepsNew-Rebuild-Plan.md §2.2-2.4):

     - Substrates: Reagent / UIx / Helix (full matrix).
     - Flags: `:include-story?` (Reagent-only in v1; UIx + Helix variants
       follow once Story's adapter coverage matches Reagent's).
     - Pending flags (reserved, gated to later stages): `:css`,
       `:include-ssr?`. Passing one today fails closed with
       `:rf.error/template-unsupported-flag` (it is NOT silently dropped)
       — see `gate-arg-keys!`.

   ## Substitution engine note

   deps-new uses **simple `{{key}}` substitution** (see
   `org.corfield.new.impl/->subst-map` + `substitute`). There is **no**
   Mustache-style conditional syntax (`{{#flag}}…{{/flag}}`) — `tools.build`'s
   `copy-dir :replace` does a flat string replace.

   This forces the `:include-story?` branch to be implemented as
   **separate template-source files** rather than conditional blocks
   inside one file: `_reagent/deps.edn` (default) vs
   `_reagent/deps_with_story.edn` (with-story). `template-fn` picks the
   right source per the flag; the output filename is the same
   (`deps.edn`) regardless of which source ran.

   `package.json` is the exception. Its sole per-flag delta — the
   `description` parenthetical naming the Story playground — is small
   enough to carry as the `{{story-tag}}` subst var, so a single
   `_shared/package.json` source serves both paths. No
   second `package_with_story.json` source exists.

   The steady-state shape (see tools/template/spec/003-DepsNew-Rebuild-Plan.md
   §1) is the same matrix; additional flags (`:css`, `:include-ssr?`)
   slot in here once their upstream gates clear."
  (:require [clojure.set :as set]
            [clojure.string :as string]))

;; -- substrate registry -----------------------------------------------------
;;
;; Single source of truth for the per-substrate facts. Each substrate's
;; display label and shields.io badge URL live in one row, so adding a
;; substrate is a one-row edit (the valid-set, the data-fn label/badge
;; lookups, and `template-fn`'s `case` all key off this same set). Keeping
;; these as one data table — rather than three parallel `case` forms keyed
;; on the same `:reagent/:uix/:helix` set — is the data-driven idiom and
;; removes the drift risk of the keys diverging across sites.

(def ^:private substrate-registry
  {:reagent {:label "Reagent"
             :badge-url "https://img.shields.io/badge/substrate-Reagent-1abc9c.svg"}
   :uix     {:label "UIx"
             :badge-url "https://img.shields.io/badge/substrate-UIx-3498db.svg"}
   :helix   {:label "Helix"
             :badge-url "https://img.shields.io/badge/substrate-Helix-9b59b6.svg"}})

;; -- :substrate coercion ----------------------------------------------------

(def ^:private valid-substrates (set (keys substrate-registry)))

(defn- coerce-substrate
  "Validate the `:substrate` arg. Accepts only a keyword (one of
  `valid-substrates`) or nil (defaults to `:reagent`). deps-new's
  top-level k/v contract guarantees the value reaches us as a keyword
  — anything else is a registration error and we throw with a clear
  message naming the valid set.

  The contract is keyword-only: string and symbol inputs are rejected
  rather than coerced, matching the SOTA pre-alpha fail-closed posture."
  [raw]
  (let [substrate-kw (cond
                       (nil? raw)     :reagent
                       (keyword? raw) raw
                       :else
                       (throw (ex-info ":rf.error/template-substrate-must-be-keyword"
                                       {:rf.error/id :rf.error/template-substrate-must-be-keyword
                                        :where     'template/coerce-substrate
                                        :recovery  :fix-registration
                                        :reason    (str ":substrate must be a keyword (one of "
                                                        (pr-str valid-substrates)
                                                        "). Got "
                                                        (.getName (class raw))
                                                        ": "
                                                        (pr-str raw))
                                        :substrate raw
                                        :valid     valid-substrates})))]
    (when-not (valid-substrates substrate-kw)
      (throw (ex-info ":rf.error/template-substrate-must-be-one-of"
                      {:rf.error/id :rf.error/template-substrate-must-be-one-of
                       :where     'template/coerce-substrate
                       :recovery  :fix-registration
                       :reason    (str ":substrate must be one of "
                                       (pr-str valid-substrates)
                                       " (got " (pr-str substrate-kw) ")")
                       :substrate substrate-kw
                       :valid     valid-substrates})))
    substrate-kw))

;; -- argument-key gate -------------------------------------------------------
;;
;; deps-new hands `data-fn` a single map that merges its own harness keys
;; (the project-name derivations + run metadata) with whatever top-level
;; k/v args the caller passed. We accept exactly two template-specific
;; flags today (`:substrate`, `:include-story?`); the `:css` / `:include-ssr?`
;; flags are reserved-but-unimplemented (gated on their upstream verification).
;;
;; The substrate posture already fails closed on bad values; this gate
;; extends that strictness to the *key set* so a flag typo
;; (`:include-story`) or a documented-future flag (`:css :tailwind`) can't
;; fail open into a misleading vanilla scaffold.
;;
;; HOW WE DISTINGUISH HARNESS KEYS FROM TEMPLATE KEYS: deps-new's
;; `preprocess-options` (org.corfield.new.impl) populates a fixed, known
;; set of harness keys before `data-fn` runs. We pin the deps-new coord in
;; this template's deps.edn (and the §3 release install), so that set is
;; deterministic — we allowlist it and reject anything outside it. If the
;; pinned deps-new version is bumped and adds a harness key, this list must
;; grow in lockstep (the gate would otherwise false-reject the new key);
;; `arg-key-gate-rejects-unknown-test` exercises a representative harness
;; key to keep that coupling honest.

(def ^:private deps-new-harness-keys
  "The keys deps-new injects into the `data` map before `data-fn` runs
   (deps-new pinned at v0.12.1 in deps.edn): the `preprocess-options`
   project-name derivations + run metadata, the `:template-dir`
   `apply-template-fns` adds just before invoking `data-fn`, plus the
   caller-supplied harness opts that survive `preprocess-options`'
   `dissoc` (`:src-dirs`, `:overwrite`). Verified against the pinned
   coord's source (org.corfield.new.impl)."
  #{:artifact/id :developer :git-dir :group/id :main :name :now/date
    :now/year :overwrite :raw-name :scm/domain :scm/repo :scm/user
    :src-dirs :target-dir :template :template-dir :top :user :version})

(def ^:private template-flag-keys
  "The template-specific flags we accept today."
  #{:substrate :include-story?})

(def ^:private reserved-flag-gates
  "Reserved-but-unimplemented flags → the gating bead that must clear
   before they go live. Passing one today fails closed rather than
   scaffolding a vanilla app that silently lacks the feature."
  {:css          "rf2-gthro (Tailwind v4 verification)"
   :include-ssr? "rf2-0m5ea (SSR validation)"})

(defn- gate-arg-keys!
  "Fail closed on reserved or unknown template arguments.

   - A reserved flag (`:css`, `:include-ssr?`) throws
     `:rf.error/template-unsupported-flag`, naming the flag and its
     gating bead — it is not silently dropped.
   - Any key that is neither a deps-new harness key nor a live
     template flag throws `:rf.error/template-unknown-flag` (catches
     typos like `:include-story` / `:include-stories?`)."
  [data]
  (doseq [[flag gate] reserved-flag-gates]
    (when (contains? data flag)
      (throw (ex-info ":rf.error/template-unsupported-flag"
                      {:rf.error/id :rf.error/template-unsupported-flag
                       :where    'template/gate-arg-keys!
                       :recovery :remove-flag
                       :reason   (str (pr-str flag) " is reserved in the v1 "
                                      "flag set but not yet implemented "
                                      "(gated on " gate "). Remove it; "
                                      "the scaffold can't honour it today.")
                       :flag     flag
                       :gate     gate}))))
  (let [known   (set/union deps-new-harness-keys template-flag-keys)
        unknown (remove known (keys data))]
    (when (seq unknown)
      (throw (ex-info ":rf.error/template-unknown-flag"
                      {:rf.error/id :rf.error/template-unknown-flag
                       :where    'template/gate-arg-keys!
                       :recovery :fix-registration
                       :reason   (str "Unknown template argument(s): "
                                      (pr-str (vec unknown))
                                      ". The accepted template flags are "
                                      (pr-str template-flag-keys)
                                      " (check for a typo, e.g. "
                                      ":include-story -> :include-story?).")
                       :unknown  (vec unknown)
                       :accepted template-flag-keys})))))

;; -- :include-story? coercion ----------------------------------------------

(defn- coerce-include-story?
  "Coerce the `:include-story?` arg to a boolean. The only accepted
   values are literal `true` / `false` / `nil` (nil ⇒ false); anything
   else throws with a clear message. The flag is Reagent-only in v1 —
   caller-level guard checks the substrate."
  [raw]
  (if (contains? #{nil true false} raw)
    (boolean raw)
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
     {{substrate}}    — the chosen substrate name, lower-case
                        (`reagent` / `uix` / `helix`).
     {{substrate-label}} — the chosen substrate's display name, proper-
                        case (`Reagent` / `UIx` / `Helix`); used in the
                        substrate-invariant shadow-cljs.edn header comment
                        and the package.json `description`, both emitted
                        once from `_shared/`.
     {{story-tag}}    — the package.json `description` suffix that varies
                        by `:include-story?`: `\"\"` on the default path,
                        `\", with Story playground\"` under
                        `:include-story? true`. Lets the single
                        `_shared/package.json` source carry both variants
                        (the only per-flag delta was this one parenthetical).
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
  ;; Fail closed on reserved + unknown arguments BEFORE any coercion, so a
  ;; flag typo or a documented-future flag never produces a misleading
  ;; vanilla scaffold.
  (gate-arg-keys! data)
  (let [substrate       (coerce-substrate (:substrate data))
        include-story?  (coerce-include-story? (:include-story? data))]
    ;; Reagent-only guard — hoisted above the name-derivation `let` so the
    ;; data map build below has no side-effecting binding.
    (when (and include-story? (not= substrate :reagent))
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
    (let [{:keys [label badge-url]} (substrate-registry substrate)
          top             (:top data)
          main            (:main data)
          top-file        (->file-path top)
          main-file       (->file-path main)
          top-ns          (->ns-form top)
          main-ns         (->ns-form main)]
      {:substrate           (name substrate)
       :substrate-kw        substrate
       :substrate-label     label
       :include-story?      include-story?
       ;; package.json `description` suffix — the single per-flag delta
       ;; between the default and with-Story descriptions. Emitting it as
       ;; a subst var lets one `_shared/package.json` source serve both
       ;; paths from a single file.
       :story-tag           (if include-story? ", with Story playground" "")
       :namespace           (str top-ns "." main-ns)
       :nested-dirs         (str top-file "/" main-file)
       :substrate-badge-url badge-url
       ;; -- DO NOT EDIT — managed by version_lockstep_test --
       ;; The three version pins below are checked against the repo-root
       ;; sources of truth (the VERSION file, implementation/package.json's
       ;; shadow-cljs / react pins) by
       ;; `test/day8/re_frame2_template/version_lockstep_test.clj`. Bumping
       ;; them here in isolation will fail that test. The §3 release pipeline
       ;; updates all four in lockstep.
       :rf2-version         "0.0.1.alpha"
       :shadow-version      "3.4.10"
       :react-version       "19.2.0"})))

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
;;     │                  or a flag switch (dotfile rename + namespace-
;;     │                   path rename for src/test files; the
;;     │                   substrate-invariant build configs
;;     │                   shadow-cljs.edn + package.json [the latter's
;;     │                   with-Story `description` delta rides the
;;     │                   {{story-tag}} subst var, not a second file];
;;     │                   stories.cljs, which only emits under
;;     │                   :include-story? true)
;;     ├── _reagent/    — Reagent-specific content (core.cljs / views.cljs
;;     │                  / deps.edn); includes the with-story core +
;;     │                  deps variants
;;     ├── _uix/        — UIx-specific content (core.cljs / views.cljs /
;;     │                  deps.edn)
;;     └── _helix/      — Helix-specific content (core.cljs / views.cljs
;;                        / deps.edn)
;;
;; The underscore-prefix convention signals "not bulk-copied — picked
;; up by a transform with :only". Per-substrate sub-trees emit only
;; for the chosen substrate; the only files that genuinely vary by
;; substrate are core.cljs (mount/adapter wiring), views.cljs (the
;; view macros), and deps.edn (the adapter coord + npm libs).

(defn template-fn
  "Build the `:transform` vector and merge it into the template EDN.

   Three transform groups:

     - Shared (from `_shared/`): dotfile rename (e.g. `gitignore` →
       `.gitignore`) + namespace-path rename for src/test source files
       + the substrate-invariant build configs (`shadow-cljs.edn`,
       `package.json`).
     - Per-substrate (from `_<substrate>/`): the files that genuinely
       differ by substrate — the entry-point `core.cljs`, the view
       module `views.cljs`, and `deps.edn` (adapter coord + npm libs).
     - Story scaffolding (Reagent-only, under `:include-story? true`):
       picks `core_with_stories.cljs` instead of `core.cljs`, picks
       `deps_with_story.edn` instead of the default `deps.edn`, and emits
       `stories.cljs` from `_shared/`. (The shared `package.json` source
       is unchanged — its with-Story `description` delta rides the
       `{{story-tag}}` subst var, not a second file.)

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
        ;; The build configs are substrate-invariant — the React
        ;; substrate is chosen in deps.edn + core.cljs, never here — so
        ;; they live in `_shared/` and emit once. package.json's only
        ;; per-flag variation is its `description` parenthetical, carried
        ;; by the `{{story-tag}}` subst var (see data-fn), so a single
        ;; `_shared/package.json` source serves both the default and the
        ;; with-Story path.
        ;; Shared transforms — renames only. `:only` skips the bulk
        ;; copy of `_shared/*`, so source files that don't appear in
        ;; the file-map below DO NOT emit. Add explicit entries if
        ;; you need them.
        shared-files   (cond-> {"gitignore"            ".gitignore"
                                "editorconfig"         ".editorconfig"
                                "cljfmt.edn"           ".cljfmt.edn"
                                "clj-kondo/config.edn" ".clj-kondo/config.edn"
                                ;; Substrate-invariant build configs.
                                "shadow-cljs.edn"      "shadow-cljs.edn"
                                "package.json"         "package.json"
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
                              "deps.edn")]
            [["_reagent" "."
              {deps-src          "deps.edn"
               core-src          (str "src/" nested "/core.cljs")
               "views.cljs"      (str "src/" nested "/views.cljs")}
              :only]])

          "uix"
          [["_uix" "."
            {"deps.edn"        "deps.edn"
             "core.cljs"       (str "src/" nested "/core.cljs")
             "views.cljs"      (str "src/" nested "/views.cljs")}
            :only]]

          "helix"
          [["_helix" "."
            {"deps.edn"        "deps.edn"
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
