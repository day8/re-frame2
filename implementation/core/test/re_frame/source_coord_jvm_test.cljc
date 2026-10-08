(ns re-frame.source-coord-jvm-test
  "`:rf.trace/call-site` on `:rf.error/*` trace events: the invocation line of
  the user-facing `dispatch-sync` / `subscribe` macro call that produced the
  error, as a flat top-level `{:ns :file :line :column}` sibling of
  `:rf.trace/trigger-handler`. The macro stamps it; the owning-ns fn-form
  (`re-frame.router/dispatch-sync!`) does not. The macros expand on the JVM for
  both targets and the delivery path is platform-neutral `.cljc`, so this
  JVM-only suite (`-jvm-test` keeps it off `:node-test`) covers both hosts.

  ## Posture split

  The call-site is dev-only: the expansion is
  `(if rf.interop/debug-enabled? <stamped> <plain>)`, so under
  `-Dre-frame.debug=false` neither the coord nor its trace event exists. Each
  case therefore also asserts, always-on, that the promoted error category
  fanned a record to the corpus-wide `:errors` registry and that the record
  carries no `:rf.trace/call-site`. The trace assertions, absence checks
  included, sit in `(when rf.interop/debug-enabled? …)` arms: against the empty
  trace stream the gate leaves, an absence check would pass vacuously."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.router :as rf.router]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- errors-of [evs op]
  (filterv #(and (= :error (:op-type %))
                 (= op     (:operation %)))
           evs))

(defn- record-both
  "Run `body-fn` with a dev-trace listener and an always-on `:errors` listener
  attached, returning `{:traces [...] :errors [...]}`."
  [body-fn]
  (let [traces (atom [])
        errors (atom [])]
    (rf/register-listener! :trace  ::rec (fn [ev]  (swap! traces conj ev)))
    (rf.error-emit/register-error-listener! ::err (fn [rec] (swap! errors conj rec)))
    (try (body-fn)
         (finally
           (rf/unregister-listener! :trace  ::rec)
           (rf.error-emit/unregister-error-listener! ::err)))
    {:traces @traces :errors @errors}))

(defn- assert-production-record
  "ALWAYS-ON: category `kw` fanned a record to the `:errors` registry, and the
  record carries no `:rf.trace/call-site`. Returns the record."
  [recs kw]
  (let [rec (first (filterv #(= kw (:error %)) recs))]
    (is (some? rec) (str "the always-on error record for " kw " fired"))
    (is (not (contains? rec :rf.trace/call-site)))
    rec))

(defn- assert-call-site-shape
  "The call-site sits at the top level of the event, not under `:tags`, and
  names this file's invocation."
  [ev]
  (let [cs (:rf.trace/call-site ev)]
    (is (= 're-frame.source-coord-jvm-test (:ns cs)))
    (is (pos-int? (:line cs)))
    (is (re-find #"source_coord_jvm_test" (:file cs)))
    (is (not (contains? (:tags ev) :rf.trace/call-site)))))

(deftest dispatch-sync-macro-stamps-call-site-on-no-such-handler
  (let [{:keys [traces errors]} (record-both #(rf/dispatch-sync [:rf2-ts1a/no-such-event]))]
    (assert-production-record errors :rf.error/no-such-handler)
    (when rf.interop/debug-enabled?
      (assert-call-site-shape (first (errors-of traces :rf.error/no-such-handler))))))

(deftest dispatch-sync-owning-fn-omits-call-site-on-no-such-handler
  ;; The fn-form reaches the same production record; only the dev trace differs.
  (let [{:keys [traces errors]} (record-both #(rf.router/dispatch-sync! [:rf2-ts1a/no-such-event]))
        [miss] (errors-of traces :rf.error/no-such-handler)]
    (assert-production-record errors :rf.error/no-such-handler)
    (when rf.interop/debug-enabled?
      (is (some? miss))
      (is (not (contains? miss :rf.trace/call-site))))))

(deftest subscribe-macro-stamps-call-site-on-no-such-sub
  (let [{:keys [traces errors]} (record-both #(rf/subscribe [:rf2-ts1a/no-such-sub]))]
    (assert-production-record errors :rf.error/no-such-sub)
    (when rf.interop/debug-enabled?
      (assert-call-site-shape (first (errors-of traces :rf.error/no-such-sub))))))

(deftest dispatch-sync-macro-stamps-call-site-on-handler-exception
  ;; The call-site rides the envelope into errors emitted inside the handler
  ;; chain. Production gets the REGISTRATION coord instead, from the always-on
  ;; error-coord registry.
  (rf/reg-event :rf2-ts1a/throws
                (fn [_cofx _event]
                  (throw (ex-info "boom" {}))))
  (let [{:keys [traces errors]} (record-both #(rf/dispatch-sync [:rf2-ts1a/throws]))
        rec (assert-production-record errors :rf.error/handler-exception)]
    (is (= (rf.source-coords/error-coords-for :event :rf2-ts1a/throws) (:source-coord rec)))
    (when rf.interop/debug-enabled?
      (assert-call-site-shape (first (errors-of traces :rf.error/handler-exception))))))
