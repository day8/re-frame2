(ns day8.re-frame2-template.hooks
  "deps-new hooks for day8/re-frame2-template.

   `template.edn` declares three hooks, invoked in this order:

     1. `data-fn` validates arguments and derives substitution values.
     2. `template-fn` selects the files for the requested variant.
     3. `post-process-fn` prints the generated project's next steps.

   The supported matrix is Reagent and UIx, plus the EXPERIMENTAL
   `:ui` variant (the first-party re-frame.ui compiled-view substrate —
   2026-07-19 template-menu ruling); Story and SSR are mutually exclusive
   Reagent-only options; Tailwind is available on every substrate. Unknown
   keys and unsupported combinations fail closed.

   deps-new performs flat `{{key}}` substitution, not conditional template
   syntax. Variants with structural differences therefore use separate
   source files; small `package.json` differences use the `{{story-tag}}`
   and `{{test-script}}` substitution values."
  (:require [clojure.set :as set]
            [clojure.string :as string]))

;; -- substrate registry -----------------------------------------------------
;;
;; Labels and badges share the same key set used for substrate validation.

(def ^:private substrate-registry
  {:reagent {:label "Reagent"
             :badge-url "https://img.shields.io/badge/substrate-Reagent-1abc9c.svg"}
   :uix     {:label "UIx"
             :badge-url "https://img.shields.io/badge/substrate-UIx-3498db.svg"}
   ;; EXPERIMENTAL — the first-party re-frame.ui compiled-view substrate
   ;; (2026-07-19 template-menu ruling). Emits the minimal consumer-shaped
   ;; app from docs/core/how-to/install-re-frame-ui.md: `defview` views,
   ;; `ui/mount`, and the ONE load-bearing build-hook setting. The
   ;; adapters remain the supported default; this variant's surface may
   ;; change between alpha releases.
   :ui      {:label "re-frame.ui"
             :badge-url "https://img.shields.io/badge/substrate-re--frame.ui%20(EXPERIMENTAL)-e67e22.svg"}})

;; -- :substrate coercion ----------------------------------------------------

(def ^:private valid-substrates (set (keys substrate-registry)))

(defn- coerce-substrate
  "Validate the `:substrate` arg. Accepts only a keyword (one of
  `valid-substrates`) or nil (defaults to `:reagent`). deps-new's
  top-level k/v contract guarantees the value reaches us as a keyword
  — anything else is a registration error and we throw with a clear
  message naming the valid set.

  The contract is keyword-only: string and symbol inputs are rejected
  rather than coerced, preserving the fail-closed argument contract."
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
;; deps-new merges caller arguments with keys produced by
;; `preprocess-options`. Keep this allowlist aligned with the pinned deps-new
;; version; otherwise a newly introduced harness key will be rejected as an
;; unknown template flag.

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
  #{:substrate :include-story? :include-ssr? :css})

(def ^:private reserved-flag-gates
  "Reserved-but-unimplemented flags and the condition that enables them.
   Passing one fails closed rather than silently emitting the default app."
  {})

