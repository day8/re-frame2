(ns re-frame.runtime-cljs-test
  "CLJS-side smoke tests. Verifies the JVM-shared API (events, subs,
  dispatch, registry) works under the Reagent reactive substrate AND
  exercises the CLJS-only macros from re-frame.views-macros."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.ssr :as ssr]
            [re-frame.substrate.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.views-macros :refer [with-frame bound-fn h reg-view]]))

;; Snapshot/restore the registrar around each test (rf2-am9d). Wiping the
;; registrar with clear-all! is hostile to CLJS test isolation: framework
;; events / subs registered at ns-load (re-frame.routing, re-frame.machines)
;; and example apps (nine-states.core) cannot be re-loaded under CLJS, so
;; once cleared they're gone for the rest of the test run. Snapshot/restore
;; preserves them while still rolling back per-test registrations.
(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- shared dispatch + sub --------------------------------------------------

(deftest dispatch-sync-cljs
  (testing "dispatch-sync runs an event-db handler under the Reagent adapter"
    (rf/reg-event-db :counter/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :counter/inc  (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:counter/init])
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (is (= 2 (:n (rf/get-frame-db :rf/default))))))

(deftest sub-chain-cljs
  (testing "layer-1 + layer-2 subs return computed values"
    (rf/reg-event-db :seed (fn [_ _] {:items [10 20 30]}))
    (rf/reg-sub :items     (fn [db _] (:items db)))
    (rf/reg-sub :item-sum  :<- [:items] (fn [items _] (reduce + items)))
    (rf/dispatch-sync [:seed])
    (is (= [10 20 30] (rf/subscribe-value [:items])))
    (is (= 60         (rf/subscribe-value [:item-sum])))))

;; ---- with-frame macro -------------------------------------------------------

(deftest with-frame-binds-current-frame
  (testing "with-frame :foo binds *current-frame* in the body"
    (with-frame :left
      (is (= :left (rf/current-frame))))
    (testing "and the [sym expr] form binds the symbol AND the dynamic var"
      (with-frame [f :right]
        (is (= :right f))
        (is (= :right (rf/current-frame))))))
  (testing "outside any binding the dynamic var falls back to :rf/default"
    (is (= :rf/default (rf/current-frame)))))

;; ---- bound-fn macro ---------------------------------------------------------

(deftest bound-fn-captures-frame
  (testing "bound-fn captures the current frame and re-binds it inside the body"
    (rf/reg-frame :side {:doc "side frame"})
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n}))
    (rf/dispatch-sync [:seed 99] {:frame :side})
    (let [captured (with-frame :side (bound-fn [] (rf/current-frame)))]
      ;; Outside the with-frame, dynamic var has reverted; the bound-fn
      ;; preserves :side.
      (is (= :rf/default (rf/current-frame)))
      (is (= :side       (captured))))))

;; ---- reg-view macro ---------------------------------------------------------

