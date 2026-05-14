(ns re-frame.story-mcp.tools-test
  "Per-tool semantics + the server dispatcher's `initialize` / `tools/list`
  / `tools/call` plumbing.

  Tests boot Story's canonical vocabulary in a per-test fixture so the
  registrar carries the seven canonical tags + the lifecycle machine,
  then register a small fixture story + variant so each tool has
  something to read."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.mcp-base.overflow :as overflow]
            [re-frame.mcp-base.vocab :as vocab]
            [re-frame.story :as story]
            [re-frame.story.recorder :as recorder]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.protocol :as proto]
            [re-frame.story-mcp.server :as server]
            [re-frame.story-mcp.tools.cap :as cap]
            [re-frame.story-mcp.tools.registry :as registry]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-story-and-config
  "Each test gets a fresh Story registry + write-gate set to false (the
  documented default per IMPL-SPEC §7.3). Tests that need writes flip
  the gate explicitly.

  Also pins re-frame's substrate to `plain-atom` so tests that exercise
  the full run-variant → assertion-record-into-frame-db → read-failures
  pipeline land assertions where `read-failures` can find them (the
  pipeline requires an initialised substrate adapter; without it
  `dispatch-sync` no-ops and `:rf.story/assertions` never accretes)."
  [t]
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
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
  (cap/invoke-tool tool-name args))

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
    (doseq [t registry/tool-registry]
      (is (string? (:name t)) (str "tool name: " (:name t)))
      (is (string? (:description t)))
      (is (map? (:inputSchema t)))
      (is (#{:dev :docs :testing :write} (:category t)))
      (is (fn? (:handler t)))))
  (testing "tool-descriptors strips category + handler (MCP wire shape)"
    (let [ds (registry/tool-descriptors)]
      (is (every? #(every? % [:name :description :inputSchema]) ds))
      (is (every? #(not (contains? % :handler)) ds))
      (is (every? #(not (contains? % :category)) ds)))))

(deftest typical-tokens-hint-on-every-tool
  ;; rf2-6sddv — `:typicalTokens` is an informational ballpark of
  ;; response-payload size in tokens; AI clients use it to budget calls.
  ;; Not a cap. Required to be a positive integer on every tool.
  (testing "registry: every tool carries a positive-integer :typicalTokens"
    (doseq [t registry/tool-registry]
      (is (integer? (:typicalTokens t))
          (str "missing :typicalTokens on " (:name t)))
      (is (pos? (:typicalTokens t))
          (str "non-positive :typicalTokens on " (:name t)))))
  (testing "tool-descriptors surfaces :typicalTokens to the wire"
    (let [ds (registry/tool-descriptors)]
      (is (every? #(integer? (:typicalTokens %)) ds))
      (is (every? #(pos? (:typicalTokens %)) ds)))))

(deftest registry-covers-impl-spec-7-2
  (testing "every tool from IMPL-SPEC §7.2 + §7.3 is present"
    (let [names (set (map :name registry/tool-registry))]
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
;; Self-healing loop — failing :rf.assert/* through run-variant → read-failures
;;
;; Per rf2-6r441: existing tests cover the optimistic (vacuous-pass) flow only.
;; This deftest drives a DELIBERATELY-FAILING `:rf.assert/path-equals` through
;; the MCP tool surface and asserts the AI-visible failure shape — the wire-
;; side contract an agent would consume.
;;
;; The agent self-healing loop has four steps:
;;   1. register-variant with a `:play` body whose assertion will fail
;;   2. run-variant — :passing? false; :assertions carries the failed record
;;   3. read-failures — non-empty :failures vector with structured data
;;   4. (agent proposes a fix — out of scope for this contract test)
;;
;; The failure record's shape (per tools/story/spec/004-Assertions.md +
;; tools/story/src/re_frame/story/assertions.cljc `assertion-record`):
;;
;;     {:assertion :rf.assert/path-equals
;;      :payload   [[:auth :status] :authenticated]
;;      :passed?   false
;;      :expected  :authenticated
;;      :actual    nil
;;      :path      [:auth :status]
;;      :reason    "expected :authenticated at [:auth :status] but got nil"
;;      :elapsed-ms <int>}
;;
;; The MCP wire serialises this as-is on `:structuredContent` (per
;; `tools/testing.cljc` `tool-read-failures` + `tool-run-variant`) —
;; Story keys survive the JSON-RPC round-trip into the agent's view.
;; ---------------------------------------------------------------------------

(deftest self-healing-loop-failing-assertion-shape
  (testing "register → run → read-failures surfaces the :rf.assert/path-equals failure shape"
    (config/set-allow-writes! true)
    ;; Step 1 — agent registers a variant whose :play body asserts a slot
    ;; that no `:events` step populated. The assertion will fail because
    ;; `(get-in @app-db [:auth :status])` is nil, not :authenticated.
    (let [reg (invoke "register-variant"
                      {:variant-id "story.auth/sad"
                       :body (str "{:doc \"Deliberately-failing assertion.\""
                                  " :events []"
                                  " :play   [[:rf.assert/path-equals [:auth :status] :authenticated]]}")})]
      (is (success? reg) "fixture registration succeeds")
      (is (true? (-> reg :structuredContent :registered?))))

    ;; Step 2 — run-variant. The wire result carries :passing? false and a
    ;; non-empty :assertions vector. The failed record carries the
    ;; assertion-id, payload, and expected/actual slots — enough for the
    ;; agent to localise the failure without re-fetching anything.
    (let [run (invoke "run-variant" {:variant-id "story.auth/sad"})
          s   (:structuredContent run)
          a   (first (:assertions s))]
      (is (success? run))
      (is (false? (:passing? s))
          "a failed assertion flips :passing? — the wire-side green-light bit")
      (is (= 1 (count (:assertions s))) "one assertion fired, one record")
      (is (= :rf.assert/path-equals (:assertion a))
          "the failed record names the canonical assertion id")
      (is (false? (:passed? a)) "the record explicitly carries :passed? false")
      (is (= :authenticated (:expected a)))
      (is (nil? (:actual a)))
      (is (= [:auth :status] (:path a))
          "the path slot localises the assertion to a single app-db site")
      (is (string? (:reason a))
          "the :reason slot is the human-readable explanation the AI surfaces back to the LLM")
      (is (re-find #":authenticated" (:reason a))
          "the reason text names the expected value"))

    ;; Step 3 — read-failures (the dedicated agent-facing read of accumulated
    ;; failures without re-running). The shape per `tool-read-failures`:
    ;;   {:variant-id <kw> :total <int> :failures <vec> :passing? <bool>}
    (let [rf (invoke "read-failures" {:variant-id "story.auth/sad"})
          s  (:structuredContent rf)
          f  (first (:failures s))]
      (is (success? rf))
      (is (= :story.auth/sad (:variant-id s))
          ":variant-id round-trips so the agent can correlate the read with its source variant")
      (is (= 1 (:total s)) ":total counts every assertion (passed + failed)")
      (is (= 1 (count (:failures s)))
          ":failures filters to those with :passed? false")
      (is (false? (:passing? s))
          ":passing? is the same bit `run-variant` returned — consistent across the read surface")
      ;; The failure record's keys match the run-variant projection — the
      ;; agent sees the same record shape regardless of which tool read it.
      (is (= :rf.assert/path-equals (:assertion f)))
      (is (false? (:passed? f)))
      (is (= :authenticated (:expected f)))
      (is (nil? (:actual f)))
      (is (= [:auth :status] (:path f))))

    ;; Step 4 (out of scope) — an agent would now propose a `:events` slot
    ;; like `[[:test/set-status]]` and re-register, then re-run. The "fix
    ;; passes" half is exercised in tools/story's `path-equals-pass` test.

    ;; Tear-down — keep the read surface clean for any downstream test.
    (config/set-allow-writes! true)
    (invoke "unregister-variant" {:variant-id "story.auth/sad"})))

(deftest self-healing-loop-survives-record-dont-throw
  (testing "play-runner records every failure and continues; read-failures returns all of them"
    ;; Per tools/story/spec/004-Assertions.md the play sequence does NOT
    ;; halt on a failed assertion — failures record into the accumulator and
    ;; the sequence runs to completion. The agent's view of `read-failures`
    ;; therefore reflects EVERY failure observed, not just the first.
    (config/set-allow-writes! true)
    (let [reg (invoke "register-variant"
                      {:variant-id "story.auth/double-fail"
                       :body (str "{:doc \"Two failing assertions; both must record.\""
                                  " :events []"
                                  " :play   [[:rf.assert/path-equals [:auth :status] :authenticated]"
                                  "          [:rf.assert/path-equals [:user :role] :admin]]}")})]
      (is (success? reg)))

    (let [run (invoke "run-variant" {:variant-id "story.auth/double-fail"})
          s   (:structuredContent run)]
      (is (success? run))
      (is (false? (:passing? s)))
      (is (= 2 (count (:assertions s)))
          "BOTH assertions recorded — the play sequence ran to completion despite the first fail"))

    (let [rf (invoke "read-failures" {:variant-id "story.auth/double-fail"})
          s  (:structuredContent rf)]
      (is (success? rf))
      (is (= 2 (:total s)))
      (is (= 2 (count (:failures s))))
      (is (= [:auth :status] (-> s :failures first :path))
          "failures preserve registration order")
      (is (= [:user :role] (-> s :failures second :path))))

    (invoke "unregister-variant" {:variant-id "story.auth/double-fail"})))

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

(deftest gated-error-tool-slot-pins-caller
  ;; Regression for rf2-c52j0. Pre-fix, `assert-writes-allowed` hardcoded
  ;; `:tool "register-variant"` in its error payload, so the two other
  ;; callers (`unregister-variant`, `record-as-variant`) returned a gated
  ;; error whose `:structuredContent :tool` slot LIED about its origin.
  ;; This test pins the slot to the actual tool name at each callsite so
  ;; the lie cannot regress.
  (testing "gated error's :structuredContent :tool matches the invoking tool"
    (is (false? (config/writes-allowed?))
        "fixture must leave the gate closed for this test")
    (let [r (invoke "register-variant" {:variant-id "story.button/danger"
                                        :body {:doc "x"}})]
      (is (error? r))
      (is (true? (-> r :structuredContent :gated)))
      (is (= "register-variant" (-> r :structuredContent :tool))))
    (let [r (invoke "unregister-variant" {:variant-id "story.button/primary"})]
      (is (error? r))
      (is (true? (-> r :structuredContent :gated)))
      (is (= "unregister-variant" (-> r :structuredContent :tool))))
    (let [r (invoke "record-as-variant" {:variant-id  "story.button/primary"
                                         :write-back? true})]
      (is (error? r))
      (is (true? (-> r :structuredContent :gated)))
      (is (= "record-as-variant" (-> r :structuredContent :tool))))))

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
;; :origin :story-mcp stamping (rf2-7dnct)
;;
;; Per spec/Cross-Cutting-Designs.md §5 — every write surface tags its
;; writes with a single `:origin` keyword so post-mortem queries can
;; answer "who wrote this?". Story-mcp's `register-variant` and
;; `record-as-variant` (write-back path) stamp `:origin :story-mcp` onto
;; the registered variant body. The keyword value is pinned in
;; `config/origin`; the registrar's open-shape variant schema admits
;; the extra slot.
;; ---------------------------------------------------------------------------

(deftest origin-const-is-story-mcp
  (testing "the origin keyword is `:story-mcp` per Cross-Cutting-Designs §5"
    (is (= :story-mcp config/origin))))

(deftest register-variant-stamps-origin-story-mcp
  (testing "register-variant writes a body carrying :origin :story-mcp"
    (config/set-allow-writes! true)
    (let [r    (invoke "register-variant"
                       {:variant-id "story.button/origin-map"
                        :body       {:doc  "Origin-stamped via map body."
                                     :args {:label "Stamped"}}})
          body (story/variant->edn :story.button/origin-map)]
      (is (success? r))
      (is (= :story-mcp (:origin body))
          "registered body must carry :origin :story-mcp")
      ;; Caller-supplied keys survive alongside the stamp.
      (is (= "Origin-stamped via map body." (:doc body)))
      (is (= {:label "Stamped"} (:args body))))))

(deftest register-variant-edn-string-body-stamps-origin
  (testing "EDN-string body also lands :origin :story-mcp on the registered body"
    (config/set-allow-writes! true)
    (let [r    (invoke "register-variant"
                       {:variant-id "story.button/origin-edn"
                        :body       "{:doc \"Origin via EDN.\" :args {:label \"OK\"}}"})
          body (story/variant->edn :story.button/origin-edn)]
      (is (success? r))
      (is (= :story-mcp (:origin body))))))

(deftest register-variant-overrides-caller-supplied-origin
  (testing "story-mcp owns the :origin slot — caller-supplied values are clobbered"
    (config/set-allow-writes! true)
    (let [r    (invoke "register-variant"
                       {:variant-id "story.button/origin-override"
                        :body       {:doc    "Caller tried to claim :app origin."
                                     :origin :app}})
          body (story/variant->edn :story.button/origin-override)]
      (is (success? r))
      (is (= :story-mcp (:origin body))
          "the write surface owns the :origin slot; an agent cannot claim a different origin"))))

(deftest record-as-variant-write-back-stamps-origin
  (testing "record-as-variant write-back lands :origin :story-mcp on the new body"
    (config/set-allow-writes! true)
    (drive-events-during-recording [[:counter/inc]] 20)
    (let [r    (invoke "record-as-variant"
                       {:variant-id  "story.button/primary"
                        :duration-ms 100
                        :write-back? true})
          body (story/variant->edn :story.button/primary)]
      (is (success? r))
      (is (true? (-> r :structuredContent :written-back?)))
      (is (= :story-mcp (:origin body))
          "write-back body must carry :origin :story-mcp")
      ;; Pre-existing body keys + the captured :play slot still land.
      (is (= "Primary button." (:doc body)))
      (is (= [[:counter/inc]] (:play body))))))

(deftest record-as-variant-write-back-new-id-stamps-origin
  (testing ":new-variant-id write-back also carries :origin :story-mcp"
    (config/set-allow-writes! true)
    (drive-events-during-recording [[:counter/inc]] 20)
    (let [r    (invoke "record-as-variant"
                       {:variant-id     "story.button/primary"
                        :new-variant-id "story.button/origin-recorded"
                        :duration-ms    100
                        :write-back?    true})
          body (story/variant->edn :story.button/origin-recorded)]
      (is (success? r))
      (is (= :story-mcp (:origin body))))))

(deftest record-as-variant-without-write-back-does-not-touch-source
  (testing "without :write-back? the source variant is untouched (no :origin landed)"
    ;; This pins the contract: the write happens only on the write-back
    ;; branch. The :origin stamp is the marker of a write — its absence
    ;; on a non-write-back call is the marker of a no-write.
    (let [_    (invoke "record-as-variant" {:variant-id "story.button/primary"})
          body (story/variant->edn :story.button/primary)]
      (is (nil? (:origin body))
          "no write happened, so no :origin stamp lands on the source body"))))

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
    (is (= (count registry/tool-registry) (count ts)))
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

;; ---------------------------------------------------------------------------
;; Wire-boundary token-budget cap (rf2-rvyzy / rf2-zavp5).
;;
;; The cap is applied at `invoke-tool` egress — the cumulative
;; `:text`-slot byte count is compared against `:max-tokens` (default
;; `overflow/default-max-tokens`; `0` disables). Over-budget responses
;; are replaced with `{:rf.mcp/overflow {...}}` per the cross-MCP shape
;; pinned in `re-frame.mcp-base.overflow/overflow-payload`.
;; ---------------------------------------------------------------------------

(defn- overflow-marker?
  "Does `result` carry the `{:rf.mcp/overflow {:limit :reached ...}}`
  marker shape? Both the structured-content and the text slot should
  reflect it. The text slot prints via `pr-str` which renders the
  namespaced key as the `#:rf.mcp{:overflow ...}` namespace-map form
  (round-trippable EDN); `read-string`-ing it round-trips to the same
  key. We check the structured shape and that the text slot is the
  round-trippable EDN form."
  [result]
  (and (map? result)
       (= :reached (get-in result [:structuredContent vocab/overflow-key :limit]))
       (string? (-> result :content first :text))
       (let [round-tripped (try (edn/read-string
                                  (-> result :content first :text))
                                (catch Throwable _ nil))]
         (= :reached (get-in round-tripped [vocab/overflow-key :limit])))))

(deftest cap-fires-when-response-exceeds-budget
  (testing "get-story-instructions response is large enough to exceed a 1-token cap"
    (let [r (cap/invoke-tool "get-story-instructions" {:max-tokens 1})]
      (is (overflow-marker? r))
      (let [body (get-in r [:structuredContent vocab/overflow-key])]
        (is (= 1 (:cap-tokens body)))
        (is (= "get-story-instructions" (:tool body)))
        (is (pos? (:token-count body)))
        (is (string? (:hint body)))))))

(deftest cap-zero-disables-the-cap
  (testing "`:max-tokens 0` bypasses the cap; the full payload returns intact"
    (let [r (cap/invoke-tool "get-story-instructions" {:max-tokens 0})]
      (is (not (overflow-marker? r)))
      (is (clojure.string/includes? (-> r :content first :text)
                                    "re-frame2-story authoring conventions"))))
  (testing "default cap (no `:max-tokens` arg) leaves a small response intact"
    (let [r (cap/invoke-tool "list-tags" {})]
      (is (not (overflow-marker? r))))))

(deftest cap-honours-default-when-omitted
  (testing "absent `:max-tokens` falls back to `overflow/default-max-tokens` (5000)"
    ;; A tiny payload like `list-tags` is well under 5K tokens; verify
    ;; the cap does not trip on routine reads.
    (let [r (cap/invoke-tool "list-tags" {})
          tokens (#'cap/sum-text-tokens r)]
      (is (not (overflow-marker? r)))
      (is (< tokens overflow/default-max-tokens)))))

(deftest cap-marker-shape-is-mcp-base-overflow
  (testing "marker is byte-identical to mcp-base/overflow-payload's shape"
    (let [r (cap/invoke-tool "get-story-instructions" {:max-tokens 1})
          body (get-in r [:structuredContent vocab/overflow-key])]
      (is (= #{:limit :token-count :cap-tokens :tool :hint}
             (set (keys body)))))))

(deftest every-tool-schema-accepts-max-tokens
  (testing "every tool's input schema carries the `:max-tokens` slot"
    (doseq [t registry/tool-registry]
      (is (contains? (-> t :inputSchema :properties) :max-tokens)
          (str "tool " (:name t) " missing :max-tokens slot"))
      (is (= "integer" (-> t :inputSchema :properties :max-tokens :type))
          (str "tool " (:name t) " :max-tokens slot is not integer-typed")))))

;; ---------------------------------------------------------------------------
;; Wire-egress privacy posture (rf2-73wuj)
;;
;; Per spec/Tool-Pair.md §Direct-read privacy posture (lines 544-566) every
;; pair-shaped tool that surfaces a live `:app-db` slice MUST route the
;; value through `re-frame.core/elide-wire-value` before egress, with
;; off-box defaults (`:rf.size/include-sensitive?` and
;; `:rf.size/include-large?` both default false). The cross-MCP
;; `:include-sensitive?` arg (rf2-vw4sq) is the documented escape hatch.
;;
;; These tests pin the contract at the story-mcp surface: a sensitive
;; slot declared via the `[:rf/elision :declarations]` registry on the
;; variant's frame must surface as `:rf/redacted` in the tool's response
;; `:app-db` slot by default, and as the raw value when the caller opts
;; in via `:include-sensitive? true`. Assertion records carrying the
;; top-level `:sensitive? true` stamp must be dropped by default and
;; included when opted in.
;;
;; Pattern mirrors `implementation/core/test/re_frame/elision_test.clj`
;; — the registry slot is populated via direct container mutation
;; (`re-frame.substrate.adapter/replace-container!`) so the test does
;; not depend on the rf2-isdwf cofx-side stamping work, which is
;; orthogonal to the wire-egress contract under test here.
;; ---------------------------------------------------------------------------

(defn- frame-container [variant-id]
  ;; `re-frame.frame/get-frame-db` returns the substrate container (an
  ;; atom under plain-atom); the user-facing `rf/get-frame-db` returns
  ;; the dereferenced VALUE. Tests need the container so they can write
  ;; the elision-registry slot back.
  ((requiring-resolve 're-frame.frame/get-frame-db) variant-id))

(defn- read-frame-db [variant-id]
  ((requiring-resolve 're-frame.substrate.adapter/read-container)
   (frame-container variant-id)))

(defn- replace-frame-db! [variant-id new-db]
  ((requiring-resolve 're-frame.substrate.adapter/replace-container!)
   (frame-container variant-id)
   new-db))

(defn- ensure-variant-frame!
  "Allocate `variant-id`'s frame if it doesn't already exist. The fixture
  only `reg-variant`s the variant body; the variant's *frame* is
  allocated lazily by `run-variant` / `preview-variant`. The privacy
  tests need the frame up-front so they can write into its app-db
  before the tool call runs."
  [variant-id]
  (when (nil? (frame-container variant-id))
    (rf/reg-frame variant-id
                  {:doc        (str "test frame for " variant-id)
                   :rf/story?  true
                   :rf/variant variant-id})))

(defn- destroy-variant-frame!
  "Tear down `variant-id`'s frame so the next test starts fresh. The
  `frames` atom is per-process (not cleared by `story/clear-all!`); a
  seeded `:rf.story/assertions` or `[:rf/elision :declarations]` slot
  would otherwise bleed across tests."
  [variant-id]
  (when (some? (frame-container variant-id))
    ((requiring-resolve 're-frame.frame/destroy-frame!) variant-id)))

(defn- declare-sensitive!
  "Write a `{:sensitive? true}` entry into `[:rf/elision :declarations
  <path>]` on the named variant's frame. The walker reads this on the
  next call to `elide-wire-value` and emits `:rf/redacted` at the slot."
  [variant-id path]
  (ensure-variant-frame! variant-id)
  (let [db (or (read-frame-db variant-id) {})]
    (replace-frame-db! variant-id
                       (assoc-in db
                                 [:rf/elision :declarations path]
                                 {:sensitive? true :source :declared}))))

(defn- seed-app-db!
  "Write `db` into `variant-id`'s frame app-db. Helper for the privacy
  tests so we can populate slots without invoking a full `run-variant`."
  [variant-id db]
  (ensure-variant-frame! variant-id)
  (replace-frame-db! variant-id db))

(defmacro ^:private with-clean-frame
  "Bind `vid` to `variant-kw`, run `body` against a clean variant frame,
  and tear the frame down on exit so the next test sees no residue. The
  `frames` atom is per-process and survives `story/clear-all!`; the
  seeded `:rf.story/assertions` and `[:rf/elision :declarations]` slots
  would otherwise leak."
  [[vid variant-kw] & body]
  `(let [~vid ~variant-kw]
     (try ~@body
          (finally (destroy-variant-frame! ~vid)))))

(deftest preview-variant-app-db-redacts-sensitive-by-default
  (testing "sensitive path in variant frame's app-db lands :rf/redacted in the response"
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "preview-variant" {:variant-id "story.button/primary"})
            s (:structuredContent r)]
        (is (success? r))
        (is (= :rf/redacted (get-in s [:app-db :secret]))
            "the :secret slot is redacted by the wire-egress walker")
        (is (= "ok" (get-in s [:app-db :public]))
            "non-sensitive slots survive the walk")))))

(deftest preview-variant-app-db-includes-sensitive-when-opted-in
  (testing ":include-sensitive? true forwards the raw value through the walker"
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "preview-variant" {:variant-id "story.button/primary"
                                         :include-sensitive? true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= "TOPSECRET" (get-in s [:app-db :secret]))
            "opt-in surfaces the raw sensitive value")))))

(deftest run-variant-app-db-redacts-sensitive-by-default
  (testing "run-variant's :app-db slot routes through the wire-egress walker"
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "run-variant" {:variant-id "story.button/primary"})
            s (:structuredContent r)]
        (is (success? r))
        ;; The registry slot at `[:rf/elision :declarations]` survives
        ;; the run (Story doesn't clear it). The redaction must show
        ;; in the response.
        (is (= :rf/redacted (get-in s [:app-db :secret]))
            "the :secret slot is redacted at egress")))))

(deftest run-variant-app-db-includes-sensitive-when-opted-in
  (testing "run-variant's :include-sensitive? true forwards the raw value"
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "run-variant" {:variant-id "story.button/primary"
                                     :include-sensitive? true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= "TOPSECRET" (get-in s [:app-db :secret])))))))

(deftest read-failures-strips-sensitive-assertion-records-by-default
  (testing "an assertion record stamped :sensitive? true is dropped at egress"
    (with-clean-frame [vid :story.button/primary]
      ;; Seed assertion accumulator with one sensitive failure + one
      ;; benign passing record. The default-drop filter (strip-sensitive
      ;; from mcp-base.sensitive) must remove only the sensitive one.
      (seed-app-db! vid
                    {:rf.story/assertions
                     [{:assertion :rf.assert/path-equals
                       :passed?   true
                       :tags      [:public]}
                      {:assertion  :rf.assert/path-equals
                       :passed?    false
                       :sensitive? true
                       :reason     "expected TOPSECRET got something-else"}]})
      (let [r (invoke "read-failures" {:variant-id "story.button/primary"})
            s (:structuredContent r)]
        (is (success? r))
        (is (= 1 (:total s)) "only the non-sensitive record survives")
        (is (empty? (:failures s)) "the sensitive failure is filtered out")
        (is (true? (:passing? s))
            ":passing? runs against the scrubbed vec — agent's view is consistent")))))

(deftest read-failures-includes-sensitive-when-opted-in
  (testing ":include-sensitive? true preserves sensitive records"
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid
                    {:rf.story/assertions
                     [{:assertion :rf.assert/path-equals
                       :passed?   true}
                      {:assertion  :rf.assert/path-equals
                       :passed?    false
                       :sensitive? true
                       :reason     "expected TOPSECRET got something-else"}]})
      (let [r (invoke "read-failures" {:variant-id "story.button/primary"
                                       :include-sensitive? true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= 2 (:total s)) "both records survive the egress")
        (is (= 1 (count (:failures s))) "the failed sensitive record is visible")
        (is (false? (:passing? s)) "the visible failure flips :passing?")))))

(deftest egress-tools-input-schema-carries-include-sensitive
  (testing "every tool surfacing :app-db or assertions accepts :include-sensitive?"
    (doseq [tname ["preview-variant" "run-variant" "read-failures"]]
      (let [t     (some #(when (= tname (:name %)) %) registry/tool-registry)
            props (-> t :inputSchema :properties)]
        (is (contains? props :include-sensitive?)
            (str tname " missing :include-sensitive? slot"))
        (is (= "boolean" (-> props :include-sensitive? :type))
            (str tname " :include-sensitive? slot is not boolean-typed"))))))
