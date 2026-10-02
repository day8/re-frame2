(ns re-frame.story.sub-overrides-cljs-test
  "View-state subscription overrides — render-path read + the
  sub-assertion-honesty rule.

  Per `tools/story/spec/017-Testing-Story.md` §View-state subscription
  overrides, a `:sub-overrides` value feeds the RENDER PATH only — never
  app-db, never `compute-sub`. The honesty rule that follows is that a
  `:sub-overrides` value does NOT satisfy `:rf.assert/sub-equals` (which
  evaluates a sub through `compute-sub` against the frame's app-db
  snapshot — see `re-frame.story.assertions/evaluate-sub-equals`).

  That boundary is core's: `re-frame.subs-override-seam-cljs-test`'s
  `override-never-reaches-compute-sub` holds a live override while it calls
  `compute-sub` and reads the REAL app-db value back. The pure-data
  resolver tests live in the host-free `re-frame.story.plan-cljs-test`.
  This file pins the React-context resolver Story publishes to core."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.sub-override-context :as rf.adapter.sub-override-context]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.sub-overrides :as rf.story.sub-overrides]))

(use-fixtures :each
  {:before (fn []
             (rf.registrar/clear-all!)
             (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil)))})

;; ---- the React-context resolver (the LIVE carriage) ----------------------
;;
;; `resolve-sub-override-hit` is the fn published to core under
;; `:subs/resolve-sub-override`. It reads the closest enclosing
;; override-context Provider's value off the shared context object's
;; `_currentValue` (the substrate-portable read React drives during a real
;; render). Node has no React renderer, so these tests set `_currentValue`
;; directly — exactly the field React mutates when a Provider boundary is
;; entered — to exercise the resolver's read + hit/miss contract without a
;; browser. The on-screen surfacing is proven by the real-React e2e
;; (`re-frame.story.sub-overrides-render-dom-cljs-test`).

(defn- with-context-overrides* [m thunk]
  (let [prev (.-_currentValue ^js rf.adapter.sub-override-context/override-context)]
    (set! (.-_currentValue ^js rf.adapter.sub-override-context/override-context) m)
    (try (thunk)
         (finally (set! (.-_currentValue ^js rf.adapter.sub-override-context/override-context) prev)))))

(deftest context-resolver-returns-a-one-element-vector-on-hit-and-nil-on-miss
  (doseq [[label provided query expected]
          [["exact-query-vector hit → [value]"              {[:login/state] :error} [:login/state] [:error]]
           ["non-overridden query → nil (miss)"             {[:login/state] :error} [:login/other] nil]
           ["a nil-valued override is a HIT → [nil]"        {[:x/value] nil}        [:x/value]     [nil]]
           ["no Provider in scope (_currentValue nil) → miss" nil                   [:anything]    nil]]]
    (testing label
      (with-context-overrides* provided
        (fn []
          (is (= expected (rf.story.sub-overrides/resolve-sub-override-hit query))))))))
