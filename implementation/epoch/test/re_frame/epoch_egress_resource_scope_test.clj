(ns re-frame.epoch-egress-resource-scope-test
  "Coverage for the OFF-BOX egress redaction of a `:rf.resource/scope-resolved`
  trace row's resolver-owned values inside an epoch record's `:trace-events`
  (rf2-84l82t, EP-0015).

  The `:rf.resource/scope-resolved` trace row carries the resolver's resolved
  `:input-values` (the concrete app-db reads — e.g. `{:username \"jake\"}`) and
  the derived `:scope` (the identity tuple embedding them). These are
  owner-local, identity-bearing values the generic value-path trace egress walk
  (`re-frame.epoch.tool-pair/elide-trace-events-slot` → `project-egress`) is
  structurally blind to once they have been copied into trace tags — a frame's
  declared `:sensitive` app-db PATHS do not match a resolver's resolved VALUES.

  The resource family owns the row's egress projector
  (`re-frame.resources.scope-registry/project-scope-resolved-egress`), published
  as the late-bound `:resources/project-scope-resolved-egress` hook the epoch
  tool-pair consults from `omit-off-box-resource-scope-values`. This test proves
  the WIRING fires end-to-end: with resources loaded, `projected-record` redacts
  the resolver values for the off-box default, and the trusted-local
  `:include-sensitive?` opt-in lifts the redaction (the `local-raw` boundary).

  resources is a TEST-ONLY dep here (production epoch never deps resources; the
  hook is nil-safe when absent — proven by the `epoch_egress_trace_events_test`
  suite which runs without resources)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as epoch]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; load-bearing: publishes the :resources/* late-bind hooks,
            ;; including :resources/project-scope-resolved-egress.
            [re-frame.resources]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (rf/reg-frame :test/rs {})
                ;; a db-reading resolver (the :inherit default — fail-closed
                ;; off-box); NOT explicitly declassified.
                (rf/reg-resource-scope :rs/session
                  {:inputs {:username [:db [:auth :user :username]]}}
                  (fn [{:keys [username]} _]
                    (when username [:rf.scope/session {:username username}])))
                ;; an explicitly :rf.egress/public resolver (the declassified
                ;; audit surface — rides verbatim off-box).
                (rf/reg-resource-scope :rs/public-locale
                  {:inputs {:locale [:db [:i18n :locale]]}
                   :rf.egress/output-sensitivity :rf.egress/public}
                  (fn [{:keys [locale]} _]
                    (when locale [:rf.scope/locale {:locale locale}]))))}))

(def ^:private secret "jake-SECRET")

(defn- contains-secret? [v]
  (boolean
    (cond
      (= v secret) true
      (map? v)     (or (some contains-secret? (keys v)) (some contains-secret? (vals v)))
      (coll? v)    (some contains-secret? v)
      :else        false)))

(defn- scope-resolved-event [resource-id input-values scope]
  {:op-type   :rf.event
   :operation :rf.resource/scope-resolved
   :tags      {:resource-id   resource-id
               :kind          :resource-scope
               :inputs        (vec (keys input-values))
               :input-values  input-values
               :whole-db?     false
               :scope         scope
               :resolved-nil? false}})

(defn- record-with [trace-events]
  {:epoch-id            1
   :frame               :test/rs
   :committed-at        0
   :event-id            :login
   :trigger-event       [:login]
   :db-before           {}
   :db-after            {}
   :outcome             :ok
   :trace-events        trace-events
   :sub-runs            []
   :renders             []
   :effects             []})

(deftest off-box-projection-redacts-sensitive-resolver-values
  (testing "rf2-84l82t — projected-record redacts a db-reading (:inherit)
            resolver's :input-values + :scope for the off-box default; the
            structural attribution slots survive and no raw secret egresses"
    (let [record    (record-with
                       [(scope-resolved-event :rs/session
                                              {:username secret}
                                              [:rf.scope/session {:username secret}])])
          projected (epoch/projected-record record)
          row       (first (:trace-events projected))]
      (is (= :rf/redacted (get-in row [:tags :input-values]))
          "the raw db reads are redacted off-box")
      (is (= :rf/redacted (get-in row [:tags :scope]))
          "the derived identity tuple is redacted off-box")
      (is (true? (get-in row [:tags :sensitive?])) "stamped sensitive")
      (testing "the structural attribution slots ride verbatim"
        (is (= :rs/session (get-in row [:tags :resource-id])))
        (is (= [:username]  (get-in row [:tags :inputs]))))
      (testing "no raw secret survives anywhere in the projected record"
        (is (not (contains-secret? projected)))))))

(deftest off-box-projection-redacts-formerly-declassified-resolver-values
  (testing "rf2-71dr8t / EP-0025 — the :rf.egress/public DECLASSIFICATION escape
            hatch was the removed propagation enum, so a resolver that once
            declared it now STILL redacts its resolved values off-box (off-box
            resolved-scope egress is unconditionally fail-closed)"
    (let [record    (record-with
                       [(scope-resolved-event :rs/public-locale
                                              {:locale "en"}
                                              [:rf.scope/locale {:locale "en"}])])
          projected (epoch/projected-record record)
          row       (first (:trace-events projected))]
      (is (= :rf/redacted (get-in row [:tags :input-values]))
          "the resolved input-values are redacted (no declassify hatch)")
      (is (= :rf/redacted (get-in row [:tags :scope]))
          "the derived scope is redacted")
      (is (true? (get-in row [:tags :sensitive?])) "stamped sensitive")
      (testing "the structural attribution slots ride verbatim"
        (is (= :rs/public-locale (get-in row [:tags :resource-id])))
        (is (= [:locale] (get-in row [:tags :inputs])))))))
