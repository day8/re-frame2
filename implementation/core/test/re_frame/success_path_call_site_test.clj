(ns re-frame.success-path-call-site-test
  "`:rf.trace/call-site` rides success-path trace events.

  Mirror to `success_path_trigger_handler_test` for the
  call-site slot. Where trigger-handler names the registration site of
  the in-scope handler, call-site names the **invocation line** of the
  surface macro (`rf/dispatch`, `rf/dispatch-sync`, `rf/subscribe`).

  Error events and success-path traces — `:rf.event/dispatched` itself
  included — both carry the dispatch-site coord. The Event lens and any
  consumer building click-to-source UX on the enqueue trace would otherwise
  lose the slot.

  Locked shape:

    {:ns <sym> :file <string> :line <int> :column <int>}

  Slot placement: top-level on the trace event, NOT under `:tags` —
  mirrors the error / trigger-handler shape exactly. Production
  elision: rides the same `rf.interop/debug-enabled?` gate the rest of
  the trace surface uses; no separate elision contract.

  JVM-only — the dynamic-var binding mechanism is platform-agnostic.

  ## Posture split

  `:rf.trace/call-site` is DEV-ONLY BY DESIGN and there is no production
  channel that carries it — checked, not assumed. `core-call-site-macros/gate`
  wraps every expansion in `(if rf.interop/debug-enabled? <stamped> <plain>)` with
  the gate OUTERMOST, so under `-Dre-frame.debug=false` the coord map is never
  built; `rf.router/process-event!` additionally re-gates the read
  (`(rf.trace/with-call-site (when rf.interop/debug-enabled? (:rf.trace/call-site
  opts)) …)`); and the envelope's `:call-site` slot is stamped only under
  that same gate (the key is omitted in production), so the `(:envelope m)`
  probe `substrate-source-test` uses has nothing to read here. Every trace assertion below is
  therefore guarded.

  What keeps this file from reporting green having executed nothing is the
  OTHER branch of that gate. `plain` — the production expansion of
  `rf/dispatch-sync` — is a distinct code path from the stamped one, and a
  dev-posture suite never executes it, because there the `if` always selects
  `stamped`. So each case
  keeps an always-on witness that the branch this posture selected actually
  reached the router and drove the cascade to completion — handler ran, db
  committed, fx executed, child dispatch delivered. Under the gate that
  covers the production expansion; under dev it is a control
  proving the guarded arm below is not being skipped for a bad reason.

  ONE NEGATIVE IS GUARDED FOR VACUITY: `event-dispatched-fn-form-
  omits-call-site` asserts `(not (contains? enqueue :rf.trace/call-site))`,
  which over the nil an empty trace ring yields — `(contains? nil k)` is false
  for every k — would under the gate certify the fn-form by never looking at
  it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.router :as rf.router]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; EP-0002: `init!` does not synthesise `:rf/default`;
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

;; ---- always-on dispatch witness -------------------------------------------
;;
;; The call-site macros expand to `(if rf.interop/debug-enabled? <stamped>
;; <plain>)`. Under `-Dre-frame.debug=false` the PLAIN branch runs, and no
;; dev-posture suite executes it. These probes assert that whichever branch this
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

;; ---- `:rf.event/dispatched` carries `:rf.trace/call-site` --------------------

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
        ;; ALWAYS-ON: whichever branch of the macro gate this
        ;; posture selected reached the router and committed.
        (assert-dispatched envelopes :top "macro dispatch-sync")
        (is (true? (:rf2-twt7m/ran? (rf/app-db-value :rf/default)))
            "the macro dispatch committed its db change in this posture")
        (when rf.interop/debug-enabled?
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
        ;; GUARDED: top-level-vs-`:tags` is a TRACE-SHAPE claim, and
        ;; no trace event exists under `-Dre-frame.debug=false`.
        (when rf.interop/debug-enabled?
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
                          (rf.router/dispatch-sync! [:rf2-twt7m/fn-form])))
            [enqueue] (events-of evs :rf.event/dispatched)]
        ;; ALWAYS-ON: the fn-form seam — the one the macro's
        ;; production branch expands to — dispatches identically. That is the
        ;; substance the gate's prod branch relies on.
        (assert-dispatched envelopes :top "rf.router/dispatch-sync! fn-form")
        (is (true? (:rf2-twt7m/fn-ran? (rf/app-db-value :rf/default)))
            "the fn-form dispatch committed its db change in this posture")
        ;; Vacuous under the gate: `enqueue` is nil there,
        ;; so the negative would certify the fn-form by never looking at it.
        (when rf.interop/debug-enabled?
          (is (some? enqueue) ":rf.event/dispatched fired")
          (is (not (contains? enqueue :rf.trace/call-site))
              ":rf.trace/call-site omitted on the fn-form path"))))))

;; ---- inner cascade emits carry the same call-site -------------------------

(deftest cascade-success-traces-carry-call-site
  (testing "every success-path trace emitted INSIDE the cascade (e.g.
   :rf.event/db-changed, :rf.fx/do-fx, :rf.fx/handled) carries the
   dispatch's call-site — the hoist matches the trigger-handler
   treatment"
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
        ;; ALWAYS-ON: the cascade the guarded hoist claim is about
        ;; runs to completion in BOTH postures — parent fx, child dispatch and
        ;; the child's own commit. Under the gate this drives the macro's
        ;; production branch through a real cascade.
        (assert-dispatched envelopes :parent "cascade parent")
        (assert-dispatched envelopes :child  "cascade child")
        (is (true? (:child? (rf/app-db-value :rf/default)))
            "the child dispatch committed in this posture")
        (when rf.interop/debug-enabled?
          (is (some? dbc) ":rf.event/db-changed fired")
          (is (some? dof) ":rf.fx/do-fx fired")
          (is (some? handled) ":rf.fx/handled fired")
          ;; The macro stamps a call-site onto the opts map;
          ;; `process-event!` binds it via `with-dispatch-id+call-site`;
          ;; every emit inside the cascade hoists it.
          (is (contains? dbc :rf.trace/call-site)
              ":rf.event/db-changed carries the dispatch's call-site")
          (is (contains? dof :rf.trace/call-site)
              ":rf.fx/do-fx carries the dispatch's call-site")
          (is (contains? handled :rf.trace/call-site)
              ":rf.fx/handled carries the dispatch's call-site"))))))
