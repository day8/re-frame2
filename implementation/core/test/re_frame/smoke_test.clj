(ns re-frame.smoke-test
  "Smoke tests — exercise the foundation end-to-end on the JVM via the
  plain-atom adapter. These are the bare-minimum 'does the dispatch
  pipeline actually work?' tests. Conformance fixtures are a separate
  TODO."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            ;; Pull http-managed in so its late-bind hooks (in particular
            ;; :http/reg-http-interceptor) are published — the
            ;; registry-introspection-round-trip test below exercises
            ;; reg-http-interceptor. Side-effect require; alias unused.
            [re-frame.http-managed]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  ;; flows.cljc keeps a private last-inputs atom for dirty-checking
  ;; (per Spec 013 §Dirty-check semantics). Without resetting it, an
  ;; entry from a prior deftest can leak into a subsequent same-keyed
  ;; flow registration and cause its first evaluation to no-op (the
  ;; new-inputs would =-equal the stale last-inputs). Clear it here so
  ;; cross-test order can't introduce hidden flakiness. See rf2-xsfj.
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (rf/init! plain-atom/adapter)
  ;; Framework events / fx / subs are registered at namespace-load time
  ;; in routing.cljc, ssr.cljc, and machines.cljc; clear-all! wiped them.
  ;; Re-eval those registrations so :rf.route/navigate, :rf/hydrate,
  ;; :rf.nav/push-url, :rf/machine etc. resurrect across smoke tests.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  ;; rf2-o1bp: registry-introspection-round-trip exercises
  ;; reg-http-interceptor which is late-bound through the http-managed
  ;; artefact. Reload so the :http/reg-http-interceptor hook is
  ;; published across runs.
  (require 're-frame.http-managed :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- registry round-trip --------------------------------------------------

(deftest registrar-round-trip
  (testing "registering and looking up a handler"
    (rf/reg-event-db :counter/inc (fn [db _] (update db :n (fnil inc 0))))
    (let [meta (rf/handler-meta :event :counter/inc)]
      (is (some? meta))
      (is (fn? (:handler-fn meta)))
      (is (= :db (:event/kind meta))))))

;; ---- end-to-end dispatch --------------------------------------------------

(deftest dispatch-sync-event-db
  (testing "dispatch-sync runs an event-db handler and commits :db"
    (rf/reg-event-db :counter/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :counter/inc  (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:counter/init])
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (is (= 2 (:n (rf/get-frame-db :rf/default))))))

(deftest dispatch-sync-event-fx
  (testing "dispatch-sync runs an event-fx handler with :db and :fx"
    (let [fired (atom 0)]
      (rf/reg-fx :test/incr-counter
                 (fn [_ _] (swap! fired inc)))
      (rf/reg-event-fx :do-it
        (fn [_ _]
          {:db {:flag :set}
           :fx [[:test/incr-counter :go]]}))
      (rf/dispatch-sync [:do-it])
      (is (= {:flag :set} (rf/get-frame-db :rf/default)))
      (is (= 1 @fired)))))

;; ---- standard interceptors ------------------------------------------------
;;
;; The path / unwrap interceptor contracts live in interceptor_test.clj —
;; that namespace pins every retained Spec 002 interceptor primitive with
;; deeper coverage than this smoke layer ever did (rf2-zqar3 deduplication).

(deftest compute-sub-against-supplied-db
  (testing "compute-sub evaluates a sub against a supplied db value"
    (rf/reg-sub :n     (fn [db _] (:n db)))
    (rf/reg-sub :m     (fn [db _] (:m db)))
    (rf/reg-sub :n*2   :<- [:n] (fn [n _] (* 2 n)))
    (rf/reg-sub :n+m   :<- [:n] :<- [:m] (fn [[n m] _] (+ n m)))
    ;; Layer-1 read.
    (is (= 7 (rf/compute-sub [:n] {:n 7})))
    ;; Layer-2 single :<-.
    (is (= 14 (rf/compute-sub [:n*2] {:n 7})))
    ;; Layer-2 multi :<-.
    (is (= 10 (rf/compute-sub [:n+m] {:n 7 :m 3})))
    ;; Unknown sub returns nil instead of throwing.
    (is (nil? (rf/compute-sub [:no-such-sub] {})))))

(deftest compute-sub-emits-sub-exception-on-body-throw
  ;; rf2-cos61: prior to the fix, compute-sub silently swallowed body
  ;; throws and returned nil — diverging from the reactive sibling
  ;; (`subs.memo/validate-and-trace`) which emits :rf.error/sub-exception
  ;; per Spec 009 §Error contract. Pin parity here: SSR + JVM-runnable
  ;; consumers must see the same debuggable signal the reactive path
  ;; produces.
  (testing "compute-sub emits :rf.error/sub-exception when the sub body throws (layer-1)"
    (rf/reg-sub :boom (fn [_db _q] (throw (ex-info "boom" {:k :v}))))
    (let [traces (atom [])]
      (rf/register-trace-listener! ::boom (fn [ev] (swap! traces conj ev)))
      (is (nil? (rf/compute-sub [:boom] {}))
          "compute-sub still returns nil (recovery :replaced-with-default)")
      (rf/unregister-trace-listener! ::boom)
      (let [ev (some (fn [e]
                       (when (= :rf.error/sub-exception (:operation e)) e))
                     @traces)]
        (is (some? ev) "an :rf.error/sub-exception trace was emitted")
        (when ev
          (is (= :error (:op-type ev)) "op-type is :error")
          ;; `:recovery` is hoisted onto the envelope by build-event.
          (is (= :replaced-with-default (:recovery ev)))
          (let [t (:tags ev)]
            (is (= :boom (:failing-id t)))
            (is (= :boom (:sub-id t)))
            (is (= [:boom] (:sub-query t)))
            (is (= :compute-sub (:where t))
                ":where distinguishes the pure-compute call site from the reactive memo path")
            (is (instance? Throwable (:exception t)))
            (is (= "boom" (:exception-message t))))))))
  (testing "compute-sub emits :rf.error/sub-exception when a layer-2 body throws"
    (rf/reg-sub :n   (fn [db _] (:n db)))
    (rf/reg-sub :n*2 :<- [:n] (fn [_n _q] (throw (ex-info "kaboom" {}))))
    (let [traces (atom [])]
      (rf/register-trace-listener! ::boom2 (fn [ev] (swap! traces conj ev)))
      (is (nil? (rf/compute-sub [:n*2] {:n 7})))
      (rf/unregister-trace-listener! ::boom2)
      (is (some (fn [e]
                  (and (= :rf.error/sub-exception (:operation e))
                       (= :n*2 (:sub-id (:tags e)))
                       (= :compute-sub (:where (:tags e)))))
                @traces)
          "layer-2 throw also emits :rf.error/sub-exception via compute-sub"))))

(deftest subscribe-handles-missing-frame
  (testing "subscribe / subscribe-once against a missing frame don't throw"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (let [traces (atom [])]
      (rf/register-trace-listener! ::missing (fn [ev] (swap! traces conj ev)))
      (is (nil? (rf/subscribe :missing/frame [:n])) "subscribe returns nil")
      (is (nil? (rf/subscribe-once :missing/frame [:n]))
          "subscribe-once returns nil")
      (rf/unregister-trace-listener! ::missing)
      (is (some (fn [ev]
                  (and (= :rf.error/frame-destroyed (:operation ev))
                       (= :replaced-with-default (:recovery ev))))
                @traces)
          "expected :rf.error/frame-destroyed trace with :replaced-with-default"))))

;; sub-cache-ref-counting and sub-hot-reload-invalidates-cache moved to
;; sub_cache_test.clj; flows-are-frame-scoped and
;; flow-hot-reload-invalidates-last-inputs moved to flows artefact's
;; flows_test.clj (rf2-zqar3 cohort split).

(deftest subscriber-captures-frame
  (testing "subscriber closes over the current frame so closures don't need to thread it"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    ;; Seed each frame synchronously so the assertions are deterministic.
    (rf/dispatch-sync [:seed 7]  {:frame :left})
    (rf/dispatch-sync [:seed 99] {:frame :right})
    ;; Capture frame-bound subscribers via with-frame.
    (let [sl (rf/with-frame :left  (rf/subscriber))
          sr (rf/with-frame :right (rf/subscriber))]
      (is (= 7  @(sl [:n])) "left subscriber sees left's :n")
      (is (= 99 @(sr [:n])) "right subscriber sees right's :n")
      ;; And :rf/default is unaffected.
      (is (nil? (rf/subscribe-once :rf/default [:n]))))))

(deftest dispatch-sync-in-handler-errors
  (testing "calling dispatch-sync from inside a handler raises a structured error"
    (let [traces (atom [])]
      (rf/register-trace-listener! ::dsih (fn [ev] (swap! traces conj ev)))
      (rf/reg-event-db :outer (fn [db _] (assoc db :ran? true)))
      (rf/reg-event-fx :nested
        (fn [_ _]
          ;; Calling dispatch-sync from inside a handler should NOT silently
          ;; interleave; it must raise :rf.error/dispatch-sync-in-handler.
          (rf/dispatch-sync [:outer])
          {}))
      (rf/dispatch-sync [:nested])
      (rf/unregister-trace-listener! ::dsih)
      (is (some (fn [ev]
                  (and (= :rf.error/dispatch-sync-in-handler (:operation ev))
                       (= :error (:op-type ev))
                       (= :no-recovery (:recovery ev))))
                @traces)
          "expected :rf.error/dispatch-sync-in-handler trace event"))))

(deftest sync-dispatch-from-handler-body-routes-to-handlers-frame
  ;; Per rf2-l5q3 — the router binds `frame/*current-frame*` to the
  ;; envelope's :frame for the duration of process-event!, so a
  ;; synchronous `(rf/dispatch ...)` from inside a handler body picks
  ;; up the in-flight event's frame (not :rf/default). The CLJS
  ;; companion `re-frame.dispatch-frame-capture-cljs-test` covers the
  ;; full matrix (sync, setTimeout, :fx [[:dispatch ...]],
  ;; :dispatch-later, dispatcher). This JVM test pins the
  ;; sync-from-handler contract on the substrate-agnostic foundation.
  (testing "(rf/dispatch ...) called synchronously from inside a handler routes to that handler's frame"
    (rf/reg-frame :rf-l5q3.jvm/tenant-a {:doc "tenant-a frame"})
    (rf/reg-frame :rf-l5q3.jvm/tenant-b {:doc "tenant-b frame"})
    (rf/reg-event-db :rf-l5q3.jvm/seed
                     (fn [_ _] {:received []}))
    (rf/dispatch-sync [:rf-l5q3.jvm/seed] {:frame :rf-l5q3.jvm/tenant-a})
    (rf/dispatch-sync [:rf-l5q3.jvm/seed] {:frame :rf-l5q3.jvm/tenant-b})
    (rf/dispatch-sync [:rf-l5q3.jvm/seed]) ;; :rf/default
    (rf/reg-event-fx :rf-l5q3.jvm/parent
                     (fn [_ _]
                       (rf/dispatch [:rf-l5q3.jvm/landed])
                       {}))
    (rf/reg-event-db :rf-l5q3.jvm/landed
                     (fn [db _]
                       (update db :received (fnil conj []) :landed)))
    (rf/dispatch-sync [:rf-l5q3.jvm/parent] {:frame :rf-l5q3.jvm/tenant-a})
    (is (= [:landed] (:received (rf/get-frame-db :rf-l5q3.jvm/tenant-a)))
        ":landed event must land on :tenant-a, not :rf/default")
    (is (empty? (:received (rf/get-frame-db :rf-l5q3.jvm/tenant-b)))
        ":tenant-b sees nothing")
    (is (empty? (:received (rf/get-frame-db :rf/default)))
        ":rf/default sees nothing — the dispatch was scoped to :tenant-a")))

(deftest current-frame-inside-handler-reports-handlers-frame
  ;; Per rf2-l5q3 — `(rf/current-frame)` consults the dynamic-var tier
  ;; first. With the router's per-handler binding of
  ;; `frame/*current-frame*` to the envelope's :frame, the call site
  ;; reports the handler's frame, not :rf/default. This is the contract
  ;; that lets `(rf/dispatcher)`, `(rf/subscriber)`, etc. — all of
  ;; which capture the value of
  ;; `(rf/current-frame)` at call time — capture the right frame when
  ;; called from a handler body. Asserts the observable property
  ;; directly (avoids the async drain-thread timing the captured-fn
  ;; invocation would introduce).
  (testing "(rf/current-frame) inside a handler reports the handler's frame"
    (rf/reg-frame :rf-l5q3.jvm.cf/tenant-a {:doc "tenant-a frame"})
    (let [observed-current-frame (atom nil)]
      (rf/reg-event-fx :rf-l5q3.jvm.cf/observe
                       (fn [_ _]
                         (reset! observed-current-frame (rf/current-frame))
                         {}))
      (rf/dispatch-sync [:rf-l5q3.jvm.cf/observe]
                        {:frame :rf-l5q3.jvm.cf/tenant-a})
      (is (= :rf-l5q3.jvm.cf/tenant-a @observed-current-frame)
          "(rf/current-frame) inside a handler reports the handler's frame, not :rf/default"))
    (testing "and a handler running on :rf/default sees :rf/default"
      (let [observed-current-frame (atom nil)]
        (rf/reg-event-fx :rf-l5q3.jvm.cf/observe-default
                         (fn [_ _]
                           (reset! observed-current-frame (rf/current-frame))
                           {}))
        (rf/dispatch-sync [:rf-l5q3.jvm.cf/observe-default])
        (is (= :rf/default @observed-current-frame))))))

;; ---- snapshot-of (rf2-vvsh) ----------------------------------------------

(deftest snapshot-of-reads-default-frame-app-db
  (testing "snapshot-of returns the value at a path in the active frame's app-db"
    (rf/reg-event-db :seed (fn [_ _] {:user {:id 7 :name "ada"}
                                      :counts {:hits 3}}))
    (rf/dispatch-sync [:seed])
    (is (= 7        (rf/snapshot-of [:user :id]))
        "single-arg form reads the active frame (defaults to :rf/default)")
    (is (= "ada"    (rf/snapshot-of [:user :name])))
    (is (= 3        (rf/snapshot-of [:counts :hits])))
    (is (= {:hits 3} (rf/snapshot-of [:counts]))
        "intermediate map paths are returned as-is")
    (is (nil? (rf/snapshot-of [:does-not-exist]))
        "missing paths return nil"))
  (testing "snapshot-of accepts {:frame frame-id} in opts"
    (rf/reg-frame :left  {:doc "left"})
    (rf/reg-frame :right {:doc "right"})
    (rf/reg-event-db :seed-n (fn [_ [_ n]] {:n n}))
    (rf/dispatch-sync [:seed-n 11] {:frame :left})
    (rf/dispatch-sync [:seed-n 99] {:frame :right})
    (is (= 11 (rf/snapshot-of [:n] {:frame :left})))
    (is (= 99 (rf/snapshot-of [:n] {:frame :right})))
    (is (nil? (rf/snapshot-of [:n] {:frame :nonexistent}))
        "missing frame yields nil rather than throwing")))

;; ---- app-schemas (rf2-vvsh) ----------------------------------------------

(deftest app-schemas-returns-registered-schema-map
  (testing "app-schemas returns {path schema} for every reg-app-schema declaration"
    (is (= {} (rf/app-schemas))
        "fresh registry: no schemas registered")
    (rf/reg-app-schema [:user]  [:map [:id :uuid]])
    (rf/reg-app-schema [:todos] [:vector :string])
    (let [m (rf/app-schemas)]
      (is (= 2 (count m)))
      (is (= [:map [:id :uuid]]   (get m [:user])))
      (is (= [:vector :string]    (get m [:todos]))))
    (is (= [:map [:id :uuid]] (rf/app-schema-at [:user]))
        "app-schema-at agrees with app-schemas for individual paths"))
  (testing "keyword-arity is sugar over the opts-map arity"
    ;; Per Spec 010 §Schemas as a tooling and agent surface: the
    ;; (app-schemas frame-id) form is sugar for (app-schemas {:frame ...}).
    ;; In v1 the registry is process-global so both arities return the
    ;; same map; the keyword/opts-map arities must not throw.
    (let [bare    (rf/app-schemas :rf/default)
          opts    (rf/app-schemas {:frame :rf/default})
          no-args (rf/app-schemas)]
      (is (map? bare))
      (is (= bare opts))
      (is (= bare no-args))))
  (testing "app-schemas rejects garbage input with a structured error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (rf/app-schemas "not-a-keyword-or-map")))))

;; ---- subscription topology: glitch-freedom (JVM) -------------------------
;;
;; The CLJS reference uses Reagent reactions and asserts no transient
;; intermediate value is observed during propagation. The JVM plain-atom
;; adapter's make-derived-value recomputes on every deref — there is no
;; reactive cascade, so glitches are impossible by construction. These
;; JVM mirrors of the CLJS topology tests pin the *algebraic* property:
;; layer-2+ subs computed via compute-sub against a post-event app-db
;; produce the post-event value (and only that value).

(deftest sub-topology-glitch-free-diamond-jvm
  (testing "diamond: app-db -> {a,b} -> c — c reads the post-swap state under compute-sub"
    (rf/reg-event-db :diamond/init (fn [_ _] {:x 1 :y 2}))
    (rf/reg-event-db :diamond/swap (fn [{:keys [x y] :as db} _]
                                     (assoc db :x y :y x)))
    (rf/reg-sub :diamond/a (fn [db _] (:x db)))
    (rf/reg-sub :diamond/b (fn [db _] (:y db)))
    (rf/reg-sub :diamond/c
      :<- [:diamond/a]
      :<- [:diamond/b]
      (fn [[a b] _] {:a a :b b}))
    (let [f (rf/make-frame {})]
      (rf/dispatch-sync [:diamond/init] {:frame f})
      (is (= {:a 1 :b 2} (rf/compute-sub [:diamond/c] (rf/get-frame-db f)))
          "initial state is fully consistent")
      (rf/dispatch-sync [:diamond/swap] {:frame f})
      (is (= {:a 2 :b 1} (rf/compute-sub [:diamond/c] (rf/get-frame-db f)))
          "post-swap state is fully consistent — never half-propagated"))))

(deftest sub-topology-glitch-free-chain-jvm
  (testing "chain: :n -> a -> (* 2) -> b -> inc -> c — compute-sub yields only the post-event value"
    (rf/reg-event-db :chain/init (fn [_ _] {:n 10}))
    (rf/reg-event-db :chain/set  (fn [db [_ n]] (assoc db :n n)))
    (rf/reg-sub :chain/a (fn [db _] (:n db)))
    (rf/reg-sub :chain/b :<- [:chain/a] (fn [a _] (* a 2)))
    (rf/reg-sub :chain/c :<- [:chain/b] (fn [b _] (inc b)))
    (let [f (rf/make-frame {})]
      (rf/dispatch-sync [:chain/init] {:frame f})
      (is (= 21 (rf/compute-sub [:chain/c] (rf/get-frame-db f)))
          "initial: n=10 → b=20 → c=21")
      (rf/dispatch-sync [:chain/set 100] {:frame f})
      (is (= 201 (rf/compute-sub [:chain/c] (rf/get-frame-db f)))
          "after :n→100: b=200 → c=201; no transient intermediates"))))

(deftest sub-correctness-on-value-equal-input-jvm
  (testing "a value-equal app-db replacement keeps the downstream sub value correct"
    (rf/reg-event-db :stable/init (fn [_ _] {:n 5 :unrelated "z"}))
    (rf/reg-event-db :stable/touch-unrelated
                     (fn [db _] (assoc db :unrelated "z")))   ;; same value
    (rf/reg-sub :stable/a (fn [db _] (:n db)))
    (rf/reg-sub :stable/squared :<- [:stable/a] (fn [a _] (* a a)))
    (let [f (rf/make-frame {})]
      (rf/dispatch-sync [:stable/init] {:frame f})
      (is (= 25 (rf/compute-sub [:stable/squared] (rf/get-frame-db f)))
          "initial value correct: 5*5 = 25")
      (rf/dispatch-sync [:stable/touch-unrelated] {:frame f})
      (is (= 25 (rf/compute-sub [:stable/squared] (rf/get-frame-db f)))
          "value still correct after a value-equal app-db replacement"))))

;; ---- subscription chain ---------------------------------------------------

(deftest layer-1-and-layer-2-subs
  (testing "layer-1 and layer-2 subs return computed values"
    (rf/reg-event-db :seed (fn [_ _] {:items [1 2 3 4 5]}))
    (rf/reg-sub :items     (fn [db _] (:items db)))
    (rf/reg-sub :item-count :<- [:items] (fn [items _] (count items)))
    (rf/dispatch-sync [:seed])
    (is (= [1 2 3 4 5] (rf/subscribe-once :rf/default [:items])))
    (is (= 5           (rf/subscribe-once :rf/default [:item-count])))))

;; ---- machine ---------------------------------------------------------------
;;
;; pure-machine-transition / machine-always-microstep / machine-raise-pre-commit
;; moved to machines artefact's machine_transition_purity_test.clj
;; (rf2-zqar3 cohort split — all three exercise the pure
;; machines/machine-transition surface; co-located with the rest of the
;; pure-transition contract tests).

;; ---- flows ----------------------------------------------------------------

(deftest flow-rectangle-area
  (testing "a flow recomputes :area when :width or :height changes"
    (rf/reg-event-db :init (fn [_ _] {:width 0 :height 0}))
    (rf/reg-event-db :w! (fn [db [_ w]] (assoc db :width w)))
    (rf/reg-event-db :h! (fn [db [_ h]] (assoc db :height h)))
    (rf/reg-flow {:id     :rect/area
                  :inputs [[:width] [:height]]
                  :output (fn [w h] (* w h))
                  :path   [:area]})
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:w! 3])
    (rf/dispatch-sync [:h! 4])
    ;; The drain calls run-flows! after :db commit per Spec 013.
    (is (= 12 (:area (rf/get-frame-db :rf/default))))))

;; ---- routing --------------------------------------------------------------

(deftest route-url-encoding
  (testing "route-url percent-encodes named params; match-url decodes them"
    (rf/reg-route :user/show {:path "/users/:id"})
    (is (= "/users/hello%20world"
           (rf/route-url :user/show {:id "hello world"})))
    (let [m (rf/match-url "/users/hello%20world")]
      (is (= "hello world" (:id (:params m))))))
  (testing "splat value preserves '/' between segments but encodes within"
    (rf/reg-route :files/get {:path "/files/*rest"})
    (is (= "/files/a/b%20c/d"
           (rf/route-url :files/get {:rest "a/b c/d"})))
    (let [m (rf/match-url "/files/a/b%20c/d")]
      (is (= "a/b c/d" (:rest (:params m))))))
  (testing "query keys and values are encoded / decoded. rf2-5ifai:
            the bare route declares no :query vocabulary, so the key
            stays a string."
    (rf/reg-route :search {:path "/search"})
    (is (= "/search?q=hello%20world"
           (rf/route-url :search {} {:q "hello world"})))
    (let [m (rf/match-url "/search?q=hello%20world")]
      (is (= "hello world" (get-in m [:query "q"]))))))

(deftest match-and-route-url
  (testing "match-url and route-url round-trip"
    (rf/reg-route :user/show {:path "/users/:id"})
    (let [m (rf/match-url "/users/42")]
      (is (= :user/show (:route-id m)))
      (is (= "42" (:id (:params m)))))
    (is (= "/users/42" (rf/route-url :user/show {:id 42})))))

;; ---- SSR emitter ----------------------------------------------------------

(deftest view-macros-load-cleanly
  (testing "the view macros from re-frame.core load on the JVM and expand"
    ;; with-frame expands into binding *current-frame*.
    (let [exp (macroexpand-1 `(re-frame.core/with-frame :foo :body))]
      (is (some #(= 're-frame.frame/*current-frame* %)
                (tree-seq coll? seq exp))
          "with-frame expansion references *current-frame*"))
    ;; reg-view (defn-shape per Spec 004 §reg-view) defs the symbol and
    ;; registers under (keyword (str *ns*) (str sym)). Per rf2-hzos the
    ;; expansion is (do (binding [...] (reg-view* ...)) (def sym (view ...)) id)
    ;; — the terminal id makes the macro return its primary id (matching
    ;; the reg-* return-value contract pinned in spec/Conventions.md).
    (let [exp (macroexpand `(re-frame.core/reg-view
                              ~'my-widget [] :body))]
      (is (= 'do (first exp))
          "reg-view expansion starts with do (binding + def + id)")
      (let [forms    (rest exp)
            ;; The trailing form is the id (terminal expression). The
            ;; def is the penultimate form.
            id-form  (last forms)
            def-form (last (butlast forms))]
        (is (keyword? id-form)
            "the trailing form in the expansion is the registered id (a keyword)")
        (is (= 'def (first def-form))
            "the penultimate form is the auto-def of the Var")
        (is (= 'my-widget (second def-form))
            "the def binds the symbol the user supplied")))))

(deftest verify-hydration-emits-mismatch
  (testing "rf/hydrate stashes :rf/hydration metadata; verify-hydration! detects mismatch"
    (require 're-frame.ssr)
    (let [verify-fn  @(resolve 're-frame.ssr/verify-hydration!)
          ;; Server-supplied payload with a render-hash.
          payload    {:rf/version     1
                      :rf/app-db      {:greeting "Hello, server!"}
                      :rf/render-hash "server-hash-X"}
          traces     (atom [])]
      (rf/dispatch-sync [:rf/hydrate payload])
      ;; Hydrate stashed the metadata.
      (is (= "server-hash-X"
             (get-in (rf/get-frame-db :rf/default) [:rf/hydration :server-hash])))
      ;; Now simulate the client render producing a different hash.
      (rf/register-trace-listener! ::vh (fn [ev] (swap! traces conj ev)))
      (verify-fn :rf/default "client-hash-Y")
      (rf/unregister-trace-listener! ::vh)
      (is (some (fn [ev]
                  (and (= :rf.ssr/hydration-mismatch (:operation ev))
                       (= "server-hash-X" (:server-hash (:tags ev)))
                       (= "client-hash-Y" (:client-hash (:tags ev)))
                       (= :warned-and-replaced (:recovery ev))))
                @traces)
          "expected :rf.ssr/hydration-mismatch trace with both hashes"))))

(deftest render-tree-hash-is-stable
  (testing "render-tree-hash is deterministic and order-sensitive on vectors"
    (require 're-frame.ssr)
    (let [hash (resolve 're-frame.ssr/render-tree-hash)
          h1   (@hash [:div {:class "x"} [:p "hello"]])
          h2   (@hash [:div {:class "x"} [:p "hello"]])
          h3   (@hash [:div {:class "y"} [:p "hello"]])]
      (is (= h1 h2) "identical trees hash identically")
      (is (not= h1 h3) "different attrs change the hash")
      (is (re-matches #"[0-9a-f]{8}" h1)
          "hash is 8-char lowercase hex (FNV-1a 32-bit)"))))

(deftest render-to-string-emits-hash
  (testing ":emit-hash? opts adds data-rf-render-hash on the root element"
    (let [out (rf/render-to-string [:div [:p "hi"]] {:emit-hash? true})]
      (is (re-find #"<div data-rf-render-hash=\"[0-9a-f]{8}\">" out)
          "root element carries the data-rf-render-hash attribute"))))

(deftest destroy-frame-signals-active-machines
  (testing "destroy-frame! emits one :rf.machine.lifecycle/destroyed per active machine, carrying :reason :parent-frame-destroyed"
    (rf/reg-frame :tenant-a {:doc "tenant"})
    ;; Seed a machine snapshot directly into app-db so we don't need to
    ;; run a full machine through this test.
    (rf/reg-event-db :seed-machines
      (fn [db _]
        (assoc db :rf/machines {:flow/login    {:state :authed   :data {}}
                                :flow/checkout {:state :pending  :data {}}})))
    (rf/dispatch-sync [:seed-machines] {:frame :tenant-a})
    (let [traces (atom [])]
      (rf/register-trace-listener! ::df (fn [ev] (swap! traces conj ev)))
      (rf/destroy-frame! :tenant-a)
      (rf/unregister-trace-listener! ::df)
      (let [machine-traces (filter #(= :rf.machine.lifecycle/destroyed
                                        (:operation %))
                                   @traces)]
        (is (= 2 (count machine-traces))
            "one trace per active machine snapshot")
        (is (every? #(= :tenant-a (:frame (:tags %))) machine-traces)
            "all traces carry the destroyed frame's id")
        (is (= #{:authed :pending}
               (set (map #(:last-state (:tags %)) machine-traces)))
            "each trace carries its machine's last-state")
        (is (every? #(= :parent-frame-destroyed (:reason (:tags %))) machine-traces)
            "each trace carries :reason :parent-frame-destroyed")))))

(deftest spawn-id-is-frame-scoped
  (testing "actor-id allocation is scoped per-parent-snapshot — independent frames don't share an actor-id sequence"
    (let [machine
          ;; Per Spec 005 §Where snapshots live: spec map does NOT carry
          ;; :id; the id comes from the surrounding reg-event-fx id.
          {:initial :idle
           :data    {}
           :states  {:idle    {:on    {:start :working}}
                     :working {:spawn {:machine-id :worker
                                        :id-prefix  :worker
                                        :start      [:begin]
                                        :on-spawn   :record}
                               :on    {:done :idle}}}
           ;; Per Spec 005 §Declarative :spawn (rf2-een2 / rf2-smba):
           ;; on-spawn callback signature is (fn [data spawned-id] new-data).
           :on-spawn-actions {:record (fn [data _id] data)}}
          handler (rf/make-machine-handler machine)]
      (rf/reg-frame :left  {:doc "left"})
      (rf/reg-frame :right {:doc "right"})
      (rf/reg-event-fx :flow handler)
      ;; Each frame's first invoke should get :worker#1.
      (let [traces (atom [])]
        (rf/register-trace-listener! ::sids (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:flow [:start]] {:frame :left})
        (rf/dispatch-sync [:flow [:start]] {:frame :right})
        (rf/unregister-trace-listener! ::sids)
        (let [spawn-traces (filter #(= :rf.machine/spawned (:operation %)) @traces)
              ids          (mapv #(get-in % [:tags :id-prefix]) spawn-traces)]
          (is (= 2 (count spawn-traces))
              "two :rf.machine/spawned traces — one per frame")
          (is (every? #(= :worker %) ids)
              "both spawned the :worker machine")
          ;; Per rf2-gr8q the spawn-counter is no longer a per-process
          ;; atom — it lives inside each parent machine's snapshot at
          ;; `:rf/spawn-counter`. Frame-scoping is inherited from
          ;; per-frame app-db isolation: each frame owns its own copy of
          ;; the :flow machine's snapshot at [:rf/machines :flow]; each
          ;; copy's counter advances independently. Read the counter
          ;; from each frame's snapshot directly.
          (let [snap-left  (get-in (rf/get-frame-db :left)  [:rf/machines :flow])
                snap-right (get-in (rf/get-frame-db :right) [:rf/machines :flow])]
            (is (= 1 (get-in snap-left  [:rf/spawn-counter :worker]))
                "left frame's :flow snapshot counts one :worker spawn")
            (is (= 1 (get-in snap-right [:rf/spawn-counter :worker]))
                "right frame's :flow snapshot counts one :worker spawn")
            ;; Sanity: both allocations are independent — neither
            ;; reached count 2 (cross-frame contamination).
            (is (every? #(= 1 (get-in % [:rf/spawn-counter :worker]))
                        [snap-left snap-right])
                "the per-frame counters didn't share an allocator")))))))

(deftest spawn-and-destroy-machine-fx
  (testing ":rf.machine/spawn and :rf.machine/destroy traverse fx without :rf.error/no-such-fx"
    (let [traces (atom [])]
      (rf/register-trace-listener! ::spawn (fn [ev] (swap! traces conj ev)))
      (rf/reg-event-fx :do-spawn
        (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id :worker
                                            :id-prefix  :worker
                                            :start      [:begin]
                                            :on-spawn   :record}]
                        [:rf.machine/destroy :worker#1]]}))
      (rf/dispatch-sync [:do-spawn])
      (rf/unregister-trace-listener! ::spawn)
      (is (some #(= :rf.machine/spawned (:operation %)) @traces)
          "expected :rf.machine/spawned trace")
      (is (some #(= :rf.machine/destroyed (:operation %)) @traces)
          "expected :rf.machine/destroyed trace")
      (is (not-any? #(= :rf.error/no-such-fx (:operation %)) @traces)
          ":rf.machine/spawn / :rf.machine/destroy should not raise no-such-fx"))))

(deftest login-machine-flow
  (testing "Spec 005 machine pattern: login flow as a state machine, end-to-end"
    ;; Stub fx that synthesises a successful or failed HTTP response.
    (rf/reg-fx :http.canned-success
      {:platforms #{:server :client}}
      (fn [{:keys [frame]} {:keys [on-success]}]
        (when on-success
          (rf/dispatch (conj on-success {:user {:id "u1" :email "a@b.c"}
                                         :token "t-1"})
                       {:frame frame}))))
    (rf/reg-fx :http.canned-failure
      {:platforms #{:server :client}}
      (fn [{:keys [frame]} {:keys [on-error]}]
        (when on-error
          (rf/dispatch (conj on-error {:message "bad creds"})
                       {:frame frame}))))
    ;; Real :http stays a no-op; the override redirects it for the test.
    (rf/reg-fx :http {:platforms #{:server :client}} (fn [_ _] nil))
    ;; Session storage stub: capture the token instead of writing to
    ;; localStorage.
    (let [stored (atom nil)]
      (rf/reg-fx :auth.session/store
        {:platforms #{:server :client}}
        (fn [_ {:keys [token]}] (reset! stored token)))

      ;; The login machine. Mirrors the structure of examples/login/core.cljs's
      ;; :auth.login/flow — five states, deepest-wins, multi-guard branch.
      (rf/reg-event-fx :auth.login/flow
        (rf/make-machine-handler
          ;; Per Spec 005: spec map does NOT carry :id; the id comes
          ;; from the reg-event-fx call above.
          {:initial :idle
           :data    {:attempts 0 :error nil}
           :guards
           {:under-retry-limit
            (fn [data _] (< (:attempts data) 3))}
           :actions
           {:clear-error    (fn [_ _] {:data {:error nil}})
            :issue-request  (fn [_ [_ creds]]
                              {:fx [[:http {:method     :post
                                            :url        "/api/login"
                                            :body       creds
                                            :on-success [:auth.login/flow [:auth.login/success]]
                                            :on-error   [:auth.login/flow [:auth.login/failure]]}]]})
            :record-error   (fn [data [_ err]]
                              {:data (-> data
                                         (update :attempts inc)
                                         (assoc :error (or (:message err) "Login failed.")))})
            :lock-account   (fn [_ _] {:fx []})
            :store-session  (fn [_ [_ {:keys [token]}]]
                              {:fx [[:auth.session/store {:token token}]]})}
           :states
           {:idle        {:on {:auth.login/submit {:target :submitting :action :clear-error}}}
            :submitting  {:entry :issue-request
                          :on    {:auth.login/success {:target :authed :action :store-session}
                                  :auth.login/failure [{:target :error-shown :guard :under-retry-limit :action :record-error}
                                                       {:target :locked-out :action :lock-account}]}}
            :error-shown {:on {:auth.login/dismiss {:target :idle}
                               :auth.login/submit  {:target :submitting}}}
            :authed      {}
            :locked-out  {}}}))

      ;; Subs over the machine snapshot.
      (rf/reg-sub :auth.login/state
        (fn [db _] (get-in db [:rf/machines :auth.login/flow :state])))

      (testing "happy path: idle → submitting → authed; session token stored"
        (reset! stored nil)
        (let [f (rf/make-frame {:fx-overrides {:http :http.canned-success}})]
          (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                               {:email "a@b.c" :password "secret"}]]
                            {:frame f})
          (is (= :authed (rf/compute-sub [:auth.login/state] (rf/get-frame-db f)))
              "machine landed in :authed after canned success")
          (is (= "t-1" @stored)
              "session token was stored via the :auth.session/store fx")))

      (testing "retry-then-lockout: 3 failures land in :error-shown, 4th in :locked-out"
        (reset! stored nil)
        (let [f (rf/make-frame {:fx-overrides {:http :http.canned-failure}})]
          (dotimes [_ 3]
            (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                                 {:email "x@y.z" :password "wrong"}]]
                              {:frame f})
            (rf/dispatch-sync [:auth.login/flow [:auth.login/dismiss]] {:frame f}))
          ;; 4th submit — :under-retry-limit fails; second clause's :locked-out
          ;; target wins.
          (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                               {:email "x@y.z" :password "wrong"}]]
                            {:frame f})
          (is (= :locked-out (rf/compute-sub [:auth.login/state] (rf/get-frame-db f)))
              "guarded multi-clause branch routed to :locked-out on 4th attempt"))))))

(deftest rf-machine-sub
  (testing "framework-shipped :rf/machine sub returns the snapshot for a registered machine"
    (rf/reg-machine :test/tiny
      {:initial :idle
       :data    {:n 0}
       :actions {:bump (fn [data _] {:data (update data :n inc)})}
       :states  {:idle {:on {:tick {:target :idle :action :bump}}}}})
    (let [f (rf/make-frame {})]
      (rf/dispatch-sync [:test/tiny [:tick]] {:frame f})
      (rf/dispatch-sync [:test/tiny [:tick]] {:frame f})
      (let [db (rf/get-frame-db f)]
        ;; Per rf2-gr8q the snapshot carries `:rf/spawn-counter` seeded
        ;; by `synthesise-initial-snapshot`. This machine never spawns,
        ;; so the slot stays empty (`{}`).
        (is (= {:state :idle :data {:n 2} :rf/spawn-counter {}}
               (get-in db [:rf/machines :test/tiny]))
            "machine snapshot exists at the spec'd path")
        (is (= {:state :idle :data {:n 2} :rf/spawn-counter {}}
               (rf/compute-sub [:rf/machine :test/tiny] db))
            ":rf/machine sub returns the same snapshot")))))

(deftest machines-introspection
  (testing "(rf/machines) returns only ids whose registration was via reg-machine"
    (let [tiny-spec   {:initial :idle
                       :data    {:n 0}
                       :doc     "A tiny test machine."
                       :actions {:bump (fn [data _]
                                         {:data (update data :n inc)})}
                       :states  {:idle {:on {:tick {:target :idle
                                                    :action :bump}}}}}
          other-spec  {:initial :off
                       :states  {:off {:on {:flip :on}}
                                 :on  {:on {:flip :off}}}}]
      (rf/reg-machine :test/tiny  tiny-spec)
      (rf/reg-machine :test/other other-spec)
      ;; A regular event-handler must NOT show up in (rf/machines).
      (rf/reg-event-db :test/regular (fn [db _] db))

      (let [ids (set (rf/machines))]
        (is (contains? ids :test/tiny)
            "(rf/machines) lists machines registered via reg-machine")
        (is (contains? ids :test/other)
            "(rf/machines) lists every reg-machine id")
        (is (not (contains? ids :test/regular))
            "(rf/machines) excludes plain event handlers"))

      (testing "(rf/machine-meta id) returns the spec map for registered machines"
        (is (= tiny-spec (rf/machine-meta :test/tiny))
            "machine-meta returns the spec map passed to reg-machine")
        (is (= other-spec (rf/machine-meta :test/other)))
        (is (= "A tiny test machine."
               (:doc (rf/machine-meta :test/tiny)))
            "the spec's :doc round-trips through machine-meta"))

      (testing "(rf/machine-meta id) returns nil for unregistered or non-machine ids"
        (is (nil? (rf/machine-meta :test/regular))
            "non-machine event handlers return nil")
        (is (nil? (rf/machine-meta :test/never-registered))
            "unregistered ids return nil")))))

;; ssr-with-fx-override and ssr-end-to-end moved to the ssr artefact's
;; ssr_end_to_end_test.clj (rf2-zqar3 cohort split — co-located with the
;; rest of the SSR request-lifecycle coverage).

(deftest reg-view-jvm
  (testing "reg-view registers a view that render-to-string resolves"
    ;; Plain-fn surface (reg-view*): explicit id, no auto-def.
    (rf/reg-view* :greet
      (fn [name] [:p "hello " [:strong name]]))
    (is (= "<p>hello <strong>world</strong></p>"
           (rf/render-to-string [:greet "world"]))
        "render-to-string resolves [:greet args] via the :view registry")
    (is (fn? (rf/view :greet))
        "view returns the registered render fn")
    (is (nil? (rf/view :no-such-view)))))

(deftest ssr-render-to-string-basics
  (testing "basic hiccup → HTML"
    (require 're-frame.ssr)
    (let [r2s @(resolve 're-frame.ssr/render-to-string)]
      (is (= "<div>hi</div>"
             (r2s [:div "hi"] {})))
      (is (= "<div class=\"a\">hi</div>"
             (r2s [:div {:class "a"} "hi"] {})))
      ;; class on tag-name + class in attrs merges
      (is (= "<div id=\"main\" class=\"col bold\">x</div>"
             (r2s [:div#main.col {:class "bold"} "x"] {})))
      ;; void elements per HTML5 — no closing tag, no self-close slash.
      (is (= "<br>" (r2s [:br] {})))
      (is (= "<input type=\"text\">"
             (r2s [:input {:type "text"}] {})))
      ;; boolean attribute
      (is (= "<input disabled>"
             (r2s [:input {:disabled true}] {})))
      ;; HTML-escape text
      (is (clojure.string/includes? (r2s [:p "a < b & c > d"] {}) "&lt;"))
      ;; doctype
      (is (clojure.string/starts-with? (r2s [:html [:body]] {:doctype? true})
                                       "<!DOCTYPE html>")))))

;; ---- rf2-o1bp: handler-ids / registrations / handler-meta ----------------
;;
;; Per test-coverage-review-2026-05-12 P3-17: the introspection re-exports
;; (`registrations`, `handler-meta`, `handler-ids`). They're used inside
;; fixtures and the source-coords tests but no single deftest pins their
;; cross-kind round-trip. This test registers handlers across the canonical
;; kinds, then walks the three introspection surfaces against each.

(deftest registry-introspection-round-trip
  (testing "rf/handler-ids, rf/registrations, rf/handler-meta cover every
            registration kind with the documented shape"
    ;; ---- :event --------------------------------------------------------
    (rf/reg-event-db :rf2-o1bp/evt1 (fn [db _] db))
    (rf/reg-event-fx :rf2-o1bp/evt2 (fn [_ _] {}))

    ;; ---- :sub ---------------------------------------------------------
    (rf/reg-sub :rf2-o1bp/sub1 (fn [db _] db))

    ;; ---- :fx ----------------------------------------------------------
    (rf/reg-fx :rf2-o1bp/fx1
               {:platforms #{:server :client}}
               (fn [_ _] nil))

    ;; ---- :cofx --------------------------------------------------------
    (rf/reg-cofx :rf2-o1bp/cofx1 (fn [cofx _] cofx))

    ;; ---- :view --------------------------------------------------------
    ;; reg-view* is the JVM-safe registration path. On CLJS it wraps the
    ;; render fn; on JVM it registers the fn directly under :view.
    (rf/reg-view* :rf2-o1bp/view1 (fn [] [:div "v1"]))

    ;; ---- :machine (registers under :event with :rf/machine? true) ----
    (rf/reg-machine :rf2-o1bp/mach1
      {:initial :idle
       :data    {}
       :states  {:idle {}}})

    ;; ---- :route -------------------------------------------------------
    (rf/reg-route :rf2-o1bp/route1 {:path "/rf2-o1bp/landing"})

    ;; ---- :flow --------------------------------------------------------
    (rf/reg-flow {:id     :rf2-o1bp/flow1
                  :inputs []
                  :output (fn [_inputs] :computed)
                  :path   [:rf2-o1bp/flow-output]})

    ;; ---- :http-interceptor --------------------------------------------
    ;; reg-http-interceptor uses its own per-frame atom (not the
    ;; registrar). The bead lists :http-interceptor among the kinds to
    ;; register across; we still exercise the surface so the late-bind
    ;; hook is touched.
    (rf/reg-http-interceptor {:id     :rf2-o1bp/interceptor1
                              :before identity})

    ;; ---- :error-projector --------------------------------------------
    ;; reg-error-projector lives in re-frame.ssr; ns-load registers
    ;; :rf.ssr/default-error-projector.
    (rf/reg-error-projector :rf2-o1bp/err1 (fn [_ _] {}))

    ;; ---- :app-schema -------------------------------------------------
    ;; Per rf2-0frdi reg-app-schema writes only to the schemas artefact's
    ;; own per-frame side-table (`schemas/schemas-by-frame`), NOT to the
    ;; registrar — same pattern as `:http-interceptor` above. The kind is
    ;; registered for completeness; introspection of registered app-db
    ;; schemas goes through `schemas/app-schemas` / `schemas/app-schema-
    ;; meta-at` rather than `handler-ids`.
    (rf/reg-app-schema [:rf2-o1bp/path] :any)

    ;; ---- (1) handler-ids returns a set of ids per kind ----------------
    (testing "(rf/handler-ids kind) returns a set of ids"
      (let [event-ids       (rf/handler-ids :event)
            sub-ids         (rf/handler-ids :sub)
            fx-ids          (rf/handler-ids :fx)
            cofx-ids        (rf/handler-ids :cofx)
            view-ids        (rf/handler-ids :view)
            route-ids       (rf/handler-ids :route)
            flow-ids        (rf/handler-ids :flow)
            ep-ids          (rf/handler-ids :error-projector)
            ;; Per rf2-0frdi `:app-schema` is owned by the schemas
            ;; artefact's side-table — handler-ids on the registrar
            ;; kind is empty. App-db schema introspection goes through
            ;; `schemas/app-schemas` (returns `{path → schema}`).
            schema-ids      (rf/handler-ids :app-schema)]
        (is (set? event-ids) "handler-ids returns a set")
        (is (contains? event-ids :rf2-o1bp/evt1))
        (is (contains? event-ids :rf2-o1bp/evt2))
        (is (contains? event-ids :rf2-o1bp/mach1)
            "the machine appears in :event id set")
        (is (contains? sub-ids :rf2-o1bp/sub1))
        (is (contains? fx-ids :rf2-o1bp/fx1))
        (is (contains? cofx-ids :rf2-o1bp/cofx1))
        (is (contains? view-ids :rf2-o1bp/view1))
        (is (contains? route-ids :rf2-o1bp/route1))
        (is (contains? flow-ids :rf2-o1bp/flow1))
        (is (contains? ep-ids :rf2-o1bp/err1))
        (is (not (contains? schema-ids [:rf2-o1bp/path]))
            "registrar handler-ids :app-schema is empty — schemas owns its
             own side-table (rf2-0frdi)")))

    ;; ---- (2) registrations returns {id → metadata} per kind -----------
    (testing "(rf/registrations kind) returns {id → metadata}"
      (let [events (rf/registrations :event)]
        (is (map? events))
        (is (contains? events :rf2-o1bp/evt1))
        (let [meta (get events :rf2-o1bp/evt1)]
          (is (map? meta) "the value is a metadata map")
          (is (fn? (:handler-fn meta))
              "metadata carries :handler-fn — the registered handler fn"))))

    ;; ---- (3) handler-meta returns the metadata for a single id -------
    (testing "(rf/handler-meta kind id) returns the metadata map"
      (let [m (rf/handler-meta :event :rf2-o1bp/evt1)]
        (is (map? m))
        (is (fn? (:handler-fn m)))
        (is (= :db (:event/kind m))
            ":event metadata carries :event/kind (set by reg-event-db)"))
      ;; A machine carries :rf/machine? true and :rf/machine <spec>.
      (let [m (rf/handler-meta :event :rf2-o1bp/mach1)]
        (is (true? (:rf/machine? m))
            "machine event metadata is tagged :rf/machine? true")
        (is (map? (:rf/machine m))
            "machine spec is stored at :rf/machine"))
      ;; Routes carry the :path field.
      (let [m (rf/handler-meta :route :rf2-o1bp/route1)]
        (is (= "/rf2-o1bp/landing" (:path m))
            ":route metadata carries :path"))
      ;; Flows carry :path and :inputs.
      (let [m (rf/handler-meta :flow :rf2-o1bp/flow1)]
        (is (= [:rf2-o1bp/flow-output] (:path m)))
        (is (= [] (:inputs m))))
      ;; Unknown id → nil.
      (is (nil? (rf/handler-meta :event :no-such-event))
          "handler-meta on an unknown id returns nil"))))
