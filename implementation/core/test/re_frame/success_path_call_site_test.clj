(ns re-frame.success-path-call-site-test
  "Per rf2-twt7m Change 1 — `:rf.trace/call-site` rides success-path trace
  events.

  Mirror to `success_path_trigger_handler_test` (rf2-lf84g) for the
  call-site slot. Where trigger-handler names the registration site of
  the in-scope handler, call-site names the **invocation line** of the
  surface macro (`rf/dispatch`, `rf/dispatch-sync`, `rf/subscribe`).

  Originally introduced (rf2-ts1a) for error events only; widened by
  rf2-twt7m so success-path traces — starting with `:rf.event/dispatched`
  itself — also carry the dispatch-site coord. The Event lens redesign
  (rf2-zh2qc) and any consumer building click-to-source UX on the
  enqueue trace would otherwise lose the slot.

  Locked shape (per rf2-ts1a):

    {:ns <sym> :file <string> :line <int> :column <int>}

  Slot placement: top-level on the trace event, NOT under `:tags` —
  mirrors the error / trigger-handler shape exactly. Production
  elision: rides the same `interop/debug-enabled?` gate the rest of
  the trace surface uses; no separate elision contract.

  JVM-only — the dynamic-var binding mechanism is platform-agnostic.

  ## Posture split (rf2-d2841)

  `:rf.trace/call-site` is DEV-ONLY BY DESIGN and there is no production
  channel that carries it — checked, not assumed. `core-call-site-macros/gate`
  wraps every expansion in `(if interop/debug-enabled? <stamped> <plain>)` with
  the gate OUTERMOST, so under `-Dre-frame.debug=false` the coord map is never
  built; `router/process-event!` additionally re-gates the read
  (`(trace/with-call-site (when interop/debug-enabled? (:rf.trace/call-site
  opts)) …)`); and the coord does NOT ride the dispatch envelope, so the
  `(:envelope m)` probe that rescued `substrate-source-test` in rf2-d2841's
  fourth pass has nothing to read here. Every trace assertion below is
  therefore guarded.

  What keeps this file off the class-2 list (a namespace reported green having
  executed nothing) is the OTHER branch of that gate. `plain` — the production
  expansion of `rf/dispatch-sync` — is a distinct code path from the stamped
  one, and until this lane existed NOTHING had ever executed it: every suite
  ran in dev posture, where the `if` always selects `stamped`. So each case
  keeps an always-on witness that the branch this posture selected actually
  reached the router and drove the cascade to completion — handler ran, db
  committed, fx executed, child dispatch delivered. Under the gate that is
  first-ever coverage of the production expansion; under dev it is a control
  proving the guarded arm below is not being skipped for a bad reason.

  ONE VACUOUS PASS CAME OFF (rf2-d2841 class 4): `event-dispatched-fn-form-
  omits-call-site` certified the fn-form path with
  `(not (contains? enqueue :rf.trace/call-site))` over the nil an empty trace
  ring yields — `(contains? nil k)` is false for every k, so under the gate it
  certified the fn-form by never looking at it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.router :as router]
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
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! :trace ::rec)))
    @seen))

