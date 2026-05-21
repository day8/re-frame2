(ns day8.re-frame2-machines-viz.ai-generate-cljs-test
  "Pure-data tests for the AI-generate surface (rf2-1bncf · v1.1).

  Coverage:

  - `build-prompt` composes the system prompt and the user request
    into a single string.
  - `generate-machine` with a stub resolver returns the parsed spec
    for canned responses (the LLM seam is the injected resolver;
    tests inject a deterministic stub).
  - `generate-machine` handles fenced (```clojure / ```edn / bare)
    and unfenced responses.
  - Error modes throw `ex-info` with `:reason :ai-generate/<kw>`.
  - Generated specs are usable by sibling exporters (`scxml->spec`
    round-trip + `mermaid/emit`), so the AI-generate path connects
    end-to-end with the rest of the substrate."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.ai-generate :as ai]
            [day8.re-frame2-machines-viz.scxml :as scxml]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def login-flow-spec
  {:initial :idle
   :states  {:idle    {:on {:login :loading}}
             :loading {:on {:ok :authenticated :err :failed}}
             :authenticated {:final? true}
             :failed  {:final? true}}})

(def parallel-form-spec
  {:type    :parallel
   :regions {:net  {:initial :idle
                    :states  {:idle    {:on {:fetch :loading}}
                              :loading {}}}
             :form {:initial :empty
                    :states  {:empty    {:on {:type :composing}}
                              :composing {}}}}})

(defn- stub-resolver
  "Build a deterministic resolver that returns `response` regardless
  of the prompt it receives. Mimics an LLM with a fixed answer."
  [response]
  (fn [_prompt] response))

(defn- fence-clojure
  "Wrap an EDN string in a ```clojure fence (the system prompt asks
  the LLM to emit this shape)."
  [edn-str]
  (str "Here's the spec:\n\n```clojure\n" edn-str "\n```\n\n"
       "Use this as a starting point — feel free to refine."))

;; ---------------------------------------------------------------------------
;; build-prompt

(deftest build-prompt-embeds-system-prompt
  (testing "the full prompt includes the system prompt"
    (let [out (ai/build-prompt "a login flow")]
      (is (str/includes? out ai/system-prompt)))))

(deftest build-prompt-includes-user-request
  (testing "the user request appears after the system prompt"
    (let [out (ai/build-prompt "a login flow")]
      (is (str/includes? out "a login flow"))
      ;; The system prompt comes first; the user request second.
      (is (< (str/index-of out ai/system-prompt)
             (str/index-of out "a login flow"))))))

(deftest system-prompt-mentions-key-conventions
  (testing "system-prompt names the load-bearing convention surfaces
            so the LLM has the right vocabulary"
    (is (str/includes? ai/system-prompt ":initial"))
    (is (str/includes? ai/system-prompt ":states"))
    (is (str/includes? ai/system-prompt ":on"))
    (is (str/includes? ai/system-prompt ":final?"))
    (is (str/includes? ai/system-prompt ":parallel"))
    (is (str/includes? ai/system-prompt ":regions"))))

;; ---------------------------------------------------------------------------
;; generate-machine — happy paths

(deftest generate-with-fenced-clojure-response-returns-spec
  (testing "a ```clojure fenced EDN response parses to the spec"
    (let [resp (fence-clojure (pr-str login-flow-spec))
          out  (ai/generate-machine "a login flow"
                                    {:resolver (stub-resolver resp)})]
      (is (= login-flow-spec out)))))

(deftest generate-with-bare-edn-response-returns-spec
  (testing "a bare EDN response (no fence) also parses"
    (let [resp (pr-str login-flow-spec)
          out  (ai/generate-machine "a login flow"
                                    {:resolver (stub-resolver resp)})]
      (is (= login-flow-spec out)))))

