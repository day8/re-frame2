(ns re-frame.scxml-conformance-cljs-test
  "SCXML semantic-core conformance corpus for the re-frame2 machine engine.

  NOTE — the namespace is named `*-cljs-test` (file `*_cljs_test.cljc`) so
  it is discovered by BOTH the shadow-cljs `:node-test` build
  (`:ns-regexp \"cljs-test$\"`, run via `npm run test:cljs`) AND the JVM
  cognitect.test-runner (`.*-test$`, run via `clojure -M:test` in
  `implementation/machines`). The engine is identical across runtimes, so
  the corpus must pass on both. This mirrors the established convention for
  the artefact's dual-runtime tests (`final_state_cljs_test.cljc` et al.).

  ## Why this namespace exists

  Spec 005 asserts xstate-v5 parity for the machine engine and Spec 005
  L1300 (and the worked examples throughout) ANCHOR that parity to the
  xstate / SCXML / Harel state-chart semantics. This corpus proves that
  parity externally against the W3C SCXML conformance suite and Harel's
  original state-chart semantics — complementing the strong per-feature
  surface (compound, parallel, entry/exit ordering, `:always`, `:after`,
  guards, final/done, wildcard, unhandled-no-op, transition resolution).

  Every test below maps a re-frame2
  machine-engine behaviour onto the SCXML / Harel semantic rule it derives
  from, citing:

    - the **SCXML / W3C test id or specification clause** it mirrors,
    - the **re-frame2 spec anchor** (Spec 005 section),
    - the **event sequence** and the **expected configuration /
      action-order / output**.

  The corpus exercises the PURE engine surface
  (`re-frame.machines/machine-transition` + the pure finality predicates +
  the registration validator) so it is JVM- and CLJS-runnable from
  arguments alone — no frame, no dispatch loop, no app-db. The file is
  `*.cljc` so cognitect.test-runner (JVM) and shadow-cljs (CLJS) both
  discover it; the engine is identical across runtimes, so both must agree.

  ## Framing: the SCXML *semantic core* (history-free)

  xstate's own published SCXML conformance is exactly this CORE SUBSET —
  the state-chart semantics (transition selection, document-order conflict
  resolution, the entry/exit/LCA cascade, parallel regions + done.state,
  eventless/transient transitions, guards/conditions, wildcards,
  self-transition internal/external semantics). xstate does NOT claim
  conformance against SCXML's ECMAScript / XPath DATAMODEL tests nor the
  `<send>` / `<invoke>` external-I/O-processor tests, because those test
  an event-I/O model and a scripting datamodel that a host-language FSM
  library deliberately does not adopt. re-frame2 takes the same stance:
  the engine is CLJS-data + effects-as-data, not the SCXML datamodel /
  event-I/O model.

  This corpus therefore covers the SCXML SEMANTIC CORE and explicitly
  DOCUMENTS the excluded families in `## SCXML EXCLUSIONS` below so the
  scope boundary is auditable rather than a silent gap.

  ## SCXML EXCLUSIONS (documented, not faked)

  The following SCXML test families are DELIBERATELY OUT OF SCOPE. They are
  excluded by design — re-frame2's machine model does not adopt the SCXML
  datamodel / event-I/O processor, so faithfully porting these tests would
  require stubbing semantics re-frame2 does not have. Excluding them here
  is the same line xstate draws for its own SCXML conformance.

   1. **ECMAScript / XPath DATAMODEL tests** — SCXML `<datamodel>`,
      `<data>`, `<assign>`, `<script>`, `cond`/`expr` attribute evaluation
      against an embedded ECMAScript or XPath engine (W3C SCXML §5,
      e.g. test 144, 147, 150-156, 277, 286, 300-355 datamodel family).
      re-frame2 SUBSTITUTE: `:data` is a plain CLJS map; guards/actions are
      host-language fns (`(fn [{:keys [data event]}] ...)`), not string
      expressions evaluated against a scripting datamodel. There is no
      `cond`/`expr` string-evaluation surface to conform against. The
      guard/action SEMANTICS (first-passing-candidate selection, action
      `:data` threading) ARE covered below under §Guards and §Transition
      selection — only the string-expression datamodel is excluded.

   2. **`<send>` / `<invoke>` external-I/O-processor tests** — SCXML
      `<send>` with delays/targets/types, the SCXML Event I/O Processor,
      `<invoke>` of external services, `<finalize>`, `_event` / `_ioprocessors`
      system variables (W3C SCXML §6, e.g. test 178-179, 187-194, 207-216,
      236-253, 338 send/invoke family).
      re-frame2 SUBSTITUTE: outbound communication is effects-as-data
      (`:fx` vectors routed by the runtime), not an SCXML I/O processor;
      actor spawning is the declarative `:spawn` / `:spawn-all` surface
      (Spec 005 §Declarative `:spawn`) which emits `:rf.machine/spawn` /
      `:rf.machine/destroy` fx. The actor-lifecycle SEMANTICS (spawn-id
      allocation, exit-cascade teardown, `:spawn-all` join) are covered by
      the artefact's own per-feature suites (`spawn_*`, `final_state_*`),
      not re-derived against SCXML's `<send>`/`<invoke>` wire model — the
      delivery mechanism diverges, so the SCXML I/O tests do not apply.

   3. **HISTORY pseudo-states** — SCXML `<history type=\"shallow|deep\">`
      (W3C SCXML §3.10, e.g. test 387, 388, 579, 580 history family).
      re-frame2 ships FIRST-CLASS history:
      `{:type :history :deep? <bool> :default-target <t>}` is
      a targetable pseudo-state under a compound's `:states`. History is
      COVERED, not excluded — the §History section below carries BOTH the
      registration-time GRAMMAR-CONSTRAINT rejections (a `:type :history` node
      MUST have an owning compound — `:rf.error/machine-history-misplaced`)
      AND the RECORD/RESTORE behavioural corpus (the W3C 387/388/579/580
      family adapted to the re-frame2 grammar shape). The Mode-B
      `:machine-transition` fixture counterparts live at
      `spec/conformance/fixtures/scxml-history-test{387,388,579,580}-*.edn`
      and `history-{shallow,deep,default-target,per-region,dangling}-*.edn`.
      This entry is listed for completeness alongside the genuine exclusions
      so the scope boundary stays auditable.

  ## Divergences from the SCXML core

  re-frame2 follows XState v5 (the gold standard) for self-transition
  defaults, which FLIPS SCXML's default: a targeted self/ancestor
  transition is INTERNAL by default; the external restart is the opt-in
  `:reenter? true`. SCXML's `type=\"external\"` default is therefore a
  DELIBERATE DIVERGENCE re-frame2 does NOT follow — it follows v5. The
  §Self-transitions tests assert the v5 model: the INTERNAL (omit-target,
  or default self/ancestor target) cases fire the action only (no
  exit/entry); the EXTERNAL cases carry `:reenter? true` and fire
  exit → action → entry, re-descending a compound's :initial chain. The
  SCXML default-external semantics are still EXERCISED (via `:reenter? true`)
  but are not the re-frame2 default."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [re-frame.machines :as machines]
   [re-frame.machines.result :as result]
   [re-frame.machines.transition :as transition]
   [re-frame.machines.lifecycle-fx.finalize :as finalize])
  #?(:clj (:import [clojure.lang ExceptionInfo])))

;; ---------------------------------------------------------------------------
;; Test harness — the pure engine surface
;; ---------------------------------------------------------------------------
;;
;; Every case drives `re-frame.machines/machine-transition` — the public
;; pure entry-point (parallel-aware; falls through to the single-machine
;; macrostep). It returns a `result/ok` Result carrying the post-macrostep
;; snapshot + emitted fx. `step` unwraps it to `{:state :data :fx :tags}`
;; so assertions read the externally-observable configuration.

(defn- step
  "Apply `event` to `machine` against `snapshot` via the pure
  `machine-transition` engine. Returns the post-macrostep snapshot, the
  emitted fx vector, and (for convenience) the snapshot's `:state` /
  `:data` / `:tags`."
  [machine snapshot event]
  (let [r (machines/machine-transition machine snapshot event)]
    {:snapshot (result/snap r)
     :fx       (vec (result/fx r))
     :state    (:state (result/snap r))
     :data     (:data (result/snap r))
     :tags     (:tags (result/snap r))}))

(defn- order-recorder
  "Return `[log-atom mk]` where `(mk tag)` is an action/entry/exit fn that
  appends `tag` to `log-atom` (in invocation order) and returns nil (a
  no-op `:data` write). Used to assert the SCXML entry/exit ordering rules
  by observing the concrete invocation sequence."
  []
  (let [log (atom [])]
    [log (fn [tag] (fn [_ctx] (swap! log conj tag) nil))]))

;; ===========================================================================
;; §1. Transition selection
;;
;; SCXML §3.13 "Selecting Transitions": for each atomic state in the
;; configuration, select the FIRST transition (in document order) whose
;; event descriptor matches and whose condition (`cond`) evaluates true.
;; re-frame2: deepest-wins leaf→root over the `:on` table, first guard-pass
;; candidate wins. Spec 005 §Transition resolution.
;; ===========================================================================

(deftest scxml-transition-selection-event-match
  (testing "SCXML §3.13 / W3C test 144-family (basic event-name match):
            an event with a matching transition fires it; the target
            becomes the new configuration. Spec 005 §Transition table grammar."
    ;; Harel/SCXML: state A on event T transitions to B.
    (let [m {:initial :a :data {}
             :states  {:a {:on {:t :b}}
                       :b {}}}
          r (step m {:state :a :data {}} [:t])]
      (is (= :b (:state r)) "matching event selects the transition")
      (is (= [] (:fx r)) "a pure target-only transition emits no fx"))))

(deftest scxml-transition-selection-first-matching-candidate-wins
  (testing "SCXML §3.13: of several transitions out of a state, the FIRST in
            DOCUMENT ORDER whose condition holds is taken. Mirrors W3C
            test 153/155 (cond ordering). Spec 005 §Multiple-candidate
            transitions — first guard-pass wins; an unguarded candidate is
            the unconditional fallback."
    (let [m {:initial :a :data {}
             :guards  {:no  (fn [_] false)
                       :yes (fn [_] true)}
             :states  {:a {:on {:ev [{:guard :no  :target :x}   ;; condition false — skip
                                      {:guard :yes :target :y}   ;; FIRST true — taken
                                      {:target :z}]}}            ;; unconditional fallback — never reached
                       :x {} :y {} :z {}}}
          r (step m {:state :a :data {}} [:ev])]
      (is (= :y (:state r))
          "the first candidate whose guard passes (in document order) is selected"))))

(deftest scxml-transition-selection-unconditional-fallback
  (testing "SCXML §3.13: when every guarded condition fails, the unguarded
            (conditionless) transition is the fallback (SCXML's
            condition-less `<transition>` after guarded ones). Spec 005
            §Multiple-candidate transitions — unguarded candidate is the
            unconditional fallback ending a guarded list."
    (let [m {:initial :a :data {}
             :guards  {:no (fn [_] false)}
             :states  {:a {:on {:ev [{:guard :no :target :x}
                                      {:target :fallback}]}}
                       :x {} :fallback {}}}
          r (step m {:state :a :data {}} [:ev])]
      (is (= :fallback (:state r))
          "all guards false ⇒ the unconditional fallback fires"))))

(deftest scxml-transition-selection-internal-action-data-write
  (testing "SCXML §3.13 + §5.4 (executable content on a transition): the
            selected transition's action runs and updates the datamodel.
            re-frame2: a transition `:action` returns a `:data` patch
            merged into the snapshot. Spec 005 §Actions."
    (let [m {:initial :a :data {:n 0}
             :actions {:bump (fn [{d :data}] {:data (update d :n inc)})}
             :states  {:a {:on {:ev {:target :b :action :bump}}}
                       :b {}}}
          r (step m {:state :a :data {:n 0}} [:ev])]
      (is (= :b (:state r)))
      (is (= 1 (get-in r [:data :n])) "the transition action's :data write is committed"))))

;; ===========================================================================
;; §2. Document-order conflict resolution
;;
;; SCXML §3.13 "optimal enabled transition set": when multiple transitions
;; are enabled, conflicts are resolved in DOCUMENT ORDER, with descendant
;; states given priority over ancestors (the next section). Within ONE
;; state's transition list, document order is decisive. The §1 candidate
;; tests already pin within-state document order; here we pin that a leaf's
;; explicit match beats its OWN later wildcard (document order: explicit
;; entries precede the `:*` catch-all at the same level).
;; ===========================================================================

