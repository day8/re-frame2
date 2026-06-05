;;;; tests/setup_drift_test.clj — structural regression for the
;;;; re-frame2-setup skill's correctness-critical contract claims.
;;;;
;;;; Guards the two drifts the skills-setup correctness review found
;;;; (rf2-0qkyn):
;;;;
;;;;   1. Lockstep is a BUILD/dependency discipline, not a boot-time
;;;;      runtime check. The runtime (`rf/init!` + `install-adapter!`)
;;;;      carries no per-artefact VERSION metadata, so the skill MUST NOT
;;;;      promise that a mixed-version app is caught "at boot time". A
;;;;      future edit re-introducing the boot-enforcement claim fails this
;;;;      suite.
;;;;
;;;;   2. The UIx / Helix manual substrate pins MUST match the generator
;;;;      template's `deps.edn` literals (the source of truth — the skill's
;;;;      own "never invent a version, match the template" discipline).
;;;;      This test READS the live template `_uix/deps.edn` /
;;;;      `_helix/deps.edn` on disk and asserts the skill's entry-namespace
;;;;      pins still match — so a template bump that isn't mirrored into the
;;;;      skill fails here rather than silently shipping a stale pin.
;;;;
;;;; This is the CHEAP class of drift the setup skill can suffer: a prose
;;;; promise of a runtime invariant that doesn't exist, or a substrate pin
;;;; that diverges from the tested template. Mirrors the shape of
;;;; `skills/shared/tests/retro_protocol_test.clj`.
;;;;
;;;; Run:    bb tests/setup_drift_test.clj   (from skills/re-frame2-setup/)
;;;; Exit:   0 = pass, non-zero = fail.
;;;;
;;;; NOT published — `package.json` :files excludes `tests/`.

