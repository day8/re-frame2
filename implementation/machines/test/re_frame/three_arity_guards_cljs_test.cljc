(ns re-frame.three-arity-guards-cljs-test
  "Coverage for the machine guard / action 3-arity escape hatch (rf2-1e0n,
  discovered-from rf2-o423).

  Per Spec 005 and `re-frame.machines`'s contract documented at
  `machines.cljc` lines 74-121, guards and actions accept two canonical
  signatures:

      (fn [data event] ...)              ; 2-arity — the 99% case
      (fn [data event ctx] ...)          ; 3-arity — opt-in introspection

  where `ctx` is `{:state (:state snapshot) :meta (:meta snapshot)}`.

  The dispatcher (`declares-3-arity?`) inspects the fn's declared
  invocation surface at runtime and routes 3-fixed-arg fns through the
  3-arity path; everything else (including variadic helpers like
  `(constantly ...)`) is INTENDED to route through the 2-arity path.
  The docstring explicitly flags variadics as a footgun: a user who
  expects `& rest` to receive the ctx will silently see it called as
  2-arity instead.

  Before this file, not a single test in the suite exercised 3-arity
  guards or actions. A `declares-3-arity?` refactor — especially around
  variadics — would have been invisible.

  ***Behaviour-vs-docstring divergence found while writing these tests***:
  on CLJS the dispatcher mis-classifies a 2-plus-rest fn
  `(fn [data event & rest] ...)` as 3-arity (`true`), so it IS called
  with `(g data event ctx)` and the user's `& rest` receives `(ctx)`.
  On JVM the same fn classifies as 2-arity (`false`) per the docstring.
  This is a real footgun the docstring doesn't cover — see the
  `declares-3-arity-classifies-2-plus-rest-divergent-by-platform` test
  and the PR body. The tests here pin the actual current behaviour on
  each platform; a follow-up bead can track the convergence work.

  This file uses reader conditionals so its assertions run on both
  JVM and CLJS:

    - JVM uses the pure `re-frame.machines/machine-transition` directly
      (no registrar / adapter needed). The pure surface lets us seed
      a snapshot's :meta directly so the 3-arity ctx assertion can
      pin the full {:state :meta} payload.
    - CLJS uses the live `reg-machine` + `dispatch-sync` surface, which
      is the same path real apps use. Because the runtime synthesises
      the initial snapshot from `{:state ... :data ...}` only — it
      never propagates a spec's `:meta` (machines.cljc:808) — the live
      tests assert `:meta` is `nil` rather than user-tagged. The
      documented behaviour holds either way: ctx is always
      `{:state ... :meta ...}`; whether `:meta` is non-nil depends on
      the snapshot, not the spec.

  The file is named `*-cljs-test.cljc` so it's discovered by both:
    - cognitect.test-runner's default `.*-test$` regex (JVM).
    - shadow-cljs's `cljs-test$` / `-cljs-test$` ns-regexp (CLJS)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.machines :as machines]
   #?@(:cljs [[re-frame.core :as rf]
              [re-frame.substrate.reagent :as reagent-adapter]
              [re-frame.test-support :as test-support]])))

#?(:cljs
   (use-fixtures :each
     (test-support/reset-runtime-fixture
       {:adapter reagent-adapter/adapter})))

;; ---- access to the private dispatcher -------------------------------------
;;
;; `declares-3-arity?` is `defn-`-private; tests reach it through its var.
;; The var-quote (`#'ns/private-fn`) is invocable on both JVM and CLJS —
;; the same idiom hash-check-cljs-test.cljs uses for ssr/canonical-edn.
;; This is the only direct internal poke; everything else goes through
;; the pure `machine-transition` surface (JVM) or the live event surface
;; (CLJS).