(deftest generate-with-edn-fence-returns-spec
  (testing "a ```edn-fenced response parses"
    (let [resp (str "```edn\n" (pr-str login-flow-spec) "\n```")
          out  (ai/generate-machine "a login flow"
                                    {:resolver (stub-resolver resp)})]
      (is (= login-flow-spec out)))))

(deftest generate-tolerates-surrounding-prose
  (testing "prose around the fenced block is stripped"
    (let [resp (str "Sure, here's a login flow machine:\n\n"
                    "```clojure\n" (pr-str login-flow-spec) "\n```\n\n"
                    "Let me know if you want to adjust the transitions.")
          out  (ai/generate-machine "a login flow"
                                    {:resolver (stub-resolver resp)})]
      (is (= login-flow-spec out)))))

(deftest generate-handles-parallel-spec
  (testing "parallel machines parse and validate"
    (let [resp (fence-clojure (pr-str parallel-form-spec))
          out  (ai/generate-machine "a parallel net + form machine"
                                    {:resolver (stub-resolver resp)})]
      (is (= parallel-form-spec out)))))

;; ---------------------------------------------------------------------------
;; generate-machine — error modes

(deftest generate-without-resolver-throws-no-resolver
  (testing "missing :resolver throws :ai-generate/no-resolver"
    (let [ex (try
               (ai/generate-machine "a login flow")
               nil
               (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
      (is ex)
      (is (= :ai-generate/no-resolver (:rf.error/id (ex-data ex)))))))

(deftest generate-with-non-string-resolver-response-throws-parse-failed
  (testing "resolver returning a non-string throws :parse-failed"
    (let [resolver (fn [_] 42)
          ex (try
               (ai/generate-machine "x" {:resolver resolver})
               nil
               (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
      (is ex)
      (is (= :ai-generate/parse-failed (:rf.error/id (ex-data ex)))))))

(deftest generate-with-unparseable-edn-throws-parse-failed
  (testing "resolver returning malformed EDN throws :parse-failed"
    (let [resolver (stub-resolver "```clojure\n{{{ not-edn }}}\n```")
          ex (try
               (ai/generate-machine "x" {:resolver resolver})
               nil
               (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
      (is ex)
      (is (= :ai-generate/parse-failed (:rf.error/id (ex-data ex)))))))

(deftest generate-with-non-machine-spec-throws-invalid-spec
  (testing "resolver returning valid EDN that isn't a machine shape
            throws :invalid-spec"
    (let [resolver (stub-resolver "{:not-a-machine 42}")
          ex (try
               (ai/generate-machine "x" {:resolver resolver})
               nil
               (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
      (is ex)
      (is (= :ai-generate/invalid-spec (:rf.error/id (ex-data ex)))))))

(deftest generate-with-non-map-spec-throws-invalid-spec
  (testing "resolver returning a vector / number / string throws :invalid-spec"
    (doseq [bad ["[]" "42" "\"hello\"" ":keyword"]]
      (let [resolver (stub-resolver bad)
            ex (try
                 (ai/generate-machine "x" {:resolver resolver})
                 nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
        (is ex (str "expected ex for " bad))
        (is (= :ai-generate/invalid-spec (:rf.error/id (ex-data ex))))))))

(deftest generate-rejects-parallel-without-regions
  (testing ":type :parallel without :regions throws :invalid-spec"
    (let [resolver (stub-resolver "{:type :parallel}")
          ex (try
               (ai/generate-machine "x" {:resolver resolver})
               nil
               (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
      (is ex)
      (is (= :ai-generate/invalid-spec (:rf.error/id (ex-data ex)))))))

;; ---------------------------------------------------------------------------
;; End-to-end — generated specs work with the rest of the substrate

(deftest generated-spec-round-trips-through-scxml
  (testing "a spec from generate-machine survives the scxml round-trip"
    (let [resp (fence-clojure (pr-str login-flow-spec))
          out  (ai/generate-machine "a login flow"
                                    {:resolver (stub-resolver resp)})]
      (is (= out (-> out scxml/spec->scxml scxml/scxml->spec))))))