(defn- gate-arg-keys!
  "Fail closed on reserved or unknown template arguments.

   - A reserved flag (see `reserved-flag-gates`; empty today) throws
     `:rf.error/template-unsupported-flag`, naming the flag and its
     gate — it is not silently dropped.
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
   else throws with a clear message. The flag is Reagent-only —
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

;; -- :include-ssr? coercion ------------------------------------------------

(defn- coerce-include-ssr?
  "Coerce the `:include-ssr?` arg to a boolean. The only accepted values
   are literal `true` / `false` / `nil` (nil ⇒ false); anything else
   throws with a clear message. The flag is Reagent-only and
   mutually exclusive with `:include-story?` — caller-level guards check
   both (see data-fn)."
  [raw]
  (if (contains? #{nil true false} raw)
    (boolean raw)
    (throw (ex-info ":rf.error/template-bad-include-ssr-flag"
                    {:rf.error/id :rf.error/template-bad-include-ssr-flag
                     :where     'template/coerce-include-ssr?
                     :recovery  :fix-registration
                     :reason    (str ":include-ssr? must be true or false (got "
                                     (pr-str raw) ")")
                     :include-ssr? raw}))))

;; -- :css coercion ---------------------------------------------------------

(def ^:private valid-css
  "Accepted `:css` values. `nil` ⇒ the plain-CSS default; `:tailwind`
   swaps in the Tailwind v4 scaffold."
  #{:tailwind})

(defn- coerce-css
  "Coerce the `:css` arg to a keyword or nil. `nil` selects the default
   plain-CSS scaffold; `:tailwind` selects the Tailwind v4 variant.
   Anything else (a string, a bogus keyword like `:tailwnid`) fails
   closed with a clear message naming the valid set — matching the
   fail-closed posture the substrate + flag coercions already carry."
  [raw]
  (cond
    (nil? raw)          nil
    (valid-css raw)     raw
    :else
    (throw (ex-info ":rf.error/template-bad-css-flag"
                    {:rf.error/id :rf.error/template-bad-css-flag
                     :where    'template/coerce-css
                     :recovery :fix-registration
                     :reason   (str ":css must be one of "
                                    (pr-str valid-css)
                                    " (or omitted for the plain-CSS "
                                    "default). Got "
                                    (pr-str raw) ".")
                     :css      raw
                     :valid    valid-css}))))

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
  "Validate template arguments and derive values used during substitution.

   deps-new supplies `:name`, `:top`, and `:main`. This hook adds the
   namespace/file forms, selected substrate metadata, package description
   and test-script variants, and dependency version pins. Keyword copies of
   selector values are retained for `template-fn`, because deps-new coerces
   substitution values to strings."
  [data]
  ;; Validate the key set before coercing values so a typo cannot silently
  ;; emit the default scaffold.
  (gate-arg-keys! data)
  (let [substrate       (coerce-substrate (:substrate data))
        include-story?  (coerce-include-story? (:include-story? data))
        include-ssr?    (coerce-include-ssr? (:include-ssr? data))
        css             (coerce-css (:css data))]
    ;; Story and SSR have separate complete source variants; there is no
    ;; combined variant.
    (when (and include-story? include-ssr?)
      (throw (ex-info
               ":rf.error/ssr-and-story-mutually-exclusive"
               {:rf.error/id :rf.error/ssr-and-story-mutually-exclusive
                :where     'template/data-fn
                :recovery  :fix-registration
                :reason    (str ":include-story? and :include-ssr? are "
                                "mutually exclusive in v1 — pass at most "
                                "one. Combining them requires a per-cell "
                                "template test the v1 surface does not "
                                "carry (004-SSR-Validation-Report §2.1).")
                :include-story? include-story?
                :include-ssr?   include-ssr?})))

    ;; Story and SSR currently have Reagent implementations only.
    (when (and include-story? (not= substrate :reagent))
      (throw (ex-info
               ":rf.error/template-include-story-reagent-only"
               {:rf.error/id :rf.error/template-include-story-reagent-only
                :where     'template/data-fn
                :recovery  :fix-registration
                :reason    (str ":include-story? is Reagent-only in v1 "
                                "(got :substrate " substrate
                                "). UIx variants follow once "
                                "Story's adapter coverage matches "
                                "Reagent's.")
                :substrate substrate
                :include-story? include-story?})))

    (when (and include-ssr? (not= substrate :reagent))
      (throw (ex-info
               ":rf.error/template-include-ssr-reagent-only"
               {:rf.error/id :rf.error/template-include-ssr-reagent-only
                :where     'template/data-fn
                :recovery  :fix-registration
                :reason    (str ":include-ssr? is Reagent-only in v1 "
                                "(got :substrate " substrate
                                "). UIx SSR variants follow once "
                                "the per-substrate adapters demonstrate "
                                "parity.")
                :substrate substrate
                :include-ssr? include-ssr?})))
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
       :include-ssr?        include-ssr?
       ;; Keep selectors as keywords for `template-fn`; substitution values
       ;; are stringified by deps-new.
       :css-kw              css
       ;; String form for flat `{{css-name}}` substitution — "tailwind" or
       ;; "" (plain). The SSR `server.clj` reads it to decide whether the live
       ;; shell also loads the Tailwind dev compiler.
       :css-name            (if css (name css) "")
       :story-tag           (if include-story? ", with Story playground" "")
       ;; Xray rides the dev build of every adapter substrate (the
       ;; :devtools/preloads entry in the shared shadow-cljs.edn), and its
       ;; machine canvas compiles against two npm packages (@xyflow/react +
       ;; elkjs, required by day8/re-frame2-machines-viz). shadow-cljs
       ;; resolves JS deps from the project-local node_modules at compile
       ;; time, so the emitted package.json must carry both — omit them and
       ;; the scaffold's first `shadow-cljs watch app` fails with a missing
       ;; JS dependency (found by the rf2-b16va G1-G4 external-consumer
       ;; validation; the in-repo emitted-test tier masks it by junctioning
       ;; implementation/node_modules). The EXPERIMENTAL :ui variant ships
       ;; no Xray coord (minimal consumer shape) and gets none of this.
       ;; Pins ride lockstep with implementation/package.json
       ;; (version_lockstep_test.clj).
       :xray-npm-deps       (if (= substrate :ui)
                              ""
                              (str ",\n    \"@xyflow/react\": \"12.4.2\","
                                   "\n    \"elkjs\": \"^0.11.1\""))
       ;; SSR emits a JVM test instead of the shared CLJS test.
       :test-script         (if include-ssr?
                              "clojure -M:test"
                              "shadow-cljs compile test && node out/node-test.js")
       :namespace           (str top-ns "." main-ns)
       :nested-dirs         (str top-file "/" main-file)
       :substrate-badge-url badge-url
       ;; Checked against the repository sources of truth by
       ;; version_lockstep_test.clj; update the pins together.
       :rf2-version         "0.0.1.alpha"
       :shadow-version      "3.4.10"
       :react-version       "19.2.0"})))

