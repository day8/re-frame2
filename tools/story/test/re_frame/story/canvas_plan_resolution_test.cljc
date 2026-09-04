(ns re-frame.story.canvas-plan-resolution-test
  "Production-path regression gate for the rf2-din8u phase-2 consolidation:
  the canvas + `render-variant` resolve sub-overrides AND decorators off the
  ONE compiled variant-plan, not the bare registrar body (rf2-45zvx /
  rf2-bhaqt / rf2-hzhmv / rf2-ba86n.8).

  ## The CI blind spot this closes

  Per the rf2-din8u ruling, EVERY fix must add a regression test exercising
  the PRODUCTION path — a REGISTERED variant compiled via the DEFAULT
  side-table lookup (NO `:lookup` arg). The pre-ruling tests fed RAW bodies
  through explicit `:lookup` maps, which masked the divergence: the canvas
  `resolve-sub-overrides` read `(:sub-overrides (rf.story.registrar/handler-meta
  :variant id))` straight off the side-table, seeing ONLY the variant's OWN
  slot — dropping overrides contributed by a `:compose`d fragment or an
  `:extends` parent (which the plan compiler COMPOSES into
  `[:render-raw :sub-overrides]`). render-variant read the composed source;
  the live canvas read the bare body; they disagreed.

  These tests instead register variants / fragments on the side-table and
  compile via the DEFAULT lookup — the path the live runtime takes — and
  prove:

    1. composed-fragment + `:extends`-chain `:sub-overrides` resolve through
       the canvas's resolver (the SAME `rf.story.render/resolve-render-sub-overrides`
       over `rf.story.plan/variant-plan` render-variant uses) — rf2-45zvx / rf2-bhaqt;
    2. the canvas decorator resolution and render-variant's render-inputs
       resolve the SAME `[:world :decorators]` (composed + inherited) —
       rf2-hzhmv (resolution agreement) / rf2-ba86n.8;
    3. `render-variant` honestly returns `:cannot-run` with no host (the
       single render path's cannot-render state) — rf2-ba86n.8.

  Pure JVM + CLJS: `plan.cljc` + `render.cljc` + `decorators.cljc` + the
  registrar are all JVM-runnable, so the DEFAULT lookup works under both
  `clojure -M:test` and `npm run test:cljs`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.decorators :as rf.story.decorators]
            [re-frame.story.late-bind  :as rf.story.late-bind]
            [re-frame.story.plan       :as rf.story.plan]
            [re-frame.story.registrar  :as rf.story.registrar]
            [re-frame.story.render     :as rf.story.render]
            [re-frame.registrar        :as rf.registrar]))

;; ---- fixtures ------------------------------------------------------------
;;
;; Wipe the Story side-table + framework registrars so the DEFAULT lookup
;; sees only what each test registers. `clear-all!` bumps the mutation-tick
;; (invalidating any plan-level memo between tests).
;;
;; The `:render-host` hook is a process-global late-bind slot. On CLJS the
;; canonical auto-install (fired by every `reg-*`) wires it, so the no-host
;; `:cannot-run` assertion must DROP `:render-host` AFTER its registrations
;; (see `render-variant-cannot-run-with-no-host`). We MUST NOT
;; `rf.story.late-bind/clear!` (that wipes the canonical shims every sibling test ns
;; relies on). The fixture snapshots + restores the whole hooks map so any
;; per-test dissoc is surgical and reverts cleanly (mirrors `render-test`).

(defn reset-fixture [test-fn]
  (rf.story.registrar/clear-all!)
  (rf.registrar/clear-kind! :sub)
  ;; The global-decorators vector is a process-global config atom (NOT part
  ;; of the side-table `clear-all!` wipes), so a test that sets globals
  ;; would leak into siblings. Clear it before each test and restore the
  ;; pre-test value after (rf2-5fibj).
  (rf.story.config/set-global-decorators! [])
  ;; `:sub-overrides` validation soft-passes when a sub carries no output
  ;; schema (the host-free floor), so an unregistered :sub is fine here.
  (let [snapshot       @rf.story.late-bind/hooks
        globals-before (rf.story.config/get-global-decorators)]
    (try (test-fn)
         (finally
           (reset! rf.story.late-bind/hooks snapshot)
           (rf.story.config/set-global-decorators! globals-before)))))

