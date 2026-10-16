(ns re-frame.machines-conformance-test
  "The machines artefact's gate over the machine registration-error
  taxonomy: every `spec/conformance/fixtures/*.edn` fixture with a
  `:reg-machine` call, whose capabilities this pure runner claims, must
  produce exactly its recorded registration error. Every other fixture,
  `:machine-transition` calls included, is graded by core's shared corpus
  runner, which holds the one matcher for each expectation."
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

(defn all-reg-machine-fixtures
  "`[filename fixture]` for every fixture with a `:reg-machine` call, in
  filename order."
  []
  (->> (file-seq fixtures-dir)
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".edn")))
       (sort-by #(.getName %))
       (map (fn [f] [(.getName f) (read-one-form (slurp f) (.getName f))]))
       (filter (fn [[_ fx]] (some (comp #{:reg-machine} :call) (:fixture/calls fx))))
       vec))

(def claimed-capabilities
  "The pure-function FSM / actor surface `validate-machine!` covers. A
  fixture declaring anything else is skipped. The `:core/*` tags ride on
  some machine fixtures and are no-ops for a pure call."
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

;; ---- calls -----------------------------------------------------------------

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
  (keep (fn [c] (when (= :reg-machine (:call c)) (run-reg-machine-call c)))
        (:fixture/calls fixture)))

(deftest run-machines-conformance-corpus
  (let [run      (filter (comp runnable? second) (all-reg-machine-fixtures))
        failures (vec (for [[fname fixture] run
                            detail          (fixture-failures fixture)]
                        (str fname ": " detail)))]
    ;; The floor keeps an emptied or orphaned corpus (wrong dir, a renamed
    ;; capability) from passing over nothing; the corpus only grows.
    (is (>= (count run) 9) (str "only " (count run) " :reg-machine fixtures ran"))
    (is (empty? failures) (str/join "\n" failures))))
