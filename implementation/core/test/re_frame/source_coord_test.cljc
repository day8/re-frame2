(ns re-frame.source-coord-test
  "Per rf2-ts1a — `:rf.trace/call-site` on `:rf.error/*` trace events.

  Complement to rf2-3nn8 (`:rf.trace/trigger-handler`). Where trigger-
  handler names the registration site of the in-scope handler, call-site
  names the **invocation line** of the user-facing surface — the
  `(rf/dispatch ...)`, `(rf/subscribe ...)`, `(rf/inject-cofx ...)`, or
  `(rf/dispatch-sync ...)` call that produced (or routed to) the error.

  Q1=C — existing-name macro + `*` fn variant (`dispatch` is the macro;
         `dispatch*` is the fn). The macro stamps the call-site; the
         fn-form skips stamping.
  Q2=A — flat `:rf.trace/call-site {:ns :file :line :column}` as a
         top-level sibling of `:rf.trace/trigger-handler`. Not nested.
  Q3=B — dev-only elision. Stripped from `:advanced` + `goog.DEBUG=
         false` bundles via the same DCE path other dev-only surfaces
         use (elision-probe sentinel covers the bundle-level assertion).

  JVM-side coverage here; CLJS mirror per
  `source_coord_cljs_test.cljs`. Macro-expansion is JVM-side for both
  targets (the `.cljc` macros run on the Clojure side of the compiler
  in either case), so the call-site capture path itself is the same;
  only the runtime delivery differs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces
  "Run `body-fn` with a trace listener attached and return the captured
  events."
  [body-fn]
  (let [seen (atom [])]
    (rf/register-trace-listener! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-trace-listener! ::rec)))
    @seen))

(defn- errors-of
  "Filter captured traces to those whose `:operation` matches the supplied
  operation keyword."
  [evs op]
  (filterv #(and (= :error (:op-type %))
                 (= op     (:operation %)))
           evs))

(defn- assert-call-site-shape
  "The call-site map MUST live at the top level of the event (not
  nested under `:tags`) and carry `:ns` / `:file` / `:line` (column
  may be absent if the macro lost it under `:file` resolution)."
  [ev]
  (let [cs (:rf.trace/call-site ev)]
    (is (some? cs)
        (str "expected :rf.trace/call-site on " (:operation ev)))
    (is (not (contains? (:tags ev) :rf.trace/call-site))
        ":rf.trace/call-site lives at the top level, NOT under :tags")
    (is (symbol? (:ns cs))   ":ns is a symbol")
    (is (string? (:file cs)) ":file is a string")
    (is (integer? (:line cs)) ":line is an integer")))

;; ---- Q1 — dispatch-sync macro stamps; dispatch-sync* fn does NOT ----------

(deftest dispatch-sync-macro-stamps-call-site-on-no-such-handler
  (testing ":rf.error/no-such-handler from dispatch-sync macro carries the call site"
    (let [evs (record-traces
               (fn []
                 ;; dispatch-sync forces synchronous drain so the
                 ;; no-such-handler trace fires before record-traces
                 ;; returns. Plain `dispatch` schedules the drain via
                 ;; next-tick; the test thread would exit first.
                 (rf/dispatch-sync [:rf2-ts1a/no-such-event])))
          [miss] (errors-of evs :rf.error/no-such-handler)]
      (is (some? miss) "no-such-handler trace fired")
      (assert-call-site-shape miss))))

(deftest dispatch-sync-star-fn-omits-call-site-on-no-such-handler
  (testing "the fn-form `dispatch-sync*` does NOT carry a call site"
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync* [:rf2-ts1a/no-such-event])))
          [miss] (errors-of evs :rf.error/no-such-handler)]
      (is (some? miss))
      (is (not (contains? miss :rf.trace/call-site))
          ":rf.trace/call-site omitted on the fn-form path"))))

;; ---- subscribe / subscribe* -----------------------------------------------

(deftest subscribe-macro-stamps-call-site-on-no-such-sub
  (testing ":rf.error/no-such-sub from subscribe macro carries the call site"
    (let [evs (record-traces
               (fn []
                 (rf/subscribe [:rf2-ts1a/no-such-sub])))
          [miss] (errors-of evs :rf.error/no-such-sub)]
      (is (some? miss) "no-such-sub trace fired")
      (assert-call-site-shape miss))))

(deftest subscribe-star-fn-omits-call-site
  (testing "the fn-form `subscribe*` does NOT carry a call site"
    (let [evs (record-traces
               (fn []
                 (rf/subscribe* [:rf2-ts1a/no-such-sub])))
          [miss] (errors-of evs :rf.error/no-such-sub)]
      (is (some? miss))
      (is (not (contains? miss :rf.trace/call-site))
          ":rf.trace/call-site omitted on the fn-form path"))))

;; ---- inject-cofx / inject-cofx* -------------------------------------------

