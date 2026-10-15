(ns re-frame.mcp-conformance.result-envelope-test
  "`:rf.mcp/result` typed eval/handler result-envelope gate.

  The marker key is the discriminator and its value a tag (`:value`,
  `:nil`, `:eval-error`, `:unserializable`) with tag-specific sibling
  slots, so a genuine nil, a thrown eval and an unserializable value do
  not collapse to a bare null. The emitter (`result_envelope.cljs`) is
  CLJS, so the shape is pinned by fixtures; the key is pinned against the
  JVM-reachable `vocab/result-key`."
  (:require [clojure.string :as str]
            [clojure.test   :refer [deftest is testing]]
            [malli.core     :as m]
            [malli.error    :as me]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]))

(def ResultEnvelope
  "Closed tagged result shapes from mcp-base/spec/vocab.md.
  Nil has its own tag; eval errors permit optional message and ex-data."
  [:multi {:dispatch :rf.mcp/result}
   [:value
    [:map {:closed true}
     [:rf.mcp/result [:= :value]]
     [:value :any]]]
   [:nil
    [:map {:closed true}
     [:rf.mcp/result [:= :nil]]]]
   [:eval-error
    [:map {:closed true}
     [:rf.mcp/result [:= :eval-error]]
     [:reason  :keyword]
     [:ex      :string]
     [:message {:optional true} [:maybe :string]]
     [:ex-data {:optional true} :any]]]
   [:unserializable
    [:map {:closed true}
     [:rf.mcp/result [:= :unserializable]]
     [:type    :string]
     [:preview :string]]]])

(def ^:private result-envelope-fixtures
  ;; Both error branches matter: the outer wrap-failed path omits the
  ;; message and ex-data slots emitted by the inner catch.
  {:re-frame2-pair-mcp-value
   {:rf.mcp/result :value :value {:a 1 :b [2 3]}}

   :re-frame2-pair-mcp-nil
   {:rf.mcp/result :nil}

   :re-frame2-pair-mcp-eval-error-inner
   {:rf.mcp/result :eval-error
    :reason        :rf.error/eval-cljs-threw
    :ex            "#error {:message \"undefined-symbol is not defined\"}"
    :message       "undefined-symbol is not defined"
    :ex-data       nil}

   :re-frame2-pair-mcp-eval-error-wrap-failed
   {:rf.mcp/result :eval-error
    :reason        :rf.error/result-envelope-wrap-failed
    :ex            "#error {:message \"classification machinery failed\"}"}

   :re-frame2-pair-mcp-unserializable
   {:rf.mcp/result :unserializable
    :type          "object"
    :preview       "#object[Object [object console]]"}})

(deftest result-envelope-fixtures-conform-to-schema
  (doseq [[fixture-name fixture-value] result-envelope-fixtures]
    (testing (str ":rf.mcp/result — fixture " fixture-name)
      (is (m/validate ResultEnvelope fixture-value)
          (str "Fixture " fixture-name " for :rf.mcp/result failed schema "
               "validation:\n"
               (me/humanize (m/explain ResultEnvelope fixture-value)))))))

(deftest result-key-matches-the-live-vocab-constant
  (is (= :rf.mcp/result rf.mcp-base.vocab/result-key)
      (str "vocab/result-key MUST be :rf.mcp/result — the literal the "
           "ResultEnvelope schema dispatches on. Got "
           (pr-str rf.mcp-base.vocab/result-key))))

;; The mcp-base vocab spec, not pair-mcp's docs, catalogues this marker.
(deftest result-key-literal-in-mcp-base-vocab-spec-doc-source
  (let [literal ":rf.mcp/result"
        rel     "tools/mcp-base/spec/vocab.md"]
    (is (str/includes? (rf.mcp-conformance.fixtures/read-source rel) literal)
        (str literal " missing from " rel
             ". The vocab spec catalogues this marker — restore the row "
             "or update this test."))))