(ns setup-drift-test
  (:require [clojure.java.io :as io]
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

(def ^:private uix-template-deps
  (delay (slurp-rel repo-root
                    "tools/template/resources/day8/re_frame2_template/_uix/deps.edn")))

(def ^:private helix-template-deps
  (delay (slurp-rel repo-root
                    "tools/template/resources/day8/re_frame2_template/_helix/deps.edn")))

(defn- contains-any? [text alts]
  (some #(str/includes? text %) alts))

(defn- mvn-version
  "Extract the :mvn/version string pinned for `coord` in a deps.edn body,
   or nil if the coord isn't found."
  [deps-body coord]
  (some-> (re-find (re-pattern (str (java.util.regex.Pattern/quote coord)
                                    "\\s*\\{:mvn/version\\s+\"([^\"]+)\""))
                   deps-body)
          second))

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
;; Lock 2 — UIx / Helix manual pins match the generator template (source of truth)
;; ---------------------------------------------------------------------------

(deftest uix-skill-pin-matches-template
  (testing "entry-namespace.md UIx pins match the template _uix/deps.edn"
    (let [tmpl-core (mvn-version @uix-template-deps "com.pitch/uix.core")
          tmpl-dom  (mvn-version @uix-template-deps "com.pitch/uix.dom")
          skill     @entry-namespace-md]
      (is (some? tmpl-core)
          "Could not read com.pitch/uix.core pin from the template _uix/deps.edn.")
      (is (some? tmpl-dom)
          "Could not read com.pitch/uix.dom pin from the template _uix/deps.edn.")
      (is (str/includes? skill (str "com.pitch/uix.core {:mvn/version \"" tmpl-core "\"}"))
          (str "entry-namespace.md's com.pitch/uix.core pin diverged from "
               "the template (template pins " (pr-str tmpl-core) "). The "
               "manual setup path must generate the same known-good deps "
               "as the generator template (rf2-0qkyn). Mirror the template "
               "bump into the skill."))
      (is (str/includes? skill (str "com.pitch/uix.dom  {:mvn/version \"" tmpl-dom "\"}"))
          (str "entry-namespace.md's com.pitch/uix.dom pin diverged from "
               "the template (template pins " (pr-str tmpl-dom) ")."))
      (is (= tmpl-core tmpl-dom)
          (str "Template uix.core (" tmpl-core ") and uix.dom (" tmpl-dom
               ") pins are no longer equal — UIx ships core+dom in "
               "lockstep; update the skill's paired pins accordingly.")))))

(deftest helix-skill-pin-matches-template
  (testing "entry-namespace.md Helix pin matches the template _helix/deps.edn"
    (let [tmpl-helix (mvn-version @helix-template-deps "lilactown/helix")
          skill      @entry-namespace-md]
      (is (some? tmpl-helix)
          "Could not read lilactown/helix pin from the template _helix/deps.edn.")
      (is (str/includes? skill (str "lilactown/helix      {:mvn/version \"" tmpl-helix "\"}"))
          (str "entry-namespace.md's lilactown/helix pin diverged from the "
               "template (template pins " (pr-str tmpl-helix) "). The manual "
               "Helix setup path must generate the same known-good dep as "
               "the generator template (rf2-0qkyn).")))))

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
;; Lock 3 — Xray layout host snippet matches the CURRENT published contract
;; (right-side host, --rf-xray-inline-width-driven width). rf2-w4axt.
;;
;; The setup recipe scaffolds the `[data-rf-xray-host]` layout host. The
;; published contract (tools/xray/spec/011-Launch-Modes.md §Layout host
;; contract + the shipped examples/_shared/css) is a RIGHT-side host whose
;; flex-basis reads `var(--rf-xray-inline-width, 560px)`. A prior revision
;; scaffolded a LEFT-side host with a literal `flex: 0 0 420px`, which (a)
;; put the panel on the wrong side and (b) ignored the persisted resize
;; width Xray's drag handle writes. These guards fail if that stale shape
;; (or the literal `420px`) reappears in the setup guidance.
;; ---------------------------------------------------------------------------

(deftest xray-host-snippet-consumes-inline-width-var
  (testing "the shadow-cljs.md host CSS reads --rf-xray-inline-width, not a literal width"
    (let [body @shadow-cljs-md]
      (is (str/includes? body "var(--rf-xray-inline-width, 560px)")
          (str "shadow-cljs.md's [data-rf-xray-host] CSS no longer reads "
               "`flex: 0 0 var(--rf-xray-inline-width, 560px)`. The host "
               "width MUST come from the published custom property "
               "(day8.re-frame2-xray.config/default-layout-host-css-var, "
               "default 560px per spec/011-Launch-Modes.md §Resizing the "
               "inline host) so the drag handle's persisted width and any "
               "cascade override take effect — a literal flex-basis ignores "
               "them (rf2-w4axt)."))
      (is (str/includes? body "box-sizing: border-box")
          (str "shadow-cljs.md's host CSS lost `box-sizing: border-box` — "
               "without it the 1px border-left renders the host 1px wider "
               "than the documented var(...) width (rf2-w4axt)."))
      (is (str/includes? body "border-left")
          (str "shadow-cljs.md's host CSS lost the `border-left` separator "
               "from the published contract (rf2-w4axt).")))))

(deftest xray-host-snippet-has-no-stale-420px-or-left-wording
  (testing "neither the host CSS nor the SKILL summary carry the stale 420px / left-column shape"
    (doseq [[label body] [["shadow-cljs.md" @shadow-cljs-md]
                          ["SKILL.md"       @skill-md]]]
      (is (not (str/includes? body "420px"))
          (str label " still mentions the stale literal `420px` host width. "
               "The host width is `var(--rf-xray-inline-width, 560px)` per "
               "the current Xray contract (rf2-w4axt)."))
      (is (not (contains-any? body ["left layout column"
                                    "left** layout column"
                                    "Xray on the left"
                                    "places Xray on the left"]))
          (str label " describes the Xray host as a LEFT column. The current "
               "contract is a RIGHT-side host (<main> first, <aside "
               "data-rf-xray-host> second) per spec/011-Launch-Modes.md "
               "(rf2-w4axt).")))))

(deftest xray-host-dom-order-is-main-then-aside
  (testing "shadow-cljs.md index.html orders <main> before the <aside> host (right-side flow)"
    (let [body @shadow-cljs-md
          main-idx  (str/index-of body "<main id=\"app\">")
          aside-idx (str/index-of body "data-rf-xray-host")]
      (is (and (some? main-idx) (some? aside-idx))
          "Could not find both <main id=\"app\"> and data-rf-xray-host in shadow-cljs.md.")
      (is (and main-idx aside-idx (< main-idx aside-idx))
          (str "shadow-cljs.md's index.html does not place <main id=\"app\"> "
               "BEFORE the <aside data-rf-xray-host>. Flex flow lays the "
               "aside to the RIGHT only when <main> comes first "
               "(spec/011-Launch-Modes.md §Layout host contract, rf2-w4axt).")))))

;; ---------------------------------------------------------------------------
;; Lock 4 — the reagent/dom CLJS-namespace troubleshooting row diagnoses the
;; Maven/classpath side, NOT npm React. rf2-w4axt.
;;
;; `reagent.dom.client` ships from the `reagent/reagent` Maven coordinate on
;; the CLJS classpath; a missing npm React surfaces as a JS module error, not
;; a missing .cljs namespace. A prior row told the author to `npm install
;; react react-dom` to fix a missing CLJS namespace — wrong layer. This guard
;; fails if the reagent/dom row is mapped back to npm-only recovery.
;; ---------------------------------------------------------------------------

(deftest reagent-dom-row-diagnoses-maven-not-npm
  (testing "SKILL.md's reagent/dom/client.cljs row points at the Maven/classpath cause"
    (let [body @skill-md
          ;; The reagent/dom/client.cljs troubleshooting bullet, isolated to
          ;; one paragraph so we don't catch the separate npm-React row.
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
      ;; And the distinct npm-React row must still exist for actual JS failures.
      (is (re-find #"Cannot find module 'react'" body)
          (str "SKILL.md lost the separate npm-React troubleshooting row "
               "(`Cannot find module 'react'` / react-dom/client). Real JS "
               "module-resolution failures need their own row pointing at "
               "`npm install` (rf2-w4axt).")))))

;; ---------------------------------------------------------------------------
;; Lock 5 — the dev CSP guidance matches the generator template's index.html.
;; rf2-pxl6l.
;;
;; The template's root/resources/public/index.html ships a DEV-flavoured CSP
;; meta tag tuned to the runtime the scaffold produces:
;;   - `style-src 'self' 'unsafe-inline'` — generated views use inline `:style`
;;     props and the default-on Xray devtools injects <style>/inline styles, so
;;     a strict `style-src 'self'` would emit violations + break Xray on first
;;     run.
;;   - NO meta `frame-ancestors` — browsers IGNORE it from a <meta> tag; it is a
;;     response-header-only directive, so it belongs in the production header.
;; A prior revision documented a strict dev CSP (`style-src 'self'`, meta
;; `frame-ancestors`), which diverged from the tested template and could cause
;; first-run CSP violations / broken Xray styling. These guards fail if that
;; stale strict-dev shape (or a meta `frame-ancestors`) reappears, and require
;; the dev/prod split (a documented stricter production RESPONSE HEADER that
;; adds `frame-ancestors`) to stay present.
;; ---------------------------------------------------------------------------

(defn- meta-csp-content
  "Extract the content=\"...\" of the Content-Security-Policy <meta> tag, or nil."
  [body]
  (some-> (re-find #"(?s)<meta http-equiv=\"Content-Security-Policy\"\s+content=\"([^\"]+)\"" body)
          second))

(deftest dev-csp-allows-unsafe-inline-styles
  (testing "shadow-cljs.md's index.html meta CSP allows 'unsafe-inline' styles (matches the template)"
    (let [meta-csp (meta-csp-content @shadow-cljs-md)]
      (is (some? meta-csp)
          "Could not find the Content-Security-Policy <meta> tag in shadow-cljs.md.")
      (is (and meta-csp (str/includes? meta-csp "style-src 'self' 'unsafe-inline'"))
          (str "shadow-cljs.md's CSP meta tag no longer allows "
               "`style-src 'self' 'unsafe-inline'`. The generated views use "
               "inline `:style` props and the default-on Xray devtools injects "
               "<style>/inline styles — a strict `style-src 'self'` emits CSP "
               "violations and breaks Xray styling on first run. The dev meta "
               "tag must match the template's "
               "root/resources/public/index.html (rf2-pxl6l)."))
      (is (and meta-csp (not (re-find #"style-src 'self';" meta-csp)))
          (str "shadow-cljs.md's CSP meta tag still carries a STRICT "
               "`style-src 'self';` (no 'unsafe-inline') for the DEV policy — "
               "that diverges from the template and breaks the first page / "
               "Xray. Strict `style-src 'self'` belongs only in the PRODUCTION "
               "response header (rf2-pxl6l).")))))

(deftest dev-csp-meta-omits-frame-ancestors
  (testing "shadow-cljs.md's index.html meta CSP omits frame-ancestors (meta-tag-ignored directive)"
    ;; Isolate the <meta http-equiv="Content-Security-Policy" ...> block content
    ;; so we test the META tag specifically, not the prose / production-header
    ;; guidance (which legitimately mentions frame-ancestors).
    (let [meta-csp (meta-csp-content @shadow-cljs-md)]
      (is (some? meta-csp)
          "Could not find the Content-Security-Policy <meta> tag in shadow-cljs.md.")
      (is (and meta-csp (not (str/includes? meta-csp "frame-ancestors")))
          (str "shadow-cljs.md's CSP <meta> tag declares `frame-ancestors`. "
               "Browsers IGNORE `frame-ancestors` from a <meta> tag — it only "
               "takes effect from a response header, so putting it in the meta "
               "tag implies anti-clickjacking protection the page does not "
               "have. It belongs in the production response header "
               "(rf2-pxl6l).")))))

(deftest csp-documents-prod-response-header-with-frame-ancestors
  (testing "shadow-cljs.md documents a stricter production response header that adds frame-ancestors"
    (let [body @shadow-cljs-md]
      (is (contains-any? body ["response header" "Production hardening"])
          (str "shadow-cljs.md no longer documents serving CSP as a production "
               "RESPONSE HEADER. The dev meta tag is not the production policy; "
               "the skill must give the dev/prod split (rf2-pxl6l)."))
      (is (str/includes? body "frame-ancestors 'none'")
          (str "shadow-cljs.md's production CSP guidance no longer adds "
               "`frame-ancestors 'none'`. That directive is response-header-"
               "only and is where anti-clickjacking actually takes effect — it "
               "must appear in the production header guidance (rf2-pxl6l).")))))

;; ---------------------------------------------------------------------------
;; Lock 6 — user-facing direct-run shadow-cljs commands are qualified with npx.
;; rf2-pxl6l.
;;
;; A fresh project's only shadow-cljs is a LOCAL npm devDependency; bare
;; `shadow-cljs watch app` hits `command not found` when no global binary is on
;; PATH (common on Windows/PowerShell). Direct-run commands in the user-facing
;; prose must use `npx shadow-cljs ...` (or `npm run ...`). Bare `shadow-cljs`
;; is fine ONLY inside a package.json "scripts" block (npm puts node_modules/.bin
;; on PATH there). This guard fails if an unqualified direct-run command
;; reappears outside a scripts context.
;; ---------------------------------------------------------------------------

(deftest first-counter-verify-command-uses-npx
  (testing "first-counter.md's verification command qualifies shadow-cljs with npx"
    (let [fc (slurp-rel setup-root "references/first-counter.md")]
      (is (str/includes? fc "npx shadow-cljs watch app")
          (str "first-counter.md's verify step no longer runs "
               "`npx shadow-cljs watch app`. A fresh project's shadow-cljs is "
               "a local devDependency — bare `shadow-cljs` is not on PATH "
               "(esp. Windows/PowerShell) (rf2-pxl6l).")))))

;; ---------------------------------------------------------------------------
;; Lock 7 — the UIx/Helix manual path supplies substrate-specific VIEW code and
;; does NOT route UIx/Helix authors to the Reagent `reg-view` first-counter.
;; rf2-74uffk.
;;
;; The Reagent first-counter leaf uses `reg-view` (auto-injected
;; dispatch/subscribe) — a Reagent-only construct. UIx/Helix have no
;; auto-injection: they read subs via the adapter `use-subscribe` hook and
;; dispatch via `(:dispatch (rf/frame-handle))`. A prior revision only supplied
;; deps/entry-root substitutions for UIx/Helix, leaving an author to combine a
;; UIx/Helix entry ns with the Reagent `reg-view` counter — a non-compiling
;; scaffold. These guards require the substrate VIEW snippets to be present in
;; entry-namespace.md (matching the template's _uix/_helix/views.cljs) and
;; require SKILL.md + first-counter.md to steer UIx/Helix away from `reg-view`.
;; ---------------------------------------------------------------------------

(deftest uix-helix-greenfield-supplies-substrate-views
  (testing "entry-namespace.md gives UIx/Helix view code (defui/defnc + use-subscribe), not just deps/entry"
    (let [body @entry-namespace-md]
      (is (and (str/includes? body "defui counter-buttons")
               (str/includes? body "defnc counter-buttons"))
          (str "entry-namespace.md no longer supplies BOTH the UIx (`defui`) "
               "and Helix (`defnc`) counter view snippets. UIx/Helix have no "
               "auto-injection — the manual path must ship the substrate "
               "`views.cljs`, not send the author to the Reagent `reg-view` "
               "leaf (rf2-74uffk)."))
      (is (and (str/includes? body "uix-adapter/use-subscribe")
               (str/includes? body "helix-adapter/use-subscribe"))
          (str "The UIx/Helix view snippets must read subscriptions through "
               "the adapter `use-subscribe` hook (no auto-injected `subscribe` "
               "on these substrates) (rf2-74uffk)."))
      (is (str/includes? body "(:dispatch (rf/frame-handle))")
          (str "The UIx/Helix view snippets must dispatch via "
               "`(:dispatch (rf/frame-handle))` — there is no auto-injected "
               "`dispatch` on these substrates (rf2-74uffk)."))
      ;; The "everything else is identical" claim must NOT swallow views.
      (is (not (re-find #"(?i)views.{0,40}identical across substrates" body))
          (str "entry-namespace.md claims views are identical across "
               "substrates — they are NOT. Reagent uses `reg-view`; UIx/Helix "
               "use `defui`/`defnc` with `use-subscribe`. The 'everything else "
               "identical' claim must exclude views (rf2-74uffk).")))))

(deftest uix-helix-not-routed-to-reagent-reg-view-counter
  (testing "SKILL.md + first-counter.md steer UIx/Helix away from the Reagent reg-view leaf"
    (let [skill @skill-md
          fc    (slurp-rel setup-root "references/first-counter.md")]
      ;; SKILL step 6 must flag the leaf as Reagent-specific and point
      ;; UIx/Helix at use-subscribe / the substrate views.
      (is (contains-any? skill ["UIx / Helix do NOT use `reg-view`"
                                "UIx / Helix** do NOT use `reg-view`"
                                "UIx and Helix do NOT use `reg-view`"
                                "UIx / Helix** do not use `reg-view`"
                                "do NOT use `reg-view`"])
          (str "SKILL.md step 6 no longer warns that UIx/Helix do not use the "
               "Reagent `reg-view` counter. A UIx/Helix author must be routed "
               "to the substrate views, not the Reagent leaf (rf2-74uffk)."))
      ;; first-counter.md must declare itself Reagent-only and redirect.
      (is (contains-any? fc ["Reagent only" "Reagent-only"])
          (str "first-counter.md no longer flags itself as Reagent-only. The "
               "leaf uses `reg-view` + `reagent.dom.client` — UIx/Helix must "
               "be redirected to the substrate views (rf2-74uffk)."))
      (is (and (str/includes? fc "use-subscribe")
               (str/includes? fc "entry-namespace.md"))
          (str "first-counter.md no longer redirects UIx/Helix authors to the "
               "`use-subscribe`/substrate path in entry-namespace.md "
               "(rf2-74uffk).")))))

;; ---------------------------------------------------------------------------
;; Lock 8 — the JS-module React recovery uses the PINNED baseline, not bare
;; `npm install react react-dom` (which writes latest-from-npm). rf2-74uffk.
;;
;; The skill's default path copies the React/ReactDOM versions from the pinned
;; `implementation/package.json` (deps-versions.md §package.json) and only takes
;; latest-from-npm on explicit opt-in. A prior revision's JS-module
;; troubleshooting row advised bare `npm install react react-dom`, which writes
;; npm's current `latest` into package.json — breaking reproducibility and
;; risking a React/Reagent mismatch. This guard fails if that bare command
;; reappears in the JS-module row and requires the pinned-baseline recovery.
;; ---------------------------------------------------------------------------

(deftest js-module-react-row-uses-pinned-baseline
  (testing "SKILL.md's JS-module React row recovers on the pinned baseline, not bare npm install"
    (let [body @skill-md
          row  (some-> (re-find #"(?m)^- \*\*`Cannot find module 'react'`.*$" body))]
      (is (some? row)
          "Could not find the `Cannot find module 'react'` troubleshooting row in SKILL.md.")
      ;; The row must positively recover from the pinned baseline...
      (is (and row (str/includes? row "pinned"))
          (str "The JS-module React row no longer recovers from the PINNED "
               "baseline. The fix is to restore react/react-dom in "
               "package.json from the pinned `implementation/package.json`, "
               "then plain `npm install` — not latest-from-npm (rf2-74uffk)."))
      (is (and row (contains-any? row ["reproducibility" "cardinal rule 1"]))
          (str "The JS-module React row no longer cites the reproducibility / "
               "cardinal-rule-1 reason for avoiding latest-from-npm "
               "(rf2-74uffk)."))
      ;; ...and must NOT POSITIVELY advise the bare unpinned command. The
      ;; corrected row may mention it only to FORBID it ("Don't run bare ...");
      ;; a positive recommendation would lack that negation cue.
      (is (and row (str/includes? row "Don't run bare `npm install react react-dom`"))
          (str "The JS-module React row no longer explicitly FORBIDS bare "
               "`npm install react react-dom` (writes npm `latest`, breaks "
               "reproducibility). If the bare command appears, it must be "
               "framed as the thing NOT to do (rf2-74uffk).")))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'setup-drift-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
