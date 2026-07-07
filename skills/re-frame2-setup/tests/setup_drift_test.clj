;;;; tests/setup_drift_test.clj — structural regression for the
;;;; re-frame2-setup skill's correctness-critical contract claims.
;;;;
;;;; Guards two contract claims the setup skill must keep correct:
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
;;;; Run locally:  bb tests/setup_drift_test.clj   (from skills/re-frame2-setup/)
;;;; Exit:         0 = pass, non-zero = fail.
;;;;
;;;; CI: gated by the `skills-structural` job in .github/workflows/test.yml,
;;;; which loops `skills/re-frame2-setup/tests/*_test.clj`. The job fires when
;;;; `report-changed-surfaces.sh` classifies a `skills/re-frame2-setup/**`
;;;; change as `skills_structural=true`. So the prose-drift Locks 1–12 below
;;;; are guarded in CI, not just locally.
;;;;
;;;; What this suite does NOT cover: it is a PROSE-DRIFT / structural guard, not
;;;; a buildability smoke. It does not materialise the scaffold and run
;;;; `npm install` + `npx shadow-cljs compile app` — that heavier
;;;; generated-project smoke is deferred (pre-publish, the framework coords
;;;; resolve only against a reviewed monorepo checkout, so a full end-to-end
;;;; build isn't a cheap per-PR gate yet). Run `npm run test:cljs` / the substrate
;;;; contract tests for real-regression coverage of the wiring this skill
;;;; teaches.
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

(def ^:private first-counter-md
  (delay (slurp-rel setup-root "references/first-counter.md")))

(def ^:private reagent-template-core
  (delay (slurp-rel repo-root
                    "tools/template/resources/day8/re_frame2_template/_reagent/core.cljs")))

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
;; (right-side host, --rf-xray-inline-width-driven width).
;;
;; The setup recipe scaffolds the `[data-rf-xray-host]` layout host. The
;; published contract (tools/xray/spec/011-Launch-Modes.md §Layout host
;; contract + the shipped examples/_shared/css) is a RIGHT-side host whose
;; flex-basis reads `var(--rf-xray-inline-width, 560px)`. A LEFT-side host
;; with a literal `flex: 0 0 420px` would (a) put the panel on the wrong
;; side and (b) ignore the persisted resize width Xray's drag handle writes.
;; These guards fail if that wrong shape (or the literal `420px`) appears in
;; the setup guidance.
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
    ;; Compare the actual HTML TAGS, not any prose mention of `data-rf-xray-host`
    ;; (the canonical-block intro + the opt-out prose both name the host
    ;; attribute before the index.html block, so a whole-body first-occurrence
    ;; search would false-fail — guard against the markup, not the prose).
    (let [body      @shadow-cljs-md
          main-idx  (str/index-of body "<main id=\"app\">")
          ;; the <aside ...> opening tag that carries data-rf-xray-host
          aside-idx (some-> (re-find #"<aside[^>]*data-rf-xray-host" body)
                            (->> (str/index-of body)))]
      (is (and (some? main-idx) (some? aside-idx))
          "Could not find both <main id=\"app\"> and the <aside ... data-rf-xray-host> tag in shadow-cljs.md.")
      (is (and main-idx aside-idx (< main-idx aside-idx))
          (str "shadow-cljs.md's index.html does not place <main id=\"app\"> "
               "BEFORE the <aside data-rf-xray-host>. Flex flow lays the "
               "aside to the RIGHT only when <main> comes first "
               "(spec/011-Launch-Modes.md §Layout host contract, rf2-w4axt).")))))

