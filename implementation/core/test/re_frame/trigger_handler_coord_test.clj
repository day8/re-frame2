(ns re-frame.trigger-handler-coord-test
  "`:rf.trace/trigger-handler` on `:rf.error/*` trace events (Spec 009
  §Handler-scope): an error emitted while a handler is in scope names that
  handler and its registration-site coord,

    {:kind :event / :sub / :fx, :id <registered-id>,
     :source-coord {:ns <sym> :file <string> :line <int> :column <int>}}

  The slot rides the dev-only trace, so those assertions sit in
  `rf.interop/debug-enabled?` arms. The substance survives production on the
  always-on `error-emit` record, which resolves `:source-coord` from the
  always-on coord registry; each case pins that channel too, and the two
  attribute differently: for an fx throw the trace names the fx, while the
  record's coord is the dispatching event's and the fx id rides `:failing-id`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
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
  ;; `init!` does not create `:rf/default`, and framework operations need a
  ;; carried frame.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- errors-of
  "The captured error traces whose `:operation` is `op`."
  [evs op]
  (filterv #(and (= :error (:op-type %))
                 (= op     (:operation %)))
           evs))

(defn- record-both
  "Run `body-fn` with a dev-trace listener and an always-on `error-emit`
  listener attached; return `{:traces [...] :errors [...]}`."
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

(defn- error-of
  "The first always-on error record whose `:error` is `kw`."
  [recs kw]
  (first (filterv #(= kw (:error %)) recs)))

(defn- assert-trigger-shape
  "`ev`'s `:rf.trace/trigger-handler` names `expected-kind` / `expected-id` and
  carries a source-coord."
  [ev expected-kind expected-id]
  (let [t (:rf.trace/trigger-handler ev)]
    (is (= expected-kind (:kind t)))
    (is (= expected-id   (:id t)))
    (let [c (:source-coord t)]
      (is (symbol? (:ns c))   ":ns is a symbol")
      (is (string? (:file c)) ":file is a string")
      (is (integer? (:line c)) ":line is an integer"))))

(deftest fx-handler-exception-carries-trigger-handler
  (rf/reg-fx :rf2-3nn8/throwing-fx
             (fn [_ctx _args] (throw (ex-info "fx boom" {}))))
  (rf/reg-event :rf2-3nn8/use-throwing-fx
                (fn [_cofx _event]
                  {:fx [[:rf2-3nn8/throwing-fx {}]]}))
  (let [{:keys [traces errors]} (record-both
                                  #(rf/dispatch-sync [:rf2-3nn8/use-throwing-fx]))
        [exc] (errors-of traces :rf.error/fx-handler-exception)]
    (is (= {:event-id     :rf2-3nn8/use-throwing-fx
            :failing-id   :rf2-3nn8/throwing-fx
            :source-coord (rf.source-coords/error-coords-for :event :rf2-3nn8/use-throwing-fx)}
           (select-keys (error-of errors :rf.error/fx-handler-exception)
                        [:event-id :failing-id :source-coord]))
        "the always-on record: the event's coord, the fx id on :failing-id")
    (when rf.interop/debug-enabled?
      (assert-trigger-shape exc :fx :rf2-3nn8/throwing-fx))))

(deftest sub-exception-carries-trigger-handler
  (rf/reg-sub :rf2-3nn8/throwing-sub
              (fn [_db _q] (throw (ex-info "sub boom" {}))))
  (let [{:keys [traces errors]} (record-both
                                  #(deref (rf/subscribe [:rf2-3nn8/throwing-sub])))
        [exc] (errors-of traces :rf.error/sub-exception)]
    (is (= {:event-id     :rf2-3nn8/throwing-sub
            :source-coord (rf.source-coords/error-coords-for :sub :rf2-3nn8/throwing-sub)}
           (select-keys (error-of errors :rf.error/sub-exception) [:event-id :source-coord]))
        "the always-on record carries the sub id and its coord under [:sub …]")
    (when rf.interop/debug-enabled?
      (assert-trigger-shape exc :sub :rf2-3nn8/throwing-sub))))

(deftest no-such-fx-carries-enclosing-event-trigger-handler
  ;; The fx walker emits `:rf.error/no-such-fx` while the event's scope is
  ;; still bound; no fx scope exists for an unregistered fx.
  (rf/reg-event :rf2-3nn8/uses-missing-fx
                (fn [_cofx _event]
                  {:fx [[:rf2-3nn8/no-such-fx {}]]}))
  (let [{:keys [traces errors]} (record-both
                                  #(rf/dispatch-sync [:rf2-3nn8/uses-missing-fx]))
        [miss] (errors-of traces :rf.error/no-such-fx)]
    (is (= (rf.source-coords/error-coords-for :event :rf2-3nn8/uses-missing-fx)
           (:source-coord (error-of errors :rf.error/no-such-fx)))
        "the always-on record carries the enclosing event's coord")
    (when rf.interop/debug-enabled?
      (assert-trigger-shape miss :event :rf2-3nn8/uses-missing-fx))))

(deftest source-coord-matches-registration-site
  ;; The coord is the registration site's, on both channels.
  (rf/reg-event :rf2-3nn8/registration-site
                (fn [_cofx _event]
                  (throw (ex-info "boom" {}))))
  (let [reg-meta (rf/handler-meta {:source :store :kind :event :id :rf2-3nn8/registration-site})
        {:keys [traces errors]} (record-both
                                  #(rf/dispatch-sync [:rf2-3nn8/registration-site]))
        [exc]    (errors-of traces :rf.error/handler-exception)
        trigger  (:rf.trace/trigger-handler exc)
        errc     (rf.source-coords/error-coords-for :event :rf2-3nn8/registration-site)
        ks       [:ns :file :line :column]]
    (is (integer? (:line errc)) "the always-on registry holds the registration coord")
    (is (= errc (:source-coord (error-of errors :rf.error/handler-exception)))
        "the always-on record's :source-coord IS the registration coord")
    ;; Under the gate `reg-meta` carries no coord keys, so this half is dev-only.
    (when rf.interop/debug-enabled?
      (is (= [:event :rf2-3nn8/registration-site] ((juxt :kind :id) trigger)))
      (is (= (mapv #(get reg-meta %) ks)
             (mapv #(get (:source-coord trigger) %) ks))))))
