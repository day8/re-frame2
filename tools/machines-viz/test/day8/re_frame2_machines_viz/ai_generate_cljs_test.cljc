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
  - Error modes throw `ex-info` carrying `:rf.error/id :ai-generate/<kw>`
    (the canonical discriminator; the message is the human sentence + token).
  - Generated specs are usable by sibling exporters (`scxml->spec`
    round-trip + `mermaid/emit`), so the AI-generate path connects
    end-to-end with the rest of the substrate."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [clojure.walk :as walk]
            [day8.re-frame2-machines-viz.ai-generate :as ai]
            [day8.re-frame2-machines-viz.scxml :as scxml]))

(defn- deep-strings
  "Every string anywhere in `m` (deep walk) — so a test can assert a
  secret never survives into the error map under any key."
  [m]
  (let [acc (volatile! [])]
    (walk/postwalk
      (fn [x] (when (string? x) (vswap! acc conj x)) x)
      m)
    @acc))

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
;; EP-0011 — error-final completion status (rf2-g89c97)
;;
;; Spec 005 §:final? lets a terminal carry `:error? true` — an error final
;; lowers to the uniform reply envelope as `:status :error` and routes the
;; spawning parent's `:spawn` `:on-error` (vs a plain final's `:status :ok` /
;; `:on-done`). The system prompt must teach `:error? true` for terminal
;; FAILURE outcomes so a generated child machine doesn't silently encode a
;; failure as a success completion.

(deftest system-prompt-teaches-error-final
  (testing "system-prompt teaches :error? true for terminal failure /
            error outcomes, distinct from plain :final? success terminals"
    (is (str/includes? ai/system-prompt ":error?")
        "the prompt names the :error? terminal marker")
    ;; The canonical example must encode a failure terminal as an error
    ;; final — `{:final? true :error? true}` — not a plain success final.
    (is (re-find #":error\?\s+true" ai/system-prompt)
        "the prompt shows :error? true on a terminal")
    (is (str/includes? ai/system-prompt ":on-error")
        "the prompt ties the error terminal to the parent's :on-error routing")))

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
;; EP-0015 — error ex-data carries NO raw LLM payload (rf2-8nzxib)
;;
;; The resolver response can carry user prompt artefacts or LLM-returned
;; secrets/source text, and the parsed spec can embed runtime :data;
;; projection cannot walk ex-data after the fact (Spec 015). The error
;; keeps value-FREE diagnostics (type, length, key set) — never the raw
;; response / stripped text / parsed spec.

(deftest parse-failed-omits-raw-response
  (testing "unparseable-EDN :parse-failed keeps only value-free response/stripped summaries"
    (let [secret   "OPENAI-SK-leaked-key-9f2a"
          ;; A response that fails EDN parsing (unbalanced braces) but
          ;; embeds a secret string the error must not retain.
          resolver (stub-resolver (str "{:leaked \"" secret "\" {{{ not-edn }}}"))
          d        (try (ai/generate-machine "x" {:resolver resolver}) nil
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :ai-generate/parse-failed (:rf.error/id d)) "category preserved")
      (is (not (contains? d :response)) "no raw :response slot")
      (is (not (contains? d :stripped)) "no raw :stripped slot")
      (is (some? (:response-summary d)) "value-free response summary present")
      (is (not (some #(str/includes? % secret) (deep-strings d)))
          "the secret must not survive anywhere in ex-data"))))

(deftest non-string-response-omits-raw-response
  (testing "non-string resolver response keeps only a value-free summary"
    (let [secret   "card-number-4111111111111111"
          resolver (fn [_] {:leak secret})            ;; non-string → parse-failed
          d        (try (ai/generate-machine "x" {:resolver resolver}) nil
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :ai-generate/parse-failed (:rf.error/id d)))
      (is (not (contains? d :response)))
      (is (not (some #(str/includes? % secret) (deep-strings d)))
          "the secret must not survive anywhere in ex-data"))))

(deftest invalid-spec-omits-raw-parsed-spec
  (testing "invalid-spec keeps only a value-free spec summary, not the parsed spec"
    (let [secret   "ssn-123-45-6789"
          ;; Parses as EDN (a map) but is not a valid machine spec, and
          ;; embeds a secret-bearing :data slot.
          resolver (stub-resolver (str "{:not-a-machine true :data {:ssn \"" secret "\"}}"))
          d        (try (ai/generate-machine "x" {:resolver resolver}) nil
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :ai-generate/invalid-spec (:rf.error/id d)) "category preserved")
      (is (not (contains? d :spec)) "no raw :spec slot")
      (is (not (contains? d :response)) "no raw :response slot")
      (is (some? (:spec-summary d)) "value-free spec summary present")
      (is (not (some #(str/includes? % secret) (deep-strings d)))
          "the secret must not survive anywhere in ex-data"))))

;; ---------------------------------------------------------------------------
;; End-to-end — generated specs work with the rest of the substrate

(deftest generated-spec-round-trips-through-scxml
  (testing "a spec from generate-machine survives the scxml round-trip"
    (let [resp (fence-clojure (pr-str login-flow-spec))
          out  (ai/generate-machine "a login flow"
                                    {:resolver (stub-resolver resp)})]
      (is (= out (-> out scxml/spec->scxml scxml/scxml->spec))))))