(deftest scxml-conflict-explicit-precedes-wildcard-same-level
  (testing "SCXML §3.12.1 event-descriptor matching + document order: at one
            state, an explicit event match is preferred over the `*` catch-all
            (the explicit descriptor is more specific and earlier in document
            order). Spec 005 §Wildcard — `:*` fires only when no explicit
            match at the same level."
    (let [m {:initial :a :data {}
             :states  {:a {:on {:specific :explicit-target
                                 :*        :wildcard-target}}
                       :explicit-target {} :wildcard-target {}}}
          explicit (step m {:state :a :data {}} [:specific])
          wild     (step m {:state :a :data {}} [:anything-else])]
      (is (= :explicit-target (:state explicit))
          "explicit descriptor wins over `:*` at the same level")
      (is (= :wildcard-target (:state wild))
          "`:*` catches the event that has no explicit descriptor"))))

;; ===========================================================================
;; §3. Deepest-wins + parent-fallthrough
;;
;; SCXML §3.13: a transition in a DESCENDANT (more deeply nested) state has
;; priority over a transition in an ANCESTOR for the same event ("states
;; that are descendants ... have priority"). If the descendant does not
;; handle the event, selection falls through to the ancestor. Harel: the
;; innermost active state gets first refusal. Spec 005 §Transition
;; resolution — deepest-wins with parent fallthrough.
;; ===========================================================================

(deftest scxml-deepest-wins-child-overrides-parent
  (testing "SCXML §3.13 descendant-priority: a child state's transition for
            an event OVERRIDES the parent's transition for the same event.
            Spec 005 §Transition resolution — deepest-wins."
    (let [m {:initial :p :data {}
             :states  {:p {:on      {:ev :parent-handled}   ;; ancestor handler
                           :initial :c
                           :states  {:c {:on {:ev :child-handled}}  ;; descendant — wins
                                     :child-handled {}}}
                       :parent-handled {}}}
          ;; Active config [:p :c]; both :p and :c handle :ev. Deepest-wins.
          r (step m {:state [:p :c] :data {}} [:ev])]
      (is (= [:p :child-handled] (:state r))
          "the descendant :c's :ev transition wins over the ancestor :p's"))))

(deftest scxml-parent-fallthrough-when-child-declines
  (testing "SCXML §3.13: when the descendant does NOT handle the event,
            selection falls through to the nearest ancestor that does
            (the auth-flow `:logout` factored-to-parent idiom). Spec 005
            §Transition resolution / §Worked example — auth flow."
    (let [m {:initial :authenticated :data {}
             :states  {:authenticated
                       {:on      {:logout [:unauthenticated]}  ;; factored to parent
                        :initial :dashboard
                        :states  {:dashboard {:on {:open-cart :cart}}
                                  :cart      {}}}
                       :unauthenticated {}}}
          ;; :logout is not on :cart; falls through to :authenticated.
          ;; The factored handler uses an absolute vector target, so the
          ;; resulting configuration is the vector path [:unauthenticated].
          r (step m {:state [:authenticated :cart] :data {}} [:logout])]
      (is (= [:unauthenticated] (:state r))
          "child :cart declines :logout; the ancestor :authenticated handles it (absolute vector target)"))))

(deftest scxml-leaf-wildcard-shadows-parent-explicit
  (testing "SCXML §3.13 descendant-priority extends to the catch-all: a
            leaf's `:*` wildcard (steps 1-2 of resolution) shadows an
            EXPLICIT match on the parent (step 3) for the same event,
            because the whole leaf level is consulted before the parent.
            Spec 005 §Wildcard (`:*` at the leaf shadows an explicit match
            on the parent for the same event)."
    (let [m {:initial :p :data {}
             :states  {:p {:on      {:ev :landed-on-parent}    ;; parent EXPLICIT
                           :initial :c
                           :states  {:c {:on {:* :wild}}        ;; leaf WILDCARD — shadows parent
                                     :wild {} :landed-on-parent {}}}}}
          r (step m {:state [:p :c] :data {}} [:ev])]
      (is (= [:p :wild] (:state r))
          "the leaf's `:*` wins over the parent's explicit `:ev` (deepest-wins)"))))

;; ===========================================================================
;; §4. Entry/exit action ORDERING (LCA-based)
;;
;; SCXML §3.13 "exit/enter set" computation via the LCCA (Least Common
;; Compound Ancestor). The microstep: (1) EXIT the states leaving the
;; configuration, in REVERSE document order (deepest / innermost first);
;; (2) run the transition's executable content; (3) ENTER the states
;; joining the configuration, in document order (shallowest / outermost
;; first), descending through the target's `:initial` chain. Harel: exit
;; inside-out, enter outside-in. Spec 005 §Entry/exit cascading along the
;; LCA.
;; ===========================================================================

(deftest scxml-lca-cascade-exit-deepest-first-enter-shallowest-first
  (testing "SCXML §3.13 LCCA exit/enter ordering (mirrors W3C test 504/505
            entry/exit-order family): transitioning [:p :a :x] → [:p :b :y]
            with LCA [:p] must EXIT x then a (deepest-first, stopping below
            the LCA), run the transition ACTION at the LCA boundary, then
            ENTER b then y (shallowest-first). Spec 005 §Entry/exit
            cascading along the LCA."
    (let [[log mk] (order-recorder)
          m {:initial :p :data {}
             :states
             {:p {:entry (mk :entry-P) :exit (mk :exit-P)   ;; LCA — neither fires
                  :initial :a
                  :states
                  {:a {:entry (mk :entry-A) :exit (mk :exit-A)
                       :initial :x
                       :states
                       {:x {:entry (mk :entry-X) :exit (mk :exit-X)
                            :on {:go {:target [:p :b :y] :action (mk :ACTION)}}}}}
                   :b {:entry (mk :entry-B) :exit (mk :exit-B)
                       :initial :y
                       :states
                       {:y {:entry (mk :entry-Y) :exit (mk :exit-Y)}}}}}}}
          r (step m {:state [:p :a :x] :data {}} [:go])]
      (is (= [:p :b :y] (:state r)) "resulting configuration is the target leaf")
      (is (= [:exit-X :exit-A :ACTION :entry-B :entry-Y] @log)
          "exit deepest-first (X,A) → action at LCA → entry shallowest-first (B,Y); LCA :p neither exits nor enters"))))

(deftest scxml-lca-flat-collapses-to-exit-action-entry
  (testing "SCXML §3.13 (flat machine): when both states are atomic top-level
            states the LCCA is the root; exit source, run action, enter
            target. Spec 005 §Entry/exit cascading along the LCA (the flat
            collapse)."
    (let [[log mk] (order-recorder)
          m {:initial :a :data {}
             :states {:a {:exit (mk :exit-A)
                          :on {:go {:target :b :action (mk :ACTION)}}}
                      :b {:entry (mk :entry-B)}}}
          r (step m {:state :a :data {}} [:go])]
      (is (= :b (:state r)))
      (is (= [:exit-A :ACTION :entry-B] @log)
          "flat: exit source → action → entry target"))))

(deftest scxml-initial-cascade-enters-every-level-shallowest-first
  (testing "SCXML §3.10 + §3.13 default-initial entry: entering a COMPOUND
            state cascades through its `<initial>` / default child chain,
            firing each entered state's onentry shallowest-first (W3C test
            355/372 default-initial family). Spec 005 §Initial cascading."
    (let [[log mk] (order-recorder)
          ;; Start at a top-level sibling :start; entering the COMPOUND
          ;; :outer fresh (LCA = root) re-runs the full default-initial
          ;; descent so every entered level's onentry is observable in order.
          m {:initial :start :data {}
             :states
             {:start {:on {:enter [:outer]}}    ;; absolute target → enter :outer fresh
              :outer {:entry   (mk :outer)
                      :initial :mid
                      :states
                      {:mid {:entry   (mk :mid)
                             :initial :leaf
                             :states  {:leaf {:entry (mk :leaf)}}}}}}}
          ;; Enter from :start → [:outer] which cascades to [:outer :mid :leaf].
          r (step m {:state :start :data {}} [:enter])]
      (is (= [:outer :mid :leaf] (:state r)) "the default-initial chain fully descends")
      (is (= [:outer :mid :leaf] @log)
          "every entered state fires onentry shallowest-first along the initial cascade"))))

;; ===========================================================================
;; §5. Parallel regions + done.state
;;
;; SCXML §3.4 `<parallel>`: all child regions are simultaneously active; an
;; event is delivered to (broadcast across) every region; a `<parallel>` is
;; "done" (raises done.state) only when EVERY child region has reached a
;; final state. Harel: AND-decomposition. Spec 005 §Parallel regions.
;; ===========================================================================

(deftest scxml-parallel-broadcast-to-every-region
  (testing "SCXML §3.4 broadcast: one event is delivered to every active
            region; each region independently resolves it (W3C test 403/404
            parallel-broadcast family). Spec 005 §Parallel regions —
            transition broadcast."
    (let [m {:type :parallel :data {}
             :regions {:left  {:initial :a :states {:a {:on {:flip :b}} :b {}}}
                       :right {:initial :x :states {:x {:on {:flip :y}} :y {}}}}}
          r (step m {:state {:left :a :right :x} :data {}} [:flip])]
      (is (= {:left :b :right :y} (:state r))
          "the single :flip event transitioned BOTH regions independently"))))

(deftest scxml-parallel-region-declines-stays-put
  (testing "SCXML §3.4: a region with no enabled transition for the event
            stays in its current state while sibling regions transition.
            Spec 005 §Parallel regions — undeclined regions stay put."
    (let [m {:type :parallel :data {}
             :regions {:left  {:initial :a :states {:a {:on {:left-only :b}} :b {}}}
                       :right {:initial :x :states {:x {} :y {}}}}}
          r (step m {:state {:left :a :right :x} :data {}} [:left-only])]
      (is (= {:left :b :right :x} (:state r))
          ":left handled :left-only; :right (no matching :on) stayed put"))))

(deftest scxml-parallel-done-when-every-region-final
  (testing "SCXML §3.4 done.state.<id>: a `<parallel>` is done only when ALL
            regions reach a final state (W3C test 570/580 parallel-done
            family). re-frame2: `all-regions-final?` is the pure predicate
            the lifecycle uses to fire `:on-done`. Spec 005 §Final states
            §Parallel regions and `:final?`."
    (let [m {:type :parallel :data {}
             :regions {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                       :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}}
          ;; First :fin: both regions reach :done in one broadcast.
          both (step m {:state {:left :run :right :run} :data {}} [:fin])]
      (is (= {:left :done :right :done} (:state both)))
      (is (true? (finalize/all-regions-final? m (:state both)))
          "every region's leaf is :final? ⇒ the parallel machine is done"))))

(deftest scxml-parallel-NOT-done-when-one-region-pending
  (testing "SCXML §3.4: a `<parallel>` is NOT done while ANY region is still
            in a non-final state (the partial-final guard — premature
            done.state is the classic parallel bug). Spec 005 §Final states
            §Parallel regions and `:final?`."
    (let [m {:type :parallel :data {}
             :regions {:left  {:initial :run :states {:run {:on {:fin-left :done}} :done {:final? true}}}
                       :right {:initial :run :states {:run {:on {:fin-right :done}} :done {:final? true}}}}}
          ;; Only :left reaches :done; :right is still :run.
          left-only (step m {:state {:left :run :right :run} :data {}} [:fin-left])]
      (is (= {:left :done :right :run} (:state left-only)))
      (is (false? (finalize/all-regions-final? m (:state left-only)))
          "one region still pending ⇒ the parallel machine is NOT done"))))

