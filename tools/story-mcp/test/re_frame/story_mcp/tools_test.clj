(ns re-frame.story-mcp.tools-test
  "Per-tool semantics + the server dispatcher's `initialize` / `tools/list`
  / `tools/call` plumbing.

  Tests boot Story's canonical vocabulary in a per-test fixture so the
  registrar carries the seven canonical tags + the lifecycle machine,
  then register a small fixture story + variant so each tool has
  something to read."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.recorder :as recorder]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.protocol :as proto]
            [re-frame.story-mcp.server :as server]
            [re-frame.story-mcp.tools :as tools]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-story-and-config
  "Each test gets a fresh Story registry + write-gate set to false (the
  documented default per IMPL-SPEC §7.3). Tests that need writes flip
  the gate explicitly."
  [t]
  (story/clear-all!)
  (story/install-canonical-vocabulary!)
  (config/set-allow-writes! false)
  ;; Recorder atom is per-process — clear between tests so a previous
  ;; test's captured events don't bleed in.
  (recorder/clear!)
  ;; Fixture story + variant.
  (story/reg-story :story.button
    {:doc       "A clickable button."
     :component :app.ui/button
     :tags      #{:dev :docs}
     :args      {:label "Click me"}})
  (story/reg-variant :story.button/primary
    {:doc  "Primary button."
     :args {:label "Save"}
     :tags #{:dev}})
  (story/reg-variant :story.button/secondary
    {:doc  "Secondary button."
     :args {:label "Cancel"}
     :tags #{:docs}})
  (story/reg-mode :Mode.theme/dark
    {:doc  "Dark theme."
     :args {:theme :dark}})
  (t))

(use-fixtures :each reset-story-and-config)

;; ---- helpers -------------------------------------------------------------

(defn- invoke
  "Invoke a tool by name. Returns the result map (success or error)."
  [tool-name args]
  (tools/invoke-tool tool-name args))

(defn- success? [result]
  (and (map? result)
       (vector? (:content result))
       (not (true? (:isError result)))))

(defn- error? [result]
  (and (map? result)
       (true? (:isError result))))

;; ---------------------------------------------------------------------------
;; Registry shape
;; ---------------------------------------------------------------------------

(deftest registry-shape
  (testing "tool-registry is a vector of complete entries"
    (doseq [t tools/tool-registry]
      (is (string? (:name t)) (str "tool name: " (:name t)))
      (is (string? (:description t)))
      (is (map? (:inputSchema t)))
      (is (#{:dev :docs :testing :write} (:category t)))
      (is (fn? (:handler t)))))
  (testing "tool-descriptors strips category + handler (MCP wire shape)"
    (let [ds (tools/tool-descriptors)]
      (is (every? #(every? % [:name :description :inputSchema]) ds))
      (is (every? #(not (contains? % :handler)) ds))
      (is (every? #(not (contains? % :category)) ds)))))

(deftest registry-covers-impl-spec-7-2
  (testing "every tool from IMPL-SPEC §7.2 + §7.3 is present"
    (let [names (set (map :name tools/tool-registry))]
      ;; Dev
      (is (contains? names "get-story-instructions"))
      (is (contains? names "preview-variant"))
      (is (contains? names "list-substrates"))
      ;; Docs
      (is (contains? names "list-stories"))
      (is (contains? names "get-story"))
      (is (contains? names "get-variant"))
      (is (contains? names "list-tags"))
      (is (contains? names "list-modes"))
      (is (contains? names "list-assertions"))
      (is (contains? names "variant->edn"))
      ;; Testing
      (is (contains? names "run-variant"))
      (is (contains? names "snapshot-identity"))
      (is (contains? names "run-a11y"))
      (is (contains? names "read-failures"))
      ;; Write
      (is (contains? names "register-variant"))
      (is (contains? names "unregister-variant"))
      (is (contains? names "record-as-variant")))))

;; ---------------------------------------------------------------------------
;; Dev tools
;; ---------------------------------------------------------------------------

(deftest get-story-instructions-returns-text
  (let [r (invoke "get-story-instructions" {})]
    (is (success? r))
    (let [text (-> r :content first :text)]
      (is (string? text))
      (is (re-find #"reg-story" text))
      (is (re-find #":rf.assert" text))
      (is (re-find #"snapshot-identity" text)))))

(deftest preview-variant-happy
  (let [r (invoke "preview-variant" {:variant-id "story.button/primary"
                                     :base-url "http://localhost:8000/"})
        s (:structuredContent r)]
    (is (success? r))
    (is (= :story.button/primary (:variant-id s)))
    (is (string? (:share-url s)))
    (is (re-find #"story\.button(/|%2F)primary" (:share-url s)))
    (is (some? (:lifecycle s)))))

(deftest preview-variant-not-found
  (let [r (invoke "preview-variant" {:variant-id "story.nope/missing"})]
    (is (error? r))
    (is (re-find #"not found" (-> r :content first :text)))))

(deftest preview-variant-missing-arg
  (let [r (invoke "preview-variant" {})]
    (is (error? r))
    (is (re-find #"variant-id" (-> r :content first :text)))))

(deftest list-substrates-returns-vector
  (let [r (invoke "list-substrates" {})]
    (is (success? r))
    (is (vector? (-> r :structuredContent :substrates)))))

;; ---------------------------------------------------------------------------
;; Docs tools
;; ---------------------------------------------------------------------------

(deftest list-stories-no-filter
  (let [r (invoke "list-stories" {})
        ss (-> r :structuredContent :stories)]
    (is (success? r))
    (is (= 1 (count ss)))
    (is (= :story.button (-> ss first :id)))
    (is (= 2 (count (-> ss first :variants))))))

(deftest list-stories-tag-filter
  (testing "filtering by :docs returns the button story"
    (let [r (invoke "list-stories" {:tags ["docs"]})]
      (is (success? r))
      (is (= [:story.button]
             (mapv :id (-> r :structuredContent :stories))))))
  (testing "filtering by :test (no matches) returns empty"
    (let [r (invoke "list-stories" {:tags ["test"]})]
      (is (success? r))
      (is (empty? (-> r :structuredContent :stories))))))

(deftest get-story-happy
  (let [r (invoke "get-story" {:story-id "story.button"})]
    (is (success? r))
    (is (= :story.button (-> r :structuredContent :id)))
    (is (= "A clickable button." (-> r :structuredContent :body :doc)))))

(deftest get-story-not-found
  (let [r (invoke "get-story" {:story-id "story.nope"})]
    (is (error? r))))

(deftest get-variant-happy
  (let [r (invoke "get-variant" {:variant-id "story.button/primary"})]
    (is (success? r))
    (is (= :story.button/primary (-> r :structuredContent :id)))
    (is (= "Primary button." (-> r :structuredContent :body :doc)))))

(deftest list-tags-includes-canonical
  (let [r (invoke "list-tags" {})
        s (:structuredContent r)]
    (is (success? r))
    (is (every? (set (:canonical s))
                [:dev :docs :test :screenshot :experimental :internal :agent]))))

(deftest list-modes-returns-fixture-mode
  (let [r (invoke "list-modes" {})
        ms (-> r :structuredContent :modes)]
    (is (success? r))
    (is (= 1 (count ms)))
    (is (= :Mode.theme/dark (-> ms first :id)))
    (is (= {:theme :dark} (-> ms first :args)))))

(deftest list-assertions-returns-canonical-seven
  (let [r (invoke "list-assertions" {})
        s (:structuredContent r)]
    (is (success? r))
    (is (= 7 (count (:canonical s))))
    (is (some #(= :rf.assert/path-equals (:id %)) (:canonical s)))
    (is (some #(= :rf.assert/no-warnings (:id %)) (:canonical s)))))

(deftest variant-edn-roundtrips
  (testing "variant->edn returns readable EDN text"
    (let [r (invoke "variant->edn" {:variant-id "story.button/primary"})]
      (is (success? r))
      (let [text (-> r :content first :text)
            back (clojure.edn/read-string text)]
        (is (map? back))
        (is (= "Primary button." (:doc back)))))))

;; ---------------------------------------------------------------------------
;; Testing tools
;; ---------------------------------------------------------------------------

(deftest run-variant-happy
  (let [r (invoke "run-variant" {:variant-id "story.button/primary"})
        s (:structuredContent r)]
    (is (success? r))
    (is (= :story.button/primary (:frame s)))
    (is (true? (:passing? s)) "no assertions ⇒ vacuously passing")
    (is (vector? (:assertions s)))))

(deftest run-variant-unknown
  (let [r (invoke "run-variant" {:variant-id "story.nope/missing"})]
    (is (error? r))
    (is (re-find #"not found" (-> r :content first :text)))))

(deftest snapshot-identity-stable
  (testing "the same args produce the same content-hash"
    (let [r1 (invoke "snapshot-identity" {:variant-id "story.button/primary"})
          r2 (invoke "snapshot-identity" {:variant-id "story.button/primary"})]
      (is (success? r1))
      (is (success? r2))
      (is (= (-> r1 :structuredContent :content-hash)
             (-> r2 :structuredContent :content-hash))))))

(deftest snapshot-identity-unknown
  (let [r (invoke "snapshot-identity" {:variant-id "story.nope/missing"})]
    (is (error? r))))

(deftest run-a11y-jvm-returns-note
  (testing "JVM-standalone deploy returns empty violations with the documented hint"
    (let [r (invoke "run-a11y" {:variant-id "story.button/primary"})
          s (:structuredContent r)]
      (is (success? r))
      (is (vector? (:violations s)))
      (is (string? (:note s)))
      (is (re-find #"CLJS-only" (:note s))))))

(deftest read-failures-empty-after-no-run
  (testing "no run yet ⇒ zero accumulated assertions"
    (let [r (invoke "read-failures" {:variant-id "story.button/primary"})
          s (:structuredContent r)]
      (is (success? r))
      (is (= 0 (:total s)))
      (is (empty? (:failures s)))
      (is (true? (:passing? s))))))

;; ---------------------------------------------------------------------------
;; Write surface (gating)
;; ---------------------------------------------------------------------------

(deftest register-variant-gated-by-default
  (testing "default config rejects register-variant"
    (is (false? (config/writes-allowed?)))
    (let [r (invoke "register-variant" {:variant-id "story.button/danger"
                                        :body {:doc "Danger button."
                                               :args {:label "Delete"}}})]
      (is (error? r))
      (is (re-find #"Write surface disabled" (-> r :content first :text)))
      (is (true? (-> r :structuredContent :gated))))))

(deftest register-variant-happy-when-allowed
  (testing "with allow-writes? true, registration goes through"
    (config/set-allow-writes! true)
    (let [r (invoke "register-variant" {:variant-id "story.button/danger"
                                        :body {:doc "Danger button."
                                               :args {:label "Delete"}}})]
      (is (success? r))
      (is (= :story.button/danger (-> r :structuredContent :variant-id)))
      (is (true? (-> r :structuredContent :registered?)))
      ;; Variant is now reachable via the read surface.
      (is (some? (story/variant->edn :story.button/danger))))))

(deftest register-variant-edn-string-body
  (testing "body may arrive as an EDN-encoded string"
    (config/set-allow-writes! true)
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/wire"
                     :body "{:doc \"Wire body.\" :args {:label \"OK\"}}"})]
      (is (success? r))
      (is (= "Wire body." (:doc (story/variant->edn :story.button/wire)))))))

(deftest register-variant-rejects-bad-shape
  (testing "registration with an invalid body returns a tool-execution error"
    (config/set-allow-writes! true)
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/bad"
                     :body {:tags #{:nonexistent-tag}}})]
      (is (error? r))
      (is (re-find #"(?i)Registration failed" (-> r :content first :text))))))

(deftest unregister-variant-gated-by-default
  (let [r (invoke "unregister-variant" {:variant-id "story.button/primary"})]
    (is (error? r))
    (is (re-find #"Write surface disabled" (-> r :content first :text)))))

(deftest unregister-variant-happy-when-allowed
  (config/set-allow-writes! true)
  (let [r (invoke "unregister-variant" {:variant-id "story.button/primary"})]
    (is (success? r))
    (is (true? (-> r :structuredContent :unregistered?)))
    (is (nil? (story/variant->edn :story.button/primary)))))

;; ---------------------------------------------------------------------------
;; record-as-variant (rf2-luhdu)
;;
;; The recorder normally captures events off the trace bus; for these tests
;; we drive `recorder/record-event!` directly during the tool's blocking
;; window via a worker thread so the assertions exercise the start →
;; capture → snippet plumbing without needing a live trace emitter.
;; ---------------------------------------------------------------------------

(defn- drive-events-during-recording
  "Helper: spawn a worker thread that, after a short delay (to ensure the
  tool has called `start-recording!`), pushes `events` into the recorder
  one at a time. The tool's `:duration-ms` must outlast the delay."
  [events ^long delay-ms]
  (doto (Thread.
          ^Runnable
          (fn []
            (Thread/sleep delay-ms)
            (doseq [ev events]
              (recorder/record-event! ev))))
    (.setDaemon true)
    (.start)))

(deftest record-as-variant-not-found
  (testing "unknown source variant ⇒ tool-execution error"
    (let [r (invoke "record-as-variant" {:variant-id "story.nope/missing"})]
      (is (error? r))
      (is (re-find #"not found" (-> r :content first :text))))))

(deftest record-as-variant-missing-arg
  (testing "missing :variant-id ⇒ tool-execution error"
    (let [r (invoke "record-as-variant" {})]
      (is (error? r))
      (is (re-find #"variant-id" (-> r :content first :text))))))

(deftest record-as-variant-zero-duration-empty-capture
  (testing "duration 0 with no in-flight dispatches ⇒ empty :play snippet"
    (let [r (invoke "record-as-variant" {:variant-id "story.button/primary"})
          s (:structuredContent r)]
      (is (success? r))
      (is (= :story.button/primary (:variant-id s)))
      (is (= 0 (:recorded-event-count s)))
      (is (false? (:written-back? s)))
      (is (string? (:play-snippet s)))
      (is (re-find #":play \[\]" (:play-snippet s)))
      (is (re-find #":story\.button/primary" (:play-snippet s))))))

(deftest record-as-variant-captures-events-during-window
  (testing "events pushed during the blocking window land in :captured"
    (drive-events-during-recording [[:counter/inc] [:counter/by 7]] 20)
    (let [r (invoke "record-as-variant"
                    {:variant-id  "story.button/primary"
                     :duration-ms 100})
          s (:structuredContent r)]
      (is (success? r))
      (is (= 2 (:recorded-event-count s)))
      (is (= [[:counter/inc] [:counter/by 7]] (:captured s)))
      (is (re-find #":counter/inc" (:play-snippet s)))
      (is (re-find #":counter/by 7" (:play-snippet s))))))

(deftest record-as-variant-write-back-gated-by-default
  (testing "write-back? true with allow-writes? false ⇒ gated error"
    (is (false? (config/writes-allowed?)))
    (let [r (invoke "record-as-variant" {:variant-id  "story.button/primary"
                                         :write-back? true})]
      (is (error? r))
      (is (re-find #"Write surface disabled" (-> r :content first :text)))
      (is (true? (-> r :structuredContent :gated))))))

(deftest record-as-variant-write-back-overwrites-source
  (testing "write-back? true with gate open re-registers the source variant"
    (config/set-allow-writes! true)
    (drive-events-during-recording [[:counter/inc] [:counter/inc]] 20)
    (let [r (invoke "record-as-variant"
                    {:variant-id  "story.button/primary"
                     :duration-ms 100
                     :write-back? true})
          s (:structuredContent r)]
      (is (success? r))
      (is (true? (:written-back? s)))
      (is (= :story.button/primary (:new-variant-id s)))
      ;; Source variant's :play slot now carries the captured events.
      (is (= [[:counter/inc] [:counter/inc]]
             (:play (story/variant->edn :story.button/primary))))
      ;; Pre-existing body keys survive (e.g. :doc).
      (is (= "Primary button." (:doc (story/variant->edn :story.button/primary)))))))

(deftest record-as-variant-write-back-new-id
  (testing ":new-variant-id lands the capture under a fresh id"
    (config/set-allow-writes! true)
    (drive-events-during-recording [[:counter/inc]] 20)
    (let [r (invoke "record-as-variant"
                    {:variant-id     "story.button/primary"
                     :new-variant-id "story.button/recorded"
                     :duration-ms    100
                     :write-back?    true})
          s (:structuredContent r)]
      (is (success? r))
      (is (true? (:written-back? s)))
      (is (= :story.button/recorded (:new-variant-id s)))
      (is (= [[:counter/inc]] (:play (story/variant->edn :story.button/recorded))))
      ;; Source variant is untouched.
      (is (nil? (:play (story/variant->edn :story.button/primary)))))))

(deftest record-as-variant-snippet-honours-doc-and-alias
  (testing ":doc and :alias flow into the rendered snippet"
    (let [r (invoke "record-as-variant"
                    {:variant-id "story.button/primary"
                     :doc        "Recorded counter run."
                     :alias      "s"})
          snippet (-> r :structuredContent :play-snippet)]
      (is (success? r))
      (is (re-find #"\(s/reg-variant" snippet))
      (is (re-find #"Recorded counter run\." snippet))
      ;; Default :extends = source variant id.
      (is (re-find #":extends :story\.button/primary" snippet)))))

;; ---------------------------------------------------------------------------
;; Server dispatcher (initialize, tools/list, tools/call, error paths)
;; ---------------------------------------------------------------------------

(deftest dispatch-initialize-handshake
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 1 :method "initialize"
                :params {:protocolVersion "2025-06-18"
                         :capabilities {}
                         :clientInfo {:name "test-client" :version "0.0.0"}}})]
    (is (= 1 (:id resp)))
    (is (= config/protocol-version (-> resp :result :protocolVersion)))
    (is (= config/server-name (-> resp :result :serverInfo :name)))
    (is (map? (-> resp :result :capabilities)))))

(deftest dispatch-tools-list-returns-registry
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 2 :method "tools/list"})
        ts (-> resp :result :tools)]
    (is (= 2 (:id resp)))
    (is (vector? ts))
    (is (= (count tools/tool-registry) (count ts)))
    (is (some #(= "list-stories" (:name %)) ts))))

(deftest dispatch-tools-call-happy
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 3 :method "tools/call"
                :params {:name "get-story"
                         :arguments {:story-id "story.button"}}})]
    (is (= 3 (:id resp)))
    (is (some? (:result resp)))
    (is (not (true? (-> resp :result :isError))))))

(deftest dispatch-tools-call-unknown-tool
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 4 :method "tools/call"
                :params {:name "unknown-tool" :arguments {}}})]
    (is (= proto/code-method-not-found (-> resp :error :code))
        "an unknown tool yields a protocol-level method-not-found")))

(deftest dispatch-malformed-envelope
  (testing "missing jsonrpc version yields invalid-request"
    (let [resp (server/dispatch {:method "tools/list" :id 5})]
      (is (= proto/code-invalid-request (-> resp :error :code))))))

(deftest dispatch-unknown-method
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 6 :method "nope/whatever"})]
    (is (= proto/code-method-not-found (-> resp :error :code)))
    (is (re-find #"nope/whatever" (-> resp :error :message)))))

(deftest dispatch-notification-no-response
  (testing "a JSON-RPC notification yields nil (no response)"
    (is (nil? (server/dispatch
                {:jsonrpc "2.0" :method "notifications/initialized"})))))

(deftest dispatch-ping-empty-result
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 7 :method "ping"})]
    (is (= {} (:result resp)))))

;; ---------------------------------------------------------------------------
;; Run-loop end-to-end (in-memory)
;; ---------------------------------------------------------------------------

(deftest run-loop-handles-multi-frame-session
  (testing "handshake + tools/list + tools/call over a pipe of frames"
    (let [in-text (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n"
                       "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list-tags\",\"arguments\":{}}}\n")
          reader (java.io.BufferedReader. (java.io.StringReader. in-text))
          sw     (java.io.StringWriter.)]
      (server/run-loop! reader sw)
      ;; Split written output into frames, parse each.
      (let [out-lines (filter seq (clojure.string/split-lines (.toString sw)))
            frames    (mapv #(cheshire.core/parse-string % true) out-lines)]
        ;; Three responses (initialize, tools/list, tools/call) — the
        ;; `notifications/initialized` notification yielded no response.
        (is (= 3 (count frames)))
        (is (= 1 (:id (nth frames 0))))
        (is (= 2 (:id (nth frames 1))))
        (is (= 3 (:id (nth frames 2))))
        (is (= config/protocol-version
               (-> (nth frames 0) :result :protocolVersion)))
        (is (vector? (-> (nth frames 1) :result :tools)))
        (is (-> (nth frames 2) :result :content vector?))))))

(deftest run-loop-survives-parse-error
  (testing "a malformed frame produces a parse-error response; loop continues"
    (let [in-text (str "{this is garbage\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}\n")
          reader (java.io.BufferedReader. (java.io.StringReader. in-text))
          sw     (java.io.StringWriter.)]
      (server/run-loop! reader sw)
      (let [out-lines (filter seq (clojure.string/split-lines (.toString sw)))
            frames    (mapv #(cheshire.core/parse-string % true) out-lines)]
        (is (= 2 (count frames)))
        (is (= proto/code-parse-error (-> (nth frames 0) :error :code)))
        (is (= 9 (:id (nth frames 1))))))))

;; ---------------------------------------------------------------------------
;; Boot config
;; ---------------------------------------------------------------------------

(deftest boot-config-defaults-locked-down
  (testing "boot config defaults allow-writes? to false"
    (let [cfg (#'server/parse-args [])]
      (is (nil? (:allow-writes? cfg))))))

(deftest boot-config-allow-writes-flag
  (testing "--allow-writes flips the gate"
    (let [cfg (#'server/parse-args ["--allow-writes"])]
      (is (true? (:allow-writes? cfg))))))
