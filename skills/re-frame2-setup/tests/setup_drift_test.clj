;;;; tests/setup_drift_test.clj — structural regression for the
;;;; re-frame2-setup skill's correctness-critical contract claims.
;;;;
;;;; Guards the contract claims the setup skill must keep correct:
;;;;
;;;;   1. The default scaffold IS the generator template's emission. The
;;;;      twelve files in `references/first-counter.md` and the three UIx
;;;;      files in `references/entry-namespace.md` are generated regions
;;;;      rendered from `tools/template/` by `tests/first_counter_derivation.clj`;
;;;;      this suite loads that renderer and asserts the leaves equal it, so a
;;;;      template change (or a hand edit inside a region) fails here until
;;;;      the leaves are regenerated. The JVM tier repeats the comparison
;;;;      against a real deps-new emission.
;;;;
;;;;   2. The day-one set is the reduced one. No schemas, no Xray coord, no
;;;;      devtools preload, no Xray host column, no `@xyflow/react` / `elkjs`,
;;;;      no CSP on the default route — each is a later, explicit step. The
;;;;      locks that once pinned those pieces as day-one now pin their absence.
;;;;
;;;;   3. Lockstep is a BUILD/dependency discipline, not a boot-time runtime
;;;;      check; the UIx pins match the template; the coordinate guidance
;;;;      branches on publication state; the pin default is zero-interview and
;;;;      the skill executes the scaffold.
;;;;
;;;; This is the CHEAP class of drift the setup skill can suffer: a prose
;;;; promise of a runtime invariant that doesn't exist, a file body that
;;;; drifted from the template it claims to be, or a day-one piece leaking
;;;; back onto the default route.
;;;;
;;;; Run locally:  bb tests/setup_drift_test.clj   (from skills/re-frame2-setup/)
;;;; Exit:         0 = pass, non-zero = fail.
;;;;
;;;; CI: gated by the `skills-structural` job in .github/workflows/test.yml,
;;;; which loops `skills/re-frame2-setup/tests/*_test.clj`. The job fires when
;;;; `report-changed-surfaces.sh` classifies a `skills/re-frame2-setup/**`
;;;; change as `skills_structural=true`. So the locks below are guarded in CI,
;;;; not just locally.
;;;;
;;;; What this suite does NOT cover: it does not materialise the scaffold and
;;;; run `npm install` + `npx shadow-cljs compile app`. That is the black-box
;;;; fixture `setup-skill-default-scaffold-mounts-test` in
;;;; tools/template/test/day8/re_frame2_template/emitted_test_run_test.clj
;;;; (behind RF2_TEMPLATE_RUN_EMITTED_TESTS=1), which compiles the shipped
;;;; leaf, boots it in Chromium and clicks the counter 0 -> 1.
;;;;
;;;; NOT published — `package.json` :files excludes `tests/`.

