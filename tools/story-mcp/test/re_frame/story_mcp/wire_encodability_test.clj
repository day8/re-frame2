(ns re-frame.story-mcp.wire-encodability-test
  "Every relayed `ex-data` must survive the JSON encoder (rf2-2z9u3).

  These tests drive the REAL JSON-RPC boundary — `server/run-loop!` over
  in-memory reader/writer, real frames in, raw JSON lines out — and read
  the encoded response, not the handler's return value. That distinction
  is the whole point of the bead: the defect lived BETWEEN the handler
  and the wire. `register-variant` built a perfectly good
  `isError: true` result, then `protocol/write-frame!` hit the live
  `malli.core/Schema` objects riding the registrar's `:explain` slot,
  threw `Cannot JSON encode object of class:
  malli.core$_and_schema$reify$…`, and `server/handle-frame!` turned that
  into a protocol-level `-32603`. A unit test on the handler would have
  passed the whole time.

  Three relay sites carry the same `ex-data`-onto-`:structuredContent`
  pattern and are covered here:

    - `tools/write.cljc`         `register-or-error`   — the headline repro.
    - `tools/recorder.cljc`      `write-back!`         — the sibling.
    - `tools/wire-pipeline.cljc` `invoke-tool`'s catch — the GENERIC arm,
      which relays a whole `ex-data` under `:data` and so fails for ANY
      handler whose throw carries a non-encodable slot.

  Only the first is reachable through the shipped tool surface today: the
  other two re-register bodies the registrar itself produced (already
  valid) or catch throws the handlers already handle. Their tests seam
  the one producer each path trusts — `story/recording->script-body`,
  `story/variant->edn` — so the throw, the relay, the encoder and
  `handle-frame!` are all the real thing while the trigger is forced."
  (:require [cheshire.core :as cheshire]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.story :as story]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.server :as server]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---- fixture --------------------------------------------------------------

(defn- reset-story-and-config [t]
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (story/clear-all!)
  (story/install-canonical-vocabulary!)
  ;; Every test here exercises the write surface; the gate is default-off.
  (config/set-allow-writes! true)
  ;; Mirrors `tools-test`: keep the epoch ring out of the wire payload so a
  ;; tools-root aggregate run doesn't balloon a result past the token cap.
  (rf/configure! {:epoch-history {:depth 0}})
  (t)
  (config/set-allow-writes! false))

(use-fixtures :each reset-story-and-config)

;; ---- the real boundary ----------------------------------------------------

(def ^:private init-frame
  "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")

(defn- call-frame
  [id tool args-json]
  (str "{\"jsonrpc\":\"2.0\",\"id\":" id ",\"method\":\"tools/call\","
       "\"params\":{\"name\":\"" tool "\",\"arguments\":" args-json "}}"))

(defn- drive-raw
  "Feed `frames` through the real `run-loop!` and return the raw response
  LINES, initialize's reply dropped. Raw strings on purpose — a decoded
  value cannot tell you whether the encoder ever ran."
  [& frames]
  (let [in  (java.io.BufferedReader.
              (java.io.StringReader. (str (str/join "\n" frames) "\n")))
        out (java.io.StringWriter.)]
    ;; `server/log!` writes the handler-threw line to stderr; keep the
    ;; suite quiet without hiding it from a failing assertion.
    (binding [*err* (java.io.StringWriter.)]
      (server/run-loop! in out))
    (rest (remove str/blank? (str/split-lines (str out))))))

(defn- call-tool
  "Drive one `tools/call` and return `[raw-line decoded-frame]`."
  [tool args-json]
  (let [line (first (drive-raw init-frame (call-frame 2 tool args-json)))]
    [line (cheshire/parse-string line true)]))

(defn- tool-error?
  "True when `frame` is a JSON-RPC SUCCESS envelope carrying an MCP
  tool-execution error — the shape MCP §Error Handling asks for, and the
  shape a `-32603` protocol fault is not."
  [frame]
  (and (nil? (:error frame))
       (true? (get-in frame [:result :isError]))))

(defn- result-text [frame]
  (get-in frame [:result :content 0 :text]))

(defn- structured [frame]
  (get-in frame [:result :structuredContent]))

;; ---- register-variant: the headline repro ---------------------------------

(deftest register-variant-schema-violation-is-a-tool-error-not-a-server-fault
  (testing "a typo'd variant slot returns isError, not a -32603 server fault"
    (let [[line frame] (call-tool "register-variant"
                                  (str "{\"variant-id\":\"story.button/x\","
                                       "\"body\":{\"compnent\":\"some/view\"}}"))]
      (is (not (str/includes? line "Server fault"))
          "the encoder must not take the response down")
      (is (nil? (:error frame))
          (str "expected a JSON-RPC result envelope, got an error: " line))
      (is (tool-error? frame)
          "a schema violation is a tool error, not a protocol error")

      (testing "the relayed message is the registrar's, and it is actionable"
        (is (str/includes? (result-text frame) ":compnent")
            "the message names the offending slot")
        (is (str/includes? (result-text frame) "did you mean :component?")
            "the message names the nearest declared slot")
        (is (str/includes? (result-text frame) "[:rf.error/variant-shape]")
            "the canonical thrown-error token rides the message"))

      (testing "the explain slot crosses the wire as DATA"
        (is (= {:compnent ["disallowed key"]}
               (:explain-humanized (structured frame)))
            "the humanized projection replaces the live malli schema objects")
        (is (nil? (:explain (structured frame)))
            "the raw, un-encodable :explain slot must not be relayed"))

      (testing "the variant is not registered"
        (is (nil? (story/variant->edn :story.button/x)))))))