(use-fixtures :each reset-fixture)

;; The canvas's resolution (post rf2-45zvx): route through the COMPILED plan
;; via the shared render-variant resolver — NOT the bare registrar body.
;; This mirrors `re-frame.story.ui.canvas/resolve-sub-overrides` exactly
;; (canvas is CLJS-only, so the production logic it now calls is asserted
;; here at the CLJC seam both paths share).
(defn- canvas-sub-overrides
  [variant-id eff-args]
  (rf.story.render/resolve-render-sub-overrides (rf.story.plan/variant-plan variant-id) eff-args))

;; ===========================================================================
;; rf2-45zvx / rf2-bhaqt — composed-fragment + :extends-chain :sub-overrides
;; ===========================================================================

(deftest composed-fragment-sub-overrides-resolve-on-canvas-path
  (testing "a REGISTERED variant whose :sub-overrides come (partly) from a
            :compose'd fragment resolves the fragment's overrides through the
            canvas resolver — the bare-body read dropped them (rf2-45zvx /
            rf2-bhaqt)"
    (rf.story.registrar/reg-fragment* :fragment.login/errored
                             {:sub-overrides {[:login/state] :error}})
    (rf.story.registrar/reg-variant* :story.login/from-fragment
                            {:compose       [:fragment.login/errored]
                             :sub-overrides {[:login/attempts] 3}
                             :setup        []})
    (let [resolved (canvas-sub-overrides :story.login/from-fragment {})]
      (testing "the variant's OWN override resolves"
        (is (= 3 (get resolved [:login/attempts]))))
      (testing "the COMPOSED fragment's override ALSO resolves — the bare-body
                read (`(:sub-overrides body)`) would have dropped it"
        (is (= :error (get resolved [:login/state]))))
      (testing "the canvas resolver agrees with the plan's composed slot"
        (is (= (get-in (rf.story.plan/variant-plan :story.login/from-fragment)
                       [:world :render :sub-overrides])
               resolved))))))

(deftest extends-chain-sub-overrides-resolve-on-canvas-path
  (testing "a child variant that :extends a parent with :sub-overrides and
            declares its OWN inherits the merged map through the canvas
            resolver (rf2-45zvx — the :extends chain)"
    (rf.story.registrar/reg-variant* :story.ext.subovr/parent
                            {:sub-overrides {[:login/state] :error}
                             :setup        []})
    (rf.story.registrar/reg-variant* :story.ext.subovr/child
                            {:extends       :story.ext.subovr/parent
                             :sub-overrides {[:login/attempts] 5}
                             :setup        []})
    (let [resolved (canvas-sub-overrides :story.ext.subovr/child {})]
      (testing "the child's OWN override resolves"
        (is (= 5 (get resolved [:login/attempts]))))
      (testing "the parent-chain override is INHERITED (rf2-din8u: the plan
                compiler is the single :extends merge authority)"
        (is (= :error (get resolved [:login/state])))))))

(deftest sub-override-arg-placeholder-reflects-control-on-canvas-path
  (testing "an override VALUE driven by [:arg key] re-resolves against the
            post-control effective args on the canvas path — the SAME
            re-substitution render-variant does. The canvas passes the
            variant's resolved effective args (incl. defaults), so the
            placeholder always has a value to substitute."
    (rf.story.registrar/reg-variant* :story.login/argdriven
                            {:args          {:message "default"}
                             :sub-overrides {[:login/error] [:arg :message]}
                             :setup        []})
    (let [plan-eff (get-in (rf.story.plan/variant-plan :story.login/argdriven)
                           [:world :effective-args])]
      (testing "the plan-time arg resolves against the variant's effective args"
        (is (= "default"
               (get (canvas-sub-overrides :story.login/argdriven plan-eff)
                    [:login/error]))))
      (testing "a control override re-substitutes the live value"
        (is (= "live!"
               (get (canvas-sub-overrides :story.login/argdriven
                                          (assoc plan-eff :message "live!"))
                    [:login/error])))))))

