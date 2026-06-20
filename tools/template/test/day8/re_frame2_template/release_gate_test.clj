(ns day8.re-frame2-template.release-gate-test
  "Workflow-sanity coverage for `.github/workflows/template-release.yml`.

   The tag-triggered template release runs a pre-release test gate
   (`test-template`) before cutting a GitHub Release. The gate is only
   meaningful if it actually exercises the behavioural emitted-app tier
   in `emitted_test_run_test.clj` — that tier compiles the generated
   `:app` + `:test` builds, runs the generated tests, and runs the
   Reagent `:advanced` release build. That tier is OFF by default and
   needs:

     1. `RF2_TEMPLATE_RUN_EMITTED_TESTS=1` (the opt-in env var), and
     2. a populated `implementation/node_modules` (`npm ci` in
        `implementation`) so the emitted bundle's React imports resolve.

   A plain `clojure -M:test` with neither would let a `template-v…` tag
   publish a scaffold that fails to compile / run / release, even though
   the PR + nightly template gates (test.yml / expensive-tests.yml) would
   have caught it.

   These tests pin the release gate's shape so it cannot silently
   regress to a fast-loop-only run. They read the workflow YAML as
   text (no YAML parser dependency — the assertions are on stable
   substrings, mirroring how the sibling shape/static-parse tests assert
   on emitted source). They run in the default `clojure -M:test` (no
   Node, no env-var gate) — this is the cheap signal the release gate
   itself is correctly wired."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [day8.re-frame2-template.test-support :refer [repo-root]]))

(defn- release-workflow-text
  "Slurp `.github/workflows/template-release.yml` from the repo root.
  Throws a legible error if it has moved/been deleted — a renamed or
  removed release workflow is itself a regression these tests should
  surface, not silently skip."
  []
  (let [f (io/file (repo-root) ".github/workflows/template-release.yml")]
    (is (.isFile f)
        (str ".github/workflows/template-release.yml must exist — it is "
             "the tag-triggered template release pipeline. Looked at "
             (.getPath f)))
    (when (.isFile f) (slurp f))))

(defn- test-template-job
  "The `test-template:` job block of the workflow text — from the job
  key up to (but not including) the next top-level `github-release:`
  job. Lets the assertions below scope to the pre-release gate without
  matching incidental substrings elsewhere in the file."
  [yaml]
  (let [start (string/index-of yaml "\n  test-template:")
        end   (string/index-of yaml "\n  github-release:")]
    (when (and start end (< start end))
      (subs yaml start end))))

(defn- github-release-job
  "The `github-release:` job block — from the job key to end-of-file
  (it is the last job). Scopes the Release-body assertions to the job
  that cuts the GitHub Release."
  [yaml]
  (let [start (string/index-of yaml "\n  github-release:")]
    (when start
      (subs yaml start))))

(deftest release-gate-exists-test
  (testing "the template release workflow is present and tag-triggered"
    (let [yaml (release-workflow-text)]
      (when yaml
        (is (string/includes? yaml "template-v[0-9]+.[0-9]+.[0-9]+")
            "release workflow triggers on a template-v… tag push")
        (is (string/includes? yaml "test-template:")
            "release workflow has a pre-release test-template gate job")
        (is (string/includes? yaml "github-release:")
            "release workflow has a github-release job")))))

(deftest release-gate-runs-emitted-app-tier-test
  (testing "the pre-release gate enables + provisions the emitted-app
            behavioural tier (cannot regress to a fast-loop-only run)"
    (let [yaml (release-workflow-text)
          job  (some-> yaml test-template-job)]
      (is (some? job)
          "could not isolate the test-template job block — has it been
           renamed or merged with github-release?")
      (when job
        ;; The opt-in env var. Without it `emitted_test_run_test.clj`
        ;; short-circuits every behavioural deftest to a skip-assert, so
        ;; the gate would compile/run nothing.
        (is (string/includes? job "RF2_TEMPLATE_RUN_EMITTED_TESTS")
            "test-template must set RF2_TEMPLATE_RUN_EMITTED_TESTS — it is
             the opt-in flag that turns the emitted-app compile/run/release
             tier ON. Without it the release gate is a fast-loop-only shape
             check and a broken scaffold can be published (rf2-ek857f F1).")
        (is (re-find #"RF2_TEMPLATE_RUN_EMITTED_TESTS:\s*[\"']?1" job)
            "RF2_TEMPLATE_RUN_EMITTED_TESTS must be set to 1 (string),
             matching test.yml / expensive-tests.yml.")
        ;; Node + npm ci provision implementation/node_modules so the
        ;; emitted bundle's React imports resolve at compile/run time.
        (is (string/includes? job "setup-node")
            "test-template must set up Node.js — the emitted-app tier
             shells out to `node` and shadow-cljs needs it.")
        (is (re-find #"npm ci" job)
            "test-template must `npm ci` in implementation/ so
             implementation/node_modules is populated for the emitted
             bundle's React imports (the smoke symlinks/junctions to it).")
        (is (string/includes? job "working-directory: implementation")
            "the npm ci step must run in implementation/ (where the
             node_modules tree the smoke links to lives).")
        ;; Still actually invokes the JVM test suite.
        (is (re-find #"clojure -M:test" job)
            "test-template must run `clojure -M:test` from tools/template
             (the suite the env var + node_modules unlock).")))))

(deftest release-body-carries-pre-split-caveat-test
  (testing "the GitHub Release body warns the pre-split, local-root-only
            release is NOT a usable public scaffold (rf2-8n4s71 #1)"
    (let [yaml (release-workflow-text)
          job  (some-> yaml github-release-job)]
      (is (some? job)
          "could not isolate the github-release job block — has it been
           renamed or removed?")
      (when job
        ;; The pre-release gate proves only "generated code compiles
        ;; after the emitted framework coords are rewritten to monorepo
        ;; :local/root paths" (emitted_test_run_test.clj). It does NOT
        ;; prove the emitted :mvn/version coords resolve — they are
        ;; unpublished and the io.github.day8/re-frame2-template git-coord
        ;; does not resolve until the repo split. So the cut Release MUST
        ;; carry a loud caveat: otherwise a `template-v…` tag advertises a
        ;; usable public scaffold that fails at the first consumer's
        ;; dependency resolution (the very false-green this guards).
        (is (re-find #"(?i)not\s+(yet\s+)?a usable public scaffold" job)
            "the GitHub Release body must state the pre-split release is
             NOT a usable public scaffold — the emitted coords don't
             resolve until the template repo split (rf2-8n4s71 #1).")
        (is (string/includes? job "rf2-8n4s71")
            "the Release-body caveat must cite rf2-8n4s71 so the
             provenance of the pre-split warning is traceable.")
        (is (re-find #"(?i):local/root" job)
            "the Release-body caveat must name the :local/root rewrite —
             the reason the gate proves 'compiles' but not 'coords
             resolve' (rf2-8n4s71 #1).")
        (is (re-find #"(?i)not\s+published|do(es)?\s+not\s+resolve" job)
            "the Release-body caveat must say the framework coords are
             not published / the git-coord does not resolve pre-split
             (rf2-8n4s71 #1).")))))
