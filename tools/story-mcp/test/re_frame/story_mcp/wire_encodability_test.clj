(ns re-frame.story-mcp.wire-encodability-test
  "Every relayed `ex-data`, and every success payload, must survive the JSON
  encoder.

  These tests drive the REAL JSON-RPC boundary — `run-loop!` over an
  in-memory reader/writer, real frames in, raw JSON lines out — and read the
  encoded response, not the handler's return value, because the failure mode
  lives BETWEEN the handler and the wire: a handler can build a perfectly
  good `isError: true` result whose live `malli.core/Schema` objects then
  make `protocol/write-frame!` throw, which `handle-frame!` turns into a
  protocol-level `-32603`.

  The relays are `register-or-error` (tools/write.cljc) and `invoke-tool`'s
  generic catch (tools/wire_pipeline.cljc), which relays a whole `ex-data`
  and so has to be TOTAL: `wire-safe-ex-data` projects by SHAPE, not by
  slot. Success data goes through `wire-safe-success` in `edn-result`.

  The relayed `(ex-message e)` is read by an AI agent, so it is a consumer
  contract too: every throw reachable here carries a human sentence plus a
  trailing `[:rf.error/<id>]` token. `tools/` is bundle-isolated and must not
  `:require re-frame.error`, so those messages are hand-rolled at each throw
  and only a boundary assertion can hold them."
  (:require [cheshire.core :as cheshire]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.error]
            [re-frame.core :as rf]
            [re-frame.story :as rf.story]
            [re-frame.story-mcp.config :as rf.story-mcp.config]
            [re-frame.story-mcp.server :as rf.story-mcp.server]
            [re-frame.story-mcp.tools.result :as rf.story-mcp.tools.result]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-story-and-config [t]
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (rf.story-mcp.config/set-allow-writes! true)
  ;; Keep the epoch ring out of the wire payload on any classpath that
  ;; carries `re-frame.epoch`, so a run result cannot balloon past the cap.
  (rf/configure! {:epoch-history {:depth 0}})
  (t)
  (rf.story-mcp.config/set-allow-writes! false))

(use-fixtures :each reset-story-and-config)

(def ^:private init-frame
  "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")

(defn- call-tool
  "Drive one `tools/call` through the real `run-loop!` and return
  `[raw-line decoded-frame]`. The raw line matters: a decoded value cannot
  show whether the encoder leaked an identity hash."
  [tool args-json]
  (let [frame (str "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                   "\"params\":{\"name\":\"" tool "\",\"arguments\":" args-json "}}")
        in    (java.io.BufferedReader. (java.io.StringReader. (str init-frame "\n" frame "\n")))
        out   (java.io.StringWriter.)]
    (binding [*err* (java.io.StringWriter.)]
      (rf.story-mcp.server/run-loop! in out))
    (let [line (second (remove str/blank? (str/split-lines (str out))))]
      [line (cheshire/parse-string line true)])))

(defn- tool-error?
  "A JSON-RPC SUCCESS envelope carrying an MCP tool-execution error — the
  shape a `-32603` protocol fault is not."
  [frame]
  (and (nil? (:error frame))
       (true? (get-in frame [:result :isError]))))

(defn- result-text [frame]
  (get-in frame [:result :content 0 :text]))

(defn- structured [frame]
  (get-in frame [:result :structuredContent]))

