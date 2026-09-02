(ns day8.re-frame2-template.hooks
  "deps-new hooks for day8/re-frame2-template.

   `template.edn` names three hooks, invoked in this order:

     1. `data-fn`         validates the arguments and derives the
                          substitution values.
     2. `template-fn`     selects the files for the chosen substrate.
     3. `post-process-fn` prints the generated project's next steps.

   The template has ONE selector, `:substrate` (`:reagent` by default, or
   `:uix`), and emits the same twelve-file counter SPA for every value of
   it. Only `deps.edn`, `core.cljs` and `views.cljs` differ per substrate;
   they live under `_<substrate>/` and `template-fn` picks the tree. A new
   substrate is one `substrate-registry` entry, one `_<substrate>/` tree of
   those three files, and one arm in `template-fn`'s `case`.

   deps-new performs flat `{{key}}` substitution, so the one per-substrate
   difference outside those three files — the display name — rides a
   substitution value (`{{substrate-label}}`). Unknown arguments fail
   closed."
  (:require [clojure.set :as set]
            [clojure.string :as string]))

;; -- substrates ---------------------------------------------------------------

(def ^:private substrate-registry
  "Every substrate the template can emit, keyed by the `:substrate` value.
   `:label` is the display name the generated README and package.json
   carry. `valid-substrates` derives from this map, and `template-fn`'s
   `case` names each substrate's `_<substrate>/` tree — a new substrate
   is added in both places."
  {:reagent {:label "Reagent"}
   :uix     {:label "UIx"}})

(def ^:private valid-substrates (set (keys substrate-registry)))

(def ^:private default-substrate :reagent)

(defn- coerce-substrate
  "Validate `:substrate`. nil selects the default; anything else must be a
   keyword in `valid-substrates`. deps-new's top-level k/v contract
   delivers keywords, so a string or symbol is a registration error and is
   rejected rather than coerced."
  [raw]
  (let [substrate (cond
                    (nil? raw)     default-substrate
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
                                                     ": " (pr-str raw))
                                     :substrate raw
                                     :valid     valid-substrates})))]
    (when-not (valid-substrates substrate)
      (throw (ex-info ":rf.error/template-substrate-must-be-one-of"
                      {:rf.error/id :rf.error/template-substrate-must-be-one-of
                       :where     'template/coerce-substrate
                       :recovery  :fix-registration
                       :reason    (str ":substrate must be one of "
                                       (pr-str valid-substrates)
                                       " (got " (pr-str substrate) ")")
                       :substrate substrate
                       :valid     valid-substrates})))
    substrate))

;; -- argument gate ------------------------------------------------------------

(def ^:private deps-new-harness-keys
  "The keys deps-new injects into the `data` map before `data-fn` runs
   (deps-new pinned at v0.12.1 in deps.edn): the `preprocess-options`
   project-name derivations + run metadata, the `:template-dir`
   `apply-template-fns` adds just before invoking `data-fn`, plus the
   caller-supplied harness opts that survive `preprocess-options`'
   `dissoc` (`:src-dirs`, `:overwrite`). Keep this aligned with the pinned
   deps-new version; otherwise a newly introduced harness key is rejected
   as an unknown template argument."
  #{:artifact/id :developer :git-dir :group/id :main :name :now/date
    :now/year :overwrite :raw-name :scm/domain :scm/repo :scm/user
    :src-dirs :target-dir :template :template-dir :top :user :version})

