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
;; .gitignore, resources/public/index.html) from .../shared/.

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
;; alternating-key-value sequence: e.g. (:substrate :uix). We accept just
;; the substrate today; future toggles (e.g. include-story?, include-10x?)
;; would slot in here.

(defn re-frame2
  "Generate a fresh re-frame2 application skeleton.

  Args:
    name        Group-qualified project name (e.g. `acme/my-app`).
    :substrate  One of :reagent :uix :helix (default :reagent).

  Files emitted match the per-substrate counter example in the reference
  implementation."
  [name & args]
  (let [opts        (apply hash-map args)
        substrate   (coerce-substrate (:substrate opts))
        substrate-name (clojure.core/name substrate)
        ;; clj-new's project-data gives us {:name :namespace :nested-dirs
        ;; :sanitized :group :artifact :year :date ...}.
        data        (merge (project-data name)
                           {:substrate         substrate-name
                            :reagent?          (= substrate :reagent)
                            :uix?              (= substrate :uix)
                            :helix?            (= substrate :helix)
                            ;; The runtime coord version of re-frame2 the
                            ;; generated app should depend on. Kept here
                            ;; in one place — bumped on each alpha
                            ;; release.
                            :rf2-version       "0.0.1.alpha"
                            ;; npm pins for the generated app — kept in
                            ;; lockstep with implementation/package.json so
                            ;; the smoke-tested combination is what users
                            ;; get.
                            :shadow-version    "2.28.20"
                            :react-version     "18.3.1"})
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
             "—" substrate-name "substrate.")
    (->files data
             ;; -- top-level project files --
             ["deps.edn"        (sub-render (str substrate-name "/deps.edn"))]
             ["shadow-cljs.edn" (sub-render (str substrate-name "/shadow-cljs.edn"))]
             ["package.json"    (sub-render (str substrate-name "/package.json"))]
             ["README.md"       (sub-render "shared/README.md")]
             [".gitignore"      (sub-raw    "shared/gitignore")]
             ;; -- src tree --
             ["src/{{nested-dirs}}/core.cljs"
              (sub-render (str substrate-name "/core.cljs"))]
             ["src/{{nested-dirs}}/events.cljs"
              (sub-render "shared/events.cljs")]
             ["src/{{nested-dirs}}/subs.cljs"
              (sub-render "shared/subs.cljs")]
             ["src/{{nested-dirs}}/views.cljs"
              (sub-render (str substrate-name "/views.cljs"))]
             ;; -- host HTML --
             ["resources/public/index.html"
              (sub-render "shared/index.html")])))
