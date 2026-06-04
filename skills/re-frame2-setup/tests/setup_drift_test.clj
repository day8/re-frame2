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
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'setup-drift-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
