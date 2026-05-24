(ns re-frame.story.xray-preset-cljs-test
  "CLJS-runtime tests for the per-story Xray preset, specifically the
  Xray-as-RHS mount-time bridges that propagate Story-side configuration
  into Xray's config slot.

  ## Why a separate `_cljs_test.cljs` file

  The pure data + JVM-runnable surface lives in
  `re-frame.story.xray-preset-test` (.cljc). The `:node-test` build's
  ns-regexp is `cljs-test$` — to land actual CLJS-runtime coverage the
  test namespace name must match that pattern. Hence this companion
  file. The `.cljc` sibling stays the home for the deep-merge / resolve
  pure-data tests that round-trip through both JVM and CLJS.

  ## Coverage

  - `disable-keybinding!` (rf2-q7who.1): writes
    `{:rf.xray/keybinding-enabled? false}` into Xray's config slot via
    its `configure!` surface. Verified directly against Xray's
    config-atom in the node-test build (Xray's source path is on the
    test classpath) and via a shimmed `configure!` fn that captures
    the call payload.
  - `detach-keybinding!` (rf2-ycrt2): drives Xray's
    `keybinding/detach!` so the listener Xray's preload installed
    under the default-true posture is removed at runtime (the slot
    alone is read only at attach time).
  - `wire-cross-host!` (rf2-q7who.1 + rf2-ycrt2): calls
    `disable-keybinding!` then `detach-keybinding!` as part of the
    cross-host bridge so Story's RHS-mounted Xray never swallows the
    host's Cmd/Ctrl+K command palette — both the intent declaration
    (slot flip) and the runtime mechanism (detach!) fire together.
    (rf2-ee38b.3 removed the DEPRECATED `ensure-xray-mounted!` shim;
    the legacy whole-shell-open composes
    `(do (wire-cross-host!) (apply-open!))` directly.)"
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.config :as xray-config]
            [day8.re-frame2-xray.keybinding :as xray-keybinding]
            [re-frame.story.xray-preset :as xray-preset]))

;; ---- disable-keybinding! -------------------------------------------------

(deftest disable-keybinding-lands-on-xray-config-slot
  (testing "disable-keybinding! writes false into Xray's :rf.xray/keybinding-enabled? slot"
    ;; The node-test build has tools/xray/src on the classpath, so
    ;; `day8.re-frame2-xray.config` is loaded and `disable-keybinding!`
    ;; can drive the real `configure!`. Seed the slot with the default
    ;; (true) then verify the bridge flips it to false.
    (xray-config/set-keybinding-enabled! true)
    (is (true? (xray-config/keybinding-attach-enabled?))
        "precondition: default keybinding-enabled? is true")
    (is (true? (xray-preset/disable-keybinding!))
        "disable-keybinding! returns true when the configure! call landed")
    (is (false? (xray-config/keybinding-attach-enabled?))
        "after disable-keybinding!, Xray's slot is false")
    ;; Restore the default so subsequent tests in this suite see a
    ;; clean slot.
    (xray-config/set-keybinding-enabled! true)))