;; ---------------------------------------------------------------------------
;; Lock 4 — the reagent/dom CLJS-namespace troubleshooting row diagnoses the
;; Maven/classpath side, NOT npm React.
;;
;; `reagent.dom.client` ships from the `reagent/reagent` Maven coordinate on
;; the CLJS classpath; a missing npm React surfaces as a JS module error, not
;; a missing .cljs namespace. Telling the author to `npm install react
;; react-dom` to fix a missing CLJS namespace is the wrong layer. This guard
;; fails if the reagent/dom row is mapped to npm-only recovery.
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
;;
;; The template's root/resources/public/index.html ships a DEV-flavoured CSP
;; meta tag tuned to the runtime the scaffold produces:
;;   - `style-src 'self' 'unsafe-inline'` — generated views use inline `:style`
;;     props and the default-on Xray devtools injects <style>/inline styles, so
;;     a strict `style-src 'self'` would emit violations + break Xray on first
;;     run.
;;   - NO meta `frame-ancestors` — browsers IGNORE it from a <meta> tag; it is a
;;     response-header-only directive, so it belongs in the production header.
;; A strict dev CSP (`style-src 'self'`, meta `frame-ancestors`) would
;; diverge from the tested template and cause first-run CSP violations /
;; broken Xray styling. These guards fail if that strict-dev shape (or a meta
;; `frame-ancestors`) appears, and require the dev/prod split (a documented
;; stricter production RESPONSE HEADER that adds `frame-ancestors`) to stay
;; present.
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
;;
;; The Reagent first-counter leaf uses `reg-view` (auto-injected
;; dispatch/subscribe) — a Reagent-only construct. UIx/Helix have no
;; auto-injection: they read subs via the adapter `use-subscribe` hook and
;; dispatch via `(:dispatch (rf/capture-frame))`. Supplying only
;; deps/entry-root substitutions for UIx/Helix would leave an author to
;; combine a UIx/Helix entry ns with the Reagent `reg-view` counter — a
;; non-compiling scaffold. These guards require the substrate VIEW snippets to
;; be present in
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
      (is (str/includes? body "(:dispatch (rf/capture-frame))")
          (str "The UIx/Helix view snippets must dispatch via "
               "`(:dispatch (rf/capture-frame))` — there is no auto-injected "
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
;; `npm install react react-dom` (which writes latest-from-npm).
;;
;; The skill's default path copies the React/ReactDOM versions from the pinned
;; `implementation/package.json` (deps-versions.md §package.json) and only takes
;; latest-from-npm on explicit opt-in. Bare `npm install react react-dom` would
;; write npm's current `latest` into package.json — breaking reproducibility
;; and risking a React/Reagent mismatch. This guard fails if that bare command
;; appears in the JS-module row and requires the pinned-baseline recovery.
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
;; Lock 9 — the greenfield framework coordinate branches on PUBLICATION STATE.
;;
;; re-frame2 is NOT on Clojars/npm yet (README §Status: "not yet published …
;; add as a :git/sha coordinate"). A `day8/re-frame2* {:mvn/version "<VERSION>"}`
;; framework coord 404s on Clojars and a greenfield project that writes it fails
;; dependency resolution before it ever compiles. So the manual setup path's
;; deps guidance MUST branch on publication state: pre-publish uses :git/sha /
;; :local/root for the framework artefacts; :mvn/version is the labelled
;; post-publish destination only. These guards fail if the deps guidance reverts
;; to teaching an unconditional `:mvn/version "<VERSION>"` framework coord.
;; ---------------------------------------------------------------------------

(deftest deps-guidance-uses-git-sha-pre-publish
  (testing "deps-versions.md teaches the pre-publish :git/sha framework coord, not an unconditional :mvn/version"
    (let [body @deps-versions-md]
      (is (contains-any? body ["not published" "NOT on Clojars" "not on Clojars"
                               "have not published" "not yet published"])
          (str "deps-versions.md no longer states re-frame2 is unpublished. "
               "The README §Status says artifacts are not on Clojars/NPM; the "
               "greenfield deps guidance must say so before it can branch the "
               "coordinate shape (rf2-ol8l7a)."))
      (is (str/includes? body ":git/sha")
          (str "deps-versions.md no longer gives the pre-publish `:git/sha` "
               "framework coordinate. Before the first Clojars release, the "
               "ONLY working manual route is :git/url + :git/sha (or "
               ":local/root) for each day8/re-frame2* artefact — not "
               "`:mvn/version`, which 404s today (rf2-ol8l7a)."))
      (is (contains-any? body ["Clojars" "resolve"])
          (str "deps-versions.md no longer points version discovery at whether "
               "the coordinate actually RESOLVES on Clojars. The repo VERSION "
               "string is the next-release tag, not a published-Maven-version "
               "guarantee (rf2-ol8l7a)."))
      ;; The :mvn/version framework shape may appear ONLY as a labelled
      ;; post-publish destination, never as the today's-route recommendation.
      (is (contains-any? body ["After publication" "Post-publish" "post-publish"
                               "AFTER PUBLICATION"])
          (str "deps-versions.md no longer labels the `:mvn/version` framework "
               "shape as the POST-PUBLISH path. Unqualified `:mvn/version "
               "\"<VERSION>\"` for day8/re-frame2* coords is a false-green "
               "setup (404 on Clojars); it must be clearly gated as the "
               "after-publication destination (rf2-ol8l7a).")))))

;; ---------------------------------------------------------------------------
;; Lock 10 — the schema-missing contract is a LOUD error, not a CLJS soft-pass.
;;
;; Core routes reg-app-schema / reg-app-schemas through :on-absent :throw —
;; without `day8/re-frame2-schemas` they throw
;; :rf.error/schemas-artefact-missing (implementation/core/.../core_schemas.cljc).
;; Requiring `re-frame.schemas` wires Malli automatically, so a registered
;; schema validates (Spec 010 §Schema implies validation on CLJS). Teaching
;; that missing the schemas artefact "soft-passes" on the normal setup path is
;; false: it throws, and a present artefact validates. These guards fail if the
;; soft-pass-on-the-setup-path framing appears and require the loud-error /
;; Malli-wired contract to be stated.
;; ---------------------------------------------------------------------------

(deftest schema-missing-artefact-is-loud-not-softpass
  (testing "deps-versions.md teaches the loud :rf.error/schemas-artefact-missing contract, not a setup-path soft-pass"
    (let [body @deps-versions-md]
      (is (str/includes? body ":rf.error/schemas-artefact-missing")
          (str "deps-versions.md no longer names the loud "
               "`:rf.error/schemas-artefact-missing` thrown when an app calls "
               "reg-app-schema without day8/re-frame2-schemas. Current core "
               "routes the registration through :on-absent :throw — it does NOT "
               "silently soft-pass on the setup path (rf2-ol8l7a)."))
      (is (str/includes? body "re-frame.schemas")
          (str "deps-versions.md no longer says you must `:require` "
               "`re-frame.schemas` before reg-app-schema. Requiring it wires "
               "Malli automatically so a registered schema validates (Spec 010 "
               "§Schema implies validation on CLJS) (rf2-ol8l7a)."))
      ;; The soft-pass word may survive ONLY adjacent to a negation / the
      ;; substitute-validator carve-out — never as the plain missing-artefact
      ;; greenfield behaviour. Assert the missing-artefact sentence is NOT
      ;; framed as a soft-pass.
      (is (not (re-find #"(?i)without (it|the artefact|day8/re-frame2-schemas)[^.\n]{0,80}soft.?pass"
                        body))
          (str "deps-versions.md still says missing the schemas artefact "
               "CLJS soft-passes on the setup path. The current contract is a "
               "loud throw (:rf.error/schemas-artefact-missing); soft-pass "
               "language is reserved for substitute-validator / explicit "
               "default-absent contexts per Spec 010 (rf2-ol8l7a)."))))
  (testing "first-counter.md frames 'no schema errors' as nothing-registered, not a soft-pass"
    (let [fc (slurp-rel setup-root "references/first-counter.md")]
      (is (not (re-find #"(?i)there'?s nothing to validate[^.\n]*soft.?passe?s?" fc))
          (str "first-counter.md still says the counter 'soft-passes' because "
               "there's nothing to validate. The correct framing: no schema is "
               "registered, so there is no validation WORK — not a soft-pass "
               "(a registered schema DOES validate once re-frame.schemas is "
               "required) (rf2-ol8l7a).")))))

;; ---------------------------------------------------------------------------
;; Lock 11 — the FIRST canonical shadow-cljs.edn block carries the day-one Xray
;; preload.
;;
;; Xray is a day-one dep (deps-versions.md) and index.html ships the
;; [data-rf-xray-host] column; the SKILL.md day-one shape + done-checklist both
;; require `:devtools/preloads [day8.re-frame2-xray.preload]`. Presenting the
;; FIRST copyable shadow-cljs.edn block WITHOUT the preload (arriving only in a
;; later ":devtools (optional, for hot-reload)" section) would make the most
;; obvious block to copy produce a compiling app whose right-side host stays
;; empty: a false-green scaffold (the dep + host markup are present but nothing
;; loads Xray). This guard fails if the day-one block drops the preload while
;; the deps/checklist still require Xray, or if the section frames the preload
;; as optional hot-reload material.
;; ---------------------------------------------------------------------------

(defn- first-shadow-build-block
  "The FIRST ```clojure fenced block in shadow-cljs.md that contains a
   `:builds` map — i.e. the canonical day-one build the author copies first.
   CRLF-tolerant (the reference files use Windows line endings)."
  [body]
  (some (fn [block] (when (str/includes? block ":builds") block))
        (map second (re-seq #"(?s)```clojure\r?\n(.*?)```" body))))

(deftest day-one-shadow-build-includes-xray-preload
  (testing "the first canonical shadow-cljs.edn build block wires the day-one Xray preload"
    (let [block (first-shadow-build-block @shadow-cljs-md)]
      (is (some? block)
          "Could not find the first `:builds` shadow-cljs.edn block in shadow-cljs.md.")
      (is (and block (str/includes? block "day8.re-frame2-xray.preload"))
          (str "The FIRST canonical shadow-cljs.edn build block omits the "
               "day-one `[day8.re-frame2-xray.preload]`. Xray is a day-one dep "
               "and index.html ships the [data-rf-xray-host] column — the most "
               "obvious block to copy must wire the preload that fills it, or "
               "the scaffold compiles with a permanently-empty Xray host "
               "(false-green) (rf2-agi57x)."))
      (is (and block (str/includes? block ":devtools"))
          (str "The first shadow-cljs.edn build block no longer carries the "
               "`:devtools` map. The day-one preload lives under `:devtools "
               "{:preloads [...]}` in the canonical block (rf2-agi57x)."))))
  (testing "the day-one deps/checklist that REQUIRE Xray are still present (guard premise)"
    (let [skill @skill-md]
      ;; The guard above is only meaningful while the day-one shape still
      ;; mandates Xray. If a future edit drops Xray from day-one entirely, this
      ;; sub-test fails LOUDLY so the preload guard is revisited deliberately
      ;; rather than silently mismatching the (now Xray-free) day-one shape.
      (is (str/includes? skill ":devtools/preloads [day8.re-frame2-xray.preload]")
          (str "SKILL.md no longer requires `:devtools/preloads "
               "[day8.re-frame2-xray.preload]` in the day-one shape/checklist. "
               "If Xray was deliberately dropped from day-one, revisit Lock 11 "
               "(the preload-in-default-block guard) — it assumes Xray is "
               "day-one (rf2-agi57x).")))))

(deftest shadow-devtools-section-not-labelled-optional-hot-reload
  (testing "the :devtools section header no longer frames the Xray preload as optional hot-reload material"
    (let [body @shadow-cljs-md]
      ;; A ":devtools block (optional, for hot-reload)" TOC/section header
      ;; would imply the Xray preload is opt-in hot-reload tooling. It is the
      ;; day-one default — not optional hot-reload material. (For the :browser
      ;; target the module :init-fn re-runs after each hot reload by default,
      ;; so no separate ^:dev/after-load hook is needed at all.)
      (is (not (str/includes? body "optional, for hot-reload"))
          (str "shadow-cljs.md still labels the `:devtools` section "
               "\"optional, for hot-reload\". The Xray preload is the day-one "
               "default, not optional hot-reload material. Reframe the section "
               "header (rf2-agi57x).")))))

;; ---------------------------------------------------------------------------
;; Lock 12 — the generator route is USER-RUN; the skill's allowed-tools do NOT
;; grant `clojure -Tnew create`, and the UIx/Helix greenfield route the skill
;; EXECUTES is the manual scaffold.
;;
;; The skill documents the one-command `clojure -Tnew create …` generator as a
;; complete alternative, but its `allowed-tools` front-matter grants only
;; `clojure -Stree`, npm, and `shadow-cljs watch/compile` — NOT `-Tnew`. So the
;; skill must frame the generator as something the AUTHOR runs, while the route
;; the skill itself executes (esp. for UIx/Helix) is the manual scaffold whose
;; commands the grant actually covers. Steering UIx/Helix users toward the
;; generator as "the fastest/complete path" without flagging that the loaded
;; skill cannot run it weakens prompt ergonomics (the agent recommends a route
;; it must then abandon or interrupt for extra tool access). These guards fail
;; if the front-matter starts advertising `-Tnew`, or if the prose stops
;; framing the generator as user-run / stops giving UIx/Helix an executable
;; manual route.
;; ---------------------------------------------------------------------------

(deftest allowed-tools-do-not-grant-generator-command
  (testing "SKILL.md allowed-tools front-matter does not grant `clojure -Tnew create`"
    (let [skill @skill-md
          ;; Isolate the YAML front-matter (between the first two `---` fences)
          ;; so we test the actual tool GRANT, not the prose that legitimately
          ;; shows the user-run generator command.
          fm    (some-> (re-find #"(?s)^---\r?\n(.*?)\r?\n---" skill) second)]
      (is (some? fm)
          "Could not isolate the SKILL.md YAML front-matter (allowed-tools block).")
      (is (and fm (not (str/includes? fm "-Tnew")))
          (str "SKILL.md's allowed-tools front-matter now grants a `-Tnew` "
               "command. The skill's executable path is the manual scaffold; "
               "the generator is a USER-RUN route. If a deliberate decision "
               "grants the generator command to the skill, update this guard "
               "(rf2-agi57x).")))))

(deftest generator-framed-as-user-run-not-skill-executed
  (testing "SKILL.md cardinal rule frames the generator as user-run, not skill-executed"
    (let [skill @skill-md]
      (is (contains-any? skill ["USER-RUN route" "user-run route" "the author runs"
                                "author runs the" "hand them the generator"
                                "the **author** runs"])
          (str "SKILL.md no longer frames the generator template as a USER-RUN "
               "route. The skill's allowed-tools cannot execute `clojure -Tnew "
               "create`; the prose must hand the author the command to run "
               "rather than imply the skill runs it (rf2-agi57x)."))
      ;; And the UIx/Helix path the skill EXECUTES must be the manual substrate
      ;; views (already locked by Lock 7) — re-assert the executable framing.
      (is (contains-any? skill ["manual seven-step" "manual scaffold"])
          (str "SKILL.md no longer names the manual seven-step scaffold as the "
               "path the skill executes (vs. the user-run generator). The "
               "skill's executable route must be the one its allowed-tools "
               "cover (rf2-agi57x)."))))
  (testing "entry-namespace.md frames the generator as user-run / skill-doesn't-run-Tnew"
    (let [body @entry-namespace-md]
      (is (contains-any? body ["user-run" "the **author** invokes" "the author invokes"
                               "does not run `clojure -Tnew`" "this skill does not run"])
          (str "entry-namespace.md no longer frames the UIx/Helix generator "
               "route as user-run. The skill cannot execute `clojure -Tnew`; "
               "the author runs it (rf2-agi57x).")))))

;; ---------------------------------------------------------------------------
;; Lock 13 — the PUBLIC entry-ramp docs (docs-site setup page + top-level
;; skills index) stay in sync with the current setup/template contract.
;;
;; The authoritative SKILL.md / setup references / template API docs are the
;; source of truth; two USER-FACING discoverability surfaces must not drift
;; from them:
;;
;;   * docs/skills/re-frame2-setup.md must teach the current lockstep count —
;;     ELEVEN publishable framework artefacts, with day8/re-frame2-xray on the
;;     same line (see SKILL.md cardinal rule 2 + README.md) — not a stale "all
;;     ten ship at the same version" count, and must link the reference leaves
;;     to the PLURAL `…/skills/re-frame2-setup/references` GitHub path (a
;;     singular `…/reference` 404s). check_doc_slugs.py can't catch either: it
;;     skips external http(s) URLs and does not validate prose artefact counts.
;;
;;   * skills/README.md must caveat the one-shot generator: the published
;;     `io.github.day8/re-frame2-template` `-Tnew create` form can't resolve
;;     pre-split (the external repo doesn't exist yet; the working route is
;;     `:local/root`, per tools/template/README.md + the setup skill's own
;;     cardinal rule 5). NOTE: the index was later slimmed to point at
;;     tools/template/README.md rather than inline the full invocation, so the
;;     guard keys off the surviving `tools/template` + `:local/root` mentions.
;;
;; These guards read the two public files off disk (no network) and fail if
;; any of the three drifts appears while check_doc_slugs.py stays green.
;; ---------------------------------------------------------------------------

(def ^:private docs-setup-page-md
  (delay (slurp-rel repo-root "docs/skills/re-frame2-setup.md")))

(def ^:private skills-index-md
  (delay (slurp-rel repo-root "skills/README.md")))

(deftest docs-setup-page-no-stale-artefact-count
  (testing "docs/skills/re-frame2-setup.md does not re-teach the stale 'all ten' lockstep count"
    (let [body @docs-setup-page-md]
      (is (not (re-find #"(?i)all ten" body))
          (str "docs/skills/re-frame2-setup.md re-teaches the stale "
               "\"all ten ship at the same version\" lockstep count. The "
               "current contract is ELEVEN publishable framework artefacts "
               "shipping in lockstep, with day8/re-frame2-xray riding the "
               "same version line (SKILL.md cardinal rule 2 / README.md). "
               "The docs-site page must not drift back to the old count "
               "(rf2-79gtjr)."))
      (is (re-find #"(?i)eleven" body)
          (str "docs/skills/re-frame2-setup.md no longer states the ELEVEN "
               "publishable framework artefacts ship in lockstep. The "
               "corrected lockstep wording must name the current count "
               "consistently with SKILL.md / README.md (rf2-79gtjr).")))))

(deftest docs-setup-page-references-link-is-plural
  (testing "docs/skills/re-frame2-setup.md links the reference leaves to the real plural `references/` path"
    (let [body @docs-setup-page-md]
      ;; The repo directory is `skills/re-frame2-setup/references` (plural);
      ;; a singular `…/reference` GitHub URL 404s. Fail on any singular
      ;; self-repo path that is NOT immediately followed by the plural `s`.
      (is (not (re-find #"skills/re-frame2-setup/reference(?!s)" body))
          (str "docs/skills/re-frame2-setup.md links the reference leaves to "
               "the SINGULAR `skills/re-frame2-setup/reference` GitHub path, "
               "which 404s — the repo directory is `references` (plural). "
               "check_doc_slugs.py skips external http(s) URLs, so this guard "
               "is the only backstop (rf2-79gtjr)."))
      (is (re-find #"skills/re-frame2-setup/references" body)
          (str "docs/skills/re-frame2-setup.md no longer links the reference "
               "leaves to the real plural `skills/re-frame2-setup/references` "
               "path (rf2-79gtjr).")))))

(deftest skills-index-template-form-carries-pre-split-caveat
  (testing "skills/README.md pairs any generator mention with a pre-split caveat + the working :local/root route"
    (let [body @skills-index-md]
      ;; Premise guard: the index must still reference the generator template
      ;; (tools/template). If that reference is gone entirely this lock is moot;
      ;; fail loudly so the caveat requirement is revisited deliberately rather
      ;; than silently passing on an absent string. (The index no longer inlines
      ;; the full `io.github.day8/re-frame2-template -Tnew create` invocation —
      ;; it now points at tools/template/README.md for the working route — so the
      ;; guard keys off the surviving `tools/template` reference instead.)
      (is (str/includes? body "tools/template")
          (str "skills/README.md no longer references the generator template "
               "(tools/template). If it was removed deliberately, revisit "
               "Lock 13 (the pre-split-caveat guard assumes the generator is "
               "mentioned) (rf2-79gtjr)."))
      ;; Any generator mention must carry the pre-split caveat: the published
      ;; :template / io.github coord can't resolve pre-split.
      (is (contains-any? body ["Pre-split" "pre-split" "isn't published yet"
                               "not published yet" "can't resolve"])
          (str "skills/README.md mentions the generator template with NO "
               "pre-split caveat. The published coord can't resolve pre-split "
               "(the external repo doesn't exist yet); the index must say so, "
               "matching tools/template/README.md and the setup skill's "
               "cardinal rule 5 (rf2-79gtjr)."))
      ;; …and must point at the working `:local/root` route — inline, or via a
      ;; pointer to tools/template/README.md — so the caveat isn't a dead end.
      (is (str/includes? body ":local/root")
          (str "skills/README.md gives the pre-split caveat but not the "
               "working `:local/root` route (inline, or via a pointer to "
               "tools/template/README.md). The caveat must be paired with the "
               "route that actually works today (rf2-79gtjr).")))))

;; ---------------------------------------------------------------------------
;; Lock 8 — the manual boot seed matches the generator's reset-boundary
;; contract.
;;
;; The generator template seeds via an explicit `(rf/dispatch-sync
;; [...initialise])` under `(rf/with-frame :rf/default ...)`, NOT via a
;; `reg-frame ... {:initial-events [...]}` seed. This matters for hot reload:
;; `reg-frame` re-registration is a surgical update that preserves app-db and
;; does NOT rerun `:initial-events`, so an `:initial-events`-only seed would not
;; re-seed on save. The manual copyable snippets (first-counter.md + entry-namespace.md)
;; must match the generator's reset-boundary shape so the manual route lands on
;; the same boot/seed behaviour the same skill teaches.
;; ---------------------------------------------------------------------------

(deftest manual-boot-seed-matches-generator-reset-boundary
  (testing "the generator Reagent template still uses the dispatch-sync reset-boundary seed (premise of this lock)"
    (let [tmpl @reagent-template-core]
      (is (and (str/includes? tmpl "(rf/with-frame :rf/default")
               (re-find #"dispatch-sync\s+\[:counter/initialise\]" tmpl))
          (str "the generator Reagent template no longer seeds via "
               "`(rf/with-frame :rf/default ... (rf/dispatch-sync "
               "[:counter/initialise]))`. If the generator boot contract "
               "changed, update this lock AND the manual snippets together "
               "(rf2-xqds87)."))
      (is (not (re-find #"reg-frame[^)]*:initial-events" tmpl))
          (str "the generator Reagent template now seeds via `:initial-events` — "
               "re-check the manual-snippet alignment in this lock "
               "(rf2-xqds87)."))))
  (testing "first-counter.md + entry-namespace.md seed via dispatch-sync under with-frame, not :initial-events"
    (doseq [[label body] [["first-counter.md" @first-counter-md]
                          ["entry-namespace.md" @entry-namespace-md]]]
      ;; `with-frame` opens the seed scope; the seed dispatch-sync follows on a
      ;; later line. A frame-local `(register-schema!)` attach MAY sit between
      ;; them (it shares the same `with-frame` scope — see first-counter.md
      ;; §Schema), so tolerate a few intervening body lines, not exactly one.
      (is (re-find #"with-frame[^\n]*(?:\n[^\n]*){0,4}?dispatch-sync\s+\[:(counter|your-app)/initialise\]"
                   body)
          (str label " no longer seeds the manual counter via "
               "`(rf/with-frame ... (rf/dispatch-sync [...initialise]))`. The "
               "manual boot must match the generator's reset-boundary seed so "
               "hot reload re-seeds demo state (reg-frame re-registration "
               "preserves app-db and does not rerun :initial-events) (rf2-xqds87)."))
      (is (not (re-find #"reg-frame[^)\n]*:initial-events\s+\[\[:(counter|your-app)/initialise\]\]"
                        body))
          (str label " still seeds the manual counter via a `reg-frame ... "
               "{:initial-events [[...initialise]]}` boundary. That only runs on the "
               "first registration; a hot-reload surgical update does not "
               "rerun it, so the demo would not re-seed. Use the "
               "dispatch-sync reset boundary instead (rf2-xqds87).")))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'setup-drift-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