(deftest inject-cofx-public-var-is-macro
  #?(:clj
     (testing "rf/inject-cofx in CLJ is a macro var (not clobbered by runtime alias)"
       (is (true? (:macro (meta #'re-frame.core/inject-cofx)))))))

(deftest inject-cofx-macro-stamps-call-site
  (testing ":rf.error/no-such-cofx carries the call site of inject-cofx (macro form)"
    (rf/reg-event-fx :rf2-ts1a/uses-missing-cofx
                     [(rf/inject-cofx :rf2-ts1a/no-such-cofx)]
                     (fn [_cofx _event] {}))
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync [:rf2-ts1a/uses-missing-cofx])))
          [miss] (errors-of evs :rf.error/no-such-cofx)]
      (is (some? miss))
      (assert-call-site-shape miss))))

(deftest inject-cofx-star-fn-omits-its-own-call-site
  (testing "the fn-form `inject-cofx*` does NOT stamp its own call-site;
   when the enclosing dispatch uses the fn-form too, no call-site rides
   on the resulting :rf.error/no-such-cofx event"
    (rf/reg-event-fx :rf2-ts1a/uses-missing-cofx-fn
                     [(rf/inject-cofx* :rf2-ts1a/no-such-cofx-fn)]
                     (fn [_cofx _event] {}))
    (let [evs (record-traces
               (fn []
                 ;; Both the inject-cofx and the dispatch-sync are fn-form
                 ;; — neither stamps a call-site, so the error event
                 ;; should carry none.
                 (rf/dispatch-sync* [:rf2-ts1a/uses-missing-cofx-fn])))
          [miss] (errors-of evs :rf.error/no-such-cofx)]
      (is (some? miss))
      (is (not (contains? miss :rf.trace/call-site))
          ":rf.trace/call-site omitted on the all-fn-form path"))))

(deftest inject-cofx-star-fn-inherits-dispatchs-call-site
  (testing "when inject-cofx* (fn-form) is reached from a macro-form
   dispatch-sync, the error carries the dispatch's call-site — the
   interceptor closure didn't capture one of its own, so the
   ambient binding wins"
    (rf/reg-event-fx :rf2-ts1a/uses-missing-cofx-mixed
                     [(rf/inject-cofx* :rf2-ts1a/no-such-cofx-mixed)]
                     (fn [_cofx _event] {}))
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync [:rf2-ts1a/uses-missing-cofx-mixed])))
          [miss] (errors-of evs :rf.error/no-such-cofx)]
      (is (some? miss))
      ;; The dispatch-sync macro stamped its own call-site onto the
      ;; envelope; process-event! bound it; the no-such-cofx emit
      ;; inside the inject-cofx*'s :before body picks it up because
      ;; the interceptor didn't pin its own call-site.
      (is (some? (:rf.trace/call-site miss))
          ":rf.trace/call-site present (dispatch's call-site)"))))

;; ---- dispatch-sync / dispatch-sync* --------------------------------------

(deftest dispatch-sync-macro-stamps-call-site-on-handler-exception
  (testing "dispatch-sync macro stamps the call-site through the envelope
   to errors emitted INSIDE the handler chain"
    (rf/reg-event-fx :rf2-ts1a/throws
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync [:rf2-ts1a/throws])))
          [exc] (errors-of evs :rf.error/handler-exception)]
      (is (some? exc))
      (assert-call-site-shape exc))))

(deftest dispatch-sync-star-fn-omits-call-site-on-handler-exception
  (testing "the fn-form `dispatch-sync*` does NOT carry a call site"
    (rf/reg-event-fx :rf2-ts1a/throws-fn
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync* [:rf2-ts1a/throws-fn])))
          [exc] (errors-of evs :rf.error/handler-exception)]
      (is (some? exc))
      (is (not (contains? exc :rf.trace/call-site))
          ":rf.trace/call-site omitted on the dispatch-sync* fn-form path"))))

;; ---- top-level placement (Q2=A) ------------------------------------------

(deftest call-site-rides-at-top-level
  (testing ":rf.trace/call-site lives at the top level, sibling of
   :rf.trace/trigger-handler — NOT nested under :tags"
    (rf/reg-event-fx :rf2-ts1a/top-level
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync [:rf2-ts1a/top-level])))
          [exc] (errors-of evs :rf.error/handler-exception)]
      (is (contains? exc :rf.trace/call-site)
          ":rf.trace/call-site lives at the top level of the event")
      (is (not (contains? (:tags exc) :rf.trace/call-site))
          ":rf.trace/call-site does NOT live under :tags")
      ;; Mirror trigger-handler placement so both pieces sit side-by-side
      ;; in the event shape.
      (is (contains? exc :rf.trace/trigger-handler)
          ":rf.trace/trigger-handler lives alongside :rf.trace/call-site"))))

;; ---- call-site captures the actual source line ----------------------------

(deftest call-site-line-matches-call-site
  (testing "the captured :line is the line of the dispatch macro form
   and :file points at this test file"
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync [:rf2-ts1a/missing])))   ;; ← THIS line
          [miss] (errors-of evs :rf.error/no-such-handler)
          cs     (:rf.trace/call-site miss)]
      (is (some? cs))
      ;; We can't hardcode the line number (file edits would break the
      ;; test); instead assert the line is plausible (positive integer)
      ;; and the file points at this test file.
      (is (integer? (:line cs)))
      (is (pos? (:line cs)))
      (is (string? (:file cs)))
      (is (re-find #"source_coord_test" (:file cs))
          (str ":file should point at this test file — got " (:file cs))))))
