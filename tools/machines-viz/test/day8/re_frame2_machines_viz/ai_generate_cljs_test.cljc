(ns day8.re-frame2-machines-viz.ai-generate-cljs-test
  "Pure-data tests for the AI-generate surface.

  Coverage:

  - `build-prompt` composes the system prompt and the user request
    into a single string.
  - `generate-machine` with a stub resolver returns the parsed spec
    for canned responses (the LLM seam is the injected resolver;
    tests inject a deterministic stub).
  - `generate-machine` handles every fence form the extractor accepts
    (```clojure / ```edn / ```cljs / an untagged ```) and an unfenced
    response.
  - Error modes throw `ex-info` carrying `:rf.error/id :ai-generate/<kw>`
    (the canonical discriminator; the message is the human sentence + token)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [clojure.walk :as walk]
            [day8.re-frame2-machines-viz.ai-generate :as ai]))

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
;; EP-0011 — error-final completion status
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

(deftest generate-accepts-each-response-form
  (testing "every response form the extractor accepts parses to the spec: a
            ```clojure fence behind prose (the shape the system prompt asks
            for), bare EDN, an ```edn fence, and untagged and ```cljs fences"
    (let [edn (pr-str login-flow-spec)]
      (doseq [[label resp]
              [["```clojure fence behind prose" (fence-clojure edn)]
               ["bare EDN"                      edn]
               ["```edn fence"                  (str "```edn\n" edn "\n```")]
               ["untagged fence behind prose"   (str "Here is the machine:\n\n```\n" edn "\n```\n")]
               ["```cljs fence behind prose"    (str "Here is the machine:\n\n```cljs\n" edn "\n```\n")]]]
        (is (= login-flow-spec
               (ai/generate-machine "a login flow" {:resolver (stub-resolver resp)}))
            label)))))

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

(deftest generate-malformed-responses-throw-their-documented-ids
  (testing "a response that is not a string or not EDN throws :parse-failed;
            parseable EDN that is not a machine shape throws :invalid-spec"
    (doseq [[label resolver id]
            [["non-string response"        (fn [_] 42)                                       :ai-generate/parse-failed]
             ["unparseable EDN"            (stub-resolver "```clojure\n{{{ not-edn }}}\n```") :ai-generate/parse-failed]
             ["a non-machine map"          (stub-resolver "{:not-a-machine 42}")             :ai-generate/invalid-spec]
             ["a vector"                   (stub-resolver "[]")                              :ai-generate/invalid-spec]
             ["a number"                   (stub-resolver "42")                              :ai-generate/invalid-spec]
             ["a string"                   (stub-resolver "\"hello\"")                       :ai-generate/invalid-spec]
             ["a keyword"                  (stub-resolver ":keyword")                        :ai-generate/invalid-spec]
             [":parallel without :regions" (stub-resolver "{:type :parallel}")               :ai-generate/invalid-spec]]]
      (let [ex (try
                 (ai/generate-machine "x" {:resolver resolver})
                 nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
        (is (= id (:rf.error/id (ex-data ex))) label)))))

;; ---------------------------------------------------------------------------
;; EP-0015 — error ex-data carries NO raw LLM payload
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
