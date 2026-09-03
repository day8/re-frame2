(ns re-frame.source-coord-jvm-test
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
  only the runtime delivery differs.

  The `-jvm-test` suffix is deliberate, not the rf2-dn6v7 omission. Shadow's
  `:node-test` build selects on `cljs-test$`; this file is the JVM half of a
  DELIBERATE pair whose CLJS half is the `.cljs` mirror named above, so the
  explicit lane suffix records that skipping the CLJS lane is the intent and
  not an oversight (rf2-lgozq).

  ## Posture split (rf2-d2841)

  Q3 above already says it: `:rf.trace/call-site` is DEV-ONLY BY DESIGN. The
  macro expansion is `(if rf.interop/debug-enabled? <stamped> <plain>)` with the
  gate OUTERMOST (`core-call-site-macros/gate`), so under
  `-Dre-frame.debug=false` the production branch never builds the coord map at
  all — and the trace event that would have carried it is not emitted either.
  Every deftest here failed under `scripts/test-core-prod-gate.sh` for that
  reason, and guarding the file wholesale would have taken a namespace that
  EXECUTES NOTHING off the roster and reported it green.

  So the file grew an ALWAYS-ON arm that is the exact production counterpart of
  the claim: `:rf.error/no-such-handler` / `:rf.error/no-such-sub` /
  `:rf.error/handler-exception` are PROMOTED categories, so each still fans a
  tight record to the corpus-wide `:errors` registry under the gate. Every case
  now asserts that record fired AND that it carries no `:rf.trace/call-site` —
  the dev-only contract, stated on a live record instead of on nothing.
  Alongside it: the macro / fn-form distinction, which is the whole subject of
  Q1, has NO effect on that record. Production observability does not depend on
  which spelling the caller reached for; only the dev jump-to-source does.

  FOUR VACUOUS PASSES CAME OFF (rf2-d2841 class 4). The three
  `…-omits-call-site` deftests and `call-site-rides-at-top-level` each certify
  an absence with `(not (contains? … :rf.trace/call-site))` against the nil an
  empty trace ring yields — `(contains? nil k)` is false for every k, so under
  the gate they certified the fn-form path by never looking at it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.router :as rf.router]
            [re-frame.subs :as rf.subs]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- errors-of
  "Filter captured traces to those whose `:operation` matches the supplied
  operation keyword."
  [evs op]
  (filterv #(and (= :error (:op-type %))
                 (= op     (:operation %)))
           evs))

(defn- record-both
  "ALWAYS-ON (rf2-d2841) capture: run `body-fn` with BOTH a dev-trace listener
  and an always-on `:errors`-stream listener attached, returning
  `{:traces [...] :errors [...]}`. The `:errors` half is the corpus-wide
  `error-emit` registry, which is not gated by `rf.interop/debug-enabled?`."
  [body-fn]
  (let [traces (atom [])
        errors (atom [])]
    (rf/register-listener! :trace  ::rec (fn [ev]  (swap! traces conj ev)))
    (rf/register-listener! :errors ::err (fn [rec] (swap! errors conj rec)))
    (try (body-fn)
         (finally
           (rf/unregister-listener! :trace  ::rec)
           (rf/unregister-listener! :errors ::err)))
    {:traces @traces :errors @errors}))