(deftest variant-with-no-sub-overrides-resolves-nil
  (testing "a registered variant authoring NO :sub-overrides resolves nil
            (render-transparent — no wrapper)"
    (rf.story.registrar/reg-variant* :story.plain/v {:setup []})
    (is (nil? (canvas-sub-overrides :story.plain/v {})))))

;; ===========================================================================
;; rf2-hzhmv / rf2-ba86n.8 — canvas + render-variant resolve the SAME
;; decorators off the compiled [:world :decorators]
;; ===========================================================================

(deftest canvas-and-render-variant-agree-on-decorators
  (testing "the canvas decorator resolution and render-variant's
            render-inputs resolve the SAME composed + inherited
            [:world :decorators] — single source of truth (rf2-hzhmv /
            rf2-ba86n.8)"
    (rf.story.registrar/reg-decorator* :deco/theme-dark
                              {:kind :hiccup :wrap (fn [body _] [:div.dark body])})
    (rf.story.registrar/reg-decorator* :deco/centered
                              {:kind :hiccup :wrap (fn [body _] [:div.centered body])})
    ;; Parent declares a decorator; child inherits via :extends and ADDS none
    ;; of its own — so the child's decorator stack comes ENTIRELY from the
    ;; parent chain (the case a bare-body read would resolve EMPTY).
    (rf.story.registrar/reg-variant* :story.deco/parent
                            {:component  :views/widget
                             :decorators [[:deco/theme-dark]]
                             :setup     []})
    (rf.story.registrar/reg-variant* :story.deco/child
                            {:extends :story.deco/parent
                             :setup  []})
    (let [;; render-variant's render-inputs carry the RAW refs off the plan.
          prepared       (rf.story.render/prepare-render :story.deco/child)
          rv-refs        (get-in prepared [:render-inputs :decorators])
          ;; the canvas resolves the same refs into its :hiccup pack.
          canvas-pack    (rf.story.decorators/resolve-decorators :story.deco/child)
          canvas-ids     (mapv :id (:hiccup canvas-pack))]
      (testing "render-variant's render-inputs carry the inherited decorator
                refs off the compiled plan"
        (is (= [[:deco/theme-dark]] rv-refs)))
      (testing "the canvas resolves the SAME inherited decorator (NOT empty —
                the bare-body read dropped the :extends-inherited stack)"
        (is (= [:deco/theme-dark] canvas-ids)))
      (testing "both paths agree: render-variant's refs resolve to the canvas
                pack's :hiccup ids"
        (is (= canvas-ids
               (mapv :id (:hiccup (rf.story.decorators/resolve-decorator-refs rv-refs)))))))))

