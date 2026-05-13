(ns re-frame.three-arity-guards-cljs-test
  "Coverage for the machine guard / action 3-arity escape hatch (rf2-1e0n,
  discovered-from rf2-o423; CLJS arity + initial-snapshot meta
  convergence rf2-l04j).

  Per Spec 005 and `re-frame.machines`'s contract documented at
  `machines.cljc` lines 74-121, guards and actions accept two canonical
  signatures:

      (fn [data event] ...)              ; 2-arity — the 99% case
      (fn [data event ctx] ...)          ; 3-arity — opt-in introspection

  where `ctx` is `{:state (:state snapshot) :meta (:meta snapshot)}`.

  The dispatcher (`declares-3-arity?`) inspects the fn's declared
  invocation surface at runtime and routes 3-fixed-arg fns through the
  3-arity path; everything else (including variadic helpers like
  `(constantly ...)` and 2-plus-rest fns) routes through the 2-arity
  path. The docstring flags variadics as a footgun: a user who expects
  `& rest` to receive the ctx will silently see it called as 2-arity
  instead.

  Before this file, not a single test in the suite exercised 3-arity
  guards or actions. A `declares-3-arity?` refactor — especially around
  variadics — would have been invisible.

  rf2-l04j convergence: previously the CLJS check classified a
  2-plus-rest fn `(fn [data event & rest] ...)` as 3-arity, so the
  user's `& rest` silently received `(ctx)`. JVM matched the docstring
  (returns false). The fix at `machines.cljc:declares-3-arity?` consults
  `cljs$lang$maxFixedArity` to detect variadicity: every variadic now
  routes through the 2-arity path on CLJS, matching JVM. The
  previously-divergent assertions below are now consistent across
  platforms.

  rf2-l04j also fixes the live initial-snapshot synthesis at
  `machines.cljc:808` so a spec-level `:meta` propagates into the
  initial snapshot. The CLJS live tests can now assert a non-nil
  user-tagged `:meta` in ctx — the same payload the JVM pure-surface
  tests pin.

  This file uses reader conditionals so its assertions run on both
  JVM and CLJS:

    - JVM uses the pure `re-frame.machines/machine-transition` directly
      (no registrar / adapter needed). The pure surface lets us seed
      a snapshot's :meta directly so the 3-arity ctx assertion can
      pin the full {:state :meta} payload.
    - CLJS uses the live `reg-machine` + `dispatch-sync` surface, which
      is the same path real apps use. With the rf2-l04j initial-
      snapshot fix, a spec-level `:meta` is reflected in the synthesised
      snapshot, so the live ctx now carries the user's tag.

  The file is named `*-cljs-test.cljc` so it's discovered by both:
    - cognitect.test-runner's default `.*-test$` regex (JVM).
    - shadow-cljs's `cljs-test$` / `-cljs-test$` ns-regexp (CLJS)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.machines :as machines]
   [re-frame.machines.result :as result]
   #?@(:cljs [[re-frame.core :as rf]
              [re-frame.adapter.reagent :as reagent-adapter]
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
  (#'re-frame.machines.transition/declares-3-arity? f))

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

(deftest declares-3-arity-classifies-2-plus-rest-as-false
  (testing "a 2-fixed-plus-rest fn (fn [data event & extras]) is
            classified as non-3-arity on BOTH JVM and CLJS (rf2-l04j).

            Previously CLJS classified this as 3-arity because the
            compiled variadic surfaces a `cljs$core$IFn$_invoke$arity$3`
            slot used by the rest dispatcher; the user's `& extras`
            silently received `(ctx)` as a single element. The fix at
            `declares-3-arity?` consults `cljs$lang$maxFixedArity` to
            detect variadicity — a variadic with max-fixed < 3 is a
            2-plus-rest helper and is NOT a 3-arity declaration, so
            CLJS now matches the JVM behaviour and the docstring's
            'distinguishes user opt-in 3-arity from variadic helpers'
            promise."
    (is (false? (boolean (declares-3-arity? (fn [_ _ & _] :ok))))
        "2-plus-rest classifies as non-3-arity on both JVM and CLJS")))

(deftest declares-3-arity-classifies-3-plus-rest-as-false
  (testing "a 3-fixed-plus-rest fn (fn [data event ctx & extras]) is a
            RestFn; on JVM it doesn't expose a declared `invoke(O,O,O)`
            on the user's class, so it classifies as non-3-arity. To
            keep the rule simple and platform-consistent, CLJS treats
            ANY variadic as non-3-arity (rf2-l04j) — including
            3-plus-rest. A user who wants the 3-arity surface should
            declare three FIXED args without `& rest`, as the canonical
            shape `(fn [data event ctx] ...)`."
    (is (false? (boolean (declares-3-arity? (fn [_ _ _ & _] :ok)))))))

(deftest declares-3-arity-classifies-multi-arity-with-3-as-true
  (testing "a multi-arity fn that includes a 3-arity case — e.g.
            `(fn ([a] :one) ([a b c] :three))` — has an explicit 3-arity
            dispatch slot and IS a 3-arity declaration on both JVM and
            CLJS. The dispatcher routes through the user's 3-arity case."
    (let [f (fn ([_a] :one) ([_a _b _c] :three))]
      (is (true? (boolean (declares-3-arity? f)))))))

(deftest declares-3-arity-classifies-multi-arity-without-3-as-false
  (testing "a multi-arity fn whose only fixed cases are 1 and 2
            (no 3-arity case) is NOT a 3-arity declaration on either
            platform."
    (let [f (fn ([_a] :one) ([_a _b] :two))]
      (is (false? (boolean (declares-3-arity? f)))))))

;; ---- (2) shared machine spec for guard / action exercises -----------------
;;
;; The same machine shape is used for the live tests below. Each fn is
;; placed DIRECTLY in the slot (no wrapper) so `declares-3-arity?` sees
;; its true arglist surface.

(defn- machine-with-guard
  "Build a machine whose :go transition's :guard slot IS exactly
  `guard-fn` (no wrapper) — so the dispatcher classifies it on its real
  arity surface. The :action is a plain 2-arity bumper so the snapshot's
  :data evolves observably when the transition fires.

  A spec-level `:meta {:user-tag :probe}` is seeded so the live CLJS
  tests can assert that the rf2-l04j initial-snapshot fix propagates
  the spec's `:meta` into the synthesised snapshot — the 3-arity ctx
  thus carries `{:state ... :meta {:user-tag :probe}}` rather than a
  nil :meta."
  [guard-fn]
  {:initial :idle
   :data    {:bumps 0}
   :meta    {:user-tag :probe}
   :states
   {:idle {:on {:go {:target :gone
                     :guard  guard-fn
                     :action (fn [data _ev]
                               {:data (update data :bumps inc)})}}}
    :gone {}}})

(defn- machine-with-action
  "Build a machine whose :go transition's :action slot IS exactly
  `action-fn` — so the dispatcher classifies it on its real arity
  surface. The :guard is omitted (defaults to `(constantly true)`).

  A spec-level `:meta {:user-tag :probe}` is seeded for the same
  reason as `machine-with-guard`."
  [action-fn]
  {:initial :idle
   :data    {:bumps 0}
   :meta    {:user-tag :probe}
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
;; The pure surface also lets us seed `:meta` directly on the snapshot.
;; Post-rf2-l04j the live event handler also propagates a spec-level
;; `:meta` into the synthesised initial snapshot, so the JVM-pure and
;; CLJS-live assertions both pin the same `{:state ... :meta {...}}`
;; ctx shape — platform parity.

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
             {snap-after ::result/snap} (machines/machine-transition
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
             {snap-after ::result/snap} (machines/machine-transition
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
             {snap-after ::result/snap} (machines/machine-transition
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
             {snap-after ::result/snap} (machines/machine-transition
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
             {snap-after ::result/snap} (machines/machine-transition
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
;; Note on :meta: per rf2-l04j, the live initial-snapshot synthesis
;; (`machines.cljc:808`) now propagates a spec-level `:meta` into the
;; synthesised snapshot. The 3-arity ctx therefore carries the spec's
;; `:meta {:user-tag :probe}` (seeded by `machine-with-guard` /
;; `machine-with-action`), matching the JVM pure-surface assertions.

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
           ;; Per rf2-l04j the live initial-snapshot synthesis now
           ;; propagates the spec's `:meta` into the snapshot, so the
           ;; 3-arity ctx carries the user-tagged :meta — matching the
           ;; JVM pure-surface assertions.
           (is (= {:state :idle :meta {:user-tag :probe}} ctx)
               "live event surface: the 3-arity guard saw {:state :meta}
                ctx; :meta carries the spec-level :meta after rf2-l04j"))))))

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
             (is (= {:state :idle :meta {:user-tag :probe}} ctx)
                 "live event surface: 3-arity action's ctx carries the
                  spec-level :meta after rf2-l04j")))))))

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
   (deftest variadic-2-plus-rest-guard-cljs-routes-as-2-arity
     (testing "Case (4) live-CLJS: a variadic 2-plus-rest guard.

               Documented intent (machines.cljc:declares-3-arity?):
               variadics with max-fixed < 3 classify as non-3-arity, so
               the dispatcher calls `(g data event)` and `& rest` stays
               empty.

               After rf2-l04j, CLJS now agrees with JVM here (the check
               consults `cljs$lang$maxFixedArity` to detect the variadic
               and ignore the auto-generated arity-3 dispatch slot). The
               assertion below is now consistent across platforms — the
               same shape pinned by `variadic-2-plus-rest-guard-routes-
               as-2-arity` on JVM."
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
           (is (or (nil? @seen-rest)
                   (and (sequential? @seen-rest) (empty? @seen-rest)))
               "post-rf2-l04j: `& rest` is empty / nil — the dispatcher
                routed the variadic through the 2-arity path, matching
                JVM and the docstring's 'distinguishes variadic helpers'
                promise"))))))

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