(def ^:private address-in-line
  "A `java.lang.Object@2c6aed22`-style identity hash. Cheshire `str`s an
  opaque map KEY instead of throwing, so only the raw bytes show the leak."
  #"@[0-9a-f]{6,}")

(deftest register-variant-schema-violation-is-a-tool-error-not-a-server-fault
  (let [[_ frame] (call-tool "register-variant"
                             (str "{\"variant-id\":\"story.button/x\","
                                  "\"body\":{\"compnent\":\"some/view\"}}"))]
    (is (tool-error? frame) "a schema violation is a tool error, not a -32603 protocol fault")
    (is (str/includes? (result-text frame) "[:rf.error/variant-shape]")
        "the registrar's message is relayed, canonical token included")
    (is (= {:explain-humanized {:compnent ["disallowed key"]}
            :rf.error          "rf.error/variant-shape"}
           (select-keys (structured frame) [:explain :explain-humanized :rf.error]))
        "the live :explain crosses as its humanized projection, and the error id rides as :rf.error")))

(defn- throwing-get-variant
  "Drive the real `get-variant` boundary with `thrown` coming out of the one
  producer the read handler trusts, and return `[raw-line frame]`."
  [thrown]
  (rf.story/reg-variant* :story.button/probe {:doc "probe"})
  (with-redefs [rf.story/variant->edn (fn [_] (throw thrown))]
    (call-tool "get-variant" "{\"variant-id\":\"story.button/probe\"}")))

(deftest handler-throw-with-nested-and-keyed-opaque-values-is-a-tool-error
  (let [[line frame] (throwing-get-variant
                       (ex-info "deep throw"
                                {:ctx      {:layers [{:handle (Object.)} :ok]}
                                 (Object.) :opaque-key
                                 :reason   "still readable"}))
        data         (:data (structured frame))]
    (is (tool-error? frame))
    (is (= [{:rf.story-mcp/unencodable "java.lang.Object"} "ok" "still readable"]
           [(get-in data [:ctx :layers 0 :handle]) (get-in data [:ctx :layers 1]) (:reason data)])
        "a value three levels down is projected; its encodable siblings are untouched")
    (is (nil? (re-find address-in-line line)) "an opaque KEY leaks no identity hash")))

(deftest handler-throw-with-ordinary-ex-data-is-relayed-verbatim
  ;; The control: a projection that made everything a marker would pass the
  ;; nested-and-keyed test above.
  (let [[_ frame] (throwing-get-variant
                    (ex-info "plain throw" {:reason "not found"
                                            :where  :rf.story/get-variant
                                            :ids    [:a/b :c/d]
                                            :count  3
                                            :nested {:ok true}}))]
    (is (= {:reason "not found" :where "rf.story/get-variant" :ids ["a/b" "c/d"] :count 3 :nested {:ok true}}
           (:data (structured frame))))))

(deftest plan-failure-messages-are-consumer-readable-at-the-wire
  ;; `re-frame.story.plan/fail!` throws at explain time, through the generic
  ;; catch. Three producers: the two `:extends` rows share
  ;; `resolve-source-chain`; `:compose` fails in a different arm.
  (doseq [{:keys [label bodies target human token]}
          [{:label  "an :extends naming an unregistered parent"
            :bodies {:story.button/orphan {:doc "child" :extends :story.button/ghost}}
            :target "story.button/orphan"
            :human  #":extends references unregistered variant"
            :token  "[:rf.error/story-extends-unknown]"}
           {:label  "an :extends cycle"
            :bodies {:story.button/loop-a {:doc "a" :extends :story.button/loop-b}
                     :story.button/loop-b {:doc "b" :extends :story.button/loop-a}}
            :target "story.button/loop-a"
            :human  #":extends cycle through"
            :token  "[:rf.error/story-extends-cycle]"}
           {:label  "a :compose naming an unregistered fragment"
            :bodies {:story.button/nofrag {:doc "c" :compose [:fragment.button/ghost]}}
            :target "story.button/nofrag"
            :human  #":compose on .* references unregistered fragment/check"
            :token  "[:rf.error/story-compose-unknown]"}]]
    (testing label
      (doseq [[id body] bodies] (rf.story/reg-variant* id body))
      (let [text (result-text (second (call-tool "explain-variant" (str "{\"variant-id\":\"" target "\"}"))))]
        (is (re-find human text) (str "the human sentence, not just a keyword; got: " (pr-str text)))
        (is (str/includes? text token) (str "the canonical token; got: " (pr-str text)))))))

(deftest wire-safe-ex-data-cannot-itself-throw
  ;; The projection runs inside the caller's catch, so a throw here would be
  ;; the same -32603 by another route.
  (with-redefs [malli.error/humanize (fn [_] (throw (ex-info "humanize blew up" {})))]
    (let [projected (rf.story-mcp.tools.result/wire-safe-ex-data {:explain {:schema :whatever} :reason "x"})]
      (is (= "clojure.lang.ExceptionInfo" (:rf.story-mcp/unencodable projected)))
      (is (string? (cheshire/generate-string projected))))))

;; `[:assert-db path :pred fn-or-sym]` is a supported Story authoring form, so
;; a live fn legitimately sits in SUCCESS data: the registered body, its
;; explain plan and the run's assertion evidence.

(def ^:private predicate-body
  {:doc     "predicate assertion"
   :db-seed {:count 1}
   ;; Two keys `wire-safe-ex-data` rewrites on an exception payload; success
   ;; data is not an exception, so they ride exactly as written.
   :args    {:explain "author-explain" :rf.error/id "author-literal"}
   :script  [[:assert-db [:count] :pred pos?]]})

(def ^:private pos-marker
  {:rf.story-mcp/unencodable "clojure.core$pos_QMARK_"})

(defn- call-success-tool
  "Drive `tool` on `variant-id` through the real boundary, uncapped."
  [tool variant-id dedup?]
  (call-tool tool (str "{\"variant-id\":\"" variant-id "\",\"max-tokens\":0"
                       (when-not dedup? ",\"dedup\":false") "}")))

(deftest callable-in-a-success-payload-crosses-as-a-marker
  ;; `edn-result` is the one chokepoint for every data-returning tool; the
  ;; run adds the egress walk and, for dedup, a re-stringified text slot.
  (rf.story/reg-variant* :story.button/predicate predicate-body)
  (doseq [[tool dedup?] [["get-variant" false] ["run-variant" true] ["run-variant" false]]]
    (testing (str tool " dedup=" dedup?)
      (let [[line frame] (call-success-tool tool "story.button/predicate" dedup?)]
        (is (= [nil nil true false]
               [(:error frame) (get-in frame [:result :isError])
                (str/includes? line "clojure.core$pos_QMARK_") (str/includes? line "#object")])
            (str "a success naming the callable by its class, no raw printed object: " line))))))

(deftest instant-in-a-success-payload-crosses-as-a-marker
  ;; `inst?` is true of a `java.time.Instant`, which Cheshire cannot write; a
  ;; `java.util.Date` in the same slot is the control.
  (rf/reg-event :probe/stamp-instant
    (fn [{:keys [db]} _] {:db (assoc db :stamped-at (java.time.Instant/ofEpochMilli 0))}))
  (rf/reg-event :probe/stamp-date
    (fn [{:keys [db]} _] {:db (assoc db :stamped-at (java.util.Date. 0))}))
  (rf.story/reg-variant* :story.probe/instant {:doc "instant" :script [[:dispatch-sync [:probe/stamp-instant]]]})
  (rf.story/reg-variant* :story.probe/date {:doc "date" :script [[:dispatch-sync [:probe/stamp-date]]]})
  (doseq [[vid stamped] [["story.probe/instant" {:rf.story-mcp/unencodable "java.time.Instant"}]
                         ["story.probe/date" "1970-01-01T00:00:00Z"]]]
    (let [[line frame] (call-success-tool "run-variant" vid false)]
      (is (= ["pass" stamped]
             [(:status (structured frame)) (get-in (structured frame) [:app-db :stamped-at])])
          line))))

(deftest callable-projection-keeps-the-surrounding-data
  (rf.story/reg-variant* :story.button/predicate predicate-body)
  (let [body (:body (structured (second (call-success-tool "get-variant" "story.button/predicate" false))))]
    (is (= [1 pos-marker {:explain "author-explain" :rf.error/id "author-literal"}]
           [(get-in body [:db-seed :count]) (get-in body [:script 0 3]) (:args body)])
        "the body survives, the callable is marked in place, and author keys are not reinterpreted"))
  (testing "the text slot is readable EDN carrying the same marker"
    (let [[_ frame] (call-success-tool "variant->edn" "story.button/predicate" false)]
      (is (= pos-marker (get-in (edn/read-string (result-text frame)) [:script 0 3]))))))

;; `clojure.walk` rebuilds a record by conj-ing the walked entries back into
;; the ORIGINAL record, so a respelled extension key would gain its marker
;; while the raw callable key stayed beside it and encoded with its identity
;; hash.

(defrecord Sample [v])

(def ^:private record-args
  {:record (assoc (->Sample pos?) pos? :extension)
   :shapes {:list '(1 2 3) :set #{:a :b} :vec [1 [2 3]]}})

(deftest record-extension-key-crosses-get-variant-as-a-marker
  (rf.story/reg-story :story.audit {})
  (rf.story/reg-variant* :story.audit/record {:args record-args})
  (let [[line frame] (call-success-tool "get-variant" "story.audit/record" false)
        read-back    (edn/read-string {:default tagged-literal} (result-text frame))
        rec          (get-in read-back [:body :args :record])
        shapes       (get-in read-back [:body :args :shapes])]
    (is (= [nil nil] [(:error frame) (get-in frame [:result :isError])]) line)
    (is (not (str/includes? line "#object")))
    (is (nil? (re-find address-in-line line)) "no identity hash, which is how a surviving key encodes")
    (is (= 2 (count (get-in (structured frame) [:body :args :record]))) "the structured record has no third entry")
    (is (= [(symbol (.getName Sample)) {:v pos-marker pos-marker :extension}]
           [(:tag rec) (:form rec)])
        "the text slot keeps the record's type; the field is marked and the respelled key REPLACES its original")
    (is (= [(:shapes record-args) [true true true]]
           [shapes [(list? (:list shapes)) (set? (:set shapes)) (vector? (:vec shapes))]])
        "data-only shapes survive, each as its own collection type")))
