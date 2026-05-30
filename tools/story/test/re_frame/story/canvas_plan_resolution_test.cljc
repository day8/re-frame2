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
  `resolve-sub-overrides` read `(:sub-overrides (registrar/handler-meta
  :variant id))` straight off the side-table, seeing ONLY the variant's OWN
  slot — dropping overrides contributed by a `:compose`d fragment or an
  `:extends` parent (which the plan compiler COMPOSES into
  `[:render-raw :sub-overrides]`). render-variant read the composed source;
  the live canvas read the bare body; they disagreed.

  These tests instead register variants / fragments on the side-table and
  compile via the DEFAULT lookup — the path the live runtime takes — and
  prove:

    1. composed-fragment + `:extends`-chain `:sub-overrides` resolve through
       the canvas's resolver (the SAME `render/resolve-render-sub-overrides`
       over `plan/variant-plan` render-variant uses) — rf2-45zvx / rf2-bhaqt;
    2. the canvas decorator resolution and render-variant's render-inputs
       resolve the SAME `[:world :decorators]` (composed + inherited) —
       rf2-hzhmv (resolution agreement) / rf2-ba86n.8;
    3. `render-variant` honestly returns `:cannot-run` with no host (the
       single render path's cannot-render state) — rf2-ba86n.8.

  Pure JVM + CLJS: `plan.cljc` + `render.cljc` + `decorators.cljc` + the
  registrar are all JVM-runnable, so the DEFAULT lookup works under both
  `clojure -M:test` and `npm run test:cljs`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.config     :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.late-bind  :as late-bind]
            [re-frame.story.plan       :as plan]
            [re-frame.story.registrar  :as registrar]
            [re-frame.story.render     :as render]
            [re-frame.registrar        :as framework-registrar]))

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
;; `late-bind/clear!` (that wipes the canonical shims every sibling test ns
;; relies on). The fixture snapshots + restores the whole hooks map so any
;; per-test dissoc is surgical and reverts cleanly (mirrors `render-test`).

(defn reset-fixture [test-fn]
  (registrar/clear-all!)
  (framework-registrar/clear-kind! :sub)
  ;; The global-decorators vector is a process-global config atom (NOT part
  ;; of the side-table `clear-all!` wipes), so a test that sets globals
  ;; would leak into siblings. Clear it before each test and restore the
  ;; pre-test value after (rf2-5fibj).
  (config/set-global-decorators! [])
  ;; `:sub-overrides` validation soft-passes when a sub carries no output
  ;; schema (the host-free floor), so an unregistered :sub is fine here.
  (let [snapshot       @late-bind/hooks
        globals-before (config/get-global-decorators)]
    (try (test-fn)
         (finally
           (reset! late-bind/hooks snapshot)
           (config/set-global-decorators! globals-before)))))

(use-fixtures :each reset-fixture)

;; The canvas's resolution (post rf2-45zvx): route through the COMPILED plan
;; via the shared render-variant resolver — NOT the bare registrar body.
;; This mirrors `re-frame.story.ui.canvas/resolve-sub-overrides` exactly
;; (canvas is CLJS-only, so the production logic it now calls is asserted
;; here at the CLJC seam both paths share).
(defn- canvas-sub-overrides
  [variant-id eff-args]
  (render/resolve-render-sub-overrides (plan/variant-plan variant-id) eff-args))

;; ===========================================================================
;; rf2-45zvx / rf2-bhaqt — composed-fragment + :extends-chain :sub-overrides
;; ===========================================================================

(deftest composed-fragment-sub-overrides-resolve-on-canvas-path
  (testing "a REGISTERED variant whose :sub-overrides come (partly) from a
            :compose'd fragment resolves the fragment's overrides through the
            canvas resolver — the bare-body read dropped them (rf2-45zvx /
            rf2-bhaqt)"
    (registrar/reg-fragment* :fragment.login/errored
                             {:sub-overrides {[:login/state] :error}})
    (registrar/reg-variant* :story.login/from-fragment
                            {:compose       [:fragment.login/errored]
                             :sub-overrides {[:login/attempts] 3}
                             :events        []})
    (let [resolved (canvas-sub-overrides :story.login/from-fragment {})]
      (testing "the variant's OWN override resolves"
        (is (= 3 (get resolved [:login/attempts]))))
      (testing "the COMPOSED fragment's override ALSO resolves — the bare-body
                read (`(:sub-overrides body)`) would have dropped it"
        (is (= :error (get resolved [:login/state]))))
      (testing "the canvas resolver agrees with the plan's composed slot"
        (is (= (get-in (plan/variant-plan :story.login/from-fragment)
                       [:world :render :sub-overrides])
               resolved))))))

