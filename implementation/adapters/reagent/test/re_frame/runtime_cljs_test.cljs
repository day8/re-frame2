(ns re-frame.runtime-cljs-test
  "CLJS-side smoke tests. Verifies the JVM-shared API (events, subs,
  dispatch, registry) works under the Reagent reactive substrate AND
  exercises the CLJS-only macros from re-frame.core."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            ;; rf2-bmzq0: sub-cache-snapshot lives in re-frame.subs.tooling.
            [re-frame.subs.tooling :as subs-tooling]
            ;; rf2-0sr0ai: reg-runtime-sub (framework runtime-db sub) is an
            ;; internal subs surface, not on the rf/ facade — required for
            ;; the two-partition projection-equality invalidation pins below.
            [re-frame.subs :as subs]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            ;; rf2-k682: routing ships in day8/re-frame2-routing.
            ;; Required here so its load-time hook + reg-sub
            ;; registrations fire before this ns's reg-route call.
            [re-frame.routing :as routing]
            ;; rf2-tfw3: flows ships in day8/re-frame2-flows.
            ;; Required here so its load-time hook registrations
            ;; fire before this ns's reg-flow call.
            [re-frame.flows]
            [re-frame.ssr :as ssr]
            ;; rf2-lt4e: epoch ships in day8/re-frame2-epoch.
            ;; Required here so its load-time hook publications
            ;; (`:epoch/settle!`, `:epoch/capture-event`,
            ;; `:epoch/epoch-history`, `:epoch/restore-epoch!`,
            ;; `:epoch/register-epoch-listener!`, `:epoch/unregister-epoch-listener!`)
            ;; fire before the epoch-history-cljs / restore-* tests
            ;; below reach into the late-bind table at call time.
            [re-frame.epoch]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [with-frame with-new-frame
                                          frame-bound-fn reg-view]]))

;; Snapshot/restore the registrar around each test (rf2-am9d). Wiping the
;; registrar with clear-all! is hostile to CLJS test isolation: framework
;; events / subs registered at ns-load (re-frame.routing, re-frame.machines)
;; and example apps (nine-states.core) cannot be re-loaded under CLJS, so
;; once cleared they're gone for the rest of the test run. Snapshot/restore
;; preserves them while still rolling back per-test registrations.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- shared dispatch + sub --------------------------------------------------

(deftest dispatch-sync-cljs
  (testing "dispatch-sync runs a reg-event handler returning a :db effect under the Reagent adapter"
    (rf/reg-event :counter/init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :counter/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:counter/init])
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (is (= 2 (:n (rf/app-db-value :rf/default))))))

(deftest sub-chain-cljs
  (testing "layer-1 + layer-2 subs return computed values"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:items [10 20 30]}}))
    (rf/reg-sub :items     (fn [db _] (:items db)))
    (rf/reg-sub :item-sum  :<- [:items] (fn [items _] (reduce + items)))
    (rf/dispatch-sync [:seed])
    (is (= [10 20 30] (rf/subscribe-once [:items])))
    (is (= 60         (rf/subscribe-once [:item-sum])))))

;; ---- with-frame macro -------------------------------------------------------

(deftest with-frame-binds-current-frame
  (testing "with-frame :foo binds *current-frame* in the body"
    (with-frame :left
      (is (= :left (rf/current-frame-id))))
    (testing "with-new-frame [sym expr] binds the symbol AND the dynamic var"
      (with-new-frame [f :right]
        (is (= :right f))
        (is (= :right (rf/current-frame-id))))))
  (testing "outside any binding the dynamic var falls back to :rf/default"
    (is (= :rf/default (rf/current-frame-id)))))

;; ---- frame-bound-fn macro ---------------------------------------------------

(deftest frame-bound-fn-captures-frame
  (testing "frame-bound-fn captures the current frame and re-binds it inside the body"
    (rf/reg-frame :side {:doc "side frame"})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/dispatch-sync [:seed 99] {:frame :side})
    (let [captured (with-frame :side (frame-bound-fn [] (rf/current-frame-id)))]
      ;; Outside the with-frame, dynamic var has reverted; the frame-bound-fn
      ;; preserves :side.
      (is (= :rf/default (rf/current-frame-id)))
      (is (= :side       (captured))))))

;; ---- reg-view macro ---------------------------------------------------------

(deftest reg-view-registers
  (testing "reg-view (defn-shape macro) registers the view under the :view kind"
    ;; Per Spec 004 §reg-view (rf2-d0pi): the macro is defn-shape. It
    ;; auto-derives the id from (keyword *ns* sym); the ^{:rf/id ...}
    ;; metadata override pins an explicit keyword for assertion.
    (reg-view ^{:rf/id :greet} greet [n] [:p "hi " n])
    (is (some? (rf/view :greet))
        "the view is registered under the :view kind")
    (is (fn? greet)
        "the macro defs the supplied symbol to a callable render fn")))

(deftest reg-view-macro-defs-the-symbol
  ;; Per Spec 004 §reg-view (rf2-d0pi), the macro is defn-shape and defs
  ;; the supplied symbol to the registered render fn — there is no
  ;; outer-def pattern any more (the legacy
  ;; `(def name (reg-view :id meta render-fn))` shape is gone with the
  ;; non-defn-shape body restriction). This test pins the new contract:
  ;; the auto-defined Var is itself usable as a hiccup head.
  (testing "the macro defs the symbol to a callable render fn"
    (reg-view ^{:rf/id :ns/widget} my-widget [n] [:span "w-" n])
    ;; The registered view is in the registry.
    (is (some? (rf/view :ns/widget))
        ":ns/widget is in the :view registry")
    ;; The defn-shape sym is bound to a fn — usable as a hiccup head.
    (is (fn? my-widget)
        "the macro defined the supplied symbol as a fn")))

;; ---- (rf/view id) — runtime-lookup handle (rf2-yl9n) ----------------------
;; Per Spec 001 §(re-frame.core/view id) and Spec 004 §Calling a registered
;; view: render trees use Vars; runtime lookups use ids. (rf/view id) is the
;; id-keyed lookup handle that returns the registered render fn (whatever
;; shape) or nil if not registered.

(deftest view-returns-registered-fn
  (testing "(rf/view :id) returns the registered render fn after reg-view"
    (reg-view ^{:rf/id :my.ns/my-view} my-view [] [:p "body"])
    (let [f (rf/view :my.ns/my-view)]
      (is (fn? f)
          "(rf/view id) returns a fn for a registered view")
      ;; Per Spec 006 §Source-coord annotation (rf2-z7f7), the wrapper
      ;; splices :data-rf2-source-coord into the root attrs map under
      ;; interop/debug-enabled?. The view's body content remains
      ;; structurally the registered hiccup (root tag, children).
      (let [out (f)]
        (is (= :p (first out)) "root tag preserved by the wrapper")
        (is (= ["body"] (drop 2 out))
            "children preserved by the wrapper"))))
  (testing "(rf/view :nope) is nil for an unregistered id (no error)"
    (is (nil? (rf/view :nope/not-registered)))))

