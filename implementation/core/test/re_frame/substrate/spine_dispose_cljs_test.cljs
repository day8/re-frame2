(ns re-frame.substrate.spine-dispose-cljs-test
  "Unit coverage for the substrate-spine's `dispose-adapter!` factory and
  the active-roots tracking (rf2-9fdkb).

  The spine builds a `dispose-adapter!` that drains the active-roots
  set by calling `.unmount` on every tracked React root, and clears
  the warn-once cache and the hiccup-emitter cell. These tests cover
  that contract by sliding fake roots (objects with an `unmount`
  method) into the active-roots cell directly — bypassing
  `react-dom-client/createRoot` so the assertions stay node-runtime
  compatible (no JSDOM, no Playwright).

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.disposable :as rf-disposable]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.spine :as spine]))

(defn- fake-root
  "Build a minimal stand-in for a React root that records every
  `.unmount` call in a per-instance counter atom. The spine only
  exercises the `.unmount` slot on a Root, so this is enough."
  []
  (let [unmount-count (atom 0)
        root          #js {:unmount #(swap! unmount-count inc)}]
    {:root          root
     :unmount-count unmount-count}))

(deftest dispose-drains-active-roots
  (testing "dispose-adapter! calls .unmount on every tracked root and empties the cell"
    (let [active-roots-cell (spine/make-active-roots-cell)
          warn-cache        (spine/make-warn-once-cache)
          emitter-cell      (spine/make-hiccup-emitter-cell)
          dispose-fn        (spine/make-dispose-adapter!
                              {:active-roots-cell active-roots-cell
                               :warn-cache        warn-cache
                               :emitter-cell      emitter-cell})
          fake-a            (fake-root)
          fake-b            (fake-root)]
      (swap! active-roots-cell conj (:root fake-a) (:root fake-b))
      (is (= 2 (count @active-roots-cell))
          "precondition: two active roots tracked")
      (is (zero? @(:unmount-count fake-a)) "fake-a not yet unmounted")
      (is (zero? @(:unmount-count fake-b)) "fake-b not yet unmounted")
      (dispose-fn)
      (is (= 1 @(:unmount-count fake-a))
          "fake-a was unmounted by dispose-adapter!")
      (is (= 1 @(:unmount-count fake-b))
          "fake-b was unmounted by dispose-adapter!")
      (is (empty? @active-roots-cell)
          "active-roots cell drained to empty after dispose"))))

(deftest dispose-swallows-per-root-unmount-throws
  (testing "one misbehaving root's unmount throw does not strand the rest of the drain"
    (let [active-roots-cell (spine/make-active-roots-cell)
          warn-cache        (spine/make-warn-once-cache)
          emitter-cell      (spine/make-hiccup-emitter-cell)
          dispose-fn        (spine/make-dispose-adapter!
                              {:active-roots-cell active-roots-cell
                               :warn-cache        warn-cache
                               :emitter-cell      emitter-cell})
          good-1            (fake-root)
          good-2            (fake-root)
          bad               #js {:unmount #(throw (js/Error. "boom"))}]
      ;; Insertion order is not preserved in a set; the swallowed-throw
      ;; guarantee is that BOTH good roots' unmount fires regardless of
      ;; the bad one's traversal position.
      (swap! active-roots-cell conj (:root good-1) bad (:root good-2))
      (dispose-fn)
      (is (= 1 @(:unmount-count good-1))
          "good-1 still unmounted despite a sibling unmount throw")
      (is (= 1 @(:unmount-count good-2))
          "good-2 still unmounted despite a sibling unmount throw")
      (is (empty? @active-roots-cell)
          "active-roots cell drained even when an unmount threw"))))

(deftest dispose-clears-warn-cache-and-emitter
  (testing "dispose-adapter! also empties the warn-once cache and the hiccup-emitter cell"
    (let [active-roots-cell (spine/make-active-roots-cell)
          warn-cache        (spine/make-warn-once-cache)
          emitter-cell      (spine/make-hiccup-emitter-cell)
          dispose-fn        (spine/make-dispose-adapter!
                              {:active-roots-cell active-roots-cell
                               :warn-cache        warn-cache
                               :emitter-cell      emitter-cell})]
      (swap! warn-cache conj :some.ns/some-id)
      (reset! emitter-cell (fn fake-emit [_ _] "<html/>"))
      (is (= #{:some.ns/some-id} @warn-cache)
          "precondition: warn-cache holds a seen id")
      (is (some? @emitter-cell)
          "precondition: emitter-cell holds a fn")
      (dispose-fn)
      (is (empty? @warn-cache)
          "warn-cache cleared so a fresh install does not inherit stale warn-once state")
      (is (nil? @emitter-cell)
          "hiccup-emitter cell cleared so a fresh install starts from no emitter"))))

;; ---- dispose-frame-sub-caches! (rf2-jcjul) -------------------------------
;;
;; The shared sub-cache walk lifted out of the Reagent adapter into the
;; spine so all three React-shaped adapters (Reagent / reagent-slim /
;; UIx / Helix) drive the same implementation of Spec 006 §Adapter
;; disposal lifecycle MUST (1): cancel all in-flight reactive
;; subscriptions.
;;
;; These tests exercise the helper in isolation by populating
;; `frame/frames` directly with fake sub-cache entries — no adapter
;; install, no real Reactions, no JSDOM. Each fake `:reaction` is a
;; reified `rf-disposable/IDisposable` that records dispose calls; an
;; integration test in `re-frame.dispose-adapter-sub-cache-walk-cljs-test`
;; pins the through-the-Reagent-adapter shape.

(defn- fake-reaction
  "Build a stand-in for a cached Reaction that records every
  `-dispose` call in a per-instance counter atom. The walk only
  exercises the `IDisposable` `-dispose` slot, so this is enough."
  []
  (let [dispose-count (atom 0)]
    {:reaction      (reify rf-disposable/IDisposable
                      (-dispose [_]
                        (swap! dispose-count inc))
                      (-add-on-dispose [_ _f] nil))
     :dispose-count dispose-count}))

(defn- fake-frame
  "Build a frame-record-shaped map carrying a `:sub-cache` atom seeded
  with the supplied `cache-map`. The walk only reads `:sub-cache` off
  the frame record, so this is enough."
  [cache-map]
  {:sub-cache (atom cache-map)})

(defn frames-fixture
  "Save and restore `frame/frames` + the `:adapter/dispose!` late-bind
  hook so each test gets a clean slate and any other suite running in
  the same JS heap sees the pre-existing globals."
  [test-fn]
  (let [saved-frames @frame/frames
        saved-hook   (late-bind/get-fn-cached :adapter/dispose!)]
    (reset! frame/frames {})
    ;; Install a dispose hook that calls rf-disposable's protocol fn so
    ;; the walk's `interop/dispose!` invocation actually fires the
    ;; recording reify. Without this seed `interop/dispose!` no-ops
    ;; (the hook is unbound in a cold-start test) and we can't tell
    ;; the walk from a stub.
    (late-bind/set-fn! :adapter/dispose! rf-disposable/-dispose)
    (try (test-fn)
         (finally
           (reset! frame/frames saved-frames)
           (when saved-hook
             (late-bind/set-fn! :adapter/dispose! saved-hook))))))

(use-fixtures :each frames-fixture)

(deftest dispose-frame-sub-caches-walks-every-live-frame
  (testing "every cached :reaction across every live frame is disposed
  and every frame's sub-cache atom is reset to {}"
    (let [r-a-x  (fake-reaction)
          r-a-y  (fake-reaction)
          r-b    (fake-reaction)
          frm-a  (fake-frame {[:sub :x] (select-keys r-a-x [:reaction])
                              [:sub :y] (select-keys r-a-y [:reaction])})
          frm-b  (fake-frame {[:sub :z] (select-keys r-b   [:reaction])})]
      (reset! frame/frames {:walk/a frm-a :walk/b frm-b})
      ;; Preconditions: cache atoms populated, no dispose yet.
      (is (= 2 (count @(:sub-cache frm-a))))
      (is (= 1 (count @(:sub-cache frm-b))))
      (is (zero? @(:dispose-count r-a-x)))
      (is (zero? @(:dispose-count r-a-y)))
      (is (zero? @(:dispose-count r-b)))

      (spine/dispose-frame-sub-caches!)

      (is (= 1 @(:dispose-count r-a-x))
          "walk/a [:sub :x]'s reaction was disposed")
      (is (= 1 @(:dispose-count r-a-y))
          "walk/a [:sub :y]'s reaction was disposed")
      (is (= 1 @(:dispose-count r-b))
          "walk/b [:sub :z]'s reaction was disposed")
      (is (= {} @(:sub-cache frm-a))
          "walk/a's sub-cache atom was reset to {}")
      (is (= {} @(:sub-cache frm-b))
          "walk/b's sub-cache atom was reset to {}"))))

(deftest dispose-frame-sub-caches-cancels-pending-dispose-timers
  (testing "any :pending-dispose timer handle on a sub-cache entry is
  clear-timeout!ed before the entry's reaction is disposed — otherwise
  a fired timer would touch a torn-down adapter slot"
    (let [timer-fired? (atom false)
          ;; A real timer scheduled far enough in the future that the
          ;; test's synchronous walk happens before fire. If the walk
          ;; correctly calls clear-timeout! the flag stays false.
          handle (js/setTimeout #(reset! timer-fired? true) 5000)
          r      (fake-reaction)
          frm    (fake-frame {[:sub :x] (assoc r :pending-dispose handle)})]
      (reset! frame/frames {:walk/a frm})

      (spine/dispose-frame-sub-caches!)

      (is (= 1 @(:dispose-count r))
          "the reaction was still disposed")
      (is (false? @timer-fired?)
          "the pending-dispose timer was cancelled (handle was clear-timeout!ed)"))))

(deftest dispose-frame-sub-caches-is-best-effort
  (testing "a throwing per-entry dispose does NOT abort the rest of the
  walk — every other cached reaction in the same cache AND every cache
  in subsequent frames still gets disposed and cleared"
    (let [good-1 (fake-reaction)
          good-2 (fake-reaction)
          ;; Poison entry: an object that doesn't satisfy IDisposable so
          ;; the seeded `:adapter/dispose!` hook (rf-disposable/-dispose)
          ;; throws when invoked on it.
          poison {:reaction (js-obj "not" "a reaction")}
          frm-a  (fake-frame {[:sub :good-1] (select-keys good-1 [:reaction])
                              [:sub :poison] poison})
          frm-b  (fake-frame {[:sub :good-2] (select-keys good-2 [:reaction])})]
      (reset! frame/frames {:walk/a frm-a :walk/b frm-b})

      (spine/dispose-frame-sub-caches!)

      (is (= 1 @(:dispose-count good-1))
          "good-1 (same cache as the poison) was disposed despite the sibling throw")
      (is (= 1 @(:dispose-count good-2))
          "good-2 (different frame) was disposed despite the poison entry")
      (is (= {} @(:sub-cache frm-a))
          "walk/a's cache was still cleared despite the throw")
      (is (= {} @(:sub-cache frm-b))
          "walk/b's cache was still cleared after the throwing walk/a entry"))))

(deftest dispose-frame-sub-caches-tolerates-empty-frames-registry
  (testing "dispose-frame-sub-caches! on an empty frames registry is a no-op (no throw)"
    (reset! frame/frames {})
    (is (nil? (spine/dispose-frame-sub-caches!))
        "returns nil with no live frames")))

(deftest dispose-frame-sub-caches-tolerates-frame-without-sub-cache
  (testing "a frame record lacking the :sub-cache key is skipped (no throw)"
    (reset! frame/frames {:walk/no-cache {:other-key :value}})
    (is (nil? (spine/dispose-frame-sub-caches!))
        "returns nil; the cacheless frame is skipped")))

(deftest make-dispose-adapter-invokes-sub-cache-walk
  (testing "the spine's `make-dispose-adapter!` factory drives the
  sub-cache walk as part of its build of MUST-1 + MUST-2 + MUST-3.
  Pinning this through the factory protects the rf2-jcjul lockstep:
  the UIx and Helix adapters wire their dispose-adapter! slot through
  this factory only — if the factory ever stopped invoking the walk,
  those adapters' dispose path would silently regress."
    (let [r          (fake-reaction)
          frm        (fake-frame {[:sub :x] (select-keys r [:reaction])})
          _          (reset! frame/frames {:walk/a frm})
          active     (spine/make-active-roots-cell)
          warn-cache (spine/make-warn-once-cache)
          emitter    (spine/make-hiccup-emitter-cell)
          dispose-fn (spine/make-dispose-adapter!
                       {:active-roots-cell active
                        :warn-cache        warn-cache
                        :emitter-cell      emitter})]
      (dispose-fn)
      (is (= 1 @(:dispose-count r))
          "factory-built dispose-adapter! reached the cached reaction")
      (is (= {} @(:sub-cache frm))
          "factory-built dispose-adapter! cleared the sub-cache atom"))))

(deftest unmount-thunk-removes-root-from-active-set
  (testing "the unmount thunk returned by `render` removes its root from the active-roots cell"
    ;; Build a render fn parameterised on a fake `.unmount`-supporting
    ;; root factory. The spine's `make-render` calls createRoot, which
    ;; requires a real DOM — so for this isolated test we simulate the
    ;; render path by mounting the root directly into the cell and
    ;; building an unmount thunk shaped like the one render returns.
    (let [active-roots-cell (spine/make-active-roots-cell)
          fake-a            (fake-root)
          fake-b            (fake-root)]
      (swap! active-roots-cell conj (:root fake-a))
      (swap! active-roots-cell conj (:root fake-b))
      ;; Mirror the render-fn's unmount-thunk shape.
      (let [unmount-a (fn []
                        (swap! active-roots-cell disj (:root fake-a))
                        (.unmount (:root fake-a)))]
        (unmount-a)
        (is (= #{(:root fake-b)} @active-roots-cell)
            "only fake-b remains tracked after fake-a's unmount thunk fired")
        (is (= 1 @(:unmount-count fake-a))
            "fake-a's actual unmount was called by the thunk")
        (is (zero? @(:unmount-count fake-b))
            "fake-b was not touched")))))