(ns setup-drift-test
  (:require [clojure.edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

;; ---------------------------------------------------------------------------
;; Filesystem helpers
;; ---------------------------------------------------------------------------

(def ^:private setup-root
  (-> *file*
      (io/file)
      (.getAbsoluteFile)
      (.getParentFile)    ;; tests/
      (.getParentFile)))  ;; skills/re-frame2-setup/

(def ^:private repo-root
  (-> setup-root
      (.getParentFile)    ;; skills/
      (.getParentFile)))  ;; repo root

(defn- slurp-rel [parent rel]
  (slurp (io/file parent rel)))

(def ^:private deps-versions-md
  (delay (slurp-rel setup-root "references/deps-versions.md")))

(def ^:private entry-namespace-md
  (delay (slurp-rel setup-root "references/entry-namespace.md")))

(def ^:private shadow-cljs-md
  (delay (slurp-rel setup-root "references/shadow-cljs.md")))

(def ^:private skill-md
  (delay (slurp-rel setup-root "SKILL.md")))

(def ^:private readme-md
  (delay (slurp-rel setup-root "README.md")))

(def ^:private first-counter-md
  (delay (slurp-rel setup-root "references/first-counter.md")))

(def ^:private template-resources
  "tools/template/resources/day8/re_frame2_template")

(def ^:private reagent-template-core
  (delay (slurp-rel repo-root (str template-resources "/_reagent/core.cljs"))))

(def ^:private reagent-template-deps
  (delay (slurp-rel repo-root (str template-resources "/_reagent/deps.edn"))))

(def ^:private uix-template-deps
  (delay (slurp-rel repo-root (str template-resources "/_uix/deps.edn"))))

(def ^:private template-index-html
  (delay (slurp-rel repo-root (str template-resources "/root/resources/public/index.html"))))

(defn- contains-any? [text alts]
  (some #(str/includes? text %) alts))

(defn- mvn-version
  "Extract the :mvn/version string pinned for `coord` in a deps.edn body,
   or nil if the coord isn't found. Whitespace-tolerant, so an aligned
   deps.edn column reads the same as a single space."
  [deps-body coord]
  (some-> (re-find (re-pattern (str (java.util.regex.Pattern/quote coord)
                                    "\\s*\\{:mvn/version\\s+\"([^\"]+)\""))
                   deps-body)
          second))

;; ---------------------------------------------------------------------------
;; The derivation — the leaves' generated regions, and their render
;; ---------------------------------------------------------------------------
;;
;; `first_counter_derivation.clj` is the renderer that WRITES the two
;; generated regions. Loading it here (not running it — its script guard
;; keys off babashka.file) gives this suite the same render to compare
;; against, plus the block extractor the JVM tier shares in spirit.

(load-file (.getPath (io/file setup-root "tests/first_counter_derivation.clj")))

(def ^:private first-counter-files
  "path → body, as shipped in first-counter.md's generated region."
  (delay (first-counter-derivation/extract-files @first-counter-md)))

(def ^:private uix-files
  "path → body, as shipped in entry-namespace.md's generated region."
  (delay (first-counter-derivation/extract-files @entry-namespace-md)))

(def ^:private rendered-reagent
  (delay (first-counter-derivation/reagent-files)))

(def ^:private rendered-uix
  (delay (first-counter-derivation/uix-swap-files)))

(def ^:private regenerate-hint
  "Regenerate with `bb tests/first_counter_derivation.clj` from skills/re-frame2-setup/ — the file bodies inside the generated regions are the template's emission, never hand-edited.")

(defn- assert-region-matches-render! [label files rendered]
  (is (seq rendered)
      (str label ": the renderer produced no files — first_counter_derivation.clj "
           "could not read tools/template; the comparison below would be vacuous."))
  (is (= (set (keys rendered)) (set (keys files)))
      (str label ": the generated region's file set differs from the template's emission. "
           "Missing " (pr-str (sort (remove (set (keys files)) (keys rendered))))
           ", extra " (pr-str (sort (remove (set (keys rendered)) (keys files))))
           ". " regenerate-hint))
  (doseq [[path body] rendered
          :when (contains? files path)]
    (is (= body (get files path))
        (str label ": `" path "` in the generated region differs from what the "
             "template emits. " regenerate-hint))))

;; ---------------------------------------------------------------------------
;; Lock 0 — the leaves are the template's emission (rf2-rc0yh slice B)
;; ---------------------------------------------------------------------------

(deftest first-counter-region-is-the-template-render
  (testing "first-counter.md's twelve files equal the template's Reagent emission for acme/my-app"
    (assert-region-matches-render! "first-counter.md" @first-counter-files @rendered-reagent)
    (is (= 12 (count @first-counter-files))
        (str "first-counter.md must carry exactly the twelve-file manifest the template "
             "emits; found " (count @first-counter-files) ". " regenerate-hint))))

(deftest uix-region-is-the-template-render
  (testing "entry-namespace.md's UIx region equals the template's three per-substrate files"
    (assert-region-matches-render! "entry-namespace.md §UIx greenfield" @uix-files @rendered-uix)))

(deftest generated-regions-carry-no-placeholders
  (testing "neither generated region leaks a template placeholder or an unresolved pin"
    (doseq [[label files] [["first-counter.md" @first-counter-files]
                           ["entry-namespace.md" @uix-files]]
            [path body] files]
      (is (not (contains-any? body ["{{" "<VERSION>" "<SHA>" "PLACEHOLDER"]))
          (str label ": `" path "` carries a placeholder. The default route writes no "
               "<VERSION>/<SHA> and no unsubstituted {{key}} — the pins are the "
               "template's literals, rendered by derivation (rf2-rc0yh)."))))
  (testing "the day-one framework coords carry a literal :mvn/version (the template's reviewed pin)"
    (let [deps (get @first-counter-files "deps.edn" "")]
      (is (some? (mvn-version deps "day8/re-frame2"))
          "first-counter.md's deps.edn no longer pins day8/re-frame2 with a literal :mvn/version.")
      (is (= (mvn-version deps "day8/re-frame2") (mvn-version deps "day8/re-frame2-reagent"))
          "first-counter.md's deps.edn pins core and the adapter at different versions — lockstep."))))

(defn- committed-byte-size
  "The leaf's size as git stores it: UTF-8 with LF line endings. A Windows
   checkout (core.autocrlf=true) writes CRLF, which would read one byte per
   line larger than the file every other platform and CI sees."
  [^java.io.File f]
  (count (.getBytes (str/replace (slurp f) "\r\n" "\n") "UTF-8")))

(deftest every-leaf-meets-the-family-byte-ceiling
  (testing "each reference leaf is ≤16 KB and SKILL.md ≤500 lines (skills/README.md §Leaf size discipline)"
    (doseq [f (.listFiles (io/file setup-root "references"))
            :when (str/ends-with? (.getName f) ".md")]
      (is (<= (committed-byte-size f) 16384)
          (str (.getName f) " is " (committed-byte-size f) " bytes (LF-normalised), over the "
               "family 16 KB leaf ceiling (rf2-rc0yh AC2). Trim the prose around the "
               "generated region, not the region.")))
    (is (<= (count (str/split-lines @skill-md)) 500)
        "SKILL.md exceeds the 500-line orchestrator ceiling.")))

(deftest default-route-reads-one-leaf
  (testing "SKILL.md's default route reads first-counter.md and nothing else"
    (let [skill @skill-md]
      (is (str/includes? skill "references/first-counter.md")
          "SKILL.md no longer routes the default to references/first-counter.md.")
      (is (str/includes? skill "nothing else needs reading")
          (str "SKILL.md no longer states that first-counter.md is the whole default — "
               "the default route is SKILL.md plus at most one leaf (rf2-rc0yh AC2)."))))
  (testing "README.md's 'same canonical scaffold' claim is stated (and Lock 0 makes it true)"
    (is (str/includes? @readme-md "Both routes land on the same canonical scaffold")
        (str "README.md dropped the claim that both routes land on the same canonical "
             "scaffold. With the manual route derived from the template it is literally "
             "true; keep saying so (rf2-rc0yh)."))))

;; ---------------------------------------------------------------------------
;; Lock 1 — lockstep is build-time discipline, NOT a boot-time runtime check
;; ---------------------------------------------------------------------------

(deftest deps-versions-does-not-promise-boot-time-enforcement
  (testing "deps-versions.md no longer claims the version contract is checked at boot"
    (let [body @deps-versions-md]
      (is (not (contains-any? body ["checked at boot time"
                                    "enforced at boot"
                                    "validated at boot"]))
          (str "deps-versions.md promises boot-time version enforcement. "
               "The runtime carries NO per-artefact VERSION metadata — "
               "`rf/init!` only nil/non-map-checks the adapter spec and "
               "`install-adapter!` stores `:kind`, never a version (see "
               "implementation/core/src/re_frame/core.cljc + "
               "substrate/adapter.cljc). Lockstep is a build/dependency "
               "discipline; do not promise a runtime guard that doesn't "
               "exist (rf2-0qkyn).")))))

(deftest deps-versions-frames-lockstep-as-build-discipline
  (testing "deps-versions.md frames lockstep as a build/dependency discipline"
    (let [body @deps-versions-md]
      (is (contains-any? body ["build/dependency discipline"
                               "not a boot-time runtime check"])
          (str "deps-versions.md no longer frames lockstep as a "
               "build/dependency discipline. The corrected guidance must "
               "say the discipline is build-time (template pin-drift test) "
               "and that a mixed set is unsupported/undefined — not "
               "caught by a runtime guard."))
      (is (contains-any? body ["unsupported" "undefined"])
          (str "The 'mixed set is unsupported and undefined' framing is "
               "missing — it is what replaces the false boot-enforcement "
               "promise."))
      (is (str/includes? body "version_lockstep_test.clj")
          (str "The pointer to the actual (build-time) lockstep guard "
               "`tools/template/.../version_lockstep_test.clj` is missing. "
               "It names where enforcement really lives.")))))

(deftest deps-versions-gives-a-self-check
  (testing "deps-versions.md gives a concrete validate-it-yourself command"
    (let [body @deps-versions-md]
      (is (contains-any? body ["Validate lockstep yourself"
                               "every printed version must be the same"
                               "grep"])
          (str "deps-versions.md no longer gives a concrete self-check for "
               "lockstep. Replacing a boot-enforcement promise requires "
               "handing the author a real validation step (per the bead's "
               "suggested direction).")))))

;; ---------------------------------------------------------------------------
;; Lock 2 — UIx manual pins match the generator template (source of truth)
;; ---------------------------------------------------------------------------

(deftest uix-leaf-deps-pins-match-template
  (testing "the UIx deps.edn in entry-namespace.md pins uix.core / uix.dom exactly as the template does"
    (let [tmpl-core (mvn-version @uix-template-deps "com.pitch/uix.core")
          tmpl-dom  (mvn-version @uix-template-deps "com.pitch/uix.dom")
          leaf-deps (get @uix-files "deps.edn" "")
          leaf-core (mvn-version leaf-deps "com.pitch/uix.core")
          leaf-dom  (mvn-version leaf-deps "com.pitch/uix.dom")]
      (is (some? tmpl-core)
          "Could not read com.pitch/uix.core pin from the template _uix/deps.edn.")
      (is (some? tmpl-dom)
          "Could not read com.pitch/uix.dom pin from the template _uix/deps.edn.")
      (is (= tmpl-core leaf-core)
          (str "entry-namespace.md's com.pitch/uix.core pin (" (pr-str leaf-core)
               ") diverged from the template (" (pr-str tmpl-core) "). The manual "
               "setup path must generate the same known-good deps as the generator "
               "template (rf2-0qkyn). " regenerate-hint))
      (is (= tmpl-dom leaf-dom)
          (str "entry-namespace.md's com.pitch/uix.dom pin (" (pr-str leaf-dom)
               ") diverged from the template (" (pr-str tmpl-dom) "). " regenerate-hint))
      (is (= tmpl-core tmpl-dom)
          (str "Template uix.core (" tmpl-core ") and uix.dom (" tmpl-dom
               ") pins are no longer equal — UIx ships core+dom in "
               "lockstep; update the template's paired pins accordingly.")))))

(deftest uix-version-target-divergence-is-flagged
  (testing "the spec-006 UIx-2.x vs template-1.4.4 divergence carries a heads-up"
    (let [skill @entry-namespace-md]
      (is (contains-any? skill ["UIx 2.x" "version target"])
          (str "entry-namespace.md no longer flags the UIx version-target "
               "divergence. spec/006 names UIx 2.x as the design target "
               "while the template pins 1.4.4 — an author following the "
               "manual path must be told the template pin is the tested "
               "set, so they don't chase 2.x and ship an unverified "
               "scaffold (rf2-0qkyn)."))
      (is (contains-any? skill ["known-good" "tested"])
          (str "The 'template pin is the known-good/tested set' framing is "
               "missing from the UIx version-target heads-up.")))))

;; ---------------------------------------------------------------------------
;; Lock 3 — the default page ships NO Xray host (flipped by rf2-rc0yh slice B).
;;
;; Before the rf2-zq34m collapse the Reagent route scaffolded an
;; `<aside data-rf-xray-host>` column beside `#app`, and three locks here
;; pinned its published geometry (right side, --rf-xray-inline-width, no
;; stale 420px). The template now emits one mount node and no host, and so
;; does the skill: the same three locks pin the ABSENCE of the host — in the
;; emitted page, in every skill file, and in the DOM shape.
;; ---------------------------------------------------------------------------

(def ^:private xray-host-tokens
  ["data-rf-xray-host" "rf2-xray-host" "--rf-xray-inline-width" "420px"
   ":devtools/preloads" "day8.re-frame2-xray.preload" "@xyflow/react" "elkjs"])

(deftest default-scaffold-ships-no-xray-host
  (testing "first-counter.md's index.html / app.css blocks carry no Xray host column or host CSS"
    (let [html (get @first-counter-files "resources/public/index.html" "")
          css  (get @first-counter-files "resources/public/css/app.css" "")]
      (is (seq html) "first-counter.md carries no resources/public/index.html block.")
      (is (seq css)  "first-counter.md carries no resources/public/css/app.css block.")
      (doseq [[label body] [["index.html" html] ["app.css" css]]
              token ["data-rf-xray-host" "rf2-xray-host" "--rf-xray"]]
        (is (not (str/includes? body token))
            (str "the default scaffold's " label " carries `" token "` — the reduced "
                 "template ships no Xray host (rf2-zq34m: STRIP BOTH); Xray attaches "
                 "later by its own recipe (rf2-rc0yh)."))))))

(deftest skill-carries-no-xray-host-wiring
  (testing "no skill file names the Xray host / preload / npm pair as scaffold wiring"
    (doseq [[label body] [["SKILL.md" @skill-md]
                          ["README.md" @readme-md]
                          ["first-counter.md" @first-counter-md]
                          ["shadow-cljs.md" @shadow-cljs-md]
                          ["entry-namespace.md" @entry-namespace-md]
                          ["deps-versions.md" @deps-versions-md]]
            token xray-host-tokens]
      (is (not (str/includes? body token))
          (str label " names `" token "`. Xray, its host column, its devtools "
               "preload and its npm pair are not part of the scaffold on any route — "
               "they attach later, by Xray's own recipe (rf2-rc0yh, rf2-zq34m).")))))

(deftest default-index-html-has-one-mount-node-and-no-aside
  (testing "first-counter.md's index.html is the template's: <main id=\"app\"> and no <aside>"
    (let [html (get @first-counter-files "resources/public/index.html" "")]
      (is (str/includes? html "<main id=\"app\">")
          "the default index.html lost its <main id=\"app\"> mount node.")
      (is (not (str/includes? html "<aside"))
          (str "the default index.html carries an <aside> — the reduced template "
               "ships one mount node and no layout column (rf2-rc0yh)."))
      (is (= (str/trim html)
             (-> @template-index-html
                 (str/replace "\r\n" "\n")
                 (str/replace "{{name}}" "acme/my-app")
                 str/trim))
          (str "the default index.html differs from the template's root index.html "
               "rendered for acme/my-app. " regenerate-hint)))))

;; ---------------------------------------------------------------------------
;; Lock 4 — the reagent/dom CLJS-namespace troubleshooting row diagnoses the
;; Maven/classpath side, NOT npm React.
;; ---------------------------------------------------------------------------

(deftest reagent-dom-row-diagnoses-maven-not-npm
  (testing "SKILL.md's reagent/dom/client.cljs row points at the Maven/classpath cause"
    (let [body @skill-md
          row   (some-> (re-find #"(?m)^- \*\*`Could not locate reagent/dom/client\.cljs`.*$"
                                 body))]
      (is (some? row)
          "Could not find the `Could not locate reagent/dom/client.cljs` troubleshooting row in SKILL.md.")
      (is (and row (str/includes? row "reagent/reagent"))
          (str "The reagent/dom/client.cljs row does not name the "
               "`reagent/reagent` Maven coordinate as the cause. That "
               "namespace is provided by the Maven dep on the CLJS "
               "classpath, not by npm React (rf2-w4axt)."))
      (is (and row (contains-any? row ["classpath" "deps.edn" "Maven"]))
          (str "The reagent/dom/client.cljs row no longer frames the fix as "
               "a Maven/classpath problem (rf2-w4axt)."))
      (is (and row (not (str/includes? row "npm install react react-dom")))
          (str "The reagent/dom/client.cljs row still advises `npm install "
               "react react-dom` — a missing CLJS namespace is never fixed "
               "by installing npm packages. npm-React failures belong in the "
               "separate JS-module-resolution row (rf2-w4axt)."))
      (is (re-find #"Cannot find module 'react'" body)
          (str "SKILL.md lost the separate npm-React troubleshooting row "
               "(`Cannot find module 'react'` / react-dom/client). Real JS "
               "module-resolution failures need their own row pointing at "
               "`npm install` (rf2-w4axt).")))))

;; ---------------------------------------------------------------------------
;; Lock 5 — the default route is CSP-free (flipped by rf2-rc0yh slice B).
;;
;; Four locks used to pin a dev-flavoured CSP meta tag in the scaffold's
;; index.html against the template's, plus the documented production
;; response header. The reduced template ships no CSP at all — a dev page
;; with a strict meta CSP is the blank-first-page trap the boot proof
;; exists for, and a production policy is the host's, not the scaffold's.
;; The flipped lock pins the absence in the emitted page and keeps CSP prose
;; off the default route (SKILL.md + first-counter.md); shadow-cljs.md may
;; carry the one 'unsafe-eval' warning for an author who adds a policy later.
;; ---------------------------------------------------------------------------

(deftest default-route-is-csp-free
  (testing "the template's index.html carries no CSP meta tag (premise)"
    (is (not (str/includes? @template-index-html "Content-Security-Policy"))
        (str "tools/template's root index.html now carries a Content-Security-Policy. "
             "If the template deliberately re-added a CSP, revisit this lock and the "
             "skill's shadow-cljs.md together.")))
  (testing "first-counter.md's index.html block carries no CSP meta tag"
    (is (not (str/includes? (get @first-counter-files "resources/public/index.html" "")
                            "Content-Security-Policy"))
        (str "the default index.html carries a Content-Security-Policy meta tag the "
             "template does not. " regenerate-hint)))
  (testing "SKILL.md and first-counter.md teach no CSP on the default route"
    (doseq [[label body] [["SKILL.md" @skill-md] ["first-counter.md" @first-counter-md]]
            token ["Content-Security-Policy" "unsafe-eval" "frame-ancestors"]]
      (is (not (str/includes? body token))
          (str label " names `" token "`. CSP / hosting policy is a later, explicit "
               "step — not day-one, not on the default route (rf2-rc0yh).")))))

;; ---------------------------------------------------------------------------
;; Lock 6 — user-facing direct-run shadow-cljs commands are qualified with npx.
;; ---------------------------------------------------------------------------

(deftest first-counter-verify-command-uses-npx
  (testing "first-counter.md's verification prose qualifies shadow-cljs with npx"
    (let [fc @first-counter-md]
      (is (str/includes? fc "npx shadow-cljs watch app")
          (str "first-counter.md's verify step no longer runs "
               "`npx shadow-cljs watch app`. A fresh project's shadow-cljs is "
               "a local devDependency — bare `shadow-cljs` is not on PATH "
               "(esp. Windows/PowerShell) (rf2-pxl6l).")))))

;; ---------------------------------------------------------------------------
;; Lock 7 — the UIx route supplies substrate-specific VIEW code and does NOT
;; route UIx authors to the Reagent `rf/reg-view` views.
;; ---------------------------------------------------------------------------

(deftest uix-greenfield-supplies-substrate-views
  (testing "entry-namespace.md gives UIx view code (defui + use-subscribe), not just deps/entry"
    (let [body @entry-namespace-md]
      (is (str/includes? body "defui counter-buttons")
          (str "entry-namespace.md no longer supplies the UIx (`defui`) "
               "counter view snippet. UIx has no "
               "auto-injection — the manual path must ship the substrate "
               "`views.cljs`, not send the author to the Reagent `reg-view` "
               "views (rf2-74uffk)."))
      (is (str/includes? body "uix-adapter/use-subscribe")
          (str "The UIx view snippet must read subscriptions through "
               "the adapter `use-subscribe` hook (no auto-injected `subscribe` "
               "on that substrate) (rf2-74uffk)."))
      (is (str/includes? body "(uix-adapter/use-frame)")
          (str "The UIx view snippet must obtain `dispatch` from the "
               "adapter `use-frame` hook (capture-frame in hook position) — "
               "there is no auto-injected `dispatch` on that substrate "
               "(rf2-p74yf2)."))
      (is (not (re-find #"(?i)views.{0,40}identical across substrates" body))
          (str "entry-namespace.md claims views are identical across "
               "substrates — they are NOT. Reagent uses `reg-view`; UIx "
               "uses `defui` with `use-subscribe`. The 'everything else "
               "identical' claim must exclude views (rf2-74uffk).")))))

(deftest uix-not-routed-to-reagent-reg-view-counter
  (testing "SKILL.md + first-counter.md steer UIx away from the Reagent reg-view views"
    (let [skill @skill-md
          fc    @first-counter-md]
      (is (contains-any? skill ["UIx does NOT use `reg-view`"
                                "UIx** does NOT use `reg-view`"
                                "UIx does not use `reg-view`"
                                "does NOT use `reg-view`"])
          (str "SKILL.md no longer warns that UIx does not use the "
               "Reagent `reg-view` views. A UIx author must be routed "
               "to the substrate views, not the Reagent leaf (rf2-74uffk)."))
      (is (contains-any? fc ["Reagent only" "Reagent-only"])
          (str "first-counter.md no longer flags itself as Reagent-only. The "
               "leaf's views use `rf/reg-view` + `reagent.dom.client` — UIx must "
               "be redirected to the substrate views (rf2-74uffk)."))
      (is (and (str/includes? fc "use-subscribe")
               (str/includes? fc "entry-namespace.md"))
          (str "first-counter.md no longer redirects UIx authors to the "
               "`use-subscribe`/substrate path in entry-namespace.md "
               "(rf2-74uffk).")))))

;; ---------------------------------------------------------------------------
;; Lock 8 — the JS-module React recovery uses the PINNED baseline, not bare
;; `npm install react react-dom` (which writes latest-from-npm).
;; ---------------------------------------------------------------------------

(deftest js-module-react-row-uses-pinned-baseline
  (testing "SKILL.md's JS-module React row recovers on the pinned baseline, not bare npm install"
    (let [body @skill-md
          row  (some-> (re-find #"(?m)^- \*\*`Cannot find module 'react'`.*$" body))]
      (is (some? row)
          "Could not find the `Cannot find module 'react'` troubleshooting row in SKILL.md.")
      (is (and row (str/includes? row "pinned"))
          (str "The JS-module React row no longer recovers from the PINNED "
               "baseline. The fix is to restore react/react-dom in "
               "package.json to the pinned versions the leaf carries, "
               "then plain `npm install` — not latest-from-npm (rf2-74uffk)."))
      (is (and row (contains-any? row ["reproducibility" "cardinal rule"]))
          (str "The JS-module React row no longer cites the reproducibility / "
               "cardinal-rule reason for avoiding latest-from-npm "
               "(rf2-74uffk)."))
      (is (and row (str/includes? row "Don't run bare `npm install react react-dom`"))
          (str "The JS-module React row no longer explicitly FORBIDS bare "
               "`npm install react react-dom` (writes npm `latest`, breaks "
               "reproducibility). If the bare command appears, it must be "
               "framed as the thing NOT to do (rf2-74uffk).")))))

;; ---------------------------------------------------------------------------
;; Lock 9 — the greenfield coordinate BRANCHES on publication state. It does
;; not teach one unconditional shape. (Subject-agnostic on purpose — see the
;; rf2-lyriz rationale: the branch survives whichever tier is currently the
;; unresolvable one; delete this deliberately only when EVERY day8/re-frame2*
;; coordinate resolves.)
;; ---------------------------------------------------------------------------

(deftest deps-guidance-branches-on-actual-publication-state
  (testing "deps-versions.md branches the coordinate shape on what actually resolves, not one unconditional :mvn/version"
    (let [body @deps-versions-md]
      (is (contains-any? body ["not published" "NOT on Clojars" "not on Clojars"
                               "have not published" "not yet published"])
          (str "deps-versions.md no longer names ANY day8/re-frame2* "
               "coordinate as unpublished, so it can no longer branch the "
               "coordinate shape at all. If EVERY day8/re-frame2* coordinate "
               "now resolves on Clojars then this lock is done: retire it "
               "deliberately, do not restate something false to get green "
               "(rf2-ol8l7a, rf2-lyriz)."))
      (is (str/includes? body ":git/sha")
          (str "deps-versions.md no longer gives the `:git/sha` route. For any "
               "day8/re-frame2* artefact whose `:mvn/version` does not resolve, "
               "`:git/url` + `:git/sha` (or `:local/root`) is the ONLY working "
               "manual route (rf2-ol8l7a, rf2-lyriz)."))
      (is (contains-any? body ["Clojars" "resolve"])
          (str "deps-versions.md no longer points version discovery at whether "
               "the coordinate actually RESOLVES on Clojars (rf2-ol8l7a)."))
      (is (contains-any? body ["After publication" "Post-publish" "post-publish"
                               "AFTER PUBLICATION"])
          (str "deps-versions.md no longer labels `:mvn/version` as the "
               "POST-PUBLISH destination for the coordinate that does not "
               "resolve yet (rf2-ol8l7a, rf2-lyriz)."))))
  (testing "SKILL.md step 2 points the framework coords at something that resolves, on both routes"
    (let [skill @skill-md]
      (is (str/includes? skill ":local/root \"<RE_FRAME2>/implementation/core\"")
          (str "SKILL.md step 2 no longer shows the pre-publish :local/root rewrite for "
               "day8/re-frame2. The leaf's deps.edn ships a forward-correct :mvn/version "
               "that 404s today; the skill must point it at the checkout before "
               "npm install (rf2-rc0yh)."))
      (is (str/includes? skill "generator route")
          (str "SKILL.md no longer says the coordinate step follows the generator route "
               "too — the generator emits the same unresolvable :mvn/version (rf2-rc0yh).")))))

;; ---------------------------------------------------------------------------
;; Lock 10 — schemas are pay-as-you-go, NOT day-one (flipped by rf2-rc0yh
;; slice B). The counter attaches no schema; `day8/re-frame2-schemas` is
;; added at the moment the author calls reg-app-schema, and deps-versions.md
;; keeps the loud `:rf.error/schemas-artefact-missing` contract on that row
;; (Spec 010: schema implies validation; no soft-pass).
;; ---------------------------------------------------------------------------

(deftest schemas-are-pay-as-you-go-not-day-one
  (testing "the default scaffold carries no schema artefact, require, or registration"
    (doseq [[path body] @first-counter-files
            token ["re-frame.schemas" "reg-app-schema" "CounterDb" "re-frame2-schemas"]]
      (is (not (str/includes? body token))
          (str "first-counter.md's `" path "` carries `" token "` — schemas are not "
               "day-one on the reduced scaffold (rf2-zq34m); add the artefact when the "
               "author writes a schema (rf2-rc0yh AC3)."))))
  (testing "the default deps.edn is exactly core + adapter + view library (+ Clojure/ClojureScript)"
    (let [deps (clojure.edn/read-string (get @first-counter-files "deps.edn" "{}"))]
      (is (= #{"org.clojure/clojure" "org.clojure/clojurescript" "day8/re-frame2"
               "day8/re-frame2-reagent" "reagent/reagent"}
             (set (map str (keys (:deps deps)))))
          (str "first-counter.md's deps.edn :deps are not the reduced five (clojure, "
               "clojurescript, core, adapter, reagent). " regenerate-hint))))
  (testing "deps-versions.md keeps the loud schemas contract on the pay-as-you-go row"
    (let [body @deps-versions-md]
      (is (str/includes? body ":rf.error/schemas-artefact-missing")
          (str "deps-versions.md no longer names the loud "
               "`:rf.error/schemas-artefact-missing` thrown when an app calls "
               "reg-app-schema without day8/re-frame2-schemas (rf2-ol8l7a)."))
      (is (str/includes? body "re-frame.schemas")
          (str "deps-versions.md no longer says you must `:require` "
               "`re-frame.schemas` before reg-app-schema (rf2-ol8l7a)."))
      (is (not (re-find #"(?i)without (it|the artefact|day8/re-frame2-schemas)[^.\n]{0,80}soft.?pass"
                        body))
          (str "deps-versions.md says missing the schemas artefact soft-passes. "
               "The contract is a loud throw (rf2-ol8l7a)."))))
  (testing "SKILL.md says no schemas on day one"
    (is (str/includes? @skill-md "no schemas")
        (str "SKILL.md no longer states that schemas are not day-one. The reduced "
             "scaffold ships none; the author adds the artefact when they write one "
             "(rf2-rc0yh)."))))

;; ---------------------------------------------------------------------------
;; Lock 11 — the default build carries NO devtools preload (flipped by
;; rf2-rc0yh slice B). The FIRST copyable shadow-cljs.edn used to have to
;; carry `:devtools {:preloads [day8.re-frame2-xray.preload]}`; the reduced
;; template emits no :devtools key at all, and Xray is a next step.
;; ---------------------------------------------------------------------------

(deftest default-shadow-build-carries-no-devtools-preload
  (testing "first-counter.md's shadow-cljs.edn is the template's two-build config with no :devtools"
    (let [block (get @first-counter-files "shadow-cljs.edn" "")]
      (is (seq block) "first-counter.md carries no shadow-cljs.edn block.")
      (is (str/includes? block ":builds") "the shadow-cljs.edn block has no :builds map.")
      (is (str/includes? block ":init-fn acme.my-app.core/init")
          "the shadow-cljs.edn block's :init-fn no longer names acme.my-app.core/init.")
      (is (str/includes? block ":test") "the shadow-cljs.edn block lost the :test build the template ships.")
      (is (not (str/includes? block ":devtools"))
          (str "the default shadow-cljs.edn carries a :devtools map — the reduced "
               "template emits none; a preload is a later, explicit step (rf2-rc0yh)."))
      (is (not (str/includes? block "re-frame2-xray"))
          "the default shadow-cljs.edn names Xray — it ships no preload (rf2-rc0yh)."))))

(deftest xray-is-a-next-step-not-day-one
  (testing "SKILL.md and first-counter.md name no Xray coordinate or preload as scaffold content"
    (doseq [[label body] [["SKILL.md" @skill-md] ["first-counter.md" @first-counter-md]]
            token ["day8/re-frame2-xray" "day8.re-frame2-xray"]]
      (is (not (str/includes? body token))
          (str label " names `" token "` — Xray is not day-one on either route "
               "(rf2-zq34m STRIP BOTH; rf2-rc0yh AC3)."))))
  (testing "SKILL.md frames Xray as a later step the handoff points at, and states the scaffold is small"
    (let [skill @skill-md]
      (is (str/includes? skill "Xray")
          "SKILL.md no longer mentions Xray at all — the handoff should still point at it as an optional next step.")
      (is (str/includes? skill "no Xray")
          "SKILL.md no longer states that Xray is not day-one (rf2-rc0yh).")
      (is (str/includes? skill "Next steps")
          "SKILL.md no longer routes the optional attachments through the generated README's Next steps."))))

;; ---------------------------------------------------------------------------
;; Lock 12 — zero-interview pin default + executor posture (rf2-rc0yh).
;;
;; The RETIRED original Lock 12 (rf2-agi57x) asserted the opposite posture for
;; the generator — that allowed-tools must NOT grant `clojure -Tnew` and the
;; prose must frame the generator as user-run. rf2-rc0yh dropped that
;; prohibition as policy; do not reintroduce it.
;; ---------------------------------------------------------------------------

(deftest pin-default-is-zero-interview
  (testing "deps-versions.md defaults the pin to the template baseline instead of stopping to ask"
    (let [body @deps-versions-md]
      (is (not (re-find #"(?i)stop and ask" body))
          (str "deps-versions.md tells the skill to stop and ask for a pin "
               "again. The zero-interview contract (rf2-rc0yh): when the "
               "author supplies no pin, the default IS the generator "
               "template's pinned baseline — proceed, don't interview."))
      (is (not (re-find #"(?i)the skill never auto-selects" body))
          (str "deps-versions.md re-introduces the never-auto-selects pin "
               "interview (rf2-rc0yh)."))
      (is (contains-any? body ["default pin is the generator template's baseline"
                               "an author-supplied pin overrides"])
          (str "deps-versions.md no longer names the generator template's "
               "pinned baseline as the no-pin default (rf2-rc0yh)."))))
  (testing "SKILL.md states the zero-interview default"
    (let [skill @skill-md]
      (is (contains-any? skill ["no clarification round" "never a reason to stop and ask"
                                "zero-interview"])
          (str "SKILL.md no longer states the zero-interview default "
               "(rf2-rc0yh).")))))

(deftest skill-executes-the-scaffold-and-reports-the-url
  (testing "SKILL.md frames the skill as running install + a terminating compile itself"
    (let [skill @skill-md
          fm    (some-> (re-find #"(?s)^---\r?\n(.*?)\r?\n---" skill) second)]
      (is (some? fm)
          "Could not isolate the SKILL.md YAML front-matter (allowed-tools block).")
      (is (and fm
               (str/includes? fm "npm install")
               (str/includes? fm "shadow-cljs compile"))
          (str "SKILL.md's allowed-tools no longer grant the executor path "
               "(`npm install` + `shadow-cljs compile`) (rf2-rc0yh)."))
      (is (str/includes? skill "The skill runs both commands itself")
          (str "SKILL.md no longer frames the skill as the executor of the "
               "verify-and-serve step (rf2-rc0yh)."))
      (is (str/includes? skill "http://localhost:8280/")
          (str "SKILL.md no longer reports the actual dev URL "
               "(http://localhost:8280/) (rf2-rc0yh)."))
      (is (contains-any? skill ["not the mount" "does not prove the mount"
                                "don't claim the mount" "compile success alone"])
          (str "SKILL.md dropped the honesty line: compile success proves the "
               "build, not the browser mount (rf2-rc0yh).")))))

;; ---------------------------------------------------------------------------
;; Lock 13 — the PUBLIC entry-ramp docs (docs-site setup page + top-level
;; skills index) stay in sync with the current setup/template contract.
;; ---------------------------------------------------------------------------

(def ^:private docs-setup-page-md
  (delay (slurp-rel repo-root "docs/skills/re-frame2-setup.md")))

(def ^:private skills-index-md
  (delay (slurp-rel repo-root "skills/README.md")))

(deftest docs-setup-page-no-stale-artefact-count
  (testing "docs/skills/re-frame2-setup.md does not re-teach the stale 'all eleven' lockstep count"
    (let [body @docs-setup-page-md]
      (is (not (re-find #"(?i)eleven" body))
          (str "docs/skills/re-frame2-setup.md re-teaches the stale "
               "\"all eleven ship at the same version\" lockstep count. The "
               "current contract is TEN publishable framework artefacts."))
      (is (re-find #"(?i)\ball ten\b" body)
          (str "docs/skills/re-frame2-setup.md no longer states the TEN "
               "publishable framework artefacts ship in lockstep."))))
  (testing "docs/skills/re-frame2-setup.md teaches the reduced twelve-file scaffold"
    (is (str/includes? @docs-setup-page-md "twelve files")
        (str "docs/skills/re-frame2-setup.md no longer describes the twelve-file "
             "scaffold the skill writes (rf2-rc0yh)."))))

(deftest docs-setup-page-references-link-is-plural
  (testing "docs/skills/re-frame2-setup.md links the reference leaves to the real plural `references/` path"
    (let [body @docs-setup-page-md]
      (is (not (re-find #"skills/re-frame2-setup/reference(?!s)" body))
          (str "docs/skills/re-frame2-setup.md links the reference leaves to "
               "the SINGULAR `skills/re-frame2-setup/reference` GitHub path, "
               "which 404s (rf2-79gtjr)."))
      (is (re-find #"skills/re-frame2-setup/references" body)
          (str "docs/skills/re-frame2-setup.md no longer links the reference "
               "leaves to the real plural path (rf2-79gtjr).")))))

(deftest skills-index-template-form-carries-pre-split-caveat
  (testing "skills/README.md pairs any generator mention with a pre-split caveat + the working :local/root route"
    (let [body @skills-index-md]
      (is (str/includes? body "tools/template")
          (str "skills/README.md no longer references the generator template "
               "(tools/template). If it was removed deliberately, revisit "
               "Lock 13 (rf2-79gtjr)."))
      (is (contains-any? body ["Pre-split" "pre-split" "isn't published yet"
                               "not published yet" "can't resolve"])
          (str "skills/README.md mentions the generator template with NO "
               "pre-split caveat (rf2-79gtjr)."))
      (is (str/includes? body ":local/root")
          (str "skills/README.md gives the pre-split caveat but not the "
               "working `:local/root` route (rf2-79gtjr).")))))

;; ---------------------------------------------------------------------------
;; Lock 8b — the manual boot seed matches the generator's frame-root ENSURE
;; contract, on both substrates.
;; ---------------------------------------------------------------------------

(def ^:private frame-root-ensure
  #"frame-root\s+\{:id\s+app-frame[^}]*:initial-events\s+\[\[:counter/initialise\]\]")

(deftest manual-boot-seed-matches-generator-frame-root-contract
  (testing "the generator Reagent template mounts via frame-root {:id app-frame :initial-events …} (premise of this lock)"
    (let [tmpl @reagent-template-core]
      (is (and (str/includes? tmpl "rf/frame-root")
               (re-find #":initial-events\s+\[\[:counter/initialise\]\]" tmpl)
               (str/includes? tmpl "(def app-frame :rf/default)"))
          (str "the generator Reagent template no longer mounts via "
               "`[rf/frame-root {:id app-frame :initial-events "
               "[[:counter/initialise]]}]` with `(def app-frame :rf/default)`. "
               "If the generator boot contract changed, regenerate the leaves "
               "AND update this lock together."))
      (is (not (str/includes? tmpl "reg-frame"))
          (str "the generator Reagent template reintroduced the retired "
               "`reg-frame` boot ceremony."))))
  (testing "both entry namespaces the skill ships mount via frame-root's ENSURE seed, not the retired ceremony"
    (doseq [[label body] [["first-counter.md core.cljs" (get @first-counter-files "src/acme/my_app/core.cljs" "")]
                          ["entry-namespace.md UIx core.cljs" (get @uix-files "src/acme/my_app/core.cljs" "")]]]
      (is (re-find frame-root-ensure body)
          (str label " no longer mounts via `frame-root {:id app-frame :initial-events "
               "[[:counter/initialise]]}`. The manual boot must match the generator's "
               "ENSURE contract. " regenerate-hint))
      (is (str/includes? body "(def app-frame :rf/default)")
          (str label " no longer defines app-frame as :rf/default. " regenerate-hint))
      (is (not (re-find #"\(rf/reg-frame" body))
          (str label " reintroduced the retired `reg-frame` boot ceremony.")))))

;; ---------------------------------------------------------------------------
;; Lock 14 — the dataflow is registered once, on both substrates, with no
;; schema axis (flipped by rf2-rc0yh slice B: the shared-dataflow.md leaf and
;; its schema.cljs are retired; events.cljs + subs.cljs live in the default
;; scaffold and the UIx entry ns requires them).
;; ---------------------------------------------------------------------------

(deftest default-scaffold-registers-events-and-sub-without-schema
  (testing "first-counter.md's events.cljs / subs.cljs register the counter vocabulary"
    (let [events (get @first-counter-files "src/acme/my_app/events.cljs" "")
          subs   (get @first-counter-files "src/acme/my_app/subs.cljs" "")
          views  (get @first-counter-files "src/acme/my_app/views.cljs" "")]
      (is (and (re-find #"reg-event\s+:counter/initialise" events)
               (re-find #"reg-event\s+:counter/increment" events))
          (str "events.cljs no longer registers BOTH :counter/initialise and "
               ":counter/increment. " regenerate-hint))
      (is (re-find #"reg-sub\s+:counter/value" subs)
          (str "subs.cljs no longer registers :counter/value. " regenerate-hint))
      (is (and (re-find #"dispatch\s+\[:counter/increment\]" views)
               (re-find #"subscribe\s+\[:counter/value\]" views))
          (str "views.cljs no longer dispatches :counter/increment / subscribes "
               ":counter/value — the ids the events/subs install. " regenerate-hint))))
  (testing "no file in the default scaffold registers a schema"
    (doseq [[path body] @first-counter-files]
      (is (not (re-find #"reg-app-schemas?|register-schema!" body))
          (str "first-counter.md's `" path "` registers a schema. The reduced scaffold "
               "has no schema axis (rf2-rc0yh AC3).")))))

(deftest uix-core-requires-events-subs-views-and-attaches-no-schema
  (testing "entry-namespace.md's UIx core.cljs requires the shared dataflow and boots init! -> mount!"
    (let [body (get @uix-files "src/acme/my_app/core.cljs" "")]
      (doseq [ns-token ["acme.my-app.events" "acme.my-app.subs" "acme.my-app.views"]]
        (is (str/includes? body (str "[" ns-token))
            (str "entry-namespace.md's UIx core.cljs no longer :requires `" ns-token
                 "`. The substrate entry ns must load the shared registrations "
                 "(rf2-3fc89f lineage). " regenerate-hint)))
      (is (not (re-find #"register-schema!|re-frame\.schemas" body))
          (str "entry-namespace.md's UIx core.cljs attaches a schema — the reduced "
               "scaffold has none on either substrate (rf2-rc0yh)."))
      (is (re-find #"\(defn\s+\^:export\s+init\s+\[\]\s+\(rf/init!\s+uix-adapter/adapter\)\s+\(mount!\)\)" body)
          (str "entry-namespace.md's UIx `init` is no longer exactly `(rf/init! "
               "uix-adapter/adapter)` then `(mount!)` — the template's two-step boot. "
               regenerate-hint)))))

;; ---------------------------------------------------------------------------
;; Lock 14b — the substrate entry ns carries the ^:dev/after-load re-render
;; hook that gives the author hot reload (rf2-ms6r8, measured under rf2-r0kk7).
;; ---------------------------------------------------------------------------

(deftest uix-core-carries-after-load-render-hook
  (testing "entry-namespace.md's UIx core.cljs re-renders from a ^:dev/after-load hook"
    (let [body (get @uix-files "src/acme/my_app/core.cljs" "")]
      (is (re-find #"\(defn\s+\^:dev/after-load\s+mount!" body)
          (str "entry-namespace.md's UIx core.cljs no longer defines "
               "`(defn ^:dev/after-load mount! ...)`. shadow does NOT re-run the "
               "module :init-fn after a hot reload — without this hook the "
               "scaffolded app compiles, reloads, and never repaints "
               "(rf2-ms6r8)."))
      (is (re-find #"\(defn\s+\^:dev/after-load\s+mount!(?:[\s\S]{0,600}?)frame-root\s+\{:id\s+app-frame[\s\S]{0,120}?:initial-events\s+\[\[:counter/initialise\]\]"
                   body)
          (str "entry-namespace.md's `^:dev/after-load mount!` no longer "
               "contains the frame-root ENSURE mount. The hook must be what "
               "re-renders (rf2-ms6r8).")))))

;; ---------------------------------------------------------------------------
;; Lock 15 — the UIx route is the template's three-file swap of the same
;; Xray-free, schema-free scaffold (flipped by rf2-rc0yh slice B: before the
;; collapse only the UIx route was Xray-free and it carried its own build
;; wiring; now both routes are, and the UIx route shares the nine other files).
;; ---------------------------------------------------------------------------

(deftest both-templates-are-xray-free-and-schema-free
  (testing "neither template deps.edn carries an Xray or schemas coord (premise of this lock)"
    (doseq [[label body] [["_reagent/deps.edn" @reagent-template-deps]
                          ["_uix/deps.edn" @uix-template-deps]]
            token ["re-frame2-xray" "re-frame2-schemas"]]
      (is (not (str/includes? body token))
          (str "tools/template's " label " now carries " token ". If the template "
               "deliberately re-ships it, update Lock 15 AND the skill's day-one rule "
               "together (rf2-zq34m, rf2-rc0yh).")))))

(deftest uix-route-shares-the-default-build-wiring
  (testing "entry-namespace.md carries no build wiring of its own — the nine shared files are the default's"
    (let [body @entry-namespace-md]
      (is (not (str/includes? body ":builds"))
          (str "entry-namespace.md carries a `:builds` map — the UIx route ships the "
               "default scaffold's shadow-cljs.edn unchanged (rf2-rc0yh)."))
      (is (not (re-find #"(?s)```(html|css)\r?\n" body))
          (str "entry-namespace.md carries an html/css block — the UIx route ships the "
               "default index.html / app.css unchanged (rf2-rc0yh)."))
      (is (str/includes? body "identical to the Reagent scaffold")
          (str "entry-namespace.md no longer states that the other nine files are "
               "identical to the Reagent scaffold (rf2-rc0yh).")))))

(deftest uix-route-is-a-three-file-swap
  (testing "the UIx region carries exactly the files the template's template-fn varies per substrate"
    (let [expected (set (first-counter-derivation/substrate-swap-paths))]
      (is (= #{"deps.edn" "src/acme/my_app/core.cljs" "src/acme/my_app/views.cljs"} expected)
          (str "the template now varies a different file set per substrate: " (pr-str expected)
               ". Regenerate the leaves and update SKILL.md's three-file-swap rule."))
      (is (= expected (set (keys @uix-files)))
          (str "entry-namespace.md's UIx region carries " (pr-str (sort (keys @uix-files)))
               " but the template varies " (pr-str (sort expected)) ". " regenerate-hint))))
  (testing "SKILL.md states the UIx route as the three-file swap of the same scaffold"
    (is (str/includes? @skill-md "three-file swap")
        "SKILL.md no longer states the UIx route as a three-file swap (rf2-rc0yh)."))
  (testing "deps-versions.md scopes no Xray to any route"
    (is (not (contains-any? @deps-versions-md ["day-one dep on the Reagent route"
                                               "Reagent-route-only"]))
        (str "deps-versions.md still scopes Xray or its npm pair to the Reagent route as "
             "day-one — neither route ships Xray (rf2-zq34m STRIP BOTH; rf2-rc0yh)."))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'setup-drift-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