;; ---- keyword-head render tree is HTML, not a view dispatch (rf2-yl9n) ----
;; Per Spec 004 §Calling a registered view: keyword vectors at render time
;; are HTML elements (Reagent's existing semantics) — the runtime does NOT
;; intercept :keyword vectors and dispatch via the views registry. This is
;; the negative-regression test: even if a view is registered under :foo,
;; bare [:foo args] in a render tree must NOT resolve to that view.

(deftest keyword-head-does-not-dispatch-to-registered-view
  (testing "[:my-view args] in a render tree is NOT intercepted by the views registry"
    ;; Register a view with the same keyword id we are about to put into a
    ;; bare hiccup head. If the runtime were intercepting :keyword heads,
    ;; the render-tree-hash of [:foo/intercept-me 1] would somehow reflect
    ;; the registered view's body. It does not: the keyword head is treated
    ;; as a custom HTML element tag, no registry consultation happens.
    (reg-view ^{:rf/id :foo/intercept-me} intercept-me-view [n]
              [:span "view-body-" n])
    (let [registered-body  ((rf/view :foo/intercept-me) 1)
          ;; Hash the bare keyword form vs a structurally different
          ;; keyword form. If the runtime were intercepting, both would
          ;; render through the same registered fn and produce identical
          ;; structure (the registered body); they wouldn't differ.
          h-keyword-form   (ssr/render-tree-hash [:foo/intercept-me 1])
          h-other-form     (ssr/render-tree-hash [:foo/some-other-tag 1])
          h-via-var        (ssr/render-tree-hash registered-body)]
      ;; Keyword-form hashes differ — they are HTML element tags,
      ;; structurally distinct based on the tag keyword.
      (is (not= h-keyword-form h-other-form)
          "[:foo/intercept-me 1] and [:foo/some-other-tag 1] hash differently — the keyword IS the tag")
      ;; And the bare keyword form does not collapse to the registered
      ;; view's body — proving no interception happens.
      (is (not= h-keyword-form h-via-var)
          "[:foo/intercept-me 1] does not render as the registered view's body — no registry interception")
      ;; Sanity — explicit Var-reference DOES produce the registered body
      ;; (the wrapper splices :data-rf2-source-coord into the root attrs
      ;; per Spec 006 §Source-coord annotation, but the structural shape
      ;; — root tag, children — matches the registered render fn).
      (let [out (intercept-me-view 1)]
        (is (= :span (first out)) "Var-reference resolves to the registered root tag")
        (is (= ["view-body-" 1] (drop 2 out))
            "Var-reference resolves to the registered children")))))


;; ---- frame isolation ------------------------------------------------------

(deftest multi-frame-state-isolation
  (testing "two frames carry independent app-db state, share handler registry"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (rf/reg-event :counter/init (fn [{:keys [db]} [_ n]] {:db {:count n}}))
    (rf/reg-event :counter/inc  (fn [{:keys [db]} _] {:db (update db :count inc)}))
    (rf/reg-sub :count (fn [db _] (:count db)))
    (rf/dispatch-sync [:counter/init 10] {:frame :left})
    (rf/dispatch-sync [:counter/init 100] {:frame :right})
    (rf/dispatch-sync [:counter/inc] {:frame :left})
    (rf/dispatch-sync [:counter/inc] {:frame :left})
    (is (= 12  (rf/subscribe-once :left  [:count])))
    (is (= 100 (rf/subscribe-once :right [:count])))
    (is (nil?  (rf/subscribe-once :rf/default [:count])))))

;; ---- reactivity -----------------------------------------------------------
;; Reagent reactions auto-update on input change. Plain-atom can't show this
;; (every deref recomputes); the Reagent adapter materialises real reactivity.

(deftest reactive-sub-tracks-changes
  (testing "a Reagent reaction's deref reflects post-event state"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r (rf/subscribe [:n])]
      (is (= 0 @r))
      (rf/dispatch-sync [:inc])
      (is (= 1 @r) "the reaction observes the new value after :inc")
      (rf/dispatch-sync [:inc])
      (rf/dispatch-sync [:inc])
      (is (= 3 @r))
      (rf/unsubscribe [:n]))))

;; ---- hot-reload sub invalidation ------------------------------------------
;;
;; The registrar replacement-hook should evict cached reactions for
;; re-registered subs on every adapter, including Reagent's.

(deftest sub-hot-reload-cljs
  (testing "re-registering a sub flips the next subscribe-once to the new body"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :answer (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (is (= 7 (rf/subscribe-once [:answer])))
    ;; Force-pin so the cache slot survives the subscribe-once
    ;; auto-unsubscribe.
    (let [_pin (rf/subscribe [:answer])]
      (rf/reg-sub :answer (fn [db _] (* 10 (:n db))))
      (is (= 70 (rf/subscribe-once [:answer]))
          "the new sub body is in effect after re-registration")
      (rf/unsubscribe [:answer]))))

;; ---- flows ----------------------------------------------------------------

(deftest flow-recomputes
  (testing "a flow recomputes when its inputs change"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:w 0 :h 0}}))
    (rf/reg-event :w!   (fn [{:keys [db]} [_ w]] {:db (assoc db :w w)}))
    (rf/reg-event :h!   (fn [{:keys [db]} [_ h]] {:db (assoc db :h h)}))
    (rf/reg-flow {:id     :rect/area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* w h))
                  :output-path   [:area]})
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:w! 3])
    (rf/dispatch-sync [:h! 4])
    (is (= 12 (:area (rf/app-db-value :rf/default))))))

;; ---- routing --------------------------------------------------------------

(deftest match-and-unparse-routes
  (testing "match-url and route-url round-trip on CLJS"
    (rf/reg-route :user/show {} "/users/:id")
    (let [m (routing/match-url "/users/42")]
      (is (= :user/show (:route-id m)))
      (is (= "42"       (:id (:params m)))))
    (is (= "/users/42" (routing/route-url :user/show {:id 42})))))

;; ---- machines (pure machine-transition) -----------------------------------

(deftest machine-transition-cljs
  (testing "pure machine-transition runs on CLJS"
    (let [m {:initial :red
             :data    {}
             :states
             {:red    {:on {:tick {:target :green}}}
              :green  {:on {:tick {:target :yellow}}}
              :yellow {:on {:tick {:target :red}}}}}
          {s :re-frame.machines.result/snap} (machines/machine-transition m {:state :red :data {}} [:tick])]
      (is (= :green (:state s))))))

