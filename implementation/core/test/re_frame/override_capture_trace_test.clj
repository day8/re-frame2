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
      per-call + lexical keys are captured (per the ruling's pinned scope).

  ## Posture split (rf2-d2841)

  What is captured, and where from, are two claims. `:fx-overrides` /
  `:interceptor-overrides` are slots on the DISPATCH ENVELOPE that
  `build-envelope` composes (per-call opt merged over the lexical
  `*fx-overrides*` binding), and a user fx-handler is handed that envelope as
  `(:envelope m)` — the production surface
  `cascade-envelope-propagation-test/fx-handler-ctx-carries-envelope-slot`
  pins. So the COMPOSITION claims — a keyword entry rides, the lexical
  binding merges in UNDER the per-call opt, per-call wins on a key collision,
  the per-frame tier is excluded — are read off the envelope and hold in both
  postures.

  What the trace genuinely owns, and what therefore sits inside
  `(when interop/debug-enabled? ...)` arms, is the CAPTURE: the tag rides
  `:rf.event/run-start`, and a fn-valued override is MARKER-IZED to
  `:rf/fn-override` at the emission site so the fn never reaches the tag. That
  marker-ization exists only on the emit path; the envelope holds the raw fn,
  which is exactly why the marker is worth asserting.

  Two vacuous passes were found and moved. `override-free-dispatch-omits-both-tags`
  is a pair of `(not (contains? tags ...))` rows, and under the gate `tags` is
  nil — `contains?` of nil is false for every key, so both certified
  \"absent\" over an event that never existed. They now sit in the arm, beside a
  new always-on partner asserting the ENVELOPE omits the keys, which is the
  claim that survives."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
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
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

;; rf2-d2841 — the ALWAYS-ON read. The override maps the trace CAPTURES are
;; slots on the dispatch envelope, and a user fx-handler receives the envelope
;; verbatim, so the composition can be inspected with no trace involvement.
(def ^:private captured-envelope (atom nil))

(defn- run-start-tags
  "Register the shared `:ovc/run` handler (each `:each` fixture wipes the
  registrar via `registrar/clear-all!`, so this must be re-registered per call,
  not once at namespace load), dispatch `event-v` (with optional
  `dispatch-opts`), and return the single `:rf.event/run-start` trace event's
  `:tags` map — nil in production posture, where nothing is emitted.

  Side effect (rf2-d2841): the handler runs an `:ovc/probe` fx that stashes the
  dispatch ENVELOPE in `captured-envelope`, so the caller can read the same
  override maps off the always-on surface."
  ([event-v] (run-start-tags event-v nil))
  ([event-v dispatch-opts]
   (reset! captured-envelope nil)
   (rf/reg-fx :ovc/probe (fn [m _] (reset! captured-envelope (:envelope m))))
   (rf/reg-event :ovc/run
     (fn [{:keys [db]} _] {:db db :fx [[:ovc/probe]]}))
   (let [acc (collect-traces! ::cap)]
     (try
       (if dispatch-opts
         (rf/dispatch-sync event-v dispatch-opts)
         (rf/dispatch-sync event-v))
       (let [run-starts (filterv #(= :rf.event/run-start (:operation %)) @acc)]
         (when interop/debug-enabled?
           (is (= 1 (count run-starts))
               "exactly one :rf.event/run-start emit per dispatch"))
         (:tags (first run-starts)))
       (finally
         (rf/unregister-listener! :trace ::cap))))))

(deftest override-free-dispatch-omits-both-tags
  (testing "no per-call overrides => neither tag rides the run-start emit"
    (let [tags (run-start-tags [:ovc/run])
          env  @captured-envelope]
      ;; ---- ALWAYS-ON (rf2-d2841): the ENVELOPE carries no override maps ----
      (is (some? env) "the dispatch envelope reached the fx-handler ctx")
      (is (empty? (:fx-overrides env))
          "an override-free dispatch composes no :fx-overrides at all")
      (is (empty? (:interceptor-overrides env))
          "nor any :interceptor-overrides")
      ;; ---- rf2-d2841 dev arm. VACUOUS OUTSIDE IT: under the gate `tags` is
      ;;      nil and `(contains? nil k)` is false for every key, so both rows
      ;;      would certify "absent" over an emit that never happened.
      (when interop/debug-enabled?
        (is (not (contains? tags :rf.event/fx-overrides)))
        (is (not (contains? tags :rf.event/interceptor-overrides)))))))

(deftest keyword-valued-fx-override-rides-verbatim
  (testing "an id-valued per-call :fx-overrides entry rides the run-start tag
   verbatim"
    (let [tags (run-start-tags [:ovc/run]
                               {:fx-overrides {:ovc/real :ovc/stub}})]
      ;; ALWAYS-ON (rf2-d2841): the composed envelope IS the thing captured.
      (is (= {:ovc/real :ovc/stub} (:fx-overrides @captured-envelope))
          "the id-redirect composes onto the dispatch envelope")
      (when interop/debug-enabled?
        (is (= {:ovc/real :ovc/stub} (:rf.event/fx-overrides tags)))))))

(deftest fn-valued-fx-override-is-marker-ized
  (testing "a fn-valued per-call :fx-overrides entry is marker-ized to
   :rf/fn-override at the emission site — the fn never rides the tag"
    (let [tags (run-start-tags [:ovc/run]
                               {:fx-overrides {:ovc/real (fn [_ _] :ran)}})]
      ;; ALWAYS-ON (rf2-d2841): the ENVELOPE holds the RAW fn — which is
      ;; precisely why marker-izing it at the emission site is worth pinning.
      (is (fn? (:ovc/real (:fx-overrides @captured-envelope)))
          "the envelope carries the fn value itself")
      ;; rf2-d2841 — the marker-ization exists only on the emit path.
      (when interop/debug-enabled?
        (is (= {:ovc/real :rf/fn-override} (:rf.event/fx-overrides tags)))))))

(deftest lexical-with-fx-overrides-merges-under-per-call
  (testing "rf/with-fx-overrides's lexical binding merges into the captured
   :fx-overrides, same precedence as the live override-application path"
    (let [tags (rf/with-fx-overrides {:ovc/lexical :ovc/lexical-stub}
                 (run-start-tags [:ovc/run]))]
      ;; ALWAYS-ON (rf2-d2841): the MERGE is envelope composition, not a
      ;; trace-side reconstruction.
      (is (= {:ovc/lexical :ovc/lexical-stub}
             (:fx-overrides @captured-envelope))
          "the lexical binding merges onto the dispatch envelope")
      (when interop/debug-enabled?
        (is (= {:ovc/lexical :ovc/lexical-stub} (:rf.event/fx-overrides tags)))))
    (testing "per-call wins over lexical on key collision"
      (let [tags (rf/with-fx-overrides {:ovc/dual :ovc/from-lexical}
                   (run-start-tags [:ovc/run]
                                   {:fx-overrides {:ovc/dual :ovc/from-call}}))]
        (is (= {:ovc/dual :ovc/from-call} (:fx-overrides @captured-envelope))
            "per-call wins over lexical on the envelope itself")
        (when interop/debug-enabled?
          (is (= {:ovc/dual :ovc/from-call} (:rf.event/fx-overrides tags))))))))

(deftest interceptor-override-rides-verbatim
  (testing "a per-call :interceptor-overrides entry rides the run-start tag
   verbatim, including a parameterized [id arg] key"
    (let [expected {::some-icpt nil [:ovc/path-icpt [:cart]] nil}
          tags (run-start-tags [:ovc/run]
                               {:interceptor-overrides expected})]
      ;; ALWAYS-ON (rf2-d2841).
      (is (= expected (:interceptor-overrides @captured-envelope))
          "the parameterized [id arg] key rides the envelope verbatim")
      (when interop/debug-enabled?
        (is (= expected (:rf.event/interceptor-overrides tags)))))))

(deftest per-frame-only-fx-override-is-not-captured
  (testing "a per-frame-only :fx-overrides entry (no per-call, no lexical) is
   NOT captured — the run-start tag omits it, matching the ruling's pinned
   per-call + lexical scope"
    (reset! captured-envelope nil)
    (rf/reg-fx :ovc/probe (fn [m _] (reset! captured-envelope (:envelope m))))
    (rf/reg-event :ovc/run (fn [{:keys [db]} _] {:db db :fx [[:ovc/probe]]}))
    (rf/make-frame {:id :ovc/framed :fx-overrides {:ovc/real :ovc/stub}})
    (let [acc (collect-traces! ::cap)]
      (try
        (rf/dispatch-sync [:ovc/run] {:frame :ovc/framed})
        ;; ---- ALWAYS-ON (rf2-d2841): the per-frame tier is a FRAME property,
        ;;      so it is genuinely absent from the envelope rather than merely
        ;;      absent from an emit that did not happen.
        (is (some? @captured-envelope) "the dispatch envelope reached the fx ctx")
        (is (empty? (:fx-overrides @captured-envelope))
            "the per-frame tier does not compose onto the envelope")
        (is (= {:ovc/real :ovc/stub}
               (:fx-overrides (:config (frame/frame :ovc/framed))))
            "...while the frame really does declare it (the contrast that makes
             the row above a discrimination rather than an empty read)")
        (when interop/debug-enabled?
          (let [run-starts (filterv #(= :rf.event/run-start (:operation %)) @acc)
                tags       (:tags (first run-starts))]
            (is (= 1 (count run-starts)))
            (is (not (contains? tags :rf.event/fx-overrides))
                "the per-frame tier does not ride the envelope-scoped capture")))
        (finally
          (rf/unregister-listener! :trace ::cap))))))
