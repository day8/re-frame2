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

  The generic arm is the one that has to be TOTAL (rf2-ia904). Naming
  `:explain` closed the reachable producer and left `{:opaque (Object.)}`
  reaching Cheshire under any other key, so `wire-safe-ex-data` now
  projects by SHAPE rather than by slot. Its tests plant opaque values
  where no slot roster would look — under a non-`:explain` key, three
  levels down, and in KEY position, which does not even throw: Cheshire
  `str`s an opaque key and leaks its address into a JSON member name.

  Only the first is reachable through the shipped tool surface today: the
  other two re-register bodies the registrar itself produced (already
  valid) or catch throws the handlers already handle. Their tests seam
  the one producer each path trusts — `story/recording->script-body`,
  `story/variant->edn` — so the throw, the relay, the encoder and
  `handle-frame!` are all the real thing while the trigger is forced.

  ## The second contract on this boundary: the message a consumer AI READS

  Encodability is necessary but not sufficient. rf2-jquiy established
  that the SAME relays ship `(ex-message e)` verbatim to an MCP client —
  an AI agent acting on the text — so the message itself is a consumer
  contract, not tool-internal prose. A bare `(str error-kw)` shipped that
  agent `:rf.error/variant-id-shape` and discarded the human sentence
  sitting in `:reason`. Every throw reachable here must therefore carry
  the canonical Spec 009 shape: a human sentence PLUS a trailing
  `[:rf.error/<id>]` greppability token.

  That contract is what the token assertions below pin. It was previously
  proven only for the two REGISTRAR families (`variant-shape` via
  `register-variant`, `unknown-tag` via the tag-membership check), which
  left the other consumer-reachable producer — `re-frame.story.plan/fail!`,
  seventeen call sites reached through `explain-variant` — with no
  regression at all: reverting any of its messages to a bare `(str id)`
  was a green change. The plan-failure table at the foot of this ns closes
  that gap. `tools/` is bundle-isolated and MUST NOT `:require
  re-frame.error`, so these messages are hand-rolled at each throw and a
  shared builder cannot enforce them — only a boundary assertion can."
  (:require [cheshire.core :as cheshire]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.error]
            [re-frame.core :as rf]
            [re-frame.story :as story]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.server :as server]
            [re-frame.story-mcp.tools.result :as result]
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

      (testing "the machine-readable error id crosses the wire (rf2-2nbck)"
        (is (= "rf.error/variant-shape" (:rf.error (structured frame)))
            "the registrar's :rf.error/id populates the wire's :rf.error slot"))

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
          "no :explain in the ex-data ⇒ no :explain-humanized on the wire")
      ;; rf2-2nbck: the discriminator must ALSO ride the structured slot,
      ;; not only the message. A keyword value JSON-encodes to a plain
      ;; string, so this is the wire spelling, not the EDN one.
      (is (= "rf.error/unknown-tag" (:rf.error (structured frame)))
          "the registrar's :rf.error/id populates the wire's :rf.error slot"))))

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
            "the recorder payload still rides alongside the projection")
        ;; rf2-2nbck: the sibling relay carries the same slot.
        (is (= "rf.error/variant-shape" (:rf.error (structured frame)))
            "the registrar's :rf.error/id populates the wire's :rf.error slot")))))

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

;; ---- wire-pipeline: ARBITRARY ex-data, not just the named slot -------------
;;
;; rf2-ia904. Naming `:explain` fixed the reachable producer and left the
;; generic promise unmet: `invoke-tool`'s catch relays a WHOLE `ex-data`, so
;; the next opaque value simply arrived under a different key. The rule is now
;; the value's SHAPE — anything outside the EDN value space becomes a bounded
;; marker naming its class — so these tests plant opaque values where no slot
;; roster would have looked: a non-`:explain` key, a nested one, and a KEY.

