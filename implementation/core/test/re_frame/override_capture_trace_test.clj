(ns re-frame.override-capture-trace-test
  "Per rf2-yigokd — Spec-Schemas §`:rf/epoch-record` + Tool-Pair §Replay. The
  router stamps the envelope's OWN per-call + lexical `:fx-overrides` (as
  `build-envelope` merges `*fx-overrides*` under the per-call opt) and
  per-call `:interceptor-overrides` onto the `:rf.event/run-start` TRACE emit
  under `:rf.event/fx-overrides` / `:rf.event/interceptor-overrides` — the
  source `re-frame.epoch.capture/find-trigger-event` reads to pin the epoch
  record's `:fx-overrides` / `:interceptor-overrides` slots.

  Contract pinned here (the router/trace layer; the epoch-record integration
  lives in `re-frame.epoch-override-capture-test`):

    * Absent on the override-free hot path (byte-identical run-start).
    * A keyword-valued (id-redirect) `:fx-overrides` entry rides verbatim.
    * A fn-valued `:fx-overrides` entry is marker-ized to `:rf/fn-override`
      AT THE EMISSION SITE — the fn itself never reaches the trace tag.
    * The LEXICAL `with-fx-overrides` binding merges in under the per-call
      opt, same precedence as the live override-application path.
    * `:interceptor-overrides` rides verbatim (EDN by construction).
    * The PER-FRAME override tier is excluded — only the envelope's own
      per-call + lexical keys are captured (per the ruling's pinned scope)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- run-start-tags
  "Register the shared `:ovc/run` no-op handler (each `:each` fixture wipes
  the registrar via `registrar/clear-all!`, so this must be re-registered
  per call, not once at namespace load), dispatch `event-v` (with optional
  `dispatch-opts`), and return the single `:rf.event/run-start` trace
  event's `:tags` map."
  ([event-v] (run-start-tags event-v nil))
  ([event-v dispatch-opts]
   (rf/reg-event :ovc/run (fn [{:keys [db]} _] {:db db}))
   (let [acc (collect-traces! ::cap)]
     (try
       (if dispatch-opts
         (rf/dispatch-sync event-v dispatch-opts)
         (rf/dispatch-sync event-v))
       (let [run-starts (filterv #(= :rf.event/run-start (:operation %)) @acc)]
         (is (= 1 (count run-starts))
             "exactly one :rf.event/run-start emit per dispatch")
         (:tags (first run-starts)))
       (finally
         (rf/unregister-listener! :trace ::cap))))))

(deftest override-free-dispatch-omits-both-tags
  (testing "no per-call overrides => neither tag rides the run-start emit"
    (let [tags (run-start-tags [:ovc/run])]
      (is (not (contains? tags :rf.event/fx-overrides)))
      (is (not (contains? tags :rf.event/interceptor-overrides))))))

(deftest keyword-valued-fx-override-rides-verbatim
  (testing "an id-valued per-call :fx-overrides entry rides the run-start tag
   verbatim"
    (let [tags (run-start-tags [:ovc/run]
                               {:fx-overrides {:ovc/real :ovc/stub}})]
      (is (= {:ovc/real :ovc/stub} (:rf.event/fx-overrides tags))))))

(deftest fn-valued-fx-override-is-marker-ized
  (testing "a fn-valued per-call :fx-overrides entry is marker-ized to
   :rf/fn-override at the emission site — the fn never rides the tag"
    (let [tags (run-start-tags [:ovc/run]
                               {:fx-overrides {:ovc/real (fn [_ _] :ran)}})]
      (is (= {:ovc/real :rf/fn-override} (:rf.event/fx-overrides tags))))))

(deftest lexical-with-fx-overrides-merges-under-per-call
  (testing "rf/with-fx-overrides's lexical binding merges into the captured
   :fx-overrides, same precedence as the live override-application path"
    (let [tags (rf/with-fx-overrides {:ovc/lexical :ovc/lexical-stub}
                 (run-start-tags [:ovc/run]))]
      (is (= {:ovc/lexical :ovc/lexical-stub} (:rf.event/fx-overrides tags))))
    (testing "per-call wins over lexical on key collision"
      (let [tags (rf/with-fx-overrides {:ovc/dual :ovc/from-lexical}
                   (run-start-tags [:ovc/run]
                                   {:fx-overrides {:ovc/dual :ovc/from-call}}))]
        (is (= {:ovc/dual :ovc/from-call} (:rf.event/fx-overrides tags)))))))

(deftest interceptor-override-rides-verbatim
  (testing "a per-call :interceptor-overrides entry rides the run-start tag
   verbatim, including a parameterized [id arg] key"
    (let [tags (run-start-tags [:ovc/run]
                               {:interceptor-overrides
                                {::some-icpt nil
                                 [:ovc/path-icpt [:cart]] nil}})]
      (is (= {::some-icpt nil [:ovc/path-icpt [:cart]] nil}
             (:rf.event/interceptor-overrides tags))))))

(deftest per-frame-only-fx-override-is-not-captured
  (testing "a per-frame-only :fx-overrides entry (no per-call, no lexical) is
   NOT captured — the run-start tag omits it, matching the ruling's pinned
   per-call + lexical scope"
    (rf/reg-event :ovc/run (fn [{:keys [db]} _] {:db db}))
    (rf/reg-frame :ovc/framed {:fx-overrides {:ovc/real :ovc/stub}})
    (let [acc (collect-traces! ::cap)]
      (try
        (rf/dispatch-sync [:ovc/run] {:frame :ovc/framed})
        (let [run-starts (filterv #(= :rf.event/run-start (:operation %)) @acc)
              tags       (:tags (first run-starts))]
          (is (= 1 (count run-starts)))
          (is (not (contains? tags :rf.event/fx-overrides))
              "the per-frame tier does not ride the envelope-scoped capture"))
        (finally
          (rf/unregister-listener! :trace ::cap))))))
