(ns day8.re-frame2-causa.panels-e2e.configure-multi-key-e2e-cljs-test
  "Multi-frame e2e port of the Causa feature-matrix Playwright scenario
  `configure! multi-key map and partial-update semantics` (rf2-rviu8 —
  was rf2-qd5r6 deepening). The Playwright original
  (`scenarios.cjs::runConfigurePartialUpdate`) opened a browser, hit
  the `window.day8.re_frame2_causa.config.configure_BANG_` JS surface
  with hash-maps wrapped from CLJS internals, and asserted:

    1. A multi-key configure! call updates every key.
    2. A subsequent single-key configure! leaves the other keys
       untouched (partial-update semantics).

  This is the most browser-free scenario in the feature matrix — it
  literally went into the browser to call a fn that lives in the
  Causa config namespace which is already in scope in Node tests. No
  DOM, no events, no rendering — just function calls.

  ## Pre-/post-state hygiene

  `configure!` writes module-level atoms (`editor`, `project-root`,
  `layout-host-selector`, `auto-open?`, `show-sensitive?`). All keys
  live under the `:rf.causa/*` reserved namespace per rf2-xea9u; the
  cross-tool privacy gate is `:rf.privacy/show-sensitive?`. Other
  tests in the suite may have flipped them; this test snapshots the
  pre-state, runs assertions against deltas, then restores.

  Why we don't need the multi-frame harness here: the assertions are
  about a single namespace's atom state, not Causa's spine + cascades.
  No frames involved. Listed under `panels_e2e/` for discoverability
  alongside the other rf2-rviu8 conversions."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.config :as cfg]))

(defn- snapshot []
  {:editor          (cfg/get-editor)
   :project-root    (cfg/get-project-root)
   :layout-host     (cfg/get-layout-host-selector)
   :auto-open?      (cfg/auto-open-enabled?)
   :show-sensitive? (cfg/get-show-sensitive)})

(defn- restore! [s]
  (cfg/configure! {:rf.causa/editor                (:editor s)
                   :rf.causa/project-root          (:project-root s)
                   :rf.causa/layout-host-selector  (:layout-host s)
                   :rf.causa/auto-open?            (:auto-open? s)
                   :rf.privacy/show-sensitive?     (:show-sensitive? s)}))

(use-fixtures :each
  {:before (fn []
             (set! (.-__rf2_rviu8_pre__ js/globalThis) (snapshot)))
   :after  (fn []
             (restore! (.-__rf2_rviu8_pre__ js/globalThis)))})

(deftest configure-multi-key-round-trips
  (testing "a single configure! call updates every supplied key"
    (cfg/configure! {:rf.causa/editor                :idea
                     :rf.causa/project-root          "/tmp/probe-multi-key"
                     :rf.causa/layout-host-selector  "#rf2-qd5r6-probe-host"
                     :rf.causa/auto-open?            false
                     :rf.privacy/show-sensitive?     true})
    (is (= :idea (cfg/get-editor))
        ":rf.causa/editor did not round-trip through multi-key configure!")
    (is (= "/tmp/probe-multi-key" (cfg/get-project-root))
        ":rf.causa/project-root did not round-trip through multi-key configure!")
    (is (= "#rf2-qd5r6-probe-host" (cfg/get-layout-host-selector))
        ":rf.causa/layout-host-selector did not round-trip through multi-key configure!")
    (is (= false (cfg/auto-open-enabled?))
        ":rf.causa/auto-open? did not round-trip through multi-key configure!")
    (is (= true (cfg/get-show-sensitive))
        ":rf.privacy/show-sensitive? did not round-trip through multi-key configure!")))

(deftest configure-partial-update-leaves-others-alone
  (testing "a single-key configure! call leaves untouched keys at their previous values"
    ;; Seed every slot first.
    (cfg/configure! {:rf.causa/editor                :idea
                     :rf.causa/project-root          "/tmp/probe-multi-key"
                     :rf.causa/layout-host-selector  "#rf2-qd5r6-probe-host"
                     :rf.causa/auto-open?            false
                     :rf.privacy/show-sensitive?     true})
    ;; Partial update — only :rf.causa/editor.
    (cfg/configure! {:rf.causa/editor :zed})
    (is (= :zed (cfg/get-editor))
        "partial configure! :rf.causa/editor did not take effect")
    (is (= "/tmp/probe-multi-key" (cfg/get-project-root))
        ":rf.causa/project-root was reset by partial configure! — partial-update semantics broke")
    (is (= "#rf2-qd5r6-probe-host" (cfg/get-layout-host-selector))
        ":rf.causa/layout-host-selector was reset by partial configure!")
    (is (= false (cfg/auto-open-enabled?))
        ":rf.causa/auto-open? was reset by partial configure!")
    (is (= true (cfg/get-show-sensitive))
        ":rf.privacy/show-sensitive? was reset by partial configure!")))

(deftest configure-empty-map-is-noop
  (testing "configure! with empty map leaves every slot at its current value"
    (cfg/configure! {:rf.causa/editor                :cursor
                     :rf.causa/project-root          "/tmp/noop-baseline"
                     :rf.causa/auto-open?            true
                     :rf.privacy/show-sensitive?     false})
    (let [pre (snapshot)]
      (cfg/configure! {})
      (is (= pre (snapshot))
          "empty configure! mutated state — partial-update contract violated"))))