(deftest canvas-and-render-variant-agree-on-full-decorator-stack
  (testing "the canvas + render-variant resolve the SAME FULL decorator
            stack — GLOBAL + parent-STORY + variant — off the ONE compiled
            [:world :decorators] (rf2-5fibj). The prior
            `canvas-and-render-variant-agree-on-decorators` test exercised
            only variant + :extends decorators, so the host-path drop of
            globals + story slipped CI green (the din8u CI-blind-spot, one
            layer up): the plan folded only the variant chain, the canvas
            re-assembled globals+story+variant, so they DIVERGED."
    (rf.story.registrar/reg-decorator* :deco/global-theme
                              {:kind :hiccup :wrap (fn [body _] [:div.global body])})
    (rf.story.registrar/reg-decorator* :deco/story-frame
                              {:kind :hiccup :wrap (fn [body _] [:div.story body])})
    (rf.story.registrar/reg-decorator* :deco/variant-pad
                              {:kind :hiccup :wrap (fn [body _] [:div.variant body])})
    ;; GLOBAL decorator (the layer the host path dropped) — Storybook
    ;; preview.ts parity, rf2-835ey.
    (rf.story.config/set-global-decorators! [[:deco/global-theme]])
    ;; parent-STORY decorator (the OTHER layer the host path dropped) — the
    ;; variant id's namespace resolves to this story.
    (rf.story.registrar/reg-story* :story.fullstack
                          {:decorators [[:deco/story-frame]]})
    ;; the variant adds its own decorator on top.
    (rf.story.registrar/reg-variant* :story.fullstack/v
                            {:component  :views/widget
                             :decorators [[:deco/variant-pad]]
                             :setup     []})
    (let [;; render-variant's render-inputs carry the FULL stack off the plan.
          prepared    (rf.story.render/prepare-render :story.fullstack/v)
          rv-refs     (get-in prepared [:render-inputs :decorators])
          ;; the canvas resolves the same plan-sourced refs into its pack.
          canvas-pack (rf.story.decorators/resolve-decorators :story.fullstack/v)
          canvas-ids  (mapv :id (:hiccup canvas-pack))]
      (testing "the compiled plan carries the FULL stack in order: global
                outermost, then story, then variant (NOT just the variant
                chain — the pre-rf2-5fibj plan dropped globals + story)"
        (is (= [[:deco/global-theme] [:deco/story-frame] [:deco/variant-pad]]
               rv-refs)))
      (testing "the canvas pack resolves the SAME full stack in the SAME order"
        (is (= [:deco/global-theme :deco/story-frame :deco/variant-pad]
               canvas-ids)))
      (testing "both paths agree: render-variant's refs resolve to the canvas
                pack's :hiccup ids — IDENTICAL decorated tree"
        (is (= canvas-ids
               (mapv :id (:hiccup (rf.story.decorators/resolve-decorator-refs rv-refs))))))
      (testing "and applying the stack wraps the leaf globals-outermost,
                variant-innermost (the rendered tree both paths paint)"
        (is (= [:div.global [:div.story [:div.variant [:span "leaf"]]]]
               (rf.story.decorators/apply-hiccup-decorators
                 (:hiccup canvas-pack) [:span "leaf"] {})))))))

(deftest render-variant-applies-decorators-through-shared-seam
  (testing "render-variant's host renders the SAME decorator refs the canvas
            applies — proven by resolving the render-inputs' refs and applying
            them the way the shared seam does (rf2-hzhmv)"
    (rf.story.registrar/reg-decorator* :deco/wrap-a
                              {:kind :hiccup :wrap (fn [body _] [:div.a body])})
    (rf.story.registrar/reg-variant* :story.deco/applied
                            {:component  :views/widget
                             :decorators [[:deco/wrap-a]]
                             :setup     []})
    (let [prepared (rf.story.render/prepare-render :story.deco/applied)
          refs     (get-in prepared [:render-inputs :decorators])
          hiccup-d (:hiccup (rf.story.decorators/resolve-decorator-refs refs))
          ;; The shared `safe-decorated-view` seam applies the :hiccup
          ;; decorators outermost-first; render-variant's host calls exactly
          ;; this (via multi-substrate/render-decorated-view).
          wrapped  (rf.story.decorators/apply-hiccup-decorators hiccup-d [:span "leaf"] {})]
      (testing "the decorator wraps the rendered tree (NOT bare — the
                pre-fix host dropped :decorators entirely)"
        (is (= [:div.a [:span "leaf"]] wrapped))))))

;; ===========================================================================
;; rf2-ba86n.8 — the single render path's cannot-render honesty
;; ===========================================================================