;; ---- error paths ----------------------------------------------------------

(deftest sub-exception-recovers-to-nil
  (testing "a sub whose body throws emits :rf.error/sub-exception and resolves to nil"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:items "broken"}}))
    (rf/reg-sub :items (fn [db _] (:items db)))
    (rf/reg-sub :items-count :<- [:items]
      (fn [items _]
        ;; Throws on a string.
        (count (.something items))))
    (rf/dispatch-sync [:init])
    (let [traces (atom [])]
      (trace-tooling/register-listener! ::sub-err (fn [ev] (swap! traces conj ev)))
      (let [v (rf/subscribe-once [:items-count])]
        (is (nil? v)
            "the sub returns nil under :replaced-with-default recovery"))
      (trace-tooling/unregister-listener! ::sub-err)
      (is (some (fn [ev]
                  (= :rf.error/sub-exception (:operation ev)))
                @traces)
          "expected :rf.error/sub-exception trace"))))

;; ---- render-tree-hash ----------------------------------------------------
;; Per Spec 011 §Hydration-mismatch detection: the hash must be stable
;; across JVM and CLJS. The JVM smoke test asserts JVM stability; this
;; test asserts CLJS stability. The matching JVM/CLJS hex strings are
;; verifiable manually (or via a future cross-runtime test harness).

