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
            [re-frame.disposable :as rf.disposable]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.spine :as rf.substrate.spine]))

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
    (let [active-roots-cell (rf.substrate.spine/make-active-roots-cell)
          warn-cache        (rf.substrate.spine/make-warn-once-cache)
          emitter-cell      (rf.substrate.spine/make-hiccup-emitter-cell)
          dispose-fn        (rf.substrate.spine/make-dispose-adapter!
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

(deftest dispose-drains-every-root-then-rethrows-the-unmount-throw
  (testing "one misbehaving root's unmount throw does not strand the rest of
  the drain, and the identical failure is rethrown once the drain finished
  (rf2-ss8x — Spec 006 §Adapter disposal lifecycle: attempt all remaining
  cleanup, then preserve and rethrow the first failure)"
    (let [active-roots-cell (rf.substrate.spine/make-active-roots-cell)
          warn-cache        (rf.substrate.spine/make-warn-once-cache)
          emitter-cell      (rf.substrate.spine/make-hiccup-emitter-cell)
          driver-root-cell  (atom nil)
          set-tick-ref      (atom :stale-setter)
          dispose-fn        (rf.substrate.spine/make-dispose-adapter!
                              {:active-roots-cell             active-roots-cell
                               :warn-cache                    warn-cache
                               :emitter-cell                  emitter-cell
                               :after-render-driver-root-cell driver-root-cell
                               :after-render-set-tick-ref     set-tick-ref})
          good-1            (fake-root)
          good-2            (fake-root)
          sentinel          (js/Error. "boom")
          bad               #js {:unmount #(throw sentinel)}]
      ;; Insertion order is not preserved in a set; the drain-everything
      ;; guarantee is that BOTH good roots' unmount fires regardless of
      ;; the bad one's traversal position.
      (swap! active-roots-cell conj (:root good-1) bad (:root good-2))
      (reset! warn-cache #{:some-stale-warn-key})
      (let [thrown (try (dispose-fn)
                        ::returned-normally
                        (catch :default e e))]
        (is (= 1 @(:unmount-count good-1))
            "good-1 still unmounted despite a sibling unmount throw")
        (is (= 1 @(:unmount-count good-2))
            "good-2 still unmounted despite a sibling unmount throw")
        (is (empty? @active-roots-cell)
            "active-roots cell drained even when an unmount threw")
        (is (identical? sentinel thrown)
            "the identical unmount failure was rethrown after the drain — a
            swallowed throw here is what let rf/destroy-adapter! report success
            over a failed teardown")
        ;; The React-hook spine's extra teardown runs in a `finally`, so the
        ;; rethrow cannot strand the warn cache or the after-render driver
        ;; root — trading MUST (2)'s leak for MUST (2)'s report.
        (is (empty? @warn-cache)
            "warn-once cache still cleared past the rethrow")
        (is (nil? @set-tick-ref)
            "the after-render set-tick slot still cleared past the rethrow")))))

(deftest dispose-clears-warn-cache-and-emitter
  (testing "dispose-adapter! also empties the warn-once cache and the hiccup-emitter cell"
    (let [active-roots-cell (rf.substrate.spine/make-active-roots-cell)
          warn-cache        (rf.substrate.spine/make-warn-once-cache)
          emitter-cell      (rf.substrate.spine/make-hiccup-emitter-cell)
          dispose-fn        (rf.substrate.spine/make-dispose-adapter!
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
;; UIx) drive the same implementation of Spec 006 §Adapter
;; disposal lifecycle MUST (1): cancel all in-flight reactive
;; subscriptions.
;;
;; These tests exercise the helper in isolation by populating
;; `rf.frame/frames` directly with fake sub-cache entries — no adapter
;; install, no real Reactions, no JSDOM. Each fake `:reaction` is a
;; reified `rf.disposable/IDisposable` that records dispose calls; an
;; integration test in `re-frame.dispose-adapter-sub-cache-walk-cljs-test`
;; pins the through-the-Reagent-adapter shape.

(defn- fake-reaction
  "Build a stand-in for a cached Reaction that records every
  `-dispose` call in a per-instance counter atom. The walk only
  exercises the `IDisposable` `-dispose` slot, so this is enough."
  []
  (let [dispose-count (atom 0)]
    {:reaction      (reify rf.disposable/IDisposable
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
  "Save and restore `rf.frame/frames` + the `:adapter/dispose!` late-bind
  hook so each test gets a clean slate and any other suite running in
  the same JS heap sees the pre-existing globals."
  [test-fn]
  (let [saved-frames @rf.frame/frames
        saved-hook   (rf.late-bind/get-fn-cached :adapter/dispose!)]
    (reset! rf.frame/frames {})
    ;; Install a dispose hook that calls rf-disposable's protocol fn so
    ;; the walk's `interop/dispose!` invocation actually fires the
    ;; recording reify. Without this seed `interop/dispose!` no-ops
    ;; (the hook is unbound in a cold-start test) and we can't tell
    ;; the walk from a stub.
    (rf.late-bind/set-fn! :adapter/dispose! rf.disposable/-dispose)
    (try (test-fn)
         (finally
           (reset! rf.frame/frames saved-frames)
           (when saved-hook
             (rf.late-bind/set-fn! :adapter/dispose! saved-hook))))))

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
      (reset! rf.frame/frames {:walk/a frm-a :walk/b frm-b})
      ;; Preconditions: cache atoms populated, no dispose yet.
      (is (= 2 (count @(:sub-cache frm-a))))
      (is (= 1 (count @(:sub-cache frm-b))))
      (is (zero? @(:dispose-count r-a-x)))
      (is (zero? @(:dispose-count r-a-y)))
      (is (zero? @(:dispose-count r-b)))

      (rf.substrate.spine/dispose-frame-sub-caches!)

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

(deftest dispose-frame-sub-caches-is-best-effort
  (testing "a throwing per-entry dispose does NOT abort the rest of the
  walk — every other cached reaction in the same cache AND every cache
  in subsequent frames still gets disposed and cleared"
    (let [good-1 (fake-reaction)
          good-2 (fake-reaction)
          ;; Poison entry: an object that doesn't satisfy IDisposable so
          ;; the seeded `:adapter/dispose!` hook (rf.disposable/-dispose)
          ;; throws when invoked on it.
          poison {:reaction (js-obj "not" "a reaction")}
          frm-a  (fake-frame {[:sub :good-1] (select-keys good-1 [:reaction])
                              [:sub :poison] poison})
          frm-b  (fake-frame {[:sub :good-2] (select-keys good-2 [:reaction])})]
      (reset! rf.frame/frames {:walk/a frm-a :walk/b frm-b})

      (rf.substrate.spine/dispose-frame-sub-caches!)

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
    (reset! rf.frame/frames {})
    (is (nil? (rf.substrate.spine/dispose-frame-sub-caches!))
        "returns nil with no live frames")))

(deftest dispose-frame-sub-caches-tolerates-frame-without-sub-cache
  (testing "a frame record lacking the :sub-cache key is skipped (no throw)"
    (reset! rf.frame/frames {:walk/no-cache {:other-key :value}})
    (is (nil? (rf.substrate.spine/dispose-frame-sub-caches!))
        "returns nil; the cacheless frame is skipped")))

(deftest make-dispose-adapter-invokes-sub-cache-walk
  (testing "the spine's `make-dispose-adapter!` factory drives the
  sub-cache walk as part of its build of MUST-1 + MUST-2 + MUST-3.
  Pinning this through the factory protects the rf2-jcjul lockstep:
  the UIx adapter wires its dispose-adapter! slot through
  this factory only — if the factory ever stopped invoking the walk,
  that adapter's dispose path would silently regress."
    (let [r          (fake-reaction)
          frm        (fake-frame {[:sub :x] (select-keys r [:reaction])})
          _          (reset! rf.frame/frames {:walk/a frm})
          active     (rf.substrate.spine/make-active-roots-cell)
          warn-cache (rf.substrate.spine/make-warn-once-cache)
          emitter    (rf.substrate.spine/make-hiccup-emitter-cell)
          dispose-fn (rf.substrate.spine/make-dispose-adapter!
                       {:active-roots-cell active
                        :warn-cache        warn-cache
                        :emitter-cell      emitter})]
      (dispose-fn)
      (is (= 1 @(:dispose-count r))
          "factory-built dispose-adapter! reached the cached reaction")
      (is (= {} @(:sub-cache frm))
          "factory-built dispose-adapter! cleared the sub-cache atom"))))

;; ---- spine derived-value -dispose idempotence + re-entrancy (rf2-1bzlai) --
;;
;; The earlier tests above drive the cache-walk through reified toy
;; disposables. These pin the ACTUAL spine-produced derived value's
;; `rf.disposable/IDisposable` `-dispose` — the concrete reify returned by
;; `make-derived-value-fn` — against repeated and re-entrant disposal. The
;; bug rf2-1bzlai: the impl fired `@on-dispose-fns` and only then cleared
;; the vector, with no disposed guard, so a second `-dispose` re-fired the
;; whole callback set and a callback that re-entered `-dispose` could recurse
;; / double-fire. A real spine derived value is buildable node-side with no
;; DOM: `make-derived-value-fn` takes `[gensym-prefix scheduler]` and returns
;; the `make-derived-value` fn `[source-containers compute-fn]`.

(defn- spine-derived-value
  "Build one real spine-produced derived value over a fresh source
  container and return it together with the source so a test can dispose
  it directly. The compute is identity-of-first-source; tests here only
  exercise the disposal protocol, not recompute."
  []
  (let [scheduler (rf.substrate.spine/make-scheduler)
        make-dv   (rf.substrate.spine/make-derived-value-fn "rf2-1bzlai-test-" scheduler)
        src       (rf.substrate.spine/make-state-container 0)
        dv        (make-dv [src] (fn [vs] (first vs)))]
    {:dv dv :src src}))

(deftest spine-derived-value-dispose-is-idempotent
  (testing "a second -dispose on a spine-produced derived value does NOT
  re-fire its on-dispose callbacks (idempotent per the IDisposable contract)"
    (let [{:keys [dv]} (spine-derived-value)
          fire-log     (atom [])]
      (rf.disposable/-add-on-dispose dv #(swap! fire-log conj :cb-1))
      (rf.disposable/-add-on-dispose dv #(swap! fire-log conj :cb-2))
      (rf.disposable/-dispose dv)
      (is (= [:cb-1 :cb-2] @fire-log)
          "first -dispose fired both callbacks in registration order")
      (rf.disposable/-dispose dv)
      (is (= [:cb-1 :cb-2] @fire-log)
          "second -dispose did NOT re-fire the callbacks (idempotent)"))))

(deftest spine-derived-value-dispose-is-re-entrant-safe
  (testing "an on-dispose callback that re-enters -dispose on the same
  spine derived value does not recurse or double-fire the callback set"
    (let [{:keys [dv]} (spine-derived-value)
          fire-log     (atom [])]
      ;; This callback defensively re-disposes the same object — the exact
      ;; re-entrant shape the bead calls out. With the guard flipped first
      ;; and callbacks snapshot-and-cleared, the re-entrant call is a no-op.
      (rf.disposable/-add-on-dispose dv
        (fn []
          (swap! fire-log conj :re-entrant-cb)
          (rf.disposable/-dispose dv)))
      (rf.disposable/-add-on-dispose dv #(swap! fire-log conj :after-cb))
      (rf.disposable/-dispose dv)
      (is (= [:re-entrant-cb :after-cb] @fire-log)
          "each callback fired exactly once despite the re-entrant -dispose; no recursion, no double-fire"))))

(deftest unmount-thunk-removes-root-from-active-set
  (testing "the unmount thunk returned by `render` removes its root from the active-roots cell"
    ;; Build a render fn parameterised on a fake `.unmount`-supporting
    ;; root factory. The spine's `make-render` calls createRoot, which
    ;; requires a real DOM — so for this isolated test we simulate the
    ;; render path by mounting the root directly into the cell and
    ;; building an unmount thunk shaped like the one render returns.
    (let [active-roots-cell (rf.substrate.spine/make-active-roots-cell)
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