(deftest register-variant-mutual-exclusion-violation-crosses-the-wire
  (testing "an `:and`-clause failure (not just an extra key) also encodes"
    ;; The class in the original fault was the `:and` schema's reify, so
    ;; the mutual-exclusion arm is the one that produced it.
    (let [[line frame] (call-tool "register-variant"
                                  (str "{\"variant-id\":\"story.button/xor\","
                                       "\"body\":\"{:script [] :plays []}\"}"))]
      (is (not (str/includes? line "Server fault")))
      (is (tool-error? frame))
      (is (some? (:explain-humanized (structured frame)))
          "the humanized projection carries the schema's own :error/message prose"))))

(deftest register-variant-valid-body-still-registers
  ;; The two-sided control. A fix to the error path that quietly broke the
  ;; happy path would otherwise look identical.
  (testing "a valid body is unaffected by the ex-data projection"
    (let [[_ frame] (call-tool "register-variant"
                               (str "{\"variant-id\":\"story.button/ok\","
                                    "\"body\":{\"doc\":\"a fine variant\"}}"))]
      (is (nil? (:error frame)))
      (is (nil? (get-in frame [:result :isError]))
          "the success envelope carries no isError flag")
      (is (= {:variant-id "story.button/ok" :registered? true}
             (structured frame)))
      (is (some? (story/variant->edn :story.button/ok))
          "the variant really is registered"))))

(deftest register-variant-non-explain-failure-is-unchanged
  ;; The sibling failure path carries no `:explain`, so the projection must
  ;; be a no-op on it. This is also the path rf2-jquiy fixed — its message
  ;; improvement was invisible to a client until this bead landed.
  (testing "an unregistered-tag rejection relays its message untouched"
    (let [[_ frame] (call-tool "register-variant"
                               (str "{\"variant-id\":\"story.button/tagfail\","
                                    "\"body\":\"{:tags #{:nope}}\"}"))]
      (is (tool-error? frame))
      (is (str/includes? (result-text frame) "unregistered tag(s)"))
      (is (str/includes? (result-text frame) "[:rf.error/unknown-tag]"))
      (is (nil? (:explain-humanized (structured frame)))
          "no :explain in the ex-data ⇒ no :explain-humanized on the wire"))))

;; ---- record-as-variant: the sibling relay ---------------------------------

(deftest record-as-variant-write-back-schema-violation-is-a-tool-error
  (testing "the write-back relay projects :explain the same way"
    (story/reg-variant* :story.button/src {:doc "source" :args {}})
    ;; `write-back!` re-registers the SOURCE body with the recorder's own
    ;; play body swapped in, and both halves are registrar-produced — so
    ;; the schema violation has to come from the one producer the path
    ;; trusts. Seam it; everything downstream is real.
    (with-redefs [story/recording->script-body (fn [_ _] "not-a-play-spec")]
      (let [[line frame] (call-tool "record-as-variant"
                                    (str "{\"variant-id\":\"story.button/src\","
                                         "\"write-back\":true,"
                                         "\"new-variant-id\":\"story.button/rec\"}"))]
        (is (not (str/includes? line "Server fault"))
            "the write-back relay must not take the encoder down")
        (is (tool-error? frame))
        (is (str/includes? (result-text frame) "Write-back failed"))
        ;; Two messages: `PlaySpec` is an `:or`, and a string satisfies
        ;; neither branch.
        (is (= {:script ["invalid type" "invalid type"]}
               (:explain-humanized (structured frame))))
        (is (false? (:written-back? (structured frame)))
            "the recorder payload still rides alongside the projection")))))

;; ---- wire-pipeline: the generic arm ---------------------------------------

(deftest handler-throw-carrying-explain-is-a-tool-error
  (testing "invoke-tool's belt-and-braces catch survives a malli-bearing throw"
    (story/reg-variant* :story.button/probe {:doc "probe"})
    ;; The generic catch relays the WHOLE ex-data under `:data`, so it
    ;; fails for any handler throw carrying a non-encodable slot — not
    ;; just the two write surfaces. Force one: a read handler that throws
    ;; the registrar's own shape-violation exception.
    (let [thrown (try (story/reg-variant* :story.button/bad {:compnent :x})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? (:explain (ex-data thrown)))
          "precondition: the registrar throw really does carry a live :explain")
      (with-redefs [story/variant->edn (fn [_] (throw thrown))]
        (let [[line frame] (call-tool "get-variant"
                                      "{\"variant-id\":\"story.button/probe\"}")]
          (is (not (str/includes? line "Server fault"))
              "the generic catch must not itself become a server fault")
          (is (tool-error? frame))
          (is (str/includes? (result-text frame) "Tool handler threw"))
          (is (= {:compnent ["disallowed key"]}
                 (get-in (structured frame) [:data :explain-humanized])))
          (is (= "rf.story/reg-story" (get-in (structured frame) [:data :where]))
              "every other ex-data slot rides through untouched"))))))