(deftest render-variant-cannot-run-with-no-host
  (testing "with no render host installed, render-variant returns :cannot-run
            for a registered variant — never a silent empty render
            (the single render path's honest cannot-render state)"
    (rf.story.registrar/reg-variant* :story.norender/v
                            {:component :views/widget :setup []})
    ;; Drop the render host AFTER registration — on CLJS the `reg-variant*`
    ;; auto-install wires it; the fixture restores it after this test.
    (swap! rf.story.late-bind/hooks dissoc :render-host)
    (let [r (rf.story.render/render-variant :story.norender/v)]
      (is (= :cannot-run (:status r)))
      (is (= :no-render-host (:reason r)))
      (is (= :story.norender/v (:frame r))))))

;; ===========================================================================
;; rf2-eyrpr — the canvas decorator path threads :run-args into the plan it
;; recompiles to read [:world :decorators].
;;
;; rf2-2cpoo (#3248) threaded run opts into the RUNTIME plan compile
;; (`prepare-context`), but the CANVAS decorator path
;; (`rf.story.decorators/resolve-decorators`, the front door the live canvas /
;; controls / docs panes call) still recompiled the plan WITHOUT them. That
;; recompile substitutes EVERY `[:arg key]` in the variant body (db-seed /
;; sub-overrides / setup / script) against the arg-map — so a key resolvable
;; ONLY through a mode / cell / global / story layer (never the variant
;; chain) threw `:rf.error/story-missing-arg` at decorator-resolution time,
;; even though the runtime compile (with run opts) substituted it cleanly.
;; The fix threads the SAME `{:active-modes :cell-overrides}` opts the canvas
;; already builds for `resolve-args` through `resolve-decorators` →
;; `collect-decorator-refs` → `variant-plan {:run-args …}`.
;;
;; These tests register on the DEFAULT side-table and call
;; `rf.story.decorators/resolve-decorators` (the production front door) — proving the
;; new capability resolves AND, critically, that the no-opts path STILL
;; throws (so the test would catch a regression / proves the gap was real).
;; ===========================================================================

(defn- missing-arg-throw?
  "True iff `thunk` throws the plan-compile `:rf.error/story-missing-arg`."
  [thunk]
  (try (thunk) false
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (= :rf.error/story-missing-arg (:rf.error/id (ex-data e))))))

(deftest canvas-decorator-resolution-threads-active-mode-arg
  (testing "a variant whose body carries `[:arg :only-in-mode]` for a key the
            variant chain NEVER declares (supplied ONLY by an active mode)
            resolves its decorator stack through the canvas front door when the
            active mode is threaded — the recompile no longer throws
            `:rf.error/story-missing-arg` (rf2-eyrpr)"
    (rf.story.registrar/reg-decorator* :deco/mode-wrap
                              {:kind :hiccup :wrap (fn [body _] [:div.mode body])})
    ;; the mode supplies :only-in-mode; the variant declares NO :args, so the
    ;; key is reachable ONLY through the mode layer (the rf2-2cpoo new
    ;; capability — `mode < variant`, mode fills the arg the variant omits).
    (rf.story.registrar/reg-mode* :Mode.canvas/big {:args {:only-in-mode "from-mode"}})
    (rf.story.registrar/reg-variant* :story.canvas.modearg/v
                            {:component  :views/widget
                             :decorators [[:deco/mode-wrap]]
                             ;; `[:arg :only-in-mode]` substitutes at PLAN
                             ;; compile, inside the `variant-plan` the canvas
                             ;; recompiles to read `[:world :decorators]`.
                             :db-seed    {:seeded [:arg :only-in-mode]}
                             :setup     []})
    (testing "the OLD no-opts front door throws — the gap rf2-2cpoo left
              (proves the test exercises the actual failing path)"
      (is (missing-arg-throw?
            #(rf.story.decorators/resolve-decorators :story.canvas.modearg/v))
          "without :run-args the recompile cannot substitute the mode-only arg"))
    (testing "threading the active mode resolves the decorator stack cleanly"
      (let [pack (rf.story.decorators/resolve-decorators
                   :story.canvas.modearg/v
                   {:active-modes [:Mode.canvas/big]})]
        (is (= [:deco/mode-wrap] (mapv :id (:hiccup pack)))
            "the variant's :hiccup decorator resolves (no throw)")
        (is (empty? (:errors pack))
            "no resolution errors — the mode-only [:arg] substituted")))))

(deftest canvas-decorator-resolution-threads-cell-override-arg
  (testing "a `:cell-override` supplies the SOLE source of an `[:arg key]` in
            the variant body; the canvas front door resolves the decorator
            stack when the override is threaded (rf2-eyrpr — the highest run
            layer, same as a mode at `cell-override > variant`)"
    (rf.story.registrar/reg-decorator* :deco/cell-wrap
                              {:kind :hiccup :wrap (fn [body _] [:div.cell body])})
    (rf.story.registrar/reg-variant* :story.canvas.cellarg/v
                            {:component  :views/widget
                             :decorators [[:deco/cell-wrap]]
                             :db-seed    {:seeded [:arg :only-in-cell]}
                             :setup     []})
    (testing "the no-opts front door throws (the cell key is variant-absent)"
      (is (missing-arg-throw?
            #(rf.story.decorators/resolve-decorators :story.canvas.cellarg/v))))
    (testing "threading the cell-override resolves the stack cleanly"
      (let [pack (rf.story.decorators/resolve-decorators
                   :story.canvas.cellarg/v
                   {:cell-overrides {:only-in-cell "from-cell"}})]
        (is (= [:deco/cell-wrap] (mapv :id (:hiccup pack))))
        (is (empty? (:errors pack)))))))

(deftest canvas-decorator-resolution-unaffected-when-arg-in-variant-chain
  (testing "the COMMON case is unchanged: when every `[:arg key]` is declared
            on the variant itself, the no-opts front door resolves fine — the
            run-args threading is purely ADDITIVE (rf2-eyrpr regression guard)"
    (rf.story.registrar/reg-decorator* :deco/plain-wrap
                              {:kind :hiccup :wrap (fn [body _] [:div.plain body])})
    (rf.story.registrar/reg-variant* :story.canvas.ownarg/v
                            {:component  :views/widget
                             :decorators [[:deco/plain-wrap]]
                             :args       {:in-variant "static"}
                             :db-seed    {:seeded [:arg :in-variant]}
                             :setup     []})
    (testing "no-opts resolves (the variant declares the key)"
      (is (= [:deco/plain-wrap]
             (mapv :id (:hiccup (rf.story.decorators/resolve-decorators
                                  :story.canvas.ownarg/v))))))
    (testing "and threading run opts resolves the IDENTICAL stack"
      (is (= (mapv :id (:hiccup (rf.story.decorators/resolve-decorators
                                  :story.canvas.ownarg/v)))
             (mapv :id (:hiccup (rf.story.decorators/resolve-decorators
                                  :story.canvas.ownarg/v
                                  {:active-modes [] :cell-overrides {}}))))))))

(deftest resolution-fingerprints-threads-run-args-without-throwing
  (testing "the hot-reload fingerprint poll (`resolution-fingerprints`) threads
            the per-run opts too, so a mode-only `[:arg]` variant does not
            throw on the 500ms poll — fingerprints are body-derived + run-layer
            invariant, the opts only let the ref-collection compile succeed
            (rf2-eyrpr)"
    (rf.story.registrar/reg-decorator* :deco/fp-wrap
                              {:kind :hiccup :wrap (fn [body _] [:div.fp body])})
    (rf.story.registrar/reg-mode* :Mode.fp/on {:args {:only-in-mode "x"}})
    (rf.story.registrar/reg-variant* :story.canvas.fp/v
                            {:component  :views/widget
                             :decorators [[:deco/fp-wrap]]
                             :db-seed    {:seeded [:arg :only-in-mode]}
                             :setup     []})
    (testing "the no-opts poll throws (the gap)"
      (is (missing-arg-throw?
            #(rf.story.decorators/resolution-fingerprints :story.canvas.fp/v))))
    (testing "threading the active mode lets the poll capture the fingerprint"
      (let [fps (rf.story.decorators/resolution-fingerprints
                  :story.canvas.fp/v
                  {:active-modes [:Mode.fp/on]})]
        (is (contains? fps :deco/fp-wrap)
            "the decorator's fingerprint is captured (no throw)")))))