;; -- template-fn ------------------------------------------------------------
;;
;; deps-new's file-emission contract:
;;
;;   1. Copy `root/` into the project root.
;;   2. Apply each `[src-dir target-dir file-map :only]` transform.
;;
;; Underscore-prefixed directories are never bulk-copied. Their explicit
;; file maps select shared, substrate-specific, and optional sources.

(defn template-fn
  "Attach the explicit file transforms for the selected project variant.

   Shared files provide build config, dotfiles, and the default CLJS slices.
   A substrate transform adds its adapter entry point, views, and deps. Story
   replaces the Reagent entry point and deps and adds `stories.cljs`; SSR
   replaces the CLJS slices with `core.cljc`, `server.clj`, a JVM test, and an
   SSR-specific README. The optional Tailwind transform runs last so it can
   replace the default stylesheet and HTML."
  [edn data]
  (let [nested         (:nested-dirs data)
        substrate      (:substrate data)
        include-story? (:include-story? data)
        include-ssr?   (:include-ssr? data)
        css            (:css-kw data)
        ;; Every shared source must be named here because `:only` disables
        ;; implicit copying.
        shared-common  {"gitignore"            ".gitignore"
                        "editorconfig"         ".editorconfig"
                        "cljfmt.edn"           ".cljfmt.edn"
                        "clj-kondo/config.edn" ".clj-kondo/config.edn"
                        ;; Substrate-invariant across the adapter substrates.
                        "package.json"         "package.json"}
        shared-files   (cond-> shared-common
                         ;; The adapter substrates share one build config
                         ;; (Xray preload included). The EXPERIMENTAL :ui
                         ;; variant emits its OWN shadow-cljs.edn instead —
                         ;; the one-setting install contract of
                         ;; docs/core/how-to/install-re-frame-ui.md, with
                         ;; no Xray preload (the minimal consumer shape
                         ;; carries no Xray coord).
                         (not= substrate "ui")
                         (assoc "shadow-cljs.edn" "shadow-cljs.edn")
                         ;; SSR folds these slices into core.cljc.
                         (not include-ssr?)
                         (assoc "events.cljs"      (str "src/" nested "/events.cljs")
                                "subs.cljs"        (str "src/" nested "/subs.cljs")
                                "schema.cljs"      (str "src/" nested "/schema.cljs")
                                "events_test.cljs" (str "test/" nested "/events_test.cljs"))
                         include-story?
                         (assoc "stories.cljs"
                                (str "src/" nested "/stories.cljs"))
                         ;; Transforms run after the root copy, so this README
                         ;; replaces the SPA README.
                         include-ssr?
                         (assoc "ssr_test.clj"
                                (str "test/" nested "/ssr_test.clj")
                                "README_with_ssr.md" "README.md"))
        shared         [["_shared" "." shared-files :only]]

        ;; Any new substrate source must be opted into its map.
        per-substrate
        (case substrate
          "reagent"
          (cond
            ;; SSR's core.cljc contains both platform entry points and all
            ;; shared registrations.
            include-ssr?
            [["_reagent" "."
              {"deps_with_ssr.edn"    "deps.edn"
               "core_with_ssr.cljc"   (str "src/" nested "/core.cljc")
               "server.clj"           (str "src/" nested "/server.clj")}
              :only]]

            :else
            (let [core-src (if include-story?
                             "core_with_stories.cljs"
                             "core.cljs")
                  deps-src (if include-story?
                             "deps_with_story.edn"
                             "deps.edn")]
              [["_reagent" "."
                {deps-src     "deps.edn"
                 core-src     (str "src/" nested "/core.cljs")
                 "views.cljs" (str "src/" nested "/views.cljs")}
                :only]]))

          "uix"
          [["_uix" "."
            {"deps.edn"        "deps.edn"
             "core.cljs"       (str "src/" nested "/core.cljs")
             "views.cljs"      (str "src/" nested "/views.cljs")}
            :only]]

          ;; EXPERIMENTAL (2026-07-19 template-menu ruling). The
          ;; re-frame.ui compiled-view scaffold: its own deps.edn (core +
          ;; ui + schemas; NO Xray coord), its own shadow-cljs.edn (the
          ;; post-S6 one-setting build-hook contract, no Xray preload),
          ;; and an EXPERIMENTAL-marked README that replaces the SPA
          ;; README the root copy laid down (transforms run after the
          ;; root copy — same mechanism as README_with_ssr.md).
          "ui"
          [["_ui" "."
            {"deps.edn"        "deps.edn"
             "shadow-cljs.edn" "shadow-cljs.edn"
             "README.md"       "README.md"
             "core.cljs"       (str "src/" nested "/core.cljs")
             "views.cljs"      (str "src/" nested "/views.cljs")}
            :only]])

        ;; This transform runs after the root copy and replaces the plain-CSS
        ;; files on every substrate.
        css-overlay
        (when (= css :tailwind)
          [["_css_tailwind" "."
            {"app.css"    "resources/public/css/app.css"
             "index.html" "resources/public/index.html"}
            :only]])]
    (assoc edn :transform (into [] (concat shared per-substrate css-overlay)))))

