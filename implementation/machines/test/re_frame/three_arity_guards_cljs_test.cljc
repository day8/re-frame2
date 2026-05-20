(ns re-frame.three-arity-guards-cljs-test
  "Coverage for the machine guard / action 3-arity escape hatch (rf2-1e0n,
  discovered-from rf2-o423; CLJS arity + initial-snapshot meta
  convergence rf2-l04j; explicit `:rf.machine/wants-ctx` metadata opt-in
  rf2-2yupx).

  Per Spec 005 §3-arity escape hatch, guards and actions accept two
  canonical signatures:

      (fn [data event] ...)                  ; 2-arity — the 99% case
      ^:rf.machine/wants-ctx                 ; 3-arity — opt-in introspection
      (fn [data event ctx] ...)

  where `ctx` is `{:state (:state snapshot) :meta (:meta snapshot)}`.

  Per rf2-2yupx the dispatcher is **metadata-driven**, not structural:
  the runtime calls `(g data event ctx)` iff `(:rf.machine/wants-ctx
  (meta g))` is truthy. Plain 3-arity fns WITHOUT the metadata flag
  route through the 2-arity path (and will error at call time if the
  fn body actually requires a third positional). The previous
  structural arity-detection rule (Java reflection on JVM, compiled-fn
  surface introspection on CLJS) is gone — the metadata flag makes the
  user's intent explicit and eliminates per-call platform reflection.

  The variadic-fn footgun (`(fn [d e & rest] ...)` previously routed
  through 2-arity unexpectedly when the user wanted ctx) is also
  resolved: with metadata-driven opt-in, the user's flag governs
  dispatch regardless of arglist shape — `^:rf.machine/wants-ctx (fn
  [d e & rest] ...)` correctly sees ctx as the first element of rest.

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
     (test-support/reset-runtime-fixture-factory
       {:adapter reagent-adapter/adapter})))

;; ---- access to the private dispatcher -------------------------------------
;;
;; `wants-ctx?` is `defn-`-private; tests reach it through its var.
;; The var-quote (`#'ns/private-fn`) is invocable on both JVM and CLJS —
;; the same idiom hash-check-cljs-test.cljs uses for ssr/canonical-edn.
;; This is the only direct internal poke; everything else goes through
;; the pure `machine-transition` surface (JVM) or the live event surface
;; (CLJS).