(def ^:private template-keys
  "The template's own arguments. `:substrate` is the one and only
   selector; every retired feature flag is simply unknown here."
  #{:substrate})

(defn- gate-arg-keys!
  "Fail closed on any argument that is neither a deps-new harness key nor a
   template key. A typo, or a flag this template no longer accepts, throws
   `:rf.error/template-unknown-flag` before any file is written."
  [data]
  (let [known   (set/union deps-new-harness-keys template-keys)
        unknown (remove known (keys data))]
    (when (seq unknown)
      (throw (ex-info ":rf.error/template-unknown-flag"
                      {:rf.error/id :rf.error/template-unknown-flag
                       :where    'template/gate-arg-keys!
                       :recovery :fix-registration
                       :reason   (str "Unknown template argument(s): "
                                      (pr-str (vec unknown))
                                      ". The template accepts "
                                      (pr-str template-keys) " only.")
                       :unknown  (vec unknown)
                       :accepted template-keys})))))

;; -- name derivations ---------------------------------------------------------
;;
;; deps-new's `preprocess-options` supplies `:top` / `:main` but computes
;; their `/ns` and `/file` forms only later, in `->subst-map` — after
;; `template-fn` has already built its rename targets. So `data-fn` derives
;; them here, with `->subst-map`'s own rules.

(defn- ->file-path
  "dots → slashes, dashes → underscores."
  [s]
  (-> s str (string/replace "." "/") (string/replace "-" "_")))

(defn- ->ns-form
  "slashes → dots, underscores → dashes."
  [s]
  (-> s str (string/replace "/" ".") (string/replace "_" "-")))

(def ^:private npm-name-max-length 214)

(defn- npm-name-valid?
  "npm's rules for a new unscoped package name: lowercase, URL-safe
   (`a-z 0-9 - _ . ~`), no leading `.` or `_`, at most 214 characters."
  [s]
  (boolean
    (and (string? s)
         (<= 1 (count s) npm-name-max-length)
         (re-matches #"[a-z0-9~-][a-z0-9._~-]*" s))))

(defn- ->npm-name
  "The emitted package.json `name`: deps-new's `:main` (the artefact
   segment of `:name` — `acme/my-app` and a bare `my-app` both give
   `my-app`), lowercased. Unscoped: a scope asserts an npm org the user may
   not own, and the package is private anyway. Validated because the
   qualified Clojure name copied verbatim is exactly what npm rejects."
  [main]
  (let [candidate (string/lower-case (str main))]
    (when-not (npm-name-valid? candidate)
      (throw (ex-info ":rf.error/template-npm-name-invalid"
                      {:rf.error/id :rf.error/template-npm-name-invalid
                       :where    'template/->npm-name
                       :recovery :fix-registration
                       :reason   (str "The artefact segment of :name, "
                                      (pr-str (str main)) ", is not a valid "
                                      "npm package name once lowercased ("
                                      (pr-str candidate) "): use a-z, 0-9, "
                                      "`-`, `_`, `.` or `~`, not starting "
                                      "with `.` or `_`, at most "
                                      npm-name-max-length " characters.")
                       :main     (str main)
                       :npm-name candidate})))
    candidate))

;; -- data-fn ------------------------------------------------------------------

(defn data-fn
  "Validate the template arguments and derive the substitution values.

   deps-new supplies `:name`, `:top` and `:main`. This hook adds the
   namespace / path forms, the npm package name, the substrate's display
   label and the dependency pins. The substrate keyword stays on the map
   for `template-fn`."
  [data]
  ;; Validate the key set before touching any value, so a typo cannot
  ;; silently emit the default scaffold.
  (gate-arg-keys! data)
  (let [substrate       (coerce-substrate (:substrate data))
        {:keys [label]} (substrate-registry substrate)
        top             (:top data)
        main            (:main data)]
    {:substrate       substrate
     :substrate-label label
     :namespace       (str (->ns-form top) "." (->ns-form main))
     :nested-dirs     (str (->file-path top) "/" (->file-path main))
     :npm-name        (->npm-name main)
     ;; Checked against the repository's sources of truth (VERSION,
     ;; implementation/package.json) by version_lockstep_test.clj; bump
     ;; them together.
     :rf2-version     "0.0.1.alpha"
     :shadow-version  "3.4.10"
     :react-version   "19.2.0"}))

;; -- template-fn --------------------------------------------------------------
;;
;; deps-new's file-emission contract:
;;
;;   1. Copy `root/` into the project root.
;;   2. Apply each `[src-dir target-dir file-map :only]` transform.
;;
;; Underscore-prefixed directories are never bulk-copied; their explicit
;; file maps are the whole emit.

(defn template-fn
  "Attach the file transforms: the substrate-agnostic `_shared/` files,
   renamed into place, then the chosen substrate's `deps.edn`, `core.cljs`
   and `views.cljs`."
  [edn data]
  (let [nested          (:nested-dirs data)
        src             (fn [f] (str "src/" nested "/" f))
        shared          [["_shared" "."
                          {"gitignore"        ".gitignore"
                           "shadow-cljs.edn"  "shadow-cljs.edn"
                           "package.json"     "package.json"
                           "events.cljs"      (src "events.cljs")
                           "subs.cljs"        (src "subs.cljs")
                           "events_test.cljs" (str "test/" nested "/events_test.cljs")}
                          :only]]
        substrate-files {"deps.edn"   "deps.edn"
                         "core.cljs"  (src "core.cljs")
                         "views.cljs" (src "views.cljs")}
        ;; One arm per substrate, naming its resource tree.
        per-substrate   (case (:substrate data)
                          :reagent [["_reagent" "." substrate-files :only]]
                          :uix     [["_uix"     "." substrate-files :only]])]
    (assoc edn :transform (into shared per-substrate))))

;; -- post-process-fn ----------------------------------------------------------

(defn post-process-fn
  "After emission, say what landed and how to run it."
  [_edn data]
  (println (str "Generated a re-frame2 application " (:name data)
                " (" (:substrate-label data) ")."))
  (println "Next steps:")
  ;; `:target-dir` is preprocess-options' computed output dir (defaults to
  ;; `(:main data)` when no `:target-dir` arg is given).
  (println (str "  cd " (:target-dir data)))
  (println "  npm install")
  (println "  npx shadow-cljs watch app")
  (println "Then open http://localhost:8280")
  (println (str "Until day8/re-frame2 is published, point its coordinates in "
                "deps.edn at a checkout with :local/root before the first watch."))
  nil)
