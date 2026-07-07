(ns re-frame.source-coord-test
  "Per rf2-ts1a — `:rf.trace/call-site` on `:rf.error/*` trace events.

  Complement to rf2-3nn8 (`:rf.trace/trigger-handler`). Where trigger-
  handler names the registration site of the in-scope handler, call-site
  names the **invocation line** of the user-facing surface — the
  `(rf/dispatch ...)`, `(rf/subscribe ...)`, or `(rf/dispatch-sync ...)`
  call that produced (or routed to) the error.

  Q1=C — macro vs owning-ns fn (`dispatch` is the macro; the fn-form is
         `re-frame.router/dispatch!` — rf2-m90brg retired the
         `re-frame.core/dispatch*` facade twin the macro used to target).
         The macro stamps the call-site; the fn-form skips stamping.
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
            [re-frame.router :as router]
            [re-frame.subs :as subs]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces
  "Run `body-fn` with a trace listener attached and return the captured
  events."
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! :trace ::rec)))
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

;; ---- Q1 — dispatch-sync macro stamps; router/dispatch-sync! fn does NOT --

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

(deftest dispatch-sync-owning-fn-omits-call-site-on-no-such-handler
  (testing "the owning-ns fn-form `re-frame.router/dispatch-sync!` does NOT
   carry a call site (the macro is the ONLY stamping surface)"
    (let [evs (record-traces
               (fn []
                 (router/dispatch-sync! [:rf2-ts1a/no-such-event])))
          [miss] (errors-of evs :rf.error/no-such-handler)]
      (is (some? miss))
      (is (not (contains? miss :rf.trace/call-site))
          ":rf.trace/call-site omitted on the fn-form path"))))

;; ---- subscribe / re-frame.subs/subscribe -----------------------------------

(deftest subscribe-macro-stamps-call-site-on-no-such-sub
  (testing ":rf.error/no-such-sub from subscribe macro carries the call site"
    (let [evs (record-traces
               (fn []
                 (rf/subscribe [:rf2-ts1a/no-such-sub])))
          [miss] (errors-of evs :rf.error/no-such-sub)]
      (is (some? miss) "no-such-sub trace fired")
      (assert-call-site-shape miss))))

(deftest subscribe-owning-fn-omits-call-site
  (testing "the owning-ns fn-form `re-frame.subs/subscribe` does NOT carry a
   call site (the macro is the ONLY stamping surface)"
    (let [evs (record-traces
               (fn []
                 (subs/subscribe [:rf2-ts1a/no-such-sub])))
          [miss] (errors-of evs :rf.error/no-such-sub)]
      (is (some? miss))
      (is (not (contains? miss :rf.trace/call-site))
          ":rf.trace/call-site omitted on the fn-form path"))))

;; ---- inject-cofx (removed in EP-0017 slice A.3; off the facade rf2-w9xyx1) ---
;;
;; `inject-cofx` is removed (no alias) — it no longer builds an interceptor or
;; emits `:rf.error/no-such-cofx`, so the former call-site-stamping deftests
;; (`inject-cofx-macro-stamps-call-site`, the two `inject-cofx*` variants) are
;; retired. rf2-w9xyx1 then removed `inject-cofx` / `inject-cofx*` from the
;; public `re-frame.core` facade entirely (no var, no manifest row), so there
;; is no longer a public macro var to assert on either. The removal hard error
;; (`:rf.error/inject-cofx-removed`) survives on the private thrower
;; `re-frame.cofx/inject-cofx` and is pinned in `re-frame.cofx-test`. The
;; dispatch / subscribe call-site stamping (above) is unaffected.

;; ---- dispatch-sync / router/dispatch-sync! ---------------------------------

(deftest dispatch-sync-macro-stamps-call-site-on-handler-exception
  (testing "dispatch-sync macro stamps the call-site through the envelope
   to errors emitted INSIDE the handler chain"
    (rf/reg-event :rf2-ts1a/throws
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [evs (record-traces
               (fn []
                 (rf/dispatch-sync [:rf2-ts1a/throws])))
          [exc] (errors-of evs :rf.error/handler-exception)]
      (is (some? exc))
      (assert-call-site-shape exc))))

(deftest dispatch-sync-owning-fn-omits-call-site-on-handler-exception
  (testing "the owning-ns fn-form `re-frame.router/dispatch-sync!` does NOT
   carry a call site"
    (rf/reg-event :rf2-ts1a/throws-fn
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [evs (record-traces
               (fn []
                 (router/dispatch-sync! [:rf2-ts1a/throws-fn])))
          [exc] (errors-of evs :rf.error/handler-exception)]
      (is (some? exc))
      (is (not (contains? exc :rf.trace/call-site))
          ":rf.trace/call-site omitted on the fn-form path"))))

;; ---- top-level placement (Q2=A) ------------------------------------------

(deftest call-site-rides-at-top-level
  (testing ":rf.trace/call-site lives at the top level, sibling of
   :rf.trace/trigger-handler — NOT nested under :tags"
    (rf/reg-event :rf2-ts1a/top-level
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