(deftest reg-view-registers
  (testing "reg-view (defn-shape macro) registers the view under the :view kind"
    ;; Per Spec 004 §reg-view (rf2-d0pi): the macro is defn-shape. It
    ;; auto-derives the id from (keyword *ns* sym); the ^{:rf/id ...}
    ;; metadata override pins an explicit keyword for assertion.
    (reg-view ^{:rf/id :greet} greet [n] [:p "hi " n])
    (is (some? (rf/get-view :greet))
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
    (is (some? (rf/get-view :ns/widget))
        ":ns/widget is in the :view registry")
    ;; The defn-shape sym is bound to a fn — usable as a hiccup head.
    (is (fn? my-widget)
        "the macro defined the supplied symbol as a fn")))

;; ---- h macro ----------------------------------------------------------------

(deftest h-rewrites-namespaced-view-keys
  (testing "h rewrites [:my-ns/widget args] but leaves DOM tags alone"
    ;; Bare keyword heads (DOM tags) pass through unchanged.
    (is (= [:div [:p "hi"]] (h [:div [:p "hi"]]))
        "DOM tag heads pass through unchanged")
    ;; A keyword in a namespace IS treated as a view reference.
    (reg-view ^{:rf/id :my-ns/widget} h-test-widget [n] [:span "w-" n])
    (let [tree (h [:my-ns/widget 7])]
      (is (fn? (first tree))
          "namespaced keyword head was rewritten to a fn")
      (is (= [:span "w-" 7] ((first tree) 7))
          "the rewritten fn produces the registered view's output"))))

;; ---- frame isolation ------------------------------------------------------

(deftest multi-frame-state-isolation
  (testing "two frames carry independent app-db state, share handler registry"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (rf/reg-event-db :counter/init (fn [_ [_ n]] {:count n}))
    (rf/reg-event-db :counter/inc  (fn [db _] (update db :count inc)))
    (rf/reg-sub :count (fn [db _] (:count db)))
    (rf/dispatch-sync [:counter/init 10] {:frame :left})
    (rf/dispatch-sync [:counter/init 100] {:frame :right})
    (rf/dispatch-sync [:counter/inc] {:frame :left})
    (rf/dispatch-sync [:counter/inc] {:frame :left})
    (is (= 12  (rf/subscribe-value :left  [:count])))
    (is (= 100 (rf/subscribe-value :right [:count])))
    (is (nil?  (rf/subscribe-value :rf/default [:count])))))

;; ---- reactivity -----------------------------------------------------------
;; Reagent reactions auto-update on input change. Plain-atom can't show this
;; (every deref recomputes); the Reagent adapter materialises real reactivity.

(deftest reactive-sub-tracks-changes
  (testing "a Reagent reaction's deref reflects post-event state"
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
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
  (testing "re-registering a sub flips the next subscribe-value to the new body"
    (rf/reg-event-db :seed (fn [_ _] {:n 7}))
    (rf/reg-sub :answer (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (is (= 7 (rf/subscribe-value [:answer])))
    ;; Force-pin so the cache slot survives the subscribe-value
    ;; auto-unsubscribe.
    (let [_pin (rf/subscribe [:answer])]
      (rf/reg-sub :answer (fn [db _] (* 10 (:n db))))
      (is (= 70 (rf/subscribe-value [:answer]))
          "the new sub body is in effect after re-registration")
      (rf/unsubscribe [:answer]))))

;; ---- flows ----------------------------------------------------------------

(deftest flow-recomputes
  (testing "a flow recomputes when its inputs change"
    (rf/reg-event-db :init (fn [_ _] {:w 0 :h 0}))
    (rf/reg-event-db :w!   (fn [db [_ w]] (assoc db :w w)))
    (rf/reg-event-db :h!   (fn [db [_ h]] (assoc db :h h)))
    (rf/reg-flow {:id     :rect/area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:area]})
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:w! 3])
    (rf/dispatch-sync [:h! 4])
    (is (= 12 (:area (rf/get-frame-db :rf/default))))))

;; ---- routing --------------------------------------------------------------

(deftest match-and-unparse-routes
  (testing "match-url and route-url round-trip on CLJS"
    (rf/reg-route :user/show {:path "/users/:id"})
    (let [m (rf/match-url "/users/42")]
      (is (= :user/show (:route-id m)))
      (is (= "42"       (:id (:params m)))))
    (is (= "/users/42" (rf/route-url :user/show {:id 42})))))

;; ---- machines (pure machine-transition) -----------------------------------

(deftest machine-transition-cljs
  (testing "pure machine-transition runs on CLJS"
    (let [m {:initial :red
             :data    {}
             :states
             {:red    {:on {:tick {:target :green}}}
              :green  {:on {:tick {:target :yellow}}}
              :yellow {:on {:tick {:target :red}}}}}
          [s _] (machines/machine-transition m {:state :red :data {}} [:tick])]
      (is (= :green (:state s))))))

;; ---- error paths ----------------------------------------------------------

(deftest sub-exception-recovers-to-nil
  (testing "a sub whose body throws emits :rf.error/sub-exception and resolves to nil"
    (rf/reg-event-db :init (fn [_ _] {:items "broken"}))
    (rf/reg-sub :items (fn [db _] (:items db)))
    (rf/reg-sub :items-count :<- [:items]
      (fn [items _]
        ;; Throws on a string.
        (count (.something items))))
    (rf/dispatch-sync [:init])
    (let [traces (atom [])]
      (rf/register-trace-cb! ::sub-err (fn [ev] (swap! traces conj ev)))
      (let [v (rf/subscribe-value [:items-count])]
        (is (nil? v)
            "the sub returns nil under :replaced-with-default recovery"))
      (rf/remove-trace-cb! ::sub-err)
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
    (rf/reg-event-db :seed (fn [_ _] {:items ["a" "b" "c"]}))
    (rf/reg-sub :items (fn [db _] (:items db)))
    (rf/reg-view ^{:rf/id :pages/list} pages-list []
      [:ul
       (for [it (rf/subscribe-value [:items])]
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
    (rf/reg-event-db :diamond/init (fn [_ _] {:x 1 :y 2}))
    (rf/reg-event-db :diamond/swap (fn [{:keys [x y] :as db} _]
                                     (assoc db :x y :y x)))
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
    (rf/reg-event-db :chain/init (fn [_ _] {:n 10}))
    (rf/reg-event-db :chain/set  (fn [db [_ n]] (assoc db :n n)))
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
      (rf/reg-event-db :stable/init (fn [_ _] {:n 5 :unrelated "z"}))
      (rf/reg-event-db :stable/touch-unrelated
                       (fn [db _] (assoc db :unrelated "z")))   ;; same value
      (rf/reg-event-db :stable/bump-n
                       (fn [db _] (update db :n inc)))          ;; real change
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

(deftest dispatch-sync-in-handler-errors-cljs
  (testing "calling dispatch-sync from inside a handler raises a structured error"
    (let [traces (atom [])]
      (rf/register-trace-cb! ::dsih (fn [ev] (swap! traces conj ev)))
      (rf/reg-event-db :outer (fn [db _] (assoc db :ran? true)))
      (rf/reg-event-fx :nested
        (fn [_ _]
          (rf/dispatch-sync [:outer])
          {}))
      (rf/dispatch-sync [:nested])
      (rf/remove-trace-cb! ::dsih)
      (is (some (fn [ev]
                  (and (= :rf.error/dispatch-sync-in-handler (:operation ev))
                       (= :error (:op-type ev))))
                @traces)
          "expected :rf.error/dispatch-sync-in-handler trace"))))

;; ---- sub-cache (rf2-vvsh) -------------------------------------------------

(deftest sub-cache-projects-tool-pair-shape
  (testing "(rf/sub-cache frame-id) returns {query-v {:value v :ref-count n}}
           for every materialised subscription in the named frame"
    (rf/reg-event-db :seed (fn [_ _] {:n 7 :name "ada"}))
    (rf/reg-sub :n     (fn [db _] (:n db)))
    (rf/reg-sub :name* (fn [db _] (:name db)))
    (rf/dispatch-sync [:seed])
    ;; Empty cache is the {} baseline.
    (is (= {} (rf/sub-cache :rf/default))
        "no subs materialised yet → empty map")
    (let [r1 (rf/subscribe [:n])
          r2 (rf/subscribe [:n])
          r3 (rf/subscribe [:name*])
          snapshot (rf/sub-cache :rf/default)]
      (is (= 2 (count snapshot))
          "snapshot contains one entry per materialised query-v")
      (is (= 7     (get-in snapshot [[:n]     :value])))
      (is (= "ada" (get-in snapshot [[:name*] :value])))
      (is (= 2     (get-in snapshot [[:n]     :ref-count]))
          "ref-count reflects two outstanding subscribes for [:n]")
      (is (= 1     (get-in snapshot [[:name*] :ref-count])))
      ;; Default no-arg form uses the active frame.
      (is (= snapshot (rf/sub-cache))
          "no-arg form returns the active frame's snapshot")
      (rf/unsubscribe [:n])
      (rf/unsubscribe [:n])
      (rf/unsubscribe [:name*]))
    ;; Missing frame yields nil rather than throwing.
    (is (nil? (rf/sub-cache :no-such-frame))
        "missing frame returns nil")))

;; ---- epoch history (Tool-Pair §Time-travel, rf2-shjf) ---------------------
;;
;; CLJS smoke test — JVM-side epoch_test.clj covers the broad surface; this
;; verifies the same machinery loads + records under the Reagent substrate.

(deftest epoch-history-cljs
  (testing "drain-settle commits a record; register-epoch-cb fires per-cascade"
    (rf/reg-frame :epoch/cljs {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (let [seen (atom [])]
      (rf/register-epoch-cb ::w (fn [r] (swap! seen conj r)))
      (rf/dispatch-sync [:seed] {:frame :epoch/cljs})
      (rf/dispatch-sync [:inc]  {:frame :epoch/cljs})
      (rf/remove-epoch-cb ::w)

      (let [history (rf/epoch-history :epoch/cljs)]
        (is (= 2 (count history)) "two cascades, two records")
        (is (= [:seed :inc] (mapv :event-id history)))
        (is (= {:n 1} (:db-after (last history))))
        (is (= 2 (count @seen)) "register-epoch-cb fired per-cascade")))))

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
  (testing "[rf/frame-provider {:frame :a} child] emits a Provider element with :a"
    (let [child       [:span "hi"]
          tree        (rf/frame-provider {:frame :a} child)
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
      ;; React Provider element. The hiccup [:>  ProviderClass {:value :a} child]
      ;; structure round-trips back through Reagent.
      (let [provider-tree (apply head value rest)
            [marker provider-class props & inner] provider-tree]
        (is (= :> marker)
            "Reagent's interop marker for native React components")
        (is (some? provider-class)
            "the React Context Provider class is in head position")
        (is (= {:value :a} props)
            "the Provider's :value carries the frame keyword")
        (is (= [child] inner)
            "children pass through unchanged")))))

(deftest frame-provider-default-fallback-no-frame-key
  (testing "frame-provider with no :frame key falls through to :rf/default"
    (let [tree (rf/frame-provider {} [:span "x"])
          [_ value & _] tree]
      (is (= :rf/default value)
          "missing :frame falls through to :rf/default — matches the no-provider case")))
  (testing "frame-provider with explicit nil :frame falls through to :rf/default"
    (let [tree (rf/frame-provider {:frame nil} [:span "x"])
          [_ value & _] tree]
      (is (= :rf/default value)
          "nil :frame falls through to :rf/default"))))

(deftest frame-provider-variadic-children
  (testing "frame-provider accepts zero, one, or many children"
    ;; Zero children — the wrapper still renders the Provider element with
    ;; no inner content. React-side that's a valid empty subtree.
    (let [tree (rf/frame-provider {:frame :z})]
      (is (= :z (second tree))
          "frame keyword threaded through with no children")
      (is (= [] (drop 2 tree))
          "no extra children when none were passed"))
    ;; One child.
    (let [tree (rf/frame-provider {:frame :one} [:p "alone"])]
      (is (= [[:p "alone"]] (drop 2 tree))
          "single child passes through"))
    ;; Many children — the variadic & captures them all in declaration order.
    (let [tree (rf/frame-provider {:frame :many}
                                  [:header]
                                  [:main]
                                  [:footer])]
      (is (= [[:header] [:main] [:footer]] (drop 2 tree))
          "all children present in source order"))))

(deftest frame-provider-keyword-frame
  (testing "frame-provider only handles keyword frame ids (per Spec 002 §Frame ids)"
    ;; The component itself doesn't validate — that's the runtime's job —
    ;; but it threads whatever keyword the user supplies.
    (let [tree (rf/frame-provider {:frame :rf.frame/anonymous-1} [:p])]
      (is (= :rf.frame/anonymous-1 (second tree))
          "namespaced gensym'd frame keyword threads through unchanged"))))

(deftest frame-provider-build-frame-provider-substrate
  (testing "build-frame-provider remains the lower-level substrate"
    ;; Per the bead: build-frame-provider stays in re-frame.views as
    ;; substrate. The user-facing API is rf/frame-provider; the substrate
    ;; hook into re-frame.substrate.reagent/register-context-provider is
    ;; build-frame-provider. Both are callable; both produce the same
    ;; final hiccup shape when invoked with a frame keyword.
    (let [provider     (re-frame.views/build-frame-provider :ignored)
          substrate-tree (provider :hello [:span "x"])
          wrapper-tree   (rf/frame-provider {:frame :hello} [:span "x"])
          ;; The wrapper invokes (build-frame-provider frame-kw) per call;
          ;; the substrate-side returns a fresh component instance. Compare
          ;; the inner Provider hiccup produced by each.
          unwrap         (fn [tree] (apply (first tree) (rest tree)))
          a              (unwrap wrapper-tree)
          b              substrate-tree]
      ;; Both produce `[:> Provider {:value :hello} [:span "x"]]`.
      (is (= (first a) (first b)) "both emit the :> Reagent native marker")
      (is (= (nth a 2) (nth b 2)) "both emit {:value :hello} as the props")
      (is (= (drop 3 a) (drop 3 b)) "both emit the same children"))))

;; ---- per-frame sub-cache grace-period (Spec 006, rf2-s9dn) ----------------
;;
;; Mirrors the synchronous-disposal portion of
;; implementation/test/re_frame/sub_cache_test.clj — verifies the cache
;; surface (configure!, ref-count, pending-dispose slot) under the Reagent
;; adapter. The JVM test exercises the full deferred-dispose contract
;; (resubscribe-cancels, real-time timer assertions) against a
;; ScheduledExecutorService — under CLJS those checks would require async
;; tests, which need a map-form fixture incompatible with this ns's
;; reset-runtime-fixture. The grace-period implementation is shared core
;; code; the JVM coverage is sufficient for the timing contract.

(defn- cache-keys-of
  [frame-id]
  (set (keys @(:sub-cache (frame/frame frame-id)))))

(deftest sub-cache-grace-zero-disposes-synchronously
  (testing "with grace=0, ref-count → 0 disposes the slot synchronously"
    (rf/configure :sub-cache {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])
    (rf/subscribe [:n])
    (is (contains? (cache-keys-of :rf/default) [:n]))
    (rf/unsubscribe [:n])
    (is (not (contains? (cache-keys-of :rf/default) [:n])))
    (rf/configure :sub-cache {:grace-period-ms 50})))

(deftest sub-cache-grace-default-defers-disposal
  (testing "with default grace, last unsubscribe leaves a pending-dispose handle"
    (rf/configure :sub-cache {:grace-period-ms 60000}) ;; long enough never to fire during the test
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])
    (rf/subscribe [:n])
    (rf/unsubscribe [:n])
    (is (contains? (cache-keys-of :rf/default) [:n])
        "slot survives the grace-period window")
    (is (some? (get-in @(:sub-cache (frame/frame :rf/default))
                       [[:n] :pending-dispose]))
        "a deferred-dispose handle was scheduled")
    (let [r2 (rf/subscribe [:n])]
      (is (some? r2) "resubscribe returns the cached reaction")
      (is (nil? (get-in @(:sub-cache (frame/frame :rf/default))
                        [[:n] :pending-dispose]))
          "resubscribe cancels the pending-dispose"))
    (rf/configure :sub-cache {:grace-period-ms 50})))
