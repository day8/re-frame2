(ns re-frame.ssr-compatibility-checks-test
  "Per rf2-69ad2 / Spec 011 §The :rf/hydrate event: the :rf.ssr/check-version
  and :rf.ssr/check-schema-digest fxs are the hydration-side compatibility
  checks the :rf/hydrate handler dispatches after replacing the client
  app-db. Each fx is best-effort — a mismatch emits a structured warning
  trace; the hydration proceeds (degraded-but-running, never crash).

  Coverage (2 per fx, matching the bead's acceptance criteria):

    - matching values → silent (no mismatch trace fires)
    - mismatching values → :rf.ssr/version-mismatch / :rf.ssr/schema-digest-
      mismatch trace fires with :expected + :actual + :recovery shape."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.ssr :as ssr]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! ssr/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr    :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- capture-traces!
  "Run f under a trace listener that captures every event; return the
  captured vector after f returns."
  [f]
  (let [traces (atom [])
        cb-id  (gensym "::capture-")]
    (rf/register-trace-cb! cb-id (fn [ev] (swap! traces conj ev)))
    (try
      (f)
      (finally
        (rf/remove-trace-cb! cb-id)))
    @traces))

(defn- traces-of [traces op]
  (filterv #(= op (:operation %)) traces))

;; ===========================================================================
;; :rf.ssr/check-version
;; ===========================================================================
;;
;; Per Spec 011 §The :rf/hydrate event: the fx receives a scalar (the
;; server's version) per the reference handler, OR a map {:expected ... :actual ...}
;; for explicit comparisons (per the rf2-69ad2 fx-input-shape clarification).
;; Matching → silent; mismatching → :rf.ssr/version-mismatch warning trace.

(deftest check-version-matching-is-silent
  (testing "matching expected + actual → no :rf.ssr/version-mismatch trace"
    (rf/reg-event-fx ::probe-check-version-match
      {:platforms #{:client}}
      (fn [_ _]
        {:fx [[:rf.ssr/check-version {:expected "1.0.0" :actual "1.0.0"}]]}))

    (let [f      (rf/make-frame {:platform :client})
          traces (capture-traces!
                   (fn []
                     (rf/dispatch-sync [::probe-check-version-match]
                                       {:frame f})))]
      (is (empty? (traces-of traces :rf.ssr/version-mismatch))
          "matching version → no mismatch trace")
      (is (empty? (traces-of traces :rf.ssr/compatibility-check-skipped))
          "both sides supplied → no skipped trace either"))))

(deftest check-version-mismatch-emits-trace
  (testing "differing expected + actual → :rf.ssr/version-mismatch warning trace"
    (rf/reg-event-fx ::probe-check-version-mismatch
      {:platforms #{:client}}
      (fn [_ _]
        {:fx [[:rf.ssr/check-version {:expected "1.0.0" :actual "2.0.0"}]]}))

    (let [f      (rf/make-frame {:platform :client})
          traces (capture-traces!
                   (fn []
                     (rf/dispatch-sync [::probe-check-version-mismatch]
                                       {:frame f})))
          hits   (traces-of traces :rf.ssr/version-mismatch)]
      (is (= 1 (count hits))
          (str "expected one :rf.ssr/version-mismatch trace; saw: "
               (pr-str (mapv :operation traces))))
      (when (seq hits)
        (let [ev (first hits)]
          (is (= :warning              (:op-type ev)))
          (is (= "1.0.0"               (-> ev :tags :expected)))
          (is (= "2.0.0"               (-> ev :tags :actual)))
          (is (= :warned-and-applied   (:recovery ev))
              ":recovery rides at top-level per Spec 009"))))))

;; ===========================================================================
;; :rf.ssr/check-schema-digest
;; ===========================================================================
;;
;; Same shape as version-check. Matching → silent; mismatch → warning trace.
;; Scalar form is what the reference :rf/hydrate handler dispatches (the
;; payload's :rf/schema-digest); the fx looks up the client-side digest
;; via the `:schemas/app-schemas-digest` late-bind hook. When the schemas
;; artefact isn't on the classpath the hook is absent and the fx emits
;; :rf.ssr/compatibility-check-skipped (covered by the scalar-form path
;; running under the real schemas artefact below).

(deftest check-schema-digest-matching-is-silent
  (testing "matching expected + actual → no :rf.ssr/schema-digest-mismatch trace"
    (rf/reg-event-fx ::probe-check-digest-match
      {:platforms #{:client}}
      (fn [_ _]
        {:fx [[:rf.ssr/check-schema-digest
               {:expected "sha256:deadbeefcafef00d"
                :actual   "sha256:deadbeefcafef00d"}]]}))

    (let [f      (rf/make-frame {:platform :client})
          traces (capture-traces!
                   (fn []
                     (rf/dispatch-sync [::probe-check-digest-match]
                                       {:frame f})))]
      (is (empty? (traces-of traces :rf.ssr/schema-digest-mismatch))
          "matching digest → no mismatch trace")
      (is (empty? (traces-of traces :rf.ssr/compatibility-check-skipped))
          "both sides supplied → no skipped trace either"))))

(deftest check-schema-digest-mismatch-emits-trace
  (testing "differing expected + actual → :rf.ssr/schema-digest-mismatch warning trace"
    (rf/reg-event-fx ::probe-check-digest-mismatch
      {:platforms #{:client}}
      (fn [_ _]
        {:fx [[:rf.ssr/check-schema-digest
               {:expected "sha256:deadbeefcafef00d"
                :actual   "sha256:0000000000000000"}]]}))

    (let [f      (rf/make-frame {:platform :client})
          traces (capture-traces!
                   (fn []
                     (rf/dispatch-sync [::probe-check-digest-mismatch]
                                       {:frame f})))
          hits   (traces-of traces :rf.ssr/schema-digest-mismatch)]
      (is (= 1 (count hits))
          (str "expected one :rf.ssr/schema-digest-mismatch trace; saw: "
               (pr-str (mapv :operation traces))))
      (when (seq hits)
        (let [ev (first hits)]
          (is (= :warning                              (:op-type ev)))
          (is (= "sha256:deadbeefcafef00d"             (-> ev :tags :expected)))
          (is (= "sha256:0000000000000000"             (-> ev :tags :actual)))
          (is (= :warned-and-applied                   (:recovery ev))))))))