(deftest extends-chain-sub-overrides-resolve-on-canvas-path
  (testing "a child variant that :extends a parent with :sub-overrides and
            declares its OWN inherits the merged map through the canvas
            resolver (rf2-45zvx — the :extends chain)"
    (registrar/reg-variant* :story.ext.subovr/parent
                            {:sub-overrides {[:login/state] :error}
                             :events        []})
    (registrar/reg-variant* :story.ext.subovr/child
                            {:extends       :story.ext.subovr/parent
                             :sub-overrides {[:login/attempts] 5}
                             :events        []})
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
    (registrar/reg-variant* :story.login/argdriven
                            {:args          {:message "default"}
                             :sub-overrides {[:login/error] [:arg :message]}
                             :events        []})
    (let [plan-eff (get-in (plan/variant-plan :story.login/argdriven)
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
    (registrar/reg-variant* :story.plain/v {:events []})
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
    (registrar/reg-decorator* :deco/theme-dark
                              {:kind :hiccup :wrap (fn [body _] [:div.dark body])})
    (registrar/reg-decorator* :deco/centered
                              {:kind :hiccup :wrap (fn [body _] [:div.centered body])})
    ;; Parent declares a decorator; child inherits via :extends and ADDS none
    ;; of its own — so the child's decorator stack comes ENTIRELY from the
    ;; parent chain (the case a bare-body read would resolve EMPTY).
    (registrar/reg-variant* :story.deco/parent
                            {:component  :views/widget
                             :decorators [[:deco/theme-dark]]
                             :events     []})
    (registrar/reg-variant* :story.deco/child
                            {:extends :story.deco/parent
                             :events  []})
    (let [;; render-variant's render-inputs carry the RAW refs off the plan.
          prepared       (render/prepare-render :story.deco/child)
          rv-refs        (get-in prepared [:render-inputs :decorators])
          ;; the canvas resolves the same refs into its :hiccup pack.
          canvas-pack    (decorators/resolve-decorators :story.deco/child)
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
               (mapv :id (:hiccup (decorators/resolve-decorator-refs rv-refs)))))))))

(deftest canvas-and-render-variant-agree-on-full-decorator-stack
  (testing "the canvas + render-variant resolve the SAME FULL decorator
            stack — GLOBAL + parent-STORY + variant — off the ONE compiled
            [:world :decorators] (rf2-5fibj). The prior
            `canvas-and-render-variant-agree-on-decorators` test exercised
            only variant + :extends decorators, so the host-path drop of
            globals + story slipped CI green (the din8u CI-blind-spot, one
            layer up): the plan folded only the variant chain, the canvas
            re-assembled globals+story+variant, so they DIVERGED."
    (registrar/reg-decorator* :deco/global-theme
                              {:kind :hiccup :wrap (fn [body _] [:div.global body])})
    (registrar/reg-decorator* :deco/story-frame
                              {:kind :hiccup :wrap (fn [body _] [:div.story body])})
    (registrar/reg-decorator* :deco/variant-pad
                              {:kind :hiccup :wrap (fn [body _] [:div.variant body])})
    ;; GLOBAL decorator (the layer the host path dropped) — Storybook
    ;; preview.ts parity, rf2-835ey.
    (config/set-global-decorators! [[:deco/global-theme]])
    ;; parent-STORY decorator (the OTHER layer the host path dropped) — the
    ;; variant id's namespace resolves to this story.
    (registrar/reg-story* :story.fullstack
                          {:decorators [[:deco/story-frame]]})
    ;; the variant adds its own decorator on top.
    (registrar/reg-variant* :story.fullstack/v
                            {:component  :views/widget
                             :decorators [[:deco/variant-pad]]
                             :events     []})
    (let [;; render-variant's render-inputs carry the FULL stack off the plan.
          prepared    (render/prepare-render :story.fullstack/v)
          rv-refs     (get-in prepared [:render-inputs :decorators])
          ;; the canvas resolves the same plan-sourced refs into its pack.
          canvas-pack (decorators/resolve-decorators :story.fullstack/v)
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
               (mapv :id (:hiccup (decorators/resolve-decorator-refs rv-refs))))))
      (testing "and applying the stack wraps the leaf globals-outermost,
                variant-innermost (the rendered tree both paths paint)"
        (is (= [:div.global [:div.story [:div.variant [:span "leaf"]]]]
               (decorators/apply-hiccup-decorators
                 (:hiccup canvas-pack) [:span "leaf"] {})))))))

(deftest render-variant-applies-decorators-through-shared-seam
  (testing "render-variant's host renders the SAME decorator refs the canvas
            applies — proven by resolving the render-inputs' refs and applying
            them the way the shared seam does (rf2-hzhmv)"
    (registrar/reg-decorator* :deco/wrap-a
                              {:kind :hiccup :wrap (fn [body _] [:div.a body])})
    (registrar/reg-variant* :story.deco/applied
                            {:component  :views/widget
                             :decorators [[:deco/wrap-a]]
                             :events     []})
    (let [prepared (render/prepare-render :story.deco/applied)
          refs     (get-in prepared [:render-inputs :decorators])
          hiccup-d (:hiccup (decorators/resolve-decorator-refs refs))
          ;; The shared `safe-decorated-view` seam applies the :hiccup
          ;; decorators outermost-first; render-variant's host calls exactly
          ;; this (via multi-substrate/render-decorated-view).
          wrapped  (decorators/apply-hiccup-decorators hiccup-d [:span "leaf"] {})]
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
    (registrar/reg-variant* :story.norender/v
                            {:component :views/widget :events []})
    ;; Drop the render host AFTER registration — on CLJS the `reg-variant*`
    ;; auto-install wires it; the fixture restores it after this test.
    (swap! late-bind/hooks dissoc :render-host)
    (let [r (render/render-variant :story.norender/v)]
      (is (= :cannot-run (:status r)))
      (is (= :no-render-host (:reason r)))
      (is (= :story.norender/v (:frame r))))))
