(ns re-frame.epoch-egress-resource-scope-test
  "Off-box egress of a `:rf.resource/scope-resolved` row inside an epoch
  record's `:trace-events`. The row carries the resolver's resolved
  `:input-values` and the `:scope` derived from them — identity-bearing values
  no app-db path declaration can match once they are copied into trace tags —
  so the epoch projection hands the row to the resources artefact's late-bound
  `:resources/project-scope-resolved-egress` projector, which fails closed.

  resources is a TEST-ONLY dep: production epoch never deps it, and with the
  artefact absent the hook is nil and the rows pass through untouched."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; load-bearing: publishes the `:epoch/project-record` hook the
            ;; door dispatches an `:rf/epoch-record` to.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; load-bearing: publishes the :resources/* late-bind hooks,
            ;; including :resources/project-scope-resolved-egress.
            [re-frame.resources]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     ;; The trace walk is rooted at the record's frame; with no live frame it
     ;; would redact the whole `:trace-events` slot.
     :init-fn #(rf/make-frame {:id :test/rs})}))

(def ^:private raw-tags
  {:resource-id   :rs/session
   :kind          :resource-scope
   :inputs        [:username]
   :input-values  {:username "jake-SECRET"}
   :whole-db?     false
   :scope         [:rf.scope/session {:username "jake-SECRET"}]
   :resolved-nil? false})

(def ^:private record
  {:kind         :rf/epoch-record
   :epoch-id     1
   :frame        :test/rs
   :trace-events [{:op-type   :rf.event
                   :operation :rf.resource/scope-resolved
                   :tags      raw-tags}]})

(defn- projected-tags [opts]
  (:tags (first (:trace-events (rf/project-egress record opts)))))

(deftest off-box-projection-redacts-sensitive-resolver-values
  (is (= (assoc raw-tags :input-values :rf/redacted :scope :rf/redacted :sensitive? true)
         (projected-tags nil))
      "the resolved values redact off-box and the row is stamped sensitive,
       while the structural attribution rides verbatim")
  (is (= raw-tags (projected-tags {:rf.egress/include-sensitive? true}))
      "the trusted-local opt-in lifts the redaction: the raw values come back
       and no sensitive stamp is added"))
