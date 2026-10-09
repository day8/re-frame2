(ns re-frame.epoch-silence-contract-test
  "The emitted `:rf.epoch.cb/silenced-on-frame-destroy` tags satisfy the
  canonical `EpochCbSilencedOnFrameDestroyTags` schema read out of
  `spec/Spec-Schemas.md`, for a non-keyword listener id the runtime accepts."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private spec-schemas-candidates
  ;; `clojure -M:test` runs from implementation/epoch; the fallbacks keep the
  ;; extraction CWD-robust.
  ["../../spec/Spec-Schemas.md" "../spec/Spec-Schemas.md" "spec/Spec-Schemas.md"])

(defn- canonical-silencing-schema
  "The `EpochCbSilencedOnFrameDestroyTags` `[:map …]` form, read from the
  markdown rather than hand-copied."
  []
  (let [f    (or (some (fn [p] (let [f (io/file p)] (when (.exists f) f)))
                       spec-schemas-candidates)
                 (throw (ex-info "cannot locate spec/Spec-Schemas.md"
                                 {:candidates spec-schemas-candidates})))
        text (slurp f)
        start (str/index-of text "(def EpochCbSilencedOnFrameDestroyTags")
        _    (when (nil? start)
               (throw (ex-info "EpochCbSilencedOnFrameDestroyTags not found"
                               {:file (str f)})))
        form (edn/read-string (subs text start))
        schema (nth form 2 nil)]
    (when-not (and (vector? schema) (= :map (first schema)))
      (throw (ex-info "schema is not a [:map …] form" {:read schema})))
    schema))

(deftest non-keyword-comparable-cb-id-round-trips-and-schema-validates
  (let [cb-id    [:my-app/epoch-log 7]         ; a vector — NOT keyword|string
        schema   (canonical-silencing-schema)
        recorded (atom [])]
    (rf/make-frame {:id :test/non-kw})
    (rf/reg-event :seed-nonkw (fn [_ _] {:db {:n 0}}))
    (rf/register-listener! :trace ::recorder-nonkw (fn [ev] (swap! recorded conj ev)))
    (rf/register-listener! :epoch cb-id (fn [_] nil))
    (rf/dispatch-sync [:seed-nonkw] {:frame :test/non-kw})
    (rf/destroy-frame! :test/non-kw)
    (let [tags (->> @recorded
                    (filter #(= :rf.epoch.cb/silenced-on-frame-destroy (:operation %)))
                    first
                    :tags)]
      (is (m/validate schema tags)
          (str "emitted tags must validate against the canonical schema: "
               (pr-str (m/explain schema tags))))
      ;; Current only if the raw vector id and its live generation came back verbatim.
      (is (true? (rf/epoch-silence-current? tags))))))
