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
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.config :as xray-config]
            [day8.re-frame2-xray.filters.typed-predicates :as xray-typed]
            [day8.re-frame2-xray.keybinding :as xray-keybinding]
            [day8.re-frame2-xray.registry :as xray-registry]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story :as rf.story]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.xray-preset :as rf.story.xray-preset]))

;; ---- fixtures ------------------------------------------------------------
;;
;; Self-sufficient setup: this namespace installs its own adapter and
;; canonical vocabulary rather than relying on an earlier namespace
;; having called `init!`. Story has a documented false-green history
;; where a suite only passed because a neighbour had seated the adapter
;; first; the per-ns isolation gate exists to catch exactly that.

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.frame/ensure-default-frame!)
  (rf.story/install-canonical-vocabulary!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- Xray-side helpers (rf2-q5pd6) --------------------------------------
;;
;; The `:filters` tests assert against Xray's REAL `:active-filters`
;; slot, so they need Xray's handler set + the `:rf/xray` frame. This is
;; the same lightweight setup Xray's own filter suites use
;; (`filters/persistence-cljs-test`): register the handlers, make the
;; frame. `reset-for-test!` clears the registry's idempotency sentinel,
;; which `reset-all!`'s `rf.registrar/clear-all!` would otherwise leave set
;; over an emptied registrar — handlers would silently not re-register.

(defn- mount-xray!
  "Model what Xray's `mount-<panel>!` does to the world: register the
  handler set and the `:rf/xray` frame. Does NOT drain the pending
  filter slot — the tests that exercise parking need the flush to be
  their own act."
  []
  (xray-registry/reset-for-test!)
  (xray-registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  nil)

(defn- install-xray-frame!
  "`mount-xray!` plus a clean slate: drain anything a previous test
  parked (`flush-pending-filters!` is the only accessor) and reset the
  live slot to unfiltered."
  []
  (mount-xray!)
  (rf.story.xray-preset/flush-pending-filters!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/hydrate-filters {:in [] :out []}]))
  nil)

(defn- active-filters
  "Read Xray's live `:active-filters` slot through its own sub."
  []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/active-filters])))

(defn- reg-filtered-variant!
  "Register a story + variant carrying `xray-preset` as its `:xray` slot."
  [variant-id preset]
  (rf.story/reg-story :story.filt
    {:doc "filters" :component :Some.view})
  (rf.story/reg-variant variant-id
    {:doc "v" :xray preset})
  variant-id)

(defn- bundle
  "Minimal event-bundle shaped as Xray's matcher reads it."
  [event-id]
  {:event [event-id {}]})

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
    (is (true? (rf.story.xray-preset/disable-keybinding!))
        "disable-keybinding! returns true when the configure! call landed")
    (is (false? (xray-config/keybinding-attach-enabled?))
        "after disable-keybinding!, Xray's slot is false")
    ;; Restore the default so subsequent tests in this suite see a
    ;; clean slot.
    (xray-config/set-keybinding-enabled! true)))

(deftest disable-keybinding-shimmed-configure
  (testing "disable-keybinding! calls Xray's configure! with the exact slot map"
    ;; Belt-and-braces test: redef Xray's `configure!` var directly
    ;; (the bridge calls it through a declared `:require`, not a
    ;; runtime symbol lookup — rf2-r8trk) so we capture the exact opts
    ;; map. Guards the payload shape against accidental extras / typos.
    (let [captured (atom nil)]
      (with-redefs [xray-config/configure!
                    (fn [opts] (reset! captured opts) nil)]
        (is (true? (rf.story.xray-preset/disable-keybinding!))
            "returns true when the configure! call landed")
        (is (= {:rf.xray/keybinding-enabled? false} @captured)
            "configure! is called with exactly the keybinding-disable slot")))))

;; ---- detach-keybinding! (rf2-ycrt2) --------------------------------------

(deftest detach-keybinding-drives-xray-keybinding-detach
  (testing "detach-keybinding! removes Xray's global keydown listener"
    ;; Belt-and-braces test: redef Xray's `detach!` var directly. The
    ;; bridge calls it through a declared `:require` (rf2-r8trk), so
    ;; the assertion mirrors that direct-reference contract rather
    ;; than a runtime symbol lookup.
    (let [called? (atom false)]
      (with-redefs [xray-keybinding/detach!
                    (fn [] (reset! called? true) nil)]
        (is (true? (rf.story.xray-preset/detach-keybinding!))
            "returns true when keybinding/detach! is reachable")
        (is (true? @called?)
            "keybinding/detach! was driven by the bridge")))))

