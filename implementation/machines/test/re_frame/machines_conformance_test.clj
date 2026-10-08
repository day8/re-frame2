(ns re-frame.machines-conformance-test
  "The machines artefact's gate over the Mode-B conformance corpus: every
  `spec/conformance/fixtures/*.edn` fixture with a `:machine-transition` or
  `:reg-machine` call, whose capabilities this pure runner claims, must
  produce exactly its recorded snapshot / effects / registration error.
  Mode-A (dispatch-driven) fixtures run in core's runner."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [re-frame.machines :as rf.machines]))

(def fixtures-dir
  ;; Anchored to this file's classpath location, not the cwd: run from
  ;; `implementation/` a cwd-relative path resolves above the repo root.
  (let [res (io/resource "re_frame/machines_conformance_test.clj")]
    (assert res "machines test/ must be on the classpath to locate the fixtures")
    (-> (io/file res)
        .getParentFile       ; test/re_frame
        .getParentFile       ; test
        .getParentFile       ; machines
        .getParentFile       ; implementation
        .getParentFile       ; repo root
        (io/file "spec" "conformance" "fixtures")
        .getCanonicalFile)))

(defn- read-one-form
  "Read `text` as exactly one EDN form, or throw: a plain read keeps the
  first form and silently drops the rest of a fixture."
  [text fixture-name]
  (let [eof  (Object.)
        rdr  (java.io.PushbackReader. (java.io.StringReader. text))
        fail (fn [why data]
               (throw (ex-info (str "conformance fixture " fixture-name " " why ".")
                               (assoc data :fixture/file fixture-name))))
        rd   (fn []
               (try (edn/read {:eof eof} rdr)
                    (catch Exception e
                      (fail (str "is not readable EDN: " (.getMessage e))
                            {:fixture/reader-error (.getMessage e)}))))
        form (rd)]
    (when (identical? eof form)
      (fail "holds no top-level EDN form" {:fixture/forms 0}))
    (when-not (identical? eof (rd))
      (fail "must hold exactly ONE top-level EDN form" {:fixture/forms :more-than-one}))
    form))

(def machine-call-ops #{:machine-transition :reg-machine})

(defn all-machine-transition-fixtures
  "`[filename fixture]` for every fixture with a call this runner executes,
  in filename order."
  []
  (->> (file-seq fixtures-dir)
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".edn")))
       (sort-by #(.getName %))
       (map (fn [f] [(.getName f) (read-one-form (slurp f) (.getName f))]))
       (filter (fn [[_ fx]] (some (comp machine-call-ops :call) (:fixture/calls fx))))
       vec))

(def claimed-capabilities
  "The pure-function FSM / actor surface `machine-transition` and
  `validate-machine!` cover. A fixture declaring anything else is skipped.
  The `:core/*` tags ride on some machine fixtures and are no-ops for a
  pure call."
  #{:fsm/flat
    :fsm/hierarchical
    :fsm/parallel-regions
    :fsm/eventless-always
    :fsm/delayed-after
    :fsm/tags
    :fsm/final-states
    :fsm/history
    :fsm/registration-validation
    :actor/spawn-destroy
    :actor/declarative-spawn
    :actor/spawn-and-join
    :actor/own-state
    :core/event-handler
    :core/sub
    :core/fx
    :core/error
    :core/trace
    :core/frame})

