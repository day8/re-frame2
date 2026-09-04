(ns re-frame.migration.reg-event-codemod-integration-test
  "Runtime integration proof for the M-70 x M-73 middle-slot normalization
  (rf2-8odvg): the codemod's emitted output is EVALUATED against the real v2
  `re-frame.core` reg-event contract — the actual failing path (emitted form
  -> namespace load -> registration), not a shape assertion.

  Positive: the canonical v1 path-interceptor registration, rewritten,
  REGISTERS cleanly — no `:rf.error/path-removed`, no
  `:rf.error/reg-event-bad-middle-slot`, no
  `:rf.error/inline-interceptor-removed`, no
  `:rf.error/unregistered-interceptor`.

  Negative controls: the PRE-FIX codemod's exact preserved-inline output (and
  the positional / inline-value / unregistered-ref variants) deterministically
  throw those errors in the SAME harness — proving the harness observes
  registration, and that a regression to the old preserve-middle behaviour
  turns the positive tests red.

  Lives behind the codemod's `:integration` alias (deps.edn) with a
  `:local/root` dep on implementation/core, so the default `:test` alias stays
  self-contained (the codemod artefact itself never loads re-frame2)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.migration.reg-event-codemod :as rf.migration.reg-event-codemod])
  (:import [java.io PushbackReader StringReader]))

;; ---- fixtures --------------------------------------------------------------

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; re-registers the framework standards (incl. :rf.interceptor/path) after
  ;; the clear — idempotent by design.
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- harness ---------------------------------------------------------------

(defn- eval-source!
  "Read + eval every top-level form of `s` with *ns* bound to THIS namespace,
  so the `rf` alias in emitted source resolves against the real
  `re-frame.core`. This is the namespace-load path a migrated file takes."
  [s]
  (binding [*ns* (the-ns 're-frame.migration.reg-event-codemod-integration-test)]
    (with-open [r (PushbackReader. (StringReader. s))]
      (loop [last-val nil]
        (let [form (read {:eof ::eof} r)]
          (if (= ::eof form)
            last-val
            (recur (eval form))))))))

(defn- rf-error-id
  "Run `f`; if it throws, walk the cause chain for the first ex-info carrying
  `:rf.error/id` (the canonical thrown-error discriminator) and return that
  id. Returns nil when nothing throws."
  [f]
  (try
    (f)
    nil
    (catch Throwable t
      (loop [e t]
        (when e
          (or (:rf.error/id (ex-data e))
              (recur (.getCause e))))))))

;; ---- positive: the emitted output registers against real v2 ---------------

(def ^:private canonical-v1
  "The bead's canonical reproduction: a v1 reg-event-db carrying the standard
  path interceptor in metadata `:interceptors`."
  (str "(rf/reg-event-db :counter/inc\n"
       "  {:interceptors [(rf/path :counter)]}\n"
       "  (fn [db _] (update db :value inc)))"))

(deftest rewritten-canonical-metadata-registers
  (testing "the codemod's emitted output for the canonical v1 form registers cleanly"
    (let [{:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string canonical-v1)]
      (is (= :rewrite (:action (first findings))))
      (is (nil? (rf-error-id #(eval-source! source)))
          "emitted output must register without any :rf.error/* throw")
      (is (some? (rf.registrar/lookup :event :counter/inc))
          "the event handler actually registered"))))

(deftest rewritten-positional-vector-registers
  (testing "the codemod's emitted output for the positional v1 chain registers cleanly"
    (let [src (str "(rf/reg-event-db :counter/dec\n"
                   "  [(rf/path :counter)]\n"
                   "  (fn [db _] (update db :value dec)))")
          {:keys [source findings]} (rf.migration.reg-event-codemod/rewrite-string src)]
      (is (= :rewrite (:action (first findings))))
      (is (nil? (rf-error-id #(eval-source! source))))
      (is (some? (rf.registrar/lookup :event :counter/dec))))))

;; ---- negative controls: the PRE-FIX outputs hard-fail registration --------
;;
;; Each source below is exactly what the pre-rf2-8odvg codemod certified as a
;; successful :rewrite/:rename. Each throws a specific :rf.error/* — proving
;; this harness observes REGISTRATION (a parse/shape check would pass all of
;; them), and that reverting the normalization turns the positives red.

(deftest old-preserved-inline-path-output-throws-path-removed
  (testing "the old head-only rewrite's output dies at the rf/path removal stub"
    (let [old (str "(rf/reg-event :counter/inc\n"
                   "  {:interceptors [(rf/path :counter)]}\n"
                   "  (fn [{:keys [db]} _] {:db (update db :value inc)}))")]
      (is (= :rf.error/path-removed (rf-error-id #(eval-source! old)))))))

(deftest positional-middle-slot-throws-bad-middle-slot
  (testing "a surviving positional vector middle slot is rejected by reg-event"
    ;; entries are valid refs so arg evaluation succeeds; the POSITIONAL slot
    ;; itself is the retired shape reg-event rejects.
    (let [old (str "(rf/reg-event :counter/inc\n"
                   "  [[:rf.interceptor/path [:counter]]]\n"
                   "  (fn [{:keys [db]} _] {:db (update db :value inc)}))")]
      (is (= :rf.error/reg-event-bad-middle-slot
             (rf-error-id #(eval-source! old)))))))

(deftest inline-interceptor-value-throws-inline-removed
  (testing "a surviving inline interceptor VALUE in metadata is rejected by reg-event"
    (let [old (str "(rf/reg-event :counter/inc\n"
                   "  {:interceptors [{:id :my/ic :before identity}]}\n"
                   "  (fn [{:keys [db]} _] {:db (update db :value inc)}))")]
      (is (= :rf.error/inline-interceptor-removed
             (rf-error-id #(eval-source! old)))))))

(deftest unregistered-ref-throws-unregistered-interceptor
  (testing "the harness also observes registration-time ref validation"
    (let [src (str "(rf/reg-event :counter/inc\n"
                   "  {:interceptors [:not/registered]}\n"
                   "  (fn [{:keys [db]} _] {:db db}))")]
      (is (= :rf.error/unregistered-interceptor
             (rf-error-id #(eval-source! src)))))))
