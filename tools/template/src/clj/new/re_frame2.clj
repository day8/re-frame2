(ns clj.new.re-frame2
  "day8/clj-template.re-frame2 — clj-new template for new re-frame2 apps
  (rf2-lrtc). The v2 equivalent of v1's day8/re-frame-template.

  Generates a fresh re-frame2 application skeleton with:

    - A substrate choice (Reagent / UIx / Helix) selected via `:substrate`
    - Modern CLJS workflow (shadow-cljs)
    - A counter example wired through `reg-event-db` / `reg-sub` / view
    - deps.edn against the alpha-channel `day8/re-frame2-*` coords

  Invocation (assuming the standard `:project/new` alias in
  ~/.clojure/deps.edn — see clj-new's README). The substrate selector
  rides on `:edn-args` because clj-new's `create` strips unknown
  top-level args:

      ;; Reagent (default)
      clojure -X:project/new :template re-frame2 :name acme/my-app

      ;; UIx
      clojure -X:project/new :template re-frame2 :name acme/my-app \\
              :edn-args '[:substrate :uix]'

      ;; Helix
      clojure -X:project/new :template re-frame2 :name acme/my-app \\
              :edn-args '[:substrate :helix]'

  Local-development invocation (this jar not yet published):

      clojure -Sdeps '{:deps {day8/clj-template.re-frame2
                              {:local/root \"tools/template\"}}}' \\
              -X clj-new/create :template re-frame2 :name acme/my-app

  The generated counter shape mirrors `examples/<substrate>/counter*/core.cljs`
  in the reference impl — the user who runs the template sees the same
  shape they read about in `docs/guide/`."
  (:require [clj.new.templates
             :refer [renderer raw-resourcer project-data project-name name-to-path sanitize-ns ->files]]
            [clojure.string :as string]))

;; -- Substrate selection ----------------------------------------------------
;;
;; Three substrates ship today (rf2-3yij UIx; rf2-2qit Helix; Reagent is
;; the canonical default). Each has its own resource sub-tree under
;; src/clj/new/re_frame2/<substrate>/ for substrate-specific files (the
;; views ns + the deps.edn / shadow-cljs.edn / package.json) and shares
;; the substrate-agnostic shell (events.cljs, subs.cljs, README.md,
;; .gitignore, .editorconfig, .clj-kondo/config.edn, dev/user.clj,
;; dev/scratch.cljs, resources/public/index.html,
;; resources/public/css/app.css) from .../shared/.

(def ^:private valid-substrates #{:reagent :uix :helix})

(defn- coerce-substrate
  "Accept the substrate arg as either a keyword (`:reagent`), a string
  (`reagent` / `:reagent`), or a symbol; return one of #{:reagent :uix
  :helix} or throw with a clear message."
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
                           " (got " (pr-str s) ")")
                      {:substrate s :valid valid-substrates})))
    s))

;; -- Template entry ---------------------------------------------------------
;;
;; clj-new's harness invokes (re-frame2 name & args) where args is a flat
;; alternating-key-value sequence: e.g. (:substrate :uix). We accept the
;; substrate and the include-story? flag today; future toggles
;; (e.g. :include-10x?) would slot in here.
;;
;; The `:include-story?` exception is documented in spec/000-Vision
;; §Non-goals and spec/DESIGN-RATIONALE §No-Story-yet — branching flags
;; are permitted when they enable optional shared scaffolding whose
;; absence forces the user into hand-wiring known idioms. Reagent-only
;; in v1; UIx + Helix variants follow once their adapter coverage
;; matches Reagent's.

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
    (throw (ex-info (str ":include-story? must be true or false (got "
                         (pr-str raw) ")")
                    {:include-story? raw}))))