;; ---- wire-cross-host! drives the bridges (rf2-ee38b.3) -------------------
;;
;; The shim `ensure-xray-mounted!` was removed; the legacy whole-shell
;; open is `(do (wire-cross-host!) (apply-open!))`. These tests pin the
;; same bridge ordering against that composition.

(deftest wire-cross-host-disables-keybinding
  (testing "wire-cross-host! drives disable-keybinding! + detach-keybinding!;
            the composed (wire-cross-host! + apply-open!) still opens"
    ;; The embed wires cross-host config on every variant-selection edge.
    ;; Verify the keybinding bridges fire. We shim the bridges so we can
    ;; assert the wiring without depending on the underlying configure!
    ;; plumbing (covered by the shimmed-configure test above), and shim
    ;; `apply-open!` so we don't actually mount a shell.
    (let [disable-called? (atom false)
          detach-called?  (atom false)
          open-called?    (atom false)]
      (with-redefs [rf.story.xray-preset/disable-keybinding!
                    (fn [] (reset! disable-called? true) true)
                    rf.story.xray-preset/detach-keybinding!
                    (fn [] (reset! detach-called? true) true)
                    rf.story.xray-preset/apply-open!
                    (fn [] (reset! open-called? true) nil)]
        (rf.story.xray-preset/wire-cross-host!)
        (rf.story.xray-preset/apply-open!)
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
      (with-redefs [rf.story.xray-preset/disable-keybinding!
                    (fn [] (swap! calls conj :disable) true)
                    rf.story.xray-preset/detach-keybinding!
                    (fn [] (swap! calls conj :detach) true)
                    rf.story.xray-preset/apply-open!
                    (fn [] (swap! calls conj :open) nil)]
        (rf.story.xray-preset/wire-cross-host!)
        (rf.story.xray-preset/apply-open!)
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
      ;; Drive the cross-host bridge for real — `disable-keybinding!`
      ;; and `detach-keybinding!` reference Xray's live config /
      ;; keybinding namespaces through declared `:require`s
      ;; (rf2-r8trk), so no availability shim is needed. No shell mount
      ;; happens — `wire-cross-host!` never calls `apply-open!`.
      (rf.story.xray-preset/wire-cross-host!)
      (is (false? (xray-config/keybinding-attach-enabled?))
          "wire-cross-host! flipped the slot to false")
      (is (false? (xray-keybinding/attached?))
          "wire-cross-host! removed the listener (rf2-ycrt2 runtime gap closed)")
      (finally
        ;; Restore defaults so neighbouring tests see the baseline.
        (xray-config/set-keybinding-enabled! true)
        (when (exists? js/document)
          (xray-keybinding/detach!))))))

;; ---- apply-preset! -------------------------------------------------------
;;
;; rf2-r8trk moved the three tests below out of the `.cljc` sibling
;; `re-frame.story.xray-preset-test`. That namespace does not match the
;; `:node-test` build's `cljs-test$` ns-regexp, so its `#?(:cljs …)`
;; blocks ran on no host at all — dead code that read as coverage.
;;
;; rf2-r8trk also retired a fourth, `cljs-apply-preset-no-xray-no-op`:
;; it shimmed `xray-available?` to `false` to exercise an absent-Xray
;; posture the artefact cannot reach. `day8/re-frame2-xray` is a
;; declared Story dependency, so a build that resolves
;; `re-frame.story.xray-preset` has already resolved Xray's mount ns,
;; and the predicate it shimmed no longer exists.

(deftest apply-preset-nil-on-missing-preset
  (testing "no :xray slot → no work, returns nil"
    (rf.story/reg-story :story.nilpre
      {:doc "no slot"
       :component :Some.view})
    (rf.story/reg-variant :story.nilpre/v
      {:doc "v"})
    (is (nil? (rf.story.xray-preset/apply-preset! :story.nilpre/v)))))

;; ---- :filters preset drives Xray's real filter surface (rf2-q5pd6) -------
;;
;; Before this bead the `:filters` slot was accepted by the schema,
;; produced no validation error, and did nothing: `apply-filters!` probed
;; `day8.re-frame2-xray.filters.config/configure!`, a namespace Xray has
;; never shipped, so the detect was permanently false and every non-empty
;; preset only warned.
;;
;; These tests assert the REAL `:rf/xray` `:active-filters` slot and a
;; real matcher outcome. They deliberately do NOT assert that a
;; `configure!` shim was called — a shimmed-call assertion is exactly the
;; shape that let an inert preset read as covered.

(deftest filters-preset-lands-on-live-active-filters-slot
  (testing "a schema-valid {:out [:app/noise]} preset becomes Xray's
            canonical pill shape in the live :active-filters slot"
    (install-xray-frame!)
    (let [vid (reg-filtered-variant! :story.filt/out {:filters {:out [:app/noise]}})]
      (is (= {:filters {:out [:app/noise]}} (rf.story.xray-preset/apply-preset! vid))
          "apply-preset! returns the resolved preset")
      (is (= {:in [] :out [{:pattern :app/noise}]} (active-filters))
          "the live slot carries Xray's pill shape, not Story's bare keyword"))))

(deftest filters-preset-actually-matches-events
  (testing "the landed pill matches the declared event and nothing else —
            this is the assertion that proves the preset FILTERS rather
            than merely occupying the slot. A bare keyword handed over
            unlowered canonicalises to :never and would match nothing."
    (install-xray-frame!)
    (let [vid (reg-filtered-variant! :story.filt/match {:filters {:out [:app/noise]}})]
      (rf.story.xray-preset/apply-preset! vid)
      (let [pill (first (:out (active-filters)))]
        (is (true? (xray-typed/event-bundle-matches-pill? (bundle :app/noise) pill))
            "the OUT pill matches the event-bundle the story declared")
        (is (false? (xray-typed/event-bundle-matches-pill? (bundle :app/signal) pill))
            "and does not match an unrelated event")
        ;; The regression this bead closes, stated directly: Story's
        ;; own wire shape must not reach Xray unlowered.
        (is (false? (xray-typed/event-bundle-matches-pill? (bundle :app/noise) :app/noise))
            "a BARE keyword canonicalises to :never — the inert shape")))))

(deftest filters-preset-lands-on-both-axes
  (testing ":in and :out both lower and both land"
    (install-xray-frame!)
    (let [vid (reg-filtered-variant! :story.filt/both
                {:filters {:in [:keep/a] :out [:drop/b]}})]
      (rf.story.xray-preset/apply-preset! vid)
      (is (= {:in [{:pattern :keep/a}] :out [{:pattern :drop/b}]}
             (active-filters))))))

(deftest filters-preset-seeds-xray-config-surface
  (testing "the preset also seeds Xray's established host-seed surface
            (:rf.xray/filters), the value filters/hydrate! reads"
    (install-xray-frame!)
    (xray-config/set-filter-seed! nil)
    (try
      (let [vid (reg-filtered-variant! :story.filt/seed {:filters {:out [:app/noise]}})]
        (rf.story.xray-preset/apply-preset! vid)
        (is (= {:in [] :out [{:pattern :app/noise}]} (xray-config/get-filter-seed))
            "the seed carries the lowered pill shape too"))
      (finally (xray-config/set-filter-seed! nil)))))

;; ---- pre-first-mount: the parked set (rf2-q5pd6) -------------------------

(deftest filters-preset-parks-then-flushes-when-frame-arrives
  (testing "an initially selected variant can resolve its preset BEFORE
            the RHS panel's first mount created :rf/xray. The lowered set
            parks and the embed's post-mount flush lands it — dropping it
            would be the same silent no-op this bead removes."
    ;; Model the pre-mount world: drain any prior park, then remove the
    ;; frame so `apply-preset!` genuinely has nowhere to dispatch.
    (install-xray-frame!)
    (swap! rf.frame/frames dissoc :rf/xray)
    (is (nil? (rf.frame/frame :rf/xray))
        "precondition: the Xray frame does not exist yet")
    (let [vid (reg-filtered-variant! :story.filt/park {:filters {:out [:app/noise]}})]
      (rf.story.xray-preset/apply-preset! vid)
      ;; The RHS panel-host now mounts Xray, registering the frame; its
      ;; very next act is the flush.
      (mount-xray!)
      (is (= {:in [] :out [{:pattern :app/noise}]}
             (rf.story.xray-preset/flush-pending-filters!))
          "the flush returns the pill set it applied")
      (is (= {:in [] :out [{:pattern :app/noise}]} (active-filters))
          "and the live slot now carries it"))))

(deftest flush-pending-filters-is-idempotent
  (testing "a second panel mount does not re-apply a preset the user may
            since have edited away through the ribbon"
    (install-xray-frame!)
    (swap! rf.frame/frames dissoc :rf/xray)
    (let [vid (reg-filtered-variant! :story.filt/once {:filters {:out [:app/noise]}})]
      (rf.story.xray-preset/apply-preset! vid)
      (mount-xray!)
      (is (some? (rf.story.xray-preset/flush-pending-filters!))
          "first flush applies the parked set")
      ;; User clears the pills through the ribbon.
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/hydrate-filters {:in [] :out []}]))
      (is (nil? (rf.story.xray-preset/flush-pending-filters!))
          "second flush is a no-op — nothing is pending")
      (is (= {:in [] :out []} (active-filters))
          "the user's cleared slot survives the second mount"))))

