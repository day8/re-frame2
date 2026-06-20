(ns re-frame.scxml-irp-semantic-core-cljs-test
  "W3C SCXML IRP semantic-core conformance corpus.

  ## What this namespace is

  The sibling `scxml_conformance_cljs_test.cljc` establishes the
  SCXML-anchored corpus and proves a SUBSET of the W3C SCXML IRP
  (Implementation Report Plan, https://www.w3.org/Voice/2013/scxml-irp/)
  semantic-core families: the microstep/internal-queue (144), transition
  selection + cond ordering (153/155), entry/exit LCCA ordering (504/505),
  parallel broadcast (403/404), parallel done (570/580), eventless settle
  (372/388 family), history (387/388/579/580), and the default-initial
  cascade (355/372). This namespace COMPLETES the semantic core: it AUDITS
  the W3C IRP MANDATORY semantic-core test set against the re-frame2
  coverage (the `## W3C SCXML IRP SEMANTIC-CORE COVERAGE MATRIX` below) and
  carries the NOT-COVERED ids as additive conformance cases here, each
  citing its W3C test id + Spec 005 anchor + xstate@5.x verification,
  matching the sibling file's citation discipline.

  These cases live in their OWN namespace (this file). They drive the SAME
  pure engine surface (`re-frame.machines/machine-transition` + the pure
  predicates + the registration validator) so they are JVM- and
  CLJS-runnable from arguments alone, dual-runtime via the `*-cljs-test` /
  `*_cljs_test.cljc` convention (discovered by both `npm run test:cljs` and
  `clojure -M:test`).

  XState v5 (xstate@5.32.0, the recorded gold standard) is the reference for
  every behaviour; the W3C SCXML IRP semantic-core is the canonical corpus.
  Where re-frame2 deliberately diverges from the raw SCXML shape
  (substrate-constrained, or behavioural parity via different expression),
  the case asserts the RE-FRAME2 behaviour and CITES the documented
  divergence rather than the SCXML default.

  =========================================================================
  ## W3C SCXML IRP SEMANTIC-CORE COVERAGE MATRIX
  =========================================================================

  The W3C IRP MANDATORY + AUTOMATED tests whose RULE is pure state-chart
  semantics (no `<datamodel>`/`<assign>`/`<script>`/cond-expression
  evaluation, no `<send>`/`<invoke>`/I-O-processor mechanics — both excluded
  by design, the same line xstate draws; see the sibling file's
  `## SCXML EXCLUSIONS`). Each row is COVERED (by an existing fixture or
  deftest), NEW (ported here), or OUT-OF-SCOPE-with-reason.

  Initial / default-entry:
    355  initial absent ⇒ first child in document order   COVERED
                                  (scxml-initial-cascade-* — first-child default)
    364  enter compound ⇒ enter its default initial child COVERED
                                  (scxml-initial-cascade-enters-every-level-*)
    412  default-entry runs the compound's initial child   COVERED (same)
    413  machine placed in the initial-specified config    COVERED
                                  (scxml-initial-cascade-*, initial_entry_test)
    576  initial attribute present ⇒ enter those states    COVERED (scxml-final-leaf-compound, initial_*)

  History (also 3xx):
    387  default history target before first visit         COVERED (scxml-history-test387-*)
    388  stored history config restored after first visit  COVERED (scxml-history-test388-*)
    579  default history fires only when nothing recorded  COVERED (scxml-history-test579-*)
    580  history pseudo-state never in active config /
         per-region shallow                                COVERED (scxml-history-test580-*)

  Entry/exit handlers + ordering:
    375  onentry handlers run in document order            NEW — onentry/onexit
                                  re-frame2 collapses N <onentry> blocks to ONE
                                  `:entry` action per node; the cross-NODE entry
                                  cascade order IS the 375 rule re-frame2 expresses.
                                  scxml-irp-test375-onentry-single-block-doc-order.
    376  each onentry is an independent block (one throw
         doesn't kill the siblings)                        OUT-OF-SCOPE (divergence) —
                                  one `:entry` action per node, so there are no
                                  sibling blocks to isolate; a throwing `:entry`
                                  yields a failure Result (no partial commit).
                                  Asserted as the divergence in
                                  scxml-irp-test376-onentry-throw-is-failure-result.
    377  onexit handlers run in document order             NEW (377 = exit twin of 375)
                                  scxml-irp-test377-onexit-single-block-doc-order.
    378  each onexit is an independent block               OUT-OF-SCOPE (divergence, as 376).
    407  run a state's onexit when it is exited            COVERED (scxml-lca-cascade-*)
    409  state removed from config after its onexit runs   COVERED (cascade order ⇒ membership timing)
    411  state added to config + onentry on entry          COVERED (scxml-lca-cascade-*, scxml-initial-cascade-*)

  Event-descriptor matching:
    396  match transition event attr against event name    NEW (re-anchor to the IRP id)
                                  scxml-irp-test396-exact-event-name-match.
    399  descriptor matching = exact OR token-prefix       NEW — re-frame2 spells the
                                  SCXML §3.12.1 dot-prefix token descriptor as the
                                  keyword NAMESPACE-wildcard `:ns/*`; a bare
                                  (non-namespaced) event has no token tier.
                                  scxml-irp-test399-*.

  done / final / parallel-done:
    372  done.state.id raised after onentry, before onexit COVERED (scxml-compound-done-*)
    415  final child of <scxml> root halts processing      NEW — re-frame2 top-level
                                  finality (whole-machine done) is the analogue;
                                  scxml-irp-test415-top-level-final-is-machine-final.
    416  done.state.id for a <final> child of a compound   COVERED (scxml-compound-done-*, scxml-final-leaf-*)
    417  done.state.id for <parallel> when all final       COVERED (scxml-parallel-done-*)
    570  parallel done.state when all regions final        COVERED (scxml-parallel-done-when-every-region-final)

  Transition selection / conflict / optimal set / exit-entry / LCCA:
    403  optimal enabled set; descendant priority; doc ord COVERED (scxml-deepest-wins-*, scxml-transition-selection-*)
    404  exit the exit set first                           COVERED (scxml-lca-cascade-*)
    405  transition content after exits, before entries    COVERED (scxml-lca-cascade-* — :ACTION at the boundary)
    406  enter the entry set after transition content      COVERED (scxml-lca-cascade-*)
    503  targetless transition ⇒ EMPTY exit set           NEW — re-anchor: an action
                                  fires, NO exit/entry, descendants preserved.
                                  scxml-irp-test503-targetless-empty-exit-set.
    504  external transition exit set = LCCA descendants   COVERED (scxml-lca-cascade-*)
    505  internal transition (compound source): target's
         descendants in the exit set                       NEW — re-frame2 / xstate v5
                                  FLIP the SCXML internal/external DEFAULT:
                                  an explicit on-path target is INTERNAL by
                                  default (re-resolves descendants); external
                                  restart is opt-in `:reenter? true`.
                                  scxml-irp-test505-internal-default-re-resolves-descendants.
    506  internal treated as external when not applicable  NEW — disjoint-subtree target
                                  is always external (exit/enter both leaves).
                                  scxml-irp-test506-disjoint-target-is-external.
    533  internal transition is external for a NON-compound
         (atomic) source                                   NEW — an atomic leaf has no
                                  descendants to re-resolve, so an explicit
                                  self-target with no `:reenter?` is a config no-op
                                  (xstate v5). scxml-irp-test533-atomic-source-self-target.

  Eventless / internal queue / microstep / macrostep / raise:
    144  raised events FIFO on the internal queue          COVERED (machine_raise_fifo_test;
                                  re-anchored here to the IRP id:
                                  scxml-irp-test144-internal-raise-fifo).
    158  executable-content block runs in document order   NEW — a transition `:action`'s
                                  side effects run in source order; the cascade
                                  exit→action→entry is the cross-boundary order.
                                  scxml-irp-test158-executable-content-doc-order.
    419  after a stable config, run the optimal NULL
         (eventless) transition set                        NEW — re-anchor: eventless
                                  fires only at a stable config, deepest-first.
                                  scxml-irp-test419-eventless-optimal-set-at-quiescence.
    421  process the internal queue (microstep) before the
         next external event                               COVERED-as-NEW — raised +
                                  eventless work drains fully within ONE macrostep
                                  before the result is observable.
                                  scxml-irp-test421-internal-queue-drains-before-return.
    423  wait for an external event, then run the enabled
         set as a microstep (macrostep boundary)           COVERED — every `step` is one
                                  macrostep; an unhandled event is a no-op
                                  (scxml-unhandled-event-is-noop-not-error).

  Executable-content document order (datamodel-agnostic):
    158  see above.

  OUT-OF-SCOPE families (documented, same line xstate draws):
    147-156, 159, 525  <if>/<foreach>/<else>/cond-expr      DATAMODEL — re-frame2
                                  guards/actions are host fns, no string cond.
    172-253, 521, 527-554  <send>/<cancel>/<invoke> I-O      SEND-INVOKE-IO — effects-as-data
                                  + declarative `:spawn`, not the SCXML wire model.
    301-354, 487-501, 550-578 (most)  datamodel / system
         variables (_event/_sessionid/_ioprocessors) / I-O  DATAMODEL / IO.
    310, 436  In() state predicate via cond-expression      DATAMODEL — re-frame2 reads
                                  the configuration directly in a host-fn guard.
  ========================================================================="
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [re-frame.machines :as machines]
   [re-frame.machines.result :as result]
   [re-frame.machines.transition :as transition]))

;; ---------------------------------------------------------------------------
;; Harness — the pure engine surface (mirrors the sibling corpus file).
;; ---------------------------------------------------------------------------

(defn- step
  "Apply `event` to `machine` against `snapshot` via the pure
  `machine-transition` engine. Returns the post-macrostep snapshot, the
  emitted fx vector, and the snapshot's `:state` / `:data`."
  [machine snapshot event]
  (let [r (machines/machine-transition machine snapshot event)]
    {:snapshot (result/snap r)
     :fx       (vec (result/fx r))
     :state    (:state (result/snap r))
     :data     (:data (result/snap r))}))

(defn- order-recorder
  "Return `[log-atom mk]` where `(mk tag)` is an action/entry/exit fn that
  appends `tag` to `log-atom` in invocation order and returns nil. Used to
  observe the concrete entry/exit/action invocation sequence."
  []
  (let [log (atom [])]
    [log (fn [tag] (fn [_ctx] (swap! log conj tag) nil))]))

;; ===========================================================================
;; §A. Event-descriptor matching — W3C IRP test396 / test399 (SCXML §3.12.1)
;;
;; SCXML §3.12.1: a transition's `event` attribute matches the raised event
;; by exact name (396) OR by a token-prefix descriptor — `error` matches
;; `error.send.failed` (399). re-frame2 events are NAMESPACED KEYWORDS, so
;; the keyword namespace is the natural token-prefix tier: `:error/*` is
;; re-frame2's spelling of SCXML's `error.*` partial descriptor.
;; The three tiers resolve most-specific-first at each level: exact >
;; namespace-wildcard `:ns/*` > total `:*`. Spec 005 §Wildcard transitions
;; §Namespaced (partial) event descriptors. xstate v5 partial descriptor
;; `mouse.*`. (The full `:ns/*` tier is also exercised by
;; `machines_ns_wildcard_cljs_test`; this re-anchors it to the IRP ids.)
;; ===========================================================================

(deftest scxml-irp-test396-exact-event-name-match
  (testing "W3C IRP test396 (SCXML §3.12.1): a transition whose `event`
            descriptor is the EXACT event name is selected over a broader
            token-prefix descriptor at the same state — exact wins. Spec 005
            §Wildcard transitions — exact > :ns/* > :*."
    (let [m {:initial :a :data {}
             :states  {:a {:on {:error/parse :exact         ;; exact token
                                 :error/*     :ns-wild       ;; token-prefix
                                 :*           :total}}        ;; total catch-all
                       :exact {} :ns-wild {} :total {}}}
          r (step m {:state :a :data {}} [:error/parse])]
      (is (= :exact (:state r))
          "the exact event-name descriptor :error/parse wins over :error/* and :*"))))

(deftest scxml-irp-test399-token-prefix-descriptor-catches-family
  (testing "W3C IRP test399 (SCXML §3.12.1): a token-prefix descriptor matches
            any event sharing the leading token — SCXML `error` catches
            `error.send.failed`; re-frame2's `:error/*` catches any
            `:error/...` event with no exact descriptor. Spec 005 §Wildcard
            transitions §Namespaced (partial) event descriptors (rf2-z4t2v)."
    (let [m {:initial :a :data {}
             :states  {:a {:on {:error/parse :exact
                                 :error/*     :family
                                 :*           :total}}
                       :exact {} :family {} :total {}}}
          ;; :error/timeout has no exact descriptor; the :error/* token-prefix
          ;; catches it (more specific than the total :*).
          fam   (step m {:state :a :data {}} [:error/timeout])
          ;; a BARE (non-namespaced) event has no token tier — only :* catches.
          plain (step m {:state :a :data {}} [:plain])]
      (is (= :family (:state fam))
          ":error/* token-prefix catches :error/timeout (no exact descriptor)")
      (is (= :total (:state plain))
          "a bare non-namespaced event has no token tier ⇒ only the total :* catches it"))))

(deftest scxml-irp-test399-token-prefix-leaf-shadows-parent-exact
  (testing "W3C IRP test399 + §3.13 descendant-priority: a leaf's token-prefix
            descriptor (`:mouse/*`) is consulted at the deeper level BEFORE
            the parent's EXACT descriptor for the same event — deepest-wins
            extends to the token-prefix tier exactly as it does to the total
            `:*` (cf. scxml-leaf-wildcard-shadows-parent-explicit). Spec 005
            §Transition resolution × §Wildcard transitions."
    (let [m {:initial :p :data {}
             :states  {:p {:on      {:mouse/down :parent-exact}  ;; parent EXACT
                           :initial :c
                           :states  {:c {:on {:mouse/* :leaf-token}}  ;; leaf token-prefix
                                     :leaf-token {} :parent-exact {}}}}}
          r (step m {:state [:p :c] :data {}} [:mouse/down])]
      (is (= [:p :leaf-token] (:state r))
          "the leaf's :mouse/* token-prefix wins over the parent's exact :mouse/down (deepest-wins)"))))

;; ===========================================================================
;; §B. Targetless transition ⇒ EMPTY exit set — W3C IRP test503 (SCXML §3.13)
;;
;; SCXML §3.13 computeExitSet: a transition with NO `target` has an EMPTY
;; exit set — no state is exited or re-entered; only the transition's
;; executable content runs. xstate v5: "To preserve child states, omit
;; target entirely." Spec 005 §Self-transitions (the targetless internal
;; form). (The sibling file proves the compound-targetless case via
;; scxml-targetless-on-compound-preserves-active-descendants; this re-anchors
;; the EXIT-SET rule to the IRP id at the leaf and confirms the empty exit
;; set with an order-recorder.)
;; ===========================================================================

(deftest scxml-irp-test503-targetless-empty-exit-set
  (testing "W3C IRP test503 (SCXML §3.13 computeExitSet): a targetless
            transition has an EMPTY exit set — the action fires but NO
            onexit/onentry, and the configuration (including active
            descendants) is preserved. Spec 005 §Self-transitions."
    (let [[log mk] (order-recorder)
          m {:initial :a :data {:n 0}
             :actions {:bump (fn [{d :data}] (swap! log conj :ACTION)
                               {:data (update d :n inc)})}
             :states  {:a {:entry (mk :entry) :exit (mk :exit)
                           :on {:tick {:action :bump}}}}}      ;; NO :target
          r (step m {:state :a :data {:n 0}} [:tick])]
      (is (= :a (:state r)) "targetless ⇒ configuration unchanged")
      (is (= 1 (get-in r [:data :n])) "the transition action ran")
      (is (= [:ACTION] @log)
          "EMPTY exit set: only the action fired — no onexit, no onentry"))))

;; ===========================================================================
;; §C. Internal vs external transition exit set — W3C IRP test505/506/533
;;      (SCXML §3.13; re-frame2 / xstate v5 FLIP the default)
;;
;; SCXML §3.13 distinguishes `type="internal"` (target's descendants in the
;; exit set, source compound NOT exited) from `type="external"` (the source
;; compound is in the exit set). SCXML's DEFAULT is EXTERNAL. xstate v5
;; FLIPS the default — an explicit on-path target is INTERNAL by default
;; (re-resolve descendants, do NOT re-enter the compound), and the external
;; restart is the opt-in `reenter: true`. re-frame2 follows xstate v5.
;; These cases assert the v5 behaviour and CITE the divergence from SCXML's
;; external-default; the SCXML external semantics are still EXERCISED via
;; `:reenter? true` in the sibling §10/§10b/§10c. Verified against
;; xstate@5.32.0. Spec 005 §Self-transitions §The three explicit-target
;; geometries.
;; ===========================================================================

(deftest scxml-irp-test505-internal-default-re-resolves-descendants
  (testing "W3C IRP test505 (SCXML §3.13 internal-transition exit set) under
            the xstate v5 DEFAULT FLIP (rf2-eicq0): an explicit target naming
            the CURRENT COMPOUND (no :reenter?) is INTERNAL — the source
            compound is NOT in the exit set (no compound onexit/onentry), but
            the target's ACTIVE DESCENDANTS ARE (xstate v5: an explicit target
            re-resolves child states to their initial). DIVERGES from SCXML's
            external default; the external restart is opt-in `:reenter? true`
            (sibling §10c). Verified against xstate@5.32.0. Spec 005
            §Self-transitions."
    (let [[log mk] (order-recorder)
          m {:initial :p :data {}
             :states {:p {:entry (mk :entry-P) :exit (mk :exit-P)  ;; NOT in exit set
                          :initial :s1
                          :on {:re {:target :p :action (mk :ACTION)}}  ;; own kw, no reenter
                          :states {:s1 {:entry (mk :entry-1) :exit (mk :exit-1)}
                                   :s3 {:entry (mk :entry-3) :exit (mk :exit-3)}}}}}
          ;; active at the non-initial child :s3; internal re-resolution exits
          ;; the active descendant and re-descends :p's :initial (:s1).
          r (step m {:state [:p :s3] :data {}} [:re])]
      (is (= [:p :s1] (:state r))
          "internal default re-resolves :p's descendants — active child resets to :initial (:s1)")
      (is (= [:exit-3 :ACTION :entry-1] @log)
          "exit set = the target's active descendant (:s3) only; :p itself NOT exited/entered (internal default, v5 flip)"))))

(deftest scxml-irp-test506-disjoint-target-is-external
  (testing "W3C IRP test506 (SCXML §3.13): a transition to a state that is
            NOT on the active path (a disjoint sibling subtree) is treated as
            EXTERNAL — both the source leaf and the target leaf cross the
            cascade boundary (exit source, enter target), the common ancestor
            untouched. This is the always-external case the internal default
            does not apply to. Spec 005 §Entry/exit cascading along the LCCA."
    (let [[log mk] (order-recorder)
          m {:initial :p :data {}
             :states {:p {:entry (mk :entry-P) :exit (mk :exit-P)  ;; LCCA — untouched
                          :initial :a
                          :states {:a {:entry (mk :entry-A) :exit (mk :exit-A)
                                       :on {:go {:target :b :action (mk :ACTION)}}}
                                   :b {:entry (mk :entry-B) :exit (mk :exit-B)}}}}}
          r (step m {:state [:p :a] :data {}} [:go])]
      (is (= [:p :b] (:state r)) "disjoint sibling target lands at :b")
      (is (= [:exit-A :ACTION :entry-B] @log)
          "external: exit source :a → action → enter target :b; LCCA :p untouched"))))

(deftest scxml-irp-test533-atomic-source-self-target-is-noop
  (testing "W3C IRP test533 (SCXML §3.13 — internal exit set defined as
            external for a NON-COMPOUND source) under the v5 default flip: an
            ATOMIC (leaf) state targeting ITSELF with no :reenter? has no
            descendants to re-resolve, so the configuration is unchanged and
            ONLY the action fires (a true internal no-op). The external
            restart (onexit → action → onentry) requires `:reenter? true`
            (sibling scxml-external-self-transition-*). Spec 005
            §Self-transitions."
    (let [[log mk] (order-recorder)
          m {:initial :a :data {:n 0}
             :actions {:bump (fn [{d :data}] (swap! log conj :ACTION)
                               {:data (update d :n inc)})}
             :states {:a {:entry (mk :entry) :exit (mk :exit)
                          :on {:self {:target :a :action :bump}}}}}  ;; own kw, no reenter
          r (step m {:state :a :data {:n 0}} [:self])]
      (is (= :a (:state r)) "atomic self-target (no :reenter?) leaves the configuration unchanged")
      (is (= 1 (get-in r [:data :n])) "the action ran")
      (is (= [:ACTION] @log)
          "no descendants to re-resolve ⇒ only the action fired — no onexit/onentry (internal no-op, v5)"))))

;; ===========================================================================
;; §D. onentry / onexit document order + the single-block divergence —
;;      W3C IRP test375/376/377/378 (SCXML §3.8, §3.9)
;;
;; SCXML allows MULTIPLE `<onentry>` / `<onexit>` blocks per state, run in
;; document order (375/377), each an INDEPENDENT block so a throw in one does
;; not abort the siblings (376/378). re-frame2 DIVERGES (documented): each
;; state node carries at most ONE `:entry` and one `:exit` action-ref (Spec
;; 005 §Entry / exit actions). So:
;;   • 375/377 (document order) re-frame2 expresses as the CROSS-NODE entry /
;;     exit cascade order along the LCCA — the per-node single block fires
;;     shallowest-first on entry, deepest-first on exit (asserted below; the
;;     sibling file's scxml-lca-cascade-* proves the multi-level cascade).
;;   • 376/378 (independent blocks) do NOT apply — there are no sibling
;;     blocks within one node to isolate. The re-frame2 behaviour for a
;;     throwing boundary action is a FAILURE Result with no partial commit
;;     (transactional pre-commit; Spec 005). Asserted as the divergence.
;; ===========================================================================

(deftest scxml-irp-test375-onentry-single-block-doc-order
  (testing "W3C IRP test375 (SCXML §3.8 onentry document order), expressed via
            re-frame2's single-block-per-node model: entering nested states
            fires each node's ONE `:entry` action shallowest-first along the
            entry cascade (re-frame2's document-order analogue). Spec 005
            §Entry / exit actions §Cascade ordering. Divergence: re-frame2 has
            one `:entry` per node, not N <onentry> blocks."
    (let [[log mk] (order-recorder)
          m {:initial :start :data {}
             :states {:start {:on {:enter [:outer]}}
                      :outer {:entry (mk :entry-outer)
                              :initial :mid
                              :states {:mid {:entry (mk :entry-mid)
                                             :initial :leaf
                                             :states {:leaf {:entry (mk :entry-leaf)}}}}}}}
          r (step m {:state :start :data {}} [:enter])]
      (is (= [:outer :mid :leaf] (:state r)))
      (is (= [:entry-outer :entry-mid :entry-leaf] @log)
          "onentry fires shallowest-first along the entry cascade (375 document-order analogue)"))))

(deftest scxml-irp-test377-onexit-single-block-doc-order
  (testing "W3C IRP test377 (SCXML §3.9 onexit document order), expressed via
            re-frame2's single-block model: exiting nested states fires each
            node's ONE `:exit` action DEEPEST-first along the exit cascade
            (the SCXML reverse-document-order rule). Spec 005 §Entry / exit
            actions §Cascade ordering."
    (let [[log mk] (order-recorder)
          m {:initial :p :data {}
             :states {:p {:initial :a
                          :on {:leave [:done]}     ;; absolute target out of :p
                          :states {:a {:exit (mk :exit-a)
                                       :initial :x
                                       :states {:x {:exit (mk :exit-x)}}}}}
                      :done {}}}
          r (step m {:state [:p :a :x] :data {}} [:leave])]
      (is (= [:done] (:state r)))
      (is (= [:exit-x :exit-a] @log)
          "onexit fires deepest-first (:x then :a) along the exit cascade (377 reverse-document-order)"))))

(deftest scxml-irp-test376-378-onentry-throw-is-failure-result-divergence
  (testing "W3C IRP test376/378 (independent onentry/onexit blocks) DIVERGENCE:
            re-frame2 has ONE `:entry`/`:exit` action per node, so there are
            no sibling blocks to isolate. A throwing boundary action yields a
            FAILURE Result (transactional pre-commit — no partial snapshot
            commit), NOT a 'continue past the throwing block' the 376/378
            rule describes. This asserts the re-frame2 behaviour and documents
            the divergence. Spec 005 §Entry / exit actions §Error handling."
    (let [m {:initial :a :data {}
             :states {:a {:on {:go :b}}
                      :b {:entry (fn [_] (throw (ex-info "onentry boom" {})))}}}
          r (machines/machine-transition m {:state :a :data {}} [:go])]
      (is (result/fail? r)
          "a throwing :entry action produces a failure Result (no partial commit) — re-frame2's single-block divergence from SCXML 376/378 independent-block isolation"))))

;; ===========================================================================
;; §E. Final child of the ROOT halts the machine — W3C IRP test415 (SCXML §3.7)
;;
;; SCXML §3.7: entering a `<final>` child of the `<scxml>` ROOT terminates
;; processing (the whole machine is done). re-frame2 analogue: a top-level
;; `:final?` leaf is WHOLE-MACHINE finality (vs an embedded final leaf, which
;; is only compound-done — sibling scxml-embedded-final-does-not-leak-to-
;; machine-finality). The DISTINGUISHING predicate is `top-level-final?`
;; (length-1 path AND `:final?`): it is true for a root final and FALSE for
;; an embedded final, whereas `final-on-leaf?` answers only "is the active
;; leaf final?" and is true for BOTH — so it cannot prove the root-vs-embedded
;; rule on its own. test415 therefore asserts `top-level-final?`; the embedded
;; counter-case below pins the predicates apart. Spec 005 §Final states
;; §Top-level vs embedded.
;; ===========================================================================

(deftest scxml-irp-test415-top-level-final-is-machine-final
  (testing "W3C IRP test415 (SCXML §3.7): entering a `<final>` child of the
            root halts the machine. re-frame2: a TOP-LEVEL `:final?` leaf is
            whole-machine finality — `top-level-final?` is true. We assert
            `top-level-final?` (NOT `final-on-leaf?`): it is the predicate that
            actually encodes the root-vs-embedded distinction this rule claims
            to prove (`final-on-leaf?` is true for an embedded final too, so it
            cannot). Spec 005 §Final states §Top-level vs embedded."
    (let [m {:initial :run :data {}
             :states {:run {:on {:finish :done}}
                      :done {:final? true}}}
          r (step m {:state :run :data {}} [:finish])]
      (is (= :done (:state r)) "transitioned into the top-level final leaf")
      (is (true? (transition/top-level-final? m (:state r)))
          "a top-level :final? leaf IS whole-machine finality (415 — root final halts)")
      (is (true? (transition/final-on-leaf? m (:state r)))
          "the active leaf is also final (final-on-leaf? agrees on a top-level final)")
      (is (false? (transition/top-level-final? m :run))
          "a non-final leaf is not machine-final")
      (is (false? (transition/final-on-leaf? m :run))
          "a non-final leaf is not a final leaf"))))

(deftest scxml-irp-test415-embedded-final-is-not-machine-final
  (testing "W3C IRP test415 counter-case (SCXML §3.7): a `<final>` leaf
            EMBEDDED inside a compound is NOT whole-machine finality — it
            signals only that the enclosing compound is done (the machine
            keeps running). This is the case that PINS the two predicates
            apart and proves the root-vs-embedded distinction: on the same
            embedded-final config `final-on-leaf?` is TRUE (the active leaf is
            final) while `top-level-final?` is FALSE (the path is length 2, not
            a direct child of the root). xstate v5 / SCXML §3.7 gold standard
            (rf2-bnjb3 / rf2-zlmz7). Spec 005 §Final states §Top-level vs
            embedded."
    (let [m {:initial :work :data {}
             :states {:work {:initial :step1
                             :states {:step1 {:on {:finish :step-done}}
                                      :step-done {:final? true}}}}}
          r (step m {:state [:work :step1] :data {}} [:finish])]
      (is (= [:work :step-done] (:state r))
          "transitioned into the embedded final leaf (still inside :work)")
      (is (true? (transition/final-on-leaf? m (:state r)))
          "final-on-leaf? is TRUE — the active leaf :step-done is :final?")
      (is (false? (transition/top-level-final? m (:state r)))
          "top-level-final? is FALSE — an embedded final is compound-done, NOT
           whole-machine finality (the predicates DIVERGE here — this is the
           root-vs-embedded distinction test415 claims to prove)"))))

;; ===========================================================================
;; §F. Internal queue + eventless macrostep — W3C IRP test144/158/419/421
;;
;; SCXML §3.13 macrostep: raised internal events drain FIFO (144), executable
;; content runs in document order (158), the optimal eventless transition set
;; runs only at a stable config (419), and the WHOLE internal queue (raised +
;; eventless) drains within one macrostep before the next external event
;; (421). The sibling file proves the eventless fixed-point (§6) and
;; machine_raise_fifo_test proves 144; these re-anchor the rules to their IRP
;; ids and add the document-order + within-one-macrostep assertions. Spec 005
;; §Eventless `:always` transitions §Macrostep semantics; §`:raise` FIFO.
;; ===========================================================================

(deftest scxml-irp-test144-internal-raise-fifo
  (testing "W3C IRP test144 (SCXML internal-event queue FIFO): raised events
            drain FIFO — an action raising [:b] then [:c], where :b's action
            raises [:d], processes :b, :c, :d (the nested :d goes to the BACK,
            behind the still-pending sibling :c), NOT depth-first. xstate v5
            / SCXML gold standard (rf2-nr434). Spec 005 §`:raise` FIFO."
    (let [log (atom [])
          la  (fn [label & raises]
                (fn [{:keys [data]}]
                  (swap! log conj label)
                  (when (seq raises)
                    {:data data :fx (mapv (fn [ev] [:raise ev]) raises)})))
          m {:initial :hub :data {}
             :actions {:go (la :go [:b] [:c])   ;; raise :b then :c
                       :b  (la :b [:d])          ;; :b raises :d
                       :c  (la :c)
                       :d  (la :d)}
             :states {:hub {:on {:go {:action :go}
                                 :b  {:action :b}
                                 :c  {:action :c}
                                 :d  {:action :d}}}}}]
      (step m {:state :hub :data {}} [:go])
      (is (= [:go :b :c :d] @log)
          "FIFO: nested raise :d lands behind sibling :c — NOT depth-first [:go :b :d :c]"))))

(deftest scxml-irp-test158-executable-content-document-order
  (testing "W3C IRP test158 (SCXML §4 executable content runs in document
            order): across a single transition the exit-action → transition
            action → entry-action sequence fires in the SCXML
            exit/content/entry order — the cross-boundary document order of
            executable content. Spec 005 §Entry / exit actions §Cascade
            ordering."
    (let [[log mk] (order-recorder)
          m {:initial :a :data {}
             :states {:a {:exit (mk :exit-A)
                          :on {:go {:target :b :action (mk :ACTION)}}}
                      :b {:entry (mk :entry-B)}}}
          r (step m {:state :a :data {}} [:go])]
      (is (= :b (:state r)))
      (is (= [:exit-A :ACTION :entry-B] @log)
          "executable content in document order: onexit → transition content → onentry (158)"))))

(deftest scxml-irp-test419-eventless-optimal-set-at-quiescence
  (testing "W3C IRP test419 (SCXML §3.13: select the optimal NULL/eventless
            transition set only AFTER the config is stable): an eventless
            `:always` fires only once the config has settled, and is
            evaluated deepest-first. Here the external :go lands at :pending,
            whose :always (guard already true) immediately redirects to
            :resolved within the SAME macrostep. Spec 005 §Eventless
            transitions §Macrostep semantics."
    (let [m {:initial :idle :data {:ready? true}
             :guards  {:ready? (fn [{d :data}] (true? (:ready? d)))}
             :states  {:idle    {:on {:go :pending}}
                       :pending {:always [{:guard :ready? :target :resolved}]}
                       :resolved {}}}
          r (step m {:state :idle :data {:ready? true}} [:go])]
      (is (= :resolved (:state r))
          "the optimal eventless set ran at the post-:go stable config, settling :idle → :pending → :resolved in one macrostep"))))

(deftest scxml-irp-test421-internal-queue-drains-before-return
  (testing "W3C IRP test421 (SCXML §3.13: the internal queue is fully
            processed before the next external event): every raised internal
            event drains completely within ONE macrostep — the observer sees
            only the FINAL settled config, never an intermediate one. The
            external :kick raises :one then :two; :one's handler raises
            :three; all three bump a counter and run within the single
            macrostep, so the result carries n=3 (the fully-drained queue),
            not n=1 (an early return after the first raise). Spec 005
            §`:raise` FIFO."
    (let [m {:initial :a :data {:n 0}
             :actions {:kick  (fn [{d :data}] {:data d :fx [[:raise [:one]] [:raise [:two]]]})
                       :one   (fn [{d :data}] {:data (update d :n inc) :fx [[:raise [:three]]]})
                       :two   (fn [{d :data}] {:data (update d :n inc)})
                       :three (fn [{d :data}] {:data (update d :n inc)})}
             :states  {:a {:on {:kick  {:action :kick}
                                :one   {:action :one}
                                :two   {:action :two}
                                :three {:action :three}}}}}
          r (step m {:state :a :data {:n 0}} [:kick])]
      (is (= :a (:state r)) "the machine settles at :a (internal queue drained)")
      (is (= 3 (get-in r [:data :n]))
          "the WHOLE internal queue drained within one macrostep (:one, :two, :three each bumped once ⇒ n=3); the observer never saw an intermediate n=1 or n=2"))))