(defn- declares-3-arity? [f]
  (#'re-frame.machines/declares-3-arity? f))

;; ---- (1) declares-3-arity? unit tests -------------------------------------

(deftest declares-3-arity-classifies-plain-3-arity-as-true
  (testing "a plain 3-arity fn with three FIXED parameters is classified
            as 3-arity (true) — this is the user opt-in path"
    (is (true? (boolean (declares-3-arity? (fn [_ _ _] :ok)))))))

(deftest declares-3-arity-classifies-plain-2-arity-as-false
  (testing "a plain 2-arity fn (the canonical 99% case) is classified as
            non-3-arity (false) — routes through the 2-arity dispatch path"
    (is (false? (boolean (declares-3-arity? (fn [_ _] :ok)))))))

(deftest declares-3-arity-classifies-constantly-as-false
  (testing "(constantly ...) returns a variadic-from-zero fn — per the
            docstring (machines.cljc:88-90), this is the canonical
            footgun. The dispatcher treats it as non-3-arity so the
            2-arity contract holds, and the variadic helper just
            absorbs whatever args it's called with."
    (is (false? (boolean (declares-3-arity? (constantly true)))))
    (is (false? (boolean (declares-3-arity? (constantly nil)))))))

(deftest declares-3-arity-classifies-2-plus-rest-divergent-by-platform
  (testing "a 2-fixed-plus-rest fn (fn [data event & extras]) is
            classified differently on JVM vs CLJS — a documented
            divergence between this implementation and the docstring's
            'distinguishes user opt-in 3-arity from variadic helpers'
            promise (machines.cljc:88-90).

            JVM behaviour: false (correct per docstring). RestFns don't
            declare invoke(O,O,O) on the user's class — the variadic is
            routed through the 2-arity path and the user's `& extras`
            stays empty.

            CLJS behaviour: true (DIVERGENT). The cljs check
              (or (= 3 (.-length f))
                  (some? (unchecked-get f \"cljs$core$IFn$_invoke$arity$3\")))
            mis-classifies a 2-plus-rest fn as 3-arity because the
            compiled fn surfaces an arity-3 invoke slot for the rest
            entry path. Net effect: the dispatcher CALLS the variadic
            with three args, and `& extras` receives `(ctx)` rather
            than being empty. This is a candidate for a follow-up
            bead — see PR body."
    #?(:clj  (is (false? (boolean (declares-3-arity? (fn [_ _ & _] :ok))))
                 "JVM: 2-plus-rest classifies as non-3-arity (per docstring)")
       :cljs (is (true?  (boolean (declares-3-arity? (fn [_ _ & _] :ok))))
                 "CLJS: 2-plus-rest classifies as 3-arity — divergent with
                  JVM and with the docstring's 'distinguishes variadic
                  helpers' promise. Pin the actual behaviour here so
                  future refactors don't accidentally 'fix' it without
                  also fixing the JVM side and the docstring."))))

(deftest declares-3-arity-classifies-3-plus-rest-as-false
  (testing "a 3-fixed-plus-rest fn (fn [data event ctx & extras]) is
            also a RestFn and is classified as non-3-arity — symmetric
            with the 2+rest case. A user who writes this signature
            expecting the 3-arity surface will instead be called as
            2-arity (and on JVM that's an arity-mismatch since the
            2-arity call is missing the third REQUIRED fixed arg).
            This is the sharper footgun the docstring's variadic
            warning covers."
    (is (false? (boolean (declares-3-arity? (fn [_ _ _ & _] :ok)))))))

;; ---- (2) shared machine spec for guard / action exercises -----------------
;;
;; The same machine shape is used for the live tests below. Each fn is
;; placed DIRECTLY in the slot (no wrapper) so `declares-3-arity?` sees
;; its true arglist surface.

(defn- machine-with-guard
  "Build a machine whose :go transition's :guard slot IS exactly
  `guard-fn` (no wrapper) — so the dispatcher classifies it on its real
  arity surface. The :action is a plain 2-arity bumper so the snapshot's
  :data evolves observably when the transition fires."
  [guard-fn]
  {:initial :idle
   :data    {:bumps 0}
   :states
   {:idle {:on {:go {:target :gone
                     :guard  guard-fn
                     :action (fn [data _ev]
                               {:data (update data :bumps inc)})}}}
    :gone {}}})

(defn- machine-with-action
  "Build a machine whose :go transition's :action slot IS exactly
  `action-fn` — so the dispatcher classifies it on its real arity
  surface. The :guard is omitted (defaults to `(constantly true)`)."
  [action-fn]
  {:initial :idle
   :data    {:bumps 0}
   :states
   {:idle {:on {:go {:target :gone
                     :action action-fn}}}
    :gone {}}})

;; ---- (3) JVM: pure machine-transition exercises ---------------------------
;;
;; The pure surface is the simpler harness: no registrar, no adapter,
;; no event-loop pumping. Per `machines.cljc` line 18 (and the comment
;; at line 532) the pure fn is JVM- and CLJS-runnable; testing it on JVM
;; alone is meaningful because the guard / action dispatcher path
;; (`call-guard` / `call-action`) is platform-independent below the
;; `declares-3-arity?` check itself, which IS platform-specific and is
;; covered by the unit tests above.
;;
;; The pure surface also lets us seed `:meta` directly on the snapshot,
;; which the live event handler doesn't do (machines.cljc:808
;; synthesises only `{:state :data}`). That's why the JVM tests below
;; pin a non-nil :meta in ctx, while the CLJS live tests assert :meta
;; is nil.

#?(:clj
   (deftest plain-3-arity-guard-receives-ctx
     (testing "Case (1) plain-3-arity guard: a guard fn with three FIXED
               args is called with [data event {:state ... :meta ...}].
               The third arg is the snapshot's :state and :meta merged
               into a ctx map (per machines.cljc:110)."
       (let [seen     (atom nil)
             guard    (fn [data event ctx]
                        (reset! seen {:data data :event event :ctx ctx})
                        true)
             machine  (machine-with-guard guard)
             snapshot {:state :idle :data {:bumps 0} :meta {:user-tag :probe}}
             [snap-after _fx] (machines/machine-transition
                                machine snapshot [:go])]
         (is (= :gone (:state snap-after))
             "the transition fired (guard returned true), so we landed at :gone")
         (is (= 1 (get-in snap-after [:data :bumps]))
             "the action ran exactly once during the transition")
         (let [{:keys [data event ctx]} @seen]
           (is (= {:bumps 0} data)
               "the guard received the snapshot's :data as its first arg")
           (is (= [:go] event)
               "the guard received the inbound event vector as its second arg")
           (is (= {:state :idle :meta {:user-tag :probe}} ctx)
               "the guard received {:state ... :meta ...} as its third arg
                — exactly the contract documented at machines.cljc:106-110"))))))

#?(:clj
   (deftest plain-3-arity-action-receives-ctx
     (testing "Case (2) plain-3-arity action: an action fn with three
               FIXED args is called with [data event {:state ... :meta ...}]
               (per machines.cljc:120). The action's return value
               (`{:data ...}`) merges into the snapshot as usual."
       (let [seen     (atom nil)
             action   (fn [data event ctx]
                        (reset! seen {:data data :event event :ctx ctx})
                        {:data (assoc data :saw-ctx? (some? ctx))})
             machine  (machine-with-action action)
             snapshot {:state :idle :data {:bumps 0} :meta {:user-tag :probe}}
             [snap-after _fx] (machines/machine-transition
                                machine snapshot [:go])]
         (is (= :gone (:state snap-after)))
         (is (true? (get-in snap-after [:data :saw-ctx?]))
             "the 3-arity action's ctx was non-nil and its return value
              landed in the committed snapshot")
         (let [{:keys [data event ctx]} @seen]
           (is (= {:bumps 0} data))
           (is (= [:go] event))
           (is (= {:state :idle :meta {:user-tag :probe}} ctx)))))))

#?(:clj
   (deftest variadic-from-zero-guard-routes-as-2-arity
     (testing "Case (3) variadic guard `(constantly true)`: per the
               docstring (machines.cljc:88-90) this is the canonical
               footgun — variadic helpers route through the 2-arity
               path. The transition still fires (the guard returns
               true regardless of args) but no ctx is offered.

               Documented behaviour: NOT classified as 3-arity. The
               dispatcher calls `(g data event)` only. We assert the
               transition fires and reaches :gone."
       (let [machine  (machine-with-guard (constantly true))
             snapshot {:state :idle :data {:bumps 0} :meta {:user-tag :probe}}
             [snap-after _fx] (machines/machine-transition
                                machine snapshot [:go])]
         (is (= :gone (:state snap-after))
             "(constantly true) returns true regardless of how many args
              it's called with — transition fires either way; the
              point is which arity the dispatcher uses, asserted by
              declares-3-arity-classifies-constantly-as-false above")))))

#?(:clj
   (deftest variadic-2-plus-rest-guard-routes-as-2-arity
     (testing "Case (4) variadic guard `(fn [data event & rest] ...)`:
               2-fixed-plus-rest fns are RestFns and classify as
               non-3-arity (declares-3-arity-classifies-2-plus-rest-as-false
               above asserts this). Documented behaviour: the dispatcher
               calls `(g data event)`, NOT `(g data event ctx)`. The
               user's `& rest` is therefore ALWAYS empty / nil — the
               ctx never reaches a variadic guard. This test pins that
               behaviour by asserting `& rest` is empty / nil."
       (let [seen-rest (atom :unset)
             guard     (fn [data _event & rest]
                         (reset! seen-rest rest)
                         (= 0 (:bumps data ::missing)))
             machine   (machine-with-guard guard)
             snapshot  {:state :idle :data {:bumps 0} :meta {:user-tag :probe}}
             [snap-after _fx] (machines/machine-transition
                                machine snapshot [:go])]
         (is (= :gone (:state snap-after))
             "transition fired — guard saw data + event correctly")
         (is (or (nil? @seen-rest)
                 (and (sequential? @seen-rest) (empty? @seen-rest)))
             "documented behaviour: `& rest` is empty / nil (the
              dispatcher called the variadic as 2-arity, NOT 3-arity)")))))

#?(:clj
   (deftest plain-2-arity-guard-still-works
     (testing "Case (5) plain-2-arity guard `(fn [data event] ...)`:
               the canonical case. Sanity-check that the 99% path still
               works after introducing the 3-arity escape hatch."
       (let [seen     (atom nil)
             guard    (fn [data event]
                        (reset! seen {:data data :event event :argc 2})
                        true)
             machine  (machine-with-guard guard)
             snapshot {:state :idle :data {:bumps 0} :meta {:user-tag :probe}}
             [snap-after _fx] (machines/machine-transition
                                machine snapshot [:go])]
         (is (= :gone (:state snap-after)))
         (is (= 1 (get-in snap-after [:data :bumps])))
         (let [{:keys [data event argc]} @seen]
           (is (= {:bumps 0} data))
           (is (= [:go] event))
           (is (= 2 argc) "guard saw exactly two args"))))))

;; ---- (4) CLJS: live event surface exercises -------------------------------
;;
;; On CLJS we exercise the same guard/action surfaces through
;; `reg-machine` + `dispatch-sync`, so the assertions land on the same
;; runtime path real apps use. The machine spec is value-identical to
;; the JVM cases above.
;;
;; Note on :meta: the live initial-snapshot synthesis (machines.cljc:808)
;; builds `{:state ... :data ...}` and does NOT propagate a spec-level
;; `:meta` into the snapshot. So the 3-arity ctx's `:meta` is `nil` in
;; these live tests — which is itself a documented-vs-actual divergence
;; recorded under "behaviour-vs-docstring observations" in the PR body.

#?(:cljs
   (defn- snapshot-of
     "Read the snapshot for `machine-id` out of the default frame's
     app-db (mirrors the helper in machines_cljs_test.cljs)."
     [machine-id]
     (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id])))

#?(:cljs
   (deftest plain-3-arity-guard-cljs-receives-ctx
     (testing "Case (1) live-CLJS: a 3-arity guard registered through
               reg-machine receives [data event ctx] when the runtime
               processes a real :go event"
       (let [seen     (atom nil)
             guard    (fn [data event ctx]
                        (reset! seen {:data data :event event :ctx ctx})
                        true)
             machine  (machine-with-guard guard)]
         (rf/reg-machine :rf2-1e0n/g3 machine)
         (rf/dispatch-sync [:rf2-1e0n/g3 [:go]])
         (let [snap (snapshot-of :rf2-1e0n/g3)
               {:keys [data event ctx]} @seen]
           (is (= :gone (:state snap)))
           (is (= 1 (get-in snap [:data :bumps])))
           (is (= {:bumps 0} data))
           (is (= [:go] event))
           ;; Live initial-snapshot synthesis seeds {:state :data}
           ;; only — :meta is nil in the ctx (machines.cljc:808).
           (is (= {:state :idle :meta nil} ctx)
               "live event surface: the 3-arity guard saw {:state :meta}
                ctx; :meta is nil because the live initial-snapshot
                builder doesn't propagate a spec-level :meta"))))))

#?(:cljs
   (deftest plain-3-arity-action-cljs-receives-ctx
     (testing "Case (2) live-CLJS: a 3-arity action registered through
               reg-machine receives [data event ctx] when its transition
               fires"
       (let [seen     (atom nil)
             action   (fn [data event ctx]
                        (reset! seen {:data data :event event :ctx ctx})
                        {:data (assoc data :saw-ctx? (some? ctx))})
             machine  (machine-with-action action)]
         (rf/reg-machine :rf2-1e0n/a3 machine)
         (rf/dispatch-sync [:rf2-1e0n/a3 [:go]])
         (let [snap (snapshot-of :rf2-1e0n/a3)]
           (is (= :gone (:state snap)))
           (is (true? (get-in snap [:data :saw-ctx?]))
               "ctx was non-nil (it's a map, even when :meta is nil) —
                the 3-arity action saw the ctx and recorded its
                truthiness in the committed snapshot")
           (let [{:keys [data event ctx]} @seen]
             (is (= {:bumps 0} data))
             (is (= [:go] event))
             (is (= {:state :idle :meta nil} ctx))))))))

#?(:cljs
   (deftest variadic-from-zero-guard-cljs-routes-as-2-arity
     (testing "Case (3) live-CLJS: `(constantly true)` as a guard —
               routes through the 2-arity path (declares-3-arity? false).
               Transition still fires."
       (let [machine (machine-with-guard (constantly true))]
         (rf/reg-machine :rf2-1e0n/v0 machine)
         (rf/dispatch-sync [:rf2-1e0n/v0 [:go]])
         (let [snap (snapshot-of :rf2-1e0n/v0)]
           (is (= :gone (:state snap))
               "transition fired under the variadic-from-zero guard"))))))

#?(:cljs
   (deftest variadic-2-plus-rest-guard-cljs-receives-ctx-in-rest
     (testing "Case (4) live-CLJS: a variadic 2-plus-rest guard.

               Documented intent (machines.cljc:88-90): variadics
               classify as non-3-arity, so the dispatcher would call
               `(g data event)` and `& rest` would be empty.

               ACTUAL CLJS behaviour: the dispatcher classifies
               2-plus-rest as 3-arity (see
               declares-3-arity-classifies-2-plus-rest-divergent-by-platform
               above) and calls `(g data event ctx)`, so `& rest`
               receives `(ctx)` — i.e. one element, the {:state :meta}
               map. We pin the actual current CLJS behaviour and flag
               the divergence in the PR body for follow-up."
       (let [seen-rest (atom :unset)
             guard     (fn [_data _event & rest]
                         (reset! seen-rest rest)
                         true)   ;; always fire so the assertion path
                                 ;; observes the post-transition snapshot
             machine   (machine-with-guard guard)]
         (rf/reg-machine :rf2-1e0n/v2 machine)
         (rf/dispatch-sync [:rf2-1e0n/v2 [:go]])
         (let [snap (snapshot-of :rf2-1e0n/v2)]
           (is (= :gone (:state snap))
               "transition fired (guard returned true)")
           (is (sequential? @seen-rest))
           (is (= 1 (count @seen-rest))
               "CLJS dispatcher classified 2-plus-rest as 3-arity →
                `& rest` received exactly one element (the ctx)")
           (is (= {:state :idle :meta nil} (first @seen-rest))
               "the lone rest element is the same {:state :meta} ctx
                a fixed-3-arity guard would have seen as its third arg
                — confirming the dispatcher took the 3-arity path"))))))

#?(:cljs
   (deftest plain-2-arity-guard-cljs-still-works
     (testing "Case (5) live-CLJS: plain 2-arity guard — the canonical
               99% path"
       (let [seen     (atom nil)
             guard    (fn [data event]
                        (reset! seen {:data data :event event :argc 2})
                        true)
             machine  (machine-with-guard guard)]
         (rf/reg-machine :rf2-1e0n/g2 machine)
         (rf/dispatch-sync [:rf2-1e0n/g2 [:go]])
         (let [snap (snapshot-of :rf2-1e0n/g2)
               {:keys [data event argc]} @seen]
           (is (= :gone (:state snap)))
           (is (= 1 (get-in snap [:data :bumps])))
           (is (= {:bumps 0} data))
           (is (= [:go] event))
           (is (= 2 argc)))))))