;; ---- empty vs absent :filters (rf2-q5pd6) -------------------------------

(deftest explicit-empty-filters-clears-the-slot
  (testing "a PRESENT but empty :filters map asserts the whole filter
            state — 'this story is deliberately unfiltered' — so it
            clears whatever pills were active"
    (install-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/hydrate-filters {:in [] :out [{:pattern :stale/pill}]}]))
    (is (= [{:pattern :stale/pill}] (:out (active-filters)))
        "precondition: a stale pill is active")
    (let [vid (reg-filtered-variant! :story.filt/empty {:filters {:in [] :out []}})]
      (rf.story.xray-preset/apply-preset! vid)
      (is (= {:in [] :out []} (active-filters))
          "the explicit empty preset cleared the pills"))))

(deftest absent-filters-leaves-the-slot-alone
  (testing "a preset with NO :filters key is a cheap no-op — it must not
            clobber the user's own ribbon pills"
    (install-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/hydrate-filters {:in [] :out [{:pattern :user/pill}]}]))
    (let [vid (reg-filtered-variant! :story.filt/nofilters {:panel :trace})]
      (rf.story.xray-preset/apply-preset! vid)
      (is (= [{:pattern :user/pill}] (:out (active-filters)))
          "the user's pill survives a preset that says nothing about filters"))))