(deftest scxml-parallel-region-ancestor-restart-is-region-local
  (testing "SCXML §3.4 + §3.13 (rf2-emz8l): the LCCA ancestor-restart applies
            PER REGION — a transition to a region-local compound ancestor
            restarts ONLY that region's subtree (exit+re-enter+re-init); the
            sibling region is untouched. Each region runs the same transition
            engine, so the LCCA fix is inherited. Spec 005 §Parallel regions
            §Entry/exit cascading along the LCCA."
    (let [[log mk] (order-recorder)
          m {:type :parallel :data {}
             :regions
             {:left {:initial :grp
                     :states
                     {:grp {:entry (mk :entry-grp) :exit (mk :exit-grp)
                            :initial :one
                            ;; declared on the region-local compound ancestor;
                            ;; :reenter? true makes the ancestor target external
                            :on {:reset {:target [:grp] :reenter? true :action (mk :L-action)}}
                            :states {:one {:entry (mk :entry-1) :exit (mk :exit-1)
                                           :on {:adv :two}}
                                     :two {:entry (mk :entry-2) :exit (mk :exit-2)}}}}}
              :right {:initial :idle :states {:idle {:entry (mk :entry-R)} :busy {}}}}}
          ;; Position :left at the NON-initial child :two, :right at :idle.
          r (step m {:state {:left [:grp :two] :right :idle} :data {}} [:reset])]
      (is (= {:left [:grp :one] :right :idle} (:state r))
          ":left's :grp restarts and re-inits to :one; :right (declines :reset) stays put")
      (is (= [:exit-2 :exit-grp :L-action :entry-grp :entry-1] @log)
          "ONLY :left's region cascades: exit :two,:grp → action → re-enter :grp → re-init :one; :right untouched"))))

;; ===========================================================================
;; §5b. done.state.<id> — TRANSITIONABLE compound / parallel onDone
;;
;; SCXML §3.7 `done.state.id`: when a COMPOUND state reaches a `<final>`
;; child — or a `<parallel>` reaches all-regions-final — the processor raises
;; `done.state.<id>` INTO the machine. An enclosing transition (`onDone` on
;; the node, or an ancestor `<transition event='done.state.id'>`) can TAKE it,
;; advancing the outer flow WHILE the machine keeps running. The signal is a
;; transitionable completion event, NOT (necessarily) a teardown. XState v5
;; `onDone`. Spec 005 §Final states §The done-state signal. These exercise the
;; PURE engine: the done-raise fires through
;; the FIFO `:raise` queue and the enclosing `:on-done` resolves in the SAME
;; macrostep — observable on the post-macrostep snapshot.
;; ===========================================================================

