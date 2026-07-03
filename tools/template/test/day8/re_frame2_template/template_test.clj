(ns day8.re-frame2-template.template-test
  "JVM tests for the deps-new template body (003-DepsNew-Rebuild-Plan.md
   §2.2-2.4).

   Strategy:

     1. Generate a tmp app via `org.corfield.new/create` for each
        substrate (Reagent / UIx / Helix). Driving the deps-new entry
        fn in-process exercises the same `data-fn` / `template-fn` /
        `post-process-fn` pipeline a shell-out `clojure -Tnew create`
        would — without spawning a JVM per substrate.
     2. Walk the generated tree and assert the expected file shape.
     3. Read the generated `deps.edn`, parse it as EDN, and assert the
        expected substrate-adapter coord is present.
     4. Assert the `:include-story?` flag branches:
          - default path emits no story files / coords
          - true on Reagent emits stories.cljs + with-stories core +
            day8/re-frame2-story coord
          - true on non-Reagent substrates throws with a clear message

   Covers the same generated-surface checks across the substrate matrix."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [day8.re-frame2-template.test-support
             :refer [tmp-dir delete-recursively run-template!
                     run-template-opts! read-edn file-exists?]]))

;; --- Test helpers ----------------------------------------------------------
;;
;; tmp-dir / delete-recursively / template-resource-dir / run-template! /
;; read-edn / file-exists? live in the shared `test-support` ns.

;; --- The expected per-substrate shape ------------------------------------

(def ^:private common-files
  ["deps.edn"
   "shadow-cljs.edn"
   "package.json"
   "README.md"
   ".gitignore"
   ;; Dev ergonomics bundle.
   ".editorconfig"
   ".clj-kondo/config.edn"
   ;; Formatter config.
   ".cljfmt.edn"
   ;; Git pre-commit hook config.
   "lefthook.yml"
   ;; Baseline CI workflow.
   ".github/workflows/ci.yml"
   "dev/user.clj"
   "dev/scratch.cljs"
   "resources/public/index.html"
   "resources/public/css/app.css"])

(def ^:private per-substrate-sources
  ;; Generated under src/<nested-dirs>/ and test/<nested-dirs>/. For
  ;; project-name "acme/my-app" deps-new produces nested-dirs
  ;; "acme/my_app".
  ["src/acme/my_app/core.cljs"
   "src/acme/my_app/events.cljs"
   "src/acme/my_app/schema.cljs"
   "src/acme/my_app/subs.cljs"
   "src/acme/my_app/views.cljs"
   "test/acme/my_app/events_test.cljs"])