(defn- events-of [evs op]
  (filterv #(= op (:operation %)) evs))

;; ---- always-on dispatch witness (rf2-d2841) -------------------------------
;;
;; The call-site macros expand to `(if interop/debug-enabled? <stamped>
;; <plain>)`. Under `-Dre-frame.debug=false` the PLAIN branch runs, and no
;; suite had ever executed it. These probes assert that whichever branch this
;; posture selected reached the router and ran the cascade to completion.

(defn- register-probe-fx!
  "Register `:rf2-twt7m/probe`, an fx recording the dispatch envelope it runs
  under, keyed by the level keyword it is called with."
  [envelopes]
  (rf/reg-fx :rf2-twt7m/probe
    (fn [m [level]] (swap! envelopes assoc level (:envelope m)))))

(defn- assert-dispatched [envelopes level where]
  (let [env (get @envelopes level)]
    (is (map? env)
        (str "the " where " dispatch reached an fx handler in this posture"))
    (is (keyword? (:frame env))
        (str "the " where " envelope resolved a target frame"))))

;; ---- Change 1 — `:rf.event/dispatched` carries `:rf.trace/call-site` ---------

(deftest event-dispatched-success-carries-call-site
  (testing ":rf.event/dispatched (success path) carries :rf.trace/call-site
   when the dispatch came in via the macro form"
    (let [envelopes (atom {})]
      (register-probe-fx! envelopes)
      (rf/reg-event :rf2-twt7m/noop
        (fn [{:keys [db]} _] {:db (assoc db :rf2-twt7m/ran? true)
                              :fx [[:rf2-twt7m/probe [:top]]]}))
      (let [evs       (record-traces
                        (fn []
                          (rf/dispatch-sync [:rf2-twt7m/noop])))
            [enqueue] (events-of evs :rf.event/dispatched)]
        ;; ALWAYS-ON (rf2-d2841): whichever branch of the macro gate this
        ;; posture selected reached the router and committed.
        (assert-dispatched envelopes :top "macro dispatch-sync")
        (is (true? (:rf2-twt7m/ran? (rf/app-db-value :rf/default)))
            "the macro dispatch committed its db change in this posture")
        (when interop/debug-enabled?
          (is (some? enqueue) ":rf.event/dispatched fired")
          (is (contains? enqueue :rf.trace/call-site)
              ":rf.trace/call-site hoisted onto the success-path emit")
          (let [cs (:rf.trace/call-site enqueue)]
            (is (symbol? (:ns cs))   ":ns is a symbol")
            (is (string? (:file cs)) ":file is a string")
            (is (integer? (:line cs)) ":line is an integer")
            (is (re-find #"success_path_call_site_test" (:file cs))
                (str ":file should point at this test file — got " (:file cs)))))))))

(deftest event-dispatched-call-site-rides-at-top-level
  (testing ":rf.trace/call-site is a top-level field on success traces,
   NOT nested under :tags — mirrors the error / trigger-handler shape"
    (let [envelopes (atom {})]
      (register-probe-fx! envelopes)
      (rf/reg-event :rf2-twt7m/top-level
        (fn [{:keys [db]} _] {:db db :fx [[:rf2-twt7m/probe [:top]]]}))
      (let [evs       (record-traces
                        (fn []
                          (rf/dispatch-sync [:rf2-twt7m/top-level])))
            [enqueue] (events-of evs :rf.event/dispatched)]
        (assert-dispatched envelopes :top "macro dispatch-sync")
        ;; rf2-d2841 — GUARDED: top-level-vs-`:tags` is a TRACE-SHAPE claim, and
        ;; no trace event exists under `-Dre-frame.debug=false`.
        (when interop/debug-enabled?
          (is (contains? enqueue :rf.trace/call-site)
              ":rf.trace/call-site lives at top level")
          (is (not (contains? (:tags enqueue) :rf.trace/call-site))
              ":rf.trace/call-site does NOT live under :tags"))))))

(deftest event-dispatched-fn-form-omits-call-site
  (testing "the owning-ns fn-form `re-frame.router/dispatch-sync!` does NOT
   stamp a call-site, so :rf.event/dispatched carries no slot — better
   no-data than poison-data (mirrors the error-path contract)"
    (let [envelopes (atom {})]
      (register-probe-fx! envelopes)
      (rf/reg-event :rf2-twt7m/fn-form
        (fn [{:keys [db]} _] {:db (assoc db :rf2-twt7m/fn-ran? true)
                              :fx [[:rf2-twt7m/probe [:top]]]}))
      (let [evs       (record-traces
                        (fn []
                          (router/dispatch-sync! [:rf2-twt7m/fn-form])))
            [enqueue] (events-of evs :rf.event/dispatched)]
        ;; ALWAYS-ON (rf2-d2841): the fn-form seam — the one the macro's
        ;; production branch expands to — dispatches identically. That is the
        ;; substance the gate's prod branch relies on.
        (assert-dispatched envelopes :top "router/dispatch-sync! fn-form")
        (is (true? (:rf2-twt7m/fn-ran? (rf/app-db-value :rf/default)))
            "the fn-form dispatch committed its db change in this posture")
        ;; rf2-d2841 — class-4 vacuous under the gate: `enqueue` is nil there,
        ;; so the negative certified the fn-form by never looking at it.
        (when interop/debug-enabled?
          (is (some? enqueue) ":rf.event/dispatched fired")
          (is (not (contains? enqueue :rf.trace/call-site))
              ":rf.trace/call-site omitted on the fn-form path"))))))

;; ---- inner cascade emits carry the same call-site -------------------------

(deftest cascade-success-traces-carry-call-site
  (testing "every success-path trace emitted INSIDE the cascade (e.g.
   :rf.event/db-changed, :rf.fx/do-fx, :rf.fx/handled) carries the
   dispatch's call-site — rf2-twt7m Change 1 widens the hoist to
   match the trigger-handler treatment (rf2-lf84g)"
    (let [envelopes (atom {})]
      (register-probe-fx! envelopes)
      (rf/reg-fx :rf2-twt7m/my-fx (fn [_ _] :ok))
      (rf/reg-event :rf2-twt7m/cascade-child
        (fn [{:keys [db]} _] {:db (assoc db :child? true)
                              :fx [[:rf2-twt7m/probe [:child]]]}))
      (rf/reg-event :rf2-twt7m/cascade
        (fn [_ _] {:db {:n 1}
                   :fx [[:rf2-twt7m/my-fx {}]
                        [:rf2-twt7m/probe [:parent]]
                        [:dispatch [:rf2-twt7m/cascade-child]]]}))
      (let [evs       (record-traces
                        (fn []
                          (rf/dispatch-sync [:rf2-twt7m/cascade])))
            [dbc]     (events-of evs :rf.event/db-changed)
            [dof]     (events-of evs :rf.fx/do-fx)
            [handled] (events-of evs :rf.fx/handled)]
        ;; ALWAYS-ON (rf2-d2841): the cascade the guarded hoist claim is about
        ;; runs to completion in BOTH postures — parent fx, child dispatch and
        ;; the child's own commit. Under the gate this is the first execution
        ;; the macro's production branch has ever had through a real cascade.
        (assert-dispatched envelopes :parent "cascade parent")
        (assert-dispatched envelopes :child  "cascade child")
        (is (true? (:child? (rf/app-db-value :rf/default)))
            "the child dispatch committed in this posture")
        (when interop/debug-enabled?
          (is (some? dbc) ":rf.event/db-changed fired")
          (is (some? dof) ":rf.fx/do-fx fired")
          (is (some? handled) ":rf.fx/handled fired")
          ;; The macro stamps a call-site onto the opts map;
          ;; `process-event!` binds it via `with-dispatch-id+call-site`;
          ;; every emit inside the cascade hoists it (rf2-twt7m).
          (is (contains? dbc :rf.trace/call-site)
              ":rf.event/db-changed carries the dispatch's call-site")
          (is (contains? dof :rf.trace/call-site)
              ":rf.fx/do-fx carries the dispatch's call-site")
          (is (contains? handled :rf.trace/call-site)
              ":rf.fx/handled carries the dispatch's call-site"))))))
