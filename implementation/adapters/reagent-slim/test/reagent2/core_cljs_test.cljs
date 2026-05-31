(ns reagent2.core-cljs-test
  "Unit tests for `reagent2.core` user-facing surface (Stage 4-D, rf2-6hyy).

  Covers the surfaces that don't have dedicated test files:

    - force-update — routes .forceUpdate on `this`; 1-arity only
      (the stock-Reagent 2-arity `[this deep?]` was dropped per
      rf2-okpsr — :deep? has no React 19 analogue and no audited
      caller relied on it).

    - Form-3 component-state surface (rf2-ynjts.3 coverage pass): the
      PUBLIC `reagent2.core` wrappers `state-atom` / `state` /
      `set-state` / `replace-state` and the argv accessors `argv` /
      `props` / `children`. These are the symbols downstream code
      imports as `reagent.core/state` etc. (re-com / Day8). The
      underlying `reagent2.impl.component/*` fns are pinned in
      component_cljs_test; what was UNVERIFIED before this pass is the
      `reagent2.core`-level wiring — that `state` derefs the cached
      cell, `set-state` MERGES while `replace-state` RESETS (a real,
      distinguishable invariant a key-swap bug would otherwise slip),
      and that the accessor wrappers route to the right impl fn. The
      `reagent2.core` re-export `atom`, the `reaction` function form,
      `create-class`, `current-component`, `after-render`, and
      `as-element` keep their dedicated per-impl coverage
      (ratom / component / template / batching).

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]))

;; ---------------------------------------------------------------------------
;; force-update — 1-arity routes .forceUpdate on `this`
;; ---------------------------------------------------------------------------