(deftest disable-keybinding-shimmed-configure
  (testing "disable-keybinding! calls Xray's configure! with the exact slot map"
    ;; Belt-and-braces test: redef `xray-config-available?` (already
    ;; true in this build) and the `resolve-fn` lookup so we capture
    ;; the exact opts map the bridge sends to `configure!`. Guards the
    ;; payload shape against accidental extras / typos.
    (let [captured (atom nil)
          shim-configure! (fn [opts] (reset! captured opts) nil)]
      (with-redefs [xray-preset/xray-config-available?
                    (fn [] true)
                    xray-preset/resolve-fn
                    (fn [sym]
                      (when (= sym 'day8.re-frame2-xray.config/configure!)
                        shim-configure!))]
        (is (true? (xray-preset/disable-keybinding!))
            "returns true when the configure! call landed")
        (is (= {:rf.xray/keybinding-enabled? false} @captured)
            "configure! is called with exactly the keybinding-disable slot")))))

;; ---- detach-keybinding! (rf2-ycrt2) --------------------------------------

(deftest detach-keybinding-drives-xray-keybinding-detach
  (testing "detach-keybinding! removes Xray's global keydown listener"
    ;; Belt-and-braces test: redef `xray-config-available?` and the
    ;; `resolve-fn` lookup so we capture that detach-keybinding! drives
    ;; `keybinding/detach!` by symbol. The bridge is symbol-based
    ;; (resolve-fn 'day8.re-frame2-xray.keybinding/detach!) so the
    ;; assertion mirrors that lookup contract.
    (let [called? (atom false)
          shim-detach! (fn [] (reset! called? true) nil)]
      (with-redefs [xray-preset/xray-config-available?
                    (fn [] true)
                    xray-preset/resolve-fn
                    (fn [sym]
                      (when (= sym 'day8.re-frame2-xray.keybinding/detach!)
                        shim-detach!))]
        (is (true? (xray-preset/detach-keybinding!))
            "returns true when keybinding/detach! is reachable")
        (is (true? @called?)
            "keybinding/detach! was driven by the bridge")))))

(deftest detach-keybinding-is-noop-without-xray
  (testing "detach-keybinding! returns nil when Xray's keybinding ns is absent"
    ;; Shim xray-config-available? false so the outer guard short-
    ;; circuits — bridges must degrade silently when Xray is not on the
    ;; classpath (the standalone-Story posture).
    (with-redefs [xray-preset/xray-config-available? (fn [] false)]
      (is (nil? (xray-preset/detach-keybinding!))
          "no Xray → no work → nil"))))

;; ---- wire-cross-host! drives the bridges (rf2-ee38b.3) -------------------
;;
;; The shim `ensure-xray-mounted!` was removed; the legacy whole-shell
;; open is `(do (wire-cross-host!) (apply-open!))`. These tests pin the
;; same bridge ordering against that composition.

(deftest wire-cross-host-disables-keybinding
  (testing "wire-cross-host! drives disable-keybinding! + detach-keybinding!;
            the composed (wire-cross-host! + apply-open!) still opens"
    ;; The embed wires cross-host config on every variant-selection edge.
    ;; Verify the keybinding bridges fire. We shim the Xray-availability
    ;; gate AND the bridges so we can assert the wiring without depending
    ;; on the underlying configure! plumbing (covered by the shimmed-
    ;; configure test above), and shim `apply-open!` so we don't actually
    ;; mount a shell.
    (let [disable-called? (atom false)
          detach-called?  (atom false)
          open-called?    (atom false)]
      (with-redefs [xray-preset/xray-available?
                    (fn [] true)
                    xray-preset/disable-keybinding!
                    (fn [] (reset! disable-called? true) true)
                    xray-preset/detach-keybinding!
                    (fn [] (reset! detach-called? true) true)
                    xray-preset/apply-open!
                    (fn [] (reset! open-called? true) nil)]
        (xray-preset/wire-cross-host!)
        (xray-preset/apply-open!)
        (is (true? @disable-called?)
            "disable-keybinding! is part of the cross-host bridge")
        (is (true? @detach-called?)
            "detach-keybinding! is part of the cross-host bridge")
        (is (true? @open-called?)
            "the composed open still fires (keybinding wire-up does not break mount)")))))

(deftest wire-cross-host-sequences-slot-then-detach
  (testing "rf2-ycrt2 — wire-cross-host! flips the slot BEFORE removing
            the listener; sequencing matters because a host (or test
            runner) inspecting the slot mid-flow must always see the
            declared intent. We capture the order via a shared log and
            assert disable-keybinding! ran before detach-keybinding!."
    (let [calls (atom [])]
      (with-redefs [xray-preset/xray-available?
                    (fn [] true)
                    xray-preset/disable-keybinding!
                    (fn [] (swap! calls conj :disable) true)
                    xray-preset/detach-keybinding!
                    (fn [] (swap! calls conj :detach) true)
                    xray-preset/apply-open!
                    (fn [] (swap! calls conj :open) nil)]
        (xray-preset/wire-cross-host!)
        (xray-preset/apply-open!)
        (is (= [:disable :detach :open] @calls)
            "slot flip (intent) lands before detach! (runtime removal)
             which lands before the composed apply-open!")))))

;; ---- runtime integration: slot + detach! together (rf2-ycrt2) ------------

(deftest wire-cross-host-clears-attached-listener
  (testing "rf2-ycrt2 — simulate Xray's preload-time attach! under the
            default-true posture, then drive wire-cross-host!; after the
            bridge the keybinding sentinel must be false (the listener
            was removed). This is the runtime contract rf2-q7who.1
            declared but did not close — the slot flip alone wouldn't
            detach the listener; rf2-ycrt2 closes the gap via
            detach-keybinding!."
    ;; Restore baseline so attach! sees the default-true slot. Then
    ;; simulate the preload: attach!. Without rf2-ycrt2 the listener
    ;; would survive past wire-cross-host!; with the fix the sentinel
    ;; flips back to false.
    (xray-config/set-keybinding-enabled! true)
    (try
      ;; Skip the inner attach when js/document is unstubbable (real
      ;; browser-test) — the contract still proves on node-test where
      ;; the keybinding suite's stub gates idempotency.
      (when (exists? js/document)
        (xray-keybinding/attach!)
        (is (true? (xray-keybinding/attached?))
            "precondition: preload-style attach! installed the listener"))
      ;; Drive the cross-host bridge. We shim Xray-availability so the
      ;; gate passes; `disable-keybinding!` and `detach-keybinding!` run
      ;; for real (their inner `resolve-fn` lookups resolve against
      ;; Xray's live config/keybinding namespaces, both on the test
      ;; classpath). No shell mount happens — `wire-cross-host!` never
      ;; calls `apply-open!`.
      (with-redefs [xray-preset/xray-available? (fn [] true)]
        (xray-preset/wire-cross-host!))
      (is (false? (xray-config/keybinding-attach-enabled?))
          "wire-cross-host! flipped the slot to false")
      (is (false? (xray-keybinding/attached?))
          "wire-cross-host! removed the listener (rf2-ycrt2 runtime gap closed)")
      (finally
        ;; Restore defaults so neighbouring tests see the baseline.
        (xray-config/set-keybinding-enabled! true)
        (when (exists? js/document)
          (xray-keybinding/detach!))))))