;; ---- project-root propagator (rf2-r1uod) ---------------------------------

(deftest propagate-project-root-reaches-xray
  (testing "propagate-project-root! bridges Story's root into Xray's config slot"
    ;; This test previously asserted `(false? (xray-config-available?))`
    ;; under the comment "this test assumes Xray is NOT on the
    ;; classpath". That claim was already untrue — the old `resolve-fn`
    ;; namespace-property walk was returning a false-negative for a
    ;; namespace that WAS present — and nothing caught it because the
    ;; test never ran. Xray is now a declared dependency and the bridge
    ;; calls `xray-config/configure!` through a direct `:require`, so
    ;; the honest assertion is that the propagation LANDS (rf2-r8trk).
    ;;
    ;; Seed Story's project-root via configure! — exercises the whole
    ;; configure! → set-project-root! → propagator pipeline.
    (rf.story/configure! {:rf.story/project-root "/home/me/code/my-app"})
    (try
      (is (= "/home/me/code/my-app" (rf.story.xray-preset/propagate-project-root!))
          "the propagator returns the root it bridged into Xray's slot")
      (is (= "/home/me/code/my-app" (xray-config/get-project-root))
          "the value actually landed in Xray's own config slot")
      (finally
        ;; Reset BOTH slots so neighbouring tests see the baseline —
        ;; the bridge writes through to Xray's global atom.
        (rf.story/configure! {:rf.story/project-root nil})
        (xray-config/set-project-root! nil)))))

(deftest propagate-project-root-nil-when-unset
  (testing "propagate-project-root! returns nil when Story has no project-root configured"
    ;; Clear any prior seed (the fixture resets the registrar but not
    ;; the config atom).
    (rf.story/configure! {:rf.story/project-root nil})
    (is (nil? (rf.story.xray-preset/propagate-project-root!))
        "no propagation when Story's project-root is nil")))