;; -- post-process-fn --------------------------------------------------------

(defn post-process-fn
  "After file emission, log what landed and where. No fix-ups required
   today — `template-fn`'s file-map handles dotfile renames inline."
  [_edn data]
  (let [substrate      (:substrate data)
        include-story? (:include-story? data)
        include-ssr?   (:include-ssr? data)
        css            (:css-kw data)
        feature-tag    (cond
                         include-story? " (with Story playground)"
                         include-ssr?   " (with SSR)"
                         :else          "")
        css-tag        (if (= css :tailwind) " (Tailwind CSS)" "")]
    (println (str "Generated a re-frame2 application " (:name data)
                  " (" substrate " substrate" feature-tag css-tag ")."))
    (when (= "ui" substrate)
      (println (str "NOTE: the re-frame.ui substrate is EXPERIMENTAL - its "
                    "surface may change between alpha releases. The Reagent "
                    "and UIx adapters are the supported defaults.")))
    (println "Next steps:")
    ;; `:target-dir` is preprocess-options' computed output dir
    ;; (defaults to `(:main data)` when no `:target-dir` arg is given).
    (println (str "  cd " (:target-dir data)))
    (println "  npm install")
    (println "  npx shadow-cljs watch app")
    (if include-ssr?
      (do
        ;; The SSR scaffold runs a JVM render server in front of the client
        ;; bundle. Build the bundle with `watch app` (above), then boot the
        ;; server in a second terminal.
        (println "  clojure -X:server   # in a second terminal — the SSR/Jetty host")
        (println "Then open http://127.0.0.1:8030"))
      (println "Then open http://localhost:8280"))
    nil))