(def ^:private substrate-coord
  {:reagent 'day8/re-frame2-reagent
   :uix     'day8/re-frame2-uix
   :helix   'day8/re-frame2-helix})

;; --- Tests ---------------------------------------------------------------

(defn- assert-shape!
  "For a given substrate, generate the app inside a tmp dir, walk the
  expected file tree, and assert deps.edn contains the expected coords."
  [substrate]
  (let [tmp (tmp-dir (str "rf2-template-" (name substrate) "-"))]
    (try
      (let [root (run-template! tmp "acme/my-app" substrate)]
        (doseq [p (concat common-files per-substrate-sources)]
          (is (file-exists? root p)
              (str "expected file " p " in generated tree for substrate " substrate)))

        ;; -- deps.edn structure --
        (let [deps (read-edn (io/file root "deps.edn"))]
          (is (map? deps) "deps.edn parses as a map")
          (is (contains? (:deps deps) 'day8/re-frame2)
              "deps.edn references day8/re-frame2 core")
          (is (contains? (:deps deps) (substrate-coord substrate))
              (str "deps.edn references " (substrate-coord substrate)))
          ;; The literal pin VALUE is owned by version_lockstep_test.clj
          ;; (reads repo-root VERSION on disk); asserting a hard-coded
          ;; "0.0.1.alpha" here would duplicate it and false-fail the
          ;; moment VERSION bumps. Present-check only.
          (is (some? (get-in deps [:deps 'day8/re-frame2 :mvn/version]))
              "core coord carries an :mvn/version pin"))

        ;; -- shadow-cljs.edn structure --
        (let [scs  (read-edn (io/file root "shadow-cljs.edn"))
              app  (get-in scs [:builds :app])
              tst  (get-in scs [:builds :test])]
          (is (= :browser (:target app))
              "shadow-cljs :app build targets :browser")
          (is (= 'acme.my-app.core/init
                 (get-in app [:modules :main :init-fn]))
              "init-fn matches generated namespace")
          (is (some #{"test"} (:source-paths scs))
              "shadow-cljs.edn :source-paths includes \"test\" so the emitted test file is discoverable")
          (is (= :node-test (:target tst))
              "shadow-cljs :test build targets :node-test")
          ;; Xray preload.
          (is (some #{'day8.re-frame2-xray.preload}
                    (get-in app [:devtools :preloads]))
              "shadow-cljs :app :devtools/preloads wires Xray"))

        ;; -- Xray coord in deps.edn --
        (let [deps (read-edn (io/file root "deps.edn"))]
          (is (contains? (:deps deps) 'day8/re-frame2-xray)
              "deps.edn references day8/re-frame2-xray")
          ;; Pin value owned by version_lockstep_test.clj.
          (is (some? (get-in deps [:deps 'day8/re-frame2-xray :mvn/version]))
              "Xray coord carries an :mvn/version pin"))

        ;; -- Schemas coord in deps.edn --
        (let [deps (read-edn (io/file root "deps.edn"))]
          (is (contains? (:deps deps) 'day8/re-frame2-schemas)
              "deps.edn references day8/re-frame2-schemas (best-practice
               whole-app-db schema needs the artefact on the classpath
               for CLJS validation to fire)")
          ;; Pin value owned by version_lockstep_test.clj.
          (is (some? (get-in deps [:deps 'day8/re-frame2-schemas :mvn/version]))
              "schemas coord carries an :mvn/version pin"))

        ;; -- Best-practice surface in events.cljs + schema.cljs --
        (let [events-text (slurp (io/file root "src/acme/my_app/events.cljs"))
              schema-text (slurp (io/file root "src/acme/my_app/schema.cljs"))
              core-text   (slurp (io/file root "src/acme/my_app/core.cljs"))]
          (is (.contains events-text "register-listener!")
              "events.cljs registers an error-sink trace listener
               (errors-are-events-too best-practice)")
          (is (.contains events-text "re-frame.trace.tooling")
              "events.cljs uses the re-frame.trace.tooling/register-listener!
               surface — CLJS-only (the rf/... alias is JVM-only,
               per rf2-qwm0a)")
          (is (.contains events-text "re-frame.schemas")
              "events.cljs side-effect-loads re-frame.schemas so Malli
               publishes into the late-bind hook table before any
               reg-app-schema runs")
          (is (.contains events-text "re-frame.schemas.malli")
              "events.cljs also loads the Malli adapter (without it the
               default validator soft-passes per Spec 010)")
          (is (.contains events-text ":rf.http/managed")
              "events.cljs ships the commented HTTP failure-matrix
               exemplar so users see the canonical call shape")
          (is (.contains events-text ":rf.http/http-5xx")
              "events.cljs's HTTP exemplar uses the closed
               :rf.http/* category set in :retry :on")
          (is (.contains schema-text "reg-app-schema")
              "schema.cljs registers a whole-app-db schema")
          (is (.contains schema-text "CounterDb")
              "schema.cljs ships the CounterDb Malli schema")
          ;; -- Emitted source must not teach the positional
          ;;    reg-app-schema grammar (schema-in-metadata).
          ;; The schema lives in the metadata map's :schema key; a bare
          ;; positional schema throws :rf.error/app-schema-bad-metadata.
          ;; Scan emitted source comments too, so events.cljs's schema-load
          ;; comment cannot cite the stale (reg-app-schema [] CounterDb).
          (is (not (.contains events-text "(reg-app-schema [] CounterDb)"))
              "events.cljs must NOT cite the retired positional
               (reg-app-schema [] CounterDb) form in a comment — the
               canonical grammar is the metadata map
               (reg-app-schema [] {:schema CounterDb})")
          (is (not (.contains schema-text "(rf/reg-app-schema [] CounterDb)"))
              "schema.cljs must NOT emit the retired positional
               (rf/reg-app-schema [] CounterDb) call — the canonical grammar
               is the metadata map (rf/reg-app-schema [] {:schema CounterDb})")
          (is (.contains schema-text "{:schema CounterDb}")
              "schema.cljs emits the canonical schema-in-metadata grammar
               (rf/reg-app-schema [] {:schema CounterDb})")

          ;; -- EP-0011 / rf2-ibksxg: HTTP exemplar teaches the ONE canonical
          ;;    uniform reply envelope (no compat dialect) --
          ;; The reply the exemplar reads IS the framework-wide uniform reply
          ;; envelope delivered verbatim; there is no separate
          ;; {:kind :success/:failure} HTTP dialect (retired). The exemplar
          ;; must name the envelope, its canonical :status/:completed-at facts,
          ;; and :rf/reply-to as the one direct reply target the
          ;; :on-success/:on-failure routing sugar sits over — so a future
          ;; edit cannot re-introduce the retired compat payload.
          (is (.contains events-text "sugar over the one")
              "events.cljs HTTP exemplar marks :on-success/:on-failure as pure
               ROUTING sugar over the one direct reply target (rf2-ibksxg — no
               {:kind …} compat dialect)")
          (is (not (.contains events-text "compatibility sugar"))
              "events.cljs HTTP exemplar must NOT re-teach the retired
               {:kind :success/:failure} compat-sugar framing (rf2-ibksxg)")
          (is (.contains events-text "uniform reply envelope")
              "events.cljs HTTP exemplar names the framework-wide uniform
               reply envelope the reply IS (EP-0011 — rf2-rzsxrk)")
          (is (.contains events-text ":rf/reply-to")
              "events.cljs HTTP exemplar names :rf/reply-to as the one direct
               reply target the :on-success/:on-failure sugar sits over
               (EP-0011 / rf2-ibksxg — rf2-rzsxrk)")
          (is (.contains events-text ":completed-at")
              "events.cljs HTTP exemplar names the canonical :completed-at
               reply fact (EP-0011 — rf2-rzsxrk)")

          ;; -- EP-0015: events.cljs decode-body classification guidance --
          ;; The :decode :auto exemplar must say it is the simple
          ;; non-sensitive case and point real bodies at a :decode SCHEMA
          ;; with :sensitive? / :large? props + the unschematized
          ;; fail-closed posture, so the scaffold cannot drift to a
          ;; "decode :auto is the whole story" framing.
          (is (.contains events-text ":sensitive?")
              "events.cljs decode note names per-slot :sensitive? schema
               props for sensitive HTTP response bodies (EP-0015 — rf2-7i66d0)")
          (is (.contains events-text "fail-closed")
              "events.cljs decode note states an unschematized HTTP body is
               whole-sensitive / fail-closed (EP-0015 — rf2-7i66d0)")

          ;; -- EP-0015: frame-owned classification pointer at the reg-frame
          ;;    site + schema-is-shape-not-egress note --
          ;; core.cljs must point the user at frame-owned egress
          ;; classification where it registers :rf/default, and schema.cljs
          ;; must state that schemas validate shape, NOT durable app-db
          ;; egress (the one-owner-one-route rule).
          (is (.contains core-text ":sensitive")
              "core.cljs points at frame-owned :sensitive egress
               classification at the reg-frame site (EP-0015 — rf2-7i66d0)")
          (is (.contains schema-text "does NOT classify durable app-db")
              "schema.cljs states a schema validates shape and does NOT
               classify durable app-db egress (frame owns that — EP-0015;
               rf2-7i66d0)"))

        ;; -- package.json sanity --
        (let [pj-text (slurp (io/file root "package.json"))]
          (is (.contains pj-text "\"shadow-cljs\"")
              "package.json declares shadow-cljs devDependency")
          (is (.contains pj-text "\"react\"")
              "package.json declares react"))

        ;; -- views.cljs picks up the substrate-specific shape --
        (let [views-text (slurp (io/file root "src/acme/my_app/views.cljs"))]
          (case substrate
            :reagent (is (.contains views-text "reg-view")
                         "Reagent views.cljs uses reg-view")
            :uix     (is (.contains views-text "defui")
                         "UIx views.cljs uses defui")
            :helix   (is (.contains views-text "defnc")
                         "Helix views.cljs uses defnc")))

        ;; -- Per-substrate README badge --
        ;;
        ;; The badge LINE varies by substrate, so it lives in the
        ;; per-substrate shape test. The substrate-INVARIANT README/CI/
        ;; security text lives in `root-content-test` below — it comes
        ;; from `root/`, so it is asserted once rather than per substrate.
        (let [readme-text (slurp (io/file root "README.md"))]
          (is (.contains readme-text
                         (case substrate
                           :reagent "substrate-Reagent"
                           :uix     "substrate-UIx"
                           :helix   "substrate-Helix"))
              "README ships the per-substrate badge")))
      (finally
        (delete-recursively tmp)))))

(deftest root-content-test
  (testing "substrate-invariant root/ content (README best-practice +
            naming + badges, baseline CI workflow, security baseline) —
            generated once. These files come from root/ and are
            substrate-agnostic, so re-running them per substrate (the
            old assert-shape! shape) was 3× redundant + mislayered
            (rf2-5v619, L3)."
    (let [tmp (tmp-dir "rf2-template-root-content-")]
      (try
        (let [root (run-template! tmp "acme/my-app" :reagent)]
          ;; -- README best-practice + naming sections --
          (let [readme-text (slurp (io/file root "README.md"))]
            (is (.contains readme-text "Best practices baked into the scaffold")
                "README has a Best practices section")
            (is (.contains readme-text "Errors are events too")
                "README documents the errors-are-events-too posture")
            (is (.contains readme-text "Typed app-db boundaries")
                "README documents the typed-at-boundaries posture")
            (is (.contains readme-text "closed failure-category set")
                "README documents the closed :rf.http/* failure-category set")
            (is (.contains readme-text "Naming conventions")
                "README documents the naming-conventions rules")
            (is (.contains readme-text "spec/Conventions.md")
                "README links to spec/Conventions.md for the normative catalogue"))

          ;; -- README Hot reload — accurate reg-* cleanup claim --
          ;; The runtime registry only adds / same-id-replaces on reload; a
          ;; deleted or renamed reg-* form's old (kind, id) is NOT pruned by
          ;; a plain shadow-cljs reload — it lingers until a browser/dev-
          ;; process refresh. The README must not over-promise that a
          ;; rename/remove "drops the old registration". Reject that
          ;; phrase and positively require the explicit-refresh recovery.
          ;; Scope the assertions to the Hot reload section.
          (let [readme-text (slurp (io/file root "README.md"))
                hr-start    (.indexOf readme-text "## Hot reload")
                hr-end      (let [i (.indexOf readme-text "\n## " (inc hr-start))]
                              (if (neg? i) (count readme-text) i))
                hr-sec      (subs readme-text (max 0 hr-start) hr-end)]
            (is (not (neg? hr-start))
                "README has a Hot reload section")
            (is (not (.contains hr-sec "drops the old registration"))
                "README Hot reload section must NOT promise that a
                 rename/remove drops the old registration — the registry
                 does not prune deleted/renamed ids on plain reload
                 (rf2-n70mno)")
            ;; The wording wraps across lines in the rendered README, so
            ;; normalise whitespace before the substring check.
            (let [hr-norm (string/replace hr-sec #"\s+" " ")]
              (is (.contains hr-norm "does NOT prune the old")
                  "README Hot reload section states deleting/renaming a
                   handler does NOT prune the old id on plain reload
                   (rf2-n70mno)"))
            (is (or (.contains hr-sec "refresh the browser")
                    (.contains hr-sec "restart the dev process"))
                "README Hot reload section documents the real recovery —
                 browser/dev-process refresh rebuilds the registry from
                 an empty slate (rf2-n70mno)"))

          ;; -- README EP-0015 privacy/egress classification --
          ;; The README must carry a concise privacy/egress section that
          ;; distinguishes app-db schemas (shape) from durable classification
          ;; (:sensitive / :large commit-plane effects), and shows where
          ;; sensitive/large app-db paths are declared. (App-specific HTTP
          ;; carrier names ride the :rf.http/managed `:carriers` block, EP-0025.)
          ;; Scope the assertions to that section so an honest mention elsewhere
          ;; can't satisfy them weakly.
          (let [readme-text (slurp (io/file root "README.md"))
                priv-start  (.indexOf readme-text "### Privacy / egress classification")
                priv-end    (let [i (.indexOf readme-text "\n### " (inc priv-start))]
                              (if (neg? i) (count readme-text) i))
                priv-sec    (subs readme-text (max 0 priv-start) priv-end)]
            (is (not (neg? priv-start))
                "README has a Privacy / egress classification section
                 (EP-0015 — rf2-7i66d0)")
            (is (.contains priv-sec "do NOT classify egress")
                "README privacy section states app-db schemas validate shape
                 and do NOT classify egress (EP-0015 — rf2-7i66d0)")
            (is (.contains priv-sec "reg-frame")
                "README privacy section shows classification declared on
                 reg-frame (frame-owned durable classification — EP-0015;
                 rf2-7i66d0)")
            (is (and (.contains priv-sec ":sensitive")
                     (.contains priv-sec ":large"))
                "README privacy section names :sensitive and :large
                 frame-owned classification keys (EP-0015 — rf2-7i66d0)")
            (is (.contains priv-sec "spec/015-Data-Classification.md")
                "README privacy section links Spec 015 for the normative
                 classification model (EP-0015 — rf2-7i66d0)"))

          ;; -- README EP-0011 HTTP reply-envelope lowering --
          ;; The README HTTP section must name the lowering: the {:kind …}
          ;; reply IS the framework-wide uniform reply envelope delivered
          ;; verbatim (rf2-ibksxg — no {:kind …} compat dialect), with the
          ;; reply :status vocabulary (:ok/:error/:cancelled; :stale
          ;; suppressed). Scope to the HTTP section.
          (let [readme-text (slurp (io/file root "README.md"))
                http-start  (.indexOf readme-text "### HTTP")
                http-end    (let [i (.indexOf readme-text "\n### " (inc http-start))]
                              (if (neg? i) (count readme-text) i))
                http-sec    (subs readme-text (max 0 http-start) http-end)]
            (is (not (neg? http-start))
                "README has an HTTP section")
            (is (not (.contains http-sec "compatibility sugar"))
                "README HTTP section must NOT re-teach the retired
                 {:kind :success/:failure} compat-sugar framing (rf2-ibksxg)")
            (is (.contains http-sec "sugar over the one")
                "README HTTP section marks :on-success/:on-failure as pure
                 ROUTING sugar over the one direct reply target (rf2-ibksxg)")
            (is (.contains http-sec "uniform reply envelope")
                "README HTTP section names the uniform reply envelope the
                 reply IS (EP-0011 — rf2-rzsxrk)")
            (is (and (.contains http-sec ":ok")
                     (.contains http-sec ":cancelled")
                     (.contains http-sec ":stale"))
                "README HTTP section maps HTTP outcomes onto the canonical
                 :status values incl. :stale suppression (EP-0011 —
                 rf2-rzsxrk)")
            (is (.contains http-sec "Managed-Effects.md")
                "README HTTP section links Managed-Effects.md for the uniform
                 reply envelope contract (EP-0011 — rf2-rzsxrk)")
            ;; EP-0015 HTTP-body classification also lives in the HTTP
            ;; section — the :decode :auto / schema-prop posture.
            (is (.contains http-sec ":sensitive?")
                "README HTTP section names :decode-schema :sensitive? props
                 for sensitive response bodies (EP-0015 — rf2-7i66d0)"))

          ;; -- README substrate-invariant badges --
          (let [readme-text (slurp (io/file root "README.md"))]
            (is (.contains readme-text "img.shields.io/badge/built")
                "README ships a 'built with re-frame2' badge")
            (is (.contains readme-text "License-MIT")
                "README ships a License badge"))

          ;; -- README hot-reload contract accuracy --
          ;; The generated README MUST describe the ACTUAL rf/init!
          ;; contract (implementation/core/src/re_frame/core.cljc init!;
          ;; pinned by implementation/core/test/re_frame/boot_test.clj):
          ;; init! is idempotent — it installs the adapter ONLY when none
          ;; is seated and does NOT create the :rf/default frame (EP-0002:
          ;; the runtime never synthesises a frame from absence; core.cljc
          ;; init! docstring). The scaffold's core/init registers the frame
          ;; explicitly via (rf/reg-frame :rf/default {}) after init!. A
          ;; second init! call does NOT re-install the adapter, snapshot the
          ;; registrar, or reset app-db. The README must NOT overstate this
          ;; ("each call to init! snapshots the registrar, re-installs the
          ;; adapter, and resets the frame's app-db") NOR claim init!
          ;; "ensures the :rf/default frame exists" — both teach a false
          ;; mental model. The reset boundary is the starter's explicit
          ;; dispatch-sync [:counter/initialise] in core.cljs, not init!.
          (let [readme-text (slurp (io/file root "README.md"))
                ;; Scope the false-claim greps to the Hot reload section
                ;; (from its heading to the next ## heading) so an honest
                ;; mention elsewhere (e.g. the adapter API discussion)
                ;; can't trip them.
                hot-reload-start (.indexOf readme-text "## Hot reload")
                hot-reload-end   (let [i (.indexOf readme-text "\n## " (inc hot-reload-start))]
                                   (if (neg? i) (count readme-text) i))
                hot-reload-sec   (subs readme-text hot-reload-start hot-reload-end)]
            (is (not (neg? hot-reload-start))
                "README has a Hot reload section")
            ;; The actual contract is stated.
            (is (.contains readme-text "dispatch-sync [:counter/initialise]")
                "README names the explicit dispatch-sync [:counter/initialise]
                 as what re-seeds the demo state on reload (the reset
                 boundary — rf2-8n4s71 #2)")
            ;; The false claims must be gone — init! by itself does none
            ;; of these (boot_test.clj pins the second-call no-op).
            (is (not (.contains hot-reload-sec "snapshots the registrar"))
                "README Hot reload section must NOT claim rf/init! snapshots
                 the registrar — boot_test.clj pins the second call as a
                 no-op (rf2-8n4s71 #2)")
            (is (not (.contains hot-reload-sec "re-installs the adapter"))
                "README Hot reload section must NOT claim rf/init! re-installs
                 the adapter on each call — it installs ONLY when none is
                 seated (core.cljc init!; rf2-8n4s71 #2)")
            (is (not (.contains hot-reload-sec "resets the frame's app-db"))
                "README Hot reload section must NOT claim rf/init! resets
                 app-db by itself — the explicit dispatch-sync
                 [:counter/initialise] is the reset boundary (rf2-8n4s71 #2)")
            ;; -- README init!/:rf/default contract --
            ;; init! does NOT create :rf/default (EP-0002); the scaffold's
            ;; core/init registers it explicitly via reg-frame. core.cljc
            ;; init! docstring + the emitted core.cljs pin this.
            (is (not (.contains hot-reload-sec "ensures the `:rf/default` frame"))
                "README Hot reload section must NOT claim rf/init! 'ensures the
                 :rf/default frame exists' — init! does NOT create the default
                 frame (EP-0002; core.cljc init!; rf2-frex1l)")
            (is (and (.contains hot-reload-sec "does **not** create the `:rf/default`")
                     (.contains hot-reload-sec "reg-frame"))
                "README Hot reload section must state init! does NOT create the
                 :rf/default frame and that reg-frame registers it explicitly
                 (EP-0002; rf2-frex1l)"))

          ;; -- README schema registration is frame-scoped --
          ;; reg-app-schema is frame-local (EP-0002); a frameless ns-load
          ;; call raises :rf.error/no-frame-context (schemas/storage.cljc
          ;; reg-app-schema). The emitted schema.cljs wraps it in
          ;; register-schema! called under (with-frame :rf/default …). The
          ;; README MUST NOT teach a frameless top-level reg-app-schema and
          ;; MUST name the frame-scoped contract.
          (let [readme-text (slurp (io/file root "README.md"))
                schema-start (.indexOf readme-text "### Typed app-db boundaries")
                schema-end   (let [i (.indexOf readme-text "\n### " (inc schema-start))]
                               (if (neg? i) (count readme-text) i))
                schema-sec   (subs readme-text schema-start schema-end)]
            (is (not (neg? schema-start))
                "README has a Typed app-db boundaries section")
            (is (not (.contains schema-sec "\n(rf/reg-app-schema [] CounterDb)"))
                "README schema section must NOT show a frameless top-level
                 (rf/reg-app-schema [] CounterDb) — it would raise
                 :rf.error/no-frame-context at ns-load (EP-0002; rf2-frex1l)")
            (is (.contains schema-sec ":rf.error/no-frame-context")
                "README schema section must name :rf.error/no-frame-context as
                 the failure mode of a frameless registration (rf2-frex1l)")
            (is (and (.contains schema-sec "register-schema!")
                     (.contains schema-sec "with-frame :rf/default"))
                "README schema section must teach the frame-scoped contract —
                 a register-schema! fn called under (with-frame :rf/default …)
                 (mirrors the emitted schema.cljs / core.cljs; rf2-frex1l)"))

          ;; -- README Xray host wording — right-side, not left --
          ;; The emitted index.html orders <main id="app"> BEFORE
          ;; <aside data-rf-xray-host> and app.css documents/implements a
          ;; RIGHT-side host (pinned by the Xray layout-host audit in
          ;; template_emission_test.clj; matches
          ;; tools/xray/spec/011-Launch-Modes.md). The README must agree:
          ;; a "left layout column" description would contradict the
          ;; emitted layout + the Xray spec.
          (let [readme-text (slurp (io/file root "README.md"))]
            (is (not (.contains readme-text "left layout column"))
                "README must NOT call the Xray host a 'left layout column' —
                 the emitted index.html/app.css ship a RIGHT-side host
                 (rf2-8n4s71 #3)")
            (is (re-find #"right-side layout host" readme-text)
                "README describes the Xray host as a right-side layout host
                 (matches the emitted index.html/app.css + Xray spec —
                 rf2-8n4s71 #3)"))

          ;; -- Baseline CI workflow --
          (let [ci-text (slurp (io/file root ".github/workflows/ci.yml"))]
            (is (.contains ci-text "name: ci")
                ".github/workflows/ci.yml declares the ci workflow")
            (is (.contains ci-text "node-version: '22'")
                "ci.yml pins Node 22 LTS")
            (is (.contains ci-text "java-version: '21'")
                "ci.yml pins JDK 21 (matches re-frame2 reference build)")
            (is (.contains ci-text "actions/checkout@")
                "ci.yml uses actions/checkout with a SHA pin")
            (is (.contains ci-text "actions/setup-java@")
                "ci.yml uses actions/setup-java with a SHA pin")
            (is (.contains ci-text "actions/setup-node@")
                "ci.yml uses actions/setup-node with a SHA pin")
            (is (.contains ci-text "DeLaGuardo/setup-clojure@")
                "ci.yml uses DeLaGuardo/setup-clojure with a SHA pin")
            (is (.contains ci-text "npm test")
                "ci.yml runs `npm test` (delegates to shadow-cljs :node-test
                 per the emitted package.json)")
            (is (.contains ci-text "# acme/my-app")
                "ci.yml header substitutes {{name}}")
            ;; deps-new's flat {{key}} substitution leaves GitHub
            ;; `${{ … }}` expressions untouched because no subst key
            ;; matches the spaced inner token. Pin that invariant so a
            ;; future data key collision or substitution-engine change
            ;; that corrupts the workflow expressions is caught here.
            (is (.contains ci-text "${{ runner.os }}")
                "ci.yml's GitHub `${{ runner.os }}` expression survives
                 substitution verbatim (not eaten by deps-new {{key}}
                 substitution)")
            (is (.contains ci-text "${{ hashFiles('deps.edn') }}")
                "ci.yml's GitHub `${{ hashFiles(...) }}` expression
                 survives substitution verbatim"))

          ;; -- Security baseline (CSP-runtime parity) --
          (let [index-text  (slurp (io/file root "resources/public/index.html"))
                ;; The actual CSP policy is the `content="…"` attribute of
                ;; the CSP meta tag — NOT the surrounding HTML comment
                ;; (which legitimately discusses directives like
                ;; frame-ancestors). Pull just the policy string so the
                ;; directive assertions below test the live policy, not
                ;; documentation prose.
                csp-policy  (some-> (re-find #"http-equiv=\"Content-Security-Policy\"\s+content=\"([^\"]*)\""
                                             index-text)
                                    second)
                readme-text (slurp (io/file root "README.md"))]
            (is (.contains index-text "Content-Security-Policy")
                "index.html ships a CSP meta tag")
            (is (some? csp-policy)
                "the CSP meta tag's content=\"…\" policy string is parseable")
            (is (.contains csp-policy "default-src 'self'")
                "index.html CSP uses default-src 'self'")
            (is (.contains csp-policy "object-src 'none'")
                "index.html CSP forbids plugin objects")
            (is (.contains index-text "data-rf-xray-host")
                "index.html provides Xray's default true-inline layout host")

            ;; The dev meta CSP MUST permit inline
            ;; styles: the generated views use inline :style props and the
            ;; default-on Xray surface injects <style> blocks + inline
            ;; styles. A strict `style-src 'self'` would emit CSP
            ;; violations on the first page and block Xray. Assert the
            ;; meta tag's style-src admits 'unsafe-inline' so the shipped
            ;; runtime renders clean under its own policy.
            (is (.contains csp-policy "style-src 'self' 'unsafe-inline'")
                "index.html meta CSP permits inline styles (generated
                 views + default-on Xray both rely on them — rf2-l4prz
                 Finding 2)")

            ;; `frame-ancestors` delivered via a <meta> tag is IGNORED by
            ;; browsers; only a response header honours it. Asserting it on
            ;; the meta tag would be a false anti-clickjacking pass. The CSP
            ;; POLICY must NOT carry frame-ancestors; the anti-clickjacking
            ;; contract lives in the README's response-header snippets,
            ;; asserted below. (The HTML comment may mention it — we test the
            ;; policy string, not the file.)
            (is (not (.contains csp-policy "frame-ancestors"))
                "index.html meta CSP policy does NOT carry frame-ancestors —
                 browsers ignore it from <meta>; it belongs in a response
                 header (rf2-l4prz Finding 3)")

            (is (.contains readme-text "Production hardening")
                "README documents Production hardening")
            ;; The anti-clickjacking contract: frame-ancestors lives in
            ;; the README's response-header snippets, where it works.
            (is (.contains readme-text "frame-ancestors 'none'")
                "README's production response-header snippets carry
                 frame-ancestors 'none' (the real anti-clickjacking
                 contract — rf2-l4prz Finding 3)")
            (is (.contains readme-text "X-Content-Type-Options")
                "README covers nosniff header")
            (is (.contains readme-text "Referrer-Policy")
                "README covers Referrer-Policy header")))
        (finally
          (delete-recursively tmp))))))

(deftest reagent-default-substrate-test
  (testing "default (no :substrate arg) produces Reagent variant"
    (let [tmp (tmp-dir "rf2-template-default-")]
      (try
        (let [root (run-template! tmp "acme/my-app" nil)
              deps (read-edn (io/file root "deps.edn"))]
          (is (contains? (:deps deps) 'day8/re-frame2-reagent)
              "default substrate is Reagent"))
        (finally
          (delete-recursively tmp))))))

(deftest reagent-substrate-test
  (testing ":substrate :reagent produces the expected tree"
    (assert-shape! :reagent)))

(deftest uix-substrate-test
  (testing ":substrate :uix produces the expected tree"
    (assert-shape! :uix)))

(deftest helix-substrate-test
  (testing ":substrate :helix produces the expected tree"
    (assert-shape! :helix)))

;; --- Name derivation -----------------------------------------------------
;;
;; Every other test in this suite scaffolds `acme/my-app` — a project
;; name with a single-segment group (no dots) and a single-dash artifact.
;; That name leaves the two derivation transforms in
;; `hooks.clj`/`data-fn` (`->file-path` dots→slashes + dashes→underscores;
;; `->ns-form` the inverse) doing only the trivial single-dash work; the
;; dot→slash branch and the multi-dash branch are never exercised. A
;; regression that broke dotted-group nesting (`com.acme` →
;; `com/acme`) or multi-dash file mangling (`my-cool-app` →
;; `my_cool_app`) would ship green from the whole rest of the suite.
;;
;; This test scaffolds `com.acme/my-cool-app` and pins the full
;; derivation chain end-to-end: the rename target nesting
;; (`src/<nested-dirs>/…` = `src/com/acme/my_cool_app/…`), the
;; substituted `{{namespace}}` flowing into the emitted ns form +
;; shadow-cljs `:init-fn`, and the group-stripped output directory name.
;; It is a fresh-emit test (no shared mutable state) and deterministic.

(def ^:private dotted-name "com.acme/my-cool-app")
(def ^:private dotted-nested "com/acme/my_cool_app")     ;; ->file-path
(def ^:private dotted-ns "com.acme.my-cool-app")         ;; ->ns-form

(deftest name-derivation-dotted-group-test
  (testing "a dotted-group + multi-dash project name derives the right
            nested file path (->file-path: dots→slashes, dashes→underscores)
            and the right namespace (->ns-form) across rename targets and
            substituted content"
    (let [tmp (tmp-dir "rf2-template-dotted-name-")]
      (try
        (let [root (run-template! tmp dotted-name :reagent)]
          ;; -- (1) project output dir is the group-stripped artifact name --
          (is (= "my-cool-app" (.getName root))
              "deps-new names the output dir after the artifact portion
               (group stripped)")

          ;; -- (2) src/test rename targets nest under the file-path form --
          (doseq [rel ["src/com/acme/my_cool_app/core.cljs"
                       "src/com/acme/my_cool_app/events.cljs"
                       "src/com/acme/my_cool_app/subs.cljs"
                       "src/com/acme/my_cool_app/schema.cljs"
                       "src/com/acme/my_cool_app/views.cljs"
                       "test/com/acme/my_cool_app/events_test.cljs"]]
            (is (file-exists? root rel)
                (str "expected " rel " — nested-dirs must be "
                     dotted-nested " (->file-path of " dotted-name ")")))

          ;; -- (3) the substituted {{namespace}} reaches the emitted ns
          ;;        form + shadow-cljs :init-fn in the dash-preserving
          ;;        ->ns-form, NOT the underscore file form. --
          (let [core-text (slurp (io/file root "src/com/acme/my_cool_app/core.cljs"))]
            (is (.contains core-text (str "(ns " dotted-ns ".core"))
                "emitted core.cljs ns form uses the ->ns-form (dashes kept)"))
          (let [scs (read-edn (io/file root "shadow-cljs.edn"))]
            (is (= (symbol (str dotted-ns ".core") "init")
                   (get-in scs [:builds :app :modules :main :init-fn]))
                "shadow-cljs :init-fn substitutes the derived namespace"))

          ;; -- (4) the events_test.cljs requires the user nses by their
          ;;        derived namespace (regression guard on the rename +
          ;;        substitution feeding the emitted test scaffold). --
          (let [test-text (slurp (io/file root "test/com/acme/my_cool_app/events_test.cljs"))]
            (is (.contains test-text (str "[" dotted-ns ".events]"))
                "events_test.cljs requires the user events ns by derived namespace")
            (is (.contains test-text (str "[" dotted-ns ".subs]"))
                "events_test.cljs requires the user subs ns by derived namespace")))
        (finally
          (delete-recursively tmp))))))

(deftest name-derivation-dotted-group-with-story-test
  (testing "the dotted-group name also threads correctly through the
            with-story scaffold: stories.cljs nests under nested-dirs and
            references the view by its derived namespaced id"
    (let [tmp (tmp-dir "rf2-template-dotted-story-")]
      (try
        (let [root (run-template! tmp dotted-name :reagent true)]
          (is (file-exists? root "src/com/acme/my_cool_app/stories.cljs")
              "stories.cljs nests under the derived nested-dirs path")
          (let [stories-text (slurp (io/file root "src/com/acme/my_cool_app/stories.cljs"))]
            (is (.contains stories-text (str ":" dotted-ns ".views/counter-app"))
                "stories.cljs references the view by the derived namespaced id
                 (the {{namespace}} substitution lands inside the keyword)")))
        (finally
          (delete-recursively tmp))))))

(deftest invalid-substrate-rejected-test
  (testing "unknown :substrate value throws with a clear message"
    (let [tmp (tmp-dir "rf2-template-bad-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-substrate-must-be-one-of"
                              (run-template! tmp "acme/my-app" :svelte))
            "unknown substrate is rejected")
        (finally
          (delete-recursively tmp))))))

(deftest non-keyword-substrate-rejected-test
  (testing "non-keyword :substrate value (string, symbol, number, …)
            is rejected with a clear message (rf2-h0imw: keyword-only
            coercion replaces the earlier forgiving-input posture)."
    (let [tmp (tmp-dir "rf2-template-non-kw-")]
      (try
        ;; String form — rejected so registration errors surface
        ;; immediately rather than being coerced silently to :reagent.
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-substrate-must-be-keyword"
                              (run-template! tmp "acme/my-app" "reagent"))
            "string substrate is rejected")
        ;; Symbol form — same.
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-substrate-must-be-keyword"
                              (run-template! tmp "acme/my-app" 'reagent))
            "symbol substrate is rejected")
        ;; Arbitrary other type — same.
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-substrate-must-be-keyword"
                              (run-template! tmp "acme/my-app" 42))
            "number substrate is rejected")
        (finally
          (delete-recursively tmp))))))

;; --- :include-story? flag (003-DepsNew-Rebuild-Plan.md §2.4) -------------

(deftest default-path-emits-no-story-files-test
  (testing "default path (no :include-story?) does not emit stories.cljs
            and does not pull in the day8/re-frame2-story coord"
    (let [tmp (tmp-dir "rf2-template-no-story-")]
      (try
        (let [root (run-template! tmp "acme/my-app" :reagent)]
          (is (not (file-exists? root "src/acme/my_app/stories.cljs"))
              "stories.cljs is NOT emitted on the default path")
          (let [deps    (read-edn (io/file root "deps.edn"))
                pkg-txt (slurp (io/file root "package.json"))]
            (is (not (contains? (:deps deps) 'day8/re-frame2-story))
                "deps.edn does NOT reference day8/re-frame2-story on default path")
            (is (not (.contains pkg-txt "\"story\":"))
                "package.json does NOT carry a `story` npm script on default path"))
          ;; The default-path core.cljs should still be the simple one —
          ;; no Story require, no hash-routing surface.
          (let [core-text (slurp (io/file root "src/acme/my_app/core.cljs"))]
            (is (not (.contains core-text "re-frame.story"))
                "default-path core.cljs does NOT require re-frame.story")
            (is (not (.contains core-text "#/stories"))
                "default-path core.cljs has no hash-routing scaffold")))
        (finally
          (delete-recursively tmp))))))

(deftest explicit-include-story-false-equals-default-test
  (testing "passing :include-story? false EXPLICITLY (not omitted) takes
            the same no-story path as the default — exercises the
            `false`-coercion branch of coerce-include-story? + the
            `(some? include-story?)` arg-passthrough in run-template!,
            distinct from the nil/omitted path the sibling test covers"
    (let [tmp (tmp-dir "rf2-template-story-false-")]
      (try
        (let [root (run-template! tmp "acme/my-app" :reagent false)]
          (is (not (file-exists? root "src/acme/my_app/stories.cljs"))
              "stories.cljs is NOT emitted when :include-story? is explicitly false")
          (let [deps      (read-edn (io/file root "deps.edn"))
                core-text (slurp (io/file root "src/acme/my_app/core.cljs"))]
            (is (not (contains? (:deps deps) 'day8/re-frame2-story))
                "deps.edn does NOT reference the story coord on explicit false")
            (is (not (.contains core-text "re-frame.story"))
                "core.cljs is the default (no-story) variant on explicit false")))
        (finally
          (delete-recursively tmp))))))

(deftest include-story-true-reagent-test
  (testing ":include-story? true on Reagent emits stories.cljs +
            the with-stories core variant, and wires the story coord"
    (let [tmp (tmp-dir "rf2-template-with-story-")]
      (try
        (let [root (run-template! tmp "acme/my-app" :reagent true)]
          ;; -- The Story scaffold lands --
          (is (file-exists? root "src/acme/my_app/stories.cljs")
              "stories.cljs is emitted under :include-story? true")
          ;; -- Story coord + npm script are wired --
          (let [deps    (read-edn (io/file root "deps.edn"))
                pkg-txt (slurp (io/file root "package.json"))]
            (is (contains? (:deps deps) 'day8/re-frame2-story)
                "deps.edn references day8/re-frame2-story")
            ;; Pin value owned by version_lockstep_test.clj.
            (is (some? (get-in deps [:deps 'day8/re-frame2-story :mvn/version]))
                "story coord carries an :mvn/version pin")
            ;; The with-Story template declares no Story-specific npm
            ;; dependency — there is no vendored qrcode-generator dep.
            (is (not (.contains pkg-txt "\"qrcode-generator\""))
                "package.json does NOT declare qrcode-generator (Share
                 popover + QR encoder retired in rf2-ymnfx Issue B)"))
          ;; -- core.cljs is the hash-routing with-stories variant --
          (let [core-text (slurp (io/file root "src/acme/my_app/core.cljs"))]
            (is (.contains core-text "re-frame.story")
                "with-stories core.cljs requires re-frame.story")
            (is (.contains core-text "mount-shell!")
                "with-stories core.cljs mounts the Story shell")
            (is (.contains core-text "#/stories")
                "with-stories core.cljs routes #/stories to the shell")
            (is (.contains core-text "acme.my-app.stories")
                "with-stories core.cljs requires the stories ns so its
                 reg-* calls fire at boot")
            ;; `init` runs on every hot-reload, and
            ;; the hashchange listener's closure identity changes per
            ;; rebuild. Without a defonce-held listener + removeEventListener
            ;; before re-adding, reloads accumulate stale listeners (each
            ;; route change then fires the mount switch N times: repeated
            ;; React root teardown, flicker, a listener leak). Assert the
            ;; hot-reload-safe wiring is present.
            (is (.contains core-text "defonce")
                "with-stories core.cljs holds hot-reload state in a defonce
                 (the hashchange listener must survive reloads — rf2-l4prz
                 Finding 4)")
            (is (.contains core-text "removeEventListener")
                "with-stories core.cljs removes the previously-installed
                 hashchange listener before re-adding on hot-reload, so
                 reloads don't accumulate stale listeners (rf2-l4prz
                 Finding 4)"))
          ;; -- stories.cljs uses the four shipped reg-* macros and
          ;;    references the template's existing event/sub/view ids --
          (let [stories-text (slurp (io/file root "src/acme/my_app/stories.cljs"))]
            (is (.contains stories-text "story/reg-story")
                "stories.cljs uses reg-story")
            (is (.contains stories-text "story/reg-variant")
                "stories.cljs uses reg-variant")
            (is (.contains stories-text "story/reg-tag")
                "stories.cljs uses reg-tag")
            (is (.contains stories-text "story/reg-workspace")
                "stories.cljs uses reg-workspace")
            (is (.contains stories-text ":counter/initialise")
                "stories.cljs references the template's :counter/initialise event")
            (is (.contains stories-text ":counter/increment")
                "stories.cljs references the template's :counter/increment event")
            (is (.contains stories-text ":counter/value")
                "stories.cljs's :rf.assert/path-equals targets the
                 template's :counter/value app-db slot")
            (is (.contains stories-text ":acme.my-app.views/counter-app")
                "stories.cljs references the template's view by namespaced
                 id (Story renders by id, not by symbol)")))
        (finally
          (delete-recursively tmp))))))

;; --- with-Story release elision: config ⇆ docs agreement -----------------
;;
;; The with-Story core docstring + the generated README must
;; NOT claim that `npx shadow-cljs release app` elides Story
;; automatically / for free. It does NOT — `re-frame.story.config/enabled?`
;; defaults true and the emitted shadow-cljs.edn sets no `:release`
;; closure-define, so a plain release SHIPS the Story shell + `#/stories`
;; route + every registration. The docs and the config must AGREE that
;; elision is OPT-IN, and the docs must give the exact closure-define a
;; user adds to elide.
;;
;; This test pins that agreement three ways:
;;   (a) the emitted shadow-cljs.edn does NOT set the Story closure-define
;;       (matches the "opt-in" story — if a future edit DID add it, the
;;       docs would need to flip to "automatic" and this test reminds us);
;;   (b) the with-Story core docstring + README both carry the exact
;;       opt-in closure-define string AND flag it as opt-in;
;;   (c) neither doc carries a false-automatic claim
;;       ("inherits that elision automatically" / "costs nothing in
;;       production") that would promise free release elision.

(deftest with-story-release-elision-docs-config-agree-test
  (testing "with-Story scaffold: the release-elision docs (core docstring
            + README) agree with the emitted shadow-cljs.edn — elision is
            documented as OPT-IN with the exact closure-define, and the
            config does not silently set it (rf2-l4prz Finding 1)"
    (let [tmp (tmp-dir "rf2-template-story-elision-")]
      (try
        (let [root      (run-template! tmp "acme/my-app" :reagent true)
              core-text (slurp (io/file root "src/acme/my_app/core.cljs"))
              readme    (slurp (io/file root "README.md"))
              scs       (read-edn (io/file root "shadow-cljs.edn"))
              ;; The exact closure-define a user adds to elide Story.
              define-sym 're-frame.story.config/enabled?]

          ;; (a) The emitted shadow-cljs.edn must NOT set the Story
          ;;     closure-define anywhere — that is what makes elision
          ;;     opt-in. Walk the whole build map for the define symbol so
          ;;     a `:release`/`:dev`/`:compiler-options` placement is all
          ;;     caught.
          (let [scs-str (pr-str scs)]
            (is (not (.contains scs-str (str define-sym)))
                (str "emitted shadow-cljs.edn must NOT set "
                     define-sym " — Story release elision is opt-in (the "
                     "docs say so). If a future change DOES set it by "
                     "default, the docs must flip to 'automatic' and this "
                     "assertion + the doc text must be updated together "
                     "(rf2-l4prz Finding 1).")))

          ;; (b) Both docs carry the exact opt-in closure-define AND mark
          ;;     it opt-in. `enabled? false` is the literal token a user
          ;;     copies; "opt-in" / "OPT-IN" marks it as not automatic.
          (doseq [[label text] [["with-Story core.cljs" core-text]
                                ["README" readme]]]
            (is (.contains text "re-frame.story.config/enabled? false")
                (str label " gives the exact closure-define a user adds "
                     "to elide Story from release (rf2-l4prz Finding 1)"))
            (is (.contains (clojure.string/lower-case text) "opt-in")
                (str label " marks Story release elision as OPT-IN "
                     "(not automatic) (rf2-l4prz Finding 1)")))

          ;; (c) The false-automatic claims must be absent from both
          ;;     docs — they would assert free/automatic release elision.
          ;;     `inherits that elision automatically` (whitespace-folded)
          ;;     and `costs nothing in production` are the two phrases
          ;;     that would promise a free/automatic release elision.
          (doseq [[label text] [["with-Story core.cljs" core-text]
                                ["README" readme]]]
            (let [folded (clojure.string/replace text #"\s+" " ")]
              (is (not (.contains folded "inherits that elision automatically"))
                  (str label " no longer claims the release build inherits "
                       "elision automatically (rf2-l4prz Finding 1)")))
            (is (not (.contains text "costs nothing in production"))
                (str label " no longer claims Story 'costs nothing in "
                     "production' (it ships unless you opt in) "
                     "(rf2-l4prz Finding 1)"))))
        (finally
          (delete-recursively tmp))))))

;; --- package.json one-source byte-exact contract -------------------------
;;
;; The template emits package.json from a SINGLE `_shared/package.json`
;; source whose `description` parenthetical rides the `{{story-tag}}`
;; subst var (`""` default / `", with Story playground"` under
;; :include-story?). This test is the generate-both-and-diff proof: both
;; the default and the with-Story emission must be BYTE-EXACT against the
;; expected literals below. The expected strings are the full resolved
;; output for the `acme/my-app` Reagent emission (the template's three
;; subst vars resolved), differing only in the `description`
;; parenthetical. If a future edit to the shared package.json or the
;; story-tag derivation drifts the output, this fires.

(def ^:private expected-package-json-default
  "Expected `_shared/package.json` emission for `acme/my-app` on the
  Reagent default path (`:include-story? false`, subst vars resolved)."
  (str "{\n"
       "  \"name\": \"acme/my-app\",\n"
       "  \"version\": \"0.1.0\",\n"
       "  \"private\": true,\n"
       "  \"description\": \"re-frame2 application (Reagent substrate).\",\n"
       "  \"scripts\": {\n"
       "    \"watch\":   \"shadow-cljs watch app\",\n"
       "    \"release\": \"shadow-cljs release app\",\n"
       "    \"test\":    \"shadow-cljs compile test && node out/node-test.js\"\n"
       "  },\n"
       "  \"devDependencies\": {\n"
       "    \"shadow-cljs\": \"3.4.10\"\n"
       "  },\n"
       "  \"dependencies\": {\n"
       "    \"react\":     \"19.2.0\",\n"
       "    \"react-dom\": \"19.2.0\"\n"
       "  }\n"
       "}\n"))

(def ^:private expected-package-json-with-story
  "Expected `_shared/package.json` emission for `acme/my-app` on the
  Reagent `:include-story? true` path — identical to the default save
  for the `{{story-tag}}`-driven `description` parenthetical."
  (str "{\n"
       "  \"name\": \"acme/my-app\",\n"
       "  \"version\": \"0.1.0\",\n"
       "  \"private\": true,\n"
       "  \"description\": \"re-frame2 application (Reagent substrate, with Story playground).\",\n"
       "  \"scripts\": {\n"
       "    \"watch\":   \"shadow-cljs watch app\",\n"
       "    \"release\": \"shadow-cljs release app\",\n"
       "    \"test\":    \"shadow-cljs compile test && node out/node-test.js\"\n"
       "  },\n"
       "  \"devDependencies\": {\n"
       "    \"shadow-cljs\": \"3.4.10\"\n"
       "  },\n"
       "  \"dependencies\": {\n"
       "    \"react\":     \"19.2.0\",\n"
       "    \"react-dom\": \"19.2.0\"\n"
       "  }\n"
       "}\n"))

(defn- normalise-eol
  "Strip CR so the byte-identical comparison is line-ending agnostic.
  The source `package.json` may be checked out with CRLF on Windows
  (git `autocrlf`); the expected literals below are written with LF.
  Normalising both sides keeps the content-identity assertion portable
  across the Windows dev box and the Linux CI runner."
  [s]
  (string/replace s "\r" ""))

(deftest package-json-story-tag-byte-identical-test
  (testing "the {{story-tag}}-driven single package.json emits the
            expected byte-exact content on both the default and
            :include-story? true paths (rf2-sqqxj one-source proof —
            EOL-normalised so it is portable Windows↔CI)"
    (let [tmp (tmp-dir "rf2-template-pkg-story-tag-")]
      (try
        (let [default-root (run-template! tmp "acme/my-app" :reagent false)
              default-txt  (slurp (io/file default-root "package.json"))]
          (is (= (normalise-eol expected-package-json-default)
                 (normalise-eol default-txt))
              "default-path package.json content matches the expected
               single-source _shared/package.json emission"))
        (finally
          (delete-recursively tmp)))
      ;; Fresh tmp for the with-Story path so the two emissions don't
      ;; collide on the same output dir.
      (let [tmp2 (tmp-dir "rf2-template-pkg-story-tag-on-")]
        (try
          (let [story-root (run-template! tmp2 "acme/my-app" :reagent true)
                story-txt  (slurp (io/file story-root "package.json"))]
            (is (= (normalise-eol expected-package-json-with-story)
                   (normalise-eol story-txt))
                "with-Story package.json content matches the expected
                 single-source _shared/package.json emission (story-tag on)"))
          (finally
            (delete-recursively tmp2)))))))

(deftest include-story-non-reagent-rejected-test
  (testing ":include-story? true is rejected for non-Reagent substrates
            in v1 — UIx + Helix follow once Story's adapter coverage
            matches Reagent's"
    (let [tmp (tmp-dir "rf2-template-story-uix-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-include-story-reagent-only"
                              (run-template! tmp "acme/my-app" :uix true))
            ":include-story? + :uix is rejected at the entry-fn")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-include-story-reagent-only"
                              (run-template! tmp "acme/my-app" :helix true))
            ":include-story? + :helix is rejected at the entry-fn")
        (finally
          (delete-recursively tmp))))))

(deftest invalid-include-story-rejected-test
  (testing "non-boolean :include-story? value throws with a clear message"
    (let [tmp (tmp-dir "rf2-template-story-bad-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-bad-include-story-flag"
                              (run-template! tmp "acme/my-app" :reagent "yes"))
            "non-boolean :include-story? is rejected")
        (finally
          (delete-recursively tmp))))))

;; --- argument-key gate ----------------------------------------------------
;;
;; The substrate posture fails closed on bad VALUES; these tests pin the
;; complementary strictness on the KEY set. Reserved-but-unimplemented
;; flags and likely typos must fail closed rather than fail open into a
;; misleading vanilla scaffold. Each asserts the emitted dir does NOT
;; exist after the throw — the gate fires before any file is written.

(defn- assert-no-scaffold-emitted!
  [^java.nio.file.Path tmp]
  (is (zero? (count (.listFiles (clojure.java.io/file (.toString tmp)))))
      "the gate fired before any scaffold was emitted (tmp dir is empty)"))

(deftest reserved-css-flag-rejected-test
  (testing ":css :tailwind is reserved (gated on rf2-gthro) and fails
            closed — it does NOT silently emit the default scaffold"
    (let [tmp (tmp-dir "rf2-template-css-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-unsupported-flag"
                              (run-template-opts! tmp "acme/my-app"
                                                  {:css :tailwind}))
            ":css :tailwind is rejected as an unsupported reserved flag")
        (assert-no-scaffold-emitted! tmp)
        (finally
          (delete-recursively tmp))))))

(deftest reserved-include-ssr-flag-rejected-test
  (testing ":include-ssr? true is reserved (gated on rf2-0m5ea) and fails
            closed — it does NOT silently emit the default scaffold"
    (let [tmp (tmp-dir "rf2-template-ssr-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-unsupported-flag"
                              (run-template-opts! tmp "acme/my-app"
                                                  {:include-ssr? true}))
            ":include-ssr? true is rejected as an unsupported reserved flag")
        (assert-no-scaffold-emitted! tmp)
        (finally
          (delete-recursively tmp))))))

(deftest misspelled-story-flag-rejected-test
  (testing "a one-character Story-flag typo (:include-story, dropping the
            ?) fails closed rather than scaffolding a Story-less app the
            user believes has Story"
    (let [tmp (tmp-dir "rf2-template-typo-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-unknown-flag"
                              (run-template-opts! tmp "acme/my-app"
                                                  {:include-story true}))
            ":include-story (typo for :include-story?) is rejected")
        (assert-no-scaffold-emitted! tmp)
        (finally
          (delete-recursively tmp))))))

(deftest pluralised-story-flag-rejected-test
  (testing "the :include-stories? plural typo also fails closed"
    (let [tmp (tmp-dir "rf2-template-typo2-")]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #":rf\.error/template-unknown-flag"
                              (run-template-opts! tmp "acme/my-app"
                                                  {:include-stories? true}))
            ":include-stories? (plural typo) is rejected")
        (assert-no-scaffold-emitted! tmp)
        (finally
          (delete-recursively tmp))))))

(deftest harness-keys-not-rejected-test
  (testing "the gate's allowlist does not false-reject deps-new harness
            keys — a representative harness key (:overwrite) plus the
            valid template flags scaffold cleanly"
    (let [tmp (tmp-dir "rf2-template-harness-")]
      (try
        ;; :overwrite + :src-dirs are harness keys run-template-opts!
        ;; already injects; add an explicit :substrate to confirm the
        ;; happy path still emits with the gate in place.
        (let [proj (run-template-opts! tmp "acme/my-app"
                                       {:substrate :reagent})]
          (is (file-exists? proj "deps.edn")
              "a valid invocation still scaffolds with the gate active"))
        (finally
          (delete-recursively tmp))))))
