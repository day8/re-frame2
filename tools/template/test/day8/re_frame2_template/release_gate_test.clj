(ns day8.re-frame2-template.release-gate-test
  "Workflow-sanity coverage for `.github/workflows/template-release.yml`,
   read as text (no YAML dependency). The tag-triggered release runs a
   `test-template` gate before cutting a GitHub Release, and that gate means
   something only if it runs the emitted-app tier of
   `emitted_test_run_test.clj`, which is OFF unless
   `RF2_TEMPLATE_RUN_EMITTED_TESTS=1` and `implementation/node_modules` is
   populated. These tests pin that wiring, and the Release body's pre-split
   caveat, so neither regresses unnoticed to a fast-loop-only release."
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
        (is (re-find #"RF2_TEMPLATE_RUN_EMITTED_TESTS:\s*[\"']?1" job)
            "test-template must set RF2_TEMPLATE_RUN_EMITTED_TESTS to 1
             (string), matching test.yml / expensive-tests.yml — it is the
             opt-in flag that turns the emitted-app compile/run/release tier
             ON. Without it the release gate is a fast-loop-only shape check
             and a broken scaffold can be published.")
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
        ;; The tier hard-fails on an unlaunchable browser, but only at tag
        ;; time; this read catches a dropped install at PR time.
        (is (re-find #"playwright install --with-deps chromium" job)
            "test-template must `npx playwright install --with-deps chromium`
             — `npm ci` installs the playwright package but no browser
             binary, and the emitted-app tier's dev-page boot proof needs a
             launchable Chromium.")
        (is (re-find #"clojure -M:test" job)
            "test-template must run `clojure -M:test` from tools/template
             (the suite the env var + node_modules unlock).")))))

(deftest release-body-carries-pre-split-caveat-test
  (testing "the GitHub Release body warns the pre-split, local-root-only
            release is NOT a usable public scaffold"
    (let [yaml (release-workflow-text)
          job  (some-> yaml github-release-job)]
      (is (some? job)
          "could not isolate the github-release job block — has it been
           renamed or removed?")
      (when job
        ;; The gate proves the scaffold compiles against :local/root rewrites
        ;; of its coords, not that the unpublished :mvn/version coords resolve.
        (is (re-find #"(?i)not\s+(yet\s+)?a usable public scaffold" job)
            "the GitHub Release body must state the pre-split release is
             NOT a usable public scaffold — the emitted coords don't
             resolve until the template repo split.")
        (is (string/includes? job "tools/template/spec/005-Repo-Split.md")
            "the Release-body caveat must cite
             tools/template/spec/005-Repo-Split.md so a reader can trace the
             pre-split warning to the post-split gate that lifts it.")
        (is (re-find #"(?i):local/root" job)
            "the Release-body caveat must name the :local/root rewrite —
             the reason the gate proves 'compiles' but not 'coords
             resolve'.")
        (is (re-find #"(?i)not\s+published|do(es)?\s+not\s+resolve" job)
            "the Release-body caveat must say the framework coords are
             not published / the git-coord does not resolve pre-split.")))))