(deftest scxml-compound-done-fires-enclosing-on-done
  (testing "SCXML §3.7 done.state.<compoundId>: a compound reaching its
            `<final>` child raises done.state into the machine; the compound's
            `:on-done` advances the OUTER flow in the SAME macrostep, and the
            machine keeps running (NOT torn down). Spec 005 §Final states §The
            done-state signal — the canonical 'sub-flow completes → continue'."
    (let [m {:initial :flow :data {}
             :states
             {:flow {:initial :step
                     ;; onDone on the compound — keyword target is a SIBLING
                     ;; of :flow (resolved at :flow's own level).
                     :on-done :next
                     :states  {:step {:on {:finish :inner-done}}
                               :inner-done {:final? true}}}
              :next {}}}
          ;; :finish lands [:flow :inner-done] (final child of :flow) → the
          ;; engine raises [:rf.machine/done [:flow]] → :flow's :on-done fires
          ;; :flow → :next, all within the one macrostep.
          r (step m {:state [:flow :step] :data {}} [:finish])]
      (is (= :next (:state r))
          "the compound :flow reached its :final? child, its :on-done fired,
           and the machine advanced to the sibling :next — same macrostep"))))

(deftest scxml-compound-done-on-done-runs-action-and-target
  (testing "SCXML §3.7 + §5.4: the compound `:on-done` transition runs its
            executable content (the `:action`'s :data write) as it advances.
            Spec 005 §Final states §The done-state signal."
    (let [m {:initial :flow :data {:done-count 0}
             :actions {:mark (fn [{d :data}] {:data (update d :done-count inc)})}
             :states
             {:flow {:initial :step
                     :on-done {:target :next :action :mark}
                     :states  {:step {:on {:finish :inner-done}}
                               :inner-done {:final? true}}}
              :next {}}}
          r (step m {:state [:flow :step] :data {:done-count 0}} [:finish])]
      (is (= :next (:state r)) "advanced to :next via :on-done")
      (is (= 1 (get-in r [:data :done-count]))
          "the :on-done action's :data write committed in the same macrostep"))))

(deftest scxml-compound-done-handled-by-ancestor-on-clause
  (testing "SCXML §3.7: an ANCESTOR may handle the raised done event explicitly
            via `:on {:rf.machine/done …}` (the lower-level escape hatch) when
            the done node declares no `:on-done`. A guard disambiguates WHICH
            compound is done off the raised path arg. Spec 005 §Final states
            §The done-state signal."
    (let [m {:initial :outer :data {}
             ;; the raised done event carries the done compound's ABSOLUTE
             ;; declaration path — here [:outer :flow].
             :guards {:flow-done? (fn [{ev :event}] (= [:outer :flow] (second ev)))}
             :states
             {:outer {:initial :flow
                      ;; ancestor handler keyed on the reserved done event-id,
                      ;; guarded on the raised compound path.
                      :on {:rf.machine/done {:guard  :flow-done?
                                             :target [:elsewhere]}}
                      :states {:flow {:initial :step
                                      :states  {:step {:on {:finish :inner-done}}
                                                :inner-done {:final? true}}}}}
              :elsewhere {}}}
          r (step m {:state [:outer :flow :step] :data {}} [:finish])]
      (is (= [:elsewhere] (:state r))
          ":flow reached its final child; the ancestor :outer's explicit
           :on {:rf.machine/done …} (guard matched the path) took it"))))

(deftest scxml-compound-NOT-done-when-child-non-final
  (testing "SCXML §3.7: NO done.state is raised while the compound's active
            child is NOT a `<final>` state — the partial-completion guard.
            Spec 005 §Final states §The done-state signal (negative)."
    (let [m {:initial :flow :data {}
             :states
             {:flow {:initial :step
                     :on-done :next
                     :states  {:step {:on {:advance :step2}}
                               :step2 {}                 ;; NOT final
                               :inner-done {:final? true}}}
              :next {}}}
          ;; :advance lands [:flow :step2] — an ordinary leaf, not final — so
          ;; no done signal, no :on-done firing.
          r (step m {:state [:flow :step] :data {}} [:advance])]
      (is (= [:flow :step2] (:state r))
          "the compound is not done (child not final) ⇒ :on-done did NOT fire"))))

(deftest scxml-embedded-final-does-not-leak-to-machine-finality
  (testing "rf2-zlmz7 D7 reconciliation: an EMBEDDED `:final?` leaf signals
            compound-done WITHOUT being whole-machine finality — the pure
            engine commits the snapshot (machine alive) rather than the
            lifecycle teardown. With NO enclosing `:on-done`, the done signal
            is a benign no-op and the machine RESTS in the final config.
            Spec 005 §Final states §Embedded vs top-level."
    (let [m {:initial :flow :data {}
             :states
             {:flow {:initial :step
                     ;; NO :on-done — the done raise has no handler.
                     :states  {:step {:on {:finish :inner-done}}
                               :inner-done {:final? true}}}}}
          r (step m {:state [:flow :step] :data {}} [:finish])]
      (is (= [:flow :inner-done] (:state r))
          "the embedded final leaf is the resting config — the pure engine
           keeps the machine in place (no whole-machine teardown); an
           unhandled done signal is benign"))))

(deftest scxml-nested-compound-done-propagates
  (testing "SCXML §3.7 nesting: the IMMEDIATE parent compound of the final
            leaf is the one that is done — its `:on-done` fires, leaving the
            grandparent compound active. Spec 005 §Final states §The done-state
            signal (nested)."
    (let [m {:initial :outer :data {}
             :states
             {:outer {:initial :inner
                      :states {:inner {:initial :leaf
                                       ;; :inner's :on-done advances to a sibling
                                       ;; of :inner (inside :outer).
                                       :on-done :inner-next
                                       :states {:leaf {:on {:finish :fin}}
                                                :fin  {:final? true}}}
                               :inner-next {}}}}}
          r (step m {:state [:outer :inner :leaf] :data {}} [:finish])]
      (is (= [:outer :inner-next] (:state r))
          ":inner (immediate parent of :fin) is done; its :on-done advanced to
           the sibling :inner-next; :outer stays active throughout"))))

(deftest scxml-parallel-done-fires-root-on-done
  (testing "SCXML §3.4 done.state.<parallelId>: when EVERY region reaches its
            final leaf, the parallel root's `:on-done` fires (action + fx)
            WITHOUT auto-destroy — the 'do these axes in parallel, then
            continue' pattern. Spec 005 §Final states §The done-state signal
            (parallel)."
    (let [fired (atom 0)
          m {:type :parallel :data {:n 0}
             :actions {:complete (fn [{d :data}] {:data (assoc d :n (inc (:n d)))})}
             ;; onDone on the PARALLEL ROOT — action-only (no in-machine target).
             :on-done {:action :complete}
             :regions {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                       :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}}
          r (step m {:state {:left :run :right :run} :data {:n 0}} [:fin])]
      (is (= {:left :done :right :done} (:state r))
          "both regions reached :final? on the broadcast :fin")
      (is (= 1 (get-in r [:data :n]))
          "the parallel root's :on-done action ran exactly once when all
           regions settled final — the machine stays in the all-final config"))))

(deftest scxml-parallel-done-not-fired-while-one-region-pending
  (testing "SCXML §3.4: the parallel root's `:on-done` does NOT fire while ANY
            region is still non-final (premature done.state is the classic
            parallel bug). Spec 005 §Final states §The done-state signal
            (parallel, negative)."
    (let [m {:type :parallel :data {:n 0}
             :actions {:complete (fn [{d :data}] {:data (update d :n inc)})}
             :on-done {:action :complete}
             :regions {:left  {:initial :run :states {:run {:on {:fin-left :done}} :done {:final? true}}}
                       :right {:initial :run :states {:run {:on {:fin-right :done}} :done {:final? true}}}}}
          r (step m {:state {:left :run :right :run} :data {:n 0}} [:fin-left])]
      (is (= {:left :done :right :run} (:state r)) "only :left reached final")
      (is (= 0 (get-in r [:data :n]))
          ":on-done did NOT fire — :right is still :run (not all-regions-final)"))))

(deftest scxml-parallel-region-compound-done-fires-region-local-on-done
  (testing "rf2-bnjb3 + rf2-zlmz7 composed: a COMPOUND region reaching its
            `<final>` child raises a region-local done.state that the region's
            own `:on-done` takes (re-broadcast through the parent internal-event
            queue), advancing that region while siblings continue. Spec 005
            §Final states §The done-state signal × §Parallel regions."
    (let [m {:type :parallel :data {}
             :regions
             {:work {:initial :flow
                     :states {:flow {:initial :step
                                     :on-done :work-done
                                     :states {:step {:on {:finish :inner-done}}
                                              :inner-done {:final? true}}}
                              :work-done {}}}
              :status {:initial :idle :states {:idle {:on {:ping :idle}} }}}}
          ;; :finish lands the :work region at [:flow :inner-done]; :flow is
          ;; done → region-local [:rf.machine/done [:flow]] raise → re-broadcast
          ;; → :work's :on-done fires :flow → :work-done. :status untouched.
          r (step m {:state {:work [:flow :step] :status :idle} :data {}} [:finish])]
      (is (= {:work :work-done :status :idle} (:state r))
          ":work's compound :flow reached final, its :on-done advanced the
           region to :work-done; :status stayed :idle"))))

(deftest scxml-parallel-region-done-not-caught-by-sibling-unguarded-on
  (testing "rf2-m3arq + rf2-12ekv: a region-local done.state is region-SCOPED —
            a SIBLING region's UNGUARDED `:on {:rf.machine/done …}` escape hatch
            must NOT catch another region's done signal, even though the parent
            internal-event queue re-broadcasts the raise across every region
            (the correct XState v5 / SCXML `:raise` rule). XState v5 / SCXML
            scope `done.state.<id>` to the region that raised it BY IDENTITY.
            Strengthened for rf2-12ekv: `:other` now SHARES the leading
            state-name `:flow` (the rf2-m3arq shape-match leaked on exactly
            this collision; only region-NAME scoping closes it). Spec 005
            §Final states §The done-state signal × §Parallel regions; the
            arm-2 escape-hatch region scoping in `pick-done-transition`."
    (let [m {:type :parallel :data {}
             :regions
             ;; :work owns a compound :flow that goes done on :finish. It uses
             ;; the lower-level explicit `:on {:rf.machine/done …}` escape hatch
             ;; (NOT `:on-done`) so the resolution exercises arm 2.
             {:work {:initial :flow
                     :on {:rf.machine/done :work-done}
                     :states {:flow {:initial :step
                                     :states {:step {:on {:finish :inner-done}}
                                              :inner-done {:final? true}}}
                              :work-done {}}}
              ;; :other carries an UNGUARDED escape hatch on the SAME reserved
              ;; event-id AND shares the leading state-name `:flow`. A naive
              ;; shape-match on the re-broadcast of :work's region-relative done
              ;; [:flow] would be CAUGHT here, leaking :other → :hijacked.
              ;; Region-name scoping keeps it put: :work's done is not :other's.
              :other {:initial :flow
                      :on {:rf.machine/done :hijacked}
                      :states {:flow {:initial :wait
                                      :states {:wait {}}}
                               :hijacked {}}}}}
          ;; :finish lands :work at [:flow :inner-done]; :flow is done →
          ;; region-scoped [:rf.machine/done [:work :flow]] raise → re-broadcast
          ;; across BOTH regions. :work catches its own done (region-name head
          ;; matches) → :work-done. :other declines (head names :work, not
          ;; :other) even though :other ALSO has a :flow.
          r (step m {:state {:work [:flow :step] :other [:flow :wait]} :data {}}
                  [:finish])]
      (is (= {:work :work-done :other [:flow :wait]} (:state r))
          ":work caught its own region-local done via its explicit `:on`
           escape hatch → :work-done; :other's UNGUARDED `:on {:rf.machine/done}`
           did NOT catch :work's done (no cross-region leak) despite sharing the
           `:flow` state-name → :other stayed [:flow :wait]"))))

(deftest scxml-parallel-region-done-arm2-not-caught-by-sibling-shared-state-name
  (testing "rf2-12ekv: arm 2 region scoping must be by region IDENTITY, not
            state-name SHAPE. A region-local done.state is region-SCOPED even
            when a SIBLING region shares the LEADING state-name on the done
            compound's region-relative path. XState v5 / SCXML scope
            `done.state.<id>` to the originating region by node identity, never
            by a same-named compound in a sibling. The rf2-m3arq arm-2 gate
            used `prefix-of?` on two REGION-RELATIVE paths — a shape match that
            falsely passes when the sibling shares the leading state-name. The
            fix region-name-prefixes the done-raise path (as `:after` /
            `:on-error` already do) and declines by region-NAME head. Spec 005
            §Final states §The done-state signal × §Parallel regions
            (005:2819 — \"sibling regions are untouched\")."
    (let [m {:type :parallel :data {}
             :regions
             ;; :work owns a compound :flow that goes done on :finish, via the
             ;; lower-level explicit `:on {:rf.machine/done …}` escape hatch
             ;; (arm 2). Its done-raise carries the region-relative path [:flow].
             {:work {:initial :flow
                     :on {:rf.machine/done :work-done}
                     :states {:flow {:initial :step
                                     :states {:step {:on {:finish :inner-done}}
                                              :inner-done {:final? true}}}
                              :work-done {}}}
              ;; :other ALSO has a compound named :flow on its active path —
              ;; SHARING the leading state-name — and an UNGUARDED escape hatch.
              ;; The bare region-relative done-path [:flow] is a prefix of
              ;; :other's active path [:flow :wait], so a naive shape-match gate
              ;; would falsely pass and fire :other's hatch → :hijacked. It
              ;; must stay put: :work's done is not :other's done.
              :other {:initial :flow
                      :on {:rf.machine/done :hijacked}
                      :states {:flow {:initial :wait
                                      :states {:wait {}}}
                               :hijacked {}}}}}
          r (step m {:state {:work [:flow :step] :other [:flow :wait]} :data {}}
                  [:finish])]
      (is (= {:work :work-done :other [:flow :wait]} (:state r))
          ":work caught its OWN region-local done → :work-done; :other shares
           the leading state-name :flow but its UNGUARDED `:on {:rf.machine/done}`
           must NOT catch :work's done (region IDENTITY, not name shape) →
           :other stayed [:flow :wait]"))))

(deftest scxml-parallel-region-done-arm1-not-caught-by-sibling-shared-state-name
  (testing "rf2-12ekv: arm 1 (`:on-done` on the done node) must ALSO be
            region-scoped by identity. The rf2-m3arq fix only gated arm 2 (and
            with a shape match); arm 1 was ungated entirely. A sibling region
            carrying an `:on-done` on a compound at the SAME region-relative
            path as the originating region's done compound resolves the done
            node via `node-at` against the sibling's body and fires on the
            broadcast — a silent cross-region leak. The fix region-name-prefixes
            the done-raise path and declines BOTH arms by region-NAME head.
            Spec 005 §Final states §The done-state signal × §Parallel regions."
    (let [m {:type :parallel :data {}
             :regions
             ;; :work's compound :flow goes done on :finish and advances itself
             ;; via its own `:on-done` (arm 1) — the headline placement.
             {:work {:initial :flow
                     :states {:flow {:initial :step
                                     :on-done :work-done
                                     :states {:step {:on {:finish :inner-done}}
                                              :inner-done {:final? true}}}
                              :work-done {}}}
              ;; :other ALSO has a compound :flow at the SAME region-relative
              ;; path, carrying its OWN `:on-done`. The bare broadcast done-path
              ;; [:flow] resolves :other's :flow node (same shape) so its ungated
              ;; arm-1 `:on-done` fires → :hijacked. It must stay put.
              :other {:initial :flow
                      :states {:flow {:initial :wait
                                      :on-done :hijacked
                                      :states {:wait {}}}
                               :hijacked {}}}}}
          r (step m {:state {:work [:flow :step] :other [:flow :wait]} :data {}}
                  [:finish])]
      (is (= {:work :work-done :other [:flow :wait]} (:state r))
          ":work's compound :flow reached final, its `:on-done` advanced :work →
           :work-done; :other's same-shaped :flow `:on-done` must NOT fire on
           :work's done (region IDENTITY) → :other stayed [:flow :wait]"))))

(deftest scxml-parallel-region-done-arm1-negative-control-genuine-sibling-done
  (testing "rf2-12ekv negative control: the region-identity scoping must NOT
            block a region's OWN compound-done from firing its own `:on-done`
            when a sibling shares the leading state-name. Both regions go done
            on the SAME external event; each must advance via its OWN `:on-done`
            — proving the gate declines a FOREIGN region's done, not the OWN
            region's. Spec 005 §Final states §The done-state signal × §Parallel
            regions."
    (let [m {:type :parallel :data {}
             :regions
             {:work {:initial :flow
                     :states {:flow {:initial :step
                                     :on-done :work-done
                                     :states {:step {:on {:finish :inner-done}}
                                              :inner-done {:final? true}}}
                              :work-done {}}}
              :other {:initial :flow
                      :states {:flow {:initial :step
                                      :on-done :other-done
                                      :states {:step {:on {:finish :inner-done}}
                                               :inner-done {:final? true}}}
                               :other-done {}}}}}
          ;; :finish lands BOTH regions at [:flow :inner-done]; each :flow is
          ;; done → each region raises its OWN region-scoped done → each fires
          ;; its OWN `:on-done`. No leak, no over-decline.
          r (step m {:state {:work [:flow :step] :other [:flow :step]} :data {}}
                  [:finish])]
      (is (= {:work :work-done :other :other-done} (:state r))
          "each region's own compound-done fired its OWN `:on-done` even though
           both share the leading state-name :flow — region scoping declines a
           FOREIGN region's done, never the OWN region's"))))

;; ===========================================================================
;; §6. Eventless / :always microstep settle (transient transitions)
;;
;; SCXML §3.13: an eventless (conditionless or condition-only) transition
;; fires automatically as part of the SAME microstep cycle, with no
;; external event, until the machine reaches a stable (quiescent)
;; configuration — the macrostep is the FIXED POINT of the eventless loop.
;; Harel: transient / "condition" transitions. Spec 005 §Eventless
;; `:always` transitions.
;; ===========================================================================

(deftest scxml-eventless-settles-to-fixed-point
  (testing "SCXML §3.13 macrostep: eventless transitions fire repeatedly
            within one microstep cycle until quiescence (W3C test 372/388
            eventless-loop family). re-frame2: the `:always` microstep loop
            settles when no guard passes. Spec 005 §Eventless transitions —
            macrostep semantics."
    (let [m {:initial :a :data {:n 0}
             :guards  {:more? (fn [{d :data}] (< (:n d) 3))}
             :actions {:bump  (fn [{d :data}] {:data (update d :n inc)})}
             ;; CANONICAL fixed-point loop: a TARGETLESS guarded :always with
             ;; an :action (Spec 005 §Self-loop forbidden at registration). The
             ;; action flips the guard false and the microstep loop settles —
             ;; no exit/entry churn. A self-:target :always (guarded or not) is
             ;; rejected at registration (acdlp), so the corpus must NOT model
             ;; one; the targetless form is observationally identical here
             ;; (:a has no :entry/:exit) and is the form a real machine uses.
             :states  {:a {:always [{:guard :more? :action :bump}]}}}
          ;; One external event kicks the macrostep; the internal eventless loop
          ;; then bumps :n in place at :a until :more? is false at :n=3.
          r (step m {:state :a :data {:n 0}} [:kick])]
      (is (= :a (:state r)) "the machine settles at :a (the eventless loop reached a fixed point)")
      (is (= 3 (get-in r [:data :n]))
          "the eventless transition fired exactly until its condition became false (n: 0→1→2→3)"))))

(deftest scxml-eventless-redirect-after-event-transition
  (testing "SCXML §3.13: an eventless transition can REDIRECT immediately
            after an event-driven transition lands in a state whose
            condition already holds — all within one macrostep, observers
            see only the settled state. Spec 005 §Eventless transitions —
            the redirect-on-entry idiom."
    (let [m {:initial :idle :data {:ready? true}
             :guards  {:ready? (fn [{d :data}] (true? (:ready? d)))}
             :states  {:idle    {:on {:go :pending}}
                       :pending {:always [{:guard :ready? :target :resolved}]}
                       :resolved {}}}
          ;; :go lands at :pending; :pending's :always (guard already true)
          ;; immediately redirects to :resolved inside the same macrostep.
          r (step m {:state :idle :data {:ready? true}} [:go])]
      (is (= :resolved (:state r))
          "external :go → :pending → (eventless) :resolved settles in one macrostep"))))

(deftest scxml-eventless-deepest-first-in-compound
  (testing "SCXML §3.13: in a compound state the eventless transition is
            evaluated DEEPEST-FIRST (the leaf has the most specific
            knowledge of its own validity), matching the entry-cascade
            order. Spec 005 §Eventless transitions §Compound interaction."
    ;; Both :outer and :leaf carry an :always whose guard is currently true.
    ;; Deepest-first means the LEAF's :always is selected for the FIRST
    ;; microstep. To make the ordering observable (rather than letting both
    ;; fire across successive microsteps), the leaf's action FLIPS the shared
    ;; guard false, so after the leaf redirect the loop reaches a fixed point
    ;; before the ancestor's :always can fire. If the ancestor had been
    ;; evaluated first, we would land at :outer-redirect instead.
    (let [m {:initial :outer :data {:go? true}
             :guards  {:go? (fn [{d :data}] (true? (:go? d)))}
             :actions {:stop (fn [{d :data}] {:data (assoc d :go? false)})}
             :states  {:outer {:always [{:guard :go? :target :outer-redirect}]
                               :initial :leaf
                               :states  {:leaf {:always [{:guard  :go?
                                                          :target :leaf-redirect
                                                          :action :stop}]}
                                         :leaf-redirect {}}}
                       :outer-redirect {}}}
          r (step m {:state [:outer :leaf] :data {:go? true}} [:kick])]
      (is (= [:outer :leaf-redirect] (:state r))
          "the leaf's eventless transition is taken before the ancestor's (deepest-first); had the ancestor won, we would be at :outer-redirect"))))

;; ===========================================================================
;; §7. Guards (conditions)
;;
;; SCXML §3.13 + §5.9.1 `cond`: a transition is enabled only if its `cond`
;; evaluates true; a false condition makes the transition NOT selected, and
;; the next candidate (or the next state up the tree) is tried. re-frame2:
;; a `:guard` fn returning false skips the candidate. Spec 005 §Guards.
;; (Only the SEMANTICS are tested — the string-`cond`-expression datamodel
;; is excluded; see §SCXML EXCLUSIONS #1.)
;; ===========================================================================

(deftest scxml-guard-false-blocks-transition
  (testing "SCXML §5.9.1 `cond`=false: a transition whose condition is false
            is not selected; the event is unhandled and the configuration is
            unchanged (W3C test 144/147 cond family — SEMANTICS only).
            Spec 005 §Guards."
    (let [m {:initial :a :data {:allowed? false}
             :guards  {:allowed? (fn [{d :data}] (true? (:allowed? d)))}
             :states  {:a {:on {:go {:guard :allowed? :target :b}}}
                       :b {}}}
          blocked (step m {:state :a :data {:allowed? false}} [:go])
          allowed (step m {:state :a :data {:allowed? true}}  [:go])]
      (is (= :a (:state blocked)) "guard false ⇒ transition not selected, state unchanged")
      (is (= :b (:state allowed)) "guard true ⇒ transition fires"))))

(deftest scxml-guard-reads-event-payload
  (testing "SCXML §5.9.1: the condition may inspect the triggering event's
            data (`_event.data`). re-frame2: the guard ctx carries `:event`,
            the full dispatched event vector. Spec 005 §Guards — the
            context-map contract."
    (let [m {:initial :a :data {}
             :guards  {:big? (fn [{[_ n] :event}] (> n 10))}
             :states  {:a {:on {:num [{:guard :big? :target :big}
                                       {:target :small}]}}
                       :big {} :small {}}}
          big   (step m {:state :a :data {}} [:num 42])
          small (step m {:state :a :data {}} [:num 3])]
      (is (= :big   (:state big))   "guard read the event payload (42 > 10)")
      (is (= :small (:state small)) "guard read the event payload (3 ≤ 10) ⇒ fallback"))))

;; ===========================================================================
;; §8. Wildcard `:*`
;;
;; SCXML §3.12.1 event descriptor "*": a transition with event="*" matches
;; ANY event, evaluated AFTER more specific descriptors at the same state.
;; re-frame2: `:*` in the `:on` table is the catch-all, consulted only when
;; no explicit key matches at that level. Spec 005 §Wildcard.
;; (§2 covers explicit-precedes-wildcard at one level; §3 covers leaf-`:*`
;; shadowing the parent. Here we pin the basic catch-all + the action.)
;; ===========================================================================

(deftest scxml-wildcard-catches-any-unmatched-event
  (testing "SCXML §3.12.1 `event=\"*\"`: the wildcard descriptor matches any
            event with no explicit transition (W3C test 318/319 wildcard
            family). Spec 005 §Wildcard."
    (let [m {:initial :a :data {}
             :states  {:a {:on {:known :known-target
                                 :*     :catch-all}}
                       :known-target {} :catch-all {}}}
          r (step m {:state :a :data {}} [:totally-unknown-event :with :args])]
      (is (= :catch-all (:state r))
          "the `:*` wildcard catches the otherwise-unmatched event"))))

(deftest scxml-wildcard-action-fail-loudly-idiom
  (testing "SCXML / xstate-v5 'fail loudly on unknown' idiom: a `:*` whose
            ACTION throws turns an otherwise-benign unhandled event into a
            real error — the documented way to opt OUT of no-op semantics.
            re-frame2: the throwing `:*` action yields a failure Result (no
            snapshot commit). Spec 005 §Transition resolution (the
            `:*`-throws note)."
    (let [m {:initial :a :data {}
             :actions {:boom (fn [_] (throw (ex-info "unknown event" {})))}
             :states  {:a {:on {:* {:target :a :action :boom}}}}}
          r (machines/machine-transition m {:state :a :data {}} [:surprise])]
      (is (result/fail? r)
          "a throwing `:*` action produces a failure Result (no silent no-op)"))))

;; ===========================================================================
;; §9. Unhandled event = benign no-op (xstate-v5 parity)
;;
;; SCXML v1 with no matching transition: the event is consumed and
;; discarded (no error). xstate-v5 removed the v4 `strict` flag — an event
;; with no transition is simply ignored. re-frame2 adopts the same runtime
;; semantics (benign no-op) but keeps an observability trace. Spec 005
;; §Transition resolution (the xstate-v5-parity note — do NOT "fix" to an
;; error).
;; ===========================================================================

(deftest scxml-unhandled-event-is-noop-not-error
  (testing "SCXML / xstate-v5: an event with no enabled transition leaves
            the configuration and datamodel UNCHANGED and is NOT an error.
            Spec 005 §Transition resolution — benign no-op."
    (let [m {:initial :a :data {:k 1}
             :states  {:a {:on {:real :b}} :b {}}}
          r (machines/machine-transition m {:state :a :data {:k 1}} [:never-declared])]
      (is (result/ok? r) "an unhandled event is NOT a failure")
      (is (= :a (:state (step m {:state :a :data {:k 1}} [:never-declared])))
          "configuration is unchanged")
      (is (= {:k 1} (:data (step m {:state :a :data {:k 1}} [:never-declared])))
          "datamodel (:data) is unchanged")
      (is (= [] (:fx (step m {:state :a :data {:k 1}} [:never-declared])))
          "no effects emitted for an unhandled event"))))

;; ===========================================================================
;; §10. Self-transitions (internal default vs external opt-in)
;;
;; XState v5 is the gold standard. THREE cases the engine must distinguish:
;;
;;   (1) TARGETLESS — the action fires; NO onexit/onentry; the active
;;       configuration (including active descendants) is PRESERVED unchanged.
;;       "To preserve child states, omit `target` entirely." (XState v5 docs.)
;;   (2) EXPLICIT self/ancestor/current-compound target, NO `:reenter?` — the
;;       TARGET state's own onexit/onentry do NOT fire, but XState v5
;;       "re-resolves child states to their initial state": the target's
;;       ACTIVE DESCENDANTS are exited and the target's :initial chain
;;       re-descends. Config CHANGES (children reset). NOT a no-op.
;;   (3) EXPLICIT target WITH `:reenter? true` — the target state itself is
;;       exited + re-entered (onexit → action → onentry; restart :after,
;;       re-spawn), then re-descends. Full restart.
;;
;; This INVERTS SCXML §3.13 `<transition type="internal|external">`, where
;; external is the default and `type="internal"` opts out — XState v5
;; deliberately flipped the default (the v4 `internal` flag was replaced by
;; the inverted `reenter`). A targetless transition is internal in both
;; models.
;;
;; The engine: `re-frame.machines.transition/target-path` resolves the
;; `:same-state` sentinel (and a `:target` naming the declaring state's own
;; keyword) to the declaring state's own path; `compute-cascade-paths` keeps
;; the LCCA at the TARGET's depth (case 2 — only descendants below the target
;; cross the cascade boundary) and pulls it UP to the target's parent (case 3)
;; ONLY when the transition carries `:reenter? true`. The effective
;; `internal?` (true config no-op) flag is reserved for TARGETLESS
;; transitions alone.
;; ===========================================================================

(deftest scxml-internal-self-transition-fires-action-only
  (testing "SCXML §3.13 internal self-transition: omitting the target fires
            the transition's executable content but NOT onexit/onentry; the
            configuration is unchanged. Spec 005 §Self-transitions (omit
            `:target`)."
    (let [[log mk] (order-recorder)
          m {:initial :a :data {:n 0}
             :states {:a {:entry (mk :entry) :exit (mk :exit)
                          :on {:self {:action :bump}}}}
             :actions {:bump (fn [{d :data}] (swap! log conj :action)
                               {:data (update d :n inc)})}}
          r (step m {:state :a :data {:n 0}} [:self])]
      (is (= :a (:state r)) "internal self-transition leaves the configuration unchanged")
      (is (= 1 (get-in r [:data :n])) "the transition's action ran")
      (is (= [:action] @log)
          "ONLY the action fired — no onexit, no onentry (internal semantics, SCXML-conformant)"))))

(deftest scxml-external-self-transition-same-state-sentinel
  (testing "external self-transition (`:target :same-state` + `:reenter? true`)
            fires onexit THEN the transition's action THEN onentry of the
            source, leaving the configuration at the source state. Spec 005
            §Self-transitions + Spec-Schemas (`:same-state` is the documented
            literal sentinel; `:reenter? true` is the v5 external opt-in —
            rf2-eicq0)."
    (let [[log mk] (order-recorder)
          m {:initial :a :data {}
             :states {:a {:entry (mk :entry) :exit (mk :exit)
                          :on {:self {:target :same-state :reenter? true :action (mk :action)}}}}}
          r (step m {:state :a :data {}} [:self])]
      (is (= :a (:state r))
          "external self-transition stays at the source state")
      (is (= [:exit :action :entry] @log)
          "onexit → action → onentry (external self-transition re-enters the state)"))))

(deftest scxml-external-self-transition-own-keyword-target
  (testing "Spec 005 §Entry/exit cascading: a `:target` naming the declaring
            state's OWN keyword with `:reenter? true` is an external
            self-transition — exit and entry fire. Same exit → action → entry
            ordering as `:same-state`. rf2-eicq0."
    (let [[log mk] (order-recorder)
          m {:initial :a :data {}
             :states {:a {:entry (mk :entry) :exit (mk :exit)
                          :on {:self {:target :a :reenter? true :action (mk :action)}}}}}
          r (step m {:state :a :data {}} [:self])]
      (is (= :a (:state r))
          "a self-naming keyword target stays at the source state")
      (is (= [:exit :action :entry] @log)
          "onexit → action → onentry — identical to the `:same-state` sentinel"))))

(deftest scxml-default-self-transition-is-internal
  (testing "rf2-eicq0 v5 DEFAULT FLIP: a `:target :same-state` (or own-keyword)
            WITHOUT `:reenter?` is INTERNAL — the action fires, onexit/onentry
            do NOT. This is the breaking regression guard that the OLD
            external-default (rf2-46ban) is gone. Spec 005 §Self-transitions."
    ;; :same-state, no :reenter?
    (let [[log mk] (order-recorder)
          m {:initial :a :data {}
             :states {:a {:entry (mk :entry) :exit (mk :exit)
                          :on {:self {:target :same-state :action (mk :action)}}}}}
          r (step m {:state :a :data {}} [:self])]
      (is (= :a (:state r)) "default self-target leaves the configuration unchanged")
      (is (= [:action] @log)
          ":target :same-state is INTERNAL by default — ONLY the action fired (v5 flip)"))
    ;; own-keyword target, no :reenter?
    (let [[log mk] (order-recorder)
          m {:initial :a :data {}
             :states {:a {:entry (mk :entry) :exit (mk :exit)
                          :on {:self {:target :a :action (mk :action)}}}}}
          r (step m {:state :a :data {}} [:self])]
      (is (= :a (:state r)) "default own-keyword self-target stays put")
      (is (= [:action] @log)
          "own-keyword self-target is INTERNAL by default — ONLY the action fired"))))

;; ===========================================================================
;; §10b. External (:reenter? true) transition to a PROPER ANCESTOR
;;       (LCCA-restart geometry)
;;
;; A `:reenter? true` transition from a descendant leaf to one of its PROPER
;; ANCESTORS A restarts A: the LCCA of {source, A} is A's PARENT (A is a
;; proper ancestor of the source but NOT of itself), so the exit set is A's
;; whole active subtree INCLUDING A, and the entry set re-enters A and
;; re-descends A's default-initial chain. Net effect: A's onexit + onentry
;; both fire and A re-initialises. This is the natural generalisation of the
;; external SELF-transition (§10) — a self-target is the degenerate case
;; where A is the source leaf itself.
;;
;; Under the XState v5 model RE-ENTERING THE ANCESTOR ITSELF is OPT-IN: an
;; ancestor target WITHOUT `:reenter?` does NOT exit/re-enter the ancestor.
;; BUT it is NOT a no-op: the ancestor's ACTIVE DESCENDANTS are
;; re-resolved (children reset to the ancestor's :initial). See
;; `scxml-default-ancestor-target-re-resolves-descendants` below. XState v5
;; flipped the SCXML default; re-frame2 follows it.
;;
;; The engine computes the true LCCA against the target BASE
;; (pre-initial-cascade): when the (`:reenter?`) target is a prefix of the
;; active path, the LCCA is pulled up to the target's parent — so an ancestor
;; target whose `:initial` chain re-descends to the source still produces the
;; full exit/re-enter cascade rather than collapsing to a no-op. Spec 005
;; §Entry/exit cascading along the LCCA.
;; ===========================================================================

(deftest scxml-external-transition-to-proper-ancestor-restarts-subtree
  (testing "a `:reenter? true` transition from a descendant leaf to a
            PROPER ANCESTOR A restarts A — exit A's active subtree (deepest-
            first, INCLUDING A), run the transition action at the LCCA
            (A's parent), then re-enter A and re-descend A's :initial chain
            (shallowest-first). Before rf2-emz8l this was a silent no-op.
            Spec 005 §Entry/exit cascading along the LCCA."
    (let [[log mk] (order-recorder)
          ;; Active config [:p :a :x]; :x targets its grandparent :a via an
          ;; absolute vector + :reenter?. A's :initial is :x, so the restart
          ;; re-descends back to [:p :a :x] — the exact case the LCP-as-LCA
          ;; missed.
          m {:initial :p :data {}
             :states
             {:p {:entry (mk :entry-P) :exit (mk :exit-P)     ;; LCCA — neither fires
                  :initial :a
                  :states
                  {:a {:entry (mk :entry-A) :exit (mk :exit-A)
                       :initial :x
                       :states
                       {:x {:entry (mk :entry-X) :exit (mk :exit-X)
                            :on {:restart {:target [:p :a] :reenter? true :action (mk :ACTION)}}}}}}}}}
          r (step m {:state [:p :a :x] :data {}} [:restart])]
      (is (= [:p :a :x] (:state r))
          "after restarting A the configuration re-descends A's :initial back to [:p :a :x]")
      (is (= [:exit-X :exit-A :ACTION :entry-A :entry-X] @log)
          "exit deepest-first (X then A) → action at the LCCA (:p) → re-enter A → re-descend A's :initial (X); :p (the LCCA) neither exits nor enters"))))

(deftest scxml-external-transition-to-ancestor-diverging-initial-re-inits
  (testing "restarting ancestor A (`:reenter? true`) re-descends A's CURRENT
            :initial child even when A's active child differed from :initial
            at restart time. Source [:p :a :two]; A's :initial is :one, so
            the restart re-enters A and re-descends to [:p :a :one] (the
            re-initialisation the LCCA restart guarantees). Spec 005
            §Entry/exit cascading along the LCCA."
    (let [[log mk] (order-recorder)
          m {:initial :p :data {}
             :states
             {:p {:initial :a
                  :states
                  {:a {:entry (mk :entry-A) :exit (mk :exit-A)
                       :initial :one
                       :states
                       {:one {:entry (mk :entry-1) :exit (mk :exit-1)}
                        :two {:entry (mk :entry-2) :exit (mk :exit-2)
                              :on {:restart {:target [:p :a] :reenter? true}}}}}}}}}
          ;; Seed the active config at the NON-initial child :two.
          r (step m {:state [:p :a :two] :data {}} [:restart])]
      (is (= [:p :a :one] (:state r))
          "restarting A re-initialises it to its :initial child :one (not back to :two)")
      (is (= [:exit-2 :exit-A :entry-A :entry-1] @log)
          "exit :two then A → re-enter A → re-descend A's :initial (:one)"))))

(deftest scxml-default-ancestor-target-re-resolves-descendants
  (testing "rf2-gt1pu (corrects an earlier rf2-eicq0 over-collapse): a DEFAULT
            ancestor target (NO :reenter?) does NOT re-enter the ancestor
            itself, but RE-RESOLVES the ancestor's active descendants —
            children reset to :initial. XState v5: 'a transition with an
            explicit target re-resolves child states to their initial state'
            (stately.ai/docs/transitions). So an ancestor target :a (no
            :reenter?) exits the active descendant :two and re-descends :a's
            :initial (:one); :a itself (and :p above it) is NOT exited/entered.
            Verified against xstate@5.32.0. Spec 005 §Self-transitions §The
            three explicit-target geometries."
    (let [[log mk] (order-recorder)
          m {:initial :p :data {}
             :states
             {:p {:entry (mk :entry-P) :exit (mk :exit-P)  ;; above the target — never fires
                  :initial :a
                  :states
                  {:a {:entry (mk :entry-A) :exit (mk :exit-A)  ;; the target — NOT re-entered
                       :initial :one
                       :states
                       {:one {:entry (mk :entry-1) :exit (mk :exit-1)}
                        :two {:entry (mk :entry-2) :exit (mk :exit-2)
                              ;; ancestor target, NO :reenter? — re-resolves :a's descendants
                              :on {:touch {:target [:p :a] :action (mk :ACTION)}}}}}}}}}
          ;; Seed at the NON-initial child :two; re-resolving :a's descendants
          ;; resets the active child back to :a's :initial (:one).
          r (step m {:state [:p :a :two] :data {}} [:touch])]
      (is (= [:p :a :one] (:state r))
          "ancestor target re-resolves :a's descendants — active child resets to :a's :initial (:one)")
      (is (= [:exit-2 :ACTION :entry-1] @log)
          "exit the active descendant :two → action at the LCCA (:a) → re-enter :a's :initial (:one); :a and :p NOT exited/entered (no :reenter?)"))))

(deftest scxml-external-transition-to-ancestor-via-same-state-on-ancestor
  (testing "Spec 005 §Self-transitions: `:target :same-state` + `:reenter? true`
            declared ON an ancestor and matched while a DESCENDANT leaf is
            active restarts the ancestor — the same LCCA-restart geometry as
            the §10 compound external self-transition, exercised through the
            pure engine. The descendant declines the event, the ancestor
            handles it. Spec 005 §Entry/exit cascading along the LCCA."
    (let [[log mk] (order-recorder)
          m {:initial :session :data {}
             :states
             {:session
              {:entry (mk :entry-session) :exit (mk :exit-session)
               :initial :active
               ;; declared on the COMPOUND ancestor; :active declines :reauth.
               ;; :reenter? true makes the ancestor self-target external.
               :on {:reauth {:target :same-state :reenter? true :action (mk :renew)}}
               :states
               {:active {:entry (mk :entry-active) :exit (mk :exit-active)}}}}}
          r (step m {:state [:session :active] :data {}} [:reauth])]
      (is (= [:session :active] (:state r))
          "the compound self-restart re-descends :session's :initial child")
      (is (= [:exit-active :exit-session :renew :entry-session :entry-active] @log)
          "exit cascade leaf→root (:active, :session) → action → entry cascade root→leaf (re-runs :initial)"))))

(deftest scxml-ancestor-restart-regression-disjoint-targets-unchanged
  (testing "REGRESSION GUARD (rf2-emz8l): the LCCA fix must NOT disturb
            DISJOINT-subtree targets. A sibling-leaf transition and a
            cross-level-to-sibling-subtree transition keep their plain
            common-prefix LCA — the common-ancestor node neither exits nor
            enters. Spec 005 §Entry/exit cascading along the LCCA."
    ;; (a) sibling-leaf: [:p :a] -> :b (sibling under :p). LCCA = :p; only
    ;;     the leaves cross the boundary, :p untouched.
    (let [[log mk] (order-recorder)
          m {:initial :p :data {}
             :states {:p {:entry (mk :entry-P) :exit (mk :exit-P)
                          :initial :a
                          :states {:a {:entry (mk :entry-A) :exit (mk :exit-A)
                                       :on {:go :b}}
                                   :b {:entry (mk :entry-B) :exit (mk :exit-B)}}}}}
          r (step m {:state [:p :a] :data {}} [:go])]
      (is (= [:p :b] (:state r)) "sibling-leaf transition lands at the sibling")
      (is (= [:exit-A :entry-B] @log)
          "only :a exits and :b enters; the common ancestor :p neither exits nor enters (unchanged)"))
    ;; (b) cross-level to a sibling SUBTREE: [:p :a :x] -> [:p :b :y].
    ;;     LCCA = :p; mirrors scxml-lca-cascade-* — must be untouched by the fix.
    (let [[log mk] (order-recorder)
          m {:initial :p :data {}
             :states
             {:p {:entry (mk :entry-P) :exit (mk :exit-P)
                  :initial :a
                  :states
                  {:a {:entry (mk :entry-A) :exit (mk :exit-A)
                       :initial :x
                       :states {:x {:entry (mk :entry-X) :exit (mk :exit-X)
                                    :on {:go {:target [:p :b :y]}}}}}
                   :b {:entry (mk :entry-B) :exit (mk :exit-B)
                       :initial :y
                       :states {:y {:entry (mk :entry-Y) :exit (mk :exit-Y)}}}}}}}
          r (step m {:state [:p :a :x] :data {}} [:go])]
      (is (= [:p :b :y] (:state r)) "cross-level lands at the sibling subtree leaf")
      (is (= [:exit-X :exit-A :entry-B :entry-Y] @log)
          "exit X,A → enter B,Y; the LCCA :p untouched (sibling-subtree case unchanged by the fix)"))))

;; ===========================================================================
;; §10c. Explicit targets on the active path RE-RESOLVE DESCENDANTS
;;       (the middle case; and parent-declared reenter
;;       to a specific descendant). XState v5 gold standard.
;;
;; The defining XState v5 rule (stately.ai/docs/transitions):
;;   "A common mistake is using target: 'process' on a parent state
;;    transition when you only want to execute actions. This will re-resolve
;;    child states to their initial state. To preserve child states, omit
;;    target entirely."
;; and "[reenter:true] will cause the state to re-enter when transitioning to
;; itself or descendent states."
;;
;; Each case below is grounded in a xstate@5.32.0 runtime check:
;;   • process.step3 + parent-declared target "process" (no reenter)
;;        -> step3 exit, step1 entry; NO process exit/entry.
;;   • parent.child.a + parent-declared target [:parent :child] (no reenter)
;;        -> exits a + child, re-enters child/a; parent NOT exited.
;;   • parent-declared target descendant WITH :reenter? true
;;        -> the declaring compound exits + re-enters, then lands on the
;;           NAMED descendant (NOT the compound's :initial).
;;   • child-declared SIBLING :reenter? true (the anti-case)
;;        -> the parent is NOT re-entered (disjoint geometry; :reenter? no-op).
;; ===========================================================================

(deftest scxml-explicit-current-compound-target-re-resolves-to-initial
  (testing "rf2-gt1pu (xstate@5.32.0): at [:process :step3], a transition
            DECLARED ON the compound :process targeting :process itself (no
            :reenter?) does NOT exit/re-enter :process, but RE-RESOLVES its
            descendants — exits the active child :step3 and re-descends
            :process's :initial (:step1). The middle case rf2-eicq0 collapsed
            into a targetless no-op. XState v5: an explicit target re-resolves
            child states to their initial. Spec 005 §Self-transitions."
    (let [[log mk] (order-recorder)
          m {:initial :process :data {}
             :states
             {:process
              {:entry (mk :entry-process) :exit (mk :exit-process)  ;; NOT re-entered
               :initial :step1
               ;; declared ON :process, target names :process itself (own kw)
               :on {:resolve {:target :process :action (mk :ACTION)}}
               :states
               {:step1 {:entry (mk :entry-1) :exit (mk :exit-1)}
                :step2 {:entry (mk :entry-2) :exit (mk :exit-2)}
                :step3 {:entry (mk :entry-3) :exit (mk :exit-3)}}}}}
          ;; :step3 declines :resolve; the compound :process handles it.
          r (step m {:state [:process :step3] :data {}} [:resolve])]
      (is (= [:process :step1] (:state r))
          "current-compound target re-resolves descendants — back to :process's :initial (:step1)")
      (is (= [:exit-3 :ACTION :entry-1] @log)
          "exit the active child :step3 → action at the LCCA (:process) → enter :initial (:step1); :process NOT exited/entered"))))

(deftest scxml-explicit-compound-target-re-resolves-child-even-when-on-initial
  (testing "rf2-gt1pu: re-resolving descendants is keyed to the TARGET's depth,
            not to whether the active child differs from :initial. Targeting
            the compound :process exits the active CHILD and re-enters the
            re-resolved :initial — EVEN when the active child IS already the
            :initial (:step1). The :state is unchanged but :step1's onexit /
            onentry DO fire (the descendant is re-resolved); :process itself is
            NOT re-entered (no :reenter?). This matches XState v5 'an explicit
            target re-resolves child states to their initial' — the child below
            the target always crosses the cascade boundary. Spec 005."
    (let [[log mk] (order-recorder)
          m {:initial :process :data {}
             :states
             {:process
              {:entry (mk :entry-process) :exit (mk :exit-process)  ;; NOT re-entered
               :initial :step1
               :on {:resolve {:target :process :action (mk :ACTION)}}
               :states
               {:step1 {:entry (mk :entry-1) :exit (mk :exit-1)}
                :step2 {:entry (mk :entry-2) :exit (mk :exit-2)}}}}}
          ;; already at :step1 (= :process's :initial)
          r (step m {:state [:process :step1] :data {}} [:resolve])]
      (is (= [:process :step1] (:state r))
          "re-resolution lands back on :step1 (= :process's :initial)")
      (is (= [:exit-1 :ACTION :entry-1] @log)
          "the active child :step1 is exited + re-entered (descendant re-resolved); :process NOT exited/entered (no :reenter?)"))))

(deftest scxml-parent-declared-descendant-target-re-enters-child-not-parent
  (testing "rf2-gt1pu (xstate@5.32.0): at [:parent :child :a], a transition
            DECLARED ON :parent targeting the DESCENDANT [:parent :child] (no
            :reenter?) re-enters the TARGETED CHILD :child (XState v5: 'child
            state nodes are always re-entered when targeted by transitions
            defined on compound state nodes') and re-descends :child's :initial
            (:a) — but does NOT exit/re-enter :parent. Spec 005 §Self-transitions
            §The three explicit-target geometries."
    (let [[log mk] (order-recorder)
          m {:initial :parent :data {}
             :states
             {:parent
              {:entry (mk :entry-parent) :exit (mk :exit-parent)  ;; NOT exited
               :initial :child
               ;; declared ON :parent; target is a DESCENDANT vector
               :on {:reset-child {:target [:parent :child] :action (mk :ACTION)}}
               :states
               {:child {:entry (mk :entry-child) :exit (mk :exit-child)  ;; re-entered (targeted child)
                        :initial :a
                        :states {:a {:entry (mk :entry-a) :exit (mk :exit-a)}
                                 :b {:entry (mk :entry-b) :exit (mk :exit-b)}}}}}}}
          ;; seed at the NON-initial grandchild :b to prove the re-descent to :a
          r (step m {:state [:parent :child :b] :data {}} [:reset-child])]
      (is (= [:parent :child :a] (:state r))
          "the targeted child :child re-enters and re-descends its :initial (:a); :parent unchanged")
      (is (= [:exit-b :exit-child :ACTION :entry-child :entry-a] @log)
          "exit deepest-first (:b, :child) → action at the LCCA (:parent) → re-enter :child → re-descend :child's :initial (:a); :parent NOT exited/entered"))))

(deftest scxml-parent-declared-descendant-target-WITH-reenter-restarts-declaring-compound
  (testing "rf2-127ff (RULED A — xstate@5.32.0): :reenter? true on a transition
            DECLARED ON a compound S whose target is a DESCENDANT of S exits +
            re-enters S itself (run S's :exit then :entry — restart S's :after,
            re-spawn), THEN descends to the NAMED descendant target (NOT S's
            :initial). The SCXML mechanics: source = the declaring compound S,
            so findLCCA([S]+[descendant]) rises to S's PARENT, putting S into
            the exit set. Spec 005 §Self-transitions §The three explicit-target
            geometries."
    (let [[log mk] (order-recorder)
          m {:initial :editor :data {}
             :states
             {:editor
              {:entry (mk :entry-editor) :exit (mk :exit-editor)  ;; S — exits + re-enters
               :initial :draft
               ;; declared ON :editor; target a NAMED descendant + :reenter?
               :on {:restart-into-preview {:target [:editor :preview]
                                           :reenter? true :action (mk :ACTION)}}
               :states
               {:draft   {:entry (mk :entry-draft)   :exit (mk :exit-draft)}
                :preview {:entry (mk :entry-preview) :exit (mk :exit-preview)}}}}}
          ;; active at :draft; forced re-entry of :editor must land on the
          ;; NAMED :preview, NOT :editor's :initial (:draft).
          r (step m {:state [:editor :draft] :data {}} [:restart-into-preview])]
      (is (= [:editor :preview] (:state r))
          "forced re-entry of :editor lands on the NAMED descendant :preview (NOT :editor's :initial :draft)")
      (is (= [:exit-draft :exit-editor :ACTION :entry-editor :entry-preview] @log)
          "exit deepest-first (:draft, :editor) → action at the LCCA (:editor's parent = root) → re-enter :editor → descend to the NAMED :preview"))))

(deftest scxml-child-declared-sibling-reenter-does-NOT-re-enter-parent
  (testing "rf2-127ff anti-case (xstate@5.32.0): :reenter? true on a transition
            DECLARED ON the CHILD :draft targeting a SIBLING [:editor :preview]
            does NOT re-enter the parent :editor — the source is a DESCENDANT,
            so findLCCA(:draft, :preview) = :editor and the domain (:editor) is
            NOT exited. :reenter? is a no-op for a disjoint-subtree target. This
            is why the parent-DECLARED shape (the test above) is the ONLY way to
            force the compound's re-entry. Spec 005 §Self-transitions."
    (let [[log mk] (order-recorder)
          m {:initial :editor :data {}
             :states
             {:editor
              {:entry (mk :entry-editor) :exit (mk :exit-editor)  ;; must NOT fire
               :initial :draft
               :states
               {:draft   {:entry (mk :entry-draft) :exit (mk :exit-draft)
                          ;; declared ON the CHILD :draft; sibling target + reenter
                          :on {:to-preview {:target [:editor :preview]
                                            :reenter? true :action (mk :ACTION)}}}
                :preview {:entry (mk :entry-preview) :exit (mk :exit-preview)}}}}}
          r (step m {:state [:editor :draft] :data {}} [:to-preview])]
      (is (= [:editor :preview] (:state r))
          "child-declared sibling transition lands at the sibling :preview")
      (is (= [:exit-draft :ACTION :entry-preview] @log)
          ":draft exits, :preview enters; :editor (the common ancestor) NOT re-entered despite :reenter? (no-op for a disjoint target)"))))

(deftest scxml-targetless-on-compound-preserves-active-descendants
  (testing "rf2-gt1pu regression guard: a TARGETLESS transition declared on a
            compound preserves the active descendants UNCHANGED — the
            distinguishing counterpart to the re-resolving explicit-target
            cases above. 'To preserve child states, omit target entirely.'
            (XState v5.) Spec 005 §Self-transitions."
    (let [[log mk] (order-recorder)
          m {:initial :process :data {:n 0}
             :states
             {:process
              {:entry (mk :entry-process) :exit (mk :exit-process)
               :initial :step1
               ;; NO :target — pure internal, descendants preserved
               :on {:tick {:action :bump}}
               :states
               {:step1 {:entry (mk :entry-1) :exit (mk :exit-1)}
                :step3 {:entry (mk :entry-3) :exit (mk :exit-3)}}}}
             :actions {:bump (fn [{d :data}] (swap! log conj :action)
                               {:data (update d :n inc)})}}
          ;; active at the NON-initial child :step3; a targetless transition
          ;; must NOT re-resolve it back to :step1.
          r (step m {:state [:process :step3] :data {:n 0}} [:tick])]
      (is (= [:process :step3] (:state r))
          "targetless transition PRESERVES the active descendant :step3 (no re-resolution)")
      (is (= 1 (get-in r [:data :n])) "the action ran")
      (is (= [:action] @log)
          "ONLY the action fired — no exit/entry, descendants preserved (true internal no-op)"))))

;; ===========================================================================
;; §11. :after — delayed transitions (SEMANTIC CORE: fire + stale)
;;
;; SCXML §6.2 `<send>` with `delay` is the SCXML delayed-event mechanism;
;; re-frame2's `:after` is the higher-level "after N ms in this state,
;; transition" sugar (Spec 005 §Delayed `:after` transitions). The wall-
;; clock SCHEDULING is an effect (excluded — see §SCXML EXCLUSIONS #2, the
;; I/O-processor family); what IS in the semantic core is how the engine
;; RESOLVES a fired-timer event: a live timer (matching per-node epoch)
;; fires its transition; a stale timer (the state was re-entered, epoch
;; advanced) is discarded as a no-op. Pinned here against the pure engine.
;; ===========================================================================

(deftest scxml-after-live-timer-fires-transition
  (testing "Spec 005 §Delayed `:after` transitions: when the synthetic
            timer event arrives with a matching per-node epoch, the `:after`
            transition fires (analogous to SCXML's delayed `<send>` event
            being delivered while the state is still active)."
    (let [m {:initial :a :data {} :states {:a {:after {1000 :b}} :b {}}}
          ;; epoch 0 matches the absent/default node epoch; decl-path [:a].
          r (step m {:state :a :data {}}
                  [:rf.machine.timer/after-elapsed 1000 0 [:a]])]
      (is (= :b (:state r)) "a live :after timer fires its transition"))))

(deftest scxml-after-stale-timer-is-noop
  (testing "Spec 005 §Delayed `:after` transitions §Hierarchy interaction:
            a timer carrying a STALE epoch (the state was re-entered since
            scheduling, advancing the per-node epoch) is discarded — the
            configuration is unchanged. This is the re-frame2 analogue of
            SCXML cancelling a pending delayed `<send>` on state exit."
    (let [m {:initial :a :data {} :states {:a {:after {1000 :b}} :b {}}}
          ;; carried epoch 5, but the snapshot's current per-node epoch is 0.
          r (step m {:state :a :data {:rf/after-epoch {[:a] 0}}}
                  [:rf.machine.timer/after-elapsed 1000 5 [:a]])]
      (is (= :a (:state r))
          "a stale :after timer (epoch mismatch) is a no-op; configuration unchanged"))))

;; ===========================================================================
;; §12. Final states (done.state) — flat + compound
;;
;; SCXML §3.7 `<final>`: entering a `<final>` child of a compound state
;; raises done.state.<parent>; the final state itself is a leaf with no
;; outgoing transitions. re-frame2: `:final? true` marks the leaf; the
;; lifecycle recomputes finality at the macrostep boundary via the pure
;; `final-on-leaf?` predicate. Spec 005 §Final states.
;; (Parallel done.state is §5; the `:on-done` parent-notification + auto-
;; destroy LIVE-runtime wiring is covered by `final_state_cljs_test`.)
;; ===========================================================================

(deftest scxml-final-leaf-flat
  (testing "SCXML §3.7 `<final>`: transitioning into a final state lands the
            configuration there and the leaf is recognised as final
            (done.state precondition). Spec 005 §Final states (`:final?`)."
    (let [m {:initial :run :data {}
             :states  {:run {:on {:finish :done}}
                       :done {:final? true}}}
          r (step m {:state :run :data {}} [:finish])]
      (is (= :done (:state r)) "transition into the final leaf")
      (is (true? (transition/final-on-leaf? m (:state r)))
          "the active leaf is recognised as final (raises done.state)")
      (is (false? (transition/final-on-leaf? m :run))
          "a non-final leaf is not final"))))

(deftest scxml-final-leaf-compound
  (testing "SCXML §3.7: a `<final>` nested inside a compound state raises
            done.state.<compound>; finality keys off the LEAF, not an
            ancestor. Spec 005 §Final states — finality is a leaf property."
    (let [m {:initial :wrapper :data {}
             :states  {:wrapper {:initial :run
                                 :states  {:run  {:on {:finish :done}}
                                           :done {:final? true}}}}}
          r (step m {:state [:wrapper :run] :data {}} [:finish])]
      (is (= [:wrapper :done] (:state r)) "transition into the nested final leaf")
      (is (true? (transition/final-on-leaf? m (:state r)))
          "the nested [:wrapper :done] leaf is final")
      (is (false? (transition/final-state-node?
                    (transition/node-at m [:wrapper])))
          "the compound :wrapper ancestor is NOT itself final (leaf-only property)"))))

;; ===========================================================================
;; §13. HISTORY — first-class grammar + record/restore (SCXML §3.10)
;;
;; SCXML §3.10 `<history type="shallow|deep">` records and restores the
;; most recent active descendant of a compound/parallel state. re-frame2
;; ships FIRST-CLASS history:
;; `{:type :history :deep? <bool> :default-target <t>}` under a compound's
;; `:states`. This section covers BOTH halves:
;;   §13a — the registration-time PLACEMENT / GRAMMAR-CONSTRAINT rejections the
;;          engine enforces (a `:type :history` node MUST have an owning
;;          compound; the closed key-set; one-per-compound).
;;   §13b — the RECORD/RESTORE behavioural corpus, the W3C 387/388/579/580
;;          history family ADAPTED to the re-frame2 grammar shape.
;;          The Mode-B `:machine-transition` fixture counterparts live at
;;          `spec/conformance/fixtures/scxml-history-test*-*.edn` +
;;          `history-*-*.edn`; these in-corpus tests give the same coverage
;;          dual-runtime (JVM + CLJS).
;;
;; SCXML ADAPTATION NOTES (cited per assertion below). re-frame2 is NOT an
;; SCXML processor — no datamodel / `<raise>` / `<send>` — so each W3C test's
;; statechart SHAPE and its last-active-config restoration SEQUENCE are
;; translated, not the `.scxml` verbatim. One structural divergence recurs:
;; W3C charts declare a deep AND a shallow `<history>` as SIBLINGS under one
;; state; re-frame2 permits AT MOST ONE history pseudo-state per compound
;; (deep-vs-shallow is the single node's `:deep?`), so a multi-history W3C
;; chart is split into per-depth machines of identical geometry.
;; ===========================================================================

(defn- history-rejection-id
  "Validate `machine` and return the `:rf.error/id` of the thrown
  registration error, or `::no-throw` if it validated. Pure — no registrar,
  no frame."
  [machine]
  (try
    (machines/validate-machine! machine)
    ::no-throw
    (catch #?(:clj ExceptionInfo :cljs :default) e
      (:rf.error/id (ex-data e)))))

(deftest scxml-history-root-type-rejected
  (testing "SCXML §3.10: a machine ROOT declaring `:type :history` is
            rejected — a history pseudo-state must be a child node under a
            compound's `:states`, never the machine root (no owning
            compound). Spec 005 §History states §Pseudo-state constraints;
            error `:rf.error/machine-history-misplaced`."
    (is (= :rf.error/machine-history-misplaced
           (history-rejection-id {:type :history :states {:s {}}}))
        "root `:type :history` is rejected with the misplaced-history error")))

(deftest scxml-history-no-owning-compound-rejected
  (testing "SCXML §3.10: a `:type :history` node declared at the FLAT
            machine top-level (a sibling of ordinary states, with no owning
            compound) is rejected — history records an enclosing compound's
            last-active config, so it must live inside that compound's
            `:states`. Spec 005 §History states §Pseudo-state constraints."
    (is (= :rf.error/machine-history-misplaced
           (history-rejection-id
             {:initial :a
              :states  {:a {}
                        :h {:type :history}}}))
        "a top-level `:type :history` with no owning compound is misplaced")))

(deftest scxml-history-region-direct-rejected
  (testing "SCXML §3.10 + §3.4: a `:type :history` declared DIRECTLY on a
            parallel region body (no enclosing compound region) is rejected.
            Per-region history must live inside a compound state within the
            region (W3C test 579/580 history-in-parallel family). Spec 005
            §Composition with parallel regions §Pseudo-state constraints."
    (is (= :rf.error/machine-history-misplaced
           (history-rejection-id
             {:type    :parallel
              :regions {:r {:type :history :initial :a :states {:a {}}}}}))
        "a region body declared `:type :history` is misplaced")))

(deftest scxml-history-extra-keys-rejected
  (testing "SCXML §3.10: a `:type :history` pseudo-state declaring keys
            outside the closed `:type` / `:deep?` / `:default-target` set
            (it is never occupied — `:on` / `:entry` / `:states` are
            meaningless) is rejected. Spec 005 §History states §Pseudo-state
            constraints; error `:rf.error/machine-history-extra-keys`."
    (is (= :rf.error/machine-history-extra-keys
           (history-rejection-id
             {:initial :c
              :states  {:c {:initial :a
                            :states  {:a {}
                                      :h {:type :history :on {:go :a}}}}}}))
        "a history node declaring `:on` is rejected for extra keys")))

(deftest scxml-history-well-placed-validates
  (testing "Control + first-class smoke: a `:type :history` node correctly
            placed inside a compound's `:states` validates cleanly — history
            is a claimed (`:fsm/history`) capability, not a deferred grammar
            feature. Spec 005 §History states; W3C 387/388 shape."
    (is (= ::no-throw
           (history-rejection-id
             {:initial :player
              :states  {:player {:initial :stopped
                                 :states  {:stopped {:on {:play [:player :hist]}}
                                           :hist    {:type :history :deep? true
                                                     :default-target :playing}
                                           :playing {:initial :at-start
                                                     :states {:at-start {}}}}}}}))
        "a well-placed first-class history pseudo-state validates without error")))

(deftest scxml-history-duplicate-per-compound-rejected
  (testing "SCXML §3.10 / re-frame2 divergence: SCXML allows sibling
            shallow+deep <history> under one state, but re-frame2 permits AT
            MOST ONE history pseudo-state per compound (deep-vs-shallow is the
            single node's `:deep?`). Two history children under one compound is
            `:rf.error/machine-history-duplicate`. Spec 005 §Pseudo-state
            constraints. This is the divergence the W3C-adapted fixtures
            (scxml-history-test387/388) split around."
    (is (= :rf.error/machine-history-duplicate
           (history-rejection-id
             {:initial :s0
              :states  {:s0 {:initial :s01
                             :states  {:s01          {}
                                       :s02          {}
                                       :hist-deep    {:type :history :deep? true}
                                       :hist-shallow {:type :history}}}}}))
        "two history nodes under one compound is rejected as duplicate")))

(deftest scxml-history-bad-default-target-rejected
  (testing "SCXML §3.10: a history pseudo-state's default transition must target
            a real state. re-frame2 rejects a `:default-target` that does not
            resolve (not a direct child of the owning compound, nor a declared
            absolute path) with `:rf.error/machine-history-bad-default-target`.
            Spec 005 §Pseudo-state constraints."
    (is (= :rf.error/machine-history-bad-default-target
           (history-rejection-id
             {:initial :s0
              :states  {:s0 {:initial :s01
                             :states  {:s01  {}
                                       :hist {:type :history :default-target :nonexistent}}}}}))
        "an unresolvable :default-target is rejected")))

;; ---------------------------------------------------------------------------
;; §13b. HISTORY record/restore — the W3C 387/388/579/580 family adapted
;; ---------------------------------------------------------------------------
;;
;; Behavioural corpus driven through the pure `machine-transition` engine
;; (`step` above unwraps the Result). Each test cites the W3C test id it
;; derives from and the re-frame2 spec anchor; the EDN Mode-B counterparts at
;; spec/conformance/fixtures/ give the same coverage through the conformance
;; runner. These run dual-runtime (JVM + CLJS) so the engine's history
;; semantics are externally proven on both.

(deftest scxml-history-test388-deep-restores-exact-leaf
  (testing "W3C test388 (DEEP arm): deep history restores the EXACT deepest
            prior leaf, not the compound's :initial. Spec 005 §Restoration."
    (let [m {:initial :s0
             :states  {:s0   {:initial :s01
                              :on      {:leave :away}
                              :states  {:s01  {:initial :s011 :states {:s011 {} :s012 {}}}
                                        :s02  {:initial :s021 :states {:s021 {} :s022 {}}}
                                        :hist {:type :history :deep? true :default-target :s022}}}
                       :away {:on {:return [:s0 :hist]}}}}
          left (step m {:state [:s0 :s01 :s012] :data {}} [:leave])]
      (is (= [:s0 :s01 :s012] (get-in (:snapshot left) [:rf/history [:s0]]))
          "deep recorded the full leaf path on exit")
      (is (= [:s0 :s01 :s012] (:state (step m (:snapshot left) [:return])))
          "deep restored the EXACT recorded leaf (s012)"))))

(deftest scxml-history-test388-shallow-restores-child-then-initial
  (testing "W3C test388 (SHALLOW arm): shallow history restores only the
            immediate child and applies its :initial — from the IDENTICAL exit
            leaf as the deep arm. Spec 005 §Restoration."
    (let [m {:initial :s0
             :states  {:s0   {:initial :s01
                              :on      {:leave :away}
                              :states  {:s01  {:initial :s011 :states {:s011 {} :s012 {}}}
                                        :s02  {:initial :s021 :states {:s021 {} :s022 {}}}
                                        :hist {:type :history :default-target :s02}}}
                       :away {:on {:return [:s0 :hist]}}}}
          left (step m {:state [:s0 :s01 :s012] :data {}} [:leave])]
      (is (= :s01 (get-in (:snapshot left) [:rf/history [:s0]]))
          "shallow recorded only the direct child :s01")
      (is (= [:s0 :s01 :s011] (:state (step m (:snapshot left) [:return])))
          "shallow restored :s01 then its :initial (s011), NOT the deep leaf s012"))))

(deftest scxml-history-test387-default-fires-at-both-levels
  (testing "W3C test387: the default history transition resolves correctly at
            both shallow (child's :initial descent) and deep (exact deep
            target) levels on FIRST entry (no recording). Spec 005 §Restoration
            — never-entered = :default-target / :initial."
    ;; Shallow default → child's :initial descent.
    (let [shallow {:initial :hub
                   :states  {:hub {:on {:into [:s0 :hist]}}
                             :s0  {:initial :s01
                                   :states  {:s01  {:initial :s011 :states {:s011 {} :s012 {}}}
                                             :hist {:type :history :default-target :s01}}}}}]
      (is (= [:s0 :s01 :s011] (:state (step shallow {:state :hub :data {}} [:into])))
          "shallow :default-target :s01 descended to its :initial s011"))
    ;; Deep default → exact deep target.
    (let [deep {:initial :hub
                :states  {:hub {:on {:into [:s1 :hist]}}
                          :s1  {:initial :s11
                                :states  {:s11  {:initial :s111 :states {:s111 {} :s112 {}}}
                                          :s12  {:initial :s121 :states {:s121 {} :s122 {}}}
                                          :hist {:type :history :deep? true
                                                 :default-target [:s1 :s12 :s122]}}}}}]
      (is (= [:s1 :s12 :s122] (:state (step deep {:state :hub :data {}} [:into])))
          "deep :default-target resolves to the exact deep target s122"))))

(deftest scxml-history-test579-default-only-when-no-recording
  (testing "W3C test579: the default history transition fires ONLY when nothing
            is recorded; once a config is stored it overrides the default.
            Spec 005 §Restoration."
    (let [m {:initial :s0
             :states  {:s0   {:initial :sh
                              :on      {:leave :away}
                              :states  {:sh  {:type :history :default-target :s01}
                                        :s01 {:on {:next :s02}}
                                        :s02 {:on {:next :s03}}
                                        :s03 {}}}
                       :away {:on {:return [:s0 :sh]}}}}]
      (is (= [:s0 :s01] (:state (step m {:state :away :data {}} [:return])))
          "first entry (no recording) ⇒ :default-target :s01")
      (is (= [:s0 :s02]
             (:state (step m {:state :away :data {} :rf/history {[:s0] :s02}} [:return])))
          "a recorded config (:s02) overrides the default — default NOT re-applied"))))

(deftest scxml-history-test580-parallel-region-shallow
  (testing "W3C test580: a shallow history inside a compound within a PARALLEL
            region remembers the most-recent child, keyed region-qualified; the
            sibling region is unaffected. Spec 005 §Composition with parallel
            regions — per-region history."
    (let [m {:type    :parallel
             :regions {:s0 {:initial :idle :states {:idle {} :busy {}}}
                       :s1 {:initial :group
                            :states  {:group  {:initial :sh
                                               :on      {:exit-to-park [:s1park]}
                                               :states  {:sh  {:type :history :default-target :s11}
                                                         :s11 {:on {:to12 :s12}}
                                                         :s12 {}}}
                                      :s1park {:on {:back [:group :sh]}}}}}}
          parked (step m {:state {:s0 :idle :s1 [:group :s12]} :data {}} [:exit-to-park])]
      (is (= :s12 (get-in (:snapshot parked) [:rf/history [:s1 :group]]))
          "shallow recorded :s12 under the region-qualified key [:s1 :group]")
      (let [back (step m (:snapshot parked) [:back])]
        (is (= [:group :s12] (get-in (:snapshot back) [:state :s1]))
            "region :s1 restored its remembered child :s12")
        (is (= :idle (get-in (:snapshot back) [:state :s0]))
            "sibling region :s0 untouched by the :s1 history restore")))))