(deftest render-tree-hash-cljs
  (testing "render-tree-hash returns 8-char lowercase hex deterministically"
    (let [r2h   (ssr/render-tree-hash [:div {:class "x"} [:p "hi"]])
          r2h-2 (ssr/render-tree-hash [:div {:class "x"} [:p "hi"]])
          r2h-3 (ssr/render-tree-hash [:div {:class "y"} [:p "hi"]])]
      (is (= r2h r2h-2))
      (is (not= r2h r2h-3))
      (is (re-matches #"[0-9a-f]{8}" r2h)))))

(deftest ssr-end-to-end-cljs
  (testing "complete SSR flow runs against the Reagent adapter on CLJS"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:items ["a" "b" "c"]}}))
    (rf/reg-sub :items (fn [db _] (:items db)))
    (rf/reg-view ^{:rf/id :pages/list} pages-list []
      [:ul
       (for [it (rf/subscribe-once [:items])]
         ^{:key it} [:li it])])
    (rf/dispatch-sync [:seed])
    (let [html (rf/render-to-string [:pages/list] {:emit-hash? true})]
      (is (re-find #"<ul[^>]*data-rf-render-hash=\"[0-9a-f]{8}\"" html)
          "rendered HTML carries a stable hash on the root <ul>")
      (is (clojure.string/includes? html "<li>a</li>"))
      (is (clojure.string/includes? html "<li>b</li>"))
      (is (clojure.string/includes? html "<li>c</li>")))))

;; ---- subscription topology: glitch-freedom -------------------------------
;;
;; A glitch is a transient inconsistent state visible to a downstream sub
;; during propagation — e.g., a layer-2 sub reading two layer-1 inputs
;; momentarily seeing "new A + old B" before the second input updates.
;; Per Spec 006 §Recompute on container replacement: layer-1 settles
;; before layer-2 fires; the cascade respects the static :<- topology.
;; In the CLJS reference this is delegated to Reagent's reaction queue,
;; which schedules dependents in topological order. These tests pin
;; that property empirically against the diamond and chain shapes.

(deftest sub-topology-glitch-free-diamond
  (testing "diamond: app-db -> {a,b} -> c — c never sees a half-propagated state"
    (rf/reg-event :diamond/init (fn [{:keys [db]} _] {:db {:x 1 :y 2}}))
    (rf/reg-event :diamond/swap (fn [{{:keys [x y] :as db} :db} _]
                                  {:db (assoc db :x y :y x)}))
    (rf/reg-sub :diamond/a (fn [db _] (:x db)))
    (rf/reg-sub :diamond/b (fn [db _] (:y db)))
    (rf/reg-sub :diamond/c
      :<- [:diamond/a]
      :<- [:diamond/b]
      (fn [[a b] _] {:a a :b b}))
    (rf/dispatch-sync [:diamond/init])
    (let [c-reaction (rf/subscribe [:diamond/c])
          history    (atom [])]
      ;; Install the watch BEFORE the first deref so Reagent installs the
      ;; dep-tracking watches on :diamond/a and :diamond/b reactions.
      (add-watch c-reaction ::observer
                 (fn [_ _ _ new-v] (swap! history conj new-v)))
      (swap! history conj @c-reaction)              ;; capture initial
      ;; Mutate both :x and :y in a single reset!.
      (rf/dispatch-sync [:diamond/swap])
      (r/flush)
      @c-reaction                                   ;; force lazy recompute
      (remove-watch c-reaction ::observer)
      (rf/unsubscribe [:diamond/c])
      (let [seen     (distinct @history)
            valid    #{{:a 1 :b 2} {:a 2 :b 1}}
            invalid  (remove valid seen)]
        (is (some #{{:a 1 :b 2}} seen)  "saw the initial state")
        (is (some #{{:a 2 :b 1}} seen)  "saw the post-swap state")
        (is (empty? invalid)
            (str "saw glitched intermediate state(s): " (pr-str invalid)))))))

(deftest sub-topology-glitch-free-chain
  (testing "chain: app-db -> a -> b -> c — each transition produces one final post-update value, no intermediates"
    (rf/reg-event :chain/init (fn [{:keys [db]} _] {:db {:n 10}}))
    (rf/reg-event :chain/set  (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
    (rf/reg-sub :chain/a (fn [db _] (:n db)))
    (rf/reg-sub :chain/b :<- [:chain/a] (fn [a _] (* a 2)))
    (rf/reg-sub :chain/c :<- [:chain/b] (fn [b _] (inc b)))
    (rf/dispatch-sync [:chain/init])
    (let [c-reaction (rf/subscribe [:chain/c])
          history    (atom [])]
      (add-watch c-reaction ::observer
                 (fn [_ _ _ new-v] (swap! history conj new-v)))
      (swap! history conj @c-reaction)              ;; initial: 21
      (rf/dispatch-sync [:chain/set 100])           ;; n:10→100, b:20→200, c:21→201
      (r/flush)
      @c-reaction
      (remove-watch c-reaction ::observer)
      (rf/unsubscribe [:chain/c])
      (let [seen (distinct @history)]
        (is (some #{21}  seen) "saw the initial value 21")
        (is (some #{201} seen) "saw the post-update value 201")
        (is (empty? (remove #{21 201} seen))
            (str "saw glitched intermediate value(s): "
                 (pr-str (remove #{21 201} seen))))))))

(deftest sub-correctness-on-value-equal-input
  (testing "[Spec 006 §No-op via value equality, rf2-719e] a value-equal app-db replacement does NOT re-run the body fn of a layer-2 sub whose resolved input is value-equal — the wrapper short-circuits to the cached return value"
    (let [a-runs       (atom 0)
          squared-runs (atom 0)]
      (rf/reg-event :stable/init (fn [{:keys [db]} _] {:db {:n 5 :unrelated "z"}}))
      (rf/reg-event :stable/touch-unrelated
                       (fn [{:keys [db]} _] {:db (assoc db :unrelated "z")}))   ;; same value
      (rf/reg-event :stable/bump-n
                       (fn [{:keys [db]} _] {:db (update db :n inc)}))          ;; real change
      (rf/reg-sub :stable/a
                  (fn [db _] (swap! a-runs inc) (:n db)))
      (rf/reg-sub :stable/squared
                  :<- [:stable/a]
                  (fn [a _] (swap! squared-runs inc) (* a a)))
      (rf/dispatch-sync [:stable/init])
      (let [r (rf/subscribe [:stable/squared])]
        (add-watch r ::touch (fn [_ _ _ _] nil))
        (is (= 25 @r) "initial value correct")
        (let [a-baseline       @a-runs
              squared-baseline @squared-runs]
          ;; Value-equal app-db replacement: neither body should re-run.
          (rf/dispatch-sync [:stable/touch-unrelated])
          (r/flush)
          @r
          (is (= 25 @r) "value still correct after a value-equal app-db replacement")
          (is (= a-baseline @a-runs)
              "layer-1 body should NOT re-run when its input is =-equal")
          (is (= squared-baseline @squared-runs)
              "layer-2 body should NOT re-run when its input is =-equal")
          ;; Real change: each body should run exactly once more.
          (rf/dispatch-sync [:stable/bump-n])
          (r/flush)
          (is (= 36 @r) "value updated after a real change")
          (is (= (inc a-baseline) @a-runs)
              "layer-1 body runs exactly once on a real input change")
          (is (= (inc squared-baseline) @squared-runs)
              "layer-2 body runs exactly once on a real input change")
          ;; A second value-equal touch must again be a no-op.
          (rf/dispatch-sync [:stable/touch-unrelated])
          (r/flush)
          @r
          (is (= 36 @r) "value still correct after a second value-equal replacement")
          (is (= (inc a-baseline) @a-runs)
              "layer-1 body still suppressed on the second value-equal replacement")
          (is (= (inc squared-baseline) @squared-runs)
              "layer-2 body still suppressed on the second value-equal replacement"))
        (remove-watch r ::touch)
        (rf/unsubscribe [:stable/squared])))))

;; ---- two-partition projection-equality invalidation (EP-0001 decision #7,
;; rf2-0sr0ai) -----------------------------------------------------------------
;;
;; Spec 006 §Frame-state container and partition projections: app-db and
;; runtime-db are PROJECTION REACTIONS over the ONE physical frame-state
;; container. A runtime-only commit recomputes the app-db projection, finds
;; it `=`, and MUST NOT propagate to app-db subs (and the converse for an
;; app-only commit). The plain-atom (JVM) pin lives in
;; `re-frame.partitioned-commit-test`; on plain-atom the short-circuit rides
;; on the core sub-cache memoised body (make-derived-value recomputes on
;; every deref). Under the Reagent reactive substrate the projections are
;; REAL Reagent Reactions and the layer-1 sub body is a Reaction over the
;; projection — the SAME contract, a DIFFERENT propagation path. These pin
;; it under Reagent. UIx / Helix carry the parameterised twin in
;; `re-frame.adapter.react-shared-suite`.
;;
;; A framework-authority handler (the `:rf/machine? true` marker the dev
;; runtime-write diagnostic keys on) emits `:rf.db/runtime` without firing
;; the diagnostic — mirrors `reg-fw-runtime-handler!` in the plain-atom
;; suite.

(defn- reg-fw-runtime-handler!
  [id f]
  (rf/reg-event id {:doc "framework-authority" :rf/machine? true} f))

(deftest runtime-only-commit-does-not-rerun-app-subs-cljs
  (testing "[EP-0001 #7, rf2-0sr0ai] a runtime-only commit leaves the app-db
  projection `=` and does NOT re-run an app-db layer-1 sub body under the
  Reagent reactive substrate"
    (let [runs (atom 0)]
      (rf/reg-event :inval/seed-app (fn [{:keys [db]} _] {:db {:n 1}}))
      (rf/dispatch-sync [:inval/seed-app])
      (rf/reg-sub :inval/app-sub (fn [db _] (swap! runs inc) (:n db)))
      (let [r (rf/subscribe [:inval/app-sub])]
        ;; Pin the reaction live with a watch so its memoised value persists
        ;; across the commit (matches sub-correctness-on-value-equal-input).
        (add-watch r ::touch (fn [_ _ _ _] nil))
        (is (= 1 @r) "precondition: app sub primes to the seeded value")
        (let [after-prime @runs]
          (reg-fw-runtime-handler! :inval/touch-rt
            (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:m 1}}}))
          (rf/dispatch-sync [:inval/touch-rt])
          (r/flush)
          @r
          (is (= 1 @r) "the app sub still derefs to the unchanged value")
          (is (= after-prime @runs)
              "the app-db layer-1 sub body did NOT re-run — app-db projection stayed `=` on a runtime-only commit")
          (is (= {:rf.runtime/machines {:m 1}} (rf/runtime-db-value :rf/default))
              "the runtime-only commit DID land in runtime-db (real short-circuit, not a dropped write)"))
        (remove-watch r ::touch)
        (rf/unsubscribe [:inval/app-sub])))))

(deftest app-only-commit-does-not-rerun-runtime-subs-cljs
  (testing "[EP-0001 #7, rf2-0sr0ai] an app-only commit leaves the runtime-db
  projection `=` and does NOT re-run a runtime-db (reg-runtime-sub) sub body
  under the Reagent reactive substrate"
    (let [runs (atom 0)]
      (reg-fw-runtime-handler! :inval/seed-rt
        (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :home}}}}))
      (rf/dispatch-sync [:inval/seed-rt])
      (subs/reg-runtime-sub :inval/rt-sub
        (fn [runtime-db _] (swap! runs inc) (get-in runtime-db [:rf.runtime/routing :current :route-id])))
      (let [r (rf/subscribe [:inval/rt-sub])]
        (add-watch r ::touch (fn [_ _ _ _] nil))
        (is (= :home @r) "precondition: runtime-db sub primes to the seeded route id")
        (let [after-prime @runs]
          (rf/reg-event :inval/app-write (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
          (rf/dispatch-sync [:inval/app-write])
          (r/flush)
          @r
          (is (= :home @r) "the runtime-db sub still derefs to the unchanged route id")
          (is (= after-prime @runs)
              "the runtime-db sub body did NOT re-run — runtime-db projection stayed `=` on an app-only commit")
          (is (true? (:touched? (rf/app-db-value :rf/default)))
              "the app-only commit DID land in app-db (real short-circuit, not a dropped write)"))
        (remove-watch r ::touch)
        (rf/unsubscribe [:inval/rt-sub])))))

(deftest real-partition-change-propagates-to-its-subs-cljs
  (testing "[EP-0001 #7, rf2-0sr0ai] the converse: a real change to a
  partition DOES re-run that partition's subs (exactly once) and only that
  partition's — under the Reagent reactive substrate"
    (let [app-runs (atom 0)
          rt-runs  (atom 0)]
      (rf/reg-event :inval/seed-app2 (fn [{:keys [db]} _] {:db {:n 1}}))
      (rf/dispatch-sync [:inval/seed-app2])
      (reg-fw-runtime-handler! :inval/seed-rt2
        (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:m 1}}}))
      (rf/dispatch-sync [:inval/seed-rt2])
      (rf/reg-sub :inval/app-sub2 (fn [db _] (swap! app-runs inc) (:n db)))
      (subs/reg-runtime-sub :inval/rt-sub2
        (fn [runtime-db _] (swap! rt-runs inc) (get-in runtime-db [:rf.runtime/machines :m])))
      (let [ra (rf/subscribe [:inval/app-sub2])
            rr (rf/subscribe [:inval/rt-sub2])]
        (add-watch ra ::touch-a (fn [_ _ _ _] nil))
        (add-watch rr ::touch-r (fn [_ _ _ _] nil))
        (is (= 1 @ra) "precondition: app sub primed")
        (is (= 1 @rr) "precondition: runtime-db sub primed")
        (let [app-baseline @app-runs
              rt-baseline  @rt-runs]
          ;; Real app-db change.
          (rf/reg-event :inval/app-write2 (fn [{:keys [db]} _] {:db (update db :n inc)}))
          (rf/dispatch-sync [:inval/app-write2])
          (r/flush)
          (is (= 2 @ra) "the app sub re-derived to the new app-db value")
          (is (= (inc app-baseline) @app-runs)
              "the app sub body re-ran exactly once on a real app-db change")
          (is (= rt-baseline @rt-runs)
              "the runtime-db sub body did NOT re-run on a real app-db change")
          ;; Real runtime-db change.
          (reg-fw-runtime-handler! :inval/rt-write2
            (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:m 2}}}))
          (rf/dispatch-sync [:inval/rt-write2])
          (r/flush)
          (is (= 2 @rr) "the runtime-db sub re-derived to the new runtime-db value")
          (is (= (inc rt-baseline) @rt-runs)
              "the runtime-db sub body re-ran exactly once on a real runtime-db change")
          (is (= (inc app-baseline) @app-runs)
              "the app sub body did NOT re-run on a real runtime-db change"))
        (remove-watch ra ::touch-a)
        (remove-watch rr ::touch-r)
        (rf/unsubscribe [:inval/app-sub2])
        (rf/unsubscribe [:inval/rt-sub2])))))

(deftest dispatch-sync-in-handler-errors-cljs
  (testing "calling dispatch-sync from inside a handler raises a structured error"
    (let [traces (atom [])]
      (trace-tooling/register-listener! ::dsih (fn [ev] (swap! traces conj ev)))
      (rf/reg-event :outer (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
      (rf/reg-event :nested
        (fn [_ _]
          (rf/dispatch-sync [:outer])
          {}))
      (rf/dispatch-sync [:nested])
      (trace-tooling/unregister-listener! ::dsih)
      (is (some (fn [ev]
                  (and (= :rf.error/dispatch-sync-in-handler (:operation ev))
                       (= :error (:op-type ev))))
                @traces)
          "expected :rf.error/dispatch-sync-in-handler trace"))))

;; ---- sub-cache (rf2-vvsh) -------------------------------------------------

(deftest sub-cache-projects-tool-pair-shape
  (testing "(re-frame.subs.tooling/sub-cache-snapshot frame-id) returns
           {query-v {:value v :ref-count n :input-kind k :realized-inputs [...]}}
           for every materialised subscription in the named frame
           (rf2-80mmlf demoted the `rf/sub-cache` facade alias — the
           tooling-surface home is `re-frame.subs.tooling`)"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7 :name "ada"}}))
    (rf/reg-sub :n     (fn [db _] (:n db)))
    (rf/reg-sub :name* (fn [db _] (:name db)))
    (rf/dispatch-sync [:seed])
    ;; Empty cache is the {} baseline.
    (is (= {} (subs-tooling/sub-cache-snapshot :rf/default))
        "no subs materialised yet → empty map")
    (let [r1 (rf/subscribe [:n])
          r2 (rf/subscribe [:n])
          r3 (rf/subscribe [:name*])
          snapshot (subs-tooling/sub-cache-snapshot :rf/default)]
      (is (= 2 (count snapshot))
          "snapshot contains one entry per materialised query-v")
      (is (= 7     (get-in snapshot [[:n]     :value])))
      (is (= "ada" (get-in snapshot [[:name*] :value])))
      (is (= 2     (get-in snapshot [[:n]     :ref-count]))
          "ref-count reflects two outstanding subscribes for [:n]")
      (is (= 1     (get-in snapshot [[:name*] :ref-count])))
      ;; rf2-e3acps — layer-1 readers carry :input-kind :db / no realized edges.
      (is (= :db (get-in snapshot [[:n]     :input-kind]))
          "layer-1 reader surfaces :input-kind :db")
      (is (= []  (get-in snapshot [[:n]     :realized-inputs]))
          "layer-1 reader has no realized input edges")
      ;; Default no-arg form uses the active frame.
      (is (= snapshot (subs-tooling/sub-cache-snapshot (rf/current-frame-id)))
          "no-arg form returns the active frame's snapshot")
      (rf/unsubscribe [:n])
      (rf/unsubscribe [:n])
      (rf/unsubscribe [:name*]))
    ;; Missing frame yields nil rather than throwing.
    (is (nil? (subs-tooling/sub-cache-snapshot :no-such-frame))
        "missing frame returns nil")))

(deftest sub-cache-surfaces-static-realized-inputs
  (testing "a :static :<- sub surfaces :input-kind :static + the literal realized edges"
    (rf/reg-event :seed2 (fn [{:keys [db]} _] {:db {:items [1 2 3] :filter :all}}))
    (rf/reg-sub :items  (fn [db _] (:items db)))
    (rf/reg-sub :filter (fn [db _] (:filter db)))
    (rf/reg-sub :visible-items
                :<- [:items]
                :<- [:filter]
                (fn [[items _filter] _] items))
    (rf/dispatch-sync [:seed2])
    (let [r (rf/subscribe [:visible-items])
          snapshot (subs-tooling/sub-cache-snapshot :rf/default)
          entry (get snapshot [:visible-items])]
      (is (= :static (:input-kind entry))
          "a :<- sub is :input-kind :static")
      (is (= [[:items] [:filter]] (:realized-inputs entry))
          "static realized inputs are the literal :<- query-vectors in order")
      (rf/unsubscribe [:visible-items]))))

(deftest sub-cache-surfaces-parametric-realized-inputs
  (testing "a :parametric input-fn sub surfaces :input-kind :parametric +
           the REALIZED input query-vectors for the concrete outer query-v"
    (rf/reg-event :seed3 (fn [{:keys [db]} _] {:db {:articles {:a1 {:title "T"}}
                                       :comments {:a1 [:c1]}
                                       :viewer   {:edit? true}}}))
    (rf/reg-sub :article/by-id        (fn [db [_ id]] (get-in db [:articles id])))
    (rf/reg-sub :comments/for-article (fn [db [_ id]] (get-in db [:comments id])))
    (rf/reg-sub :viewer/current       (fn [db _] (:viewer db)))
    (rf/reg-sub :article/page
                (fn [[_ id]]
                  [[:article/by-id id]
                   [:comments/for-article id]
                   [:viewer/current]])
                (fn [[article comments viewer] [_ id]]
                  {:article article :comments comments :viewer viewer}))
    (rf/dispatch-sync [:seed3])
    (let [r (rf/subscribe [:article/page :a1])
          snapshot (subs-tooling/sub-cache-snapshot :rf/default)
          entry (get snapshot [:article/page :a1])]
      (is (= :parametric (:input-kind entry))
          "the input-fn sub is :input-kind :parametric")
      (is (= [[:article/by-id :a1]
              [:comments/for-article :a1]
              [:viewer/current]]
             (:realized-inputs entry))
          "realized-inputs are the (input-fn query-v) result — concrete edges for :a1")
      ;; The static topology must NOT enumerate these — only the live cache does.
      ;; CLJS calls the tooling ns directly (rf/sub-topology is a JVM-only alias).
      (is (= :parametric (:inputs ((subs-tooling/sub-topology) :article/page)))
          "static sub-topology reports the :parametric sentinel, not the realized edges")
      (rf/unsubscribe [:article/page :a1]))))

;; ---- live sub-cache algebra view (EP-0014 slice-2, rf2-gge6mf) -------------
;;
;; Per [spec/Derivations.md] §Static and live graphs + the
;; `:rf/derivation-node` shape in [spec/Spec-Schemas.md]. The LIVE
;; counterpart to `sub-algebra-view` (the static, JVM-runnable view): one
;; algebra node per concrete cached entry, keyed by its query vector, with
;; the REALIZED `[:sub query-vector]` input edges the static graph cannot
;; enumerate. CLJS-only / dev-only (DCE'd in production), mirroring
;; `sub-cache-snapshot`.

(deftest sub-cache-algebra-view-exposes-the-live-node
  (testing "a live sub-cache entry exposes the full ephemeral / on-demand /
           cache-entry algebra node, keyed by its concrete query vector"
    (rf/reg-event :seed-av (fn [{:keys [db]} _] {:db {:n 7 :name "ada"}}))
    (rf/reg-sub :n     (fn [db _] (:n db)))
    (rf/reg-sub :name* (fn [db _] (:name db)))
    (rf/dispatch-sync [:seed-av])
    ;; Empty cache is the {} baseline.
    (is (= {} (subs-tooling/sub-cache-algebra-view :rf/default))
        "no subs materialised yet → empty map")
    (let [r1   (rf/subscribe [:n])
          r2   (rf/subscribe [:n])
          r3   (rf/subscribe [:name*])
          view (subs-tooling/sub-cache-algebra-view :rf/default)]
      (is (= 2 (count view))
          "one algebra node per materialised query-v")
      (let [node-n (get view [:n])]
        (is (= [:n] (:id node-n))
            "the live node's id is its concrete query vector")
        (is (= :derivation (:kind node-n)))
        (is (= :ephemeral (:storage node-n)))
        (is (= :on-demand (:evaluation node-n)))
        (is (= :subscription-cache-entry (:lifecycle node-n)))
        (is (= false (:materialized? node-n)))
        (is (= [:fact [:n]] (:output node-n))
            "the output fact is addressed by the concrete query vector")
        (is (= {:kind :reg-sub :id :n} (:source-form node-n)))
        (is (= [] (:inputs node-n))
            "a layer-1 reader has no realized [:sub …] edges")
        (is (= :db (:input-kind node-n)))
        (is (= 7 (:value node-n))
            "the node carries the live deref of the cached reaction")
        (is (= 2 (:ref-count node-n))
            "ref-count is the lifecycle evidence the cache-entry owner is kept alive")))
    ;; No-arg-by-id parity with the active frame.
    (is (= (subs-tooling/sub-cache-algebra-view :rf/default)
           (subs-tooling/sub-cache-algebra-view (rf/current-frame-id)))
        "the named-frame view equals the active-frame view")
    (rf/unsubscribe [:n])
    (rf/unsubscribe [:n])
    (rf/unsubscribe [:name*])
    (is (nil? (subs-tooling/sub-cache-algebra-view :no-such-frame))
        "missing frame returns nil")))

(deftest sub-cache-algebra-view-lowers-static-realized-edges
  (testing "a static :<- entry lowers its literal realized inputs to [:sub q] edges"
    (rf/reg-event :seed-av2 (fn [{:keys [db]} _] {:db {:items [1 2 3] :filter :all}}))
    (rf/reg-sub :items  (fn [db _] (:items db)))
    (rf/reg-sub :filter (fn [db _] (:filter db)))
    (rf/reg-sub :visible-items
                :<- [:items]
                :<- [:filter]
                (fn [[items _filter] _] items))
    (rf/dispatch-sync [:seed-av2])
    (let [r    (rf/subscribe [:visible-items])
          view (subs-tooling/sub-cache-algebra-view :rf/default)
          node (get view [:visible-items])]
      (is (= :static (:input-kind node)))
      (is (= [[:sub [:items]] [:sub [:filter]]] (:inputs node))
          "the static entry's realized edges are its literal :<- query-vectors, lowered to [:sub q]")
      (rf/unsubscribe [:visible-items]))))

(deftest sub-cache-algebra-view-lowers-parametric-realized-edges
  (testing "a parametric entry lowers its REALIZED (input-fn query-v) result to [:sub q] edges"
    ;; This is the load-bearing static/live distinction: the static
    ;; `sub-algebra-view` reports the :parametric marker; the live view
    ;; reports the concrete realized [:sub …] edges for THIS query vector.
    (rf/reg-event :seed-av3 (fn [{:keys [db]} _] {:db {:articles {:a1 {:title "T"}}
                                          :comments {:a1 [:c1]}}}))
    (rf/reg-sub :article/by-id        (fn [db [_ id]] (get-in db [:articles id])))
    (rf/reg-sub :comments/for-article (fn [db [_ id]] (get-in db [:comments id])))
    (rf/reg-sub :article/page
                (fn [[_ id]]
                  [[:article/by-id id]
                   [:comments/for-article id]])
                (fn [[article comments] _]
                  {:article article :comments comments}))
    (rf/dispatch-sync [:seed-av3])
    (let [r    (rf/subscribe [:article/page :a1])
          view (subs-tooling/sub-cache-algebra-view :rf/default)
          node (get view [:article/page :a1])]
      (is (= [:article/page :a1] (:id node))
          "the live node id is the concrete parametric query vector")
      (is (= :parametric (:input-kind node)))
      (is (= [[:sub [:article/by-id :a1]]
              [:sub [:comments/for-article :a1]]]
             (:inputs node))
          "the live view reports the REALIZED [:sub …] edges for :a1")
      ;; The static view reports the marker, never these realized edges.
      (is (= :parametric (:inputs (subs-tooling/sub-algebra-view :article/page)))
          "the static algebra view reports the :parametric marker, not the realized edges")
      (rf/unsubscribe [:article/page :a1]))))

;; ---- epoch history (Tool-Pair §Time-travel, rf2-shjf) ---------------------
;;
;; CLJS smoke test — JVM-side epoch_test.clj covers the broad surface; this
;; verifies the same machinery loads + records under the Reagent substrate.

(deftest epoch-history-cljs
  (testing "drain-settle commits a record; register-epoch-listener! fires per-cascade"
    (rf/reg-frame :epoch/cljs {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (let [seen (atom [])]
      (rf/register-epoch-listener! ::w (fn [r] (swap! seen conj r)))
      (rf/dispatch-sync [:seed] {:frame :epoch/cljs})
      (rf/dispatch-sync [:inc]  {:frame :epoch/cljs})
      (rf/unregister-epoch-listener! ::w)

      (let [history (rf/epoch-history :epoch/cljs)]
        (is (= 2 (count history)) "two cascades, two records")
        (is (= [:seed :inc] (mapv :event-id history)))
        (is (= {:n 1} (:db-after (last history))))
        (is (= 2 (count @seen)) "register-epoch-listener! fired per-cascade")))))

;; ---- frame-provider (rf2-sixo) -------------------------------------------
;;
;; rf/frame-provider is a Reagent component that scopes a frame keyword to
;; its subtree via React context. Per Spec 002 §What `frame-provider` is.
;;
;; These tests verify the component-shape contract — the hiccup the wrapper
;; emits and how it composes with build-frame-provider. The runtime
;; React-context resolution path (which is currently broken under
;; Reagent 1.2 + React 18 — tracked by rf2-kdwc) is exercised in the
;; browser-test target where a real DOM is available; node-test cannot
;; mount a component, so these tests stop at the hiccup-emission level.

(deftest frame-provider-emits-provider-hiccup
  (testing "[rf/frame-provider-existing {:frame :a} child] emits a Provider element with :a"
    (let [child       [:span "hi"]
          tree        (rf/frame-provider-existing {:frame :a} child)
          [head value & rest] tree]
      ;; The wrapper composes the Reagent component returned by
      ;; build-frame-provider as the head, with the frame keyword threaded
      ;; through as the first arg so the inner fn closes over it.
      (is (fn? head)
          "head is a fn (the Reagent component)")
      (is (= :a value)
          "the frame keyword is the first arg passed to the component")
      (is (= [child] rest)
          "children follow the frame keyword unchanged")
      ;; Invoke the head with the value + child to verify it produces the
      ;; React Provider element via `:r>`. The hiccup shape is
      ;; `[:r> ProviderClass #js {:value :a} child]` — `:r>` bypasses
      ;; Reagent's `convert-prop-value` so the keyword's namespace
      ;; survives the React-context round trip.
      (let [provider-tree (apply head value rest)
            [marker provider-class js-props & inner] provider-tree]
        (is (= :r> marker)
            "Reagent's raw-React.createElement marker — bypasses convert-prop-value")
        (is (some? provider-class)
            "the React Context Provider class is in head position")
        ;; The props are a raw JS object — read :value off it.
        (is (= :a (aget js-props "value"))
            "the Provider's :value carries the frame keyword unchanged")
        (is (= [child] inner)
            "children pass through unchanged")))))

(deftest frame-provider-missing-frame-key-raises-no-frame-context
  ;; EP-0002 (rf2-9o48ih) — inverted from the prior
  ;; `frame-provider-default-fallback-no-frame-key`. Under the carried
  ;; invariant there is no `(or (:frame props) :rf/default)` floor: a
  ;; `frame-provider` with no (or nil) `:frame` is a CONFIGURATION ERROR
  ;; — the provider raises `:rf.error/no-frame-context` rather than
  ;; synthesising a default. `:frame` is a required prop (per Spec 002
  ;; §Frame target resolution — the carried invariant).
  (testing "frame-provider-existing with no :frame key raises :rf.error/no-frame-context"
    (is (thrown-with-msg? :default #":rf.error/no-frame-context"
          (rf/frame-provider-existing {} [:span "x"]))
        "missing :frame is a config error — no :rf/default floor"))
  (testing "frame-provider with explicit nil :frame raises :rf.error/no-frame-context"
    (is (thrown-with-msg? :default #":rf.error/no-frame-context"
          (rf/frame-provider-existing {:frame nil} [:span "x"]))
        "nil :frame is a config error — no :rf/default floor")))

(deftest frame-provider-variadic-children
  (testing "frame-provider accepts zero, one, or many children"
    ;; Zero children — the wrapper still renders the Provider element with
    ;; no inner content. React-side that's a valid empty subtree.
    (let [tree (rf/frame-provider-existing {:frame :z})]
      (is (= :z (second tree))
          "frame keyword threaded through with no children")
      (is (= [] (drop 2 tree))
          "no extra children when none were passed"))
    ;; One child.
    (let [tree (rf/frame-provider-existing {:frame :one} [:p "alone"])]
      (is (= [[:p "alone"]] (drop 2 tree))
          "single child passes through"))
    ;; Many children — the variadic & captures them all in declaration order.
    (let [tree (rf/frame-provider-existing {:frame :many}
                                  [:header]
                                  [:main]
                                  [:footer])]
      (is (= [[:header] [:main] [:footer]] (drop 2 tree))
          "all children present in source order"))))

(deftest frame-provider-keyword-frame
  (testing "frame-provider only handles keyword frame ids (per Spec 002 §Frame ids)"
    ;; The component itself doesn't validate — that's the runtime's job —
    ;; but it threads whatever keyword the user supplies.
    (let [tree (rf/frame-provider-existing {:frame :rf.frame/anonymous-1} [:p])]
      (is (= :rf.frame/anonymous-1 (second tree))
          "namespaced gensym'd frame keyword threads through unchanged"))))

(deftest frame-provider-build-frame-provider-substrate
  (testing "build-frame-provider remains the lower-level substrate"
    ;; Per the bead: build-frame-provider stays in re-frame.views as
    ;; substrate. The user-facing API is rf/frame-provider; the substrate
    ;; hook into re-frame.adapter.reagent/register-context-provider is
    ;; build-frame-provider. Both are callable; both produce the same
    ;; final hiccup shape when invoked with a frame keyword.
    ;; rf2-4y60: build-frame-provider is 0-arity — the returned component
    ;; takes the frame keyword at render time.
    (let [provider     (re-frame.views/build-frame-provider)
          substrate-tree (provider :hello [:span "x"])
          wrapper-tree   (rf/frame-provider-existing {:frame :hello} [:span "x"])
          ;; The wrapper invokes (build-frame-provider) per call;
          ;; the substrate-side returns the same generic component. Compare
          ;; the inner Provider hiccup produced by each.
          unwrap         (fn [tree] (apply (first tree) (rest tree)))
          a              (unwrap wrapper-tree)
          b              substrate-tree]
      ;; Both produce `[:r> Provider #js {:value :hello} [:span "x"]]`.
      (is (= (first a) (first b)) "both emit the :r> raw-React.createElement marker")
      (is (= (aget (nth a 2) "value")
             (aget (nth b 2) "value"))
          "both emit the same :value on the raw JS props object")
      (is (= (drop 3 a) (drop 3 b)) "both emit the same children"))))

;; ---- per-frame sub-cache disposal (Spec 006, rf2-cmfln) -------------------
;;
;; Mirrors the synchronous-disposal portion of
;; implementation/test/re_frame/sub_cache_test.clj under the Reagent
;; adapter. Per rf2-cmfln sub-cache disposal is synchronous on
;; derefer-count → 0; no grace-period to configure.

(defn- cache-keys-of
  [frame-id]
  (set (keys @(:sub-cache (frame/frame frame-id)))))

(deftest sub-cache-sync-disposes-on-last-unsubscribe
  (testing "ref-count → 0 disposes the slot synchronously (rf2-cmfln)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])
    (rf/subscribe [:n])
    (is (contains? (cache-keys-of :rf/default) [:n]))
    (rf/unsubscribe [:n])
    (is (not (contains? (cache-keys-of :rf/default) [:n]))
        "slot evicted in-tick on the 1 → 0 transition")))

;; ---- restore-epoch! reactive surfaces (Tool-Pair §Time-travel, rf2-2fat) ---
;;
;; Per Tool-Pair §Time-travel + Spec 006 §Subscription cache:
;; restore-epoch! goes through adapter/replace-container! — the same
;; choke point used by the drain loop's :db commit. On the Reagent
;; substrate, that means a reaction held across a restore observes
;; the rewound value through Reagent's reactive graph, exactly as
;; it does for a normal event commit.
;;
;; The JVM-side epoch_test.clj covers subscribe-once / pinned-reaction
;; semantics under the plain-atom adapter (every deref recomputes,
;; so observable equivalence is straightforward). This test pins the
;; same contract under the Reagent reactive substrate where the
;; reaction itself caches and only re-runs when its source changes
;; by =.

(deftest restore-rewinds-reagent-reaction
  (testing "a Reagent-backed reaction held across restore-epoch! derefs
  to the restored value — proving the restore goes through the same
  reactive-graph notification path as a drain :db commit."
    (rf/reg-frame :restore/cljs {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed] {:frame :restore/cljs})  ;; n=0
    (rf/dispatch-sync [:inc]  {:frame :restore/cljs})  ;; n=1
    (rf/dispatch-sync [:inc]  {:frame :restore/cljs})  ;; n=2
    (rf/dispatch-sync [:inc]  {:frame :restore/cljs})  ;; n=3

    (let [r       (rf/subscribe :restore/cljs [:n])
          _       (is (= 3 @r) "the reaction sees current value before restore")
          history (rf/epoch-history :restore/cljs)
          target  (some (fn [rec] (when (= 1 (:n (:db-after rec))) rec))
                        history)]
      (is (true? (rf/restore-epoch! :restore/cljs (:epoch-id target))))
      (is (= 1 @r)
          "the same reaction handle observes the rewound value after restore")
      (rf/unsubscribe :restore/cljs [:n]))))

(deftest restore-reagent-frame-isolation
  (testing "restoring frame A does not cause frame B's reactions to
  re-derive to a different value. Frame-isolation under the reactive
  substrate."
    (rf/reg-frame :restore/a {})
    (rf/reg-frame :restore/b {})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed 0]   {:frame :restore/a})
    (rf/dispatch-sync [:inc]      {:frame :restore/a})  ;; a-n=1
    (rf/dispatch-sync [:inc]      {:frame :restore/a})  ;; a-n=2
    (rf/dispatch-sync [:seed 100] {:frame :restore/b})
    (rf/dispatch-sync [:inc]      {:frame :restore/b})  ;; b-n=101

    (let [a-r       (rf/subscribe :restore/a [:n])
          b-r       (rf/subscribe :restore/b [:n])
          _         (is (= 2   @a-r))
          _         (is (= 101 @b-r))
          a-history (rf/epoch-history :restore/a)
          a-target  (some (fn [rec] (when (= 1 (:n (:db-after rec))) rec))
                          a-history)]
      (is (true? (rf/restore-epoch! :restore/a (:epoch-id a-target))))
      (is (= 1   @a-r) "frame A's reaction sees the rewound value")
      (is (= 101 @b-r) "frame B's reaction is unaffected by the cross-frame restore")
      (rf/unsubscribe :restore/a [:n])
      (rf/unsubscribe :restore/b [:n]))))