(def ^:private address-in-line
  "A `java.lang.Object@2c6aed22`-style identity hash. Cheshire does NOT
  throw on an opaque map KEY — it `str`s it — so the key half of this
  fails silently, and only an assertion on the raw bytes catches it."
  #"@[0-9a-f]{6,}")

(defn- throwing-get-variant
  "Drive the real `get-variant` boundary with `thrown` coming out of the
  one producer the read handler trusts, and return `[raw-line frame]`."
  [thrown]
  (story/reg-variant* :story.button/probe {:doc "probe"})
  (with-redefs [story/variant->edn (fn [_] (throw thrown))]
    (call-tool "get-variant" "{\"variant-id\":\"story.button/probe\"}")))

(deftest handler-throw-with-opaque-ex-data-outside-explain-is-a-tool-error
  (testing "an opaque value under a NON-:explain key still returns isError"
    ;; The bead's headline repro, verbatim: before the shape rule this line
    ;; was {"error":{"code":-32603,"message":"Server fault: Cannot JSON
    ;; encode object of class: class java.lang.Object …"}}.
    (let [[line frame] (throwing-get-variant
                         (ex-info "opaque throw" {:opaque (Object.)}))]
      (is (not (str/includes? line "Server fault"))
          "the generic catch must contain an arbitrary ex-data, not just a malli one")
      (is (nil? (:error frame))
          (str "expected a JSON-RPC result envelope, got an error: " line))
      (is (tool-error? frame))
      (is (str/includes? (result-text frame) "opaque throw")
          "the handler's own message still reaches the agent")
      (is (= {:rf.story-mcp/unencodable "java.lang.Object"}
             (get-in (structured frame) [:data :opaque]))
          "the opaque value is a bounded marker naming its class")
      (is (nil? (re-find address-in-line line))
          "and the marker carries the class only — never an object address"))))

(deftest handler-throw-with-nested-and-keyed-opaque-values-is-a-tool-error
  (testing "depth and key position are covered by the same rule"
    (let [[line frame] (throwing-get-variant
                         (ex-info "deep throw"
                                  {:ctx    {:layers [{:handle (Object.)} :ok]}
                                   (Object.) :opaque-key
                                   :reason "still readable"}))
          data (:data (structured frame))]
      (is (not (str/includes? line "Server fault")))
      (is (tool-error? frame))
      (is (= {:rf.story-mcp/unencodable "java.lang.Object"}
             (get-in data [:ctx :layers 0 :handle]))
          "a value nested three levels down is projected too")
      (is (= "ok" (get-in data [:ctx :layers 1]))
          "its encodable siblings are untouched")
      (is (= "still readable" (:reason data))
          "and so is every sibling slot")
      (is (nil? (re-find address-in-line line))
          "an opaque KEY does not throw — Cheshire strs it — so the only
           evidence of the leak is the raw line"))))

(deftest handler-throw-with-ordinary-ex-data-is-relayed-verbatim
  ;; The control. A projection that made everything a marker would pass the
  ;; two tests above and be useless.
  (testing "plain data crosses unchanged"
    (let [[_ frame] (throwing-get-variant
                      (ex-info "plain throw" {:reason  "not found"
                                              :where   :rf.story/get-variant
                                              :ids     [:a/b :c/d]
                                              :count   3
                                              :nested  {:ok true}}))
          data (:data (structured frame))]
      (is (tool-error? frame))
      (is (= {:reason "not found"
              :where  "rf.story/get-variant"
              :ids    ["a/b" "c/d"]
              :count  3
              :nested {:ok true}}
             data)
          "every slot is its own JSON encoding of the EDN value, marker-free"))))

(deftest wire-safe-ex-data-output-always-encodes
  ;; The fast guard under the three boundary tests. It cannot replace them —
  ;; the defect lived between the handler and the wire, and only `run-loop!`
  ;; covers that — but it fails in milliseconds when the rule regresses, and
  ;; it can enumerate more shapes than it is worth driving a server for.
  (testing "no ex-data shape survives the projection un-encodable"
    (doseq [[label d] [["opaque"      {:opaque (Object.)}]
                       ["nested"      {:a [{:b #{(Object.)}}]}]
                       ["opaque key"  {(Object.) 1}]
                       ["throwable"   {:cause (ex-info "boom" {:x 1})}]
                       ["regex"       {:re #"x"}]
                       ["fn"          {:f (fn [] 1)}]
                       ["atom"        {:a (atom 1)}]
                       ["tagged EDN"  {:u (java.util.UUID/randomUUID)
                                       :d (java.util.Date. 0)}]
                       ["nil"         nil]]]
      (is (string? (cheshire/generate-string
                     (result/wire-safe-ex-data d)))
          (str "wire-safe-ex-data output must encode: " label)))))

;; ---- plan failures: the message contract, driven end to end ---------------
;;
;; rf2-jquiy. `re-frame.story.plan/fail!` is the second consumer-reachable
;; throw producer on this surface, and until now the ONLY thing standing
;; behind its messages was a docstring. These rows drive `explain-variant`
;; through the real `run-loop!` — no `with-redefs`, no seam: the trigger is
;; an ordinary variant registration whose `:extends` cannot resolve, which
;; is exactly what a consumer AI does by hand when it mistypes a parent id.
;;
;; `reg-variant*` stores the raw body with `:extends` INTACT and resolves
;; nothing (the plan compiler is the single merge authority — spec/017
;; §8.4), so registration SUCCEEDS and the failure surfaces later, at
;; explain time, through the wire-pipeline's generic catch. That split is
;; why a registration-time test could never have covered this.

(defn- explain-frame
  "Register `bodies` (a map of variant-id → body), then drive the real
  `explain-variant` boundary for `target` and return the decoded frame."
  [bodies target]
  (doseq [[id body] bodies]
    (story/reg-variant* id body))
  (second (call-tool "explain-variant"
                     (str "{\"variant-id\":\"" (subs (str target) 1) "\"}"))))

(deftest plan-failure-messages-are-consumer-readable-at-the-wire
  ;; ONE table, three families, three assertions each — the shape the
  ;; rf2-jquiy audit asked for. A mutation of any of these messages back to
  ;; a bare `(str id)` reds the `human` row; dropping the trailing token
  ;; reds the `token` row; a regression that turns the tool error back into
  ;; a protocol fault reds `tool-error?` on every row at once.
  (doseq [{:keys [label bodies target human token]}
          [{:label  "an :extends naming an unregistered parent"
            :bodies {:story.button/orphan {:doc "child" :extends :story.button/ghost}}
            :target :story.button/orphan
            :human  #":extends references unregistered variant"
            :token  "[:rf.error/story-extends-unknown]"}

           {:label  "an :extends cycle"
            :bodies {:story.button/loop-a {:doc "a" :extends :story.button/loop-b}
                     :story.button/loop-b {:doc "b" :extends :story.button/loop-a}}
            :target :story.button/loop-a
            :human  #":extends cycle through"
            :token  "[:rf.error/story-extends-cycle]"}

           ;; A THIRD producer, deliberately not an `:extends` one: the two
           ;; above share `resolve-source-chain`, so on their own they would
           ;; pin one function rather than the family. `:compose` fails in a
           ;; different arm of the compiler and reaches `fail!` independently.
           {:label  "a :compose naming an unregistered fragment"
            :bodies {:story.button/nofrag {:doc "c" :compose [:fragment.button/ghost]}}
            :target :story.button/nofrag
            :human  #":compose on .* references unregistered fragment/check"
            :token  "[:rf.error/story-compose-unknown]"}]]
    (testing label
      (let [frame (explain-frame bodies target)
            text  (result-text frame)]
        (is (tool-error? frame)
            (str label ": a plan failure is a tool error, not a -32603 protocol fault"))
        (is (re-find human text)
            (str label ": the message carries the human sentence, not just a keyword\n"
                 "  got: " (pr-str text)))
        (is (str/includes? text token)
            (str label ": the canonical [:rf.error/…] token rides the message\n"
                 "  got: " (pr-str text)))))))

(deftest plan-failure-ex-data-still-carries-the-machine-id
  ;; The message is for the agent to READ; `:rf.error/id` is for it to
  ;; BRANCH on. Both, or the shape is half-delivered.
  (testing "the generic catch relays the discriminator alongside the prose"
    (let [frame (explain-frame
                  {:story.button/orphan2 {:doc "child" :extends :story.button/ghost2}}
                  :story.button/orphan2)]
      (is (tool-error? frame))
      (is (= "rf.error/story-extends-unknown"
             (:rf.error (:data (structured frame))))
          "the plan's :rf.error/id survives wire-safe-ex-data into :data")
      (is (= "story.button/ghost2" (:parent (:data (structured frame))))
          "and the actionable slot — WHICH parent is missing — rides with it"))))

(deftest explain-variant-happy-path-is-unaffected
  ;; The two-sided control, matching `register-variant-valid-body-still-registers`
  ;; above: an error-path change that broke ordinary explains would otherwise
  ;; look identical to a pass.
  (testing "a resolvable :extends chain still explains"
    (let [frame (explain-frame
                  {:story.button/base  {:doc "base"}
                   :story.button/child {:doc "child" :extends :story.button/base}}
                  :story.button/child)]
      (is (nil? (:error frame)))
      (is (nil? (get-in frame [:result :isError]))
          "the success envelope carries no isError flag")
      (is (some? (structured frame))
          "and the explain projection really is returned"))))

(deftest wire-safe-ex-data-cannot-itself-throw
  ;; The projection runs INSIDE the caller's catch, so a throw here would be
  ;; the same -32603 by another route.
  (testing "a humanizer that throws degrades to a bounded marker"
    (with-redefs [malli.error/humanize (fn [_] (throw (ex-info "humanize blew up" {})))]
      (let [projected (result/wire-safe-ex-data {:explain {:schema :whatever}
                                                 :reason  "x"})]
        (is (= "clojure.lang.ExceptionInfo"
               (:rf.story-mcp/unencodable projected))
            "the guard names what failed")
        (is (string? (cheshire/generate-string projected))
            "and its fallback encodes")))))