(def claimed-spec-versions #{"1.0"})

(defn- runnable? [fixture]
  (and (every? claimed-capabilities (:fixture/capabilities fixture))
       (let [v (:fixture/spec-version fixture)]
         (or (nil? v) (contains? claimed-spec-versions v)))))

;; ---- handler-body DSL (spec/conformance/README.md §Handler-body DSL) --------
;; Mirrors core's `realise-machine-handlers`, which lives in core/test and is
;; not on this artefact's classpath.

(defn- realise-machine-action [steps]
  (fn [{:keys [data event]}]
    (let [eval-value (requiring-resolve 're-frame.conformance/eval-value*)
          final
          (reduce
            (fn [{:keys [data] :as ctx} step]
              (case (first step)
                :set    (let [[_ path v] step]
                          (assoc ctx :data (assoc-in data path (eval-value v ctx))))
                :fx     (let [[_ a b] step]
                          (update ctx :fx (fnil conj []) [a (eval-value b ctx)]))
                :throw  (throw (ex-info (str (second step)) {:from-fixture? true}))
                ctx))
            {:data data :event event :fx []}
            steps)]
      (cond-> {}
        (not= data (:data final)) (assoc :data (:data final))
        (seq (:fx final))         (assoc :fx (:fx final))))))

(defn- realise-machine-guard [steps]
  (fn [{:keys [data event]}]
    (let [eval-value (requiring-resolve 're-frame.conformance/eval-value*)
          step       (first steps)]
      (when (and (vector? step) (= :fn (first step)))
        (boolean (eval-value step {:data data :event event}))))))

(defn- realise-machine-handlers [fixture]
  (let [handlers (:fixture/handlers fixture)]
    {:actions (update-vals (:machine-action handlers) realise-machine-action)
     :guards  (update-vals (:machine-guard handlers) realise-machine-guard)}))

;; ---- calls -----------------------------------------------------------------

(defn- run-machine-transition-call [call {:keys [actions guards]}]
  (let [definition (-> (:definition call)
                       (update :actions #(merge actions %))
                       (update :guards  #(merge guards %)))
        r          (try (rf.machines/machine-transition definition (:snapshot call) (:event call))
                        (catch Throwable e
                          {:snapshot nil :fx [:error (.getMessage e)]}))
        ;; A depth-limit abort rolls the macrostep back: the observable
        ;; result is the input snapshot and no effects.
        depth-abort? (contains? #{:rf.error/machine-always-depth-exceeded
                                  :rf.error/machine-raise-depth-exceeded}
                                (get-in r [:error :kind]))
        snap-out   (if depth-abort? (:snapshot call) (:snapshot r))
        fx-out     (if depth-abort? [] (vec (:fx r)))
        want-snap  (:expect-next-snapshot call)
        want-fx    (or (:expect-effects call) [])]
    (when-not (and (= want-snap snap-out) (= want-fx fx-out))
      (str "machine-transition " (:event call)
           "\n    expected snapshot: " want-snap "\n    actual   snapshot: " snap-out
           "\n    expected effects:  " want-fx   "\n    actual   effects:  " fx-out))))

(defn- run-reg-machine-call
  "`:expect-error` names the `:rf.error/id` `validate-machine!` must throw;
  without it the definition must validate."
  [call]
  (let [want-error (:expect-error call)
        thrown     (try (rf.machines/validate-machine! (:definition call)) nil
                        (catch Throwable e e))
        got-id     (:rf.error/id (ex-data thrown))]
    (cond
      (and want-error (not= want-error got-id))
      (str "reg-machine: expected :rf.error/id " want-error ", got " got-id
           " (" (some-> thrown ex-message) ")")

      (and (not want-error) thrown)
      (str "reg-machine: expected a valid machine, threw " (ex-message thrown)))))

(defn- fixture-failures [fixture]
  (let [realised (realise-machine-handlers fixture)]
    (keep (fn [c]
            (case (:call c)
              :machine-transition (run-machine-transition-call c realised)
              :reg-machine        (run-reg-machine-call c)
              nil))
          (:fixture/calls fixture))))

(deftest run-machines-conformance-corpus
  (let [run      (filter (comp runnable? second) (all-machine-transition-fixtures))
        failures (vec (for [[fname fixture] run
                            detail          (fixture-failures fixture)]
                        (str fname ": " detail)))]
    ;; The floor keeps an emptied or orphaned corpus (wrong dir, a renamed
    ;; capability) from passing over nothing; the corpus only grows.
    (is (>= (count run) 40) (str "only " (count run) " Mode-B fixtures ran"))
    (is (empty? failures) (str/join "\n" failures))))