(defn- fake-react-instance
  "Build a minimal stand-in for a mounted React class instance. Tracks
  .forceUpdate invocations on the `calls` atom."
  [calls]
  (let [c #js {}]
    (set! (.-forceUpdate c)
          (fn [] (swap! calls conj :force-update)))
    c))

(deftest force-update-1-arity-calls-forceupdate
  (testing "(force-update this) invokes .forceUpdate on the instance"
    (let [calls (atom [])
          this  (fake-react-instance calls)]
      (r/force-update this)
      (is (= [:force-update] @calls)
          ".forceUpdate fired exactly once"))))

(deftest force-update-no-throw-when-forceupdate-missing
  (testing "missing .forceUpdate is a silent no-op (defensive against
            stand-in instances that don't carry the React method)"
    (let [bare #js {}]
      ;; No throw expected — the when-some guard skips the call.
      (r/force-update bare)
      (is true "did not throw"))))

(deftest force-update-is-strictly-1-arity
  (testing "force-update declares a single 1-arity body (rf2-okpsr —
            the stock-Reagent 2-arity `[this deep?]` was dropped). The
            metadata `:arglists` is the canonical contract surface."
    (let [arglists (-> #'r/force-update meta :arglists)]
      (is (= 1 (count arglists))
          ":arglists declares exactly one signature")
      (is (= '[^js this] (first arglists))
          ":arglists is [this] only"))))

;; ---------------------------------------------------------------------------
;; Form-3 component-state surface (rf2-ynjts.3)
;;
;; `reagent2.core/state-atom` lazily creates a per-component RAtom and
;; caches it on the instance; `state` derefs it; `set-state` MERGES a map
;; into it; `replace-state` RESETS it. These public wrappers are the
;; Form-3 component-local-state API (re-com / Day8). The impl-level
;; `component/state-atom` is pinned in component_cljs_test; here we pin
;; the `reagent2.core`-level behaviour — most importantly that `set-state`
;; and `replace-state` are NOT interchangeable (merge vs replace).
;; ---------------------------------------------------------------------------

(deftest state-atom-lazily-creates-and-caches
  (testing "(state-atom this) creates a cell on first call and returns
            the SAME cell on every subsequent call for that instance"
    (let [this #js {}
          a    (r/state-atom this)
          a2   (r/state-atom this)]
      (is (some? a) "first call materialised a state cell")
      (is (identical? a a2)
          "second call returned the cached cell, not a fresh atom"))))

(deftest state-reads-the-cell
  (testing "(state this) is nil before any state is set, then reflects
            the cached state-atom's value"
    (let [this #js {}]
      (is (nil? (r/state this))
          "no state set yet → nil (derefs the freshly-seeded cell)")
      (reset! (r/state-atom this) {:count 3})
      (is (= {:count 3} (r/state this))
          "state derefs the same cell that state-atom returns"))))

(deftest set-state-merges
  (testing "(set-state this m) MERGES m into the existing state map —
            sibling keys survive; colliding keys are overwritten"
    (let [this #js {}]
      (r/set-state this {:a 1 :b 2})
      (is (= {:a 1 :b 2} (r/state this))
          "first set-state seeds the map")
      (r/set-state this {:b 20 :c 3})
      (is (= {:a 1 :b 20 :c 3} (r/state this))
          "second set-state merged: :a survived, :b overwritten, :c added"))))

(deftest replace-state-resets
  (testing "(replace-state this m) REPLACES the whole state map —
            prior keys are dropped, NOT merged (the set-state contrast)"
    (let [this #js {}]
      (r/set-state this {:a 1 :b 2})
      (is (= {:a 1 :b 2} (r/state this)) "precondition: state seeded")
      (r/replace-state this {:only :this})
      (is (= {:only :this} (r/state this))
          "replace-state dropped :a and :b — it reset, did not merge"))))

(deftest set-state-and-replace-state-are-distinct
  (testing "set-state and replace-state are NOT interchangeable on the
            same starting state — a key-swap bug would fail here"
    (let [merge-this   #js {}
          replace-this #js {}
          seed         {:keep 1}]
      (r/set-state merge-this seed)
      (r/replace-state replace-this seed)
      ;; Apply the same delta via each mutator.
      (r/set-state merge-this {:add 2})
      (r/replace-state replace-this {:add 2})
      (is (= {:keep 1 :add 2} (r/state merge-this))
          "set-state kept the original :keep")
      (is (= {:add 2} (r/state replace-this))
          "replace-state discarded the original :keep")
      (is (not= (r/state merge-this) (r/state replace-this))
          "the two mutators produced observably different results"))))

(deftest state-is-per-instance
  (testing "state cells are keyed per component instance — two instances
            do not share state"
    (let [a #js {}
          b #js {}]
      (r/set-state a {:who :a})
      (r/set-state b {:who :b})
      (is (= {:who :a} (r/state a)))
      (is (= {:who :b} (r/state b))
          "instance b's state is independent of instance a's")
      (is (not (identical? (r/state-atom a) (r/state-atom b)))
          "distinct instances get distinct state cells"))))

;; ---------------------------------------------------------------------------
;; Form-3 argv accessors (rf2-ynjts.3)
;;
;; `reagent2.core/argv` / `props` / `children` are thin wrappers over the
;; `component/get-*` fns (impl-pinned in component_cljs_test). Here we pin
;; the `reagent2.core`-level routing + the props/children convention: the
;; head is the render fn, argv[1] is the props map iff it is a map, and
;; children are everything after the head (skipping the props map when
;; present).
;; ---------------------------------------------------------------------------

(defn- fake-instance
  "Minimal stand-in carrying the .-cljsArgv slot the accessors read."
  [argv]
  (let [c #js {}]
    (set! (.-cljsArgv c) argv)
    c))

(deftest argv-returns-full-arg-vector
  (testing "(argv this) returns the full hiccup-style argv (head included)"
    (let [render-fn (fn [_])
          this      (fake-instance [render-fn {:k :v} :child])]
      (is (= [render-fn {:k :v} :child] (r/argv this))))))

(deftest props-returns-map-second-arg
  (testing "(props this) returns argv[1] iff it is a map, else nil"
    (let [render-fn (fn [_])]
      (is (= {:k :v}
             (r/props (fake-instance [render-fn {:k :v} :child])))
          "map second arg surfaces as props")
      (is (nil? (r/props (fake-instance [render-fn :child])))
          "non-map second arg → nil props"))))

(deftest children-skip-props-map
  (testing "(children this) returns the tail after the head, skipping a
            leading props map when present"
    (let [render-fn (fn [_])]
      (is (= [:a :b]
             (vec (r/children (fake-instance [render-fn {:k :v} :a :b]))))
          "props map present → children start after it")
      (is (= [:a :b]
             (vec (r/children (fake-instance [render-fn :a :b]))))
          "no props map → children start right after the head"))))