(defn- wants-ctx? [f]
  (#'re-frame.machines.transition/wants-ctx? f))

;; ---- (1) wants-ctx? unit tests --------------------------------------------
;;
;; Per rf2-2yupx the dispatcher is metadata-driven: only the
;; `:rf.machine/wants-ctx true` flag in the fn's metadata routes the
;; call through the 3-arity path. The fn's arglist shape (fixed-arity,
;; variadic, multi-arity, RestFn) is irrelevant.

(deftest wants-ctx-recognises-explicit-opt-in
  (testing "a fn carrying `^:rf.machine/wants-ctx` metadata is classified
            as wanting ctx — this is the user opt-in path"
    (is (true? (wants-ctx? ^:rf.machine/wants-ctx (fn [_ _ _] :ok))))))

(deftest wants-ctx-recognises-helper-wrapper
  (testing "the `machines/wants-ctx` helper attaches the metadata flag —
            equivalent to the reader-macro form, useful for fns built
            by combinators or wrappers"
    (is (true? (wants-ctx? (machines/wants-ctx (fn [_ _ _] :ok)))))))

(deftest wants-ctx-plain-fn-without-metadata-is-false
  (testing "a plain fn (no opt-in metadata) routes through the 2-arity
            path regardless of its declared arity — there is no
            structural arity-detection anymore"
    (is (false? (wants-ctx? (fn [_ _] :ok))))
    (is (false? (wants-ctx? (fn [_ _ _] :ok))) ;; no metadata → 2-arity
        "plain 3-fixed-arity without metadata routes through 2-arity")
    (is (false? (wants-ctx? (fn [_ _ & _] :ok)))
        "2-plus-rest without metadata routes through 2-arity")
    (is (false? (wants-ctx? (fn [_ _ _ & _] :ok)))
        "3-plus-rest without metadata routes through 2-arity")
    (is (false? (wants-ctx? (constantly true)))
        "variadic-from-zero (e.g. (constantly ...)) routes through 2-arity")))

(deftest wants-ctx-resolves-variadic-footgun
  (testing "a variadic 2-plus-rest fn that DOES carry the opt-in flag
            correctly routes through 3-arity — the metadata flag wins
            over the arglist shape, resolving the pre-rf2-2yupx footgun
            where a variadic intended for ctx silently got 2-arity"
    (is (true? (wants-ctx? ^:rf.machine/wants-ctx (fn [_ _ & _] :ok))))))

;; ---- (2) shared machine spec for guard / action exercises -----------------
;;
;; The same machine shape is used for the live tests below. Each fn is
;; placed DIRECTLY in the slot (no wrapper) so the metadata it carries
;; reaches the dispatcher unmodified.

(defn- machine-with-guard
  "Build a machine whose :go transition's :guard slot IS exactly
  `guard-fn` (no wrapper) — so the dispatcher reads the fn's metadata
  unchanged. The :action is a plain 2-arity bumper so the snapshot's
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
  `action-fn` — so the dispatcher reads the fn's metadata unchanged.
  The :guard is omitted (defaults to `(constantly true)`).

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
;; no event-loop pumping. The pure fn is JVM- and CLJS-runnable; testing
;; it on JVM alone is meaningful because the guard / action dispatcher
;; path (`call-guard` / `call-action`) is platform-independent below the
;; `wants-ctx?` metadata check itself — and that check IS now platform-
;; independent too (a single `meta` lookup, no reflection), covered by
;; the unit tests above.
;;
;; The pure surface also lets us seed `:meta` directly on the snapshot.
;; Post-rf2-l04j the live event handler also propagates a spec-level
;; `:meta` into the synthesised initial snapshot, so the JVM-pure and
;; CLJS-live assertions both pin the same `{:state ... :meta {...}}`
;; ctx shape — platform parity.

#?(:clj
   (deftest opt-in-3-arity-guard-receives-ctx
     (testing "Case (1) opt-in 3-arity guard: a guard fn carrying the
               `^:rf.machine/wants-ctx` metadata flag is called with
               [data event {:state ... :meta ...}]. The third arg is
               the snapshot's :state and :meta merged into a ctx map."
       (let [seen     (atom nil)
             guard    ^:rf.machine/wants-ctx
                      (fn [data event ctx]
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
                — the contract documented at transition.cljc"))))))

#?(:clj
   (deftest opt-in-3-arity-action-receives-ctx
     (testing "Case (2) opt-in 3-arity action: an action fn carrying the
               `^:rf.machine/wants-ctx` metadata flag is called with
               [data event {:state ... :meta ...}]. The action's return
               value (`{:data ...}`) merges into the snapshot as usual."
       (let [seen     (atom nil)
             action   ^:rf.machine/wants-ctx
                      (fn [data event ctx]
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
   (deftest wants-ctx-helper-action-receives-ctx
     (testing "Case (2b) opt-in via `machines/wants-ctx` helper: the
               wrapper attaches the metadata flag programmatically,
               equivalent to the reader-macro form. The action is
               still called with [data event ctx]."
       (let [seen     (atom nil)
             action   (machines/wants-ctx
                        (fn [data event ctx]
                          (reset! seen {:data data :event event :ctx ctx})
                          {:data (assoc data :saw-ctx? (some? ctx))}))
             machine  (machine-with-action action)
             snapshot {:state :idle :data {:bumps 0} :meta {:user-tag :probe}}
             {snap-after ::result/snap} (machines/machine-transition
                                          machine snapshot [:go])]
         (is (= :gone (:state snap-after)))
         (is (true? (get-in snap-after [:data :saw-ctx?])))
         (let [{:keys [ctx]} @seen]
           (is (= {:state :idle :meta {:user-tag :probe}} ctx)
               "the `wants-ctx` helper opts the fn into the 3-arity path
                just like the reader-macro form"))))))

#?(:clj
   (deftest plain-3-arity-without-opt-in-routes-as-2-arity
     (testing "Case (3) plain 3-arity guard WITHOUT the opt-in flag:
               post-rf2-2yupx, arity is no longer structurally detected.
               A `(fn [_ _ _] ...)` without `^:rf.machine/wants-ctx` is
               called as 2-arity. Clojure raises
               ArityException — we assert the transition does NOT
               commit (engine catches the arity throw at run-action).

               The point: the user's metadata flag, not the arglist
               shape, governs dispatch. This is what makes the rule
               declarative and footgun-free."
       (let [guard    (fn [_data _event _ctx] true) ;; no opt-in metadata
             machine  (machine-with-guard guard)
             snapshot {:state :idle :data {:bumps 0} :meta {:user-tag :probe}}]
         (is (thrown? Throwable
               (machines/machine-transition machine snapshot [:go]))
             "without the opt-in flag, the engine calls (g data event);
              a fn whose body requires three fixed positionals throws
              an ArityException which propagates out of the pure
              transition fn")))))

#?(:clj
   (deftest variadic-from-zero-guard-routes-as-2-arity
     (testing "Case (4) variadic guard `(constantly true)`: no opt-in
               metadata, routes through the 2-arity path. The transition
               still fires (the guard returns true regardless of args).

               This shows that variadic helpers continue to work the
               same way as before — they were always 2-arity, and they
               stay 2-arity, just for the simpler reason: no metadata
               flag means no ctx."
       (let [machine  (machine-with-guard (constantly true))
             snapshot {:state :idle :data {:bumps 0} :meta {:user-tag :probe}}
             {snap-after ::result/snap} (machines/machine-transition
                                          machine snapshot [:go])]
         (is (= :gone (:state snap-after))
             "(constantly true) returns true regardless of how many args
              it's called with — transition fires either way; the
              point is which arity the dispatcher uses, asserted by
              wants-ctx-plain-fn-without-metadata-is-false above")))))

#?(:clj
   (deftest variadic-2-plus-rest-guard-routes-as-2-arity-when-no-opt-in
     (testing "Case (5) variadic guard `(fn [data event & rest] ...)`
               WITHOUT the opt-in flag: routes through 2-arity. The
               user's `& rest` is empty / nil — the ctx never reaches a
               variadic guard that didn't opt in."
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
             "no opt-in flag: `& rest` is empty / nil (the dispatcher
              called the variadic as 2-arity, NOT 3-arity)")))))

#?(:clj
   (deftest variadic-2-plus-rest-guard-routes-as-3-arity-when-opt-in
     (testing "Case (5b) variadic guard `(fn [data event & rest] ...)` WITH
               the opt-in flag: routes through 3-arity. The user's
               `& rest` now receives the ctx as its sole element.

               This is the rf2-2yupx footgun fix — pre-refactor, the
               structural arity-detection rule classified every variadic
               as 2-arity regardless of the user's intent. The
               metadata-driven rule respects the user's flag."
       (let [seen-rest (atom :unset)
             guard     ^:rf.machine/wants-ctx
                       (fn [_data _event & rest]
                         (reset! seen-rest rest)
                         true)
             machine   (machine-with-guard guard)
             snapshot  {:state :idle :data {:bumps 0} :meta {:user-tag :probe}}
             {snap-after ::result/snap} (machines/machine-transition
                                          machine snapshot [:go])]
         (is (= :gone (:state snap-after)))
         (is (= [{:state :idle :meta {:user-tag :probe}}] (vec @seen-rest))
             "with the opt-in flag, the variadic correctly received the
              ctx as the sole element of its `& rest`")))))

#?(:clj
   (deftest plain-2-arity-guard-still-works
     (testing "Case (6) plain-2-arity guard `(fn [data event] ...)`:
               the canonical case. Sanity-check that the 99% path still
               works after introducing the metadata-driven opt-in."
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
;; propagates a spec-level `:meta` into the synthesised snapshot. The
;; 3-arity ctx therefore carries the spec's `:meta {:user-tag :probe}`
;; (seeded by `machine-with-guard` / `machine-with-action`), matching
;; the JVM pure-surface assertions.

#?(:cljs
   (defn- snapshot-of
     "Read the snapshot for `machine-id` out of the default frame's
     app-db (mirrors the helper in machines_cljs_test.cljs)."
     [machine-id]
     (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id])))

#?(:cljs
   (deftest opt-in-3-arity-guard-cljs-receives-ctx
     (testing "Case (1) live-CLJS: a 3-arity guard with
               `^:rf.machine/wants-ctx` metadata registered through
               reg-machine receives [data event ctx] when the runtime
               processes a real :go event"
       (let [seen     (atom nil)
             guard    ^:rf.machine/wants-ctx
                      (fn [data event ctx]
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
   (deftest opt-in-3-arity-action-cljs-receives-ctx
     (testing "Case (2) live-CLJS: a 3-arity action with the opt-in
               flag registered through reg-machine receives
               [data event ctx] when its transition fires"
       (let [seen     (atom nil)
             action   ^:rf.machine/wants-ctx
                      (fn [data event ctx]
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
               no opt-in metadata, routes through the 2-arity path.
               Transition still fires."
       (let [machine (machine-with-guard (constantly true))]
         (rf/reg-machine :rf2-1e0n/v0 machine)
         (rf/dispatch-sync [:rf2-1e0n/v0 [:go]])
         (let [snap (snapshot-of :rf2-1e0n/v0)]
           (is (= :gone (:state snap))
               "transition fired under the variadic-from-zero guard"))))))

#?(:cljs
   (deftest variadic-2-plus-rest-guard-cljs-routes-as-2-arity-without-opt-in
     (testing "Case (4) live-CLJS: a variadic 2-plus-rest guard WITHOUT
               the opt-in flag routes through 2-arity. `& rest` stays
               empty (no ctx delivered). Platform-independent post-
               rf2-2yupx — the rule is metadata-driven, identical on
               JVM and CLJS."
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
               "no opt-in: `& rest` is empty / nil — the dispatcher
                routed the variadic through the 2-arity path"))))))

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