(defn re-frame2
  "Generate a fresh re-frame2 application skeleton.

  Args:
    name              Group-qualified project name (e.g. `acme/my-app`).
    :substrate        One of :reagent :uix :helix (default :reagent).
    :include-story?   When true, scaffolds the Story playground
                      alongside the live app — adds the
                      `day8/re-frame2-story` coord, emits
                      `stories.cljs`, and swaps `core.cljs` for the
                      hash-routing entry-fn (`#/` → live app,
                      `#/stories` → Story shell). Reagent-only in v1.

  Files emitted match the per-substrate counter example in the reference
  implementation."
  [name & args]
  (let [opts            (apply hash-map args)
        substrate       (coerce-substrate (:substrate opts))
        substrate-name  (clojure.core/name substrate)
        include-story?  (coerce-include-story? (:include-story? opts))
        _               (when (and include-story? (not= substrate :reagent))
                          (throw (ex-info
                                   (str ":include-story? is Reagent-only in v1 "
                                        "(got :substrate " substrate
                                        "). UIx + Helix variants follow once "
                                        "Story's adapter coverage matches "
                                        "Reagent's.")
                                   {:substrate substrate
                                    :include-story? include-story?})))
        ;; clj-new's project-data gives us {:name :namespace :nested-dirs
        ;; :sanitized :group :artifact :year :date ...}.
        data        (merge (project-data name)
                           {:substrate         substrate-name
                            :reagent?          (= substrate :reagent)
                            :uix?              (= substrate :uix)
                            :helix?            (= substrate :helix)
                            ;; Drives Mustache section emission in
                            ;; deps.edn (+ day8/re-frame2-story coord)
                            ;; and package.json (+ `npm run story`
                            ;; script). The file-list below branches
                            ;; on the same flag for `stories.cljs` +
                            ;; the with-stories `core.cljs` variant.
                            :include-story?    include-story?
                            ;; Static-shields badge for the chosen
                            ;; substrate. Shields.io renders the SVG;
                            ;; the colour is matched per-substrate so
                            ;; the README's badge row reads at a
                            ;; glance which adapter is wired.
                            :substrate-badge-url
                            (case substrate
                              :reagent "https://img.shields.io/badge/substrate-Reagent-1abc9c.svg"
                              :uix     "https://img.shields.io/badge/substrate-UIx-3498db.svg"
                              :helix   "https://img.shields.io/badge/substrate-Helix-9b59b6.svg")
                            ;; The runtime coord version of re-frame2 the
                            ;; generated app should depend on. Kept here
                            ;; in one place — bumped on each alpha
                            ;; release.
                            :rf2-version       "0.0.1.alpha"
                            ;; npm pins for the generated app — kept in
                            ;; lockstep with implementation/package.json so
                            ;; the smoke-tested combination is what users
                            ;; get.
                            :shadow-version    "3.4.10"
                            :react-version     "19.2.0"})
        ;; Each renderer is scoped to its sub-tree, so the per-substrate
        ;; views.cljs / deps.edn / shadow-cljs.edn / package.json live
        ;; under .../re-frame2/<substrate>/ and substrate-agnostic files
        ;; under .../re-frame2/shared/.
        ;;
        ;; clj-new's `renderer` builds its lookup path as
        ;; `clj/new/<sanitized-template-name>/<file>`. We want to descend
        ;; further than the one-level template scheme allows, so we use
        ;; the convention of passing the relative resource path
        ;; (`re-frame2/shared/README.md`) and a renderer keyed off the
        ;; top-level template name `re-frame2` — internal slashes are
        ;; preserved.
        render      (renderer "re-frame2")
        raw         (raw-resourcer "re-frame2")
        sub-render  (fn [path] (render path data))
        sub-raw     (fn [path] (raw path))]
    (println "Generating a re-frame2 project called"
             (project-name name)
             "—" substrate-name "substrate"
             (if include-story? "(with Story playground)." "."))
    (->files data
             ;; -- top-level project files --
             ["deps.edn"        (sub-render (str substrate-name "/deps.edn"))]
             ["shadow-cljs.edn" (sub-render (str substrate-name "/shadow-cljs.edn"))]
             ["package.json"    (sub-render (str substrate-name "/package.json"))]
             ["README.md"       (sub-render "shared/README.md")]
             [".gitignore"      (sub-raw    "shared/gitignore")]
             ;; -- dev ergonomics (rf2-r2jqo) --
             ;;
             ;; Dotfile sources live without their leading dot on the
             ;; classpath (clj-new's resource lookup chokes on hidden
             ;; resources); we re-attach the dot on the output side.
             [".editorconfig"   (sub-raw    "shared/editorconfig")]
             [".clj-kondo/config.edn"
              (sub-render "shared/clj-kondo/config.edn")]
             ;; cljfmt config — `clojure -M:cljfmt check` / `fix`.
             [".cljfmt.edn"     (sub-render "shared/cljfmt.edn")]
             ;; lefthook — pre-commit format + lint gate.
             ["lefthook.yml"    (sub-render "shared/lefthook.yml")]
             ;; -- src tree --
             ;;
             ;; `core.cljs` swaps to the hash-routing with-stories variant
             ;; under `:include-story? true`. Only Reagent ships the
             ;; with-stories core today; the caller-level guard above
             ;; rejects `:include-story? true` for non-Reagent substrates.
             ["src/{{nested-dirs}}/core.cljs"
              (sub-render (str substrate-name "/"
                               (if include-story?
                                 "core_with_stories.cljs"
                                 "core.cljs")))]
             ["src/{{nested-dirs}}/events.cljs"
              (sub-render "shared/events.cljs")]
             ["src/{{nested-dirs}}/subs.cljs"
              (sub-render "shared/subs.cljs")]
             ["src/{{nested-dirs}}/views.cljs"
              (sub-render (str substrate-name "/views.cljs"))]
             ;; -- optional: Story scaffolding (rf2-t009p) --
             ;;
             ;; Emitted only under `:include-story? true`. ->files
             ;; treats nil entries as a no-op, so a `(when ...)` here
             ;; gives us a conditional emit without restructuring
             ;; ->files's vector-of-pairs interface.
             (when include-story?
               ["src/{{nested-dirs}}/stories.cljs"
                (sub-render "shared/stories.cljs")])
             ;; -- test tree --
             ;;
             ;; A single events-side test gives the `:test` build entry
             ;; in shadow-cljs.edn (`:target :node-test`, `:ns-regexp
             ;; "-test$"`) a real target. Substrate-agnostic — uses
             ;; the plain-atom adapter so it runs node-side without a
             ;; DOM. See the README "Run tests" section the template
             ;; also emits.
             ["test/{{nested-dirs}}/events_test.cljs"
              (sub-render "shared/events_test.cljs")]
             ;; -- dev tree (Q8 lock) --
             ;;
             ;; `dev/user.clj` — JVM-side `(user/refresh)` entry.
             ;; `dev/scratch.cljs` — REPL scratch ns for firing
             ;; (rf/dispatch …) against the running app. The `:shadow`
             ;; alias in deps.edn puts `dev` on the classpath so both
             ;; files are reachable from `clojure -M:shadow` and
             ;; shadow's nREPL.
             ["dev/user.clj"    (sub-render "shared/dev/user.clj")]
             ["dev/scratch.cljs"
              (sub-render "shared/dev/scratch.cljs")]
             ;; -- host HTML + stylesheet --
             ["resources/public/index.html"
              (sub-render "shared/index.html")]
             ["resources/public/css/app.css"
              (sub-raw    "shared/resources/public/css/app.css")])))
