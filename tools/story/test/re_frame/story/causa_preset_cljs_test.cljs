(ns re-frame.story.causa-preset-cljs-test
  "CLJS-runtime tests for the per-story Causa preset, specifically the
  Causa-as-RHS mount-time bridges that propagate Story-side configuration
  into Causa's config slot.

  ## Why a separate `_cljs_test.cljs` file

  The pure data + JVM-runnable surface lives in
  `re-frame.story.causa-preset-test` (.cljc). The `:node-test` build's
  ns-regexp is `cljs-test$` — to land actual CLJS-runtime coverage the
  test namespace name must match that pattern. Hence this companion
  file. The `.cljc` sibling stays the home for the deep-merge / resolve
  pure-data tests that round-trip through both JVM and CLJS.

  ## Coverage

  - `disable-keybinding!` (rf2-q7who.1): writes
    `{:launch.keybinding/enabled? false}` into Causa's config slot via
    its `configure!` surface. Verified directly against Causa's
    config-atom in the node-test build (Causa's source path is on the
    test classpath) and via a shimmed `configure!` fn that captures
    the call payload.
  - `ensure-causa-mounted!` (rf2-q7who.1): calls `disable-keybinding!`
    as part of the mount edge so Story's RHS-mounted Causa never
    swallows the host's Cmd/Ctrl+K command palette."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-causa.config :as causa-config]
            [re-frame.story.causa-preset :as causa-preset]))

;; ---- disable-keybinding! -------------------------------------------------

(deftest disable-keybinding-lands-on-causa-config-slot
  (testing "disable-keybinding! writes false into Causa's :launch.keybinding/enabled? slot"
    ;; The node-test build has tools/causa/src on the classpath, so
    ;; `day8.re-frame2-causa.config` is loaded and `disable-keybinding!`
    ;; can drive the real `configure!`. Seed the slot with the default
    ;; (true) then verify the bridge flips it to false.
    (causa-config/set-keybinding-enabled! true)
    (is (true? (causa-config/keybinding-attach-enabled?))
        "precondition: default keybinding-enabled? is true")
    (is (true? (causa-preset/disable-keybinding!))
        "disable-keybinding! returns true when the configure! call landed")
    (is (false? (causa-config/keybinding-attach-enabled?))
        "after disable-keybinding!, Causa's slot is false")
    ;; Restore the default so subsequent tests in this suite see a
    ;; clean slot.
    (causa-config/set-keybinding-enabled! true)))

(deftest disable-keybinding-shimmed-configure
  (testing "disable-keybinding! calls Causa's configure! with the exact slot map"
    ;; Belt-and-braces test: redef `causa-config-available?` (already
    ;; true in this build) and the `resolve-fn` lookup so we capture
    ;; the exact opts map the bridge sends to `configure!`. Guards the
    ;; payload shape against accidental extras / typos.
    (let [captured (atom nil)
          shim-configure! (fn [opts] (reset! captured opts) nil)]
      (with-redefs [causa-preset/causa-config-available?
                    (fn [] true)
                    causa-preset/resolve-fn
                    (fn [sym]
                      (when (= sym 'day8.re-frame2-causa.config/configure!)
                        shim-configure!))]
        (is (true? (causa-preset/disable-keybinding!))
            "returns true when the configure! call landed")
        (is (= {:launch.keybinding/enabled? false} @captured)
            "configure! is called with exactly the keybinding-disable slot")))))

;; ---- ensure-causa-mounted! drives the bridge -----------------------------

(deftest ensure-causa-mounted-disables-keybinding
  (testing "ensure-causa-mounted! drives disable-keybinding! on the mount edge"
    ;; The shell calls ensure-causa-mounted! on every variant-selection
    ;; edge. Verify the keybinding-disable bridge is invoked as part of
    ;; that flow. We shim the Causa-availability gate AND
    ;; `disable-keybinding!` itself so we can assert the wiring without
    ;; depending on the underlying configure! plumbing (covered by the
    ;; shimmed-configure test above). We also stub the `resolve-fn`
    ;; lookup `apply-open!` uses so we don't actually try to mount a
    ;; shell.
    (let [disable-called? (atom false)
          open-called?    (atom false)]
      (with-redefs [causa-preset/causa-available?
                    (fn [] true)
                    causa-preset/disable-keybinding!
                    (fn [] (reset! disable-called? true) true)
                    causa-preset/resolve-fn
                    (fn [sym]
                      (when (= sym 'day8.re-frame2-causa.mount/open!)
                        (fn [] (reset! open-called? true) nil)))]
        (causa-preset/ensure-causa-mounted!)
        (is (true? @disable-called?)
            "disable-keybinding! is part of the mount edge")
        (is (true? @open-called?)
            "apply-open! still fires (keybinding wire-up does not break mount)")))))
