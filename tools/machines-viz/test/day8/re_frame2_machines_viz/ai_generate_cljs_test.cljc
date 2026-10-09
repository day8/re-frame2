(ns day8.re-frame2-machines-viz.ai-generate-cljs-test
  "AI generation through an injected resolver: prompt composition, every
  response form the extractor accepts, and the documented error ids, whose
  ex-data never carries the LLM's payload."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.ai-generate :as ai]))

(def login-flow-spec
  {:initial :idle
   :states  {:idle          {:on {:login :loading}}
             :loading       {:on {:ok :authenticated :err :failed}}
             :authenticated {:final? true}
             :failed        {:final? true}}})

(defn- thrown-data
  "The ex-data `generate-machine` throws when `resolver` answers."
  [resolver]
  (try (ai/generate-machine "x" {:resolver resolver})
       nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e))))

(deftest build-prompt-includes-user-request
  (let [out (ai/build-prompt "a login flow")]
    (is (< (str/index-of out ai/system-prompt)
           (str/index-of out "a login flow")))))

;; spec/API.md claims the prompt teaches an error final: a FAILURE terminal
;; is `{:final? true :error? true}` and routes the parent's `:on-error`.
(deftest system-prompt-teaches-error-final
  (is (re-find #":error\?\s+true" ai/system-prompt))
  (is (str/includes? ai/system-prompt ":on-error")))

(deftest generate-accepts-each-response-form
  (testing "a ```clojure fence behind prose (the shape the system prompt asks
            for), an ```edn fence, an untagged fence, and bare EDN"
    (let [edn (pr-str login-flow-spec)]
      (doseq [[label resp]
              [["```clojure fence behind prose" (str "Here's the spec:\n\n```clojure\n" edn "\n```\n\nRefine freely.")]
               ["```edn fence"                  (str "```edn\n" edn "\n```")]
               ["untagged fence behind prose"   (str "Here is the machine:\n\n```\n" edn "\n```\n")]
               ["bare EDN"                      edn]]]
        (is (= login-flow-spec
               (ai/generate-machine "a login flow" {:resolver (constantly resp)}))
            label)))))

(deftest generate-without-resolver-throws-no-resolver
  (is (= :ai-generate/no-resolver
         (try (ai/generate-machine "a login flow")
              nil
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                (:rf.error/id (ex-data e)))))))

(deftest generate-malformed-responses-throw-their-documented-ids
  (doseq [edn ["[]" "{:not-a-machine 42}" "{:type :parallel}"]]
    (is (= :ai-generate/invalid-spec (:rf.error/id (thrown-data (constantly edn)))) edn)))

(deftest parse-failed-omits-raw-response
  (testing "an LLM response is untrusted: unparseable EDN and a non-string
            response throw :parse-failed with a value-free summary only"
    (doseq [[secret resolver]
            [["OPENAI-SK-leaked-key-9f2a"
              (constantly "{:leaked \"OPENAI-SK-leaked-key-9f2a\" {{{ not-edn }}}")]
             ["card-number-4111111111111111"
              (constantly {:leak "card-number-4111111111111111"})]]]
      (let [d (thrown-data resolver)]
        (is (= :ai-generate/parse-failed (:rf.error/id d)) secret)
        (is (some? (:response-summary d)) secret)
        (is (not (str/includes? (pr-str d) secret)) secret)))))
