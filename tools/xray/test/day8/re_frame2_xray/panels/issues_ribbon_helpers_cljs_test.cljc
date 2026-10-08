(ns day8.re-frame2-xray.panels.issues-ribbon-helpers-cljs-test
  "Pure-data tests for Xray's issue projection — the algebra behind the
  `:rf.xray/issues-ribbon` signal, whose reader (the auto-open-on-error
  watcher) counts its `:issues`, and behind the L2 pink-wash predicate.

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `schema_violation_timeline_helpers_cljs_
  test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **issue-event?** — classifies trace events by severity: the
       `:error` and `:warning` op-types are issues; every other op-type
       (`:info` among them) is not.
    2. **project-feed** — the focused epoch's `:trace-events` project to
       the issue subset. The resolver feeding it is pinned in
       `shared/focus_resolver_cljs_test`."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing]])
            [day8.re-frame2-xray.panels.issues-ribbon-helpers :as h]))

;; ---- fixture builder ----------------------------------------------------

(defn- trace-ev
  "A Spec 009-shaped trace event."
  [id op-type operation]
  {:id        id
   :op-type   op-type
   :operation operation
   :time      1000
   :tags      {}})

;; ---- issue-event? -------------------------------------------------------

(deftest issue-event?-classification
  (testing "the :error and :warning op-types are issues; :info is ACTIVITY —
            the runtime's success-path lifecycle rows, `:rf.http/issued`
            on every managed request among them — never an issue"
    (are [ev expected] (= expected (h/issue-event? ev))
      (trace-ev 1 :error   :rf.error/handler-exception) true
      (trace-ev 2 :warning :rf.warning/recoverable)     true
      (trace-ev 3 :info    :rf.http/issued)             false)))

;; ---- project-feed -------------------------------------------------------

(deftest project-feed-renders-issues-from-trace-events
  (testing "the focused epoch's :trace-events feed the projection;
            non-issue traces — the `:info` activity row among them — are
            silently dropped"
    (let [feed (h/project-feed {:epoch-id     42
                                :trace-events [(trace-ev 1 :error    :rf.error/handler-exception)
                                               (trace-ev 2 :rf.event :rf.event/dispatched)
                                               (trace-ev 3 :warning  :rf.warning/recoverable)
                                               (trace-ev 4 :info     :rf.info/note)]}
                               :focused)]
      (is (= [1 3] (sort (map :id (:issues feed))))))))
