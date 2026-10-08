(ns reagent2.core-cljs-test
  "Unit tests for the `reagent2.core` surface without a dedicated file:
  force-update, the Form-3 state wrappers (`set-state` merges while
  `replace-state` resets) and argv accessors, and the `reaction` macro."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]
            [reagent2.ratom]))

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

;; ---------------------------------------------------------------------------
;; Form-3 component state: `state-atom` lazily caches a per-instance RAtom.
;; ---------------------------------------------------------------------------

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
      (r/set-state this {:b 20 :c 3})
      (is (= {:a 1 :b 20 :c 3} (r/state this))
          "second set-state merged: :a survived, :b overwritten, :c added"))))

(deftest replace-state-resets
  (testing "(replace-state this m) REPLACES the whole state map —
            prior keys are dropped, NOT merged (the set-state contrast)"
    (let [this #js {}]
      (r/set-state this {:a 1 :b 2})
      (r/replace-state this {:only :this})
      (is (= {:only :this} (r/state this))
          "replace-state dropped :a and :b — it reset, did not merge"))))

(deftest state-is-per-instance
  (testing "state cells are keyed per component instance — two instances
            do not share state"
    (let [a #js {}
          b #js {}]
      (r/set-state a {:who :a})
      (r/set-state b {:who :b})
      (is (= [{:who :a} {:who :b}] [(r/state a) (r/state b)])))))

;; ---------------------------------------------------------------------------
;; Form-3 argv accessors: the head is the render fn, argv[1] is the props
;; map iff it is a map, and children follow the head and any props map.
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

;; ---------------------------------------------------------------------------
;; `r/reaction` is a macro over its body, as in stock Reagent: a thunk-taking
;; function would run the body eagerly at the call site.
;; ---------------------------------------------------------------------------

(deftest reaction-body-is-deferred-until-deref
  (testing "(r/reaction body) does not run body at construction; the
            first deref runs it once"
    (let [runs (atom 0)
          rx   (r/reaction (do (swap! runs inc) :computed))]
      (is (zero? @runs) "body did not run at construction")
      (is (= :computed @rx) "deref yields the body's value")
      (is (= 1 @runs) "the first deref ran the body exactly once"))))