(defn- error-of
  "The first always-on error record whose `:error` is `kw`."
  [recs kw]
  (first (filterv #(= kw (:error %)) recs)))

(defn- assert-production-record
  "ALWAYS-ON (rf2-d2841): the promoted category `kw` fanned a tight record to
  the corpus-wide `:errors` registry, and that record carries NO
  `:rf.trace/call-site` — the dev-only contract of Q3, asserted against a
  record proven to exist rather than against an empty stream."
  [recs kw]
  (let [rec (error-of recs kw)]
    (is (some? rec)
        (str "the always-on error record for " kw " fired"))
    (is (not (contains? rec :rf.trace/call-site))
        (str kw "'s production record carries no :rf.trace/call-site"))
    rec))

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

;; ---- Q1 — dispatch-sync macro stamps; rf.router/dispatch-sync! fn does NOT --

(deftest dispatch-sync-macro-stamps-call-site-on-no-such-handler
  (testing ":rf.error/no-such-handler from dispatch-sync macro carries the call site"
    (let [{:keys [traces errors]}
          (record-both
            (fn []
              ;; dispatch-sync forces synchronous drain so the
              ;; no-such-handler trace fires before record-both
              ;; returns. Plain `dispatch` schedules the drain via
              ;; next-tick; the test thread would exit first.
              (rf/dispatch-sync [:rf2-ts1a/no-such-event])))
          [miss] (errors-of traces :rf.error/no-such-handler)]
      (assert-production-record errors :rf.error/no-such-handler)
      (when rf.interop/debug-enabled?
        (is (some? miss) "no-such-handler trace fired")
        (assert-call-site-shape miss)))))

(deftest dispatch-sync-owning-fn-omits-call-site-on-no-such-handler
  (testing "the owning-ns fn-form `re-frame.router/dispatch-sync!` does NOT
   carry a call site (the macro is the ONLY stamping surface)"
    (let [{:keys [traces errors]}
          (record-both
            (fn []
              (rf.router/dispatch-sync! [:rf2-ts1a/no-such-event])))
          [miss] (errors-of traces :rf.error/no-such-handler)]
      ;; ALWAYS-ON (rf2-d2841): the fn-form reaches the SAME production record
      ;; the macro form does. Q1's macro/fn split is a dev jump-to-source
      ;; distinction only; off-box observability is identical either way.
      (assert-production-record errors :rf.error/no-such-handler)
      ;; rf2-d2841 -- class-4 vacuous under the gate: `miss` is nil there, and
      ;; `(contains? nil k)` is false for every k.
      (when rf.interop/debug-enabled?
        (is (some? miss))
        (is (not (contains? miss :rf.trace/call-site))
            ":rf.trace/call-site omitted on the fn-form path")))))

;; ---- subscribe / re-frame.subs/subscribe -----------------------------------

(deftest subscribe-macro-stamps-call-site-on-no-such-sub
  (testing ":rf.error/no-such-sub from subscribe macro carries the call site"
    (let [{:keys [traces errors]}
          (record-both
            (fn []
              (rf/subscribe [:rf2-ts1a/no-such-sub])))
          [miss] (errors-of traces :rf.error/no-such-sub)]
      (assert-production-record errors :rf.error/no-such-sub)
      (when rf.interop/debug-enabled?
        (is (some? miss) "no-such-sub trace fired")
        (assert-call-site-shape miss)))))

(deftest subscribe-owning-fn-omits-call-site
  (testing "the owning-ns fn-form `re-frame.subs/subscribe` does NOT carry a
   call site (the macro is the ONLY stamping surface)"
    (let [{:keys [traces errors]}
          (record-both
            (fn []
              (rf.subs/subscribe [:rf2-ts1a/no-such-sub])))
          [miss] (errors-of traces :rf.error/no-such-sub)]
      (assert-production-record errors :rf.error/no-such-sub)
      ;; rf2-d2841 -- class-4 vacuous under the gate.
      (when rf.interop/debug-enabled?
        (is (some? miss))
        (is (not (contains? miss :rf.trace/call-site))
            ":rf.trace/call-site omitted on the fn-form path")))))

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

;; ---- dispatch-sync / rf.router/dispatch-sync! ---------------------------------

(deftest dispatch-sync-macro-stamps-call-site-on-handler-exception
  (testing "dispatch-sync macro stamps the call-site through the envelope
   to errors emitted INSIDE the handler chain"
    (rf/reg-event :rf2-ts1a/throws
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [{:keys [traces errors]}
          (record-both
            (fn []
              (rf/dispatch-sync [:rf2-ts1a/throws])))
          [exc] (errors-of traces :rf.error/handler-exception)
          rec   (assert-production-record errors :rf.error/handler-exception)]
      ;; ALWAYS-ON (rf2-d2841): what production DOES get for this failure is
      ;; the REGISTRATION coord, off the always-on `error-coords-by-id`
      ;; registry -- not the invocation coord this file is about.
      (is (= (rf.source-coords/error-coords-for :event :rf2-ts1a/throws) (:source-coord rec))
          "the production record carries the registration coord instead")
      (when rf.interop/debug-enabled?
        (is (some? exc))
        (assert-call-site-shape exc)))))

(deftest dispatch-sync-owning-fn-omits-call-site-on-handler-exception
  (testing "the owning-ns fn-form `re-frame.router/dispatch-sync!` does NOT
   carry a call site"
    (rf/reg-event :rf2-ts1a/throws-fn
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [{:keys [traces errors]}
          (record-both
            (fn []
              (rf.router/dispatch-sync! [:rf2-ts1a/throws-fn])))
          [exc] (errors-of traces :rf.error/handler-exception)
          rec   (assert-production-record errors :rf.error/handler-exception)]
      ;; ALWAYS-ON: the fn-form loses the INVOCATION coord, not the
      ;; REGISTRATION coord -- the record still names where the handler lives.
      (is (= (rf.source-coords/error-coords-for :event :rf2-ts1a/throws-fn) (:source-coord rec))
          "fn-form dispatch still ships the registration coord")
      ;; rf2-d2841 -- class-4 vacuous under the gate.
      (when rf.interop/debug-enabled?
        (is (some? exc))
        (is (not (contains? exc :rf.trace/call-site))
            ":rf.trace/call-site omitted on the fn-form path")))))

;; ---- top-level placement (Q2=A) ------------------------------------------

(deftest call-site-rides-at-top-level
  (testing ":rf.trace/call-site lives at the top level, sibling of
   :rf.trace/trigger-handler — NOT nested under :tags"
    (rf/reg-event :rf2-ts1a/top-level
                     (fn [_cofx _event]
                       (throw (ex-info "boom" {}))))
    (let [{:keys [traces errors]}
          (record-both
            (fn []
              (rf/dispatch-sync [:rf2-ts1a/top-level])))
          [exc] (errors-of traces :rf.error/handler-exception)]
      (assert-production-record errors :rf.error/handler-exception)
      ;; rf2-d2841 -- GUARDED. The `:tags` negative was class-4 vacuous under
      ;; the gate for the usual reason: `exc` is nil, `(contains? nil k)` false.
      (when rf.interop/debug-enabled?
        (is (contains? exc :rf.trace/call-site)
            ":rf.trace/call-site lives at the top level of the event")
        (is (not (contains? (:tags exc) :rf.trace/call-site))
            ":rf.trace/call-site does NOT live under :tags")
        ;; Mirror trigger-handler placement so both pieces sit side-by-side
        ;; in the event shape.
        (is (contains? exc :rf.trace/trigger-handler)
            ":rf.trace/trigger-handler lives alongside :rf.trace/call-site")))))

;; ---- call-site captures the actual source line ----------------------------

(deftest call-site-line-matches-call-site
  (testing "the captured :line is the line of the dispatch macro form
   and :file points at this test file"
    (let [{:keys [traces errors]}
          (record-both
            (fn []
              (rf/dispatch-sync [:rf2-ts1a/missing])))   ;; ← THIS line
          [miss] (errors-of traces :rf.error/no-such-handler)
          cs     (:rf.trace/call-site miss)]
      (assert-production-record errors :rf.error/no-such-handler)
      (when rf.interop/debug-enabled?
        (is (some? cs))
        ;; We can't hardcode the line number (file edits would break the
        ;; test); instead assert the line is plausible (positive integer)
        ;; and the file points at this test file.
        (is (integer? (:line cs)))
        (is (pos? (:line cs)))
        (is (string? (:file cs)))
        (is (re-find #"source_coord_jvm_test" (:file cs))
            (str ":file should point at this test file — got " (:file cs)))))))
