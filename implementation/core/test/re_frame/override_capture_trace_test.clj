(ns re-frame.override-capture-trace-test
  "Spec-Schemas §`:rf/epoch-record` + Tool-Pair §Replay. The router stamps the
  envelope's own per-call + lexical `:fx-overrides` and per-call
  `:interceptor-overrides` onto the `:rf.event/run-start` trace under
  `:rf.event/fx-overrides` / `:rf.event/interceptor-overrides` — the source
  `re-frame.epoch.capture/find-trigger-event` reads to pin the epoch record's
  override slots (the epoch-record integration lives in
  `re-frame.epoch-override-capture-test`).

  ## Posture split

  The override maps are slots on the dispatch ENVELOPE, which a user
  fx-handler receives as `(:envelope m)`, so the composition claims — a keyword
  entry rides, the lexical binding merges under the per-call opt, per-call wins
  a collision, the per-frame tier is excluded — are read off the envelope and
  hold in both postures. The capture itself — the tag on `:rf.event/run-start`,
  and a fn-valued override marker-ized to `:rf/fn-override` at the emission
  site — exists only on the dev trace path and sits in
  `(when rf.interop/debug-enabled? ...)` arms. Under the gate `tags` is nil, so
  a `(not (contains? tags ...))` outside an arm would certify absence over an
  emit that never happened."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(def ^:private captured-envelope (atom nil))

(defn- run-start-tags
  "Register `:ovc/run` (whose `:ovc/probe` fx stashes the dispatch envelope in
  `captured-envelope`), dispatch `event-v` with optional `dispatch-opts`, and
  return the single `:rf.event/run-start` trace's `:tags` — nil under the gate,
  where nothing is emitted."
  ([event-v] (run-start-tags event-v nil))
  ([event-v dispatch-opts]
   (reset! captured-envelope nil)
   (rf/reg-fx :ovc/probe (fn [m _] (reset! captured-envelope (:envelope m))))
   (rf/reg-event :ovc/run
     (fn [{:keys [db]} _] {:db db :fx [[:ovc/probe]]}))
   (let [acc (atom [])]
     (rf/register-listener! :trace ::cap (fn [ev] (swap! acc conj ev)))
     (try
       (if dispatch-opts
         (rf/dispatch-sync event-v dispatch-opts)
         (rf/dispatch-sync event-v))
       (let [run-starts (filterv #(= :rf.event/run-start (:operation %)) @acc)]
         (when rf.interop/debug-enabled?
           (is (= 1 (count run-starts))
               "exactly one :rf.event/run-start emit per dispatch"))
         (:tags (first run-starts)))
       (finally
         (rf/unregister-listener! :trace ::cap))))))

(deftest override-free-dispatch-omits-both-tags
  (testing "no per-call overrides => neither tag rides the run-start emit"
    (let [tags (run-start-tags [:ovc/run])
          env  @captured-envelope]
      (is (some? env) "the dispatch envelope reached the fx-handler ctx")
      (is (empty? (:fx-overrides env)))
      (is (empty? (:interceptor-overrides env)))
      (when rf.interop/debug-enabled?
        (is (not (contains? tags :rf.event/fx-overrides)))
        (is (not (contains? tags :rf.event/interceptor-overrides)))))))

(deftest fn-valued-fx-override-is-marker-ized
  (testing "a fn-valued per-call :fx-overrides entry is marker-ized to
            :rf/fn-override at the emission site — the fn never rides the tag"
    (let [tags (run-start-tags [:ovc/run]
                               {:fx-overrides {:ovc/real (fn [_ _] :ran)}})]
      (is (fn? (:ovc/real (:fx-overrides @captured-envelope)))
          "the envelope itself carries the raw fn")
      (when rf.interop/debug-enabled?
        (is (= {:ovc/real :rf/fn-override} (:rf.event/fx-overrides tags)))))))

(deftest lexical-with-fx-overrides-merges-under-per-call
  (testing "rf/with-fx-overrides's lexical binding merges into the captured
            :fx-overrides"
    (let [tags (rf/with-fx-overrides {:ovc/lexical :ovc/lexical-stub}
                 (run-start-tags [:ovc/run]))]
      (is (= {:ovc/lexical :ovc/lexical-stub} (:fx-overrides @captured-envelope)))
      (when rf.interop/debug-enabled?
        (is (= {:ovc/lexical :ovc/lexical-stub} (:rf.event/fx-overrides tags))))))
  (testing "per-call wins over lexical on key collision"
    (let [tags (rf/with-fx-overrides {:ovc/dual :ovc/from-lexical}
                 (run-start-tags [:ovc/run]
                                 {:fx-overrides {:ovc/dual :ovc/from-call}}))]
      (is (= {:ovc/dual :ovc/from-call} (:fx-overrides @captured-envelope)))
      (when rf.interop/debug-enabled?
        (is (= {:ovc/dual :ovc/from-call} (:rf.event/fx-overrides tags)))))))

(deftest interceptor-override-rides-verbatim
  (testing "a per-call :interceptor-overrides entry rides the run-start tag
            verbatim, including a parameterized [id arg] key"
    (let [expected {::some-icpt nil [:ovc/path-icpt [:cart]] nil}
          tags     (run-start-tags [:ovc/run] {:interceptor-overrides expected})]
      (is (= expected (:interceptor-overrides @captured-envelope)))
      (when rf.interop/debug-enabled?
        (is (= expected (:rf.event/interceptor-overrides tags)))))))

(deftest per-frame-only-fx-override-is-not-captured
  (testing "a per-frame-only :fx-overrides entry (no per-call, no lexical) is
            not captured — only the envelope's own per-call + lexical keys are"
    (rf/make-frame {:id :ovc/framed :fx-overrides {:ovc/real :ovc/stub}})
    (let [tags (run-start-tags [:ovc/run] {:frame :ovc/framed})]
      (is (some? @captured-envelope) "the dispatch envelope reached the fx ctx")
      (is (empty? (:fx-overrides @captured-envelope))
          "the per-frame tier does not compose onto the envelope")
      (is (= {:ovc/real :ovc/stub}
             (:fx-overrides (:config (rf.frame/frame :ovc/framed))))
          "while the frame does declare it, so the row above discriminates")
      (when rf.interop/debug-enabled?
        (is (not (contains? tags :rf.event/fx-overrides)))))))
